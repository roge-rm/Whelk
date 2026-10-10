package com.rm.whelk.data

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

/** Locks the app after it's been in the background for the time set in [Prefs.lockAfter]. */
object Lock {
    val locked = MutableStateFlow(false)
    private var backgroundAt = 0L
    private var started = false

    fun onStart() {
        val minutes = Settings.value.lockAfter
        if (minutes < 0) return
        val away = SystemClock.elapsedRealtime() - backgroundAt
        // A fresh start always locks.
        if (!started || away >= minutes * 60_000L) locked.value = true
        started = true
    }

    fun onStop() {
        backgroundAt = SystemClock.elapsedRealtime()
    }

    fun unlock() {
        locked.value = false
    }
}
