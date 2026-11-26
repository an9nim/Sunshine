package com.sunshine.mesh.core.transport

import com.sunshine.mesh.core.ids.PeerId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Merges BLE and Wi-Fi Direct below a single [Transport], so MeshEngine keeps
 * one TTL/dedup contour, one retry loop and one outbound path.
 *
 * [incoming] is a single-consumer flow: child transports expose receiveAsFlow,
 * so only MeshEngine may collect it. Sends pick the first transport that has a
 * link to the peer and fall back to the next one on failure.
 */
class TransportHub(
    private val transports: List<Transport>,
    scope: CoroutineScope,
) : Transport {

    init {
        require(transports.isNotEmpty()) { "at least one transport is required" }
    }

    private val hubJob = Job(scope.coroutineContext[Job])
    private val hubScope = CoroutineScope(scope.coroutineContext + hubJob)

    @Volatile
    private var closed = false

    private var started = false

    override val peers: StateFlow<Set<PeerId>> =
        combine(transports.map { it.peers }) { sets ->
            buildSet {
                for (set in sets) addAll(set)
            }
        }.stateIn(
            scope = hubScope,
            started = SharingStarted.Eagerly,
            initialValue = emptySet(),
        )

    /** Per-peer view of which radios currently have a link. */
    val links: StateFlow<Map<PeerId, Set<TransportKind>>> =
        combine(transports.map { it.peers }) { sets ->
            val result = mutableMapOf<PeerId, Set<TransportKind>>()
            transports.forEachIndexed { index, transport ->
                for (peerId in sets[index]) {
                    result[peerId] = result[peerId].orEmpty() + transport.kind
                }
            }
            result
        }.stateIn(
            scope = hubScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyMap(),
        )

    override val state: StateFlow<TransportState> =
        combine(
            combine(transports.map { it.state }) { states -> states.toList() },
            peers,
        ) { states, linked ->
            aggregate(states, connectedPeers = linked.size)
        }.stateIn(
            scope = hubScope,
            started = SharingStarted.Eagerly,
            initialValue = TransportState.Idle,
        )

    override val incoming: Flow<InboundPacket> =
        merge(*transports.map { it.incoming }.toTypedArray())

    override fun start() {
        if (closed) throw TransportException(TransportError.INTERNAL)
        if (started) return
        started = true

        var firstError: TransportException? = null
        var startedCount = 0
        for (transport in transports) {
            try {
                transport.start()
                startedCount += 1
            } catch (error: TransportException) {
                if (firstError == null) firstError = error
                Timber.w("Transport start failed: code=%s", error.code)
            } catch (error: Exception) {
                if (firstError == null) {
                    firstError = TransportException(TransportError.INTERNAL, error)
                }
                Timber.w("Transport start failed")
            }
        }
        if (startedCount == 0) {
            throw firstError ?: TransportException(TransportError.INTERNAL)
        }
    }

    /**
     * Races every transport: BLE and Wi-Fi Direct usually see the same peer,
     * and the first successful link wins. A transport that has no discovery
     * data fails fast, so the other one still gets its full timeout.
     */
    override suspend fun connect(peerId: PeerId) {
        if (closed) throw TransportException(TransportError.INTERNAL)
        if (peerId in peers.value || transports.any { peerId in it.peers.value }) return

        coroutineScope {
            val results = Channel<TransportException?>(transports.size)
            val attempts = transports.map { transport ->
                launch {
                    val error = try {
                        transport.connect(peerId)
                        null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: TransportException) {
                        error
                    } catch (error: Exception) {
                        TransportException(TransportError.INTERNAL, error)
                    }
                    results.send(error)
                }
            }

            var connected = false
            var lastError: TransportException? = null
            var remaining = attempts.size
            while (remaining > 0) {
                val error = results.receive()
                remaining -= 1
                if (error == null) {
                    connected = true
                    break
                }
                lastError = error
            }
            attempts.forEach { it.cancel() }
            if (!connected) throw lastError ?: TransportException(TransportError.NOT_CONNECTED)
        }
    }

    override suspend fun send(peerId: PeerId, packet: ByteArray) {
        if (closed) throw TransportException(TransportError.INTERNAL)

        val candidates = transports.filter { peerId in it.peers.value }
        if (candidates.isEmpty()) {
            throw TransportException(TransportError.NOT_CONNECTED)
        }

        var lastError: TransportException? = null
        for (transport in candidates) {
            try {
                transport.send(peerId, packet)
                return
            } catch (error: TransportException) {
                lastError = error
            }
        }
        throw lastError ?: TransportException(TransportError.NOT_CONNECTED)
    }

    override fun close() {
        if (closed) return
        closed = true
        for (transport in transports) {
            try {
                transport.close()
            } catch (error: Exception) {
                Timber.w("Transport close failed")
            }
        }
        hubJob.cancel()
    }

    private fun aggregate(
        states: List<TransportState>,
        connectedPeers: Int,
    ): TransportState {
        if (states.any { it is TransportState.Running }) {
            return TransportState.Running(connectedPeers)
        }
        val failure = states.filterIsInstance<TransportState.Failed>().firstOrNull()
        if (states.all { it is TransportState.Failed }) {
            return failure ?: TransportState.Failed(TransportError.INTERNAL)
        }
        if (states.all { it is TransportState.Closed }) {
            return TransportState.Closed
        }
        if (states.any { it is TransportState.Starting }) {
            return TransportState.Starting
        }
        if (failure != null && states.none { it is TransportState.Idle }) {
            return failure
        }
        return TransportState.Idle
    }
}
