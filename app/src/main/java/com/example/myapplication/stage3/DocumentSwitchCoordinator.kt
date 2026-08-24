package com.example.myapplication.stage3

import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.LocalRepositoryError
import com.example.myapplication.stage2.SourceFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The identity captured by every piece of work that can outlive the event
 * which started it.  DocumentId protects durable identity, sourceFingerprint
 * protects the contents behind a reused URI, and generation distinguishes two
 * separate sessions for the same document (A -> B -> A).
 */
data class DocumentSessionToken(
    val documentId: DocumentId,
    val sourceUri: String,
    val sourceFingerprint: SourceFingerprint?,
    val generation: Long
) {
    init {
        require(sourceUri.isNotBlank()) { "sourceUri must not be blank" }
        require(generation > 0L) { "generation must be positive" }
    }

    /** Stable identity for disk/in-memory caches. Session generation is kept
     * out of this value so a new session may reuse a valid source cache while
     * result application still requires the full token. */
    val sourceCacheKey: String
        get() = buildString {
            append(documentId.value)
            append('|')
            append(sourceUri)
            append('|')
            append(sourceFingerprint?.let { "${it.algorithm}:${it.digestHex}:${it.byteCount}" } ?: "unknown")
        }
}

data class DocumentSession(
    val target: ResolvedDocumentTarget,
    val token: DocumentSessionToken
)

data class ResolvedDocumentTarget(val association: DocumentAssociation)

/** A key for page/render/OCR/search/selection work. */
data class DocumentWorkToken(
    val session: DocumentSessionToken,
    val pageIndex: Int? = null,
    val queryRevision: Long? = null
) {
    init {
        require(pageIndex == null || pageIndex >= 0) { "pageIndex must be non-negative" }
        require(queryRevision == null || queryRevision >= 0L) { "queryRevision must be non-negative" }
    }
}

sealed class TargetResolution {
    data class Resolved(val target: ResolvedDocumentTarget) : TargetResolution()
    data class Failed(val failure: SwitchFailure) : TargetResolution()
}

data class DocumentLoadFailure(
    val detail: String,
    val repositoryError: LocalRepositoryError? = null,
    val cause: Throwable? = null
)

/** Result of a remote snapshot replacement through the Stage 3 seam. */
sealed class SessionSnapshotApplyResult {
    data object Applied : SessionSnapshotApplyResult()
    data object Stale : SessionSnapshotApplyResult()
    data class Failed(val error: LocalRepositoryError) : SessionSnapshotApplyResult()
}

sealed class SessionLoadResult {
    data class Loaded(
        val snapshot: DocumentSnapshotV1,
        val recoveredFromPrevious: Boolean = false,
        val pageCount: Int? = null
    ) : SessionLoadResult()

    /** No snapshot is a valid new-document case and produces an empty target. */
    data class Empty(val pageCount: Int? = null) : SessionLoadResult()

    data class Failed(val failure: DocumentLoadFailure) : SessionLoadResult()
}

enum class SwitchFailureStage {
    RESOLVE_TARGET,
    OUTGOING_FLUSH,
    TARGET_LOAD,
    TARGET_APPLY,
    CANCELLED
}

data class SwitchFailure(
    val stage: SwitchFailureStage,
    val detail: String,
    val repositoryError: LocalRepositoryError? = null,
    val cause: Throwable? = null
)

sealed class SwitchResult {
    data class Switched(
        val session: DocumentSession,
        val loadedSnapshot: Boolean,
        val recoveredFromPrevious: Boolean
    ) : SwitchResult()

    data class AlreadyActive(val session: DocumentSession) : SwitchResult()

    data class Failed(
        val failure: SwitchFailure,
        val preservedSession: DocumentSession?
    ) : SwitchResult()

    /** A newer switch superseded this transaction. */
    data class Superseded(val requestedSourceUri: String) : SwitchResult()
}

/**
 * Narrow host boundary between the transaction controller and Android/Compose
 * state. The coordinator never reads ViewModel state during a save. The host
 * must capture a complete immutable snapshot before calling saveSnapshot.
 */
interface DocumentSessionCallbacks {
    suspend fun resolveTarget(sourceUri: String): TargetResolution

    fun captureSnapshot(session: DocumentSession): DocumentSnapshotV1

    /** Capture form used while a document transaction is already held. */
    fun captureSnapshotWithinDocumentTransaction(session: DocumentSession): DocumentSnapshotV1 =
        captureSnapshot(session)

    /**
     * Reads the last known-good durable snapshot without consulting mutable
     * live UI state.  The default keeps lightweight test/legacy hosts source
     * compatible; Android overrides it with the repository read authority.
     */
    suspend fun captureDurableSnapshot(session: DocumentSession): DocumentSnapshotV1? =
        captureSnapshot(session)

    suspend fun saveSnapshot(
        session: DocumentSession,
        frozenSnapshot: DocumentSnapshotV1
    ): DocumentSaveResult

