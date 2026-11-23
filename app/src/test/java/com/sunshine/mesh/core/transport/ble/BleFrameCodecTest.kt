package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BleFrameCodecTest {

    private val peerId = PeerId.parse("0011223344556677")
    private val otherPeerId = PeerId.parse("8899aabbccddeeff")

    @Test
    fun payloadBytesAtMtu23() {
        assertEquals(16, BleFrameCodec.dataPayloadBytes(23))
    }

    @Test
    fun payloadBytesAtMtu247() {
        assertEquals(240, BleFrameCodec.dataPayloadBytes(247))
    }

    @Test
    fun payloadBytesAtMtu517IsCappedByAttributeSize() {
        assertEquals(508, BleFrameCodec.dataPayloadBytes(517))
    }

    @Test
    fun chunkCountAtMtu23() {
        assertEquals(32, BleFrameCodec.chunkCount(512, 23))
        assertEquals(1, BleFrameCodec.chunkCount(16, 23))
        assertEquals(2, BleFrameCodec.chunkCount(17, 23))
    }

    @Test
    fun chunkCountAtMtu247() {
        assertEquals(3, BleFrameCodec.chunkCount(512, 247))
        assertEquals(1, BleFrameCodec.chunkCount(240, 247))
    }

    @Test
    fun chunkCountAtMtu517() {
        assertEquals(2, BleFrameCodec.chunkCount(512, 517))
        assertEquals(1, BleFrameCodec.chunkCount(508, 517))
    }

    @Test
    fun singleBytePacketRoundTrips() {
        val packet = byteArrayOf(0x42)

        val chunks = BleFrameCodec.encodeData(packet, 23, transferId = 0)

        assertEquals(1, chunks.size)
        assertEquals(5, chunks.single().size)
        assertArrayEquals(packet, assemble(chunks))
    }

    @Test
    fun fullPacketRoundTripsAtMtu23() {
        val packet = ByteArray(512) { index -> (index * 7).toByte() }

        val chunks = BleFrameCodec.encodeData(packet, 23, transferId = 7)

        assertEquals(32, chunks.size)
        assertTrue(chunks.all { it.size == BleProtocol.FRAME_HEADER_BYTES + 16 })
        assertArrayEquals(packet, assemble(chunks))
    }

    @Test
    fun fullPacketRoundTripsAtLargeMtu() {
        val packet = ByteArray(512) { index -> (index + 1).toByte() }

        val chunks = BleFrameCodec.encodeData(packet, 517, transferId = 9)

        assertEquals(2, chunks.size)
        assertEquals(512, chunks[0].size)
        assertEquals(8, chunks[1].size)
        assertArrayEquals(packet, assemble(chunks))
    }

    @Test
    fun chunksNeverExceedMaxAttributeSize() {
        val packet = ByteArray(512) { 0x5a }

        for (mtu in listOf(23, 247, 517)) {
            val chunks = BleFrameCodec.encodeData(packet, mtu, transferId = 1)
            assertTrue(chunks.all { it.isNotEmpty() })
            assertTrue(chunks.all { it.size <= BleProtocol.MAX_ATTRIBUTE_BYTES })
        }
    }

    @Test
    fun helloRoundTrips() {
        val decoded = BleFrameCodec.decode(BleFrameCodec.encodeHello(peerId))

        assertEquals(BleFrame.Hello(BleProtocol.VERSION, peerId), decoded)
        assertEquals(10, BleFrameCodec.encodeHello(peerId).size)
    }

    @Test
    fun helloAckRoundTrips() {
        val decoded = BleFrameCodec.decode(
            BleFrameCodec.encodeHelloAck(otherPeerId, version = 3),
        )

        assertEquals(BleFrame.HelloAck(version = 3, peerId = otherPeerId), decoded)
    }

    @Test
    fun everyErrorCodeRoundTrips() {
        for (code in TransportError.values()) {
            val decoded = BleFrameCodec.decode(BleFrameCodec.encodeError(code))

            assertEquals(BleFrame.Error(code), decoded)
        }
    }

    @Test
    fun decodeCopiesPayload() {
        val chunk = BleFrameCodec.encodeData(ByteArray(32) { 0x11 }, 247, transferId = 2)
            .single()

        val frame = BleFrameCodec.decode(chunk) as BleFrame.Data
        val expected = frame.payload.copyOf()
        chunk.fill(0)

        assertArrayEquals(expected, frame.payload)
    }

    @Test
    fun rejectsEmptyAndOversizedPackets() {
        assertProtocolError {
            BleFrameCodec.encodeData(ByteArray(0), 23, transferId = 0)
        }
        val tooLarge = assertThrows(TransportException::class.java) {
            BleFrameCodec.encodeData(ByteArray(BleProtocol.MAX_PACKET_BYTES + 1), 23, transferId = 0)
        }
        assertEquals(TransportError.PACKET_TOO_LARGE, tooLarge.code)
    }

    @Test
    fun rejectsInvalidMtuAndTransferId() {
        assertThrows(IllegalArgumentException::class.java) {
            BleFrameCodec.encodeData(byteArrayOf(1), 22, transferId = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BleFrameCodec.encodeData(byteArrayOf(1), 23, transferId = 256)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BleFrameCodec.dataPayloadBytes(22)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BleFrameCodec.chunkCount(0, 23)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BleFrameCodec.chunkCount(BleProtocol.MAX_PACKET_BYTES + 1, 23)
        }
    }

    @Test
    fun rejectsBrokenFrames() {
        assertProtocolError { BleFrameCodec.decode(ByteArray(0)) }
        assertProtocolError { BleFrameCodec.decode(ByteArray(513)) }
        assertProtocolError { BleFrameCodec.decode(byteArrayOf(0x7f)) }
        assertProtocolError {
            BleFrameCodec.decode(ByteArray(9).also { it[0] = BleProtocol.FRAME_HELLO.toByte() })
        }
        assertProtocolError {
            BleFrameCodec.decode(ByteArray(11).also {
                it[0] = BleProtocol.FRAME_HELLO_ACK.toByte()
            })
        }
    }

    @Test
    fun rejectsInvalidDataHeaders() {
        assertProtocolError {
            BleFrameCodec.decode(byteArrayOf(BleProtocol.FRAME_DATA.toByte(), 0, 0))
        }
        assertProtocolError {
            BleFrameCodec.decode(
                byteArrayOf(BleProtocol.FRAME_DATA.toByte(), 0, 0, 0, 1),
            )
        }
        assertProtocolError {
            BleFrameCodec.decode(
                byteArrayOf(BleProtocol.FRAME_DATA.toByte(), 0, 1, 1, 1),
            )
        }
        assertProtocolError {
            BleFrameCodec.decode(
                byteArrayOf(BleProtocol.FRAME_DATA.toByte(), 0, 0, 1),
            )
        }
    }

    @Test
    fun rejectsInvalidErrorFrames() {
        assertProtocolError {
            BleFrameCodec.decode(byteArrayOf(BleProtocol.FRAME_ERROR.toByte()))
        }
        assertProtocolError {
            BleFrameCodec.decode(
                byteArrayOf(BleProtocol.FRAME_ERROR.toByte(), 0, 0),
            )
        }
        assertProtocolError {
            BleFrameCodec.decode(
                byteArrayOf(BleProtocol.FRAME_ERROR.toByte(), 0x7f),
            )
        }
    }

    private fun assemble(chunks: List<ByteArray>): ByteArray {
        val assembler = BlePacketAssembler()
        var packet: ByteArray? = null
        for (chunk in chunks) {
            val frame = BleFrameCodec.decode(chunk) as BleFrame.Data
            try {
                packet = assembler.accept(frame) ?: packet
            } finally {
                frame.close()
            }
        }
        return requireNotNull(packet)
    }

    private fun assertProtocolError(block: () -> Unit) {
        val error = assertThrows(TransportException::class.java) { block() }
        assertEquals(TransportError.PROTOCOL_ERROR, error.code)
    }
}
