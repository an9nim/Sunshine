package com.sunshine.mesh.ui.profile

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.domain.model.Profile
import com.sunshine.mesh.domain.model.TrustState
import com.sunshine.mesh.domain.validation.NicknameError

sealed interface ProfileUiState {

    data object Loading : ProfileUiState

    data class Ready(
        val profile: Profile,
        val nicknameDraft: String,
        val nicknameError: NicknameError?,
        val savingNickname: Boolean,
        val peers: List<ProfilePeer>,
        /** False while the transport service is not bound. */
        val trustActionsAvailable: Boolean,
        val notice: ProfileNotice?,
    ) : ProfileUiState

    data object Failed : ProfileUiState
}

data class ProfilePeer(
    val peerId: PeerId,
    /** Empty when the peer has not been stored with a nickname yet. */
    val nickname: String,
    val trustState: TrustState,
    /** Six groups of five digits, or null when the own key is unavailable. */
    val safetyNumber: String?,
    val actionPending: Boolean,
)

/** Transient profile line; mapped to a string by the screen. */
enum class ProfileNotice {
    NICKNAME_SAVED,
    NICKNAME_SAVE_FAILED,
    TRUST_ACTION_FAILED,
    NETWORK_OFFLINE,
}
