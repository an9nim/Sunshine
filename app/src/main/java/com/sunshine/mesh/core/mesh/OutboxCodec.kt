package com.sunshine.mesh.core.mesh

import com.sunshine.mesh.core.crypto.e2e.EnvelopeCodec

/**
 * Outbox representation for MessageEntity.packet: fixed 546-byte mesh packets
 * concatenated in fragment order. The format is deliberately simple because it
 * is internal to one app version and is rewritten on every retry.
 */
object OutboxCodec {

    private const val PACKET_BYTES = MeshPacket.TOTAL_BYTES
    private const val MAX_PACKETS = EnvelopeCodec.MAX_PARTS

    fun encode(packets: List<ByteArray>): ByteArray {
        require(packets.isNotEmpty()) { "outbox must contain at least one packet" }
        require(packets.size <= MAX_PACKETS) {
            "outbox holds ${packets.size} packets, max is $MAX_PACKETS"
        }
        packets.forEach { packet ->
            require(packet.size == PACKET_BYTES) {
                "outbox packet must be $PACKET_BYTES bytes"
            }
        }

        val encoded = ByteArray(packets.size * PACKET_BYTES)
        var offset = 0
        for (packet in packets) {
            packet.copyInto(encoded, offset)
            offset += PACKET_BYTES
        }
        return encoded
    }

    fun decode(encoded: ByteArray): List<ByteArray> {
        require(encoded.isNotEmpty()) { "outbox must not be empty" }
        require(encoded.size % PACKET_BYTES == 0) {
            "outbox size must be a multiple of $PACKET_BYTES"
        }
        val count = encoded.size / PACKET_BYTES
        require(count <= MAX_PACKETS) {
            "outbox holds $count packets, max is $MAX_PACKETS"
        }

        return List(count) { index ->
            val start = index * PACKET_BYTES
            encoded.copyOfRange(start, start + PACKET_BYTES)
        }
    }
}
