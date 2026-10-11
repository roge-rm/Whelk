package com.rm.whelk.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.rm.whelk.core.Imported
import com.rm.whelk.core.Importer
import com.rm.whelk.core.KnownHostsFile
import com.rm.whelk.core.Sealed
import com.rm.whelk.core.Vault
import com.rm.whelk.core.merge
import com.rm.whelk.data.Store
import com.rm.whelk.data.Sync
import com.rm.whelk.data.SyncTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the passphrase dialog is for. */
private sealed class Pending {
    class Export(val uri: Uri) : Pending()
    class Restore(val uri: Uri) : Pending()
    class SyncFolder(val uri: Uri) : Pending()
    class ShareGroup(val uri: Uri, val groupId: String) : Pending()
    class JoinTeam(val uri: Uri, val file: String) : Pending()
}

@Composable
fun BackupScreen(nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault by Store.vault.collectAsState()
    val targets by Sync.targets.collectAsState()
    val running by Sync.running.collectAsState()
    var pending by remember { mutableStateOf<Pending?>(null) }
    var shareGroupId by remember { mutableStateOf<String?>(null) }
    var joinFolder by remember { mutableStateOf<Uri?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var restored by remember { mutableStateOf<Pair<Uri, Vault>?>(null) }
    var removing by remember { mutableStateOf<SyncTarget?>(null) }
    var imported by remember { mutableStateOf<Imported?>(null) }

    fun showImport(result: Result<Imported>) {
        result.onSuccess { if (it.isEmpty) message = "Nothing to import in that" else imported = it }
            .onFailure { message = "Couldn't read it: ${it.message}" }
    }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            showImport(withContext(Dispatchers.IO) {
                runCatching {
                    val name = DocumentFile.fromSingleUri(context, uri)?.name.orEmpty()
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    if (bytes.size > 4_000_000) throw IllegalArgumentException("too big")
                    Importer.read(name, bytes)
                }
            })
        }
    }
    val importFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            showImport(withContext(Dispatchers.IO) {
                runCatching {
                    val dir = DocumentFile.fromTreeUri(context, uri) ?: throw IllegalStateException("Can't open the folder")
                    val files = dir.listFiles().filter { it.isFile && it.length() < 1_000_000 }.associateBy { it.name.orEmpty() }
                    fun text(name: String) = files[name]?.let { f -> context.contentResolver.openInputStream(f.uri)?.use { it.readBytes() } }
                    val keys: (String) -> String? = { n -> text(n)?.toString(Charsets.UTF_8)?.takeIf { it.contains("PRIVATE KEY") || it.startsWith("PuTTY-User-Key-File") } }
                    var all = Imported()
                    text("config")?.let { all += Importer.read("config", it, keys) }
                    text("known_hosts")?.let { all += KnownHostsFile.parse(it.toString(Charsets.UTF_8)) }
                    for (reg in files.keys.filter { it.endsWith(".reg", ignoreCase = true) }) text(reg)?.let { all += Importer.read(reg, it, keys) }
                    all
                }
            })
        }
    }

    fun keep(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    fun folderName(uri: Uri) = DocumentFile.fromTreeUri(context, uri)?.name ?: "Folder"

    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) pending = Pending.Export(uri)
    }
    val restoreFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pending = Pending.Restore(uri)
    }
    val syncFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) pending = Pending.SyncFolder(uri)
    }
    val teamFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val g = shareGroupId
        if (uri != null && g != null) pending = Pending.ShareGroup(uri, g)
    }
    val joinPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) joinFolder = uri
    }

    FormScreen(title = "Backup, sync and import", onBack = { nav.back() }, onSave = null) {
        Section("Backup")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = {
                val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                exportTo.launch("whelk-backup-$day.whelk")
            }) { Text("Export backup") }
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { restoreFrom.launch(arrayOf("*/*")) }) { Text("Restore backup") }
        }

        Section("Import")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { importFile.launch(arrayOf("*/*")) }) { Text("Import a file") }
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { importFolder.launch(null) }) { Text("Import an .ssh folder") }
        }

        Section("Sync")
        val personal = targets.filter { !it.team }
        for (t in personal) TargetRow(t, "Everything", onRemove = { removing = t })
        if (personal.isEmpty()) {
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { syncFolder.launch(null) }) { Text("Sync through a folder") }
        }

        Section("Teams")
        val teams = targets.filter { it.team }
        for (t in teams) {
            val name = vault.group(t.groupId)?.name?.ifEmpty { "Group" } ?: "Waiting for the first sync"
            TargetRow(t, name, onRemove = { removing = t })
        }
        val shareable = vault.liveGroups().filter { g -> teams.none { it.groupId == g.id } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (g in shareable) {
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = {
                    shareGroupId = g.id
                    teamFolder.launch(null)
                }) { Text("Share ${g.name.ifEmpty { "group" }}") }
            }
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { joinPicker.launch(null) }) { Text("Join a team") }
        }

        if (targets.isNotEmpty()) {
            Row(Modifier.padding(top = 16.dp)) {
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { Sync.syncAll() }, enabled = !running) { Text(if (running) "Syncing…" else "Sync now") }
            }
        }
    }

    // Picking a team file within the chosen folder.
    joinFolder?.let { folder ->
        val files = remember(folder) {
            DocumentFile.fromTreeUri(context, folder)?.listFiles()
                ?.mapNotNull { it.name }?.filter { it.endsWith(".whelk") && !it.contains("conflict") }?.sorted()
                ?: emptyList()
        }
        AlertDialog(
            onDismissRequest = { joinFolder = null },
            title = { Text("Team file") },
            text = {
                Column {
                    if (files.isEmpty()) Text("No .whelk files in that folder.")
                    for (f in files) {
                        TextButton(onClick = {
                            joinFolder = null
                            pending = Pending.JoinTeam(folder, f)
                        }, modifier = Modifier.fillMaxWidth()) { Text(f) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { joinFolder = null }) { Text("Cancel") } },
        )
    }

    pending?.let { p ->
        val confirm = p is Pending.Export || p is Pending.SyncFolder || p is Pending.ShareGroup
        PassphraseDialog(
            title = when (p) {
                is Pending.Export -> "Passphrase for the backup"
                is Pending.Restore -> "Backup passphrase"
                is Pending.SyncFolder -> "Passphrase for the sync file"
                is Pending.ShareGroup -> "Passphrase for the team"
                is Pending.JoinTeam -> "Team passphrase"
            },
            confirm = confirm,
            onDismiss = { pending = null },
        ) { pass ->
            pending = null
            when (p) {
                is Pending.Export -> scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val sealed = Sealed.seal(Store.vault.value.toJson().toByteArray(), pass.toCharArray())
                            context.contentResolver.openOutputStream(p.uri, "wt")!!.use { it.write(sealed) }
                        }
                    }
                    message = if (ok.isSuccess) "Backup saved" else "Couldn't save: ${ok.exceptionOrNull()?.message}"
                }
                is Pending.Restore -> scope.launch {
                    val read = withContext(Dispatchers.IO) {
                        runCatching {
                            val bytes = context.contentResolver.openInputStream(p.uri)!!.use { it.readBytes() }
                            Vault.fromJson(Sealed.open(bytes, pass.toCharArray()).toString(Charsets.UTF_8))
                        }
                    }
                    read.onSuccess { restored = p.uri to it }
                        .onFailure { message = if (it is Sealed.WrongPassphrase) "Wrong passphrase, or the file is damaged" else "Couldn't read it: ${it.message}" }
                }
                is Pending.SyncFolder -> {
                    keep(p.uri)
                    Sync.add(SyncTarget(folder = p.uri.toString(), folderName = folderName(p.uri), file = "whelk-sync.whelk", passphrase = pass))
                }
                is Pending.ShareGroup -> {
                    keep(p.uri)
                    val name = vault.group(p.groupId)?.name?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")?.trim('-')?.ifEmpty { null } ?: "group"
                    Sync.add(
                        SyncTarget(
                            folder = p.uri.toString(), folderName = folderName(p.uri), file = "whelk-team-$name.whelk",
                            passphrase = pass, team = true, groupId = p.groupId,
                        )
                    )
                }
                is Pending.JoinTeam -> {
                    keep(p.uri)
                    Sync.add(SyncTarget(folder = p.uri.toString(), folderName = folderName(p.uri), file = p.file, passphrase = pass, team = true))
                }
            }
        }
    }

    restored?.let { (_, backup) ->
        AlertDialog(
            onDismissRequest = { restored = null },
            title = { Text("Restore backup") },
            text = { Text("${backup.liveConnections().size} connections, ${backup.liveIdentities().size} identities. Add them to what's here, or replace everything?") },
            confirmButton = {
                TextButton(onClick = {
                    Store.update { it.merge(backup) }
                    restored = null
                    message = "Backup added"
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = {
                    Store.update { backup }
                    restored = null
                    message = "Backup restored"
                }) { Text("Replace") }
            },
        )
    }

    imported?.let { r ->
        AlertDialog(
            onDismissRequest = { imported = null },
            title = { Text("Import") },
            text = {
                Column {
                    val parts = buildList {
                        if (r.connections.isNotEmpty()) add(plural(r.connections.size, "connection"))
                        if (r.identities.isNotEmpty()) add(plural(r.identities.size, "identity", "identities"))
                        if (r.forwards.isNotEmpty()) add(plural(r.forwards.size, "port forward"))
                        if (r.knownHosts.isNotEmpty()) add(plural(r.knownHosts.size, "host key"))
                    }
                    Text(parts.joinToString(", ") + ". Ones already here are left as they are.")
                    if (r.skipped.isNotEmpty()) {
                        Text("Not imported:", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                        for (s in r.skipped.take(8)) Text(s, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Store.update { r.addTo(it) }
                    imported = null
                    message = "Imported"
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { imported = null }) { Text("Cancel") } },
        )
    }

    removing?.let { t ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Stop syncing?") },
            text = { Text("Nothing is deleted.") },
            confirmButton = { TextButton(onClick = { Sync.remove(t.id); removing = null }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }

    message?.let {
        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        message = null
    }
}

private fun plural(n: Int, one: String, many: String = one + "s") = "$n " + if (n == 1) one else many

@Composable
private fun TargetRow(t: SyncTarget, what: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(what, style = MaterialTheme.typography.bodyLarge)
            Text("${t.folderName}/${t.file}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                t.lastError != null -> Text(t.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                t.lastSync > 0 -> Text(
                    "Synced " + DateUtils.getRelativeTimeSpanString(t.lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onRemove) { Text("Stop") }
    }
    HorizontalDivider()
}

@Composable
private fun PassphraseDialog(title: String, confirm: Boolean, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Field("Passphrase", a, { a = it; error = null }, secret = true)
                if (confirm) Field("Again", b, { b = it; error = null }, secret = true, error = error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    a.length < 8 && confirm -> error = "At least 8 characters"
                    confirm && a != b -> error = "Doesn't match"
                    else -> onDone(a)
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
