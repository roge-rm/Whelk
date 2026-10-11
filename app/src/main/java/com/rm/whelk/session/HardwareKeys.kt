package com.rm.whelk.session

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import com.rm.whelk.core.Keys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** Asks the user to unlock before a key in the phone's hardware signs. The main screen shows it. */
class UnlockRequest(val title: String) {
    internal val done = CompletableDeferred<Boolean>()
}

/**
 * ECDSA P-256 keys made and kept in the Android Keystore, in StrongBox where the phone has one.
 * The private key never leaves the hardware. Each use needs a fingerprint or the screen lock,
 * which stays good for [UNLOCKED_SECONDS] so one login doesn't ask twice.
 */
object HardwareKeys {
    private const val STORE = "AndroidKeyStore"
    private const val PREFIX = "whelk-ssh-"
    private const val UNLOCKED_SECONDS = 15

    private val state = MutableStateFlow<UnlockRequest?>(null)
    val pending: StateFlow<UnlockRequest?> = state

    private fun store() = KeyStore.getInstance(STORE).apply { load(null) }

    fun has(alias: String): Boolean = runCatching { store().containsAlias(alias) }.getOrDefault(false)

    fun delete(alias: String) {
        runCatching { store().deleteEntry(alias) }
    }

    /** Makes a key and returns its alias and OpenSSH public key line. Blocks; call off the main thread. */
    fun make(context: Context, comment: String): Pair<String, String> {
        if (!context.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            throw IOException("Set a screen lock on the phone first")
        }
        val alias = PREFIX + UUID.randomUUID()
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(false)
            .apply {
                if (Build.VERSION.SDK_INT >= 30) {
                    setUserAuthenticationParameters(UNLOCKED_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(UNLOCKED_SECONDS)
                }
                if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true)
            }
            .build()
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE)
        val strongBox = Build.VERSION.SDK_INT >= 28 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        val pair = try {
            gen.initialize(spec(strongBox))
            gen.generateKeyPair()
        } catch (e: Exception) {
            if (!strongBox || Build.VERSION.SDK_INT < 28 || e !is StrongBoxUnavailableException) throw e
            gen.initialize(spec(false))
            gen.generateKeyPair()
        }
        val line = Keys.typeName(pair.public) + " " +
            java.util.Base64.getEncoder().encodeToString(Keys.blob(pair.public)) + " " + comment
        return alias to line
    }

    /** sshj's view of the key. Asks to unlock, if needed, when it's about to sign. */
    fun provider(alias: String): KeyProvider {
        val entry = store().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: throw IOException("This key is kept on another phone")
        val public = entry.certificate.publicKey
        return object : KeyProvider {
            override fun getPrivate(): PrivateKey {
                unlock(entry.privateKey)
                return entry.privateKey
            }
            override fun getPublic(): PublicKey = public
            override fun getType(): KeyType = KeyType.ECDSA256
        }
    }

    private fun unlock(key: PrivateKey) {
        if (ready(key)) return
        val request = UnlockRequest("Use the key on this phone")
        synchronized(this) {
            if (state.value != null) throw IOException("Another login is waiting to unlock")
            state.value = request
        }
        try {
            if (!runBlocking { request.done.await() }) throw IOException("Cancelled")
        } finally {
            state.compareAndSet(request, null)
        }
        if (!ready(key)) throw IOException("The key is still locked")
    }

    // The lookup tries each provider in turn, so the Keystore's reason can come wrapped.
    private fun ready(key: PrivateKey): Boolean = try {
        Signature.getInstance("SHA256withECDSA").initSign(key)
        true
    } catch (e: java.security.InvalidKeyException) {
        if (generateSequence<Throwable>(e) { it.cause }.none { it is UserNotAuthenticatedException }) throw e
        false
    }

    fun answer(request: UnlockRequest, ok: Boolean) {
        request.done.complete(ok)
    }
}
