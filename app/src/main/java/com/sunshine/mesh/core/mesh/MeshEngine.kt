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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
) : Closeable {

    private val engineJob = Job(scope.coroutineContext[Job])
    private val engineScope = CoroutineScope(scope.coroutineContext + engineJob)

    @Volatile
    private var closed = false

    private var started = false

    fun start() {
        if (closed) throw TransportException(TransportError.INTERNAL)
        if (started) return
        started = true
        engineScope.launch {
            transport.incoming.collect { inbound -> receive(inbound) }
        }
        engineScope.launch {
            while (isActive) {
                delay(retryIntervalMs)
                retryDue()
            }
        }
    }

    /**
     * Wraps a local envelope into a mesh packet and floods it to every linked
     * peer. Persistence of our own outbox is Stage 5.
     */
    suspend fun send(envelope: ByteArray, recipientId: PeerId, msgId: MessageId) {
        if (closed) throw TransportException(TransportError.INTERNAL)
        if (envelope.size != EnvelopeCodec.ENVELOPE_BYTES) {
            throw TransportException(TransportError.PACKET_TOO_LARGE)
        }
        val partIndex = envelope[EnvelopeCodec.PART_INDEX_OFFSET].toInt() and 0xff
        seen.markSeen(msgId, partIndex)
        val header = MeshHeader(
            version = MeshHeader.VERSION,
            ttl = MeshPolicy.DEFAULT_TTL,
            msgId = msgId,
            senderId = localPeerId,
            recipientId = recipientId,
        )
        val bytes = header.encode() + envelope
        try {
            broadcast(bytes, exclude = null)
        } finally {
            bytes.fill(0)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
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

    private suspend fun broadcast(bytes: ByteArray, exclude: PeerId?) {
        for (peer in transport.peers.value) {
            if (peer == localPeerId || peer == exclude) continue
            try {
                transport.send(peer, bytes)
            } catch (error: TransportException) {
                // A link can vanish mid-flood; the remaining peers still get it.
            }
        }
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
