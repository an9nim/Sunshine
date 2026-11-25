package com.sunshine.mesh.ui.chatlist

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sunshine.mesh.R
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.domain.model.ChatSummary
import com.sunshine.mesh.domain.model.MessageStatus
import com.sunshine.mesh.domain.model.TrustState
import com.sunshine.mesh.ui.components.AmberAvatar
import com.sunshine.mesh.ui.components.AmberCard
import com.sunshine.mesh.ui.components.AmberEmptyState
import com.sunshine.mesh.ui.components.AmberErrorState
import com.sunshine.mesh.ui.components.AmberLoadingState
import com.sunshine.mesh.ui.components.AmberText
import com.sunshine.mesh.ui.components.AmberTextButton
import com.sunshine.mesh.ui.components.AmberTopBar
import com.sunshine.mesh.ui.components.MessageStatusMark
import com.sunshine.mesh.ui.theme.AmberBackground
import com.sunshine.mesh.ui.theme.AmberBorder
import com.sunshine.mesh.ui.theme.AmberDestructive
import com.sunshine.mesh.ui.theme.AmberMono
import com.sunshine.mesh.ui.theme.AmberMutedForeground
import com.sunshine.mesh.ui.theme.AmberPrimary
import com.sunshine.mesh.ui.theme.MaginerBricks
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ChatListScreen(
    state: ChatListUiState,
    onChatClick: (PeerId) -> Unit,
    onNetworkClick: () -> Unit,
    onProfileClick: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmberBackground)
            .systemBarsPadding(),
    ) {
        AmberTopBar(
            title = stringResource(R.string.app_name),
            titleFontFamily = MaginerBricks,
        ) {
            AmberTextButton(
                text = stringResource(R.string.chat_list_network),
                onClick = onNetworkClick,
            )
            AmberTextButton(
                text = stringResource(R.string.chat_list_profile),
                onClick = onProfileClick,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AmberBorder),
        )

        when (state) {
            ChatListUiState.Loading -> AmberLoadingState(
                text = stringResource(R.string.chat_list_loading),
            )

            ChatListUiState.Failed -> AmberErrorState(
                title = stringResource(R.string.chat_list_error_title),
                body = stringResource(R.string.chat_list_error_body),
                actionText = stringResource(R.string.chat_list_retry),
                onAction = onRetry,
            )

            is ChatListUiState.Ready -> {
                if (state.chats.isEmpty()) {
                    AmberEmptyState(
                        title = stringResource(R.string.chat_list_empty_title),
                        body = stringResource(R.string.chat_list_empty_body),
                        actionText = stringResource(R.string.chat_list_empty_action),
                        onAction = onNetworkClick,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.chats, key = { it.peerId.value }) { chat ->
                            ChatRow(
                                chat = chat,
                                onClick = { onChatClick(chat.peerId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatRow(
    chat: ChatSummary,
    onClick: () -> Unit,
) {
    val displayName = chat.nickname.ifBlank {
        stringResource(R.string.chat_unknown_peer, chat.peerId.value.take(8))
    }
    val preview = when {
        chat.lastMessageText == null -> stringResource(R.string.chat_no_messages)
        chat.lastMessageOutgoing ->
            stringResource(R.string.chat_you_prefix, chat.lastMessageText)

        else -> chat.lastMessageText
    }

    AmberCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AmberAvatar(initial = displayName.take(1).uppercase(Locale.getDefault()))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AmberText(
                        text = displayName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chat.lastMessageAt != null) {
                        Spacer(Modifier.width(8.dp))
                        AmberText(
                            text = formatChatTime(chat.lastMessageAt),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = AmberMono,
                            ),
                            color = AmberMutedForeground,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AmberText(
                        text = preview,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AmberMutedForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chat.lastMessageOutgoing) {
                        chat.lastMessageStatus?.let { status ->
                            Spacer(Modifier.width(6.dp))
                            val statusColor = when (status) {
                                MessageStatus.DELIVERED -> AmberPrimary
                                MessageStatus.FAILED -> AmberDestructive
                                else -> AmberMutedForeground
                            }
                            MessageStatusMark(status = status, color = statusColor)
                        }
                    }
                    TrustMark(chat.trustState)
                }
            }
        }
    }
}

@Composable
private fun TrustMark(trustState: TrustState) {
    val (symbol, color) = when (trustState) {
        TrustState.UNKNOWN ->
            stringResource(R.string.chat_trust_unknown) to AmberMutedForeground

        TrustState.VERIFIED ->
            stringResource(R.string.chat_trust_verified) to AmberPrimary

        TrustState.KEY_CHANGED ->
            stringResource(R.string.chat_trust_changed) to AmberDestructive

        TrustState.TOFU_ACCEPTED -> return
    }
    Spacer(Modifier.width(6.dp))
    AmberText(
        text = symbol,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = AmberMono),
        color = color,
        maxLines = 1,
    )
}

private fun formatChatTime(timestamp: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "dd.MM.yy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}
