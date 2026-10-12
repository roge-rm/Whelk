package com.rm.whelk.data

import android.content.Context
import android.content.SharedPreferences
import com.rm.whelk.core.CancelledException
import com.rm.whelk.core.Connection
import com.rm.whelk.core.Vault
import com.rm.whelk.session.Ask
import com.rm.whelk.session.Prompts

/**
 * Connections a team file changed in a way that could send your saved login somewhere new. Each
 * asks once before it next connects. Kept on this phone only.
 */
object TeamReview {
    private const val KEY = "ids"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("team-review", Context.MODE_PRIVATE)
    }

    private fun ids(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty()

    /**
     * Notes connections in [incoming] that point somewhere new compared with [before]: an existing
     * one whose host, port, identity, jump host, proxy or agent forwarding changed, or a new one
     * that uses an identity that was already here.
     */
    fun noteChanges(before: Vault, incoming: Vault) {
        val changed = incoming.connections.filter { c ->
            if (c.deleted) return@filter false
            val had = before.connection(c.id)
            if (had == null) {
                c.identityId != null && before.identity(c.identityId) != null
            } else {
                c.updated > had.updated && (c.host != had.host || c.port != had.port || c.identityId != had.identityId ||
                    c.viaId != had.viaId || c.proxy != had.proxy || (c.forwardAgent && !had.forwardAgent))
            }
        }.map { it.id }
        if (changed.isNotEmpty()) synchronized(this) { prefs.edit().putStringSet(KEY, ids() + changed).apply() }
    }

    /**
     * Asks before connecting [connection] if a team file changed it or any jump host on the way.
     * Blocks; throws if the user says no.
     */
    fun confirm(promptId: Int, connection: Connection, vault: Vault) {
        var c: Connection? = connection
        val seen = HashSet<String>()
        while (c != null && seen.add(c.id)) {
            confirmOne(promptId, c, vault)
            c = vault.connection(c.viaId)
        }
    }

    private fun confirmOne(promptId: Int, connection: Connection, vault: Vault) {
        if (connection.id !in ids()) return
        val user = vault.identity(connection.identityId)?.username.orEmpty()
        val where = (if (user.isEmpty()) "" else "$user@") + connection.host + if (connection.port != 22) ":${connection.port}" else ""
        val ok = Prompts.ask(
            Ask.Confirm(promptId, "Changed by your team", "${connection.title(vault)} now connects to $where. Use your saved login with it?", "Connect", "Cancel"),
        ) == "yes"
        if (!ok) throw CancelledException()
        synchronized(this) { prefs.edit().putStringSet(KEY, ids() - connection.id).apply() }
    }
}
