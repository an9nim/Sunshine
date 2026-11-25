package com.sunshine.mesh.domain.model

import com.sunshine.mesh.core.ids.MessageId

/**
 * One message as the chat UI needs it. The encrypted outbox packet is
 * deliberately absent.
 */
data class ChatMessage(
    val messageId: MessageId,
    val text: String,
    val createdAt: Long,
    val outgoing: Boolean,
    val status: MessageStatus,
)
