package com.sunshine.mesh.domain.model

/**
 * Result of the trust gate before a session may be created. The safety number
 * is precomputed so the UI cannot show a dialog without a fingerprint.
 */
sealed interface TrustDecision {
    class Allowed(val peerPublicKey: ByteArray) : TrustDecision

    class TrustRequired(val safetyNumber: String) : TrustDecision

    class KeyChanged(val safetyNumber: String) : TrustDecision
}
