package com.sunshine.mesh.domain.model

/** Local trust in a peer identity key. Stored by name in Room. */
enum class TrustState {
    UNKNOWN,
    TOFU_ACCEPTED,
    VERIFIED,
    KEY_CHANGED,
}
