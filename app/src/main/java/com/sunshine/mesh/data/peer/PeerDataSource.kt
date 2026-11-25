package com.sunshine.mesh.data.peer

import com.sunshine.mesh.domain.model.Peer
import kotlinx.coroutines.flow.Flow

/** Read-only peer list used by UI screens that only display known peers. */
interface PeerDataSource {
    fun observeAll(): Flow<List<Peer>>
}
