package com.rm.whelk.core

/**
 * Starting Mosh: log in over SSH, run mosh-server, and read the UDP port and session key it
 * prints. mosh-client then talks to that port directly, and the SSH login can close.
 */
object Mosh {
    const val DEFAULT_SERVER = "mosh-server"

    /** UTF-8 locales to try in turn, as mosh-server refuses to start without one. */
    val LOCALES = listOf("C.UTF-8", "en_US.UTF-8")

    data class Connect(val port: Int, val key: String)

    private val CONNECT = Regex("""MOSH CONNECT (\d{1,5}) ([A-Za-z0-9/+]{22})""")

    /** The command line for the server. [server] is the user's own, like a path or extra options. */
    fun serverCommand(server: String?, locale: String): String {
        val base = server?.trim()?.ifEmpty { null } ?: DEFAULT_SERVER
        return "$base new -s -c 256 -l LANG=$locale -l LC_ALL=$locale"
    }

    fun parse(output: String): Connect? {
        val m = CONNECT.find(output) ?: return null
        val port = m.groupValues[1].toInt()
        if (port !in 1..65535) return null
        return Connect(port, m.groupValues[2])
    }

    /** Whether mosh-server turned down the locale, so the next one is worth trying. */
    fun localeRefused(output: String): Boolean =
        output.contains("UTF-8", ignoreCase = true) && output.contains("locale", ignoreCase = true) && parse(output) == null

    /** A short reason for the user when the server didn't start. */
    fun failure(output: String): String {
        if (output.contains("not found") || output.contains("No such file")) return "mosh-server isn't installed on the server"
        val line = output.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return if (line == null) "mosh-server didn't start" else "mosh-server: $line"
    }
}
