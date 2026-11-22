package com.sunshine.mesh.core.transport

import com.sunshine.mesh.core.ids.PeerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportTest {

    private val peerId = PeerId.parse("0011223344556677")

    @Test
    fun inboundPacketCloseWipesPacket() {
        val packet = ByteArray(512) { 0x7f }
        val inbound = InboundPacket(peerId, packet)

        inbound.close()

        assertTrue(packet.all { it == 0.toByte() })
    }

    @Test
    fun inboundPacketCloseIsIdempotent() {
        val packet = ByteArray(512) { 0x7f }
        val inbound = InboundPacket(peerId, packet)

        inbound.close()
        packet[0] = 1
        inbound.close()

        assertTrue(packet.all { it == 0.toByte() })
    }

    @Test
    fun inboundPacketToStringDoesNotExposePayload() {
        val packet = ByteArray(512) { 0x41 }

        val text = InboundPacket(peerId, packet).toString()

        assertTrue(text.contains("512B"))
        assertFalse(text.contains("41"))
    }

    @Test
    fun transportExceptionMessageIsTheErrorCode() {
        val error = TransportException(TransportError.HANDSHAKE_TIMEOUT)

        assertEquals("HANDSHAKE_TIMEOUT", error.message)
        assertNull(error.cause)
    }

    @Test
    fun runningStateCarriesPeerCount() {
        val state = TransportState.Running(connectedPeers = 3)

        assertEquals(3, state.connectedPeers)
    }
}
