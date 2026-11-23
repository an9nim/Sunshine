package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BleCentralLinkTest {

    private val local = PeerId.parse("0000000000000001")
    private val remote = PeerId.parse("ffffffffffffffff")

    @Test
    fun establishRunsGattSetupAndHello() = runBlocking {
        val client = FakeBleGattClient(remote)
        val link = BleCentralLink(client, local, remote, this)
        try {
            link.establish()

            assertEquals(1, client.connectCount)
            assertEquals(1, client.discoverCount)
            assertEquals(1, client.mtuCount)
            assertEquals(1, client.indicationsCount)
            assertEquals(517, link.mtu)

            val hello = BleFrameCodec.decode(client.written.first())
            assertTrue(hello is BleFrame.Hello)
            assertEquals(local, (hello as BleFrame.Hello).peerId)
        } finally {
            link.close()
        }
    }

    @Test
    fun sendChunksOnePacketWithoutMixing() = runBlocking {
        val client = FakeBleGattClient(remote)
        val link = BleCentralLink(client, local, remote, this)
        try {
            link.establish()
            val packet = ByteArray(512) { it.toByte() }
            link.send(packet)

            val dataFrames = client.written.drop(1).map {
                BleFrameCodec.decode(it) as BleFrame.Data
            }
            val assembler = BlePacketAssembler()
            var assembled: ByteArray? = null
            for (frame in dataFrames) {
                assembler.accept(frame)?.let { assembled = it }
            }
            assertArrayEquals(packet, assembled)
        } finally {
            link.close()
        }
    }

    @Test
    fun incomingEmitsOnlyCompletePackets() = runBlocking {
        val client = FakeBleGattClient(remote)
        val link = BleCentralLink(client, local, remote, this)
        try {
            link.establish()
            val packet = ByteArray(512) { (it % 251).toByte() }
            val chunks = BleFrameCodec.encodeData(packet, link.mtu, transferId = 7)
            for (chunk in chunks) {
                client.eventsChannel.trySend(BleGattClientEvent.FrameReceived(chunk))
            }

            val received = withTimeout(1_000) { link.incoming.first() }
            assertArrayEquals(packet, received)
        } finally {
            link.close()
        }
    }

    @Test
    fun handshakeTimeoutFailsWithLinkLost() = runBlocking {
        val client = FakeBleGattClient(remote, autoAck = false)
        val link = BleCentralLink(client, local, remote, this, handshakeTimeoutMs = 50)
        try {
            link.establish()
            fail("expected handshake timeout")
        } catch (error: TransportException) {
            assertEquals(TransportError.LINK_LOST, error.code)
        } finally {
            link.close()
        }
    }

    @Test
    fun unsupportedAckVersionFailsWithProtocolError() = runBlocking {
        val client = FakeBleGattClient(remote, ackVersion = BleProtocol.VERSION + 1)
        val link = BleCentralLink(client, local, remote, this)
        try {
            link.establish()
            fail("expected protocol error")
        } catch (error: TransportException) {
            assertEquals(TransportError.PROTOCOL_ERROR, error.code)
        } finally {
            link.close()
        }
    }

    @Test
    fun ackPeerMismatchFailsWithProtocolError() = runBlocking {
        val client = FakeBleGattClient(remote, ackPeerId = PeerId.parse("0000000000000002"))
        val link = BleCentralLink(client, local, remote, this)
        try {
            link.establish()
            fail("expected protocol error")
        } catch (error: TransportException) {
            assertEquals(TransportError.PROTOCOL_ERROR, error.code)
        } finally {
            link.close()
        }
    }

    @Test
    fun dataBeforeHandshakeFailsWithProtocolError() = runBlocking {
        val client = FakeBleGattClient(remote, autoAck = false)
        val link = BleCentralLink(client, local, remote, this, handshakeTimeoutMs = 500)
        val feeder = launch {
            delay(50)
            val chunk = BleFrameCodec.encodeData(ByteArray(16), BleProtocol.DEFAULT_MTU, 0).first()
            client.eventsChannel.trySend(BleGattClientEvent.FrameReceived(chunk))
        }
        try {
            link.establish()
            fail("expected protocol error")
        } catch (error: TransportException) {
            assertEquals(TransportError.PROTOCOL_ERROR, error.code)
        } finally {
            feeder.cancel()
            link.close()
        }
    }

    @Test
    fun wrongRoleIsRejectedBeforeConnect() = runBlocking {
        val client = FakeBleGattClient(local)
        val link = BleCentralLink(client, localPeerId = remote, peerId = local, scope = this)
        try {
            link.establish()
            fail("expected protocol error")
        } catch (error: TransportException) {
            assertEquals(TransportError.PROTOCOL_ERROR, error.code)
            assertEquals(0, client.connectCount)
        } finally {
            link.close()
        }
    }

    private class FakeBleGattClient(
        private val remotePeerId: PeerId,
        private val autoAck: Boolean = true,
        private val ackVersion: Int = BleProtocol.VERSION,
        private val ackPeerId: PeerId = remotePeerId,
    ) : BleGattClient {

        val eventsChannel = Channel<BleGattClientEvent>(Channel.UNLIMITED)
        override val events: Flow<BleGattClientEvent> = eventsChannel.receiveAsFlow()
        override var mtu = BleProtocol.DEFAULT_MTU

        val written = mutableListOf<ByteArray>()
        var connectCount = 0
        var discoverCount = 0
        var mtuCount = 0
        var indicationsCount = 0

        override suspend fun connect() {
            connectCount += 1
        }

        override suspend fun discoverServices() {
            discoverCount += 1
        }

        override suspend fun requestMtu(requested: Int): Int {
            mtuCount += 1
            mtu = BleProtocol.REQUESTED_MTU
            return mtu
        }

        override suspend fun enableIndications() {
            indicationsCount += 1
        }

        override suspend fun write(frame: ByteArray) {
            written.add(frame.copyOf())
            val decoded = BleFrameCodec.decode(frame)
            if (decoded is BleFrame.Hello && autoAck) {
                eventsChannel.trySend(
                    BleGattClientEvent.FrameReceived(
                        BleFrameCodec.encodeHelloAck(ackPeerId, ackVersion),
                    ),
                )
            }
        }

        override fun close() {
            eventsChannel.close()
        }
    }
}
