package com.rm.whelk.session

import com.rm.whelk.core.HostKeyCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking

/** A question for the user from a connecting session. */
sealed class Ask {
    abstract val sessionId: Int

    data class HostKey(override val sessionId: Int, val check: HostKeyCheck) : Ask()

    data class Confirm(
        override val sessionId: Int,
        val title: String,
        val message: String,
        val yes: String,
        val no: String = "Deny",
    ) : Ask()

    data class Text(
        override val sessionId: Int,
        val title: String,
        val message: String,
        val label: String,
        val secret: Boolean,
    ) : Ask()
}

class Pending(val ask: Ask) {
    internal val answer = CompletableDeferred<String?>()
}

/**
 * Questions waiting for the user. Sessions ask from their own threads and block; the UI shows the
 * first waiting question and answers it.
 */
object Prompts {
    private val state = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = state

    /** Blocks until answered. Null means cancelled. */
    fun ask(ask: Ask): String? {
        val p = Pending(ask)
        state.update { it + p }
        return try {
            runBlocking { p.answer.await() }
        } finally {
            state.update { it - p }
        }
    }

    fun answer(p: Pending, value: String?) {
        p.answer.complete(value)
    }

    /** Cancels everything a session is waiting on, when it closes. */
    fun cancel(sessionId: Int) {
        state.value.filter { it.ask.sessionId == sessionId }.forEach { it.answer.complete(null) }
    }
}
