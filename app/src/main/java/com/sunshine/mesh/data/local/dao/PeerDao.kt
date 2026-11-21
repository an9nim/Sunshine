package com.sunshine.mesh.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sunshine.mesh.data.local.entity.PeerEntity
import com.sunshine.mesh.domain.model.TrustState
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {

    @Query("SELECT * FROM peers ORDER BY last_seen_at DESC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE peer_id = :peerId")
    fun observe(peerId: String): Flow<PeerEntity?>

    @Query("SELECT * FROM peers WHERE peer_id = :peerId")
    suspend fun find(peerId: String): PeerEntity?

    /**
     * First public key for a peer id wins. Updating the anchor would break TOFU
     * without an explicit user decision, so conflicts are intentionally ignored.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(peer: PeerEntity): Long

    @Query(
        "UPDATE peers SET last_seen_public_key = :publicKey, " +
            "trust_state = :trustState, verified_at = NULL, last_seen_at = :seenAt " +
            "WHERE peer_id = :peerId",
    )
    suspend fun updateObservedKey(
        peerId: String,
        publicKey: ByteArray,
        trustState: TrustState,
        seenAt: Long,
    )

    @Query("UPDATE peers SET last_seen_at = :seenAt WHERE peer_id = :peerId")
    suspend fun touch(peerId: String, seenAt: Long)

    /**
     * Compares the expected key inside SQLite, so a key change between the
     * dialog and the button press leaves the row untouched.
     */
    @Query(
        "UPDATE peers SET trust_state = :trustState, verified_at = :verifiedAt, " +
            "last_seen_at = :seenAt " +
            "WHERE peer_id = :peerId AND last_seen_public_key = :expectedKey",
    )
    suspend fun updateTrust(
        peerId: String,
        expectedKey: ByteArray,
        trustState: TrustState,
        verifiedAt: Long?,
        seenAt: Long,
    ): Int

    @Query(
        "UPDATE peers SET nickname = :nickname, last_seen_at = :seenAt " +
            "WHERE peer_id = :peerId",
    )
    suspend fun updateNickname(
        peerId: String,
        nickname: String,
        seenAt: Long,
    ): Int
}
