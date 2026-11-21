package com.sunshine.mesh.domain.model

/** Outcome of comparing an observed peer key with the last one on record. */
enum class TrustResult {
    NEW,
    SAME,
    CHANGED,
}
