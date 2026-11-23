package com.sunshine.mesh.data.message

import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.data.local.dao.MessageDao
import com.sunshine.mesh.data.local.entity.MessageEntity
import com.sunshine.mesh.domain.model.MessageDirection
import com.sunshine.mesh.domain.model.MessageStatus

/**
 * Room-backed message history. The message id and wire msgId are the same value
 * for messages created by Stage 5; the separate column keeps the schema ready
 * for imported history with a different local id.
 */
class RoomMessageStore(
    private val dao: MessageDao,
) : MessageStore {

    override suspend fun insert(message: MessageRecord): Boolean =
        dao.insertIfAbsent(
            MessageEntity(
                messageId = message.messageId.value,
                msgId = message.msgId.value,
                peerId = message.peerId.value,
                direction = message.direction,
                body = message.body.copyOf(),
                createdAt = message.createdAt,
                status = message.status,
                packet = message.packet?.copyOf(),
            ),
        ) != INSERT_CONFLICT

    override suspend fun findByMsgId(msgId: MessageId): MessageRecord? =
        dao.findByMsgId(msgId.value)?.toRecord()

    override suspend fun findPending(limit: Int): List<MessageRecord> =
        dao.findForDelivery(
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.PENDING,
            limit = limit,
        ).map { it.toRecord() }

    override suspend fun updateStatus(
        msgId: MessageId,
        status: MessageStatus,
    ): Boolean {
        val updated = when (status) {
            MessageStatus.SENT -> dao.updateStatusFrom(
                msgId = msgId.value,
                fromStatuses = listOf(MessageStatus.PENDING),
                status = status,
            )

            MessageStatus.DELIVERED -> dao.updateStatusFrom(
                msgId = msgId.value,
                fromStatuses = listOf(MessageStatus.PENDING, MessageStatus.SENT),
                status = status,
            )

            MessageStatus.FAILED -> dao.updateStatusFrom(
                msgId = msgId.value,
                fromStatuses = listOf(MessageStatus.PENDING, MessageStatus.SENT),
                status = status,
            )

            MessageStatus.PENDING,
            MessageStatus.RECEIVED,
            -> dao.updateStatusByMsgId(msgId.value, status)
        }
        return updated > 0
    }

    override suspend fun updatePacket(msgId: MessageId, packet: ByteArray?): Boolean =
        dao.updatePacket(msgId.value, packet?.copyOf()) > 0

    private fun MessageEntity.toRecord(): MessageRecord = MessageRecord(
        messageId = MessageId.parse(messageId),
        msgId = MessageId.parse(msgId),
        peerId = PeerId.parse(peerId),
        direction = direction,
        body = body.copyOf(),
        createdAt = createdAt,
        status = status,
        packet = packet?.copyOf(),
    )

    private companion object {
        const val INSERT_CONFLICT = -1L
    }
}
