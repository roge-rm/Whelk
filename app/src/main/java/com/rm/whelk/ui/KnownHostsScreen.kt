package com.rm.whelk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rm.whelk.core.KnownHost
import com.rm.whelk.core.Keys
import com.rm.whelk.core.now
import com.rm.whelk.data.Store
import java.util.Base64

/** The host keys Whelk has trusted, to look over or forget. */
@Composable
fun KnownHostsScreen(nav: Nav) {
    val vault by Store.vault.collectAsState()
    var query by remember { mutableStateOf("") }
    var forgetting by remember { mutableStateOf<KnownHost?>(null) }
    val hosts = vault.knownHosts.filter { !it.deleted && it.id.contains(query.trim(), ignoreCase = true) }.sortedBy { it.id }

    FormScreen(title = "Known hosts", onBack = { nav.back() }, onSave = null) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        if (hosts.isEmpty()) EmptyNote(if (query.isBlank()) "None yet. A server's key is saved the first time you trust it." else "No matches.")
        for (h in hosts) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(h.id.removeSuffix(":22"), style = MaterialTheme.typography.bodyLarge)
                    Text(h.keyType, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val fp = runCatching { Keys.fingerprint(Base64.getDecoder().decode(h.key)) }.getOrDefault("?")
                    Text(fp, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { forgetting = h }) { Icon(Icons.Default.Delete, contentDescription = "Forget") }
            }
            HorizontalDivider()
        }
    }

    forgetting?.let { h ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${h.id.removeSuffix(":22")}?") },
            text = { Text("You'll be asked to check its key again next time.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    Store.update { it.put(h.copy(deleted = true, updated = now())) }
                    forgetting = null
                }) { Text("Forget") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}
