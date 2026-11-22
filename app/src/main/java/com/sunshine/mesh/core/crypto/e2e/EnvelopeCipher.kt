package com.sunshine.mesh.core.crypto.e2e

import java.security.GeneralSecurityException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for one outbound key. The instance remembers which nonces were
 * already used for encryption and fails closed on a repeat: with GCM, a nonce
 * reuse under the same key leaks plaintext relationships and breaks the tag.
 *
 * The set is a per-session programming-error guard, not a replacement for
 * random 96-bit nonces. It is wiped by [close] together with the session.
 */
internal class EnvelopeCipher {

    private val usedNonces = HashSet<NonceKey>()

    @Volatile
    private var closed = false

    @Synchronized
    fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        paddedPlaintext: ByteArray,
    ): SealedPayload {
        check(!closed) { "cipher is closed" }
        requireKeySize(key)
        requireNonceSize(nonce)
        require(aad.size == EnvelopeAad.BYTES) {
            "aad must be ${EnvelopeAad.BYTES} bytes"
        }
        require(paddedPlaintext.size == EnvelopeCodec.CIPHERTEXT_BYTES) {
            "padded plaintext must be ${EnvelopeCodec.CIPHERTEXT_BYTES} bytes"
        }

        val nonceKey = NonceKey(nonce)
        if (!usedNonces.add(nonceKey)) {
            nonceKey.wipe()
            throw E2EException(E2EException.Code.NONCE_REUSE)
        }

        val output = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, KEY_ALGORITHM),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.updateAAD(aad)
            cipher.doFinal(paddedPlaintext)
        } catch (exception: GeneralSecurityException) {
            throw E2EException(E2EException.Code.ENCRYPTION_FAILED, exception)
        }

        try {
            val ciphertextBytes = output.size - TAG_BYTES
            return SealedPayload(
                ciphertext = output.copyOfRange(0, ciphertextBytes),
                tag = output.copyOfRange(ciphertextBytes, output.size),
            )
        } finally {
            output.fill(0)
        }
    }

    @Synchronized
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray,
    ): ByteArray {
        check(!closed) { "cipher is closed" }
        requireKeySize(key)
        requireNonceSize(nonce)
        require(aad.size == EnvelopeAad.BYTES) {
            "aad must be ${EnvelopeAad.BYTES} bytes"
        }
        require(ciphertext.size == EnvelopeCodec.CIPHERTEXT_BYTES) {
            "ciphertext must be ${EnvelopeCodec.CIPHERTEXT_BYTES} bytes"
        }
        require(tag.size == TAG_BYTES) { "tag must be $TAG_BYTES bytes" }

        val input = ByteArray(ciphertext.size + tag.size)
        ciphertext.copyInto(input)
        tag.copyInto(input, ciphertext.size)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, KEY_ALGORITHM),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.updateAAD(aad)
            return cipher.doFinal(input)
        } catch (exception: BadPaddingException) {
            throw E2EException(E2EException.Code.AUTHENTICATION_FAILED, exception)
        } catch (exception: GeneralSecurityException) {
            throw E2EException(E2EException.Code.DECRYPTION_FAILED, exception)
        } finally {
            input.fill(0)
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        usedNonces.forEach { it.wipe() }
        usedNonces.clear()
    }

    internal class SealedPayload(
        val ciphertext: ByteArray,
        val tag: ByteArray,
    ) {
        init {
            require(ciphertext.size == EnvelopeCodec.CIPHERTEXT_BYTES)
            require(tag.size == EnvelopeCodec.TAG_BYTES)
        }
    }

    private class NonceKey(nonce: ByteArray) {
        private val bytes = nonce.copyOf()

        override fun equals(other: Any?): Boolean =
            other is NonceKey && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        fun wipe() {
            bytes.fill(0)
        }
    }

    private fun requireKeySize(key: ByteArray) {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
    }

    private fun requireNonceSize(nonce: ByteArray) {
        require(nonce.size == EnvelopeCodec.NONCE_BYTES) {
            "nonce must be ${EnvelopeCodec.NONCE_BYTES} bytes"
        }
    }

    private companion object {
        const val KEY_BYTES = 32
        const val TAG_BYTES = 16
        const val TAG_BITS = TAG_BYTES * 8
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALGORITHM = "AES"
    }
}
