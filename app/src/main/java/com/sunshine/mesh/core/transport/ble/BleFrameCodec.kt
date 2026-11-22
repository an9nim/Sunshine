package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException

/**
 * Encodes and decodes BLE transport frames.
 *
 * DATA wire layout:
 * type(1) | transferId(1) | chunkIndex(1) | totalChunks(1) | payload.
 *
 * The codec never logs or prints payload bytes.
 */
object BleFrameCodec {

    fun encodeHello(
        peerId: PeerId,
        version: Int = BleProtocol.VERSION,
    ): ByteArray = encodePeerFrame(BleProtocol.FRAME_HELLO, peerId, version)

    fun encodeHelloAck(
        peerId: PeerId,
        version: Int = BleProtocol.VERSION,
    ): ByteArray = encodePeerFrame(BleProtocol.FRAME_HELLO_ACK, peerId, version)

    fun encodeError(code: TransportError): ByteArray =
        byteArrayOf(
            BleProtocol.FRAME_ERROR.toByte(),
            code.ordinal.toByte(),
        )

    fun encodeData(
        packet: ByteArray,
        mtu: Int,
        transferId: Int,
    ): List<ByteArray> {
        if (packet.isEmpty()) {
            throw protocolError("packet must not be empty")
        }
        if (packet.size > BleProtocol.MAX_PACKET_BYTES) {
            throw TransportException(TransportError.PACKET_TOO_LARGE)
        }
        require(mtu >= BleProtocol.MIN_MTU) {
            "mtu must be at least ${BleProtocol.MIN_MTU}"
        }
        require(transferId in 0..MAX_BYTE) {
            "transferId must be between 0 and $MAX_BYTE"
        }

        val payloadBytes = dataPayloadBytes(mtu)
        val totalChunks = chunkCount(packet.size, mtu)
        return buildList(totalChunks) {
            for (index in 0 until totalChunks) {
                val start = index * payloadBytes
                val end = minOf(start + payloadBytes, packet.size)
                val chunk = ByteArray(BleProtocol.FRAME_HEADER_BYTES + (end - start))
                chunk[0] = BleProtocol.FRAME_DATA.toByte()
                chunk[1] = transferId.toByte()
                chunk[2] = index.toByte()
                chunk[3] = totalChunks.toByte()
                packet.copyInto(
                    destination = chunk,
                    destinationOffset = BleProtocol.FRAME_HEADER_BYTES,
                    startIndex = start,
                    endIndex = end,
                )
                add(chunk)
            }
        }
    }

    fun decode(frame: ByteArray): BleFrame {
        if (frame.isEmpty() || frame.size > BleProtocol.MAX_ATTRIBUTE_BYTES) {
            throw protocolError(
                "frame size must be between 1 and ${BleProtocol.MAX_ATTRIBUTE_BYTES}",
            )
        }

        return when (frame[0].toInt() and MAX_BYTE) {
            BleProtocol.FRAME_HELLO -> decodePeerFrame(frame, ack = false)
            BleProtocol.FRAME_HELLO_ACK -> decodePeerFrame(frame, ack = true)
            BleProtocol.FRAME_DATA -> decodeData(frame)
            BleProtocol.FRAME_ERROR -> decodeError(frame)
            else -> throw protocolError("unknown frame type")
        }
    }

    fun dataPayloadBytes(mtu: Int): Int {
        require(mtu >= BleProtocol.MIN_MTU) {
            "mtu must be at least ${BleProtocol.MIN_MTU}"
        }
        return minOf(
            mtu - BleProtocol.ATT_HEADER_BYTES - BleProtocol.FRAME_HEADER_BYTES,
            BleProtocol.MAX_DATA_PAYLOAD_BYTES,
        )
    }

    fun chunkCount(packetBytes: Int, mtu: Int): Int {
        require(packetBytes in 1..BleProtocol.MAX_PACKET_BYTES) {
            "packetBytes must be between 1 and ${BleProtocol.MAX_PACKET_BYTES}"
        }
        val payloadBytes = dataPayloadBytes(mtu)
        return (packetBytes + payloadBytes - 1) / payloadBytes
    }

    private fun encodePeerFrame(
        type: Int,
        peerId: PeerId,
        version: Int,
    ): ByteArray {
        require(version in 0..MAX_BYTE) {
            "version must be between 0 and $MAX_BYTE"
        }
        val peerBytes = peerId.toByteArray()
        return ByteArray(PEER_FRAME_BYTES).also { frame ->
            frame[0] = type.toByte()
            frame[1] = version.toByte()
            peerBytes.copyInto(frame, destinationOffset = 2)
        }
    }

    private fun decodePeerFrame(frame: ByteArray, ack: Boolean): BleFrame {
        if (frame.size != PEER_FRAME_BYTES) {
            throw protocolError("peer frame must be $PEER_FRAME_BYTES bytes")
        }
        val version = frame[1].toInt() and MAX_BYTE
        val peerId = PeerId.fromBytes(frame.copyOfRange(2, PEER_FRAME_BYTES))
        return if (ack) {
            BleFrame.HelloAck(version = version, peerId = peerId)
        } else {
            BleFrame.Hello(version = version, peerId = peerId)
        }
    }

    private fun decodeData(frame: ByteArray): BleFrame {
        if (frame.size < BleProtocol.FRAME_HEADER_BYTES) {
            throw protocolError("data frame is too short")
        }

        val transferId = frame[1].toInt() and MAX_BYTE
        val chunkIndex = frame[2].toInt() and MAX_BYTE
        val totalChunks = frame[3].toInt() and MAX_BYTE
        if (totalChunks !in 1..BleProtocol.MAX_CHUNKS) {
            throw protocolError("totalChunks is out of range")
        }
        if (chunkIndex >= totalChunks) {
            throw protocolError("chunkIndex is outside totalChunks")
        }

        val payload = frame.copyOfRange(BleProtocol.FRAME_HEADER_BYTES, frame.size)
        if (payload.isEmpty() || payload.size > BleProtocol.MAX_DATA_PAYLOAD_BYTES) {
            payload.fill(0)
            throw protocolError("data payload size is out of range")
        }

        return BleFrame.Data(
            transferId = transferId,
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            payload = payload,
        )
    }

    private fun decodeError(frame: ByteArray): BleFrame {
        if (frame.size != ERROR_FRAME_BYTES) {
            throw protocolError("error frame must be $ERROR_FRAME_BYTES bytes")
        }
        val code = TransportError.values().getOrNull(frame[1].toInt() and MAX_BYTE)
            ?: throw protocolError("unknown transport error code")
        return BleFrame.Error(code)
    }

    private fun protocolError(message: String): TransportException =
        TransportException(TransportError.PROTOCOL_ERROR, IllegalArgumentException(message))

    private const val MAX_BYTE = 0xff
    private const val PEER_FRAME_BYTES = 10
    private const val ERROR_FRAME_BYTES = 2
}
