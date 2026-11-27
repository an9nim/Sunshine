package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/**
 * BLE advertising adapter. The peer id is the only identity placed on air;
 * trust and E2E stay above the transport.
 */
interface BleAdvertiser : Closeable {

    val events: Flow<BleAdvertiseEvent>

    fun start(peerId: PeerId)

    /**
     * Re-arms advertising after the controller dropped it: a central attached
     * or detached. The default stops and starts; implementations whose stack
     * silently stopped advertising may override this with a cheaper path.
     */
    fun restart(peerId: PeerId) {
        stop()
        start(peerId)
    }

    fun stop()

    override fun close()
}

sealed interface BleAdvertiseEvent {

    data object Started : BleAdvertiseEvent

    data class Failed(val error: TransportError) : BleAdvertiseEvent
}
