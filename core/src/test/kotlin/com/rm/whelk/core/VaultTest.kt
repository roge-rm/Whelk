package com.rm.whelk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultTest {
    @Test
    fun jsonRoundTrip() {
        val id = Identity(name = "me", username = "dan", password = "pw")
        val c = Connection(name = "box", host = "example.com", identityId = id.id)
        val v = Vault().put(id).put(c).put(Snippet(name = "up", text = "uptime"))
        val back = Vault.fromJson(v.toJson())
        assertEquals(v, back)
        assertEquals("box", back.connection(c.id)!!.title(back))
    }

    @Test
    fun unknownKeysAreIgnored() {
        val v = Vault.fromJson("""{"connections":[{"id":"a","host":"h","future":1}],"later":[]}""")
        assertEquals("h", v.connection("a")!!.host)
    }

    @Test
    fun titleFallsBackToUserAtHost() {
        val id = Identity(username = "root")
        val v = Vault().put(id)
        assertEquals("root@h:2222", Connection(host = "h", port = 2222, identityId = id.id).title(v))
        assertEquals("h", Connection(host = "h").title(Vault()))
    }

    @Test
    fun mergeTakesNewestAndKeepsDeletions() {
        val a = Connection(id = "1", host = "old", updated = 100)
        val b = a.copy(host = "new", updated = 200)
        val gone = Connection(id = "2", host = "x", updated = 300, deleted = true)
        val mine = Vault(connections = listOf(a, Connection(id = "2", host = "x", updated = 150)))
        val theirs = Vault(connections = listOf(b, gone))
        val merged = mine.merge(theirs, at = 1000)
        assertEquals("new", merged.connections.first { it.id == "1" }.host)
        assertNull(merged.connection("2"))
        assertTrue(merged.connections.first { it.id == "2" }.deleted)
        // Both ways round give the same.
        assertEquals(merged.connections.toSet(), theirs.merge(mine, at = 1000).connections.toSet())
    }

    @Test
    fun oldDeletionsAreDropped() {
        val day = 24L * 60 * 60 * 1000
        val gone = Connection(id = "1", updated = 0, deleted = true)
        val merged = Vault(connections = listOf(gone)).merge(Vault(), at = (TOMBSTONE_DAYS + 1) * day)
        assertTrue(merged.connections.isEmpty())
    }

    @Test
    fun jumpHostLoopsAreRefused() {
        val a = Connection(id = "a", host = "a", viaId = "b")
        val b = Connection(id = "b", host = "b", viaId = "a")
        val v = Vault(connections = listOf(a, b))
        assertNull(SshTarget.from(v, a))
        val c = Connection(id = "c", host = "c", viaId = "d")
        val d = Connection(id = "d", host = "d")
        val v2 = Vault(connections = listOf(c, d))
        assertEquals("d", SshTarget.from(v2, c)!!.via!!.host)
    }

    @Test
    fun sealedRoundTrip() {
        val plain = "hello vault".toByteArray()
        val box = Sealed.seal(plain, "secret".toCharArray(), memoryKib = 8 * 1024, passes = 1)
        assertTrue(Sealed.isSealed(box))
        assertEquals("hello vault", String(Sealed.open(box, "secret".toCharArray())))
        assertFailsWith<Sealed.WrongPassphrase> { Sealed.open(box, "wrong".toCharArray()) }
        box[box.size - 1] = (box[box.size - 1] + 1).toByte()
        assertFailsWith<Sealed.WrongPassphrase> { Sealed.open(box, "secret".toCharArray()) }
    }

    @Test
    fun oldFilesStillOpen() {
        val box = Sealed.sealV1("old file".toByteArray(), "secret".toCharArray(), 2000)
        assertEquals("old file", String(Sealed.open(box, "secret".toCharArray())))
        assertFailsWith<Sealed.WrongPassphrase> { Sealed.open(box, "wrong".toCharArray()) }
    }

    @Test
    fun sealedRefusesCostlyHeaders() {
        val box = Sealed.sealV1("x".toByteArray(), "secret".toCharArray(), 2000)
        // Ask for 10 million rounds in the header.
        java.nio.ByteBuffer.wrap(box).putInt(6, 10_000_000)
        assertFailsWith<java.io.IOException> { Sealed.open(box, "secret".toCharArray()) }
        val v2 = Sealed.seal("x".toByteArray(), "secret".toCharArray(), memoryKib = 8 * 1024, passes = 1)
        // Ask for 4 GiB of memory.
        java.nio.ByteBuffer.wrap(v2).putInt(6, 4 * 1024 * 1024)
        assertFailsWith<java.io.IOException> { Sealed.open(v2, "secret".toCharArray()) }
    }
}

