package com.sunshine.mesh.coordinator

import com.sunshine.mesh.core.ids.PeerId
import kotlinx.coroutines.flow.SharedFlow

/**
 * Narrow coordinator surface used by the chat UI. Keeping it separate lets the
 * ViewModel be tested without constructing the whole mesh stack.
 */
interface ChatTransport {

    val events: SharedFlow<MeshEvent>

    suspend fun sendText(peerId: PeerId, text: String): SendTextResult
}
