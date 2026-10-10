package com.rm.whelk.session

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rm.whelk.MainActivity
import com.rm.whelk.R
import com.rm.whelk.WhelkApp

/** Keeps the process alive while sessions are open, with a notification listing them. */
class SessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            Sessions.closeAll()
            return START_NOT_STICKY
        }
        val sessions = Sessions.list.value
        if (sessions.isEmpty()) {
            keepAwake(false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(sessions), type)
        keepAwake(com.rm.whelk.data.Settings.value.keepAwake)
        return START_NOT_STICKY
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /** Holds the CPU and Wi-Fi awake while sessions are open, if that's turned on. */
    private fun keepAwake(on: Boolean) {
        if (on && wakeLock == null) {
            wakeLock = getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Whelk:sessions").apply { acquire() }
            val mode = if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else @Suppress("DEPRECATION") android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                ?.createWifiLock(mode, "Whelk:sessions")?.apply { acquire() }
        } else if (!on) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock = null
        }
    }

    override fun onDestroy() {
        keepAwake(false)
        super.onDestroy()
    }

    private fun notification(sessions: List<Session>): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_DISCONNECT_ALL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (sessions.size == 1) sessions[0].title else "${sessions.size} sessions open"
        val forwards = sessions.sumOf { it.forwards.value.size }
        val text = buildList {
            if (sessions.size > 1) add(sessions.joinToString(", ") { it.title })
            if (forwards > 0) add(if (forwards == 1) "1 port forward" else "$forwards port forwards")
            val closed = sessions.count { it.state.value == SessionState.CLOSED }
            if (closed > 0) add("$closed disconnected")
        }.joinToString(" · ")
        return NotificationCompat.Builder(this, WhelkApp.CHANNEL_SESSIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Disconnect all", disconnect)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT_ALL = "com.rm.whelk.DISCONNECT_ALL"

        /** Starts, refreshes or stops the service to match the open sessions. */
        fun update(context: Context) {
            val intent = Intent(context, SessionService::class.java)
            if (Sessions.list.value.isEmpty()) {
                context.stopService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
