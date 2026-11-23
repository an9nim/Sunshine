package com.sunshine.mesh.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.sunshine.mesh.data.local.entity.PendingPacketEntity

@Dao
interface PendingPacketDao {

    @Insert
    suspend fun insert(entity: PendingPacketEntity): Long

    @Query(
        "SELECT * FROM pending_packets " +
            "WHERE expires_at > :now AND next_retry_at <= :now " +
            "ORDER BY next_retry_at ASC LIMIT :limit",
    )
    suspend fun loadRetryable(now: Long, limit: Int): List<PendingPacketEntity>

    @Query("UPDATE pending_packets SET next_retry_at = :nextRetryAt WHERE id = :id")
    suspend fun reschedule(id: Long, nextRetryAt: Long)

    @Query("DELETE FROM pending_packets WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM pending_packets WHERE expires_at <= :now")
    suspend fun deleteExpired(now: Long): Int
}
