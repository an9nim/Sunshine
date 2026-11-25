package com.sunshine.mesh.data.peer

import com.sunshine.mesh.core.crypto.e2e.E2EException
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.data.local.dao.PeerDao
import com.sunshine.mesh.data.local.entity.PeerEntity
import com.sunshine.mesh.domain.model.Peer
import com.sunshine.mesh.domain.model.TrustResult
import com.sunshine.mesh.domain.model.TrustState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * TOFU storage. The first observed key becomes [PeerEntity.publicKey], an
 * immutable anchor. Every later key is compared with lastSeenPublicKey: a
 * change marks the peer KEY_CHANGED and clears manual verification.
 *
 * The incoming peerId is treated as an eight-byte lookup label, not as proof
 * that the key hashes to it. Requiring PeerId.fromPublicKey(key) == peerId here
 * would make CHANGED unreachable: our own PeerId is derived from the key, so an
 * honest peer cannot present a different key under the same id. The trust
 * decision is bound to the key through the TOFU anchor and the safety number;
 * the label only selects the stored peer.
 */
class PeerRepository(
    private val peerDao: PeerDao,
    private val transactions: PeerTransactions,
    private val now: () -> Long = System::currentTimeMillis,
) : PeerTrustStore, PeerKeyStore, PeerTrustActions {

    override suspend fun checkAndUpdateKey(
        peerId: PeerId,
        newPublicKey: ByteArray,
    ): TrustResult {
        requireMatchingKey(newPublicKey)

        return transactions.run {
            val timestamp = now()
            val existing = peerDao.find(peerId.value)
            if (existing == null) {
                val entity = PeerEntity(
                    peerId = peerId.value,
                    publicKey = newPublicKey.copyOf(),
                    lastSeenPublicKey = newPublicKey.copyOf(),
                    nickname = "",
                    firstSeenAt = timestamp,
                    lastSeenAt = timestamp,
                    trustState = TrustState.UNKNOWN,
                    verifiedAt = null,
                )
                val rowId = peerDao.insertIfAbsent(entity)
                if (rowId != INSERT_CONFLICT) {
                    TrustResult.NEW
                } else {
                    observeKey(requireNotNull(peerDao.find(peerId.value)), newPublicKey, timestamp)
                }
            } else {
                observeKey(existing, newPublicKey, timestamp)
            }
        }
    }

    /**
     * Accepts the key the user actually saw. Returns false without changing
     * anything if lastSeenPublicKey moved since the dialog was shown.
     */
    override suspend fun acceptKey(peerId: PeerId, expectedPublicKey: ByteArray): Boolean {
        requireMatchingKey(expectedPublicKey)

        return transactions.run {
            val existing = peerDao.find(peerId.value) ?: return@run false
            if (!existing.lastSeenPublicKey.contentEquals(expectedPublicKey)) {
                return@run false
            }

            val nextState = if (existing.trustState == TrustState.VERIFIED) {
                TrustState.VERIFIED
            } else {
                TrustState.TOFU_ACCEPTED
            }
            val verifiedAt = if (nextState == TrustState.VERIFIED) {
                existing.verifiedAt
            } else {
                null
            }
            peerDao.updateTrust(
                peerId = peerId.value,
                expectedKey = expectedPublicKey.copyOf(),
                trustState = nextState,
                verifiedAt = verifiedAt,
                seenAt = now(),
            ) > 0
        }
    }

    /** Marks the displayed key as manually compared, out of band. */
    override suspend fun verifyManually(peerId: PeerId, expectedPublicKey: ByteArray): Boolean {
        requireMatchingKey(expectedPublicKey)

        return transactions.run {
            val existing = peerDao.find(peerId.value) ?: return@run false
            if (!existing.lastSeenPublicKey.contentEquals(expectedPublicKey)) {
                return@run false
            }
            val timestamp = now()
            peerDao.updateTrust(
                peerId = peerId.value,
                expectedKey = expectedPublicKey.copyOf(),
                trustState = TrustState.VERIFIED,
                verifiedAt = timestamp,
                seenAt = timestamp,
            ) > 0
        }
    }

    override suspend fun getTrustState(peerId: PeerId): TrustState =
        peerDao.find(peerId.value)?.trustState ?: TrustState.UNKNOWN

    override suspend fun getPeer(peerId: PeerId): Peer? =
        peerDao.find(peerId.value)?.toDomain()

    fun observePeer(peerId: PeerId): Flow<Peer?> =
        peerDao.observe(peerId.value).map { it?.toDomain() }

    override fun observeAll(): Flow<List<Peer>> =
        peerDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun updateNickname(peerId: PeerId, nickname: String): Boolean {
        require(nickname.isNotBlank()) { "nickname must not be blank" }
        return peerDao.updateNickname(peerId.value, nickname, now()) > 0
    }

    private suspend fun observeKey(
        existing: PeerEntity,
        newPublicKey: ByteArray,
        timestamp: Long,
    ): TrustResult {
        if (existing.lastSeenPublicKey.contentEquals(newPublicKey)) {
            peerDao.touch(existing.peerId, timestamp)
            return TrustResult.SAME
        }

        peerDao.updateObservedKey(
            peerId = existing.peerId,
            publicKey = newPublicKey.copyOf(),
            trustState = TrustState.KEY_CHANGED,
            seenAt = timestamp,
        )
        return TrustResult.CHANGED
    }

    private fun requireMatchingKey(publicKey: ByteArray) {
        if (publicKey.size != PUBLIC_KEY_BYTES) {
            throw E2EException(E2EException.Code.INVALID_PUBLIC_KEY)
        }
    }

    private fun PeerEntity.toDomain(): Peer = Peer(
        peerId = PeerId.parse(peerId),
        nickname = nickname,
        firstSeenPublicKey = publicKey.copyOf(),
        lastSeenPublicKey = lastSeenPublicKey.copyOf(),
        trustState = trustState,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
        verifiedAt = verifiedAt,
    )

    private companion object {
        const val PUBLIC_KEY_BYTES = 32
        const val INSERT_CONFLICT = -1L
    }
}
