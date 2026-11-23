package com.sunshine.mesh.data.mesh

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.mesh.MeshStore
import com.sunshine.mesh.core.mesh.StoredMeshPacket
import com.sunshine.mesh.core.net.MeshPolicy
import com.sunshine.mesh.data.local.dao.PendingPacketDao
import com.sunshine.mesh.data.local.entity.PendingPacketEntity

/**
 * Room-backed store-and-forward. Only ciphertext is persisted; rows expire
 * after 72 hours and are deleted before any retry attempt.
 */
class RoomMeshStore(
    private val dao: PendingPacketDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : MeshStore {

    override suspend fun save(senderId: PeerId, packet: ByteArray, expiresAt: Long) {
        val now = clock()
        dao.insert(
            PendingPacketEntity(
                senderId = senderId.value,
                payload = packet.copyOf(),
                createdAt = now,
                expiresAt = expiresAt,
                nextRetryAt = now + MeshPolicy.RETRY_INTERVAL_MS,
            ),
        )
    }

    override suspend fun loadRetryable(now: Long, limit: Int): List<StoredMeshPacket> =
        dao.loadRetryable(now, limit).map { entity ->
            StoredMeshPacket(
                id = entity.id,
                senderId = PeerId.parse(entity.senderId),
                packet = entity.payload,
                expiresAt = entity.expiresAt,
            )
        }

    override suspend fun reschedule(id: Long, nextRetryAt: Long) {
        dao.reschedule(id, nextRetryAt)
    }

    override suspend fun delete(id: Long) {
        dao.delete(id)
    }

    override suspend fun deleteExpired(now: Long): Int = dao.deleteExpired(now)
}
