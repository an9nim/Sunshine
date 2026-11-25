package com.sunshine.mesh.ui.chatlist

import com.sunshine.mesh.domain.model.ChatSummary

sealed interface ChatListUiState {
    data object Loading : ChatListUiState
    data class Ready(val chats: List<ChatSummary>) : ChatListUiState
    data object Failed : ChatListUiState
}
