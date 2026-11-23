package com.sunshine.mesh.core.mesh

import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException

/**
 * Outer mesh header, separate from the E2E envelope. Relays rewrite [ttl]
 * without touching the authenticated envelope bytes.
 *
 * Wire layout: version(1) | ttl(1) | msgId(16) | senderId(8) | recipientId(8).
 * [ttl] is a hop counter, not a timestamp: every relay decrements it.
 */
data class MeshHeader(
    val version: Int,
    val ttl: Int,
    val msgId: MessageId,
    val senderId: PeerId,
    val recipientId: PeerId,
) {

    init {
        require(version in 0..MAX_BYTE) { "version must fit one byte" }
        require(ttl in 0..MAX_BYTE) { "ttl must fit one byte" }
    }

    fun encode(): ByteArray {
        val bytes = ByteArray(SIZE)
        bytes[OFFSET_VERSION] = version.toByte()
        bytes[OFFSET_TTL] = ttl.toByte()
        msgId.toByteArray().copyInto(bytes, destinationOffset = OFFSET_MSG_ID)
        senderId.toByteArray().copyInto(bytes, destinationOffset = OFFSET_SENDER_ID)
        recipientId.toByteArray().copyInto(bytes, destinationOffset = OFFSET_RECIPIENT_ID)
        return bytes
    }

    companion object {
        const val SIZE = 34
        const val VERSION = 1

        private const val MAX_BYTE = 0xff
        private const val OFFSET_VERSION = 0
        private const val OFFSET_TTL = 1
        private const val OFFSET_MSG_ID = 2
        private const val OFFSET_SENDER_ID = 18
        private const val OFFSET_RECIPIENT_ID = 26

        fun decode(bytes: ByteArray): MeshHeader {
            if (bytes.size != SIZE) {
                throw protocolError("mesh header must be $SIZE bytes")
            }
            val version = bytes[OFFSET_VERSION].toInt() and MAX_BYTE
            if (version != VERSION) {
                throw protocolError("unsupported mesh header version")
            }
            return MeshHeader(
                version = version,
                ttl = bytes[OFFSET_TTL].toInt() and MAX_BYTE,
                msgId = MessageId.fromBytes(
                    bytes.copyOfRange(OFFSET_MSG_ID, OFFSET_SENDER_ID),
                ),
                senderId = PeerId.fromBytes(
                    bytes.copyOfRange(OFFSET_SENDER_ID, OFFSET_RECIPIENT_ID),
                ),
                recipientId = PeerId.fromBytes(
                    bytes.copyOfRange(OFFSET_RECIPIENT_ID, SIZE),
                ),
            )
        }

        private fun protocolError(message: String): TransportException =
            TransportException(
                TransportError.PROTOCOL_ERROR,
                IllegalArgumentException(message),
            )
    }
}
