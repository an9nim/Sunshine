package com.sunshine.mesh.core.crypto

sealed class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class KeystoreUnavailable(cause: Throwable? = null) :
        CryptoException("Android Keystore is unavailable", cause)

    class SealFailed(cause: Throwable? = null) :
        CryptoException("Could not encrypt data", cause)

    class OpenFailed(cause: Throwable? = null) :
        CryptoException("Could not decrypt data", cause)
}
