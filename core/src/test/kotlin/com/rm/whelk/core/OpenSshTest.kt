package com.rm.whelk.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Against real OpenSSH: the test server from tools/test-server, which trusts the CA in its ca/
 * folder. Runs when WHELK_OPENSSH is set to its address, like 127.0.0.1:2222, and skips otherwise.
 */
class OpenSshTest {
    private val address = System.getenv("WHELK_OPENSSH")
    private val caDir = File(System.getProperty("user.dir")).resolve("../tools/test-server/ca")

    private object Trusting : SshPrompter {
        override fun trustHostKey(check: HostKeyCheck) = true
        override fun username(target: SshTarget): String? = null
        override fun password(target: SshTarget): String? = null
        override fun passphrase(target: SshTarget): String? = null
        override fun challenge(target: SshTarget, instruction: String, prompt: String, echo: Boolean): String? = null
        override fun progress(text: String) {}
    }

    private object NoStore : HostKeyStore {
        override fun get(host: String, port: Int): KnownHost? = null
        override fun put(host: String, port: Int, keyType: String, key: String) {}
    }

    private fun target(key: KeyText, cert: String?): SshTarget {
        val (host, port) = address!!.split(":")
        return SshTarget(host, port.toInt(), "whelk", privateKey = key.privateKey, certificate = cert)
    }

    /** A user certificate for [key] signed by the test CA [ca] for user whelk. */
    private fun certify(key: KeyText, ca: String): String {
        val dir = kotlin.io.path.createTempDirectory("whelk-cert").toFile()
        try {
            File(caDir, ca).copyTo(File(dir, "ca"))
            java.nio.file.Files.setPosixFilePermissions(File(dir, "ca").toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
            File(dir, "user.pub").writeText(key.publicKey + "\n")
            keygen(dir, "-q", "-s", "ca", "-I", "whelk-test", "-n", "whelk", "-V", "-5m:+1h", "user.pub")
            return File(dir, "user-cert.pub").readText()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun certificates() {
        if (address == null) return
        for ((kind, ca) in listOf(KeyKind.ED25519 to "user_ca", KeyKind.ECDSA_256 to "user_ca", KeyKind.RSA_2048 to "user_ca_rsa")) {
            val key = Keys.generate(kind, "")
            val cert = certify(key, ca)
            SshConnection(target(key, cert), Trusting, NoStore).use { c ->
                c.connect()
                assertEquals("whelk\n", c.exec("whoami"), kind.name)
            }
            // Without the certificate the key alone isn't let in.
            assertFailsWith<Exception>(kind.name) { SshConnection(target(key, null), Trusting, NoStore).use { it.connect() } }
        }
    }

    @Test
    fun agentForwarding() {
        if (address == null) return
        val key = Keys.generate(KeyKind.ED25519, "")
        val cert = certify(key, "user_ca")
        val agentKey = Keys.agentKey(key.privateKey, null, null, "phone key")!!
        var used = 0
        val keys = object : AgentKeys {
            override fun keys() = listOf(agentKey)
            override fun allow(key: AgentKey): Boolean {
                used++
                return true
            }
        }
        SshConnection(target(key, cert), Trusting, NoStore, keys).use { c ->
            c.connect()
            val listed = c.exec("ssh-add -l")
            assertTrue(listed.contains("phone key"), listed)
            // Signing through the forwarded agent: sign a file with ssh-keygen and check it.
            val out = c.exec(
                "echo hello > /tmp/msg && ssh-add -L > /tmp/k.pub && " +
                    "ssh-keygen -Y sign -f /tmp/k.pub -n test /tmp/msg >/dev/null 2>&1 && " +
                    "ssh-keygen -Y check-novalidate -n test -f /tmp/k.pub -s /tmp/msg.sig < /tmp/msg; rm -f /tmp/msg*"
            )
            assertTrue(out.contains("Good"), out)
        }
        assertTrue(used >= 1)
    }

    @Test
    fun securityKey() {
        if (address == null) return
        // A pretend security key signing as a FIDO authenticator does; OpenSSH checks it in software.
        val pair = org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator()
            .apply { init(org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters(java.security.SecureRandom())) }.generateKeyPair()
        val handle = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        var touched = 0
        val signer = com.hierynomus.sshj.userauth.fido.SecurityKeySigner { req ->
            touched++
            val authData = java.security.MessageDigest.getInstance("SHA-256").digest(req.application.toByteArray()) + byteArrayOf(0x01, 0, 0, 0, 1)
            val s = org.bouncycastle.crypto.signers.Ed25519Signer().apply { init(true, pair.private) }
            val signed = authData + req.challenge
            s.update(signed, 0, signed.size)
            com.hierynomus.sshj.userauth.fido.SecurityKeySignatureData(0x01, 1, s.generateSignature())
        }
        val raw = (pair.public as org.bouncycastle.crypto.params.Ed25519PublicKeyParameters).encoded
        val sk = SecurityKeys.keyFile(SecurityKeys.ED25519, raw, handle, SecurityKeys.FLAG_USER_PRESENCE, "test-sk")

        // Let the key in, through a certificate login.
        val admin = Keys.generate(KeyKind.ED25519, "")
        SshConnection(target(admin, certify(admin, "user_ca")), Trusting, NoStore).use { c ->
            c.connect()
            c.exec("mkdir -p ~/.ssh && echo '${sk.publicKey}' >> ~/.ssh/authorized_keys")
        }
        try {
            SshConnection(target(sk, null), Trusting, NoStore, securityKey = signer).use { c ->
                c.connect()
                assertEquals("whelk\n", c.exec("whoami"))
            }
            assertEquals(1, touched)
        } finally {
            SshConnection(target(admin, certify(admin, "user_ca")), Trusting, NoStore).use { c ->
                c.connect()
                c.exec("sed -i '/test-sk/d' ~/.ssh/authorized_keys")
            }
        }
    }

    companion object {
        fun keygen(dir: File, vararg args: String) {
            val p = ProcessBuilder("/usr/bin/ssh-keygen", *args).directory(dir).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            check(p.waitFor() == 0) { out }
        }
    }
}
