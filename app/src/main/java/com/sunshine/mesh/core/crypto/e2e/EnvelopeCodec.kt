package com.sunshine.mesh.core.crypto.e2e

import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import java.security.SecureRandom

/**
 * Fixed-size envelope codec. Every packet is exactly [ENVELOPE_BYTES], so the
 * relay cannot infer message length from the datagram size.
 *
 * Wire layout:
 * version(1) | type(1) | messageId(16) | senderId(8) | recipientId(8) |
 * paddingLength(2) | partIndex(1) | totalParts(1) | nonce(12) |
 * ciphertext(446) | tag(16).
 *
 * Padding is part of the encrypted plaintext: [pad] appends random bytes up to
 * [CIPHERTEXT_BYTES], [unpad] removes exactly [EnvelopeHeader.paddingLength]
 * bytes after authentication. The two-byte length is required: with a 512-byte
 * packet a short message needs 445 padding bytes, which one byte cannot encode.
 */
object EnvelopeCodec {
    const val ENVELOPE_BYTES = 512
    const val AAD_BYTES = EnvelopeAad.BYTES
    const val PADDING_LENGTH_BYTES = 2
    const val PART_INDEX_BYTES = 1
    const val TOTAL_PARTS_BYTES = 1
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val CIPHERTEXT_BYTES = ENVELOPE_BYTES - AAD_BYTES - NONCE_BYTES - TAG_BYTES
    const val MAX_PADDING_BYTES = CIPHERTEXT_BYTES
    const val MAX_PLAINTEXT_BYTES = CIPHERTEXT_BYTES
    const val MAX_PARTS = 255

    private const val OFFSET_VERSION = 0
    private const val OFFSET_TYPE = 1
    private const val OFFSET_MESSAGE_ID = 2
    private const val OFFSET_SENDER_ID = 18
    private const val OFFSET_RECIPIENT_ID = 26
    private const val OFFSET_PADDING_LENGTH = 34
    private const val OFFSET_PART_INDEX = 36
    private const val OFFSET_TOTAL_PARTS = 37
    private const val OFFSET_NONCE = OFFSET_TOTAL_PARTS + TOTAL_PARTS_BYTES
    private const val OFFSET_CIPHERTEXT = OFFSET_NONCE + NONCE_BYTES
    private const val OFFSET_TAG = OFFSET_CIPHERTEXT + CIPHERTEXT_BYTES

    /**
     * Appends random padding so the plaintext buffer fed to AES-GCM is exactly
     * [CIPHERTEXT_BYTES]. The caller owns the returned buffer and must close it
     * after encryption.
     */
    fun pad(plaintext: ByteArray, random: SecureRandom): PaddedPayload {
        if (plaintext.size > MAX_PLAINTEXT_BYTES) {
            throw E2EException(E2EException.Code.ENVELOPE_TOO_LARGE)
        }

        val paddingLength = CIPHERTEXT_BYTES - plaintext.size
        val padded = ByteArray(CIPHERTEXT_BYTES)
        plaintext.copyInto(padded)
        if (paddingLength > 0) {
            val padding = ByteArray(paddingLength)
            random.nextBytes(padding)
            padding.copyInto(padded, plaintext.size)
            padding.fill(0)
        }
        return PaddedPayload(bytes = padded, paddingLength = paddingLength)
    }

    /** Returns the plaintext prefix after tag verification and padding removal. */
    fun unpad(padded: ByteArray, paddingLength: Int): ByteArray {
        if (padded.size != CIPHERTEXT_BYTES ||
            paddingLength !in 0..MAX_PADDING_BYTES
        ) {
            throw E2EException(E2EException.Code.MALFORMED_ENVELOPE)
        }
        return padded.copyOfRange(0, padded.size - paddingLength)
    }

