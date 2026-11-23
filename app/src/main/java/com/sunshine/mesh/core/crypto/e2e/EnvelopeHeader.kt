package com.sunshine.mesh.core.crypto.e2e

import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId

/**
 * Authenticated part of an envelope. The same fields are serialized into the
 * 38-byte AAD; TTL and transport length stay outside because a relay may
 * rewrite them. [paddingLength], [partIndex] and [totalParts] are authenticated
 * too, otherwise an attacker could change trimming or fragment placement.
 */
data class EnvelopeHeader(
    val version: Int,
    val type: EnvelopeType,
    val messageId: MessageId,
    val senderId: PeerId,
    val recipientId: PeerId,
    val paddingLength: Int,
    val partIndex: Int,
    val totalParts: Int,
) {
    init {
        require(version == CURRENT_VERSION) {
            "unsupported envelope version: $version"
        }
        require(paddingLength in 0..EnvelopeCodec.MAX_PADDING_BYTES) {
            "paddingLength must be between 0 and ${EnvelopeCodec.MAX_PADDING_BYTES}"
        }
        require(totalParts in 1..EnvelopeCodec.MAX_PARTS) {
            "totalParts must be between 1 and ${EnvelopeCodec.MAX_PARTS}"
        }
        require(partIndex in 0 until totalParts) {
            "partIndex must be between 0 and ${totalParts - 1}"
        }
    }

    companion object {
        const val CURRENT_VERSION = 1

        fun of(
            type: EnvelopeType,
            messageId: MessageId,
            senderId: PeerId,
            recipientId: PeerId,
            paddingLength: Int,
            partIndex: Int,
            totalParts: Int,
        ): EnvelopeHeader = EnvelopeHeader(
            version = CURRENT_VERSION,
            type = type,
            messageId = messageId,
            senderId = senderId,
            recipientId = recipientId,
            paddingLength = paddingLength,
            partIndex = partIndex,
            totalParts = totalParts,
        )

        fun text(
            messageId: MessageId,
            senderId: PeerId,
            recipientId: PeerId,
            paddingLength: Int,
            partIndex: Int,
            totalParts: Int,
        ): EnvelopeHeader = of(
            type = EnvelopeType.TEXT,
            messageId = messageId,
            senderId = senderId,
            recipientId = recipientId,
            paddingLength = paddingLength,
            partIndex = partIndex,
            totalParts = totalParts,
        )
    }
}
