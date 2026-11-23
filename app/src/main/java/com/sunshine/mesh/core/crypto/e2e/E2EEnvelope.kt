package com.sunshine.mesh.core.crypto.e2e

import java.io.Closeable

/**
 * Parsed envelope. Arrays are immutable by convention and [toString] exposes
 * only sizes so accidental logs cannot print ciphertext. The owner must close
 * the envelope after decryption to wipe the copied payload.
 */
class E2EEnvelope(
    val header: EnvelopeHeader,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val tag: ByteArray,
) : Closeable {

    @Volatile
    private var closed = false

    init {
        require(nonce.size == EnvelopeCodec.NONCE_BYTES)
        require(ciphertext.size == EnvelopeCodec.CIPHERTEXT_BYTES)
        require(tag.size == EnvelopeCodec.TAG_BYTES)
    }

    override fun close() {
        if (closed) return
        closed = true
        nonce.fill(0)
        ciphertext.fill(0)
        tag.fill(0)
    }

    override fun toString(): String =
        "E2EEnvelope(header=$header, nonce=${nonce.size}B, " +
            "ciphertext=${ciphertext.size}B, tag=${tag.size}B)"
}
