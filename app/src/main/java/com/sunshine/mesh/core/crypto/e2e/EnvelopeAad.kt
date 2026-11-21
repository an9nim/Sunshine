package com.sunshine.mesh.core.crypto.e2e

/**
 * Bytes authenticated by AES-GCM but not encrypted:
 * version(1) | type(1) | messageId(16) | senderId(8) | recipientId(8) |
 * paddingLength(2) | partIndex(1) | totalParts(1).
 *
 * Public keys and mesh TTL are deliberately absent: keys are bound through
 * HKDF, while a relay must be able to rewrite TTL without breaking the tag.
 */
object EnvelopeAad {
    const val BYTES = 38

    private const val OFFSET_VERSION = 0
    private const val OFFSET_TYPE = 1
    private const val OFFSET_MESSAGE_ID = 2
    private const val OFFSET_SENDER_ID = 18
    private const val OFFSET_RECIPIENT_ID = 26
    private const val OFFSET_PADDING_LENGTH = 34
    private const val OFFSET_PART_INDEX = 36
    private const val OFFSET_TOTAL_PARTS = 37

    fun build(header: EnvelopeHeader): ByteArray {
        val aad = ByteArray(BYTES)
        aad[OFFSET_VERSION] = header.version.toByte()
        aad[OFFSET_TYPE] = header.type.code
        header.messageId.toByteArray().copyInto(aad, OFFSET_MESSAGE_ID)
        header.senderId.toByteArray().copyInto(aad, OFFSET_SENDER_ID)
        header.recipientId.toByteArray().copyInto(aad, OFFSET_RECIPIENT_ID)
        aad[OFFSET_PADDING_LENGTH] = (header.paddingLength ushr 8).toByte()
        aad[OFFSET_PADDING_LENGTH + 1] = header.paddingLength.toByte()
        aad[OFFSET_PART_INDEX] = header.partIndex.toByte()
        aad[OFFSET_TOTAL_PARTS] = header.totalParts.toByte()
        return aad
    }
}
