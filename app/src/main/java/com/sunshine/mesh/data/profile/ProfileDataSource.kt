package com.sunshine.mesh.data.profile

import com.sunshine.mesh.domain.model.Profile
import com.sunshine.mesh.domain.validation.NicknameError

/** Own profile plus the public half of the identity key. */
class OwnProfile(
    val profile: Profile,
    val publicKey: ByteArray,
) {
    override fun toString(): String =
        "OwnProfile(peerId=${profile.peerId}, nickname=${profile.nickname}, " +
            "publicKey=${publicKey.size}B)"
}

sealed interface UpdateNicknameResult {
    data class Updated(val profile: Profile) : UpdateNicknameResult
    data class InvalidNickname(val reason: NicknameError) : UpdateNicknameResult
    data object MissingProfile : UpdateNicknameResult
    data class Failed(val cause: Throwable) : UpdateNicknameResult
}

/** The narrow profile surface used by the profile screen. */
interface ProfileDataSource {
    suspend fun loadOwnProfile(): OwnProfile?

    suspend fun updateNickname(nicknameInput: String): UpdateNicknameResult
}
