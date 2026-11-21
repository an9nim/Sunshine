package com.sunshine.mesh.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sunshine.mesh.data.local.entity.SeenIdEntity

@Dao
interface SeenIdDao {

    @Query(
        "SELECT EXISTS(SELECT 1 FROM seen_ids " +
            "WHERE msg_id = :msgId AND part_index = :partIndex)",
    )
    suspend fun contains(msgId: String, partIndex: Int): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(seenId: SeenIdEntity): Long

    @Query("DELETE FROM seen_ids WHERE seen_at < :threshold")
    suspend fun deleteOlderThan(threshold: Long): Int
}
