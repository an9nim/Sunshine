package com.sunshine.mesh.data.chat

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.domain.model.ChatMessage
import com.sunshine.mesh.domain.model.Peer
import kotlinx.coroutines.flow.Flow

/** Read-only chat data used by the chat screens. */
interface ChatDataSource {

    fun observePeer(peerId: PeerId): Flow<Peer?>

    fun observeConversation(peerId: PeerId): Flow<List<ChatMessage>>
}
