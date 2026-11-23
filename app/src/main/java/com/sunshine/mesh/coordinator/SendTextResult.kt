package com.sunshine.mesh.coordinator

import com.sunshine.mesh.core.ids.MessageId

sealed interface SendTextResult {

    /** The message is stored and all fragments were handed to a link. */
    data class Sent(val messageId: MessageId) : SendTextResult

    /** The message is stored as PENDING and will be retried. */
    data class Pending(
        val messageId: MessageId,
        val reason: PendingReason,
    ) : SendTextResult

    /**
     * No row exists when [messageId] is null. A non-null id means the row was
     * created and then marked FAILED.
     */
    data class Failed(
        val messageId: MessageId?,
        val error: SendError,
    ) : SendTextResult
}

enum class PendingReason {
    AWAITING_PEER_KEY,
    TRUST_REQUIRED,
    KEY_CHANGED,
    NO_LINK,
}

enum class SendError {
    INVALID_TEXT,
    MESSAGE_TOO_LARGE,
    STORAGE_FAILED,
    CRYPTO_FAILED,
    INTERNAL,
}
