package com.sunshine.mesh.domain.model

import com.sunshine.mesh.core.crypto.e2e.EnvelopeCodec
import com.sunshine.mesh.core.crypto.e2e.EnvelopeHeader
import java.io.Closeable

/**
 * One authenticated and decrypted envelope fragment. The caller owns
 * [plaintext] and must close this object after passing it to the assembler,
 * which copies the bytes it needs.
 */
class OpenedFragment(
    val header: EnvelopeHeader,
    val plaintext: ByteArray,
) : Closeable {

    @Volatile
    private var closed = false

    init {
        require(plaintext.size <= EnvelopeCodec.MAX_PLAINTEXT_BYTES) {
            "fragment plaintext exceeds ${EnvelopeCodec.MAX_PLAINTEXT_BYTES} bytes"
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        plaintext.fill(0)
    }

    override fun toString(): String =
        "OpenedFragment(msgId=${header.messageId}, part=${header.partIndex + 1}/" +
            "${header.totalParts}, plaintext=${plaintext.size}B)"
}
