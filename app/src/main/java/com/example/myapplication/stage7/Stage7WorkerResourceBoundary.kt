package com.example.myapplication.stage7

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Stage 7's narrow boundary for expensive PDF/image work.
 *
 * The coordinator remains the owner of document/session transitions. This
 * boundary only makes the execution and publication sides explicit: blocking
 * work runs on [workerDispatcher], while UI state publication runs on
 * [mainDispatcher]. The dispatchers are injectable so JVM tests can exercise
 * the production ordering without Android threads.
 */
class Stage7WorkerResourceBoundary(
    val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    /** Shared by every OCR/search/render route using this worker boundary. */
    val ocrSessionRegistry: OcrSessionRegistry = OcrSessionRegistry(),
    /** Shared with the coordinator's token invalidation fence. */
    val publicationFence: Stage7PublicationFence = Stage7PublicationFence.global,
    /** JVM-only seam for cancellation at the worker-to-owner handoff. */
    internal val beforeWorkerHandoff: ((kotlinx.coroutines.Job?) -> Unit)? = null
) {
    suspend fun <T> withWorker(block: suspend () -> T): T =
        withContext(workerDispatcher) {
            currentCoroutineContext().ensureActive()
            block().also {
                // This runs after the worker body has released its internal
                // operation lease but before withContext returns to the
                // caller.  Production leaves it null; tests use it to make
                // that cancellation boundary deterministic.
                beforeWorkerHandoff?.invoke(currentCoroutineContext()[kotlinx.coroutines.Job])
            }
        }

    suspend fun <T> withMain(block: suspend () -> T): T =
        withContext(mainDispatcher) {
            currentCoroutineContext().ensureActive()
            block()
        }

    /**
     * Runs a worker load and publishes it only after two checks:
     * [acceptsBeforeMain] is safe to run from the worker, and [acceptsOnMain]
     * is the final session/page/query check immediately before UI publication.
     *
     * A loaded resource which is rejected, canceled after loading, or cannot
     * be published is released on the worker. The rejection callback therefore
     * must release the complete resource ownership represented by [T].
     */
    suspend fun <T : Any> computeAndPublish(
        compute: suspend () -> T?,
        acceptsBeforeMain: () -> Boolean,
        acceptsOnMain: () -> Boolean = acceptsBeforeMain,
        publish: (T) -> Unit,
        reject: (T) -> Unit
    ): Boolean {
        val slot = PublicationSlot<T>()
        var primaryFailure: Throwable? = null
        try {
            // Record the value inside the worker context before its result is
            // handed back through a cancellable withContext boundary. If
            // cancellation wins that handoff, the slot still retains the
            // resource for exactly-once rejection below.
            withWorker {
                compute()?.also(slot::record)
            }
            val value = slot.loadedValue() ?: return false
            if (!acceptsBeforeMain()) return false

            val accepted = withMain {
                if (!acceptsOnMain()) {
                    false
                } else {
                    publish(value)
                    // publish is deliberately non-suspending. A cancellation
                    // flag set during/after publication can therefore only be
                    // observed after this atomic commit transfer, preventing
                    // cleanup from rejecting an already-published resource.
                    check(slot.commit()) { "published resource was not loaded" }
                    true
                }
            }
            return accepted
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            val value = slot.rejectIfLoaded()
            if (value != null) {
                try {
                    // Cleanup must still run if cancellation arrived between
                    // the worker result and the final Main publication check.
                    withContext(NonCancellable + workerDispatcher) {
                        reject(value)
                    }
                } catch (cleanupFailure: Throwable) {
                    if (primaryFailure != null) {
                        primaryFailure?.addSuppressed(cleanupFailure)
                    } else {
                        throw cleanupFailure
                    }
                }
            }
        }
    }

    /**
     * Small synchronized state machine for the worker-to-owner handoff.
     * EMPTY -> LOADED -> COMMITTED or REJECTED. The value is only released
     * by the caller which wins the LOADED transition, so cancellation cannot
     * race a successful publication into a second cleanup.
     */
    private class PublicationSlot<T : Any> {
        private enum class State { EMPTY, LOADED, COMMITTED, REJECTED }

        private val lock = Any()
        private var state = State.EMPTY
        private var value: T? = null

        fun record(resource: T) {
            synchronized(lock) {
                check(state == State.EMPTY) { "resource was recorded more than once" }
                value = resource
                state = State.LOADED
            }
        }

        fun loadedValue(): T? = synchronized(lock) {
            if (state == State.LOADED) value else null
        }

        fun commit(): Boolean = synchronized(lock) {
            if (state == State.LOADED) {
                state = State.COMMITTED
                true
            } else {
                state == State.COMMITTED
            }
        }

        fun rejectIfLoaded(): T? = synchronized(lock) {
            if (state != State.LOADED) return@synchronized null
            state = State.REJECTED
            val rejected = value
            value = null
            rejected
        }
    }
}

