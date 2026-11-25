package com.sunshine.mesh.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sunshine.mesh.coordinator.TrustAcceptor
import com.sunshine.mesh.core.crypto.SafetyNumber
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.data.peer.PeerTrustActions
import com.sunshine.mesh.data.profile.ProfileDataSource
import com.sunshine.mesh.data.profile.UpdateNicknameResult
import com.sunshine.mesh.domain.model.Profile
import com.sunshine.mesh.domain.validation.Nickname
import com.sunshine.mesh.domain.validation.NicknameError
import com.sunshine.mesh.domain.validation.NicknameResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

class ProfileViewModel(
    private val profileSource: ProfileDataSource,
    private val peerActions: PeerTrustActions,
    private val trustAcceptor: StateFlow<TrustAcceptor?>,
) : ViewModel() {

    private val identity = MutableStateFlow<IdentityState>(IdentityState.Loading)
    private val local = MutableStateFlow(LocalState())

    private var loadJob: Job? = null

    private val readyState = combine(
        identity,
        peerActions.observeAll(),
        local,
        trustAcceptor,
    ) { identity, peers, local, acceptor ->
        when (identity) {
            IdentityState.Loading -> ProfileUiState.Loading
            IdentityState.Failed -> ProfileUiState.Failed
            is IdentityState.Loaded -> ProfileUiState.Ready(
                profile = identity.profile,
                nicknameDraft = local.nicknameDraft,
                nicknameError = local.nicknameError,
                savingNickname = local.savingNickname,
                peers = peers
                    .sortedBy { it.nickname.ifBlank { it.peerId.value } }
                    .map { peer ->
                        ProfilePeer(
                            peerId = peer.peerId,
                            nickname = peer.nickname,
                            trustState = peer.trustState,
                            safetyNumber = safetyNumber(identity.publicKey, peer.lastSeenPublicKey),
                            actionPending = local.pendingPeer == peer.peerId,
                        )
                    },
                trustActionsAvailable = acceptor != null,
                notice = local.notice,
            )
        }
    }

    val state: StateFlow<ProfileUiState> = readyState.catch { error ->
        if (error is CancellationException) throw error
        Timber.w("Profile state failed")
        emit(ProfileUiState.Failed)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = ProfileUiState.Loading,
    )

    init {
        load()
    }

    fun retry() {
        load()
    }

    fun onNicknameChange(value: String) {
        local.update {
            it.copy(
                nicknameDraft = value,
                nicknameError = null,
                notice = null,
            )
        }
    }

    fun saveNickname() {
        val current = local.value
        if (current.savingNickname) return
        val loaded = identity.value as? IdentityState.Loaded ?: return

        val nickname = when (val result = Nickname.normalize(current.nicknameDraft)) {
            is NicknameResult.Invalid -> {
                local.update { it.copy(nicknameError = result.reason) }
                return
            }
            is NicknameResult.Ok -> result.value
        }
        if (nickname == loaded.profile.nickname) {
            local.update {
                it.copy(
                    nicknameDraft = nickname,
                    nicknameError = null,
                    notice = null,
                )
            }
            return
        }

        local.update {
            it.copy(
                nicknameDraft = nickname,
                nicknameError = null,
                savingNickname = true,
                notice = null,
            )
        }
        viewModelScope.launch {
            try {
                when (val result = profileSource.updateNickname(nickname)) {
                    is UpdateNicknameResult.Updated -> {
                        identity.value = loaded.copy(profile = result.profile)
                        local.update {
                            it.copy(
                                nicknameDraft = result.profile.nickname,
                                savingNickname = false,
                                notice = ProfileNotice.NICKNAME_SAVED,
                            )
                        }
                    }

                    is UpdateNicknameResult.InvalidNickname -> local.update {
                        it.copy(
                            savingNickname = false,
                            nicknameError = result.reason,
                        )
                    }

                    UpdateNicknameResult.MissingProfile -> {
                        identity.value = IdentityState.Failed
                        local.update { it.copy(savingNickname = false) }
                    }

                    is UpdateNicknameResult.Failed -> {
                        Timber.w("Nickname update failed")
                        local.update {
                            it.copy(
                                savingNickname = false,
                                notice = ProfileNotice.NICKNAME_SAVE_FAILED,
                            )
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w("Nickname update crashed")
                local.update {
                    it.copy(
                        savingNickname = false,
                        notice = ProfileNotice.NICKNAME_SAVE_FAILED,
                    )
                }
            }
        }
    }

    /** Explicitly accepts the newest key shown for a changed or new peer. */
    fun acceptPeerKey(peerId: PeerId) {
        val acceptor = trustAcceptor.value
        if (acceptor == null) {
            local.update { it.copy(notice = ProfileNotice.NETWORK_OFFLINE) }
            return
        }
        if (local.value.pendingPeer != null) return
        local.update { it.copy(pendingPeer = peerId, notice = null) }

        viewModelScope.launch {
            try {
                val peer = peerActions.getPeer(peerId)
                if (peer == null) {
                    local.update { it.copy(notice = ProfileNotice.TRUST_ACTION_FAILED) }
                    return@launch
                }
                if (!acceptor.acceptTrust(peerId, peer.lastSeenPublicKey)) {
                    Timber.w("Trust accept failed: peerId=%s", peerId)
                    local.update { it.copy(notice = ProfileNotice.TRUST_ACTION_FAILED) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w("Trust accept crashed: peerId=%s", peerId)
                local.update { it.copy(notice = ProfileNotice.TRUST_ACTION_FAILED) }
            } finally {
                local.update { it.copy(pendingPeer = null) }
            }
        }
    }

    /** Marks a manually compared key as VERIFIED. */
    fun verifyPeer(peerId: PeerId) {
        if (local.value.pendingPeer != null) return
        local.update { it.copy(pendingPeer = peerId, notice = null) }

        viewModelScope.launch {
            try {
                val peer = peerActions.getPeer(peerId)
                if (peer == null) {
                    local.update { it.copy(notice = ProfileNotice.TRUST_ACTION_FAILED) }
                    return@launch
                }
                if (!peerActions.verifyManually(peerId, peer.lastSeenPublicKey)) {
                    Timber.w("Manual verification failed: peerId=%s", peerId)
                    local.update { it.copy(notice = ProfileNotice.TRUST_ACTION_FAILED) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w("Manual verification crashed: peerId=%s", peerId)
                local.update { it.copy(notice = ProfileNotice.TRUST_ACTION_FAILED) }
            } finally {
                local.update { it.copy(pendingPeer = null) }
            }
        }
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            identity.value = IdentityState.Loading
            try {
                val own = profileSource.loadOwnProfile()
                if (own == null) {
                    identity.value = IdentityState.Failed
                    return@launch
                }
                identity.value = IdentityState.Loaded(
                    profile = own.profile,
                    publicKey = own.publicKey,
                )
                local.update {
                    if (it.nicknameDraft.isEmpty()) {
                        it.copy(nicknameDraft = own.profile.nickname)
                    } else {
                        it
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w("Profile load failed")
                identity.value = IdentityState.Failed
            }
        }
    }

    private fun safetyNumber(ownPublicKey: ByteArray, peerPublicKey: ByteArray): String? =
        try {
            SafetyNumber.of(ownPublicKey, peerPublicKey)
        } catch (error: IllegalArgumentException) {
            null
        }

    private sealed interface IdentityState {
        data object Loading : IdentityState
        data class Loaded(val profile: Profile, val publicKey: ByteArray) : IdentityState
        data object Failed : IdentityState
    }

    private data class LocalState(
        val nicknameDraft: String = "",
        val nicknameError: NicknameError? = null,
        val savingNickname: Boolean = false,
        val pendingPeer: PeerId? = null,
        val notice: ProfileNotice? = null,
    )

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(
            profileSource: ProfileDataSource,
            peerActions: PeerTrustActions,
            trustAcceptor: StateFlow<TrustAcceptor?>,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProfileViewModel(
                    profileSource = profileSource,
                    peerActions = peerActions,
                    trustAcceptor = trustAcceptor,
                )
            }
        }
    }
}
