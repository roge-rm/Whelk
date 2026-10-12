package com.rm.whelk.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.rm.whelk.core.Sealed
import com.rm.whelk.core.Vault
import com.rm.whelk.core.merge
import com.rm.whelk.core.newId
import com.rm.whelk.core.now
import com.rm.whelk.core.teamPart
import com.rm.whelk.core.withSecretsFrom
import com.rm.whelk.core.withoutSecrets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.concurrent.Executors

/**
 * A sync file in a folder the user picked: the whole vault, or for a team, one group and what it
 * uses. Kept on this device only, in sync.bin.
 */
@Serializable
data class SyncTarget(
    val id: String = newId(),
    /** A tree URI from the system folder picker. */
    val folder: String,
    val folderName: String = "",
    val file: String,
    val passphrase: String,
    val team: Boolean = false,
    /** The shared group, for a team. Null until a joined team file is first read. */
    val groupId: String? = null,
    val lastSync: Long = 0,
    val lastError: String? = null,
)

/**
 * Two way sync through encrypted files. Each round reads the file (and any conflicted copies a
 * sync app left beside it), merges them into the vault, then writes the merged result back if it
 * differs. Runs on one background thread, a few seconds after each change and when the app opens.
 */
object Sync {
    private const val TAG = "Sync"
    private const val DELAY_MS = 5_000L
    private lateinit var appContext: Context
    private lateinit var file: File
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Sync") }
    private val main = Handler(Looper.getMainLooper())
    private val serializer = ListSerializer(SyncTarget.serializer())

    private val state = MutableStateFlow<List<SyncTarget>>(emptyList())
    val targets: StateFlow<List<SyncTarget>> = state
    val running = MutableStateFlow(false)

    private val later = Runnable { syncAll() }

    fun init(context: Context) {
        appContext = context.applicationContext
        file = File(appContext.filesDir, "sync.bin")
        state.value = try {
            if (file.exists()) Vault.json.decodeFromString(serializer, Secure.open(file.readBytes()).toString(Charsets.UTF_8)) else emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Can't read sync settings", e)
            emptyList()
        }
        Store.addListener { schedule() }
    }

    /** Syncs a few seconds from now, so a run of changes goes out together. */
    fun schedule() {
        if (state.value.isEmpty()) return
        main.removeCallbacks(later)
        main.postDelayed(later, DELAY_MS)
    }

    fun syncAll() {
        main.removeCallbacks(later)
        worker.execute {
            running.value = true
            try {
                for (t in state.value) syncOne(t)
            } finally {
                running.value = false
            }
        }
    }

    fun add(t: SyncTarget) {
        change { it + t }
        syncAll()
    }

    fun remove(id: String) = change { list -> list.filter { it.id != id } }

    private fun change(f: (List<SyncTarget>) -> List<SyncTarget>) {
        synchronized(this) {
            val next = f(state.value)
            state.value = next
            try {
                Secure.write(file, Vault.json.encodeToString(serializer, next).toByteArray(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.e(TAG, "Can't save sync settings", e)
            }
        }
    }

    private fun record(id: String, f: (SyncTarget) -> SyncTarget) = change { list -> list.map { if (it.id == id) f(it) else it } }

    private fun syncOne(t: SyncTarget) {
        try {
            val dir = DocumentFile.fromTreeUri(appContext, Uri.parse(t.folder))
                ?: throw IllegalStateException("Can't open the folder")
            if (!dir.canWrite()) throw IllegalStateException("No access to the folder. Pick it again.")
            val stem = t.file.substringBeforeLast('.')
            val files = dir.listFiles().filter { f ->
                val n = f.name ?: return@filter false
                n == t.file || (n.startsWith(stem) && n.contains("conflict", ignoreCase = true))
            }
            val main = files.firstOrNull { it.name == t.file }
            val conflicts = files.filter { it.name != t.file }

            var remote = Vault()
            val unreadable = HashSet<String>()
            for (f in files) {
                try {
                    val bytes = appContext.contentResolver.openInputStream(f.uri)!!.use { it.readBytes() }
                    remote = remote.merge(Vault.fromJson(Sealed.open(bytes, t.passphrase.toCharArray()).toString(Charsets.UTF_8)))
                } catch (e: Exception) {
                    // A conflict copy that won't open is left alone; only the main file has to read.
                    if (f == main) throw e
                    f.name?.let { unreadable += it }
                }
            }

            var groupId = t.groupId
            if (t.team && groupId == null) {
                groupId = remote.groups.firstOrNull { !it.deleted }?.id ?: throw IllegalStateException("No shared group in that file")
                record(t.id) { it.copy(groupId = groupId) }
            }

            val incoming = if (t.team) remote.teamPart(groupId!!) else remote
            if (t.team) TeamReview.noteChanges(Store.vault.value, incoming)
            // Items from a copy without secrets get this phone's back.
            Store.update { it.merge(incoming).withSecretsFrom(it) }

            val local = Store.vault.value
            val out = (if (t.team) local.teamPart(groupId!!) else local)
                .let { if (Settings.value.secretsStayHere) it.withoutSecrets() else it }
            if (main == null || conflicts.isNotEmpty() || out.normal() != remote.normal()) {
                val sealed = Sealed.seal(out.toJson().toByteArray(Charsets.UTF_8), t.passphrase.toCharArray())
                val target = main ?: dir.createFile("application/octet-stream", t.file)
                    ?: throw IllegalStateException("Can't make ${t.file}")
                appContext.contentResolver.openOutputStream(target.uri, "wt")!!.use { it.write(sealed) }
                // The conflicted copies are merged in now.
                for (c in conflicts) if (c.name !in unreadable) runCatching { c.delete() }
            }
            record(t.id) { it.copy(lastSync = now(), lastError = null) }
        } catch (e: Exception) {
            // Parse errors quote the decrypted text, so only their kind is logged and shown.
            val parse = e is kotlinx.serialization.SerializationException || e is IllegalArgumentException
            if (parse) Log.w(TAG, "Sync failed: ${e.javaClass.simpleName}") else Log.w(TAG, "Sync failed", e)
            val message = when (e) {
                is Sealed.WrongPassphrase -> "Wrong passphrase, or the file is damaged"
                is SecurityException -> "No access to the folder. Pick it again."
                else -> if (parse) "The sync file couldn't be read" else e.message ?: e.javaClass.simpleName
            }
            record(t.id) { it.copy(lastError = message) }
        }
    }

    /** The vault with every list in id order, to compare two copies. */
    private fun Vault.normal() = Vault(
        connections = connections.sortedBy { it.id },
        identities = identities.sortedBy { it.id },
        groups = groups.sortedBy { it.id },
        snippets = snippets.sortedBy { it.id },
        forwards = forwards.sortedBy { it.id },
        knownHosts = knownHosts.sortedBy { it.id },
    )
}
