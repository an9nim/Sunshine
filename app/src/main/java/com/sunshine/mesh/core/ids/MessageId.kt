package com.sunshine.mesh.core.ids

import java.security.SecureRandom

@JvmInline
value class MessageId private constructor(val value: String) {

    init {
        require(value.length == HEX_LENGTH && value.all(HexCodec::isHexDigit)) {
            "messageId must be $HEX_LENGTH lowercase hex characters"
        }
    }

    fun toByteArray(): ByteArray = HexCodec.decode(value, BYTE_LENGTH)

    override fun toString(): String = value

    companion object {
        private const val BYTE_LENGTH = 16
        private const val HEX_LENGTH = BYTE_LENGTH * 2

        fun random(random: SecureRandom = SecureRandom()): MessageId {
            val bytes = ByteArray(BYTE_LENGTH)
            random.nextBytes(bytes)
            return fromBytes(bytes)
        }

        fun fromBytes(bytes: ByteArray): MessageId {
            require(bytes.size == BYTE_LENGTH) {
                "messageId must be $BYTE_LENGTH bytes"
            }
            return MessageId(HexCodec.encode(bytes))
        }

        fun parse(value: String): MessageId = MessageId(value.lowercase())
    }
}
