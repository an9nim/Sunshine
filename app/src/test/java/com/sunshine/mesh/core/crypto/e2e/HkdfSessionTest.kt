package com.sunshine.mesh.core.crypto.e2e

import com.sunshine.mesh.core.crypto.IdentityMaterial
import com.sunshine.mesh.core.crypto.IdentityKeys
import com.sunshine.mesh.core.ids.PeerId
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HkdfSessionTest {

    @Test
    fun bothSidesDeriveMatchingDirectionalKeys() {
        val alice = identity(seed = 1)
        val bob = identity(seed = 100)
        val aliceSession = HkdfSession.derive(alice, bob.publicKey)
        val bobSession = HkdfSession.derive(bob, alice.publicKey)
        try {
            val aliceOutbound = aliceSession.useOutboundKey { it.copyOf() }
            val aliceInbound = aliceSession.useInboundKey { it.copyOf() }
            val bobOutbound = bobSession.useOutboundKey { it.copyOf() }
            val bobInbound = bobSession.useInboundKey { it.copyOf() }

            assertArrayEquals(aliceOutbound, bobInbound)
            assertArrayEquals(aliceInbound, bobOutbound)
            assertFalse(aliceOutbound.contentEquals(aliceInbound))

            aliceOutbound.fill(0)
            aliceInbound.fill(0)
            bobOutbound.fill(0)
            bobInbound.fill(0)
        } finally {
            aliceSession.close()
            bobSession.close()
            alice.close()
            bob.close()
        }
    }

    @Test
    fun differentPeerProducesDifferentKeys() {
        val alice = identity(seed = 1)
        val bob = identity(seed = 100)
        val carol = identity(seed = 200)
        val toBob = HkdfSession.derive(alice, bob.publicKey)
        val toCarol = HkdfSession.derive(alice, carol.publicKey)
        try {
            val bobKey = toBob.useOutboundKey { it.copyOf() }
            val carolKey = toCarol.useOutboundKey { it.copyOf() }

            assertFalse(bobKey.contentEquals(carolKey))

            bobKey.fill(0)
            carolKey.fill(0)
        } finally {
            toBob.close()
            toCarol.close()
            alice.close()
            bob.close()
            carol.close()
        }
    }

    @Test
    fun rejectsPublicKeyOfWrongSize() {
        val identity = identity(seed = 1)
        try {
            val exception = assertThrows(E2EException::class.java) {
                HkdfSession.derive(identity, ByteArray(31))
            }

            assertEquals(E2EException.Code.INVALID_PUBLIC_KEY, exception.code)
        } finally {
            identity.close()
        }
    }

    @Test
    fun closeRejectsFurtherKeyUse() {
        val alice = identity(seed = 1)
        val bob = identity(seed = 100)
        val session = HkdfSession.derive(alice, bob.publicKey)
        session.close()

        assertThrows(IllegalStateException::class.java) {
            session.useOutboundKey { it }
        }
        assertThrows(IllegalStateException::class.java) {
            session.useInboundKey { it }
        }

        alice.close()
        bob.close()
    }

    @Test
    fun toStringRedactsKeyMaterial() {
        val alice = identity(seed = 1)
        val bob = identity(seed = 100)
        val session = HkdfSession.derive(alice, bob.publicKey)
        try {
            val text = session.toString()

            assertTrue(text.contains(alice.peerId.value))
            assertTrue(text.contains(bob.peerId.value))
            assertTrue(text.contains("redacted"))
        } finally {
            session.close()
            alice.close()
            bob.close()
        }
    }

    @Test
    fun sessionKeysCloseWipesBothBuffers() {
        val outbound = ByteArray(32) { 7 }
        val inbound = ByteArray(32) { 9 }
        val keys = SessionKeys(outbound, inbound)

        keys.close()

        assertTrue(outbound.all { it == 0.toByte() })
        assertTrue(inbound.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) {
            keys.useOutbound { it }
        }
        assertThrows(IllegalStateException::class.java) {
            keys.useInbound { it }
        }
    }

    private fun identity(seed: Int): IdentityMaterial {
        val privateKeyBytes = ByteArray(32) { index -> (seed + index).toByte() }
        val privateKey = X25519PrivateKeyParameters(privateKeyBytes, 0)
        val publicKey = privateKey.generatePublicKey().encoded
        return IdentityMaterial.from(
            peerId = PeerId.fromPublicKey(publicKey),
            publicKey = publicKey,
            privateKey = privateKey.encoded,
        )
    }
}
