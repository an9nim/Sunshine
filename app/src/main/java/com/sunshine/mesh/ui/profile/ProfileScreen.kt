package com.sunshine.mesh.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sunshine.mesh.R
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.domain.model.TrustState
import com.sunshine.mesh.domain.validation.NicknameError
import com.sunshine.mesh.ui.components.AmberAvatar
import com.sunshine.mesh.ui.components.AmberButton
import com.sunshine.mesh.ui.components.AmberButtonStyle
import com.sunshine.mesh.ui.components.AmberCard
import com.sunshine.mesh.ui.components.AmberErrorState
import com.sunshine.mesh.ui.components.AmberLoadingState
import com.sunshine.mesh.ui.components.AmberText
import com.sunshine.mesh.ui.components.AmberTextField
import com.sunshine.mesh.ui.components.AmberTextButton
import com.sunshine.mesh.ui.components.AmberTopBar
import com.sunshine.mesh.ui.theme.AmberBackground
import com.sunshine.mesh.ui.theme.AmberBorder
import com.sunshine.mesh.ui.theme.AmberDestructive
import com.sunshine.mesh.ui.theme.AmberForeground
import com.sunshine.mesh.ui.theme.AmberMono
import com.sunshine.mesh.ui.theme.AmberMuted
import com.sunshine.mesh.ui.theme.AmberMutedForeground
import com.sunshine.mesh.ui.theme.AmberPrimary
import com.sunshine.mesh.ui.theme.AmberShape
import java.util.Locale

@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onNicknameChange: (String) -> Unit,
    onSaveNickname: () -> Unit,
    onAcceptPeerKey: (PeerId) -> Unit,
    onVerifyPeer: (PeerId) -> Unit,
    onBack: () -> Unit,
    onHelpClick: () -> Unit,
    onUpdateClick: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val copyFingerprint: (String) -> Unit = { fingerprint ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(context.getString(R.string.app_name), fingerprint),
        )
        Toast.makeText(
            context,
            context.getString(R.string.profile_fingerprint_copied),
            Toast.LENGTH_SHORT,
        ).show()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmberBackground)
            .systemBarsPadding(),
    ) {
        AmberTopBar(
            title = stringResource(R.string.profile_title),
            subtitle = stringResource(R.string.profile_subtitle),
        ) {
            AmberTextButton(
                text = stringResource(R.string.common_back),
                onClick = onBack,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AmberBorder),
        )

        when (state) {
            ProfileUiState.Loading -> AmberLoadingState(
                text = stringResource(R.string.profile_loading),
            )

            ProfileUiState.Failed -> AmberErrorState(
                title = stringResource(R.string.profile_load_error_title),
                body = stringResource(R.string.profile_load_error_body),
                actionText = stringResource(R.string.profile_retry),
                onAction = onRetry,
            )

            is ProfileUiState.Ready -> ProfileContent(
                state = state,
                onNicknameChange = onNicknameChange,
                onSaveNickname = onSaveNickname,
                onCopyFingerprint = copyFingerprint,
                onAcceptPeerKey = onAcceptPeerKey,
                onVerifyPeer = onVerifyPeer,
                onHelpClick = onHelpClick,
                onUpdateClick = onUpdateClick,
            )
        }
    }
}

@Composable
private fun ProfileContent(
    state: ProfileUiState.Ready,
    onNicknameChange: (String) -> Unit,
    onSaveNickname: () -> Unit,
    onCopyFingerprint: (String) -> Unit,
    onAcceptPeerKey: (PeerId) -> Unit,
    onVerifyPeer: (PeerId) -> Unit,
    onHelpClick: () -> Unit,
    onUpdateClick: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.notice != null) {
            item { ProfileNoticeBar(state.notice) }
        }

        item {
            NicknameCard(
                state = state,
                onNicknameChange = onNicknameChange,
                onSaveNickname = onSaveNickname,
            )
        }

        item {
            FingerprintCard(
                peerId = state.profile.peerId,
                onCopyFingerprint = onCopyFingerprint,
            )
        }

        item {
            PeersHeader(count = state.peers.size)
        }

        if (state.peers.isEmpty()) {
            item { EmptyPeersCard() }
        } else {
            items(state.peers, key = { it.peerId.value }) { peer ->
                PeerCard(
                    peer = peer,
                    trustActionsAvailable = state.trustActionsAvailable,
                    onAcceptPeerKey = onAcceptPeerKey,
                    onVerifyPeer = onVerifyPeer,
                )
            }
        }

        item {
            ProfileLinkCard(
                title = stringResource(R.string.profile_help_title),
                body = stringResource(R.string.profile_help_body),
                onClick = onHelpClick,
            )
        }

        item {
            ProfileLinkCard(
                title = stringResource(R.string.profile_update_title),
                body = stringResource(R.string.profile_update_body),
                onClick = onUpdateClick,
            )
        }
    }
}

