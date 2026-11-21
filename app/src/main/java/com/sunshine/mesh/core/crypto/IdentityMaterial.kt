package com.sunshine.mesh.core.crypto

import com.sunshine.mesh.core.ids.PeerId
import java.io.Closeable

/**
 * Local X25519 identity. The nickname deliberately stays outside: changing it
 * must never regenerate keys or change [peerId].
 *
 * The private key is owned by this object and wiped by [close]. It is never
 * exposed through a public API and never appears in [toString].
 */
class IdentityMaterial private constructor(
    val peerId: PeerId,
    private val publicKeyBytes: ByteArray,
    private val privateKeyBytes: ByteArray,
) : Closeable {

    @Volatile
    private var closed = false

    /** Defensive copy: the caller may keep or discard it without touching identity. */
    val publicKey: ByteArray
        get() = publicKeyBytes.copyOf()

    /**
     * Direct access for the E2E layer only. The block must not retain the array
     * after it returns; the array is wiped when this identity is closed.
     */
    internal fun <T> usePrivateKey(block: (ByteArray) -> T): T {
        check(!closed) { "identity material is closed" }
        return block(privateKeyBytes)
    }

    override fun close() {
        if (closed) return
        closed = true
        privateKeyBytes.fill(0)
    }

    override fun toString(): String =
        "IdentityMaterial(peerId=$peerId, publicKey=${publicKeyBytes.size} bytes, privateKey=redacted)"

    companion object {
        private const val KEY_BYTES = 32

        fun from(
            peerId: PeerId,
            publicKey: ByteArray,
            privateKey: ByteArray,
        ): IdentityMaterial {
            require(publicKey.size == KEY_BYTES) {
                "X25519 public key must be $KEY_BYTES bytes"
            }
            require(privateKey.size == KEY_BYTES) {
                "X25519 private key must be $KEY_BYTES bytes"
            }
            require(PeerId.fromPublicKey(publicKey) == peerId) {
                "peerId does not match the public key"
            }
            return IdentityMaterial(
                peerId = peerId,
                publicKeyBytes = publicKey.copyOf(),
                privateKeyBytes = privateKey.copyOf(),
            )
        }
    }
}
