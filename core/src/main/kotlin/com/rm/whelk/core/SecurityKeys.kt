package com.rm.whelk.core

import net.schmizz.sshj.common.Buffer
import java.security.SecureRandom
import java.util.Base64

/**
 * OpenSSH FIDO security keys (sk-ssh-ed25519 and sk-ecdsa-sha2-nistp256). The secret stays on the
 * security key; the key file holds the public key, the application ("ssh:"), the flags and the
 * credential id the security key needs back to sign. See OpenSSH's PROTOCOL.u2f.
 */
object SecurityKeys {
    const val ED25519 = "sk-ssh-ed25519@openssh.com"
    const val ECDSA = "sk-ecdsa-sha2-nistp256@openssh.com"
    const val APPLICATION = "ssh:"

    /** Touch the key for every use. */
    const val FLAG_USER_PRESENCE: Byte = 0x01
    /** Enter the security key's PIN for every use. */
    const val FLAG_USER_VERIFICATION: Byte = 0x04

    /** COSE algorithm numbers for making a key. */
    const val COSE_EDDSA = -8
    const val COSE_ES256 = -7

    private val random = SecureRandom()

    /**
     * The key file and public key line for a new credential. [publicKey] is the raw 32 byte
     * Ed25519 key, or the 65 byte uncompressed P-256 point for ECDSA.
     */
    fun keyFile(type: String, publicKey: ByteArray, keyHandle: ByteArray, flags: Byte, comment: String, application: String = APPLICATION): KeyText {
        val pub = Buffer.PlainBuffer().putString(type)
        if (type == ECDSA) pub.putString("nistp256")
        pub.putBytes(publicKey).putString(application)
        val publicBlob = pub.compactData

        val check = random.nextInt().toLong() and 0xFFFFFFFFL
        val secret = Buffer.PlainBuffer().putUInt32(check).putUInt32(check).putString(type)
        if (type == ECDSA) secret.putString("nistp256")
        secret.putBytes(publicKey).putString(application).putByte(flags).putBytes(keyHandle).putString("").putString(comment)
        var pad = 1
        while (secret.available() % 8 != 0) secret.putByte((pad++).toByte())
        val body = Buffer.PlainBuffer()
            .putRawBytes("openssh-key-v1".toByteArray() + byteArrayOf(0))
            .putString("none").putString("none").putString("")
            .putUInt32(1)
            .putBytes(publicBlob)
            .putBytes(secret.compactData)
        val b64 = Base64.getEncoder().encodeToString(body.compactData)
        val pem = buildString {
            append("-----BEGIN OPENSSH PRIVATE KEY-----\n")
            b64.chunked(70).forEach { append(it).append('\n') }
            append("-----END OPENSSH PRIVATE KEY-----\n")
        }
        val line = "$type ${Base64.getEncoder().encodeToString(publicBlob)}" + if (comment.isEmpty()) "" else " $comment"
        return KeyText(pem, line)
    }

    /** Whether a private key file is for a security key. */
    fun isSecurityKey(publicLine: String?): Boolean = publicLine?.startsWith("sk-") == true

    /** Flags and counter from authenticator data: the rp id hash (32 bytes), flags, then a 4 byte counter. */
    fun flagsAndCounter(authData: ByteArray): Pair<Byte, Long> {
        require(authData.size >= 37) { "Short authenticator data" }
        val counter = ((authData[33].toLong() and 0xFF) shl 24) or ((authData[34].toLong() and 0xFF) shl 16) or
            ((authData[35].toLong() and 0xFF) shl 8) or (authData[36].toLong() and 0xFF)
        return authData[32] to counter
    }
}
