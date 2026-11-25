package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.InboundPacket
import com.sunshine.mesh.core.transport.Transport
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import com.sunshine.mesh.core.transport.TransportKind
import com.sunshine.mesh.core.transport.TransportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE implementation of [Transport]. It owns discovery, advertising, the GATT
 * server and every central client, but never parses packet payloads.
 *
 * Role rule: the lower peerId connects (central), the higher peerId accepts
 * (peripheral). At most [maxLinks] links are active at once.
 */
class BleTransport(
    private val localPeerId: PeerId,
    private val scanner: BleScanner,
    private val advertiser: BleAdvertiser,
    private val server: BleGattServer,
    private val clientFactory: BleGattClientFactory,
    scope: CoroutineScope,
    private val maxLinks: Int = BleProtocol.MAX_LINKS,
) : Transport {

    init {
        require(maxLinks > 0) { "maxLinks must be positive" }
    }

    private val transportJob = Job(scope.coroutineContext[Job])
    private val transportScope = CoroutineScope(scope.coroutineContext + transportJob)

    private val mutableState = MutableStateFlow<TransportState>(TransportState.Idle)
    override val state: StateFlow<TransportState> = mutableState.asStateFlow()

    private val mutablePeers = MutableStateFlow<Set<PeerId>>(emptySet())
    override val peers: StateFlow<Set<PeerId>> = mutablePeers.asStateFlow()

    override val kind: TransportKind = TransportKind.BLE

    private val incomingChannel = Channel<InboundPacket>(capacity = INCOMING_QUEUE_CAPACITY)
    override val incoming: Flow<InboundPacket> = incomingChannel.receiveAsFlow()

    private val registryLock = Any()
    private val links = ConcurrentHashMap<PeerId, BleLink>()
    private val linkJobs = ConcurrentHashMap<PeerId, Job>()
    private val centralPending = ConcurrentHashMap.newKeySet<PeerId>()
    private val peripherals = ConcurrentHashMap<Long, BlePeripheralLink>()
    private val peripheralPending = ConcurrentHashMap.newKeySet<Long>()

    @Volatile
    private var closed = false

    @Volatile
    private var started = false

    override fun start() {
        synchronized(registryLock) {
            if (closed) throw TransportException(TransportError.INTERNAL)
            if (started) return
            started = true
        }
        mutableState.value = TransportState.Starting
        transportScope.launch {
            try {
                if (closed) return@launch
                server.open()
                advertiser.start(localPeerId)
                scanner.start()
                startDispatchers()
                mutableState.value = TransportState.Running(linkCount())
            } catch (error: TransportException) {
                fail(error.code)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(TransportError.INTERNAL)
            }
        }
    }

    override suspend fun send(peerId: PeerId, packet: ByteArray) {
        if (closed) throw TransportException(TransportError.INTERNAL)
        val link = links[peerId] ?: throw TransportException(TransportError.NOT_CONNECTED)
        link.send(packet)
    }

    override fun close() {
        synchronized(registryLock) {
            if (closed) return
            closed = true
        }
        mutableState.value = TransportState.Closed
        shutdown()
    }

    private fun startDispatchers() {
        transportScope.launch {
            scanner.events.collect { event -> handleScanEvent(event) }
        }
        transportScope.launch {
            advertiser.events.collect { event ->
                if (event is BleAdvertiseEvent.Failed) fail(event.error)
            }
        }
        transportScope.launch {
            server.events.collect { event -> handleServerEvent(event) }
        }
    }

    private fun handleScanEvent(event: BleScanEvent) {
        when (event) {
            is BleScanEvent.Found -> maybeConnectCentral(event.advertisement)
            is BleScanEvent.Failed -> fail(event.error)
        }
    }

    private fun maybeConnectCentral(advertisement: BleAdvertisement) {
        val remote = advertisement.peerId
        if (remote == localPeerId) return
        if (localPeerId.value >= remote.value) return

        synchronized(registryLock) {
            if (closed) return
            if (links.containsKey(remote) || centralPending.contains(remote)) return
            if (activeCount() >= maxLinks) return
            centralPending.add(remote)
        }

        transportScope.launch {
            val client = clientFactory.create(advertisement.address)
            val link = BleCentralLink(client, localPeerId, remote, transportScope)
            try {
                link.establish()
                val registered = synchronized(registryLock) {
                    centralPending.remove(remote)
                    if (closed || links.containsKey(remote)) {
                        false
                    } else {
                        links[remote] = link
                        true
                    }
                }
                if (!registered) {
                    link.close()
                    return@launch
                }
                startLinkCollector(remote, link)
                updateRunningState()
            } catch (error: TransportException) {
                synchronized(registryLock) { centralPending.remove(remote) }
                link.close()
                updateRunningState()
            } catch (error: CancellationException) {
                synchronized(registryLock) { centralPending.remove(remote) }
                link.close()
                throw error
            }
        }
    }

    private fun handleServerEvent(event: BleGattServerEvent) {
        when (event) {
            is BleGattServerEvent.Connected -> onServerConnected(event.connection)
            is BleGattServerEvent.Disconnected ->
                onServerDisconnected(event.connection, event.error)
            is BleGattServerEvent.MtuChanged ->
                peripherals[event.connection.id]?.onMtuChanged(event.mtu)
            is BleGattServerEvent.IndicationsChanged ->
                peripherals[event.connection.id]?.onIndicationsChanged(event.enabled)
            is BleGattServerEvent.FrameReceived -> {
                val link = peripherals[event.connection.id]
                if (link != null) link.onFrame(event) else event.close()
            }
        }
    }

    private fun onServerConnected(connection: BleServerConnection) {
        val link = synchronized(registryLock) {
            if (closed || activeCount() >= maxLinks) return
            val created = BlePeripheralLink(server, connection, localPeerId, transportScope)
            peripherals[connection.id] = created
            peripheralPending.add(connection.id)
            created
        }

        transportScope.launch {
            try {
                link.establish()
                val peerId = link.peerId
                val registered = synchronized(registryLock) {
                    peripheralPending.remove(connection.id)
                    if (closed || links.containsKey(peerId)) {
                        false
                    } else {
                        links[peerId] = link
                        true
                    }
                }
                if (!registered) {
                    synchronized(registryLock) { peripherals.remove(connection.id) }
                    link.close()
                    updateRunningState()
                    return@launch
                }
                startLinkCollector(peerId, link)
                updateRunningState()
            } catch (error: TransportException) {
                synchronized(registryLock) {
                    peripheralPending.remove(connection.id)
                    peripherals.remove(connection.id)
                }
                link.close()
                updateRunningState()
            } catch (error: CancellationException) {
                synchronized(registryLock) {
                    peripheralPending.remove(connection.id)
                    peripherals.remove(connection.id)
                }
                link.close()
                throw error
            }
        }
    }

    private fun onServerDisconnected(
        connection: BleServerConnection,
        error: TransportError?,
    ) {
        val link = synchronized(registryLock) {
            peripheralPending.remove(connection.id)
            peripherals.remove(connection.id)
        } ?: return

        link.onDisconnected(error)
        if (link.established.isCompleted) {
            removeEstablishedLink(link.peerId)
        } else {
            link.close()
            updateRunningState()
        }
    }

    private fun startLinkCollector(peerId: PeerId, link: BleLink) {
        val job = transportScope.launch {
            try {
                link.incoming.collect { packet ->
                    incomingChannel.send(InboundPacket(peerId, packet))
                }
            } catch (_: ClosedSendChannelException) {
                // Transport is closing; the packet is owned by the channel.
            } finally {
                val current = synchronized(registryLock) { links[peerId] }
                if (current === link) removeEstablishedLink(peerId)
            }
        }
        synchronized(registryLock) { linkJobs[peerId] = job }
    }

    private fun removeEstablishedLink(peerId: PeerId) {
        val link = synchronized(registryLock) {
            linkJobs.remove(peerId)?.cancel()
            val removed = links.remove(peerId)
            if (removed != null) {
                val entry = peripherals.entries.firstOrNull { it.value === removed }
                if (entry != null) {
                    peripherals.remove(entry.key)
                    peripheralPending.remove(entry.key)
                }
            }
            removed
        }
        link?.close()
        updateRunningState()
    }

    private fun fail(error: TransportError) {
        if (closed) return
        Timber.w("BLE transport failed: code=%s", error)
        mutableState.value = TransportState.Failed(error)
        shutdown()
    }

    private fun shutdown() {
        transportJob.cancel()
        val snapshot = synchronized(registryLock) {
            val all = links.values.toList() + peripherals.values.toList()
            links.clear()
            peripherals.clear()
            centralPending.clear()
            peripheralPending.clear()
            linkJobs.clear()
            all
        }
        snapshot.forEach { it.close() }
        mutablePeers.value = emptySet()
        scanner.close()
        advertiser.close()
        server.close()
        incomingChannel.close()
    }

    private fun updateRunningState() {
        val snapshot = synchronized(registryLock) { links.keys.toSet() }
        mutablePeers.value = snapshot
        if (closed) return
        if (mutableState.value is TransportState.Running) {
            mutableState.value = TransportState.Running(snapshot.size)
        }
    }

    private fun linkCount(): Int = synchronized(registryLock) { links.size }

    /** Must be called while holding [registryLock]. */
    private fun activeCount(): Int =
        links.size + centralPending.size + peripheralPending.size

    private companion object {
        const val INCOMING_QUEUE_CAPACITY = 64
    }
}
