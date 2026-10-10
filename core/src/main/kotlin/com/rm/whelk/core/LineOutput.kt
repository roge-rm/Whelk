package com.rm.whelk.core

import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * Writes keys to a serial line or raw socket: the Enter key's CR becomes what the device wants,
 * and with local echo, what's sent is shown in the terminal through [echo].
 */
class LineOutput(out: OutputStream, private val settings: LineSettings, private val echo: (ByteArray) -> Unit) :
    FilterOutputStream(out) {

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        val enter = settings.enter.bytes.toByteArray()
        val buf = java.io.ByteArrayOutputStream(len + 4)
        for (i in off until off + len) {
            if (b[i] == '\r'.code.toByte()) buf.write(enter) else buf.write(b[i].toInt())
        }
        val bytes = buf.toByteArray()
        out.write(bytes)
        if (settings.localEcho) {
            // The terminal needs CR LF to start a new line, whatever the device gets.
            echo(String(bytes, Charsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n").toByteArray())
        }
    }
}
