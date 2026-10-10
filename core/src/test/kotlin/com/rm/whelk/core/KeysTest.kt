package com.rm.whelk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeysTest {
    @Test
    fun generatedKeysReadBack() {
        for (kind in KeyKind.values().filter { it != KeyKind.RSA_4096 && it != KeyKind.RSA_3072 }) {
            val k = Keys.generate(kind, "test@whelk")
            assertFalse(Keys.isEncrypted(k.privateKey), kind.name)
            // The public line worked out from the private key matches the one made with it.
            assertEquals(k.publicKey, Keys.publicLineFor(k.privateKey, null, "test@whelk"), kind.name)
            assertTrue(Keys.fingerprintOfLine(k.publicKey)!!.startsWith("SHA256:"))
        }
    }

    @Test
    fun keyTypes() {
        assertTrue(Keys.generate(KeyKind.ED25519, "").publicKey.startsWith("ssh-ed25519 "))
        assertTrue(Keys.generate(KeyKind.ECDSA_256, "").publicKey.startsWith("ecdsa-sha2-nistp256 "))
        assertTrue(Keys.generate(KeyKind.RSA_2048, "").publicKey.startsWith("ssh-rsa "))
    }
}
