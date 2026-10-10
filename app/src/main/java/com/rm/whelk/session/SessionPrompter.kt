package com.rm.whelk.session

import com.rm.whelk.core.HostKeyCheck
import com.rm.whelk.core.SshPrompter
import com.rm.whelk.core.SshTarget

/** Asks the user through [Prompts] on behalf of a session, and shows progress in its terminal. */
class SessionPrompter(private val sessionId: Int, private val show: (String) -> Unit) : SshPrompter {
    override fun trustHostKey(check: HostKeyCheck): Boolean =
        Prompts.ask(Ask.HostKey(sessionId, check)) == "yes"

    override fun username(target: SshTarget): String? =
        Prompts.ask(Ask.Text(sessionId, target.host, "Log in as", "Username", secret = false))

    override fun password(target: SshTarget): String? =
        Prompts.ask(Ask.Text(sessionId, target.label, "", "Password", secret = true))

    override fun passphrase(target: SshTarget): String? =
        Prompts.ask(Ask.Text(sessionId, target.label, "The private key is locked", "Passphrase", secret = true))

    override fun challenge(target: SshTarget, instruction: String, prompt: String, echo: Boolean): String? =
        Prompts.ask(Ask.Text(sessionId, target.label, instruction, prompt.trim().trimEnd(':'), secret = !echo))

    override fun progress(text: String) {
        show("\u001b[2K\r$text")
    }
}
