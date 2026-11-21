package com.sunshine.mesh.core.crypto.e2e

import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EnvelopeHeaderTest {

    @Test
    fun textHeaderUsesCurrentVersionAndFragmentPosition() {
        val header = EnvelopeHeader.text(
            messageId = MESSAGE_ID,
            senderId = SENDER_ID,
            recipientId = RECIPIENT_ID,
            paddingLength = 7,
            partIndex = 1,
            totalParts = 3,
        )

        assertEquals(EnvelopeHeader.CURRENT_VERSION, header.version)
        assertEquals(EnvelopeType.TEXT, header.type)
        assertEquals(7, header.paddingLength)
        assertEquals(1, header.partIndex)
        assertEquals(3, header.totalParts)
    }

    @Test
    fun shortMessageUsesSinglePart() {
        val header = EnvelopeHeader.text(
            messageId = MESSAGE_ID,
            senderId = SENDER_ID,
            recipientId = RECIPIENT_ID,
            paddingLength = EnvelopeCodec.MAX_PADDING_BYTES,
            partIndex = 0,
            totalParts = 1,
        )

        assertEquals(0, header.partIndex)
        assertEquals(1, header.totalParts)
    }

    @Test
    fun rejectsUnknownVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            header(version = EnvelopeHeader.CURRENT_VERSION + 1)
        }
    }

    @Test
    fun rejectsPaddingLengthBeyondCiphertext() {
        assertThrows(IllegalArgumentException::class.java) {
            header(paddingLength = EnvelopeCodec.MAX_PADDING_BYTES + 1)
        }
    }

    @Test
    fun rejectsInvalidPartBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            header(partIndex = 0, totalParts = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            header(partIndex = 0, totalParts = EnvelopeCodec.MAX_PARTS + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            header(partIndex = 3, totalParts = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            header(partIndex = -1, totalParts = 2)
        }
    }

    @Test
    fun textAndUnknownTypeAreDistinct() {
        assertEquals(EnvelopeType.TEXT, EnvelopeType.fromCode(1))
        assertThrows(E2EException::class.java) {
            EnvelopeType.fromCode(2)
        }
    }

    private fun header(
        version: Int = EnvelopeHeader.CURRENT_VERSION,
        paddingLength: Int = 0,
        partIndex: Int = 0,
        totalParts: Int = 1,
    ): EnvelopeHeader = EnvelopeHeader(
        version = version,
        type = EnvelopeType.TEXT,
        messageId = MESSAGE_ID,
        senderId = SENDER_ID,
        recipientId = RECIPIENT_ID,
        paddingLength = paddingLength,
        partIndex = partIndex,
        totalParts = totalParts,
    )

    private companion object {
        val MESSAGE_ID = MessageId.parse("00112233445566778899aabbccddeeff")
        val SENDER_ID = PeerId.parse("0123456789abcdef")
        val RECIPIENT_ID = PeerId.parse("fedcba9876543210")
    }
}
