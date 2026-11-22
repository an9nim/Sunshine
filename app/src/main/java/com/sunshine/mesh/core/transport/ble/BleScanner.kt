package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.transport.TransportError
import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/**
 * BLE discovery adapter. It emits raw advertisements and never decides whether
 * to connect; that belongs to BleTransport.
 */
interface BleScanner : Closeable {

    val events: Flow<BleScanEvent>

    fun start()

    fun stop()

    override fun close()
}

sealed interface BleScanEvent {

    data class Found(val advertisement: BleAdvertisement) : BleScanEvent

    data class Failed(val error: TransportError) : BleScanEvent
}
