package com.sunshine.mesh.core.crypto

import com.sunshine.mesh.core.crypto.e2e.E2EException
import java.security.MessageDigest

/**
 * Human-checkable fingerprint of two identity keys. Both sides must derive the
 * same code, so the keys are sorted with [PublicKeyOrder] before hashing. The
 * code is intentionally short and groups are zero-padded, which makes a verbal
 * comparison possible without hiding the underlying full fingerprint forever.
 */
object SafetyNumber {
    const val DIGITS = 5
    const val GROUPS = 6
    private const val GROUP_BYTES = 5
    private const val MODULUS = 100_000L
    private const val PUBLIC_KEY_BYTES = 32

    fun of(ownPublicKey: ByteArray, peerPublicKey: ByteArray): String {
        requireKeySize(ownPublicKey)
        requireKeySize(peerPublicKey)

        val (lower, higher) = PublicKeyOrder.lowerAndHigher(ownPublicKey, peerPublicKey)
        val digest = MessageDigest.getInstance("SHA-256").run {
            update(lower)
            update(higher)
            digest()
        }
        try {
            return buildString(GROUPS * DIGITS + GROUPS - 1) {
                for (group in 0 until GROUPS) {
                    if (group > 0) append('-')
                    append(groupValue(digest, group * GROUP_BYTES))
                }
            }
        } finally {
            digest.fill(0)
        }
    }

    private fun groupValue(digest: ByteArray, offset: Int): String {
        var value = 0L
        for (index in 0 until GROUP_BYTES) {
            value = (value shl 8) or (digest[offset + index].toLong() and 0xff)
        }
        return (value % MODULUS).toString().padStart(DIGITS, '0')
    }

    private fun requireKeySize(publicKey: ByteArray) {
        if (publicKey.size != PUBLIC_KEY_BYTES) {
            throw E2EException(E2EException.Code.INVALID_PUBLIC_KEY)
        }
    }
}
