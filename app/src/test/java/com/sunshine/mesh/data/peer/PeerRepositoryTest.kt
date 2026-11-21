package com.sunshine.mesh.data.peer

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.data.local.dao.PeerDao
import com.sunshine.mesh.data.local.entity.PeerEntity
import com.sunshine.mesh.domain.model.TrustResult
import com.sunshine.mesh.domain.model.TrustState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerRepositoryTest {

    private val dao = FakePeerDao()
    private var currentTime = 1_000L
    private val repository = PeerRepository(
        peerDao = dao,
        transactions = ImmediateTransactions,
        now = { currentTime },
    )

    @Test
    fun newKeyIsStoredAsUnknown() = runBlocking {
        val result = repository.checkAndUpdateKey(PEER_ID, FIRST_KEY)

        assertEquals(TrustResult.NEW, result)
        val entity = requireNotNull(dao.peers[PEER_ID.value])
        assertEquals(TrustState.UNKNOWN, entity.trustState)
        assertArrayEquals(FIRST_KEY, entity.publicKey)
        assertArrayEquals(FIRST_KEY, entity.lastSeenPublicKey)
        assertNull(entity.verifiedAt)
        assertEquals(currentTime, entity.firstSeenAt)
    }

    @Test
    fun sameKeyReturnsSameButDoesNotImplicitlyTrust() = runBlocking {
        repository.checkAndUpdateKey(PEER_ID, FIRST_KEY)
        currentTime += 10

        val result = repository.checkAndUpdateKey(PEER_ID, FIRST_KEY)

        assertEquals(TrustResult.SAME, result)
        assertEquals(TrustState.UNKNOWN, repository.getTrustState(PEER_ID))
        assertEquals(currentTime, requireNotNull(dao.peers[PEER_ID.value]).lastSeenAt)
    }

    @Test
    fun changedKeyMarksKeyChangedAndKeepsAnchor() = runBlocking {
        repository.checkAndUpdateKey(PEER_ID, FIRST_KEY)
        assertTrue(repository.verifyManually(PEER_ID, FIRST_KEY))
        currentTime += 20

        val result = repository.checkAndUpdateKey(PEER_ID, SECOND_KEY)

        assertEquals(TrustResult.CHANGED, result)
        val entity = requireNotNull(dao.peers[PEER_ID.value])
        assertEquals(TrustState.KEY_CHANGED, entity.trustState)
        assertArrayEquals(FIRST_KEY, entity.publicKey)
        assertArrayEquals(SECOND_KEY, entity.lastSeenPublicKey)
        assertNull(entity.verifiedAt)
    }

    @Test
    fun changedKeyAgainStaysKeyChanged() = runBlocking {
        repository.checkAndUpdateKey(PEER_ID, FIRST_KEY)
        repository.checkAndUpdateKey(PEER_ID, SECOND_KEY)
        currentTime += 20

        val result = repository.checkAndUpdateKey(PEER_ID, THIRD_KEY)

        assertEquals(TrustResult.CHANGED, result)
        assertEquals(TrustState.KEY_CHANGED, repository.getTrustState(PEER_ID))
        assertArrayEquals(THIRD_KEY, requireNotNull(dao.peers[PEER_ID.value]).lastSeenPublicKey)
    }

    @Test
    fun acceptKeyRejectsStaleExpectedKey() = runBlocking {
        repository.checkAndUpdateKey(PEER_ID, FIRST_KEY)
        repository.checkAndUpdateKey(PEER_ID, SECOND_KEY)

        val accepted = repository.acceptKey(PEER_ID, FIRST_KEY)

        assertFalse(accepted)
        val entity = requireNotNull(dao.peers[PEER_ID.value])
        assertEquals(TrustState.KEY_CHANGED, entity.trustState)
        assertArrayEquals(SECOND_KEY, entity.lastSeenPublicKey)
    }

    @Test
    fun acceptKeyAcceptsCurrentKeyAsTofu() = runBlocking {
        repository.checkAndUpdateKey(PEER_ID, FIRST_KEY)

        val accepted = repository.acceptKey(PEER_ID, FIRST_KEY)

        assertTrue(accepted)
        assertEquals(TrustState.TOFU_ACCEPTED, repository.getTrustState(PEER_ID))
    }

    @Test
    fun acceptKeyDoesNotDowngradeVerifiedState() = runBlocking {
        repository.checkAndUpdateKey(PEER_ID, FIRST_KEY)
        assertTrue(repository.verifyManually(PEER_ID, FIRST_KEY))
        val verifiedAt = requireNotNull(dao.peers[PEER_ID.value]).verifiedAt

        val accepted = repository.acceptKey(PEER_ID, FIRST_KEY)

        assertTrue(accepted)
        val entity = requireNotNull(dao.peers[PEER_ID.value])
        assertEquals(TrustState.VERIFIED, entity.trustState)
        assertEquals(verifiedAt, entity.verifiedAt)
    }

    @Test
    fun verifyManuallyRequiresTheDisplayedKey() = runBlocking {
        repository.checkAndUpdateKey(PEER_ID, FIRST_KEY)

        assertFalse(repository.verifyManually(PEER_ID, SECOND_KEY))
        assertEquals(TrustState.UNKNOWN, repository.getTrustState(PEER_ID))

        currentTime += 5
        assertTrue(repository.verifyManually(PEER_ID, FIRST_KEY))
        val entity = requireNotNull(dao.peers[PEER_ID.value])
        assertEquals(TrustState.VERIFIED, entity.trustState)
        assertEquals(currentTime, entity.verifiedAt)
    }

    @Test
    fun acceptKeyRejectsUnknownPeer() = runBlocking {
        assertFalse(repository.acceptKey(PEER_ID, FIRST_KEY))
    }

    @Test
    fun missingPeerHasUnknownTrust() = runBlocking {
        assertEquals(TrustState.UNKNOWN, repository.getTrustState(PEER_ID))
    }

    private object ImmediateTransactions : PeerTransactions {
        override suspend fun <T> run(block: suspend () -> T): T = block()
    }

    private class FakePeerDao : PeerDao {
        val peers = linkedMapOf<String, PeerEntity>()

        override fun observeAll(): Flow<List<PeerEntity>> = flowOf(peers.values.toList())

        override fun observe(peerId: String): Flow<PeerEntity?> = flowOf(peers[peerId])

        override suspend fun find(peerId: String): PeerEntity? = peers[peerId]

        override suspend fun insertIfAbsent(peer: PeerEntity): Long {
            if (peers.containsKey(peer.peerId)) return -1L
            peers[peer.peerId] = peer
            return 1L
        }

        override suspend fun updateObservedKey(
            peerId: String,
            publicKey: ByteArray,
            trustState: TrustState,
            seenAt: Long,
        ) {
            val current = requireNotNull(peers[peerId])
            peers[peerId] = current.copy(
                lastSeenPublicKey = publicKey,
                trustState = trustState,
                verifiedAt = null,
                lastSeenAt = seenAt,
            )
        }

        override suspend fun touch(peerId: String, seenAt: Long) {
            val current = requireNotNull(peers[peerId])
            peers[peerId] = current.copy(lastSeenAt = seenAt)
        }

        override suspend fun updateTrust(
            peerId: String,
            expectedKey: ByteArray,
            trustState: TrustState,
            verifiedAt: Long?,
            seenAt: Long,
        ): Int {
            val current = peers[peerId] ?: return 0
            if (!current.lastSeenPublicKey.contentEquals(expectedKey)) return 0
            peers[peerId] = current.copy(
                trustState = trustState,
                verifiedAt = verifiedAt,
                lastSeenAt = seenAt,
            )
            return 1
        }

        override suspend fun updateNickname(
            peerId: String,
            nickname: String,
            seenAt: Long,
        ): Int {
            val current = peers[peerId] ?: return 0
            peers[peerId] = current.copy(nickname = nickname, lastSeenAt = seenAt)
            return 1
        }
    }

    private companion object {
        val PEER_ID = PeerId.parse("0123456789abcdef")
        val FIRST_KEY = ByteArray(32) { 1 }
        val SECOND_KEY = ByteArray(32) { 2 }
        val THIRD_KEY = ByteArray(32) { 3 }
    }
}
