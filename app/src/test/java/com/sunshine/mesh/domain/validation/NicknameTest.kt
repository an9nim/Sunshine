package com.sunshine.mesh.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NicknameTest {

    @Test
    fun normalizesOuterAndRepeatedWhitespace() {
        val result = Nickname.normalize("  Иван   Петров  ")

        assertEquals(NicknameResult.Ok("Иван Петров"), result)
    }

    @Test
    fun rejectsBlankNickname() {
        val result = Nickname.normalize("   \n\t ")

        assertEquals(NicknameResult.Invalid(NicknameError.EMPTY), result)
    }

    @Test
    fun acceptsExactlyMaxCodePoints() {
        val nickname = "😀".repeat(Nickname.MAX_LENGTH)

        assertEquals(NicknameResult.Ok(nickname), Nickname.normalize(nickname))
    }

    @Test
    fun rejectsNicknameAboveMaxCodePoints() {
        val result = Nickname.normalize("😀".repeat(Nickname.MAX_LENGTH + 1))

        assertEquals(NicknameResult.Invalid(NicknameError.TOO_LONG), result)
    }

    @Test
    fun rejectsControlCharacters() {
        val result = Nickname.normalize("Иван\u0007")

        assertEquals(NicknameResult.Invalid(NicknameError.CONTROL_CHARS), result)
    }

    @Test
    fun keepsSingleCharacterNickname() {
        val result = Nickname.normalize("Я")

        assertTrue(result is NicknameResult.Ok)
        assertEquals("Я", (result as NicknameResult.Ok).value)
    }
}
