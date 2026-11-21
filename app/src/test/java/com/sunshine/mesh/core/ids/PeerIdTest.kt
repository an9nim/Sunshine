package com.sunshine.mesh.core.ids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerIdTest {

    @Test
    fun derivesStableLowercaseFingerprint() {
        val publicKey = ByteArray(32) { 1 }
        val first = PeerId.fromPublicKey(publicKey)
        val second = PeerId.fromPublicKey(publicKey)

        assertEquals(first, second)
        assertEquals(16, first.value.length)
        assertTrue(first.value.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun differentKeysProduceDifferentIds() {
        val first = PeerId.fromPublicKey(ByteArray(32) { 1 })
        val second = PeerId.fromPublicKey(ByteArray(32) { 2 })

        assertTrue(first != second)
    }

    @Test
    fun parseNormalizesCase() {
        val parsed = PeerId.parse("0123456789ABCDEF")

        assertEquals("0123456789abcdef", parsed.value)
    }

    @Test
    fun parseRejectsWrongLength() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerId.parse("0123456789abcdef0")
        }
    }

    @Test
    fun fromPublicKeyRejectsWrongKeySize() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerId.fromPublicKey(ByteArray(31))
        }
    }

    @Test
    fun rawBytesRoundTrip() {
        val peerId = PeerId.parse("0123456789abcdef")
        val bytes = peerId.toByteArray()

        assertEquals(8, bytes.size)
        assertEquals(peerId, PeerId.fromBytes(bytes))
    }

    @Test
    fun rawBytesRejectWrongLength() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerId.fromBytes(ByteArray(7))
        }
    }
}
