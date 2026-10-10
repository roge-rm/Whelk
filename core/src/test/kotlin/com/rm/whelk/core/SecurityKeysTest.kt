package com.rm.whelk.core

import com.hierynomus.sshj.userauth.fido.SecurityKeyPrivateKey
import com.hierynomus.sshj.userauth.fido.SecurityKeySignatureData
import com.hierynomus.sshj.userauth.fido.SecurityKeySigner
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Factory
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A pretend security key: an Ed25519 key in memory, signing the way a FIDO authenticator does. */
class SecurityKeysTest {
    private val pair = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }.generateKeyPair()
    private val handle = ByteArray(48).also { SecureRandom().nextBytes(it) }

    private val fake = SecurityKeySigner { req ->
        assertTrue(req.keyHandle.contentEquals(handle))
        val rpIdHash = MessageDigest.getInstance("SHA-256").digest(req.application.toByteArray())
        val authData = rpIdHash + byteArrayOf(0x01, 0, 0, 0, 7)
        val signer = Ed25519Signer().apply { init(true, pair.private as Ed25519PrivateKeyParameters) }
        val signed = authData + req.challenge
        signer.update(signed, 0, signed.size)
        val (flags, counter) = SecurityKeys.flagsAndCounter(authData)
        SecurityKeySignatureData(flags, counter, signer.generateSignature())
    }

    @Test
    fun keyFileReadsBackAndSigns() {
        val raw = (pair.public as Ed25519PublicKeyParameters).encoded
        val k = SecurityKeys.keyFile(SecurityKeys.ED25519, raw, handle, SecurityKeys.FLAG_USER_PRESENCE, "phone")
        assertTrue(k.publicKey.startsWith("sk-ssh-ed25519@openssh.com "))
        // Whelk reads the public line back from the file the same as for any other key.
        assertEquals(k.publicKey, Keys.publicLineFor(k.privateKey, null, "phone"))

        val provider = Keys.provider(k.privateKey, null, null, fake)
        val private = provider.private as SecurityKeyPrivateKey
        assertTrue(private.keyHandle.contentEquals(handle))
        assertEquals(SecurityKeys.FLAG_USER_PRESENCE, private.flags)

        // Sign the way a login does, and check it with sshj's verifier.
        val algorithm = Factory.Named.Util.create(SshConnection.config(false).keyAlgorithms, SecurityKeys.ED25519)
        val sig = algorithm.newSignature()
        sig.initSign(private)
        sig.update("login data".toByteArray())
        val encoded = sig.encode(sig.sign())
        val verify = algorithm.newSignature()
        verify.initVerify(provider.public)
        verify.update("login data".toByteArray())
        assertTrue(verify.verify(encoded))
        val buf = Buffer.PlainBuffer(encoded)
        assertEquals(SecurityKeys.ED25519, buf.readString())
    }
}
