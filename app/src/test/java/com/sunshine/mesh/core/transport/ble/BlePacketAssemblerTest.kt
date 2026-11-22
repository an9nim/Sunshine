package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BlePacketAssemblerTest {

    @Test
    fun assemblesChunksInOrder() {
        val first = ByteArray(16) { 1 }
        val second = ByteArray(16) { 2 }
        val last = ByteArray(4) { 3 }
        val assembler = BlePacketAssembler()

        assertNull(assembler.accept(chunk(0, 3, first)))
        assertNull(assembler.accept(chunk(1, 3, second)))
        val packet = assembler.accept(chunk(2, 3, last))

        assertArrayEquals(first + second + last, packet)
        assertFalse(assembler.isAssembling)
    }

    @Test
    fun assemblesOutOfOrderChunks() {
        val parts = listOf(
            ByteArray(16) { 1 },
            ByteArray(16) { 2 },
            ByteArray(16) { 3 },
        )
        val assembler = BlePacketAssembler()

        assertNull(assembler.accept(chunk(0, 3, parts[0])))
        assertNull(assembler.accept(chunk(2, 3, parts[2])))
        val packet = assembler.accept(chunk(1, 3, parts[1]))

        assertArrayEquals(parts[0] + parts[1] + parts[2], packet)
    }

    @Test
    fun ignoresIdenticalDuplicateChunk() {
        val assembler = BlePacketAssembler()

        assertNull(assembler.accept(chunk(0, 2, ByteArray(16) { 7 })))
        assertNull(assembler.accept(chunk(0, 2, ByteArray(16) { 7 })))
        val packet = assembler.accept(chunk(1, 2, ByteArray(16) { 8 }))

        assertArrayEquals(ByteArray(16) { 7 } + ByteArray(16) { 8 }, packet)
    }

    @Test
    fun rejectsConflictingDuplicateChunk() {
        val assembler = BlePacketAssembler()
        assembler.accept(chunk(0, 2, ByteArray(16) { 7 }))

        assertProtocolError {
            assembler.accept(chunk(0, 2, ByteArray(16) { 8 }))
        }
    }

    @Test
    fun newTransferIdRestartsOnlyFromChunkZero() {
        val assembler = BlePacketAssembler()
        assembler.accept(chunk(0, 2, ByteArray(16) { 1 }, transferId = 1))

        assertProtocolError {
            assembler.accept(chunk(1, 2, ByteArray(16) { 2 }, transferId = 2))
        }

        val packet = assembler.accept(chunk(0, 1, ByteArray(5) { 9 }, transferId = 2))

        assertArrayEquals(ByteArray(5) { 9 }, packet)
    }

    @Test
    fun rejectsTotalChunksChangeWithinTransfer() {
        val assembler = BlePacketAssembler()
        assembler.accept(chunk(0, 2, ByteArray(16) { 1 }))

        assertProtocolError {
            assembler.accept(chunk(1, 3, ByteArray(16) { 2 }))
        }
    }

    @Test
    fun expiresPartialTransferAtDeadline() {
        var now = 1_000L
        val assembler = BlePacketAssembler(timeoutMs = 10_000, clock = { now })
        assembler.accept(chunk(0, 2, ByteArray(16) { 1 }))

        now += 9_999
        assertFalse(assembler.expire())
        assertTrue(assembler.isAssembling)

        now += 1
        assertTrue(assembler.expire())
        assertFalse(assembler.isAssembling)
    }

    @Test
    fun expiredTransferCanBeReplaced() {
        var now = 0L
        val assembler = BlePacketAssembler(timeoutMs = 10_000, clock = { now })
        assembler.accept(chunk(0, 2, ByteArray(16) { 1 }))
        now = 10_000
        assertTrue(assembler.expire())

        val packet = assembler.accept(chunk(0, 1, ByteArray(3) { 5 }))

        assertArrayEquals(ByteArray(3) { 5 }, packet)
    }

    @Test
    fun clearDropsPartialTransfer() {
        val assembler = BlePacketAssembler()
        assembler.accept(chunk(0, 2, ByteArray(16) { 1 }))

        assembler.clear()

        assertFalse(assembler.isAssembling)
        val packet = assembler.accept(chunk(0, 1, ByteArray(2) { 3 }))
        assertArrayEquals(ByteArray(2) { 3 }, packet)
    }

    @Test
    fun clearWipesBufferedCopies() {
        val assembler = BlePacketAssembler()
        assembler.accept(chunk(0, 2, ByteArray(16) { 0x5a }))
        val field = BlePacketAssembler::class.java.getDeclaredField("chunks")
        field.isAccessible = true
        val buffered = (field.get(assembler) as Array<*>)[0] as ByteArray

        assembler.clear()

        assertTrue(buffered.all { it == 0.toByte() })
        assertNull(field.get(assembler))
    }

    @Test
    fun rejectsPacketAboveLimit() {
        val assembler = BlePacketAssembler()
        assertNull(assembler.accept(chunk(0, 2, ByteArray(508) { 1 })))

        assertProtocolError {
            assembler.accept(chunk(1, 2, ByteArray(16) { 2 }))
        }
    }

    @Test
    fun rejectsInvalidChunks() {
        val assembler = BlePacketAssembler()

        assertProtocolError { assembler.accept(chunk(0, 1, ByteArray(0))) }
        assertProtocolError { assembler.accept(chunk(0, 0, ByteArray(1))) }
        assertProtocolError { assembler.accept(chunk(1, 1, ByteArray(1))) }
        assertProtocolError { assembler.accept(chunk(0, 1, ByteArray(509))) }
        assertProtocolError {
            assembler.accept(chunk(0, 1, ByteArray(1), transferId = 256))
        }
    }

    @Test
    fun rejectsNonPositiveTimeout() {
        assertThrows(IllegalArgumentException::class.java) {
            BlePacketAssembler(timeoutMs = 0)
        }
    }

    @Test
    fun completedPacketResetsAssembler() {
        val assembler = BlePacketAssembler()

        assertArrayEquals(
            ByteArray(4) { 1 },
            assembler.accept(chunk(0, 1, ByteArray(4) { 1 }, transferId = 3)),
        )
        assertFalse(assembler.isAssembling)
        assertArrayEquals(
            ByteArray(4) { 2 },
            assembler.accept(chunk(0, 1, ByteArray(4) { 2 }, transferId = 3)),
        )
    }

    @Test
    fun toStringReportsProgressWithoutPayload() {
        val assembler = BlePacketAssembler()
        assembler.accept(chunk(0, 2, ByteArray(16) { 0x5a }))

        val text = assembler.toString()

        assertTrue(text.contains("chunks=1/2"))
        assertTrue(text.contains("bytes=16"))
        assertFalse(text.contains("5a"))
    }

    private fun chunk(
        index: Int,
        total: Int,
        payload: ByteArray,
        transferId: Int = 1,
    ): BleFrame.Data = BleFrame.Data(
        transferId = transferId,
        chunkIndex = index,
        totalChunks = total,
        payload = payload,
    )

    private fun assertProtocolError(block: () -> Unit) {
        val error = assertThrows(TransportException::class.java) { block() }
        assertEquals(TransportError.PROTOCOL_ERROR, error.code)
    }
}
