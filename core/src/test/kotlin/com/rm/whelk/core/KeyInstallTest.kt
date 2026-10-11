package com.rm.whelk.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeyInstallTest {
    private fun runIn(home: File, command: String): String {
        val p = ProcessBuilder("sh", "-c", command).apply { environment()["HOME"] = home.path }.redirectErrorStream(true).start()
        return p.inputStream.readBytes().toString(Charsets.UTF_8).also { p.waitFor() }
    }

    @Test
    fun addsOnceAndKeepsWhatsThere() {
        val home = Files.createTempDirectory("whelk-home").toFile()
        try {
            File(home, ".ssh").mkdirs()
            // An existing file without a final line break.
            File(home, ".ssh/authorized_keys").writeText("ssh-ed25519 AAAAold old@box")
            val line = "ecdsa-sha2-nistp256 AAAAnew dan@whelk"
            assertTrue(KeyInstall.installed(runIn(home, KeyInstall.command(line))))
            assertTrue(KeyInstall.installed(runIn(home, KeyInstall.command(line))))
            assertEquals("ssh-ed25519 AAAAold old@box\n$line\n", File(home, ".ssh/authorized_keys").readText())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun makesTheFolder() {
        val home = Files.createTempDirectory("whelk-home").toFile()
        try {
            assertTrue(KeyInstall.installed(runIn(home, KeyInstall.command("ssh-ed25519 AAAA me"))))
            assertEquals("ssh-ed25519 AAAA me\n", File(home, ".ssh/authorized_keys").readText())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun refusesQuotes() {
        assertFailsWith<IllegalArgumentException> { KeyInstall.command("ssh-ed25519 AAAA it's") }
    }
}
