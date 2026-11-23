package com.sunshine.mesh.core.transport.wifidirect

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber

/**
 * DNS-SD discovery over WifiP2pManager. The TXT record carries the peerId and
 * the TCP port; the device address comes from the discovery response.
 */
class AndroidWifiDirectDiscovery(
    context: Context,
    private val prerequisites: WifiDirectPrerequisites,
) : WifiDirectDiscovery {

    private val appContext = context.applicationContext

    private val eventChannel = Channel<WifiDirectDiscoveryEvent>(
        capacity = EVENT_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<WifiDirectDiscoveryEvent> = eventChannel.receiveAsFlow()

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var serviceInfo: WifiP2pDnsSdServiceInfo? = null
    private var currentPeerId: PeerId? = null

    @Volatile
    private var running = false

    private var closed = false

    private val txtListener = WifiP2pManager.DnsSdTxtRecordListener { _, record, device ->
        if (!running) return@DnsSdTxtRecordListener
        val peerId = record[WifiDirectProtocol.TXT_PEER_ID]
            ?.let { runCatching { PeerId.parse(it) }.getOrNull() }
            ?: return@DnsSdTxtRecordListener
        val port = record[WifiDirectProtocol.TXT_PORT]?.toIntOrNull()
            ?: return@DnsSdTxtRecordListener
        val address = device.deviceAddress ?: return@DnsSdTxtRecordListener
        eventChannel.trySend(
            WifiDirectDiscoveryEvent.Found(peerId, address, port),
        )
    }

    private val serviceListener = WifiP2pManager.DnsSdServiceResponseListener { _, _, _ ->
        // The TXT listener already carries everything we need.
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun start(localPeerId: PeerId) {
        if (closed) throw TransportException(TransportError.INTERNAL)
        if (running && currentPeerId == localPeerId) return

        stopInternal()
        prerequisites.requireDiscoveryReady()
        val manager = prerequisites.requireManager()
        val channel = manager.initialize(appContext, appContext.mainLooper, null)
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            WifiDirectProtocol.SERVICE_NAME,
            WifiDirectProtocol.SERVICE_TYPE,
            mapOf(
                WifiDirectProtocol.TXT_PEER_ID to localPeerId.value,
                WifiDirectProtocol.TXT_PORT to WifiDirectProtocol.PORT.toString(),
            ),
        )

        this.manager = manager
        this.channel = channel
        this.serviceInfo = serviceInfo
        currentPeerId = localPeerId
        running = true
        try {
            manager.addLocalService(channel, serviceInfo, actionListener("addLocalService"))
            manager.setDnsSdResponseListeners(channel, serviceListener, txtListener)
            manager.discoverServices(channel, actionListener("discoverServices"))
        } catch (error: SecurityException) {
            stopInternal()
            throw TransportException(TransportError.PERMISSION_DENIED, error)
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun stop() {
        stopInternal()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        stopInternal()
        eventChannel.close()
    }

    private fun actionListener(operation: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = Unit

        override fun onFailure(reason: Int) {
            if (!running) return
            Timber.w("Wi-Fi Direct %s failed: reason=%d", operation, reason)
            eventChannel.trySend(
                WifiDirectDiscoveryEvent.Failed(TransportError.WIFI_DISCOVERY_FAILED),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopInternal() {
        val currentManager = manager
        val currentChannel = channel
        val currentService = serviceInfo
        manager = null
        channel = null
        serviceInfo = null
        currentPeerId = null
        running = false
        if (currentManager == null || currentChannel == null) return

        val silent = object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) = Unit
        }
        try {
            if (currentService != null) {
                currentManager.removeLocalService(currentChannel, currentService, silent)
            }
            currentManager.clearServiceRequests(currentChannel, silent)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    private companion object {
        const val EVENT_QUEUE_CAPACITY = 64
    }
}
