package com.rm.whelk.session

import com.rm.whelk.core.AgentKey
import com.rm.whelk.core.AgentKeys
import com.rm.whelk.core.Keys
import com.rm.whelk.data.Settings
import com.rm.whelk.data.Store

/**
 * The keys a forwarded agent offers: the connection's own identity, and any identity set to be
 * offered to all servers, each if it opens without asking for a passphrase, with its certificate
 * too. The connection's own key asks before each use unless that's off; the others always ask.
 * After [MAX_REFUSALS] refusals the session's agent stops asking and refuses.
 */
class PhoneAgent(private val sessionId: Int, private val host: String, private val identityId: String?) : AgentKeys {
    private var refusals = 0
    private val own = HashSet<AgentKey>()

    private val loaded: List<AgentKey> by lazy {
        Store.vault.value.liveIdentities()
            .filter { !it.privateKey.isNullOrBlank() && (it.id == identityId || it.offerToAll) }
            .flatMap { i ->
                listOfNotNull(
                    i.certificate?.let { Keys.agentKey(i.privateKey!!, i.passphrase, it, i.title() + " (certificate)") },
                    Keys.agentKey(i.privateKey!!, i.passphrase, null, i.title()),
                ).also { if (i.id == identityId) own += it }
            }
    }

    override fun keys(): List<AgentKey> = loaded

    @Synchronized
    override fun allow(key: AgentKey): Boolean {
        if (refusals >= MAX_REFUSALS) return false
        if (key in own && !Settings.value.askBeforeAgent) return true
        val ok = Prompts.ask(Ask.Confirm(sessionId, "Use ${key.comment}?", "$host wants to sign with this key, through agent forwarding.", "Allow")) == "yes"
        if (!ok) refusals++
        return ok
    }

    companion object {
        const val MAX_REFUSALS = 3
    }
}
