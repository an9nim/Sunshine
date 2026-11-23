package com.sunshine.mesh.data.peer

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.domain.model.Peer

/**
 * The part of peer storage the coordinator needs: the newest observed key and
 * the explicit TOFU acceptance operation. Key changes are surfaced by
 * PeerTrustGate and are not accepted by Stage 5.
 */
interface PeerKeyStore {
    suspend fun getPeer(peerId: PeerId): Peer?

    suspend fun acceptKey(peerId: PeerId, expectedPublicKey: ByteArray): Boolean
}
