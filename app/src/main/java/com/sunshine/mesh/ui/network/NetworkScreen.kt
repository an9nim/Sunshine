package com.sunshine.mesh.ui.network

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.sunshine.mesh.R
import com.sunshine.mesh.core.transport.TransportKind
import com.sunshine.mesh.ui.components.AmberAvatar
import com.sunshine.mesh.ui.components.AmberButton
import com.sunshine.mesh.ui.components.AmberButtonStyle
import com.sunshine.mesh.ui.components.AmberCard
import com.sunshine.mesh.ui.components.AmberErrorState
import com.sunshine.mesh.ui.components.AmberLoadingState
import com.sunshine.mesh.ui.components.AmberText
import com.sunshine.mesh.ui.components.AmberTextButton
import com.sunshine.mesh.ui.components.AmberTopBar
import com.sunshine.mesh.ui.theme.AmberBackground
import com.sunshine.mesh.ui.theme.AmberBorder
import com.sunshine.mesh.ui.theme.AmberDestructive
import com.sunshine.mesh.ui.theme.AmberMono
import com.sunshine.mesh.ui.theme.AmberMuted
import com.sunshine.mesh.ui.theme.AmberMutedForeground
import com.sunshine.mesh.ui.theme.AmberPrimary
import com.sunshine.mesh.ui.theme.AmberShape
import java.util.Locale

@Composable
fun NetworkScreen(
    state: NetworkUiState,
    onToggleNetwork: () -> Unit,
    onRestartNetwork: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onPermissionsResult: () -> Unit,
    onBack: () -> Unit,
    onHelpClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state as? NetworkUiState.Ready
    val subtitle = if (ready == null) {
        stringResource(R.string.network_loading)
    } else {
        statusLabel(ready.status)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        onPermissionsResult()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        onRefreshPermissions()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmberBackground)
            .systemBarsPadding(),
    ) {
        AmberTopBar(
            title = stringResource(R.string.nav_network),
            subtitle = subtitle,
        ) {
            AmberTextButton(
                text = stringResource(R.string.common_back),
                onClick = onBack,
            )
            AmberTextButton(
                text = stringResource(R.string.nav_help),
                onClick = onHelpClick,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AmberBorder),
        )

        when (state) {
            NetworkUiState.Loading -> AmberLoadingState(
                text = stringResource(R.string.network_loading),
            )

            NetworkUiState.Failed -> AmberErrorState(
                title = stringResource(R.string.network_error_title),
                body = stringResource(R.string.network_error_body),
                actionText = stringResource(R.string.common_back),
                onAction = onBack,
            )

            is NetworkUiState.Ready -> NetworkContent(
                state = state,
                onToggleNetwork = onToggleNetwork,
                onRestartNetwork = onRestartNetwork,
                onRequestPermissions = {
                    permissionLauncher.launch(state.missingPermissions.toTypedArray())
                },
                onHelpClick = onHelpClick,
            )
        }
    }
}

@Composable
private fun NetworkContent(
    state: NetworkUiState.Ready,
    onToggleNetwork: () -> Unit,
    onRestartNetwork: () -> Unit,
    onRequestPermissions: () -> Unit,
    onHelpClick: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            StatusCard(
                status = state.status,
                onToggleNetwork = onToggleNetwork,
                onRestartNetwork = onRestartNetwork,
            )
        }

        if (state.missingPermissions.isNotEmpty()) {
            item {
                PermissionsCard(
                    denied = state.permissionsDenied,
                    onRequestPermissions = onRequestPermissions,
                )
            }
        }

        item {
            PeersHeader(count = state.peers.size)
        }

        if (state.peers.isEmpty()) {
            item { EmptyPeersCard() }
        } else {
            items(state.peers, key = { it.peerId.value }) { peer ->
                PeerRow(peer)
            }
        }

        item {
            HelpCard(onClick = onHelpClick)
        }
    }
}

@Composable
private fun StatusCard(
    status: NetworkStatus,
    onToggleNetwork: () -> Unit,
    onRestartNetwork: () -> Unit,
) {
    AmberCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor(status)),
                )
                Spacer(Modifier.width(8.dp))
                AmberText(
                    text = statusLabel(status),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            AmberText(
                text = statusDescription(status),
                style = MaterialTheme.typography.bodyMedium,
                color = AmberMutedForeground,
            )

            if (status is NetworkStatus.Failed) {
                AmberText(
                    text = stringResource(R.string.network_error_code, status.error.name),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = AmberMono,
                    ),
                    color = AmberDestructive,
                    maxLines = 1,
                )
            }

            when (status) {
                is NetworkStatus.Failed -> AmberButton(
                    text = stringResource(R.string.network_restart),
                    onClick = onRestartNetwork,
                )

                is NetworkStatus.Running,
                is NetworkStatus.Starting,
                -> AmberButton(
                    text = stringResource(R.string.network_toggle_off),
                    onClick = onToggleNetwork,
                    style = AmberButtonStyle.Ghost,
                )

                else -> AmberButton(
                    text = stringResource(R.string.network_toggle_on),
                    onClick = onToggleNetwork,
                )
            }
        }
    }
}

