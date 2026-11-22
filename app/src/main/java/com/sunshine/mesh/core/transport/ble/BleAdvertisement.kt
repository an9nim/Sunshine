package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.ids.PeerId

/**
 * One peer discovered by the scanner. The address is needed to open a GATT
 * connection, but it must never appear in logs or in [toString].
 */
class BleAdvertisement internal constructor(
    val peerId: PeerId,
    val address: String,
    val rssi: Int,
) {
    override fun toString(): String =
        "BleAdvertisement(peerId=$peerId, rssi=$rssi)"
}
