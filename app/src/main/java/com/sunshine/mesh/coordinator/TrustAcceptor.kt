package com.sunshine.mesh.coordinator

import com.sunshine.mesh.core.ids.PeerId

/** Explicit TOFU acceptance, exposed without leaking the rest of the coordinator. */
interface TrustAcceptor {
    suspend fun acceptTrust(peerId: PeerId, expectedPublicKey: ByteArray): Boolean
}
