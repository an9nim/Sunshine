package com.sunshine.mesh.core.crypto.e2e

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EnvelopeCipherTest {

    private val cipher = EnvelopeCipher()
    private val key = ByteArray(32) { index -> (index + 1).toByte() }
    private val nonce = ByteArray(EnvelopeCodec.NONCE_BYTES) { index -> (index + 11).toByte() }
    private val aad = ByteArray(EnvelopeAad.BYTES) { index -> (index + 21).toByte() }
    private val padded = ByteArray(EnvelopeCodec.CIPHERTEXT_BYTES) { index ->
        (index + 31).toByte()
    }

    @Test
    fun encryptsAndDecryptsPaddedPlaintext() {
        val sealed = cipher.encrypt(key, nonce, aad, padded)

        assertEquals(EnvelopeCodec.CIPHERTEXT_BYTES, sealed.ciphertext.size)
        assertEquals(EnvelopeCodec.TAG_BYTES, sealed.tag.size)

        val opened = cipher.decrypt(key, nonce, aad, sealed.ciphertext, sealed.tag)
        try {
            assertArrayEquals(padded, opened)
        } finally {
            opened.fill(0)
        }
    }

    @Test
    fun reusedNonceIsRejectedForTheSameKey() {
        cipher.encrypt(key, nonce, aad, padded)

        val exception = assertThrows(E2EException::class.java) {
            cipher.encrypt(key, nonce, aad, padded)
        }

        assertEquals(E2EException.Code.NONCE_REUSE, exception.code)
    }

    @Test
    fun wrongTagFailsAuthentication() {
        val sealed = cipher.encrypt(key, nonce, aad, padded)
        val badTag = sealed.tag.copyOf()
        badTag[0] = (badTag[0].toInt() xor 1).toByte()

        try {
            assertCode(E2EException.Code.AUTHENTICATION_FAILED) {
                cipher.decrypt(key, nonce, aad, sealed.ciphertext, badTag)
            }
        } finally {
            badTag.fill(0)
        }
    }

    @Test
    fun wrongAadFailsAuthentication() {
        val sealed = cipher.encrypt(key, nonce, aad, padded)
        val badAad = aad.copyOf()
        badAad[0] = (badAad[0].toInt() xor 1).toByte()

        try {
            assertCode(E2EException.Code.AUTHENTICATION_FAILED) {
                cipher.decrypt(key, nonce, badAad, sealed.ciphertext, sealed.tag)
            }
        } finally {
            badAad.fill(0)
        }
    }

    @Test
    fun closeRejectsFurtherEncryptionAndDecryption() {
        val sealed = cipher.encrypt(key, nonce, aad, padded)
        cipher.close()

        assertThrows(IllegalStateException::class.java) {
            cipher.encrypt(key, nonce, aad, padded)
        }
        assertThrows(IllegalStateException::class.java) {
            cipher.decrypt(key, nonce, aad, sealed.ciphertext, sealed.tag)
        }
    }

    private fun assertCode(code: E2EException.Code, block: () -> Unit) {
        val exception = assertThrows(E2EException::class.java) { block() }
        assertEquals(code, exception.code)
    }
}