    /** Cancel and join cancellable work belonging to this session. */
    suspend fun cancelAndJoinDocumentWork(session: DocumentSession)

    /** Remove the old session's authority before live state is cleared. */
    fun invalidateDocumentWork(session: DocumentSession)

    /** Clear only document-scoped UI/cache state. */
    fun clearDocumentState()

    /** Make the token visible to the UI before a target load starts. */
    fun establishSession(session: DocumentSession)

    /** Resolve/migrate/load the target exactly once. Must not mutate live state. */
    suspend fun loadTarget(session: DocumentSession): SessionLoadResult

    /** Apply a fully validated snapshot only after the coordinator verifies the token. */
    fun applyLoadedSnapshot(session: DocumentSession, snapshot: DocumentSnapshotV1)

    /** Apply target metadata only after the target token has been revalidated. */
    fun onTargetMetadata(session: DocumentSession, pageCount: Int?) = Unit

    fun onRecoveredSnapshot(session: DocumentSession) = Unit

    fun onSwitchFailure(failure: SwitchFailure) = Unit

    /** Start OCR/render/search/Drive work only after target state is applied. */
    fun startDocumentBackgroundWork(session: DocumentSession) = Unit

    /** Re-arm non-persistence document work after an outgoing flush failure. */
    fun resumeDocumentBackgroundWork(session: DocumentSession) = Unit

    /** Surface ordinary debounced-save failures without pretending success. */
    fun onAutosaveFailure(session: DocumentSession, result: DocumentSaveResult.Failed) = Unit
}

/**
 * Debounced local persistence. It owns a single save mutex shared by delayed
 * autosave and the explicit switch flush, so a delayed A save can never race
 * with a switch and capture B's mutable state.
 */
class DocumentAutosaveController(
    private val scope: CoroutineScope,
    private val debounceMillis: Long,
    private val isCurrent: (DocumentSessionToken) -> Boolean,
    private val captureSnapshot: (DocumentSession) -> DocumentSnapshotV1,
    private val saveSnapshot: suspend (DocumentSession, DocumentSnapshotV1) -> DocumentSaveResult,
    private val onFailure: (DocumentSession, DocumentSaveResult.Failed) -> Unit,
    private val transactionBarrier: DocumentTransactionBarrier
) {
    private val saveMutex = Mutex()
    private val pendingLock = Any()
    private var pendingJob: Job? = null

    init {
        require(debounceMillis >= 0L) { "debounceMillis must be non-negative" }
    }

    fun markDirty(session: DocumentSession) {
        val replacement = scope.launch {
            try {
                delay(debounceMillis)
                // Lock order is document barrier -> save mutex.  Keeping the
                // barrier outside the save mutex prevents a lifecycle/switch
                // flush from waiting on a save that is itself waiting for the
                // same document barrier.
                transactionBarrier.withDocument(session.token.documentId) {
                    saveMutex.withLock saveLock@{
                        if (!isCurrent(session.token)) return@saveLock
                        val frozen = captureSnapshot(session)
                        if (!isCurrent(session.token)) return@saveLock
                        when (val result = saveSnapshot(session, frozen)) {
                            is DocumentSaveResult.Saved -> Unit
                            is DocumentSaveResult.Failed -> onFailure(session, result)
                        }
                    }
                }
            } catch (_: CancellationException) {
                throw CancellationException("document autosave cancelled")
            }
        }
        synchronized(pendingLock) {
            pendingJob?.cancel()
            pendingJob = replacement
        }
    }

    suspend fun cancelForSession(session: DocumentSession) {
        val job = synchronized(pendingLock) {
            val current = pendingJob
            if (current?.isActive == true) current.cancel()
            if (current?.isCompleted == true || current?.isCancelled == true) pendingJob = null
            current
        }
        job?.join()
    }

    /**
     * Saves the already frozen snapshot. The caller may place this call in a
     * narrowly scoped NonCancellable section for an explicit switch.
     */
    suspend fun flushFrozen(
        session: DocumentSession,
        frozenSnapshot: DocumentSnapshotV1
    ): DocumentSaveResult = transactionBarrier.withDocument(session.token.documentId) {
        flushFrozenWithinDocumentTransaction(session, frozenSnapshot)
    }

    /**
     * Saves a frozen snapshot while the caller already owns the document
     * barrier.  This is the only form used by Stage 3 transition code and by
     * the Stage 4 remote-acceptance seam, so the barrier is not reacquired.
     */
    suspend fun flushFrozenWithinDocumentTransaction(
        session: DocumentSession,
        frozenSnapshot: DocumentSnapshotV1
    ): DocumentSaveResult = saveMutex.withLock {
        saveSnapshot(session, frozenSnapshot)
    }

    suspend fun close() {
        val job = synchronized(pendingLock) {
            pendingJob?.cancel()
            pendingJob
        }
        job?.join()
    }
}

