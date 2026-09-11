package com.example.myapplication.stage9b

import com.example.myapplication.stage5.PhotoDocumentCriticalSections
import java.io.IOException
import java.nio.file.Path
import java.util.IdentityHashMap

/**
 * Owns failed release tickets independently of caller, store and Activity lifetimes.
 * Entries resume the exact pool/registry lease, including committed-publication
 * and anchor-cleanup phases. No file bytes are copied and no background work is
 * started. A root's next admission or collection retries each ticket once.
 */
internal object DeferredPhotoReleaseOwner {
    private val lock = Any()
    private val pending = mutableMapOf<String, IdentityHashMap<PhotoAssetLease, () -> Unit>>()

    fun retain(root: Path, lease: PhotoAssetLease, retry: () -> Unit) = synchronized(lock) {
        pending.getOrPut(PhotoDocumentCriticalSections.rootKey(root)) { IdentityHashMap() }[lease] = retry
    }

    fun forget(root: Path, lease: PhotoAssetLease) = synchronized(lock) {
        val key = PhotoDocumentCriticalSections.rootKey(root)
        pending[key]?.let { tickets ->
            tickets.remove(lease)
            if (tickets.isEmpty()) pending.remove(key)
        }
        Unit
    }

    /** Must be called before acquiring any pool-instance or pool-root lock. */
    fun recover(root: Path) {
        val retries = synchronized(lock) {
            pending[PhotoDocumentCriticalSections.rootKey(root)]?.values?.toList().orEmpty()
        }
        var failure: Throwable? = null
        // Never hold the registry lock while entering another pool or doing I/O.
        // Tickets remove themselves only after both ownership and cleanup finish.
        retries.forEach { retry ->
            try {
                retry()
            } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error
                else if (first !== error && first.suppressed.none { it === error }) first.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    /**
     * Recheck under the shared root lock: a release may have failed after the
     * recovery snapshot was taken. Do not publish over a newly pending receipt.
     * Admission then fails explicitly and the next boundary can retry it.
     */
    fun requireDrained(root: Path) = synchronized(lock) {
        if (!pending[PhotoDocumentCriticalSections.rootKey(root)].isNullOrEmpty()) {
            throw IOException("immutable photo pool has pending release recovery")
        }
    }
}
