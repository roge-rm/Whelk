package com.rm.whelk.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Reconnects sessions that dropped on an error, after a wait that grows with each try, or straight
 * away when the network comes back.
 */
object Reconnect {
    private val DELAYS = longArrayOf(2, 5, 10, 30, 60)
    private const val MAX_TRIES = 8
    private lateinit var context: Context
    private val pending = LinkedHashMap<Int, Pending>()

    private class Pending(val session: Session, var at: Long)

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            for (p in pending.values.toList()) {
                val left = (p.at - now + 999) / 1000
                if (left <= 0) go(p.session) else p.session.reconnecting.value = "Reconnecting in $left s"
            }
            if (pending.isNotEmpty()) Sessions.main.postDelayed(this, 1000)
        }
    }

    fun init(c: Context) {
        context = c.applicationContext
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Give the new network a moment, then try everything waiting.
                    Sessions.main.postDelayed({ for (p in pending.values.toList()) p.at = 0 }, 1500)
                }
            })
        }
    }

    fun schedule(s: Session) {
        if (s.reconnectTries >= MAX_TRIES) {
            s.reconnecting.value = null
            if (Alerts.away(s)) Alerts.post(s, Alerts.KIND_DROPPED, s.title, "Couldn't reconnect")
            return
        }
        val wait = DELAYS[minOf(s.reconnectTries, DELAYS.size - 1)] * 1000
        pending[s.id] = Pending(s, System.currentTimeMillis() + wait)
        Sessions.main.removeCallbacks(tick)
        Sessions.main.post(tick)
    }

    fun cancel(s: Session) {
        pending.remove(s.id)
        s.reconnecting.value = null
    }

    private fun go(s: Session) {
        pending.remove(s.id)
        s.reconnecting.value = null
        s.reconnectTries++
        val fresh = Sessions.reconnect(context, s)
        // A connection that stays up for a minute counts as fixed.
        Sessions.main.postDelayed({ if (fresh.state.value == SessionState.OPEN) fresh.reconnectTries = 0 }, 60_000)
    }
}
