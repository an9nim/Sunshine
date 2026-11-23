package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * Central side of one BLE link. The remote peer id is already known from the
 * advertisement; establish() performs the GATT setup and HELLO/HELLO_ACK.
 */
class BleCentralLink(
    private val client: BleGattClient,
    private val localPeerId: PeerId,
    override val peerId: PeerId,
    scope: CoroutineScope,
    private val handshakeTimeoutMs: Long = BleProtocol.HANDSHAKE_TIMEOUT_MS,
) : BleLink {

    private val linkJob = Job(scope.coroutineContext[Job])
    private val linkScope = CoroutineScope(scope.coroutineContext + linkJob)

    private val incomingChannel = Channel<ByteArray>(capacity = INCOMING_QUEUE_CAPACITY)
    override val incoming: Flow<ByteArray> = incomingChannel.receiveAsFlow()

    private val assembler = BlePacketAssembler()
    private val sendMutex = Mutex()
    private var transferCounter = 0

    @Volatile
    private var currentMtu = BleProtocol.DEFAULT_MTU
    override val mtu: Int get() = currentMtu

    @Volatile
    private var closed = false

    @Volatile
    private var established = false

    private val helloAckDeferred = CompletableDeferred<BleFrame.HelloAck>()

    suspend fun establish() {
        checkOpen()
        if (established) return
        BleHandshake.checkCentralRole(localPeerId, peerId)

        client.connect()
        client.discoverServices()
        currentMtu = client.requestMtu(BleProtocol.REQUESTED_MTU)
        client.enableIndications()

        startCollector()
        startExpireTicker()
        client.write(BleFrameCodec.encodeHello(localPeerId))

        val ack = try {
            withTimeout(handshakeTimeoutMs) { helloAckDeferred.await() }
        } catch (error: TimeoutCancellationException) {
            fail(TransportError.LINK_LOST)
            throw TransportException(TransportError.LINK_LOST, error)
        }
        try {
            BleHandshake.validateHelloAck(ack, localPeerId, peerId)
        } catch (error: TransportException) {
            fail(error.code)
            throw error
        }
        established = true
    }

    override suspend fun send(packet: ByteArray) {
        checkReady()
        sendMutex.withLock {
            val chunks = BleFrameCodec.encodeData(packet, mtu, nextTransferId())
            for (chunk in chunks) {
                client.write(chunk)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        helloAckDeferred.completeExceptionally(TransportException(TransportError.LINK_LOST))
        incomingChannel.close()
        assembler.clear()
        linkJob.cancel()
        client.close()
    }

    private fun startCollector() {
        linkScope.launch {
            client.events.collect { event -> handleEvent(event) }
        }
    }

    private fun startExpireTicker() {
        linkScope.launch {
            while (isActive) {
                delay(EXPIRE_TICK_MS)
                assembler.expire()
            }
        }
    }

    private fun handleEvent(event: BleGattClientEvent) {
        when (event) {
            is BleGattClientEvent.Connected -> Unit
            is BleGattClientEvent.MtuChanged -> currentMtu = event.mtu
            is BleGattClientEvent.Disconnected ->
                fail(event.error ?: TransportError.LINK_LOST)
            is BleGattClientEvent.Failed -> fail(event.error)
            is BleGattClientEvent.FrameReceived -> handleFrame(event)
        }
    }

    private fun handleFrame(event: BleGattClientEvent.FrameReceived) {
        val decoded = try {
            BleFrameCodec.decode(event.frame)
        } catch (error: TransportException) {
            event.close()
            fail(error.code)
            return
        }
        event.close()

        try {
            when (decoded) {
                is BleFrame.HelloAck -> {
                    if (established) {
                        fail(TransportError.PROTOCOL_ERROR)
                    } else {
                        helloAckDeferred.complete(decoded)
                    }
                }
                is BleFrame.Data -> {
                    if (!established) {
                        fail(TransportError.PROTOCOL_ERROR)
                    } else {
                        acceptData(decoded)
                    }
                }
                is BleFrame.Hello -> fail(TransportError.PROTOCOL_ERROR)
                is BleFrame.Error -> fail(decoded.code)
            }
        } finally {
            decoded.close()
        }
    }

    private fun acceptData(data: BleFrame.Data) {
        val packet = try {
            assembler.accept(data)
        } catch (error: TransportException) {
            fail(error.code)
            return
        }
        if (packet != null && incomingChannel.trySend(packet).isFailure) {
            packet.fill(0)
            fail(TransportError.BUFFER_OVERFLOW)
        }
    }

    private fun nextTransferId(): Int {
        val id = transferCounter
        transferCounter = (transferCounter + 1) and 0xff
        return id
    }

    private fun fail(error: TransportError) {
        if (closed) return
        closed = true
        Timber.w("BLE central link failed: peerId=%s code=%s", peerId, error)
        helloAckDeferred.completeExceptionally(TransportException(error))
        incomingChannel.close()
        assembler.clear()
        linkJob.cancel()
        client.close()
    }

    private fun checkOpen() {
        if (closed) throw TransportException(TransportError.INTERNAL)
    }

    private fun checkReady() {
        checkOpen()
        if (!established) throw TransportException(TransportError.NOT_CONNECTED)
    }

    private companion object {
        const val INCOMING_QUEUE_CAPACITY = 16
        const val EXPIRE_TICK_MS = 1_000L
    }
}
