package com.sunshine.mesh.core.mesh

import com.sunshine.mesh.core.crypto.e2e.EnvelopeCodec
import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MeshHeaderTest {

    private val msgId = MessageId.parse("00112233445566778899aabbccddeeff")
    private val sender = PeerId.parse("0000000000000001")
    private val recipient = PeerId.parse("ffffffffffffffff")

    @Test
    fun encodeDecodeRoundTrip() {
        val header = MeshHeader(MeshHeader.VERSION, ttl = 8, msgId, sender, recipient)

        val decoded = MeshHeader.decode(header.encode())

        assertEquals(header, decoded)
        assertEquals(34, header.encode().size)
    }

    @Test
    fun ttlSurvivesTheFullByteRange() {
        for (ttl in listOf(0, 1, 8, 255)) {
            val header = MeshHeader(MeshHeader.VERSION, ttl, msgId, sender, recipient)
            assertEquals(ttl, MeshHeader.decode(header.encode()).ttl)
        }
    }

    @Test
    fun decodeRejectsWrongSizeAndVersion() {
        assertProtocolError { MeshHeader.decode(ByteArray(33)) }
        assertProtocolError { MeshHeader.decode(ByteArray(35)) }
        val wrongVersion = MeshHeader(MeshHeader.VERSION, 8, msgId, sender, recipient).encode()
        wrongVersion[0] = (MeshHeader.VERSION + 1).toByte()
        assertProtocolError { MeshHeader.decode(wrongVersion) }
    }

    @Test
    fun meshPacketIs546BytesAndReadsPartIndexFromAad() {
        val envelope = ByteArray(EnvelopeCodec.ENVELOPE_BYTES)
        envelope[EnvelopeCodec.PART_INDEX_OFFSET] = 3
        val header = MeshHeader(MeshHeader.VERSION, 8, msgId, sender, recipient)

        val packet = MeshPacket.fromBytes(header.encode() + envelope)

        assertEquals(546, MeshPacket.TOTAL_BYTES)
        assertEquals(header, packet.header)
        assertEquals(3, packet.partIndex)
        assertArrayEquals(envelope, packet.envelope)
    }

    @Test
    fun meshPacketRejectsWrongTotalSize() {
        assertProtocolError { MeshPacket.fromBytes(ByteArray(545)) }
        assertProtocolError { MeshPacket.fromBytes(ByteArray(547)) }
    }

    @Test
    fun closeWipesTheEnvelopeAndToStringHidesIt() {
        val envelope = ByteArray(EnvelopeCodec.ENVELOPE_BYTES) { 0x5a }
        val packet = MeshPacket(
            MeshHeader(MeshHeader.VERSION, 8, msgId, sender, recipient),
            envelope,
        )

        val text = packet.toString()
        packet.close()
        packet.close()

        assertTrue(envelope.all { it == 0.toByte() })
        assertTrue(text.contains(msgId.value))
        assertTrue(text.contains("512B"))
    }

    private fun assertProtocolError(block: () -> Unit) {
        try {
            block()
            fail("expected TransportException(PROTOCOL_ERROR)")
        } catch (error: TransportException) {
            assertEquals(TransportError.PROTOCOL_ERROR, error.code)
        }
    }
}
