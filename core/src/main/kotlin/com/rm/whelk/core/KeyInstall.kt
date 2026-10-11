package com.rm.whelk.core

/**
 * Adds a public key to the server's ~/.ssh/authorized_keys, like ssh-copy-id: makes the folder and
 * file with the right permissions if needed, and leaves the file alone if the key is already there.
 */
object KeyInstall {
    const val DONE = "whelk-key-installed"
    const val ALREADY = "whelk-key-already-there"

    private const val SCRIPT = "umask 077; mkdir -p \"\$HOME/.ssh\" && cd \"\$HOME/.ssh\" && touch authorized_keys && " +
        "if grep -qxF \"\$1\" authorized_keys; then echo $ALREADY; else " +
        "if [ -s authorized_keys ] && [ -n \"\$(tail -c1 authorized_keys)\" ]; then echo >> authorized_keys; fi; " +
        "printf \"%s\\n\" \"\$1\" >> authorized_keys && echo $DONE; fi"

    /**
     * The command to run, through sh whatever the login shell is. The key goes in as an argument in
     * single quotes, so it can't hold one, nor a line break.
     */
    fun command(publicKeyLine: String): String {
        val line = publicKeyLine.trim()
        require(line.isNotEmpty() && '\'' !in line && '\n' !in line && '\r' !in line) { "That public key can't be sent" }
        return "sh -c '$SCRIPT' whelk '$line'"
    }

    /** Whether the server's reply says the key is in place. */
    fun installed(output: String): Boolean = DONE in output || ALREADY in output
}
