package com.sunshine.mesh.core.transport.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleCccdTest {

    @Test
    fun indicationValueEnablesAndDisables() {
        assertArrayEquals(
            byteArrayOf(0x02, 0x00),
            BleCccd.indicationValue(enabled = true),
        )
        assertArrayEquals(
            byteArrayOf(0x00, 0x00),
            BleCccd.indicationValue(enabled = false),
        )
    }

    @Test
    fun parsesIndicationAndDisabledValues() {
        assertEquals(true, BleCccd.isIndicationEnabled(byteArrayOf(0x02, 0x00)))
        assertEquals(false, BleCccd.isIndicationEnabled(byteArrayOf(0x00, 0x00)))
    }

    @Test
    fun rejectsNotificationRequest() {
        assertNull(BleCccd.isIndicationEnabled(byteArrayOf(0x01, 0x00)))
    }

    @Test
    fun rejectsWrongSizeOrHighByte() {
        assertNull(BleCccd.isIndicationEnabled(null))
        assertNull(BleCccd.isIndicationEnabled(ByteArray(0)))
        assertNull(BleCccd.isIndicationEnabled(byteArrayOf(0x02)))
        assertNull(BleCccd.isIndicationEnabled(byteArrayOf(0x02, 0x00, 0x00)))
        assertNull(BleCccd.isIndicationEnabled(byteArrayOf(0x02, 0x01)))
    }
}
