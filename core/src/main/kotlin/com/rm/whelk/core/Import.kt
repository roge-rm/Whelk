package com.rm.whelk.core

import java.net.URLDecoder

/** What an import found, ready to add to the vault, and what it couldn't use. */
data class Imported(
    val connections: List<Connection> = emptyList(),
    val identities: List<Identity> = emptyList(),
    val forwards: List<PortForward> = emptyList(),
    val knownHosts: List<KnownHost> = emptyList(),
    val skipped: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = connections.isEmpty() && knownHosts.isEmpty()

    operator fun plus(o: Imported) = Imported(
        connections + o.connections, identities + o.identities, forwards + o.forwards,
        knownHosts + o.knownHosts, skipped + o.skipped,
    )

    /**
     * Adds what's new to [vault]. A connection to the same user, host and port as one already
     * there is left out, along with anything only it used, and a host key already known is kept.
     */
    fun addTo(vault: Vault): Vault {
        var v = vault
        val idMap = HashMap<String, String>()
        // Imported connections that are already here, to the one already here.
        val connMap = HashMap<String, String>()
        val mine = Vault(identities = identities)
        for (c in connections) {
            val same = vault.liveConnections().firstOrNull { key(vault, it) == key(mine, c) }
            if (same != null) connMap[c.id] = same.id
        }
        val keep = connections.filter { it.id !in connMap }
        val usedIdentities = keep.mapNotNull { it.identityId }.toSet()
        for (i in identities.filter { it.id in usedIdentities }) {
            // Reuse an identity that's the same login.
            val same = vault.liveIdentities().firstOrNull {
                it.username == i.username && it.privateKey == i.privateKey && it.password == i.password
            }
            if (same != null) idMap[i.id] = same.id else v = v.put(i)
        }
        for (c in keep) {
            v = v.put(c.copy(identityId = c.identityId?.let { idMap[it] ?: it }, viaId = c.viaId?.let { connMap[it] ?: it }))
        }
        for (f in forwards.filter { it.connectionId !in connMap }) v = v.put(f)
        for (k in knownHosts) if (vault.knownHosts.none { it.id == k.id && !it.deleted }) v = v.put(k)
        return v
    }

    private fun key(vault: Vault, c: Connection) =
        "${vault.identity(c.identityId)?.username.orEmpty()}@${c.host.lowercase()}:${c.port}:${c.type}"
}

/** Reads OpenSSH client config files. [keys] gives the text of a key file by its name, if it has it. */
object OpenSshConfig {
    private class Block(val patterns: List<String>, val options: MutableList<Pair<String, String>> = mutableListOf())

    fun parse(text: String, keys: (String) -> String? = { null }): Imported {
        val blocks = mutableListOf(Block(listOf("*")))
        val skipped = mutableListOf<String>()
        var inMatch = false
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val m = Regex("""^(\S+?)(?:\s*=\s*|\s+)(.*)$""").find(line) ?: continue
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[2].trim().removeSurrounding("\"")
            when (key) {
                "host" -> {
                    inMatch = false
                    blocks += Block(value.split(Regex("\\s+")))
                }
                "match" -> {
                    inMatch = true
                    skipped += "Match blocks"
                }
                "include" -> skipped += "Include $value"
                else -> if (!inMatch) blocks.last().options += key to value
            }
        }

        val aliases = blocks.flatMap { it.patterns }.filter { p -> !p.startsWith("!") && p.none { it == '*' || it == '?' } }.distinct()
        val settings = aliases.associateWith { alias -> resolve(alias, blocks) }

        val connections = LinkedHashMap<String, Connection>()
        val identities = LinkedHashMap<String, Identity>()
        val forwards = mutableListOf<PortForward>()

        fun identityFor(user: String, keyFile: String?): Identity? {
            if (user.isEmpty() && keyFile == null) return null
            val name = keyFile?.substringAfterLast('/')?.substringAfterLast('\\')
            val k = "$user|${name.orEmpty()}"
            return identities.getOrPut(k) {
                val keyText = name?.let(keys)
                val pub = keyText?.let { runCatching { Keys.publicLineFor(it, null, user) }.getOrNull() }
                if (name != null && keyText == null) skipped += "Key $name isn't here; add it to the identity"
                Identity(name = name ?: "", username = user, privateKey = keyText, publicKey = pub)
            }
        }

