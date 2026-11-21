package com.sunshine.mesh.domain.validation

enum class NicknameError {
    EMPTY,
    TOO_LONG,
    CONTROL_CHARS,
}

sealed interface NicknameResult {
    data class Ok(val value: String) : NicknameResult
    data class Invalid(val reason: NicknameError) : NicknameResult
}

object Nickname {
    const val MAX_LENGTH = 32

    private val whitespace = Regex("\\s+")

    fun normalize(raw: String): NicknameResult {
        // Check before trim(): String.trim() removes control characters at the edges.
        if (raw.any { it.isISOControl() && !it.isWhitespace() }) {
            return NicknameResult.Invalid(NicknameError.CONTROL_CHARS)
        }
        val collapsed = raw.trim().replace(whitespace, " ")
        if (collapsed.isEmpty()) return NicknameResult.Invalid(NicknameError.EMPTY)
        if (collapsed.codePointCount(0, collapsed.length) > MAX_LENGTH) {
            return NicknameResult.Invalid(NicknameError.TOO_LONG)
        }
        return NicknameResult.Ok(collapsed)
    }
}
