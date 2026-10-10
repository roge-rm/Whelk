package com.rm.whelk.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.rm.whelk.core.Paste
import com.rm.whelk.data.Settings
import com.rm.whelk.data.Store
import com.rm.whelk.data.Swipe
import com.rm.whelk.data.VolumeKeys
import com.rm.whelk.session.Session
import com.rm.whelk.session.SessionState
import com.rm.whelk.session.Sessions
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/** Ctrl and Alt from the extra keys, held for the next key. */
class Modifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    /** Locked on by a long press, until tapped again. */
    var ctrlLock by mutableStateOf(false)
    var altLock by mutableStateOf(false)

    fun used() {
        if (!ctrlLock) ctrl = false
        if (!altLock) alt = false
    }
}

@Composable
fun TerminalScreen(nav: Nav, sessionId: Int) {
    val context = LocalContext.current
    val sessions by Sessions.list.collectAsState()
    val prefs by Settings.flow.collectAsState()
    val vault by Store.vault.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId }
    if (session == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val state by session.state.collectAsState()
    val modifiers = remember { Modifiers() }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var menu by remember { mutableStateOf(false) }
    var switcher by remember { mutableStateOf(false) }
    var showSnippets by remember { mutableStateOf(false) }
    var showForwards by remember { mutableStateOf(false) }
    var showLinks by remember { mutableStateOf<List<String>?>(null) }
    var title by remember { mutableStateOf(session.title) }
    var fontSize by remember { mutableIntStateOf(prefs.fontSize) }
    val theme = TerminalThemes.named(session.connection?.theme ?: prefs.theme)

    fun showKeyboard() {
        val v = view ?: return
        v.requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(v, 0)
    }

    fun setFont(sp: Int) {
        val size = sp.coerceIn(6, 40)
        fontSize = size
        view?.setTextSize(spToPx(context, size))
        Settings.update { it.copy(fontSize = size) }
    }

    DisposableEffect(session) {
        Sessions.onScreenUpdate = { t -> if (t === view?.currentSession) view?.onScreenUpdated() }
        Sessions.onTitleChange = { t -> if (t === session.terminal) title = t.title?.takeIf { it.isNotBlank() } ?: session.title }
        onDispose {
            Sessions.onScreenUpdate = null
            Sessions.onTitleChange = null
        }
    }

    Column(Modifier.fillMaxSize().background(Color(theme.background)).statusBarsPadding().navigationBarsPadding().imePadding()) {
        // Top bar: back, the session (tap to switch), and the menu.
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Row(
                Modifier.weight(1f).combinedClickableCompat(onClick = { switcher = true }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (sessions.size > 1) {
                    Text(" ${sessions.indexOf(session) + 1}/${sessions.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch session")
                DropdownMenu(expanded = switcher, onDismissRequest = { switcher = false }) {
                    for (s in sessions) {
                        DropdownMenuItem(
                            text = { Text(s.title + if (s.state.value == SessionState.CLOSED) " (closed)" else "") },
                            onClick = {
                                switcher = false
                                if (s !== session) nav.swap(Screen.Terminal(s.id))
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("New local shell") }, onClick = {
                        switcher = false
                        nav.swap(Screen.Terminal(Sessions.open(context, null).id))
                    })
                    DropdownMenuItem(text = { Text("Connections") }, onClick = { switcher = false; nav.reset() })
                }
            }
            IconButton(onClick = { showSnippets = true }) { Icon(Icons.Default.Edit, contentDescription = "Snippets") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Keyboard") }, onClick = { menu = false; showKeyboard() })
                    DropdownMenuItem(text = { Text("Paste") }, onClick = {
                        menu = false
                        Sessions.client.onPasteTextFromClipboard(session.terminal)
                    })
                    DropdownMenuItem(text = { Text("Open a link") }, onClick = {
                        menu = false
                        showLinks = findLinks(session.terminal)
                    })
                    if (session.connection != null && session.connection.type == com.rm.whelk.core.ConnectionType.SSH) {
                        DropdownMenuItem(text = { Text("Port forwards") }, onClick = { menu = false; showForwards = true })
                    }
                    if (session.connection != null && (session.connection.type == com.rm.whelk.core.ConnectionType.SSH || session.connection.type == com.rm.whelk.core.ConnectionType.MOSH)) {
                        DropdownMenuItem(text = { Text("Files") }, onClick = { menu = false; nav.go(Screen.Files(session.connection.id, session.id)) })
                    }
                    DropdownMenuItem(text = { Text("Bigger text") }, onClick = { setFont(fontSize + 1) })
                    DropdownMenuItem(text = { Text("Smaller text") }, onClick = { setFont(fontSize - 1) })
                    DropdownMenuItem(text = { Text("Reset terminal") }, onClick = { menu = false; session.terminal.reset() })
                    if (state == SessionState.CLOSED) {
                        DropdownMenuItem(text = { Text("Reconnect") }, onClick = {
                            menu = false
                            nav.swap(Screen.Terminal(Sessions.reconnect(context, session).id))
                        })
                    }
                    DropdownMenuItem(text = { Text(if (state == SessionState.CLOSED) "Close" else "Disconnect") }, onClick = {
                        menu = false
                        Sessions.close(session)
                    })
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        // Size first: it makes the renderer that the font is set on.
                        setTextSize(spToPx(ctx, fontSize))
                        setTypeface(Fonts.typeface(ctx, prefs.font))
                        tag = prefs.font
                        keepScreenOn = prefs.keepScreenOn
                        view = this
                    }
                },
                update = { v ->
                    // The view's tag holds the font name it was last given.
                    if (v.tag != prefs.font) {
                        v.setTypeface(Fonts.typeface(v.context, prefs.font))
                        v.tag = prefs.font
                    }
                    v.setTerminalViewClient(ViewClient(v, modifiers, ::showKeyboard, ::setFont, { fontSize }) { right ->
                        // Swiping right goes back to the session before, like turning a page.
                        val i = sessions.indexOf(session)
                        val next = sessions.getOrNull(if (right) i - 1 else i + 1)
                        if (next != null) nav.swap(Screen.Terminal(next.id))
                    })
                    v.keepScreenOn = prefs.keepScreenOn
                    if (v.currentSession !== session.terminal) {
                        v.attachSession(session.terminal)
                        title = session.terminal.title?.takeIf { it.isNotBlank() } ?: session.title
                    }
                    v.setBackgroundColor(theme.background)
                },
                modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp),
            )
            if (state == SessionState.CLOSED) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 4.dp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Disconnected", Modifier.padding(horizontal = 8.dp))
                        Button(onClick = { nav.swap(Screen.Terminal(Sessions.reconnect(context, session).id)) }) { Text("Reconnect") }
                        Gap()
                        OutlinedButton(onClick = { Sessions.close(session) }) { Text("Close") }
                    }
                }
            }
        }

        if (prefs.extraKeys) {
            ExtraKeys(modifiers, onKey = { key -> view?.let { sendKey(it, key, modifiers) } })
        }
    }

    LaunchedEffect(view, session) {
        if (view != null) showKeyboard()
    }

    if (showSnippets) {
        val snippets = vault.liveSnippets().sortedBy { it.name.lowercase() }
        PickerDialog(
            title = "Snippets",
            onDismiss = { showSnippets = false },
            empty = "No snippets yet.",
            items = snippets.map { it.name.ifEmpty { it.text.take(30) } to it.text },
            onPick = { i ->
                val s = snippets[i]
                session.type(if (s.enter) s.text + "\r" else s.text)
                showSnippets = false
            },
            action = "New snippet" to { showSnippets = false; nav.go(Screen.EditSnippet(null)) },
        )
    }

    showLinks?.let { links ->
        PickerDialog(
            title = "Links",
            onDismiss = { showLinks = null },
            empty = "No links on screen.",
            items = links.map { it to "" },
            onPick = { i ->
                showLinks = null
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(links[i])))
                } catch (e: ActivityNotFoundException) {
                    // Nothing to open it with.
                }
            },
        )
    }

    if (showForwards) {
        ForwardsDialog(session, onDismiss = { showForwards = false }, onAdd = {
            showForwards = false
            nav.go(Screen.EditForward(null, session.connection?.id))
        })
    }
}

