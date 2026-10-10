package com.rm.whelk.ui

import android.content.Context
import android.graphics.Typeface
import java.io.File

/**
 * Terminal fonts: Nerd Font versions of a few monospace fonts, so prompts and status lines that use
 * powerline and icon symbols draw properly, plus Android's own and one the user adds. Each is one
 * Regular file; the terminal draws bold itself.
 */
object Fonts {
    const val SYSTEM = "Android monospace"
    const val CUSTOM = "My font"

    private val bundled = linkedMapOf(
        "JetBrains Mono" to "fonts/jetbrains-mono.ttf",
        "Fira Mono" to "fonts/fira-mono.otf",
        "Hack" to "fonts/hack.ttf",
        "Ubuntu Mono" to "fonts/ubuntu-mono.ttf",
    )

    private val cache = HashMap<String, Typeface>()

    fun customFile(context: Context) = File(context.filesDir, "fonts/custom")

    fun names(context: Context): List<String> =
        bundled.keys.toList() + SYSTEM + (if (customFile(context).exists()) listOf(CUSTOM) else emptyList())

    fun typeface(context: Context, name: String): Typeface = synchronized(cache) {
        cache.getOrPut(name) {
            runCatching {
                when (name) {
                    SYSTEM -> Typeface.MONOSPACE
                    CUSTOM -> Typeface.createFromFile(customFile(context))
                    else -> Typeface.createFromAsset(context.assets, bundled[name] ?: bundled.values.first())
                }
            }.getOrDefault(Typeface.MONOSPACE)
        }
    }

    /** Takes a font file the user picked. Returns false if Android can't read it as a font. */
    fun setCustom(context: Context, bytes: ByteArray): Boolean {
        val f = customFile(context)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "custom.tmp")
        tmp.writeBytes(bytes)
        val ok = runCatching { Typeface.createFromFile(tmp) != Typeface.DEFAULT }.getOrDefault(false)
        if (!ok) {
            tmp.delete()
            return false
        }
        tmp.renameTo(f)
        synchronized(cache) { cache.remove(CUSTOM) }
        return true
    }
}
