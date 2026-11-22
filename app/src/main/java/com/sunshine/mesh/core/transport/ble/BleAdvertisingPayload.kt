package com.sunshine.mesh.core.transport.ble

import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid
import com.sunshine.mesh.core.ids.PeerId

/**
 * Android-facing advertisement payload. The 16-bit service UUID and the 8-byte
 * peer id fit the legacy 31-byte budget; no scan response is used in Stage 3.
 */
internal object BleAdvertisingPayload {

    fun advertiseData(peerId: PeerId): AdvertiseData =
        AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleUuids.SERVICE))
            .addManufacturerData(
                BleProtocol.MANUFACTURER_ID,
                BleAdvertisementCodec.manufacturerData(peerId),
            )
            .build()

    fun peerIdFrom(record: ScanRecord?): PeerId? = try {
        BleAdvertisementCodec.parseManufacturerData(
            record?.getManufacturerSpecificData(BleProtocol.MANUFACTURER_ID),
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}
