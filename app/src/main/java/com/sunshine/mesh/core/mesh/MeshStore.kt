package com.sunshine.mesh.core.mesh

import com.sunshine.mesh.core.ids.PeerId
import java.io.Closeable

/**
 * Persistence for packets addressed to us that we cannot open yet (no peer
 * key / trust pending). Entries expire after [com.sunshine.mesh.core.net.MeshPolicy.STORE_TTL_MS]
 * and are deleted without another decryption attempt.
 */
interface MeshStore {

    suspend fun save(senderId: PeerId, packet: ByteArray, expiresAt: Long)

    /** Non-expired entries whose retry time has come, oldest first. */
    suspend fun loadRetryable(now: Long, limit: Int): List<StoredMeshPacket>

    suspend fun reschedule(id: Long, nextRetryAt: Long)

    suspend fun delete(id: Long)

    suspend fun deleteExpired(now: Long): Int
}

class StoredMeshPacket(
    val id: Long,
    val senderId: PeerId,
    val packet: ByteArray,
    val expiresAt: Long,
) : Closeable {

    override fun close() {
        packet.fill(0)
    }

    override fun toString(): String =
        "StoredMeshPacket(id=$id, senderId=$senderId, packet=${packet.size}B)"
}