class TeamTest {
    @Test
    fun teamPartTakesGroupAndWhatItUses() {
        val g = Group(name = "work")
        val idA = Identity(username = "a")
        val idJ = Identity(username = "j")
        val idX = Identity(username = "x")
        val jump = Connection(host = "jump", identityId = idJ.id)
        val a = Connection(host = "a", identityId = idA.id, viaId = jump.id, groupIds = listOf(g.id))
        val other = Connection(host = "other", identityId = idX.id)
        val gone = Connection(host = "gone", groupIds = listOf(g.id), deleted = true)
        val f = PortForward(connectionId = a.id)
        val v = Vault(
            connections = listOf(jump, a, other, gone),
            identities = listOf(idA, idJ, idX),
            groups = listOf(g, Group(name = "home")),
            forwards = listOf(f, PortForward(connectionId = other.id)),
            snippets = listOf(Snippet(text = "ls")),
        )
        val part = v.teamPart(g.id)
        assertEquals(setOf("a", "jump", "gone"), part.connections.map { it.host }.toSet())
        assertEquals(setOf("a", "j"), part.identities.map { it.username }.toSet())
        assertEquals(listOf(g), part.groups)
        assertEquals(listOf(f), part.forwards)
        assertTrue(part.snippets.isEmpty())
    }

    @Test
    fun secretsLeftOutAndPutBack() {
        val id = Identity(username = "dan", password = "pw", privateKey = "KEY", passphrase = "pp", publicKey = "ssh-ed25519 AAAA")
        val c = Connection(host = "box", proxy = ProxySettings(type = ProxyType.SOCKS5, host = "p", password = "proxypw"))
        val local = Vault().put(id).put(c)

        val out = local.withoutSecrets()
        val sentId = out.identity(id.id)!!
        assertNull(sentId.password)
        assertNull(sentId.privateKey)
        assertEquals("ssh-ed25519 AAAA", sentId.publicKey)
        assertEquals("", out.connection(c.id)!!.proxy.password)

        // Another device changes the name later; the secrets come back from this one.
        val changed = out.put(sentId.copy(name = "renamed", updated = id.updated + 1))
        val merged = local.merge(changed).withSecretsFrom(local)
        val back = merged.identity(id.id)!!
        assertEquals("renamed", back.name)
        assertEquals("pw", back.password)
        assertEquals("KEY", back.privateKey)
        assertEquals("pp", back.passphrase)
        assertEquals(false, back.secretsKept)
        assertEquals("proxypw", merged.connection(c.id)!!.proxy.password)

        // A password removed on purpose elsewhere, from a copy with secrets, stays removed.
        val removed = local.put(id.copy(password = null, updated = id.updated + 2))
        assertNull(local.merge(removed).withSecretsFrom(local).identity(id.id)!!.password)
    }

    @Test
    fun futureTimesDontWinForever() {
        val now = 1_000_000_000_000L
        val c = Connection(host = "mine", updated = now - 1000)
        val fresh = Connection(host = "new", updated = Long.MAX_VALUE)
        val forged = Vault().put(c.copy(host = "theirs", updated = Long.MAX_VALUE)).put(fresh)
        val merged = Vault().put(c).merge(forged, at = now)
        // A change dated far ahead to something already here is ignored.
        assertEquals("mine", merged.connection(c.id)!!.host)
        // Something new comes in, dated now, so later edits win over it.
        assertEquals(now, merged.connection(fresh.id)!!.updated)
        val edited = merged.put(merged.connection(fresh.id)!!.copy(host = "edited", updated = now + 5000))
        assertEquals("edited", edited.merge(forged, at = now + 6000).connection(fresh.id)!!.host)
    }
}
