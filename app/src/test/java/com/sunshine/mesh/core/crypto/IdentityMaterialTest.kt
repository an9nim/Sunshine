package com.sunshine.mesh.core.crypto

import com.sunshine.mesh.core.ids.PeerId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityMaterialTest {

    @Test
    fun rejectsPeerIdThatDoesNotMatchPublicKey() {
        val keyPair = IdentityKeys.generate()
        val wrongPeerId = PeerId.parse("0000000000000000")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                IdentityMaterial.from(
                    peerId = wrongPeerId,
                    publicKey = keyPair.publicKey,
                    privateKey = keyPair.privateKey,
                )
            }
        } finally {
            keyPair.destroy()
        }
    }

    @Test
    fun publicKeyReturnsDefensiveCopy() {
        val identity = identity()
        try {
            val first = identity.publicKey
            val expected = first.copyOf()
            first.fill(0)

            assertTrue(expected.contentEquals(identity.publicKey))
        } finally {
            identity.close()
        }
    }

    @Test
    fun closeWipesPrivateKeyAndRejectsFurtherUse() {
        val identity = identity()
        val privateKey = identity.usePrivateKey { it }

        identity.close()

        assertTrue(privateKey.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) {
            identity.usePrivateKey { it }
        }
    }

    @Test
    fun toStringDoesNotContainKeyMaterial() {
        val identity = identity()
        try {
            val text = identity.toString()

            assertTrue(text.contains(identity.peerId.value))
            assertTrue(text.contains("redacted"))
            assertFalse(text.contains(identity.publicKey.joinToString("")))
        } finally {
            identity.close()
        }
    }

    private fun identity(): IdentityMaterial {
        val keyPair = IdentityKeys.generate()
        try {
            return IdentityMaterial.from(
                peerId = PeerId.fromPublicKey(keyPair.publicKey),
                publicKey = keyPair.publicKey,
                privateKey = keyPair.privateKey,
            )
        } finally {
            keyPair.destroy()
        }
    }
}
