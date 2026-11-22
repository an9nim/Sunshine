package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.transport.TransportError
import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/**
 * Peripheral-side GATT adapter. One instance serves every accepted connection;
 * [events] has exactly one consumer, which dispatches events to per-connection
 * links by [BleServerConnection.id].
 */
interface BleGattServer : Closeable {

    val events: Flow<BleGattServerEvent>

    suspend fun open()

    /**
     * Sends one BLE frame as an indication and waits for onNotificationSent.
     * The per-connection queue never has more than one indication in flight.
     */
    suspend fun indicate(connection: BleServerConnection, frame: ByteArray)

    override fun close()
}

sealed interface BleGattServerEvent {

    data class Connected(
        val connection: BleServerConnection,
    ) : BleGattServerEvent

    data class Disconnected(
        val connection: BleServerConnection,
        val error: TransportError?,
    ) : BleGattServerEvent

    data class MtuChanged(
        val connection: BleServerConnection,
        val mtu: Int,
    ) : BleGattServerEvent

    data class IndicationsChanged(
        val connection: BleServerConnection,
        val enabled: Boolean,
    ) : BleGattServerEvent

    class FrameReceived(
        val connection: BleServerConnection,
        val frame: ByteArray,
    ) : BleGattServerEvent, Closeable {

        override fun close() {
            frame.fill(0)
        }

        override fun toString(): String =
            "FrameReceived(connection=$connection, frame=${frame.size}B)"
    }
}
