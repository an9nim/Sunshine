package com.sunshine.mesh.core.crypto.e2e

import com.sunshine.mesh.core.ids.MessageId
import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class E2ESessionRejectionTest {

    @Test
    fun wrongRecipientIsRejected() = withFixture { fixture ->
        val packet = fixture.aliceToBob.sealText(MESSAGE_ID, "секрет").single()

        assertCode(E2EException.Code.WRONG_RECIPIENT) {
            fixture.carolToAlice.openFragment(packet)
        }
    }

    @Test
    fun wrongSenderIsRejected() = withFixture { fixture ->
        val packet = fixture.aliceToBob.sealText(MESSAGE_ID, "секрет").single()

        assertCode(E2EException.Code.WRONG_SENDER) {
            fixture.bobToCarol.openFragment(packet)
        }
    }

    @Test
    fun tamperedTagFailsAuthentication() = withFixture { fixture ->
        val packet = fixture.aliceToBob.sealText(MESSAGE_ID, "секрет").single()
        val tampered = packet.copyOf()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()

        assertCode(E2EException.Code.AUTHENTICATION_FAILED) {
            fixture.bobToAlice.openFragment(tampered)
        }
    }

    @Test
    fun tamperedCiphertextFailsAuthentication() = withFixture { fixture ->
        val packet = fixture.aliceToBob.sealText(MESSAGE_ID, "секрет").single()
        val tampered = packet.copyOf()
        val offset = EnvelopeCodec.AAD_BYTES + EnvelopeCodec.NONCE_BYTES
        tampered[offset] = (tampered[offset].toInt() xor 1).toByte()

        assertCode(E2EException.Code.AUTHENTICATION_FAILED) {
            fixture.bobToAlice.openFragment(tampered)
        }
    }

    @Test
    fun unknownTypeIsRejectedByDecodeBeforeDecryption() = withFixture { fixture ->
        val packet = fixture.aliceToBob.sealText(MESSAGE_ID, "секрет").single()
        val tampered = packet.copyOf()
        tampered[1] = 0x7f

        assertCode(E2EException.Code.UNKNOWN_TYPE) {
            fixture.bobToAlice.openFragment(tampered)
        }
    }

    @Test
    fun oversizedAndTruncatedPacketsAreRejected() = withFixture { fixture ->
        assertCode(E2EException.Code.ENVELOPE_TOO_LARGE) {
            fixture.bobToAlice.openFragment(ByteArray(EnvelopeCodec.ENVELOPE_BYTES + 1))
        }
        assertCode(E2EException.Code.MALFORMED_ENVELOPE) {
            fixture.bobToAlice.openFragment(ByteArray(EnvelopeCodec.ENVELOPE_BYTES - 1))
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private fun assertCode(code: E2EException.Code, block: () -> Unit) {
        val exception = assertThrows(E2EException::class.java) { block() }
        assertEquals(code, exception.code)
    }

    private class Fixture : Closeable {
        private val alice = E2ETestSupport.identity(seed = 1)
        private val bob = E2ETestSupport.identity(seed = 100)
        private val carol = E2ETestSupport.identity(seed = 200)

        val aliceToBob = E2ETestSupport.session(alice, bob)
        val bobToAlice = E2ETestSupport.session(bob, alice)
        val bobToCarol = E2ETestSupport.session(bob, carol)
        val carolToAlice = E2ETestSupport.session(carol, alice)

        override fun close() {
            aliceToBob.close()
            bobToAlice.close()
            bobToCarol.close()
            carolToAlice.close()
            alice.close()
            bob.close()
            carol.close()
        }
    }

    private companion object {
        val MESSAGE_ID = MessageId.parse("00112233445566778899aabbccddeeff")
    }
}
