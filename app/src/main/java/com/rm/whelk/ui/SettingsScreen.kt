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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.TextButton
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.compose.ui.platform.LocalContext
import com.rm.whelk.data.Settings
import com.rm.whelk.data.Swipe
import com.rm.whelk.data.VolumeKeys
import com.rm.whelk.session.Sessions

@Composable
fun SettingsScreen(nav: Nav) {
    val p by Settings.flow.collectAsState()
    val context = LocalContext.current
    FormScreen(title = "Settings", onBack = { nav.back() }, onSave = null) {
        OutlinedButton(onClick = { nav.go(Screen.Backup) }, modifier = Modifier.fillMaxWidth()) { Text("Backup, sync and import") }
        OutlinedButton(onClick = { nav.go(Screen.KnownHosts) }, modifier = Modifier.fillMaxWidth()) { Text("Known hosts") }

        Section("Security")
        Choice("Lock", p.lockAfter, listOf(-1, 0, 1, 5, 15, 60), ::lockName) { v ->
            val can = BiometricManager.from(context).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            ) == BiometricManager.BIOMETRIC_SUCCESS
            if (v >= 0 && !can) {
                Toast.makeText(context, "Set a screen lock on the phone first", Toast.LENGTH_LONG).show()
            } else {
                Settings.update { it.copy(lockAfter = v) }
            }
        }

        SwitchRow("Ask before a server uses my keys", p.askBeforeAgent) { v -> Settings.update { it.copy(askBeforeAgent = v) } }

        Section("Terminal")
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Text size", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = { Settings.update { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(6)) } }) { Text("−") }
            Text("${p.fontSize}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { Settings.update { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(40)) } }) { Text("+") }
        }
        val fontFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val bytes = runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()
            if (bytes != null && bytes.size < 30_000_000 && Fonts.setCustom(context, bytes)) {
                Settings.update { it.copy(font = Fonts.CUSTOM) }
            } else {
                Toast.makeText(context, "That isn't a font Android can use", Toast.LENGTH_LONG).show()
            }
        }
        Choice("Font", p.font, Fonts.names(context), { it }) { f -> Settings.update { it.copy(font = f) } }
        TextButton(onClick = { fontFile.launch(arrayOf("font/*", "application/x-font-ttf", "application/octet-stream", "*/*")) }) {
            Text("Use a font file")
        }
        Choice("Colours", p.theme, TerminalThemes.all.map { it.name }, { it }) { name ->
            Settings.update { it.copy(theme = name) }
            recolour(name)
        }
        Choice("Volume keys", p.volumeKeys, VolumeKeys.values().toList(), { it.label }) { v -> Settings.update { it.copy(volumeKeys = v) } }
        Choice("Swipe sideways to switch", p.swipe, Swipe.values().toList(), { it.label }) { v -> Settings.update { it.copy(swipe = v) } }
        Choice("Scrollback lines", p.scrollback, listOf(1000, 2000, 5000, 10000, 20000), { it.toString() }) { v ->
            Settings.update { it.copy(scrollback = v) }
        }
        Choice("Terminal type", p.terminalType, listOf("xterm-256color", "xterm", "screen-256color", "tmux-256color", "linux", "vt100"), { it }) { v ->
            Settings.update { it.copy(terminalType = v) }
        }
        SwitchRow("Plain quotes and dashes when pasting", p.straightenPaste) { v -> Settings.update { it.copy(straightenPaste = v) } }
        SwitchRow("Extra keys row", p.extraKeys) { v -> Settings.update { it.copy(extraKeys = v) } }
        SwitchRow("Back key sends Esc", p.backIsEscape) { v -> Settings.update { it.copy(backIsEscape = v) } }
        SwitchRow("Vibrate on bell", p.vibrateOnBell) { v -> Settings.update { it.copy(vibrateOnBell = v) } }
        SwitchRow("Keep the screen on in a session", p.keepScreenOn) { v -> Settings.update { it.copy(keepScreenOn = v) } }
    }
}

private fun lockName(minutes: Int) = when (minutes) {
    -1 -> "Off"
    0 -> "Whenever I leave the app"
    1 -> "After 1 minute away"
    else -> "After $minutes minutes away"
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
