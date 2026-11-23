package com.sunshine.mesh.core.mesh

import com.sunshine.mesh.core.ids.MessageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeenCacheTest {

    private val msgId = MessageId.parse("00112233445566778899aabbccddeeff")
    private val otherMsgId = MessageId.parse("ffeeddccbbaa99887766554433221100")

    @Test
    fun firstSeenIsNewAndSecondIsDuplicate() {
        val cache = SeenCache()

        assertTrue(cache.markSeen(msgId, 0))
        assertFalse(cache.markSeen(msgId, 0))
    }

    @Test
    fun partsOfTheSameMessageAreIndependent() {
        val cache = SeenCache()

        assertTrue(cache.markSeen(msgId, 0))
        assertTrue(cache.markSeen(msgId, 1))
        assertTrue(cache.markSeen(msgId, 254))
        assertFalse(cache.markSeen(msgId, 1))
    }

    @Test
    fun differentMessagesAreIndependent() {
        val cache = SeenCache()

        assertTrue(cache.markSeen(msgId, 0))
        assertTrue(cache.markSeen(otherMsgId, 0))
    }

    @Test
    fun lruEvictsTheLeastRecentlyUsedEntry() {
        val cache = SeenCache(capacity = 2)
        cache.markSeen(msgId, 0)
        cache.markSeen(msgId, 1)

        cache.markSeen(msgId, 0)   // touch: part 1 becomes the eldest
        cache.markSeen(msgId, 2)

        assertEquals(2, cache.size)
        assertFalse(cache.markSeen(msgId, 0))  // still present
        assertTrue(cache.markSeen(msgId, 1))   // was evicted, new again
    }

    @Test
    fun clearDropsEverything() {
        val cache = SeenCache()
        cache.markSeen(msgId, 0)

        cache.clear()

        assertEquals(0, cache.size)
        assertTrue(cache.markSeen(msgId, 0))
    }
}
