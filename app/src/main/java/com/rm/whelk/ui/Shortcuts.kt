package com.rm.whelk.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.rm.whelk.MainActivity
import com.rm.whelk.R
import com.rm.whelk.core.Connection
import com.rm.whelk.core.Vault

/** Launcher shortcuts: the connections used most recently, and ones pinned to the home screen. */
object Shortcuts {
    private const val RECENT = 4

    private fun shortcut(context: Context, vault: Vault, c: Connection): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_CONNECT)
            .putExtra(MainActivity.EXTRA_CONNECTION, c.id)
        val title = c.title(vault)
        return ShortcutInfoCompat.Builder(context, "c-" + c.id)
            .setShortLabel(title.take(24))
            .setLongLabel(title)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
    }

    /** Keeps the long-press shortcuts on the app icon to the recent connections. */
    fun update(context: Context, vault: Vault) {
        runCatching {
            val recent = vault.liveConnections().filter { it.lastUsed > 0 }.sortedByDescending { it.lastUsed }.take(RECENT)
            ShortcutManagerCompat.setDynamicShortcuts(context, recent.map { shortcut(context, vault, it) })
            // Pinned ones for deleted connections stop working; the launcher greys them out.
            val gone = vault.connections.filter { it.deleted }.map { "c-" + it.id }
            if (gone.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, gone, "That connection is gone")
        }
    }

    fun canPin(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    fun pin(context: Context, vault: Vault, c: Connection) {
        ShortcutManagerCompat.requestPinShortcut(context, shortcut(context, vault, c), null)
    }
}
