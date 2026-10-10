package com.rm.whelk.session

import android.content.Context
import com.rm.whelk.core.Mosh
import com.rm.whelk.core.SshConnection
import com.rm.whelk.core.SshTarget
import com.rm.whelk.data.Settings
import com.rm.whelk.data.Store
import com.termux.terminal.ProcessSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Starts a Mosh session: logs in over SSH to run mosh-server, then runs mosh-client (built into
 * the app as libmosh-client.so) on a pty with the port and key the server gave.
 */
class MoshStarter(private val context: Context, private val session: Session) : ProcessSession.Preparer {
    @Volatile private var ssh: SshConnection? = null

    override fun prepare(terminal: ProcessSession, columns: Int, rows: Int): ProcessSession.Command {
        val conn = session.connection ?: throw IllegalStateException("No connection")
        val vault = Store.vault.value
        val target = SshTarget.from(vault, conn) ?: throw IllegalStateException("The jump hosts go round in a loop")
        if (target.host.isEmpty()) throw IllegalStateException("No host name")
        val client = File(context.applicationInfo.nativeLibraryDir, "libmosh-client.so")
        if (!client.canExecute()) throw IllegalStateException("Mosh isn't built into this copy of Whelk")

        val c = SshConnection(target, SessionPrompter(session.id) { terminal.progress(it) }, Store.hostKeys, null, SecurityKey.Signer(session.id))
        ssh = c
        val connect: Mosh.Connect
        try {
            c.connect()
            terminal.progress("\u001b[2K\rStarting mosh-server…")
            var found: Mosh.Connect? = null
            var output = ""
            for (locale in Mosh.LOCALES) {
                val tmux = conn.tmux?.takeIf { it.isNotBlank() }?.let { Mosh.tmux(it.trim()) }
                output = c.exec(Mosh.serverCommand(conn.moshServer, locale, conn.env, tmux))
                found = Mosh.parse(output)
                if (found != null || !Mosh.localeRefused(output)) break
            }
            connect = found ?: throw IllegalStateException(Mosh.failure(output))
        } finally {
            c.close()
            ssh = null
        }

        // Mosh talks to the server directly over UDP, even when SSH went through a jump host.
        val address = InetAddress.getAllByName(target.host).let { all -> all.firstOrNull { it !is Inet6Address } ?: all.first() }
        terminal.progress("\u001b[2K\r")
        session.state.value = SessionState.OPEN
        Sessions.main.post { Sessions.changed() }
        vault.snippet(conn.startSnippetId)?.let { s -> session.type(if (s.enter) s.text + "\r" else s.text) }

        val env = arrayOf(
            "MOSH_KEY=${connect.key}",
            "TERM=${Settings.value.terminalType}",
            "LANG=C.UTF-8",
            "HOME=${context.filesDir.absolutePath}",
            "TMPDIR=${context.cacheDir.absolutePath}",
        )
        return ProcessSession.Command(
            client.absolutePath, context.filesDir.absolutePath,
            arrayOf("mosh-client", address.hostAddress, connect.port.toString()), env,
        )
    }

    override fun cancel() {
        Prompts.cancel(session.id)
        SecurityKey.cancel(session.id)
        ssh?.let { c -> Sessions.io.execute { c.close() } }
    }

    companion object {
        fun terminal(context: Context, session: Session, rows: Int, client: TerminalSessionClient) =
            ProcessSession(MoshStarter(context, session), rows, client)
    }
}
