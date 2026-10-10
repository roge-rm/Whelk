package com.rm.whelk.core

import com.hierynomus.sshj.key.KeyAlgorithms
import net.schmizz.sshj.Config
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.signature.SignatureRSA
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.transport.compression.DelayedZlibCompression
import net.schmizz.sshj.transport.compression.NoneCompression
import net.schmizz.sshj.transport.compression.ZlibCompression
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.PublicKey
import java.util.Base64

/** Where to log in and how, worked out from a connection and its identity. */
data class SshTarget(
    val host: String,
    val port: Int,
    val username: String,
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
    val certificate: String? = null,
    val keepAlive: Int = 30,
    val compression: Boolean = false,
    /** A jump host to connect through first. */
    val via: SshTarget? = null,
) {
    val label: String get() = if (port == 22) "$username@$host" else "$username@$host:$port"

    companion object {
        /** The target for [connection], following its jump hosts. Null if a jump host loops. */
        fun from(vault: Vault, connection: Connection, seen: Set<String> = emptySet()): SshTarget? {
            if (connection.id in seen) return null
            val identity = vault.identity(connection.identityId)
            val via = vault.connection(connection.viaId)?.let { from(vault, it, seen + connection.id) ?: return null }
            return SshTarget(
                host = connection.host.trim(),
                port = connection.port,
                username = identity?.username.orEmpty(),
                password = identity?.password,
                privateKey = identity?.privateKey,
                passphrase = identity?.passphrase,
                certificate = identity?.certificate,
                keepAlive = connection.keepAlive,
                compression = connection.compression,
                via = via,
            )
        }
    }
}

/** A server's host key, to show the user before trusting it. */
data class HostKeyCheck(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    /** The fingerprint saved before, if the key has changed since. */
    val previousFingerprint: String?,
)

/**
 * Questions for the user while connecting. Every call comes from the connecting thread and blocks
 * until answered. Null means the user cancelled.
 */
interface SshPrompter {
    fun trustHostKey(check: HostKeyCheck): Boolean
    fun username(target: SshTarget): String?
    fun password(target: SshTarget): String?
    fun passphrase(target: SshTarget): String?
    /** One keyboard-interactive prompt, such as a two-factor code. */
    fun challenge(target: SshTarget, instruction: String, prompt: String, echo: Boolean): String?
    fun progress(text: String)
}

/** Saved host keys. */
interface HostKeyStore {
    fun get(host: String, port: Int): KnownHost?
    fun put(host: String, port: Int, keyType: String, key: String)
}

class CancelledException : IOException("Cancelled")

/**
 * One SSH login, with its jump hosts. [connect] blocks, so call it off the main thread. Shells,
 * commands and port forwards all share the one connection.
 */
