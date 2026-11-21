package com.sunshine.mesh.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM key that never leaves Android Keystore. The sealed envelope is
 * versioned so a future key format can be introduced without guessing.
 */
class KeystoreSecret(
    private val alias: String,
    private val aadLabel: String,
) {
    private val lock = Any()

    fun seal(plaintext: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(aadLabel.toByteArray(StandardCharsets.UTF_8))
            val iv = cipher.iv
            check(iv.size == IV_BYTES) { "unexpected GCM IV size: ${iv.size}" }
            val ciphertext = cipher.doFinal(plaintext)

            val output = ByteArray(VERSION_BYTES + IV_BYTES + ciphertext.size)
            output[0] = VERSION
            System.arraycopy(iv, 0, output, VERSION_BYTES, IV_BYTES)
            System.arraycopy(ciphertext, 0, output, VERSION_BYTES + IV_BYTES, ciphertext.size)
            return output
        } catch (exception: GeneralSecurityException) {
            throw CryptoException.SealFailed(exception)
        }
    }

    fun open(sealed: ByteArray): ByteArray {
        val minimumSize = VERSION_BYTES + IV_BYTES + TAG_BYTES
        if (sealed.size < minimumSize) {
            throw CryptoException.OpenFailed(IllegalArgumentException("sealed value is too short"))
        }
        if (sealed[0] != VERSION) {
            throw CryptoException.OpenFailed(IllegalArgumentException("unsupported sealed version"))
        }

        val iv = sealed.copyOfRange(VERSION_BYTES, VERSION_BYTES + IV_BYTES)
        val ciphertext = sealed.copyOfRange(VERSION_BYTES + IV_BYTES, sealed.size)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aadLabel.toByteArray(StandardCharsets.UTF_8))
            return cipher.doFinal(ciphertext)
        } catch (exception: GeneralSecurityException) {
            throw CryptoException.OpenFailed(exception)
        }
    }

    private fun getOrCreateKey(): SecretKey = synchronized(lock) {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(alias, null)
            if (existing is SecretKey) return@synchronized existing
            if (existing != null) {
                throw CryptoException.KeystoreUnavailable(
                    IllegalStateException("Keystore alias does not hold a secret key"),
                )
            }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(KEY_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
            generator.init(spec)
            generator.generateKey()
        } catch (exception: CryptoException) {
            throw exception
        } catch (exception: GeneralSecurityException) {
            throw CryptoException.KeystoreUnavailable(exception)
        } catch (exception: IOException) {
            throw CryptoException.KeystoreUnavailable(exception)
        } catch (exception: ProviderException) {
            throw CryptoException.KeystoreUnavailable(exception)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        const val IV_BYTES = 12
        const val VERSION_BYTES = 1
        const val VERSION: Byte = 1
    }
}
