package com.rm.whelk.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rm.whelk.data.Settings
import com.rm.whelk.data.VolumeKeys
import com.rm.whelk.session.Sessions

@Composable
fun SettingsScreen(nav: Nav) {
    val p by Settings.flow.collectAsState()
    FormScreen(title = "Settings", onBack = { nav.back() }, onSave = null) {
        Section("Terminal")
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Text size", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = { Settings.update { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(6)) } }) { Text("−") }
            Text("${p.fontSize}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { Settings.update { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(40)) } }) { Text("+") }
        }
        Choice("Colours", p.theme, TerminalThemes.all.map { it.name }, { it }) { name ->
            Settings.update { it.copy(theme = name) }
            recolour(name)
        }
        Choice("Volume keys", p.volumeKeys, VolumeKeys.values().toList(), { it.label }) { v -> Settings.update { it.copy(volumeKeys = v) } }
        Choice("Scrollback lines", p.scrollback, listOf(1000, 2000, 5000, 10000, 20000), { it.toString() }) { v ->
            Settings.update { it.copy(scrollback = v) }
        }
        Choice("Terminal type", p.terminalType, listOf("xterm-256color", "xterm", "screen-256color", "tmux-256color", "linux", "vt100"), { it }) { v ->
            Settings.update { it.copy(terminalType = v) }
        }
        SwitchRow("Extra keys row", p.extraKeys) { v -> Settings.update { it.copy(extraKeys = v) } }
        SwitchRow("Back key sends Esc", p.backIsEscape) { v -> Settings.update { it.copy(backIsEscape = v) } }
        SwitchRow("Vibrate on bell", p.vibrateOnBell) { v -> Settings.update { it.copy(vibrateOnBell = v) } }
        SwitchRow("Keep the screen on in a session", p.keepScreenOn) { v -> Settings.update { it.copy(keepScreenOn = v) } }
    }
}

/** Gives open sessions that use the default colours the new ones. */
private fun recolour(name: String) {
    val theme = TerminalThemes.named(name)
    TerminalThemes.apply(theme)
    for (s in Sessions.list.value) {
        if (s.connection?.theme != null) continue
        val emulator = s.terminal.emulator ?: continue
        emulator.mColors.reset()
        Sessions.onScreenUpdate?.invoke(s.terminal)
    }
}
