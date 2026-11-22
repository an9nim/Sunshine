package com.sunshine.mesh.core.transport.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BleUuidsTest {

    @Test
    fun serviceAndCharacteristicsUseTheBluetoothBaseUuid() {
        assertEquals(
            "0000fff0-0000-1000-8000-00805f9b34fb",
            BleUuids.SERVICE.toString(),
        )
        assertEquals(
            "0000fff1-0000-1000-8000-00805f9b34fb",
            BleUuids.RX.toString(),
        )
        assertEquals(
            "0000fff2-0000-1000-8000-00805f9b34fb",
            BleUuids.TX.toString(),
        )
        assertEquals(
            "00002902-0000-1000-8000-00805f9b34fb",
            BleUuids.CCCD.toString(),
        )
    }
}
