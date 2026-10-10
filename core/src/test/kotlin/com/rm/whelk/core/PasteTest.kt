package com.rm.whelk.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PasteTest {
    @Test
    fun invisibleAndOddSpaces() {
        assertEquals("ls -la /tmp", Paste.clean("ls -la​ /tmp﻿"))
        assertEquals("sudo apt install", Paste.clean("sudo apt install­"))
    }

    @Test
    fun lineEndingsAndTrailingBreak() {
        assertEquals("a\nb\nc", Paste.clean("a\r\nb\rc\r\n"))
        assertEquals("echo hi", Paste.clean("echo hi\n\n  "))
        assertEquals("a\tb", Paste.clean("a\tb"))
    }

    @Test
    fun quotesAndDashes() {
        assertEquals("echo \"it's\" --force -x", Paste.clean("echo “it’s” ––force —x"))
        assertEquals("echo “hi”", Paste.clean("echo “hi”", straighten = false))
    }

    @Test
    fun controlCharactersGo() {
        assertEquals("rm x", Paste.clean("rm\u0007 x\u0008"))
    }

    @Test
    fun plainTextUnchanged() {
        val s = "for f in *.txt; do wc -l \"\$f\"; done"
        assertEquals(s, Paste.clean(s))
        assertEquals("héllo wörld 日本", Paste.clean("héllo wörld 日本"))
    }
}
