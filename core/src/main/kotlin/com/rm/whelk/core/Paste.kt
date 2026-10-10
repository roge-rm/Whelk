package com.rm.whelk.core

/**
 * Cleans text pasted into a terminal. Web pages and documents bring characters a shell takes
 * literally or chokes on: invisible marks, non-breaking and other odd spaces, Windows line endings,
 * and with [straighten], curly quotes and long dashes their editors put in for straight ones.
 */
object Paste {
    // Zero width spaces and joiners, word joiner, byte order mark, soft hyphen, and the direction marks.
    private val INVISIBLE = Regex("[​‌‍⁠﻿­‎‏‪-‮⁦-⁩]")
    // No-break, figure, narrow and the other fixed width spaces, and the ideographic space.
    private val SPACES = Regex("[  -   　]")
    private val LINE_ENDS = Regex("\r\n?|[  \u0085]")

    fun clean(text: String, straighten: Boolean = true): String {
        var t = text.replace(INVISIBLE, "").replace(SPACES, " ").replace(LINE_ENDS, "\n")
        // Tabs from a web page are rare; tabs from a file are meant. Other control characters
        // (not tab or newline) would act as keys, so they go.
        t = t.filter { it == '\n' || it == '\t' || it >= ' ' && it != '\u007F' }
        if (straighten) {
            t = t.replace('‘', '\'').replace('’', '\'').replace('‚', '\'').replace('′', '\'')
                .replace('“', '"').replace('”', '"').replace('„', '"').replace('″', '"')
                .replace('–', '-').replace('—', '-').replace('−', '-').replace('‐', '-').replace('‑', '-')
                .replace("…", "...")
        }
        // A line break at the end would run the command before it can be checked.
        return t.trimEnd('\n', ' ', '\t')
    }
}
