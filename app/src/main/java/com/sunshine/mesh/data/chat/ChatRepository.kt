package com.sunshine.mesh.data.chat

import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.data.local.dao.MessageDao
import com.sunshine.mesh.data.local.entity.MessageEntity
import com.sunshine.mesh.data.peer.PeerRepository
import com.sunshine.mesh.domain.model.ChatMessage
import com.sunshine.mesh.domain.model.ChatSummary
import com.sunshine.mesh.domain.model.MessageDirection
import com.sunshine.mesh.domain.model.Peer
import com.sunshine.mesh.domain.model.TrustState
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Read-only join of peers and their latest message. Room flows keep the chat
 * list current without the coordinator pushing UI events.
 */
class ChatRepository(
    private val peerRepository: PeerRepository,
    private val messageDao: MessageDao,
) : ChatDataSource {

    fun observeChats(): Flow<List<ChatSummary>> = combine(
        peerRepository.observeAll(),
        messageDao.observeLatestPerPeer(),
    ) { peers, latest -> mergeChats(peers, latest) }

    override fun observePeer(peerId: PeerId): Flow<Peer?> =
        peerRepository.observePeer(peerId)

    override fun observeConversation(peerId: PeerId): Flow<List<ChatMessage>> =
        messageDao.observeConversation(peerId.value).map { entities ->
            entities.map { it.toChatMessage() }
        }
}

/**
 * Pure merge step, kept separate so the ordering and fallback rules are
 * testable without Room or a real [PeerRepository].
 */
internal fun mergeChats(
    peers: List<Peer>,
    latest: List<MessageEntity>,
): List<ChatSummary> {
    val peerById = peers.associateBy { it.peerId }
    val peersWithMessages = latest.map { message ->
        val parsedPeerId = PeerId.parse(message.peerId)
        val peer = peerById[parsedPeerId]
        message.toSummary(
            peerId = parsedPeerId,
            nickname = peer?.nickname ?: "",
            trustState = peer?.trustState ?: TrustState.UNKNOWN,
        )
    }
    val peersWithoutMessages = peers
        .filter { peer -> latest.none { it.peerId == peer.peerId.value } }
        .map { peer ->
            ChatSummary(
                peerId = peer.peerId,
                nickname = peer.nickname,
                trustState = peer.trustState,
                lastMessageText = null,
                lastMessageAt = null,
                lastMessageStatus = null,
                lastMessageOutgoing = false,
            )
        }

    return (peersWithMessages + peersWithoutMessages).sortedWith(
        compareByDescending<ChatSummary> { it.lastMessageAt ?: Long.MIN_VALUE }
            .thenBy { it.nickname },
    )
}

private fun MessageEntity.toSummary(
    peerId: PeerId,
    nickname: String,
    trustState: TrustState,
): ChatSummary = ChatSummary(
    peerId = peerId,
    nickname = nickname,
    trustState = trustState,
    lastMessageText = String(body, StandardCharsets.UTF_8),
    lastMessageAt = createdAt,
    lastMessageStatus = status,
    lastMessageOutgoing = direction == MessageDirection.OUTGOING,
)

internal fun MessageEntity.toChatMessage(): ChatMessage = ChatMessage(
    messageId = MessageId.parse(messageId),
    text = String(body, StandardCharsets.UTF_8),
    createdAt = createdAt,
    outgoing = direction == MessageDirection.OUTGOING,
    status = status,
)
