package com.sunshine.mesh.domain.model

import com.sunshine.mesh.core.ids.PeerId

/**
 * Read model for the chat list. It carries only UI-safe fields: no keys and no
 * encrypted packets.
 */
data class ChatSummary(
    val peerId: PeerId,
    val nickname: String,
    val trustState: TrustState,
    val lastMessageText: String?,
    val lastMessageAt: Long?,
    val lastMessageStatus: MessageStatus?,
    val lastMessageOutgoing: Boolean,
)
