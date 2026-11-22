package com.sunshine.mesh.core.transport.ble

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex

/**
 * One accepted GATT connection. The MAC address stays inside the Android
 * adapter; callers and logs only see the monotonic [id].
 */
class BleServerConnection internal constructor(
    val id: Long,
    internal val device: BluetoothDevice,
) {
    internal val indicationMutex = Mutex()

    @Volatile
    internal var indicationsEnabled = false

    @Volatile
    internal var mtu = BleProtocol.DEFAULT_MTU

    @Volatile
    internal var pendingIndication: CompletableDeferred<Unit>? = null

    override fun toString(): String = "BleServerConnection(id=$id)"
}
