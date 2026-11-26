package com.sunshine.mesh.ui.chat

import com.sunshine.mesh.coordinator.ChatTransport
import com.sunshine.mesh.coordinator.ConnectResult
import com.sunshine.mesh.coordinator.MeshEvent
import com.sunshine.mesh.coordinator.PendingReason
import com.sunshine.mesh.coordinator.SendError
import com.sunshine.mesh.coordinator.SendTextResult
import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.data.chat.ChatDataSource
import com.sunshine.mesh.domain.model.ChatMessage
import com.sunshine.mesh.domain.model.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sendDelegatesToTransportAndClearsDraft() = runTest(dispatcher) {
        val transport = FakeChatTransport(SendTextResult.Sent(MESSAGE_ID))
        val viewModel = createViewModel(transport)
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.onDraftChange("hello")
        viewModel.send()
        advanceUntilIdle()

        assertEquals(listOf("hello"), transport.sentTexts)
        val state = viewModel.state.value as ChatUiState.Ready
        assertEquals("", state.draft)
        assertNull(state.notice)
    }

    @Test
    fun blankDraftIsNotSent() = runTest(dispatcher) {
        val transport = FakeChatTransport(SendTextResult.Sent(MESSAGE_ID))
        val viewModel = createViewModel(transport)
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.onDraftChange("   ")
        viewModel.send()
        advanceUntilIdle()

        assertTrue(transport.sentTexts.isEmpty())
    }

    @Test
    fun failedSendRestoresDraftAndShowsNotice() = runTest(dispatcher) {
        val transport = FakeChatTransport(
            SendTextResult.Failed(null, SendError.STORAGE_FAILED),
        )
        val viewModel = createViewModel(transport)
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.onDraftChange("hello")
        viewModel.send()
        advanceUntilIdle()

        val state = viewModel.state.value as ChatUiState.Ready
        assertEquals("hello", state.draft)
        assertEquals(ChatNotice.STORAGE_FAILED, state.notice)
    }

    @Test
    fun pendingReasonIsMappedToNotice() = runTest(dispatcher) {
        val transport = FakeChatTransport(
            SendTextResult.Pending(MESSAGE_ID, PendingReason.NO_LINK),
        )
        val viewModel = createViewModel(transport)
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.onDraftChange("hello")
        viewModel.send()
        advanceUntilIdle()

        val state = viewModel.state.value as ChatUiState.Ready
        assertEquals(ChatNotice.NO_LINK, state.notice)
    }

    @Test
    fun missingTransportShowsOfflineNotice() = runTest(dispatcher) {
        val viewModel = createViewModel(transport = null)
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.onDraftChange("hello")
        viewModel.send()
        advanceUntilIdle()

        val state = viewModel.state.value as ChatUiState.Ready
        assertEquals("hello", state.draft)
        assertEquals(ChatNotice.NETWORK_OFFLINE, state.notice)
    }

    @Test
    fun connectFailureIsShownInState() = runTest(dispatcher) {
        val transport = FakeChatTransport(
            result = SendTextResult.Sent(MESSAGE_ID),
            connectResult = ConnectResult.Timeout,
        )
        val viewModel = createViewModel(transport)
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        val state = viewModel.state.value as ChatUiState.Ready
        assertTrue(state.connectFailed)
        assertFalse(state.connecting)
        assertEquals(1, transport.connects)
    }

    @Test
    fun retryAfterConnectFailureRunsConnectAgain() = runTest(dispatcher) {
        val transport = FakeChatTransport(
            result = SendTextResult.Sent(MESSAGE_ID),
            connectResult = ConnectResult.Timeout,
        )
        val viewModel = createViewModel(transport)
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        transport.connectResult = ConnectResult.Connected
        viewModel.connect()
        advanceUntilIdle()

        val state = viewModel.state.value as ChatUiState.Ready
        assertFalse(state.connectFailed)
        assertFalse(state.connecting)
        assertEquals(2, transport.connects)
    }

    private fun createViewModel(transport: ChatTransport?): ChatViewModel =
        ChatViewModel(
            peerId = PEER_ID,
            dataSource = FakeChatDataSource,
            transport = MutableStateFlow(transport),
        )

    private object FakeChatDataSource : ChatDataSource {
        override fun observePeer(peerId: PeerId): Flow<Peer?> = flowOf(null)

        override fun observeConversation(peerId: PeerId): Flow<List<ChatMessage>> =
            flowOf(emptyList())
    }

    private class FakeChatTransport(
        private val result: SendTextResult,
        var connectResult: ConnectResult = ConnectResult.Connected,
    ) : ChatTransport {

        override val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 8)
        val sentTexts = mutableListOf<String>()
        var connects = 0

        override suspend fun connect(peerId: PeerId): ConnectResult {
            connects += 1
            return connectResult
        }

        override suspend fun sendText(peerId: PeerId, text: String): SendTextResult {
            sentTexts += text
            return result
        }
    }

    private companion object {
        val PEER_ID = PeerId.parse("aaaaaaaaaaaaaaaa")
        val MESSAGE_ID = MessageId.parse("00112233445566778899aabbccddeeff")
    }
}
