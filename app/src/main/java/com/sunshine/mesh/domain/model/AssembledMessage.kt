package com.sunshine.mesh.domain.model

import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import java.io.Closeable

/**
 * Reassembled message bytes. UTF-8 decoding is intentionally left to the
 * caller: a fragmented character must never be decoded fragment by fragment.
 * The owner must close the message to wipe the plaintext.
 */
class AssembledMessage(
    val messageId: MessageId,
    val senderId: PeerId,
    val recipientId: PeerId,
    val plaintext: ByteArray,
) : Closeable {

    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        plaintext.fill(0)
    }

    override fun toString(): String =
        "AssembledMessage(msgId=$messageId, senderId=$senderId, " +
            "recipientId=$recipientId, plaintext=${plaintext.size}B)"
}
