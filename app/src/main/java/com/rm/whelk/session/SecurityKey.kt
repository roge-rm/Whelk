package com.rm.whelk.session

import android.app.Activity
import com.hierynomus.sshj.userauth.fido.SecurityKeySignatureData
import com.hierynomus.sshj.userauth.fido.SecurityKeySigner
import com.hierynomus.sshj.userauth.fido.SecurityKeySigningRequest
import com.rm.whelk.core.KeyText
import com.rm.whelk.core.SecurityKeys
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.fido.ctap.ClientPin
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV1
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV2
import com.yubico.yubikit.fido.webauthn.AuthenticatorData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Something to do with a FIDO security key once one is plugged in or held to the phone. The
 * main screen watches [SecurityKey.pending], shows a dialog and listens for keys on USB and NFC.
 */
class KeyJob(val sessionId: Int, val title: String, internal val work: (Ctap2Session) -> Any) {
    internal val done = CompletableDeferred<Result<Any>>()
    val status = MutableStateFlow("Plug in your security key, or hold it to the phone.")
}

object SecurityKey {
    private val state = MutableStateFlow<KeyJob?>(null)
    val pending: StateFlow<KeyJob?> = state
    private var manager: YubiKitManager? = null
    private var listening: Activity? = null

    /** Runs [work] on the next security key that turns up, blocking until it's done or cancelled. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> run(sessionId: Int, title: String, work: (Ctap2Session) -> T): T {
        val job = KeyJob(sessionId, title, work)
        synchronized(this) {
            if (state.value != null) throw IOException("The security key is busy")
            state.value = job
        }
        try {
            return runBlocking { job.done.await() }.getOrThrow() as T
        } finally {
            state.compareAndSet(job, null)
        }
    }

    fun cancel(job: KeyJob) {
        job.done.complete(Result.failure(IOException("Cancelled")))
    }

    fun cancel(sessionId: Int) {
        state.value?.takeIf { it.sessionId == sessionId }?.let { cancel(it) }
    }

    /** Starts listening for keys on USB and NFC while [activity] is in front. */
    fun listen(activity: Activity) {
        if (listening === activity) return
        stop()
        val m = manager ?: YubiKitManager(activity.applicationContext).also { manager = it }
        m.startUsbDiscovery(UsbConfiguration()) { device -> use(device) }
        try {
            m.startNfcDiscovery(NfcConfiguration().timeout(30_000), activity) { device -> use(device) }
        } catch (e: NfcNotAvailable) {
            // USB only on this phone.
        }
        listening = activity
    }

    fun stop() {
        val a = listening ?: return
        manager?.stopUsbDiscovery()
        manager?.stopNfcDiscovery(a)
        listening = null
    }

    private fun use(device: YubiKeyDevice) {
        val job = state.value ?: return
        val type = if (device.supportsConnection(FidoConnection::class.java)) FidoConnection::class.java else SmartCardConnection::class.java
        job.status.value = "Touch your security key."
        device.requestConnection(type) { result ->
            val outcome = runCatching {
                result.value.use { connection ->
                    val ctap = when (connection) {
                        is FidoConnection -> Ctap2Session(connection)
                        is SmartCardConnection -> Ctap2Session(connection)
                        else -> throw IOException("Not a FIDO key")
                    }
                    job.work(ctap)
                }
            }
            if (outcome.isFailure && !job.done.isCompleted) {
                job.status.value = (outcome.exceptionOrNull()?.message ?: "That didn't work") + ". Try again."
                // A key that was pulled out too soon can be tried again; anything else ends the job.
                if (outcome.exceptionOrNull() !is IOException) job.done.complete(outcome)
            } else {
                job.done.complete(outcome)
            }
        }
    }

