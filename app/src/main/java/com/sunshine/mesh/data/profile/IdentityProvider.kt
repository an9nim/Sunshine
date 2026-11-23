package com.sunshine.mesh.data.profile

import com.sunshine.mesh.core.crypto.IdentityMaterial

/**
 * Loads the local X25519 identity. The caller owns the returned material and
 * must close it to wipe the private key.
 */
fun interface IdentityProvider {
    suspend fun load(): IdentityMaterial?
}
