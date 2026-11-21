package com.sunshine.mesh.core.crypto.e2e

import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EnvelopeAadTest {

    @Test
    fun serializesFieldsInDocumentedOrder() {
        val header = header(paddingLength = 258, partIndex = 4, totalParts = 7)
        val aad = EnvelopeAad.build(header)

        assertEquals(38, aad.size)
        assertEquals(1, aad[0].toInt() and 0xff)
        assertEquals(EnvelopeType.TEXT.code, aad[1])
        assertArrayEquals(MESSAGE_ID.toByteArray(), aad.copyOfRange(2, 18))
        assertArrayEquals(SENDER_ID.toByteArray(), aad.copyOfRange(18, 26))
        assertArrayEquals(RECIPIENT_ID.toByteArray(), aad.copyOfRange(26, 34))
        assertEquals(0x01, aad[34].toInt() and 0xff)
        assertEquals(0x02, aad[35].toInt() and 0xff)
        assertEquals(4, aad[36].toInt() and 0xff)
        assertEquals(7, aad[37].toInt() and 0xff)
    }

    @Test
    fun paddingLengthIsAuthenticated() {
        val withoutPadding = EnvelopeAad.build(header(paddingLength = 0))
        val withPadding = EnvelopeAad.build(header(paddingLength = 1))

        assertFalse(withoutPadding.contentEquals(withPadding))
    }

    @Test
    fun fragmentPositionIsAuthenticated() {
        val base = EnvelopeAad.build(header(partIndex = 0, totalParts = 3))
        val otherPart = EnvelopeAad.build(header(partIndex = 1, totalParts = 3))
        val otherTotal = EnvelopeAad.build(header(partIndex = 0, totalParts = 4))

        assertFalse(base.contentEquals(otherPart))
        assertFalse(base.contentEquals(otherTotal))
    }

    @Test
    fun changingAnyIdentityFieldChangesAad() {
        val base = EnvelopeAad.build(header())
        val variants = listOf(
            header().copy(messageId = OTHER_MESSAGE_ID),
            header().copy(senderId = OTHER_SENDER_ID),
            header().copy(recipientId = OTHER_RECIPIENT_ID),
        )

        variants.forEach { header ->
            assertFalse(base.contentEquals(EnvelopeAad.build(header)))
        }
    }

    private fun header(
        paddingLength: Int = 7,
        partIndex: Int = 0,
        totalParts: Int = 1,
    ): EnvelopeHeader = EnvelopeHeader.text(
        messageId = MESSAGE_ID,
        senderId = SENDER_ID,
        recipientId = RECIPIENT_ID,
        paddingLength = paddingLength,
        partIndex = partIndex,
        totalParts = totalParts,
    )

    private companion object {
        val MESSAGE_ID = MessageId.parse("00112233445566778899aabbccddeeff")
        val OTHER_MESSAGE_ID = MessageId.parse("ffeeddccbbaa99887766554433221100")
        val SENDER_ID = PeerId.parse("0123456789abcdef")
        val OTHER_SENDER_ID = PeerId.parse("1111111111111111")
        val RECIPIENT_ID = PeerId.parse("fedcba9876543210")
        val OTHER_RECIPIENT_ID = PeerId.parse("2222222222222222")
    }
}
