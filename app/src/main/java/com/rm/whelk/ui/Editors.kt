package com.rm.whelk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rm.whelk.core.Connection
import com.rm.whelk.core.Backspace
import com.rm.whelk.core.Codec
import com.rm.whelk.core.ConnectionType
import com.rm.whelk.core.EnterSends
import com.rm.whelk.core.FlowControl
import com.rm.whelk.core.LineSettings
import com.rm.whelk.core.Parity
import com.rm.whelk.core.StopBits
import com.rm.whelk.session.Bluetooth
import com.rm.whelk.session.Usb
import com.rm.whelk.core.ForwardKind
import com.rm.whelk.core.Group
import com.rm.whelk.core.Identity
import com.rm.whelk.core.KeyKind
import com.rm.whelk.core.Keys
import com.rm.whelk.core.PortForward
import com.rm.whelk.core.Snippet
import com.rm.whelk.core.now
import com.rm.whelk.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The prompts and security key job for making a key on a security key, apart from any session's. */
private const val MAKE_KEY_PROMPTS = -2

private fun portOf(text: String, fallback: Int): Int = text.trim().toIntOrNull()?.takeIf { it in 0..65535 } ?: fallback

@Composable
fun EditConnectionScreen(nav: Nav, id: String?) {
    val vault by Store.vault.collectAsState()
    val original = remember { vault.connection(id) ?: Connection() }
    var name by remember { mutableStateOf(original.name) }
    var type by remember { mutableStateOf(original.type) }
    var host by remember { mutableStateOf(original.host) }
    var port by remember { mutableStateOf(original.port.toString()) }
    var identityId by remember { mutableStateOf(original.identityId) }
    var viaId by remember { mutableStateOf(original.viaId) }
    var groupIds by remember { mutableStateOf(original.groupIds) }
    var snippetId by remember { mutableStateOf(original.startSnippetId) }
    var keepAlive by remember { mutableStateOf(original.keepAlive.toString()) }
    var compression by remember { mutableStateOf(original.compression) }
    var theme by remember { mutableStateOf(original.theme) }
    var forwardAgent by remember { mutableStateOf(original.forwardAgent) }
    var moshServer by remember { mutableStateOf(original.moshServer.orEmpty()) }
    var line by remember { mutableStateOf(original.line) }
    var charset by remember { mutableStateOf(original.charset) }
    var backspace by remember { mutableStateOf(original.backspace) }
    var envText by remember { mutableStateOf(original.env.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
    var tmux by remember { mutableStateOf(original.tmux.orEmpty()) }
    var proxy by remember { mutableStateOf(original.proxy) }
    var watchText by remember { mutableStateOf(original.watch.joinToString(", ")) }
    var device by remember { mutableStateOf(original.device) }
    var deviceName by remember { mutableStateOf(original.deviceName) }
    var deviceError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var hostError by remember { mutableStateOf<String?>(null) }

    // A quick identity can be typed in here when there's none to pick.
    var quickUser by remember { mutableStateOf("") }
    var quickPassword by remember { mutableStateOf("") }

    fun save() {
        if (type.hasHost && host.isBlank()) {
            hostError = "Needs a host"
            return
        }
        if (type == ConnectionType.BLUETOOTH && device.isEmpty()) {
            deviceError = "Pick a device"
            return
        }
        var ident = identityId
        if (ident == null && quickUser.isNotBlank() && (type == ConnectionType.SSH || type == ConnectionType.MOSH)) {
            val newIdentity = Identity(name = "", username = quickUser.trim(), password = quickPassword.ifEmpty { null })
            Store.update { it.put(newIdentity) }
            ident = newIdentity.id
        }
        val c = original.copy(
            name = name.trim(),
            type = type,
            host = host.trim(),
            port = portOf(port, type.defaultPort),
            identityId = ident,
            viaId = viaId,
            groupIds = groupIds,
            startSnippetId = snippetId,
            keepAlive = keepAlive.trim().toIntOrNull()?.coerceIn(0, 3600) ?: 30,
            compression = compression,
            theme = theme,
            forwardAgent = forwardAgent && type == ConnectionType.SSH,
            moshServer = moshServer.trim().ifEmpty { null },
            line = line,
            charset = charset,
            backspace = backspace,
            env = envText.lines().mapNotNull { l ->
                val i = l.indexOf('=')
                if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1)
            }.filter { it.first.isNotEmpty() }.toMap(),
            tmux = tmux.trim().ifEmpty { null }.takeIf { type == ConnectionType.SSH || type == ConnectionType.MOSH },
            proxy = if (type == ConnectionType.SSH || type == ConnectionType.MOSH) proxy else com.rm.whelk.core.ProxySettings(),
            watch = watchText.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            device = if (type == ConnectionType.SERIAL || type == ConnectionType.BLUETOOTH) device else "",
            deviceName = if (type == ConnectionType.SERIAL || type == ConnectionType.BLUETOOTH) deviceName else "",
            updated = now(),
        )
        Store.update { it.put(c) }
        nav.back()
    }

    FormScreen(
        title = if (id == null) "New connection" else "Edit connection",
        onBack = { nav.back() },
        onSave = ::save,
        onDelete = if (id != null) { { deleting = true } } else null,
    ) {
        Choice("Type", type, ConnectionType.values().toList(), { it.label }) {
            if (port.toIntOrNull() == type.defaultPort || port.isBlank()) port = it.defaultPort.toString()
            type = it
        }
        Field("Nickname", name, { name = it })
        if (type.hasHost) {
            Field("Host", host, { host = it; hostError = null }, error = hostError)
            Field("Port", port, { port = it.filter(Char::isDigit) }, number = true)
        }
        if (type == ConnectionType.SERIAL) {
            UsbChoice(device, deviceName) { id, n -> device = id; deviceName = n }
        }
        if (type == ConnectionType.BLUETOOTH) {
            BluetoothChoice(device, deviceName, deviceError) { a, n -> device = a; deviceName = n; deviceError = null }
        }
        if (type.isLine) LineEditor(type, line) { line = it }
        if (type == ConnectionType.SSH || type == ConnectionType.MOSH) {
            val identities = vault.liveIdentities().sortedBy { it.title().lowercase() }
            Choice("Identity", identityId, listOf<String?>(null) + identities.map { it.id }, { i ->
                if (i == null) "None" else vault.identity(i)?.let { "${it.title()} (${it.username})" } ?: "None"
            }) { identityId = it }
            if (identityId == null) {
                Field("Username", quickUser, { quickUser = it })
                Field("Password", quickPassword, { quickPassword = it }, secret = true)
            }
            TextButton(onClick = { nav.go(Screen.EditIdentity(null)) }) { Text("New identity") }

            val others = vault.liveConnections().filter { it.id != original.id && it.type == ConnectionType.SSH }
            Choice("Connect via", viaId, listOf<String?>(null) + others.map { it.id }, { v ->
                if (v == null) "Directly" else vault.connection(v)?.title(vault) ?: "Directly"
            }) { viaId = it }
        }
        if (type != ConnectionType.LOCAL || vault.liveSnippets().isNotEmpty()) {
            val snippets = vault.liveSnippets()
            if (snippets.isNotEmpty()) {
                Choice("Run snippet on connect", snippetId, listOf<String?>(null) + snippets.map { it.id }, { s ->
                    if (s == null) "None" else vault.snippet(s)?.name?.ifEmpty { "Snippet" } ?: "None"
                }) { snippetId = it }
            }
        }

        val groups = vault.liveGroups().sortedBy { it.name.lowercase() }
        if (groups.isNotEmpty()) {
            Section("Groups")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (g in groups) {
                    FilterChip(
                        selected = g.id in groupIds,
                        onClick = { groupIds = if (g.id in groupIds) groupIds - g.id else groupIds + g.id },
                        label = { Text(g.name.ifEmpty { "Group" }) },
                    )
                }
            }
        }

        if (type != ConnectionType.LOCAL) {
            Choice("Encoding", charset, Codec.CHARSETS, { it }) { charset = it }
            Choice("Backspace sends", backspace, Backspace.values().toList(), { it.label }) { backspace = it }
        }
        Choice("Colours", theme, listOf<String?>(null) + TerminalThemes.names(vault), { it ?: "Same as settings" }) { theme = it }
        Field("Notify me when these show up, comma between", watchText, { watchText = it })

        if (type == ConnectionType.SSH || type == ConnectionType.MOSH) {
            Section("Advanced")
            Field("Attach to tmux session", tmux, { tmux = it })
            Field("Environment variables, NAME=value", envText, { envText = it }, singleLine = false, minLines = 2, monospace = true)
            Field("Keepalive seconds (0 for off)", keepAlive, { keepAlive = it.filter(Char::isDigit) }, number = true)
            SwitchRow("Compression", compression) { compression = it }
            if (type == ConnectionType.SSH) SwitchRow("Forward agent", forwardAgent) { forwardAgent = it }
            Choice("Proxy", proxy.type, com.rm.whelk.core.ProxyType.values().toList(), { it.label }) { proxy = proxy.copy(type = it) }
            if (proxy.type != com.rm.whelk.core.ProxyType.NONE) {
                Field("Proxy host", proxy.host, { proxy = proxy.copy(host = it.trim()) })
                Field("Proxy port", proxy.port.toString(), { t -> proxy = proxy.copy(port = t.filter(Char::isDigit).toIntOrNull()?.coerceIn(1, 65535) ?: proxy.port) }, number = true)
                Field("Proxy username", proxy.username, { proxy = proxy.copy(username = it) })
                if (proxy.type != com.rm.whelk.core.ProxyType.SOCKS4) Field("Proxy password", proxy.password, { proxy = proxy.copy(password = it) }, secret = true)
            }
            if (type == ConnectionType.MOSH) Field("Mosh server command", moshServer, { moshServer = it }, monospace = true)
        }
    }

    if (deleting) {
        ConfirmDelete(original.title(vault), onDismiss = { deleting = false }) {
            Store.update { it.put(original.copy(deleted = true, updated = now())) }
            nav.back()
        }
    }
}

