package com.sunshine.mesh.core.transport.wifidirect

/**
 * Wi-Fi Direct transport constants. Data frames are length-prefixed TCP
 * payloads; the first frame on every socket is the 8-byte peerId handshake.
 */
object WifiDirectProtocol {
    const val SERVICE_NAME = "_sunshine"
    const val SERVICE_TYPE = "_tcp"
    const val TXT_PEER_ID = "peerId"
    const val TXT_PORT = "port"

    const val PORT = 8988
    const val LENGTH_PREFIX_BYTES = 4
    const val HANDSHAKE_BYTES = 8

    /** Mesh header (34) + E2E envelope (512), same wire size as BLE. */
    const val MAX_FRAME_BYTES = 546

    const val CONNECT_TIMEOUT_MS = 10_000L
    const val GROUP_TIMEOUT_MS = 10_000L
    const val SOCKET_TIMEOUT_MS = 10_000L
    const val GROUP_POLL_MS = 200L

    const val MAX_LINKS = 4
}
