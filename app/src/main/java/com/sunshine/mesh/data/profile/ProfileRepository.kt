package com.sunshine.mesh.data.profile

import com.sunshine.mesh.core.crypto.IdentityKeys
import com.sunshine.mesh.core.crypto.KeystoreSecret
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.domain.model.Profile
import com.sunshine.mesh.domain.validation.Nickname
import com.sunshine.mesh.domain.validation.NicknameError
import com.sunshine.mesh.domain.validation.NicknameResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface CreateProfileResult {
    data class Created(val profile: Profile) : CreateProfileResult
    data class InvalidNickname(val reason: NicknameError) : CreateProfileResult
    data object AlreadyExists : CreateProfileResult
    data class Failed(val cause: Throwable) : CreateProfileResult
}

class ProfileRepository(
    private val store: ProfileStore,
    private val secret: KeystoreSecret,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun loadProfile(): Profile? = withContext(ioDispatcher) {
        store.readProfile()
    }

    suspend fun createProfile(nicknameInput: String): CreateProfileResult =
        withContext(ioDispatcher) {
            try {
                when (val nickname = Nickname.normalize(nicknameInput)) {
                    is NicknameResult.Invalid ->
                        CreateProfileResult.InvalidNickname(nickname.reason)
                    is NicknameResult.Ok ->
                        createNewProfile(nickname.value)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                CreateProfileResult.Failed(exception)
            }
        }

    private suspend fun createNewProfile(nickname: String): CreateProfileResult {
        if (store.readProfile() != null) {
            return CreateProfileResult.AlreadyExists
        }

        val keyPair = IdentityKeys.generate()
        try {
            val peerId = PeerId.fromPublicKey(keyPair.publicKey)
            val sealedPrivateKey = secret.seal(keyPair.privateKey)
            val profile = Profile(
                peerId = peerId,
                nickname = nickname,
                createdAt = System.currentTimeMillis(),
            )
            store.save(
                profile = profile,
                publicKey = keyPair.publicKey,
                sealedPrivateKey = sealedPrivateKey,
            )
            return CreateProfileResult.Created(profile)
        } finally {
            keyPair.destroy()
        }
    }
}
