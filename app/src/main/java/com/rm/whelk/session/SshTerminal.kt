package com.rm.whelk.session

import com.rm.whelk.core.Codec
import com.rm.whelk.core.Mosh
import com.rm.whelk.core.SshConnection
import com.rm.whelk.core.SshTarget
import com.rm.whelk.data.Settings
import com.rm.whelk.data.Store
import com.termux.terminal.StreamSession
import com.termux.terminal.TerminalSessionClient

/** A terminal on an SSH shell. Connects when the view first sizes it. */
class SshTerminal(
    private val session: Session,
    transcriptRows: Int,
    client: TerminalSessionClient,
) : StreamSession(transcriptRows, client) {
    @Volatile private var connection: SshConnection? = null
    @Volatile private var shell: net.schmizz.sshj.connection.channel.direct.SessionChannel? = null
    @Volatile private var size = intArrayOf(80, 24)
    @Volatile private var stopped = false

    override fun connect(columns: Int, rows: Int): Streams {
        size = intArrayOf(columns, rows)
        val vault = Store.vault.value
        val conn = session.connection ?: throw IllegalStateException("No connection")
        val target = SshTarget.from(vault, conn) ?: throw IllegalStateException("The jump hosts go round in a loop")
        if (target.host.isEmpty()) throw IllegalStateException("No host name")
        val agent = if (conn.forwardAgent) PhoneAgent(session.id, target.host) else null
        val c = SshConnection(target, SessionPrompter(session.id) { feed(it) }, Store.hostKeys, agent, SecurityKey.Signer(session.id), HardwareKeys::provider)
        connection = c
        if (stopped) throw IllegalStateException("Cancelled")
        c.connect()
        if (stopped) {
            c.close()
            throw IllegalStateException("Cancelled")
        }
        val command = conn.tmux?.takeIf { it.isNotBlank() }?.let { Mosh.tmux(it.trim()) }
        val sh = c.openShell(Settings.value.terminalType, size[0], size[1], conn.env, command)
        shell = sh
        // The view may have changed size while connecting.
        if (size[0] != columns || size[1] != rows) sh.changeWindowDimensions(size[0], size[1], 0, 0)
        session.ssh = c
        session.state.value = SessionState.OPEN
        Sessions.main.post { Sessions.changed() }
        feed("\u001b[2K\r")
        for (id in session.autoForwards.toList()) vault.forward(id)?.let { session.startForward(it) }
        vault.snippet(conn.startSnippetId)?.let { s -> Sessions.io.execute { session.typeStartSnippet(s) } }
        return Streams(Codec.input(sh.inputStream, conn.charset), Codec.output(sh.outputStream, conn.charset, conn.backspace))
    }

    override fun onResize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        size = intArrayOf(columns, rows)
        val sh = shell ?: return
        Sessions.io.execute { runCatching { sh.changeWindowDimensions(columns, rows, 0, 0) } }
    }

    override fun onFinish() {
        stopped = true
        Prompts.cancel(session.id)
        SecurityKey.cancel(session.id)
        val c = connection
        Sessions.io.execute { c?.close() }
        if (c == null) finished("\r\n[Disconnected]")
    }

    override fun onClosed() {
        connection?.close()
    }

    override fun closedMessage(error: Exception?): String {
        if (stopped) return "\r\n[Disconnected]"
        return super.closedMessage(error)
    }
}