        // Connections first, so jump hosts can point at them.
        for ((alias, s) in settings) {
            val user = s["user"].orEmpty()
            val identity = identityFor(user, s["identityfile"])
            connections[alias] = Connection(
                name = alias,
                host = s["hostname"]?.replace("%h", alias) ?: alias,
                port = s["port"]?.toIntOrNull() ?: 22,
                identityId = identity?.id,
                keepAlive = s["serveraliveinterval"]?.toIntOrNull() ?: 30,
                compression = s["compression"].equals("yes", ignoreCase = true),
            )
        }
        for ((alias, s) in settings) {
            val jump = s["proxyjump"] ?: continue
            if (jump.equals("none", ignoreCase = true)) continue
            // A chain "a,b" goes through a then b; the last one is the direct jump host.
            var via: String? = null
            for (hop in jump.split(',').map { it.trim() }) {
                via = jumpConnection(hop, via, connections, ::identityFor)
            }
            connections[alias] = connections[alias]!!.copy(viaId = via)
        }
        for ((alias, s) in settings) {
            val conn = connections[alias]!!
            for ((k, v) in s.all) {
                forward(k, v, conn.id)?.let { forwards += it }
            }
        }
        return Imported(connections.values.toList(), identities.values.toList(), forwards, skipped = skipped.distinct())
    }

    private fun jumpConnection(
        hop: String,
        via: String?,
        connections: LinkedHashMap<String, Connection>,
        identityFor: (String, String?) -> Identity?,
    ): String {
        connections[hop]?.let { return it.id }
        val m = Regex("""^(?:([^@]+)@)?(\[[^]]+]|[^:]+)(?::(\d+))?$""").find(hop)
        val user = m?.groupValues?.get(1).orEmpty()
        val host = m?.groupValues?.get(2)?.removeSurrounding("[", "]") ?: hop
        val port = m?.groupValues?.get(3)?.toIntOrNull() ?: 22
        val c = Connection(name = hop, host = host, port = port, identityId = identityFor(user, null)?.id, viaId = via)
        connections[hop] = c
        return c.id
    }

    private fun forward(key: String, value: String, connectionId: String): PortForward? {
        val kind = when (key) {
            "localforward" -> ForwardKind.LOCAL
            "remoteforward" -> ForwardKind.REMOTE
            "dynamicforward" -> ForwardKind.DYNAMIC
            else -> return null
        }
        val parts = value.split(Regex("\\s+"))
        val (bindHost, bindPort) = hostPort(parts[0], if (kind == ForwardKind.REMOTE) "localhost" else "127.0.0.1") ?: return null
        if (kind == ForwardKind.DYNAMIC) return PortForward(connectionId = connectionId, kind = kind, bindHost = bindHost, bindPort = bindPort)
        val (destHost, destPort) = parts.getOrNull(1)?.let { hostPort(it, "localhost") } ?: return null
        return PortForward(connectionId = connectionId, kind = kind, bindHost = bindHost, bindPort = bindPort, destHost = destHost, destPort = destPort)
    }

    /** "8080", "host:8080" or "[::1]:8080". */
    private fun hostPort(s: String, defaultHost: String): Pair<String, Int>? {
        s.toIntOrNull()?.let { return defaultHost to it }
        val i = s.lastIndexOf(':')
        if (i <= 0) return null
        val port = s.substring(i + 1).toIntOrNull() ?: return null
        return s.substring(0, i).removeSurrounding("[", "]") to port
    }

    private class Settings(val all: List<Pair<String, String>>) {
        private val first = LinkedHashMap<String, String>().also { m -> for ((k, v) in all) m.putIfAbsent(k, v) }
        operator fun get(key: String) = first[key]
    }

    private fun resolve(alias: String, blocks: List<Block>): Settings {
        val all = mutableListOf<Pair<String, String>>()
        for (b in blocks) if (matches(alias, b.patterns)) all += b.options
        return Settings(all)
    }

    private fun matches(alias: String, patterns: List<String>): Boolean {
        var any = false
        for (p in patterns) {
            if (p.startsWith("!")) {
                if (glob(p.substring(1)).matches(alias)) return false
            } else if (glob(p).matches(alias)) {
                any = true
            }
        }
        return any
    }

    private fun glob(p: String) = Regex(p.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } }, RegexOption.IGNORE_CASE)
}

