package com.sunshine.mesh.core.crypto.e2e

import java.io.Closeable

/**
 * Two directional AES-256-GCM keys produced by HKDF. The constructor takes
 * ownership of the arrays; [close] wipes them and further use is rejected.
 */
class SessionKeys internal constructor(
    private val outbound: ByteArray,
    private val inbound: ByteArray,
) : Closeable {

    @Volatile
    private var closed = false

    init {
        require(outbound.size == KEY_BYTES) {
            "outbound key must be $KEY_BYTES bytes"
        }
        require(inbound.size == KEY_BYTES) {
            "inbound key must be $KEY_BYTES bytes"
        }
    }

    internal fun <T> useOutbound(block: (ByteArray) -> T): T {
        check(!closed) { "session keys are closed" }
        return block(outbound)
    }

    internal fun <T> useInbound(block: (ByteArray) -> T): T {
        check(!closed) { "session keys are closed" }
        return block(inbound)
    }

    override fun close() {
        if (closed) return
        closed = true
        outbound.fill(0)
        inbound.fill(0)
    }

    override fun toString(): String =
        "SessionKeys(outbound=$KEY_BYTES bytes, inbound=$KEY_BYTES bytes, material=redacted)"

    private companion object {
        const val KEY_BYTES = 32
    }
}
