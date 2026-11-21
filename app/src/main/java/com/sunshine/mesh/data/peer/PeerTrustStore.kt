package com.sunshine.mesh.data.peer

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.domain.model.TrustResult
import com.sunshine.mesh.domain.model.TrustState

/**
 * The narrow part of peer storage needed by the trust gate. Keeping the gate on
 * this interface prevents it from reaching into Room and makes the state
 * machine testable without an Android device.
 */
interface PeerTrustStore {
    suspend fun checkAndUpdateKey(peerId: PeerId, newPublicKey: ByteArray): TrustResult

    suspend fun getTrustState(peerId: PeerId): TrustState
}
