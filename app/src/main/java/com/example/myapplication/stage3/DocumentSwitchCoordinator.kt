package com.example.myapplication.stage3

import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentDurableSnapshotState
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.LocalRepositoryError
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage7.Stage7PublicationFence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

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

/**
 * Identity of one coordinator owner.  It deliberately has reference
 * equality: a coordinator rebound for the same full session token receives a
 * different owner and cannot clean up the newer owner's resources.
 */
class DocumentWorkOwner internal constructor()

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

/** Opaque detached host state retained only by one outgoing switch transaction. */
interface DocumentSwitchRollbackState

/**
 * Detached ViewModel state retained across a new coordinator reopening the
 * identical verified document. It is recovery evidence only: a failed initial
 * load may restore it, but it does not become an applied session by itself.
 */
data class InitialDocumentRetainedState(
    val snapshot: DocumentSnapshotV1,
    val rollbackState: DocumentSwitchRollbackState? = null
)

/**
 * Narrow host boundary between the transaction controller and Android/Compose
 * state. The coordinator never reads ViewModel state during a save. The host
 * must capture a complete immutable snapshot before calling saveSnapshot.
 */
interface DocumentSessionCallbacks {
    suspend fun resolveTarget(sourceUri: String): TargetResolution

    fun captureSnapshot(session: DocumentSession): DocumentSnapshotV1

    /** Capture non-persisted state before the outgoing document is cleared. */
    fun captureSwitchRollbackState(session: DocumentSession): DocumentSwitchRollbackState? = null

    /** Restore the exact outgoing snapshot and the state captured for that switch. */
    fun applySwitchRollbackSnapshot(
        session: DocumentSession,
        snapshot: DocumentSnapshotV1,
        rollbackState: DocumentSwitchRollbackState?
    ) {
        applyRollbackSnapshot(session, snapshot)
    }

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

    /**
     * Captures the exact accepted current/previous repository slots when a
     * host can provide them.  The default is a compatibility view for older
     * lightweight hosts and represents only their current durable snapshot.
     */
    suspend fun captureDurableSnapshotState(session: DocumentSession): DocumentDurableSnapshotState =
        captureDurableSnapshot(session)?.let { snapshot ->
            DocumentDurableSnapshotState(
                current = com.example.myapplication.stage2.DurableSnapshotSlot(snapshot, null),
                previous = null
            )
        } ?: DocumentDurableSnapshotState(current = null, previous = null)

    /**
     * Restores an exact durable slot pair.  Existing Stage 2/3 hosts retain a
     * source-compatible save-based fallback; the Android repository host
     * overrides this for true current/previous/absent restoration.
     */
    suspend fun restoreDurableSnapshotState(
        session: DocumentSession,
        state: DocumentDurableSnapshotState
    ): DocumentSaveResult {
        val snapshot = state.current?.snapshot ?: state.previous?.snapshot
            ?: return DocumentSaveResult.Failed(
                LocalRepositoryError.InvalidSnapshot(
                    "exact durable rollback has no snapshot for a legacy host"
                )
            )
        return saveSnapshot(session, snapshot)
    }

    suspend fun saveSnapshot(
        session: DocumentSession,
        frozenSnapshot: DocumentSnapshotV1
    ): DocumentSaveResult

    /** Cancel and join cancellable work belonging to this session. */
    suspend fun cancelAndJoinDocumentWork(session: DocumentSession)

    /**
     * Owner-bound cancellation seam.  The default keeps existing lightweight
     * hosts source-compatible; Android hosts override it to retain the exact
     * coordinator identity through cleanup.
     */
    suspend fun cancelAndJoinDocumentWork(
        session: DocumentSession,
        owner: DocumentWorkOwner
    ) = cancelAndJoinDocumentWork(session)

    /** Close owner-scoped resources after all session jobs have joined. */
    suspend fun closeDocumentWork() = Unit

    /** Remove the old session's authority before live state is cleared. */
    fun invalidateDocumentWork(session: DocumentSession)

