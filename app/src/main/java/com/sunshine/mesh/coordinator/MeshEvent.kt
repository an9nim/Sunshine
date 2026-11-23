package com.sunshine.mesh.coordinator

import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.domain.model.MessageStatus
import java.nio.charset.StandardCharsets

/**
 * UI-facing events from the mesh coordinator. Payloads are copied: the
 * coordinator wipes its own buffers as soon as the event is emitted.
 *
 * No event ever contains a private key or an unencrypted wire packet.
 */
sealed interface MeshEvent {

    /** A complete text message was authenticated, reassembled and stored. */
    data class MessageReceived(
        val messageId: MessageId,
        val peerId: PeerId,
        val body: ByteArray,
        val createdAt: Long,
    ) : MeshEvent {
        val text: String get() = String(body, StandardCharsets.UTF_8)

        override fun toString(): String =
            "MessageReceived(messageId=$messageId, peerId=$peerId, body=${body.size}B)"
    }

    /** First contact with a key. Stage 6 shows the safety number dialog. */
    data class TrustRequired(
        val peerId: PeerId,
        val safetyNumber: String,
        val publicKey: ByteArray,
    ) : MeshEvent {
        override fun toString(): String =
            "TrustRequired(peerId=$peerId, publicKey=${publicKey.size}B)"
    }

    /** A known peer presented a different key. Stage 5 never accepts this. */
    data class KeyChanged(
        val peerId: PeerId,
        val safetyNumber: String,
        val publicKey: ByteArray,
    ) : MeshEvent {
        override fun toString(): String =
            "KeyChanged(peerId=$peerId, publicKey=${publicKey.size}B)"
    }

    /** A delivery receipt changed an outgoing message status. */
    data class DeliveryChanged(
        val messageId: MessageId,
        val status: MessageStatus,
    ) : MeshEvent
}