/**
 * Linearizes a cancellation-sensitive cache insertion.
 *
 * The caller supplies the active check so this remains usable by worker-side
 * caches without coupling the Stage 7 seam to a particular coroutine scope.
 * Existing entries are never replaced. The mutation itself is the commit
 * point: cancellation observed before it rejects the insertion, while a
 * later transaction rollback removes only the exact value this operation
 * installed.
 */
internal class Stage7CacheCommitter<K : Any, V : Any>(
    private val store: MutableMap<K, V>,
    private val lock: Any = store,
    private val beforeCommit: (() -> Unit)? = null
) {
    fun commitIfActive(
        key: K,
        value: V,
        ensureActive: () -> Unit
    ): Stage7CacheCommit<K, V>? = synchronized(lock) {
        ensureActive()
        if (store.containsKey(key)) return@synchronized null

        // This hook is intentionally synchronous. Tests use it to request
        // cancellation between the initial check and the final check.
        beforeCommit?.invoke()
        ensureActive()
        store[key] = value
        Stage7CacheCommit(store, lock, key, value)
    }
}

internal class Stage7CacheCommit<K : Any, V : Any> internal constructor(
    private val store: MutableMap<K, V>,
    private val lock: Any,
    private val key: K,
    private val value: V
) {
    /** Remove only this operation's still-current value. */
    fun rollbackIfOwned() {
        synchronized(lock) {
            if (store[key] === value) store.remove(key)
        }
    }
}

/**
 * Groups page commits and the full-document marker for one worker operation.
 * Cancellation rolls back only values still owned by this transaction. Once
 * the caller reaches the full-document marker, [commit] seals the successful
 * linearization point and prevents a later cancellation from removing valid
 * cache state observed by another operation.
 */
internal class Stage7CacheCommitTransaction {
    private val lock = Any()
    private val commits = mutableListOf<Stage7CacheCommit<*, *>>()
    private var sealed = false

    fun record(commit: Stage7CacheCommit<*, *>?) {
        if (commit == null) return
        val rollbackImmediately = synchronized(lock) {
            if (sealed) true else {
                commits += commit
                false
            }
        }
        if (rollbackImmediately) commit.rollbackIfOwned()
    }

    fun commit() {
        synchronized(lock) {
            sealed = true
            commits.clear()
        }
    }

    fun rollback() {
        val pending = synchronized(lock) {
            if (sealed) return
            sealed = true
            val copy = commits.asReversed().toList()
            commits.clear()
            copy
        }
        pending.forEach { it.rollbackIfOwned() }
    }
}

/** Ordered copy of both stores taken immediately before live publication. */
internal class Stage7NamespaceCacheSnapshot<V : Any>(
    val pageEntries: List<Pair<String, V>>,
    val markerEntries: List<Pair<String, Any>>
)

/** Marker value written only when one namespace completes a full OCR pass. */
internal data class Stage7FullDocumentIndexMarker(val namespace: String)

/**
 * Per-document visibility authority for OCR cache transactions. Work stages
 * results in a private transaction while the namespace [Mutex] excludes a
 * competing reader transaction. Synchronous readers only inspect committed
 * maps. The visibility lock is held only around the non-suspending
 * publication section, never across worker/Main suspension. Publication snapshots restore
 * the complete ordered stores if a later commit check fails, including entries
 * evicted by an access-order LRU insertion.
 */
