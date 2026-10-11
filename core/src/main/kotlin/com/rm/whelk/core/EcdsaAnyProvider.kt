package com.rm.whelk.core

import net.schmizz.sshj.signature.Signature
import net.schmizz.sshj.signature.SignatureECDSA
import java.security.PrivateKey
import java.security.PublicKey

/**
 * ecdsa-sha2-nistp256 signatures that also work with keys BouncyCastle can't use, like ones kept in
 * Android's Keystore. sshj asks BouncyCastle by name; when that turns the key down, signing goes to
 * whichever provider takes it. Verifying and the wire format stay sshj's.
 */
class EcdsaAnyProvider : Signature {
    private val standard = SignatureECDSA.Factory256().create()
    private var other: java.security.Signature? = null

    override fun getSignatureName(): String = standard.signatureName

    override fun initVerify(publicKey: PublicKey) {
        other = null
        standard.initVerify(publicKey)
    }

    override fun initSign(privateKey: PrivateKey) {
        other = null
        try {
            standard.initSign(privateKey)
        } catch (e: RuntimeException) {
            // Thrown on here if no provider can sign with it, or the key needs the user first.
            other = java.security.Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey) }
        }
    }

    override fun update(data: ByteArray) {
        other?.update(data) ?: standard.update(data)
    }

    override fun update(data: ByteArray, off: Int, len: Int) {
        other?.update(data, off, len) ?: standard.update(data, off, len)
    }

    override fun sign(): ByteArray = other?.sign() ?: standard.sign()

    override fun encode(signature: ByteArray): ByteArray = standard.encode(signature)

    override fun verify(signature: ByteArray): Boolean = standard.verify(signature)

    class Factory : net.schmizz.sshj.common.Factory.Named<Signature> {
        override fun getName() = "ecdsa-sha2-nistp256"
        override fun create(): Signature = EcdsaAnyProvider()
    }
}
