package com.sunshine.mesh.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sunshine.mesh.coordinator.ChatTransport
import com.sunshine.mesh.coordinator.ConnectResult
import com.sunshine.mesh.coordinator.MeshEvent
import com.sunshine.mesh.coordinator.PendingReason
import com.sunshine.mesh.coordinator.SendError
import com.sunshine.mesh.coordinator.SendTextResult
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.data.chat.ChatDataSource
import com.sunshine.mesh.domain.model.ChatMessage
import com.sunshine.mesh.domain.model.Peer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

class ChatViewModel(
    private val peerId: PeerId,
    private val dataSource: ChatDataSource,
    private val transport: StateFlow<ChatTransport?>,
) : ViewModel() {

    private val draft = MutableStateFlow("")
    private val sending = MutableStateFlow(false)
    private val notice = MutableStateFlow<ChatNotice?>(null)
    private val connection = MutableStateFlow(ConnectionState())
    private val scrollSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var connectJob: Job? = null

    /** Emitted when an incoming message should scroll the list to the bottom. */
    val events: SharedFlow<Unit> = scrollSignals.asSharedFlow()

    private val content = combine(
        dataSource.observePeer(peerId),
        dataSource.observeConversation(peerId),
        transport,
    ) { peer, messages, activeTransport ->
        Content(
            peer = peer,
            messages = messages,
            transportAvailable = activeTransport != null,
        )
    }

    private val readyState: Flow<ChatUiState> = combine(
        content,
        draft,
        sending,
        notice,
        connection,
    ) { content, draft, sending, notice, connection ->
        ChatUiState.Ready(
            peer = content.peer,
            messages = content.messages,
            draft = draft,
            sending = sending,
            canSend = content.transportAvailable && draft.isNotBlank() && !sending,
            notice = notice ?: if (!content.transportAvailable) {
                ChatNotice.NETWORK_OFFLINE
            } else {
                null
            },
            connecting = connection.connecting,
            connectFailed = connection.failed,
        )
    }

    val state: StateFlow<ChatUiState> = readyState.catch { error ->
        if (error is CancellationException) throw error
        Timber.w("Chat load failed")
        emit(ChatUiState.Failed)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = ChatUiState.Loading,
    )

    init {
        observeTransportEvents()
        viewModelScope.launch {
            // The service may bind a moment after navigation.
            transport.filterNotNull().first()
            connect()
        }
    }

    fun onDraftChange(value: String) {
        draft.value = value
        if (notice.value != null) notice.value = null
    }

    fun send() {
        val text = draft.value.trim()
        if (text.isEmpty() || sending.value) return

        val activeTransport = transport.value
        if (activeTransport == null) {
            notice.value = ChatNotice.NETWORK_OFFLINE
            return
        }

        draft.value = ""
        notice.value = null
        sending.value = true
        viewModelScope.launch {
            try {
                notice.value = when (val result = activeTransport.sendText(peerId, text)) {
                    is SendTextResult.Sent -> null
                    is SendTextResult.Pending -> result.reason.toNotice()
                    is SendTextResult.Failed -> {
                        if (result.messageId == null && draft.value.isBlank()) {
                            draft.value = text
                        }
                        result.error.toNotice()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (draft.value.isBlank()) draft.value = text
                notice.value = ChatNotice.SEND_FAILED
                Timber.w("Chat send failed: peerId=%s", peerId)
            } finally {
                sending.value = false
            }
        }
    }

    /** Called on entry and from the retry button in the connection banner. */
    fun connect() {
        val activeTransport = transport.value
        if (activeTransport == null) {
            connection.value = ConnectionState(failed = true)
            return
        }
        if (connectJob?.isActive == true) return

        connection.value = ConnectionState(connecting = true)
        connectJob = viewModelScope.launch {
            val result = try {
                activeTransport.connect(peerId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w("Chat connect crashed: peerId=%s", peerId)
                ConnectResult.Failed(TransportError.INTERNAL)
            }

            connection.value = ConnectionState(
                failed = result != ConnectResult.Connected,
            )
            when (result) {
                ConnectResult.Connected -> Unit
                ConnectResult.Timeout ->
                    Timber.w("Chat connect timeout: peerId=%s", peerId)

                is ConnectResult.Failed -> Timber.w(
                    "Chat connect failed: peerId=%s code=%s",
                    peerId,
                    result.error,
                )
            }
        }
    }

    private fun observeTransportEvents() {
        viewModelScope.launch {
            transport
                .filterNotNull()
                .flatMapLatest { it.events }
                .collect { event ->
                    when (event) {
                        is MeshEvent.MessageReceived -> {
                            if (event.peerId == peerId) scrollSignals.tryEmit(Unit)
                        }

                        // Room already stores the new status; this event is only
                        // logged because observeConversation drives the UI.
                        is MeshEvent.DeliveryChanged ->
                            Timber.d("Delivery event: msgId=%s", event.messageId)

                        // Trust decisions belong to the profile/network UI.
                        is MeshEvent.TrustRequired,
                        is MeshEvent.KeyChanged,
                        -> Unit
                    }
                }
        }
    }

    private data class Content(
        val peer: Peer?,
        val messages: List<ChatMessage>,
        val transportAvailable: Boolean,
    )

    private data class ConnectionState(
        val connecting: Boolean = false,
        val failed: Boolean = false,
    )

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(
            peerId: PeerId,
            dataSource: ChatDataSource,
            transport: StateFlow<ChatTransport?>,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    peerId = peerId,
                    dataSource = dataSource,
                    transport = transport,
                )
            }
        }
    }
}

private fun SendError.toNotice(): ChatNotice = when (this) {
    SendError.INVALID_TEXT -> ChatNotice.INVALID_TEXT
    SendError.MESSAGE_TOO_LARGE -> ChatNotice.TOO_LARGE
    SendError.STORAGE_FAILED -> ChatNotice.STORAGE_FAILED
    SendError.CRYPTO_FAILED -> ChatNotice.CRYPTO_FAILED
    SendError.INTERNAL -> ChatNotice.SEND_FAILED
}

private fun PendingReason.toNotice(): ChatNotice = when (this) {
    PendingReason.AWAITING_PEER_KEY -> ChatNotice.AWAITING_PEER_KEY
    PendingReason.TRUST_REQUIRED -> ChatNotice.TRUST_REQUIRED
    PendingReason.KEY_CHANGED -> ChatNotice.KEY_CHANGED
    PendingReason.NO_LINK -> ChatNotice.NO_LINK
}
