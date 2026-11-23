package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
 * Peripheral side of one BLE link. The remote peer id is unknown until HELLO;
 * [established] completes only after a valid HELLO and a sent HELLO_ACK.
 *
 * Server events are dispatched here by BleTransport; this link never collects
 * the shared server event flow itself.
 */
class BlePeripheralLink(
    private val server: BleGattServer,
    private val connection: BleServerConnection,
    private val localPeerId: PeerId,
    scope: CoroutineScope,
    private val handshakeTimeoutMs: Long = BleProtocol.HANDSHAKE_TIMEOUT_MS,
) : BleLink {

    private val linkJob = Job(scope.coroutineContext[Job])
    private val linkScope = CoroutineScope(scope.coroutineContext + linkJob)

    private val frameChannel =
        Channel<BleGattServerEvent.FrameReceived>(capacity = FRAME_QUEUE_CAPACITY)
    private val incomingChannel = Channel<ByteArray>(capacity = INCOMING_QUEUE_CAPACITY)
    override val incoming: Flow<ByteArray> = incomingChannel.receiveAsFlow()

    private val assembler = BlePacketAssembler()
    private val sendMutex = Mutex()
    private var transferCounter = 0

    @Volatile
    private var remotePeerId: PeerId? = null
    override val peerId: PeerId
        get() = requireNotNull(remotePeerId) { "peripheral link is not established" }

    @Volatile
    private var currentMtu = BleProtocol.DEFAULT_MTU
    override val mtu: Int get() = currentMtu

    @Volatile
    private var closed = false

    private val helloDeferred = CompletableDeferred<BleFrame.Hello>()
    private val indicationsDeferred = CompletableDeferred<Unit>()
    private val establishedDeferred = CompletableDeferred<PeerId>()
    val established: Deferred<PeerId> = establishedDeferred

    init {
        startCollector()
        startExpireTicker()
    }

    suspend fun establish() {
        checkOpen()
        if (establishedDeferred.isCompleted) return

        val hello = try {
            withTimeout(handshakeTimeoutMs) { helloDeferred.await() }
        } catch (error: TimeoutCancellationException) {
            fail(TransportError.LINK_LOST)
            throw TransportException(TransportError.LINK_LOST, error)
        }
        try {
            BleHandshake.validateHello(hello, localPeerId)
        } catch (error: TransportException) {
            fail(error.code)
            throw error
        }

        try {
            withTimeout(handshakeTimeoutMs) { indicationsDeferred.await() }
        } catch (error: TimeoutCancellationException) {
            fail(TransportError.LINK_LOST)
            throw TransportException(TransportError.LINK_LOST, error)
        }

        try {
            server.indicate(connection, BleFrameCodec.encodeHelloAck(localPeerId))
        } catch (error: TransportException) {
            fail(error.code)
            throw error
        }

        remotePeerId = hello.peerId
        currentMtu = connection.mtu
        establishedDeferred.complete(hello.peerId)
    }

    fun onFrame(event: BleGattServerEvent.FrameReceived) {
        if (closed) {
            event.close()
            return
        }
        if (frameChannel.trySend(event).isFailure) {
            event.close()
            fail(TransportError.BUFFER_OVERFLOW)
        }
    }

    fun onMtuChanged(mtu: Int) {
        currentMtu = mtu.coerceAtLeast(BleProtocol.MIN_MTU)
    }

    fun onIndicationsChanged(enabled: Boolean) {
        if (enabled) {
            indicationsDeferred.complete(Unit)
        } else if (establishedDeferred.isCompleted) {
            fail(TransportError.LINK_LOST)
        }
    }

    fun onDisconnected(error: TransportError?) {
        fail(error ?: TransportError.LINK_LOST)
    }

    override suspend fun send(packet: ByteArray) {
        checkReady()
        sendMutex.withLock {
            val chunks = BleFrameCodec.encodeData(packet, mtu, nextTransferId())
            for (chunk in chunks) {
                server.indicate(connection, chunk)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        completePending(TransportError.LINK_LOST)
        frameChannel.close()
        incomingChannel.close()
        assembler.clear()
        linkJob.cancel()
    }

    private fun startCollector() {
        linkScope.launch {
            for (event in frameChannel) {
                handleFrame(event)
            }
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

    private fun handleFrame(event: BleGattServerEvent.FrameReceived) {
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
                is BleFrame.Hello -> {
                    if (establishedDeferred.isCompleted) {
                        fail(TransportError.PROTOCOL_ERROR)
                    } else {
                        helloDeferred.complete(decoded)
                    }
                }
                is BleFrame.HelloAck -> fail(TransportError.PROTOCOL_ERROR)
                is BleFrame.Data -> {
                    if (!establishedDeferred.isCompleted) {
                        fail(TransportError.PROTOCOL_ERROR)
                    } else {
                        acceptData(decoded)
                    }
                }
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
        Timber.w(
            "BLE peripheral link failed: connection=%d code=%s",
            connection.id,
            error,
        )
        completePending(error)
        frameChannel.close()
        incomingChannel.close()
        assembler.clear()
        linkJob.cancel()
    }

    private fun completePending(error: TransportError) {
        val exception = TransportException(error)
        helloDeferred.completeExceptionally(exception)
        indicationsDeferred.completeExceptionally(exception)
        establishedDeferred.completeExceptionally(exception)
    }

    private fun checkOpen() {
        if (closed) throw TransportException(TransportError.INTERNAL)
    }

    private fun checkReady() {
        checkOpen()
        if (remotePeerId == null) throw TransportException(TransportError.NOT_CONNECTED)
    }

    private companion object {
        const val FRAME_QUEUE_CAPACITY = 64
        const val INCOMING_QUEUE_CAPACITY = 16
        const val EXPIRE_TICK_MS = 1_000L
    }
}
