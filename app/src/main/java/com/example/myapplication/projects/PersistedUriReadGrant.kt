package com.example.myapplication.projects

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/**
 * Leases an app persisted read grant for an exact SAF URI. [newlyAcquired]
 * reports whether this call had to invoke takePersistableUriPermission; shared
 * cleanup ownership is tracked across all live handles by the registry.
 */
internal class PersistedUriReadGrant internal constructor(
    val uri: Uri,
    val newlyAcquired: Boolean,
    internal val leaseId: Long
) {
    /**
     * Release this operation's read grant only when [isStillNeeded] confirms
     * that no accepted or active owner still uses it. The predicate is
     * synchronous and must fail closed (return `true`) if its inventory cannot
     * be read. Run this method on an appropriate worker thread when the
     * predicate reads durable state, and from a NonCancellable cleanup section
     * when called from a cancellable coroutine.
     *
     * Returns true if the read permission is confirmed absent or was released.
     * Existing grants, grants still in use, and uncertain checks return false.
     */
    fun markAccepted() {
        PersistedUriReadGrantRegistry.markAccepted(this)
    }

    fun releaseIfUnused(
        resolver: ContentResolver,
        isStillNeeded: (Uri) -> Boolean
    ): Boolean = PersistedUriReadGrantRegistry.releaseIfUnused(resolver, this, isStillNeeded)
}

/** Persist the exact read grant and remember whether this operation acquired it. */
internal fun ContentResolver.takePersistableReadGrant(uri: Uri): PersistedUriReadGrant =
    PersistedUriReadGrantRegistry.take(this, uri)

/** Serializes in-process SAF read-grant acquisition and conditional release. */
private object PersistedUriReadGrantRegistry {
    private val lock = Any()
    private var nextLeaseId = 0L
    private val entries = mutableMapOf<Uri, Entry>()

    private data class Entry(
        var acquiredByRegistry: Boolean,
        var activeLeases: Int,
        var accepted: Boolean = false,
        var retainedForUncertainUse: Boolean = false,
        val activeLeaseIds: MutableSet<Long> = mutableSetOf()
    )

    fun take(resolver: ContentResolver, uri: Uri): PersistedUriReadGrant = synchronized(lock) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Persisted SAF grants require a content URI" }
        val prior = entries[uri]
        val existed = hasReadGrant(resolver, uri)
        if (!existed) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (failure: Exception) {
                // Providers can report an error after changing their permission
                // table. Undo only a grant that was absent before this attempt.
                if (hasReadGrant(resolver, uri)) {
                    try {
                        resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Exception) {
                        // Leave cleanup to the caller's later ownership check.
                    }
                }
                throw failure
            }

            if (!hasReadGrant(resolver, uri)) {
                try {
                    resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                    // Keep the original postcondition failure as the result.
                }
                throw SecurityException("The selected document read grant was not persisted")
            }
        }

        val entry = prior ?: Entry(
            acquiredByRegistry = !existed,
            activeLeases = 0
        ).also { entries[uri] = it }
        // If a permission disappeared while a prior lease remained active,
        // this attempt reacquired it. Treat the shared grant as ours to clean
        // only after every in-flight lease is settled.
        if (!existed) entry.acquiredByRegistry = true
        val id = ++nextLeaseId
        entry.activeLeases++
        entry.activeLeaseIds += id
        PersistedUriReadGrant(uri, newlyAcquired = !existed, leaseId = id)
    }

    fun markAccepted(grant: PersistedUriReadGrant) = synchronized(lock) {
        val entry = entries[grant.uri] ?: return@synchronized
        if (!entry.activeLeaseIds.remove(grant.leaseId)) return@synchronized
        entry.accepted = true
        entry.activeLeases--
        removeSettled(grant.uri, entry)
    }

    fun releaseIfUnused(
        resolver: ContentResolver,
        grant: PersistedUriReadGrant,
        isStillNeeded: (Uri) -> Boolean
    ): Boolean = synchronized(lock) {
        val entry = entries[grant.uri] ?: return@synchronized false
        if (!entry.activeLeaseIds.remove(grant.leaseId)) return@synchronized false
        entry.activeLeases--

        val currentlyPersisted = try { hasReadGrant(resolver, grant.uri) }
        catch (_: Exception) { entry.retainedForUncertainUse = true; removeSettled(grant.uri, entry); return@synchronized false }
        if (!currentlyPersisted) {
            removeSettled(grant.uri, entry)
            return@synchronized true
        }

        if (!entry.acquiredByRegistry || entry.accepted || entry.retainedForUncertainUse || entry.activeLeases > 0) {
            removeSettled(grant.uri, entry)
            return@synchronized false
        }

        val needed = try { isStillNeeded(grant.uri) }
        catch (_: Exception) { entry.retainedForUncertainUse = true; removeSettled(grant.uri, entry); return@synchronized false }
        if (needed) {
            entry.retainedForUncertainUse = true
            removeSettled(grant.uri, entry)
            return@synchronized false
        }

        try {
            resolver.releasePersistableUriPermission(grant.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            entry.retainedForUncertainUse = true
            removeSettled(grant.uri, entry)
            return@synchronized false
        }
        val released = try { !hasReadGrant(resolver, grant.uri) }
        catch (_: Exception) { false }
        if (!released) entry.retainedForUncertainUse = true
        removeSettled(grant.uri, entry)
        released
    }

    private fun removeSettled(uri: Uri, entry: Entry) {
        if (entry.activeLeases == 0) entries.remove(uri, entry)
    }

    private fun hasReadGrant(resolver: ContentResolver, uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
}
