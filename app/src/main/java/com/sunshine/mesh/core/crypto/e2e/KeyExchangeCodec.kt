package com.sunshine.mesh.core.crypto.e2e

import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import java.io.Closeable

/**
 * Public-key exchange envelope. The 32-byte X25519 key is stored in the fixed
 * ciphertext area without encryption: a public key is not secret, and the
 * receiver still applies TOFU through PeerTrustGate before deriving a session.
 *
 * The envelope keeps the normal 512-byte layout, so relays cannot distinguish
 * it from a text envelope by size. Nonce and tag are zero and are never used
 * for authentication; only TEXT and RECEIPT envelopes are authenticated.
 */
object KeyExchangeCodec {

    const val PUBLIC_KEY_BYTES = HkdfSession.PUBLIC_KEY_BYTES

    fun encode(
        messageId: MessageId,
        senderId: PeerId,
        recipientId: PeerId,
        publicKey: ByteArray,
    ): ByteArray {
        if (publicKey.size != PUBLIC_KEY_BYTES) {
            throw E2EException(E2EException.Code.INVALID_PUBLIC_KEY)
        }

        val header = EnvelopeHeader.of(
            type = EnvelopeType.KEY_EXCHANGE,
            messageId = messageId,
            senderId = senderId,
            recipientId = recipientId,
            paddingLength = 0,
            partIndex = 0,
            totalParts = 1,
        )
        val ciphertext = ByteArray(EnvelopeCodec.CIPHERTEXT_BYTES)
        try {
            publicKey.copyInto(ciphertext)
            return EnvelopeCodec.encode(
                header = header,
                nonce = ByteArray(EnvelopeCodec.NONCE_BYTES),
                ciphertext = ciphertext,
                tag = ByteArray(EnvelopeCodec.TAG_BYTES),
            )
        } finally {
            ciphertext.fill(0)
        }
    }

    fun decode(packet: ByteArray): KeyExchange {
        val header = EnvelopeCodec.peekHeader(packet)
        if (header.type != EnvelopeType.KEY_EXCHANGE ||
            header.paddingLength != 0 ||
            header.partIndex != 0 ||
            header.totalParts != 1
        ) {
            throw E2EException(E2EException.Code.MALFORMED_ENVELOPE)
        }

        return KeyExchange(
            header = header,
            publicKey = packet.copyOfRange(
                EnvelopeCodec.CIPHERTEXT_OFFSET,
                EnvelopeCodec.CIPHERTEXT_OFFSET + PUBLIC_KEY_BYTES,
            ),
        )
    }
}

/**
 * Decoded key exchange. The public key is copied out of the envelope; close it
 * after the trust gate has consumed it.
 */
class KeyExchange(
    val header: EnvelopeHeader,
    val publicKey: ByteArray,
) : Closeable {

    init {
        require(publicKey.size == KeyExchangeCodec.PUBLIC_KEY_BYTES)
    }

    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        publicKey.fill(0)
    }

    override fun toString(): String =
        "KeyExchange(msgId=${header.messageId}, senderId=${header.senderId}, " +
            "publicKey=${publicKey.size}B)"
}
