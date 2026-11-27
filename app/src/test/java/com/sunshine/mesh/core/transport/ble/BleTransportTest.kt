package com.sunshine.mesh.core.transport.ble

import android.bluetooth.BluetoothDevice
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import com.sunshine.mesh.core.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BleTransportTest {

    private val local = PeerId.parse("0000000000000001")
    private val remote = PeerId.parse("ffffffffffffffff")

    @Test
    fun startOpensServerAndStartsScannerAndAdvertiser() = runBlocking {
        val fixture = Fixture(local, this)
        try {
            fixture.transport.start()
            fixture.transport.state.first { it is TransportState.Running }

            assertEquals(1, fixture.server.openCount)
            assertEquals(1, fixture.scanner.startCount)
            assertEquals(local, fixture.advertiser.startedPeerId)
        } finally {
            fixture.transport.close()
        }
    }

    @Test
    fun lowerLocalPeerIdCreatesCentralLinkAndSends() = runBlocking {
        val fixture = Fixture(local, this)
        try {
            fixture.transport.start()
            fixture.scanner.eventsChannel.tryScanFound(remote)
            fixture.transport.state.first {
                it is TransportState.Running && it.connectedPeers == 1
            }

            val packet = ByteArray(512) { it.toByte() }
            fixture.transport.send(remote, packet)

            val client = fixture.factory.clients.single()
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
            fixture.transport.close()
        }
    }

    @Test
    fun incomingMergesLinkPacketsWithPeerId() = runBlocking {
        val fixture = Fixture(local, this)
        try {
            fixture.transport.start()
            fixture.scanner.eventsChannel.tryScanFound(remote)
            fixture.transport.state.first {
                it is TransportState.Running && it.connectedPeers == 1
            }

            val client = fixture.factory.clients.single()
            val packet = ByteArray(512) { (it % 251).toByte() }
            val chunks = BleFrameCodec.encodeData(packet, client.mtu, transferId = 3)
            for (chunk in chunks) {
                client.eventsChannel.trySend(BleGattClientEvent.FrameReceived(chunk))
            }

            val inbound = withTimeout(1_000) { fixture.transport.incoming.first() }
            assertEquals(remote, inbound.peerId)
            assertArrayEquals(packet, inbound.packet)
            inbound.close()
        } finally {
            fixture.transport.close()
        }
    }

    @Test
    fun higherLocalPeerIdDoesNotInitiateCentral() = runBlocking {
        val fixture = Fixture(localPeerId = remote, scope = this)
        try {
            fixture.transport.start()
            fixture.scanner.eventsChannel.tryScanFound(local)
            delay(100)

            assertTrue(fixture.factory.clients.isEmpty())
        } finally {
            fixture.transport.close()
        }
    }

    @Test
    fun sendWithoutLinkFailsWithNotConnected() = runBlocking {
        val fixture = Fixture(local, this)
        try {
            fixture.transport.start()
            fixture.transport.state.first { it is TransportState.Running }

            try {
                fixture.transport.send(remote, ByteArray(512))
                fail("expected NOT_CONNECTED")
            } catch (error: TransportException) {
                assertEquals(TransportError.NOT_CONNECTED, error.code)
            }
        } finally {
            fixture.transport.close()
        }
    }

    @Test
    fun linkLimitRefusesExtraCentralLinks() = runBlocking {
        val fixture = Fixture(local, this, maxLinks = 1)
        try {
            fixture.transport.start()
            fixture.scanner.eventsChannel.tryScanFound(remote)
            fixture.transport.state.first {
                it is TransportState.Running && it.connectedPeers == 1
            }

            val second = PeerId.parse("fffffffffffffffe")
            fixture.scanner.eventsChannel.tryScanFound(second)
            delay(100)

            assertEquals(1, fixture.factory.clients.size)
            val state = fixture.transport.state.value
            assertTrue(state is TransportState.Running && state.connectedPeers == 1)
        } finally {
            fixture.transport.close()
        }
    }

    @Test
    fun scanFailureMovesTransportToFailed() = runBlocking {
        val fixture = Fixture(local, this)
        try {
            fixture.transport.start()
            fixture.transport.state.first { it is TransportState.Running }

            fixture.scanner.eventsChannel.trySend(BleScanEvent.Failed(TransportError.SCAN_FAILED))
            val state = fixture.transport.state.first { it is TransportState.Failed }
            assertEquals(TransportError.SCAN_FAILED, (state as TransportState.Failed).error)
        } finally {
            fixture.transport.close()
        }
    }

    @Test
    fun serverConnectionReArmsAdvertising() = runBlocking {
        val fixture = Fixture(localPeerId = remote, scope = this)
        try {
            fixture.transport.start()
            fixture.transport.state.first { it is TransportState.Running }
            assertEquals(1, fixture.advertiser.startCount)

            val connection = stubConnection(id = 1L)
            fixture.server.eventsChannel.trySend(BleGattServerEvent.Connected(connection))
            delay(100)
            assertEquals(1, fixture.advertiser.restartCount)

            // The handshake never completed: the disconnect must clean up the
            // half-open link without throwing from the event collector.
            fixture.server.eventsChannel.trySend(
                BleGattServerEvent.Disconnected(connection, TransportError.LINK_LOST),
            )
            delay(100)

            assertEquals(2, fixture.advertiser.restartCount)
            assertEquals(emptySet<PeerId>(), fixture.transport.peers.value)
            assertTrue(fixture.transport.state.value is TransportState.Running)
        } finally {
            fixture.transport.close()
        }
    }

    @Test
    fun establishedPeripheralLinkIsRemovedOnDisconnect() = runBlocking {
        val fixture = Fixture(localPeerId = remote, scope = this)
        try {
            fixture.transport.start()
            fixture.transport.state.first { it is TransportState.Running }

            val connection = stubConnection(id = 7L)
            fixture.server.eventsChannel.trySend(BleGattServerEvent.Connected(connection))
            fixture.server.eventsChannel.trySend(
                BleGattServerEvent.IndicationsChanged(connection, true),
            )
            fixture.server.eventsChannel.trySend(
                BleGattServerEvent.FrameReceived(
                    connection,
                    BleFrameCodec.encodeHello(local),
                ),
            )
            assertEquals(
                setOf(local),
                withTimeout(1_000) { fixture.transport.peers.first { it.isNotEmpty() } },
            )

            fixture.server.eventsChannel.trySend(
                BleGattServerEvent.Disconnected(connection, null),
            )
            withTimeout(1_000) { fixture.transport.peers.first { it.isEmpty() } }

            assertEquals(2, fixture.advertiser.restartCount)
            assertTrue(fixture.transport.state.value is TransportState.Running)
        } finally {
            fixture.transport.close()
        }
    }

    @Test
    fun peersFlowTracksEstablishedLinks() = runBlocking {
        val fixture = Fixture(local, this)
        try {
            fixture.transport.start()
            fixture.scanner.eventsChannel.tryScanFound(remote)
            fixture.transport.state.first {
                it is TransportState.Running && it.connectedPeers == 1
            }
            assertEquals(setOf(remote), fixture.transport.peers.value)

            fixture.transport.close()
            assertEquals(emptySet<PeerId>(), fixture.transport.peers.value)
        } finally {
            fixture.transport.close()
        }
    }

    @Test
    fun closeReleasesResourcesAndSetsClosed() = runBlocking {
        val fixture = Fixture(local, this)
        fixture.transport.start()
        fixture.transport.state.first { it is TransportState.Running }

        fixture.transport.close()

        assertEquals(TransportState.Closed, fixture.transport.state.value)
        assertTrue(fixture.scanner.closed)
        assertTrue(fixture.advertiser.closed)
        assertTrue(fixture.server.closed)
    }

    private class Fixture(
        localPeerId: PeerId,
        scope: CoroutineScope,
        maxLinks: Int = BleProtocol.MAX_LINKS,
    ) {
        val scanner = FakeScanner()
        val advertiser = FakeAdvertiser()
        val server = FakeServer()
        val factory = FakeClientFactory()
        val transport = BleTransport(
            localPeerId = localPeerId,
            scanner = scanner,
            advertiser = advertiser,
            server = server,
            clientFactory = factory,
            scope = scope,
            maxLinks = maxLinks,
        )
    }

    /** android.jar stubs cannot be constructed; allocate one without a constructor. */
    private fun stubConnection(id: Long): BleServerConnection {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val device = allocateInstance.invoke(theUnsafe, BluetoothDevice::class.java) as BluetoothDevice
        return BleServerConnection(id = id, device = device)
    }

    private fun Channel<BleScanEvent>.tryScanFound(peerId: PeerId) {
        trySend(
            BleScanEvent.Found(
                BleAdvertisement(peerId = peerId, address = peerId.value, rssi = -50),
            ),
        )
    }

    private class FakeScanner : BleScanner {
        val eventsChannel = Channel<BleScanEvent>(Channel.UNLIMITED)
        override val events: Flow<BleScanEvent> = eventsChannel.receiveAsFlow()
        var startCount = 0
        var closed = false

        override fun start() {
            startCount += 1
        }

        override fun stop() = Unit

        override fun close() {
            closed = true
            eventsChannel.close()
        }
    }

    private class FakeAdvertiser : BleAdvertiser {
        val eventsChannel = Channel<BleAdvertiseEvent>(Channel.UNLIMITED)
        override val events: Flow<BleAdvertiseEvent> = eventsChannel.receiveAsFlow()
        var startCount = 0
        var restartCount = 0
        var startedPeerId: PeerId? = null
        var closed = false

        override fun start(peerId: PeerId) {
            startCount += 1
            startedPeerId = peerId
            eventsChannel.trySend(BleAdvertiseEvent.Started)
        }

        override fun restart(peerId: PeerId) {
            restartCount += 1
            startedPeerId = peerId
        }

        override fun stop() = Unit

        override fun close() {
            closed = true
            eventsChannel.close()
        }
    }

    private class FakeServer : BleGattServer {
        val eventsChannel = Channel<BleGattServerEvent>(Channel.UNLIMITED)
        override val events: Flow<BleGattServerEvent> = eventsChannel.receiveAsFlow()
        var openCount = 0
        var closed = false

        override suspend fun open() {
            openCount += 1
        }

        override suspend fun indicate(
            connection: BleServerConnection,
            frame: ByteArray,
        ) = Unit

        override fun close() {
            closed = true
            eventsChannel.close()
        }
    }

    private class FakeClientFactory : BleGattClientFactory {
        val clients = mutableListOf<FakeBleGattClient>()

        override fun create(address: String): BleGattClient {
            val client = FakeBleGattClient(PeerId.parse(address))
            clients.add(client)
            return client
        }
    }

    private class FakeBleGattClient(
        private val remotePeerId: PeerId,
    ) : BleGattClient {
        val eventsChannel = Channel<BleGattClientEvent>(Channel.UNLIMITED)
        override val events: Flow<BleGattClientEvent> = eventsChannel.receiveAsFlow()
        override var mtu = BleProtocol.DEFAULT_MTU
        val written = mutableListOf<ByteArray>()

        override suspend fun connect() = Unit
        override suspend fun discoverServices() = Unit

        override suspend fun requestMtu(requested: Int): Int {
            mtu = BleProtocol.REQUESTED_MTU
            return mtu
        }

        override suspend fun enableIndications() = Unit

        override suspend fun write(frame: ByteArray) {
            written.add(frame.copyOf())
            val decoded = BleFrameCodec.decode(frame)
            if (decoded is BleFrame.Hello) {
                eventsChannel.trySend(
                    BleGattClientEvent.FrameReceived(
                        BleFrameCodec.encodeHelloAck(remotePeerId),
                    ),
                )
            }
        }

        override fun close() {
            eventsChannel.close()
        }
    }
}
