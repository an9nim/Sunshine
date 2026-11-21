package com.sunshine.mesh.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "seen_ids",
    primaryKeys = ["msg_id", "part_index"],
    indices = [Index(value = ["seen_at"])],
)
data class SeenIdEntity(
    @ColumnInfo(name = "msg_id")
    val msgId: String,
    @ColumnInfo(name = "part_index")
    val partIndex: Int,
    @ColumnInfo(name = "seen_at")
    val seenAt: Long,
)