@Composable
fun EditIdentityScreen(nav: Nav, id: String?) {
    val context = LocalContext.current
    val vault by Store.vault.collectAsState()
    val original = remember { vault.identity(id) ?: Identity() }
    var name by remember { mutableStateOf(original.name) }
    var username by remember { mutableStateOf(original.username) }
    var password by remember { mutableStateOf(original.password.orEmpty()) }
    var privateKey by remember { mutableStateOf(original.privateKey.orEmpty()) }
    var publicKey by remember { mutableStateOf(original.publicKey.orEmpty()) }
    var passphrase by remember { mutableStateOf(original.passphrase.orEmpty()) }
    var certificate by remember { mutableStateOf(original.certificate.orEmpty()) }
    var pastingCert by remember { mutableStateOf(false) }
    var makingSk by remember { mutableStateOf(false) }
    var keyError by remember { mutableStateOf<String?>(null) }
    var generating by remember { mutableStateOf(false) }
    var chooseKind by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun comment() = username.ifEmpty { "whelk" } + "@whelk"

    fun takeKey(text: String) {
        val key = text.trim() + "\n"
        try {
            publicKey = Keys.publicLineFor(key, passphrase.ifEmpty { null }, comment())
            privateKey = key
            keyError = null
        } catch (e: Exception) {
            if (Keys.isEncrypted(key) && passphrase.isEmpty()) {
                privateKey = key
                publicKey = ""
                keyError = "Enter the key's passphrase below"
            } else {
                keyError = e.message ?: "Not a key Whelk can read"
            }
        }
    }

    val importKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            if (text == null || text.length > 64_000) keyError = "Can't read that file" else takeKey(text)
        }
    }

    val importCert = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            if (text == null || text.length > 64_000) keyError = "Can't read that file"
            else takeCertificate(text)?.let { keyError = it } ?: run { certificate = text.trim(); keyError = null }
        }
    }

    fun save() {
        if (privateKey.isNotBlank() && publicKey.isBlank()) {
            // Check the passphrase now rather than at connect time.
            try {
                publicKey = Keys.publicLineFor(privateKey, passphrase.ifEmpty { null }, comment())
            } catch (e: Exception) {
                if (passphrase.isNotEmpty()) {
                    keyError = "Wrong passphrase"
                    return
                }
            }
        }
        val i = original.copy(
            name = name.trim(),
            username = username.trim(),
            password = password.ifEmpty { null },
            privateKey = privateKey.ifBlank { null },
            publicKey = publicKey.ifBlank { null },
            passphrase = passphrase.ifEmpty { null },
            certificate = certificate.trim().ifEmpty { null }.takeIf { privateKey.isNotBlank() },
            updated = now(),
        )
        Store.update { it.put(i) }
        nav.back()
    }

    FormScreen(
        title = if (id == null) "New identity" else "Edit identity",
        onBack = { nav.back() },
        onSave = ::save,
        onDelete = if (id != null) { { deleting = true } } else null,
    ) {
        Field("Nickname", name, { name = it })
        Field("Username", username, { username = it })
        Field("Password", password, { password = it }, secret = true)

        Section("Private key")
        if (privateKey.isNotBlank()) {
            val fp = Keys.fingerprintOfLine(publicKey)
            Text(
                publicKey.substringBefore(' ').ifEmpty { "Key" } + (fp?.let { "\n$it" } ?: ""),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Field("Key passphrase", passphrase, { passphrase = it; keyError = null }, secret = true)
        }
        keyError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp)) }
        if (generating) {
            Row(Modifier.padding(8.dp)) { CircularProgressIndicator(Modifier.size(24.dp)); Gap(); Text("Making a key…") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { chooseKind = true }, enabled = !generating) { Text("Generate") }
            OutlinedButton(onClick = { makingSk = true }, enabled = !generating) { Text("Security key") }
            OutlinedButton(onClick = { importKey.launch(arrayOf("*/*")) }) { Text("Import file") }
            OutlinedButton(onClick = { pasting = true }) { Text("Paste") }
            if (publicKey.isNotBlank()) {
                OutlinedButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Public key", publicKey))
                    Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy public key") }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, publicKey)
                    context.startActivity(Intent.createChooser(send, "Share public key"))
                }) { Text("Share public key") }
            }
            if (privateKey.isNotBlank()) {
                OutlinedButton(onClick = { privateKey = ""; publicKey = ""; passphrase = ""; certificate = ""; keyError = null }) { Text("Remove key") }
            }
        }

        if (privateKey.isNotBlank()) {
            Section("Certificate")
            if (certificate.isNotBlank()) {
                Text(
                    certificate.trim().substringBefore(' '),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importCert.launch(arrayOf("*/*")) }) { Text(if (certificate.isBlank()) "Import file" else "Replace") }
                OutlinedButton(onClick = { pastingCert = true }) { Text("Paste") }
                if (certificate.isNotBlank()) OutlinedButton(onClick = { certificate = "" }) { Text("Remove") }
            }
        }
    }

    if (makingSk) {
        var ed25519 by remember { mutableStateOf(true) }
        var verify by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { makingSk = false },
            title = { Text("Make a key on a security key") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Choice("Type", ed25519, listOf(true, false), { if (it) "Ed25519" else "ECDSA (older keys)" }) { ed25519 = it }
                    SwitchRow("Ask for the key's PIN each time", verify) { verify = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    makingSk = false
                    generating = true
                    val id = MAKE_KEY_PROMPTS
                    scope.launch {
                        val made = withContext(Dispatchers.IO) {
                            runCatching {
                                com.rm.whelk.session.SecurityKey.make(id, ed25519, verify, comment()) {
                                    com.rm.whelk.session.Prompts.ask(com.rm.whelk.session.Ask.Text(id, "Security key", "", "PIN", secret = true))
                                }
                            }
                        }
                        generating = false
                        made.onSuccess {
                            privateKey = it.privateKey
                            publicKey = it.publicKey
                            passphrase = ""
                            certificate = ""
                            keyError = null
                        }.onFailure { if (it.message != "Cancelled") keyError = it.message ?: "That didn't work" }
                    }
                }) { Text("Make") }
            },
            dismissButton = { TextButton(onClick = { makingSk = false }) { Text("Cancel") } },
        )
    }

    if (pastingCert) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pastingCert = false },
            title = { Text("Certificate") },
            text = { Field("The line from the -cert.pub file", text, { text = it }, singleLine = false, minLines = 4, monospace = true) },
            confirmButton = {
                TextButton(onClick = {
                    pastingCert = false
                    takeCertificate(text)?.let { keyError = it } ?: run { certificate = text.trim() }
                }) { Text("Use") }
            },
            dismissButton = { TextButton(onClick = { pastingCert = false }) { Text("Cancel") } },
        )
    }

    if (chooseKind) {
        AlertDialog(
            onDismissRequest = { chooseKind = false },
            title = { Text("Key type") },
            text = {
                androidx.compose.foundation.layout.Column {
                    for (k in KeyKind.values()) {
                        TextButton(onClick = {
                            chooseKind = false
                            generating = true
                            scope.launch {
                                val made = withContext(Dispatchers.Default) { runCatching { Keys.generate(k, comment()) } }
                                generating = false
                                made.onSuccess {
                                    privateKey = it.privateKey
                                    publicKey = it.publicKey
                                    passphrase = ""
                                    keyError = null
                                }.onFailure { keyError = it.message }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text(k.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { chooseKind = false }) { Text("Cancel") } },
        )
    }

    if (pasting) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text("Paste private key") },
            text = { Field("Private key", text, { text = it }, singleLine = false, minLines = 6, monospace = true) },
            confirmButton = { TextButton(onClick = { pasting = false; takeKey(text) }) { Text("Use") } },
            dismissButton = { TextButton(onClick = { pasting = false }) { Text("Cancel") } },
        )
    }

    if (deleting) {
        ConfirmDelete(original.title(), onDismiss = { deleting = false }) {
            Store.update { it.put(original.copy(deleted = true, password = null, privateKey = null, passphrase = null, updated = now())) }
            nav.back()
        }
    }
}

@Composable
fun EditSnippetScreen(nav: Nav, id: String?) {
    val vault by Store.vault.collectAsState()
    val original = remember { vault.snippet(id) ?: Snippet() }
    var name by remember { mutableStateOf(original.name) }
    var text by remember { mutableStateOf(original.text) }
    var enter by remember { mutableStateOf(original.enter) }
    var deleting by remember { mutableStateOf(false) }

    FormScreen(
        title = if (id == null) "New snippet" else "Edit snippet",
        onBack = { nav.back() },
        onSave = {
            Store.update { it.put(original.copy(name = name.trim(), text = text, enter = enter, updated = now())) }
            nav.back()
        },
        onDelete = if (id != null) { { deleting = true } } else null,
    ) {
        Field("Name", name, { name = it })
        Field("Command", text, { text = it }, singleLine = false, minLines = 4, monospace = true)
        SwitchRow("Press Enter after", enter) { enter = it }
    }
    if (deleting) {
        ConfirmDelete(original.name.ifEmpty { "snippet" }, onDismiss = { deleting = false }) {
            Store.update { it.put(original.copy(deleted = true, updated = now())) }
            nav.back()
        }
    }
}

@Composable
fun EditForwardScreen(nav: Nav, id: String?, connectionId: String?) {
    val vault by Store.vault.collectAsState()
    val original = remember { vault.forward(id) ?: PortForward(connectionId = connectionId ?: "") }
    var name by remember { mutableStateOf(original.name) }
    var conn by remember { mutableStateOf(original.connectionId) }
    var kind by remember { mutableStateOf(original.kind) }
    var bindHost by remember { mutableStateOf(original.bindHost) }
    var bindPort by remember { mutableStateOf(original.bindPort.toString()) }
    var destHost by remember { mutableStateOf(original.destHost) }
    var destPort by remember { mutableStateOf(original.destPort.toString()) }
    var autoStart by remember { mutableStateOf(original.autoStart) }
    var deleting by remember { mutableStateOf(false) }
    var connError by remember { mutableStateOf<String?>(null) }

    val sshConnections = vault.liveConnections().filter { it.type == ConnectionType.SSH }

    FormScreen(
        title = if (id == null) "New port forward" else "Edit port forward",
        onBack = { nav.back() },
        onSave = {
            if (vault.connection(conn) == null) {
                connError = "Pick a connection"
            } else {
                Store.update {
                    it.put(
                        original.copy(
                            name = name.trim(), connectionId = conn, kind = kind,
                            bindHost = bindHost.trim().ifEmpty { "127.0.0.1" }, bindPort = portOf(bindPort, original.bindPort),
                            destHost = destHost.trim().ifEmpty { "localhost" }, destPort = portOf(destPort, original.destPort),
                            autoStart = autoStart, updated = now(),
                        )
                    )
                }
                nav.back()
            }
        },
        onDelete = if (id != null) { { deleting = true } } else null,
    ) {
        Field("Name", name, { name = it })
        Choice("Connection", conn.ifEmpty { null }, sshConnections.map { it.id }, { c ->
            c?.let { vault.connection(it)?.title(vault) } ?: "Pick a connection"
        }) { conn = it ?: ""; connError = null }
        connError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Choice("Type", kind, ForwardKind.values().toList(), { it.label }) {
            if (it == ForwardKind.REMOTE && bindHost == "127.0.0.1") bindHost = "localhost"
            kind = it
        }
        Section(if (kind == ForwardKind.REMOTE) "Listen on the server" else "Listen on this device")
        Field("Address", bindHost, { bindHost = it })
        Field("Port", bindPort, { bindPort = it.filter(Char::isDigit) }, number = true)
        if (kind != ForwardKind.DYNAMIC) {
            Section(if (kind == ForwardKind.REMOTE) "Connect to, from this device" else "Connect to, from the server")
            Field("Host", destHost, { destHost = it })
            Field("Port", destPort, { destPort = it.filter(Char::isDigit) }, number = true)
        }
        SwitchRow("Start when the connection opens", autoStart) { autoStart = it }
    }
    if (deleting) {
        ConfirmDelete(original.title(), onDismiss = { deleting = false }) {
            Store.update { it.put(original.copy(deleted = true, updated = now())) }
            nav.back()
        }
    }
}

private val groupColours = listOf(
    0L, 0xFFE57373, 0xFFFFB74D, 0xFFFFF176, 0xFF81C784, 0xFF4DD0E1, 0xFF64B5F6, 0xFFBA68C8, 0xFFA1887F, 0xFF90A4AE,
)

@Composable
fun EditGroupScreen(nav: Nav, id: String?) {
    val vault by Store.vault.collectAsState()
    val original = remember { vault.group(id) ?: Group() }
    var name by remember { mutableStateOf(original.name) }
    var colour by remember { mutableStateOf(original.colour) }
    var deleting by remember { mutableStateOf(false) }

    FormScreen(
        title = if (id == null) "New group" else "Edit group",
        onBack = { nav.back() },
        onSave = {
            Store.update { it.put(original.copy(name = name.trim(), colour = colour, updated = now())) }
            nav.back()
        },
        onDelete = if (id != null) { { deleting = true } } else null,
    ) {
        Field("Name", name, { name = it })
        Section("Colour")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (c in groupColours) {
                Box(
                    Modifier.size(36.dp)
                        .background(if (c == 0L) MaterialTheme.colorScheme.surfaceVariant else Color(c), CircleShape)
                        .border(if (c == colour) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        .combinedClickableCompat(onClick = { colour = c })
                )
            }
        }
    }
    if (deleting) {
        ConfirmDelete(original.name.ifEmpty { "group" }, onDismiss = { deleting = false }) {
            Store.update { v ->
                v.liveConnections().filter { original.id in it.groupIds }
                    .fold(v) { acc, c -> acc.put(c.copy(groupIds = c.groupIds - original.id, updated = now())) }
                    .put(original.copy(deleted = true, updated = now()))
            }
            nav.back()
        }
    }
}

@Composable
private fun UsbChoice(device: String, deviceName: String, onPick: (String, String) -> Unit) {
    val context = LocalContext.current
    var adapters by remember { mutableStateOf(Usb.adapters(context).map { it.id to it.name }) }
    val options = listOf("" to "Any adapter") + adapters +
        (if (device.isNotEmpty() && adapters.none { it.first == device }) listOf(device to deviceName.ifEmpty { device }) else emptyList())
    Choice("Adapter", device, options.map { it.first }, { id -> options.firstOrNull { it.first == id }?.second ?: id }) { id ->
        onPick(id, options.firstOrNull { it.first == id && id.isNotEmpty() }?.second.orEmpty())
    }
    TextButton(onClick = { adapters = Usb.adapters(context).map { it.id to it.name } }) {
        Text(if (adapters.isEmpty()) "None plugged in. Look again" else "Look again")
    }
}

@Composable
private fun BluetoothChoice(address: String, deviceName: String, error: String?, onPick: (String, String) -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(Bluetooth.allowed(context)) }
    var paired by remember { mutableStateOf(Bluetooth.paired(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        allowed = ok
        paired = Bluetooth.paired(context)
    }
    if (!allowed) {
        OutlinedButton(onClick = { ask.launch(android.Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Allow Bluetooth") }
        return
    }
    val options = paired.map { it.address to it.name } +
        (if (address.isNotEmpty() && paired.none { it.address == address }) listOf(address to deviceName.ifEmpty { address }) else emptyList())
    if (options.isEmpty()) {
        Text("No paired devices. Pair one in the phone's Bluetooth settings first.", modifier = Modifier.padding(vertical = 8.dp))
        TextButton(onClick = { paired = Bluetooth.paired(context) }) { Text("Look again") }
        return
    }
    Choice("Device", address.ifEmpty { null }, options.map { it.first }, { a ->
        options.firstOrNull { it.first == a }?.let { "${it.second} (${it.first})" } ?: "Pick a device"
    }) { a -> if (a != null) onPick(a, options.first { it.first == a }.second) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun LineEditor(type: ConnectionType, line: LineSettings, onChange: (LineSettings) -> Unit) {
    if (type == ConnectionType.SERIAL) {
        Section("Line")
        var custom by remember { mutableStateOf(line.baud !in LineSettings.BAUDS) }
        Choice("Baud", if (custom) -1 else line.baud, LineSettings.BAUDS + -1, { if (it == -1) "Other" else it.toString() }) {
            if (it == -1) custom = true else { custom = false; onChange(line.copy(baud = it)) }
        }
        if (custom) {
            Field("Baud rate", line.baud.toString(), { t -> t.filter(Char::isDigit).toIntOrNull()?.let { onChange(line.copy(baud = it)) } }, number = true)
        }
        Choice("Data bits", line.dataBits, listOf(8, 7, 6, 5), { it.toString() }) { onChange(line.copy(dataBits = it)) }
        Choice("Parity", line.parity, Parity.values().toList(), { it.label }) { onChange(line.copy(parity = it)) }
        Choice("Stop bits", line.stopBits, StopBits.values().toList(), { it.label }) { onChange(line.copy(stopBits = it)) }
        Choice("Flow control", line.flow, FlowControl.values().toList(), { it.label }) { onChange(line.copy(flow = it)) }
    } else {
        Section("Line")
    }
    Choice("Enter sends", line.enter, EnterSends.values().toList(), { it.label }) { onChange(line.copy(enter = it)) }
    SwitchRow("Show what I type", line.localEcho) { onChange(line.copy(localEcho = it)) }
}

/** Checks a certificate line, returning a reason it won't do, or null if it's fine. */
private fun takeCertificate(text: String): String? {
    val parts = text.trim().split(Regex("\\s+"))
    if (parts.size < 2 || !parts[0].contains("-cert-v01@openssh.com")) return "That isn't an OpenSSH certificate"
    return try {
        java.util.Base64.getDecoder().decode(parts[1])
        null
    } catch (e: IllegalArgumentException) {
        "That isn't an OpenSSH certificate"
    }
}
