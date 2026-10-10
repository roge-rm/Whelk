package com.rm.whelk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.rm.whelk.session.Ask
import com.rm.whelk.session.Prompts

/** Shows the first question a session is waiting on, over whatever screen is showing. */
@Composable
fun PromptHost() {
    val pending by Prompts.pending.collectAsState()
    val p = pending.firstOrNull() ?: return
    key(p) {
        when (val ask = p.ask) {
            is Ask.HostKey -> HostKeyDialog(ask, onAnswer = { Prompts.answer(p, if (it) "yes" else null) })
            is Ask.Text -> TextDialog(ask, onAnswer = { Prompts.answer(p, it) })
            is Ask.Confirm -> AlertDialog(
                onDismissRequest = { Prompts.answer(p, null) },
                properties = DialogProperties(dismissOnClickOutside = false),
                title = { Text(ask.title) },
                text = { Text(ask.message) },
                confirmButton = { TextButton(onClick = { Prompts.answer(p, "yes") }) { Text(ask.yes) } },
                dismissButton = { TextButton(onClick = { Prompts.answer(p, null) }) { Text("Deny") } },
            )
        }
    }
}

@Composable
private fun HostKeyDialog(ask: Ask.HostKey, onAnswer: (Boolean) -> Unit) {
    val c = ask.check
    val changed = c.previousFingerprint != null
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(if (changed) "Host key changed" else "New host") },
        text = {
            Column {
                if (changed) {
                    Text(
                        "The key for ${c.host} isn't the one saved before. Someone may be in the middle, or the server was reinstalled.",
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text("First time connecting to ${c.host}:${c.port}. Check the fingerprint matches the server's.")
                }
                Text(c.keyType, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
                Text(c.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (changed) {
                    Text("Saved before", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                    Text(c.previousFingerprint!!, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(if (changed) "Trust new key" else "Trust") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Cancel") } },
    )
}

@Composable
private fun TextDialog(ask: Ask.Text, onAnswer: (String?) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(ask.title) },
        text = {
            Column {
                if (ask.message.isNotBlank()) Text(ask.message, Modifier.padding(bottom = 8.dp))
                Field(ask.label, value, { value = it }, secret = ask.secret)
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(value) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text("Cancel") } },
    )
}
