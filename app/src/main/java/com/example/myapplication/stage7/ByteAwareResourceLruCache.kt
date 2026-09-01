package com.example.myapplication.stage7

import java.io.Closeable
import java.util.IdentityHashMap
import java.util.LinkedHashMap

/**
 * A document/session-qualified key for a resident resource cache.
 *
 * Keeping the namespace in the key makes it impossible for a page or photo
 * name from one document to accidentally address an entry from another
 * document.  The cache does not interpret either component.
 */
data class Stage7CacheKey<K : Any>(
    val namespace: String,
    val key: K
) {
    init {
        require(namespace.isNotBlank()) { "cache namespace must not be blank" }
    }
}

/** The result of a cache admission attempt.  Rejections never transfer ownership. */
enum class ByteAwareCachePutResult {
    ACCEPTED,
    REJECTED_CACHE_CLOSED,
    REJECTED_INVALID_BYTES,
    REJECTED_OVERSIZED,
    REJECTED_BUDGET;

    val accepted: Boolean
        get() = this == ACCEPTED
}

/** A read-only view of an active cache entry, useful for observable adapters. */
data class ByteAwareResourceCacheEntry<K : Any, V : Any>(
    val key: Stage7CacheKey<K>,
    val value: V,
    val bytes: Long
)

data class ByteAwareResourceCacheStats(
    val maxTotalBytes: Long,
    val totalBytes: Long,
    val activeEntryCount: Int,
    val retiredLeasedEntryCount: Int,
    val closed: Boolean
)

/**
 * A synchronized, access-order LRU for resources whose residency is measured
 * in bytes rather than entry count.
 *
 * A cache entry may be retired while a consumer lease is open.  Retired
 * leased entries remain in [totalBytes] until the lease closes, so admission
 * can never exceed [maxTotalBytes].  Resource records use identity semantics;
 * the same object stored under multiple keys is released exactly once after
 * its last entry and lease disappear.
 */