@Composable
private fun ProfileNoticeBar(notice: ProfileNotice) {
    AmberCard(modifier = Modifier.fillMaxWidth()) {
        AmberText(
            text = stringResource(notice.messageRes()),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelMedium,
            color = notice.color(),
        )
    }
}

@Composable
private fun NicknameCard(
    state: ProfileUiState.Ready,
    onNicknameChange: (String) -> Unit,
    onSaveNickname: () -> Unit,
) {
    val initial = state.nicknameDraft.ifBlank { state.profile.nickname }
        .take(1)
        .uppercase(Locale.getDefault())

    AmberCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AmberAvatar(initial = initial)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    AmberText(
                        text = stringResource(R.string.profile_nickname),
                        style = MaterialTheme.typography.labelMedium,
                        color = AmberMutedForeground,
                    )
                    AmberText(
                        text = state.profile.nickname,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            AmberTextField(
                value = state.nicknameDraft,
                onValueChange = onNicknameChange,
                placeholder = stringResource(R.string.nickname_hint),
                isError = state.nicknameError != null,
                errorText = state.nicknameError?.let { stringResource(it.messageRes()) },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                AmberText(
                    text = stringResource(R.string.profile_nickname_hint),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberMutedForeground,
                )
                Spacer(Modifier.width(12.dp))
                AmberButton(
                    text = stringResource(
                        if (state.savingNickname) {
                            R.string.profile_nickname_saving
                        } else {
                            R.string.profile_nickname_save
                        },
                    ),
                    onClick = onSaveNickname,
                    enabled = !state.savingNickname && state.nicknameDraft.isNotBlank(),
                )
            }
        }
    }
}

@Composable
private fun FingerprintCard(
    peerId: PeerId,
    onCopyFingerprint: (String) -> Unit,
) {
    AmberCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AmberText(
                text = stringResource(R.string.profile_peer_id),
                style = MaterialTheme.typography.labelLarge,
                color = AmberMutedForeground,
            )
            AmberText(
                text = peerId.value,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = AmberMono),
                maxLines = 1,
            )
            AmberText(
                text = stringResource(R.string.profile_peer_id_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = AmberMutedForeground,
            )
            AmberButton(
                text = stringResource(R.string.profile_fingerprint_copy),
                onClick = { onCopyFingerprint(peerId.value) },
            )
        }
    }
}

