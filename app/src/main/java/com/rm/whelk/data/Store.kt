package com.rm.whelk.data

import android.content.Context
import android.util.Log
import com.rm.whelk.core.Connection
import com.rm.whelk.core.HostKeyStore
import com.rm.whelk.core.KnownHost
import com.rm.whelk.core.Vault
import com.rm.whelk.core.now
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors

/**
 * The vault, held in memory and saved whole to vault.bin on every change, encrypted by [Secure]. Changes go through [update] on any thread; saving happens on a
 * single background thread in order.
 */
object Store {
    private const val TAG = "Store"
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
            Vault.fromJson(Secure.open(file.readBytes()).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            // Keep the unreadable file aside rather than writing over it.
            Log.e(TAG, "Can't read the vault", e)
            file.renameTo(File(file.parentFile, "vault-unreadable-${System.currentTimeMillis()}.bin"))
            Vault()
        }
    }

    private fun save(vault: Vault) {
        try {
            Secure.write(file, vault.toJson().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "Can't save the vault", e)
        }
    }
}
