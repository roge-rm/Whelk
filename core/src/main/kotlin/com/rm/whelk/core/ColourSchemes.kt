package com.rm.whelk.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A terminal colour scheme the user imported. Colours are ARGB. Kept in the vault, so it syncs. */
@Serializable
data class ColourScheme(
    override val id: String = newId(),
    val name: String = "",
    val background: Long = 0xFF000000,
    val foreground: Long = 0xFFFFFFFF,
    val cursor: Long = 0xFFFFFFFF,
    /** The 16 ANSI colours. */
    val ansi: List<Long> = emptyList(),
    override val updated: Long = now(),
    override val deleted: Boolean = false,
) : Item

/**
 * Reads colour schemes from other terminals' files: iTerm2 (.itermcolors), Xresources, Termux
 * (colors.properties), Windows Terminal (a JSON scheme) and Alacritty (TOML).
 */
object ColourSchemes {
    fun read(fileName: String, text: String): ColourScheme? {
        val name = fileName.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim().ifEmpty { "Imported" }
        val t = text.trim()
        return when {
            t.startsWith("<?xml") || t.contains("<plist") -> iterm(name, t)
            t.startsWith("{") -> windowsTerminal(name, t)
            t.contains("[colors") -> alacritty(name, t)
            t.contains("color0") && (t.contains("*") || t.contains("URxvt") || t.contains("XTerm")) -> xresources(name, t)
            t.contains("color0") -> properties(name, t)
            else -> null
        }
    }

    private fun hex(s: String): Long? {
        val h = s.trim().removePrefix("#").removePrefix("0x").removePrefix("'").removeSuffix("'").removeSuffix("\"").removePrefix("\"")
            .removePrefix("#")
        return when (h.length) {
            6 -> h.toLongOrNull(16)?.let { it or 0xFF000000 }
            3 -> h.map { "$it$it" }.joinToString("").toLongOrNull(16)?.let { it or 0xFF000000 }
            else -> null
        }
    }

    private fun build(name: String, colours: Map<String, Long>): ColourScheme? {
        val ansi = (0 until 16).map { colours["color$it"] ?: return null }
        val bg = colours["background"] ?: ansi[0]
        val fg = colours["foreground"] ?: ansi[7]
        return ColourScheme(name = name, background = bg, foreground = fg, cursor = colours["cursor"] ?: fg, ansi = ansi)
    }

    private fun properties(name: String, text: String): ColourScheme? {
        val m = HashMap<String, Long>()
        for (line in text.lines()) {
            val i = line.indexOf('=')
            if (i <= 0 || line.trimStart().startsWith("#")) continue
            hex(line.substring(i + 1))?.let { m[line.substring(0, i).trim()] = it }
        }
        return build(name, m)
    }

    private fun xresources(name: String, text: String): ColourScheme? {
        val m = HashMap<String, Long>()
        val defines = HashMap<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("#define")) {
                val p = line.split(Regex("\\s+"))
                if (p.size >= 3) defines[p[1]] = p[2]
                continue
            }
            if (line.startsWith("!") || !line.contains(':')) continue
            val key = line.substringBefore(':').trim().substringAfterLast('.').substringAfterLast('*')
            var value = line.substringAfter(':').trim()
            defines[value]?.let { value = it }
            val k = if (key == "cursorColor") "cursor" else key
            hex(value)?.let { m[k] = it }
        }
        return build(name, m)
    }

    private fun windowsTerminal(name: String, text: String): ColourScheme? {
        val o = Json.parseToJsonElement(text).jsonObject
        val scheme = (o["schemes"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonObject ?: o
        fun c(k: String) = (scheme[k]?.jsonPrimitive?.content)?.let { hex(it) }
        val names = listOf("black", "red", "green", "yellow", "blue", "purple", "cyan", "white")
        val m = HashMap<String, Long>()
        names.forEachIndexed { i, n ->
            c(n)?.let { m["color$i"] = it }
            c("bright" + n.replaceFirstChar { it.uppercase() })?.let { m["color${i + 8}"] = it }
        }
        c("background")?.let { m["background"] = it }
        c("foreground")?.let { m["foreground"] = it }
        c("cursorColor")?.let { m["cursor"] = it }
        val n = (scheme["name"]?.jsonPrimitive?.content) ?: name
        return build(n, m)
    }

    private fun alacritty(name: String, text: String): ColourScheme? {
        val names = listOf("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white")
        val m = HashMap<String, Long>()
        var section = ""
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                section = line.trim('[', ']').trim()
                continue
            }
            val i = line.indexOf('=')
            if (i <= 0) continue
            val key = line.substring(0, i).trim()
            val value = hex(line.substring(i + 1).substringBefore('#', line.substring(i + 1)).let { v ->
                // Values are quoted like "#1d1f21"; keep the hash inside the quotes.
                Regex("['\"](#?[0-9a-fA-Fx]+)['\"]").find(line.substring(i + 1))?.groupValues?.get(1) ?: v
            }) ?: continue
            when (section) {
                "colors.primary" -> if (key == "background" || key == "foreground") m[key] = value
                "colors.cursor" -> if (key == "cursor") m["cursor"] = value
                "colors.normal" -> names.indexOf(key).takeIf { it >= 0 }?.let { m["color$it"] = value }
                "colors.bright" -> names.indexOf(key).takeIf { it >= 0 }?.let { m["color${it + 8}"] = value }
            }
        }
        return build(name, m)
    }

    private fun iterm(name: String, text: String): ColourScheme? {
        // Each colour is a <key>Name</key><dict> with Red, Green and Blue components from 0 to 1.
        val m = HashMap<String, Long>()
        val entry = Regex("""<key>([^<]+)</key>\s*<dict>(.*?)</dict>""", RegexOption.DOT_MATCHES_ALL)
        for (e in entry.findAll(text)) {
            val comp = HashMap<String, Double>()
            for (c in Regex("""<key>(Red|Green|Blue) Component</key>\s*<real>([^<]+)</real>""").findAll(e.groupValues[2])) {
                comp[c.groupValues[1]] = c.groupValues[2].toDouble()
            }
            if (comp.size < 3) continue
            fun b(x: Double) = (x.coerceIn(0.0, 1.0) * 255 + 0.5).toLong()
            val argb = 0xFF000000 or (b(comp["Red"]!!) shl 16) or (b(comp["Green"]!!) shl 8) or b(comp["Blue"]!!)
            val key = e.groupValues[1]
            val k = when {
                key.startsWith("Ansi ") -> "color" + key.removePrefix("Ansi ").removeSuffix(" Color")
                key == "Background Color" -> "background"
                key == "Foreground Color" -> "foreground"
                key == "Cursor Color" -> "cursor"
                else -> null
            } ?: continue
            m[k] = argb
        }
        return build(name, m)
    }
}
