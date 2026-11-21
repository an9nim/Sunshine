package com.sunshine.mesh.domain.model

import com.sunshine.mesh.core.ids.PeerId

/**
 * A peer known to the local database. [firstSeenPublicKey] is the immutable TOFU
 * anchor; [lastSeenPublicKey] follows the newest observed key so a changed key
 * can be shown and accepted explicitly.
 */
class Peer(
    val peerId: PeerId,
    val nickname: String,
    val firstSeenPublicKey: ByteArray,
    val lastSeenPublicKey: ByteArray,
    val trustState: TrustState,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val verifiedAt: Long?,
) {
    init {
        require(firstSeenPublicKey.size == PUBLIC_KEY_BYTES)
        require(lastSeenPublicKey.size == PUBLIC_KEY_BYTES)
    }

    override fun toString(): String =
        "Peer(peerId=$peerId, nickname=$nickname, firstSeenPublicKey=" +
            "${firstSeenPublicKey.size}B, lastSeenPublicKey=${lastSeenPublicKey.size}B, " +
            "trustState=$trustState, firstSeenAt=$firstSeenAt, lastSeenAt=$lastSeenAt, " +
            "verifiedAt=$verifiedAt)"

    private companion object {
        const val PUBLIC_KEY_BYTES = 32
    }
}
