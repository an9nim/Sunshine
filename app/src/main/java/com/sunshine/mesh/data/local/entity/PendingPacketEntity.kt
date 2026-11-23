package com.sunshine.mesh.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A packet addressed to us that we could not open yet. Only ciphertext is
 * stored; the row is deleted after [expiresAt] or once delivery succeeds.
 */
@Entity(
    tableName = "pending_packets",
    indices = [
        Index(value = ["expires_at"]),
        Index(value = ["next_retry_at"]),
    ],
)
data class PendingPacketEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "sender_id")
    val senderId: String,
    @ColumnInfo(name = "payload")
    val payload: ByteArray,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,
    @ColumnInfo(name = "next_retry_at")
    val nextRetryAt: Long,
)