/**
 * The single authoritative local document switch transaction.
 *
 * The critical transition is serialized, while target loading is deliberately
 * performed by a coordinator-owned job outside the mutex. A newer switch can
 * cancel and join that job; a completion that ignores cancellation is still
 * rejected by the full session token before it can apply.
 */
class DocumentSwitchCoordinator(
    private val callbacks: DocumentSessionCallbacks,
    parentScope: CoroutineScope,
    debounceMillis: Long = 750L,
    private val coordinatorDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    private val transactionBarrier: DocumentTransactionBarrier = DocumentTransactionBarrier()
) {
    private val coordinatorScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + coordinatorDispatcher)
    private val switchMutex = Mutex()
    private val loadLock = Any()
    private val jobsLock = Any()
    private val invalidatedLock = Any()
    private val documentJobs = mutableMapOf<DocumentSessionToken, MutableSet<Job>>()
    private val invalidatedTokens = mutableSetOf<DocumentSessionToken>()

    @Volatile
    private var activeSessionInternal: DocumentSession? = null
    @Volatile
    private var activeLoad: ActiveLoad? = null
    /** Published only after the target snapshot has been applied to memory. */
    @Volatile
    private var appliedSessionToken: DocumentSessionToken? = null
    private var generation: Long = 0L

    private val autosave = DocumentAutosaveController(
        scope = coordinatorScope,
        debounceMillis = debounceMillis,
        isCurrent = ::isCurrent,
        captureSnapshot = callbacks::captureSnapshot,
        saveSnapshot = callbacks::saveSnapshot,
        onFailure = callbacks::onAutosaveFailure,
        transactionBarrier = transactionBarrier
    )

    private data class ActiveLoad(
        val session: DocumentSession,
        val deferred: Deferred<SessionLoadResult>,
        /** The last committed session to restore if this provisional load is abandoned. */
        val outgoing: DocumentSession?,
        val outgoingSnapshot: DocumentSnapshotV1?
    )

    fun currentSession(): DocumentSession? = activeSessionInternal

    fun isCurrent(token: DocumentSessionToken): Boolean =
        activeSessionInternal?.token == token && synchronized(invalidatedLock) {
            token !in invalidatedTokens
        }

    /** Provisional/cleared targets are not admissible sync sources. */
    fun isCurrentApplied(token: DocumentSessionToken): Boolean =
        appliedSessionToken == token && isCurrent(token)

    fun workToken(
        session: DocumentSession = activeSessionInternal ?: error("No active document session"),
        pageIndex: Int? = null,
        queryRevision: Long? = null
    ): DocumentWorkToken = DocumentWorkToken(session.token, pageIndex, queryRevision)

    /**
     * Validates a late result against document identity, source revision,
     * session generation, page, and query revision as applicable.
     */
    fun accepts(
        work: DocumentWorkToken,
        currentPageIndex: Int? = work.pageIndex,
        currentQueryRevision: Long? = work.queryRevision
    ): Boolean {
        if (!isCurrent(work.session)) return false
        if (work.pageIndex != null && work.pageIndex != currentPageIndex) return false
        if (work.queryRevision != null && work.queryRevision != currentQueryRevision) return false
        return true
    }

    fun markDocumentDirty() {
        // A target is token-current as soon as setup establishes it, but it
        // remains provisional until its loaded snapshot has been applied.
        // Do not let a surviving mutation callback schedule autosave against
        // the cleared placeholder state during that interval.
        activeSessionInternal
            ?.takeIf { isCurrentApplied(it.token) }
            ?.let(autosave::markDirty)
    }

    /**
     * Capture a complete immutable snapshot while the switch mutex prevents
     * the outgoing session from being cleared. This is used by independent
     * background consumers such as Drive autosync.
     */
    suspend fun captureCurrentSnapshot(token: DocumentSessionToken): DocumentSnapshotV1? =
        transactionBarrier.withDocument(token.documentId) {
            captureCurrentSnapshotWithinDocumentTransaction(token)
        }

    /** Capture form for callers that already own the shared document barrier. */
    suspend fun captureCurrentSnapshotWithinDocumentTransaction(
        token: DocumentSessionToken
    ): DocumentSnapshotV1? = switchMutex.withLock {
        val session = activeSessionInternal
        if (session?.token != token || !isCurrentApplied(token)) null
        else callbacks.captureSnapshotWithinDocumentTransaction(session)
    }

    /** Launches work with authority explicitly bound to one document session. */
    fun launchDocumentJob(
        token: DocumentSessionToken,
        block: suspend () -> Unit
    ): Job {
        val job = coordinatorScope.launch(start = CoroutineStart.LAZY) {
            if (!isCurrent(token)) return@launch
            try {
                block()
            } finally {
                val currentJob = coroutineContext[Job]
                if (currentJob != null) {
                    synchronized(jobsLock) { documentJobs[token]?.remove(currentJob) }
                }
            }
        }
        synchronized(jobsLock) { documentJobs.getOrPut(token) { linkedSetOf() }.add(job) }
        job.start()
        return job
    }

    private suspend fun cancelAndJoinDocumentJobs(session: DocumentSession) {
        val jobs = synchronized(jobsLock) { documentJobs.remove(session.token).orEmpty().toList() }
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
    }

    /** Used by lifecycle/autosave callers when they already hold a frozen capture. */
    suspend fun flushCurrent(): DocumentSaveResult? {
        val token = activeSessionInternal?.token ?: return null
        return transactionBarrier.withDocument(token.documentId) {
            switchMutex.withLock {
                val session = activeSessionInternal
                if (session?.token != token || !isCurrentApplied(token)) return@withLock null
                // A target session is provisional until its load result has
                // been applied and finishLoadLocked clears activeLoad. A
                // lifecycle flush must never capture the cleared loading
                // state and persist it over that target's last durable
                // snapshot.
                val provisional = synchronized(loadLock) {
                    activeLoad?.session?.token == session.token
                }
                if (provisional) return@withLock null
                withContext(NonCancellable) {
                    // Cancel a pending debounced save before freezing live
                    // state so the explicit lifecycle flush is the only
                    // writer for this session. The document barrier is held
                    // before cancellation, capture, and durable save.
                    autosave.cancelForSession(session)
                    val frozen = callbacks.captureSnapshot(session)
                    when (val result = autosave.flushFrozenWithinDocumentTransaction(session, frozen)) {
                        is DocumentSaveResult.Saved -> result
                        is DocumentSaveResult.Failed -> {
                            callbacks.onAutosaveFailure(session, result)
                            result
                        }
                    }
                }
            }
        }
    }

    /** Compatibility save primitive for bridges that still expose save-only. */
    suspend fun persistCurrentSnapshot(
        token: DocumentSessionToken,
        snapshot: DocumentSnapshotV1
    ): DocumentSaveResult? = transactionBarrier.withDocument(token.documentId) {
        switchMutex.withLock {
            val session = activeSessionInternal
                ?.takeIf { it.token == token && isCurrentApplied(token) }
                ?: return@withLock null
            autosave.cancelForSession(session)
            autosave.flushFrozenWithinDocumentTransaction(session, snapshot)
        }
    }

    /**
     * Persists and applies a complete remote snapshot as one session-bound
     * transition. The repository save is resolved through the callbacks' Stage
     * 2 association/fingerprint path; live state is replaced only afterward.
     */
    suspend fun persistAndApplyCurrentSnapshot(
        token: DocumentSessionToken,
        snapshot: DocumentSnapshotV1,
        isBindingCurrent: () -> Boolean = { true }
    ): SessionSnapshotApplyResult = transactionBarrier.withDocument(token.documentId) {
        persistAndApplyCurrentSnapshotWithinDocumentTransaction(token, snapshot, isBindingCurrent)
    }

    /**
     * Imports a canonical snapshot for the already active source.  The full
     * session token and freshly recomputed source fingerprint are checked while
     * holding the shared document barrier before the durable-before-memory
     * replacement seam is entered.  An import for another URI, changed bytes,
     * or a stale binding is rejected without publication.
     */
    suspend fun importCurrentSnapshot(
        token: DocumentSessionToken,
        snapshot: DocumentSnapshotV1,
        currentSourceFingerprint: SourceFingerprint?,
        isBindingCurrent: () -> Boolean = { true }
    ): SessionSnapshotApplyResult = transactionBarrier.withDocument(token.documentId) {
        if (snapshot.source.sourceUri != token.sourceUri ||
            currentSourceFingerprint != token.sourceFingerprint
        ) {
            return@withDocument SessionSnapshotApplyResult.Stale
        }
        persistAndApplyCurrentSnapshotWithinDocumentTransaction(token, snapshot, isBindingCurrent)
    }

    /**
     * Stage 4 calls this form while it already owns [transactionBarrier].
     * Keeping the barrier across the entire method closes the interval in
     * which a switch could otherwise capture old memory after the repository
     * accepted the remote snapshot but before live replacement.
     */
    suspend fun persistAndApplyCurrentSnapshotWithinDocumentTransaction(
        token: DocumentSessionToken,
        snapshot: DocumentSnapshotV1,
        isBindingCurrent: () -> Boolean = { true }
    ): SessionSnapshotApplyResult {
        // Only admission and the final in-memory replacement need the switch
        // mutex. The durable write is serialized by autosave's save mutex but
        // the shared document barrier remains held by the caller, so a
        // document switch cannot capture the old live state in this interval.
        val session = switchMutex.withLock {
            activeSessionInternal
                ?.takeIf { it.token == token && isCurrentApplied(token) && isBindingCurrent() }
        } ?: return SessionSnapshotApplyResult.Stale

        val previousLive = switchMutex.withLock {
            activeSessionInternal
                ?.takeIf { it.token == token && isCurrentApplied(token) }
                ?.let(callbacks::captureSnapshotWithinDocumentTransaction)
        } ?: return SessionSnapshotApplyResult.Stale
        val previousDurable = try {
            callbacks.captureDurableSnapshot(session) ?: previousLive
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return SessionSnapshotApplyResult.Failed(
                LocalRepositoryError.InvalidSnapshot(
                    "could not capture the previous durable snapshot: ${error.message ?: error}"
                )
            )
        }

        autosave.cancelForSession(session)
        if (!isCurrentApplied(token) || !isBindingCurrent()) return SessionSnapshotApplyResult.Stale
        val saved = try {
            autosave.flushFrozenWithinDocumentTransaction(session, snapshot)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                restoreSnapshotWithinDocumentTransaction(token, previousDurable, previousLive)
            }
            throw cancelled
        }
        return when (saved) {
            is DocumentSaveResult.Failed -> SessionSnapshotApplyResult.Failed(saved.error)
            is DocumentSaveResult.Saved -> {
                val applied = try {
                    switchMutex.withLock {
                        if (!isCurrentApplied(token) || !isBindingCurrent() || activeSessionInternal?.token != token) {
                            SessionSnapshotApplyResult.Stale
                        } else {
                            try {
                                callbacks.applyLoadedSnapshot(session, snapshot)
                                SessionSnapshotApplyResult.Applied
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                SessionSnapshotApplyResult.Failed(
                                    LocalRepositoryError.InvalidSnapshot(
                                        error.message ?: "remote snapshot replacement failed"
                                    )
                                )
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) {
                        restoreSnapshotWithinDocumentTransaction(token, previousDurable, previousLive)
                    }
                    throw cancelled
                }
                if (applied is SessionSnapshotApplyResult.Applied) applied
                else {
                    val restored = withContext(NonCancellable) {
                        restoreSnapshotWithinDocumentTransaction(token, previousDurable, previousLive)
                    }
                    if (restored is SessionSnapshotApplyResult.Failed) {
                        SessionSnapshotApplyResult.Failed(
                            LocalRepositoryError.InvalidSnapshot(
                                "remote snapshot apply failed and rollback failed: ${restored.error}"
                            )
                        )
                    } else {
                        applied
                    }
                }
            }
        }
    }

    /**
     * Restores durable canonical state first and then the live replacement
     * while the caller owns the shared document barrier.  This is deliberately
     * awaitable and never reacquires [transactionBarrier].
     */
    suspend fun restoreSnapshotWithinDocumentTransaction(
        token: DocumentSessionToken,
        durableSnapshot: DocumentSnapshotV1,
        liveSnapshot: DocumentSnapshotV1,
        isBindingCurrent: () -> Boolean = { true }
    ): SessionSnapshotApplyResult {
        val session = switchMutex.withLock {
            activeSessionInternal?.takeIf { it.token == token }
        } ?: return SessionSnapshotApplyResult.Stale
        autosave.cancelForSession(session)
        val saved = autosave.flushFrozenWithinDocumentTransaction(session, durableSnapshot)
        if (saved is DocumentSaveResult.Failed) return SessionSnapshotApplyResult.Failed(saved.error)
        return switchMutex.withLock {
            if (!isCurrentApplied(token) || !isBindingCurrent() || activeSessionInternal?.token != token) {
                SessionSnapshotApplyResult.Stale
            } else {
                try {
                    callbacks.applyLoadedSnapshot(session, liveSnapshot)
                    SessionSnapshotApplyResult.Applied
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    SessionSnapshotApplyResult.Failed(
                        LocalRepositoryError.InvalidSnapshot(
                            "rollback live replacement failed: ${error.message ?: error}"
                        )
                    )
                }
            }
        }
    }

    suspend fun switchTo(sourceUri: String): SwitchResult {
        var setup: Setup? = null
        try {
            setup = prepareSwitch(sourceUri)
            if (setup is Setup.Immediate) return setup.result

            val prepared = setup as Setup.Prepared
            val loadResult = try {
                prepared.load.deferred.await()
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                return transactionBarrier.withDocument(prepared.session.token.documentId) {
                    switchMutex.withLock { supersededOrCancelled(prepared) }
                }
            }

            return transactionBarrier.withDocument(prepared.session.token.documentId) {
                switchMutex.withLock {
                    if (!isCurrent(prepared.session.token)) {
                        SwitchResult.Superseded(sourceUri)
                    } else {
                        finishLoadLocked(prepared, loadResult)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            val prepared = setup as? Setup.Prepared
            if (prepared != null) {
                // If the caller is cancelled after the outgoing session has
                // been invalidated, finish cleanup and restore the last
                // complete outgoing snapshot before propagating cancellation.
                // Otherwise a cancelled caller could strand a blank target
                // session with no authoritative switch owner left to finish.
                withContext(NonCancellable) {
                    transactionBarrier.withDocument(prepared.session.token.documentId) {
                        switchMutex.withLock {
                            if (isCurrent(prepared.session.token)) {
                                cancelActiveLoadLocked(prepared.session)
                                rollbackAfterTargetFailureLocked(
                                    prepared,
                                    SwitchFailure(
                                        stage = SwitchFailureStage.CANCELLED,
                                        detail = "Document switch was cancelled"
                                    )
                                )
                            }
                        }
                    }
                }
            }
            // Caller cancellation is never silently converted to success. The
            // final outgoing flush, if it already started, is protected by its
            // own NonCancellable section; target work remains cancellable.
            throw cancelled
        } catch (error: Throwable) {
            val prepared = setup as? Setup.Prepared
            if (prepared != null) {
                return transactionBarrier.withDocument(prepared.session.token.documentId) {
                    switchMutex.withLock {
                        if (isCurrent(prepared.session.token)) {
                            rollbackAfterTargetFailureLocked(
                                prepared,
                                SwitchFailure(
                                    stage = SwitchFailureStage.TARGET_LOAD,
                                    detail = error.message ?: error::class.java.simpleName,
                                    cause = error
                                )
                            )
                        } else {
                            SwitchResult.Superseded(prepared.sourceUri)
                        }
                    }
                }
            }
            val failure = SwitchFailure(
                stage = SwitchFailureStage.TARGET_LOAD,
                detail = error.message ?: error::class.java.simpleName,
                cause = error
            )
            callbacks.onSwitchFailure(failure)
            return SwitchResult.Failed(failure, activeSessionInternal)
        }
    }

    /**
     * Acquires the document barrier before the switch mutex.  The outgoing
     * capture, autosave cancellation/join, and durable flush in
     * [prepareSwitchLocked] therefore cannot overlap Stage 4 remote apply.
     * A token re-check handles a transition that completed while the barrier
     * was being acquired without holding the switch mutex while awaiting it.
     */
    private suspend fun prepareSwitch(sourceUri: String): Setup {
        while (true) {
            val outgoingToken = activeSessionInternal?.token
            if (outgoingToken == null) {
                return switchMutex.withLock { prepareSwitchLocked(sourceUri) }
            }

            var retry = false
            val setup = transactionBarrier.withDocument(outgoingToken.documentId) {
                // Resolve while the document transaction is held, but do not
                // hold switchMutex while awaiting the Stage 4 cancellation
                // and join callback.  The callback may wait for a worker
                // which is itself waiting to enter a Stage 3 seam; awaiting
                // it while switchMutex is held would deadlock the cross-stage
                // transaction.
                val resolved = callbacks.resolveTarget(sourceUri)
                if (activeSessionInternal?.token != outgoingToken) {
                    retry = true
                    null
                } else if (resolved is TargetResolution.Resolved &&
                    sameTarget(activeSessionInternal!!, resolved.target)
                ) {
                    switchMutex.withLock {
                        prepareSwitchLocked(sourceUri, resolved)
                    }
                } else if (resolved is TargetResolution.Failed) {
                    // A target that failed identity resolution cannot replace
                    // the current session. Do not cancel/join its live work
                    // merely because the failed target was inspected.
                    switchMutex.withLock {
                        if (activeSessionInternal?.token != outgoingToken) {
                            retry = true
                            null
                        } else {
                            prepareSwitchLocked(sourceUri, resolved)
                        }
                    }
                } else {
                    withContext(NonCancellable) {
                        callbacks.cancelAndJoinDocumentWork(activeSessionInternal!!)
                    }
                    switchMutex.withLock {
                        if (activeSessionInternal?.token != outgoingToken) {
                            retry = true
                            null
                        } else {
                            prepareSwitchLocked(
                                sourceUri,
                                resolved,
                                documentWorkAlreadyJoined = true
                            )
                        }
                    }
                }
            }
            if (!retry) return requireNotNull(setup)
        }
    }

    private sealed class Setup {
        data class Immediate(val result: SwitchResult) : Setup()
        data class Prepared(
            val sourceUri: String,
            val session: DocumentSession,
            val outgoing: DocumentSession?,
            val outgoingSnapshot: DocumentSnapshotV1?,
            val load: ActiveLoad
        ) : Setup()
    }

    private suspend fun prepareSwitchLocked(
        sourceUri: String,
        resolvedResolution: TargetResolution? = null,
        documentWorkAlreadyJoined: Boolean = false
    ): Setup {
        val resolution = when (val resolved = resolvedResolution ?: callbacks.resolveTarget(sourceUri)) {
            is TargetResolution.Resolved -> resolved
            is TargetResolution.Failed -> {
                callbacks.onSwitchFailure(resolved.failure)
                return Setup.Immediate(SwitchResult.Failed(resolved.failure, activeSessionInternal))
            }
        }
        val target = resolution.target
        val current = activeSessionInternal
        var outgoingSession: DocumentSession? = current
        if (current != null && sameTarget(current, target)) {
            return Setup.Immediate(SwitchResult.AlreadyActive(current))
        }

        var outgoingSnapshot: DocumentSnapshotV1? = null
        if (current != null) {
            val provisionalLoad = synchronized(loadLock) {
                activeLoad?.takeIf { it.session.token == current.token }
            }
            // The old session loses authority before cancellation starts. Its
            // immutable snapshot is still capturable below, but any completion
            // racing this transaction is already stale—even before the new
            // target has finished resolving/loading.
            synchronized(invalidatedLock) { invalidatedTokens += current.token }
            val outgoingFailure = try {
                withContext(NonCancellable) {
                    cancelActiveLoadLocked(current)
                    autosave.cancelForSession(current)
                    cancelAndJoinDocumentJobs(current)
                    if (!documentWorkAlreadyJoined) {
                        callbacks.cancelAndJoinDocumentWork(current)
                    }

                    if (provisionalLoad != null) {
                        // This target never became live. Its UI state is only
                        // the cleared loading state, so saving it would replace
                        // the last durable target snapshot with an empty one.
                        outgoingSession = provisionalLoad.outgoing
                        outgoingSnapshot = provisionalLoad.outgoingSnapshot
                        callbacks.invalidateDocumentWork(current)
                        null
                    } else {
                        // This is the only capture used by the explicit switch
                        // flush. It happens while the old token still owns live
                        // state.
                        outgoingSnapshot = callbacks.captureSnapshot(current)
                        val saved = autosave.flushFrozenWithinDocumentTransaction(current, outgoingSnapshot!!)
                        if (saved is DocumentSaveResult.Failed) {
                            synchronized(invalidatedLock) { invalidatedTokens -= current.token }
                            callbacks.resumeDocumentBackgroundWork(current)
                            SwitchFailure(
                                stage = SwitchFailureStage.OUTGOING_FLUSH,
                                detail = "Outgoing snapshot was not durably committed",
                                repositoryError = saved.error
                            )
                        } else {
                            callbacks.invalidateDocumentWork(current)
                            null
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                synchronized(invalidatedLock) { invalidatedTokens -= current.token }
                callbacks.resumeDocumentBackgroundWork(current)
                throw cancelled
            } catch (error: Throwable) {
                synchronized(invalidatedLock) { invalidatedTokens -= current.token }
                callbacks.resumeDocumentBackgroundWork(current)
                SwitchFailure(
                    stage = SwitchFailureStage.OUTGOING_FLUSH,
                    detail = error.message ?: "Outgoing document could not be flushed",
                    cause = error
                )
            }
            if (outgoingFailure != null) {
                callbacks.onSwitchFailure(outgoingFailure)
                return Setup.Immediate(SwitchResult.Failed(outgoingFailure, current))
            }
        }

        var targetSession: DocumentSession? = null
        try {
            appliedSessionToken = null
            callbacks.clearDocumentState()
            generation += 1L
            val session = DocumentSession(
                target = target,
                token = DocumentSessionToken(
                    documentId = target.association.documentId,
                    sourceUri = target.association.source.sourceUri,
                    sourceFingerprint = target.association.sourceFingerprint,
                    generation = generation
                )
            )
            targetSession = session
            activeSessionInternal = session
            callbacks.establishSession(session)

            val deferred = coordinatorScope.async(start = CoroutineStart.LAZY) {
                callbacks.loadTarget(session)
            }
            val activeLoad = ActiveLoad(session, deferred, outgoingSession, outgoingSnapshot)
            synchronized(loadLock) { this.activeLoad = activeLoad }
            deferred.start()
            return Setup.Prepared(sourceUri, session, outgoingSession, outgoingSnapshot, activeLoad)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                rollbackSetupFailureLocked(
                    targetSession = targetSession,
                    outgoing = outgoingSession,
                    outgoingSnapshot = outgoingSnapshot,
                    failure = SwitchFailure(
                        stage = SwitchFailureStage.CANCELLED,
                        detail = "Document switch setup was cancelled",
                        cause = cancelled
                    )
                )
            }
            throw cancelled
        } catch (error: Throwable) {
            return rollbackSetupFailureLocked(
                targetSession = targetSession,
                outgoing = outgoingSession,
                outgoingSnapshot = outgoingSnapshot,
                failure = SwitchFailure(
                    stage = SwitchFailureStage.TARGET_APPLY,
                    detail = error.message ?: "Target session could not be established",
                    cause = error
                )
            )
        }
    }

    private suspend fun rollbackSetupFailureLocked(
        targetSession: DocumentSession?,
        outgoing: DocumentSession?,
        outgoingSnapshot: DocumentSnapshotV1?,
        failure: SwitchFailure
    ): Setup.Immediate {
        if (targetSession != null) {
            cancelActiveLoadLocked(targetSession)
            synchronized(invalidatedLock) { invalidatedTokens += targetSession.token }
            callbacks.invalidateDocumentWork(targetSession)
        }
        callbacks.onSwitchFailure(failure)
        runCatching { callbacks.clearDocumentState() }

        if (outgoing != null && outgoingSnapshot != null) {
            generation += 1L
            val restored = outgoing.copy(
                token = outgoing.token.copy(generation = generation)
            )
            activeSessionInternal = restored
            callbacks.establishSession(restored)
            callbacks.applyLoadedSnapshot(restored, outgoingSnapshot)
            appliedSessionToken = restored.token
            callbacks.resumeDocumentBackgroundWork(restored)
        } else {
            activeSessionInternal = null
            appliedSessionToken = null
        }
        return Setup.Immediate(SwitchResult.Failed(failure, activeSessionInternal))
    }

    private fun sameTarget(current: DocumentSession, target: ResolvedDocumentTarget): Boolean {
        val association = target.association
        return current.token.documentId == association.documentId &&
            current.token.sourceUri == association.source.sourceUri &&
            current.token.sourceFingerprint == association.sourceFingerprint
    }

    private suspend fun cancelActiveLoadLocked(session: DocumentSession) {
        val load = synchronized(loadLock) { activeLoad }
        if (load?.session?.token == session.token) {
            load.deferred.cancel()
            load.deferred.join()
            synchronized(loadLock) {
                if (activeLoad?.session?.token == session.token) activeLoad = null
            }
        }
    }

    private fun finishLoadLocked(prepared: Setup.Prepared, result: SessionLoadResult): SwitchResult {
        synchronized(loadLock) {
            if (activeLoad?.session?.token == prepared.session.token) activeLoad = null
        }
        return when (result) {
            is SessionLoadResult.Loaded -> {
                try {
                    if (!isCurrent(prepared.session.token)) return SwitchResult.Superseded(prepared.sourceUri)
                    callbacks.onTargetMetadata(prepared.session, result.pageCount)
                    callbacks.applyLoadedSnapshot(prepared.session, result.snapshot)
                    appliedSessionToken = prepared.session.token
                    if (result.recoveredFromPrevious) callbacks.onRecoveredSnapshot(prepared.session)
                    callbacks.startDocumentBackgroundWork(prepared.session)
                    SwitchResult.Switched(prepared.session, loadedSnapshot = true, recoveredFromPrevious = result.recoveredFromPrevious)
                } catch (error: Throwable) {
                    rollbackAfterTargetFailureLocked(
                        prepared,
                        SwitchFailure(
                            stage = SwitchFailureStage.TARGET_APPLY,
                            detail = error.message ?: "Target snapshot could not be applied",
                            cause = error
                        )
                    )
                }
            }
            is SessionLoadResult.Empty -> {
                callbacks.onTargetMetadata(prepared.session, result.pageCount)
                appliedSessionToken = prepared.session.token
                callbacks.startDocumentBackgroundWork(prepared.session)
                SwitchResult.Switched(prepared.session, loadedSnapshot = false, recoveredFromPrevious = false)
            }
            is SessionLoadResult.Failed -> rollbackAfterTargetFailureLocked(
                prepared,
                SwitchFailure(
                    stage = SwitchFailureStage.TARGET_LOAD,
                    detail = result.failure.detail,
                    repositoryError = result.failure.repositoryError,
                    cause = result.failure.cause
                )
            )
        }
    }

    private fun rollbackAfterTargetFailureLocked(
        prepared: Setup.Prepared,
        failure: SwitchFailure
    ): SwitchResult {
        synchronized(loadLock) {
            if (activeLoad?.session?.token == prepared.session.token) activeLoad = null
        }
        callbacks.onSwitchFailure(failure)
        synchronized(invalidatedLock) { invalidatedTokens += prepared.session.token }
        appliedSessionToken = null
        callbacks.invalidateDocumentWork(prepared.session)
        callbacks.clearDocumentState()
        val outgoing = prepared.outgoing
        val outgoingSnapshot = prepared.outgoingSnapshot
        if (outgoing != null && outgoingSnapshot != null) {
            generation += 1L
            val restored = outgoing.copy(
                token = outgoing.token.copy(generation = generation)
            )
            synchronized(invalidatedLock) { invalidatedTokens -= restored.token }
            activeSessionInternal = restored
            callbacks.establishSession(restored)
            callbacks.applyLoadedSnapshot(restored, outgoingSnapshot)
            appliedSessionToken = restored.token
            callbacks.resumeDocumentBackgroundWork(restored)
        } else {
            activeSessionInternal = null
            appliedSessionToken = null
        }
        return SwitchResult.Failed(failure, activeSessionInternal)
    }

    private fun supersededOrCancelled(prepared: Setup.Prepared): SwitchResult =
        if (isCurrent(prepared.session.token)) {
            val failure = SwitchFailure(
                stage = SwitchFailureStage.CANCELLED,
                detail = "Target load was cancelled",
            )
            rollbackAfterTargetFailureLocked(prepared, failure)
        } else {
            SwitchResult.Superseded(prepared.session.token.sourceUri)
        }

    fun close() {
        appliedSessionToken = null
        coordinatorScope.cancel()
    }
}
