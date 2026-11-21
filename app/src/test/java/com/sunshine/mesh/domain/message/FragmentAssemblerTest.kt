package com.sunshine.mesh.domain.message

import com.sunshine.mesh.core.crypto.e2e.EnvelopeCodec
import com.sunshine.mesh.core.crypto.e2e.EnvelopeHeader
import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.domain.model.OpenedFragment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FragmentAssemblerTest {

    private var currentTime = 1_000L
    private val assembler = FragmentAssembler(
        now = { currentTime },
        ttlMillis = 100,
    )

    @Test
    fun singleFragmentCompletesImmediately() {
        val message = accept(fragment(0, 1, "привет"))

        assertEquals("привет", message.plaintext.decodeToString())
        assertEquals(MESSAGE_ID, message.messageId)
        assertEquals(SENDER_ID, message.senderId)
        assertEquals(RECIPIENT_ID, message.recipientId)
        message.close()
    }

    @Test
    fun outOfOrderFragmentsAreReassembled() {
        assertNull(assembler.accept(fragment(2, 3, "!")))
        assertNull(assembler.accept(fragment(0, 3, "при")))
        val message = accept(fragment(1, 3, "вет"))

        assertEquals("привет!", message.plaintext.decodeToString())
        message.close()
    }

    @Test
    fun missingPartKeepsAssemblyIncompleteUntilItArrives() {
        assertNull(assembler.accept(fragment(0, 3, "a")))
        assertNull(assembler.accept(fragment(2, 3, "c")))

        val message = accept(fragment(1, 3, "b"))
        assertEquals("abc", message.plaintext.decodeToString())
        message.close()
    }

    @Test
    fun assemblesMaximumNumberOfParts() {
        val body = ByteArray(EnvelopeCodec.MAX_PARTS) { (it % 251).toByte() }

        for (index in 0 until EnvelopeCodec.MAX_PARTS - 1) {
            assertNull(
                assembler.accept(
                    fragment(index, EnvelopeCodec.MAX_PARTS, byteArrayOf(body[index])),
                ),
            )
        }
        val message = accept(
            fragment(
                EnvelopeCodec.MAX_PARTS - 1,
                EnvelopeCodec.MAX_PARTS,
                byteArrayOf(body.last()),
            ),
        )

        assertArrayEquals(body, message.plaintext)
        message.close()
    }

    @Test
    fun identicalDuplicateDoesNotCompleteOrChangeAssembly() {
        assertNull(assembler.accept(fragment(0, 2, "one")))
        assertNull(assembler.accept(fragment(0, 2, "one")))
        assertNull(assembler.accept(fragment(0, 2, "one")))

        val message = accept(fragment(1, 2, "two"))
        assertEquals("onetwo", message.plaintext.decodeToString())
        message.close()
    }

    @Test
    fun conflictingDuplicateDiscardsAssembly() {
        assertNull(assembler.accept(fragment(0, 2, "first")))
        assertNull(assembler.accept(fragment(0, 2, "tampered")))
        assertNull(assembler.accept(fragment(1, 2, "tail")))

        val message = accept(fragment(0, 2, "first"))
        assertEquals("firsttail", message.plaintext.decodeToString())
        message.close()
    }

    @Test
    fun changedTotalPartsDiscardsAssembly() {
        assertNull(assembler.accept(fragment(0, 3, "a")))
        assertNull(assembler.accept(fragment(0, 2, "a")))

        assertNull(assembler.accept(fragment(1, 2, "b")))
        val message = accept(fragment(0, 2, "a"))
        assertEquals("ab", message.plaintext.decodeToString())
        message.close()
    }

    @Test
    fun changedRecipientDiscardsAssembly() {
        assertNull(assembler.accept(fragment(0, 2, "a")))
        assertNull(assembler.accept(fragment(1, 2, "b", recipientId = OTHER_RECIPIENT_ID)))

        assertNull(assembler.accept(fragment(0, 2, "a", recipientId = OTHER_RECIPIENT_ID)))
        val message = accept(fragment(1, 2, "b", recipientId = OTHER_RECIPIENT_ID))
        assertEquals("ab", message.plaintext.decodeToString())
        message.close()
    }

    @Test
    fun assembliesAreIsolatedBySenderAndMessageId() {
        assertNull(assembler.accept(fragment(0, 2, "a")))
        assertNull(assembler.accept(fragment(0, 2, "x", senderId = OTHER_SENDER_ID)))
        assertNull(assembler.accept(fragment(0, 2, "m", messageId = OTHER_MESSAGE_ID)))

        val first = accept(fragment(1, 2, "b"))
        val second = accept(fragment(1, 2, "y", senderId = OTHER_SENDER_ID))
        val third = accept(fragment(1, 2, "n", messageId = OTHER_MESSAGE_ID))

        assertEquals("ab", first.plaintext.decodeToString())
        assertEquals("xy", second.plaintext.decodeToString())
        assertEquals("mn", third.plaintext.decodeToString())
        first.close()
        second.close()
        third.close()
    }

    @Test
    fun expiredAssemblyIsDropped() {
        assertNull(assembler.accept(fragment(0, 2, "old")))
        currentTime += 101

        assertNull(assembler.accept(fragment(1, 2, "new")))
        val message = accept(fragment(0, 2, "old"))
        assertEquals("oldnew", message.plaintext.decodeToString())
        message.close()
    }

    @Test
    fun duplicateFragmentsDoNotExtendAssemblyLifetime() {
        assertNull(assembler.accept(fragment(0, 2, "old")))
        currentTime += 60
        assertNull(assembler.accept(fragment(0, 2, "old")))
        currentTime += 41

        assertNull(assembler.accept(fragment(1, 2, "new")))
        val message = accept(fragment(0, 2, "old"))
        assertEquals("oldnew", message.plaintext.decodeToString())
        message.close()
    }

    @Test
    fun oldestAssemblyIsEvictedWhenLimitIsReached() {
        val limited = FragmentAssembler(
            now = { currentTime },
            ttlMillis = 10_000,
            maxAssemblies = 1,
        )

        assertNull(limited.accept(fragment(0, 2, "old")))
        currentTime += 1
        assertNull(limited.accept(fragment(0, 2, "new", messageId = OTHER_MESSAGE_ID)))

        val message = accept(
            limited,
            fragment(1, 2, "new", messageId = OTHER_MESSAGE_ID),
        )
        assertEquals("newnew", message.plaintext.decodeToString())
        message.close()

        assertNull(limited.accept(fragment(1, 2, "old")))
    }

    @Test
    fun clearDropsEverythingAndWipesState() {
        assertNull(assembler.accept(fragment(0, 2, "one")))
        assembler.clear()

        assertNull(assembler.accept(fragment(1, 2, "two")))
        val message = accept(fragment(0, 2, "one"))
        assertEquals("onetwo", message.plaintext.decodeToString())
        message.close()
    }

    @Test
    fun rejectsFragmentLargerThanCiphertext() {
        val header = header(partIndex = 0, totalParts = 1)
        assertThrows(IllegalArgumentException::class.java) {
            OpenedFragment(
                header = header,
                plaintext = ByteArray(EnvelopeCodec.MAX_PLAINTEXT_BYTES + 1),
            )
        }
    }

    @Test
    fun toStringDoesNotPrintPlaintext() {
        val fragment = fragment(0, 1, "secret")
        val text = fragment.toString()

        assertFalse(text.contains("secret"))
        fragment.close()
        assertArrayEquals(ByteArray("secret".length), fragment.plaintext)
    }

    private fun accept(fragment: OpenedFragment): com.sunshine.mesh.domain.model.AssembledMessage =
        accept(assembler, fragment)

    private fun accept(
        target: FragmentAssembler,
        fragment: OpenedFragment,
    ): com.sunshine.mesh.domain.model.AssembledMessage {
        val message = requireNotNull(target.accept(fragment))
        fragment.close()
        return message
    }

    private fun fragment(
        partIndex: Int,
        totalParts: Int,
        body: String,
        messageId: MessageId = MESSAGE_ID,
        senderId: PeerId = SENDER_ID,
        recipientId: PeerId = RECIPIENT_ID,
    ): OpenedFragment = fragment(
        partIndex,
        totalParts,
        body.toByteArray(),
        messageId,
        senderId,
        recipientId,
    )

    private fun fragment(
        partIndex: Int,
        totalParts: Int,
        body: ByteArray,
        messageId: MessageId = MESSAGE_ID,
        senderId: PeerId = SENDER_ID,
        recipientId: PeerId = RECIPIENT_ID,
    ): OpenedFragment = OpenedFragment(
        header = header(partIndex, totalParts, messageId, senderId, recipientId),
        plaintext = body,
    )

    private fun header(
        partIndex: Int,
        totalParts: Int,
        messageId: MessageId = MESSAGE_ID,
        senderId: PeerId = SENDER_ID,
        recipientId: PeerId = RECIPIENT_ID,
    ): EnvelopeHeader = EnvelopeHeader.text(
        messageId = messageId,
        senderId = senderId,
        recipientId = recipientId,
        paddingLength = 0,
        partIndex = partIndex,
        totalParts = totalParts,
    )

    private companion object {
        val MESSAGE_ID = MessageId.parse("00112233445566778899aabbccddeeff")
        val OTHER_MESSAGE_ID = MessageId.parse("ffeeddccbbaa99887766554433221100")
        val SENDER_ID = PeerId.parse("0123456789abcdef")
        val OTHER_SENDER_ID = PeerId.parse("1111111111111111")
        val RECIPIENT_ID = PeerId.parse("fedcba9876543210")
        val OTHER_RECIPIENT_ID = PeerId.parse("2222222222222222")
    }
}