    /** A PIN token for [permission], using the newest PIN protocol the key has. */
    private fun pinParam(ctap: Ctap2Session, pin: CharArray, permission: Int, rpId: String, hash: ByteArray): Pair<ByteArray, Int> {
        val protocols = ctap.cachedInfo.pinUvAuthProtocols
        val protocol = if (2 in protocols) PinUvAuthProtocolV2() else PinUvAuthProtocolV1()
        val token = ClientPin(ctap, protocol).getPinToken(pin, permission, rpId)
        return protocol.authenticate(token, hash) to protocol.version
    }

    private fun hasPin(ctap: Ctap2Session) = ctap.cachedInfo.options["clientPin"] == true

    /** Signs for an SSH login. Asks for the PIN only when the key was made to need it. */
    class Signer(private val sessionId: Int) : SecurityKeySigner {
        override fun sign(request: SecurityKeySigningRequest): SecurityKeySignatureData {
            val needsPin = request.minFlags.toInt() and SecurityKeys.FLAG_USER_VERIFICATION.toInt() != 0
            val pin = if (needsPin) {
                Prompts.ask(Ask.Text(sessionId, "Security key", "", "PIN", secret = true))?.toCharArray()
                    ?: throw IOException("Cancelled")
            } else null
            return run(sessionId, "Sign in with your security key") { ctap ->
                val allow = listOf(mapOf("type" to "public-key", "id" to request.keyHandle))
                val (param, protocol) = if (pin != null) {
                    pinParam(ctap, pin, ClientPin.PIN_PERMISSION_GA, request.application, request.challenge)
                } else null to null
                val assertion = ctap.getAssertions(
                    request.application, request.challenge, allow, null, mapOf("up" to true), param, protocol, null,
                ).first()
                val (flags, counter) = SecurityKeys.flagsAndCounter(assertion.authenticatorData)
                SecurityKeySignatureData(flags, counter, assertion.signature)
            }
        }
    }

    /**
     * Makes a new key on a security key and returns it as an OpenSSH sk key file. [ed25519] picks
     * sk-ssh-ed25519, otherwise sk-ecdsa-sha2-nistp256. [pin] is needed if the key has one set.
     */
    fun make(sessionId: Int, ed25519: Boolean, verify: Boolean, comment: String, pin: () -> String?): KeyText {
        val random = SecureRandom()
        val hash = ByteArray(32).also { random.nextBytes(it) }
        var pinChars: CharArray? = null
        return run(sessionId, "Make a key on your security key") { ctap ->
            val (param, protocol) = if (hasPin(ctap)) {
                val p = pinChars ?: (pin()?.toCharArray() ?: throw IOException("Cancelled")).also { pinChars = it }
                pinParam(ctap, p, ClientPin.PIN_PERMISSION_MC, SecurityKeys.APPLICATION, hash)
            } else null to null
            val alg = if (ed25519) SecurityKeys.COSE_EDDSA else SecurityKeys.COSE_ES256
            val created = ctap.makeCredential(
                hash,
                mapOf("id" to SecurityKeys.APPLICATION, "name" to SecurityKeys.APPLICATION),
                mapOf("id" to ByteArray(32), "name" to "openssh", "displayName" to "openssh"),
                listOf(mapOf("type" to "public-key", "alg" to alg)),
                null, null, mapOf("rk" to false), param, protocol, null, null,
            )
            val auth = AuthenticatorData.parseFrom(ByteBuffer.wrap(created.authenticatorData))
            val cred = auth.attestedCredentialData ?: throw IOException("The key didn't say what it made")
            val cose = cred.cosePublicKey
            val raw = if (ed25519) {
                cose[-2] as ByteArray
            } else {
                byteArrayOf(0x04) + (cose[-2] as ByteArray) + (cose[-3] as ByteArray)
            }
            var flags = SecurityKeys.FLAG_USER_PRESENCE.toInt()
            if (verify) flags = flags or SecurityKeys.FLAG_USER_VERIFICATION.toInt()
            SecurityKeys.keyFile(if (ed25519) SecurityKeys.ED25519 else SecurityKeys.ECDSA, raw, cred.credentialId, flags.toByte(), comment)
        }
    }
}
