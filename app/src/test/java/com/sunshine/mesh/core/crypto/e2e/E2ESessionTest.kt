package com.sunshine.mesh.core.crypto.e2e

import com.sunshine.mesh.core.ids.MessageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class E2ESessionTest {

    @Test
    fun sealsShortTextAsOneFixedSizeEnvelope() = withSessions { alice, bob ->
        val packets = alice.sealText(MESSAGE_ID, "привет")

        assertEquals(1, packets.size)
        assertTrue(packets.all { it.size == EnvelopeCodec.ENVELOPE_BYTES })

        val header = EnvelopeCodec.decode(packets.single()).header
        assertEquals(MESSAGE_ID, header.messageId)
        assertEquals(alice.localPeerId, header.senderId)
        assertEquals(bob.localPeerId, header.recipientId)
        assertEquals(EnvelopeType.TEXT, header.type)
        assertEquals(0, header.partIndex)
        assertEquals(1, header.totalParts)
    }

    @Test
    fun partCountFollowsThe446ByteBoundary() = withSessions { alice, _ ->
        val cases = listOf(
            0 to 1,
            1 to 1,
            446 to 1,
            447 to 2,
            892 to 2,
            893 to 3,
        )

        for ((bytes, expectedParts) in cases) {
            val packets = alice.sealText(MESSAGE_ID, "a".repeat(bytes))
            assertEquals("bytes=$bytes", expectedParts, packets.size)
            assertTrue(packets.all { it.size == EnvelopeCodec.ENVELOPE_BYTES })
        }
    }

    @Test
    fun fragmentedTextIsReassembledBeforeUtf8Decoding() = withSessions { alice, bob ->
        val text = "Солнце ".repeat(100)
        val packets = alice.sealText(MESSAGE_ID, text)

        assertEquals(3, packets.size)
        assertEquals(text, E2ETestSupport.openText(bob, packets.reversed()))
    }

    @Test
    fun utf8CharacterCanSpanTwoParts() = withSessions { alice, bob ->
        val text = "a".repeat(445) + "😀" + "b"
        val packets = alice.sealText(MESSAGE_ID, text)

        assertEquals(2, packets.size)
        assertEquals(text, E2ETestSupport.openText(bob, packets))
    }

    @Test
    fun maximumMessageUses255PartsAndOneMoreByteIsRejected() = withSessions { alice, _ ->
        val maxBytes = EnvelopeCodec.MAX_PARTS * EnvelopeCodec.MAX_PLAINTEXT_BYTES
        val packets = alice.sealText(MESSAGE_ID, "a".repeat(maxBytes))

        assertEquals(EnvelopeCodec.MAX_PARTS, packets.size)

        val exception = assertThrows(E2EException::class.java) {
            alice.sealText(MESSAGE_ID, "a".repeat(maxBytes + 1))
        }
        assertEquals(E2EException.Code.MESSAGE_TOO_LARGE, exception.code)
    }

    @Test
    fun everyPartUsesADistinctNonce() = withSessions { alice, _ ->
        val packets = alice.sealText(MESSAGE_ID, "a".repeat(1_000))
        val nonces = packets
            .map { EnvelopeCodec.decode(it).nonce }
            .map { nonce -> nonce.joinToString(",") { (it.toInt() and 0xff).toString() } }
            .toSet()

        assertEquals(packets.size, nonces.size)
    }

    @Test
    fun closeRejectsFurtherOperationsAndIsIdempotent() = withSessions { alice, _ ->
        val packets = alice.sealText(MESSAGE_ID, "закрыть")
        alice.close()

        assertThrows(IllegalStateException::class.java) {
            alice.sealText(MESSAGE_ID, "ещё")
        }
        assertThrows(IllegalStateException::class.java) {
            alice.openFragment(packets.single())
        }

        alice.close()
        assertTrue(alice.toString().contains("closed=true"))
    }

    private fun withSessions(block: (alice: E2ESession, bob: E2ESession) -> Unit) {
        val aliceIdentity = E2ETestSupport.identity(seed = 1)
        val bobIdentity = E2ETestSupport.identity(seed = 100)
        val alice = E2ETestSupport.session(aliceIdentity, bobIdentity)
        val bob = E2ETestSupport.session(bobIdentity, aliceIdentity)
        try {
            block(alice, bob)
        } finally {
            alice.close()
            bob.close()
            aliceIdentity.close()
            bobIdentity.close()
        }
    }

    private companion object {
        val MESSAGE_ID = MessageId.parse("00112233445566778899aabbccddeeff")
    }
}
