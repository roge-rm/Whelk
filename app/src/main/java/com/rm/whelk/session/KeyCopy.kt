package com.rm.whelk.session

import android.content.Context
import android.widget.Toast
import com.rm.whelk.core.Connection
import com.rm.whelk.core.KeyInstall
import com.rm.whelk.core.SshConnection
import com.rm.whelk.core.SshTarget
import com.rm.whelk.data.Store
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Puts a connection's public key on its server, like ssh-copy-id. Logs in without the key, as it
 * isn't there yet, so it asks for the password if none is saved.
 */
object KeyCopy {
    private val nextPromptId = AtomicInteger(5000)

    /** The key line to install for [connection], or null if its identity has none. */
    fun keyFor(connection: Connection): String? =
        Store.vault.value.identity(connection.identityId)?.publicKey?.takeIf { it.isNotBlank() }

    fun install(context: Context, connection: Connection) {
        val app = context.applicationContext
        fun toast(text: String) {
            Sessions.main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
        }
        val line = keyFor(connection) ?: return toast("This connection's identity has no key")
        val promptId = -nextPromptId.incrementAndGet()
        toast("Copying the key to ${connection.host}…")
        thread(name = "KeyCopy") {
            val message = try {
                val target = SshTarget.from(Store.vault.value, connection) ?: throw IllegalStateException("The jump hosts go round in a loop")
                val withoutKey = target.copy(privateKey = null, passphrase = null, certificate = null, hardwareKey = null)
                SshConnection(withoutKey, SessionPrompter(promptId) {}, Store.hostKeys, null, SecurityKey.Signer(promptId), HardwareKeys::provider).use { c ->
                    c.connect()
                    val out = c.exec(KeyInstall.command(line))
                    when {
                        KeyInstall.ALREADY in out -> "The key was already on ${connection.host}"
                        KeyInstall.installed(out) -> "Key copied to ${connection.host}"
                        else -> "Couldn't copy the key: " + out.trim().lines().lastOrNull().orEmpty().ifEmpty { "no reply" }
                    }
                }
            } catch (e: Exception) {
                if (e is com.rm.whelk.core.CancelledException || e.message == "Cancelled") null
                else "Couldn't copy the key: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                Prompts.cancel(promptId)
            }
            message?.let { toast(it) }
        }
    }
}
