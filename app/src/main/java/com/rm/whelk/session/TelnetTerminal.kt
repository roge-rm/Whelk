package com.rm.whelk.session

import com.rm.whelk.core.Codec
import com.rm.whelk.core.Telnet
import com.rm.whelk.data.Settings
import com.termux.terminal.StreamSession
import com.termux.terminal.TerminalSessionClient

/** A terminal on a Telnet connection. */
class TelnetTerminal(
    private val session: Session,
    transcriptRows: Int,
    client: TerminalSessionClient,
) : StreamSession(transcriptRows, client) {
    @Volatile private var telnet: Telnet? = null
    @Volatile private var stopped = false

    override fun connect(columns: Int, rows: Int): Streams {
        val conn = session.connection ?: throw IllegalStateException("No connection")
        feed("Connecting to ${conn.host}:${conn.port}…")
        val t = Telnet(conn.host.trim(), conn.port, Settings.value.terminalType)
        telnet = t
        if (stopped) {
            t.close()
            throw IllegalStateException("Cancelled")
        }
        t.resize(columns, rows)
        feed("\u001b[2K\r")
        session.state.value = SessionState.OPEN
        Sessions.main.post { Sessions.changed() }
        return Streams(Codec.input(t.input, conn.charset), Codec.output(t.output, conn.charset, conn.backspace))
    }

    override fun onResize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        val t = telnet ?: return
        Sessions.io.execute { runCatching { t.resize(columns, rows) } }
    }

    override fun onFinish() {
        stopped = true
        val t = telnet
        Sessions.io.execute { t?.close() }
        if (t == null) finished("\r\n[Disconnected]")
    }

    override fun onClosed() {
        telnet?.close()
    }

    override fun closedMessage(error: Exception?): String {
        if (stopped) return "\r\n[Disconnected]"
        return super.closedMessage(error)
    }
}
