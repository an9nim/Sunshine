package com.sunshine.mesh.core.transport.wifidirect

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.InboundPacket
import com.sunshine.mesh.core.transport.Transport
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import com.sunshine.mesh.core.transport.TransportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi Direct implementation of [Transport]. Role rule mirrors BLE: the
 * higher peerId creates the group and accepts sockets (GO), the lower peerId
 * connects as a client. Payloads stay opaque; framing is length-prefixed TCP.
 */
class WifiDirectTransport(
    private val localPeerId: PeerId,
    private val discovery: WifiDirectDiscovery,
    private val groups: WifiDirectGroupManager,
    private val prerequisites: WifiDirectGate,
    scope: CoroutineScope,
    private val maxLinks: Int = WifiDirectProtocol.MAX_LINKS,
    private val port: Int = WifiDirectProtocol.PORT,
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

    private val incomingChannel = Channel<InboundPacket>(capacity = INCOMING_QUEUE_CAPACITY)
    override val incoming: Flow<InboundPacket> = incomingChannel.receiveAsFlow()

    private val registryLock = Any()
    private val links = ConcurrentHashMap<PeerId, WifiDirectSocketLink>()
    private val linkJobs = ConcurrentHashMap<PeerId, Job>()
    private val pending = ConcurrentHashMap.newKeySet<PeerId>()

    @Volatile
    private var closed = false

    @Volatile
    private var started = false

    private var groupStarted = false

    override fun start() {
        synchronized(registryLock) {
            if (closed) throw TransportException(TransportError.INTERNAL)
            if (started) return
            started = true
        }
        mutableState.value = TransportState.Starting
        transportScope.launch {
            try {
                prerequisites.requireDiscoveryReady()
                discovery.start(localPeerId)
                transportScope.launch {
                    discovery.events.collect { event -> handleDiscoveryEvent(event) }
                }
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

    private fun handleDiscoveryEvent(event: WifiDirectDiscoveryEvent) {
        when (event) {
            is WifiDirectDiscoveryEvent.Found -> onFound(event)
            is WifiDirectDiscoveryEvent.Failed -> fail(event.error)
        }
    }

    private fun onFound(event: WifiDirectDiscoveryEvent.Found) {
        val remote = event.peerId
        if (remote == localPeerId) return

        if (localPeerId.value > remote.value) {
            ensureGroup()
            return
        }

        synchronized(registryLock) {
            if (closed) return
            if (links.containsKey(remote) || pending.contains(remote)) return
            if (activeCount() >= maxLinks) return
            pending.add(remote)
        }

        transportScope.launch {
            try {
                val info = groups.connect(event.deviceAddress)
                val link = connectWifiDirectLink(
                    info.ownerAddress,
                    event.port,
                    localPeerId,
                    transportScope,
                )
                val registered = synchronized(registryLock) {
                    pending.remove(remote)
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
                synchronized(registryLock) { pending.remove(remote) }
                updateRunningState()
            } catch (error: CancellationException) {
                synchronized(registryLock) { pending.remove(remote) }
                throw error
            }
        }
    }

    private fun ensureGroup() {
        synchronized(registryLock) {
            if (closed || groupStarted) return
            groupStarted = true
        }
        transportScope.launch {
            try {
                groups.createGroup()
                startAcceptLoop()
            } catch (error: TransportException) {
                synchronized(registryLock) { groupStarted = false }
                fail(error.code)
            }
        }
    }

    private fun startAcceptLoop() {
        transportScope.launch {
            val serverSocket = withContext(Dispatchers.IO) { ServerSocket(port) }
            try {
                while (isActive) {
                    val accepted = try {
                        acceptWifiDirectLink(serverSocket, transportScope)
                    } catch (error: TransportException) {
                        continue
                    }
                    val remote = accepted.peerId
                    val link = accepted.link
                    val registered = synchronized(registryLock) {
                        if (closed ||
                            remote.value >= localPeerId.value ||
                            links.containsKey(remote) ||
                            activeCount() >= maxLinks
                        ) {
                            false
                        } else {
                            links[remote] = link
                            true
                        }
                    }
                    if (!registered) {
                        link.close()
                        continue
                    }
                    startLinkCollector(remote, link)
                    updateRunningState()
                }
            } finally {
                try {
                    serverSocket.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun startLinkCollector(peerId: PeerId, link: WifiDirectSocketLink) {
        val job = transportScope.launch {
            try {
                link.incoming.collect { packet ->
                    incomingChannel.send(InboundPacket(peerId, packet))
                }
            } catch (_: ClosedSendChannelException) {
            } finally {
                val current = synchronized(registryLock) { links[peerId] }
                if (current === link) removeLink(peerId)
            }
        }
        synchronized(registryLock) { linkJobs[peerId] = job }
    }

    private fun removeLink(peerId: PeerId) {
        val link = synchronized(registryLock) {
            linkJobs.remove(peerId)?.cancel()
            links.remove(peerId)
        }
        link?.close()
        updateRunningState()
    }

    private fun fail(error: TransportError) {
        if (closed) return
        Timber.w("Wi-Fi Direct transport failed: code=%s", error)
        mutableState.value = TransportState.Failed(error)
        shutdown()
    }

    private fun shutdown() {
        transportJob.cancel()
        val snapshot = synchronized(registryLock) {
            val all = links.values.toList()
            links.clear()
            pending.clear()
            linkJobs.clear()
            all
        }
        snapshot.forEach { it.close() }
        mutablePeers.value = emptySet()
        discovery.close()
        groups.close()
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
    private fun activeCount(): Int = links.size + pending.size

    private companion object {
        const val INCOMING_QUEUE_CAPACITY = 64
    }
}
