package com.rm.whelk.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Placeholders in snippets: {{name}} or {{name:default}} ask for a value before the snippet is
 * typed. {{host}}, {{user}}, {{date}} and {{time}} fill themselves in.
 */
object Placeholders {
    data class Field(val name: String, val default: String)

    private val PATTERN = Regex("""\{\{\s*([A-Za-z0-9_ .-]+?)\s*(?::([^}]*))?}}""")
    private val BUILT_IN = setOf("host", "user", "date", "time")

    /** The fields to ask for, in order, each once. */
    fun fields(text: String): List<Field> =
        PATTERN.findAll(text).map { Field(it.groupValues[1], it.groupValues[2]) }
            .filter { it.name.lowercase() !in BUILT_IN }
            .distinctBy { it.name }.toList()

    fun fill(text: String, values: Map<String, String>, host: String = "", user: String = "", now: Date = Date()): String {
        // A default given at one use of a name counts for every use of it.
        val defaults = PATTERN.findAll(text).filter { it.groupValues[2].isNotEmpty() }.associate { it.groupValues[1] to it.groupValues[2] }
        return PATTERN.replace(text) { m ->
            val name = m.groupValues[1]
            when (name.lowercase()) {
                "host" -> host
                "user" -> user
                "date" -> SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(now)
                "time" -> SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(now)
                else -> values[name] ?: defaults[name].orEmpty()
            }
        }
    }
}