@Composable
private fun PeersHeader(count: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 4.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AmberText(
                text = stringResource(R.string.profile_peers_title),
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
        AmberText(
            text = stringResource(R.string.profile_safety_number_hint),
            style = MaterialTheme.typography.labelSmall,
            color = AmberMutedForeground,
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
                text = stringResource(R.string.profile_peers_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            AmberText(
                text = stringResource(R.string.profile_peers_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = AmberMutedForeground,
            )
        }
    }
}

@Composable
private fun PeerCard(
    peer: ProfilePeer,
    trustActionsAvailable: Boolean,
    onAcceptPeerKey: (PeerId) -> Unit,
    onVerifyPeer: (PeerId) -> Unit,
) {
    val displayName = peer.nickname.ifBlank {
        stringResource(R.string.chat_unknown_peer, peer.peerId.value.take(8))
    }

    AmberCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AmberAvatar(initial = displayName.take(1).uppercase(Locale.getDefault()))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    AmberText(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AmberText(
                        text = peer.peerId.value,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = AmberMono,
                        ),
                        color = AmberMutedForeground,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(8.dp))
                TrustBadge(trustState = peer.trustState)
            }

            if (peer.safetyNumber != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AmberText(
                        text = stringResource(R.string.profile_safety_number),
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberMutedForeground,
                    )
                    AmberText(
                        text = peer.safetyNumber,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = AmberMono,
                        ),
                        color = AmberForeground,
                        maxLines = 2,
                    )
                }
            }

            when (peer.trustState) {
                TrustState.KEY_CHANGED -> {
                    AmberText(
                        text = stringResource(R.string.profile_accept_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberDestructive,
                    )
                    AmberButton(
                        text = peer.actionText(),
                        onClick = { onAcceptPeerKey(peer.peerId) },
                        enabled = trustActionsAvailable && !peer.actionPending,
                    )
                }

                TrustState.UNKNOWN -> {
                    AmberButton(
                        text = peer.actionText(),
                        onClick = { onAcceptPeerKey(peer.peerId) },
                        enabled = trustActionsAvailable && !peer.actionPending,
                    )
                }

                TrustState.TOFU_ACCEPTED -> {
                    AmberButton(
                        text = peer.actionText(),
                        onClick = { onVerifyPeer(peer.peerId) },
                        enabled = !peer.actionPending,
                        style = AmberButtonStyle.Ghost,
                    )
                }

                TrustState.VERIFIED -> Unit
            }

            if (!trustActionsAvailable &&
                (peer.trustState == TrustState.KEY_CHANGED ||
                    peer.trustState == TrustState.UNKNOWN)
            ) {
                AmberText(
                    text = stringResource(R.string.profile_network_offline_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberMutedForeground,
                )
            }
        }
    }
}

@Composable
private fun ProfilePeer.actionText(): String = stringResource(
    when {
        actionPending -> R.string.profile_action_pending
        trustState == TrustState.TOFU_ACCEPTED -> R.string.profile_verify
        else -> R.string.profile_accept
    },
)

@Composable
private fun TrustBadge(trustState: TrustState) {
    Box(
        modifier = Modifier
            .background(color = AmberMuted, shape = AmberShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        AmberText(
            text = stringResource(trustState.labelRes()),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = AmberMono),
            color = trustState.color(),
            maxLines = 1,
        )
    }
}

@Composable
private fun ProfileLinkCard(
    title: String,
    body: String,
    onClick: () -> Unit,
) {
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
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                AmberText(
                    text = body,
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

private fun TrustState.labelRes(): Int = when (this) {
    TrustState.UNKNOWN -> R.string.profile_trust_unknown
    TrustState.TOFU_ACCEPTED -> R.string.profile_trust_tofu
    TrustState.VERIFIED -> R.string.profile_trust_verified
    TrustState.KEY_CHANGED -> R.string.profile_trust_changed
}

private fun TrustState.color() = when (this) {
    TrustState.UNKNOWN -> AmberMutedForeground
    TrustState.TOFU_ACCEPTED -> AmberForeground
    TrustState.VERIFIED -> AmberPrimary
    TrustState.KEY_CHANGED -> AmberDestructive
}

private fun ProfileNotice.messageRes(): Int = when (this) {
    ProfileNotice.NICKNAME_SAVED -> R.string.profile_nickname_saved
    ProfileNotice.NICKNAME_SAVE_FAILED -> R.string.profile_nickname_save_failed
    ProfileNotice.TRUST_ACTION_FAILED -> R.string.profile_trust_action_failed
    ProfileNotice.NETWORK_OFFLINE -> R.string.profile_action_network_offline
}

private fun ProfileNotice.color() = when (this) {
    ProfileNotice.NICKNAME_SAVED -> AmberPrimary
    ProfileNotice.NICKNAME_SAVE_FAILED,
    ProfileNotice.TRUST_ACTION_FAILED,
    -> AmberDestructive

    ProfileNotice.NETWORK_OFFLINE -> AmberMutedForeground
}

private fun NicknameError.messageRes(): Int = when (this) {
    NicknameError.EMPTY -> R.string.nickname_error_empty
    NicknameError.TOO_LONG -> R.string.nickname_error_too_long
    NicknameError.CONTROL_CHARS -> R.string.nickname_error_control
}
