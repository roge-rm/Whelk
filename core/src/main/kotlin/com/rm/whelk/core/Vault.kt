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
    val colourSchemes: List<ColourScheme> = emptyList(),
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
    fun put(c: ColourScheme) = copy(colourSchemes = colourSchemes.upsert(c))
    fun liveColourSchemes() = colourSchemes.filter { !it.deleted }

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
    TCP("Raw TCP", 23),
    SERIAL("USB serial", 0),
    BLUETOOTH("Bluetooth serial", 0),
    ;

    /** Connections to a device or socket with no login, which have line settings. */
    val isLine: Boolean get() = this == TCP || this == SERIAL || this == BLUETOOTH
    val hasHost: Boolean get() = this != LOCAL && this != SERIAL && this != BLUETOOTH
}

@Serializable
enum class Parity(val label: String) { NONE("None"), ODD("Odd"), EVEN("Even"), MARK("Mark"), SPACE("Space") }

@Serializable
enum class StopBits(val label: String) { ONE("1"), ONE_HALF("1.5"), TWO("2") }

@Serializable
enum class FlowControl(val label: String) { NONE("None"), RTS_CTS("RTS/CTS"), XON_XOFF("XON/XOFF") }

@Serializable
enum class EnterSends(val label: String, val bytes: String) { CR("CR", "\r"), LF("LF", "\n"), CRLF("CR LF", "\r\n") }

/** How a serial port, Bluetooth serial or raw TCP connection talks. */
@Serializable
data class LineSettings(
    val baud: Int = 115200,
    val dataBits: Int = 8,
    val parity: Parity = Parity.NONE,
    val stopBits: StopBits = StopBits.ONE,
    val flow: FlowControl = FlowControl.NONE,
    val enter: EnterSends = EnterSends.CR,
    /** Show what's typed, for devices that don't echo it back. */
    val localEcho: Boolean = false,
) {
    fun describe(): String = "$baud ${dataBits}${parity.label.first()}${stopBits.label}"

    companion object {
        val BAUDS = listOf(300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600, 1000000, 1500000, 2000000, 3000000)
    }
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
    /** Let the server use this phone's keys, for git or going on to other servers. */
    val forwardAgent: Boolean = false,
    /** Colour scheme name, or null for the app's own setting. */
    val theme: String? = null,
    /** The mosh-server command, when it isn't on the server's path or needs options. */
    val moshServer: String? = null,
    /** For serial, Bluetooth and raw TCP. */
    val line: LineSettings = LineSettings(),
    /** The server's character set, for ones that aren't UTF-8. */
    val charset: String = "UTF-8",
    val backspace: Backspace = Backspace.DEL,
    /** Environment variables to send. The server only takes the ones its AcceptEnv allows. */
    val env: Map<String, String> = emptyMap(),
    /** A tmux session to attach to, or make, in place of a plain shell. */
    val tmux: String? = null,
    val proxy: ProxySettings = ProxySettings(),
    /** Words that bring up a notification when they show up in the output. */
    val watch: List<String> = emptyList(),
    /** Which device: "vendor:product" in hex for USB (empty for the first one), the address for Bluetooth. */
    val device: String = "",
    /** The device's name, to show in lists. */
    val deviceName: String = "",
    val lastUsed: Long = 0,
    /** The proxy password was left on the device that wrote this copy. */
    val secretsKept: Boolean = false,
    override val updated: Long = now(),
    override val deleted: Boolean = false,
) : Item {
    /** The name shown in lists: the nickname, or user@host. */
    fun title(vault: Vault): String {
        if (name.isNotEmpty()) return name
        if (type == ConnectionType.LOCAL) return "Local shell"
        if (type == ConnectionType.SERIAL) return deviceName.ifEmpty { "USB serial" }
        if (type == ConnectionType.BLUETOOTH) return deviceName.ifEmpty { "Bluetooth serial" }
        if (type == ConnectionType.TCP) return if (port != type.defaultPort) "$host:$port" else host
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
    /** An OpenSSH certificate for the key, the line from its -cert.pub file. */
    val certificate: String? = null,
    /** The alias of a key kept in one phone's secure hardware, in place of [privateKey]. */
    val hardwareKey: String? = null,
    /** The password, key and passphrase were left on the device that wrote this copy. */
    val secretsKept: Boolean = false,
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
