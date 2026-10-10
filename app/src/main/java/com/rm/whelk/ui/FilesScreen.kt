package com.rm.whelk.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.rm.whelk.core.RemoteEntry
import com.rm.whelk.core.Sftp
import com.rm.whelk.core.SshConnection
import com.rm.whelk.core.SshTarget
import com.rm.whelk.data.Store
import com.rm.whelk.session.Prompts
import com.rm.whelk.session.SessionPrompter
import com.rm.whelk.session.Sessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The SFTP side of a connection. Uses the open session's SSH login when there is one, otherwise
 * logs in on its own and logs out when closed.
 */
private class Remote(private val connectionId: String, private val sessionId: Int?) {
    private var own: SshConnection? = null
    private var sftp: Sftp? = null
    /** For prompts while logging in, apart from any session's. */
    val promptId = -nextPromptId.incrementAndGet()

    fun open(): Sftp {
        sftp?.let { return it }
        val shared = sessionId?.let { Sessions.find(it)?.ssh }?.takeIf { it.isConnected }
        val conn = shared ?: run {
            val vault = Store.vault.value
            val c = vault.connection(connectionId) ?: throw IllegalStateException("The connection is gone")
            val target = SshTarget.from(vault, c) ?: throw IllegalStateException("The jump hosts go round in a loop")
            SshConnection(target, SessionPrompter(promptId) {}, Store.hostKeys, null, com.rm.whelk.session.SecurityKey.Signer(promptId)).also {
                own = it
                it.connect()
            }
        }
        return Sftp(conn).also { sftp = it }
    }

    fun close() {
        Prompts.cancel(promptId)
        com.rm.whelk.session.SecurityKey.cancel(promptId)
        sftp?.close()
        own?.close()
        sftp = null
        own = null
    }

