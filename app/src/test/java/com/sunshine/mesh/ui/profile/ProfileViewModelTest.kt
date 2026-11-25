package com.sunshine.mesh.ui.profile

import com.sunshine.mesh.coordinator.TrustAcceptor
import com.sunshine.mesh.core.crypto.SafetyNumber
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.data.peer.PeerTrustActions
import com.sunshine.mesh.data.profile.OwnProfile
import com.sunshine.mesh.data.profile.ProfileDataSource
import com.sunshine.mesh.data.profile.UpdateNicknameResult
import com.sunshine.mesh.domain.model.Peer
import com.sunshine.mesh.domain.model.Profile
import com.sunshine.mesh.domain.model.TrustState
import com.sunshine.mesh.domain.validation.NicknameError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

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
    fun loadsProfilePeersAndSafetyNumbers() = runTest(dispatcher) {
        val ownKey = key(1)
        val keyA = key(2)
        val keyB = key(3)
        val source = FakeProfileSource(ownProfile(nickname = "Alice", publicKey = ownKey))
        val actions = FakePeerActions(
            listOf(
                peer(PEER_B, "Zed", keyB, TrustState.TOFU_ACCEPTED),
                peer(PEER_A, "Bob", keyA, TrustState.VERIFIED),
            ),
        )
        val viewModel = createViewModel(source, actions)
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        val ready = viewModel.ready()
        assertEquals("Alice", ready.nicknameDraft)
        assertEquals(listOf("Bob", "Zed"), ready.peers.map { it.nickname })
        assertEquals(SafetyNumber.of(ownKey, keyA), ready.peers[0].safetyNumber)
        assertEquals(SafetyNumber.of(ownKey, keyB), ready.peers[1].safetyNumber)
        assertTrue(ready.trustActionsAvailable)
    }

    @Test
    fun invalidNicknameIsRejectedWithoutSaving() = runTest(dispatcher) {
        val source = FakeProfileSource(ownProfile())
        val viewModel = createViewModel(source, FakePeerActions(emptyList()))
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.onNicknameChange("   ")
        viewModel.saveNickname()
        advanceUntilIdle()

        assertEquals(NicknameError.EMPTY, viewModel.ready().nicknameError)
        assertTrue(source.updates.isEmpty())
    }

    @Test
    fun saveNicknameUpdatesDraftAndProfile() = runTest(dispatcher) {
        val source = FakeProfileSource(ownProfile(nickname = "Alice"))
        source.updateResult = UpdateNicknameResult.Updated(
            ownProfile(nickname = "Bob").profile,
        )
        val viewModel = createViewModel(source, FakePeerActions(emptyList()))
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.onNicknameChange("  Bob  ")
        viewModel.saveNickname()
        advanceUntilIdle()

        assertEquals(listOf("Bob"), source.updates)
        assertEquals("Bob", viewModel.ready().profile.nickname)
        assertEquals("Bob", viewModel.ready().nicknameDraft)
        assertEquals(ProfileNotice.NICKNAME_SAVED, viewModel.ready().notice)
    }

    @Test
    fun saveNicknameFailureShowsNotice() = runTest(dispatcher) {
        val source = FakeProfileSource(ownProfile(nickname = "Alice"))
        source.updateResult = UpdateNicknameResult.Failed(IllegalStateException("disk"))
        val viewModel = createViewModel(source, FakePeerActions(emptyList()))
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.onNicknameChange("Bob")
        viewModel.saveNickname()
        advanceUntilIdle()

        assertEquals(ProfileNotice.NICKNAME_SAVE_FAILED, viewModel.ready().notice)
    }

    @Test
    fun acceptPeerKeyUsesNewestStoredKey() = runTest(dispatcher) {
        val keyA = key(2)
        val actions = FakePeerActions(
            listOf(peer(PEER_A, "Bob", keyA, TrustState.KEY_CHANGED)),
        )
        val acceptor = FakeTrustAcceptor()
        val viewModel = createViewModel(
            FakeProfileSource(ownProfile()),
            actions,
            MutableStateFlow<TrustAcceptor?>(acceptor),
        )
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.acceptPeerKey(PEER_A)
        advanceUntilIdle()

        assertEquals(1, acceptor.calls.size)
        assertEquals(PEER_A, acceptor.calls[0].first)
        assertArrayEquals(keyA, acceptor.calls[0].second)
        assertNull(viewModel.ready().notice)
    }

    @Test
    fun acceptWithoutCoordinatorShowsOfflineNotice() = runTest(dispatcher) {
        val actions = FakePeerActions(
            listOf(peer(PEER_A, "Bob", key(2), TrustState.KEY_CHANGED)),
        )
        val viewModel = createViewModel(
            FakeProfileSource(ownProfile()),
            actions,
            MutableStateFlow<TrustAcceptor?>(null),
        )
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.acceptPeerKey(PEER_A)
        advanceUntilIdle()

        assertEquals(ProfileNotice.NETWORK_OFFLINE, viewModel.ready().notice)
    }

    @Test
    fun verifyPeerDelegatesWithNewestKey() = runTest(dispatcher) {
        val keyA = key(2)
        val actions = FakePeerActions(
            listOf(peer(PEER_A, "Bob", keyA, TrustState.TOFU_ACCEPTED)),
        )
        val viewModel = createViewModel(FakeProfileSource(ownProfile()), actions)
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.verifyPeer(PEER_A)
        advanceUntilIdle()

        assertEquals(1, actions.verified.size)
        assertEquals(PEER_A, actions.verified[0].first)
        assertArrayEquals(keyA, actions.verified[0].second)
    }

    @Test
    fun missingProfileShowsFailedState() = runTest(dispatcher) {
        val viewModel = createViewModel(FakeProfileSource(null), FakePeerActions(emptyList()))
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        assertEquals(ProfileUiState.Failed, viewModel.state.value)
    }

    private fun createViewModel(
        source: ProfileDataSource,
        actions: PeerTrustActions,
        acceptor: MutableStateFlow<TrustAcceptor?> = MutableStateFlow(FakeTrustAcceptor()),
    ): ProfileViewModel = ProfileViewModel(
        profileSource = source,
        peerActions = actions,
        trustAcceptor = acceptor,
    )

    private fun ProfileViewModel.ready(): ProfileUiState.Ready =
        state.value as ProfileUiState.Ready

    private class FakeProfileSource(
        private val own: OwnProfile?,
    ) : ProfileDataSource {
        var updateResult: UpdateNicknameResult? = null
        val updates = mutableListOf<String>()

        override suspend fun loadOwnProfile(): OwnProfile? = own

        override suspend fun updateNickname(nicknameInput: String): UpdateNicknameResult {
            updates += nicknameInput
            return updateResult ?: UpdateNicknameResult.Updated(
                requireNotNull(own).profile.copy(nickname = nicknameInput),
            )
        }
    }

    private class FakePeerActions(
        initial: List<Peer>,
    ) : PeerTrustActions {
        private val peers = MutableStateFlow(initial)
        val verified = mutableListOf<Pair<PeerId, ByteArray>>()

        override fun observeAll(): Flow<List<Peer>> = peers

        override suspend fun getPeer(peerId: PeerId): Peer? =
            peers.value.firstOrNull { it.peerId == peerId }

        override suspend fun verifyManually(
            peerId: PeerId,
            expectedPublicKey: ByteArray,
        ): Boolean {
            verified += peerId to expectedPublicKey
            return true
        }
    }

    private class FakeTrustAcceptor : TrustAcceptor {
        val calls = mutableListOf<Pair<PeerId, ByteArray>>()

        override suspend fun acceptTrust(
            peerId: PeerId,
            expectedPublicKey: ByteArray,
        ): Boolean {
            calls += peerId to expectedPublicKey
            return true
        }
    }

    private companion object {
        val PEER_A = PeerId.parse("aaaaaaaaaaaaaaaa")
        val PEER_B = PeerId.parse("bbbbbbbbbbbbbbbb")
        const val OWN_PEER_ID = "cccccccccccccccc"

        fun key(value: Int): ByteArray = ByteArray(32) { value.toByte() }

        fun ownProfile(
            nickname: String = "Alice",
            publicKey: ByteArray = key(1),
        ): OwnProfile = OwnProfile(
            profile = Profile(
                peerId = PeerId.parse(OWN_PEER_ID),
                nickname = nickname,
                createdAt = 0,
            ),
            publicKey = publicKey,
        )

        fun peer(
            peerId: PeerId,
            nickname: String,
            publicKey: ByteArray,
            trustState: TrustState,
        ): Peer = Peer(
            peerId = peerId,
            nickname = nickname,
            firstSeenPublicKey = publicKey.copyOf(),
            lastSeenPublicKey = publicKey.copyOf(),
            trustState = trustState,
            firstSeenAt = 0,
            lastSeenAt = 0,
            verifiedAt = null,
        )
    }
}