internal class Stage7NamespaceCacheAuthority<V : Any>(
    private val pageStore: MutableMap<String, V>,
    private val markerStore: MutableMap<String, Any>,
    private val beforeCommit: (() -> Unit)? = null,
    private val beforeReservationRelease: (() -> Unit)? = null,
    private val publicationFence: Stage7PublicationFence = Stage7PublicationFence.global
) {
    private val visibilityLock = ReentrantLock()
    private val namespaceLocks = ConcurrentHashMap<String, Mutex>()
    private val activeNamespaces = mutableMapOf<String, Int>()
    @Volatile
    private var committedPageView: Map<String, V> = copyEntries(pageStore)
    @Volatile
    private var committedMarkerView: Map<String, Any> = copyEntries(markerStore)
    private val pageCommitter = Stage7CacheCommitter(pageStore, visibilityLock, beforeCommit)
    private val markerCommitter = Stage7CacheCommitter(markerStore, visibilityLock, beforeCommit)

    suspend fun <T> withNamespaceTransaction(
        namespace: String,
        block: suspend Stage7NamespaceCacheTransaction<V>.() -> T
    ): T = withNamespaceTransaction(
        namespace = namespace,
        publicationAdmission = {},
        block = block
    )

    /**
     * Runs the same transaction with an owner/session fence evaluated inside
     * the final visibility lock. The callback must not suspend. It is called
     * before every live mutation, so a token invalidated after staging causes
     * the complete transaction to roll back instead of publishing a page or
     * full-document marker.
     */
    suspend fun <T> withNamespaceTransaction(
        namespace: String,
        publicationAdmission: () -> Unit,
        block: suspend Stage7NamespaceCacheTransaction<V>.() -> T
    ): T {
        reserveNamespace(namespace)
        try {
            val namespaceMutex = namespaceLocks.computeIfAbsent(namespace) { Mutex() }
            // The reservation is acquired before this potentially suspending
            // mutex wait and remains held until the withLock call has released
            // the mutex. A synchronous compatibility helper therefore cannot
            // publish in either lifecycle gap.
            return namespaceMutex.withLock {
                val transaction = Stage7NamespaceCacheTransaction(this, namespace)
                try {
                    val result = transaction.block()
                    transaction.commit(publicationAdmission)
                    result
                } catch (cancelled: CancellationException) {
                    transaction.rollback()
                    throw cancelled
                } catch (error: Throwable) {
                    transaction.rollback()
                    throw error
                }
            }
        } finally {
            // This hook is synchronous and exists only for deterministic seam
            // tests. It runs after Mutex.unlock but before the reservation is
            // released, so it cannot create a publication window.
            try {
                beforeReservationRelease?.invoke()
            } finally {
                releaseNamespace(namespace)
            }
        }
    }

    fun page(key: String): V? {
        if (!visibilityLock.tryLock()) return committedPageView[key]
        return try {
            pageStore[key]
        } finally {
            visibilityLock.unlock()
        }
    }

    fun isDocumentCached(namespace: String): Boolean {
        if (!visibilityLock.tryLock()) return committedMarkerView.containsKey(namespace)
        return try {
            markerStore.containsKey(namespace)
        } finally {
            visibilityLock.unlock()
        }
    }

    /** Compatibility helper; it cannot publish while any namespace operation is reserved. */
    fun markDocumentCached(namespace: String) {
        // The helper can be called from Compose/Main. Never wait for a worker
        // publication; a later transaction remains responsible for retrying
        // its own marker commit when this non-blocking compatibility call is
        // fenced.
        publicationFence.tryWithPublication {
            if (!visibilityLock.tryLock()) return@tryWithPublication
            try {
                // This non-suspending legacy helper cannot acquire the
                // namespace Mutex. Treat every reserved operation, including
                // one waiting for that Mutex, as the owner of the namespace
                // so the helper cannot publish a full marker in the middle of
                // a staged or rollback-able operation.
                if ((activeNamespaces[namespace] ?: 0) == 0) {
                    markerStore.putIfAbsent(namespace, Stage7FullDocumentIndexMarker(namespace))
                    refreshCommittedViewsLocked()
                }
            } finally {
                visibilityLock.unlock()
            }
        }
    }

    internal fun hasPage(key: String): Boolean {
        if (!visibilityLock.tryLock()) return committedPageView.containsKey(key)
        return try {
            pageStore.containsKey(key)
        } finally {
            visibilityLock.unlock()
        }
    }

    private suspend fun publish(
        transaction: Stage7NamespaceCacheTransaction<V>,
        ensureActive: () -> Unit
    ) = publicationFence.withPublication {
        visibilityLock.lock()
        try {
            var snapshot: Stage7NamespaceCacheSnapshot<V>? = null
            try {
                // No suspension is permitted while the visibility lock is
                // held. The publication fence was acquired first, so a
                // coordinator invalidation either completed before this
                // admission or waits until every page/marker mutation and
                // the committed-view seal below are complete.
                ensureActive()
                snapshot = Stage7NamespaceCacheSnapshot(
                    pageEntries = pageStore.entries.map { it.key to it.value },
                    markerEntries = markerStore.entries.map { it.key to it.value }
                )
                transaction.pendingPages().forEach { (key, page) ->
                    transaction.recordInstalled(
                        pageCommitter.commitIfActive(key, page, ensureActive)
                    )
                }
                transaction.pendingMarker()?.let { (key, marker) ->
                    transaction.recordInstalled(
                        markerCommitter.commitIfActive(key, marker, ensureActive)
                    )
                }
                // The final map mutation above is the linearization point.
                // Do not checkpoint after it: a cancellation delivered after
                // this point must not make a valid committed cache invisible.
                refreshCommittedViewsLocked()
                transaction.sealCommitted()
            } catch (cancelled: CancellationException) {
                rollbackPublication(transaction, snapshot, cancelled)
                throw cancelled
            } catch (error: Throwable) {
                rollbackPublication(transaction, snapshot, error)
                throw error
            }
        } finally {
            visibilityLock.unlock()
        }
    }

    private fun rollbackPublication(
        transaction: Stage7NamespaceCacheTransaction<V>,
        snapshot: Stage7NamespaceCacheSnapshot<V>?,
        failure: Throwable
    ) {
        try {
            transaction.rollback()
        } catch (rollbackFailure: Throwable) {
            failure.addSuppressed(rollbackFailure)
        }
        if (snapshot != null) {
            try {
                restoreSnapshot(snapshot)
            } catch (restoreFailure: Throwable) {
                failure.addSuppressed(restoreFailure)
            }
        }
    }

    private fun restoreSnapshot(snapshot: Stage7NamespaceCacheSnapshot<V>) {
        pageStore.clear()
        snapshot.pageEntries.forEach { (key, value) -> pageStore[key] = value }
        markerStore.clear()
        snapshot.markerEntries.forEach { (key, value) -> markerStore[key] = value }
        refreshCommittedViewsLocked()
    }

    private fun refreshCommittedViewsLocked() {
        committedPageView = copyEntries(pageStore)
        committedMarkerView = copyEntries(markerStore)
    }

    private fun reserveNamespace(namespace: String) {
        visibilityLock.lock()
        try {
            activeNamespaces[namespace] = (activeNamespaces[namespace] ?: 0) + 1
        } finally {
            visibilityLock.unlock()
        }
    }

    private fun releaseNamespace(namespace: String) {
        visibilityLock.lock()
        try {
            val reservations = activeNamespaces[namespace] ?: return
            if (reservations <= 1) {
                // Every transaction reserves before it can wait for the
                // namespace mutex. Removing the mutex only at the final
                // reservation release therefore cannot strand a waiter or
                // invalidate an active owner.
                activeNamespaces.remove(namespace)
                namespaceLocks.remove(namespace)
            } else {
                activeNamespaces[namespace] = reservations - 1
            }
        } finally {
            visibilityLock.unlock()
        }
    }

    private fun <T : Any> copyEntries(store: Map<String, T>): Map<String, T> =
        LinkedHashMap<String, T>().also { copy ->
            store.entries.forEach { entry -> copy[entry.key] = entry.value }
        }

    internal suspend fun commit(
        transaction: Stage7NamespaceCacheTransaction<V>,
        publicationAdmission: () -> Unit = {}
    ) {
        val context = currentCoroutineContext()
        context.ensureActive()
        publish(transaction) {
            context.ensureActive()
            publicationAdmission()
        }
    }

    /**
     * Atomically removes every page and full-pass marker below one exact cache
     * namespace prefix. The publication fence prevents a session close from
     * racing a transaction's visibility commit.
     */
    internal suspend fun clearNamespacePrefix(prefix: String) {
        publicationFence.withInvalidation {
            visibilityLock.lock()
            try {
                pageStore.keys.filter { it.startsWith(prefix) }.toList().forEach(pageStore::remove)
                markerStore.keys.filter { it.startsWith(prefix) }.toList().forEach(markerStore::remove)
                refreshCommittedViewsLocked()
            } finally {
                visibilityLock.unlock()
            }
        }
    }

    /** Deterministic retention inspection for bounded namespace tests. */
    internal fun retainedNamespaceLockCount(): Int {
        visibilityLock.lock()
        return try {
            namespaceLocks.size
        } finally {
            visibilityLock.unlock()
        }
    }

    /** Deterministic reservation inspection for waiter/owner race tests. */
    internal fun activeNamespaceReservationCount(): Int {
        visibilityLock.lock()
        return try {
            activeNamespaces.values.sum()
        } finally {
            visibilityLock.unlock()
        }
    }
}

