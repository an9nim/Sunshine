package com.sunshine.mesh.data.message

import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.domain.model.MessageDirection
import com.sunshine.mesh.domain.model.MessageStatus

/**
 * One stored message. [body] is plaintext for the UI; [packet] is the encrypted
 * outbox representation and is null for incoming messages.
 */
data class MessageRecord(
    val messageId: MessageId,
    val msgId: MessageId,
    val peerId: PeerId,
    val direction: MessageDirection,
    val body: ByteArray,
    val createdAt: Long,
    val status: MessageStatus,
    val packet: ByteArray?,
) {

    override fun toString(): String =
        "MessageRecord(messageId=$messageId, msgId=$msgId, peerId=$peerId, " +
            "direction=$direction, body=${body.size}B, status=$status, " +
            "packet=${packet?.size ?: 0}B)"
}

interface MessageStore {

    /** false when message_id or msg_id already exists. */
    suspend fun insert(message: MessageRecord): Boolean

    suspend fun findByMsgId(msgId: MessageId): MessageRecord?

    suspend fun findPending(limit: Int = DEFAULT_PENDING_LIMIT): List<MessageRecord>

    /**
     * Status transitions are guarded: SENT applies only to PENDING, DELIVERED
     * applies to PENDING or SENT. DELIVERED is never downgraded.
     */
    suspend fun updateStatus(msgId: MessageId, status: MessageStatus): Boolean

    suspend fun updatePacket(msgId: MessageId, packet: ByteArray?): Boolean

    companion object {
        const val DEFAULT_PENDING_LIMIT = 64
    }
}
