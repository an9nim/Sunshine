package com.sunshine.mesh.core.crypto.e2e

import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeCodecTest {

    @Test
    fun padProducesFixedCiphertextBufferWithRandomBytes() {
        val plaintext = "привет".toByteArray()
        val padded = EnvelopeCodec.pad(plaintext, ConstantRandom(0x5a))

        try {
            assertEquals(EnvelopeCodec.CIPHERTEXT_BYTES, padded.bytes.size)
            assertEquals(EnvelopeCodec.CIPHERTEXT_BYTES - plaintext.size, padded.paddingLength)
            assertArrayEquals(plaintext, padded.bytes.copyOfRange(0, plaintext.size))
            assertTrue(
                padded.bytes.copyOfRange(plaintext.size, padded.bytes.size)
                    .all { it == 0x5a.toByte() },
            )
        } finally {
            padded.close()
        }
    }

    @Test
    fun closeWipesPaddedBuffer() {
        val padded = EnvelopeCodec.pad("secret".toByteArray(), ConstantRandom(0x11))

        padded.close()

        assertTrue(padded.bytes.all { it == 0.toByte() })
    }

    @Test
    fun maximumPlaintextHasNoPadding() {
        val plaintext = ByteArray(EnvelopeCodec.MAX_PLAINTEXT_BYTES) { 1 }
        val padded = EnvelopeCodec.pad(plaintext, ConstantRandom(0x22))

        try {
            assertEquals(0, padded.paddingLength)
            assertEquals(EnvelopeCodec.CIPHERTEXT_BYTES, padded.bytes.size)
        } finally {
            padded.close()
        }
    }

    @Test
    fun emptyPlaintextIsFullyPadded() {
        val padded = EnvelopeCodec.pad(ByteArray(0), ConstantRandom(0x44))

        try {
            assertEquals(EnvelopeCodec.MAX_PADDING_BYTES, padded.paddingLength)
            assertEquals(EnvelopeCodec.CIPHERTEXT_BYTES, padded.bytes.size)
        } finally {
            padded.close()
        }
    }

    @Test
    fun padRejectsPayloadLargerThanFixedBlock() {
        val exception = assertThrows(E2EException::class.java) {
            EnvelopeCodec.pad(ByteArray(EnvelopeCodec.MAX_PLAINTEXT_BYTES + 1), ConstantRandom(0))
        }

        assertEquals(E2EException.Code.ENVELOPE_TOO_LARGE, exception.code)
    }

    @Test
    fun unpadRestoresExactlyTheOriginalMessage() {
        val plaintext = "сообщение".toByteArray()
        val padded = EnvelopeCodec.pad(plaintext, ConstantRandom(0x33))

        try {
            val restored = EnvelopeCodec.unpad(padded.bytes, padded.paddingLength)
            try {
                assertArrayEquals(plaintext, restored)
            } finally {
                restored.fill(0)
            }
        } finally {
            padded.close()
        }
    }

    @Test
    fun unpadRejectsInvalidPadding() {
        val invalidLength = assertThrows(E2EException::class.java) {
            EnvelopeCodec.unpad(
                ByteArray(EnvelopeCodec.CIPHERTEXT_BYTES),
                EnvelopeCodec.MAX_PADDING_BYTES + 1,
            )
        }
        assertEquals(E2EException.Code.MALFORMED_ENVELOPE, invalidLength.code)

        val invalidSize = assertThrows(E2EException::class.java) {
            EnvelopeCodec.unpad(ByteArray(EnvelopeCodec.CIPHERTEXT_BYTES - 1), 0)
        }
        assertEquals(E2EException.Code.MALFORMED_ENVELOPE, invalidSize.code)
    }

    @Test
    fun encodeAndDecodeRoundTripAtFixedSize() {
        val header = header(paddingLength = 258, partIndex = 4, totalParts = 7)
        val nonce = nonce()
        val ciphertext = ciphertext()
        val tag = tag()

        val packet = EnvelopeCodec.encode(header, nonce, ciphertext, tag)
        val decoded = EnvelopeCodec.decode(packet)

        assertEquals(EnvelopeCodec.ENVELOPE_BYTES, packet.size)
        assertEquals(header, decoded.header)
        assertArrayEquals(nonce, decoded.nonce)
        assertArrayEquals(ciphertext, decoded.ciphertext)
        assertArrayEquals(tag, decoded.tag)
        assertEquals(0x01, packet[34].toInt() and 0xff)
        assertEquals(0x02, packet[35].toInt() and 0xff)
        assertEquals(4, packet[36].toInt() and 0xff)
        assertEquals(7, packet[37].toInt() and 0xff)
    }

    @Test
    fun decodeRejectsOversizedPacketBeforeParsing() {
        val exception = assertThrows(E2EException::class.java) {
            EnvelopeCodec.decode(ByteArray(10 * 1024 * 1024))
        }

        assertEquals(E2EException.Code.ENVELOPE_TOO_LARGE, exception.code)
    }

    @Test
    fun decodeRejectsShortPacket() {
        val exception = assertThrows(E2EException::class.java) {
            EnvelopeCodec.decode(ByteArray(EnvelopeCodec.ENVELOPE_BYTES - 1))
        }

        assertEquals(E2EException.Code.MALFORMED_ENVELOPE, exception.code)
    }

    @Test
    fun decodeRejectsUnknownVersionTypeAndPaddingLength() {
        val unknownVersion = validPacket().also { it[0] = 2 }
        assertCode(E2EException.Code.UNKNOWN_VERSION) {
            EnvelopeCodec.decode(unknownVersion)
        }

        val unknownType = validPacket().also { it[1] = 9 }
        assertCode(E2EException.Code.UNKNOWN_TYPE) {
            EnvelopeCodec.decode(unknownType)
        }

        val badPadding = validPacket().also {
            it[34] = 0x01
            it[35] = 0xc1.toByte()
        }
        assertCode(E2EException.Code.MALFORMED_ENVELOPE) {
            EnvelopeCodec.decode(badPadding)
        }

        val zeroParts = validPacket().also {
            it[36] = 0
            it[37] = 0
        }
        assertCode(E2EException.Code.MALFORMED_ENVELOPE) {
            EnvelopeCodec.decode(zeroParts)
        }

        val partOutsideRange = validPacket().also {
            it[36] = 4
            it[37] = 4
        }
        assertCode(E2EException.Code.MALFORMED_ENVELOPE) {
            EnvelopeCodec.decode(partOutsideRange)
        }
    }

    @Test
    fun encodeRejectsWrongComponentSizes() {
        val header = header()
        assertCode(E2EException.Code.MALFORMED_ENVELOPE) {
            EnvelopeCodec.encode(header, ByteArray(11), ciphertext(), tag())
        }
        assertCode(E2EException.Code.MALFORMED_ENVELOPE) {
            EnvelopeCodec.encode(
                header,
                nonce(),
                ByteArray(EnvelopeCodec.CIPHERTEXT_BYTES - 1),
                tag(),
            )
        }
        assertCode(E2EException.Code.MALFORMED_ENVELOPE) {
            EnvelopeCodec.encode(header, nonce(), ciphertext(), ByteArray(15))
        }
    }

    @Test
    fun envelopeToStringDoesNotPrintPayloadBytes() {
        val decoded = EnvelopeCodec.decode(validPacket())
        val text = decoded.toString()

        assertTrue(text.contains("ciphertext=${EnvelopeCodec.CIPHERTEXT_BYTES}B"))
        assertTrue(text.contains("nonce=${EnvelopeCodec.NONCE_BYTES}B"))
        assertFalse(text.contains(decoded.ciphertext.joinToString("")))
    }

    private fun validPacket(): ByteArray = EnvelopeCodec.encode(
        header = header(),
        nonce = nonce(),
        ciphertext = ciphertext(),
        tag = tag(),
    )

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

    private fun nonce(): ByteArray = ByteArray(EnvelopeCodec.NONCE_BYTES) { (it + 1).toByte() }

    private fun ciphertext(): ByteArray =
        ByteArray(EnvelopeCodec.CIPHERTEXT_BYTES) { (it % 251).toByte() }

    private fun tag(): ByteArray = ByteArray(EnvelopeCodec.TAG_BYTES) { (it + 10).toByte() }

    private fun assertCode(expected: E2EException.Code, block: () -> Unit) {
        val exception = assertThrows(E2EException::class.java) { block() }
        assertEquals(expected, exception.code)
    }

    private class ConstantRandom(private val value: Byte) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.fill(value)
        }
    }

    private companion object {
        val MESSAGE_ID = MessageId.parse("00112233445566778899aabbccddeeff")
        val SENDER_ID = PeerId.parse("0123456789abcdef")
        val RECIPIENT_ID = PeerId.parse("fedcba9876543210")
    }
}