/** Reads OpenSSH known_hosts files. Hashed host names can't be read back, so they're skipped. */
object KnownHostsFile {
    fun parse(text: String): Imported {
        val keys = mutableListOf<KnownHost>()
        var hashed = 0
        var markers = 0
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("@")) {
                markers++
                continue
            }
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 3) continue
            val type = parts[1]
            val key = parts[2]
            for (h in parts[0].split(',')) {
                if (h.startsWith("|")) {
                    hashed++
                    continue
                }
                if (h.contains('*') || h.contains('?') || h.startsWith("!")) continue
                val m = Regex("""^\[(.+)]:(\d+)$""").find(h)
                val (host, port) = if (m != null) m.groupValues[1] to m.groupValues[2].toInt() else h to 22
                keys += KnownHost(KnownHost.idFor(host, port), type, key)
            }
        }
        val skipped = buildList {
            if (hashed > 0) add("$hashed hashed host names")
            if (markers > 0) add("$markers @cert-authority or @revoked lines")
        }
        return Imported(knownHosts = keys.distinctBy { it.id }, skipped = skipped)
    }
}

/** Reads saved sessions from a PuTTY registry export (.reg). */
object PuttyReg {
    private const val SESSIONS = "\\Software\\SimonTatham\\PuTTY\\Sessions\\"

    /** Regedit writes UTF-16 with a byte order mark. */
    fun decode(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    }

