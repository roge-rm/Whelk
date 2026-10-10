package com.rm.whelk.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.rm.whelk.core.Connection
import com.rm.whelk.core.HostKeyStore
import com.rm.whelk.core.KnownHost
import com.rm.whelk.core.Vault
import com.rm.whelk.core.now
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The vault, held in memory and saved whole to vault.bin on every change, encrypted with an AES key
 * kept in the Android Keystore. Changes go through [update] on any thread; saving happens on a
 * single background thread in order.
 */
object Store {
    private const val TAG = "Store"
    private const val KEY_ALIAS = "whelk-vault"
    private lateinit var file: File
    private val saver = Executors.newSingleThreadExecutor { Thread(it, "VaultSaver") }
    private val listeners = mutableListOf<(Vault) -> Unit>()

    private val state = MutableStateFlow(Vault())
    val vault: StateFlow<Vault> = state

    fun init(context: Context) {
        file = File(context.filesDir, "vault.bin")
        state.value = load()
    }

    /** Changes the vault and saves it. */
    fun update(change: (Vault) -> Vault) {
        val next: Vault
        synchronized(this) {
            next = change(state.value)
            if (next == state.value) return
            state.value = next
        }
        saver.execute { save(next) }
        listeners.toList().forEach { it(next) }
    }

    /** Called after each change, for sync. */
    fun addListener(l: (Vault) -> Unit) {
        listeners += l
    }

    fun touch(connection: Connection) = update { it.put(connection.copy(lastUsed = now(), updated = now())) }

    val hostKeys = object : HostKeyStore {
        override fun get(host: String, port: Int): KnownHost? = state.value.knownHost(host, port)
        override fun put(host: String, port: Int, keyType: String, key: String) =
            update { it.put(KnownHost(KnownHost.idFor(host, port), keyType, key)) }
    }

    private fun load(): Vault {
        if (!file.exists()) return Vault()
        return try {
            val data = file.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
            Vault.fromJson(cipher.doFinal(data, 12, data.size - 12).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            // Keep the unreadable file aside rather than writing over it.
            Log.e(TAG, "Can't read the vault", e)
            file.renameTo(File(file.parentFile, "vault-unreadable-${System.currentTimeMillis()}.bin"))
            Vault()
        }
    }

    private fun save(vault: Vault) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val sealed = cipher.iv + cipher.doFinal(vault.toJson().toByteArray(Charsets.UTF_8))
            val tmp = File(file.parentFile, "vault.tmp")
            tmp.outputStream().use {
                it.write(sealed)
                it.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IllegalStateException("rename failed")
        } catch (e: Exception) {
            Log.e(TAG, "Can't save the vault", e)
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