    fun encode(
        header: EnvelopeHeader,
        nonce: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray,
    ): ByteArray {
        if (header.paddingLength !in 0..MAX_PADDING_BYTES ||
            header.totalParts !in 1..MAX_PARTS ||
            header.partIndex !in 0 until header.totalParts ||
            nonce.size != NONCE_BYTES ||
            ciphertext.size != CIPHERTEXT_BYTES ||
            tag.size != TAG_BYTES
        ) {
            throw E2EException(E2EException.Code.MALFORMED_ENVELOPE)
        }

        return ByteArray(ENVELOPE_BYTES).also { packet ->
            packet[OFFSET_VERSION] = header.version.toByte()
            packet[OFFSET_TYPE] = header.type.code
            header.messageId.toByteArray().copyInto(packet, OFFSET_MESSAGE_ID)
            header.senderId.toByteArray().copyInto(packet, OFFSET_SENDER_ID)
            header.recipientId.toByteArray().copyInto(packet, OFFSET_RECIPIENT_ID)
            packet[OFFSET_PADDING_LENGTH] = (header.paddingLength ushr 8).toByte()
            packet[OFFSET_PADDING_LENGTH + 1] = header.paddingLength.toByte()
            packet[OFFSET_PART_INDEX] = header.partIndex.toByte()
            packet[OFFSET_TOTAL_PARTS] = header.totalParts.toByte()
            nonce.copyInto(packet, OFFSET_NONCE)
            ciphertext.copyInto(packet, OFFSET_CIPHERTEXT)
            tag.copyInto(packet, OFFSET_TAG)
        }
    }

    /**
     * Parses the fixed-size packet. Oversized input is rejected by its length
     * before any copying, parsing or decrypting happens.
     */
    fun decode(packet: ByteArray): E2EEnvelope {
        if (packet.size > ENVELOPE_BYTES) {
            throw E2EException(E2EException.Code.ENVELOPE_TOO_LARGE)
        }
        if (packet.size != ENVELOPE_BYTES) {
            throw E2EException(E2EException.Code.MALFORMED_ENVELOPE)
        }

        val version = packet[OFFSET_VERSION].toInt() and 0xff
        if (version != EnvelopeHeader.CURRENT_VERSION) {
            throw E2EException(E2EException.Code.UNKNOWN_VERSION)
        }
        val type = EnvelopeType.fromCode(packet[OFFSET_TYPE])
        val paddingLength = readPaddingLength(packet)
        if (paddingLength > MAX_PADDING_BYTES) {
            throw E2EException(E2EException.Code.MALFORMED_ENVELOPE)
        }
        val partIndex = packet[OFFSET_PART_INDEX].toInt() and 0xff
        val totalParts = packet[OFFSET_TOTAL_PARTS].toInt() and 0xff
        if (totalParts !in 1..MAX_PARTS || partIndex >= totalParts) {
            throw E2EException(E2EException.Code.MALFORMED_ENVELOPE)
        }

        return E2EEnvelope(
            header = EnvelopeHeader(
                version = version,
                type = type,
                messageId = MessageId.fromBytes(
                    packet.copyOfRange(OFFSET_MESSAGE_ID, OFFSET_MESSAGE_ID + 16),
                ),
                senderId = PeerId.fromBytes(
                    packet.copyOfRange(OFFSET_SENDER_ID, OFFSET_SENDER_ID + 8),
                ),
                recipientId = PeerId.fromBytes(
                    packet.copyOfRange(OFFSET_RECIPIENT_ID, OFFSET_RECIPIENT_ID + 8),
                ),
                paddingLength = paddingLength,
                partIndex = partIndex,
                totalParts = totalParts,
            ),
            nonce = packet.copyOfRange(OFFSET_NONCE, OFFSET_NONCE + NONCE_BYTES),
            ciphertext = packet.copyOfRange(
                OFFSET_CIPHERTEXT,
                OFFSET_CIPHERTEXT + CIPHERTEXT_BYTES,
            ),
            tag = packet.copyOfRange(OFFSET_TAG, OFFSET_TAG + TAG_BYTES),
        )
    }

    private fun readPaddingLength(packet: ByteArray): Int =
        ((packet[OFFSET_PADDING_LENGTH].toInt() and 0xff) shl 8) or
            (packet[OFFSET_PADDING_LENGTH + 1].toInt() and 0xff)
}
