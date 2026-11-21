package com.sunshine.mesh.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sunshine.mesh.domain.model.TrustState

@Entity(
    tableName = "peers",
    indices = [Index(value = ["public_key"], unique = true)],
)
data class PeerEntity(
    @PrimaryKey
    @ColumnInfo(name = "peer_id")
    val peerId: String,
    /** First key ever seen for this peer id: the immutable TOFU anchor. */
    @ColumnInfo(name = "public_key")
    val publicKey: ByteArray,
    /** Newest observed key, so a change can be shown and accepted explicitly. */
    @ColumnInfo(name = "last_seen_public_key")
    val lastSeenPublicKey: ByteArray,
    @ColumnInfo(name = "nickname")
    val nickname: String,
    @ColumnInfo(name = "first_seen_at")
    val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long,
    @ColumnInfo(name = "trust_state")
    val trustState: TrustState,
    @ColumnInfo(name = "verified_at")
    val verifiedAt: Long?,
)
