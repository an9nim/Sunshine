package com.sunshine.mesh.core.transport

import com.sunshine.mesh.core.ids.PeerId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

/**
 * Opaque packet transport. Implementations never parse the payload: a packet
 * is either delivered whole or rejected. E2E and trust live above this layer.
 */
interface Transport : Closeable {

    val state: StateFlow<TransportState>

    val incoming: Flow<InboundPacket>

    fun start()

    suspend fun send(peerId: PeerId, packet: ByteArray)

    override fun close()
}

/**
 * One complete inbound packet. The consumer owns [packet] and must close this
 * object after E2E processing; [close] wipes the ciphertext.
 */
class InboundPacket(
    val peerId: PeerId,
    val packet: ByteArray,
) : Closeable {

    override fun close() {
        packet.fill(0)
    }

    override fun toString(): String =
        "InboundPacket(peerId=$peerId, packet=${packet.size}B)"
}

sealed interface TransportState {
    data object Idle : TransportState
    data object Starting : TransportState
    data class Running(val connectedPeers: Int) : TransportState
    data class Failed(val error: TransportError) : TransportState
    data object Closed : TransportState
}

/**
 * Stable transport failure codes. The name is safe for logs: it never contains
 * keys, plaintext, addresses or raw platform exception messages.
 */
enum class TransportError {
    BLUETOOTH_UNAVAILABLE,
    BLUETOOTH_DISABLED,
    PERMISSION_DENIED,
    LOCATION_DISABLED,
    PROFILE_MISSING,
    FOREGROUND_SERVICE_DENIED,
    SCAN_FAILED,
    ADVERTISE_FAILED,
    GATT_SERVER_FAILED,
    CONNECT_TIMEOUT,
    CONNECT_FAILED,
    SERVICE_DISCOVERY_FAILED,
    HANDSHAKE_TIMEOUT,
    PROTOCOL_ERROR,
    TRANSFER_TIMEOUT,
    LINK_LOST,
    NOT_CONNECTED,
    PACKET_TOO_LARGE,
    BUFFER_OVERFLOW,
    INTERNAL,
}

class TransportException(
    val code: TransportError,
    cause: Throwable? = null,
) : Exception(code.name, cause)
