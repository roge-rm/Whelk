package com.rm.whelk.core

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Factory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentTest {
    private val keys = listOf(KeyKind.ED25519, KeyKind.ECDSA_256, KeyKind.RSA_2048).map { kind ->
        val k = Keys.generate(kind, kind.label)
        Keys.agentKey(k.privateKey, null, null, kind.label)!!
    }

    private var asked = 0
    private var allow = true
    private val agent = AgentProtocol(object : AgentKeys {
        override fun keys() = keys
        override fun allow(key: AgentKey): Boolean {
            asked++
            return allow
        }
    })

    @Test
    fun listsKeys() {
        val r = Buffer.PlainBuffer(agent.handle(byteArrayOf(AgentProtocol.REQUEST_IDENTITIES.toByte())))
        assertEquals(AgentProtocol.IDENTITIES_ANSWER, r.readByte().toInt())
        assertEquals(3, r.readUInt32AsInt())
        assertTrue(r.readBytes().contentEquals(keys[0].blob))
        assertEquals("Ed25519", r.readString())
    }

    private fun sign(key: AgentKey, flags: Int): ByteArray {
        val req = Buffer.PlainBuffer().putByte(AgentProtocol.SIGN_REQUEST.toByte())
            .putBytes(key.blob).putBytes("session data".toByteArray()).putUInt32(flags.toLong()).compactData
        return agent.handle(req)
    }

    @Test
    fun signaturesVerify() {
        for ((key, flags) in keys.map { it to 0 } + (keys[2] to AgentProtocol.RSA_SHA2_512)) {
            val r = Buffer.PlainBuffer(sign(key, flags))
            assertEquals(AgentProtocol.SIGN_RESPONSE, r.readByte().toInt())
            val sig = r.readBytes()
            val name = Buffer.PlainBuffer(sig).readString()
            if (flags != 0) assertEquals("rsa-sha2-512", name)
            val algorithm = Factory.Named.Util.create(SshConnection.config(false).keyAlgorithms, name)
            assertNotNull(algorithm, name)
            val verifier = algorithm.newSignature()
            verifier.initVerify(key.publicKey)
            verifier.update("session data".toByteArray())
            assertTrue(verifier.verify(sig), name)
        }
    }

    @Test
    fun refusedWhenNotAllowed() {
        allow = false
        assertEquals(AgentProtocol.FAILURE, sign(keys[0], 0)[0].toInt())
        assertEquals(1, asked)
        // Adding keys and the like is refused.
        assertEquals(AgentProtocol.FAILURE, agent.handle(byteArrayOf(17))[0].toInt())
    }
}
