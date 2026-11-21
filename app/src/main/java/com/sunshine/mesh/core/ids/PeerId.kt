package com.sunshine.mesh.core.ids

import java.security.MessageDigest

/**
 * Eight bytes of SHA-256 over the X25519 public key. Short enough for every
 * mesh header, wide enough that a local crowd cannot collide by accident.
 */
@JvmInline
value class PeerId private constructor(val value: String) {

    init {
        require(value.length == HEX_LENGTH && value.all(HexCodec::isHexDigit)) {
            "peerId must be $HEX_LENGTH lowercase hex characters"
        }
    }

    fun toByteArray(): ByteArray = HexCodec.decode(value, BYTE_LENGTH)

    override fun toString(): String = value

    companion object {
        private const val BYTE_LENGTH = 8
        private const val HEX_LENGTH = BYTE_LENGTH * 2
        private const val PUBLIC_KEY_BYTES = 32

        fun fromPublicKey(publicKey: ByteArray): PeerId {
            require(publicKey.size == PUBLIC_KEY_BYTES) {
                "X25519 public key must be $PUBLIC_KEY_BYTES bytes"
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
            return PeerId(HexCodec.encode(digest.copyOf(BYTE_LENGTH)))
        }

        fun fromBytes(bytes: ByteArray): PeerId {
            require(bytes.size == BYTE_LENGTH) {
                "peerId must be $BYTE_LENGTH bytes"
            }
            return PeerId(HexCodec.encode(bytes))
        }

        fun parse(value: String): PeerId = PeerId(value.lowercase())
    }
}
