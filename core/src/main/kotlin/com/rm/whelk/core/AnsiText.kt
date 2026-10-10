package com.rm.whelk.core

/**
 * Turns terminal output into plain text for logs: escape sequences are dropped, and carriage
 * returns, backspaces and line feeds give lines as they'd read. Feed it in pieces; it keeps its
 * place across them.
 */
class AnsiText {
    private enum class State { TEXT, ESC, CSI, OSC, OSC_ESC, CHARSET }

    private var state = State.TEXT
    private val line = StringBuilder()

    /** Takes some output and returns the lines it finished, each ending in a newline. */
    fun feed(text: String): String {
        val done = StringBuilder()
        for (c in text) {
            when (state) {
                State.TEXT -> when (c) {
                    '\u001b' -> state = State.ESC
                    '\n' -> { done.append(line.trimEnd()).append('\n'); line.setLength(0) }
                    '\r' -> {}
                    '\b' -> if (line.isNotEmpty()) line.setLength(line.length - 1)
                    '\u0007' -> {}
                    else -> if (c >= ' ' || c == '\t') line.append(c)
                }
                State.ESC -> state = when (c) {
                    '[' -> State.CSI
                    ']', 'P', '_', '^' -> State.OSC
                    '(', ')', '*', '+' -> State.CHARSET
                    else -> State.TEXT
                }
                State.CSI -> if (c in '@'..'~') state = State.TEXT
                State.OSC -> when (c) {
                    '\u0007' -> state = State.TEXT
                    '\u001b' -> state = State.OSC_ESC
                }
                State.OSC_ESC -> state = if (c == '\\') State.TEXT else State.OSC
                State.CHARSET -> state = State.TEXT
            }
        }
        return done.toString()
    }

    /** What's left on the last line. */
    fun rest(): String = line.toString()
}
