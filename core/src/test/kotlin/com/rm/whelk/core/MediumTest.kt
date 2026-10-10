package com.rm.whelk.core

import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PlaceholdersTest {
    @Test
    fun fieldsAndFilling() {
        val s = "git checkout {{branch:main}} && echo {{ note }} on {{host}} as {{user}} {{branch}}"
        assertEquals(listOf(Placeholders.Field("branch", "main"), Placeholders.Field("note", "")), Placeholders.fields(s))
        val out = Placeholders.fill(s, mapOf("note" to "hi"), host = "box", user = "dan")
        assertEquals("git checkout main && echo hi on box as dan main", out)
        assertEquals("2026-10-10", Placeholders.fill("{{date}}", emptyMap(), now = Date(1791660000000)))
        assertEquals("plain", Placeholders.fill("plain", emptyMap()))
    }
}

class AnsiTextTest {
    @Test
    fun strips() {
        val a = AnsiText()
        val out = a.feed("\u001b[1;32mok\u001b[0m line\r\n\u001b]0;title\u0007abcX\bd\r\nprogress 10%\rprogress 99%\n")
        assertEquals("ok line\nabcd\nprogress 10%progress 99%\n", out)
        assertEquals("par", a.feed("par").let { a.rest() })
    }
}

class ColourSchemesTest {
    @Test
    fun xresources() {
        val text = (0 until 16).joinToString("\n") { "*.color$it: #%02x%02x%02x".format(it, it, it) } +
            "\n*.background: #101010\n*.foreground: #eeeeee\n*.cursorColor: #ff0000\n! comment"
        val s = ColourSchemes.read("gruvy.Xresources", text)!!
        assertEquals(0xFF101010, s.background)
        assertEquals(0xFFFF0000, s.cursor)
        assertEquals(0xFF0F0F0F, s.ansi[15])
        assertEquals("gruvy", s.name)
    }

    @Test
    fun iterm() {
        fun c(name: String, r: Double, g: Double, b: Double) =
            "<key>$name</key><dict><key>Blue Component</key><real>$b</real><key>Green Component</key><real>$g</real><key>Red Component</key><real>$r</real></dict>"
        val body = (0 until 16).joinToString("") { c("Ansi $it Color", 1.0, 0.0, 0.0) } +
            c("Background Color", 0.0, 0.0, 0.0) + c("Foreground Color", 1.0, 1.0, 1.0)
        val s = ColourSchemes.read("Red Alert.itermcolors", "<?xml version=\"1.0\"?><plist><dict>$body</dict></plist>")!!
        assertEquals(0xFFFF0000, s.ansi[3])
        assertEquals(0xFF000000, s.background)
        assertEquals("Red Alert", s.name)
    }

    @Test
    fun windowsTerminalAndAlacritty() {
        val names = listOf("black", "red", "green", "yellow", "blue", "purple", "cyan", "white")
        val json = "{\"name\":\"Mine\",\"background\":\"#000001\",\"foreground\":\"#FFFFFF\"," +
            names.joinToString(",") { "\"$it\":\"#111111\",\"bright${it.replaceFirstChar { c -> c.uppercase() }}\":\"#222222\"" } + "}"
        val w = ColourSchemes.read("x.json", json)!!
        assertEquals("Mine", w.name)
        assertEquals(0xFF222222, w.ansi[9])
        val ala = "[colors.primary]\nbackground = '#1d1f21'\nforeground = \"#c5c8c6\"\n[colors.normal]\n" +
            listOf("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white").joinToString("\n") { "$it = '#010101'" } +
            "\n[colors.bright]\n" + listOf("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white").joinToString("\n") { "$it = '#020202'" }
        val a = ColourSchemes.read("tomorrow.toml", ala)
        assertNotNull(a)
        assertEquals(0xFF1D1F21, a.background)
        assertEquals(0xFF020202, a.ansi[8])
    }
}
