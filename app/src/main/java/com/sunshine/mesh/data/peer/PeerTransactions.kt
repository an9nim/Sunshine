package com.sunshine.mesh.data.peer

/**
 * Runs a block atomically. The interface exists so repository logic can be
 * unit-tested with an immediate runner while production uses a Room
 * transaction.
 */
interface PeerTransactions {
    suspend fun <T> run(block: suspend () -> T): T
}
