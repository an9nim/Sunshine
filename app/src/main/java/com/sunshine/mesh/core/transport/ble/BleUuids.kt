package com.sunshine.mesh.core.transport.ble

import java.util.UUID

/**
 * Development 16-bit UUIDs. They fit a legacy advertisement together with the
 * 8-byte peer id. Replace them with assigned values before release.
 */
object BleUuids {
    val SERVICE: UUID = uuid16(0xFFF0)
    val RX: UUID = uuid16(0xFFF1)
    val TX: UUID = uuid16(0xFFF2)

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private fun uuid16(value: Int): UUID {
        require(value in 0..0xffff) { "16-bit UUID must be between 0 and 0xffff" }
        return UUID.fromString(
            "0000" + value.toString(16).padStart(4, '0') +
                "-0000-1000-8000-00805f9b34fb",
        )
    }
}
