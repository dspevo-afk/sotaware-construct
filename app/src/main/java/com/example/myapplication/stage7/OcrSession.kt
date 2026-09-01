package com.example.myapplication.stage7

import com.example.myapplication.OcrBox
import com.example.myapplication.PageOcr
import com.example.myapplication.stage3.DocumentSessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.IdentityHashMap
import java.util.LinkedHashMap

/** Raised when work tries to use an OCR session which has already been closed. */
class OcrSessionClosedException(message: String) : IllegalStateException(message)

/** Raised internally when a session/page/query admission check rejects work. */
class OcrSessionStaleException(message: String) : IllegalStateException(message)

/** Factory seam for one complete OCR resource graph. */
fun interface OcrSessionResourceFactory {
    suspend fun open(token: DocumentSessionToken): OcrSessionResourceGraph
}

/**
 * Resources shared by every page operation in one [DocumentSessionToken].
 * Implementations own all platform handles opened by the session and must
 * close the complete graph from [Closeable.close].
 */
interface OcrSessionResourceGraph : Closeable {
    suspend fun pageCount(): Int

    /** Return embedded word boxes; an insufficient list selects OCR fallback. */
    suspend fun extractEmbeddedText(pageIndex: Int): List<OcrBox>

    /** Render and recognize one page using the session-owned recognizer. */
    suspend fun recognizePage(pageIndex: Int): List<OcrBox>
}

/**
 * Owns one token-scoped OCR resource graph. Page access is serialized because
 * PdfRenderer pages and the shared ML Kit recognizer are not treated as
 * concurrently usable. Transient page/bitmap ownership belongs to the graph's
 * [OcrSessionResourceGraph.recognizePage] implementation.
 */
