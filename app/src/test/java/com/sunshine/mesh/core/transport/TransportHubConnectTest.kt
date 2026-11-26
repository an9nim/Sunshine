package com.sunshine.mesh.core.transport

import com.sunshine.mesh.core.ids.PeerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransportHubConnectTest {

    @Test
    fun alreadyLinkedPeerDoesNotTouchTransports() = runTest {
        val transport = FakeTransport(TransportKind.BLE).apply {
            peers.value = setOf(PEER)
        }
        val hub = TransportHub(listOf(transport), backgroundScope)
        advanceUntilIdle()

        hub.connect(PEER)

        assertEquals(0, transport.connectCalls)
        hub.close()
    }

    @Test
    fun timeoutFromEveryTransportIsPropagated() = runTest {
        val ble = FakeTransport(TransportKind.BLE) {
            throw TransportException(TransportError.CONNECT_TIMEOUT)
        }
        val wifi = FakeTransport(TransportKind.WIFI_DIRECT) {
            throw TransportException(TransportError.CONNECT_TIMEOUT)
        }
        val hub = TransportHub(listOf(ble, wifi), backgroundScope)
        advanceUntilIdle()

        val error = runCatching { hub.connect(PEER) }.exceptionOrNull()

        assertTrue(error is TransportException)
        assertEquals(TransportError.CONNECT_TIMEOUT, (error as TransportException).code)
        assertEquals(1, ble.connectCalls)
        assertEquals(1, wifi.connectCalls)
        hub.close()
    }

    @Test
    fun firstSuccessfulTransportWinsTheRace() = runTest {
        val ble = FakeTransport(TransportKind.BLE) {
            throw TransportException(TransportError.CONNECT_TIMEOUT)
        }
        val wifi = FakeTransport(TransportKind.WIFI_DIRECT)
        val hub = TransportHub(listOf(ble, wifi), backgroundScope)
        advanceUntilIdle()

        hub.connect(PEER)

        assertEquals(1, ble.connectCalls)
        assertEquals(1, wifi.connectCalls)
        hub.close()
    }

    private class FakeTransport(
        override val kind: TransportKind,
        private val onConnect: suspend () -> Unit = {},
    ) : Transport {
        override val state = MutableStateFlow<TransportState>(TransportState.Running(0))
        override val peers = MutableStateFlow<Set<PeerId>>(emptySet())
        override val incoming: Flow<InboundPacket> = emptyFlow()

        var connectCalls = 0

        override fun start() = Unit

        override suspend fun connect(peerId: PeerId) {
            connectCalls += 1
            onConnect()
        }

        override suspend fun send(peerId: PeerId, packet: ByteArray) = Unit

        override fun close() = Unit
    }

    private companion object {
        val PEER = PeerId.parse("aaaaaaaaaaaaaaaa")
    }
}
