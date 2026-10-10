package com.rm.whelk.core

import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

@Serializable
enum class ProxyType(val label: String) { NONE("None"), HTTP("HTTP"), SOCKS5("SOCKS5"), SOCKS4("SOCKS4") }

/** A proxy to reach the server through. */
@Serializable
data class ProxySettings(
    val type: ProxyType = ProxyType.NONE,
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    val password: String = "",
)

object Proxies {
    /** A socket to [host]:[port] through the proxy, ready to talk to the server. */
    fun connect(p: ProxySettings, host: String, port: Int, timeout: Int = 20_000): Socket {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(p.host, p.port), timeout)
            s.soTimeout = timeout
            when (p.type) {
                ProxyType.HTTP -> http(s, p, host, port)
                ProxyType.SOCKS5 -> socks5(s, p, host, port)
                ProxyType.SOCKS4 -> socks4(s, p, host, port)
                ProxyType.NONE -> throw IllegalArgumentException("No proxy")
            }
            s.soTimeout = 0
            return s
        } catch (e: Exception) {
            runCatching { s.close() }
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    private fun http(s: Socket, p: ProxySettings, host: String, port: Int) {
        val target = if (host.contains(':')) "[$host]:$port" else "$host:$port"
        val req = StringBuilder("CONNECT $target HTTP/1.1\r\nHost: $target\r\n")
        if (p.username.isNotEmpty()) {
            val auth = Base64.getEncoder().encodeToString("${p.username}:${p.password}".toByteArray())
            req.append("Proxy-Authorization: Basic $auth\r\n")
        }
        req.append("\r\n")
        s.getOutputStream().write(req.toString().toByteArray(Charsets.ISO_8859_1))
        // Read the status line and headers byte by byte, so nothing past them is taken.
        val input = s.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val c = input.read()
            if (c < 0) throw IOException("The proxy closed the connection")
            head.append(c.toChar())
            if (head.length > 16_384) throw IOException("The proxy's answer is too long")
        }
        val status = head.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull()
        if (status != 200) throw IOException("The proxy said: " + head.lineSequence().first().trim())
    }

    private fun socks5(s: Socket, p: ProxySettings, host: String, port: Int) {
        val out = s.getOutputStream()
        val input = DataInputStream(s.getInputStream())
        val withLogin = p.username.isNotEmpty()
        out.write(if (withLogin) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        if (input.readUnsignedByte() != 5) throw IOException("Not a SOCKS5 proxy")
        when (input.readUnsignedByte()) {
            0 -> {}
            2 -> {
                val u = p.username.toByteArray()
                val w = p.password.toByteArray()
                out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(w.size.toByte()) + w)
                input.readUnsignedByte()
                if (input.readUnsignedByte() != 0) throw IOException("The proxy turned down the login")
            }
            else -> throw IOException("The proxy wants a login Whelk can't give")
        }
        val h = host.toByteArray()
        out.write(byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h + byteArrayOf((port shr 8).toByte(), port.toByte()))
        if (input.readUnsignedByte() != 5) throw IOException("Not a SOCKS5 proxy")
        val code = input.readUnsignedByte()
        input.readUnsignedByte()
        val skip = when (input.readUnsignedByte()) {
            1 -> 4
            4 -> 16
            3 -> input.readUnsignedByte()
            else -> throw IOException("Odd answer from the proxy")
        }
        input.readFully(ByteArray(skip + 2))
        if (code != 0) throw IOException("The proxy couldn't connect (SOCKS error $code)")
    }

    private fun socks4(s: Socket, p: ProxySettings, host: String, port: Int) {
        // SOCKS4a: 0.0.0.1 as the address, with the host name after the user id.
        val out = s.getOutputStream()
        out.write(
            byteArrayOf(4, 1, (port shr 8).toByte(), port.toByte(), 0, 0, 0, 1) + p.username.toByteArray() + byteArrayOf(0) +
                host.toByteArray() + byteArrayOf(0)
        )
        val reply = ByteArray(8)
        DataInputStream(s.getInputStream()).readFully(reply)
        if (reply[1].toInt() != 90) throw IOException("The proxy couldn't connect")
    }
}
