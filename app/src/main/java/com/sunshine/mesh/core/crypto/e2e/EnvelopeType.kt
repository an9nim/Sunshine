package com.sunshine.mesh.core.crypto.e2e

/**
 * Payload kind carried by an envelope. Unknown codes are rejected rather than
 * treated as text, so a new sender cannot silently downgrade the protocol.
 */
enum class EnvelopeType(val code: Byte) {
    TEXT(1),

    /**
     * Public-key exchange. The payload is unencrypted but still uses the fixed
     * envelope layout; trust is decided by PeerTrustGate before any session.
     */
    KEY_EXCHANGE(2),

    /** Encrypted delivery receipt carrying the acknowledged message id. */
    RECEIPT(3),
    ;

    companion object {
        fun fromCode(code: Byte): EnvelopeType =
            entries.firstOrNull { it.code == code }
                ?: throw E2EException(E2EException.Code.UNKNOWN_TYPE)
    }
}