    /** Clear only document-scoped UI/cache state. */
    fun clearDocumentState()

    /** Clear canonical-empty owner state without invalidating an already-established host session. */
    fun clearRetainedStateForEmptyTarget(target: ResolvedDocumentTarget) {
        clearDocumentState()
    }

    /**
     * Capture recovery-only state before a new coordinator reopens an identical
     * verified target. Hosts that return non-null must also restore that state
     * through [restoreInitialRetainedState].
     */
    fun captureInitialRetainedState(target: ResolvedDocumentTarget): InitialDocumentRetainedState? = null

    /** Restore state captured by [captureInitialRetainedState] without publishing a session. */
    fun restoreInitialRetainedState(state: InitialDocumentRetainedState) = Unit

    /** New coordinators may retain a ViewModel owner only for an identical verified target. */
    fun clearDocumentStateForTarget(target: ResolvedDocumentTarget, initialSetup: Boolean) {
        clearDocumentState()
    }


    /** Make the token visible to the UI before a target load starts. */
    fun establishSession(session: DocumentSession)

    /** Resolve/migrate/load the target exactly once. Must not mutate live state. */
    suspend fun loadTarget(session: DocumentSession): SessionLoadResult

    /** Apply a fully validated snapshot only after the coordinator verifies the token. */
    fun applyLoadedSnapshot(session: DocumentSession, snapshot: DocumentSnapshotV1)

    /**
     * Apply a compensating snapshot after an accepted replacement failed.  A
     * production host may restore history that was invalidated by the failed
     * replacement; legacy hosts keep the source-compatible apply behavior.
     */
    fun applyRollbackSnapshot(session: DocumentSession, snapshot: DocumentSnapshotV1) =
        applyLoadedSnapshot(session, snapshot)

    /** Apply target metadata only after the target token has been revalidated. */
    fun onTargetMetadata(session: DocumentSession, pageCount: Int?) = Unit

    fun onRecoveredSnapshot(session: DocumentSession) = Unit

    fun onSwitchFailure(failure: SwitchFailure) = Unit

    /** Start OCR/render/search/Drive work only after target state is applied. */
    fun startDocumentBackgroundWork(session: DocumentSession) = Unit

    /** Owner-bound start seam for session-resource ownership. */
    fun startDocumentBackgroundWork(session: DocumentSession, owner: DocumentWorkOwner) =
        startDocumentBackgroundWork(session)

    /** Re-arm non-persistence document work after an outgoing flush failure. */
    fun resumeDocumentBackgroundWork(session: DocumentSession) = Unit

