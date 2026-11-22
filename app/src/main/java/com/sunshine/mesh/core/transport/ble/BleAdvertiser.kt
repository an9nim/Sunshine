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

    fun stop()

    override fun close()
}

sealed interface BleAdvertiseEvent {

    data object Started : BleAdvertiseEvent

    data class Failed(val error: TransportError) : BleAdvertiseEvent
}