    fun parse(text: String, keys: (String) -> String? = { null }): Imported {
        val sessions = LinkedHashMap<String, MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                val i = line.indexOf(SESSIONS, ignoreCase = true)
                current = if (i >= 0) {
                    val name = URLDecoder.decode(line.substring(i + SESSIONS.length).removeSuffix("]"), "UTF-8")
                    sessions.getOrPut(name) { mutableMapOf() }
                } else null
                continue
            }
            val m = Regex("""^"([^"]+)"=(.*)$""").find(line) ?: continue
            current?.put(m.groupValues[1].lowercase(), value(m.groupValues[2]))
        }

        val connections = mutableListOf<Connection>()
        val identities = LinkedHashMap<String, Identity>()
        val forwards = mutableListOf<PortForward>()
        val skipped = mutableListOf<String>()
        for ((name, s) in sessions) {
            if (name == "Default Settings") continue
            val type = when (s["protocol"]) {
                "ssh", null -> ConnectionType.SSH
                "telnet" -> ConnectionType.TELNET
                "raw" -> ConnectionType.TCP
                "serial" -> ConnectionType.SERIAL
                else -> {
                    skipped += "$name (${s["protocol"]})"
                    continue
                }
            }
            val hostField = s["hostname"].orEmpty()
            val userInHost = hostField.substringBefore('@', "")
            val host = hostField.substringAfter('@')
            if (host.isEmpty() && type != ConnectionType.SERIAL) continue
            val user = s["username"].orEmpty().ifEmpty { userInHost }
            val keyName = s["publickeyfile"]?.takeIf { it.isNotEmpty() }?.substringAfterLast('\\')?.substringAfterLast('/')
            val identity = if (type == ConnectionType.SSH && (user.isNotEmpty() || keyName != null)) {
                identities.getOrPut("$user|${keyName.orEmpty()}") {
                    val keyText = keyName?.let(keys)
                    if (keyName != null && keyText == null) skipped += "Key $keyName isn't here; add it to the identity"
                    val pub = keyText?.let { runCatching { Keys.publicLineFor(it, null, user) }.getOrNull() }
                    Identity(name = keyName ?: "", username = user, privateKey = keyText, publicKey = pub)
                }
            } else null
            val line = if (type == ConnectionType.SERIAL) {
                LineSettings(
                    baud = s["serialspeed"]?.toIntOrNull() ?: 9600,
                    dataBits = s["serialdatabits"]?.toIntOrNull() ?: 8,
                    parity = when (s["serialparity"]) { "1" -> Parity.ODD; "2" -> Parity.EVEN; "3" -> Parity.MARK; "4" -> Parity.SPACE; else -> Parity.NONE },
                    stopBits = when (s["serialstophalfbits"]) { "3" -> StopBits.ONE_HALF; "4" -> StopBits.TWO; else -> StopBits.ONE },
                    flow = when (s["serialflowcontrol"]) { "2" -> FlowControl.RTS_CTS; "1" -> FlowControl.XON_XOFF; else -> FlowControl.NONE },
                )
            } else LineSettings()
            val c = Connection(
                name = name,
                type = type,
                host = if (type == ConnectionType.SERIAL) "" else host,
                port = s["portnumber"]?.toIntOrNull() ?: type.defaultPort,
                identityId = identity?.id,
                keepAlive = s["pinginterval"]?.toIntOrNull()?.takeIf { it > 0 } ?: 30,
                compression = s["compression"] == "1",
                line = line,
            )
            connections += c
            s["portforwardings"]?.takeIf { it.isNotEmpty() }?.split(',')?.forEach { f -> forward(f, c.id)?.let { forwards += it } }
        }
        return Imported(connections, identities.values.toList(), forwards, skipped = skipped.distinct())
    }

    /** A registry value: "text" or dword:0000001f. */
    private fun value(v: String): String = when {
        v.startsWith("dword:") -> v.removePrefix("dword:").toLong(16).toString()
        v.startsWith("\"") -> v.removeSurrounding("\"").replace("\\\\", "\\").replace("\\\"", "\"")
        else -> v
    }

    /** PuTTY writes forwards as L8080=host:80, R2222=localhost:22 or D1080, with 4 or 6 to pick a family. */
    private fun forward(spec: String, connectionId: String): PortForward? {
        var s = spec.trim()
        if (s.startsWith("4") || s.startsWith("6")) s = s.substring(1)
        if (s.isEmpty()) return null
        val kind = when (s[0]) { 'L' -> ForwardKind.LOCAL; 'R' -> ForwardKind.REMOTE; 'D' -> ForwardKind.DYNAMIC; else -> return null }
        val rest = s.substring(1)
        val bind = rest.substringBefore('=')
        val bindPort = bind.substringAfterLast(':').toIntOrNull() ?: return null
        val bindHost = if (bind.contains(':')) bind.substringBeforeLast(':') else if (kind == ForwardKind.REMOTE) "localhost" else "127.0.0.1"
        if (kind == ForwardKind.DYNAMIC) return PortForward(connectionId = connectionId, kind = kind, bindHost = bindHost, bindPort = bindPort)
        val dest = rest.substringAfter('=', "")
        val destPort = dest.substringAfterLast(':').toIntOrNull() ?: return null
        return PortForward(
            connectionId = connectionId, kind = kind, bindHost = bindHost, bindPort = bindPort,
            destHost = dest.substringBeforeLast(':'), destPort = destPort,
        )
    }
}

/** Picks the right reader for a file by what's in it. */
object Importer {
    private val KEY_TYPE = Regex("""^(ssh-|ecdsa-|sk-)""")

    fun read(name: String, bytes: ByteArray, keys: (String) -> String? = { null }): Imported {
        val text = PuttyReg.decode(bytes)
        if (name.endsWith(".reg", ignoreCase = true) || text.contains("SimonTatham\\PuTTY")) return PuttyReg.parse(text, keys)
        val looksKnownHosts = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.take(5).let { lines ->
            lines.isNotEmpty() && lines.all { l -> l.startsWith("@") || l.split(Regex("\\s+")).getOrNull(1)?.let { KEY_TYPE.containsMatchIn(it) } == true }
        }
        if (looksKnownHosts) return KnownHostsFile.parse(text)
        return OpenSshConfig.parse(text, keys)
    }
}
