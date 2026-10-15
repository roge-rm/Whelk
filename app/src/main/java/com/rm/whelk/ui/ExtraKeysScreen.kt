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

/** The keys above the keyboard: reorder, remove, add built in keys or your own text. Two rows are edited apart. */
@Composable
fun ExtraKeysScreen(nav: Nav) {
    val prefs by Settings.flow.collectAsState()
    // The row being added to: 0 the single row or the top one, 1 the bottom one.
    var adding by remember { mutableStateOf<Int?>(null) }
    var custom by remember { mutableStateOf<Int?>(null) }

    fun keys(row: Int) = when {
        !prefs.twoRowKeys -> prefs.extraKeyList
        row == 0 -> prefs.extraKeyTop
        else -> prefs.extraKeyBottom
    }
    fun set(row: Int, list: List<ExtraKey>) = Settings.update {
        when {
            !it.twoRowKeys -> it.copy(extraKeyList = list)
            row == 0 -> it.copy(extraKeyTop = list)
            else -> it.copy(extraKeyBottom = list)
        }
    }

    FormScreen(title = "Extra keys", onBack = { nav.back() }, onSave = null) {
        val rows = if (prefs.twoRowKeys) listOf(0, 1) else listOf(0)
        for (row in rows) {
            if (prefs.twoRowKeys) Section(if (row == 0) "Top row" else "Bottom row")
            val list = keys(row)
            for ((i, k) in list.withIndex()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(k.label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                        val typed = k.text
                        if (typed != null && typed != k.label) {
                            Text(typed, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { if (i > 0) set(row, list.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move left")
                    }
                    IconButton(onClick = { if (i < list.size - 1) set(row, list.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < list.size - 1) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move right")
                    }
                    IconButton(onClick = { set(row, list.toMutableList().apply { removeAt(i) }) }) { Icon(Icons.Default.Close, contentDescription = "Remove") }
                }
                HorizontalDivider()
            }
            Row(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { adding = row }) { Text("Add a key") }
                Gap()
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { custom = row }) { Text("Add your own") }
            }
        }
        TextButton(onClick = {
            Settings.update {
                if (it.twoRowKeys) it.copy(extraKeyTop = ExtraKeys.defaultTop, extraKeyBottom = ExtraKeys.defaultBottom)
                else it.copy(extraKeyList = ExtraKeys.defaults)
            }
        }) { Text("Reset") }
    }

    adding?.let { row ->
        AlertDialog(
            onDismissRequest = { adding = null },
            title = { Text("Add a key") },
            text = {
                androidx.compose.foundation.layout.FlowRow {
                    for ((id, key) in ExtraKeys.builtIn) {
                        TextButton(onClick = {
                            set(row, keys(row) + ExtraKey(key.label, id = id))
                            adding = null
                        }) { Text(key.label, fontFamily = FontFamily.Monospace) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { adding = null }) { Text("Cancel") } },
        )
    }

    custom?.let { row ->
        var label by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { custom = null },
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
                    if (text.isNotEmpty()) set(row, keys(row) + ExtraKey(label.ifEmpty { text }, text = text))
                    custom = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { custom = null }) { Text("Cancel") } },
        )
    }
}
