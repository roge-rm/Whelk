package com.rm.whelk.core

import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SshTest {
    private lateinit var server: SshServer
    private val root = kotlin.io.path.createTempDirectory("whelk-sftp").toFile()
    private val key = Keys.generate(KeyKind.ECDSA_256, "")
    private val keyBlob = Base64.getDecoder().decode(key.publicKey.split(" ")[1])

    @BeforeTest
    fun start() {
        server = SshServer.setUpDefaultServer()
        server.host = "127.0.0.1"
        server.port = 0
        server.keyPairProvider = SimpleGeneratorHostKeyProvider().apply { algorithm = "EC" }
        server.passwordAuthenticator = PasswordAuthenticator { user, pw, _ -> user == "dan" && pw == "pw" }
        server.publickeyAuthenticator = PublickeyAuthenticator { user, k, _ -> user == "dan" && Keys.blob(k).contentEquals(keyBlob) }
        server.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        server.commandFactory = ProcessShellCommandFactory.INSTANCE
        server.subsystemFactories = listOf(org.apache.sshd.sftp.server.SftpSubsystemFactory())
        server.fileSystemFactory = org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(root.toPath())
        server.start()
    }

    @AfterTest
    fun stop() {
        server.stop(true)
        root.deleteRecursively()
    }

    @Test
    fun sftpFiles() {
        java.io.File(root, "a.txt").writeText("hello")
        java.io.File(root, "sub").mkdir()
        SshConnection(target("pw"), Prompter(), Store()).use { c ->
            c.connect()
            Sftp(c).use { f ->
                val home = f.home()
                val list = f.list(home)
                assertEquals(listOf("sub", "a.txt"), list.map { it.name })
                assertTrue(list[0].isDir)
                val out = java.io.ByteArrayOutputStream()
                f.download(Sftp.join(home, "a.txt"), out)
                assertEquals("hello", out.toString())
                f.upload("new file".byteInputStream(), Sftp.join(home, "sub/b.txt"))
                assertEquals("new file", java.io.File(root, "sub/b.txt").readText())
                f.rename(Sftp.join(home, "a.txt"), Sftp.join(home, "c.txt"))
                f.mkdir(Sftp.join(home, "sub/deeper"))
                f.delete(f.list(home).first { it.name == "sub" })
                assertEquals(listOf("c.txt"), f.list(home).map { it.name })
            }
        }
        assertEquals("rwxr-x---", Sftp.permissionString(0b111101000))
        assertEquals("/home", Sftp.parent("/home/dan"))
        assertEquals("/", Sftp.parent("/home"))
        assertEquals(null, Sftp.parent("/"))
    }

    private class Store : HostKeyStore {
        val keys = HashMap<String, KnownHost>()
        override fun get(host: String, port: Int) = keys[KnownHost.idFor(host, port)]
        override fun put(host: String, port: Int, keyType: String, key: String) {
            keys[KnownHost.idFor(host, port)] = KnownHost(KnownHost.idFor(host, port), keyType, key)
        }
    }

    private open class Prompter(var trust: Boolean = true, val passwords: MutableList<String> = mutableListOf()) : SshPrompter {
        var hostChecks = 0
        override fun trustHostKey(check: HostKeyCheck): Boolean {
            hostChecks++
            return trust
        }
        override fun username(target: SshTarget): String? = "dan"
        override fun password(target: SshTarget): String? = passwords.removeFirstOrNull()
        override fun passphrase(target: SshTarget): String? = null
        override fun challenge(target: SshTarget, instruction: String, prompt: String, echo: Boolean): String? = null
        override fun progress(text: String) {}
    }

    private fun target(password: String? = null, privateKey: String? = null, user: String = "dan") =
        SshTarget("127.0.0.1", server.port, user, password = password, privateKey = privateKey)

    @Test
    fun passwordLoginAndHostKeyIsRemembered() {
        val store = Store()
        val p = Prompter()
        SshConnection(target("pw"), p, store).use { c ->
            c.connect()
            assertTrue(c.isConnected)
            assertEquals("hello\n", c.exec("echo hello"))
        }
        assertEquals(1, p.hostChecks)
        SshConnection(target("pw"), p, store).use { it.connect() }
        assertEquals(1, p.hostChecks)
    }

    @Test
    fun refusedHostKeyStops() {
        assertFailsWith<Exception> {
            SshConnection(target("pw"), Prompter(trust = false), Store()).use { it.connect() }
        }
    }

    @Test
    fun asksForPasswordWhenNoneSaved() {
        SshConnection(target(), Prompter(passwords = mutableListOf("wrong", "pw")), Store()).use { c ->
            c.connect()
            assertTrue(c.isConnected)
        }
    }

    @Test
    fun serverWithoutKeyboardInteractivePrompts() {
        // Like OpenSSH without PAM: keyboard-interactive is offered but fails without asking.
        server.keyboardInteractiveAuthenticator = object : org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator {
            override fun generateChallenge(session: org.apache.sshd.server.session.ServerSession?, username: String?, lang: String?, subMethods: String?) = null
            override fun authenticate(session: org.apache.sshd.server.session.ServerSession?, username: String?, responses: MutableList<String>?) = false
        }
        server.properties["max-auth-requests"] = "6"
        SshConnection(target("pw"), Prompter(), Store()).use { c ->
            c.connect()
            assertTrue(c.isConnected)
        }
    }

    @Test
    fun cancelledPasswordCancels() {
        assertFailsWith<CancelledException> {
            SshConnection(target(), Prompter(), Store()).use { it.connect() }
        }
    }

    @Test
    fun keyLogin() {
        SshConnection(target(privateKey = key.privateKey), Prompter(), Store()).use { c ->
            c.connect()
            assertEquals("ok\n", c.exec("echo ok"))
        }
    }

    @Test
    fun askedUsername() {
        SshConnection(target("pw", user = ""), Prompter(), Store()).use { c ->
            c.connect()
            assertTrue(c.isConnected)
        }
    }

    @Test
    fun jumpHost() {
        val jump = target("pw")
        val t = target("pw").copy(via = jump)
        SshConnection(t, Prompter(), Store()).use { c ->
            c.connect()
            assertEquals("via\n", c.exec("echo via"))
        }
    }

    /** A server that answers each connection with "echo:" and the first line it gets. */
    private fun echoServer(): ServerSocket {
        val s = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (true) {
                val client = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) {
                    client.use {
                        val line = it.getInputStream().bufferedReader().readLine()
                        it.getOutputStream().write("echo:$line\n".toByteArray())
                    }
                }
            }
        }
        return s
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun ask(socket: Socket): String {
        socket.getOutputStream().write("hi\n".toByteArray())
        return socket.getInputStream().bufferedReader().readLine()
    }

    @Test
    fun localForward() {
        echoServer().use { echo ->
            SshConnection(target("pw"), Prompter(), Store()).use { c ->
                c.connect()
                val port = freePort()
                c.localForward("127.0.0.1", port, "127.0.0.1", echo.localPort).use {
                    Socket("127.0.0.1", port).use { s -> assertEquals("echo:hi", ask(s)) }
                }
            }
        }
    }

    @Test
    fun remoteForward() {
        echoServer().use { echo ->
            SshConnection(target("pw"), Prompter(), Store()).use { c ->
                c.connect()
                val port = freePort()
                c.remoteForward("127.0.0.1", port, "127.0.0.1", echo.localPort).use {
                    Socket("127.0.0.1", port).use { s -> assertEquals("echo:hi", ask(s)) }
                }
            }
        }
    }

    @Test
    fun socks5() {
        echoServer().use { echo ->
            SshConnection(target("pw"), Prompter(), Store()).use { c ->
                c.connect()
                val port = freePort()
                c.dynamicForward("127.0.0.1", port).use {
                    Socket().use { s ->
                        s.connect(InetSocketAddress("127.0.0.1", port))
                        val out = s.getOutputStream()
                        val input = DataInputStream(s.getInputStream())
                        out.write(byteArrayOf(5, 1, 0))
                        assertEquals(5, input.readUnsignedByte())
                        assertEquals(0, input.readUnsignedByte())
                        val host = "localhost".toByteArray()
                        out.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host +
                            byteArrayOf((echo.localPort shr 8).toByte(), echo.localPort.toByte()))
                        val reply = ByteArray(10).also { input.readFully(it) }
                        assertEquals(0, reply[1].toInt())
                        assertEquals("echo:hi", ask(s))
                    }
                }
            }
        }
    }

    @Test
    fun socks4a() {
        echoServer().use { echo ->
            SshConnection(target("pw"), Prompter(), Store()).use { c ->
                c.connect()
                val port = freePort()
                c.dynamicForward("127.0.0.1", port).use {
                    Socket("127.0.0.1", port).use { s ->
                        val p = echo.localPort
                        s.getOutputStream().write(byteArrayOf(4, 1, (p shr 8).toByte(), p.toByte(), 0, 0, 0, 1, 0) +
                            "127.0.0.1".toByteArray() + byteArrayOf(0))
                        val reply = ByteArray(8).also { DataInputStream(s.getInputStream()).readFully(it) }
                        assertEquals(90, reply[1].toInt())
                        assertEquals("echo:hi", ask(s))
                    }
                }
            }
        }
    }
}
