package com.sunshine.mesh.core.transport.ble

/**
 * CCCD values for the TX characteristic. The server supports indications only:
 * 0x0002 enables them, 0x0000 disables them. A notification request (0x0001)
 * is rejected because TX has PROPERTY_INDICATE, not PROPERTY_NOTIFY.
 */
object BleCccd {

    const val VALUE_BYTES = 2

    private const val DISABLED = 0x00
    private const val INDICATION = 0x02

    fun isIndicationEnabled(value: ByteArray?): Boolean? {
        if (value == null || value.size != VALUE_BYTES) return null
        if (value[1].toInt() != 0) return null
        return when (value[0].toInt() and 0xff) {
            DISABLED -> false
            INDICATION -> true
            else -> null
        }
    }

    fun indicationValue(enabled: Boolean): ByteArray =
        byteArrayOf(
            (if (enabled) INDICATION else DISABLED).toByte(),
            0.toByte(),
        )
}