class OcrSession(
    val token: DocumentSessionToken,
    private val resources: OcrSessionResourceGraph
) : Closeable {
    companion object {
        const val EMBEDDED_TEXT_MIN_BOXES: Int = 10
    }

    private val pageMutex = Mutex()
    private val stateLock = Any()
    private val activeJobs = IdentityHashMap<Job, Unit>()
    private var closed = false
    private var resourcesClosed = false
    private var closeFailure: Throwable? = null
    private var cachedPageCount: Int? = null
    private val closeMutex = Mutex()
    private val resourceCloseLock = Any()
    private var closeStarted = false
    private var closeComplete = false
    private var closeCompletion: CompletableDeferred<Unit>? = null

    private data class ClosePlan(
        val ownsClose: Boolean,
        val completion: CompletableDeferred<Unit>?,
        val callerIsActiveOperation: Boolean
    )

    /** Read the shared page count through the same serialized resource path. */
    suspend fun pageCount(admits: () -> Boolean = { true }): Int =
        runSerialized(admits) {
            cachedPageCount ?: resources.pageCount().also { count ->
                require(count >= 0) { "OCR page count must be non-negative" }
                cachedPageCount = count
            }
        }

    /**
     * Build one page result. A rejected admission is surfaced to the caller so
     * no caller can accidentally cache the result. Registry-leased callers
     * retire and close the exact entry after the lease unwinds; direct owners
     * retain responsibility for [closeAndJoin].
     */
    suspend fun pageOcr(
        pageIndex: Int,
        admits: () -> Boolean = { true }
    ): PageOcr = runSerialized(admits) {
        require(pageIndex >= 0) { "pageIndex must be non-negative" }
        val count = cachedPageCount ?: resources.pageCount().also { pageCount ->
            require(pageCount >= 0) { "OCR page count must be non-negative" }
            cachedPageCount = pageCount
        }
        require(pageIndex < count) { "pageIndex $pageIndex is outside page count $count" }

        val embedded = resources.extractEmbeddedText(pageIndex)
        currentCoroutineContext().ensureActive()
        if (embedded.size >= EMBEDDED_TEXT_MIN_BOXES) {
            PageOcr(pageIndex, embedded)
        } else {
            PageOcr(pageIndex, resources.recognizePage(pageIndex))
        }
    }

    /**
     * Atomically marks the session closed, cancels and joins active operations,
     * and closes the resource graph under [NonCancellable]. If [primaryFailure]
     * is supplied, a close failure is attached to it and never replaces it.
     */
    suspend fun closeAndJoin(primaryFailure: Throwable? = null) {
        val callerJob = currentCoroutineContext()[Job]
        val closePlan = synchronized(stateLock) {
            closed = true
            when {
                closeComplete -> ClosePlan(false, null, false)
                !closeStarted -> {
                    closeStarted = true
                    CompletableDeferred<Unit>().also { completion ->
                        closeCompletion = completion
                    }.let { completion ->
                        ClosePlan(true, completion, false)
                    }
                }
                else -> ClosePlan(
                    ownsClose = false,
                    completion = closeCompletion,
                    callerIsActiveOperation = callerJob != null && activeJobs.containsKey(callerJob)
                )
            }
        }

        if (closePlan.ownsClose) {
            val jobs = synchronized(stateLock) {
                activeJobs.keys.filter { it !== callerJob }
            }
            try {
                withContext(NonCancellable) {
                    val cancellation = CancellationException("OCR session closed")
                    jobs.forEach { it.cancel(cancellation) }
                    jobs.forEach { it.join() }
                    closeMutex.withLock {
                        closeResourcesIfNeeded()
                    }
                }
            } finally {
                synchronized(stateLock) { closeComplete = true }
                closePlan.completion?.complete(Unit)
            }
        } else if (!closePlan.callerIsActiveOperation) {
            closePlan.completion?.let { completion ->
                withContext(NonCancellable) { completion.await() }
            }
        }

        val failure = synchronized(stateLock) { closeFailure }
        if (primaryFailure != null) {
            if (failure != null && failure !== primaryFailure &&
                primaryFailure.suppressed.none { it === failure }
            ) {
                primaryFailure.addSuppressed(failure)
            }
        } else {
            failure?.let { throw it }
        }
    }

    /**
     * Compatibility close for non-suspending owners. New lifecycle code must
     * use [closeAndJoin] so active operations are joined before resources are
     * closed; this method still invalidates and cancels the session first.
     */
    override fun close() {
        val (jobs, ownsClose, completion) = synchronized(stateLock) {
            closed = true
            val active = activeJobs.keys.toList()
            if (!closeStarted) {
                closeStarted = true
                val created = CompletableDeferred<Unit>()
                closeCompletion = created
                Triple(active, true, created)
            } else {
                Triple(active, false, closeCompletion)
            }
        }
        val cancellation = CancellationException("OCR session closed")
        jobs.forEach { it.cancel(cancellation) }
        if (ownsClose) {
            try {
                closeResourcesIfNeeded()
            } finally {
                synchronized(stateLock) { closeComplete = true }
                completion?.complete(Unit)
            }
        }
        synchronized(stateLock) { closeFailure }?.let { throw it }
    }

    private suspend fun <T> runSerialized(
        admits: () -> Boolean,
        block: suspend () -> T
    ): T {
        currentCoroutineContext().ensureActive()
        val job = currentCoroutineContext()[Job]
        if (job != null) {
            synchronized(stateLock) {
                if (closed) throw OcrSessionClosedException("OCR session is closed")
                activeJobs[job] = Unit
            }
        } else {
            synchronized(stateLock) {
                if (closed) throw OcrSessionClosedException("OCR session is closed")
            }
        }

        try {
            return pageMutex.withLock {
                ensureAdmitted(admits)
                currentCoroutineContext().ensureActive()
                val result = block()
                currentCoroutineContext().ensureActive()
                ensureAdmitted(admits)
                result
            }
        } finally {
            if (job != null) synchronized(stateLock) { activeJobs.remove(job) }
        }
    }

    private fun ensureAdmitted(admits: () -> Boolean) {
        if (!admits()) throw OcrSessionStaleException("OCR work is no longer current")
    }

    private fun closeResourcesIfNeeded() {
        synchronized(resourceCloseLock) {
            synchronized(stateLock) {
                if (resourcesClosed) return
                resourcesClosed = true
            }
            try {
                resources.close()
            } catch (error: Throwable) {
                synchronized(stateLock) { closeFailure = error }
            }
        }
    }
}

