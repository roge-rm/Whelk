package com.rm.whelk.core

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class LineOutputTest {
    private fun send(text: String, settings: LineSettings): Pair<String, String> {
        val out = ByteArrayOutputStream()
        val shown = ByteArrayOutputStream()
        LineOutput(out, settings) { shown.write(it) }.write(text.toByteArray())
        return out.toString("UTF-8") to shown.toString("UTF-8")
    }

    @Test
    fun enterModes() {
        assertEquals("ls\r", send("ls\r", LineSettings()).first)
        assertEquals("ls\n", send("ls\r", LineSettings(enter = EnterSends.LF)).first)
        assertEquals("ls\r\n", send("ls\r", LineSettings(enter = EnterSends.CRLF)).first)
    }

    @Test
    fun localEcho() {
        assertEquals("", send("at\r", LineSettings()).second)
        assertEquals("at\r\n", send("at\r", LineSettings(localEcho = true, enter = EnterSends.LF)).second)
    }

    @Test
    fun oldVaultsReadWithDefaults() {
        val v = Vault.fromJson("""{"connections":[{"id":"a","type":"SERIAL","line":{"baud":9600}}]}""")
        assertEquals(9600, v.connection("a")!!.line.baud)
        assertEquals(EnterSends.CR, v.connection("a")!!.line.enter)
    }
}
