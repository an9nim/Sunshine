package com.sunshine.mesh.core.net

object MeshPolicy {
    const val BLE_CONNECT_TIMEOUT_MS = 10_000L

    /** Hop-count TTL: every relay decrements it; zero means drop. */
    const val DEFAULT_TTL = 8
    const val SEEN_CAPACITY = 4096
    const val STORE_TTL_MS = 72L * 60 * 60 * 1000
    const val RETRY_INTERVAL_MS = 60_000L
    const val RETRY_BATCH = 32
}
