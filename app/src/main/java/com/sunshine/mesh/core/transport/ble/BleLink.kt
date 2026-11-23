package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.ids.PeerId
import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/**
 * One established BLE link. It deals in whole 512-byte packets; chunking and
 * reassembly are internal. The transport above never sees BLE frames.
 */
interface BleLink : Closeable {

    val peerId: PeerId

    val mtu: Int

    /** Emits only fully reassembled packets. The consumer owns each array. */
    val incoming: Flow<ByteArray>

    suspend fun send(packet: ByteArray)

    override fun close()
}
