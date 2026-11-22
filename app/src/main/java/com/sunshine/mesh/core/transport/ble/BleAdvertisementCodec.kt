package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.ids.PeerId

/**
 * Pure advertisement payload logic.
 *
 * Android's [android.bluetooth.le.AdvertiseData.Builder.addManufacturerData]
 * takes the company id separately, and
 * [android.bluetooth.le.ScanRecord.getManufacturerSpecificData] returns only
 * the bytes after that company id. Therefore this codec deals with the 8-byte
 * peer id payload, not the two company-id bytes.
 *
 * Legacy advertisement budget for variant A:
 * flags 3 + 16-bit UUID AD 4 + manufacturer AD 12 = 19 bytes.
 */
object BleAdvertisementCodec {

    fun manufacturerData(peerId: PeerId): ByteArray = peerId.toByteArray()

    fun parseManufacturerData(data: ByteArray?): PeerId? {
        if (data == null || data.size != BleProtocol.PEER_ID_BYTES) return null
        return PeerId.fromBytes(data)
    }

    fun legacyBytes(): Int =
        FLAGS_AD_BYTES + UUID16_AD_BYTES + MANUFACTURER_AD_BYTES

    private const val FLAGS_AD_BYTES = 3
    private const val UUID16_AD_BYTES = 4
    private const val MANUFACTURER_AD_BYTES = 2 + 2 + BleProtocol.PEER_ID_BYTES
}
