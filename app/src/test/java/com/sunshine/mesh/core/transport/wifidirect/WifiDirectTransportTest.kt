package com.sunshine.mesh.core.transport.wifidirect

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import com.sunshine.mesh.core.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class WifiDirectTransportTest {

    private val low = PeerId.parse("0000000000000001")
    private val high = PeerId.parse("ffffffffffffffff")
    private val second = PeerId.parse("fffffffffffffffe")

    @Test
    fun startBeginsDiscovery() = runBlocking {
        val fixture = Fixture(this, low)
        try {
            fixture.transport.start()
            fixture.transport.state.first { it is TransportState.Running }

            assertEquals(low, fixture.discovery.startedPeerId)
        } finally {
            fixture.transport.close()
        }
    }

    @Test
    fun lowerPeerIdConnectsAsClientAndExchangesPackets() = runBlocking {
        val go = FakeGoServer()
        val goJob = go.start(this)
        val fixture = Fixture(this, low)
        try {
            fixture.transport.start()
            fixture.transport.state.first { it is TransportState.Running }
            fixture.discovery.emitFound(high, go.server.localPort)
            awaitUntil { fixture.transport.peers.value.contains(high) }

            assertEquals(1, fixture.groups.connectCount)
            assertEquals(0, fixture.groups.createCount)

            val packet = ByteArray(WifiDirectProtocol.MAX_FRAME_BYTES) { it.toByte() }
            fixture.transport.send(high, packet)
            val received = withTimeout(2_000) { go.received.receive() }
            assertArrayEquals(packet, received)

            val inbound = withTimeout(2_000) { fixture.transport.incoming.first() }
            assertEquals(high, inbound.peerId)
            assertArrayEquals(packet, inbound.packet)
            inbound.close()
        } finally {
            fixture.transport.close()
            goJob.cancel()
            go.close()
        }
    }

    @Test
    fun higherPeerIdCreatesGroup() = runBlocking {
        val fixture = Fixture(this, high)
        try {
            fixture.transport.start()
            fixture.transport.state.first { it is TransportState.Running }
            fixture.discovery.emitFound(low, port = 9999)
            awaitUntil { fixture.groups.createCount == 1 }

            assertEquals(0, fixture.groups.connectCount)
        } finally {
            fixture.transport.close()
        }
    }

    @Test
    fun linkLimitRefusesExtraClients() = runBlocking {
        val go = FakeGoServer()
        val goJob = go.start(this)
        val fixture = Fixture(this, low, maxLinks = 1)
        try {
            fixture.transport.start()
            fixture.transport.state.first { it is TransportState.Running }
            fixture.discovery.emitFound(high, go.server.localPort)
            awaitUntil { fixture.transport.peers.value.contains(high) }

            fixture.discovery.emitFound(second, go.server.localPort)
            delay(100)

            assertEquals(1, fixture.groups.connectCount)
            assertEquals(setOf(high), fixture.transport.peers.value)
        } finally {
            fixture.transport.close()
            goJob.cancel()
            go.close()
        }
    }

    @Test
    fun sendWithoutLinkFailsWithNotConnected() = runBlocking {
        val fixture = Fixture(this, low)
        try {
            fixture.transport.start()
            fixture.transport.state.first { it is TransportState.Running }

            try {
                fixture.transport.send(high, ByteArray(WifiDirectProtocol.MAX_FRAME_BYTES))
                fail("expected NOT_CONNECTED")
            } catch (error: TransportException) {
                assertEquals(TransportError.NOT_CONNECTED, error.code)
            }
        } finally {
            fixture.transport.close()
        }
    }

    @Test
    fun discoveryFailureMovesTransportToFailed() = runBlocking {
        val fixture = Fixture(this, low)
        try {
            fixture.transport.start()
            fixture.transport.state.first { it is TransportState.Running }

            fixture.discovery.eventsChannel.trySend(
                WifiDirectDiscoveryEvent.Failed(TransportError.WIFI_DISCOVERY_FAILED),
            )
            val state = fixture.transport.state.first { it is TransportState.Failed }
            assertEquals(
                TransportError.WIFI_DISCOVERY_FAILED,
                (state as TransportState.Failed).error,
            )
        } finally {
            fixture.transport.close()
        }
    }

    @Test
    fun closeForcesRemoveGroupAndReleasesDiscovery() = runBlocking {
        val fixture = Fixture(this, high)
        fixture.transport.start()
        fixture.transport.state.first { it is TransportState.Running }
        fixture.discovery.emitFound(low, port = 9999)
        awaitUntil { fixture.groups.createCount == 1 }

        fixture.transport.close()

        assertEquals(TransportState.Closed, fixture.transport.state.value)
        assertTrue(fixture.groups.closed)
        assertTrue(fixture.groups.removed)
        assertTrue(fixture.discovery.closed)
        assertEquals(emptySet<PeerId>(), fixture.transport.peers.value)
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(3_000) {
            while (!condition()) delay(10)
        }
    }

    private class Fixture(
        scope: CoroutineScope,
        localPeerId: PeerId,
        maxLinks: Int = WifiDirectProtocol.MAX_LINKS,
    ) {
        val discovery = FakeDiscovery()
        val groups = FakeGroups()
        val transport = WifiDirectTransport(
            localPeerId = localPeerId,
            discovery = discovery,
            groups = groups,
            prerequisites = object : WifiDirectGate {
                override fun requireDiscoveryReady() = Unit
            },
            scope = scope,
            maxLinks = maxLinks,
            port = 0,
        )
    }

    private class FakeDiscovery : WifiDirectDiscovery {
        val eventsChannel = Channel<WifiDirectDiscoveryEvent>(Channel.UNLIMITED)
        override val events: Flow<WifiDirectDiscoveryEvent> = eventsChannel.receiveAsFlow()
        var startedPeerId: PeerId? = null
        var closed = false

        override fun start(localPeerId: PeerId) {
            startedPeerId = localPeerId
        }

        override fun stop() = Unit

        override fun close() {
            closed = true
            eventsChannel.close()
        }

        fun emitFound(peerId: PeerId, port: Int) {
            eventsChannel.trySend(
                WifiDirectDiscoveryEvent.Found(peerId, "00:11:22:33:44:55", port),
            )
        }
    }

    private class FakeGroups : WifiDirectGroupManager {
        var createCount = 0
        var connectCount = 0
        var removed = false
        var closed = false

        override suspend fun createGroup(): WifiDirectGroupInfo {
            createCount += 1
            return WifiDirectGroupInfo(true, InetAddress.getLoopbackAddress())
        }

        override suspend fun connect(deviceAddress: String): WifiDirectGroupInfo {
            connectCount += 1
            return WifiDirectGroupInfo(false, InetAddress.getLoopbackAddress())
        }

        override suspend fun removeGroup() {
            removed = true
        }

        override suspend fun currentGroup(): WifiDirectGroupInfo? = null

        override fun close() {
            closed = true
            removed = true
        }
    }

    /** Loopback stand-in for the remote group owner: reads the handshake, echoes frames. */
    private class FakeGoServer : Closeable {
        val server = ServerSocket(0)
        val received = Channel<ByteArray>(Channel.UNLIMITED)
        private var socket: Socket? = null

        fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
            val accepted = server.accept()
            socket = accepted
            val input = BufferedInputStream(accepted.getInputStream())
            val output = BufferedOutputStream(accepted.getOutputStream())
            WifiDirectFraming.readFrame(input, WifiDirectProtocol.HANDSHAKE_BYTES)
            while (true) {
                val frame = WifiDirectFraming.readFrame(
                    input,
                    WifiDirectProtocol.MAX_FRAME_BYTES,
                ) ?: break
                received.trySend(frame)
                WifiDirectFraming.writeFrame(output, frame)
                output.flush()
            }
        }

        override fun close() {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            server.close()
            received.close()
        }
    }
}
