package com.rm.whelk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoshTest {
    @Test
    fun parsesConnectLine() {
        val out = "\r\nMOSH CONNECT 60001 4NeCCgvZFe2RnPgrcU1PQw\r\n\r\nmosh-server (mosh 1.4.0) [build mosh 1.4.0]\r\n[mosh-server detached, pid = 42]\r\n"
        assertEquals(Mosh.Connect(60001, "4NeCCgvZFe2RnPgrcU1PQw"), Mosh.parse(out))
        assertNull(Mosh.parse("bash: mosh-server: command not found"))
    }

    @Test
    fun command() {
        assertEquals("mosh-server new -s -c 256 -l LANG=C.UTF-8 -l LC_ALL=C.UTF-8", Mosh.serverCommand(null, "C.UTF-8"))
        assertTrue(Mosh.serverCommand(" /opt/bin/mosh-server ", "C.UTF-8").startsWith("/opt/bin/mosh-server new"))
    }

    @Test
    fun envAndTmux() {
        val c = Mosh.serverCommand(null, "C.UTF-8", mapOf("EDITOR" to "vim", "MSG" to "it's here"), Mosh.tmux("main"))
        assertEquals("mosh-server new -s -c 256 -l LANG=C.UTF-8 -l LC_ALL=C.UTF-8 -l EDITOR=vim -l 'MSG=it'\\''s here' -- tmux new -A -s main", c)
    }

    @Test
    fun failures() {
        assertEquals("mosh-server isn't installed on the server", Mosh.failure("sh: mosh-server: not found"))
        assertTrue(Mosh.localeRefused("mosh-server needs a UTF-8 native locale to run.\nUnfortunately, the local environment ([no charset variables]) specifies\nthe character set \"US-ASCII\""))
    }
}
