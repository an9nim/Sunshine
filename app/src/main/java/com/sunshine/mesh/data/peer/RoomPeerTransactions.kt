package com.sunshine.mesh.data.peer

import androidx.room.withTransaction
import com.sunshine.mesh.data.local.SunshineDatabase

class RoomPeerTransactions(
    private val database: SunshineDatabase,
) : PeerTransactions {

    override suspend fun <T> run(block: suspend () -> T): T =
        database.withTransaction { block() }
}
