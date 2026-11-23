package com.sunshine.mesh.core.transport.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleGattClientEventTest {

    @Test
    fun frameReceivedCloseWipesTheFrame() {
        val frame = byteArrayOf(1, 2, 3, 4)
        val event = BleGattClientEvent.FrameReceived(frame)

        event.close()

        assertArrayEquals(byteArrayOf(0, 0, 0, 0), frame)
    }

    @Test
    fun repeatedCloseKeepsTheFrameWiped() {
        val frame = byteArrayOf(5, 6, 7)
        val event = BleGattClientEvent.FrameReceived(frame)

        event.close()
        frame[0] = 9
        event.close()

        assertArrayEquals(byteArrayOf(0, 0, 0), frame)
    }

    @Test
    fun toStringDoesNotLeakPayloadBytes() {
        val event = BleGattClientEvent.FrameReceived(byteArrayOf(0x41, 0x42, 0x43))

        val text = event.toString()

        assertTrue(text.contains("3B"))
        assertFalse(text.contains("65"))
        assertFalse(text.contains("66"))
    }
}
