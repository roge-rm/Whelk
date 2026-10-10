package com.rm.whelk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportTest {
    private val key = Keys.generate(KeyKind.ED25519, "")

    private val config = """
        # My hosts
        Host web
            HostName web.example.com
            User deploy
            IdentityFile ~/.ssh/id_ed25519
            LocalForward 8080 localhost:80
            DynamicForward 1080

        Host db db2
          HostName=10.0.0.5
          Port 2222
          ProxyJump web
          RemoteForward 9000 127.0.0.1:9000

        Host far
          HostName far.example.com
          ProxyJump admin@bastion.example.com:2200,web

        Host *.internal !secret.internal
          User internal

        Host box.internal secret.internal
          Compression yes

        Match host foo
          User nobody

        Host *
          User dan
          ServerAliveInterval 15
    """.trimIndent()

    @Test
    fun openSshConfig() {
        val r = OpenSshConfig.parse(config) { name -> if (name == "id_ed25519") key.privateKey else null }
        val byName = r.connections.associateBy { it.name }
        val web = byName["web"]!!
        assertEquals("web.example.com", web.host)
        assertEquals(15, web.keepAlive)
        val webId = r.identities.first { it.id == web.identityId }
        assertEquals("deploy", webId.username)
        assertEquals(key.privateKey, webId.privateKey)
        assertEquals(key.publicKey + " deploy", webId.publicKey)

        val db = byName["db"]!!
        assertEquals("10.0.0.5", db.host)
        assertEquals(2222, db.port)
        assertEquals(web.id, db.viaId)
        assertEquals("dan", r.identities.first { it.id == db.identityId }.username)
        assertNotNull(byName["db2"])

        // far goes through bastion, then web.
        val far = byName["far"]!!
        assertEquals(web.id, far.viaId)
        val bastion = byName["admin@bastion.example.com:2200"]!!
        assertEquals("bastion.example.com", bastion.host)
        assertEquals(2200, bastion.port)
        assertNull(bastion.viaId)

        assertEquals("internal", r.identities.first { it.id == byName["box.internal"]!!.identityId }.username)
        assertTrue(byName["box.internal"]!!.compression)
        assertEquals("dan", r.identities.first { it.id == byName["secret.internal"]!!.identityId }.username)
        assertNull(byName["foo"])

        val webForwards = r.forwards.filter { it.connectionId == web.id }
        assertEquals(setOf(ForwardKind.LOCAL, ForwardKind.DYNAMIC), webForwards.map { it.kind }.toSet())
        val local = webForwards.first { it.kind == ForwardKind.LOCAL }
        assertEquals(8080, local.bindPort)
        assertEquals("localhost", local.destHost)
        assertEquals(80, local.destPort)
        assertEquals(2, r.forwards.count { it.kind == ForwardKind.REMOTE })
        assertTrue(r.skipped.contains("Match blocks"))
    }

    @Test
    fun missingKeyIsNoted() {
        val r = OpenSshConfig.parse("Host a\n  IdentityFile ~/.ssh/work_rsa\n  User me")
        assertNull(r.identities.single().privateKey)
        assertTrue(r.skipped.any { it.contains("work_rsa") })
    }

    @Test
    fun knownHosts() {
        val r = KnownHostsFile.parse(
            """
            github.com,140.82.112.3 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl
            [git.example.com]:2222 ecdsa-sha2-nistp256 AAAAE2VjZHNh
            |1|abc=|def= ssh-rsa AAAAB3
            @cert-authority *.example.com ssh-rsa AAAAB3
            # comment
            """.trimIndent()
        )
        assertEquals(setOf("github.com:22", "140.82.112.3:22", "git.example.com:2222"), r.knownHosts.map { it.id }.toSet())
        assertEquals("ssh-ed25519", r.knownHosts.first { it.id == "github.com:22" }.keyType)
        assertEquals(2, r.skipped.size)
    }

    @Test
    fun puttyReg() {
        val reg = """
            Windows Registry Editor Version 5.00

            [HKEY_CURRENT_USER\Software\SimonTatham\PuTTY\Sessions\My%20Server]
            "HostName"="dan@server.example.com"
            "PortNumber"=dword:00000016
            "Protocol"="ssh"
            "PublicKeyFile"="C:\\Users\\Dan\\keys\\server.ppk"
            "PortForwardings"="L8080=localhost:80,4D1080,R2222=127.0.0.1:22"
            "Compression"=dword:00000001

            [HKEY_CURRENT_USER\Software\SimonTatham\PuTTY\Sessions\Router]
            "HostName"=""
            "Protocol"="serial"
            "SerialLine"="COM3"
            "SerialSpeed"=dword:00002580
            "SerialParity"=dword:00000002

            [HKEY_CURRENT_USER\Software\SimonTatham\PuTTY\Sessions\Default%20Settings]
            "HostName"=""
        """.trimIndent()
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + reg.toByteArray(Charsets.UTF_16LE)
        val r = PuttyReg.parse(PuttyReg.decode(bytes))
        assertEquals(listOf("My Server", "Router"), r.connections.map { it.name })
        val s = r.connections[0]
        assertEquals("server.example.com", s.host)
        assertEquals(22, s.port)
        assertTrue(s.compression)
        assertEquals("dan", r.identities.first { it.id == s.identityId }.username)
        assertEquals("server.ppk", r.identities.first { it.id == s.identityId }.name)
        assertEquals(3, r.forwards.size)
        val router = r.connections[1]
        assertEquals(ConnectionType.SERIAL, router.type)
        assertEquals(9600, router.line.baud)
        assertEquals(Parity.EVEN, router.line.parity)
    }

    @Test
    fun addingSkipsWhatsThere() {
        val me = Identity(username = "deploy")
        val have = Connection(host = "web.example.com", identityId = me.id)
        val vault = Vault().put(me).put(have)
        val r = OpenSshConfig.parse("Host web\n HostName web.example.com\n User deploy\nHost other\n HostName other.example.com\n User deploy\n ProxyJump web")
        val v = r.addTo(vault)
        assertEquals(2, v.liveConnections().size)
        val other = v.liveConnections().first { it.host == "other.example.com" }
        assertEquals(have.id, other.viaId)
        assertEquals(me.id, other.identityId)
        assertEquals(1, v.liveIdentities().size)
    }
}
