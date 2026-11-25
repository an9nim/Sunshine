package com.sunshine.mesh.ui.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sunshine.mesh.data.chat.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class ChatListViewModel(
    private val repository: ChatRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ChatListUiState>(ChatListUiState.Loading)
    val state: StateFlow<ChatListUiState> = _state.asStateFlow()

    private var observeJob: Job? = null

    init {
        observe()
    }

    fun retry() {
        observe()
    }

    private fun observe() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            _state.value = ChatListUiState.Loading
            try {
                repository.observeChats().collect { chats ->
                    _state.value = ChatListUiState.Ready(chats)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w("Chat list load failed")
                _state.value = ChatListUiState.Failed
            }
        }
    }

    companion object {
        fun factory(repository: ChatRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ChatListViewModel(repository)
                }
            }
    }
}