    companion object {
        val nextPromptId = AtomicInteger(1000)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(nav: Nav, connectionId: String, sessionId: Int?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remote = remember { Remote(connectionId, sessionId) }
    var path by remember { mutableStateOf<String?>(null) }
    var start by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>("Connecting…") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RemoteEntry?>(null) }
    var renaming by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleting by remember { mutableStateOf<RemoteEntry?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<RemoteEntry?>(null) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var editStatus by remember { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { editing?.stop() } }
    val title = Store.vault.value.connection(connectionId)?.title(Store.vault.value) ?: "Files"

    DisposableEffect(Unit) {
        onDispose { Sessions.io.execute { remote.close() } }
    }

    /** Runs [work] off the main thread with a status line, then refreshes the listing. */
    fun run(status: String, refresh: Boolean = true, work: suspend (Sftp) -> Unit) {
        scope.launch {
            busy = status
            progress = null
            error = null
            try {
                withContext(Dispatchers.IO) {
                    val f = remote.open()
                    work(f)
                    if (refresh) {
                        val p = path ?: f.home().also { start = it }
                        path = p
                        entries = f.list(p)
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            busy = null
            progress = null
        }
    }

    fun go(p: String) {
        run("Opening…") { f ->
            val c = f.canonical(p)
            entries = f.list(c)
            path = c
        }
    }

    LaunchedEffect(Unit) { run("Connecting…") {} }

    BackHandler(enabled = path != null && path != start && Sftp.parent(path!!) != null) {
        go(Sftp.parent(path!!)!!)
    }

    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val dir = path ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        run("Uploading…") { f ->
            for (uri in uris) {
                val (name, size) = nameAndSize(context, uri)
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    f.upload(input, Sftp.join(dir, name)) { n -> if (size > 0) progress = n.toFloat() / size }
                }
            }
        }
    }

    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val e = saving ?: return@rememberLauncherForActivityResult
        saving = null
        if (uri == null) return@rememberLauncherForActivityResult
        run("Downloading…", refresh = false) { f ->
            context.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                f.download(e.path, out) { n -> if (e.size > 0) progress = n.toFloat() / e.size }
            }
        }
    }

    /** Downloads [e], opens it in an editor app, and uploads it again each time it's saved there. */
    fun edit(e: RemoteEntry) {
        editing?.stop()
        run("Downloading…", refresh = false) { f ->
            val dir = File(context.cacheDir, "sftp/edit/" + System.currentTimeMillis()).apply { mkdirs() }
            val file = File(dir, e.name)
            file.outputStream().use { out -> f.download(e.path, out) }
            withContext(Dispatchers.Main) {
                val ed = Editing(file, e) { local ->
                    scope.launch {
                        editStatus = "Uploading ${e.name}…"
                        val ok = withContext(Dispatchers.IO) { runCatching { local.inputStream().use { remote.open().upload(it, e.path) } } }
                        editStatus = if (ok.isSuccess) "Saved ${e.name} to the server" else "Couldn't upload: ${ok.exceptionOrNull()?.message}"
                    }
                }
                editing = ed
                editStatus = "Editing ${e.name}. Saving it uploads it."
                editFile(context, file)
            }
        }
    }

    fun open(e: RemoteEntry) {
        if (e.isDir) {
            go(e.path)
            return
        }
        run("Downloading…", refresh = false) { f ->
            val dir = File(context.cacheDir, "sftp").apply { mkdirs() }
            val file = File(dir, e.name)
            file.outputStream().use { out -> f.download(e.path, out) { n -> if (e.size > 0) progress = n.toFloat() / e.size } }
            withContext(Dispatchers.Main) { view(context, file) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.combinedClickableCompat(onClick = { goTo = true })) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(path ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { upload.launch(arrayOf("*/*")) }, enabled = path != null) { Icon(Icons.Default.Add, "Upload") }
                    IconButton(onClick = { path?.let { go(it) } }, enabled = path != null) { Icon(Icons.Default.Refresh, "Refresh") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("New folder") }, onClick = { menu = false; newFolder = true })
                            DropdownMenuItem(text = { Text("Go to…") }, onClick = { menu = false; goTo = true })
                            DropdownMenuItem(text = { Text(if (showHidden) "Hide dot files" else "Show dot files") }, onClick = { menu = false; showHidden = !showHidden })
                            start?.let { home -> DropdownMenuItem(text = { Text("Home folder") }, onClick = { menu = false; go(home) }) }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            busy?.let { b ->
                if (progress != null) LinearProgressIndicator(progress = { progress!! }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(b, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            if (editing != null) {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(editStatus, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { editing?.let { editFile(context, it.file) } }) { Text("Open again") }
                    TextButton(onClick = { editing?.stop(); editing = null }) { Text("Done") }
                }
            }
            val shown = entries.filter { showHidden || !it.name.startsWith(".") }
            LazyColumn(Modifier.fillMaxSize()) {
                val parent = path?.let { Sftp.parent(it) }
                if (parent != null) {
                    item {
                        ListItem(
                            headlineContent = { Text("..") },
                            supportingContent = { Text("Up a folder") },
                            modifier = Modifier.combinedClickableCompat(onClick = { go(parent) }),
                        )
                    }
                }
                items(shown, key = { it.path }) { e ->
                    Box {
                        ListItem(
                            headlineContent = { Text(if (e.isDir) e.name + "/" else e.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                            supportingContent = {
                                val parts = buildList {
                                    if (!e.isDir) add(Formatter.formatShortFileSize(context, e.size))
                                    if (e.modified > 0) add(DateUtils.formatDateTime(context, e.modified, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE or DateUtils.FORMAT_SHOW_YEAR))
                                    add(e.permissions)
                                    if (e.isLink) add("link")
                                }
                                Text(parts.joinToString(" · "))
                            },
                            modifier = Modifier.combinedClickableCompat(onClick = { open(e) }, onLongClick = { selected = e }),
                        )
                        DropdownMenu(expanded = selected == e, onDismissRequest = { selected = null }) {
                            if (!e.isDir) {
                                DropdownMenuItem(text = { Text("Open") }, onClick = { selected = null; open(e) })
                                DropdownMenuItem(text = { Text("Edit") }, onClick = { selected = null; edit(e) })
                                DropdownMenuItem(text = { Text("Save to phone") }, onClick = { selected = null; saving = e; saveTo.launch(e.name) })
                            }
                            DropdownMenuItem(text = { Text("Copy path") }, onClick = {
                                selected = null
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Path", e.path))
                                Toast.makeText(context, "Path copied", Toast.LENGTH_SHORT).show()
                            })
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { selected = null; renaming = e })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { selected = null; deleting = e })
                        }
                    }
                }
                if (busy == null && error == null && shown.isEmpty()) item { EmptyNote("Nothing here.") }
            }
        }
    }

    renaming?.let { e ->
        NameDialog("Rename", e.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            val dir = Sftp.parent(e.path) ?: "/"
            run("Renaming…") { f -> f.rename(e.path, Sftp.join(dir, name)) }
        }
    }
    if (newFolder) {
        NameDialog("New folder", "", onDismiss = { newFolder = false }) { name ->
            newFolder = false
            val dir = path ?: return@NameDialog
            run("Making the folder…") { f -> f.mkdir(Sftp.join(dir, name)) }
        }
    }
    if (goTo) {
        NameDialog("Go to", path ?: "", onDismiss = { goTo = false }) { p ->
            goTo = false
            go(p)
        }
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${e.name}?") },
            text = { if (e.isDir && !e.isLink) Text("Everything in it goes too.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    run("Deleting…") { f -> f.delete(e) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Field("Name", text, { text = it }) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onDone(text.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun nameAndSize(context: Context, uri: Uri): Pair<String, Long> {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val name = c.getString(0) ?: "upload"
            val size = if (c.isNull(1)) -1L else c.getLong(1)
            return name to size
        }
    }
    return (uri.lastPathSegment ?: "upload") to -1L
}

private fun view(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val ext = file.extension.lowercase()
    val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "text/plain"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, file.name))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Nothing on the phone opens that", Toast.LENGTH_SHORT).show()
    }
}

/**
 * A downloaded file being edited in another app. Uploads after each save, once the file has
 * settled for a moment.
 */
private class Editing(val file: File, val entry: RemoteEntry, private val upload: (File) -> Unit) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastModified = file.lastModified()
    private val settle = Runnable {
        if (file.lastModified() != lastModified) {
            lastModified = file.lastModified()
            upload(file)
        }
    }

    @Suppress("DEPRECATION")
    private val observer = object : android.os.FileObserver(file.path, CLOSE_WRITE or MODIFY or MOVED_TO) {
        override fun onEvent(event: Int, path: String?) {
            handler.removeCallbacks(settle)
            handler.postDelayed(settle, 800)
        }
    }.also { it.startWatching() }

    fun stop() {
        observer.stopWatching()
        handler.removeCallbacks(settle)
    }
}

private fun editFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "text/plain"
    val intent = Intent(Intent.ACTION_EDIT).setDataAndType(uri, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, file.name))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app on the phone edits that", Toast.LENGTH_SHORT).show()
    }
}
