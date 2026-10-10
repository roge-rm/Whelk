package com.rm.whelk

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.rm.whelk.data.Settings
import com.rm.whelk.data.Store
import com.rm.whelk.data.Sync
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class WhelkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android ships a cut down "BC" provider. sshj needs the full one under that name.
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())

        // Setting up sshj and BouncyCastle takes seconds the first time, so do it now rather
        // than when the first connection is made.
        Thread({
            runCatching {
                com.rm.whelk.core.SshConnection.config(false)
                java.security.KeyPairGenerator.getInstance("X25519", "BC").generateKeyPair()
                javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
            }
        }, "WarmUp").apply { priority = Thread.MIN_PRIORITY }.start()

        Settings.init(this)
        Store.init(this)
        Sync.init(this)
        com.rm.whelk.ui.Shortcuts.update(this, Store.vault.value)
        Store.addListener { v -> com.rm.whelk.ui.Shortcuts.update(this, v) }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SESSIONS, "Open sessions", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Session alerts", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CHANNEL_SESSIONS = "sessions"
        const val CHANNEL_ALERTS = "alerts"
    }
}
