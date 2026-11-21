package com.sunshine.mesh.data.peer

import com.sunshine.mesh.core.crypto.SafetyNumber
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.domain.model.TrustDecision
import com.sunshine.mesh.domain.model.TrustResult
import com.sunshine.mesh.domain.model.TrustState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerTrustGateTest {

    @Test
    fun newPeerRequiresTrust() = runBlocking {
        val decision = gate(TrustResult.NEW, TrustState.UNKNOWN).evaluate(
            PEER_ID,
            OWN_KEY,
            PEER_KEY,
        )

        assertTrue(decision is TrustDecision.TrustRequired)
        assertEquals(
            SafetyNumber.of(OWN_KEY, PEER_KEY),
            (decision as TrustDecision.TrustRequired).safetyNumber,
        )
    }

    @Test
    fun changedKeyWarnsAboutMitm() = runBlocking {
        val decision = gate(TrustResult.CHANGED, TrustState.KEY_CHANGED).evaluate(
            PEER_ID,
            OWN_KEY,
            PEER_KEY,
        )

        assertTrue(decision is TrustDecision.KeyChanged)
        assertEquals(
            SafetyNumber.of(OWN_KEY, PEER_KEY),
            (decision as TrustDecision.KeyChanged).safetyNumber,
        )
    }

    @Test
    fun sameUnknownStillRequiresTrust() = runBlocking {
        val decision = gate(TrustResult.SAME, TrustState.UNKNOWN).evaluate(
            PEER_ID,
            OWN_KEY,
            PEER_KEY,
        )

        assertTrue(decision is TrustDecision.TrustRequired)
    }

    @Test
    fun sameKeyChangedStillWarns() = runBlocking {
        val decision = gate(TrustResult.SAME, TrustState.KEY_CHANGED).evaluate(
            PEER_ID,
            OWN_KEY,
            PEER_KEY,
        )

        assertTrue(decision is TrustDecision.KeyChanged)
    }

    @Test
    fun sameTofuAcceptedAllowsSession() = runBlocking {
        val decision = gate(TrustResult.SAME, TrustState.TOFU_ACCEPTED).evaluate(
            PEER_ID,
            OWN_KEY,
            PEER_KEY,
        )

        assertTrue(decision is TrustDecision.Allowed)
        val allowed = decision as TrustDecision.Allowed
        assertArrayEquals(PEER_KEY, allowed.peerPublicKey)
        assertNotSame(PEER_KEY, allowed.peerPublicKey)
    }

    @Test
    fun sameVerifiedAllowsSession() = runBlocking {
        val decision = gate(TrustResult.SAME, TrustState.VERIFIED).evaluate(
            PEER_ID,
            OWN_KEY,
            PEER_KEY,
        )

        assertTrue(decision is TrustDecision.Allowed)
    }

    @Test
    fun gatePassesPeerIdAndKeyToStorage() = runBlocking {
        val store = FakePeerStore(TrustResult.SAME, TrustState.VERIFIED)
        val gate = PeerTrustGate(store)

        gate.evaluate(PEER_ID, OWN_KEY, PEER_KEY)

        assertEquals(PEER_ID, store.checkedPeerId)
        assertArrayEquals(PEER_KEY, requireNotNull(store.checkedKey))
    }

    private fun gate(result: TrustResult, state: TrustState): PeerTrustGate =
        PeerTrustGate(FakePeerStore(result, state))

    private class FakePeerStore(
        private val result: TrustResult,
        private val state: TrustState,
    ) : PeerTrustStore {
        var checkedPeerId: PeerId? = null
        var checkedKey: ByteArray? = null

        override suspend fun checkAndUpdateKey(
            peerId: PeerId,
            newPublicKey: ByteArray,
        ): TrustResult {
            checkedPeerId = peerId
            checkedKey = newPublicKey.copyOf()
            return result
        }

        override suspend fun getTrustState(peerId: PeerId): TrustState = state
    }

    private companion object {
        val PEER_ID = PeerId.parse("0123456789abcdef")
        val OWN_KEY = ByteArray(32) { (it + 1).toByte() }
        val PEER_KEY = ByteArray(32) { (it + 0x40).toByte() }
    }
}
