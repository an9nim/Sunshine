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

    @Query("UPDATE messages SET status = :status WHERE message_id = :messageId")
    suspend fun updateStatus(messageId: String, status: MessageStatus)
}
