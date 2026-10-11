package com.rm.whelk.core

/**
 * The vault with passwords, private keys and passphrases left out, for backups and sync files when
 * secrets stay on the phone. Items that had one are marked, so [withSecretsFrom] knows to put back
 * the copy's own secrets instead of taking the empty ones as removed.
 */
fun Vault.withoutSecrets(): Vault = copy(
    identities = identities.map { i ->
        if (i.password == null && i.privateKey == null && i.passphrase == null) i
        else i.copy(password = null, privateKey = null, passphrase = null, secretsKept = true)
    },
    connections = connections.map { c ->
        if (c.proxy.password.isEmpty()) c
        else c.copy(proxy = c.proxy.copy(password = ""), secretsKept = true)
    },
)

/**
 * Puts back secrets from [local] on items that came from a copy without them, matched by id. Only a
 * secret this copy doesn't have is filled, so one set elsewhere since is kept.
 */
fun Vault.withSecretsFrom(local: Vault): Vault {
    val ids = local.identities.associateBy { it.id }
    val conns = local.connections.associateBy { it.id }
    return copy(
        identities = identities.map { i ->
            val mine = ids[i.id]
            if (!i.secretsKept || mine == null) i
            else i.copy(
                password = i.password ?: mine.password,
                privateKey = i.privateKey ?: mine.privateKey,
                passphrase = i.passphrase ?: mine.passphrase,
                secretsKept = false,
            )
        },
        connections = connections.map { c ->
            val mine = conns[c.id]
            if (!c.secretsKept || mine == null) c
            else c.copy(proxy = c.proxy.copy(password = c.proxy.password.ifEmpty { mine.proxy.password }), secretsKept = false)
        },
    )
}
