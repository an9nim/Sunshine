package com.sunshine.mesh.ui.network

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.InboundPacket
import com.sunshine.mesh.core.transport.Transport
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportHub
import com.sunshine.mesh.core.transport.TransportKind
import com.sunshine.mesh.core.transport.TransportState
import com.sunshine.mesh.data.peer.PeerDataSource
import com.sunshine.mesh.domain.model.Peer
import com.sunshine.mesh.domain.model.TrustState
import com.sunshine.mesh.service.NetworkControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun runningWithoutHubShowsStarting() = runTest(dispatcher) {
        val control = FakeNetworkControl().apply { running.value = true }
        val viewModel = createViewModel(control)
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        assertEquals(NetworkStatus.Starting, viewModel.ready().status)
    }

    @Test
    fun startupErrorIsShownWithCode() = runTest(dispatcher) {
        val control = FakeNetworkControl().apply {
            startError.value = TransportError.PERMISSION_DENIED
        }
        val viewModel = createViewModel(control)
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        assertEquals(
            NetworkStatus.Failed(TransportError.PERMISSION_DENIED),
            viewModel.ready().status,
        )
    }

    @Test
    fun transportFailureIsShownWithCode() = runTest(dispatcher) {
        val transport = FakeTransport(TransportKind.BLE).apply {
            state.value = TransportState.Failed(TransportError.BLUETOOTH_DISABLED)
        }
        val hub = TransportHub(listOf(transport), backgroundScope)
        val control = FakeNetworkControl().apply {
            this.hub.value = hub
            running.value = true
        }
        val viewModel = createViewModel(control)
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        assertEquals(
            NetworkStatus.Failed(TransportError.BLUETOOTH_DISABLED),
            viewModel.ready().status,
        )
        hub.close()
    }

    @Test
    fun toggleStartsWhenIdle() = runTest(dispatcher) {
        val control = FakeNetworkControl()
        val viewModel = createViewModel(control)
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.onToggleNetwork()

        assertEquals(1, control.starts)
        assertEquals(0, control.stops)
    }

    @Test
    fun toggleStopsRunningNetwork() = runTest(dispatcher) {
        val hub = TransportHub(listOf(FakeTransport(TransportKind.BLE)), backgroundScope)
        val control = FakeNetworkControl().apply {
            this.hub.value = hub
            running.value = true
        }
        val viewModel = createViewModel(control)
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.onToggleNetwork()

        assertEquals(1, control.stops)
        assertEquals(0, control.starts)
        hub.close()
    }

    @Test
    fun permissionResultMarksDeniedUntilGranted() = runTest(dispatcher) {
        val permissions = FakeNetworkPermissions(mutableListOf("android.permission.BLUETOOTH_SCAN"))
        val viewModel = createViewModel(FakeNetworkControl(), permissions = permissions)
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        assertEquals(1, viewModel.ready().missingPermissions.size)
        assertFalse(viewModel.ready().permissionsDenied)

        viewModel.onPermissionsResult()
        advanceUntilIdle()
        assertTrue(viewModel.ready().permissionsDenied)

        permissions.missingPermissions.clear()
        viewModel.onPermissionsResult()
        advanceUntilIdle()
        assertTrue(viewModel.ready().missingPermissions.isEmpty())
        assertFalse(viewModel.ready().permissionsDenied)
    }

    @Test
    fun peersAreMergedWithNicknamesAndTransports() {
        val links = mapOf(
            PEER_A to setOf(TransportKind.BLE, TransportKind.WIFI_DIRECT),
            PEER_B to setOf(TransportKind.WIFI_DIRECT),
        )
        val peers = listOf(
            peer(PEER_A, "Alice"),
            peer(PEER_B, "Bob"),
        )

        val result = mergeNetworkPeers(links, peers)

        assertEquals(listOf("Alice", "Bob"), result.map { it.nickname })
        assertEquals(setOf(TransportKind.BLE, TransportKind.WIFI_DIRECT), result[0].transports)
        assertEquals(setOf(TransportKind.WIFI_DIRECT), result[1].transports)
    }

    @Test
    fun unknownPeerKeepsEmptyNickname() {
        val result = mergeNetworkPeers(
            links = mapOf(PEER_A to setOf(TransportKind.BLE)),
            peers = emptyList(),
        )

        assertEquals(1, result.size)
        assertEquals("", result[0].nickname)
        assertEquals(PEER_A, result[0].peerId)
    }

    private fun createViewModel(
        control: NetworkControl,
        permissions: NetworkPermissions = FakeNetworkPermissions(),
    ): NetworkViewModel = NetworkViewModel(
        control = control,
        peerSource = FakePeerSource,
        permissions = permissions,
    )

    private fun NetworkViewModel.ready(): NetworkUiState.Ready =
        state.value as NetworkUiState.Ready

    private object FakePeerSource : PeerDataSource {
        override fun observeAll(): Flow<List<Peer>> = flowOf(emptyList())
    }

    private class FakeNetworkControl : NetworkControl {
        override val hub = MutableStateFlow<TransportHub?>(null)
        override val running = MutableStateFlow(false)
        override val startError = MutableStateFlow<TransportError?>(null)

        var starts = 0
        var stops = 0
        var restarts = 0

        override fun start() {
            starts += 1
        }

        override fun stop() {
            stops += 1
        }

        override fun restart() {
            restarts += 1
        }
    }

    private class FakeNetworkPermissions(
        val missingPermissions: MutableList<String> = mutableListOf(),
    ) : NetworkPermissions {
        override val required: List<String> = missingPermissions.toList()

        override fun missing(): List<String> = missingPermissions.toList()
    }

    private class FakeTransport(
        override val kind: TransportKind,
        initialState: TransportState = TransportState.Running(0),
    ) : Transport {
        override val state = MutableStateFlow(initialState)
        override val peers = MutableStateFlow<Set<PeerId>>(emptySet())
        override val incoming: Flow<InboundPacket> = emptyFlow()

        override fun start() = Unit

        override suspend fun send(peerId: PeerId, packet: ByteArray) = Unit

        override fun close() = Unit
    }

    private companion object {
        val PEER_A = PeerId.parse("aaaaaaaaaaaaaaaa")
        val PEER_B = PeerId.parse("bbbbbbbbbbbbbbbb")

        fun peer(peerId: PeerId, nickname: String): Peer = Peer(
            peerId = peerId,
            nickname = nickname,
            firstSeenPublicKey = ByteArray(32) { 1 },
            lastSeenPublicKey = ByteArray(32) { 2 },
            trustState = TrustState.UNKNOWN,
            firstSeenAt = 0,
            lastSeenAt = 0,
            verifiedAt = null,
        )
    }
}
