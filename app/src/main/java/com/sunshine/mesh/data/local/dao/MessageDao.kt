package com.sunshine.mesh.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sunshine.mesh.data.local.entity.MessageEntity
import com.sunshine.mesh.domain.model.MessageDirection
import com.sunshine.mesh.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: MessageEntity)

    /** Returns the new row id, or -1 when message_id or msg_id already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE peer_id = :peerId ORDER BY created_at ASC")
    fun observeConversation(peerId: String): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM messages WHERE direction = :direction AND status = :status " +
            "ORDER BY created_at ASC",
    )
    suspend fun findForDelivery(
        direction: MessageDirection,
        status: MessageStatus,
    ): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE direction = :direction AND status = :status " +
            "ORDER BY created_at ASC LIMIT :limit",
    )
    suspend fun findForDelivery(
        direction: MessageDirection,
        status: MessageStatus,
        limit: Int,
    ): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE msg_id = :msgId LIMIT 1")
    suspend fun findByMsgId(msgId: String): MessageEntity?

    @Query("UPDATE messages SET packet = :packet WHERE msg_id = :msgId")
    suspend fun updatePacket(msgId: String, packet: ByteArray?): Int

    @Query("UPDATE messages SET status = :status WHERE message_id = :messageId")
    suspend fun updateStatus(messageId: String, status: MessageStatus)

    @Query("UPDATE messages SET status = :status WHERE msg_id = :msgId")
    suspend fun updateStatusByMsgId(msgId: String, status: MessageStatus): Int

    @Query(
        "UPDATE messages SET status = :status " +
            "WHERE msg_id = :msgId AND status IN (:fromStatuses)",
    )
    suspend fun updateStatusFrom(
        msgId: String,
        fromStatuses: List<MessageStatus>,
        status: MessageStatus,
    ): Int
}
