package com.rm.whelk.session

import com.rm.whelk.core.AgentKey
import com.rm.whelk.core.AgentKeys
import com.rm.whelk.core.Keys
import com.rm.whelk.data.Settings
import com.rm.whelk.data.Store

/**
 * The keys a forwarded agent offers: every identity's key that opens without asking for a
 * passphrase, and its certificate too if it has one. Asks before each use unless that's off.
 */
class PhoneAgent(private val sessionId: Int, private val host: String) : AgentKeys {
    private val loaded: List<AgentKey> by lazy {
        Store.vault.value.liveIdentities().filter { !it.privateKey.isNullOrBlank() }.flatMap { i ->
            listOfNotNull(
                i.certificate?.let { Keys.agentKey(i.privateKey!!, i.passphrase, it, i.title() + " (certificate)") },
                Keys.agentKey(i.privateKey!!, i.passphrase, null, i.title()),
            )
        }
    }

    override fun keys(): List<AgentKey> = loaded

    override fun allow(key: AgentKey): Boolean {
        if (!Settings.value.askBeforeAgent) return true
        return Prompts.ask(Ask.Confirm(sessionId, "Use ${key.comment}?", "$host wants to sign with this key, through agent forwarding.", "Allow")) == "yes"
    }
}
