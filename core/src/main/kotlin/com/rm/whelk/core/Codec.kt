package com.rm.whelk.core

import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** What the Backspace key sends. Most servers want DEL; some old systems want ^H. */
enum class Backspace(val label: String, val byte: Int) { DEL("^? (DEL)", 0x7F), BS("^H", 0x08) }

/**
 * Converts between the terminal, which speaks UTF-8, and a server using another character set, and
 * sends Backspace as the server wants it.
 */
object Codec {
    val CHARSETS = listOf(
        "UTF-8", "ISO-8859-1", "ISO-8859-15", "windows-1252", "IBM437", "KOI8-R", "windows-1251",
        "ISO-8859-2", "Shift_JIS", "EUC-JP", "GBK", "Big5", "EUC-KR",
    )

    fun isUtf8(charset: String) = charset.equals("UTF-8", ignoreCase = true)

    /** Output from the server in [charset], read as UTF-8. */
    fun input(raw: InputStream, charset: String): InputStream =
        if (isUtf8(charset)) raw else Recoding(raw, Charset.forName(charset), Charsets.UTF_8)

    /** Keys from the terminal (UTF-8 bytes) written to the server in [charset], with Backspace mapped. */
    fun output(raw: OutputStream, charset: String, backspace: Backspace): OutputStream {
        val mapped = if (backspace == Backspace.DEL) raw else object : FilterOutputStream(raw) {
            override fun write(b: Int) = out.write(if (b == 0x7F) backspace.byte else b)
            override fun write(b: ByteArray, off: Int, len: Int) {
                val copy = b.copyOfRange(off, off + len)
                for (i in copy.indices) if (copy[i] == 0x7F.toByte()) copy[i] = backspace.byte.toByte()
                out.write(copy)
            }
        }
        if (isUtf8(charset)) return mapped
        val from = Charsets.UTF_8
        val to = Charset.forName(charset)
        return object : OutputStream() {
            private val decoder = from.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            private val encoder = to.newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            private var pending = ByteArray(0)

            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                val bytes = ByteBuffer.wrap(pending + b.copyOfRange(off, off + len))
                val chars = CharBuffer.allocate(bytes.remaining() + 1)
                decoder.decode(bytes, chars, false)
                pending = ByteArray(bytes.remaining()).also { bytes.get(it) }
                chars.flip()
                val encoded = encoder.encode(chars)
                mapped.write(encoded.array(), encoded.arrayOffset(), encoded.remaining())
                mapped.flush()
            }

            override fun flush() = mapped.flush()
            override fun close() = mapped.close()
        }
    }

    /** Reads [raw] in one charset and gives the same text in another, keeping split characters for the next read. */
    private class Recoding(private val raw: InputStream, from: Charset, private val to: Charset) : InputStream() {
        private val decoder = from.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        private val inBuf = ByteBuffer.allocate(8192).also { it.flip() }
        private var outBuf = ByteBuffer.allocate(0)

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (!outBuf.hasRemaining()) {
                inBuf.compact()
                val n = raw.read(inBuf.array(), inBuf.position(), inBuf.remaining())
                if (n < 0) return -1
                inBuf.position(inBuf.position() + n)
                inBuf.flip()
                val chars = CharBuffer.allocate(inBuf.remaining() + 1)
                decoder.decode(inBuf, chars, false)
                chars.flip()
                outBuf = to.encode(chars)
            }
            val n = minOf(len, outBuf.remaining())
            outBuf.get(b, off, n)
            return n
        }

        override fun available(): Int = outBuf.remaining()
        override fun close() = raw.close()
    }
}
