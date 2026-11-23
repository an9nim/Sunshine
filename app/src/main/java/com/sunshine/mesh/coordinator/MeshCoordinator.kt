package com.sunshine.mesh.coordinator

import com.sunshine.mesh.core.crypto.IdentityMaterial
import com.sunshine.mesh.core.crypto.SafetyNumber
import com.sunshine.mesh.core.crypto.e2e.E2EException
import com.sunshine.mesh.core.crypto.e2e.E2ESession
import com.sunshine.mesh.core.crypto.e2e.EnvelopeCodec
import com.sunshine.mesh.core.crypto.e2e.EnvelopeHeader
import com.sunshine.mesh.core.crypto.e2e.EnvelopeType
import com.sunshine.mesh.core.crypto.e2e.HkdfSession
import com.sunshine.mesh.core.crypto.e2e.KeyExchangeCodec
import com.sunshine.mesh.core.crypto.e2e.ReceiptPayload
import com.sunshine.mesh.core.crypto.e2e.ReceiptStatus
import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.mesh.MeshHandleResult
import com.sunshine.mesh.core.mesh.MeshPacket
import com.sunshine.mesh.core.mesh.MeshPacketHandler
import com.sunshine.mesh.core.mesh.MeshSendResult
import com.sunshine.mesh.core.mesh.MeshSender
import com.sunshine.mesh.core.mesh.OutboxCodec
import com.sunshine.mesh.core.net.MeshPolicy
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import com.sunshine.mesh.data.message.MessageRecord
import com.sunshine.mesh.data.message.MessageStore
import com.sunshine.mesh.data.peer.PeerKeyStore
import com.sunshine.mesh.data.peer.PeerTrustGate
import com.sunshine.mesh.domain.message.FragmentAssembler
import com.sunshine.mesh.domain.model.AssembledMessage
import com.sunshine.mesh.domain.model.MessageDirection
import com.sunshine.mesh.domain.model.MessageStatus
import com.sunshine.mesh.domain.model.OpenedFragment
import com.sunshine.mesh.domain.model.TrustDecision
import com.sunshine.mesh.domain.model.TrustState
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Stage 5 integration point: E2E sessions, trust, Room and the mesh layer.
 *
 * MeshEngine remains the only collector of Transport.incoming. It calls
 * [handle] for packets addressed to this node, stores packets when this class
 * returns [MeshHandleResult.StoreNeeded], and retries them later. Outbound
 * messages are persisted as PENDING before encryption, so a process restart
 * never loses a message that the user already submitted.
 *
 * Trust rules:
 * - a new peer must present a key that hashes to its envelope senderId;
 * - a changed key is surfaced as [MeshEvent.KeyChanged] and never accepted;
 * - [acceptTrust] only accepts a key whose derived PeerId equals the peer id.
 */
