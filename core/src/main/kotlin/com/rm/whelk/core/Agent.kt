package com.rm.whelk.core

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.channel.AbstractChannel
import net.schmizz.sshj.connection.channel.forwarded.AbstractForwardedChannel
import net.schmizz.sshj.connection.channel.forwarded.AbstractForwardedChannelOpener
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.security.PrivateKey
import java.security.PublicKey

/** A key the agent can sign with. [blob] is the public key or certificate in SSH wire format. */
class AgentKey(val blob: ByteArray, val comment: String, val privateKey: PrivateKey, val publicKey: PublicKey)

/** Where the forwarded agent gets its keys, and asks before each use. */
interface AgentKeys {
    fun keys(): List<AgentKey>

    /** Whether [key] may sign for the server. Blocks while the user answers. */
    fun allow(key: AgentKey): Boolean
}

/**
 * The SSH agent protocol, enough for a forwarded agent: listing keys and signing. Everything else,
 * like adding or removing keys, is refused.
 */
class AgentProtocol(private val keys: AgentKeys) {
    /** Answers one request (type byte and body, without the length) with a response in the same form. */
    fun handle(request: ByteArray): ByteArray {
        if (request.isEmpty()) return failure()
        val body = Buffer.PlainBuffer(request.copyOfRange(1, request.size))
        return try {
            when (request[0].toInt()) {
                REQUEST_IDENTITIES -> identities()
                SIGN_REQUEST -> sign(body.readBytes(), body.readBytes(), body.readUInt32AsInt())
                else -> failure()
            }
        } catch (e: Exception) {
            failure()
        }
    }

    private fun identities(): ByteArray {
        val list = keys.keys()
        val b = Buffer.PlainBuffer().putByte(IDENTITIES_ANSWER.toByte()).putUInt32(list.size.toLong())
        for (k in list) b.putBytes(k.blob).putString(k.comment)
        return b.compactData
    }

    private fun sign(blob: ByteArray, data: ByteArray, flags: Int): ByteArray {
        val key = keys.keys().firstOrNull { it.blob.contentEquals(blob) } ?: return failure()
        if (!keys.allow(key)) return failure()
        val name = when (KeyType.fromKey(key.privateKey)) {
            KeyType.RSA -> when {
                flags and RSA_SHA2_512 != 0 -> "rsa-sha2-512"
                flags and RSA_SHA2_256 != 0 -> "rsa-sha2-256"
                else -> "ssh-rsa"
            }
            else -> KeyType.fromKey(key.publicKey).toString()
        }
        val algorithm = Factory.Named.Util.create(SshConnection.config(false, legacy = true).keyAlgorithms, name) ?: return failure()
        val signature = algorithm.newSignature()
        signature.initSign(key.privateKey)
        signature.update(data)
        val encoded = signature.encode(signature.sign())
        val sig = Buffer.PlainBuffer().putString(name).putBytes(encoded).compactData
        return Buffer.PlainBuffer().putByte(SIGN_RESPONSE.toByte()).putBytes(sig).compactData
    }

    private fun failure() = byteArrayOf(FAILURE.toByte())

    /** Serves requests from the stream until it closes. */
    fun serve(input: java.io.InputStream, output: java.io.OutputStream) {
        val data = DataInputStream(input)
        while (true) {
            val length = try {
                data.readInt()
            } catch (e: EOFException) {
                return
            }
            if (length <= 0 || length > 256 * 1024) return
            val request = ByteArray(length).also { data.readFully(it) }
            val response = handle(request)
            val framed = Buffer.PlainBuffer().putUInt32(response.size.toLong()).putRawBytes(response).compactData
            output.write(framed)
            output.flush()
        }
    }

    companion object {
        const val FAILURE = 5
        const val REQUEST_IDENTITIES = 11
        const val IDENTITIES_ANSWER = 12
        const val SIGN_REQUEST = 13
        const val SIGN_RESPONSE = 14
        const val RSA_SHA2_256 = 2
        const val RSA_SHA2_512 = 4
    }
}

/** Takes the agent channels the server opens back to us, and serves the agent protocol on each. */
class AgentForwarder(conn: Connection, private val keys: AgentKeys) : AbstractForwardedChannelOpener(TYPE, conn) {

    private class AgentChannel(conn: Connection, recipient: Int, winSize: Long, maxPacket: Long) :
        AbstractForwardedChannel(conn, TYPE, recipient, winSize, maxPacket, "", 0)

    override fun handleOpen(buf: SSHPacket) {
        val channel = AgentChannel(conn, buf.readUInt32AsInt(), buf.readUInt32(), buf.readUInt32())
        callListener({ c ->
            c.confirm()
            try {
                AgentProtocol(keys).serve(c.inputStream, c.outputStream)
            } catch (e: IOException) {
                // The server closed it.
            } finally {
                runCatching { c.close() }
            }
        }, channel)
    }

    companion object {
        const val TYPE = "auth-agent@openssh.com"

        /** Asks the server to forward the agent for this session. sshj keeps channel requests to itself. */
        fun request(session: Session) {
            val send = AbstractChannel::class.java.getDeclaredMethod(
                "sendChannelRequest", String::class.java, Boolean::class.javaPrimitiveType, Buffer.PlainBuffer::class.java,
            )
            send.isAccessible = true
            send.invoke(session, "auth-agent-req@openssh.com", false, Buffer.PlainBuffer())
        }
    }
}
