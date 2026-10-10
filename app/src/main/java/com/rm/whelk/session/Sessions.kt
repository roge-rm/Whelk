package com.rm.whelk.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import com.rm.whelk.core.Connection
import com.rm.whelk.core.ConnectionType
import com.rm.whelk.core.ForwardKind
import com.rm.whelk.core.Paste
import com.rm.whelk.core.PortForward
import com.rm.whelk.core.SshConnection
import com.rm.whelk.data.Settings
import com.rm.whelk.data.Store
import com.rm.whelk.ui.TerminalThemes
import com.rm.whelk.widget.ForwardWidget
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.util.concurrent.Executors

enum class SessionState { CONNECTING, OPEN, CLOSED }

/** One open terminal: a connection (or the local shell) and what it's running. */
class Session(
    val id: Int,
    val connection: Connection?,
    val title: String,
) {
    lateinit var terminal: TerminalSession
        internal set
    val state = MutableStateFlow(SessionState.CONNECTING)

    /** The SSH login, once connected, for port forwards. */
    @Volatile var ssh: SshConnection? = null
        internal set

    /** Port forwards running on this session, by forward id. */
    val forwards = MutableStateFlow<Map<String, Closeable>>(emptyMap())
    val forwardErrors = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Ids of the forwards to start once connected. */
    internal val autoForwards: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    fun startForward(f: PortForward) {
        val c = ssh ?: return
        Sessions.io.execute {
            try {
                val handle = when (f.kind) {
                    ForwardKind.LOCAL -> c.localForward(f.bindHost, f.bindPort, f.destHost, f.destPort)
                    ForwardKind.REMOTE -> c.remoteForward(f.bindHost, f.bindPort, f.destHost, f.destPort)
                    ForwardKind.DYNAMIC -> c.dynamicForward(f.bindHost, f.bindPort)
                }
                forwards.update { it + (f.id to handle) }
                forwardErrors.update { it - f.id }
                Sessions.forwardsChanged()
            } catch (e: Exception) {
                forwardErrors.update { it + (f.id to (e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun stopForward(id: String) {
        val handle = forwards.value[id] ?: return
        forwards.update { it - id }
        Sessions.io.execute { runCatching { handle.close() } }
        Sessions.forwardsChanged()
    }

    internal fun stopAllForwards() {
        val all = forwards.value.values.toList()
        forwards.value = emptyMap()
        all.forEach { runCatching { it.close() } }
        if (all.isNotEmpty()) Sessions.forwardsChanged()
    }

    /** Types text into the session as if from the keyboard. */
    fun type(text: String) {
        Sessions.main.post { if (terminal.isRunning) terminal.write(text) }
    }
}

/** The open sessions, for the whole app. Sessions outlive the screens, kept alive by [SessionService]. */
object Sessions {
    internal val main = Handler(Looper.getMainLooper())
    internal val io = Executors.newCachedThreadPool { Thread(it, "SessionIO").apply { isDaemon = true } }

    private val state = MutableStateFlow<List<Session>>(emptyList())
    val list: StateFlow<List<Session>> = state

    private var nextId = 1
    private lateinit var appContext: Context

    /** Called when the screen of a session changes, to redraw the view showing it. */
    var onScreenUpdate: ((TerminalSession) -> Unit)? = null
    var onTitleChange: ((TerminalSession) -> Unit)? = null

    fun find(id: Int) = state.value.firstOrNull { it.id == id }

    fun of(terminal: TerminalSession) = state.value.firstOrNull { it.terminal === terminal }

    /**
     * Opens a session for [connection], or the local shell when null. A session opened in the
     * [background], with no screen to show it, starts at once at a standard size; otherwise it
     * starts when the view sizes it, so the shell's first prompt is drawn at the right width.
     */
    fun open(context: Context, connection: Connection?, forwards: Set<String> = emptySet(), background: Boolean = forwards.isNotEmpty()): Session {
        appContext = context.applicationContext
        val vault = Store.vault.value
        val type = connection?.type ?: ConnectionType.LOCAL
        val title = connection?.title(vault) ?: "Local shell"
        val session = Session(nextId++, connection, title)
        val rows = Settings.value.scrollback
        session.terminal = when (type) {
            ConnectionType.SSH, ConnectionType.MOSH -> SshTerminal(session, rows, client)
            ConnectionType.TELNET -> TelnetTerminal(session, rows, client)
            ConnectionType.LOCAL -> localShell(appContext, rows)
        }
        session.terminal.mSessionName = title
        if (connection != null) {
            Store.touch(connection)
            vault.liveForwards().filter { it.connectionId == connection.id && it.autoStart }
                .forEach { session.autoForwards += it.id }
            session.autoForwards += forwards
        }
        if (type == ConnectionType.LOCAL) session.state.value = SessionState.OPEN
        state.update { it + session }
        // The emulator takes its colours from the shared scheme when it's made.
        TerminalThemes.apply(TerminalThemes.named(connection?.theme ?: Settings.value.theme))
        if (background) session.terminal.updateSize(80, 24, 1, 1)
        SessionService.update(appContext)
        return session
    }

    /** Disconnects and forgets the session. */
    fun close(session: Session) {
        Prompts.cancel(session.id)
        session.stopAllForwards()
        if (session.terminal.isRunning) session.terminal.finishIfRunning()
        state.update { it - session }
        SessionService.update(appContext)
    }

    fun closeAll() {
        state.value.toList().forEach { close(it) }
    }

    /** Opens the session again after it closed, in the same place in the list. */
    fun reconnect(context: Context, session: Session): Session {
        val fresh = open(context, session.connection)
        state.update { list ->
            val without = list - fresh
            val i = without.indexOf(session)
            if (i < 0) list else without.toMutableList().also { it[i] = fresh }
        }
        session.stopAllForwards()
        SessionService.update(appContext)
        return fresh
    }

    /** Updates the notification and the forward widgets. Safe from any thread. */
    internal fun forwardsChanged() {
        main.post {
            SessionService.update(appContext)
            ForwardWidget.refreshAll(appContext)
        }
    }

    internal fun changed() {
        state.update { it.toList() }
        SessionService.update(appContext)
    }

    /** Cleans text and pastes it, bracketed if the program asked for that. */
    fun paste(session: TerminalSession, raw: String) {
        val text = Paste.clean(raw, Settings.value.straightenPaste)
        if (text.isEmpty()) return
        session.emulator?.paste(text)
    }

    private fun localShell(context: Context, rows: Int): TerminalSession {
        val home = context.filesDir.absolutePath
        val env = arrayOf(
            "TERM=${Settings.value.terminalType}",
            "HOME=$home",
            "PATH=" + (System.getenv("PATH") ?: "/system/bin"),
            "ANDROID_ROOT=" + (System.getenv("ANDROID_ROOT") ?: "/system"),
            "ANDROID_DATA=" + (System.getenv("ANDROID_DATA") ?: "/data"),
            "TMPDIR=" + context.cacheDir.absolutePath,
        )
        return com.termux.terminal.ProcessSession("/system/bin/sh", home, arrayOf("-sh"), env, rows, client)
    }

    val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            onScreenUpdate?.invoke(changedSession)
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            onTitleChange?.invoke(changedSession)
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            val s = of(finishedSession) ?: return
            s.state.value = SessionState.CLOSED
            s.stopAllForwards()
            changed()
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val cm = appContext.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("Terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val cm = appContext.getSystemService(ClipboardManager::class.java)
            val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return
            // The plain text, never the styled or HTML form a web page puts beside it.
            val raw = item.text?.toString()
                ?: item.htmlText?.let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_COMPACT).toString() }
                ?: item.coerceToText(appContext)?.toString()
                ?: return
            paste(session, raw)
        }

        override fun onBell(session: TerminalSession) {
            if (!Settings.value.vibrateOnBell) return
            val v = appContext.getSystemService(Vibrator::class.java) ?: return
            v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }

        override fun onColorsChanged(session: TerminalSession) {
            onScreenUpdate?.invoke(session)
        }

        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String, message: String) { android.util.Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { android.util.Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { android.util.Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { android.util.Log.e(tag, "", e) }
    }
}