class SshConnection(
    private val target: SshTarget,
    private val prompter: SshPrompter,
    private val hostKeys: HostKeyStore,
    /** Keys to offer through agent forwarding, or null for none. */
    private val agent: AgentKeys? = null,
) : Closeable {
    private var agentAttached = false
    private val clients = mutableListOf<SSHClient>()
    private val forwards = mutableListOf<Closeable>()
    @Volatile private var closed = false
    @Volatile private var cancelled = false

    lateinit var client: SSHClient
        private set

    val isConnected: Boolean get() = !closed && ::client.isInitialized && client.isConnected && client.isAuthenticated

    fun connect() {
        client = open(target, target)
    }

    private fun open(t: SshTarget, last: SshTarget): SSHClient {
        val parent = t.via?.let { open(it, last) }
        if (closed) throw CancelledException()
        val c = SSHClient(config(t.compression))
        synchronized(clients) { clients += c }
        c.connectTimeout = 20_000
        c.addHostKeyVerifier(Verifier(t))
        c.connection.keepAlive.keepAliveInterval = t.keepAlive
        if (parent != null) {
            prompter.progress("Connecting to ${t.host} through ${t.via!!.host}…")
            c.connectVia(parent.newDirectConnection(t.host, t.port))
        } else {
            prompter.progress("Connecting to ${t.host}:${t.port}…")
            c.connect(t.host, t.port)
        }
        authenticate(c, t)
        return c
    }

    private fun authenticate(c: SSHClient, t: SshTarget) {
        var username = t.username
        if (username.isEmpty()) username = prompter.username(t) ?: throw CancelledException()
        val target = t.copy(username = username)
        prompter.progress("Logging in as $username…")
        val methods = mutableListOf<AuthMethod>()
        if (!target.privateKey.isNullOrBlank()) {
            var passphrase = target.passphrase
            if (passphrase == null && Keys.isEncrypted(target.privateKey)) {
                passphrase = prompter.passphrase(target) ?: throw CancelledException()
            }
            methods += AuthPublickey(Keys.provider(target.privateKey, passphrase, target.certificate))
        }
        val password = PasswordOnce(target)
        methods += AuthKeyboardInteractive(Challenges(target, password))
        methods += AuthPassword(password)
        try {
            c.auth(username, methods)
        } catch (e: IOException) {
            // A cancel from a prompt is thrown on sshj's reader thread, which drops the connection.
            if (closed || cancelled) throw CancelledException()
            if (e is UserAuthException) throw IOException("Login failed for $username@${t.host}", e)
            throw e
        }
    }

    private fun cancel(): Nothing {
        cancelled = true
        throw CancelledException()
    }

    /** The stored password once, then asks, and asks again if the server lets us retry. */
    private inner class PasswordOnce(private val t: SshTarget) : PasswordFinder {
        private var usedStored = false

        fun next(): CharArray {
            if (!usedStored && !t.password.isNullOrEmpty()) {
                usedStored = true
                return t.password.toCharArray()
            }
            usedStored = true
            return (prompter.password(t) ?: cancel()).toCharArray()
        }

        override fun reqPassword(resource: Resource<*>?): CharArray = next()
        override fun shouldRetry(resource: Resource<*>?) = !closed
    }

    /** Keyboard-interactive prompts. Password prompts take the password, anything else asks. */
    private inner class Challenges(private val t: SshTarget, private val password: PasswordOnce) : ChallengeResponseProvider {
        private var instruction = ""
        private var askedThisRound = 0
        private var rounds = 0

        override fun getSubmethods(): List<String> = emptyList()

        override fun init(resource: Resource<*>?, name: String?, instruction: String?) {
            this.instruction = listOfNotNull(name?.takeIf { it.isNotBlank() }, instruction?.takeIf { it.isNotBlank() }).joinToString("\n")
        }

        override fun getResponse(prompt: String, echo: Boolean): CharArray {
            askedThisRound++
            if (!echo && PASSWORD_PROMPT.matches(prompt)) return password.next()
            return (prompter.challenge(t, instruction, prompt, echo) ?: cancel()).toCharArray()
        }

        // Retry only after a round that asked something, so a server that turns the method down
        // at once doesn't use up its login attempts before the password is tried.
        override fun shouldRetry(): Boolean {
            val retry = !closed && askedThisRound > 0 && ++rounds < 3
            askedThisRound = 0
            return retry
        }
    }

    private inner class Verifier(private val t: SshTarget) : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val blob = Keys.blob(key)
            val b64 = Base64.getEncoder().encodeToString(blob)
            val type = Keys.typeName(key)
            val known = hostKeys.get(t.host, t.port)
            if (known != null && known.key == b64) return true
            val previous = known?.let {
                try {
                    Keys.fingerprint(Base64.getDecoder().decode(it.key))
                } catch (e: IllegalArgumentException) {
                    "?"
                }
            }
            val ok = prompter.trustHostKey(HostKeyCheck(t.host, t.port, type, Keys.fingerprint(blob), previous))
            if (ok) hostKeys.put(t.host, t.port, type, b64)
            return ok
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
            listOfNotNull(hostKeys.get(t.host, t.port)?.keyType)
    }

    /** Opens an interactive shell with a pty. */
    fun openShell(term: String, columns: Int, rows: Int): Pair<Session, Session.Shell> {
        val session = client.startSession()
        session.allocatePTY(term, columns, rows, 0, 0, emptyMap())
        requestAgent(session)
        return session to session.startShell()
    }

    private fun requestAgent(session: Session) {
        val keys = agent ?: return
        synchronized(this) {
            if (!agentAttached) {
                client.connection.attach(AgentForwarder(client.connection, keys))
                agentAttached = true
            }
        }
        AgentForwarder.request(session)
    }

    /** Runs a command and returns what it printed, standard output then standard error. */
    fun exec(command: String): String {
        client.startSession().use { s ->
            requestAgent(s)
            val cmd = s.exec(command)
            val out = cmd.inputStream.readBytes().toString(Charsets.UTF_8)
            val err = cmd.errorStream.readBytes().toString(Charsets.UTF_8)
            cmd.join()
            return out + err
        }
    }

    /** Listens on this device and forwards each connection to [destHost]:[destPort] from the server. */
    fun localForward(bindHost: String, bindPort: Int, destHost: String, destPort: Int): Closeable {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(bindHost, bindPort))
        val forwarder = client.newLocalPortForwarder(Parameters(bindHost, server.localPort, destHost, destPort), server)
        val thread = Thread({
            try {
                forwarder.listen()
            } catch (e: IOException) {
                // Closed.
            }
        }, "LocalForward-$bindPort")
        thread.isDaemon = true
        thread.start()
        return track(Closeable {
            forwarder.close()
            server.close()
        })
    }

    /** Asks the server to listen and forwards each connection to [destHost]:[destPort] from this device. */
    fun remoteForward(bindHost: String, bindPort: Int, destHost: String, destPort: Int): Closeable {
        val remote = client.remotePortForwarder
        val forward = remote.bind(
            RemotePortForwarder.Forward(bindHost, bindPort),
            SocketForwardingConnectListener(InetSocketAddress(destHost, destPort)),
        )
        return track(Closeable { if (client.isConnected) remote.cancel(forward) })
    }

    /** A SOCKS 4 and 5 proxy on this device, connecting out from the server. */
    fun dynamicForward(bindHost: String, bindPort: Int): Closeable {
        val proxy = SocksServer(bindHost, bindPort) { host, port -> client.newDirectConnection(host, port) }
        proxy.start()
        return track(proxy)
    }

    private fun track(c: Closeable): Closeable {
        synchronized(forwards) { forwards += c }
        return Closeable {
            synchronized(forwards) { forwards -= c }
            c.close()
        }
    }

    override fun close() {
        closed = true
        val fs = synchronized(forwards) { forwards.toList().also { forwards.clear() } }
        for (f in fs) runCatching { f.close() }
        val cs = synchronized(clients) { clients.toList().also { clients.clear() } }
        for (c in cs.asReversed()) runCatching { c.disconnect() }
    }

    companion object {
        private val PASSWORD_PROMPT = Regex("(?is).*password.*:\\s*")

        // sshj's configurations, made once each as building one is slow.
        private val plain: DefaultConfig by lazy { DefaultConfig().apply { addRsaCertificates() } }
        private val compressed: DefaultConfig by lazy {
            DefaultConfig().apply {
                addRsaCertificates()
                compressionFactories = listOf(
                    DelayedZlibCompression.Factory(),
                    ZlibCompression.Factory(),
                    NoneCompression.Factory(),
                )
            }
        }

        fun config(compression: Boolean): Config = if (compression) compressed else plain

        /**
         * sshj only signs RSA certificates with SHA-1, which OpenSSH has refused since 8.8. These
         * sign them with SHA-2 and come first.
         */
        private fun DefaultConfig.addRsaCertificates() {
            keyAlgorithms = listOf(
                KeyAlgorithms.Factory("rsa-sha2-512-cert-v01@openssh.com", SignatureRSA.FactoryRSASHA512(), KeyType.RSA_CERT),
                KeyAlgorithms.Factory("rsa-sha2-256-cert-v01@openssh.com", SignatureRSA.FactoryRSASHA256(), KeyType.RSA_CERT),
            ) + keyAlgorithms
        }
    }
}
