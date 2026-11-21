package com.sunshine.mesh.core.ids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageIdTest {

    @Test
    fun randomProducesSixteenBytesOfLowercaseHex() {
        val messageId = MessageId.random()

        assertEquals(32, messageId.value.length)
        assertTrue(messageId.value.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(16, messageId.toByteArray().size)
    }

    @Test
    fun randomIdsDiffer() {
        assertFalse(MessageId.random() == MessageId.random())
    }

    @Test
    fun fromBytesRoundTrips() {
        val bytes = ByteArray(16) { it.toByte() }
        val messageId = MessageId.fromBytes(bytes)

        assertTrue(bytes.contentEquals(messageId.toByteArray()))
    }

    @Test
    fun parseNormalizesCase() {
        assertEquals(
            "00112233445566778899aabbccddeeff",
            MessageId.parse("00112233445566778899AABBCCDDEEFF").value,
        )
    }

    @Test
    fun rejectsWrongSizesAndCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            MessageId.fromBytes(ByteArray(15))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MessageId.parse("00112233445566778899aabbccddeef")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MessageId.parse("00112233445566778899aabbccddeefg")
        }
    }
}