class ByteAwareResourceLruCache<K : Any, V : Any>(
    val maxTotalBytes: Long,
    private val releaseResource: (V) -> Unit,
    private val onChanged: (() -> Unit)? = null
) : Closeable {
    init {
        require(maxTotalBytes > 0L) { "maxTotalBytes must be positive" }
    }

    private val lock = Any()
    private val activeEntries = LinkedHashMap<Stage7CacheKey<K>, Entry<K, V>>(16, 0.75f, true)
    private val retiredEntries = LinkedHashSet<Entry<K, V>>()
    private val resources = IdentityHashMap<V, ResourceRecord<V>>()
    private var totalResidentBytes = 0L
    private var closed = false

    /**
     * Admits [value] only when it fits after deterministic unleased LRU
     * eviction.  [transferOwnership] is invoked only after admission has been
     * established and before the cache mutates its live entry set.  The
     * callback must transfer responsibility for [value] to this cache; if the
     * callback throws, the cache remains unchanged and the caller retains the
     * responsibility to release its producer owner.
     */
    fun putOwned(
        key: Stage7CacheKey<K>,
        value: V,
        bytes: Long,
        transferOwnership: () -> Unit = {}
    ): ByteAwareCachePutResult = synchronized(lock) {
        if (closed) return@synchronized ByteAwareCachePutResult.REJECTED_CACHE_CLOSED
        if (bytes <= 0L) return@synchronized ByteAwareCachePutResult.REJECTED_INVALID_BYTES
        if (bytes > maxTotalBytes) return@synchronized ByteAwareCachePutResult.REJECTED_OVERSIZED

        val existing = activeEntries[key]
        // Re-publishing the same object under the same key does not create a
        // second entry reference.  It still requires an ownership transfer for
        // the producer owner that supplied this publication.
        if (existing != null && existing.value === value && existing.bytes == bytes) {
            transferOwnership()
            activeEntries[key] // Touch the entry for access-order LRU.
            return@synchronized ByteAwareCachePutResult.ACCEPTED
        }

        val removals = ArrayList<Entry<K, V>>()
        var projectedBytes = totalResidentBytes
        if (existing != null) {
            removals += existing
            if (existing.leaseCount == 0) projectedBytes -= existing.bytes
        }

        if (exceedsBudget(projectedBytes, bytes)) {
            // LinkedHashMap iteration is oldest-to-newest because it is an
            // access-order map.  Leased entries are deliberately skipped: they
            // remain resident until their consumer releases them.
            for (candidate in activeEntries.values.toList()) {
                if (candidate === existing || candidate.leaseCount != 0 || candidate in removals) {
                    continue
                }
                removals += candidate
                projectedBytes -= candidate.bytes
                if (!exceedsBudget(projectedBytes, bytes)) break
            }
        }
        if (exceedsBudget(projectedBytes, bytes)) {
            return@synchronized ByteAwareCachePutResult.REJECTED_BUDGET
        }

        // The transfer is intentionally inside the same monitor as the
        // admission decision.  A rejected operation never invokes it.
        transferOwnership()

        // Register the new reference before retiring old entries.  If an alias
        // is being replaced/evicted, this prevents its underlying resource
        // from being released between the two entry mutations.
        val record = resources[value] ?: ResourceRecord(value).also {
            resources[value] = it
        }
        record.entryCount++
        val replacement = Entry(key, value, bytes, record)

        // Unleased removals are released now; leased removals become retired
        // and keep their bytes reserved until the lease closes.
        removals.forEach { oldEntry ->
            if (activeEntries.remove(oldEntry.key) === oldEntry) {
                retire(oldEntry)
            }
        }
        activeEntries[key] = replacement
        totalResidentBytes += bytes
        notifyChanged()
        ByteAwareCachePutResult.ACCEPTED
    }

    /** Returns an entry and marks it recently used without taking a lease. */
    fun get(key: Stage7CacheKey<K>): V? = synchronized(lock) {
        activeEntries[key]?.value
    }

    fun contains(key: Stage7CacheKey<K>): Boolean = synchronized(lock) {
        activeEntries.containsKey(key)
    }

    /**
     * Acquires a display/consumer lease.  The lease's value remains valid even
     * if this entry is subsequently evicted or replaced.
     */
    fun acquire(key: Stage7CacheKey<K>): ByteAwareResourceLease<V>? = synchronized(lock) {
        val entry = activeEntries[key] ?: return@synchronized null
        entry.leaseCount++
        entry.record.leaseCount++
        ByteAwareResourceLease(entry.value) {
            releaseLease(entry)
        }
    }

    fun remove(key: Stage7CacheKey<K>): Boolean = synchronized(lock) {
        val entry = activeEntries.remove(key) ?: return@synchronized false
        retire(entry)
        notifyChanged()
        true
    }

    /** Clears only active entries belonging to [namespace]. */
    fun clearNamespace(namespace: String) = synchronized(lock) {
        val entries = activeEntries.values.filter { it.key.namespace == namespace }
        if (entries.isEmpty()) return@synchronized
        entries.forEach { entry ->
            if (activeEntries.remove(entry.key) === entry) retire(entry)
        }
        notifyChanged()
    }

    fun clear() = synchronized(lock) {
        if (activeEntries.isEmpty()) return@synchronized
        val entries = activeEntries.values.toList()
        activeEntries.clear()
        entries.forEach(::retire)
        notifyChanged()
    }

    /** Active entries are returned in current LRU order (oldest first). */
    fun activeEntries(): List<ByteAwareResourceCacheEntry<K, V>> = synchronized(lock) {
        activeEntries.values.map { entry ->
            ByteAwareResourceCacheEntry(entry.key, entry.value, entry.bytes)
        }
    }

    fun stats(): ByteAwareResourceCacheStats = synchronized(lock) {
        ByteAwareResourceCacheStats(
            maxTotalBytes = maxTotalBytes,
            totalBytes = totalResidentBytes,
            activeEntryCount = activeEntries.size,
            retiredLeasedEntryCount = retiredEntries.size,
            closed = closed
        )
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        val entries = activeEntries.values.toList()
        activeEntries.clear()
        entries.forEach(::retire)
        notifyChanged()
    }

    private fun exceedsBudget(currentBytes: Long, additionalBytes: Long): Boolean =
        currentBytes > maxTotalBytes - additionalBytes

    private fun retire(entry: Entry<K, V>) {
        if (entry.retired || entry.finalized) return
        entry.retired = true
        if (entry.leaseCount == 0) {
            finalizeEntry(entry)
        } else {
            retiredEntries += entry
        }
    }

    private fun releaseLease(entry: Entry<K, V>) = synchronized(lock) {
        check(entry.leaseCount > 0) { "resource lease was released more than once" }
        check(entry.record.leaseCount > 0) { "resource lease record is inconsistent" }
        entry.leaseCount--
        entry.record.leaseCount--
        if (entry.retired && entry.leaseCount == 0) finalizeEntry(entry)
    }

    private fun finalizeEntry(entry: Entry<K, V>) {
        if (entry.finalized) return
        entry.finalized = true
        retiredEntries.remove(entry)
        totalResidentBytes -= entry.bytes
        entry.record.entryCount--
        if (entry.record.entryCount == 0 && entry.record.leaseCount == 0 && !entry.record.released) {
            entry.record.released = true
            resources.remove(entry.record.value)
            // Resource cleanup must not leave cache bookkeeping partially
            // applied.  Android Bitmap.recycle() is non-throwing in normal
            // operation; a defensive catch keeps close/eviction idempotent for
            // any caller-supplied cleanup implementation as well.
            runCatching { releaseResource(entry.record.value) }
        }
    }

    private fun notifyChanged() {
        onChanged?.invoke()
    }

    private class ResourceRecord<V : Any>(val value: V) {
        var entryCount: Int = 0
        var leaseCount: Int = 0
        var released: Boolean = false
    }

    private class Entry<K : Any, V : Any>(
        val key: Stage7CacheKey<K>,
        val value: V,
        val bytes: Long,
        val record: ResourceRecord<V>
    ) {
        var leaseCount: Int = 0
        var retired: Boolean = false
        var finalized: Boolean = false
    }
}

/** An idempotent lease which keeps a retired resource alive for its consumer. */
class ByteAwareResourceLease<T : Any> internal constructor(
    val value: T,
    private val onClose: () -> Unit
) : Closeable {
    private val lock = Any()
    private var closed = false

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (shouldClose) onClose()
    }
}
