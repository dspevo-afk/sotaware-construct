package com.example.myapplication

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.example.myapplication.stage7.BitmapBudgetPolicy
import com.example.myapplication.stage7.ByteAwareCachePutResult
import com.example.myapplication.stage7.ByteAwareResourceCacheEntry
import com.example.myapplication.stage7.ByteAwareResourceLease
import com.example.myapplication.stage7.ByteAwareResourceLruCache
import com.example.myapplication.stage7.Stage7CacheKey
import com.example.myapplication.stage7.Stage7OwnedResource
import java.io.Closeable

/**
 * Compose-facing adapter for resident Bitmaps.  The pure byte-aware cache
 * remains Android-free; this adapter supplies actual platform allocation
 * bytes and mirrors active entries into a SnapshotStateMap so eviction and
 * replacement invalidate the UI.
 */
class Stage7BitmapCache(
    maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES
) : Closeable {
    companion object {
        /** Four single-bitmap policy budgets provide a bounded resident pool. */
        const val DEFAULT_MAX_TOTAL_BYTES: Long = BitmapBudgetPolicy.MAX_BITMAP_BYTES * 4L
    }

    val maxBytes: Long = maxTotalBytes
    val entries: SnapshotStateMap<Stage7CacheKey<String>, Bitmap> = mutableStateMapOf()

    private val cache = ByteAwareResourceLruCache<String, Bitmap>(
        maxTotalBytes = maxTotalBytes,
        releaseResource = ::releaseBitmap,
        onChanged = ::synchronizeEntries
    )

    fun get(key: Stage7CacheKey<String>): Bitmap? = cache.get(key)

    fun contains(key: Stage7CacheKey<String>): Boolean = entries.containsKey(key)

    fun acquire(key: Stage7CacheKey<String>): ByteAwareResourceLease<Bitmap>? =
        cache.acquire(key)

    /**
     * Transfers [owner] only when the actual platform allocation fits the
     * resident budget.  A rejection leaves [owner] with the producer so the
     * worker boundary can close it exactly once.
     */
    fun putOwned(
        key: Stage7CacheKey<String>,
        owner: Stage7OwnedResource<Bitmap>
    ): ByteAwareCachePutResult {
        val bitmap = owner.value
        if (bitmap.isRecycled) return ByteAwareCachePutResult.REJECTED_INVALID_BYTES
        val actualBytes = runCatching { actualBitmapAllocationBytes(bitmap) }.getOrDefault(0L)
        if (actualBytes > BitmapBudgetPolicy.MAX_BITMAP_BYTES) {
            return ByteAwareCachePutResult.REJECTED_OVERSIZED
        }
        return cache.putOwned(
            key = key,
            value = bitmap,
            bytes = actualBytes,
            transferOwnership = { owner.transferOwnership() }
        )
    }

    /** Compatibility helper for callers which already transferred raw Bitmap ownership. */
    fun put(
        key: Stage7CacheKey<String>,
        bitmap: Bitmap
    ): ByteAwareCachePutResult {
        if (bitmap.isRecycled) {
            releaseBitmap(bitmap)
            return ByteAwareCachePutResult.REJECTED_INVALID_BYTES
        }
        val actualBytes = runCatching { actualBitmapAllocationBytes(bitmap) }.getOrDefault(0L)
        if (actualBytes > BitmapBudgetPolicy.MAX_BITMAP_BYTES) {
            releaseBitmap(bitmap)
            return ByteAwareCachePutResult.REJECTED_OVERSIZED
        }
        val result = cache.putOwned(key, bitmap, actualBytes)
        if (!result.accepted) releaseBitmap(bitmap)
        return result
    }

    fun clearNamespace(namespace: String) = cache.clearNamespace(namespace)

    fun clear() = cache.clear()

    fun stats() = cache.stats()

    override fun close() = cache.close()

    private fun synchronizeEntries() {
        val active = cache.activeEntries()
        entries.clear()
        active.forEach { entry: ByteAwareResourceCacheEntry<String, Bitmap> ->
            entries[entry.key] = entry.value
        }
    }

    private fun releaseBitmap(bitmap: Bitmap) {
        runCatching {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
