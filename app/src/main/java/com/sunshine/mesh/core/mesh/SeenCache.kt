package com.sunshine.mesh.core.mesh

import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.net.MeshPolicy

/**
 * In-memory LRU of seen (msgId, partIndex) pairs. One message spans up to 255
 * packets, so deduplication must be per fragment: a msgId-only cache would
 * drop parts 2..N of every multi-part message.
 */
class SeenCache(private val capacity: Int = MeshPolicy.SEEN_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private data class Key(val msgId: String, val partIndex: Int)

    private val entries = object : LinkedHashMap<Key, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Unit>): Boolean =
            size > capacity
    }

    /** Returns true when the pair is new, false for a duplicate. Touches LRU order. */
    @Synchronized
    fun markSeen(msgId: MessageId, partIndex: Int): Boolean {
        val key = Key(msgId.value, partIndex)
        val existed = entries.put(key, Unit) != null
        return !existed
    }

    @get:Synchronized
    val size: Int
        get() = entries.size

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
