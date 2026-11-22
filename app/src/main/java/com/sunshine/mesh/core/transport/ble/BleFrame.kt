package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import java.io.Closeable

/**
 * Transport-level frame. HELLO/HELLO_ACK bind a link to a peerId before any
 * data is accepted; DATA carries one chunk of a 512-byte packet.
 *
 * [Data] is deliberately not a data class: its ByteArray must not participate
 * in equals/hashCode/toString, and [close] wipes it after the assembler copied
 * what it needs.
 */
sealed interface BleFrame : Closeable {

    override fun close() = Unit

    data class Hello(
        val version: Int,
        val peerId: PeerId,
    ) : BleFrame

    data class HelloAck(
        val version: Int,
        val peerId: PeerId,
    ) : BleFrame

    class Data(
        val transferId: Int,
        val chunkIndex: Int,
        val totalChunks: Int,
        val payload: ByteArray,
    ) : BleFrame {

        override fun close() {
            payload.fill(0)
        }

        override fun toString(): String =
            "Data(transferId=$transferId, chunk=${chunkIndex + 1}/$totalChunks, " +
                "payload=${payload.size}B)"
    }

    data class Error(
        val code: TransportError,
    ) : BleFrame
}
