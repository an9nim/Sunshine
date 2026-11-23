package com.sunshine.mesh.core.transport.wifidirect

import com.sunshine.mesh.core.ids.PeerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class WifiDirectSocketLinkTest {

    private val localPeerId = PeerId.parse("0000000000000001")

    @Test
    fun handshakeAndFullDuplexFramingOverLoopback() = runBlocking {
        val server = ServerSocket(0)
        var client: WifiDirectSocketLink? = null
        var accepted: AcceptedWifiDirectLink? = null
        try {
            val acceptJob = async(Dispatchers.IO) {
                acceptWifiDirectLink(server, this@runBlocking)
            }
            client = connectWifiDirectLink(
                InetAddress.getLoopbackAddress(),
                server.localPort,
                localPeerId,
                this@runBlocking,
            )
            accepted = acceptJob.await()
            assertEquals(localPeerId, accepted.peerId)

            val packet = ByteArray(WifiDirectProtocol.MAX_FRAME_BYTES) { it.toByte() }
            client.send(packet)
            val received = withTimeout(2_000) { accepted.link.incoming.first() }
            assertArrayEquals(packet, received)

            accepted.link.send(packet)
            val echo = withTimeout(2_000) { client.incoming.first() }
            assertArrayEquals(packet, echo)
        } finally {
            client?.close()
            accepted?.link?.close()
            server.close()
        }
    }

    @Test
    fun framingRoundTripsExactBytes() {
        withSocketPair { input, output ->
            val payload = ByteArray(546) { (it % 251).toByte() }
            WifiDirectFraming.writeFrame(output, payload)
            output.flush()

            val frame = WifiDirectFraming.readFrame(input, WifiDirectProtocol.MAX_FRAME_BYTES)

            assertArrayEquals(payload, frame)
        }
    }

    @Test
    fun readFrameReturnsNullOnCleanEof() {
        withSocketPair { input, output ->
            output.close()
            assertNull(WifiDirectFraming.readFrame(input, WifiDirectProtocol.MAX_FRAME_BYTES))
        }
    }

    @Test
    fun readFrameRejectsOutOfRangeLength() {
        withSocketPair { input, output ->
            output.write(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
            output.flush()
            try {
                WifiDirectFraming.readFrame(input, WifiDirectProtocol.MAX_FRAME_BYTES)
                fail("expected IOException")
            } catch (expected: IOException) {
                assertTrue(expected.message?.contains("length") == true)
            }
        }
    }

    @Test
    fun oversizedSendIsRejected() = runBlocking {
        val server = ServerSocket(0)
        var client: WifiDirectSocketLink? = null
        var accepted: AcceptedWifiDirectLink? = null
        try {
            val acceptJob = async(Dispatchers.IO) {
                acceptWifiDirectLink(server, this@runBlocking)
            }
            client = connectWifiDirectLink(
                InetAddress.getLoopbackAddress(),
                server.localPort,
                localPeerId,
                this@runBlocking,
            )
            accepted = acceptJob.await()

            try {
                client.send(ByteArray(WifiDirectProtocol.MAX_FRAME_BYTES + 1))
                fail("expected PACKET_TOO_LARGE")
            } catch (expected: com.sunshine.mesh.core.transport.TransportException) {
                assertEquals(
                    com.sunshine.mesh.core.transport.TransportError.PACKET_TOO_LARGE,
                    expected.code,
                )
            }
        } finally {
            client?.close()
            accepted?.link?.close()
            server.close()
        }
    }

    private fun withSocketPair(block: (BufferedInputStream, BufferedOutputStream) -> Unit) {
        val server = ServerSocket(0)
        var clientSocket: Socket? = null
        var acceptedSocket: Socket? = null
        try {
            clientSocket = Socket(InetAddress.getLoopbackAddress(), server.localPort)
            acceptedSocket = server.accept()
            block(
                BufferedInputStream(acceptedSocket.getInputStream()),
                BufferedOutputStream(clientSocket.getOutputStream()),
            )
        } finally {
            clientSocket?.close()
            acceptedSocket?.close()
            server.close()
        }
    }
}
