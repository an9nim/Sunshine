package com.sunshine.mesh.data.profile

import com.sunshine.mesh.domain.model.Profile

/**
 * Raw persisted identity: public key plus the Keystore-sealed private key.
 * The sealed blob is still sensitive, so [toString] prints only its size.
 */
class StoredProfile(
    val profile: Profile,
    val publicKey: ByteArray,
    val sealedPrivateKey: ByteArray,
) {
    override fun toString(): String =
        "StoredProfile(peerId=${profile.peerId}, publicKey=${publicKey.size}B, " +
            "sealedPrivateKey=${sealedPrivateKey.size}B)"
}
