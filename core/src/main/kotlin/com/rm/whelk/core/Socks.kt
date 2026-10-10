package com.rm.whelk.core

import net.schmizz.sshj.connection.channel.direct.DirectConnection
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * A SOCKS 4, 4a and 5 proxy (CONNECT only, no login) that opens each connection through [open],
 * a direct-tcpip channel on the SSH connection.
 */
class SocksServer(
    private val bindHost: String,
    private val bindPort: Int,
    private val open: (host: String, port: Int) -> DirectConnection,
) : Closeable {
    private val server = ServerSocket()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "Socks").apply { isDaemon = true } }
    @Volatile private var closed = false

    val port: Int get() = server.localPort

    fun start() {
        server.reuseAddress = true
        server.bind(InetSocketAddress(bindHost, bindPort))
        val accept = Thread({
            while (!closed) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                pool.execute { serve(socket) }
            }
        }, "SocksAccept-$bindPort")
        accept.isDaemon = true
        accept.start()
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.tcpNoDelay = true
                val input = DataInputStream(s.getInputStream())
                val output = s.getOutputStream()
                when (input.readUnsignedByte()) {
                    5 -> socks5(input, output)
                    4 -> socks4(input, output)
                    else -> return
                }?.let { channel -> pipe(s, channel) }
            } catch (e: IOException) {
                // The client or the server went away.
            }
        }
    }

    private fun socks5(input: DataInputStream, output: OutputStream): DirectConnection? {
        val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        if (!methods.contains(0)) {
            output.write(byteArrayOf(5, 0xFF.toByte()))
            return null
        }
        output.write(byteArrayOf(5, 0))
        if (input.readUnsignedByte() != 5) return null
        val command = input.readUnsignedByte()
        input.readUnsignedByte()
        val host = when (input.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress
            3 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }, Charsets.US_ASCII)
            4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress
            else -> return null
        }
        val port = input.readUnsignedShort()
        if (command != 1) {
            output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
            return null
        }
        val channel = try {
            open(host, port)
        } catch (e: IOException) {
            output.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
            return null
        }
        output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        output.flush()
        return channel
    }

    private fun socks4(input: DataInputStream, output: OutputStream): DirectConnection? {
        val command = input.readUnsignedByte()
        val port = input.readUnsignedShort()
        val ip = ByteArray(4).also { input.readFully(it) }
        readNulTerminated(input)
        // 0.0.0.x with x non-zero means SOCKS 4a: the host name follows.
        val host = if (ip[0] == 0.toByte() && ip[1] == 0.toByte() && ip[2] == 0.toByte() && ip[3] != 0.toByte()) {
            readNulTerminated(input)
        } else {
            InetAddress.getByAddress(ip).hostAddress
        }
        if (command != 1) {
            output.write(byteArrayOf(0, 91, 0, 0, 0, 0, 0, 0))
            return null
        }
        val channel = try {
            open(host, port)
        } catch (e: IOException) {
            output.write(byteArrayOf(0, 91, 0, 0, 0, 0, 0, 0))
            return null
        }
        output.write(byteArrayOf(0, 90, 0, 0, 0, 0, 0, 0))
        output.flush()
        return channel
    }

    private fun readNulTerminated(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b <= 0) break
            if (sb.length > 255) throw IOException("Too long")
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun pipe(socket: Socket, channel: DirectConnection) {
        channel.use { ch ->
            val up = Thread({
                try {
                    copy(socket.getInputStream(), ch.outputStream)
                } catch (e: IOException) {
                    // Done.
                } finally {
                    runCatching { ch.outputStream.close() } // sends EOF
                }
            }, "SocksUp")
            up.isDaemon = true
            up.start()
            try {
                copy(ch.inputStream, socket.getOutputStream())
            } catch (e: IOException) {
                // Done.
            }
            runCatching { socket.shutdownOutput() }
            up.join(2000)
        }
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = from.read(buf)
            if (n < 0) return
            to.write(buf, 0, n)
            to.flush()
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        pool.shutdownNow()
    }
}
