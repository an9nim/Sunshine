package com.sunshine.mesh.coordinator

import com.sunshine.mesh.core.crypto.IdentityMaterial
import com.sunshine.mesh.core.crypto.e2e.E2ESession
import com.sunshine.mesh.core.crypto.e2e.E2ETestSupport
import com.sunshine.mesh.core.crypto.e2e.EnvelopeCodec
import com.sunshine.mesh.core.crypto.e2e.EnvelopeType
import com.sunshine.mesh.core.crypto.e2e.KeyExchangeCodec
import com.sunshine.mesh.core.crypto.e2e.ReceiptPayload
import com.sunshine.mesh.core.crypto.e2e.ReceiptStatus
import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.mesh.MeshHandleResult
import com.sunshine.mesh.core.mesh.MeshHeader
import com.sunshine.mesh.core.mesh.MeshPacket
import com.sunshine.mesh.core.mesh.MeshSendResult
import com.sunshine.mesh.core.mesh.MeshSender
import com.sunshine.mesh.core.net.MeshPolicy
import com.sunshine.mesh.data.message.MessageRecord
import com.sunshine.mesh.data.message.MessageStore
import com.sunshine.mesh.data.peer.PeerKeyStore
import com.sunshine.mesh.data.peer.PeerTrustGate
import com.sunshine.mesh.data.peer.PeerTrustStore
import com.sunshine.mesh.domain.model.MessageDirection
import com.sunshine.mesh.domain.model.MessageStatus
import com.sunshine.mesh.domain.model.Peer
import com.sunshine.mesh.domain.model.TrustResult
import com.sunshine.mesh.domain.model.TrustState
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshCoordinatorTest {

    @Test
    fun incomingKeyExchangeRequiresTrust() = runBlocking {
        val fixture = Fixture(E2ETestSupport.identity(1), E2ETestSupport.identity(40), this)
        val events = CopyOnWriteArrayList<MeshEvent>()
        val collector = collectEvents(fixture.coordinator, events)
        try {
            val result = fixture.coordinator.handle(fixture.keyExchangePacket())

            assertEquals(MeshHandleResult.Delivered, result)
            awaitUntil { events.any { it is MeshEvent.TrustRequired } }
            val event = events.filterIsInstance<MeshEvent.TrustRequired>().single()
            assertEquals(fixture.peerId, event.peerId)
            assertArrayEquals(fixture.peer.publicKey, event.publicKey)
            assertEquals(
                TrustState.UNKNOWN,
                fixture.peerStore.getPeer(fixture.peerId)?.trustState,
            )
        } finally {
            collector.cancel()
            fixture.close()
        }
    }

    @Test
    fun acceptTrustRejectsUnboundKeyAndAcceptsObservedKey() = runBlocking {
        val fixture = Fixture(E2ETestSupport.identity(1), E2ETestSupport.identity(40), this)
        val other = E2ETestSupport.identity(80)
        try {
            fixture.coordinator.handle(fixture.keyExchangePacket())
            fixture.sender.peers.value = setOf(fixture.peerId)

            assertFalse(fixture.coordinator.acceptTrust(fixture.peerId, other.publicKey))
            assertTrue(fixture.coordinator.acceptTrust(fixture.peerId, fixture.peer.publicKey))
            assertEquals(
                TrustState.TOFU_ACCEPTED,
                fixture.peerStore.getPeer(fixture.peerId)?.trustState,
            )
            assertTrue(
                fixture.sender.sent.any {
                    EnvelopeCodec.peekHeader(it.envelope).type == EnvelopeType.KEY_EXCHANGE
                },
            )
        } finally {
            other.close()
            fixture.close()
        }
    }

    @Test
    fun incomingTextIsStoredAndReceiptIsSent() = runBlocking {
        val fixture = Fixture(E2ETestSupport.identity(1), E2ETestSupport.identity(40), this)
        val events = CopyOnWriteArrayList<MeshEvent>()
        val collector = collectEvents(fixture.coordinator, events)
        val peerSession = E2ETestSupport.session(fixture.peer, fixture.localIdentity)
        try {
            fixture.coordinator.handle(fixture.keyExchangePacket())
            fixture.sender.peers.value = setOf(fixture.peerId)
            assertTrue(fixture.coordinator.acceptTrust(fixture.peerId, fixture.peer.publicKey))

            val messageId = MessageId.random()
            val text = "hello from peer"
            val packets = fixture.textPackets(peerSession, messageId, text)
            for (packet in packets) {
                assertEquals(MeshHandleResult.Delivered, fixture.coordinator.handle(packet))
            }

            awaitUntil { fixture.messages.byMsgId(messageId)?.status == MessageStatus.RECEIVED }
            val stored = requireNotNull(fixture.messages.byMsgId(messageId))
            assertEquals(fixture.peerId, stored.peerId)
            assertEquals(text, String(stored.body, StandardCharsets.UTF_8))

            awaitUntil { events.any { it is MeshEvent.MessageReceived } }
            val received = events.filterIsInstance<MeshEvent.MessageReceived>().single()
            assertEquals(messageId, received.messageId)
            assertEquals(text, received.text)

            awaitUntil {
                fixture.sender.sent.any { sent ->
                    EnvelopeCodec.peekHeader(sent.envelope).type == EnvelopeType.RECEIPT
                }
            }
            val receiptSent = fixture.sender.sent.first { sent ->
                EnvelopeCodec.peekHeader(sent.envelope).type == EnvelopeType.RECEIPT
            }
            val fragment = peerSession.openFragment(receiptSent.envelope, EnvelopeType.RECEIPT)
            try {
                val receipt = ReceiptPayload.decode(fragment.plaintext)
                assertEquals(messageId, receipt.messageId)
                assertEquals(ReceiptStatus.DELIVERED, receipt.status)
            } finally {
                fragment.close()
            }
        } finally {
            peerSession.close()
            collector.cancel()
            fixture.close()
        }
    }

    @Test
    fun outgoingTextWithoutPeerKeyStoresPendingAndSendsKeyExchange() = runBlocking {
        val fixture = Fixture(E2ETestSupport.identity(1), E2ETestSupport.identity(40), this)
        try {
            fixture.sender.peers.value = setOf(fixture.peerId)
            val result = fixture.coordinator.sendText(fixture.peerId, "hello")

            assertTrue(result is SendTextResult.Pending)
            assertEquals(PendingReason.AWAITING_PEER_KEY, (result as SendTextResult.Pending).reason)
            val stored = requireNotNull(fixture.messages.byMsgId(result.messageId))
            assertEquals(MessageStatus.PENDING, stored.status)
            assertNull(stored.packet)
            assertTrue(
                fixture.sender.sent.any {
                    EnvelopeCodec.peekHeader(it.envelope).type == EnvelopeType.KEY_EXCHANGE
                },
            )
            assertFalse(
                fixture.sender.sent.any {
                    EnvelopeCodec.peekHeader(it.envelope).type == EnvelopeType.TEXT
                },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun outgoingTextWithTrustedPeerIsEncryptedAndMarkedSent() = runBlocking {
        val fixture = Fixture(E2ETestSupport.identity(1), E2ETestSupport.identity(40), this)
        val peerSession = E2ETestSupport.session(fixture.peer, fixture.localIdentity)
        try {
            fixture.peerStore.put(
                fixture.peerId,
                fixture.peer.publicKey,
                TrustState.TOFU_ACCEPTED,
            )
            fixture.sender.peers.value = setOf(fixture.peerId)

            val result = fixture.coordinator.sendText(fixture.peerId, "hello from local")

            assertTrue(result is SendTextResult.Sent)
            val messageId = (result as SendTextResult.Sent).messageId
            val stored = requireNotNull(fixture.messages.byMsgId(messageId))
            assertEquals(MessageStatus.SENT, stored.status)
            assertNotNull(stored.packet)

            val textSent = fixture.sender.sent.first {
                EnvelopeCodec.peekHeader(it.envelope).type == EnvelopeType.TEXT
            }
            val fragment = peerSession.openFragment(textSent.envelope)
            try {
                assertEquals(
                    "hello from local",
                    String(fragment.plaintext, StandardCharsets.UTF_8),
                )
            } finally {
                fragment.close()
            }
        } finally {
            peerSession.close()
            fixture.close()
        }
    }

    @Test
    fun deliveryReceiptMarksOutgoingMessageDelivered() = runBlocking {
        val fixture = Fixture(E2ETestSupport.identity(1), E2ETestSupport.identity(40), this)
        val events = CopyOnWriteArrayList<MeshEvent>()
        val collector = collectEvents(fixture.coordinator, events)
        val peerSession = E2ETestSupport.session(fixture.peer, fixture.localIdentity)
        try {
            fixture.peerStore.put(
                fixture.peerId,
                fixture.peer.publicKey,
                TrustState.TOFU_ACCEPTED,
            )
            fixture.sender.peers.value = setOf(fixture.peerId)
            val sent = fixture.coordinator.sendText(fixture.peerId, "deliver me")
            assertTrue(sent is SendTextResult.Sent)
            val messageId = (sent as SendTextResult.Sent).messageId

            assertEquals(
                MeshHandleResult.Delivered,
                fixture.coordinator.handle(fixture.receiptPacket(peerSession, messageId)),
            )

            awaitUntil { fixture.messages.byMsgId(messageId)?.status == MessageStatus.DELIVERED }
            awaitUntil {
                events.any {
                    it is MeshEvent.DeliveryChanged &&
                        it.messageId == messageId &&
                        it.status == MessageStatus.DELIVERED
                }
            }
        } finally {
            peerSession.close()
            collector.cancel()
            fixture.close()
        }
    }

    @Test
    fun changedKeyIsSurfacedAndOldSessionIsDropped() = runBlocking {
        val fixture = Fixture(E2ETestSupport.identity(1), E2ETestSupport.identity(40), this)
        val attacker = E2ETestSupport.identity(80)
        val events = CopyOnWriteArrayList<MeshEvent>()
        val collector = collectEvents(fixture.coordinator, events)
        try {
            fixture.peerStore.put(
                fixture.peerId,
                fixture.peer.publicKey,
                TrustState.TOFU_ACCEPTED,
            )
            fixture.sender.peers.value = setOf(fixture.peerId)
            assertTrue(fixture.coordinator.sendText(fixture.peerId, "before") is SendTextResult.Sent)

            val result = fixture.coordinator.handle(
                fixture.keyExchangePacket(
                    from = attacker,
                    senderId = fixture.peerId,
                    recipientId = fixture.localIdentity.peerId,
                ),
            )
            assertEquals(MeshHandleResult.Delivered, result)
            awaitUntil { events.any { it is MeshEvent.KeyChanged } }
            assertEquals(
                TrustState.KEY_CHANGED,
                fixture.peerStore.getPeer(fixture.peerId)?.trustState,
            )

            val after = fixture.coordinator.sendText(fixture.peerId, "after")
            assertTrue(after is SendTextResult.Pending)
            assertEquals(PendingReason.KEY_CHANGED, (after as SendTextResult.Pending).reason)
        } finally {
            attacker.close()
            collector.cancel()
            fixture.close()
        }
    }

    @Test
    fun outboxRetriesPendingMessageWhenLinkAppears() = runBlocking {
        val fixture = Fixture(E2ETestSupport.identity(1), E2ETestSupport.identity(40), this)
        try {
            fixture.peerStore.put(
                fixture.peerId,
                fixture.peer.publicKey,
                TrustState.TOFU_ACCEPTED,
            )
            fixture.start()

            val result = fixture.coordinator.sendText(fixture.peerId, "retry me")
            assertTrue(result is SendTextResult.Pending)
            assertEquals(PendingReason.NO_LINK, (result as SendTextResult.Pending).reason)
            assertEquals(MessageStatus.PENDING, fixture.messages.byMsgId(result.messageId)?.status)

            fixture.sender.peers.value = setOf(fixture.peerId)

            awaitUntil { fixture.messages.byMsgId(result.messageId)?.status == MessageStatus.SENT }
        } finally {
            fixture.close()
        }
    }

    private fun CoroutineScope.collectEvents(
        coordinator: MeshCoordinator,
        events: MutableList<MeshEvent>,
    ) = launch(start = CoroutineStart.UNDISPATCHED) {
        coordinator.events.collect { event -> events.add(event) }
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(2_000) {
            while (!condition()) delay(5)
        }
    }

    private class Fixture(
        local: IdentityMaterial,
        val peer: IdentityMaterial,
        scope: CoroutineScope,
    ) {
        val localIdentity: IdentityMaterial = local
        val localPeerId: PeerId = local.peerId
        val peerId: PeerId = peer.peerId
        val peerStore = FakePeerStore()
        val messages = FakeMessageStore()
        val sender = FakeSender(localPeerId)
        val coordinator = MeshCoordinator(
            identity = local,
            peerKeyStore = peerStore,
            trustGate = PeerTrustGate(peerStore),
            messageStore = messages,
            scope = scope,
            retryIntervalMs = 60_000,
        ).also { it.attach(sender) }

        fun start() = coordinator.start()

        fun close() {
            coordinator.close()
            peer.close()
        }

        fun keyExchangePacket(
            from: IdentityMaterial = peer,
            senderId: PeerId = peerId,
            recipientId: PeerId = localPeerId,
        ): MeshPacket {
            val messageId = MessageId.random()
            val envelope = KeyExchangeCodec.encode(
                messageId = messageId,
                senderId = senderId,
                recipientId = recipientId,
                publicKey = from.publicKey,
            )
            return meshPacket(envelope, senderId, recipientId, messageId)
        }

        fun textPackets(
            session: E2ESession,
            messageId: MessageId,
            text: String,
            senderId: PeerId = peerId,
            recipientId: PeerId = localPeerId,
        ): List<MeshPacket> = session.sealText(messageId, text).map { envelope ->
            meshPacket(envelope, senderId, recipientId, messageId)
        }

        fun receiptPacket(
            session: E2ESession,
            acknowledged: MessageId,
            senderId: PeerId = peerId,
            recipientId: PeerId = localPeerId,
        ): MeshPacket {
            val receiptId = MessageId.random()
            val payload = ReceiptPayload.encode(acknowledged, ReceiptStatus.DELIVERED)
            try {
                val envelope = session.seal(EnvelopeType.RECEIPT, receiptId, payload).single()
                return meshPacket(envelope, senderId, recipientId, receiptId)
            } finally {
                payload.fill(0)
            }
        }

        private fun meshPacket(
            envelope: ByteArray,
            senderId: PeerId,
            recipientId: PeerId,
            messageId: MessageId,
        ): MeshPacket = MeshPacket(
            MeshHeader(
                version = MeshHeader.VERSION,
                ttl = MeshPolicy.DEFAULT_TTL,
                msgId = messageId,
                senderId = senderId,
                recipientId = recipientId,
            ),
            envelope,
        )
    }

    private class FakeSender(
        private val localPeerId: PeerId,
    ) : MeshSender {

        override val peers = MutableStateFlow<Set<PeerId>>(emptySet())

        data class Sent(
            val header: MeshHeader,
            val envelope: ByteArray,
        )

        val sent = CopyOnWriteArrayList<Sent>()

        @Volatile
        var retryRequests = 0

        override fun start() = Unit

        override fun wrap(
            envelope: ByteArray,
            recipientId: PeerId,
            msgId: MessageId,
        ): ByteArray = MeshHeader(
            version = MeshHeader.VERSION,
            ttl = MeshPolicy.DEFAULT_TTL,
            msgId = msgId,
            senderId = localPeerId,
            recipientId = recipientId,
        ).encode() + envelope

        override suspend fun sendPacket(packet: ByteArray): MeshSendResult {
            val header = MeshHeader.decode(packet.copyOfRange(0, MeshHeader.SIZE))
            val envelope = packet.copyOfRange(MeshHeader.SIZE, MeshPacket.TOTAL_BYTES)
            sent.add(Sent(header, envelope))
            val linked = peers.value.size
            return MeshSendResult(attempted = linked, delivered = linked)
        }

        override fun requestRetry() {
            retryRequests += 1
        }

        override fun close() = Unit
    }

    private class FakePeerStore : PeerTrustStore, PeerKeyStore {

        private val lock = Any()
        private val peers = mutableMapOf<PeerId, Peer>()

        fun put(peerId: PeerId, publicKey: ByteArray, trustState: TrustState) {
            synchronized(lock) {
                peers[peerId] = peer(peerId, publicKey, trustState)
            }
        }

        override suspend fun checkAndUpdateKey(
            peerId: PeerId,
            newPublicKey: ByteArray,
        ): TrustResult = synchronized(lock) {
            val existing = peers[peerId]
            if (existing == null) {
                peers[peerId] = peer(peerId, newPublicKey, TrustState.UNKNOWN)
                return@synchronized TrustResult.NEW
            }
            if (!existing.lastSeenPublicKey.contentEquals(newPublicKey)) {
                peers[peerId] = Peer(
                    peerId = peerId,
                    nickname = existing.nickname,
                    firstSeenPublicKey = existing.firstSeenPublicKey.copyOf(),
                    lastSeenPublicKey = newPublicKey.copyOf(),
                    trustState = TrustState.KEY_CHANGED,
                    firstSeenAt = existing.firstSeenAt,
                    lastSeenAt = existing.lastSeenAt + 1,
                    verifiedAt = null,
                )
                return@synchronized TrustResult.CHANGED
            }
            TrustResult.SAME
        }

        override suspend fun getTrustState(peerId: PeerId): TrustState =
            synchronized(lock) { peers[peerId]?.trustState ?: TrustState.UNKNOWN }

        override suspend fun getPeer(peerId: PeerId): Peer? =
            synchronized(lock) { peers[peerId] }

        override suspend fun acceptKey(
            peerId: PeerId,
            expectedPublicKey: ByteArray,
        ): Boolean = synchronized(lock) {
            val existing = peers[peerId] ?: return@synchronized false
            if (!existing.lastSeenPublicKey.contentEquals(expectedPublicKey)) {
                return@synchronized false
            }
            val nextState = if (existing.trustState == TrustState.VERIFIED) {
                TrustState.VERIFIED
            } else {
                TrustState.TOFU_ACCEPTED
            }
            peers[peerId] = Peer(
                peerId = peerId,
                nickname = existing.nickname,
                firstSeenPublicKey = existing.firstSeenPublicKey.copyOf(),
                lastSeenPublicKey = existing.lastSeenPublicKey.copyOf(),
                trustState = nextState,
                firstSeenAt = existing.firstSeenAt,
                lastSeenAt = existing.lastSeenAt,
                verifiedAt = if (nextState == TrustState.VERIFIED) {
                    existing.verifiedAt
                } else {
                    null
                },
            )
            true
        }

        private fun peer(
            peerId: PeerId,
            publicKey: ByteArray,
            trustState: TrustState,
        ): Peer = Peer(
            peerId = peerId,
            nickname = "",
            firstSeenPublicKey = publicKey.copyOf(),
            lastSeenPublicKey = publicKey.copyOf(),
            trustState = trustState,
            firstSeenAt = 0,
            lastSeenAt = 0,
            verifiedAt = null,
        )
    }

    private class FakeMessageStore : MessageStore {

        private val lock = Any()
        private val records = LinkedHashMap<MessageId, MessageRecord>()

        fun byMsgId(msgId: MessageId): MessageRecord? = synchronized(lock) {
            records.values.firstOrNull { it.msgId == msgId }?.copy(
                body = records.values.first { it.msgId == msgId }.body.copyOf(),
                packet = records.values.first { it.msgId == msgId }.packet?.copyOf(),
            )
        }

        override suspend fun insert(message: MessageRecord): Boolean = synchronized(lock) {
            if (records.containsKey(message.messageId) ||
                records.values.any { it.msgId == message.msgId }
            ) {
                return@synchronized false
            }
            records[message.messageId] = message.copy(
                body = message.body.copyOf(),
                packet = message.packet?.copyOf(),
            )
            true
        }

        override suspend fun findByMsgId(msgId: MessageId): MessageRecord? = byMsgId(msgId)

        override suspend fun findPending(limit: Int): List<MessageRecord> = synchronized(lock) {
            records.values
                .filter {
                    it.direction == MessageDirection.OUTGOING &&
                        it.status == MessageStatus.PENDING
                }
                .sortedBy { it.createdAt }
                .take(limit)
                .map {
                    it.copy(
                        body = it.body.copyOf(),
                        packet = it.packet?.copyOf(),
                    )
                }
        }

        override suspend fun updateStatus(msgId: MessageId, status: MessageStatus): Boolean =
            synchronized(lock) {
                val existing = records.values.firstOrNull { it.msgId == msgId }
                    ?: return@synchronized false
                val allowedFrom = when (status) {
                    MessageStatus.SENT -> setOf(MessageStatus.PENDING)
                    MessageStatus.DELIVERED,
                    MessageStatus.FAILED,
                    -> setOf(MessageStatus.PENDING, MessageStatus.SENT)

                    MessageStatus.PENDING,
                    MessageStatus.RECEIVED,
                    -> null
                }
                if (allowedFrom != null && existing.status !in allowedFrom) {
                    return@synchronized false
                }
                records[existing.messageId] = existing.copy(status = status)
                true
            }

        override suspend fun updatePacket(msgId: MessageId, packet: ByteArray?): Boolean =
            synchronized(lock) {
                val existing = records.values.firstOrNull { it.msgId == msgId }
                    ?: return@synchronized false
                records[existing.messageId] = existing.copy(packet = packet?.copyOf())
                true
            }
    }
}
