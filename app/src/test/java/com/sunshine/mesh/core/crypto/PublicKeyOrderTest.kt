package com.sunshine.mesh.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicKeyOrderTest {

    @Test
    fun comparesBytesAsUnsigned() {
        val lower = byteArrayOf(0x7f)
        val higher = byteArrayOf(0x80.toByte())

        assertTrue(PublicKeyOrder.compareUnsigned(lower, higher) < 0)
        assertTrue(PublicKeyOrder.compareUnsigned(higher, lower) > 0)
        assertEquals(0, PublicKeyOrder.compareUnsigned(lower, lower.copyOf()))
    }

    @Test
    fun fallsBackToLengthForCommonPrefix() {
        val shorter = byteArrayOf(1, 2)
        val longer = byteArrayOf(1, 2, 3)

        assertTrue(PublicKeyOrder.compareUnsigned(shorter, longer) < 0)
        assertTrue(PublicKeyOrder.compareUnsigned(longer, shorter) > 0)
    }

    @Test
    fun lowerAndHigherIsSymmetricAndDoesNotCopy() {
        val first = byteArrayOf(0x00, 0xff.toByte())
        val second = byteArrayOf(0x00, 0x01)

        val fromFirst = PublicKeyOrder.lowerAndHigher(first, second)
        val fromSecond = PublicKeyOrder.lowerAndHigher(second, first)

        assertSame(second, fromFirst.first)
        assertSame(first, fromFirst.second)
        assertSame(second, fromSecond.first)
        assertSame(first, fromSecond.second)
        assertArrayEquals(second, fromFirst.first)
    }

    @Test
    fun equalKeysReturnFirstAsLower() {
        val first = byteArrayOf(5, 5)
        val second = byteArrayOf(5, 5)

        val (lower, higher) = PublicKeyOrder.lowerAndHigher(first, second)

        assertSame(first, lower)
        assertSame(second, higher)
    }
}
