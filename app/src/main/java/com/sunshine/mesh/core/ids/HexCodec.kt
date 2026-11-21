package com.sunshine.mesh.core.ids

internal object HexCodec {
    private const val DIGITS = "0123456789abcdef"

    fun isHexDigit(char: Char): Boolean = char.digitToIntOrNull(16) != null

    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append(DIGITS[value ushr 4])
            append(DIGITS[value and 0x0f])
        }
    }

    fun decode(value: String, expectedBytes: Int): ByteArray {
        require(value.length == expectedBytes * 2) {
            "hex value must be ${expectedBytes * 2} characters"
        }
        val result = ByteArray(expectedBytes)
        for (index in result.indices) {
            val high = value[index * 2].digitToIntOrNull(16)
                ?: throw IllegalArgumentException("hex value contains a non-hex character")
            val low = value[index * 2 + 1].digitToIntOrNull(16)
                ?: throw IllegalArgumentException("hex value contains a non-hex character")
            result[index] = ((high shl 4) or low).toByte()
        }
        return result
    }
}
