package com.rm.whelk.core

import com.hierynomus.sshj.userauth.fido.SecurityKeySigner
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.params.ECNamedDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64

enum class KeyKind(val label: String) {
    ED25519("Ed25519"),
    ECDSA_256("ECDSA 256"),
    ECDSA_384("ECDSA 384"),
    ECDSA_521("ECDSA 521"),
    RSA_2048("RSA 2048"),
    RSA_3072("RSA 3072"),
    RSA_4096("RSA 4096"),
}

/** A private key as text an SSH client can read, and its OpenSSH public key line. */
class KeyText(val privateKey: String, val publicKey: String)

object Keys {
    private val random = SecureRandom()

    fun generate(kind: KeyKind, comment: String): KeyText {
        val gen = when (kind) {
            KeyKind.ED25519 -> Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(random)) }
            KeyKind.ECDSA_256, KeyKind.ECDSA_384, KeyKind.ECDSA_521 -> {
                val curve = when (kind) {
                    KeyKind.ECDSA_256 -> "P-256"
                    KeyKind.ECDSA_384 -> "P-384"
                    else -> "P-521"
                }
                val domain = ECNamedDomainParameters(ECNamedCurveTable.getOID(curve), ECNamedCurveTable.getByName(curve))
                ECKeyPairGenerator().apply { init(ECKeyGenerationParameters(domain, random)) }
            }
            KeyKind.RSA_2048, KeyKind.RSA_3072, KeyKind.RSA_4096 -> {
                val bits = when (kind) {
                    KeyKind.RSA_2048 -> 2048
                    KeyKind.RSA_3072 -> 3072
                    else -> 4096
                }
                RSAKeyPairGenerator().apply { init(RSAKeyGenerationParameters(BigInteger.valueOf(65537), random, bits, 100)) }
            }
        }
        val pair = gen.generateKeyPair()
        val publicBlob = OpenSSHPublicKeyUtil.encodePublicKey(pair.public)
        return KeyText(openSshPrivate(pair, publicBlob, comment), publicLine(publicBlob, comment))
    }

    /** The unencrypted "openssh-key-v1" format that ssh-keygen writes, for any of the key types. */
    private fun openSshPrivate(pair: AsymmetricCipherKeyPair, publicBlob: ByteArray, comment: String): String {
        val check = random.nextInt()
        val secret = Buffer.PlainBuffer().putUInt32(check.toLong() and 0xFFFFFFFFL).putUInt32(check.toLong() and 0xFFFFFFFFL)
        when (val priv = pair.private) {
            is Ed25519PrivateKeyParameters -> {
                val pub = (pair.public as Ed25519PublicKeyParameters).encoded
                secret.putString("ssh-ed25519").putBytes(pub).putBytes(priv.encoded + pub)
            }
            is ECPrivateKeyParameters -> {
                val pub = pair.public as ECPublicKeyParameters
                val name = Buffer.PlainBuffer(publicBlob).readString()
                secret.putString(name).putString(name.substringAfterLast('-'))
                    .putBytes(pub.q.getEncoded(false)).putMPInt(priv.d)
            }
            is RSAPrivateCrtKeyParameters -> {
                secret.putString("ssh-rsa").putMPInt(priv.modulus).putMPInt(priv.publicExponent)
                    .putMPInt(priv.exponent).putMPInt(priv.qInv).putMPInt(priv.p).putMPInt(priv.q)
            }
            else -> throw IllegalArgumentException("Unknown key type")
        }
        secret.putString(comment)
        var pad = 1
        while (secret.available() % 8 != 0) secret.putByte((pad++).toByte())
        val body = Buffer.PlainBuffer()
            .putRawBytes("openssh-key-v1".toByteArray() + byteArrayOf(0))
            .putString("none").putString("none").putString("")
            .putUInt32(1)
            .putBytes(publicBlob)
            .putBytes(secret.compactData)
        return pem("OPENSSH PRIVATE KEY", body.compactData)
    }

    /**
     * Reads a private key (OpenSSH, PEM, PKCS#8 or PuTTY) and returns its public key line. Throws
     * if the key can't be read, including for a wrong passphrase.
     */
    fun publicLineFor(privateKey: String, passphrase: String?, comment: String): String {
        val key = provider(privateKey, passphrase).public
        return publicLine(blob(key), comment)
    }

    /** Whether the private key needs a passphrase to read. */
    fun isEncrypted(privateKey: String): Boolean {
        if (privateKey.contains("ENCRYPTED") || privateKey.contains("Proc-Type: 4,ENCRYPTED")) return true
        if (privateKey.contains("Encryption:") && !privateKey.contains("Encryption: none")) return true
        if (privateKey.contains("BEGIN OPENSSH PRIVATE KEY")) {
            return try {
                provider(privateKey, null).public
                false
            } catch (e: Exception) {
                true
            }
        }
        return false
    }

    /**
     * A key provider for sshj. The passphrase is only used if the key needs one. With a
     * [certificate], logging in offers the certificate in place of the bare public key.
     */
    fun provider(
        privateKey: String,
        passphrase: String?,
        certificate: String? = null,
        securityKey: SecurityKeySigner? = null,
    ): KeyProvider {
        val format = KeyProviderUtil.detectKeyFileFormat(privateKey, false)
        val fkp: FileKeyProvider = Factory.Named.Util.create(config.fileKeyProviderFactories, format.toString())
            ?: throw IllegalArgumentException("Not a key Whelk can read")
        val finder = object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray =
                passphrase?.toCharArray() ?: throw IllegalArgumentException("The key needs its passphrase")
            override fun shouldRetry(resource: Resource<*>?) = false
        }
        // A security key's file says how to find the credential; the signing happens on the key.
        if (securityKey != null && fkp is OpenSSHKeyV1KeyFile) fkp.setSecurityKeySigner(securityKey)
        fkp.init(privateKey, certificate?.trim()?.ifEmpty { null }, finder)
        return fkp
    }

    /** A key for the forwarded agent, or null if it can't be read without asking for a passphrase. */
    fun agentKey(privateKey: String, passphrase: String?, certificate: String?, comment: String): AgentKey? = try {
        val p = provider(privateKey, passphrase, certificate)
        AgentKey(blob(p.public), comment, p.private, p.public)
    } catch (e: Exception) {
        null
    }

    /** The public key blob in SSH wire format. */
    fun blob(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData

    fun typeName(key: PublicKey): String = KeyType.fromKey(key).toString()

    /** OpenSSH style SHA-256 fingerprint, "SHA256:..." */
    fun fingerprint(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    fun fingerprintOfLine(publicLine: String): String? {
        val parts = publicLine.trim().split(Regex("\\s+"))
        if (parts.size < 2) return null
        return try {
            fingerprint(Base64.getDecoder().decode(parts[1]))
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun publicLine(blob: ByteArray, comment: String): String {
        val type = Buffer.PlainBuffer(blob).readString()
        val b64 = Base64.getEncoder().encodeToString(blob)
        return if (comment.isEmpty()) "$type $b64" else "$type $b64 $comment"
    }

    private fun pem(type: String, der: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(der)
        return buildString {
            append("-----BEGIN ").append(type).append("-----\n")
            b64.chunked(70).forEach { append(it).append('\n') }
            append("-----END ").append(type).append("-----\n")
        }
    }

    private val config by lazy { DefaultConfig() }
}
