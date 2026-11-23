package com.sunshine.mesh.core.mesh

import com.sunshine.mesh.core.crypto.e2e.EnvelopeCodec
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import java.io.Closeable

/**
 * One mesh packet: 34-byte header + opaque 512-byte E2E envelope = 546 bytes.
 * The mesh layer never decrypts the envelope; [partIndex] is read from the
 * public AAD so deduplication works per fragment, not per message.
 */
class MeshPacket(
    val header: MeshHeader,
    val envelope: ByteArray,
) : Closeable {

    init {
        require(envelope.size == EnvelopeCodec.ENVELOPE_BYTES) {
            "envelope must be ${EnvelopeCodec.ENVELOPE_BYTES} bytes"
        }
    }

    val partIndex: Int
        get() = envelope[EnvelopeCodec.PART_INDEX_OFFSET].toInt() and 0xff

    fun toBytes(): ByteArray = header.encode() + envelope

    override fun close() {
        envelope.fill(0)
    }

    override fun toString(): String =
        "MeshPacket(msgId=${header.msgId}, ttl=${header.ttl}, " +
            "envelope=${envelope.size}B)"

    companion object {
        const val TOTAL_BYTES = MeshHeader.SIZE + EnvelopeCodec.ENVELOPE_BYTES

        fun fromBytes(bytes: ByteArray): MeshPacket {
            if (bytes.size != TOTAL_BYTES) {
                throw TransportException(
                    TransportError.PROTOCOL_ERROR,
                    IllegalArgumentException("mesh packet must be $TOTAL_BYTES bytes"),
                )
            }
            val header = MeshHeader.decode(bytes.copyOfRange(0, MeshHeader.SIZE))
            val envelope = bytes.copyOfRange(MeshHeader.SIZE, TOTAL_BYTES)
            return MeshPacket(header, envelope)
        }
    }
}
