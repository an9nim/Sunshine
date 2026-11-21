package com.sunshine.mesh.domain.message

import com.sunshine.mesh.core.crypto.e2e.EnvelopeCodec
import com.sunshine.mesh.core.crypto.e2e.EnvelopeHeader
import com.sunshine.mesh.core.ids.MessageId
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.domain.model.AssembledMessage
import com.sunshine.mesh.domain.model.OpenedFragment

/**
 * Reassembles decrypted fragments by (senderId, messageId). Fragments may
 * arrive out of order. Identical duplicates are ignored; any conflicting
 * duplicate or metadata change discards the whole assembly, because silently
 * choosing one version would break integrity. Completed assemblies are removed
 * immediately, while incomplete ones expire by TTL and are bounded by count
 * and buffered bytes.
 */
class FragmentAssembler(
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxAssemblies: Int = DEFAULT_MAX_ASSEMBLIES,
    private val maxBufferedBytes: Int = DEFAULT_MAX_BUFFERED_BYTES,
) {
    init {
        require(ttlMillis > 0) { "ttlMillis must be positive" }
        require(maxAssemblies > 0) { "maxAssemblies must be positive" }
        require(maxBufferedBytes >= MAX_MESSAGE_BYTES) {
            "maxBufferedBytes must hold one maximal message"
        }
    }

    private val pending = LinkedHashMap<AssemblyKey, PendingAssembly>()
    private var bufferedBytes = 0

    /**
     * Returns the message once every part is present, or null while waiting for
     * more parts and when this fragment was rejected. The assembler copies the
     * bytes; the caller still owns [OpenedFragment] and must close it.
     */
    @Synchronized
    fun accept(fragment: OpenedFragment): AssembledMessage? {
        val currentTime = now()
        evictExpired(currentTime)

        val header = fragment.header
        val key = AssemblyKey(senderId = header.senderId, messageId = header.messageId)
        val existing = pending[key]
        if (existing != null &&
            (existing.totalParts != header.totalParts ||
                existing.recipientId != header.recipientId)
        ) {
            remove(key)
            return null
        }

        val assembly = existing ?: run {
            if (!reserve(key, fragment.plaintext.size)) return null
            newAssembly(key, header)
        }
        val partIndex = header.partIndex
        val previous = assembly.parts[partIndex]
        if (previous != null) {
            if (!previous.contentEquals(fragment.plaintext)) remove(key)
            return null
        }

        if (existing != null && !reserve(key, fragment.plaintext.size)) return null
        val copy = fragment.plaintext.copyOf()
        assembly.parts[partIndex] = copy
        assembly.received += 1
        assembly.bufferedBytes += copy.size
        bufferedBytes += copy.size

        if (assembly.received != assembly.totalParts) return null
        return assemble(key, assembly)
    }

    /** Drops and wipes every incomplete assembly, for shutdown or lock. */
    @Synchronized
    fun clear() {
        pending.values.forEach(::wipe)
        pending.clear()
        bufferedBytes = 0
    }

    private fun newAssembly(key: AssemblyKey, header: EnvelopeHeader): PendingAssembly {
        if (pending.size >= maxAssemblies) evictOldest()
        return PendingAssembly(
            recipientId = header.recipientId,
            totalParts = header.totalParts,
            createdAt = now(),
        ).also { pending[key] = it }
    }

    private fun reserve(key: AssemblyKey, additionalBytes: Int): Boolean {
        while (bufferedBytes + additionalBytes > maxBufferedBytes) {
            val oldest = pending
                .filterKeys { it != key }
                .minByOrNull { it.value.createdAt }
                ?.key
                ?: return false
            remove(oldest)
        }
        return true
    }

    private fun assemble(key: AssemblyKey, assembly: PendingAssembly): AssembledMessage {
        val plaintext = ByteArray(assembly.bufferedBytes)
        var offset = 0
        for (part in assembly.parts) {
            val bytes = requireNotNull(part) { "completed assembly has an empty slot" }
            bytes.copyInto(plaintext, offset)
            offset += bytes.size
        }
        val message = AssembledMessage(
            messageId = key.messageId,
            senderId = key.senderId,
            recipientId = assembly.recipientId,
            plaintext = plaintext,
        )
        remove(key)
        return message
    }

    private fun evictExpired(currentTime: Long) {
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTime - entry.value.createdAt > ttlMillis) {
                bufferedBytes -= entry.value.bufferedBytes
                wipe(entry.value)
                iterator.remove()
            }
        }
    }

    private fun evictOldest() {
        val oldest = pending.minByOrNull { it.value.createdAt } ?: return
        remove(oldest.key)
    }

    private fun remove(key: AssemblyKey) {
        val assembly = pending.remove(key) ?: return
        bufferedBytes -= assembly.bufferedBytes
        wipe(assembly)
    }

    private fun wipe(assembly: PendingAssembly) {
        assembly.parts.forEach { it?.fill(0) }
        assembly.parts.fill(null)
        assembly.received = 0
        assembly.bufferedBytes = 0
    }

    private data class AssemblyKey(
        val senderId: PeerId,
        val messageId: MessageId,
    )

    private class PendingAssembly(
        val recipientId: PeerId,
        val totalParts: Int,
        val createdAt: Long,
    ) {
        val parts: Array<ByteArray?> = arrayOfNulls(totalParts)
        var received: Int = 0
        var bufferedBytes: Int = 0
    }

    private companion object {
        const val DEFAULT_TTL_MILLIS = 5 * 60_000L
        const val DEFAULT_MAX_ASSEMBLIES = 100
        const val DEFAULT_MAX_BUFFERED_BYTES = 4 * 1024 * 1024
        const val MAX_MESSAGE_BYTES =
            EnvelopeCodec.MAX_PARTS * EnvelopeCodec.MAX_PLAINTEXT_BYTES
    }
}
