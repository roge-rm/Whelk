package com.rm.whelk.core

/** How long a deleted item is kept, so the deletion reaches every copy before it's dropped. */
const val TOMBSTONE_DAYS = 180L

/**
 * Merges two copies of the vault item by item: for each id the copy changed last wins, and a tie
 * keeps [this]. Deleted items older than [TOMBSTONE_DAYS] are dropped.
 */
fun Vault.merge(other: Vault, at: Long = now()): Vault {
    val cutoff = at - TOMBSTONE_DAYS * 24 * 60 * 60 * 1000
    return Vault(
        connections = mergeItems(connections, other.connections, cutoff),
        identities = mergeItems(identities, other.identities, cutoff),
        groups = mergeItems(groups, other.groups, cutoff),
        snippets = mergeItems(snippets, other.snippets, cutoff),
        forwards = mergeItems(forwards, other.forwards, cutoff),
        knownHosts = mergeItems(knownHosts, other.knownHosts, cutoff),
    )
}

private fun <T : Item> mergeItems(mine: List<T>, theirs: List<T>, cutoff: Long): List<T> {
    val out = LinkedHashMap<String, T>()
    for (item in mine) out[item.id] = item
    for (item in theirs) {
        val have = out[item.id]
        if (have == null || item.updated > have.updated) out[item.id] = item
    }
    return out.values.filter { !(it.deleted && it.updated < cutoff) }
}

/**
 * The part of the vault shared with a team through group [groupId]: the group, its connections
 * (deleted ones too, so deletions reach the team), the jump hosts they go through, the identities
 * all of those use, and their port forwards.
 */
fun Vault.teamPart(groupId: String): Vault {
    val group = groups.filter { it.id == groupId }
    val conns = LinkedHashMap<String, Connection>()
    val queue = ArrayDeque(connections.filter { groupId in it.groupIds })
    while (queue.isNotEmpty()) {
        val c = queue.removeFirst()
        if (conns.put(c.id, c) != null) continue
        connections.firstOrNull { it.id == c.viaId }?.let { queue.addLast(it) }
    }
    val identityIds = conns.values.mapNotNull { it.identityId }.toSet()
    return Vault(
        groups = group,
        connections = conns.values.toList(),
        identities = identities.filter { it.id in identityIds },
        forwards = forwards.filter { it.connectionId in conns },
    )
}
