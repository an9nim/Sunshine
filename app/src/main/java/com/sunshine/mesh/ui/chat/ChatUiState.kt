package com.sunshine.mesh.ui.chat

import com.sunshine.mesh.domain.model.ChatMessage
import com.sunshine.mesh.domain.model.Peer

sealed interface ChatUiState {

    data object Loading : ChatUiState

    data class Ready(
        val peer: Peer?,
        val messages: List<ChatMessage>,
        val draft: String,
        val sending: Boolean,
        val canSend: Boolean,
        val notice: ChatNotice?,
        val connecting: Boolean = false,
        val connectFailed: Boolean = false,
    ) : ChatUiState

    data object Failed : ChatUiState
}

/** Transient line above the input bar; mapped to a string by the screen. */
enum class ChatNotice {
    NETWORK_OFFLINE,
    INVALID_TEXT,
    TOO_LARGE,
    STORAGE_FAILED,
    CRYPTO_FAILED,
    SEND_FAILED,
    AWAITING_PEER_KEY,
    TRUST_REQUIRED,
    KEY_CHANGED,
    NO_LINK,
}
