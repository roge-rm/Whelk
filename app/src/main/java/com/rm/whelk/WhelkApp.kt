package com.rm.whelk

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.rm.whelk.data.Settings
import com.rm.whelk.data.Store
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class WhelkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android ships a cut down "BC" provider. sshj needs the full one under that name.
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())

        Settings.init(this)
        Store.init(this)

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
