package com.sunshine.mesh.core.crypto

import com.sunshine.mesh.core.ids.PeerId
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityKeysTest {

    @Test
    fun generatesX25519KeysOfExpectedSize() {
        val keyPair = IdentityKeys.generate(SecureRandom())
        try {
            assertEquals(32, keyPair.privateKey.size)
            assertEquals(32, keyPair.publicKey.size)
        } finally {
            keyPair.destroy()
        }
    }

    @Test
    fun generatesDistinctKeyPairs() {
        val first = IdentityKeys.generate(SecureRandom())
        val second = IdentityKeys.generate(SecureRandom())
        try {
            assertFalse(first.publicKey.contentEquals(second.publicKey))
        } finally {
            first.destroy()
            second.destroy()
        }
    }

    @Test
    fun differentX25519KeysProduceDifferentPeerIds() {
        val first = IdentityKeys.generate(SecureRandom())
        val second = IdentityKeys.generate(SecureRandom())
        try {
            val firstPeerId = PeerId.fromPublicKey(first.publicKey)
            val secondPeerId = PeerId.fromPublicKey(second.publicKey)

            assertNotEquals(firstPeerId, secondPeerId)
        } finally {
            first.destroy()
            second.destroy()
        }
    }

    @Test
    fun destroyWipesPrivateKey() {
        val keyPair = IdentityKeys.generate(SecureRandom())

        keyPair.destroy()

        assertTrue(keyPair.privateKey.all { it == 0.toByte() })
    }
}
