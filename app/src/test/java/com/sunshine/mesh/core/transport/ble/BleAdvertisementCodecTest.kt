package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.ids.PeerId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleAdvertisementCodecTest {

    private val peerId = PeerId.parse("0011223344556677")

    @Test
    fun manufacturerDataIsTheEightBytePeerId() {
        assertArrayEquals(peerId.toByteArray(), BleAdvertisementCodec.manufacturerData(peerId))
    }

    @Test
    fun parsesEightByteManufacturerData() {
        assertEquals(
            peerId,
            BleAdvertisementCodec.parseManufacturerData(peerId.toByteArray()),
        )
    }

    @Test
    fun rejectsNullOrWrongSizeManufacturerData() {
        assertNull(BleAdvertisementCodec.parseManufacturerData(null))
        assertNull(BleAdvertisementCodec.parseManufacturerData(ByteArray(0)))
        assertNull(BleAdvertisementCodec.parseManufacturerData(ByteArray(7)))
        assertNull(BleAdvertisementCodec.parseManufacturerData(ByteArray(9)))
    }

    @Test
    fun legacyPayloadFitsThe31ByteLimit() {
        assertEquals(19, BleAdvertisementCodec.legacyBytes())
        assertTrue(BleAdvertisementCodec.legacyBytes() <= BleProtocol.LEGACY_ADV_BYTES)
    }

    @Test
    fun advertisementToStringDoesNotLeakTheAddress() {
        val advertisement = BleAdvertisement(
            peerId = peerId,
            address = "AA:BB:CC:DD:EE:FF",
            rssi = -51,
        )

        val text = advertisement.toString()

        assertTrue(text.contains(peerId.value))
        assertTrue(text.contains("-51"))
        assertFalse(text.contains("AA:BB"))
    }
}