@Composable
private fun PermissionsCard(
    denied: Boolean,
    onRequestPermissions: () -> Unit,
) {
    AmberCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AmberText(
                text = stringResource(R.string.permissions_title),
                style = MaterialTheme.typography.titleMedium,
            )
            AmberText(
                text = stringResource(R.string.network_permissions_body),
                style = MaterialTheme.typography.bodyMedium,
                color = AmberMutedForeground,
            )
            if (denied) {
                AmberText(
                    text = stringResource(R.string.permissions_denied),
                    style = MaterialTheme.typography.labelMedium,
                    color = AmberDestructive,
                )
            }
            AmberButton(
                text = stringResource(R.string.network_permissions_request),
                onClick = onRequestPermissions,
            )
        }
    }
}

@Composable
private fun PeersHeader(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AmberText(
            text = stringResource(R.string.network_peers_title),
            style = MaterialTheme.typography.labelLarge,
            color = AmberMutedForeground,
        )
        Spacer(Modifier.weight(1f))
        AmberText(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = AmberMono),
            color = AmberMutedForeground,
            maxLines = 1,
        )
    }
}

@Composable
private fun PeerRow(peer: NetworkPeer) {
    val displayName = peer.nickname.ifBlank {
        stringResource(R.string.chat_unknown_peer, peer.peerId.value.take(8))
    }

    AmberCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AmberAvatar(
                initial = displayName.take(1).uppercase(Locale.getDefault()),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                AmberText(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                AmberText(
                    text = peer.peerId.value,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = AmberMono,
                    ),
                    color = AmberMutedForeground,
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    peer.transports.sortedBy { it.ordinal }.forEach { kind ->
                        TransportBadge(kind)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportBadge(kind: TransportKind) {
    val label = when (kind) {
        TransportKind.BLE -> R.string.network_transport_ble
        TransportKind.WIFI_DIRECT -> R.string.network_transport_wifi
        TransportKind.UNKNOWN -> R.string.network_transport_unknown
    }

    Box(
        modifier = Modifier
            .clip(AmberShape)
            .background(AmberMuted)
            .border(width = 1.dp, color = AmberBorder, shape = AmberShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        AmberText(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = AmberMono),
            color = AmberPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyPeersCard() {
    AmberCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AmberText(
                text = stringResource(R.string.network_peers_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            AmberText(
                text = stringResource(R.string.network_peers_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = AmberMutedForeground,
            )
        }
    }
}

@Composable
private fun HelpCard(onClick: () -> Unit) {
    AmberCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AmberText(
                    text = stringResource(R.string.network_help_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                AmberText(
                    text = stringResource(R.string.network_help_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AmberMutedForeground,
                )
            }
            Spacer(Modifier.width(12.dp))
            AmberText(
                text = "→",
                style = MaterialTheme.typography.titleLarge,
                color = AmberPrimary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun statusLabel(status: NetworkStatus): String = when (status) {
    NetworkStatus.Idle -> stringResource(R.string.network_status_idle)
    NetworkStatus.Starting -> stringResource(R.string.network_status_starting)
    is NetworkStatus.Running -> stringResource(R.string.network_status_running)
    is NetworkStatus.Failed -> stringResource(R.string.network_status_failed)
    NetworkStatus.Closed -> stringResource(R.string.network_status_closed)
}

@Composable
private fun statusDescription(status: NetworkStatus): String = when (status) {
    NetworkStatus.Idle -> stringResource(R.string.network_status_idle_body)
    NetworkStatus.Starting -> stringResource(R.string.network_status_starting_body)
    is NetworkStatus.Running -> stringResource(R.string.network_status_running_body)
    is NetworkStatus.Failed -> stringResource(R.string.network_status_failed_body)
    NetworkStatus.Closed -> stringResource(R.string.network_status_closed_body)
}

private fun statusColor(status: NetworkStatus): Color = when (status) {
    is NetworkStatus.Running,
    is NetworkStatus.Starting,
    -> AmberPrimary

    is NetworkStatus.Failed -> AmberDestructive
    NetworkStatus.Idle,
    NetworkStatus.Closed,
    -> AmberMutedForeground
}
