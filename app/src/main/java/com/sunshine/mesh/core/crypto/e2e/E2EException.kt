package com.sunshine.mesh.core.crypto.e2e

/**
 * Typed failure of the end-to-end layer. The message is the stable code name:
 * it is safe for logs because it never contains key bytes or plaintext.
 */
class E2EException(
    val code: Code,
    cause: Throwable? = null,
) : Exception(code.name, cause) {

    enum class Code {
        INVALID_PUBLIC_KEY,
        UNKNOWN_VERSION,
        UNKNOWN_TYPE,
        MALFORMED_ENVELOPE,
        ENVELOPE_TOO_LARGE,
        MESSAGE_TOO_LARGE,
        AUTHENTICATION_FAILED,
        WRONG_RECIPIENT,
        WRONG_SENDER,
        KEY_DERIVATION_FAILED,
        NONCE_REUSE,
        ENCRYPTION_FAILED,
        DECRYPTION_FAILED,
    }
}