internal class Stage7NamespaceCacheTransaction<V : Any> internal constructor(
    private val authority: Stage7NamespaceCacheAuthority<V>,
    val namespace: String
) {
    private val pendingPages = LinkedHashMap<String, V>()
    private var pendingMarker: Any? = null
    private val installed = Stage7CacheCommitTransaction()
    private var sealed = false

    fun page(key: String): V? = pendingPages[key] ?: authority.page(key)

    fun isDocumentCached(): Boolean =
        pendingMarker != null || authority.isDocumentCached(namespace)

    fun stagePageIfActive(
        key: String,
        page: V,
        ensureActive: () -> Unit
    ): Boolean {
        ensureActive()
        if (pendingPages.containsKey(key) || authority.hasPage(key)) return false
        pendingPages[key] = page
        return true
    }

    fun stageMarkerIfActive(ensureActive: () -> Unit): Boolean {
        ensureActive()
        if (pendingMarker != null || authority.isDocumentCached(namespace)) return false
        pendingMarker = Stage7FullDocumentIndexMarker(namespace)
        return true
    }

    internal fun pendingPages(): List<Pair<String, V>> = pendingPages.entries.map { it.key to it.value }

    internal fun pendingMarker(): Pair<String, Any>? =
        pendingMarker?.let { namespace to it }

    internal fun recordInstalled(commit: Stage7CacheCommit<*, *>?) {
        installed.record(commit)
    }

    internal suspend fun commit(publicationAdmission: () -> Unit = {}) =
        authority.commit(this, publicationAdmission)

    internal fun sealCommitted() {
        if (sealed) return
        sealed = true
        installed.commit()
        pendingPages.clear()
        pendingMarker = null
    }

    internal fun rollback() {
        if (sealed) return
        sealed = true
        installed.rollback()
        pendingPages.clear()
        pendingMarker = null
    }
}

