package com.sunshine.mesh.core.crypto.e2e

import com.sunshine.mesh.core.ids.MessageId

/**
 * Encrypted delivery receipt. The payload references the acknowledged message
 * by its 16-byte wire id; the receipt envelope itself gets a fresh message id.
 */
object ReceiptPayload {

    private const val MESSAGE_ID_BYTES = 16
    const val BYTES = MESSAGE_ID_BYTES + 1

    fun encode(messageId: MessageId, status: ReceiptStatus): ByteArray =
        ByteArray(BYTES).also { payload ->
            messageId.toByteArray().copyInto(payload)
            payload[MESSAGE_ID_BYTES] = status.code
        }

    fun decode(payload: ByteArray): Receipt {
        if (payload.size != BYTES) {
            throw E2EException(E2EException.Code.MALFORMED_ENVELOPE)
        }
        val status = ReceiptStatus.fromCode(payload[MESSAGE_ID_BYTES])
        return Receipt(
            messageId = MessageId.fromBytes(
                payload.copyOfRange(0, MESSAGE_ID_BYTES),
            ),
            status = status,
        )
    }
}

enum class ReceiptStatus(val code: Byte) {
    DELIVERED(1),
    ;

    companion object {
        fun fromCode(code: Byte): ReceiptStatus =
            entries.firstOrNull { it.code == code }
                ?: throw E2EException(E2EException.Code.MALFORMED_ENVELOPE)
    }
}

data class Receipt(
    val messageId: MessageId,
    val status: ReceiptStatus,
)
