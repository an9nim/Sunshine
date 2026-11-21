package com.sunshine.mesh.core.crypto.e2e

import com.sunshine.mesh.core.crypto.IdentityMaterial
import com.sunshine.mesh.core.crypto.PublicKeyOrder
import com.sunshine.mesh.core.ids.PeerId
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Static X25519 ECDH followed by HKDF-SHA256.
 *
 * The salt is SHA-256 over the two public keys sorted bytewise (unsigned), not
 * "own key first": both sides must hash the same 64 bytes no matter who asks.
 * The first 32 HKDF bytes belong to the smaller-key -> larger-key direction,
 * the second 32 to the opposite direction. That also makes the mapping
 * role-independent.
 */
class HkdfSession private constructor(
    val localPeerId: PeerId,
    val remotePeerId: PeerId,
    private val keys: SessionKeys,
) : Closeable {

    @Volatile
    private var closed = false

    internal fun <T> useOutboundKey(block: (ByteArray) -> T): T {
        check(!closed) { "session is closed" }
        return keys.useOutbound(block)
    }

    internal fun <T> useInboundKey(block: (ByteArray) -> T): T {
        check(!closed) { "session is closed" }
        return keys.useInbound(block)
    }

    override fun close() {
        if (closed) return
        closed = true
        keys.close()
    }

    override fun toString(): String =
        "HkdfSession(localPeerId=$localPeerId, remotePeerId=$remotePeerId, keys=redacted)"

    companion object {
        const val PUBLIC_KEY_BYTES = 32

        private const val KEY_BYTES = 32
        private const val OUTPUT_BYTES = KEY_BYTES * 2
        private val INFO = "sunshine/e2e/v1".toByteArray(StandardCharsets.US_ASCII)

        fun derive(identity: IdentityMaterial, peerPublicKey: ByteArray): HkdfSession {
            if (peerPublicKey.size != PUBLIC_KEY_BYTES) {
                throw E2EException(E2EException.Code.INVALID_PUBLIC_KEY)
            }

            val remotePeerId = PeerId.fromPublicKey(peerPublicKey)
            val ownPublicKey = identity.publicKey
            return identity.usePrivateKey { privateKey ->
                val salt = deriveSalt(ownPublicKey, peerPublicKey)
                val sharedSecret = agree(privateKey, peerPublicKey)
                try {
                    val okm = deriveKeyMaterial(sharedSecret, salt)
                    try {
                        val first = okm.copyOfRange(0, KEY_BYTES)
                        val second = okm.copyOfRange(KEY_BYTES, OUTPUT_BYTES)
                        val outboundFirst =
                            PublicKeyOrder.compareUnsigned(ownPublicKey, peerPublicKey) < 0
                        val keys = if (outboundFirst) {
                            SessionKeys(outbound = first, inbound = second)
                        } else {
                            SessionKeys(outbound = second, inbound = first)
                        }
                        HkdfSession(
                            localPeerId = identity.peerId,
                            remotePeerId = remotePeerId,
                            keys = keys,
                        )
                    } finally {
                        okm.fill(0)
                    }
                } finally {
                    sharedSecret.fill(0)
                    salt.fill(0)
                }
            }
        }

        private fun agree(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
            try {
                val agreement = X25519Agreement()
                agreement.init(X25519PrivateKeyParameters(privateKey, 0))
                val sharedSecret = ByteArray(agreement.agreementSize)
                agreement.calculateAgreement(
                    X25519PublicKeyParameters(peerPublicKey, 0),
                    sharedSecret,
                    0,
                )
                return sharedSecret
            } catch (exception: RuntimeException) {
                throw E2EException(E2EException.Code.KEY_DERIVATION_FAILED, exception)
            }
        }

        private fun deriveSalt(ownPublicKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
            val (lower, higher) = PublicKeyOrder.lowerAndHigher(ownPublicKey, peerPublicKey)
            return MessageDigest.getInstance("SHA-256").run {
                update(lower)
                update(higher)
                digest()
            }
        }

        private fun deriveKeyMaterial(sharedSecret: ByteArray, salt: ByteArray): ByteArray {
            val okm = ByteArray(OUTPUT_BYTES)
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(sharedSecret, salt, INFO))
            hkdf.generateBytes(okm, 0, okm.size)
            return okm
        }
    }
}
