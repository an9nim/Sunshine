package com.sunshine.mesh.core.transport.ble

/**
 * BLE transport protocol constants.
 *
 * A transport packet is a 34-byte mesh header plus the 512-byte E2E envelope
 * (546 bytes total). A GATT attribute value is capped at 512 bytes, so a DATA
 * frame carries at most 508 payload bytes after the 4-byte frame header. At
 * the default ATT MTU of 23 that leaves 16 bytes of payload per chunk.
 */
object BleProtocol {
    const val VERSION = 1

    const val MAX_PACKET_BYTES = 546
    const val MAX_ATTRIBUTE_BYTES = 512

    // Legacy advertising: 16-bit service UUID + 8-byte peer id fits in 19 B.
    const val MANUFACTURER_ID = 0xFFFF // development id; replace before release
    const val LEGACY_ADV_BYTES = 31
    const val PEER_ID_BYTES = 8
    const val ATT_HEADER_BYTES = 3
    const val FRAME_HEADER_BYTES = 4
    const val MAX_DATA_PAYLOAD_BYTES = MAX_ATTRIBUTE_BYTES - FRAME_HEADER_BYTES

    const val MIN_MTU = 23
    const val DEFAULT_MTU = MIN_MTU
    const val REQUESTED_MTU = 517

    const val MAX_CHUNKS = 255
    const val MAX_LINKS = 4
    const val TRANSFER_TIMEOUT_MS = 10_000L
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val GATT_OPEN_TIMEOUT_MS = 10_000L
    const val GATT_OPERATION_TIMEOUT_MS = 10_000L
    const val MTU_TIMEOUT_MS = 5_000L
    const val INDICATION_TIMEOUT_MS = 10_000L
    const val HANDSHAKE_TIMEOUT_MS = 5_000L

    const val FRAME_HELLO = 0x01
    const val FRAME_HELLO_ACK = 0x02
    const val FRAME_DATA = 0x03
    const val FRAME_ERROR = 0x04
}
