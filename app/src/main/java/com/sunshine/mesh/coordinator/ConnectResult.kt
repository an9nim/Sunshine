package com.sunshine.mesh.coordinator

import com.sunshine.mesh.core.transport.TransportError

/** Result of bringing up a transport link before a chat is opened. */
sealed interface ConnectResult {
    data object Connected : ConnectResult

    /** The radio was scanning, but no link appeared before the timeout. */
    data object Timeout : ConnectResult

    data class Failed(val error: TransportError) : ConnectResult
}
