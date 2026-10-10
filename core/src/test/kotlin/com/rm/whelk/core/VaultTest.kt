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
        val box = Sealed.seal(plain, "secret".toCharArray(), iterations = 2000)
        assertTrue(Sealed.isSealed(box))
        assertEquals("hello vault", String(Sealed.open(box, "secret".toCharArray())))
        assertFailsWith<Sealed.WrongPassphrase> { Sealed.open(box, "wrong".toCharArray()) }
        box[box.size - 1] = (box[box.size - 1] + 1).toByte()
        assertFailsWith<Sealed.WrongPassphrase> { Sealed.open(box, "secret".toCharArray()) }
    }
}
