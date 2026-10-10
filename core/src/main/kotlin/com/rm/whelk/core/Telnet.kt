package com.rm.whelk.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A Telnet connection. [input] gives the data with the protocol commands taken out and answered,
 * and [output] escapes what's written. It offers the window size (NAWS) and terminal type, and
 * lets the server echo and suppress go-ahead.
 */
class Telnet(host: String, port: Int, private val terminalType: String) : Closeable {
    private val socket = Socket()
    private val raw: InputStream
    private val rawOut: OutputStream
    private val writeLock = Any()
    @Volatile private var columns = 80
    @Volatile private var rows = 24
    private var nawsOn = false

    init {
        socket.connect(InetSocketAddress(host, port), 20_000)
        socket.tcpNoDelay = true
        raw = socket.getInputStream()
        rawOut = socket.getOutputStream()
        send(IAC, WILL, NAWS)
        send(IAC, WILL, TTYPE)
        send(IAC, DO, SGA)
    }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            while (true) {
                val n = read(b, 0, 1)
                if (n < 0) return -1
                if (n == 1) return b[0].toInt() and 0xFF
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            var count = 0
            while (count == 0) {
                val c = raw.read()
                if (c < 0) return -1
                if (c != IAC) {
                    b[off + count++] = c.toByte()
                    // Take what's already here without blocking, stopping at a command.
                    while (count < len && raw.available() > 0) {
                        val d = raw.read()
                        if (d < 0) break
                        if (d == IAC) {
                            if (command()) b[off + count++] = IAC.toByte()
                            break
                        }
                        b[off + count++] = d.toByte()
                    }
                } else {
                    val escaped = command()
                    if (escaped) b[off + count++] = IAC.toByte()
                }
            }
            return count
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val out = java.io.ByteArrayOutputStream(len + 8)
            for (i in off until off + len) {
                val v = b[i].toInt() and 0xFF
                out.write(v)
                if (v == IAC) out.write(IAC)
                // A bare CR is sent as CR NUL.
                if (v == '\r'.code && (i + 1 >= off + len || b[i + 1].toInt() != '\n'.code)) out.write(0)
            }
            synchronized(writeLock) {
                rawOut.write(out.toByteArray())
                rawOut.flush()
            }
        }
    }

    /** Handles a command after IAC. Returns true for an escaped 255 data byte. */
    private fun command(): Boolean {
        when (val verb = raw.read()) {
            IAC -> return true
            DO -> when (val opt = raw.read()) {
                NAWS -> {
                    nawsOn = true
                    sendSize()
                }
                TTYPE -> {}
                else -> send(IAC, WONT, opt)
            }
            DONT -> raw.read()
            WILL -> when (val opt = raw.read()) {
                ECHO, SGA -> send(IAC, DO, opt)
                else -> send(IAC, DONT, opt)
            }
            WONT -> raw.read()
            SB -> subnegotiation()
            -1 -> throw IOException("Closed")
            else -> if (verb < 0) throw IOException("Closed")
        }
        return false
    }

    private fun subnegotiation() {
        val opt = raw.read()
        val data = java.io.ByteArrayOutputStream()
        while (true) {
            val c = raw.read()
            if (c < 0) throw IOException("Closed")
            if (c == IAC) {
                val n = raw.read()
                if (n == SE) break
                data.write(n)
            } else {
                data.write(c)
            }
        }
        val bytes = data.toByteArray()
        // TTYPE SEND is 1.
        if (opt == TTYPE && bytes.isNotEmpty() && bytes[0].toInt() == 1) {
            val name = terminalType.uppercase().toByteArray(Charsets.US_ASCII)
            synchronized(writeLock) {
                rawOut.write(byteArrayOf(IAC.toByte(), SB.toByte(), TTYPE.toByte(), 0) + name + byteArrayOf(IAC.toByte(), SE.toByte()))
                rawOut.flush()
            }
        }
    }

    fun resize(columns: Int, rows: Int) {
        this.columns = columns
        this.rows = rows
        if (nawsOn) sendSize()
    }

    private fun sendSize() {
        val c = columns
        val r = rows
        val body = byteArrayOf((c shr 8).toByte(), c.toByte(), (r shr 8).toByte(), r.toByte())
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(IAC.toByte(), SB.toByte(), NAWS.toByte()))
        for (b in body) {
            out.write(b.toInt() and 0xFF)
            if ((b.toInt() and 0xFF) == IAC) out.write(IAC)
        }
        out.write(byteArrayOf(IAC.toByte(), SE.toByte()))
        synchronized(writeLock) {
            rawOut.write(out.toByteArray())
            rawOut.flush()
        }
    }

    private fun send(vararg bytes: Int) {
        synchronized(writeLock) {
            rawOut.write(ByteArray(bytes.size) { bytes[it].toByte() })
            rawOut.flush()
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        const val IAC = 255
        const val DONT = 254
        const val DO = 253
        const val WONT = 252
        const val WILL = 251
        const val SB = 250
        const val SE = 240
        const val ECHO = 1
        const val SGA = 3
        const val TTYPE = 24
        const val NAWS = 31
    }
}
