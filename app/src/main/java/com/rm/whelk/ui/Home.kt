package com.rm.whelk.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.combine
import com.rm.whelk.core.Connection
import com.rm.whelk.core.ConnectionType
import com.rm.whelk.core.Vault
import com.rm.whelk.core.newId
import com.rm.whelk.core.now
import com.rm.whelk.data.Store
import com.rm.whelk.session.Session
import com.rm.whelk.session.SessionState
import com.rm.whelk.session.Sessions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: Nav, tab: Tab) {
    val vault by Store.vault.collectAsState()
    Scaffold(
        topBar = {
            WhelkTopBar(
                title = if (tab == Tab.CONNECTIONS) "Whelk" else tab.label,
                actions = {
                    IconButton(onClick = { nav.go(Screen.Settings) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                for (t in Tab.values()) {
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { nav.swap(Screen.Home(t)) },
                        icon = { Icon(t.icon(), contentDescription = null) },
                        label = { Text(t.short()) },
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                onClick = {
                nav.go(
                    when (tab) {
                        Tab.CONNECTIONS -> Screen.EditConnection(null)
                        Tab.IDENTITIES -> Screen.EditIdentity(null)
                        Tab.SNIPPETS -> Screen.EditSnippet(null)
                        Tab.FORWARDS -> Screen.EditForward(null)
                    }
                )
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.CONNECTIONS -> ConnectionsTab(nav, vault)
                Tab.IDENTITIES -> IdentitiesTab(nav, vault)
                Tab.SNIPPETS -> SnippetsTab(nav, vault)
                Tab.FORWARDS -> ForwardsTab(nav, vault)
            }
        }
    }
}

private fun Tab.icon(): ImageVector = when (this) {
    Tab.CONNECTIONS -> Icons.AutoMirrored.Filled.List
    Tab.IDENTITIES -> Icons.Default.Person
    Tab.SNIPPETS -> Icons.Default.Create
    Tab.FORWARDS -> Icons.Default.Share
}

private fun Tab.short(): String = when (this) {
    Tab.FORWARDS -> "Forwards"
    else -> label
}

/** Opens a session for the connection and shows it. */
fun connect(nav: Nav, context: android.content.Context, connection: Connection?) {
    val s = Sessions.open(context, connection)
    nav.go(Screen.Terminal(s.id))
}

@Composable
private fun ConnectionsTab(nav: Nav, vault: Vault) {
    val context = LocalContext.current
    val sessions by Sessions.list.collectAsState()
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Connection?>(null) }

    val all = vault.liveConnections()
    val shown = all.filter { c ->
        (group == null || group in c.groupIds) &&
            (query.isBlank() || c.title(vault).contains(query, ignoreCase = true) || c.host.contains(query, ignoreCase = true))
    }.sortedBy { it.title(vault).lowercase() }
    // With no search or group picked, the five used last come first and the rest wait behind a button.
    val browsing = query.isBlank() && group == null
    val recent = if (browsing) {
        all.sortedWith(compareByDescending<Connection> { it.lastUsed }.thenBy { it.title(vault).lowercase() }).take(RECENT)
    } else emptyList()
    val rest = if (browsing) shown.filter { c -> recent.none { it.id == c.id } } else shown
    var showAll by rememberSaveable { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        if (sessions.isNotEmpty()) {
            header("Open sessions")
            items(sessions, key = { "s" + it.id }) { s -> SessionRow(s, onOpen = { nav.go(Screen.Terminal(s.id)) }) }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search, or user@host to connect") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        val groups = vault.liveGroups().sortedBy { it.name.lowercase() }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = group == null, onClick = { group = null }, label = { Text("All") })
                for (g in groups) {
                    FilterChip(
                        selected = group == g.id,
                        onClick = { group = if (group == g.id) null else g.id },
                        label = { Text(g.name.ifEmpty { "Group" }) },
                        leadingIcon = if (g.colour != 0L) {
                            { Box(Modifier.size(10.dp).background(Color(g.colour), CircleShape)) }
                        } else null,
                    )
                }
                FilterChip(selected = false, onClick = { nav.go(Screen.EditGroup(null)) }, label = { Text("New group") })
                if (group != null) {
                    FilterChip(selected = false, onClick = { nav.go(Screen.EditGroup(group)) }, label = { Text("Edit group") })
                }
            }
        }
        quickConnect(query)?.let { q ->
            item(key = "quick") {
                ListItem(
                    headlineContent = { Text("Connect to ${q.label}") },
                    supportingContent = { Text("Saved to your connections") },
                    leadingContent = {
                        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    },
                    modifier = Modifier.combinedClickableCompat(onClick = {
                        val c = saveQuick(q)
                        query = ""
                        connect(nav, context, c)
                    }),
                )
            }
        }
        val localShell: LazyListScope.() -> Unit = {
            item(key = "local") { ConnectionRow(null, vault, nav, onConnect = { connect(nav, context, null) }, onDelete = {}) }
        }
        if (!browsing) localShell()
        if (recent.isNotEmpty()) {
            header("Recent")
            items(recent, key = { "r" + it.id }) { c ->
                ConnectionRow(c, vault, nav, onConnect = { connect(nav, context, c) }, onDelete = { deleting = c })
            }
        }
        if (browsing) {
            item(key = "all") {
                TextButton(
                    onClick = { showAll = !showAll },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(if (showAll) "Show fewer" else "Show ${rest.size + 1} more", Modifier.weight(1f))
                    Icon(if (showAll) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
                }
            }
        }
        if (browsing && showAll) localShell()
        if (!browsing || showAll) {
            items(rest, key = { it.id }) { c ->
                ConnectionRow(c, vault, nav, onConnect = { connect(nav, context, c) }, onDelete = { deleting = c })
            }
        }
        if (all.isEmpty()) {
            item {
                Text(
                    "Add a connection with the + button.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        item { Spacer(Modifier.size(80.dp)) }
    }

    deleting?.let { c ->
        ConfirmDelete(c.title(vault), onDismiss = { deleting = null }) {
            Store.update { v ->
                v.put(c.copy(deleted = true, updated = now()))
                    .let { vv -> vv.liveForwards().filter { it.connectionId == c.id }.fold(vv) { acc, f -> acc.put(f.copy(deleted = true, updated = now())) } }
            }
            deleting = null
        }
    }
}

private const val RECENT = 5

fun LazyListScope.header(text: String) {
    item(key = "h$text") {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.5.sp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun SessionRow(s: Session, onOpen: () -> Unit) {
    val state by s.state.collectAsState()
    val forwards by s.forwards.collectAsState()
    ListItem(
        headlineContent = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val parts = buildList {
                add(
                    when (state) {
                        SessionState.CONNECTING -> "Connecting"
                        SessionState.OPEN -> "Connected"
                        SessionState.CLOSED -> "Disconnected"
                    }
                )
                if (forwards.isNotEmpty()) add(if (forwards.size == 1) "1 forward" else "${forwards.size} forwards")
            }
            Text(parts.joinToString(" · "))
        },
        leadingContent = {
            val colour = when (state) {
                SessionState.CONNECTING -> MaterialTheme.colorScheme.secondary
                SessionState.OPEN -> MaterialTheme.colorScheme.primary
                SessionState.CLOSED -> MaterialTheme.colorScheme.outline
            }
            Box(Modifier.size(10.dp).background(colour, CircleShape))
        },
        trailingContent = {
            IconButton(onClick = { Sessions.close(s) }) { Icon(Icons.Default.Close, contentDescription = "Disconnect") }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.combinedClickableCompat(onClick = onOpen),
    )
}

@Composable
private fun ConnectionRow(c: Connection?, vault: Vault, nav: Nav, onConnect: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val sessions by Sessions.list.collectAsState()
    val subtitle = when {
        c == null || c.type == ConnectionType.LOCAL -> "local"
        c.type == ConnectionType.SERIAL -> "usb " + c.line.describe()
        c.type == ConnectionType.BLUETOOTH -> "bt " + c.device
        else -> buildString {
            append(if (c.type == ConnectionType.TCP) "tcp" else c.type.label.lowercase())
            append(" ")
            vault.identity(c.identityId)?.username?.takeIf { it.isNotEmpty() }?.let { append(it).append("@") }
            append(c.host)
            if (c.port != c.type.defaultPort) append(":").append(c.port)
            vault.connection(c.viaId)?.let { append(" via ").append(it.title(vault)) }
        }
    }
    val groupColour = c?.groupIds?.firstNotNullOfOrNull { vault.group(it)?.colour?.takeIf { col -> col != 0L } }
    // A dot while any of its sessions is connecting or connected.
    val open by remember(sessions, c?.id) {
        val states = sessions.filter { c != null && it.connection?.id == c.id }.map { it.state }
        combine(states) { all -> all.any { it != SessionState.CLOSED } }
    }.collectAsState(false)
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c?.title(vault) ?: "Local shell", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (open) Box(Modifier.padding(start = 8.dp).size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
        },
        supportingContent = {
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = monoFamily(), style = MaterialTheme.typography.bodyMedium)
        },
        leadingContent = {
            Box(
                Modifier.size(width = 4.dp, height = 40.dp).background(
                    if (groupColour != null) Color(groupColour) else MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        },
        trailingContent = if (c != null) {
            {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; nav.go(Screen.EditConnection(c.id)) })
                        if (Shortcuts.canPin(LocalContext.current)) {
                            val ctx = LocalContext.current
                            DropdownMenuItem(text = { Text("Add to home screen") }, onClick = { menu = false; Shortcuts.pin(ctx, vault, c) })
                        }
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                            menu = false
                            Store.update { it.put(c.copy(id = newId(), name = if (c.name.isEmpty()) "" else c.name + " copy", lastUsed = 0, updated = now())) }
                        })
                        if (c.type == ConnectionType.SSH || c.type == ConnectionType.MOSH) {
                            DropdownMenuItem(text = { Text("Browse files") }, onClick = { menu = false; nav.go(Screen.Files(c.id)) })
                        }
                        if (c.type == ConnectionType.SSH) {
                            DropdownMenuItem(text = { Text("Add port forward") }, onClick = { menu = false; nav.go(Screen.EditForward(null, c.id)) })
                        }
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        } else null,
        modifier = Modifier.combinedClickableCompat(onClick = onConnect, onLongClick = if (c != null) { { nav.go(Screen.EditConnection(c.id)) } } else null),
    )
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

@Composable
fun ConfirmDelete(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $name?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun IdentitiesTab(nav: Nav, vault: Vault) {
    var deleting by remember { mutableStateOf<com.rm.whelk.core.Identity?>(null) }
    val list = vault.liveIdentities().sortedBy { it.title().lowercase() }
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { i ->
            val used = vault.liveConnections().count { it.identityId == i.id }
            ListItem(
                headlineContent = { Text(i.title()) },
                supportingContent = {
                    val parts = buildList {
                        if (i.username.isNotEmpty()) add(i.username)
                        if (!i.privateKey.isNullOrBlank()) add("key")
                        if (!i.password.isNullOrEmpty()) add("password")
                        add(if (used == 1) "1 connection" else "$used connections")
                    }
                    Text(parts.joinToString(" · "))
                },
                trailingContent = {
                    IconButton(onClick = { deleting = i }) { Icon(Icons.Default.Close, contentDescription = "Delete") }
                },
                modifier = Modifier.combinedClickableCompat(onClick = { nav.go(Screen.EditIdentity(i.id)) }),
            )
        }
        if (list.isEmpty()) item { EmptyNote("A username with a password or key, for any number of connections.") }
        item { Spacer(Modifier.size(80.dp)) }
    }
    deleting?.let { i ->
        ConfirmDelete(i.title(), onDismiss = { deleting = null }) {
            Store.update { it.put(i.copy(deleted = true, password = null, privateKey = null, passphrase = null, updated = now())) }
            deleting = null
        }
    }
}

@Composable
fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun SnippetsTab(nav: Nav, vault: Vault) {
    val list = vault.liveSnippets().sortedBy { it.name.lowercase() }
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { s ->
            ListItem(
                headlineContent = { Text(s.name.ifEmpty { "Snippet" }) },
                supportingContent = {
                    Text(s.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                },
                modifier = Modifier.combinedClickableCompat(onClick = { nav.go(Screen.EditSnippet(s.id)) }),
            )
        }
        if (list.isEmpty()) item { EmptyNote("Commands to type into a session from its menu.") }
        item { Spacer(Modifier.size(80.dp)) }
    }
}

@Composable
private fun ForwardsTab(nav: Nav, vault: Vault) {
    val context = LocalContext.current
    val sessions by Sessions.list.collectAsState()
    val list = vault.liveForwards().sortedWith(compareBy({ vault.connection(it.connectionId)?.title(vault)?.lowercase() }, { it.title().lowercase() }))
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { f ->
            val conn = vault.connection(f.connectionId)
            val session = sessions.firstOrNull { it.connection?.id == f.connectionId && it.state.value != SessionState.CLOSED }
            val running = session?.forwards?.collectAsState()?.value?.containsKey(f.id) == true
            val error = session?.forwardErrors?.collectAsState()?.value?.get(f.id)
            ListItem(
                headlineContent = { Text(f.title()) },
                supportingContent = {
                    Column {
                        Text(listOfNotNull(f.kind.label, conn?.title(vault) ?: "No connection", if (f.name.isNotEmpty()) f.describe() else null).joinToString(" · "))
                        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                    }
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    if (running && f.kind == com.rm.whelk.core.ForwardKind.LOCAL) {
                        TextButton(onClick = { openForward(context, f) }) { Text("Open") }
                    }
                    Switch(
                        checked = running,
                        enabled = conn != null,
                        onCheckedChange = { on ->
                            if (on) {
                                if (session != null && session.ssh != null) {
                                    session.startForward(f)
                                } else if (session != null) {
                                    session.autoForwards += f.id
                                } else if (conn != null) {
                                    Sessions.open(context, conn, setOf(f.id))
                                }
                            } else {
                                session?.stopForward(f.id)
                                session?.autoForwards?.remove(f.id)
                            }
                        },
                    )
                    }
                },
                modifier = Modifier.combinedClickableCompat(onClick = { nav.go(Screen.EditForward(f.id)) }),
            )
            HorizontalDivider()
        }
        if (list.isEmpty()) item { EmptyNote("Local, remote and SOCKS forwards over a connection.") }
        item { Spacer(Modifier.size(80.dp)) }
    }
}

@Composable
fun Gap() = Spacer(Modifier.width(8.dp))

/** A user@host:port typed in the search box, to connect to without making a connection first. */
data class QuickTarget(val user: String, val host: String, val port: Int) {
    val label: String get() = (if (user.isEmpty()) "" else "$user@") + host + (if (port != 22) ":$port" else "")
}

private val QUICK = Regex("""^(?:([^@\s]+)@)?(\[[0-9a-fA-F:]+]|[A-Za-z0-9.-]+)(?::(\d{1,5}))?$""")

fun quickConnect(query: String): QuickTarget? {
    val q = query.trim()
    // A bare word is a search; it takes an @, a dot or a port to look like a host.
    if (q.isEmpty() || !(q.contains('@') || q.contains('.') || q.contains(':'))) return null
    val m = QUICK.find(q) ?: return null
    val port = m.groupValues[3].toIntOrNull() ?: 22
    if (port !in 1..65535) return null
    return QuickTarget(m.groupValues[1], m.groupValues[2].removeSurrounding("[", "]"), port)
}

/** Saves a quick connection, reusing a login with the same username and nothing else. */
fun saveQuick(q: QuickTarget): Connection {
    var conn: Connection? = null
    Store.update { v ->
        val existing = v.liveConnections().firstOrNull { c ->
            c.host.equals(q.host, true) && c.port == q.port && c.type == ConnectionType.SSH &&
                v.identity(c.identityId)?.username.orEmpty() == q.user
        }
        if (existing != null) {
            conn = existing
            return@update v
        }
        var vv = v
        val identity = if (q.user.isEmpty()) null else v.liveIdentities().firstOrNull {
            it.username == q.user && it.privateKey == null && it.password == null && it.name.isEmpty()
        } ?: com.rm.whelk.core.Identity(username = q.user).also { vv = vv.put(it) }
        val c = Connection(host = q.host, port = q.port, identityId = identity?.id)
        conn = c
        vv.put(c)
    }
    return conn!!
}

/** Opens a local forward's port in the browser. */
fun openForward(context: android.content.Context, f: com.rm.whelk.core.PortForward) {
    val scheme = if (f.destPort == 443 || f.destPort == 8443) "https" else "http"
    val host = if (f.bindHost == "0.0.0.0" || f.bindHost.isEmpty()) "127.0.0.1" else f.bindHost
    try {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("$scheme://$host:${f.bindPort}/")))
    } catch (e: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "No browser to open it with", android.widget.Toast.LENGTH_SHORT).show()
    }
}