    /** Owner-bound resume seam for session-resource ownership. */
    fun resumeDocumentBackgroundWork(session: DocumentSession, owner: DocumentWorkOwner) =
        resumeDocumentBackgroundWork(session)

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
    private val transactionBarrier: DocumentTransactionBarrier = DocumentTransactionBarrier(),
    private val publicationFence: Stage7PublicationFence = Stage7PublicationFence.global,
    /** UI hosts sharing retained state must finish the previous host before any target access. */
    private val beforeSwitch: suspend () -> Unit = {}
) {
    /** Stable identity passed to every owner-bound callback from this instance. */
    val documentWorkOwner: DocumentWorkOwner = DocumentWorkOwner()

    private val coordinatorScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + coordinatorDispatcher)
    private val switchMutex = Mutex()
    private val loadLock = Any()
    private val jobsLock = Any()
    private val invalidatedLock = Any()
    private val documentJobs = mutableMapOf<DocumentSessionToken, MutableSet<Job>>()
    private val invalidatedTokens = mutableSetOf<DocumentSessionToken>()
    private val closedLock = Any()
    /** Setups are admitted before target resolution and drained before teardown. */
    private var activeSetups: Int = 0
    private var setupDrainWaiter: CompletableDeferred<Unit>? = null

    @Volatile
    private var activeSessionInternal: DocumentSession? = null
    @Volatile
    private var activeLoad: ActiveLoad? = null
    /** Published only after the target snapshot has been applied to memory. */
    @Volatile
    private var appliedSessionToken: DocumentSessionToken? = null
    @Volatile
    private var closed = false
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
        val outgoingSnapshot: DocumentSnapshotV1?,
        val outgoingRollbackState: DocumentSwitchRollbackState?,
        /** Recovery-only ViewModel state inherited across initial/provisional re-entry. */
        val initialRetainedState: InitialDocumentRetainedState?
    )

    fun currentSession(): DocumentSession? = activeSessionInternal

    fun isCurrent(token: DocumentSessionToken): Boolean =
        !closed && activeSessionInternal?.token == token && synchronized(invalidatedLock) {
            token !in invalidatedTokens
        }

    /**
     * Fences token invalidation against the cache's final publication section.
     * The fence is acquired before [invalidatedLock], matching the cache
     * order (publication fence -> visibility lock); no Main thread is blocked
     * because the fence acquisition suspends when a worker is publishing.
     */
    private suspend fun invalidateToken(token: DocumentSessionToken) {
        publicationFence.withInvalidation {
            synchronized(invalidatedLock) { invalidatedTokens += token }
        }
    }

    private suspend fun restoreToken(token: DocumentSessionToken) {
        publicationFence.withInvalidation {
            synchronized(invalidatedLock) { invalidatedTokens -= token }
        }
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
        synchronized(closedLock) {
            if (closed) {
                return Job().also {
                    it.cancel(CancellationException("document coordinator is closed"))
                }
            }
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
        importCurrentSnapshotWithinDocumentTransaction(
            token = token,
            snapshot = snapshot,
            currentSourceFingerprint = currentSourceFingerprint,
            isBindingCurrent = isBindingCurrent
        )
    }

    /**
     * Import form for callers that already own [transactionBarrier].  The
     * caller can keep the same document transaction across legacy-photo
     * publication, canonical durable/live replacement, and photo commit;
     * reacquiring the per-document mutex here would deadlock that boundary.
     */
    suspend fun importCurrentSnapshotWithinDocumentTransaction(
        token: DocumentSessionToken,
        snapshot: DocumentSnapshotV1,
        currentSourceFingerprint: SourceFingerprint?,
        isBindingCurrent: () -> Boolean = { true }
    ): SessionSnapshotApplyResult {
        if (snapshot.source.sourceUri != token.sourceUri ||
            currentSourceFingerprint != token.sourceFingerprint
        ) {
            return SessionSnapshotApplyResult.Stale
        }
        return persistAndApplyCurrentSnapshotWithinDocumentTransaction(token, snapshot, isBindingCurrent)
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
                    callbacks.applyRollbackSnapshot(session, liveSnapshot)
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

    /**
     * Restores the exact durable current/previous pair first and then the
     * live snapshot while the caller owns the shared document barrier.  This
     * is the Stage 6 compensation seam; it deliberately does not route an
     * absent pair through ordinary save-back.
     */
    suspend fun restoreSnapshotStateWithinDocumentTransaction(
        token: DocumentSessionToken,
        durableState: DocumentDurableSnapshotState,
        liveSnapshot: DocumentSnapshotV1,
        isBindingCurrent: () -> Boolean = { true }
    ): SessionSnapshotApplyResult {
        val session = switchMutex.withLock {
            activeSessionInternal?.takeIf { it.token == token }
        } ?: return SessionSnapshotApplyResult.Stale
        autosave.cancelForSession(session)
        val saved = callbacks.restoreDurableSnapshotState(session, durableState)
        if (saved is DocumentSaveResult.Failed) return SessionSnapshotApplyResult.Failed(saved.error)
        return switchMutex.withLock {
            if (!isCurrentApplied(token) || !isBindingCurrent() || activeSessionInternal?.token != token) {
                SessionSnapshotApplyResult.Stale
            } else {
                try {
                    callbacks.applyRollbackSnapshot(session, liveSnapshot)
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
            if (!tryAdmitSetup()) return closedSwitchResult(sourceUri)
            try {
                currentCoroutineContext().ensureActive()
                beforeSwitch()
                currentCoroutineContext().ensureActive()
                setup = prepareSwitch(sourceUri)
            } finally {
                releaseSetup()
            }
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
            withOpenSetupMutation(currentCoroutineContext()) { callbacks.onSwitchFailure(failure) }
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
        if (closed) return Setup.Immediate(closedSwitchResult(sourceUri))
        while (true) {
            if (closed) return Setup.Immediate(closedSwitchResult(sourceUri))
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
                currentCoroutineContext().ensureActive()
                if (closed) {
                    Setup.Immediate(closedSwitchResult(sourceUri))
                } else if (activeSessionInternal?.token != outgoingToken) {
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
                        callbacks.cancelAndJoinDocumentWork(
                            activeSessionInternal!!,
                            documentWorkOwner
                        )
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
            val outgoingRollbackState: DocumentSwitchRollbackState?,
            val initialRetainedState: InitialDocumentRetainedState?,
            val load: ActiveLoad
        ) : Setup()
    }

    private suspend fun prepareSwitchLocked(
        sourceUri: String,
        resolvedResolution: TargetResolution? = null,
        documentWorkAlreadyJoined: Boolean = false
    ): Setup {
        currentCoroutineContext().ensureActive()
        val resolution = when (val resolved = resolvedResolution ?: callbacks.resolveTarget(sourceUri)) {
            is TargetResolution.Resolved -> {
                currentCoroutineContext().ensureActive()
                if (closed) return Setup.Immediate(closedSwitchResult(sourceUri))
                resolved
            }
            is TargetResolution.Failed -> {
                currentCoroutineContext().ensureActive()
                if (closed) return Setup.Immediate(closedSwitchResult(sourceUri))
                withOpenSetupMutation(currentCoroutineContext()) { callbacks.onSwitchFailure(resolved.failure) }
                return Setup.Immediate(SwitchResult.Failed(resolved.failure, activeSessionInternal))
            }
        }
        if (closed) return Setup.Immediate(closedSwitchResult(sourceUri))
        val target = resolution.target
        val current = activeSessionInternal
        var outgoingSession: DocumentSession? = current
        if (current != null && sameTarget(current, target)) {
            return Setup.Immediate(SwitchResult.AlreadyActive(current))
        }

        var outgoingSnapshot: DocumentSnapshotV1? = null
        var outgoingRollbackState: DocumentSwitchRollbackState? = null
        var initialRetainedState: InitialDocumentRetainedState? = null
        if (current != null) {
            val provisionalLoad = synchronized(loadLock) {
                activeLoad?.takeIf { it.session.token == current.token }
            }
            // The old session loses authority before cancellation starts. Its
            // immutable snapshot is still capturable below, but any completion
            // racing this transaction is already staleâ€”even before the new
            // target has finished resolving/loading.
            withContext(NonCancellable) { invalidateToken(current.token) }
            val outgoingFailure: SwitchFailure? = try {
                val result: SwitchFailure? = withContext(NonCancellable) {
                    cancelActiveLoadLocked(current)
                    autosave.cancelForSession(current)
                    cancelAndJoinDocumentJobs(current)
                    if (!documentWorkAlreadyJoined) {
                        callbacks.cancelAndJoinDocumentWork(current, documentWorkOwner)
                    }

                    if (provisionalLoad != null) {
                        // This target never became live. Its UI state is only
                        // the cleared loading state, so saving it would replace
                        // the last durable target snapshot with an empty one.
                        outgoingSession = provisionalLoad.outgoing
                        outgoingSnapshot = provisionalLoad.outgoingSnapshot
                        outgoingRollbackState = provisionalLoad.outgoingRollbackState
                        initialRetainedState = provisionalLoad.initialRetainedState
                        withOpenSetupMutation { callbacks.invalidateDocumentWork(current) }
                        null
                    } else {
                        // This is the only capture used by the explicit switch
                        // flush. It happens while the old token still owns live
                        // state.
                        outgoingSnapshot = callbacks.captureSnapshot(current)
                        outgoingRollbackState = callbacks.captureSwitchRollbackState(current)
                        val saved = autosave.flushFrozenWithinDocumentTransaction(current, outgoingSnapshot!!)
                        if (saved is DocumentSaveResult.Failed) {
                            restoreToken(current.token)
                            withOpenSetupMutation {
                                callbacks.resumeDocumentBackgroundWork(current, documentWorkOwner)
                            }
                            SwitchFailure(
                                stage = SwitchFailureStage.OUTGOING_FLUSH,
                                detail = "Outgoing snapshot was not durably committed",
                                repositoryError = saved.error
                            )
                        } else {
                            withOpenSetupMutation { callbacks.invalidateDocumentWork(current) }
                            null
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                result
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    restoreToken(current.token)
                    withOpenSetupMutation {
                        callbacks.resumeDocumentBackgroundWork(current, documentWorkOwner)
                    }
                }
                throw cancelled
            } catch (error: Throwable) {
                withContext(NonCancellable) { restoreToken(current.token) }
                withOpenSetupMutation {
                    callbacks.resumeDocumentBackgroundWork(current, documentWorkOwner)
                }
                SwitchFailure(
                    stage = SwitchFailureStage.OUTGOING_FLUSH,
                    detail = error.message ?: "Outgoing document could not be flushed",
                    cause = error
                )
            }
            if (closed) return Setup.Immediate(closedSwitchResult(sourceUri))
            if (outgoingFailure != null) {
                val failure = outgoingFailure
                currentCoroutineContext().ensureActive()
                withOpenSetupMutation(currentCoroutineContext()) { callbacks.onSwitchFailure(failure) }
                return Setup.Immediate(SwitchResult.Failed(failure, current))
            }
        }

        if (outgoingSession == null && initialRetainedState == null) {
            initialRetainedState = try {
                val captured = withOpenSetupMutation(currentCoroutineContext()) {
                    callbacks.captureInitialRetainedState(target)
                }
                if (captured == null && closed) {
                    return Setup.Immediate(closedSwitchResult(sourceUri))
                }
                captured
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (closed) return Setup.Immediate(closedSwitchResult(sourceUri))
                val failure = SwitchFailure(
                    stage = SwitchFailureStage.TARGET_APPLY,
                    detail = error.message ?: "Retained document state could not be captured",
                    cause = error
                )
                withOpenSetupMutation(currentCoroutineContext()) { callbacks.onSwitchFailure(failure) }
                return Setup.Immediate(SwitchResult.Failed(failure, null))
            }
        }

        var targetSession: DocumentSession? = null
        try {
            val session = withOpenSetupMutation(currentCoroutineContext()) {
                appliedSessionToken = null
                callbacks.clearDocumentStateForTarget(target, initialSetup = outgoingSession == null)
                generation += 1L
                DocumentSession(
                    target = target,
                    token = DocumentSessionToken(
                        documentId = target.association.documentId,
                        sourceUri = target.association.source.sourceUri,
                        sourceFingerprint = target.association.sourceFingerprint,
                        generation = generation
                    )
                )
                    .also {
                        targetSession = it
                        activeSessionInternal = it
                        callbacks.establishSession(it)
                    }
            } ?: return Setup.Immediate(closedSwitchResult(sourceUri))

            currentCoroutineContext().ensureActive()
            if (closed) return Setup.Immediate(closedSwitchResult(sourceUri))

            val deferred = coordinatorScope.async(start = CoroutineStart.LAZY) {
                callbacks.loadTarget(session)
            }
            val activeLoad = ActiveLoad(
                session,
                deferred,
                outgoingSession,
                outgoingSnapshot,
                outgoingRollbackState,
                initialRetainedState
            )
            synchronized(loadLock) { this.activeLoad = activeLoad }
            if (closed) return Setup.Immediate(closedSwitchResult(sourceUri))
            deferred.start()
            return Setup.Prepared(
                sourceUri,
                session,
                outgoingSession,
                outgoingSnapshot,
                outgoingRollbackState,
                initialRetainedState,
                activeLoad
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                rollbackSetupFailureLocked(
                    targetSession = targetSession,
                    outgoing = outgoingSession,
                    outgoingSnapshot = outgoingSnapshot,
                    outgoingRollbackState = outgoingRollbackState,
                    initialRetainedState = initialRetainedState,
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
                outgoingRollbackState = outgoingRollbackState,
                initialRetainedState = initialRetainedState,
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
        outgoingRollbackState: DocumentSwitchRollbackState?,
        initialRetainedState: InitialDocumentRetainedState?,
        failure: SwitchFailure
    ): Setup.Immediate {
        if (closed) {
            if (targetSession != null) {
                cancelActiveLoadLocked(targetSession)
                withContext(NonCancellable) { invalidateToken(targetSession.token) }
            }
            return Setup.Immediate(closedSwitchResult(failure.detail))
        }
        if (targetSession != null) {
            cancelActiveLoadLocked(targetSession)
            withContext(NonCancellable) { invalidateToken(targetSession.token) }
        }
        val published = withOpenSetupMutation(currentCoroutineContext()) {
            targetSession?.let(callbacks::invalidateDocumentWork)
            callbacks.onSwitchFailure(failure)
            runCatching { callbacks.clearDocumentState() }

            if (outgoing != null && outgoingSnapshot != null) {
                generation += 1L
                val restored = outgoing.copy(
                    token = outgoing.token.copy(generation = generation)
                )
                // The restored session has a fresh generation. Only the
                // failed target token was invalidated above, so there is no
                // invalidation to undo here. Calling restoreToken would also
                // acquire the publication fence from this closed-state
                // critical section and invert the fence -> closedLock order.
                activeSessionInternal = restored
                callbacks.establishSession(restored)
                callbacks.applySwitchRollbackSnapshot(restored, outgoingSnapshot, outgoingRollbackState)
                appliedSessionToken = restored.token
                callbacks.resumeDocumentBackgroundWork(restored, documentWorkOwner)
            } else {
                activeSessionInternal = null
                appliedSessionToken = null
                initialRetainedState?.let(callbacks::restoreInitialRetainedState)
            }
            Unit
        }
        if (published == null) return Setup.Immediate(closedSwitchResult(failure.detail))
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

    private suspend fun finishLoadLocked(prepared: Setup.Prepared, result: SessionLoadResult): SwitchResult {
        currentCoroutineContext().ensureActive()
        synchronized(loadLock) {
            if (activeLoad?.session?.token == prepared.session.token) activeLoad = null
        }
        return when (result) {
            is SessionLoadResult.Loaded -> {
                try {
                    if (!isCurrent(prepared.session.token)) return SwitchResult.Superseded(prepared.sourceUri)
                    val published = withOpenSetupMutation(currentCoroutineContext()) {
                        callbacks.onTargetMetadata(prepared.session, result.pageCount)
                        callbacks.applyLoadedSnapshot(prepared.session, result.snapshot)
                        appliedSessionToken = prepared.session.token
                        if (result.recoveredFromPrevious) callbacks.onRecoveredSnapshot(prepared.session)
                        callbacks.startDocumentBackgroundWork(prepared.session, documentWorkOwner)
                    }
                    if (published == null) return SwitchResult.Superseded(prepared.sourceUri)
                    SwitchResult.Switched(prepared.session, loadedSnapshot = true, recoveredFromPrevious = result.recoveredFromPrevious)
                } catch (cancelled: CancellationException) {
                    throw cancelled
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
                // A retained ViewModel from Activity recreation is not the
                // canonical authority when the repository proves this target
                // empty. Clear the retained maps/history before publishing the
                // new empty session.
                val published = withOpenSetupMutation(currentCoroutineContext()) {
                    callbacks.clearRetainedStateForEmptyTarget(prepared.session.target)
                    callbacks.onTargetMetadata(prepared.session, result.pageCount)
                    appliedSessionToken = prepared.session.token
                    callbacks.startDocumentBackgroundWork(prepared.session, documentWorkOwner)
                }
                if (published == null) return SwitchResult.Superseded(prepared.sourceUri)
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

    private suspend fun rollbackAfterTargetFailureLocked(
        prepared: Setup.Prepared,
        failure: SwitchFailure
    ): SwitchResult {
        if (closed) return closedSwitchResult(prepared.sourceUri)
        synchronized(loadLock) {
            if (activeLoad?.session?.token == prepared.session.token) activeLoad = null
        }
        invalidateToken(prepared.session.token)
        val outgoing = prepared.outgoing
        val outgoingSnapshot = prepared.outgoingSnapshot
        val published = withOpenSetupMutation(currentCoroutineContext()) {
            callbacks.onSwitchFailure(failure)
            appliedSessionToken = null
            callbacks.invalidateDocumentWork(prepared.session)
            callbacks.clearDocumentState()
            if (outgoing != null && outgoingSnapshot != null) {
                generation += 1L
                val restored = outgoing.copy(
                    token = outgoing.token.copy(generation = generation)
                )
                activeSessionInternal = restored
                callbacks.establishSession(restored)
                callbacks.applySwitchRollbackSnapshot(restored, outgoingSnapshot, prepared.outgoingRollbackState)
                appliedSessionToken = restored.token
                callbacks.resumeDocumentBackgroundWork(restored, documentWorkOwner)
            } else {
                activeSessionInternal = null
                appliedSessionToken = null
                prepared.initialRetainedState?.let(callbacks::restoreInitialRetainedState)
            }
            Unit
        }
        if (published == null) return closedSwitchResult(prepared.sourceUri)
        return SwitchResult.Failed(failure, activeSessionInternal)
    }

    private suspend fun supersededOrCancelled(prepared: Setup.Prepared): SwitchResult =
        if (isCurrent(prepared.session.token)) {
            val failure = SwitchFailure(
                stage = SwitchFailureStage.CANCELLED,
                detail = "Target load was cancelled",
            )
            rollbackAfterTargetFailureLocked(prepared, failure)
        } else {
            SwitchResult.Superseded(prepared.session.token.sourceUri)
        }

    /**
     * Atomically fences every active token, then cancels/joins coordinator
     * work and owner resources before returning. This is the lifecycle path;
     * it is deliberately suspendable so teardown never blocks Main.
     */
    suspend fun closeAndJoin() {
        var firstFailure: Throwable? = null
        withContext(NonCancellable) {
            closeJoinMutex.withLock {
                if (teardownComplete) return@withLock

            // The publication fence is acquired before the coordinator state
            // locks. This is the terminal invalidation linearization point:
            // cache publication either seals before it or is rejected after
            // the token fence is visible.
            val session = publicationFence.withInvalidation {
                markClosedAndFenceTokens()
            }

            // A caller may be suspended in target resolution outside the
            // coordinator scope.  The closed fence makes that setup stale;
            // waiting here keeps teardown from closing callback-owned
            // resources while the admitted setup is still unwinding.
            coordinatorScope.cancel(CancellationException("document coordinator closed"))
            awaitAdmittedSetups()

            // Invalidate the owner-facing seam before any cancellation can
            // deliver a late result to Compose or the OCR cache.
            if (session != null) {
                try {
                    callbacks.invalidateDocumentWork(session)
                } catch (error: Throwable) {
                    firstFailure = error
                }
            }

            val loadJob = synchronized(loadLock) { activeLoad?.deferred }
            loadJob?.cancel(CancellationException("document coordinator closed"))
            val jobs = synchronized(jobsLock) {
                documentJobs.values.flatMap { it.toList() }.also { documentJobs.clear() }
            }
            jobs.forEach { it.cancel(CancellationException("document coordinator closed")) }
            jobs.forEach { job ->
                try {
                    job.join()
                } catch (error: Throwable) {
                    if (firstFailure == null) firstFailure = error
                    else firstFailure?.addSuppressed(error)
                }
            }
            try {
                loadJob?.join()
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
                else firstFailure?.addSuppressed(error)
            }
            synchronized(loadLock) { activeLoad = null }

            try {
                autosave.close()
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
                else firstFailure?.addSuppressed(error)
            }

            // The callback owns the document-scoped OCR/sync workers. It is
            // called only after their coordinator jobs have joined.
            if (session != null) {
                try {
                    callbacks.cancelAndJoinDocumentWork(session, documentWorkOwner)
                } catch (error: Throwable) {
                    if (firstFailure == null) firstFailure = error
                    else firstFailure?.addSuppressed(error)
                }
            }
            try {
                callbacks.closeDocumentWork()
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
                else firstFailure?.addSuppressed(error)
            }

            // Wait for a switch that was already inside the critical section,
            // then remove the closed session's live authority.
            try {
                switchMutex.withLock {
                    activeSessionInternal = null
                    appliedSessionToken = null
                }
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
                else firstFailure?.addSuppressed(error)
            }

            teardownComplete = true
            }
        }
        // Throw outside the NonCancellable context so a coroutine dispatcher
        // boundary cannot replace the aggregate with a recovered copy that
        // drops later suppressed cleanup failures.
        firstFailure?.let { throw it }
    }

    /** Compatibility non-suspending fence; it does not pretend to join. */
    fun close() {
        // This compatibility method cannot suspend. It takes the same
        // exclusive fence when available; lifecycle owners must use
        // closeAndJoin so a concurrent worker publication is awaited rather
        // than merely canceled.
        var fenced = false
        val session = publicationFence.tryWithInvalidation {
            fenced = true
            markClosedAndFenceTokens()
        }.let { result ->
            if (fenced) result
            else synchronized(closedLock) {
                closed = true
                activeSessionInternal
            }
        }
        session?.let { callbacks.invalidateDocumentWork(it) }
        appliedSessionToken = null
        coordinatorScope.cancel(CancellationException("document coordinator closed"))
    }

    /** Called only while [publicationFence]'s exclusive side is held. */
    private fun markClosedAndFenceTokens(): DocumentSession? = synchronized(closedLock) {
        closed = true
        val active = activeSessionInternal
        synchronized(invalidatedLock) {
            active?.token?.let(invalidatedTokens::add)
            synchronized(jobsLock) {
                documentJobs.keys.forEach(invalidatedTokens::add)
            }
            synchronized(loadLock) {
                activeLoad?.session?.token?.let(invalidatedTokens::add)
            }
        }
        active
    }

    private val closeJoinMutex = Mutex()
    @Volatile
    private var teardownComplete = false

    private fun tryAdmitSetup(): Boolean = synchronized(closedLock) {
        if (closed) return@synchronized false
        activeSetups += 1
        true
    }

    private fun releaseSetup() {
        val waiter = synchronized(closedLock) {
            check(activeSetups > 0) { "document setup admission underflow" }
            activeSetups -= 1
            if (activeSetups == 0) setupDrainWaiter.also { setupDrainWaiter = null } else null
        }
        waiter?.complete(Unit)
    }

    private suspend fun awaitAdmittedSetups() {
        val waiter = synchronized(closedLock) {
            if (activeSetups == 0) null
            else setupDrainWaiter ?: CompletableDeferred<Unit>().also { setupDrainWaiter = it }
        }
        waiter?.await()
    }

    private fun closedSwitchResult(sourceUri: String): SwitchResult =
        SwitchResult.Failed(
            SwitchFailure(
                stage = SwitchFailureStage.CANCELLED,
                detail = "Document coordinator is closed"
            ),
            activeSessionInternal
        )

    /** Immediate state callbacks must linearize with the teardown fence. */
    private fun <T> withOpenSetupMutation(block: () -> T): T? = synchronized(closedLock) {
        if (closed) null else block()
    }

    /** Also checks the caller while holding the same fence used by teardown. */
    private fun <T> withOpenSetupMutation(
        callerContext: CoroutineContext,
        block: () -> T
    ): T? = synchronized(closedLock) {
        if (closed) null else {
            callerContext.ensureActive()
            block()
        }
    }
}