class MeshCoordinator(
    private val identity: IdentityMaterial,
    private val peerKeyStore: PeerKeyStore,
    private val trustGate: PeerTrustGate,
    private val messageStore: MessageStore,
    scope: CoroutineScope,
    private val assembler: FragmentAssembler = FragmentAssembler(),
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val retryIntervalMs: Long = MeshPolicy.RETRY_INTERVAL_MS,
) : MeshPacketHandler, Closeable {

    val localPeerId: PeerId = identity.peerId

    private val ownPublicKey = identity.publicKey
    private val coordinatorJob = Job(scope.coroutineContext[Job])
    private val coordinatorScope = CoroutineScope(scope.coroutineContext + coordinatorJob)
    private val retrySignal = Channel<Unit>(Channel.CONFLATED)
    private val sessionMutex = Mutex()
    private val sessions = ConcurrentHashMap<PeerId, E2ESession>()
    private val outboxMutex = Mutex()
    private val eventsBuffer = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 64)

    private val notificationLock = Any()
    private val trustNotified = mutableMapOf<PeerId, String>()
    private val keyChangedNotified = mutableMapOf<PeerId, String>()
    private val keyExchangeSent = mutableSetOf<PeerId>()

    @Volatile
    private var closed = false

    private var started = false
    private var sender: MeshSender? = null

    val events: SharedFlow<MeshEvent> = eventsBuffer.asSharedFlow()

    /**
     * Wires the outbound mesh facade. Called once because MeshEngine and the
     * coordinator reference each other: the engine needs a handler first, the
     * coordinator needs the engine second.
     */
    @Synchronized
    fun attach(sender: MeshSender) {
        check(!closed) { "coordinator is closed" }
        check(!started) { "coordinator already started" }
        check(this.sender == null) { "sender already attached" }
        this.sender = sender
    }

    /** Starts the mesh sender, the outbox retry loop and the link observer. */
    fun start() {
        if (closed) throw TransportException(TransportError.INTERNAL)
        if (started) return
        val mesh = sender ?: error("sender is not attached")

        mesh.start()
        started = true

        coordinatorScope.launch {
            mesh.peers.collect { linked ->
                synchronized(notificationLock) {
                    keyExchangeSent.retainAll(linked)
                }
                if (linked.isNotEmpty()) retrySignal.trySend(Unit)
            }
        }
        coordinatorScope.launch {
            while (isActive) {
                withTimeoutOrNull(retryIntervalMs) { retrySignal.receive() }
                flushOutbox()
            }
        }
        retrySignal.trySend(Unit)
    }

    override fun close() {
        if (closed) return
        closed = true
        coordinatorJob.cancel()
        sender?.close()
        sessions.values.forEach { it.close() }
        sessions.clear()
        assembler.clear()
        synchronized(notificationLock) {
            trustNotified.clear()
            keyChangedNotified.clear()
            keyExchangeSent.clear()
        }
        ownPublicKey.fill(0)
        identity.close()
    }

    override suspend fun handle(packet: MeshPacket): MeshHandleResult {
        if (closed) return MeshHandleResult.Rejected(TransportError.INTERNAL)

        val header = try {
            EnvelopeCodec.peekHeader(packet.envelope)
        } catch (error: E2EException) {
            Timber.w("Inbound envelope rejected: code=%s", error.code)
            return MeshHandleResult.Rejected(TransportError.PROTOCOL_ERROR)
        }

        if (header.recipientId != localPeerId || header.senderId == localPeerId) {
            return MeshHandleResult.Rejected(TransportError.PROTOCOL_ERROR)
        }

        return try {
            when (header.type) {
                EnvelopeType.KEY_EXCHANGE -> handleKeyExchange(packet, header)
                EnvelopeType.TEXT -> handleText(packet, header)
                EnvelopeType.RECEIPT -> handleReceipt(packet, header)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: E2EException) {
            Timber.w(
                "Inbound crypto failed: msgId=%s peerId=%s code=%s",
                header.messageId,
                header.senderId,
                error.code,
            )
            MeshHandleResult.Rejected(TransportError.PROTOCOL_ERROR)
        } catch (error: Exception) {
            Timber.w("Inbound failed: msgId=%s peerId=%s", header.messageId, header.senderId)
            MeshHandleResult.Rejected(TransportError.INTERNAL)
        }
    }

    /**
     * Stores a text message before any network work and then tries to send it.
     * A missing peer key or pending trust is not an error: the row stays
     * PENDING and the outbox retry loop picks it up after the handshake.
     */
    suspend fun sendText(peerId: PeerId, text: String): SendTextResult {
        if (closed) return SendTextResult.Failed(null, SendError.INTERNAL)
        if (sender == null) return SendTextResult.Failed(null, SendError.INTERNAL)

        val body = text.toByteArray(StandardCharsets.UTF_8)
        try {
            if (text.isBlank()) {
                return SendTextResult.Failed(null, SendError.INVALID_TEXT)
            }
            if (body.size > E2ESession.MAX_MESSAGE_BYTES) {
                return SendTextResult.Failed(null, SendError.MESSAGE_TOO_LARGE)
            }

            val messageId = MessageId.random(random)
            val record = MessageRecord(
                messageId = messageId,
                msgId = messageId,
                peerId = peerId,
                direction = MessageDirection.OUTGOING,
                body = body.copyOf(),
                createdAt = now(),
                status = MessageStatus.PENDING,
                packet = null,
            )
            val inserted = try {
                messageStore.insert(record)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w("Outbox insert failed: msgId=%s peerId=%s", messageId, peerId)
                return SendTextResult.Failed(null, SendError.STORAGE_FAILED)
            }
            if (!inserted) {
                return SendTextResult.Failed(null, SendError.STORAGE_FAILED)
            }

            return when (val state = ensureOutgoingSession(peerId)) {
                SessionState.READY -> {
                    sendKeyExchangeIfNeeded(peerId)
                    sendNewMessage(record)
                }

                SessionState.NO_PEER_KEY -> SendTextResult.Pending(
                    messageId,
                    PendingReason.AWAITING_PEER_KEY,
                )

                SessionState.TRUST_REQUIRED -> SendTextResult.Pending(
                    messageId,
                    PendingReason.TRUST_REQUIRED,
                )

                SessionState.KEY_CHANGED -> SendTextResult.Pending(
                    messageId,
                    PendingReason.KEY_CHANGED,
                )

                SessionState.FAILED -> {
                    markFailed(messageId)
                    SendTextResult.Failed(messageId, SendError.CRYPTO_FAILED)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w("Send failed: peerId=%s", peerId)
            return SendTextResult.Failed(null, SendError.INTERNAL)
        } finally {
            body.fill(0)
        }
    }

    /**
     * Accepts the key the user saw and rebuilds the session. The old session is
     * closed before a new one is derived, then stored inbound packets and the
     * outbox are woken.
     */
    suspend fun acceptTrust(peerId: PeerId, expectedPublicKey: ByteArray): Boolean {
        if (closed) return false
        val key = expectedPublicKey.copyOf()
        try {
            val derived = try {
                PeerId.fromPublicKey(key)
            } catch (error: IllegalArgumentException) {
                return false
            }
            if (derived != peerId) {
                Timber.w("Trust rejected: peerId=%s code=KEY_ID_MISMATCH", peerId)
                return false
            }

            val accepted = try {
                peerKeyStore.acceptKey(peerId, key)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w("Trust accept failed: peerId=%s", peerId)
                return false
            }
            if (!accepted) {
                Timber.w("Trust accept rejected: peerId=%s code=KEY_MOVED", peerId)
                return false
            }

            invalidateSession(peerId)
            clearNotifications(peerId)
            clearKeyExchangeSent(peerId)
            if (!ensureSession(peerId, key)) {
                Timber.w("Trust accepted without session: peerId=%s", peerId)
                return false
            }

            sendKeyExchangeIfNeeded(peerId)
            sender?.requestRetry()
            retrySignal.trySend(Unit)
            return true
        } finally {
            key.fill(0)
        }
    }

    /**
     * Sends every stored PENDING message that has a usable session. Safe to
     * call concurrently: runs are serialized by [outboxMutex].
     */
    suspend fun flushOutbox() {
        if (closed || !started) return
        outboxMutex.withLock {
            val pending = try {
                messageStore.findPending()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w("Outbox load failed")
                return@withLock
            }

            for (record in pending) {
                if (closed) return@withLock
                try {
                    flushRecord(record)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.w("Outbox retry failed: msgId=%s peerId=%s", record.msgId, record.peerId)
                }
            }
        }
    }

    private suspend fun handleKeyExchange(
        packet: MeshPacket,
        header: EnvelopeHeader,
    ): MeshHandleResult {
        val exchange = try {
            KeyExchangeCodec.decode(packet.envelope)
        } catch (error: E2EException) {
            Timber.w("Key exchange rejected: peerId=%s code=%s", header.senderId, error.code)
            return MeshHandleResult.Rejected(TransportError.PROTOCOL_ERROR)
        }

        try {
            val publicKey = exchange.publicKey
            val derivedPeerId = try {
                PeerId.fromPublicKey(publicKey)
            } catch (error: IllegalArgumentException) {
                return MeshHandleResult.Rejected(TransportError.PROTOCOL_ERROR)
            }

            val existing = try {
                peerKeyStore.getPeer(header.senderId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return MeshHandleResult.StoreNeeded
            }

            // A new label must be bound to its key. An existing label may show
            // a changed key so the trust gate can emit KEY_CHANGED instead of
            // silently accepting a different identity.
            if (existing == null && derivedPeerId != header.senderId) {
                Timber.w("Key exchange rejected: peerId=%s code=KEY_ID_MISMATCH", header.senderId)
                return MeshHandleResult.Rejected(TransportError.PROTOCOL_ERROR)
            }

            val hadSession = hasSession(header.senderId)
            val decision = try {
                trustGate.evaluate(header.senderId, ownPublicKey, publicKey)
            } catch (error: CancellationException) {
                throw error
            } catch (error: E2EException) {
                return MeshHandleResult.Rejected(TransportError.PROTOCOL_ERROR)
            } catch (error: Exception) {
                return MeshHandleResult.StoreNeeded
            }

            return when (decision) {
                is TrustDecision.Allowed -> {
                    val allowedKey = decision.peerPublicKey
                    val ready = try {
                        ensureSession(header.senderId, allowedKey)
                    } finally {
                        allowedKey.fill(0)
                    }
                    if (!ready) {
                        Timber.w("Key exchange rejected: peerId=%s code=KEY_BINDING", header.senderId)
                        return MeshHandleResult.Rejected(TransportError.PROTOCOL_ERROR)
                    }

                    clearNotifications(header.senderId)
                    if (!hadSession) sendKeyExchangeIfNeeded(header.senderId)
                    sender?.requestRetry()
                    retrySignal.trySend(Unit)
                    Timber.i("Key exchange accepted: peerId=%s", header.senderId)
                    MeshHandleResult.Delivered
                }

                is TrustDecision.TrustRequired -> {
                    invalidateSession(header.senderId)
                    announceTrust(header.senderId, decision.safetyNumber, publicKey)
                    MeshHandleResult.Delivered
                }

                is TrustDecision.KeyChanged -> {
                    invalidateSession(header.senderId)
                    clearKeyExchangeSent(header.senderId)
                    announceKeyChanged(header.senderId, decision.safetyNumber, publicKey)
                    MeshHandleResult.Delivered
                }
            }
        } finally {
            exchange.close()
        }
    }

    private suspend fun handleText(
        packet: MeshPacket,
        header: EnvelopeHeader,
    ): MeshHandleResult {
        if (!hasSession(header.senderId)) {
            when (establishIncomingSession(header.senderId)) {
                SessionState.READY -> sender?.requestRetry()
                SessionState.NO_PEER_KEY,
                SessionState.TRUST_REQUIRED,
                SessionState.KEY_CHANGED,
                SessionState.FAILED,
                -> return MeshHandleResult.StoreNeeded
            }
        }

        val fragment = try {
            openFragment(header.senderId, packet.envelope, EnvelopeType.TEXT)
                ?: return MeshHandleResult.StoreNeeded
        } catch (error: E2EException) {
            Timber.w(
                "Text rejected: msgId=%s peerId=%s code=%s",
                header.messageId,
                header.senderId,
                error.code,
            )
            return MeshHandleResult.Rejected(TransportError.PROTOCOL_ERROR)
        } catch (error: IllegalStateException) {
            return MeshHandleResult.StoreNeeded
        }

        try {
            val assembled = assembler.accept(fragment) ?: return MeshHandleResult.Delivered
            try {
                return storeIncoming(assembled)
            } finally {
                assembled.close()
            }
        } finally {
            fragment.close()
        }
    }

    private suspend fun handleReceipt(
        packet: MeshPacket,
        header: EnvelopeHeader,
    ): MeshHandleResult {
        if (!hasSession(header.senderId)) {
            when (establishIncomingSession(header.senderId)) {
                SessionState.READY -> Unit
                SessionState.NO_PEER_KEY,
                SessionState.TRUST_REQUIRED,
                SessionState.KEY_CHANGED,
                SessionState.FAILED,
                -> return MeshHandleResult.StoreNeeded
            }
        }

        val fragment = try {
            openFragment(header.senderId, packet.envelope, EnvelopeType.RECEIPT)
                ?: return MeshHandleResult.StoreNeeded
        } catch (error: E2EException) {
            Timber.w(
                "Receipt rejected: msgId=%s peerId=%s code=%s",
                header.messageId,
                header.senderId,
                error.code,
            )
            return MeshHandleResult.Rejected(TransportError.PROTOCOL_ERROR)
        } catch (error: IllegalStateException) {
            return MeshHandleResult.StoreNeeded
        }

        try {
            val receipt = try {
                ReceiptPayload.decode(fragment.plaintext)
            } catch (error: E2EException) {
                return MeshHandleResult.Rejected(TransportError.PROTOCOL_ERROR)
            }
            if (receipt.status != ReceiptStatus.DELIVERED) {
                return MeshHandleResult.Rejected(TransportError.PROTOCOL_ERROR)
            }

            val updated = try {
                messageStore.updateStatus(receipt.messageId, MessageStatus.DELIVERED)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return MeshHandleResult.StoreNeeded
            }
            if (updated) {
                eventsBuffer.emit(
                    MeshEvent.DeliveryChanged(receipt.messageId, MessageStatus.DELIVERED),
                )
                Timber.i("Delivery receipt: msgId=%s peerId=%s", receipt.messageId, header.senderId)
            }
            return MeshHandleResult.Delivered
        } finally {
            fragment.close()
        }
    }

    private suspend fun establishIncomingSession(peerId: PeerId): SessionState {
        val peer = try {
            peerKeyStore.getPeer(peerId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return SessionState.FAILED
        } ?: return SessionState.NO_PEER_KEY

        val publicKey = peer.lastSeenPublicKey
        if (PeerId.fromPublicKey(publicKey) != peerId) {
            clearKeyExchangeSent(peerId)
            announceKeyChanged(peerId, safetyNumber(publicKey), publicKey)
            return SessionState.KEY_CHANGED
        }

        return when (peer.trustState) {
            TrustState.TOFU_ACCEPTED,
            TrustState.VERIFIED,
            -> if (ensureSession(peerId, publicKey)) {
                clearNotifications(peerId)
                SessionState.READY
            } else {
                SessionState.FAILED
            }

            TrustState.UNKNOWN -> {
                announceTrust(peerId, safetyNumber(publicKey), publicKey)
                SessionState.TRUST_REQUIRED
            }

            TrustState.KEY_CHANGED -> {
                clearKeyExchangeSent(peerId)
                announceKeyChanged(peerId, safetyNumber(publicKey), publicKey)
                SessionState.KEY_CHANGED
            }
        }
    }

    private suspend fun ensureOutgoingSession(peerId: PeerId): SessionState {
        if (hasSession(peerId)) return SessionState.READY

        val peer = try {
            peerKeyStore.getPeer(peerId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return SessionState.FAILED
        }

        if (peer == null) {
            sendKeyExchangeIfNeeded(peerId)
            return SessionState.NO_PEER_KEY
        }

        val publicKey = peer.lastSeenPublicKey
        if (PeerId.fromPublicKey(publicKey) != peerId) {
            clearKeyExchangeSent(peerId)
            announceKeyChanged(peerId, safetyNumber(publicKey), publicKey)
            return SessionState.KEY_CHANGED
        }

        return when (peer.trustState) {
            TrustState.TOFU_ACCEPTED,
            TrustState.VERIFIED,
            -> if (ensureSession(peerId, publicKey)) {
                clearNotifications(peerId)
                SessionState.READY
            } else {
                SessionState.FAILED
            }

            TrustState.UNKNOWN -> {
                announceTrust(peerId, safetyNumber(publicKey), publicKey)
                SessionState.TRUST_REQUIRED
            }

            TrustState.KEY_CHANGED -> {
                clearKeyExchangeSent(peerId)
                announceKeyChanged(peerId, safetyNumber(publicKey), publicKey)
                SessionState.KEY_CHANGED
            }
        }
    }

    private suspend fun sendNewMessage(record: MessageRecord): SendTextResult {
        val outbox = try {
            encryptToOutbox(record)
        } catch (error: CancellationException) {
            throw error
        } catch (error: E2EException) {
            Timber.w("Encrypt failed: msgId=%s peerId=%s code=%s", record.msgId, record.peerId, error.code)
            markFailed(record.msgId)
            return SendTextResult.Failed(record.messageId, SendError.CRYPTO_FAILED)
        } catch (error: Exception) {
            markFailed(record.msgId)
            return SendTextResult.Failed(record.messageId, SendError.INTERNAL)
        }

        val packet = when (outbox) {
            is EncryptedOutbox.Ready -> outbox.bytes
            EncryptedOutbox.NoSession -> {
                markFailed(record.msgId)
                return SendTextResult.Failed(record.messageId, SendError.CRYPTO_FAILED)
            }

            EncryptedOutbox.StorageFailed -> {
                markFailed(record.msgId)
                return SendTextResult.Failed(record.messageId, SendError.STORAGE_FAILED)
            }
        }

        return try {
            if (sendRecord(record.copy(packet = packet))) {
                SendTextResult.Sent(record.messageId)
            } else {
                SendTextResult.Pending(record.messageId, PendingReason.NO_LINK)
            }
        } finally {
            packet.fill(0)
        }
    }

    private suspend fun flushRecord(record: MessageRecord) {
        if (ensureOutgoingSession(record.peerId) != SessionState.READY) return
        sendKeyExchangeIfNeeded(record.peerId)

        val packet = if (record.packet != null) {
            record.packet
        } else {
            when (val outbox = encryptToOutbox(record)) {
                is EncryptedOutbox.Ready -> outbox.bytes
                EncryptedOutbox.NoSession,
                EncryptedOutbox.StorageFailed,
                -> return
            }
        }

        try {
            sendRecord(record.copy(packet = packet))
        } finally {
            if (record.packet == null) packet.fill(0)
        }
    }

    /**
     * Returns true when every fragment reached at least one link. A partial
     * flood stays PENDING and is retried as a whole; per-fragment dedup makes
     * the duplicate fragments harmless.
     */
    private suspend fun sendRecord(record: MessageRecord): Boolean {
        val outbox = record.packet ?: return false
        val packets = try {
            OutboxCodec.decode(outbox)
        } catch (error: IllegalArgumentException) {
            Timber.w("Outbox corrupt: msgId=%s peerId=%s", record.msgId, record.peerId)
            markFailed(record.msgId)
            return false
        }

        var deliveredAll = packets.isNotEmpty()
        try {
            for (packet in packets) {
                if (closed) {
                    deliveredAll = false
                    break
                }
                val result = try {
                    sender?.sendPacket(packet) ?: MeshSendResult(attempted = 0, delivered = 0)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: TransportException) {
                    MeshSendResult(attempted = 0, delivered = 0)
                } catch (error: Exception) {
                    MeshSendResult(attempted = 0, delivered = 0)
                }
                if (!result.reachedAnyLink) deliveredAll = false
            }
        } finally {
            packets.forEach { it.fill(0) }
        }

        if (deliveredAll) {
            try {
                messageStore.updateStatus(record.msgId, MessageStatus.SENT)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w("Status update failed: msgId=%s peerId=%s", record.msgId, record.peerId)
            }
            Timber.i(
                "Message sent: msgId=%s peerId=%s parts=%d",
                record.msgId,
                record.peerId,
                packets.size,
            )
        }
        return deliveredAll
    }

    private suspend fun encryptToOutbox(record: MessageRecord): EncryptedOutbox {
        val envelopes = withSession(record.peerId) { session ->
            session.seal(EnvelopeType.TEXT, record.msgId, record.body)
        } ?: return EncryptedOutbox.NoSession

        try {
            val encoded = OutboxCodec.encode(envelopes)
            val updated = try {
                messageStore.updatePacket(record.msgId, encoded)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                false
            }
            if (!updated) {
                encoded.fill(0)
                return EncryptedOutbox.StorageFailed
            }
            return EncryptedOutbox.Ready(encoded)
        } finally {
            envelopes.forEach { it.fill(0) }
        }
    }

    private suspend fun storeIncoming(assembled: AssembledMessage): MeshHandleResult {
        val body = assembled.plaintext.copyOf()
        try {
            val createdAt = now()
            val inserted = try {
                messageStore.insert(
                    MessageRecord(
                        messageId = assembled.messageId,
                        msgId = assembled.messageId,
                        peerId = assembled.senderId,
                        direction = MessageDirection.INCOMING,
                        body = body,
                        createdAt = createdAt,
                        status = MessageStatus.RECEIVED,
                        packet = null,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w(
                    "Incoming insert failed: msgId=%s peerId=%s",
                    assembled.messageId,
                    assembled.senderId,
                )
                return MeshHandleResult.StoreNeeded
            }

            if (inserted) {
                eventsBuffer.emit(
                    MeshEvent.MessageReceived(
                        messageId = assembled.messageId,
                        peerId = assembled.senderId,
                        body = body.copyOf(),
                        createdAt = createdAt,
                    ),
                )
                Timber.i(
                    "Message received: msgId=%s peerId=%s bytes=%d",
                    assembled.messageId,
                    assembled.senderId,
                    body.size,
                )
            }

            sendReceipt(assembled.senderId, assembled.messageId)
            return MeshHandleResult.Delivered
        } finally {
            body.fill(0)
        }
    }

    private suspend fun sendReceipt(peerId: PeerId, messageId: MessageId): Boolean {
        val receiptId = MessageId.random(random)
        val payload = ReceiptPayload.encode(messageId, ReceiptStatus.DELIVERED)
        try {
            val envelopes = withSession(peerId) { session ->
                session.seal(EnvelopeType.RECEIPT, receiptId, payload)
            } ?: return false

            try {
                var delivered = false
                for (envelope in envelopes) {
                    if (sendEnvelope(envelope, peerId, receiptId)) delivered = true
                }
                return delivered
            } finally {
                envelopes.forEach { it.fill(0) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: E2EException) {
            Timber.w("Receipt encrypt failed: msgId=%s peerId=%s code=%s", messageId, peerId, error.code)
            return false
        } catch (error: IllegalStateException) {
            return false
        } finally {
            payload.fill(0)
        }
    }

    private suspend fun sendEnvelope(
        envelope: ByteArray,
        recipientId: PeerId,
        msgId: MessageId,
    ): Boolean {
        val mesh = sender ?: return false
        val packet = try {
            mesh.wrap(envelope, recipientId, msgId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: TransportException) {
            return false
        } catch (error: Exception) {
            return false
        }

        return try {
            mesh.sendPacket(packet).reachedAnyLink
        } catch (error: CancellationException) {
            throw error
        } catch (error: TransportException) {
            false
        } catch (error: Exception) {
            false
        } finally {
            packet.fill(0)
        }
    }

    private suspend fun sendKeyExchangeIfNeeded(peerId: PeerId): Boolean {
        synchronized(notificationLock) {
            if (keyExchangeSent.contains(peerId)) return true
        }

        val sent = sendKeyExchange(peerId)
        if (sent) {
            synchronized(notificationLock) {
                keyExchangeSent.add(peerId)
            }
        }
        return sent
    }

    private suspend fun sendKeyExchange(peerId: PeerId): Boolean {
        val publicKey = identity.publicKey
        try {
            val messageId = MessageId.random(random)
            val envelope = KeyExchangeCodec.encode(
                messageId = messageId,
                senderId = localPeerId,
                recipientId = peerId,
                publicKey = publicKey,
            )
            try {
                return sendEnvelope(envelope, peerId, messageId)
            } finally {
                envelope.fill(0)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: E2EException) {
            Timber.w("Key exchange encrypt failed: peerId=%s code=%s", peerId, error.code)
            return false
        } catch (error: Exception) {
            return false
        } finally {
            publicKey.fill(0)
        }
    }

    private suspend fun ensureSession(peerId: PeerId, publicKey: ByteArray): Boolean =
        sessionMutex.withLock {
            if (sessions.containsKey(peerId)) return@withLock true

            val hkdf = try {
                HkdfSession.derive(identity, publicKey)
            } catch (error: CancellationException) {
                throw error
            } catch (error: E2EException) {
                Timber.w("Session derive failed: peerId=%s code=%s", peerId, error.code)
                return@withLock false
            } catch (error: Exception) {
                Timber.w("Session derive failed: peerId=%s", peerId)
                return@withLock false
            }

            var adopted = false
            try {
                if (hkdf.remotePeerId != peerId) {
                    Timber.w("Session key binding failed: peerId=%s", peerId)
                    return@withLock false
                }
                val session = E2ESession(hkdf, random)
                val previous = sessions.putIfAbsent(peerId, session)
                if (previous != null) session.close()
                adopted = true
                true
            } catch (error: Exception) {
                Timber.w("Session create failed: peerId=%s", peerId)
                false
            } finally {
                if (!adopted) hkdf.close()
            }
        }

    private suspend fun invalidateSession(peerId: PeerId) {
        val old = sessionMutex.withLock { sessions.remove(peerId) }
        old?.close()
    }

    private suspend fun hasSession(peerId: PeerId): Boolean =
        sessionMutex.withLock { sessions.containsKey(peerId) }

    private suspend fun <T> withSession(
        peerId: PeerId,
        block: (E2ESession) -> T,
    ): T? = sessionMutex.withLock {
        val session = sessions[peerId] ?: return@withLock null
        block(session)
    }

    private suspend fun openFragment(
        peerId: PeerId,
        envelope: ByteArray,
        type: EnvelopeType,
    ): OpenedFragment? = withSession(peerId) { session ->
        session.openFragment(envelope, type)
    }

    private suspend fun announceTrust(
        peerId: PeerId,
        safetyNumber: String,
        publicKey: ByteArray,
    ) {
        val shouldEmit = synchronized(notificationLock) {
            if (trustNotified[peerId] == safetyNumber) {
                false
            } else {
                trustNotified[peerId] = safetyNumber
                keyChangedNotified.remove(peerId)
                true
            }
        }
        if (shouldEmit) {
            eventsBuffer.emit(MeshEvent.TrustRequired(peerId, safetyNumber, publicKey.copyOf()))
        }
    }

    private suspend fun announceKeyChanged(
        peerId: PeerId,
        safetyNumber: String,
        publicKey: ByteArray,
    ) {
        val shouldEmit = synchronized(notificationLock) {
            if (keyChangedNotified[peerId] == safetyNumber) {
                false
            } else {
                keyChangedNotified[peerId] = safetyNumber
                trustNotified.remove(peerId)
                true
            }
        }
        if (shouldEmit) {
            eventsBuffer.emit(MeshEvent.KeyChanged(peerId, safetyNumber, publicKey.copyOf()))
        }
    }

    private fun clearNotifications(peerId: PeerId) {
        synchronized(notificationLock) {
            trustNotified.remove(peerId)
            keyChangedNotified.remove(peerId)
        }
    }

    private fun clearKeyExchangeSent(peerId: PeerId) {
        synchronized(notificationLock) {
            keyExchangeSent.remove(peerId)
        }
    }

    private fun safetyNumber(peerPublicKey: ByteArray): String =
        SafetyNumber.of(ownPublicKey, peerPublicKey)

    private suspend fun markFailed(messageId: MessageId) {
        try {
            messageStore.updateStatus(messageId, MessageStatus.FAILED)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w("Failed status update failed: msgId=%s", messageId)
        }
    }

    private enum class SessionState {
        READY,
        NO_PEER_KEY,
        TRUST_REQUIRED,
        KEY_CHANGED,
        FAILED,
    }

    private sealed interface EncryptedOutbox {
        class Ready(val bytes: ByteArray) : EncryptedOutbox
        data object NoSession : EncryptedOutbox
        data object StorageFailed : EncryptedOutbox
    }
}
