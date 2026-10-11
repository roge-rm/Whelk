package com.rm.whelk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rm.whelk.core.ExtraKey
import com.rm.whelk.data.Settings

/** The row of keys above the keyboard: reorder, remove, add built in keys or your own text. */
@Composable
fun ExtraKeysScreen(nav: Nav) {
    val prefs by Settings.flow.collectAsState()
    val keys = prefs.extraKeyList
    var adding by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }

    fun set(list: List<ExtraKey>) = Settings.update { it.copy(extraKeyList = list) }

    FormScreen(title = "Extra keys", onBack = { nav.back() }, onSave = null) {
        for ((i, k) in keys.withIndex()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(k.label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                    val typed = k.text
                    if (typed != null && typed != k.label) {
                        Text(typed, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { if (i > 0) set(keys.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move left")
                }
                IconButton(onClick = { if (i < keys.size - 1) set(keys.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < keys.size - 1) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move right")
                }
                IconButton(onClick = { set(keys - k) }) { Icon(Icons.Default.Close, contentDescription = "Remove") }
            }
            HorizontalDivider()
        }
        Row(Modifier.padding(top = 12.dp)) {
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { adding = true }) { Text("Add a key") }
            Gap()
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { custom = true }) { Text("Add your own") }
            Gap()
            TextButton(onClick = { set(ExtraKeys.defaults) }) { Text("Reset") }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add a key") },
            text = {
                androidx.compose.foundation.layout.FlowRow {
                    for ((id, key) in ExtraKeys.builtIn) {
                        TextButton(onClick = {
                            set(keys + ExtraKey(key.label, id = id))
                            adding = false
                        }) { Text(key.label, fontFamily = FontFamily.Monospace) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }

    if (custom) {
        var label by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text("Your own key") },
            text = {
                Column {
                    Field("Label", label, { label = it })
                    Field("Types", text, { text = it }, monospace = true)
                    Text("\\e Esc, \\t Tab, \\n Enter, ^C Ctrl+C", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotEmpty()) set(keys + ExtraKey(label.ifEmpty { text }, text = text))
                    custom = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { custom = false }) { Text("Cancel") } },
        )
    }
}
