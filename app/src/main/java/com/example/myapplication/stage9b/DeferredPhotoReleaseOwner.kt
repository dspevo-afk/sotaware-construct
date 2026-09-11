package com.example.myapplication.stage9b

import com.example.myapplication.stage5.PhotoDocumentCriticalSections
import java.io.IOException
import java.nio.file.Path
import java.util.LinkedHashMap

/**
 * Owns ordered release tickets independently of caller, store and Activity lifetimes.
 * Entries resume the exact pool/registry lease, including committed-publication
 * and anchor-cleanup phases. No file bytes are copied and no background work is
 * started. Release, admission and collection retry the relevant tickets once.
 */
internal object DeferredPhotoReleaseOwner {
    private val lock = Any()
    // PhotoAssetLease is a final identity-equality class. Re-registering a failed
    // ticket must preserve its place ahead of releases queued behind its receipt.
    private val pending = mutableMapOf<String, LinkedHashMap<PhotoAssetLease, () -> Unit>>()

    fun retain(root: Path, lease: PhotoAssetLease, retry: () -> Unit) = synchronized(lock) {
        pending.getOrPut(PhotoDocumentCriticalSections.rootKey(root)) { LinkedHashMap() }[lease] = retry
    }

    fun forget(root: Path, lease: PhotoAssetLease) = synchronized(lock) {
        val key = PhotoDocumentCriticalSections.rootKey(root)
        pending[key]?.let { tickets ->
            tickets.remove(lease)
            if (tickets.isEmpty()) pending.remove(key)
        }
        Unit
    }

    /**
     * Must be called before acquiring any pool-state or pool-root lock.
     * A public release only waits through its own ticket, not later releases.
     */
    fun recover(root: Path, through: PhotoAssetLease? = null) {
        val retries = synchronized(lock) {
            val tickets = pending[PhotoDocumentCriticalSections.rootKey(root)]?.entries?.toList().orEmpty()
            val count = if (through == null) tickets.size else tickets.indexOfFirst { it.key === through } + 1
            tickets.take(count).map { it.value }
        }
        // Never hold the registry lock while entering another pool or doing I/O.
        // Stop at the first failure: later tickets cannot publish past its receipt.
        // Each callback removes itself only after ownership and cleanup finish.
        retries.forEach { retry -> retry() }
    }

    /** Recheck under the shared root lock before a release touches the manifest. */
    fun requireTurn(root: Path, lease: PhotoAssetLease) = synchronized(lock) {
        val first = pending[PhotoDocumentCriticalSections.rootKey(root)]?.keys?.firstOrNull()
        if (first !== lease) {
            throw IOException("immutable photo release is waiting for earlier recovery")
        }
    }

    /**
     * Recheck under the shared root lock: a release may have been queued after
     * the recovery snapshot was taken. Admission must not publish over it.
     */
    fun requireDrained(root: Path) = synchronized(lock) {
        if (!pending[PhotoDocumentCriticalSections.rootKey(root)].isNullOrEmpty()) {
            throw IOException("immutable photo pool has pending release recovery")
        }
    }
}
