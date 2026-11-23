package com.sunshine.mesh.core.transport.wifidirect

import java.net.InetAddress
import java.io.Closeable

/**
 * Group formation and teardown. The higher peerId creates the group (GO),
 * the lower peerId connects as a client; the transport enforces that rule.
 */
interface WifiDirectGroupManager : Closeable {

    suspend fun createGroup(): WifiDirectGroupInfo

    suspend fun connect(deviceAddress: String): WifiDirectGroupInfo

    suspend fun removeGroup()

    suspend fun currentGroup(): WifiDirectGroupInfo?

    /** Must force removeGroup even when no group is tracked locally. */
    override fun close()
}

data class WifiDirectGroupInfo(
    val isGroupOwner: Boolean,
    val ownerAddress: InetAddress,
)
