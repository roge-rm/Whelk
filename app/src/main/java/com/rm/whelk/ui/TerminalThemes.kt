package com.rm.whelk.ui

import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import java.util.Properties

/** A terminal colour scheme: background, foreground, cursor and the 16 ANSI colours. */
class TerminalTheme(val name: String, val background: Int, val foreground: Int, val cursor: Int, val ansi: IntArray) {
    val isLight: Boolean get() = TerminalColors.getPerceivedBrightnessOfColor(background) > 130
}

private fun c(hex: Long) = hex.toInt()

private fun theme(name: String, bg: Long, fg: Long, cursor: Long, vararg ansi: Long) =
    TerminalTheme(name, c(bg), c(fg), c(cursor), IntArray(16) { c(ansi[it]) })

object TerminalThemes {
    val all = listOf(
        theme(
            "Whelk", 0xFF121417, 0xFFDADDE2, 0xFFE8C9A0,
            0xFF1E2227, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B, 0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFABB2BF,
            0xFF5C6370, 0xFFEF7E87, 0xFFAEDB8F, 0xFFF0CF8E, 0xFF7CC0F5, 0xFFD38FE8, 0xFF6FCCD8, 0xFFFFFFFF,
        ),
        theme(
            "Molokai", 0xFF1B1D1E, 0xFFF8F8F2, 0xFFF8F8F0,
            0xFF1B1D1E, 0xFFF92672, 0xFF82B414, 0xFFFD971F, 0xFF56C2D6, 0xFF8C54FE, 0xFF465457, 0xFFCCCCC6,
            0xFF505354, 0xFFFF5995, 0xFFB6E354, 0xFFFEED6C, 0xFF8CEDFF, 0xFF9E6FFE, 0xFF899CA1, 0xFFF8F8F2,
        ),
        theme(
            "Monokai", 0xFF272822, 0xFFF8F8F2, 0xFFF8F8F0,
            0xFF272822, 0xFFF92672, 0xFFA6E22E, 0xFFF4BF75, 0xFF66D9EF, 0xFFAE81FF, 0xFFA1EFE4, 0xFFF8F8F2,
            0xFF75715E, 0xFFF92672, 0xFFA6E22E, 0xFFF4BF75, 0xFF66D9EF, 0xFFAE81FF, 0xFFA1EFE4, 0xFFF9F8F5,
        ),
        theme(
            "Solarized Dark", 0xFF002B36, 0xFF839496, 0xFF93A1A1,
            0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900, 0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
            0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83, 0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3,
        ),
        theme(
            "Solarized Light", 0xFFFDF6E3, 0xFF657B83, 0xFF586E75,
            0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900, 0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
            0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83, 0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3,
        ),
        theme(
            "Dracula", 0xFF282A36, 0xFFF8F8F2, 0xFFF8F8F2,
            0xFF21222C, 0xFFFF5555, 0xFF50FA7B, 0xFFF1FA8C, 0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF8F8F2,
            0xFF6272A4, 0xFFFF6E6E, 0xFF69FF94, 0xFFFFFFA5, 0xFFD6ACFF, 0xFFFF92DF, 0xFFA4FFFF, 0xFFFFFFFF,
        ),
        theme(
            "Gruvbox Dark", 0xFF282828, 0xFFEBDBB2, 0xFFEBDBB2,
            0xFF282828, 0xFFCC241D, 0xFF98971A, 0xFFD79921, 0xFF458588, 0xFFB16286, 0xFF689D6A, 0xFFA89984,
            0xFF928374, 0xFFFB4934, 0xFFB8BB26, 0xFFFABD2F, 0xFF83A598, 0xFFD3869B, 0xFF8EC07C, 0xFFEBDBB2,
        ),
        theme(
            "Nord", 0xFF2E3440, 0xFFD8DEE9, 0xFFD8DEE9,
            0xFF3B4252, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B, 0xFF81A1C1, 0xFFB48EAD, 0xFF88C0D0, 0xFFE5E9F0,
            0xFF4C566A, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B, 0xFF81A1C1, 0xFFB48EAD, 0xFF8FBCBB, 0xFFECEFF4,
        ),
        theme(
            "Tango", 0xFF000000, 0xFFD3D7CF, 0xFFD3D7CF,
            0xFF2E3436, 0xFFCC0000, 0xFF4E9A06, 0xFFC4A000, 0xFF3465A4, 0xFF75507B, 0xFF06989A, 0xFFD3D7CF,
            0xFF555753, 0xFFEF2929, 0xFF8AE234, 0xFFFCE94F, 0xFF729FCF, 0xFFAD7FA8, 0xFF34E2E2, 0xFFEEEEEC,
        ),
        theme(
            "Classic", 0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF,
            0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00, 0xFF0000EE, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
            0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00, 0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF,
        ),
        theme(
            "Paper", 0xFFF8F8F5, 0xFF2B2B2B, 0xFF2B2B2B,
            0xFF2B2B2B, 0xFFB3261E, 0xFF2E7D32, 0xFF8D6E00, 0xFF1A5FB4, 0xFF8E3FA6, 0xFF00796B, 0xFFBDBDBD,
            0xFF6B6B6B, 0xFFD32F2F, 0xFF388E3C, 0xFFA67C00, 0xFF1E88E5, 0xFFAB47BC, 0xFF00897B, 0xFF9E9E9E,
        ),
    )

    /** A built in scheme, or one the user imported, by name. */
    fun named(name: String?): TerminalTheme {
        all.firstOrNull { it.name == name }?.let { return it }
        val custom = com.rm.whelk.data.Store.vault.value.liveColourSchemes().firstOrNull { it.name == name } ?: return all[0]
        if (custom.ansi.size < 16) return all[0]
        return TerminalTheme(custom.name, custom.background.toInt(), custom.foreground.toInt(), custom.cursor.toInt(), IntArray(16) { custom.ansi[it].toInt() })
    }

    fun names(vault: com.rm.whelk.core.Vault): List<String> = all.map { it.name } + vault.liveColourSchemes().map { it.name }.sorted()

    private var applied: TerminalTheme? = null

    /**
     * Sets the colours every terminal starts from. The emulator keeps one shared scheme, and each
     * session's colours are reset from it.
     */
    fun apply(theme: TerminalTheme) {
        if (applied?.name == theme.name && applied?.background == theme.background) return
        applied = theme
        val props = Properties()
        for (i in 0 until 16) props["color$i"] = hex(theme.ansi[i])
        props["background"] = hex(theme.background)
        props["foreground"] = hex(theme.foreground)
        props["cursor"] = hex(theme.cursor)
        TerminalColors.COLOR_SCHEME.updateWith(props)
    }

    private fun hex(c: Int) = String.format("#%06x", c and 0xFFFFFF)

    /** Index of the background colour in an emulator's colour table. */
    const val BACKGROUND = TextStyle.COLOR_INDEX_BACKGROUND
}
