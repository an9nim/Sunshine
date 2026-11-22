package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException

/**
 * Reassembles one 512-byte packet from DATA chunks. One instance belongs to
 * one link, so chunks from different peers can never mix.
 *
 * Out-of-order chunks are accepted because the header carries their index.
 * A transfer is bounded by [timeoutMs] from its first chunk; on expiry the
 * partial buffer is wiped. [clock] is injectable for deterministic tests.
 */
class BlePacketAssembler(
    private val timeoutMs: Long = BleProtocol.TRANSFER_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    init {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
    }

    private var transferId: Int = -1
    private var totalChunks: Int = 0
    private var chunks: Array<ByteArray?>? = null
    private var received: Int = 0
    private var bufferedBytes: Int = 0
    private var startedAt: Long = 0L

    val isAssembling: Boolean
        get() = chunks != null

    /**
     * Returns the complete packet once every chunk is present, or null while
     * waiting for more chunks. The returned array is owned by the caller.
     */
    @Synchronized
    fun accept(chunk: BleFrame.Data): ByteArray? {
        validate(chunk)

        if (chunks == null) {
            if (chunk.chunkIndex != 0) {
                throw protocolError("a transfer must start at chunk 0")
            }
            start(chunk)
        } else if (chunk.transferId != transferId) {
            if (chunk.chunkIndex != 0) {
                throw protocolError("a new transfer must start at chunk 0")
            }
            clear()
            start(chunk)
        } else if (chunk.totalChunks != totalChunks) {
            throw protocolError("totalChunks changed within a transfer")
        }

        val assembly = requireNotNull(chunks)
        val previous = assembly[chunk.chunkIndex]
        if (previous != null) {
            if (previous.contentEquals(chunk.payload)) return null
            throw protocolError("conflicting duplicate chunk")
        }

        if (bufferedBytes + chunk.payload.size > BleProtocol.MAX_PACKET_BYTES) {
            throw protocolError("packet exceeds ${BleProtocol.MAX_PACKET_BYTES} bytes")
        }

        assembly[chunk.chunkIndex] = chunk.payload.copyOf()
        received += 1
        bufferedBytes += chunk.payload.size

        if (received != totalChunks) return null
        return assemble()
    }

    /**
     * Drops an expired partial transfer. Returns true when something was
     * dropped, false when there is nothing pending or the deadline is ahead.
     */
    @Synchronized
    fun expire(): Boolean {
        if (chunks == null) return false
        if (clock() - startedAt < timeoutMs) return false
        clear()
        return true
    }

    /** Drops and wipes every buffered chunk. */
    @Synchronized
    fun clear() {
        chunks?.forEach { it?.fill(0) }
        chunks = null
        transferId = -1
        totalChunks = 0
        received = 0
        bufferedBytes = 0
        startedAt = 0L
    }

    override fun toString(): String =
        if (chunks == null) {
            "BlePacketAssembler(idle)"
        } else {
            "BlePacketAssembler(transferId=$transferId, " +
                "chunks=$received/$totalChunks, bytes=$bufferedBytes)"
        }

    private fun start(chunk: BleFrame.Data) {
        transferId = chunk.transferId
        totalChunks = chunk.totalChunks
        chunks = arrayOfNulls(totalChunks)
        received = 0
        bufferedBytes = 0
        startedAt = clock()
    }

    private fun assemble(): ByteArray {
        val assembly = requireNotNull(chunks)
        val packet = ByteArray(bufferedBytes)
        var offset = 0
        for (index in 0 until totalChunks) {
            val part = requireNotNull(assembly[index]) {
                "completed assembly has an empty slot"
            }
            part.copyInto(packet, destinationOffset = offset)
            offset += part.size
            part.fill(0)
        }

        chunks = null
        transferId = -1
        totalChunks = 0
        received = 0
        bufferedBytes = 0
        startedAt = 0L
        return packet
    }

    private fun validate(chunk: BleFrame.Data) {
        if (chunk.transferId !in 0..0xff) {
            throw protocolError("transferId is out of range")
        }
        if (chunk.totalChunks !in 1..BleProtocol.MAX_CHUNKS) {
            throw protocolError("totalChunks is out of range")
        }
        if (chunk.chunkIndex !in 0 until chunk.totalChunks) {
            throw protocolError("chunkIndex is outside totalChunks")
        }
        if (chunk.payload.isEmpty() ||
            chunk.payload.size > BleProtocol.MAX_DATA_PAYLOAD_BYTES
        ) {
            throw protocolError("data payload size is out of range")
        }
    }

    private fun protocolError(message: String): TransportException =
        TransportException(TransportError.PROTOCOL_ERROR, IllegalArgumentException(message))
}
