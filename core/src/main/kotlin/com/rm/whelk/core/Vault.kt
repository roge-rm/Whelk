package com.rm.whelk.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Everything the user sets up: connections, identities, groups, snippets, port forwards and known
 * host keys. Saved whole as one JSON document. Each item has an id, the time it last changed and a
 * deleted flag, so two copies can be merged item by item (see [merge]).
 */
@Serializable
data class Vault(
    val connections: List<Connection> = emptyList(),
    val identities: List<Identity> = emptyList(),
    val groups: List<Group> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val forwards: List<PortForward> = emptyList(),
    val knownHosts: List<KnownHost> = emptyList(),
) {
    fun connection(id: String?) = connections.firstOrNull { it.id == id && !it.deleted }
    fun identity(id: String?) = identities.firstOrNull { it.id == id && !it.deleted }
    fun group(id: String?) = groups.firstOrNull { it.id == id && !it.deleted }
    fun snippet(id: String?) = snippets.firstOrNull { it.id == id && !it.deleted }
    fun forward(id: String?) = forwards.firstOrNull { it.id == id && !it.deleted }
    fun knownHost(host: String, port: Int) =
        knownHosts.firstOrNull { it.id == KnownHost.idFor(host, port) && !it.deleted }

    fun liveConnections() = connections.filter { !it.deleted }
    fun liveIdentities() = identities.filter { !it.deleted }
    fun liveGroups() = groups.filter { !it.deleted }
    fun liveSnippets() = snippets.filter { !it.deleted }
    fun liveForwards() = forwards.filter { !it.deleted }

    fun put(c: Connection) = copy(connections = connections.upsert(c))
    fun put(i: Identity) = copy(identities = identities.upsert(i))
    fun put(g: Group) = copy(groups = groups.upsert(g))
    fun put(s: Snippet) = copy(snippets = snippets.upsert(s))
    fun put(f: PortForward) = copy(forwards = forwards.upsert(f))
    fun put(k: KnownHost) = copy(knownHosts = knownHosts.upsert(k))

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            prettyPrint = false
        }

        fun fromJson(text: String): Vault = json.decodeFromString(serializer(), text)
    }
}

/** What every item in the vault has, for merging. */
interface Item {
    val id: String
    /** Milliseconds since 1970 when this item last changed. */
    val updated: Long
    val deleted: Boolean
}

fun newId(): String = UUID.randomUUID().toString()

fun now(): Long = System.currentTimeMillis()

private fun <T : Item> List<T>.upsert(item: T): List<T> {
    val i = indexOfFirst { it.id == item.id }
    return if (i < 0) this + item else toMutableList().also { it[i] = item }
}

@Serializable
enum class ConnectionType(val label: String, val defaultPort: Int) {
    SSH("SSH", 22),
    MOSH("Mosh", 22),
    TELNET("Telnet", 23),
    LOCAL("Local", 0),
}

@Serializable
data class Connection(
    override val id: String = newId(),
    val name: String = "",
    val type: ConnectionType = ConnectionType.SSH,
    val host: String = "",
    val port: Int = 22,
    val identityId: String? = null,
    /** Another SSH connection to go through first, a jump host. */
    val viaId: String? = null,
    val groupIds: List<String> = emptyList(),
    /** A snippet typed in once the session starts. */
    val startSnippetId: String? = null,
    /** Seconds between keepalives, 0 for none. */
    val keepAlive: Int = 30,
    val compression: Boolean = false,
    /** Colour scheme name, or null for the app's own setting. */
    val theme: String? = null,
    val lastUsed: Long = 0,
    override val updated: Long = now(),
    override val deleted: Boolean = false,
) : Item {
    /** The name shown in lists: the nickname, or user@host. */
    fun title(vault: Vault): String {
        if (name.isNotEmpty()) return name
        if (type == ConnectionType.LOCAL) return "Local shell"
        val user = vault.identity(identityId)?.username
        val portPart = if (port != type.defaultPort) ":$port" else ""
        return if (user.isNullOrEmpty()) "$host$portPart" else "$user@$host$portPart"
    }
}

/** A login: a username with a password, a private key, or both. Shared between connections. */
@Serializable
data class Identity(
    override val id: String = newId(),
    val name: String = "",
    val username: String = "",
    val password: String? = null,
    /** OpenSSH, PEM or PuTTY private key text. */
    val privateKey: String? = null,
    /** The OpenSSH public key line, kept so it can be copied to servers. */
    val publicKey: String? = null,
    val passphrase: String? = null,
    override val updated: Long = now(),
    override val deleted: Boolean = false,
) : Item {
    fun title(): String = name.ifEmpty { username.ifEmpty { "Identity" } }
}

@Serializable
data class Group(
    override val id: String = newId(),
    val name: String = "",
    /** ARGB, or 0 for none. */
    val colour: Long = 0,
    override val updated: Long = now(),
    override val deleted: Boolean = false,
) : Item

@Serializable
data class Snippet(
    override val id: String = newId(),
    val name: String = "",
    val text: String = "",
    /** Press Enter after typing it. */
    val enter: Boolean = true,
    override val updated: Long = now(),
    override val deleted: Boolean = false,
) : Item

@Serializable
enum class ForwardKind(val label: String) {
    LOCAL("Local"),
    REMOTE("Remote"),
    DYNAMIC("Dynamic (SOCKS)"),
}

@Serializable
data class PortForward(
    override val id: String = newId(),
    val name: String = "",
    val connectionId: String = "",
    val kind: ForwardKind = ForwardKind.LOCAL,
    /** Where to listen: on this device for local and dynamic, on the server for remote. */
    val bindHost: String = "127.0.0.1",
    val bindPort: Int = 8080,
    /** Where to connect to: from the server for local, from this device for remote. Unused for dynamic. */
    val destHost: String = "localhost",
    val destPort: Int = 80,
    /** Start it whenever its connection opens a session. */
    val autoStart: Boolean = false,
    override val updated: Long = now(),
    override val deleted: Boolean = false,
) : Item {
    fun title(): String = name.ifEmpty { describe() }

    fun describe(): String = when (kind) {
        ForwardKind.LOCAL -> "$bindPort → $destHost:$destPort"
        ForwardKind.REMOTE -> "server $bindPort → $destHost:$destPort"
        ForwardKind.DYNAMIC -> "SOCKS on $bindPort"
    }
}

/** A server's host key, once accepted. The id is host:port. */
@Serializable
data class KnownHost(
    override val id: String,
    val keyType: String,
    /** Base64 of the public key blob. */
    val key: String,
    override val updated: Long = now(),
    override val deleted: Boolean = false,
) : Item {
    companion object {
        fun idFor(host: String, port: Int) = "${host.lowercase()}:$port"
    }
}
