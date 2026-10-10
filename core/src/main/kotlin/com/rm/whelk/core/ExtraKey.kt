package com.rm.whelk.core

import kotlinx.serialization.Serializable

/**
 * A key on the row above the keyboard: one of the built in keys by [id] (Esc, Ctrl, arrows, F1 and
 * so on), or with [text], typed as written. In the text, \e is Esc, \t Tab, \n Enter, \\ a
 * backslash, and ^X is Ctrl and the letter.
 */
@Serializable
data class ExtraKey(val label: String, val id: String? = null, val text: String? = null) {
    companion object {
        /** The bytes for key text in the notation above. */
        fun decode(text: String): String = buildString {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '\\' && i + 1 < text.length -> {
                        when (val n = text[i + 1]) {
                            'e' -> append('\u001b')
                            't' -> append('\t')
                            'n', 'r' -> append('\r')
                            '\\' -> append('\\')
                            '^' -> append('^')
                            else -> append('\\').append(n)
                        }
                        i += 2
                    }
                    c == '^' && i + 1 < text.length && text[i + 1].uppercaseChar() in '@'..'_' -> {
                        append((text[i + 1].uppercaseChar().code - 64).toChar())
                        i += 2
                    }
                    c == '^' && i + 1 < text.length && text[i + 1] == '?' -> {
                        append('\u007f')
                        i += 2
                    }
                    else -> {
                        append(c)
                        i++
                    }
                }
            }
        }
    }
}
