package com.sunshine.mesh.data.profile

import com.sunshine.mesh.core.crypto.IdentityMaterial
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds [IdentityMaterial] from the persisted profile. The sealed private key
 * is opened through a function so the Android Keystore dependency stays at the
 * composition root and tests can use a deterministic key.
 */
class IdentityRepository(
    private val loadStoredProfile: suspend () -> StoredProfile?,
    private val openPrivateKey: (ByteArray) -> ByteArray,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : IdentityProvider {

    override suspend fun load(): IdentityMaterial? = withContext(ioDispatcher) {
        val stored = loadStoredProfile() ?: return@withContext null
        val privateKey = openPrivateKey(stored.sealedPrivateKey)
        try {
            IdentityMaterial.from(
                peerId = stored.profile.peerId,
                publicKey = stored.publicKey,
                privateKey = privateKey,
            )
        } finally {
            privateKey.fill(0)
        }
    }
}
