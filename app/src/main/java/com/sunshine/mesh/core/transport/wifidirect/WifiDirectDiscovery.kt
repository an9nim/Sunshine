package com.sunshine.mesh.core.transport.wifidirect

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/**
 * DNS-SD service discovery: advertises our peerId/port and emits remote peers.
 * Role decisions belong to the transport, not to discovery.
 */
interface WifiDirectDiscovery : Closeable {

    val events: Flow<WifiDirectDiscoveryEvent>

    fun start(localPeerId: PeerId)

    fun stop()

    override fun close()
}

sealed interface WifiDirectDiscoveryEvent {

    data class Found(
        val peerId: PeerId,
        val deviceAddress: String,
        val port: Int,
    ) : WifiDirectDiscoveryEvent

    data class Failed(val error: TransportError) : WifiDirectDiscoveryEvent
}
