package com.sunshine.mesh.core.mesh

import com.sunshine.mesh.core.crypto.e2e.EnvelopeCodec
import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.net.MeshPolicy
import com.sunshine.mesh.core.transport.InboundPacket
import com.sunshine.mesh.core.transport.Transport
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable

/**
 * Mesh layer above [Transport]: hop-TTL, per-fragment deduplication, flooding
 * and 72-hour store-and-forward. It never decrypts envelopes; the local
 * delivery path is delegated to [MeshPacketHandler] (E2E wiring is Stage 5).
 */
class MeshEngine(
    private val transport: Transport,
    private val localPeerId: PeerId,
    private val seen: SeenCache,
    private val store: MeshStore,
    private val handler: MeshPacketHandler,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryIntervalMs: Long = MeshPolicy.RETRY_INTERVAL_MS,
) : MeshSender {

    override val peers: StateFlow<Set<PeerId>> = transport.peers

    private val engineJob = Job(scope.coroutineContext[Job])
    private val engineScope = CoroutineScope(scope.coroutineContext + engineJob)
    private val retrySignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var closed = false

    private var started = false

    override fun start() {
        if (closed) throw TransportException(TransportError.INTERNAL)
        if (started) return
        started = true
        engineScope.launch {
            transport.incoming.collect { inbound -> receive(inbound) }
        }
        engineScope.launch {
            while (isActive) {
                withTimeoutOrNull(retryIntervalMs) { retrySignal.receive() }
                retryDue()
            }
        }
    }

    /**
     * Builds a 546-byte mesh packet with a fresh hop TTL. Pure: it does not
     * mark the fragment as seen and does not touch the transport.
     */
    override fun wrap(
        envelope: ByteArray,
        recipientId: PeerId,
        msgId: MessageId,
    ): ByteArray {
        if (closed) throw TransportException(TransportError.INTERNAL)
        if (envelope.size != EnvelopeCodec.ENVELOPE_BYTES) {
            throw TransportException(TransportError.PACKET_TOO_LARGE)
        }
        val header = MeshHeader(
            version = MeshHeader.VERSION,
            ttl = MeshPolicy.DEFAULT_TTL,
            msgId = msgId,
            senderId = localPeerId,
            recipientId = recipientId,
        )
        return header.encode() + envelope
    }

    /**
     * Floods an already wrapped packet to every linked peer. The sender is the
     * local node; inbound packets are never passed here. Returns how many links
     * were attempted and how many accepted the packet.
     */
    override suspend fun sendPacket(packet: ByteArray): MeshSendResult {
        if (closed) throw TransportException(TransportError.INTERNAL)
        if (packet.size != MeshPacket.TOTAL_BYTES) {
            throw TransportException(TransportError.PACKET_TOO_LARGE)
        }

        val header = try {
            MeshHeader.decode(packet.copyOfRange(0, MeshHeader.SIZE))
        } catch (error: TransportException) {
            throw TransportException(TransportError.PROTOCOL_ERROR, error)
        }
        if (header.senderId != localPeerId || header.ttl <= 0) {
            throw TransportException(TransportError.PROTOCOL_ERROR)
        }

        val partIndex = packet[MeshHeader.SIZE + EnvelopeCodec.PART_INDEX_OFFSET].toInt() and 0xff
        seen.markSeen(header.msgId, partIndex)
        return broadcast(packet, exclude = null)
    }

    /**
     * Wraps a local envelope and floods it. The temporary packet is wiped even
     * when the transport throws.
     */
    suspend fun send(
        envelope: ByteArray,
        recipientId: PeerId,
        msgId: MessageId,
    ): MeshSendResult {
        val packet = wrap(envelope, recipientId, msgId)
        return try {
            sendPacket(packet)
        } finally {
            packet.fill(0)
        }
    }

    /** Wakes the retry loop without waiting for [retryIntervalMs]. */
    override fun requestRetry() {
        if (closed) return
        retrySignal.trySend(Unit)
    }

    override fun close() {
        if (closed) return
        closed = true
        // Cancellation stops the pending receive; closing the channel first
        // would surface ClosedReceiveChannelException instead.
        engineJob.cancel()
    }

    private suspend fun receive(inbound: InboundPacket) {
        try {
            val packet = try {
                MeshPacket.fromBytes(inbound.packet)
            } catch (error: TransportException) {
                return
            }
            try {
                route(packet, source = inbound.peerId)
            } finally {
                packet.close()
            }
        } finally {
            inbound.close()
        }
    }

    private suspend fun route(packet: MeshPacket, source: PeerId) {
        val header = packet.header
        if (header.ttl <= 0) return
        if (!seen.markSeen(header.msgId, packet.partIndex)) return
        if (header.senderId == localPeerId) return

        if (header.recipientId == localPeerId) {
            when (handler.handle(packet)) {
                MeshHandleResult.Delivered -> Unit
                MeshHandleResult.StoreNeeded ->
                    store.save(
                        senderId = source,
                        packet = packet.toBytes(),
                        expiresAt = clock() + MeshPolicy.STORE_TTL_MS,
                    )
                is MeshHandleResult.Rejected -> Unit
            }
            return
        }

        val nextTtl = header.ttl - 1
        if (nextTtl <= 0) return
        val forwarded = header.copy(ttl = nextTtl).encode() + packet.envelope
        try {
            broadcast(forwarded, exclude = source)
        } finally {
            forwarded.fill(0)
        }
    }

    private suspend fun broadcast(bytes: ByteArray, exclude: PeerId?): MeshSendResult {
        var attempted = 0
        var delivered = 0
        for (peer in transport.peers.value) {
            if (peer == localPeerId || peer == exclude) continue
            attempted += 1
            try {
                transport.send(peer, bytes)
                delivered += 1
            } catch (error: TransportException) {
                // A link can vanish mid-flood; the remaining peers still get it.
            }
        }
        return MeshSendResult(attempted = attempted, delivered = delivered)
    }

    /**
     * Retries stored packets. Expired entries are deleted first and never
     * reach the handler; a non-positive hop TTL is treated the same way.
     */
    private suspend fun retryDue() {
        val now = clock()
        store.deleteExpired(now)
        val due = store.loadRetryable(now, MeshPolicy.RETRY_BATCH)
        for (stored in due) {
            try {
                if (stored.expiresAt <= now) {
                    store.delete(stored.id)
                    continue
                }
                val packet = try {
                    MeshPacket.fromBytes(stored.packet)
                } catch (error: TransportException) {
                    store.delete(stored.id)
                    continue
                }
                try {
                    if (packet.header.ttl <= 0) {
                        store.delete(stored.id)
                        continue
                    }
                    when (handler.handle(packet)) {
                        MeshHandleResult.Delivered -> store.delete(stored.id)
                        MeshHandleResult.StoreNeeded ->
                            store.reschedule(stored.id, now + retryIntervalMs)
                        is MeshHandleResult.Rejected -> store.delete(stored.id)
                    }
                } finally {
                    packet.close()
                }
            } finally {
                stored.close()
            }
        }
    }
}

/**
 * Outbound facade used by the coordinator. [close] stops the engine but leaves
 * the underlying transport open: TransportHub owns the transports.
 */
interface MeshSender : Closeable {
    /** Links currently visible to the mesh layer. */
    val peers: StateFlow<Set<PeerId>>

    fun start()

    fun wrap(
        envelope: ByteArray,
        recipientId: PeerId,
        msgId: MessageId,
    ): ByteArray

    suspend fun sendPacket(packet: ByteArray): MeshSendResult

    fun requestRetry()
}

data class MeshSendResult(
    val attempted: Int,
    val delivered: Int,
) {
    val reachedAnyLink: Boolean get() = delivered > 0
}

/**
 * Local delivery hook. The handler must copy anything it needs: the engine
 * wipes the envelope right after [handle] returns.
 */
fun interface MeshPacketHandler {
    suspend fun handle(packet: MeshPacket): MeshHandleResult
}

sealed interface MeshHandleResult {
    data object Delivered : MeshHandleResult
    data object StoreNeeded : MeshHandleResult
    data class Rejected(val error: TransportError) : MeshHandleResult
}