private val LINK = Regex("""(https?|ftp)://[^\s<>"'`]+[^\s<>"'`.,;:!?)\]}]""")

private fun findLinks(terminal: TerminalSession): List<String> {
    val text = terminal.emulator?.screen?.transcriptTextWithFullLinesJoined ?: return emptyList()
    return LINK.findAll(text).map { it.value }.toList().distinct().asReversed()
}

fun spToPx(context: Context, sp: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), context.resources.displayMetrics).toInt()

@Composable
private fun PickerDialog(
    title: String,
    onDismiss: () -> Unit,
    empty: String,
    items: List<Pair<String, String>>,
    onPick: (Int) -> Unit,
    action: Pair<String, () -> Unit>? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (items.isEmpty()) {
                Text(empty)
            } else {
                LazyColumn(Modifier.widthIn(max = 480.dp)) {
                    items(items.indices.toList()) { i ->
                        val (head, sub) = items[i]
                        ListItem(
                            headlineContent = { Text(head, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = if (sub.isNotEmpty() && sub != head) {
                                { Text(sub, maxLines = 2, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                            } else null,
                            modifier = Modifier.combinedClickableCompat(onClick = { onPick(i) }),
                        )
                    }
                }
            }
        },
        confirmButton = { if (action != null) TextButton(onClick = action.second) { Text(action.first) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ForwardsDialog(session: Session, onDismiss: () -> Unit, onAdd: () -> Unit) {
    val vault by Store.vault.collectAsState()
    val running by session.forwards.collectAsState()
    val errors by session.forwardErrors.collectAsState()
    val state by session.state.collectAsState()
    val list = vault.liveForwards().filter { it.connectionId == session.connection?.id }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Port forwards") },
        text = {
            Column {
                if (list.isEmpty()) Text("None for this connection yet.")
                for (f in list) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(f.title())
                            Text(f.kind.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            errors[f.id]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (f.id in running && f.kind == com.rm.whelk.core.ForwardKind.LOCAL) {
                            val ctx = LocalContext.current
                            TextButton(onClick = { openForward(ctx, f) }) { Text("Open") }
                        }
                        Switch(
                            checked = f.id in running,
                            enabled = state == SessionState.OPEN,
                            onCheckedChange = { if (it) session.startForward(f) else session.stopForward(f.id) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text("New port forward") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A key on the extra keys row. */
sealed class Key(val label: String) {
    class Code(label: String, val keyCode: Int) : Key(label)
    class Text(label: String, val text: String = label) : Key(label)
    object Ctrl : Key("Ctrl")
    object Alt : Key("Alt")
}

private val extraKeys = listOf(
    Key.Code("Esc", KeyEvent.KEYCODE_ESCAPE),
    Key.Code("Tab", KeyEvent.KEYCODE_TAB),
    Key.Ctrl,
    Key.Alt,
    Key.Code("←", KeyEvent.KEYCODE_DPAD_LEFT),
    Key.Code("↑", KeyEvent.KEYCODE_DPAD_UP),
    Key.Code("↓", KeyEvent.KEYCODE_DPAD_DOWN),
    Key.Code("→", KeyEvent.KEYCODE_DPAD_RIGHT),
    Key.Text("|"),
    Key.Text("/"),
    Key.Text("-"),
    Key.Text("~"),
    Key.Text("_"),
    Key.Code("Home", KeyEvent.KEYCODE_MOVE_HOME),
    Key.Code("End", KeyEvent.KEYCODE_MOVE_END),
    Key.Code("PgUp", KeyEvent.KEYCODE_PAGE_UP),
    Key.Code("PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
    Key.Code("Ins", KeyEvent.KEYCODE_INSERT),
    Key.Code("Del", KeyEvent.KEYCODE_FORWARD_DEL),
) + (1..12).map { Key.Code("F$it", KeyEvent.KEYCODE_F1 + it - 1) }

@Composable
private fun ExtraKeys(modifiers: Modifiers, onKey: (Key) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).horizontalScroll(rememberScrollState()).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (k in extraKeys) {
            val on = (k is Key.Ctrl && modifiers.ctrl) || (k is Key.Alt && modifiers.alt)
            val locked = (k is Key.Ctrl && modifiers.ctrlLock) || (k is Key.Alt && modifiers.altLock)
            Box(
                Modifier
                    .height(44.dp)
                    .widthIn(min = 46.dp)
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .combinedClickableCompat(
                        onClick = {
                            when (k) {
                                Key.Ctrl -> { modifiers.ctrl = !modifiers.ctrl; modifiers.ctrlLock = false }
                                Key.Alt -> { modifiers.alt = !modifiers.alt; modifiers.altLock = false }
                                else -> onKey(k)
                            }
                        },
                        onLongClick = when (k) {
                            Key.Ctrl -> { { modifiers.ctrl = true; modifiers.ctrlLock = true } }
                            Key.Alt -> { { modifiers.alt = true; modifiers.altLock = true } }
                            else -> null
                        },
                    )
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (locked) k.label.uppercase() else k.label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun sendKey(view: TerminalView, key: Key, modifiers: Modifiers) {
    val session = view.currentSession ?: return
    when (key) {
        is Key.Code -> {
            var mod = 0
            if (modifiers.ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
            if (modifiers.alt) mod = mod or KeyHandler.KEYMOD_ALT
            view.handleKeyCode(key.keyCode, mod)
        }
        is Key.Text -> for (ch in key.text) view.inputCodePoint(ch.code, modifiers.ctrl, modifiers.alt)
        else -> {}
    }
    if (session.isRunning) modifiers.used()
}

/** Connects the Termux view to Whelk's settings and the extra keys. */
private class ViewClient(
    private val view: TerminalView,
    private val modifiers: Modifiers,
    private val showKeyboard: () -> Unit,
    private val setFont: (Int) -> Unit,
    private val fontSize: () -> Int,
    private val switchSession: (Boolean) -> Unit,
) : TerminalViewClient {
    private var scale = 1f

    override fun onScale(scale: Float): Float {
        // Pinch: a step of font size each time the fingers move about 15% apart or together.
        this.scale = scale
        if (scale > 1.15f || scale < 0.87f) {
            setFont(fontSize() + if (scale > 1f) 1 else -1)
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) = showKeyboard()
    override fun shouldBackButtonBeMappedToEscape() = Settings.value.backIsEscape
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val up = keyCode == KeyEvent.KEYCODE_VOLUME_UP
            when (Settings.value.volumeKeys) {
                VolumeKeys.FONT_SIZE -> setFont(fontSize() + if (up) 1 else -1)
                VolumeKeys.ARROWS -> view.handleKeyCode(if (up) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, 0)
                VolumeKeys.PAGE -> view.handleKeyCode(if (up) KeyEvent.KEYCODE_PAGE_UP else KeyEvent.KEYCODE_PAGE_DOWN, 0)
                VolumeKeys.OFF -> return false
            }
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean =
        (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && Settings.value.volumeKeys != VolumeKeys.OFF

    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = modifiers.ctrl
    override fun readAltKey() = modifiers.alt
    override fun readShiftKey() = false
    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        modifiers.used()
        return false
    }

    override fun onEmulatorSet() {}

    override fun onSideFling(right: Boolean): Boolean {
        val swipe = Settings.value.swipe
        when (swipe) {
            Swipe.OFF -> return false
            Swipe.SESSIONS -> switchSession(right)
            else -> view.currentSession?.write(swipe.prefix + if (right) swipe.previous else swipe.next)
        }
        return true
    }

    /** More than one character at once from the keyboard is a paste from its clipboard, or a swiped word. */
    override fun onCommitText(text: CharSequence): Boolean {
        if (text.length < 2) return false
        val raw = text.toString()
        if (!raw.contains('\n') && Paste.clean(raw, Settings.value.straightenPaste) == raw) return false
        val session = view.currentSession ?: return false
        Sessions.paste(session, raw)
        modifiers.used()
        return true
    }
    override fun logError(tag: String, message: String) { android.util.Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { android.util.Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { android.util.Log.e(tag, "", e) }
}
