package com.sunshine.mesh.core.crypto

import com.sunshine.mesh.core.crypto.e2e.E2EException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyNumberTest {

    @Test
    fun matchesKnownVector() {
        val own = ByteArray(32) { it.toByte() }
        val peer = ByteArray(32) { (0x80 + it).toByte() }

        assertEquals("44819-58157-63818-71319-08514-03798", SafetyNumber.of(own, peer))
    }

    @Test
    fun isSymmetric() {
        val first = ByteArray(32) { (it * 7).toByte() }
        val second = ByteArray(32) { (0xff - it).toByte() }

        assertEquals(SafetyNumber.of(first, second), SafetyNumber.of(second, first))
    }

    @Test
    fun hasSixFiveDigitGroups() {
        val code = SafetyNumber.of(ByteArray(32) { 1 }, ByteArray(32) { 2 })

        assertTrue(Regex("""\d{5}(-\d{5}){5}""").matches(code))
        assertEquals(35, code.length)
    }

    @Test
    fun differentKeysProduceDifferentCodes() {
        val own = ByteArray(32) { 1 }
        val firstPeer = ByteArray(32) { 2 }
        val secondPeer = ByteArray(32) { 3 }

        val first = SafetyNumber.of(own, firstPeer)
        val second = SafetyNumber.of(own, secondPeer)

        assertTrue(first != second)
    }

    @Test
    fun rejectsWrongKeySize() {
        val exception = assertThrows(E2EException::class.java) {
            SafetyNumber.of(ByteArray(31), ByteArray(32))
        }

        assertEquals(E2EException.Code.INVALID_PUBLIC_KEY, exception.code)
    }
}
