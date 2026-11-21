package com.sunshine.mesh.core.crypto.e2e

/**
 * Payload kind carried by an envelope. Unknown codes are rejected rather than
 * treated as text, so a new sender cannot silently downgrade the protocol.
 */
enum class EnvelopeType(val code: Byte) {
    TEXT(1),
    ;

    companion object {
        fun fromCode(code: Byte): EnvelopeType =
            entries.firstOrNull { it.code == code }
                ?: throw E2EException(E2EException.Code.UNKNOWN_TYPE)
    }
}
