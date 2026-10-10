package com.rm.whelk.ui

import com.termux.terminal.TerminalEmulator

/** A search match: a row (negative into the scrollback) and the columns it covers. */
data class Match(val row: Int, val x1: Int, val x2: Int)

/** Every place [query] shows in the scrollback and screen, oldest first, ignoring case. */
fun findInTerminal(emulator: TerminalEmulator, query: String): List<Match> {
    if (query.isEmpty()) return emptyList()
    val screen = emulator.screen
    val out = ArrayList<Match>()
    for (row in -screen.activeTranscriptRows until emulator.mRows) {
        val line = screen.getSelectedText(0, row, emulator.mColumns, row, false)
        var i = line.indexOf(query, ignoreCase = true)
        while (i >= 0) {
            out += Match(row, i, i + query.length - 1)
            i = line.indexOf(query, i + 1, ignoreCase = true)
        }
    }
    return out
}
