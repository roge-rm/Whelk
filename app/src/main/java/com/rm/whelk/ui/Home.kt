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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
            TopAppBar(
                title = { Text(if (tab == Tab.CONNECTIONS) "Whelk" else tab.label) },
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
            FloatingActionButton(onClick = {
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
    val recent = if (query.isBlank() && group == null && all.size > 5) {
        all.filter { it.lastUsed > 0 }.sortedByDescending { it.lastUsed }.take(3)
    } else emptyList()

    LazyColumn(Modifier.fillMaxSize()) {
        if (sessions.isNotEmpty()) {
            header("Open sessions")
            items(sessions, key = { "s" + it.id }) { s -> SessionRow(s, onOpen = { nav.go(Screen.Terminal(s.id)) }) }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search") },
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
        item {
            ConnectionRow(null, vault, nav, onConnect = { connect(nav, context, null) }, onDelete = {})
        }
        if (recent.isNotEmpty()) {
            header("Recent")
            items(recent, key = { "r" + it.id }) { c ->
                ConnectionRow(c, vault, nav, onConnect = { connect(nav, context, c) }, onDelete = { deleting = c })
            }
            header("All")
        }
        items(shown, key = { it.id }) { c ->
            ConnectionRow(c, vault, nav, onConnect = { connect(nav, context, c) }, onDelete = { deleting = c })
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

fun LazyListScope.header(text: String) {
    item(key = "h$text") {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
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
                SessionState.OPEN -> Color(0xFF7BC67B)
                SessionState.CLOSED -> MaterialTheme.colorScheme.outline
            }
            Box(Modifier.size(12.dp).background(colour, CircleShape))
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
    val subtitle = when {
        c == null -> "This device"
        c.type == ConnectionType.LOCAL -> "This device"
        c.type == ConnectionType.SERIAL -> "USB serial · " + c.line.describe()
        c.type == ConnectionType.BLUETOOTH -> "Bluetooth serial · " + c.device
        else -> buildString {
            append(c.type.label)
            append(" · ")
            append(c.host)
            if (c.port != c.type.defaultPort) append(":").append(c.port)
            vault.connection(c.viaId)?.let { append(" via ").append(it.title(vault)) }
        }
    }
    val groupColour = c?.groupIds?.firstNotNullOfOrNull { vault.group(it)?.colour?.takeIf { col -> col != 0L } }
    ListItem(
        headlineContent = { Text(c?.title(vault) ?: "Local shell", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Box(
                Modifier.size(36.dp).background(
                    if (groupColour != null) Color(groupColour) else MaterialTheme.colorScheme.secondaryContainer,
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
        trailingContent = if (c != null) {
            {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; nav.go(Screen.EditConnection(c.id)) })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                            menu = false
                            Store.update { it.put(c.copy(id = newId(), name = if (c.name.isEmpty()) "" else c.name + " copy", lastUsed = 0, updated = now())) }
                        })
                        DropdownMenuItem(text = { Text("Add port forward") }, onClick = { menu = false; nav.go(Screen.EditForward(null, c.id)) })
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
        if (list.isEmpty()) item { EmptyNote("An identity is a username with a password or key, shared by any number of connections.") }
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
        if (list.isEmpty()) item { EmptyNote("Snippets are commands you can type into a session from its menu.") }
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
                },
                modifier = Modifier.combinedClickableCompat(onClick = { nav.go(Screen.EditForward(f.id)) }),
            )
            HorizontalDivider()
        }
        if (list.isEmpty()) item { EmptyNote("Port forwards run over a connection: local, remote or a SOCKS proxy.") }
        item { Spacer(Modifier.size(80.dp)) }
    }
}

@Composable
fun Gap() = Spacer(Modifier.width(8.dp))
