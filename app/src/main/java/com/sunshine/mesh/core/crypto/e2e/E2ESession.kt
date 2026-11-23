package com.sunshine.mesh.core.crypto.e2e

import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.domain.model.OpenedFragment
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * Text session bound to one pair of identity keys. Trust is decided before this
 * object is created: the session only encrypts and authenticates envelopes and
 * never reads or stores [com.sunshine.mesh.domain.model.TrustState].
 *
 * Fragmentation is local to one message: every envelope is exactly 512 bytes
 * and carries the same messageId plus its partIndex/totalParts. Reassembly is
 * deliberately outside: callers feed [openFragment] results into
 * [com.sunshine.mesh.domain.message.FragmentAssembler] and decode UTF-8 only
 * after the assembler returns a complete message.
 */
class E2ESession(
    private val hkdf: HkdfSession,
    private val random: SecureRandom = SecureRandom(),
) : Closeable {

    val localPeerId: PeerId = hkdf.localPeerId
    val remotePeerId: PeerId = hkdf.remotePeerId

    private val cipher = EnvelopeCipher()

    @Volatile
    private var closed = false

    /**
     * Encrypts UTF-8 text into one or more fixed-size envelopes. The caller
     * owns [messageId] so the same id can be written to the messages table.
     */
    fun sealText(messageId: MessageId, text: String): List<ByteArray> {
        check(!closed) { "session is closed" }
        val plaintext = text.toByteArray(StandardCharsets.UTF_8)
        try {
            return seal(EnvelopeType.TEXT, messageId, plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * Encrypts an arbitrary payload. KEY_EXCHANGE is unencrypted by design and
     * must be built with [KeyExchangeCodec] instead.
     */
    fun seal(
        type: EnvelopeType,
        messageId: MessageId,
        payload: ByteArray,
    ): List<ByteArray> {
        check(!closed) { "session is closed" }
        if (type == EnvelopeType.KEY_EXCHANGE) {
            throw E2EException(E2EException.Code.UNKNOWN_TYPE)
        }
        val totalParts = partCount(payload.size)
        return buildList(totalParts) {
            for (partIndex in 0 until totalParts) {
                add(sealPart(type, messageId, payload, partIndex, totalParts))
            }
        }
    }

    /**
     * Authenticates and decrypts one envelope. The returned fragment is not
     * text: reassembly must finish before any UTF-8 decoding.
     */
    fun openFragment(
        packet: ByteArray,
        expectedType: EnvelopeType = EnvelopeType.TEXT,
    ): OpenedFragment {
        check(!closed) { "session is closed" }
        if (expectedType == EnvelopeType.KEY_EXCHANGE) {
            throw E2EException(E2EException.Code.UNKNOWN_TYPE)
        }
        val envelope = EnvelopeCodec.decode(packet)
        try {
            val header = envelope.header
            if (header.type != expectedType) {
                throw E2EException(E2EException.Code.UNKNOWN_TYPE)
            }
            if (header.recipientId != localPeerId) {
                throw E2EException(E2EException.Code.WRONG_RECIPIENT)
            }
            if (header.senderId != remotePeerId) {
                throw E2EException(E2EException.Code.WRONG_SENDER)
            }

            val aad = EnvelopeAad.build(header)
            try {
                val padded = hkdf.useInboundKey { key ->
                    cipher.decrypt(
                        key = key,
                        nonce = envelope.nonce,
                        aad = aad,
                        ciphertext = envelope.ciphertext,
                        tag = envelope.tag,
                    )
                }
                try {
                    val plaintext = EnvelopeCodec.unpad(padded, header.paddingLength)
                    return OpenedFragment(header, plaintext)
                } finally {
                    padded.fill(0)
                }
            } finally {
                aad.fill(0)
            }
        } finally {
            envelope.nonce.fill(0)
            envelope.ciphertext.fill(0)
            envelope.tag.fill(0)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        cipher.close()
        hkdf.close()
    }

    override fun toString(): String =
        "E2ESession(localPeerId=$localPeerId, remotePeerId=$remotePeerId, closed=$closed)"

    private fun sealPart(
        type: EnvelopeType,
        messageId: MessageId,
        plaintext: ByteArray,
        partIndex: Int,
        totalParts: Int,
    ): ByteArray {
        val start = partIndex * EnvelopeCodec.MAX_PLAINTEXT_BYTES
        val end = minOf(start + EnvelopeCodec.MAX_PLAINTEXT_BYTES, plaintext.size)
        val chunk = plaintext.copyOfRange(start, end)
        try {
            val padded = EnvelopeCodec.pad(chunk, random)
            try {
                val header = EnvelopeHeader.of(
                    type = type,
                    messageId = messageId,
                    senderId = localPeerId,
                    recipientId = remotePeerId,
                    paddingLength = padded.paddingLength,
                    partIndex = partIndex,
                    totalParts = totalParts,
                )
                val aad = EnvelopeAad.build(header)
                try {
                    val nonce = ByteArray(EnvelopeCodec.NONCE_BYTES)
                    random.nextBytes(nonce)
                    try {
                        val sealed = hkdf.useOutboundKey { key ->
                            cipher.encrypt(
                                key = key,
                                nonce = nonce,
                                aad = aad,
                                paddedPlaintext = padded.bytes,
                            )
                        }
                        try {
                            return EnvelopeCodec.encode(
                                header = header,
                                nonce = nonce,
                                ciphertext = sealed.ciphertext,
                                tag = sealed.tag,
                            )
                        } finally {
                            sealed.ciphertext.fill(0)
                            sealed.tag.fill(0)
                        }
                    } finally {
                        nonce.fill(0)
                    }
                } finally {
                    aad.fill(0)
                }
            } finally {
                padded.close()
            }
        } finally {
            chunk.fill(0)
        }
    }

    private fun partCount(plaintextBytes: Int): Int {
        if (plaintextBytes > MAX_MESSAGE_BYTES) {
            throw E2EException(E2EException.Code.MESSAGE_TOO_LARGE)
        }
        if (plaintextBytes == 0) return 1
        return (plaintextBytes + EnvelopeCodec.MAX_PLAINTEXT_BYTES - 1) /
            EnvelopeCodec.MAX_PLAINTEXT_BYTES
    }

    companion object {
        const val MAX_MESSAGE_BYTES =
            EnvelopeCodec.MAX_PARTS * EnvelopeCodec.MAX_PLAINTEXT_BYTES
    }
}
