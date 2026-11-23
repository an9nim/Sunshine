package com.sunshine.mesh.core.mesh

import com.sunshine.mesh.core.crypto.e2e.EnvelopeCodec
import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.net.MeshPolicy
import com.sunshine.mesh.core.transport.InboundPacket
import com.sunshine.mesh.core.transport.Transport
import com.sunshine.mesh.core.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshEngineTest {

    private val local = PeerId.parse("0000000000000001")
    private val source = PeerId.parse("0000000000000002")
    private val other = PeerId.parse("0000000000000003")
    private val third = PeerId.parse("0000000000000004")
    private val msgId = MessageId.parse("00112233445566778899aabbccddeeff")

    @Test
    fun expiredTtlIsDropped() = runBlocking {
        val fixture = Fixture(this)
        fixture.engine.start()
        try {
            fixture.transport.emit(source, meshPacket(ttl = 0))
            delay(60)

            assertTrue(fixture.handler.handled.isEmpty())
            assertTrue(fixture.transport.sent.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun duplicatePacketIsDropped() = runBlocking {
        val fixture = Fixture(this)
        fixture.engine.start()
        try {
            val packet = meshPacket()
            fixture.transport.emit(source, packet)
            fixture.transport.emit(source, packet.copyOf())
            awaitUntil { fixture.handler.handled.size == 1 }
            delay(40)

            assertEquals(1, fixture.handler.handled.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun partsOfOneMessageAreAllDelivered() = runBlocking {
        val fixture = Fixture(this)
        fixture.engine.start()
        try {
            fixture.transport.emit(source, meshPacket(partIndex = 0))
            fixture.transport.emit(source, meshPacket(partIndex = 1))
            awaitUntil { fixture.handler.handled.size == 2 }

            assertEquals(2, fixture.handler.handled.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun malformedPacketIsDroppedWithoutCrash() = runBlocking {
        val fixture = Fixture(this)
        fixture.engine.start()
        try {
            fixture.transport.emit(source, ByteArray(10))
            fixture.transport.emit(source, meshPacket())
            awaitUntil { fixture.handler.handled.size == 1 }

            assertEquals(1, fixture.handler.handled.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun ownSenderIsDropped() = runBlocking {
        val fixture = Fixture(this)
        fixture.engine.start()
        try {
            fixture.transport.emit(source, meshPacket(sender = local))
            delay(60)

            assertTrue(fixture.handler.handled.isEmpty())
            assertTrue(fixture.transport.sent.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun storeNeededPersistsPacketFor72Hours() = runBlocking {
        val fixture = Fixture(this, MeshHandleResult.StoreNeeded)
        fixture.engine.start()
        try {
            fixture.transport.emit(source, meshPacket())
            awaitUntil { fixture.store.entries.size == 1 }

            val entry = fixture.store.entries.single()
            assertEquals(source, entry.senderId)
            assertEquals(fixture.now + MeshPolicy.STORE_TTL_MS, entry.expiresAt)
            assertEquals(546, entry.packet.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun foreignPacketIsFloodedToEveryoneExceptSource() = runBlocking {
        val fixture = Fixture(this)
        fixture.transport.peers.value = setOf(source, other, third)
        fixture.engine.start()
        try {
            fixture.transport.emit(source, meshPacket(recipient = other, ttl = 8))
            awaitUntil { fixture.transport.sent.size == 2 }

            val targets = fixture.transport.sent.map { it.peerId }.toSet()
            assertEquals(setOf(other, third), targets)
            for (sent in fixture.transport.sent) {
                val header = MeshHeader.decode(sent.packet.copyOfRange(0, MeshHeader.SIZE))
                assertEquals(7, header.ttl)
                assertEquals(msgId, header.msgId)
                assertEquals(other, header.recipientId)
            }
            assertTrue(fixture.handler.handled.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun ttlOneIsNotForwarded() = runBlocking {
        val fixture = Fixture(this)
        fixture.transport.peers.value = setOf(source, other)
        fixture.engine.start()
        try {
            fixture.transport.emit(source, meshPacket(recipient = other, ttl = 1))
            delay(60)

            assertTrue(fixture.transport.sent.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun sendFloodsLocalEnvelopeAndDedupsTheEcho() = runBlocking {
        val fixture = Fixture(this)
        fixture.transport.peers.value = setOf(source, other)
        fixture.engine.start()
        try {
            val envelope = ByteArray(EnvelopeCodec.ENVELOPE_BYTES)
            fixture.engine.send(envelope, recipientId = other, msgId = msgId)
            assertEquals(2, fixture.transport.sent.size)
            for (sent in fixture.transport.sent) {
                val header = MeshHeader.decode(sent.packet.copyOfRange(0, MeshHeader.SIZE))
                assertEquals(MeshPolicy.DEFAULT_TTL, header.ttl)
                assertEquals(local, header.senderId)
                assertEquals(other, header.recipientId)
            }

            val echo = fixture.transport.sent.first().packet.copyOf()
            fixture.transport.emit(source, echo)
            delay(60)

            assertEquals(2, fixture.transport.sent.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun retryDeletesExpiredEntriesWithoutDecryptionAttempt() = runBlocking {
        val fixture = Fixture(this)
        fixture.store.seed(source, meshPacket(), expiresAt = fixture.now - 1, nextRetryAt = 0)
        fixture.engine.start()
        try {
            awaitUntil { fixture.store.entries.isEmpty() }

            assertTrue(fixture.handler.handled.isEmpty())
            assertTrue(fixture.store.deletedExpiredCalls >= 1)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun retryDeletesZeroTtlEntriesWithoutDecryptionAttempt() = runBlocking {
        val fixture = Fixture(this)
        fixture.store.seed(
            source,
            meshPacket(ttl = 0),
            expiresAt = fixture.now + MeshPolicy.STORE_TTL_MS,
            nextRetryAt = 0,
        )
        fixture.engine.start()
        try {
            awaitUntil { fixture.store.entries.isEmpty() }

            assertTrue(fixture.handler.handled.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun retryDeliveredDeletesTheEntry() = runBlocking {
        val fixture = Fixture(this)
        val packet = meshPacket()
        fixture.store.seed(
            source,
            packet,
            expiresAt = fixture.now + MeshPolicy.STORE_TTL_MS,
            nextRetryAt = 0,
        )
        fixture.engine.start()
        try {
            awaitUntil { fixture.handler.handled.size == 1 && fixture.store.entries.isEmpty() }

            assertEquals(msgId, fixture.handler.handled.single().msgId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun retryStoreNeededReschedulesInsteadOfDeleting() = runBlocking {
        val fixture = Fixture(this, MeshHandleResult.StoreNeeded)
        fixture.store.seed(
            source,
            meshPacket(),
            expiresAt = fixture.now + MeshPolicy.STORE_TTL_MS,
            nextRetryAt = 0,
        )
        fixture.engine.start()
        try {
            awaitUntil {
                fixture.handler.handled.size == 1 &&
                    fixture.store.entries.single().nextRetryAt > fixture.now
            }

            assertEquals(1, fixture.store.entries.size)
        } finally {
            fixture.close()
        }
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(2_000) {
            while (!condition()) delay(5)
        }
    }

    private fun meshPacket(
        ttl: Int = MeshPolicy.DEFAULT_TTL,
        sender: PeerId = source,
        recipient: PeerId = local,
        partIndex: Int = 0,
    ): ByteArray {
        val envelope = ByteArray(EnvelopeCodec.ENVELOPE_BYTES)
        envelope[EnvelopeCodec.PART_INDEX_OFFSET] = partIndex.toByte()
        val header = MeshHeader(MeshHeader.VERSION, ttl, msgId, sender, recipient)
        return header.encode() + envelope
    }

    private class Fixture(
        scope: CoroutineScope,
        handlerResult: MeshHandleResult = MeshHandleResult.Delivered,
    ) {
        var now = 1_000_000L
        val transport = FakeTransport()
        val store = FakeStore { now }
        val handler = FakeHandler(handlerResult)
        val engine = MeshEngine(
            transport = transport,
            localPeerId = PeerId.parse("0000000000000001"),
            seen = SeenCache(),
            store = store,
            handler = handler,
            scope = scope,
            clock = { now },
            retryIntervalMs = 20,
        )

        fun close() {
            engine.close()
            transport.close()
        }
    }

    private class FakeTransport : Transport {
        override val state = MutableStateFlow<TransportState>(TransportState.Running(0))
        override val peers = MutableStateFlow<Set<PeerId>>(emptySet())
        private val incomingChannel = Channel<InboundPacket>(Channel.UNLIMITED)
        override val incoming: Flow<InboundPacket> = incomingChannel.receiveAsFlow()

        data class Sent(val peerId: PeerId, val packet: ByteArray)

        val sent = mutableListOf<Sent>()

        override fun start() = Unit

        override suspend fun send(peerId: PeerId, packet: ByteArray) {
            sent.add(Sent(peerId, packet.copyOf()))
        }

        override fun close() {
            incomingChannel.close()
        }

        fun emit(peerId: PeerId, packet: ByteArray) {
            incomingChannel.trySend(InboundPacket(peerId, packet))
        }
    }

    private class FakeHandler(
        private val result: MeshHandleResult,
    ) : MeshPacketHandler {
        val handled = mutableListOf<MeshHeader>()

        override suspend fun handle(packet: MeshPacket): MeshHandleResult {
            handled.add(packet.header)
            return result
        }
    }

    private class FakeStore(private val clock: () -> Long) : MeshStore {
        class Entry(
            val id: Long,
            val senderId: PeerId,
            val packet: ByteArray,
            val expiresAt: Long,
            var nextRetryAt: Long,
        )

        val entries = mutableListOf<Entry>()
        var deletedExpiredCalls = 0
        private var nextId = 1L

        fun seed(senderId: PeerId, packet: ByteArray, expiresAt: Long, nextRetryAt: Long) {
            entries.add(Entry(nextId++, senderId, packet.copyOf(), expiresAt, nextRetryAt))
        }

        override suspend fun save(senderId: PeerId, packet: ByteArray, expiresAt: Long) {
            entries.add(
                Entry(
                    nextId++,
                    senderId,
                    packet.copyOf(),
                    expiresAt,
                    clock() + MeshPolicy.RETRY_INTERVAL_MS,
                ),
            )
        }

        override suspend fun loadRetryable(now: Long, limit: Int): List<StoredMeshPacket> =
            entries
                .filter { it.expiresAt > now && it.nextRetryAt <= now }
                .take(limit)
                .map { StoredMeshPacket(it.id, it.senderId, it.packet.copyOf(), it.expiresAt) }

        override suspend fun reschedule(id: Long, nextRetryAt: Long) {
            entries.first { it.id == id }.nextRetryAt = nextRetryAt
        }

        override suspend fun delete(id: Long) {
            entries.removeAll { it.id == id }
        }

        override suspend fun deleteExpired(now: Long): Int {
            deletedExpiredCalls += 1
            val before = entries.size
            entries.removeAll { it.expiresAt <= now }
            return before - entries.size
        }
    }
}
