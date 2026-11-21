package com.sunshine.mesh.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sunshine.mesh.domain.model.MessageDirection
import com.sunshine.mesh.domain.model.MessageStatus

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["peer_id", "created_at"]),
        Index(value = ["msg_id"], unique = true),
        Index(value = ["direction", "status"]),
    ],
)
data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "msg_id")
    val msgId: String,
    @ColumnInfo(name = "peer_id")
    val peerId: String,
    @ColumnInfo(name = "direction")
    val direction: MessageDirection,
    @ColumnInfo(name = "body")
    val body: ByteArray,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "status")
    val status: MessageStatus,
    /**
     * Encrypted wire packet kept only for outgoing messages until the mesh
     * delivers it. Incoming packets are relayed from memory and never stored.
     */
    @ColumnInfo(name = "packet")
    val packet: ByteArray?,
)
