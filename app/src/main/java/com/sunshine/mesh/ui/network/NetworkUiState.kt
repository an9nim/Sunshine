package com.sunshine.mesh.ui.network

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportKind

sealed interface NetworkUiState {

    data object Loading : NetworkUiState

    data class Ready(
        val status: NetworkStatus,
        val peers: List<NetworkPeer>,
        val missingPermissions: List<String>,
        val permissionsDenied: Boolean,
        val connectingPeerId: PeerId? = null,
        val connectFailedPeerId: PeerId? = null,
    ) : NetworkUiState

    data object Failed : NetworkUiState
}

/** UI-facing mirror of [com.sunshine.mesh.core.transport.TransportState]. */
sealed interface NetworkStatus {
    data object Idle : NetworkStatus
    data object Starting : NetworkStatus
    data class Running(val connectedPeers: Int) : NetworkStatus
    data class Failed(val error: TransportError) : NetworkStatus
    data object Closed : NetworkStatus
}

data class NetworkPeer(
    val peerId: PeerId,
    /** Empty when the peer has not been stored with a nickname yet. */
    val nickname: String,
    val transports: Set<TransportKind>,
)
