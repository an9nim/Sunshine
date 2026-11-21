package com.sunshine.mesh.core.crypto.e2e

/**
 * Parsed envelope. Arrays are immutable by convention and [toString] exposes
 * only sizes so accidental logs cannot print ciphertext.
 */
class E2EEnvelope(
    val header: EnvelopeHeader,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val tag: ByteArray,
) {
    init {
        require(nonce.size == EnvelopeCodec.NONCE_BYTES)
        require(ciphertext.size == EnvelopeCodec.CIPHERTEXT_BYTES)
        require(tag.size == EnvelopeCodec.TAG_BYTES)
    }

    override fun toString(): String =
        "E2EEnvelope(header=$header, nonce=${nonce.size}B, " +
            "ciphertext=${ciphertext.size}B, tag=${tag.size}B)"
}
