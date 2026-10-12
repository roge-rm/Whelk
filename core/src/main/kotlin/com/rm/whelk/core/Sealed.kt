package com.rm.whelk.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase encryption for backups and sync files: Argon2id makes an AES-256 key, and AES-GCM
 * encrypts and checks the contents. The header is authenticated along with the contents.
 *
 * Version 2 layout: "WHELK", 2, memory in KiB (4 bytes), passes (4), lanes (1), a 16 byte salt,
 * a 12 byte nonce, then the ciphertext with its tag.
 * Version 1, still read: "WHELK", 1, PBKDF2-HMAC-SHA256 iterations (4), salt (16), nonce (12).
 */
object Sealed {
    private val MAGIC = byteArrayOf('W'.code.toByte(), 'H'.code.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'K'.code.toByte())
    private const val V1_HEADER = 6 + 4 + 16 + 12
    private const val V2_HEADER = 6 + 4 + 4 + 1 + 16 + 12

    /** 32 MiB and 2 passes: about a second on a slow phone, and costly to guess on a GPU. */
    const val MEMORY_KIB = 32 * 1024
    const val PASSES = 2

    // Limits on what a file's header may ask for, so a crafted file can't tie the phone up.
    private const val MAX_PBKDF2 = 2_000_000
    private const val MAX_MEMORY_KIB = 256 * 1024
    private const val MAX_PASSES = 10
    private const val MAX_LANES = 4

    private val random = SecureRandom()

    /** Keys already worked out, by passphrase, salt and settings, so each sync round doesn't redo it. */
    private val keys = object : LinkedHashMap<String, SecretKeySpec>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SecretKeySpec>?) = size > 16
    }

    class WrongPassphrase : IOException("Wrong passphrase, or the file is damaged")

    fun isSealed(data: ByteArray): Boolean = data.size > V1_HEADER &&
        data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) && (data[5].toInt() == 1 || (data[5].toInt() == 2 && data.size > V2_HEADER))

    fun seal(plain: ByteArray, passphrase: CharArray, memoryKib: Int = MEMORY_KIB, passes: Int = PASSES): ByteArray {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(V2_HEADER).put(MAGIC).put(2).putInt(memoryKib).putInt(passes).put(1).put(salt).put(nonce).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, argon2(passphrase, salt, memoryKib, passes, 1), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    fun open(data: ByteArray, passphrase: CharArray): ByteArray {
        if (!isSealed(data)) throw IOException("Not a Whelk file")
        val buf = ByteBuffer.wrap(data)
        buf.position(MAGIC.size)
        val version = buf.get().toInt()
        val key: SecretKeySpec
        val headerSize: Int
        if (version == 1) {
            val iterations = buf.int
            if (iterations < 1000 || iterations > MAX_PBKDF2) throw IOException("Not a Whelk file")
            val salt = ByteArray(16).also { buf.get(it) }
            key = pbkdf2(passphrase, salt, iterations)
            headerSize = V1_HEADER
        } else {
            val memory = buf.int
            val passes = buf.int
            val lanes = buf.get().toInt()
            if (memory < 8 * 1024 || memory > MAX_MEMORY_KIB || passes !in 1..MAX_PASSES || lanes !in 1..MAX_LANES) {
                throw IOException("Not a Whelk file")
            }
            val salt = ByteArray(16).also { buf.get(it) }
            key = argon2(passphrase, salt, memory, passes, lanes)
            headerSize = V2_HEADER
        }
        val nonce = ByteArray(12).also { buf.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(data, 0, headerSize)
        return try {
            cipher.doFinal(data, headerSize, data.size - headerSize)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPassphrase()
        }
    }

    private fun cacheKey(kind: String, passphrase: CharArray, salt: ByteArray, vararg params: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(String(passphrase).toByteArray(Charsets.UTF_8))
        digest.update(salt)
        return kind + params.joinToString(",", ":", ":") + digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun argon2(passphrase: CharArray, salt: ByteArray, memoryKib: Int, passes: Int, lanes: Int): SecretKeySpec {
        val id = cacheKey("a", passphrase, salt, memoryKib, passes, lanes)
        synchronized(keys) { keys[id] }?.let { return it }
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKib)
            .withIterations(passes)
            .withParallelism(lanes)
            .build()
        val out = ByteArray(32)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(passphrase, out)
        return SecretKeySpec(out, "AES").also { synchronized(keys) { keys[id] = it } }
    }

    private fun pbkdf2(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val id = cacheKey("p", passphrase, salt, iterations)
        synchronized(keys) { keys[id] }?.let { return it }
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES").also { synchronized(keys) { keys[id] = it } }
    }

    /** Version 1 files, for tests that check old files still open. */
    internal fun sealV1(plain: ByteArray, passphrase: CharArray, iterations: Int): ByteArray {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(V1_HEADER).put(MAGIC).put(1).putInt(iterations).put(salt).put(nonce).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, pbkdf2(passphrase, salt, iterations), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }
}
