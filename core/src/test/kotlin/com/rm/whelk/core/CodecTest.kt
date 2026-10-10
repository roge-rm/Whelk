package com.rm.whelk.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class CodecTest {
    @Test
    fun latin1BothWays() {
        val server = "café über".toByteArray(Charsets.ISO_8859_1)
        // A byte at a time, as a slow line delivers it.
        val input = Codec.input(object : java.io.InputStream() {
            var i = 0
            override fun read(): Int = if (i < server.size) server[i++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val c = read()
                if (c < 0) return -1
                b[off] = c.toByte()
                return 1
            }
        }, "ISO-8859-1")
        assertEquals("café über", input.readBytes().toString(Charsets.UTF_8))

        val out = ByteArrayOutputStream()
        val o = Codec.output(out, "ISO-8859-1", Backspace.DEL)
        // "é" split across two writes, as UTF-8 bytes can be.
        val e = "é".toByteArray()
        o.write(byteArrayOf('a'.code.toByte(), e[0]))
        o.write(byteArrayOf(e[1], 'b'.code.toByte()))
        assertEquals("aéb", out.toByteArray().toString(Charsets.ISO_8859_1))
    }

    @Test
    fun shiftJis() {
        val text = "日本語のテスト"
        val input = Codec.input(ByteArrayInputStream(text.toByteArray(charset("Shift_JIS"))), "Shift_JIS")
        assertEquals(text, input.readBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun backspace() {
        val out = ByteArrayOutputStream()
        Codec.output(out, "UTF-8", Backspace.BS).write(byteArrayOf('x'.code.toByte(), 0x7F))
        assertEquals(listOf(0x78, 0x08), out.toByteArray().map { it.toInt() })
        val same = ByteArrayOutputStream()
        Codec.output(same, "UTF-8", Backspace.DEL).write(byteArrayOf(0x7F))
        assertEquals(0x7F, same.toByteArray()[0].toInt())
    }

    @Test
    fun extraKeyText() {
        assertEquals("\u001b:wq\r", ExtraKey.decode("\\e:wq\\n"))
        assertEquals("\u0003", ExtraKey.decode("^C"))
        assertEquals("\u0002n", ExtraKey.decode("^bn"))
        assertEquals("a^b", ExtraKey.decode("a\\^b"))
        assertEquals("|", ExtraKey.decode("|"))
    }
}
