package com.sunshine.mesh.data.profile

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.domain.model.Profile
import kotlinx.coroutines.flow.first

private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(name = "profile")

fun createProfileStore(context: Context): ProfileStore =
    ProfileStore(context.profileDataStore)

class ProfileStore(private val dataStore: DataStore<Preferences>) {

    suspend fun readProfile(): Profile? = readStoredProfile()?.profile

    suspend fun readStoredProfile(): StoredProfile? {
        val preferences = dataStore.data.first()
        val nickname = preferences[Keys.NICKNAME]
        val peerId = preferences[Keys.PEER_ID]
        val publicKey = preferences[Keys.PUBLIC_KEY]
        val sealedPrivateKey = preferences[Keys.SEALED_PRIVATE_KEY]
        val createdAt = preferences[Keys.CREATED_AT]

        if (nickname == null && peerId == null && publicKey == null &&
            sealedPrivateKey == null && createdAt == null
        ) {
            return null
        }
        if (nickname == null || peerId == null || publicKey == null ||
            sealedPrivateKey == null || createdAt == null
        ) {
            error("Stored profile is incomplete")
        }

        val publicKeyBytes = Base64.decode(publicKey, Base64.NO_WRAP)
        val storedPeerId = PeerId.parse(peerId)
        val derivedPeerId = PeerId.fromPublicKey(publicKeyBytes)
        if (storedPeerId != derivedPeerId) {
            error("Stored peer id does not match the public key")
        }

        return StoredProfile(
            profile = Profile(
                peerId = storedPeerId,
                nickname = nickname,
                createdAt = createdAt,
            ),
            publicKey = publicKeyBytes,
            sealedPrivateKey = Base64.decode(sealedPrivateKey, Base64.NO_WRAP),
        )
    }

    suspend fun save(profile: Profile, publicKey: ByteArray, sealedPrivateKey: ByteArray) {
        dataStore.edit { preferences ->
            preferences[Keys.NICKNAME] = profile.nickname
            preferences[Keys.PEER_ID] = profile.peerId.value
            preferences[Keys.PUBLIC_KEY] = Base64.encodeToString(publicKey, Base64.NO_WRAP)
            preferences[Keys.SEALED_PRIVATE_KEY] =
                Base64.encodeToString(sealedPrivateKey, Base64.NO_WRAP)
            preferences[Keys.CREATED_AT] = profile.createdAt
        }
    }

    /** Changes only the nickname; keys and creation time are left untouched. */
    suspend fun updateNickname(nickname: String): Profile? {
        var updated: Profile? = null
        dataStore.edit { preferences ->
            val peerId = preferences[Keys.PEER_ID] ?: return@edit
            val createdAt = preferences[Keys.CREATED_AT] ?: return@edit
            preferences[Keys.NICKNAME] = nickname
            updated = Profile(
                peerId = PeerId.parse(peerId),
                nickname = nickname,
                createdAt = createdAt,
            )
        }
        return updated
    }

    private object Keys {
        val NICKNAME = stringPreferencesKey("nickname")
        val PEER_ID = stringPreferencesKey("peer_id")
        val PUBLIC_KEY = stringPreferencesKey("public_key")
        val SEALED_PRIVATE_KEY = stringPreferencesKey("sealed_private_key")
        val CREATED_AT = longPreferencesKey("created_at")
    }
}
