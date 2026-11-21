package com.sunshine.mesh.data.peer

import com.sunshine.mesh.core.crypto.SafetyNumber
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.domain.model.TrustDecision
import com.sunshine.mesh.domain.model.TrustResult
import com.sunshine.mesh.domain.model.TrustState

/**
 * Decides whether a session may be opened. SAME alone is not enough: after a
 * NEW key the state is still UNKNOWN, and after a CHANGED key it is
 * KEY_CHANGED. Letting those through would bypass the first-contact dialog and
 * the MITM warning with a repeated packet.
 *
 * E2ESession stays unaware of trust: this gate runs before session derivation.
 */
class PeerTrustGate(
    private val peers: PeerTrustStore,
    private val safetyNumber: (ByteArray, ByteArray) -> String = SafetyNumber::of,
) {

    suspend fun evaluate(
        peerId: PeerId,
        ownPublicKey: ByteArray,
        peerPublicKey: ByteArray,
    ): TrustDecision {
        when (peers.checkAndUpdateKey(peerId, peerPublicKey)) {
            TrustResult.NEW -> return trustRequired(ownPublicKey, peerPublicKey)
            TrustResult.CHANGED -> return keyChanged(ownPublicKey, peerPublicKey)
            TrustResult.SAME -> Unit
        }

        return when (peers.getTrustState(peerId)) {
            TrustState.TOFU_ACCEPTED,
            TrustState.VERIFIED,
            -> TrustDecision.Allowed(peerPublicKey.copyOf())

            TrustState.UNKNOWN ->
                trustRequired(ownPublicKey, peerPublicKey)

            TrustState.KEY_CHANGED ->
                keyChanged(ownPublicKey, peerPublicKey)
        }
    }

    private fun trustRequired(
        ownPublicKey: ByteArray,
        peerPublicKey: ByteArray,
    ): TrustDecision.TrustRequired = TrustDecision.TrustRequired(
        safetyNumber = safetyNumber(ownPublicKey, peerPublicKey),
    )

    private fun keyChanged(
        ownPublicKey: ByteArray,
        peerPublicKey: ByteArray,
    ): TrustDecision.KeyChanged = TrustDecision.KeyChanged(
        safetyNumber = safetyNumber(ownPublicKey, peerPublicKey),
    )
}
