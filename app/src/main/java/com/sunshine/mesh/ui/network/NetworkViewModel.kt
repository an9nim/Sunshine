package com.sunshine.mesh.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sunshine.mesh.coordinator.ConnectResult
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportKind
import com.sunshine.mesh.core.transport.TransportState
import com.sunshine.mesh.data.peer.PeerDataSource
import com.sunshine.mesh.domain.model.Peer
import com.sunshine.mesh.service.NetworkControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

class NetworkViewModel(
    private val control: NetworkControl,
    private val peerSource: PeerDataSource,
    private val permissions: NetworkPermissions,
) : ViewModel() {

    private val permissionState = MutableStateFlow(
        PermissionSnapshot(
            missing = permissions.missing(),
            denied = false,
        ),
    )

    private val connectingPeer = MutableStateFlow<PeerId?>(null)
    private val connectFailedPeer = MutableStateFlow<PeerId?>(null)
    private val connectSignals = MutableSharedFlow<PeerId>(extraBufferCapacity = 1)

    /** Emits the peer whose link is ready; the nav host then opens its chat. */
    val connectEvents: SharedFlow<PeerId> = connectSignals.asSharedFlow()

    private val statusFlow: Flow<NetworkStatus> = combine(
        control.hub.flatMapLatest { hub ->
            hub?.state ?: flowOf<TransportState?>(null)
        },
        control.startError,
        control.running,
    ) { transportState, startError, running ->
        toStatus(transportState, startError, running)
    }

    private val peersFlow: Flow<List<NetworkPeer>> = combine(
        control.hub.flatMapLatest { hub ->
            hub?.links ?: flowOf<Map<PeerId, Set<TransportKind>>>(emptyMap())
        },
        peerSource.observeAll(),
    ) { links, peers ->
        mergeNetworkPeers(links, peers)
    }

    private val readyState: Flow<NetworkUiState> = combine(
        statusFlow,
        peersFlow,
        permissionState,
        connectingPeer,
        connectFailedPeer,
    ) { status, peers, permissions, connecting, failed ->
        NetworkUiState.Ready(
            status = status,
            peers = peers,
            missingPermissions = permissions.missing,
            permissionsDenied = permissions.denied,
            connectingPeerId = connecting,
            connectFailedPeerId = failed,
        )
    }

    val state: StateFlow<NetworkUiState> = readyState.catch { error ->
        if (error is CancellationException) throw error
        Timber.w("Network state failed")
        emit(NetworkUiState.Failed)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = NetworkUiState.Loading,
    )

    fun onToggleNetwork() {
        when ((state.value as? NetworkUiState.Ready)?.status) {
            is NetworkStatus.Running,
            NetworkStatus.Starting,
            -> control.stop()

            NetworkStatus.Closed -> control.restart()
            else -> control.start()
        }
    }

    fun onRestartNetwork() {
        control.restart()
    }

    /**
     * Connects before navigating: a link that is already up returns at once,
     * otherwise the row shows "Подключение…" until the transport timeout.
     */
    fun onPeerClick(peerId: PeerId) {
        if (connectingPeer.value != null) return
        connectFailedPeer.value = null
        connectingPeer.value = peerId

        viewModelScope.launch {
            val result = try {
                control.connect(peerId)
            } catch (error: CancellationException) {
                connectingPeer.value = null
                throw error
            } catch (error: Exception) {
                Timber.w("Peer connect crashed: peerId=%s", peerId)
                ConnectResult.Failed(TransportError.INTERNAL)
            }

            connectingPeer.value = null
            when (result) {
                ConnectResult.Connected -> connectSignals.tryEmit(peerId)
                ConnectResult.Timeout -> connectFailedPeer.value = peerId
                is ConnectResult.Failed -> {
                    Timber.w("Peer connect failed: peerId=%s code=%s", peerId, result.error)
                    connectFailedPeer.value = peerId
                }
            }
        }
    }

    /** Re-reads permissions after returning from system settings. */
    fun onRefreshPermissions() {
        val previous = permissionState.value
        val missing = permissions.missing()
        permissionState.value = PermissionSnapshot(
            missing = missing,
            denied = previous.denied && missing.isNotEmpty(),
        )
    }

    /** Called after the runtime permission dialog closes. */
    fun onPermissionsResult() {
        val missing = permissions.missing()
        permissionState.value = PermissionSnapshot(
            missing = missing,
            denied = missing.isNotEmpty(),
        )
    }

    private data class PermissionSnapshot(
        val missing: List<String>,
        val denied: Boolean,
    )

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(
            control: NetworkControl,
            peerSource: PeerDataSource,
            permissions: NetworkPermissions,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                NetworkViewModel(
                    control = control,
                    peerSource = peerSource,
                    permissions = permissions,
                )
            }
        }
    }
}

private fun toStatus(
    transportState: TransportState?,
    startError: TransportError?,
    running: Boolean,
): NetworkStatus = when (transportState) {
    is TransportState.Running -> NetworkStatus.Running(transportState.connectedPeers)
    is TransportState.Failed -> NetworkStatus.Failed(transportState.error)
    TransportState.Starting -> NetworkStatus.Starting
    TransportState.Closed -> NetworkStatus.Closed
    TransportState.Idle,
    null,
    -> when {
        startError != null -> NetworkStatus.Failed(startError)
        running -> NetworkStatus.Starting
        else -> NetworkStatus.Idle
    }
}

internal fun mergeNetworkPeers(
    links: Map<PeerId, Set<TransportKind>>,
    peers: List<Peer>,
): List<NetworkPeer> {
    val byId = peers.associateBy { it.peerId }
    return links.map { (peerId, transports) ->
        NetworkPeer(
            peerId = peerId,
            nickname = byId[peerId]?.nickname.orEmpty(),
            transports = transports,
        )
    }.sortedBy { it.nickname.ifBlank { it.peerId.value } }
}