/**
 * Identity-aware ownership for resources which are not [Closeable], notably
 * Android Bitmaps. Aliased references are registered once and released once.
 */
class Stage7ResourceOwner<T : Any>(
    private val releaseResource: (T) -> Unit
) : Closeable {
    private val lock = Any()
    private val resources = IdentityHashMap<T, Unit>()
    private var closed = false

    fun own(resource: T): T {
        try {
            synchronized(lock) {
                check(!closed) { "resource owner is already closed" }
                resources[resource] = Unit
            }
        } catch (error: Throwable) {
            // Registration is the first ownership boundary. If it fails
            // (including a closed owner or an allocation failure in the map),
            // the caller must not be left holding an unowned bitmap/resource.
            try {
                releaseResource(resource)
            } catch (cleanupFailure: Throwable) {
                error.addSuppressed(cleanupFailure)
            }
            throw error
        }
        return resource
    }

    fun ownCreated(create: () -> T): T = own(create())

    fun ownCreatedOrNull(create: () -> T?): T? = create()?.let(::own)

    fun owned(resource: T): Stage7OwnedResource<T> {
        own(resource)
        return try {
            Stage7OwnedResource(resource, this)
        } catch (error: Throwable) {
            release(resource)
            throw error
        }
    }

    fun ownedCreated(create: () -> T): Stage7OwnedResource<T> = owned(create())

    fun ownedCreatedOrNull(create: () -> T?): Stage7OwnedResource<T>? =
        create()?.let(::owned)

    /** Detaches a resource when another owner takes over responsibility. */
    fun detach(resource: T): Boolean = synchronized(lock) {
        resources.remove(resource) != null
    }

    fun release(resource: T) {
        val shouldRelease = synchronized(lock) {
            resources.remove(resource) != null
        }
        if (shouldRelease) releaseResource(resource)
    }

    override fun close() {
        val toRelease: List<T> = synchronized(lock) {
            if (closed) return
            closed = true
            val values = resources.keys.toList()
            resources.clear()
            values
        }

        var firstFailure: Throwable? = null
        toRelease.forEach { resource ->
            try {
                releaseResource(resource)
            } catch (error: Throwable) {
                if (firstFailure == null) {
                    firstFailure = error
                } else {
                    firstFailure?.addSuppressed(error)
                }
            }
        }
        firstFailure?.let { throw it }
    }
}

/** A value whose complete resource graph is released by [close]. */
class Stage7OwnedResource<T : Any> internal constructor(
    val value: T,
    private val owner: Stage7ResourceOwner<T>
) : Closeable {
    /** Transfers responsibility to a cache or another long-lived UI owner. */
    fun transferOwnership(): T {
        check(owner.detach(value)) { "resource ownership was already transferred or closed" }
        return value
    }

    override fun close() = owner.close()
}
