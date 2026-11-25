package com.sunshine.mesh.data.peer

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.domain.model.Peer

/**
 * Trust operations the profile screen performs on a stored peer. Keys are read
 * at action time so the user always acts on the newest observed key.
 */
interface PeerTrustActions : PeerDataSource {
    suspend fun getPeer(peerId: PeerId): Peer?

    suspend fun verifyManually(peerId: PeerId, expectedPublicKey: ByteArray): Boolean
}