/**
 * Opens short-lived sessions for direct tests/owners. The finally block
 * closes a successful session too, making the normal-success lifecycle
 * deterministic without forcing the long-lived registry to close between
 * page routes.
 */
class OcrSessionRunner(private val factory: OcrSessionResourceFactory) {
    suspend fun open(token: DocumentSessionToken): OcrSession {
        currentCoroutineContext().ensureActive()
        val resources = factory.open(token)
        return try {
            currentCoroutineContext().ensureActive()
            OcrSession(token, resources)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                try {
                    resources.close()
                } catch (closeFailure: Throwable) {
                    error.addSuppressed(closeFailure)
                }
            }
            throw error
        }
    }

    suspend fun <T> run(
        token: DocumentSessionToken,
        block: suspend (OcrSession) -> T
    ): T {
        val session = open(token)
        var primaryFailure: Throwable? = null
        try {
            return block(session)
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                session.closeAndJoin(primaryFailure)
            }
        }
    }
}

/**
 * Reuses one [OcrSession] for the exact full token. A new generation, even for
 * the same document/source, receives a separate resource graph.
 */
class OcrSessionRegistry(
    /** JVM-only cancellation seam between graph open and registry handoff. */
    private val afterOpenBeforeRegistration: ((Job?) -> Unit)? = null,
    /** JVM-only hook used to hold the post-removal/pre-close race boundary. */
    private val beforeEntryClose: (() -> Unit)? = null
) : Closeable {
    private val openMutex = Mutex()
    private val stateLock = Any()
    private class SessionEntry(val session: OcrSession) {
        var leases: Int = 0
        var closing: Boolean = false
        val leaseJobs = mutableListOf<Job>()
        val noLeases = CompletableDeferred<Unit>()
    }

    private class SessionLease(
        private val entry: SessionEntry,
        val ownerJob: Job?
    ) {
        val session: OcrSession get() = entry.session
        val expectedEntry: SessionEntry get() = entry
        private var released = false

        fun release(onReleased: (SessionEntry, Job?) -> Unit) {
            if (released) return
            released = true
            onReleased(entry, ownerJob)
        }
    }

    private val sessions = LinkedHashMap<DocumentSessionToken, SessionEntry>()
    private val closedTokens = mutableSetOf<DocumentSessionToken>()
    private var closed = false

    suspend fun getOrOpen(
        token: DocumentSessionToken,
        factory: OcrSessionResourceFactory
    ): OcrSession {
        // Source-compatible legacy handoff. New production routes must use
        // withSession so the registry keeps an active-use lease until the
        // caller's page/query block has returned.
        val lease = acquire(token, factory)
        lease.release(::release)
        return lease.session
    }

    /**
     * Runs work while the exact token's graph is leased. Eviction removes the
     * entry first, then waits for this lease before closing it; a later caller
     * therefore opens a fresh graph and can never receive a closing one.
     */
    suspend fun <T> withSession(
        token: DocumentSessionToken,
        factory: OcrSessionResourceFactory,
        block: suspend (OcrSession) -> T
    ): T {
        val lease = acquire(token, factory)
        var primaryFailure: Throwable? = null
        return try {
            block(lease.session)
        } catch (cancelled: CancellationException) {
            primaryFailure = cancelled
            throw cancelled
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            if (primaryFailure == null) {
                lease.release(::release)
            } else {
                withContext(NonCancellable) {
                    val failure = checkNotNull(primaryFailure)
                    // Retire the exact entry before releasing the operation
                    // lease.  Otherwise a namespace publication failure can
                    // release E, let a same-token caller reacquire E, and
                    // then close that newer owner from this stale cleanup.
                    // Removal and markClosing are linearized by openMutex;
                    // release then lets the retired entry finish without
                    // exposing it to a new acquire.
                    var retired: SessionEntry? = null
                    var retirementFailure: Throwable? = null
                    try {
                        retired = retireEntryIfCurrent(token, lease.expectedEntry)
                    } catch (error: Throwable) {
                        retirementFailure = error
                    } finally {
                        // Even an unexpected retirement failure must not
                        // strand the operation lease or replace cancellation.
                        lease.release(::release)
                    }
                    retirementFailure?.let { error ->
                        if (error !== failure && failure.suppressed.none { it === error }) {
                            failure.addSuppressed(error)
                        }
                    }
                    retired?.let { entry ->
                        try {
                            beforeEntryClose?.invoke()
                            closeEntryAndJoin(entry, failure)
                        } catch (cleanupFailure: Throwable) {
                            if (cleanupFailure !== failure &&
                                failure.suppressed.none { it === cleanupFailure }
                            ) {
                                failure.addSuppressed(cleanupFailure)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Atomically removes only the entry owned by this operation.  The caller
     * must release its lease after this returns and before closing the entry.
     */
    private suspend fun retireEntryIfCurrent(
        token: DocumentSessionToken,
        expectedEntry: SessionEntry
    ): SessionEntry? = openMutex.withLock {
        synchronized(stateLock) {
            if (sessions[token] !== expectedEntry) {
                null
            } else {
                sessions.remove(token)?.also(::markClosing)
            }
        }
    }

    private suspend fun acquire(
        token: DocumentSessionToken,
        factory: OcrSessionResourceFactory
    ): SessionLease {
        currentCoroutineContext().ensureActive()
        val ownerJob = currentCoroutineContext()[Job]
        var opened: OcrSession? = null
        try {
            return openMutex.withLock {
                val existing = synchronized(stateLock) {
                    if (closed || token in closedTokens) {
                        throw OcrSessionClosedException("OCR session token is closed")
                    }
                    sessions[token]?.takeIf { !it.closing }?.also {
                        it.leases++
                        ownerJob?.let(it.leaseJobs::add)
                    }
                }
                if (existing != null) return@withLock SessionLease(existing, ownerJob)

                val created = OcrSessionRunner(factory).open(token)
                // Take ownership before the next cancellation checkpoint.
                // If that checkpoint rejects the operation, the catch block
                // closes this exact candidate instead of losing the Android
                // PDF/renderer/recognizer graph between open and insertion.
                opened = created
                afterOpenBeforeRegistration?.invoke(ownerJob)
                currentCoroutineContext().ensureActive()
                val entry = synchronized(stateLock) {
                    if (closed || token in closedTokens) {
                        throw OcrSessionClosedException("OCR session token was closed while opening")
                    }
                    SessionEntry(created).also { entry ->
                        entry.leases = 1
                        ownerJob?.let(entry.leaseJobs::add)
                        sessions[token] = entry
                        entry
                    }
                }
                val lease = SessionLease(entry, ownerJob)
                // The lease now owns the candidate.  From here on any
                // failure is handled by the registry's exact-entry path.
                opened = null
                lease
            }
        } catch (error: Throwable) {
            opened?.let { candidate ->
                val retained = synchronized(stateLock) { sessions[token]?.session === candidate }
                if (!retained) {
                    withContext(NonCancellable) {
                        candidate.closeAndJoin(error)
                    }
                }
            }
            throw error
        }
    }

    private fun release(entry: SessionEntry, ownerJob: Job?) {
        synchronized(stateLock) {
            check(entry.leases > 0) { "OCR session lease released more than once" }
            entry.leases -= 1
            if (ownerJob != null) {
                val index = entry.leaseJobs.indexOfFirst { it === ownerJob }
                if (index >= 0) entry.leaseJobs.removeAt(index)
            }
            if (entry.closing && entry.leases == 0) entry.noLeases.complete(Unit)
        }
    }

    suspend fun closeSessionAndJoin(
        token: DocumentSessionToken,
        primaryFailure: Throwable? = null
    ) = closeSessionInternal(token, primaryFailure, permanently = true)

    /**
     * Removes a failed/stale operation's closed session without permanently
     * fencing the token. A later query can reopen a clean graph for the same
     * still-current document session; owner teardown uses the permanent path.
     */
    suspend fun evictSessionAndJoin(
        token: DocumentSessionToken,
        primaryFailure: Throwable? = null
    ) = closeSessionInternal(token, primaryFailure, permanently = false)

    /**
     * Failure cleanup for a leased operation. A failed old lease may finish
     * after its entry was removed and a new entry was rebound for the same
     * full token, so removal is conditional on the exact entry identity.
     */
    private suspend fun evictSessionAndJoin(
        token: DocumentSessionToken,
        expectedEntry: SessionEntry,
        primaryFailure: Throwable?
    ) = closeSessionInternal(
        token = token,
        primaryFailure = primaryFailure,
        permanently = false,
        expectedEntry = expectedEntry
    )

    /** Identity-safe cleanup for an OcrIndex operation which still holds the session reference. */
    internal suspend fun evictSessionAndJoinIfCurrent(
        token: DocumentSessionToken,
        expectedSession: OcrSession,
        primaryFailure: Throwable? = null,
        onlyIfNoActiveLeases: Boolean = false
    ) = closeSessionInternal(
        token = token,
        primaryFailure = primaryFailure,
        permanently = false,
        expectedSession = expectedSession,
        onlyIfNoActiveLeases = onlyIfNoActiveLeases
    )

    private suspend fun closeSessionInternal(
        token: DocumentSessionToken,
        primaryFailure: Throwable?,
        permanently: Boolean,
        expectedEntry: SessionEntry? = null,
        expectedSession: OcrSession? = null,
        onlyIfNoActiveLeases: Boolean = false
    ) = withContext(NonCancellable) {
        val entry = openMutex.withLock {
            synchronized(stateLock) {
                if (permanently) closedTokens += token
                val current = sessions[token]
                val matchesExpectation = (expectedEntry == null || current === expectedEntry) &&
                    (expectedSession == null || current?.session === expectedSession)
                val hasNoActiveLeases = current == null || current.leases == 0
                if (!matchesExpectation || (onlyIfNoActiveLeases && !hasNoActiveLeases)) {
                    null
                } else {
                    sessions.remove(token)?.also(::markClosing)
                }
            }
        }
        entry?.let {
            beforeEntryClose?.invoke()
            closeEntryAndJoin(it, primaryFailure)
        }
    }

    suspend fun closeAndJoin(primaryFailure: Throwable? = null) {
        val toClose = withContext(NonCancellable) {
            openMutex.withLock {
                synchronized(stateLock) {
                    closed = true
                    closedTokens += sessions.keys
                    sessions.values.toList().also { entries ->
                        entries.forEach(::markClosing)
                        sessions.clear()
                    }
                }
            }
        }
        var firstFailure: Throwable? = null
        toClose.forEach { entry ->
            try {
                closeEntryAndJoin(entry, primaryFailure)
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
                else firstFailure?.addSuppressed(error)
            }
        }
        if (primaryFailure != null) {
            firstFailure?.let(primaryFailure::addSuppressed)
        } else {
            firstFailure?.let { throw it }
        }
    }

    /** Compatibility close; lifecycle owners should call [closeAndJoin]. */
    override fun close() {
        val toClose = synchronized(stateLock) {
            closed = true
            closedTokens += sessions.keys
            sessions.values.toList().also { entries ->
                entries.forEach(::markClosing)
                sessions.clear()
            }
        }
        var firstFailure: Throwable? = null
        toClose.forEach { entry ->
            try {
                entry.session.close()
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
                else firstFailure?.addSuppressed(error)
            }
        }
        firstFailure?.let { throw it }
    }

    private fun markClosing(entry: SessionEntry) {
        entry.closing = true
        if (entry.leases == 0) entry.noLeases.complete(Unit)
    }

    private suspend fun closeEntryAndJoin(
        entry: SessionEntry,
        primaryFailure: Throwable?
    ) {
        withContext(NonCancellable) {
            val leaseJobs = synchronized(stateLock) { entry.leaseJobs.toList() }
            val cancellation = CancellationException("OCR session entry is being evicted")
            leaseJobs.forEach { it.cancel(cancellation) }
            if (!entry.noLeases.isCompleted) entry.noLeases.await()
            entry.session.closeAndJoin(primaryFailure)
        }
    }
}
