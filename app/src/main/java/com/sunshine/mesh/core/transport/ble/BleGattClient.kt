package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.transport.TransportError
import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/**
 * Central-side GATT adapter. One instance owns exactly one connection; all
 * GATT operations are serialized by a per-connection Mutex. Callbacks never
 * take that Mutex: they only complete pending operations and emit events.
 */
interface BleGattClient : Closeable {

    val events: Flow<BleGattClientEvent>

    /** Negotiated ATT MTU, or 23 until onMtuChanged arrives. */
    val mtu: Int

    suspend fun connect()

    suspend fun discoverServices()

    /**
     * Requests an MTU and waits for onMtuChanged. If no callback arrives within
     * [BleProtocol.MTU_TIMEOUT_MS], returns the default MTU of 23.
     */
    suspend fun requestMtu(requested: Int): Int

    /** Writes the CCCD with ENABLE_INDICATION_VALUE and waits for onDescriptorWrite. */
    suspend fun enableIndications()

    /** Writes one BLE frame to RX with response and waits for onCharacteristicWrite. */
    suspend fun write(frame: ByteArray)

    override fun close()
}

sealed interface BleGattClientEvent {

    data object Connected : BleGattClientEvent

    data class Disconnected(
        val error: TransportError?,
    ) : BleGattClientEvent

    data class MtuChanged(
        val mtu: Int,
    ) : BleGattClientEvent

    data class Failed(
        val error: TransportError,
    ) : BleGattClientEvent

    class FrameReceived(
        val frame: ByteArray,
    ) : BleGattClientEvent, Closeable {

        override fun close() {
            frame.fill(0)
        }

        override fun toString(): String = "FrameReceived(frame=${frame.size}B)"
    }
}

fun interface BleGattClientFactory {
    fun create(address: String): BleGattClient
}
