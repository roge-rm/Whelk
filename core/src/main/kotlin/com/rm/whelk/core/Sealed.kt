package com.rm.whelk.core

import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase encryption for backups and sync files: PBKDF2-HMAC-SHA256 makes an AES-256 key, and
 * AES-GCM encrypts and checks the contents.
 *
 * Layout: "WHELK" and a version byte, the iteration count (4 bytes), a 16 byte salt, a 12 byte
 * nonce, then the ciphertext with its tag. The header is authenticated along with the contents.
 */
object Sealed {
    private val MAGIC = byteArrayOf('W'.code.toByte(), 'H'.code.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'K'.code.toByte(), 1)
    private const val HEADER = 6 + 4 + 16 + 12

    /** About a second on a slow phone, which is fine for something done now and then. */
    const val ITERATIONS = 200_000

    private val random = SecureRandom()

    class WrongPassphrase : IOException("Wrong passphrase, or the file is damaged")

    fun isSealed(data: ByteArray): Boolean =
        data.size > HEADER && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun seal(plain: ByteArray, passphrase: CharArray, iterations: Int = ITERATIONS): ByteArray {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).putInt(iterations).put(salt).put(nonce).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    fun open(data: ByteArray, passphrase: CharArray): ByteArray {
        if (!isSealed(data)) throw IOException("Not a Whelk file")
        val buf = ByteBuffer.wrap(data)
        buf.position(MAGIC.size)
        val iterations = buf.int
        if (iterations < 1000 || iterations > 10_000_000) throw IOException("Not a Whelk file")
        val salt = ByteArray(16).also { buf.get(it) }
        val nonce = ByteArray(12).also { buf.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, nonce))
        cipher.updateAAD(data, 0, HEADER)
        return try {
            cipher.doFinal(data, HEADER, data.size - HEADER)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPassphrase()
        }
    }

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }
}
