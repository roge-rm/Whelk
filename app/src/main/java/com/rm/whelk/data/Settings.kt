package com.rm.whelk.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class VolumeKeys(val label: String) {
    FONT_SIZE("Font size"),
    ARROWS("Up and down arrows"),
    PAGE("Page up and down"),
    OFF("Volume"),
}

enum class Swipe(val label: String, val prefix: String, val next: String, val previous: String) {
    OFF("Nothing", "", "", ""),
    TMUX("tmux windows", "\u0002", "n", "p"),
    SCREEN("screen windows", "\u0001", "n", "p"),
    SESSIONS("Whelk sessions", "", "", ""),
}

/** This device's own settings. They aren't synced. */
data class Prefs(
    /** Terminal text size in sp. */
    val fontSize: Int = 14,
    val theme: String = "Whelk",
    val font: String = "JetBrains Mono",
    val volumeKeys: VolumeKeys = VolumeKeys.FONT_SIZE,
    val keepScreenOn: Boolean = false,
    val extraKeys: Boolean = true,
    val vibrateOnBell: Boolean = true,
    val backIsEscape: Boolean = false,
    /** Lock after this many minutes in the background, or -1 for no lock. */
    val lockAfter: Int = -1,
    val scrollback: Int = 5000,
    val terminalType: String = "xterm-256color",
    /** Turn curly quotes and long dashes into plain ones when pasting. */
    val straightenPaste: Boolean = true,
    /** What a sideways swipe on the terminal does. */
    val swipe: Swipe = Swipe.SESSIONS,
    /** Ask each time a server wants to sign with a key through agent forwarding. */
    val askBeforeAgent: Boolean = true,
)

object Settings {
    private lateinit var prefs: SharedPreferences
    private val state = MutableStateFlow(Prefs())
    val flow: StateFlow<Prefs> = state
    val value: Prefs get() = state.value

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val d = Prefs()
        state.value = Prefs(
            fontSize = prefs.getInt("fontSize", d.fontSize),
            theme = prefs.getString("theme", d.theme) ?: d.theme,
            font = prefs.getString("font", d.font) ?: d.font,
            volumeKeys = runCatching { VolumeKeys.valueOf(prefs.getString("volumeKeys", null)!!) }.getOrDefault(d.volumeKeys),
            keepScreenOn = prefs.getBoolean("keepScreenOn", d.keepScreenOn),
            extraKeys = prefs.getBoolean("extraKeys", d.extraKeys),
            vibrateOnBell = prefs.getBoolean("vibrateOnBell", d.vibrateOnBell),
            backIsEscape = prefs.getBoolean("backIsEscape", d.backIsEscape),
            lockAfter = prefs.getInt("lockAfter", d.lockAfter),
            scrollback = prefs.getInt("scrollback", d.scrollback),
            terminalType = prefs.getString("terminalType", d.terminalType) ?: d.terminalType,
            straightenPaste = prefs.getBoolean("straightenPaste", d.straightenPaste),
            swipe = runCatching { Swipe.valueOf(prefs.getString("swipe", null)!!) }.getOrDefault(d.swipe),
            askBeforeAgent = prefs.getBoolean("askBeforeAgent", d.askBeforeAgent),
        )
    }

    fun update(change: (Prefs) -> Prefs) {
        val p = change(state.value)
        state.value = p
        prefs.edit()
            .putInt("fontSize", p.fontSize)
            .putString("theme", p.theme)
            .putString("font", p.font)
            .putString("volumeKeys", p.volumeKeys.name)
            .putBoolean("keepScreenOn", p.keepScreenOn)
            .putBoolean("extraKeys", p.extraKeys)
            .putBoolean("vibrateOnBell", p.vibrateOnBell)
            .putBoolean("backIsEscape", p.backIsEscape)
            .putInt("lockAfter", p.lockAfter)
            .putInt("scrollback", p.scrollback)
            .putString("terminalType", p.terminalType)
            .putBoolean("straightenPaste", p.straightenPaste)
            .putString("swipe", p.swipe.name)
            .putBoolean("askBeforeAgent", p.askBeforeAgent)
            .apply()
    }
}
