package com.rm.whelk.ui

import android.view.KeyEvent
import com.rm.whelk.core.ExtraKey

/** A key on the extra keys row, ready to send. */
sealed class Key(val label: String) {
    class Code(label: String, val keyCode: Int) : Key(label)
    class Text(label: String, val text: String = label) : Key(label)
    object Ctrl : Key("Ctrl")
    object Alt : Key("Alt")
}

object ExtraKeys {
    /** The built in keys by id, in the order they're offered. */
    val builtIn: LinkedHashMap<String, Key> = linkedMapOf<String, Key>(
        "esc" to Key.Code("Esc", KeyEvent.KEYCODE_ESCAPE),
        "tab" to Key.Code("Tab", KeyEvent.KEYCODE_TAB),
        "ctrl" to Key.Ctrl,
        "alt" to Key.Alt,
        "left" to Key.Code("←", KeyEvent.KEYCODE_DPAD_LEFT),
        "up" to Key.Code("↑", KeyEvent.KEYCODE_DPAD_UP),
        "down" to Key.Code("↓", KeyEvent.KEYCODE_DPAD_DOWN),
        "right" to Key.Code("→", KeyEvent.KEYCODE_DPAD_RIGHT),
        "home" to Key.Code("Home", KeyEvent.KEYCODE_MOVE_HOME),
        "end" to Key.Code("End", KeyEvent.KEYCODE_MOVE_END),
        "pgup" to Key.Code("PgUp", KeyEvent.KEYCODE_PAGE_UP),
        "pgdn" to Key.Code("PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
        "ins" to Key.Code("Ins", KeyEvent.KEYCODE_INSERT),
        "del" to Key.Code("Del", KeyEvent.KEYCODE_FORWARD_DEL),
    ).apply { for (n in 1..12) put("f$n", Key.Code("F$n", KeyEvent.KEYCODE_F1 + n - 1)) }

    val defaults: List<ExtraKey> =
        listOf("esc", "tab", "ctrl", "alt", "left", "up", "down", "right").map { ExtraKey(builtIn[it]!!.label, id = it) } +
            listOf("|", "/", "-", "~", "_").map { ExtraKey(it, text = it) } +
            (listOf("home", "end", "pgup", "pgdn", "ins", "del") + (1..12).map { "f$it" }).map { ExtraKey(builtIn[it]!!.label, id = it) }

    fun toKey(k: ExtraKey): Key? {
        k.id?.let { return builtIn[it] }
        val text = k.text ?: return null
        return Key.Text(k.label.ifEmpty { text }, ExtraKey.decode(text))
    }
}
