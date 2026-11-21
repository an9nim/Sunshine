package com.sunshine.mesh.core.crypto.e2e

import java.io.Closeable

/**
 * Plaintext buffer padded to the fixed ciphertext size. It contains message
 * bytes before encryption, so it is Closeable and never printed.
 */
class PaddedPayload internal constructor(
    val bytes: ByteArray,
    val paddingLength: Int,
) : Closeable {

    @Volatile
    private var closed = false

    init {
        require(bytes.size == EnvelopeCodec.CIPHERTEXT_BYTES)
        require(paddingLength in 0..EnvelopeCodec.MAX_PADDING_BYTES)
    }

    override fun close() {
        if (closed) return
        closed = true
        bytes.fill(0)
    }

    override fun toString(): String =
        "PaddedPayload(bytes=${bytes.size}, paddingLength=$paddingLength)"
}
