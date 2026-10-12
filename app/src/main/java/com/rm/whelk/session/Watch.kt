package com.rm.whelk.session

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rm.whelk.MainActivity
import com.rm.whelk.R
import com.rm.whelk.WhelkApp
import com.rm.whelk.core.AnsiText
import com.rm.whelk.data.Settings
import com.termux.terminal.TerminalSession
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Notifications about sessions you aren't looking at. */
object Alerts {
    private lateinit var context: Context

    /** Whether the app is in front, and which session it's showing. */
    @Volatile var appVisible = false
    @Volatile var shownSession: Int? = null

    fun init(c: Context) {
        context = c.applicationContext
    }

    fun away(s: Session) = !appVisible || shownSession != s.id

    private val lastPosted = HashMap<Int, Long>()

    /**
     * Shows a notification for [s]. A tap opens that session. Each [kind] has its own slot per
     * session. The lock screen shows only that there's an alert, and each slot sounds at most once
     * every [MIN_GAP_MS], so a program can't fill the screen or keep buzzing.
     */
    fun post(s: Session, kind: Int, title: String, text: String) {
        val slot = 1000 + s.id * 8 + kind
        val now = System.currentTimeMillis()
        val quiet = synchronized(lastPosted) {
            val last = lastPosted[slot] ?: 0L
            (now - last < MIN_GAP_MS).also { if (!it) lastPosted[slot] = now }
        }
        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHOW_SESSION)
            .putExtra(MainActivity.EXTRA_SESSION, s.id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val n = NotificationCompat.Builder(context, WhelkApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFC6F24E.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setSilent(quiet)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, WhelkApp.CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setColor(0xFFC6F24E.toInt())
                    .setContentTitle("Session alert")
                    .build(),
            )
            .setContentIntent(PendingIntent.getActivity(context, s.id * 8 + kind, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(slot, n) }
    }

    /** Clears a session's notifications, when it's opened. */
    fun clear(s: Session) {
        val nm = NotificationManagerCompat.from(context)
        for (k in 0 until 8) nm.cancel(1000 + s.id * 8 + k)
    }

    private const val MIN_GAP_MS = 10_000L

    const val KIND_ACTIVITY = 0
    const val KIND_QUIET = 1
    const val KIND_WORD = 2
    const val KIND_BELL = 3
    const val KIND_PROGRAM = 4
    const val KIND_DROPPED = 5
}

/**
 * Watches a session's output, on its reader thread: writes the log, and posts notifications for new
 * output, words to watch for, and output stopping.
 */
class Watch(private val session: Session) : TerminalSession.OutputListener {
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val pending = ByteBuffer.allocate(16)
    private val recent = StringBuilder()
    private val wordText = AnsiText()

    @Volatile var lastOutput = 0L
        private set
    /** Output came after the user last looked, so going quiet is worth telling. */
    @Volatile var quietPending = false
    @Volatile var activityShown = false

    private var log: OutputStream? = null
    private var logText: AnsiText? = null
    @Volatile var logging: String? = null
        private set

    override fun onOutput(data: ByteArray, offset: Int, count: Int) {
        lastOutput = System.currentTimeMillis()
        val text = decode(data, offset, count)
        synchronized(this) {
            log?.let { out ->
                runCatching { out.write(logText!!.feed(text).toByteArray()) }.onFailure { stopLog() }
            }
        }
        if (!Alerts.away(session)) return
        if (session.notifyQuiet.value) quietPending = true
        if (session.notifyActivity.value && !activityShown) {
            activityShown = true
            Alerts.post(session, Alerts.KIND_ACTIVITY, session.title, "New output")
        }
        val words = session.connection?.watch.orEmpty()
        if (words.isNotEmpty()) {
            val plain = wordText.feed(text) + wordText.rest()
            recent.append(plain)
            if (recent.length > 400) recent.delete(0, recent.length - 400)
            for (w in words) {
                val i = recent.indexOf(w, ignoreCase = true)
                if (i >= 0) {
                    val line = recent.substring(maxOf(0, recent.lastIndexOf('\n', i) + 1)).substringBefore('\n').trim()
                    Alerts.post(session, Alerts.KIND_WORD, "“$w” in ${session.title}", line)
                    recent.setLength(0)
                    break
                }
            }
        }
    }

    /** Called about once a second: tells when output has stopped for a while. */
    fun tick(now: Long) {
        if (quietPending && now - lastOutput > QUIET_MS && Alerts.away(session)) {
            quietPending = false
            Alerts.post(session, Alerts.KIND_QUIET, session.title, "Output has stopped")
        }
    }

    /** The user is looking at the session again. */
    fun seen() {
        activityShown = false
        quietPending = false
    }

    private fun decode(data: ByteArray, offset: Int, count: Int): String {
        val bytes = ByteBuffer.allocate(pending.position() + count)
        pending.flip()
        bytes.put(pending)
        pending.clear()
        bytes.put(data, offset, count)
        bytes.flip()
        val chars = CharBuffer.allocate(bytes.remaining() + 1)
        decoder.decode(bytes, chars, false)
        // A character split between reads waits for the rest.
        if (bytes.remaining() in 1..15) pending.put(bytes)
        chars.flip()
        return chars.toString()
    }

    /** Starts writing the session's output as plain text to [uri]. */
    fun startLog(context: Context, uri: Uri, name: String) {
        val out = context.contentResolver.openOutputStream(uri, "wa") ?: return
        synchronized(this) {
            stopLog()
            log = out
            logText = AnsiText()
            logging = name
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
            runCatching { out.write("--- ${session.title}, $stamp ---\n".toByteArray()) }
        }
    }

    /** Carries on writing [old]'s log, for a reconnected session. */
    fun adoptLog(old: Watch) {
        synchronized(old) {
            val out = old.log ?: return
            synchronized(this) {
                log = out
                logText = old.logText
                logging = old.logging
            }
            old.log = null
            old.logText = null
            old.logging = null
        }
        runCatching { log?.write("--- reconnected ---\n".toByteArray()) }
    }

    fun stopLog() {
        synchronized(this) {
            val out = log ?: return
            runCatching {
                out.write((logText?.rest().orEmpty() + "\n").toByteArray())
                out.close()
            }
            log = null
            logText = null
            logging = null
        }
    }

    companion object {
        const val QUIET_MS = 5_000L
    }
}
