package com.sunshine.mesh.data.chat

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.data.local.entity.MessageEntity
import com.sunshine.mesh.domain.model.MessageDirection
import com.sunshine.mesh.domain.model.MessageStatus
import com.sunshine.mesh.domain.model.Peer
import com.sunshine.mesh.domain.model.TrustState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryTest {

    @Test
    fun sortsByLatestMessageAndKeepsPeersWithoutMessages() {
        val ann = peer(PEER_A, "Ann", TrustState.VERIFIED)
        val bob = peer(PEER_B, "Bob", TrustState.UNKNOWN)
        val cid = peer(PEER_C, "Cid", TrustState.TOFU_ACCEPTED)

        val result = mergeChats(
            peers = listOf(ann, bob, cid),
            latest = listOf(
                message(PEER_A, "hi", createdAt = 200, direction = MessageDirection.OUTGOING),
                message(PEER_B, "yo", createdAt = 300, direction = MessageDirection.INCOMING),
            ),
        )

        assertEquals(listOf(PEER_B, PEER_A, PEER_C), result.map { it.peerId })
        assertEquals("yo", result[0].lastMessageText)
        assertFalse(result[0].lastMessageOutgoing)
        assertEquals("hi", result[1].lastMessageText)
        assertTrue(result[1].lastMessageOutgoing)
        assertEquals(MessageStatus.SENT, result[1].lastMessageStatus)
        assertEquals(TrustState.VERIFIED, result[1].trustState)
        assertNull(result[2].lastMessageText)
        assertNull(result[2].lastMessageAt)
        assertNull(result[2].lastMessageStatus)
    }

    @Test
    fun messageFromUnknownPeerUsesFallbackIdentity() {
        val result = mergeChats(
            peers = emptyList(),
            latest = listOf(
                message(PEER_A, "hello", createdAt = 10, direction = MessageDirection.INCOMING),
            ),
        )

        assertEquals(1, result.size)
        assertEquals("", result[0].nickname)
        assertEquals(TrustState.UNKNOWN, result[0].trustState)
        assertEquals("hello", result[0].lastMessageText)
    }

    @Test
    fun conversationEntityMapsToChatMessage() {
        val entity = message(
            peerId = PEER_A,
            text = "привет",
            createdAt = 42L,
            direction = MessageDirection.INCOMING,
            status = MessageStatus.RECEIVED,
        )

        val message = entity.toChatMessage()

        assertEquals("привет", message.text)
        assertEquals(42L, message.createdAt)
        assertFalse(message.outgoing)
        assertEquals(MessageStatus.RECEIVED, message.status)
    }

    private fun peer(
        peerId: PeerId,
        nickname: String,
        trustState: TrustState,
    ): Peer = Peer(
        peerId = peerId,
        nickname = nickname,
        firstSeenPublicKey = ByteArray(32),
        lastSeenPublicKey = ByteArray(32),
        trustState = trustState,
        firstSeenAt = 1L,
        lastSeenAt = 2L,
        verifiedAt = null,
    )

    private fun message(
        peerId: PeerId,
        text: String,
        createdAt: Long,
        direction: MessageDirection,
        status: MessageStatus = MessageStatus.SENT,
    ): MessageEntity = MessageEntity(
        messageId = "0123456789abcdef0123456789abcdef",
        msgId = "0123456789abcdef0123456789abcdef",
        peerId = peerId.value,
        direction = direction,
        body = text.toByteArray(Charsets.UTF_8),
        createdAt = createdAt,
        status = status,
        packet = null,
    )

    private companion object {
        val PEER_A = PeerId.parse("aaaaaaaaaaaaaaaa")
        val PEER_B = PeerId.parse("bbbbbbbbbbbbbbbb")
        val PEER_C = PeerId.parse("cccccccccccccccc")
    }
}
