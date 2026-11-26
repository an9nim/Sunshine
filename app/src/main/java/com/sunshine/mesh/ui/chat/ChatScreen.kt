package com.sunshine.mesh.ui.chat

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sunshine.mesh.R
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.domain.model.ChatMessage
import com.sunshine.mesh.domain.model.Peer
import com.sunshine.mesh.ui.components.AmberButton
import com.sunshine.mesh.ui.components.AmberErrorState
import com.sunshine.mesh.ui.components.AmberLoadingState
import com.sunshine.mesh.ui.components.AmberText
import com.sunshine.mesh.ui.components.AmberTextField
import com.sunshine.mesh.ui.components.AmberTextButton
import com.sunshine.mesh.ui.components.AmberTopBar
import com.sunshine.mesh.ui.components.MessageStatusMark
import com.sunshine.mesh.ui.theme.AmberBackground
import com.sunshine.mesh.ui.theme.AmberBorder
import com.sunshine.mesh.ui.theme.AmberCard
import com.sunshine.mesh.ui.theme.AmberDestructive
import com.sunshine.mesh.ui.theme.AmberForeground
import com.sunshine.mesh.ui.theme.AmberMono
import com.sunshine.mesh.ui.theme.AmberMutedForeground
import com.sunshine.mesh.ui.theme.AmberPrimary
import com.sunshine.mesh.ui.theme.AmberPrimaryForeground
import com.sunshine.mesh.ui.theme.AmberShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow

@Composable
fun ChatScreen(
    peerId: PeerId,
    state: ChatUiState,
    scrollEvents: Flow<Unit>,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetryConnect: () -> Unit,
    onBack: () -> Unit,
    onNetworkClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val peer = (state as? ChatUiState.Ready)?.peer
    val title = peerTitle(peer, peerId)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmberBackground)
            .systemBarsPadding()
            .imePadding(),
    ) {
        AmberTopBar(
            title = title,
            subtitle = stringResource(R.string.chat_peer_id, peerId.value.take(8)),
            subtitleMono = true,
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

        if (state is ChatUiState.Ready && (state.connecting || state.connectFailed)) {
            ConnectionBanner(
                connecting = state.connecting,
                failed = state.connectFailed,
                onRetry = onRetryConnect,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (state) {
                ChatUiState.Loading -> AmberLoadingState(
                    text = stringResource(R.string.chat_loading),
                )

                ChatUiState.Failed -> AmberErrorState(
                    title = stringResource(R.string.chat_load_error_title),
                    body = stringResource(R.string.chat_load_error_body),
                    actionText = stringResource(R.string.common_back),
                    onAction = onBack,
                )

                is ChatUiState.Ready -> {
                    if (state.messages.isEmpty()) {
                        EmptyChat()
                    } else {
                        MessageList(
                            messages = state.messages,
                            scrollEvents = scrollEvents,
                        )
                    }
                }
            }
        }

        if (state is ChatUiState.Ready) {
            ChatNoticeBar(
                notice = state.notice,
                onNetworkClick = onNetworkClick,
            )
            ChatInputBar(
                state = state,
                onDraftChange = onDraftChange,
                onSend = onSend,
            )
        }
    }
}

@Composable
private fun ConnectionBanner(
    connecting: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AmberCard)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            connecting -> AmberText(
                text = stringResource(R.string.chat_connecting),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = AmberMono,
                ),
                color = AmberMutedForeground,
            )

            failed -> {
                AmberText(
                    text = stringResource(R.string.chat_connect_failed),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = AmberDestructive,
                )
                AmberTextButton(
                    text = stringResource(R.string.chat_connect_retry),
                    onClick = onRetry,
                )
            }
        }
    }
}

@Composable
private fun peerTitle(peer: Peer?, peerId: PeerId): String =
    peer?.nickname?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.chat_unknown_peer, peerId.value.take(8))

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    scrollEvents: Flow<Unit>,
) {
    val listState = rememberLazyListState()
    val messageCount by rememberUpdatedState(messages.size)

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    LaunchedEffect(scrollEvents) {
        scrollEvents.collect {
            if (messageCount > 0) {
                listState.animateScrollToItem(messageCount - 1)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.messageId.value }) { message ->
            MessageBubble(message)
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val outgoing = message.outgoing
    val bubbleColor = if (outgoing) AmberPrimary else AmberCard
    val bubbleBorder = if (outgoing) AmberPrimary else AmberBorder
    val textColor = if (outgoing) AmberPrimaryForeground else AmberForeground
    val footerColor = if (outgoing) {
        AmberPrimaryForeground.copy(alpha = 0.8f)
    } else {
        AmberMutedForeground
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(AmberShape)
                .background(bubbleColor)
                .border(width = 1.dp, color = bubbleBorder, shape = AmberShape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            AmberText(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AmberText(
                    text = formatMessageTime(message.createdAt),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = AmberMono,
                    ),
                    color = footerColor,
                    maxLines = 1,
                )
                if (outgoing) {
                    MessageStatusMark(
                        status = message.status,
                        color = footerColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyChat() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AmberText(
            text = stringResource(R.string.chat_empty_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        AmberText(
            text = stringResource(R.string.chat_empty_body),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = AmberMutedForeground,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChatNoticeBar(
    notice: ChatNotice?,
    onNetworkClick: () -> Unit,
) {
    if (notice == null) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AmberText(
            text = stringResource(notice.messageRes()),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = notice.color(),
        )
        if (notice == ChatNotice.NETWORK_OFFLINE) {
            AmberTextButton(
                text = stringResource(R.string.chat_list_network),
                onClick = onNetworkClick,
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    state: ChatUiState.Ready,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AmberTextField(
            value = state.draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = stringResource(R.string.chat_input_hint),
            singleLine = false,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        AmberButton(
            text = stringResource(R.string.chat_send),
            onClick = onSend,
            enabled = state.canSend,
        )
    }
}

private fun ChatNotice.messageRes(): Int = when (this) {
    ChatNotice.NETWORK_OFFLINE -> R.string.chat_notice_network_offline
    ChatNotice.INVALID_TEXT -> R.string.chat_notice_invalid
    ChatNotice.TOO_LARGE -> R.string.chat_notice_too_large
    ChatNotice.STORAGE_FAILED -> R.string.chat_notice_storage
    ChatNotice.CRYPTO_FAILED -> R.string.chat_notice_crypto
    ChatNotice.SEND_FAILED -> R.string.chat_notice_failed
    ChatNotice.AWAITING_PEER_KEY -> R.string.chat_notice_awaiting_key
    ChatNotice.TRUST_REQUIRED -> R.string.chat_notice_trust_required
    ChatNotice.KEY_CHANGED -> R.string.chat_notice_key_changed
    ChatNotice.NO_LINK -> R.string.chat_notice_no_link
}

private fun ChatNotice.color(): Color = when (this) {
    ChatNotice.INVALID_TEXT,
    ChatNotice.TOO_LARGE,
    ChatNotice.STORAGE_FAILED,
    ChatNotice.CRYPTO_FAILED,
    ChatNotice.SEND_FAILED,
    ChatNotice.KEY_CHANGED,
    -> AmberDestructive

    else -> AmberMutedForeground
}

private fun formatMessageTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
