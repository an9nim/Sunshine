package com.sunshine.mesh.service

import com.sunshine.mesh.coordinator.ConnectResult
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportHub
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow UI-facing control of the transport service. The service owns the mesh;
 * this interface only starts, stops and reports it.
 */
interface NetworkControl {

    val hub: StateFlow<TransportHub?>

    /** True between a successful start request and an explicit stop. */
    val running: StateFlow<Boolean>

    /** Last startup failure, if the service could not create the transports. */
    val startError: StateFlow<TransportError?>

    /**
     * Ensures a transport link and starts the E2E key exchange. Returns a
     * typed result so the UI can tell a timeout from a radio failure.
     */
    suspend fun connect(peerId: PeerId): ConnectResult

    fun start()

    fun stop()

    fun restart()
}
