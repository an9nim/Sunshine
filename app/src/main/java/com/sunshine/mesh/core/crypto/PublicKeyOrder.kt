package com.sunshine.mesh.core.crypto

/**
 * Canonical ordering for two public keys. HKDF salt derivation and safety
 * numbers must agree on "lower" and "higher", otherwise both sides of a session
 * would hash different bytes. The order is unsigned lexicographic, so byte
 * values above 0x7f are not treated as negative.
 */
object PublicKeyOrder {

    fun compareUnsigned(first: ByteArray, second: ByteArray): Int {
        val sharedSize = minOf(first.size, second.size)
        for (index in 0 until sharedSize) {
            val difference =
                (first[index].toInt() and 0xff) - (second[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return first.size - second.size
    }

    fun lowerAndHigher(first: ByteArray, second: ByteArray): Pair<ByteArray, ByteArray> =
        if (compareUnsigned(first, second) <= 0) {
            first to second
        } else {
            second to first
        }
}
