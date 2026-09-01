package com.example.myapplication.stage4

import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.LocalRepositoryError
import com.example.myapplication.stage3.DocumentSession
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentTransactionBarrier
import com.example.myapplication.stage5.PhotoCanonicalRecoveryException
import com.example.myapplication.stage5.PhotoCanonicalRecoveryMode
import com.example.myapplication.stage5.Stage5ValidationException
import com.example.myapplication.stage5.photoCanonicalIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** Every upload/check/apply route is represented by one of these reasons. */
enum class SyncReason {
    IMMEDIATE,
    DEBOUNCED,
    MANUAL,
    PERIODIC,
    PHOTO,
    IMPORT,
    LIFECYCLE,
    REMOTE_CHECK,
    REMOTE_ACCEPTANCE
}

sealed class SyncState {
    data object Idle : SyncState()
    data class Dirty(val generation: Long) : SyncState()
    data class Uploading(val generation: Long, val reason: SyncReason) : SyncState()
    data class Conflict(val cursor: RemoteCursor, val detail: String? = null) : SyncState()
    data class ApplyingRemote(val generation: Long, val cursor: RemoteCursor) : SyncState()
    data class Error(val error: SyncError) : SyncState()
}

data class SyncError(
    val kind: Kind,
    val detail: String,
    val cause: Throwable? = null
) {
    enum class Kind {
        METADATA,
        REMOTE,
        VALIDATION,
        LOCAL_PERSISTENCE,
        STALE_SESSION,
        CANCELED,
        RECOVERY,
        UNKNOWN
    }
}

data class SyncStatus(
    val scope: SyncScope,
    val state: SyncState,
    val acceptedCursor: RemoteCursor?,
    val conflictCursor: RemoteCursor?,
    val remoteReference: RemoteReference?,
    val pendingAdoption: RemoteAdoptionCandidate? = null
)

sealed class SyncOutcome {
    data class Uploaded(val generation: Long, val remote: RemoteSnapshotEnvelope) : SyncOutcome()
    data class RemoteConflict(val generation: Long, val remote: RemoteDocumentMetadata) : SyncOutcome()
    /** A same-source resource was found under another device-local DocumentId. */
    data class PendingAdoption(val candidate: RemoteAdoptionCandidate) : SyncOutcome()
    data class Adopted(
        val generation: Long,
        val remote: RemoteDocumentMetadata,
        val adoptedRemoteDocumentId: DocumentId
    ) : SyncOutcome()
    data class AppliedRemote(val generation: Long, val remote: RemoteSnapshotEnvelope) : SyncOutcome()
    data object NoRemoteState : SyncOutcome()
    data object RemoteUnchanged : SyncOutcome()
    data object BlockedByConflict : SyncOutcome()
    data object Stale : SyncOutcome()
    data object StaleSession : SyncOutcome()
    data object Canceled : SyncOutcome()
    data class Failed(val error: SyncError) : SyncOutcome()
}

/** Records only typed failures that a rollback/cleanup boundary is expected to handle. */
private suspend fun recordExpectedSyncFailure(
    failures: MutableList<Throwable>,
    operation: suspend () -> Unit
) {
    try {
        operation()
    } catch (cancelled: CancellationException) {
        failures += cancelled
    } catch (error: PhotoCanonicalRecoveryException) {
        failures += error
    } catch (error: Stage5ValidationException) {
        failures += error
    } catch (error: IOException) {
        failures += error
    } catch (error: SecurityException) {
        failures += error
    } catch (error: IllegalArgumentException) {
        failures += error
    } catch (error: IllegalStateException) {
        failures += error
    }
}

/** Immutable admission capability; epochs are never reused. */
data class SyncBinding(
    val scope: SyncScope,
    val token: DocumentSessionToken,
    val epoch: Long
) {
    init {
        require(scope.documentId == token.documentId) { "binding scope and session document differ" }
        require(epoch > 0L) { "binding epoch must be positive" }
    }
}

/** Result of the Stage 3 durable-before-memory replacement seam. */
sealed class SnapshotApplyResult {
    data object Applied : SnapshotApplyResult()
    data object Stale : SnapshotApplyResult()
    data class Failed(val error: LocalRepositoryError) : SnapshotApplyResult()
}

/** Bridge to the Stage 3 owner. */
interface SyncSessionBridge {
    fun currentSession(scope: SyncScope): DocumentSession?

    suspend fun captureSnapshot(session: DocumentSession): DocumentSnapshotV1?

    /** Capture form for a caller that already owns the shared document barrier. */
    suspend fun captureSnapshotWithinDocumentTransaction(session: DocumentSession): DocumentSnapshotV1? =
        captureSnapshot(session)

    /** Last known-good durable canonical snapshot used by reversible apply. */
    suspend fun captureDurableSnapshot(session: DocumentSession): DocumentSnapshotV1? = null

    /**
     * Existing Stage 3 save authority used by immediate and photo routes to
     * durably commit the already-frozen canonical snapshot before Drive work.
     */
    suspend fun persistSnapshot(session: DocumentSession, snapshot: DocumentSnapshotV1): DocumentSaveResult

    fun isCurrent(token: DocumentSessionToken): Boolean

    /** Stage 3 target has completed load and applied its snapshot. */
    fun isReady(token: DocumentSessionToken): Boolean = isCurrent(token)

    /** PHOTO uploads must opt in only when all referenced bytes are available. */
    fun hasRequiredPhotoContent(snapshot: DocumentSnapshotV1): Boolean = false

    /** Supplies the complete bytes for every photo referenced by a snapshot. */
    suspend fun capturePhotoContent(snapshot: DocumentSnapshotV1): Map<String, ByteArray> = emptyMap()

    /** Snapshot-aware upload admission keeps durable and live authorities separate. */
    suspend fun capturePhotoContentForAdmission(
        session: DocumentSession,
        currentDurableSnapshot: DocumentSnapshotV1,
        currentLiveSnapshot: DocumentSnapshotV1
    ): Map<String, ByteArray> {
        reconcilePhotoContent(session, currentDurableSnapshot, currentLiveSnapshot)
        return capturePhotoContent(currentLiveSnapshot)
    }

    /** Snapshot-aware required-photo check paired with the capture seam. */
    suspend fun hasRequiredPhotoContentForAdmission(
        session: DocumentSession,
        currentDurableSnapshot: DocumentSnapshotV1,
        currentLiveSnapshot: DocumentSnapshotV1
    ): Boolean {
        reconcilePhotoContent(session, currentDurableSnapshot, currentLiveSnapshot)
        return hasRequiredPhotoContent(currentLiveSnapshot)
    }

    /**
     * Persists remote photo bytes before the canonical snapshot is made live.
     * Android overrides this with its filesDir transaction; legacy fixtures
     * have no photo file store and safely retain the no-op default.
     */
    suspend fun persistPhotoContent(
        session: DocumentSession,
        remote: RemoteSnapshotEnvelope
    ): DocumentSaveResult = DocumentSaveResult.Saved(session.token.documentId)

    /**
     * Prepares a rollback-capable photo replacement.  The default preserves
     * the source-compatible legacy bridge contract; Android supplies a real
     * file transaction so bytes are not published before canonical apply.
     */
    suspend fun preparePhotoContent(
        session: DocumentSession,
        remote: RemoteSnapshotEnvelope
    ): PhotoContentPreparation = PhotoContentPreparation(
        persistPhotoContent(session, remote)
    )

    /**
     * Reconciles a photo journal left by a prior process before this
     * coordinator begins another canonical/photo transition. Android binds
     * this to the document photo root; legacy bridges retain the no-op default.
     */
    suspend fun reconcilePhotoContent(
        session: DocumentSession,
        currentDurableSnapshot: DocumentSnapshotV1,
        currentLiveSnapshot: DocumentSnapshotV1
    ) = Unit

    /** Runs after canonical/apply, metadata, and photo commit are authoritative. */
    suspend fun cleanupPhotoContentAfterCommit(
        session: DocumentSession,
        acceptedSnapshot: DocumentSnapshotV1
    ) = Unit

    /** Retained for legacy fixtures and source compatibility. */
    fun applySnapshotReplace(session: DocumentSession, snapshot: DocumentSnapshotV1)

    /**
     * Restores photo bytes while retaining their journal until the enclosing
     * canonical and metadata authorities have also been restored.
     */
    suspend fun afterPhotoRollbackBeforeCanonicalRestore() = Unit

    /** Production bridges override this with the Stage 3 save/apply seam. */
    suspend fun persistAndApplySnapshot(
        binding: SyncBinding,
        session: DocumentSession,
        snapshot: DocumentSnapshotV1
    ): SnapshotApplyResult {
        if (!isCurrent(binding.token)) return SnapshotApplyResult.Stale
        return when (val saved = persistSnapshot(session, snapshot)) {
            is DocumentSaveResult.Failed -> SnapshotApplyResult.Failed(saved.error)
            is DocumentSaveResult.Saved -> {
                if (!isCurrent(binding.token)) SnapshotApplyResult.Stale else try {
                    applySnapshotReplace(session, snapshot)
                    SnapshotApplyResult.Applied
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Stage5ValidationException) {
                    SnapshotApplyResult.Failed(
                        LocalRepositoryError.InvalidSnapshot(error.message ?: "snapshot replacement failed")
                    )
                } catch (error: IllegalArgumentException) {
                    SnapshotApplyResult.Failed(
                        LocalRepositoryError.InvalidSnapshot(error.message ?: "snapshot replacement failed")
                    )
                } catch (error: IOException) {
                    SnapshotApplyResult.Failed(
                        LocalRepositoryError.InvalidSnapshot(error.message ?: "snapshot replacement failed")
                    )
                } catch (error: SecurityException) {
                    SnapshotApplyResult.Failed(
                        LocalRepositoryError.InvalidSnapshot(error.message ?: "snapshot replacement failed")
                    )
                } catch (error: IllegalStateException) {
                    SnapshotApplyResult.Failed(
                        LocalRepositoryError.InvalidSnapshot(error.message ?: "snapshot replacement failed")
                    )
                }
            }
        }
    }

    /**
     * Stage 4 invokes this while holding the shared per-document transaction
     * barrier. A production Stage 3 bridge overrides it with its
     * no-reacquire save/apply seam; legacy fixtures safely inherit the normal
     * implementation.
     */
    suspend fun persistAndApplySnapshotWithinDocumentTransaction(
        binding: SyncBinding,
        session: DocumentSession,
        snapshot: DocumentSnapshotV1
    ): SnapshotApplyResult = persistAndApplySnapshot(binding, session, snapshot)

    /** Optional deterministic seam for cancellation/failure tests after apply. */
    suspend fun afterSnapshotAppliedWithinDocumentTransaction() = Unit

    /** Restores durable and live state while the shared barrier is held. */
    suspend fun restoreSnapshotWithinDocumentTransaction(
        binding: SyncBinding,
        session: DocumentSession,
        durableSnapshot: DocumentSnapshotV1,
        liveSnapshot: DocumentSnapshotV1
    ): SnapshotApplyResult {
        if (!isCurrent(binding.token)) return SnapshotApplyResult.Stale
        return when (val saved = persistSnapshot(session, durableSnapshot)) {
            is DocumentSaveResult.Failed -> SnapshotApplyResult.Failed(saved.error)
            is DocumentSaveResult.Saved -> {
                if (!isCurrent(binding.token)) SnapshotApplyResult.Stale else try {
                    applySnapshotReplace(session, liveSnapshot)
                    SnapshotApplyResult.Applied
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Stage5ValidationException) {
                    SnapshotApplyResult.Failed(
                        LocalRepositoryError.InvalidSnapshot(
                            "rollback live replacement failed: ${error.message ?: error}"
                        )
                    )
                } catch (error: IllegalArgumentException) {
                    SnapshotApplyResult.Failed(
                        LocalRepositoryError.InvalidSnapshot(
                            "rollback live replacement failed: ${error.message ?: error}"
                        )
                    )
                } catch (error: IOException) {
                    SnapshotApplyResult.Failed(
                        LocalRepositoryError.InvalidSnapshot(
                            "rollback live replacement failed: ${error.message ?: error}"
                        )
                    )
                } catch (error: SecurityException) {
                    SnapshotApplyResult.Failed(
                        LocalRepositoryError.InvalidSnapshot(
                            "rollback live replacement failed: ${error.message ?: error}"
                        )
                    )
                } catch (error: IllegalStateException) {
                    SnapshotApplyResult.Failed(
                        LocalRepositoryError.InvalidSnapshot(
                            "rollback live replacement failed: ${error.message ?: error}"
                        )
                    )
                }
            }
        }
    }

    fun onConflict(binding: SyncBinding, remote: RemoteDocumentMetadata) = Unit

    /** Exposes a persisted, user-consumable same-source adoption candidate. */
    fun onPendingAdoption(binding: SyncBinding, candidate: RemoteAdoptionCandidate) = Unit

    fun onError(binding: SyncBinding, error: SyncError) = Unit
}

/** A coordinator-owned periodic loop; no detached global timer is created. */
class PeriodicSyncHandle internal constructor(
    val binding: SyncBinding,
    internal val job: Job,
    /** Fences requests already admitted to the coordinator queue on stop. */
    internal val requestGate: AtomicBoolean
) {
    val scope: SyncScope get() = binding.scope
    val token: DocumentSessionToken get() = binding.token

    /** Lets lifecycle owners await a stopped periodic loop without owning its Job. */
    suspend fun join() = job.join()

    val isCancelled: Boolean get() = job.isCancelled
}

/**
 * Lifecycle/session-scoped serialized synchronization owner.
 *
 * Preparation can be superseded, but every final remote mutation, accepted
 * metadata transition, and remote acceptance holds one per-scope mutation
 * lease. The lease is acquired before the final mutation and is held through
 * the coordinator's accepted-metadata commit.
 */
class SyncCoordinator(
    private val gateway: DriveGateway,
    private val metadataStore: SyncMetadataStore,
    private val bridge: SyncSessionBridge,
    parentScope: CoroutineScope,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    private val documentTransactionBarrier: DocumentTransactionBarrier = DocumentTransactionBarrier(),
    /** Main/UI owner supplies the synchronously observable current full scope. */
    private val currentScopeProvider: (() -> SyncScope?)? = null
) {
    private val coordinatorJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val coordinatorScope = CoroutineScope(
        parentScope.coroutineContext + coordinatorJob + dispatcher
    )
    private val records = ConcurrentHashMap<SyncScope, ScopeRecord>()
    /** Remote/check state is serialized by durable DocumentId even while
     * metadata remains scoped by account and backup root. */
    private val documentSyncMutexes = ConcurrentHashMap<DocumentId, Mutex>()
    private val jobsByBinding = ConcurrentHashMap<SyncBinding, MutableSet<Job>>()
    private val periodicByBinding = ConcurrentHashMap<SyncBinding, PeriodicSyncHandle>()
    private val pendingDirtyDocuments = ConcurrentHashMap.newKeySet<DocumentId>()
    private val workerLock = Any()
    private val bindingLock = Any()
    private val nextBindingEpoch = AtomicLong(0L)
    private val activeBindings = mutableSetOf<SyncBinding>()
    private val invalidatedEpochs = mutableSetOf<Long>()
    /** Authoritative account/root epoch source; document entries remain independent. */
    private var currentAccountRoot: AccountRoot? = null
    private val currentScopeByDocument = mutableMapOf<DocumentId, SyncScope>()

    @Volatile
    private var closed = false

    private class ScopeRecord {
        val mutex = Mutex()
        val mutationLease = ScopeRemoteMutationLease()
        /** One FIFO worker owns the complete remote/state transaction for this scope. */
        val queue = Channel<QueuedRequest>(Channel.UNLIMITED)
        @Volatile var worker: Job? = null
        var loaded = false
        var metadata: SyncMetadata? = null
        @Volatile var generation: Long = 0L
        @Volatile var state: SyncState = SyncState.Idle
        var pendingUpload: PendingUpload? = null
        var durablePendingUpload: DurablePendingUpload? = null
    }

    private data class PendingUpload(
        val binding: SyncBinding,
        val reason: SyncReason,
        val generation: Long,
        val snapshot: DocumentSnapshotV1,
        val photoFiles: Map<String, ByteArray>,
        val expectedCursor: RemoteCursor?,
        val durablyPersisted: Boolean
    ) {
        fun toDurable(): DurablePendingUpload = DurablePendingUpload(
            reason = reason,
            sourceUri = binding.token.sourceUri,
            sourceFingerprint = binding.token.sourceFingerprint,
            generation = generation,
            expectedCursor = expectedCursor,
            snapshot = snapshot,
            photoFiles = photoFiles.mapValues { (_, bytes) -> bytes.copyOf() }
        )
    }

    /**
     * A request carries its immutable binding into the worker. The binding
     * epoch is checked again at execution time, so an item queued before a
     * sign-out/session replacement can never publish through a later epoch.
     */
    private data class QueuedRequest(
        val binding: SyncBinding,
        val result: CompletableDeferred<SyncOutcome>,
        val execute: suspend () -> SyncOutcome
    )

    private data class AccountRoot(val accountId: String, val backupRootId: String)

    private fun recordFor(scope: SyncScope): ScopeRecord =
        records.computeIfAbsent(scope) { ScopeRecord() }

    private fun DurablePendingUpload.rebase(
        binding: SyncBinding,
        generation: Long
    ): PendingUpload? {
        if (sourceUri != binding.token.sourceUri || sourceFingerprint != binding.token.sourceFingerprint) {
            return null
        }
        return PendingUpload(
            binding = binding,
            reason = reason,
            generation = generation,
            snapshot = snapshot,
            photoFiles = photoFiles.mapValues { (_, bytes) -> bytes.copyOf() },
            expectedCursor = expectedCursor,
            durablyPersisted = true
        )
    }

    private fun PendingUpload.rebase(
        binding: SyncBinding,
        generation: Long
    ): PendingUpload = copy(binding = binding, generation = generation)

    private fun documentSyncMutexFor(documentId: DocumentId): Mutex =
        documentSyncMutexes.computeIfAbsent(documentId) { Mutex() }

    /**
     * Atomically updates the authoritative account/root scope and fences every
     * older binding before a route can be admitted. A null scope is the
     * sign-out/no-ready-session fence. Different documents under one account
     * and root remain independently bindable.
     */
    fun updateCurrentScope(scope: SyncScope?) {
        // A stale closure must not be able to move the coordinator's
        // authoritative account/root pointer backward. The UI owner updates
        // this method synchronously when its state changes; route closures
        // are validated against the same source before they can fence work.
        if (currentScopeProvider != null && currentScopeProvider.invoke() != scope) return
        updateCurrentScopeInternal(scope)
    }

    /**
     * Synchronous owner-side sign-out/session-clear fence. This bypasses the
     * provider equality check because the caller invokes it immediately before
     * changing the provider's state.
     */
    fun invalidateCurrentScope() {
        updateCurrentScopeInternal(null)
    }

    private fun updateCurrentScopeInternal(scope: SyncScope?) {
        val toFence = synchronized(bindingLock) {
            if (scope == null) {
                currentAccountRoot = null
                currentScopeByDocument.clear()
                activeBindings.toList().also { old ->
                    old.forEach { invalidatedEpochs += it.epoch }
                    activeBindings.clear()
                }
            } else {
                val accountRoot = AccountRoot(scope.accountId, scope.backupRootId)
                val accountChanged =
                    (currentAccountRoot != null && currentAccountRoot != accountRoot) ||
                        (currentAccountRoot == null && activeBindings.any {
                            it.scope.accountId != scope.accountId ||
                                it.scope.backupRootId != scope.backupRootId
                        })
                currentAccountRoot = accountRoot
                val old = activeBindings.filter {
                    accountChanged ||
                        (it.scope.documentId == scope.documentId && it.scope != scope)
                }
                old.forEach { invalidatedEpochs += it.epoch }
                activeBindings.removeAll(old.toSet())
                currentScopeByDocument[scope.documentId] = scope
                old
            }
        }
        cancelBindingWork(toFence)
    }

    /** Synchronous exact fence used by Stage 3 switching before async cleanup. */
    fun fenceForSession(token: DocumentSessionToken) {
        val toFence = synchronized(bindingLock) {
            val old = activeBindings.filter { it.token == token }
            old.forEach { invalidatedEpochs += it.epoch }
            activeBindings.removeAll(old.toSet())
            old
        }
        cancelBindingWork(toFence)
    }

    fun bind(scope: SyncScope, token: DocumentSessionToken): SyncBinding? {
        if (closed || scope.documentId != token.documentId ||
            !bridge.isCurrent(token) || !bridge.isReady(token)
        ) return null
        if (currentScopeProvider != null && currentScopeProvider.invoke() != scope) return null
        // Admission itself is the synchronous rebind boundary when the UI
        // effect has not run yet; update the stored authority before creating
        // the new epoch rather than relying on delayed cleanup.
        if (currentScopeProvider != null) updateCurrentScope(scope)
        return synchronized(bindingLock) {
            if (closed || (currentAccountRoot != null &&
                    currentAccountRoot != AccountRoot(scope.accountId, scope.backupRootId))) return null
            val existing = activeBindings.firstOrNull { it.token == token && it.scope == scope }
            if (existing != null) return existing.takeIf { it.epoch !in invalidatedEpochs }
            val prior = activeBindings.filter {
                it.scope == scope || it.token.documentId == token.documentId
            }
            prior.forEach { invalidatedEpochs += it.epoch }
            activeBindings.removeAll(prior.toSet())
            currentScopeByDocument[scope.documentId] = scope
            SyncBinding(scope, token, nextBindingEpoch.incrementAndGet()).also {
                activeBindings += it
                if (pendingDirtyDocuments.contains(scope.documentId)) {
                    recordFor(scope).state = SyncState.Dirty(recordFor(scope).generation)
                }
            }
        }
    }

    /** Route admission with the caller's captured full current scope. */
    fun admit(binding: SyncBinding, currentScope: SyncScope?): Boolean {
        if (currentScopeProvider != null && currentScopeProvider.invoke() != currentScope) return false
        if (currentScope == null) {
            updateCurrentScope(null)
            return false
        }
        updateCurrentScope(currentScope)
        return isBindingCurrent(binding)
    }

    fun isBindingCurrent(binding: SyncBinding): Boolean {
        if (currentScopeProvider != null && currentScopeProvider.invoke() != binding.scope) return false
        return !closed && synchronized(bindingLock) {
            binding in activeBindings &&
                binding.epoch !in invalidatedEpochs &&
                (currentAccountRoot == null ||
                    currentAccountRoot == AccountRoot(binding.scope.accountId, binding.scope.backupRootId)) &&
                currentScopeByDocument[binding.scope.documentId] == binding.scope
        } && bridge.isCurrent(binding.token) && bridge.isReady(binding.token)
    }

    /** Nullable import admission preserves local-only offline import safely. */
    fun currentImportBindingOrNull(candidate: SyncBinding?, token: DocumentSessionToken): SyncBinding? {
        if (candidate == null) return null
        require(candidate.token == token && isBindingCurrent(candidate)) {
            "the synchronization scope changed during import"
        }
        return candidate
    }

    /** Legacy callers get a strict failure rather than a silent rebinding. */
    fun requireCurrentImportBinding(candidate: SyncBinding?, token: DocumentSessionToken): SyncBinding =
        requireNotNull(currentImportBindingOrNull(candidate, token)) {
            "a current Drive synchronization scope is required for this remote import"
        }

    fun status(scope: SyncScope): SyncStatus? = records[scope]?.let { record ->
        val metadata = record.metadata
        SyncStatus(
            scope,
            record.state,
            metadata?.acceptedCursor,
            metadata?.conflictCursor,
            metadata?.remoteReference,
            metadata?.pendingAdoption
        )
    }

    /** Serialized dirty admission used by mutation routes before debounce/upload. */
    fun markDirty(binding: SyncBinding): Deferred<SyncOutcome> = launchTracked(binding) {
        val record = recordFor(binding.scope)
        if (!isBindingCurrent(binding)) return@launchTracked SyncOutcome.StaleSession
        record.mutex.withLock {
            if (!isBindingCurrent(binding)) return@withLock SyncOutcome.StaleSession
            pendingDirtyDocuments.remove(binding.scope.documentId)
            record.state = record.metadata?.conflictCursor?.let {
                SyncState.Conflict(it, record.metadata?.conflictDetail)
            } ?: SyncState.Dirty(++record.generation)
            SyncOutcome.RemoteUnchanged
        }
    }

    /**
     * Local-only mutation admission while signed out/offline.  A token is
     * mandatory at this boundary: a document id alone cannot distinguish a
     * ready applied session from a provisional target whose live state is the
     * cleared placeholder.
     */
    fun markDirtyForDocument(documentId: DocumentId, token: DocumentSessionToken? = null): Boolean {
        if (token == null || token.documentId != documentId ||
            closed || !bridge.isCurrent(token) || !bridge.isReady(token)
        ) return false
        pendingDirtyDocuments += documentId
        records.filterKeys { it.documentId == documentId }.values.forEach { record ->
            record.state = record.metadata?.conflictCursor?.let {
                SyncState.Conflict(it, record.metadata?.conflictDetail)
            } ?: SyncState.Dirty(record.generation)
        }
        return true
    }

    /** Single upload entry point for immediate/debounced/manual/periodic/photo/import/lifecycle routes. */
    fun enqueueUpload(binding: SyncBinding, reason: SyncReason): Deferred<SyncOutcome> =
        enqueueUpload(binding, reason) { true }

    /** Internal admission used by the periodic owner to fence stopped ticks. */
    private fun enqueueUpload(
        binding: SyncBinding,
        reason: SyncReason,
        requestIsCurrent: () -> Boolean
    ): Deferred<SyncOutcome> = launchTracked(binding) {
        executeUpload(binding, reason, requestIsCurrent, frozenPending = null)
    }

    private fun enqueueFrozenUpload(pending: PendingUpload): Deferred<SyncOutcome> =
        launchTracked(pending.binding) {
            executeUpload(
                binding = pending.binding,
                reason = pending.reason,
                requestIsCurrent = { true },
                frozenPending = pending
            )
        }

    /** Single read/check entry point; it never creates a remote folder. */
    fun enqueueRemoteCheck(
        binding: SyncBinding,
        reason: SyncReason = SyncReason.REMOTE_CHECK
    ): Deferred<SyncOutcome> = enqueueRemoteCheck(binding, reason) { true }

    /** Internal admission used by the periodic owner to fence stopped ticks. */
    private fun enqueueRemoteCheck(
        binding: SyncBinding,
        reason: SyncReason,
        requestIsCurrent: () -> Boolean
    ): Deferred<SyncOutcome> = launchTracked(binding) {
        executeRemoteCheck(binding, reason, requestIsCurrent)
    }

    /** The only remote-acceptance entry point. */
    fun enqueueRemoteAcceptance(binding: SyncBinding): Deferred<SyncOutcome> =
        launchTracked(binding) { executeRemoteAcceptance(binding) }

    /** Explicit user-directed adoption/link operation; never auto-called by a check. */
    fun enqueueAdoptRemote(
        binding: SyncBinding,
        candidate: RemoteAdoptionCandidate
    ): Deferred<SyncOutcome> = launchTracked(binding) {
        executeAdoptRemote(binding, candidate)
    }

    /**
     * Lifecycle upload admission is conditional on the Stage 3 durable flush.
     * A failed or stale flush is reported through the same per-scope FIFO
     * worker, without touching remote state or accepted metadata.
     */
    suspend fun enqueueLifecycleUploadAfterDurableFlush(
        binding: SyncBinding,
        isSessionCurrent: () -> Boolean,
        flushCurrent: suspend () -> DocumentSaveResult?
    ): SyncOutcome {
        if (closed || !isBindingCurrent(binding) || !isSessionCurrent()) {
            return SyncOutcome.StaleSession
        }
        return when (val flushed = flushCurrent()) {
            is DocumentSaveResult.Saved -> {
                if (closed || !isBindingCurrent(binding) || !isSessionCurrent()) {
                    SyncOutcome.StaleSession
                } else {
                    enqueueUpload(binding, SyncReason.LIFECYCLE).await()
                }
            }
            is DocumentSaveResult.Failed -> {
                val error = SyncError(
                    kind = SyncError.Kind.LOCAL_PERSISTENCE,
                    detail = "lifecycle local flush failed: ${flushed.error}"
                )
                enqueueLocalPersistenceFailure(binding, error).await()
            }
            null -> SyncOutcome.StaleSession
        }
    }

    fun startPeriodic(binding: SyncBinding, intervalMillis: Long = 5 * 60 * 1000L): PeriodicSyncHandle? {
        return synchronized(workerLock) {
            if (closed || intervalMillis <= 0L || !isBindingCurrent(binding)) return@synchronized null
            periodicByBinding.remove(binding)?.let(::cancelPeriodicHandle)
            val requestGate = AtomicBoolean(true)
            val job = coordinatorScope.launch {
                try {
                    while (isActive && requestGate.get() && isBindingCurrent(binding) && !closed) {
                        delay(intervalMillis)
                        if (!isActive || !requestGate.get() || !isBindingCurrent(binding) || closed) break
                        val checked = awaitPeriodicRequest(
                            enqueueRemoteCheck(binding, SyncReason.PERIODIC, requestGate::get)
                        )
                        if (!requestGate.get() || !isBindingCurrent(binding) || closed) break
                        if (checked is SyncOutcome.NoRemoteState || checked is SyncOutcome.RemoteUnchanged) {
                            awaitPeriodicRequest(
                                enqueueUpload(binding, SyncReason.PERIODIC, requestGate::get)
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } finally {
                    requestGate.set(false)
                }
            }
            PeriodicSyncHandle(binding, job, requestGate).also { periodicByBinding[binding] = it }
        }
    }

    fun stopPeriodicForBinding(binding: SyncBinding) {
        periodicByBinding.remove(binding)?.let(::cancelPeriodicHandle)
    }

    /** Compatibility helper for Stage 3 teardown. */
    fun stopPeriodicForToken(token: DocumentSessionToken) {
        periodicByBinding.entries.filter { it.key.token == token }.forEach { (binding, _) ->
            periodicByBinding.remove(binding)?.let(::cancelPeriodicHandle)
        }
    }

    private suspend fun awaitPeriodicRequest(request: Deferred<SyncOutcome>): SyncOutcome = try {
        request.await()
    } catch (cancelled: CancellationException) {
        // A timer owns neither the worker nor the binding. Cancelling its
        // result makes a queued request skippable, while requestGate fences an
        // already-running request before its final remote mutation.
        request.cancel(cancelled)
        throw cancelled
    }

    private fun cancelPeriodicHandle(handle: PeriodicSyncHandle) {
        handle.requestGate.set(false)
        handle.job.cancel()
    }

    private fun cancelBindingWork(bindings: Collection<SyncBinding>) {
        if (bindings.isEmpty()) return
        bindings.forEach { binding ->
            periodicByBinding.remove(binding)?.let(::cancelPeriodicHandle)
        }
        val jobs = synchronized(jobsByBinding) {
            bindings.flatMap { binding -> jobsByBinding.remove(binding).orEmpty().toList() }
        }
        jobs.forEach { it.cancel() }
        val scopes = bindings.map { it.scope }.toSet()
        val protectedScopes = synchronized(bindingLock) { activeBindings.map { it.scope }.toSet() }
        records.filterKeys { it in scopes && it !in protectedScopes }
            .values
            .mapNotNull { it.worker }
            .distinct()
            .forEach { it.cancel() }
    }

    suspend fun cancelForSessionAndJoin(token: DocumentSessionToken) {
        // Remove only the exact binding epoch(s) for this Stage 3 token. A
        // later binding for the same DocumentId must never be canceled by an
        // older switch closure.
        val bindings = synchronized(bindingLock) { activeBindings.filter { it.token == token } }
        bindings.forEach(::fenceForBinding)
        val periodic = periodicByBinding.entries
            .filter { it.key.token == token }
            .mapNotNull { (binding, _) -> periodicByBinding.remove(binding) }
        val jobs = synchronized(jobsByBinding) {
            jobsByBinding.keys.filter { it.token == token }.flatMap { binding ->
                jobsByBinding.remove(binding).orEmpty().toList()
            }
        }
        val workers = records.entries
            .filter { entry -> bindings.any { it.scope == entry.key } }
            .mapNotNull { it.value.worker }
            .distinct()
        periodic.forEach(::cancelPeriodicHandle)
        jobs.forEach { it.cancel() }
        workers.forEach { it.cancel() }
        periodic.forEach { it.job.join() }
        jobs.forEach { it.join() }
        workers.forEach { it.join() }
        resetRecordsFor(bindings.map { it.scope }.toSet())
    }

    fun fenceForBinding(binding: SyncBinding) {
        val removed = synchronized(bindingLock) {
            if (activeBindings.remove(binding)) {
                invalidatedEpochs += binding.epoch
                listOf(binding)
            } else emptyList()
        }
        cancelBindingWork(removed)
    }

    suspend fun cancelForBindingAndJoin(binding: SyncBinding) {
        fenceForBinding(binding)
        val worker = records[binding.scope]?.worker
        worker?.join()
        resetRecordsFor(setOf(binding.scope))
    }

    private suspend fun resetRecordsFor(scopes: Set<SyncScope>) {
        scopes.forEach { scope ->
            records[scope]?.let { record ->
                record.mutex.withLock {
                    ++record.generation
                    record.pendingUpload = null
                    record.state = record.metadata?.conflictCursor?.let {
                        SyncState.Conflict(it, record.metadata?.conflictDetail)
                    } ?: record.metadata?.pendingUpload?.let {
                        SyncState.Dirty(it.generation)
                    } ?: SyncState.Idle
                }
            }
        }
    }

    private fun invalidateBindings(token: DocumentSessionToken) {
        synchronized(bindingLock) {
            val old = activeBindings.filter { it.token == token }
            old.forEach { invalidatedEpochs += it.epoch }
            activeBindings.removeAll(old.toSet())
        }
    }

    private fun launchTracked(binding: SyncBinding, block: suspend () -> SyncOutcome): Deferred<SyncOutcome> {
        val deferred = CompletableDeferred<SyncOutcome>()
        deferred.invokeOnCompletion {
            synchronized(jobsByBinding) {
                jobsByBinding[binding]?.remove(deferred)
                if (jobsByBinding[binding].isNullOrEmpty()) jobsByBinding.remove(binding)
            }
        }

        if (closed) {
            deferred.complete(SyncOutcome.Canceled)
            return deferred
        }
        if (!isBindingCurrent(binding)) {
            deferred.complete(SyncOutcome.StaleSession)
            return deferred
        }

        val record = recordFor(binding.scope)
        val queued = QueuedRequest(binding, deferred, block)
        synchronized(workerLock) {
            if (closed) {
                deferred.complete(SyncOutcome.Canceled)
                return@synchronized
            }
            if (!isBindingCurrent(binding)) {
                deferred.complete(SyncOutcome.StaleSession)
                return@synchronized
            }
            synchronized(jobsByBinding) {
                jobsByBinding.getOrPut(binding) { linkedSetOf() }.add(deferred)
            }
            ensureWorkerLocked(binding.scope, record)
            if (!record.queue.trySend(queued).isSuccess) {
                deferred.complete(SyncOutcome.Canceled)
            }
        }
        return deferred
    }

    private fun enqueueLocalPersistenceFailure(
        binding: SyncBinding,
        error: SyncError
    ): Deferred<SyncOutcome> = launchTracked(binding) {
        executeLocalPersistenceFailure(binding, error)
    }

    private suspend fun executeLocalPersistenceFailure(
        binding: SyncBinding,
        error: SyncError
    ): SyncOutcome {
        if (closed || !isBindingCurrent(binding)) return SyncOutcome.StaleSession
        val record = recordFor(binding.scope)
        val published = record.mutex.withLock {
            if (!isBindingCurrent(binding)) {
                false
            } else {
                record.state = SyncState.Error(error)
                true
            }
        }
        if (!published) return SyncOutcome.StaleSession
        bridge.onError(binding, error)
        return SyncOutcome.Failed(error)
    }

    /** Starts at most one FIFO worker for a full account/root/document scope. */
    private fun ensureWorkerLocked(scope: SyncScope, record: ScopeRecord) {
        val existing = record.worker
        if (existing != null && !existing.isCompleted) return
        record.worker = coordinatorScope.launch {
            runScopeWorker(scope, record)
        }
    }

    /**
     * The worker is the serialization boundary, not merely a dispatcher for
     * the gateway call. It does not dequeue the next item until the current
     * item has completed capture/read, remote transfer, metadata/cursor
     * commit, and any current-session callback.
     */
    private suspend fun runScopeWorker(scope: SyncScope, record: ScopeRecord) {
        val workerJob = coroutineContext[Job]
        try {
            for (request in record.queue) {
                if (request.result.isCancelled) continue
                try {
                    request.result.complete(request.execute())
                } catch (cancelled: CancellationException) {
                    request.result.cancel(cancelled)
                    throw cancelled
                } catch (error: Throwable) {
                    request.result.completeExceptionally(error)
                }
            }
        } finally {
            val cancellation = CancellationException("scope synchronization worker closed")
            while (true) {
                val pending = record.queue.tryReceive()
                if (!pending.isSuccess) break
                pending.getOrNull()?.result?.cancel(cancellation)
            }
            synchronized(workerLock) {
                if (record.worker === workerJob) record.worker = null
            }
        }
    }

    private suspend fun ensureLoadedLocked(scope: SyncScope, record: ScopeRecord): SyncError? {
        if (record.loaded) return null
        return when (val loaded = metadataStore.read(scope)) {
            is MetadataReadResult.Loaded -> {
                val metadata = loaded.metadata ?: SyncMetadata(scope = scope)
                record.metadata = metadata
                record.durablePendingUpload = metadata.pendingUpload
                record.loaded = true
                metadata.conflictCursor?.let { record.state = SyncState.Conflict(it, metadata.conflictDetail) }
                if (metadata.conflictCursor == null && metadata.pendingUpload != null) {
                    record.state = SyncState.Dirty(metadata.pendingUpload.generation)
                }
                null
            }
            is MetadataReadResult.Failed -> {
                val error = loaded.error.asSyncError()
                record.state = SyncState.Error(error)
                error
            }
        }
    }

    private suspend fun executeUpload(
        binding: SyncBinding,
        reason: SyncReason,
        requestIsCurrent: () -> Boolean,
        frozenPending: PendingUpload?
    ): SyncOutcome {
        if (!requestIsCurrent()) return SyncOutcome.Canceled
        return documentSyncMutexFor(binding.scope.documentId).withLock {
            if (!requestIsCurrent()) SyncOutcome.Canceled
            else executeUploadSerialized(binding, reason, requestIsCurrent, frozenPending)
        }
    }

    /**
     * Captures and, for immediate/photo routes, durably persists one frozen
     * canonical snapshot before a remote mutation is even admitted. The
     * record mutex deliberately is not held across the Stage 3 transaction;
     * that keeps lock order to document-sync mutex -> document barrier and
     * avoids waiting on local I/O while blocking metadata state transitions.
     */
    private suspend fun executeUploadSerialized(
        binding: SyncBinding,
        reason: SyncReason,
        requestIsCurrent: () -> Boolean,
        frozenPending: PendingUpload? = null
    ): SyncOutcome {
        if (!requestIsCurrent()) return SyncOutcome.Canceled
        if (closed || !isBindingCurrent(binding)) return SyncOutcome.StaleSession
        val scope = binding.scope
        val record = recordFor(scope)
        val preparation = record.mutex.withLock {
            if (!requestIsCurrent()) return@withLock UploadPreparation.Canceled
            ensureLoadedLocked(scope, record)?.let { return@withLock UploadPreparation.Failed(it) }
            val session = bridge.currentSession(scope)?.takeIf { it.token == binding.token }
                ?: return@withLock UploadPreparation.StaleSession
            if (!isBindingCurrent(binding)) return@withLock UploadPreparation.StaleSession
            UploadPreparation.Ready(session, ++record.generation, frozenPending)
        }
        val prepared = when (preparation) {
            UploadPreparation.Canceled -> return SyncOutcome.Canceled
            UploadPreparation.StaleSession -> return SyncOutcome.StaleSession
            is UploadPreparation.Failed -> return failUploadBeforeRemote(
                binding,
                record,
                preparation.error,
                requestIsCurrent
            )
            is UploadPreparation.Ready -> preparation
        }

        val (queuedPending, durablePending) = record.mutex.withLock {
            record.pendingUpload to record.durablePendingUpload
        }
        val pendingCandidate = prepared.frozenPending ?: queuedPending
            ?: durablePending?.rebase(binding, prepared.generation)
        if (prepared.frozenPending == null && queuedPending == null &&
            durablePending != null && pendingCandidate == null
        ) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(
                    SyncError.Kind.VALIDATION,
                    "durable pending upload does not match the current source identity"
                ),
                requestIsCurrent
            )
        }
        val effectivePending = try {
            pendingCandidate?.let { pending ->
                require(pending.binding.scope == binding.scope) {
                    "pending upload belongs to another synchronization scope"
                }
                require(pending.snapshot.source.sourceUri == binding.token.sourceUri) {
                    "pending upload source does not match the active session"
                }
                pending.rebase(binding, prepared.generation)
            }
        } catch (error: IllegalArgumentException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.VALIDATION, "pending upload identity validation failed", error),
                requestIsCurrent
            )
        } catch (error: IllegalStateException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.VALIDATION, "pending upload identity validation failed", error),
                requestIsCurrent
            )
        }
        if (pendingCandidate != null && effectivePending == null) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(
                    SyncError.Kind.VALIDATION,
                    "durable pending upload does not match the current source identity"
                ),
                requestIsCurrent
            )
        }
        val snapshot = try {
            effectivePending?.snapshot
                ?: (bridge.captureSnapshot(prepared.session) ?: return SyncOutcome.StaleSession)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Stage5ValidationException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.VALIDATION, "captured snapshot failed validation", error),
                requestIsCurrent
            )
        } catch (error: IOException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "captured snapshot failed", error),
                requestIsCurrent
            )
        } catch (error: SecurityException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "captured snapshot failed", error),
                requestIsCurrent
            )
        } catch (error: IllegalStateException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.VALIDATION, "captured snapshot failed", error),
                requestIsCurrent
            )
        }
        try {
            requireValidSnapshot(snapshot)
            require(snapshot.source.sourceUri == binding.token.sourceUri) {
                "captured snapshot source does not match the active session"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Stage5ValidationException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.VALIDATION, "captured snapshot validation failed", error),
                requestIsCurrent
            )
        } catch (error: IllegalArgumentException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.VALIDATION, "captured snapshot validation failed", error),
                requestIsCurrent
            )
        } catch (error: IllegalStateException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.VALIDATION, "captured snapshot validation failed", error),
                requestIsCurrent
            )
        }
        val currentDurableSnapshot = try {
            bridge.captureDurableSnapshot(prepared.session)
                ?: throw IllegalStateException("current durable snapshot is unavailable for photo admission")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PhotoCanonicalRecoveryException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.RECOVERY, "photo/canonical recovery is ambiguous", error),
                requestIsCurrent
            )
        } catch (error: Stage5ValidationException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.VALIDATION, "photo content capture failed validation", error),
                requestIsCurrent
            )
        } catch (error: IllegalArgumentException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.VALIDATION, "photo content capture failed validation", error),
                requestIsCurrent
            )
        } catch (error: IOException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "photo content capture failed", error),
                requestIsCurrent
            )
        } catch (error: SecurityException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "current durable snapshot could not be captured for photo admission", error),
                requestIsCurrent
            )
        } catch (error: IllegalStateException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "current durable snapshot could not be captured for photo admission", error),
                requestIsCurrent
            )
        }
        try {
            requireValidSnapshot(currentDurableSnapshot)
            require(currentDurableSnapshot.source.sourceUri == binding.token.sourceUri) {
                "current durable snapshot source does not match the active session"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Stage5ValidationException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "current durable snapshot validation failed for photo admission", error),
                requestIsCurrent
            )
        } catch (error: IllegalArgumentException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "current durable snapshot validation failed for photo admission", error),
                requestIsCurrent
            )
        } catch (error: IllegalStateException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "current durable snapshot validation failed for photo admission", error),
                requestIsCurrent
            )
        }
        val photoFiles = try {
            effectivePending?.photoFiles?.mapValues { (_, bytes) -> bytes.copyOf() }
                ?: bridge.capturePhotoContentForAdmission(
                    prepared.session,
                    currentDurableSnapshot,
                    snapshot
                )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PhotoCanonicalRecoveryException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.RECOVERY, "photo/canonical recovery is ambiguous", error),
                requestIsCurrent
            )
        } catch (error: Stage5ValidationException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.VALIDATION, "photo content capture failed validation", error),
                requestIsCurrent
            )
        } catch (error: IllegalArgumentException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.VALIDATION, "photo content capture failed validation", error),
                requestIsCurrent
            )
        } catch (error: IOException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "photo content capture failed", error),
                requestIsCurrent
            )
        } catch (error: SecurityException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "photo content capture failed", error),
                requestIsCurrent
            )
        } catch (error: IllegalStateException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "photo content capture failed", error),
                requestIsCurrent
            )
        }
        val hasRequiredPhotoContent = try {
            bridge.hasRequiredPhotoContentForAdmission(
                prepared.session,
                currentDurableSnapshot,
                snapshot
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PhotoCanonicalRecoveryException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.RECOVERY, "photo/canonical recovery is ambiguous", error),
                requestIsCurrent
            )
        } catch (error: Stage5ValidationException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.VALIDATION, "required photo admission failed validation", error),
                requestIsCurrent
            )
        } catch (error: IllegalArgumentException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.VALIDATION, "required photo admission failed validation", error),
                requestIsCurrent
            )
        } catch (error: IOException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "required photo admission failed", error),
                requestIsCurrent
            )
        } catch (error: SecurityException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "required photo admission failed", error),
                requestIsCurrent
            )
        } catch (error: IllegalStateException) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "required photo admission failed", error),
                requestIsCurrent
            )
        }
        val photoBytesValid = if (snapshotContainsPhotoBytes(snapshot)) {
            try {
                validatedPhotoFiles(snapshot, photoFiles)
                true
            } catch (_: Stage5ValidationException) {
                false
            }
        } else {
            true
        }
        if (snapshotContainsPhotoBytes(snapshot) && (!hasRequiredPhotoContent || !photoBytesValid)) {
            return failUploadBeforeRemote(
                binding,
                record,
                SyncError(
                    SyncError.Kind.VALIDATION,
                    "photo synchronization requires every referenced photo byte, not only JSON names"
                ),
                requestIsCurrent
            )
        }
        if (!requestIsCurrent()) return SyncOutcome.Canceled
        if (!isBindingCurrent(binding)) return SyncOutcome.StaleSession

        val frozenReplay = effectivePending != null
        if (reason.requiresDurableLocalPersistence() || frozenReplay) {
            val persisted = if (frozenReplay) {
                // Re-apply the preserved local snapshot only after its durable
                // Stage 3 write succeeds.  This is the explicit local side of
                // conflict resolution; it must not recapture the just-applied
                // remote memory.
                documentTransactionBarrier.withDocument(scope.documentId) {
                    bridge.persistAndApplySnapshotWithinDocumentTransaction(
                        binding,
                        prepared.session,
                        snapshot
                    ).toDocumentSaveResult(scope.documentId)
                }
            } else try {
                bridge.persistSnapshot(prepared.session, snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                return failUploadBeforeRemote(
                    binding,
                    record,
                    SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "local snapshot persistence failed", error),
                    requestIsCurrent
                )
            } catch (error: IOException) {
                return failUploadBeforeRemote(
                    binding,
                    record,
                    SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "local snapshot persistence failed", error),
                    requestIsCurrent
                )
            } catch (error: SecurityException) {
                return failUploadBeforeRemote(
                    binding,
                    record,
                    SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "local snapshot persistence failed", error),
                    requestIsCurrent
                )
            } catch (error: IllegalStateException) {
                return failUploadBeforeRemote(
                    binding,
                    record,
                    SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "local snapshot persistence failed", error),
                    requestIsCurrent
                )
            }
            when (persisted) {
                is DocumentSaveResult.Saved -> {
                    if (persisted.documentId != scope.documentId) {
                        return failUploadBeforeRemote(
                            binding,
                            record,
                            SyncError(
                                SyncError.Kind.LOCAL_PERSISTENCE,
                                "local snapshot persistence returned another document identity"
                            ),
                            requestIsCurrent
                        )
                    }
                }
                is DocumentSaveResult.Failed -> {
                    return failUploadBeforeRemote(
                        binding,
                        record,
                            SyncError(
                                SyncError.Kind.LOCAL_PERSISTENCE,
                                "local snapshot persistence failed: ${persisted.error}"
                            ),
                        requestIsCurrent
                    )
                }
            }
            if (reason == SyncReason.PHOTO || snapshotContainsPhotoBytes(snapshot)) {
                val postPersistCleanupFailure = try {
                    // Admission is read-only.  Once the canonical snapshot
                    // has been durably persisted/applied, take the shared
                    // document barrier again so the bridge can capture fresh
                    // durable/live authorities and collect only after that
                    // state is stable.
                    withContext(NonCancellable) {
                        documentTransactionBarrier.withDocument(scope.documentId) {
                            bridge.cleanupPhotoContentAfterCommit(prepared.session, snapshot)
                        }
                    }
                    null
                } catch (cancelled: CancellationException) {
                    cancelled
                } catch (error: PhotoCanonicalRecoveryException) {
                    error
                } catch (error: Stage5ValidationException) {
                    error
                } catch (error: IOException) {
                    error
                } catch (error: SecurityException) {
                    error
                } catch (error: IllegalArgumentException) {
                    error
                } catch (error: IllegalStateException) {
                    error
                }
                postPersistCleanupFailure?.let { error ->
                    return failUploadBeforeRemote(
                        binding,
                        record,
                        SyncError(
                            SyncError.Kind.RECOVERY,
                            "canonical photo persistence succeeded but post-persist cleanup is uncertain",
                            error
                        ),
                        requestIsCurrent
                    )
                }
            }
            if (!requestIsCurrent()) return SyncOutcome.Canceled
            if (!isBindingCurrent(binding)) return SyncOutcome.StaleSession
        }

        val begin = record.mutex.withLock {
            if (!requestIsCurrent()) return@withLock BeginResult.Canceled
            if (!isBindingCurrent(binding) || record.generation != prepared.generation) {
                return@withLock BeginResult.StaleSession
            }
            val pending = PendingUpload(
                binding = binding,
                reason = reason,
                generation = prepared.generation,
                snapshot = snapshot,
                photoFiles = photoFiles.mapValues { (_, bytes) -> bytes.copyOf() },
                expectedCursor = record.metadata?.acceptedCursor,
                durablyPersisted = reason.requiresDurableLocalPersistence() || frozenReplay
            )
            if (record.state is SyncState.Conflict || record.metadata?.conflictCursor != null) {
                val preserved = record.pendingUpload
                    ?: record.durablePendingUpload?.rebase(binding, prepared.generation)
                    ?: pending
                record.pendingUpload = preserved
                record.metadata?.conflictCursor?.let {
                    record.state = SyncState.Conflict(it, record.metadata?.conflictDetail)
                }
                return@withLock BeginResult.Blocked(preserved)
            }
            pendingDirtyDocuments.remove(scope.documentId)
            record.state = SyncState.Uploading(prepared.generation, reason)
            BeginResult.Ready(pending, effectivePending)
        }
        when (begin) {
            BeginResult.Canceled -> return SyncOutcome.Canceled
            BeginResult.StaleSession -> return SyncOutcome.StaleSession
            is BeginResult.Blocked -> {
                val preserved = preservePendingUploadDurably(
                    binding,
                    prepared.session,
                    record,
                    begin.pending,
                    requestIsCurrent
                )
                return preserved ?: SyncOutcome.BlockedByConflict
            }
            is BeginResult.Failed -> return failUploadBeforeRemote(binding, record, begin.error)
            is BeginResult.Ready -> Unit
        }
        val readyBegin = begin as BeginResult.Ready
        val upload = readyBegin.pending
        record.mutationLease.advance(upload.generation)
        if (!requestIsCurrent()) return SyncOutcome.Canceled
        if (!isBindingCurrent(binding)) return SyncOutcome.StaleSession
        val expectedCursor = record.mutex.withLock { record.metadata?.acceptedCursor }
        val request = UploadRequest(
            scope = scope,
            displayName = upload.snapshot.source.displayName ?: "document.pdf",
            snapshot = upload.snapshot,
            expectedCursor = expectedCursor,
            generation = upload.generation,
            mutationLease = record.mutationLease,
            isGenerationCurrent = {
                requestIsCurrent() && record.mutationLease.isGenerationCurrent(upload.generation) &&
                    isBindingCurrent(binding)
            },
            sourceFingerprint = binding.token.sourceFingerprint,
            photoFiles = photoFiles
        )
        val result = try {
            gateway.upload(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            UploadResult.Rejected(
                DriveFailure.Validation("upload adapter validation failed", error)
            )
        } catch (error: IOException) {
            UploadResult.Rejected(
                DriveFailure.Transfer("upload snapshot", error.message ?: error.toString(), error)
            )
        } catch (error: SecurityException) {
            UploadResult.Rejected(
                DriveFailure.Transfer("upload snapshot", error.message ?: error.toString(), error)
            )
        } catch (error: IllegalStateException) {
            UploadResult.Rejected(DriveFailure.Transfer("upload snapshot", error.message ?: error.toString(), error))
        }
        return try {
            when (result) {
                is UploadResult.Uploaded -> {
                    if (!requestIsCurrent()) {
                        SyncOutcome.Canceled
                    } else if (!isGenerationCurrent(binding, upload.generation)) {
                        SyncOutcome.Stale
                    } else {
                        val error = record.mutex.withLock {
                            if (!requestIsCurrent()) {
                                return@withLock SyncError(SyncError.Kind.CANCELED, "upload request was canceled")
                            }
                            if (!isGenerationCurrent(binding, upload.generation)) {
                                return@withLock SyncError(SyncError.Kind.STALE_SESSION, "upload generation is no longer current")
                            }
                            val old = record.metadata ?: SyncMetadata(scope = scope)
                            val next = old.copy(
                                remoteReference = result.remote.reference,
                                acceptedCursor = result.remote.cursor,
                                conflictCursor = null,
                                conflictDetail = null,
                                pendingUpload = if (readyBegin.replay != null) {
                                    null
                                } else {
                                    old.pendingUpload
                                }
                            )
                            when (val committed = metadataStore.write(next)) {
                                MetadataWriteResult.Committed -> {
                                    record.metadata = next
                                    if (readyBegin.replay != null) {
                                        record.pendingUpload = null
                                    }
                                    record.durablePendingUpload = next.pendingUpload
                                    record.state = if (record.pendingUpload == null) {
                                        SyncState.Idle
                                    } else {
                                        SyncState.Dirty(record.pendingUpload!!.generation)
                                    }
                                    null
                                }
                                is MetadataWriteResult.Failed -> {
                                    val syncError = committed.error.asSyncError()
                                    record.state = SyncState.Error(syncError)
                                    syncError
                                }
                            }
                        }
                        when (error?.kind) {
                            null -> SyncOutcome.Uploaded(upload.generation, result.remote)
                            SyncError.Kind.CANCELED -> SyncOutcome.Canceled
                            SyncError.Kind.STALE_SESSION -> SyncOutcome.Stale
                            else -> {
                                val failure = requireNotNull(error)
                                if (isBindingCurrent(binding)) bridge.onError(binding, failure)
                                SyncOutcome.Failed(failure)
                            }
                        }
                    }
                }
                is UploadResult.Conflict -> {
                    if (!requestIsCurrent()) {
                        SyncOutcome.Canceled
                    } else {
                        val preserved = upload.rebase(binding, upload.generation)
                        record.mutex.withLock {
                            if (requestIsCurrent() && isGenerationCurrent(binding, upload.generation)) {
                                record.pendingUpload = record.pendingUpload ?: preserved
                            }
                        }
                        preservePendingUploadDurably(
                            binding,
                            prepared.session,
                            record,
                            preserved,
                            requestIsCurrent
                        )?.let { return it }
                        handleRemoteConflict(binding, upload.generation, result.remote, requestIsCurrent)
                    }
                }
                is UploadResult.PendingAdoption -> {
                    pendingAdoption(binding, result.candidate)
                }
                is UploadResult.Rejected -> {
                    when {
                        !requestIsCurrent() -> SyncOutcome.Canceled
                        result.failure is DriveFailure.StaleGeneration || !isGenerationCurrent(binding, upload.generation) -> {
                            SyncOutcome.Stale
                        }
                        else -> {
                            val error = result.failure.asSyncError()
                            record.mutex.withLock { record.state = SyncState.Error(error) }
                            if (isBindingCurrent(binding)) bridge.onError(binding, error)
                            SyncOutcome.Failed(error)
                        }
                    }
                }
            }
        } finally {
            result.mutationSession?.close()
        }
    }

    /** Persist a frozen local conflict payload before returning a barrier result. */
    private suspend fun preservePendingUploadDurably(
        binding: SyncBinding,
        session: DocumentSession,
        record: ScopeRecord,
        pending: PendingUpload,
        requestIsCurrent: () -> Boolean
    ): SyncOutcome? {
        if (!requestIsCurrent() || !isBindingCurrent(binding)) return SyncOutcome.StaleSession
        val durablePending = if (pending.durablyPersisted) {
            pending
        } else {
            when (val saved = try {
                bridge.persistSnapshot(session, pending.snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                DocumentSaveResult.Failed(
                    LocalRepositoryError.IoFailure(
                        operation = "preserve pending local snapshot",
                        path = null,
                        detail = error.message ?: error.toString()
                    )
                )
            } catch (error: IOException) {
                DocumentSaveResult.Failed(
                    LocalRepositoryError.IoFailure(
                        operation = "preserve pending local snapshot",
                        path = null,
                        detail = error.message ?: error.toString()
                    )
                )
            } catch (error: SecurityException) {
                DocumentSaveResult.Failed(
                    LocalRepositoryError.IoFailure(
                        operation = "preserve pending local snapshot",
                        path = null,
                        detail = error.message ?: error.toString()
                    )
                )
            } catch (error: IllegalStateException) {
                DocumentSaveResult.Failed(
                    LocalRepositoryError.IoFailure(
                        operation = "preserve pending local snapshot",
                        path = null,
                        detail = error.message ?: error.toString()
                    )
                )
            }) {
                is DocumentSaveResult.Saved -> pending.copy(durablyPersisted = true)
                is DocumentSaveResult.Failed -> {
                    val error = SyncError(
                        SyncError.Kind.LOCAL_PERSISTENCE,
                        "pending local snapshot was not durably preserved: ${saved.error}"
                    )
                    record.mutex.withLock {
                        if (isGenerationCurrent(binding, pending.generation)) record.state = SyncState.Error(error)
                    }
                    if (isBindingCurrent(binding)) bridge.onError(binding, error)
                    return SyncOutcome.Failed(error)
                }
            }
        }
        if (!requestIsCurrent() || !isBindingCurrent(binding)) return SyncOutcome.StaleSession
        val durableRecord = durablePending.toDurable()
        val metadataResult = record.mutex.withLock {
            if (!requestIsCurrent() || !isBindingCurrent(binding)) {
                MetadataWriteResult.Failed(SyncMetadataError.Injected("pending upload", "binding is stale"))
            } else {
                val old = record.metadata ?: SyncMetadata(scope = binding.scope)
                val next = old.copy(pendingUpload = durableRecord)
                when (val written = metadataStore.write(next)) {
                    MetadataWriteResult.Committed -> {
                        record.metadata = next
                        record.durablePendingUpload = durableRecord
                        record.pendingUpload = durablePending
                        record.state = next.conflictCursor?.let {
                            SyncState.Conflict(it, next.conflictDetail)
                        } ?: SyncState.Dirty(durablePending.generation)
                        written
                    }
                    is MetadataWriteResult.Failed -> written
                }
            }
        }
        if (metadataResult is MetadataWriteResult.Failed) {
            val error = metadataResult.error.asSyncError()
            record.mutex.withLock { if (isBindingCurrent(binding)) record.state = SyncState.Error(error) }
            if (isBindingCurrent(binding)) bridge.onError(binding, error)
            return SyncOutcome.Failed(error)
        }
        return null
    }

    private suspend fun failUploadBeforeRemote(
        binding: SyncBinding,
        record: ScopeRecord,
        error: SyncError,
        requestIsCurrent: () -> Boolean = { true }
    ): SyncOutcome {
        if (!requestIsCurrent()) return SyncOutcome.Canceled
        val published = record.mutex.withLock {
            if (!requestIsCurrent() || !isBindingCurrent(binding)) {
                false
            } else {
                record.state = SyncState.Error(error)
                true
            }
        }
        if (!requestIsCurrent()) return SyncOutcome.Canceled
        if (!published) return SyncOutcome.StaleSession
        bridge.onError(binding, error)
        return SyncOutcome.Failed(error)
    }

    private suspend fun executeRemoteCheck(
        binding: SyncBinding,
        reason: SyncReason,
        requestIsCurrent: () -> Boolean
    ): SyncOutcome {
        if (!requestIsCurrent()) return SyncOutcome.Canceled
        return documentSyncMutexFor(binding.scope.documentId).withLock {
            if (!requestIsCurrent()) SyncOutcome.Canceled
            else executeRemoteCheckSerialized(binding, reason, requestIsCurrent)
        }
    }

    private suspend fun executeRemoteCheckSerialized(
        binding: SyncBinding,
        reason: SyncReason,
        requestIsCurrent: () -> Boolean
    ): SyncOutcome {
        if (!requestIsCurrent()) return SyncOutcome.Canceled
        if (closed || !isBindingCurrent(binding)) return SyncOutcome.StaleSession
        val scope = binding.scope
        val record = recordFor(scope)
        val preparation = record.mutex.withLock {
            if (!requestIsCurrent()) return@withLock RemoteCheckPreparation.Canceled
            ensureLoadedLocked(scope, record)?.let { return@withLock RemoteCheckPreparation.Failed(it) }
            if (!isBindingCurrent(binding)) return@withLock RemoteCheckPreparation.StaleSession
            RemoteCheckPreparation.Ready(++record.generation)
        }
        val generation = when (preparation) {
            RemoteCheckPreparation.Canceled -> return SyncOutcome.Canceled
            RemoteCheckPreparation.StaleSession -> return SyncOutcome.StaleSession
            is RemoteCheckPreparation.Failed -> {
                if (!requestIsCurrent()) return SyncOutcome.Canceled
                if (isBindingCurrent(binding)) bridge.onError(binding, preparation.error)
                return SyncOutcome.Failed(preparation.error)
            }
            is RemoteCheckPreparation.Ready -> preparation.generation
        }
        if (!requestIsCurrent()) return SyncOutcome.Canceled
        record.mutationLease.advance(generation)
        if (!requestIsCurrent()) return SyncOutcome.Canceled
        if (!isGenerationCurrent(binding, generation)) return SyncOutcome.Stale
        val remote = when (val found = gateway.find(scope, binding.token.sourceFingerprint)) {
            is RemoteLookup.Found -> found.metadata
            RemoteLookup.NotFound -> {
                if (!requestIsCurrent()) return SyncOutcome.Canceled
                if (isGenerationCurrent(binding, generation)) record.mutex.withLock {
                    if (requestIsCurrent() && record.generation == generation && record.state !is SyncState.Conflict) {
                        record.state = SyncState.Idle
                    }
                }
                return if (requestIsCurrent()) SyncOutcome.NoRemoteState else SyncOutcome.Canceled
            }
            is RemoteLookup.PendingAdoption -> {
                return pendingAdoption(binding, found.candidate)
            }
            is RemoteLookup.Failed -> {
                if (!requestIsCurrent()) return SyncOutcome.Canceled
                val error = found.failure.asSyncError()
                if (isGenerationCurrent(binding, generation)) {
                    record.mutex.withLock {
                        if (requestIsCurrent() && isGenerationCurrent(binding, generation)) {
                            record.state = SyncState.Error(error)
                        }
                    }
                    if (requestIsCurrent() && isBindingCurrent(binding)) bridge.onError(binding, error)
                }
                return if (requestIsCurrent()) SyncOutcome.Failed(error) else SyncOutcome.Canceled
            }
        }
        if (!requestIsCurrent()) return SyncOutcome.Canceled
        if (!isGenerationCurrent(binding, generation)) return SyncOutcome.Stale
        val accepted = record.mutex.withLock { record.metadata?.acceptedCursor }
        return if (accepted != null && accepted == remote.cursor) {
            record.mutex.withLock {
                if (requestIsCurrent() && record.generation == generation && record.state !is SyncState.Conflict) {
                    record.state = SyncState.Idle
                }
            }
            if (requestIsCurrent()) SyncOutcome.RemoteUnchanged else SyncOutcome.Canceled
        } else handleRemoteConflict(binding, generation, remote, requestIsCurrent)
    }

    private suspend fun handleRemoteConflict(
        binding: SyncBinding,
        generation: Long,
        remote: RemoteDocumentMetadata,
        requestIsCurrent: () -> Boolean = { true }
    ): SyncOutcome {
        val scope = binding.scope
        val record = recordFor(scope)
        if (!requestIsCurrent()) return SyncOutcome.Canceled
        if (!isGenerationCurrent(binding, generation)) return SyncOutcome.Stale
        val error = record.mutex.withLock {
            if (!requestIsCurrent()) return@withLock SyncError(
                SyncError.Kind.CANCELED,
                "remote conflict request was canceled"
            )
            if (!isGenerationCurrent(binding, generation)) return@withLock SyncError(
                SyncError.Kind.STALE_SESSION,
                "remote conflict belongs to an older generation"
            )
            val old = record.metadata ?: SyncMetadata(scope = scope)
            val pendingDurable = record.pendingUpload?.toDurable()
                ?: record.durablePendingUpload
            val next = old.copy(
                remoteReference = remote.reference,
                conflictCursor = remote.cursor,
                conflictDetail = "Remote revision ${remote.cursor.revision} is newer than the accepted cursor",
                pendingUpload = pendingDurable
            )
            when (val committed = metadataStore.write(next)) {
                MetadataWriteResult.Committed -> {
                    record.metadata = next
                    record.durablePendingUpload = next.pendingUpload
                    record.state = SyncState.Conflict(remote.cursor, next.conflictDetail)
                    null
                }
                is MetadataWriteResult.Failed -> {
                    val syncError = committed.error.asSyncError()
                    record.state = SyncState.Error(syncError)
                    syncError
                }
            }
        }
        if (error != null) {
            return when (error.kind) {
                SyncError.Kind.CANCELED -> SyncOutcome.Canceled
                SyncError.Kind.STALE_SESSION -> SyncOutcome.Stale
                else -> {
                    if (requestIsCurrent() && isBindingCurrent(binding)) bridge.onError(binding, error)
                    SyncOutcome.Failed(error)
                }
            }
        }
        if (!requestIsCurrent()) return SyncOutcome.Canceled
        if (!isBindingCurrent(binding)) return SyncOutcome.StaleSession
        bridge.onConflict(binding, remote)
        return SyncOutcome.RemoteConflict(generation, remote)
    }

    private suspend fun executeRemoteAcceptance(binding: SyncBinding): SyncOutcome =
        documentSyncMutexFor(binding.scope.documentId).withLock {
            executeRemoteAcceptanceSerialized(binding)
        }

    private suspend fun executeRemoteAcceptanceSerialized(binding: SyncBinding): SyncOutcome {
        if (closed || !isBindingCurrent(binding)) return SyncOutcome.StaleSession
        val scope = binding.scope
        val record = recordFor(scope)
        val generation = record.mutex.withLock {
            ensureLoadedLocked(scope, record)?.let { return@withLock -1L }
            if (!isBindingCurrent(binding)) return@withLock -1L
            ++record.generation
        }
        if (generation < 0L) return SyncOutcome.StaleSession
        record.mutationLease.advance(generation)
        val acceptanceSession = record.mutationLease.begin(generation) { isGenerationCurrent(binding, generation) }
            ?: return SyncOutcome.StaleSession
        return try {
            acceptanceSession.mutate {
                // The per-scope worker and mutation lease serialize remote
                // work; this shared per-document barrier additionally fences
                // Stage 3 switching/autosave from the full remote
                // find/download/validate/durable-persist/apply/metadata
                // acceptance transaction. Lock order is worker -> document
                // barrier -> Stage 3 switch mutex -> autosave save mutex.
                documentTransactionBarrier.withDocument(scope.documentId) {
                    performRemoteAcceptance(binding, generation)
                }
            }
        } finally {
            acceptanceSession.close()
        }
    }

    private suspend fun performRemoteAcceptance(binding: SyncBinding, generation: Long): SyncOutcome {
        val scope = binding.scope
        val record = recordFor(scope)
        val found = when (val lookup = gateway.find(scope, binding.token.sourceFingerprint)) {
            is RemoteLookup.Found -> lookup.metadata
            RemoteLookup.NotFound -> {
                val conflict = record.mutex.withLock { record.metadata?.conflictCursor }
                if (conflict != null) return failed(binding, SyncError(SyncError.Kind.REMOTE, "remote document is missing; it was not treated as an empty snapshot"))
                return SyncOutcome.NoRemoteState
            }
            is RemoteLookup.PendingAdoption -> return pendingAdoption(binding, lookup.candidate)
            is RemoteLookup.Failed -> return failed(binding, lookup.failure.asSyncError())
        }
        val expectedConflict = record.mutex.withLock { record.metadata?.conflictCursor }
        if (expectedConflict != null && expectedConflict != found.cursor) return handleRemoteConflict(binding, generation, found)
        if (!isGenerationCurrent(binding, generation)) return SyncOutcome.Stale
        record.mutex.withLock {
            if (record.generation == generation) record.state = SyncState.ApplyingRemote(generation, found.cursor)
        }
        val downloaded = when (val result = gateway.download(scope, found.reference, found.cursor)) {
            is DownloadResult.Downloaded -> result.remote
            DownloadResult.NotFound -> return failed(binding, SyncError(SyncError.Kind.REMOTE, "remote snapshot file disappeared during acceptance"))
            is DownloadResult.Failed -> return failed(binding, result.failure.asSyncError())
        }
        if (downloaded.cursor != found.cursor) return failed(binding, SyncError(SyncError.Kind.VALIDATION, "remote cursor changed during acceptance"))
        val session = bridge.currentSession(scope)?.takeIf { it.token == binding.token }
            ?: return SyncOutcome.StaleSession
        val localSnapshot = try {
            requireValidSnapshot(downloaded.snapshot)
            require(downloaded.scope == scope) { "remote snapshot scope mismatch" }
            if (binding.token.sourceFingerprint != null) {
                require(downloaded.sourceFingerprint == binding.token.sourceFingerprint) {
                    "remote snapshot source fingerprint is not verified for this local document"
                }
                // A controlled source may be exposed through a different
                // provider URI on another device. Preserve the local Stage 2
                // association while requiring the same verified bytes.
                downloaded.snapshot.copy(source = session.target.association.source)
            } else {
                require(downloaded.snapshot.source.sourceUri == binding.token.sourceUri) {
                    "remote snapshot source identity is unavailable for adoption"
                }
                downloaded.snapshot
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Stage5ValidationException) {
            return failed(binding, SyncError(SyncError.Kind.VALIDATION, "remote snapshot validation failed", error))
        } catch (error: IllegalArgumentException) {
            return failed(binding, SyncError(SyncError.Kind.VALIDATION, "remote snapshot validation failed", error))
        } catch (error: IOException) {
            return failed(binding, SyncError(SyncError.Kind.VALIDATION, "remote snapshot validation failed", error))
        } catch (error: SecurityException) {
            return failed(binding, SyncError(SyncError.Kind.VALIDATION, "remote snapshot validation failed", error))
        } catch (error: IllegalStateException) {
            return failed(binding, SyncError(SyncError.Kind.VALIDATION, "remote snapshot validation failed", error))
        }
        if (!isGenerationCurrent(binding, generation)) return SyncOutcome.StaleSession

        // Journal every pre-acceptance authority before publishing any
        // incoming photo or canonical bytes.  The journal is restored under
        // the same document barrier if apply, metadata, cancellation, or
        // photo rollback fails.
        val rollbackState = try {
            val previousLive = bridge.captureSnapshotWithinDocumentTransaction(session)
                ?: return SyncOutcome.StaleSession
            val previousDurable = bridge.captureDurableSnapshot(session)
                ?: throw IllegalStateException("previous durable snapshot is unavailable")
            requireValidSnapshot(previousLive)
            requireValidSnapshot(previousDurable)
            AcceptanceRollbackState(
                durableSnapshot = previousDurable,
                liveSnapshot = previousLive,
                metadata = record.mutex.withLock {
                    record.metadata ?: SyncMetadata(scope = scope)
                }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Stage5ValidationException) {
            return failed(
                binding,
                SyncError(
                    SyncError.Kind.LOCAL_PERSISTENCE,
                    "previous canonical state could not be journaled before remote acceptance",
                    error
                )
            )
        } catch (error: IllegalArgumentException) {
            return failed(
                binding,
                SyncError(
                    SyncError.Kind.LOCAL_PERSISTENCE,
                    "previous canonical state could not be journaled before remote acceptance",
                    error
                )
            )
        } catch (error: IOException) {
            return failed(
                binding,
                SyncError(
                    SyncError.Kind.LOCAL_PERSISTENCE,
                    "previous canonical state could not be journaled before remote acceptance",
                    error
                )
            )
        } catch (error: SecurityException) {
            return failed(
                binding,
                SyncError(
                    SyncError.Kind.LOCAL_PERSISTENCE,
                    "previous canonical state could not be journaled before remote acceptance",
                    error
                )
            )
        } catch (error: IllegalStateException) {
            return failed(
                binding,
                SyncError(
                    SyncError.Kind.LOCAL_PERSISTENCE,
                    "previous canonical state could not be journaled before remote acceptance",
                    error
                )
            )
        }

        try {
            bridge.reconcilePhotoContent(
                session,
                rollbackState.durableSnapshot,
                rollbackState.liveSnapshot
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PhotoCanonicalRecoveryException) {
            return failed(
                binding,
                SyncError(
                    SyncError.Kind.RECOVERY,
                    "photo/canonical recovery is ambiguous; remote acceptance was not started",
                    error
                )
            )
        } catch (error: Stage5ValidationException) {
            return failed(
                binding,
                SyncError(
                    SyncError.Kind.LOCAL_PERSISTENCE,
                    "photo/canonical recovery could not be completed",
                    error
                )
            )
        } catch (error: IOException) {
            return failed(
                binding,
                SyncError(
                    SyncError.Kind.LOCAL_PERSISTENCE,
                    "photo/canonical recovery could not be completed",
                    error
                )
            )
        } catch (error: SecurityException) {
            return failed(
                binding,
                SyncError(
                    SyncError.Kind.LOCAL_PERSISTENCE,
                    "photo/canonical recovery could not be completed",
                    error
                )
            )
        } catch (error: IllegalArgumentException) {
            return failed(
                binding,
                SyncError(
                    SyncError.Kind.LOCAL_PERSISTENCE,
                    "photo/canonical recovery could not be completed",
                    error
                )
            )
        } catch (error: IllegalStateException) {
            return failed(
                binding,
                SyncError(
                    SyncError.Kind.LOCAL_PERSISTENCE,
                    "photo/canonical recovery could not be completed",
                    error
                )
            )
        }

        val photoPreparation = try {
            bridge.preparePhotoContent(session, downloaded)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PhotoCanonicalRecoveryException) {
            PhotoContentPreparation(
                result = DocumentSaveResult.Failed(
                    LocalRepositoryError.IoFailure(
                        operation = "prepare remote photo content",
                        path = null,
                        detail = error.message ?: error.toString()
                    )
                )
            )
        } catch (error: Stage5ValidationException) {
            PhotoContentPreparation(
                result = DocumentSaveResult.Failed(
                    LocalRepositoryError.IoFailure(
                        operation = "prepare remote photo content",
                        path = null,
                        detail = error.message ?: error.toString()
                    )
                )
            )
        } catch (error: IOException) {
            PhotoContentPreparation(
                result = DocumentSaveResult.Failed(
                    LocalRepositoryError.IoFailure(
                        operation = "prepare remote photo content",
                        path = null,
                        detail = error.message ?: error.toString()
                    )
                )
            )
        } catch (error: SecurityException) {
            PhotoContentPreparation(
                result = DocumentSaveResult.Failed(
                    LocalRepositoryError.IoFailure(
                        operation = "prepare remote photo content",
                        path = null,
                        detail = error.message ?: error.toString()
                    )
                )
            )
        } catch (error: IllegalArgumentException) {
            PhotoContentPreparation(
                result = DocumentSaveResult.Failed(
                    LocalRepositoryError.IoFailure(
                        operation = "prepare remote photo content",
                        path = null,
                        detail = error.message ?: error.toString()
                    )
                )
            )
        } catch (error: IllegalStateException) {
            PhotoContentPreparation(
                result = DocumentSaveResult.Failed(
                    LocalRepositoryError.IoFailure(
                        operation = "prepare remote photo content",
                        path = null,
                        detail = error.message ?: error.toString()
                    )
                )
            )
        }
        when (val photoPrepared = photoPreparation.result) {
            is DocumentSaveResult.Saved -> Unit
            is DocumentSaveResult.Failed -> {
                val error = SyncError(
                    SyncError.Kind.LOCAL_PERSISTENCE,
                    "remote photo content was not prepared: ${photoPrepared.error}"
                )
                val finalError = rollbackRemoteAcceptance(
                    binding,
                    session,
                    generation,
                    rollbackState,
                    photoPreparation.transaction,
                    error,
                    publishError = true
                )
                return SyncOutcome.Failed(finalError)
            }
        }

        val photoTransaction = photoPreparation.transaction
        suspend fun failPhotoOperation(error: Throwable, detail: String): SyncOutcome {
            val finalError = rollbackRemoteAcceptance(
                binding,
                session,
                generation,
                rollbackState,
                photoTransaction,
                SyncError(
                    if (error is PhotoCanonicalRecoveryException) {
                        SyncError.Kind.RECOVERY
                    } else {
                        SyncError.Kind.LOCAL_PERSISTENCE
                    },
                    detail,
                    error
                ),
                publishError = true
            )
            return SyncOutcome.Failed(finalError)
        }
        if (!isGenerationCurrent(binding, generation)) {
            withContext(NonCancellable) { photoTransaction?.rollback() }
            return SyncOutcome.StaleSession
        }
        try {
            photoTransaction?.prepareCanonicalRecovery(
                photoCanonicalIdentity(scope.documentId, rollbackState.durableSnapshot),
                photoCanonicalIdentity(scope.documentId, rollbackState.liveSnapshot),
                rollbackState.liveSnapshot,
                photoCanonicalIdentity(scope.documentId, localSnapshot),
                PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE
            )
        } catch (cancelled: CancellationException) {
            rollbackRemoteAcceptance(
                binding,
                session,
                generation,
                rollbackState,
                photoTransaction,
                SyncError(SyncError.Kind.CANCELED, "photo canonical recovery preparation was canceled"),
                publishError = false
            )
            throw cancelled
        } catch (error: PhotoCanonicalRecoveryException) {
            return failPhotoOperation(error, "photo canonical recovery intent could not be recorded")
        } catch (error: Stage5ValidationException) {
            return failPhotoOperation(error, "photo canonical recovery intent could not be recorded")
        } catch (error: IOException) {
            return failPhotoOperation(error, "photo canonical recovery intent could not be recorded")
        } catch (error: SecurityException) {
            return failPhotoOperation(error, "photo canonical recovery intent could not be recorded")
        } catch (error: IllegalArgumentException) {
            return failPhotoOperation(error, "photo canonical recovery intent could not be recorded")
        } catch (error: IllegalStateException) {
            return failPhotoOperation(error, "photo canonical recovery intent could not be recorded")
        }
        try {
            photoTransaction?.publish()
        } catch (cancelled: CancellationException) {
            rollbackRemoteAcceptance(
                binding,
                session,
                generation,
                rollbackState,
                photoTransaction,
                SyncError(SyncError.Kind.CANCELED, "remote photo publication was canceled"),
                publishError = false
            )
            throw cancelled
        } catch (error: PhotoCanonicalRecoveryException) {
            return failPhotoOperation(error, "remote photo replacement could not be published atomically")
        } catch (error: Stage5ValidationException) {
            return failPhotoOperation(error, "remote photo replacement could not be published atomically")
        } catch (error: IOException) {
            return failPhotoOperation(error, "remote photo replacement could not be published atomically")
        } catch (error: SecurityException) {
            return failPhotoOperation(error, "remote photo replacement could not be published atomically")
        } catch (error: IllegalArgumentException) {
            return failPhotoOperation(error, "remote photo replacement could not be published atomically")
        } catch (error: IllegalStateException) {
            return failPhotoOperation(error, "remote photo replacement could not be published atomically")
        }

        // The production bridge performs Stage 3 association/fingerprint
        // validation, durable repository save, then applySnapshotReplace.
        val applied = try {
            bridge.persistAndApplySnapshotWithinDocumentTransaction(binding, session, localSnapshot)
        } catch (cancelled: CancellationException) {
            rollbackRemoteAcceptance(
                binding,
                session,
                generation,
                rollbackState,
                photoTransaction,
                SyncError(SyncError.Kind.CANCELED, "remote snapshot replacement was canceled"),
                publishError = false
            )
            throw cancelled
        } catch (error: PhotoCanonicalRecoveryException) {
            return failPhotoOperation(error, "remote snapshot was not durably persisted/applied")
        } catch (error: Stage5ValidationException) {
            return failPhotoOperation(error, "remote snapshot was not durably persisted/applied")
        } catch (error: IOException) {
            return failPhotoOperation(error, "remote snapshot was not durably persisted/applied")
        } catch (error: SecurityException) {
            return failPhotoOperation(error, "remote snapshot was not durably persisted/applied")
        } catch (error: IllegalArgumentException) {
            return failPhotoOperation(error, "remote snapshot was not durably persisted/applied")
        } catch (error: IllegalStateException) {
            return failPhotoOperation(error, "remote snapshot was not durably persisted/applied")
        }
        when (applied) {
            SnapshotApplyResult.Applied -> Unit
            SnapshotApplyResult.Stale -> {
                rollbackRemoteAcceptance(
                    binding,
                    session,
                    generation,
                    rollbackState,
                    photoTransaction,
                    SyncError(SyncError.Kind.STALE_SESSION, "remote snapshot replacement became stale"),
                    publishError = false
                )
                return SyncOutcome.StaleSession
            }
            is SnapshotApplyResult.Failed -> {
                val error = rollbackRemoteAcceptance(
                    binding,
                    session,
                    generation,
                    rollbackState,
                    photoTransaction,
                    SyncError(
                        SyncError.Kind.LOCAL_PERSISTENCE,
                        "remote snapshot was not durably persisted/applied: ${applied.error}"
                    ),
                    publishError = true
                )
                return SyncOutcome.Failed(error)
            }
        }
        try {
            bridge.afterSnapshotAppliedWithinDocumentTransaction()
        } catch (cancelled: CancellationException) {
            rollbackRemoteAcceptance(
                binding,
                session,
                generation,
                rollbackState,
                photoTransaction,
                SyncError(SyncError.Kind.CANCELED, "remote snapshot acceptance was canceled after apply"),
                publishError = false
            )
            throw cancelled
        } catch (error: PhotoCanonicalRecoveryException) {
            return failPhotoOperation(error, "post-apply acceptance hook failed")
        } catch (error: Stage5ValidationException) {
            return failPhotoOperation(error, "post-apply acceptance hook failed")
        } catch (error: IOException) {
            return failPhotoOperation(error, "post-apply acceptance hook failed")
        } catch (error: SecurityException) {
            return failPhotoOperation(error, "post-apply acceptance hook failed")
        } catch (error: IllegalArgumentException) {
            return failPhotoOperation(error, "post-apply acceptance hook failed")
        } catch (error: IllegalStateException) {
            return failPhotoOperation(error, "post-apply acceptance hook failed")
        }
        if (!isGenerationCurrent(binding, generation)) {
            rollbackRemoteAcceptance(
                binding,
                session,
                generation,
                rollbackState,
                photoTransaction,
                SyncError(SyncError.Kind.STALE_SESSION, "remote acceptance became stale after apply"),
                publishError = false
            )
            return SyncOutcome.StaleSession
        }
        var metadataPhaseCancellation: CancellationException? = null
        val acceptanceCommit = record.mutex.withLock {
            if (!isGenerationCurrent(binding, generation)) return@withLock null
            val old = record.metadata ?: SyncMetadata(scope = scope)
            val resume = record.pendingUpload
                ?: record.durablePendingUpload?.rebase(binding, generation)
            val pendingDurable = resume?.toDurable() ?: old.pendingUpload
            val next = old.copy(
                remoteReference = downloaded.reference,
                acceptedCursor = downloaded.cursor,
                conflictCursor = null,
                conflictDetail = null,
                pendingUpload = pendingDurable
            )
            when (val committed = metadataStore.write(next)) {
                MetadataWriteResult.Committed -> {
                    val phaseFailure = try {
                        // The phase marker is the durable proof that metadata
                        // crossed its authority boundary. It is written
                        // before photo commit and is therefore what restart
                        // uses to distinguish old/old rollback from a safe
                        // new/new finalization.
                        withContext(NonCancellable) {
                            photoTransaction?.markMetadataCommitted()
                        }
                        null
                    } catch (cancelled: CancellationException) {
                        metadataPhaseCancellation = cancelled
                        cancelled
                    } catch (error: PhotoCanonicalRecoveryException) {
                        error
                    } catch (error: Stage5ValidationException) {
                        error
                    } catch (error: IOException) {
                        error
                    } catch (error: SecurityException) {
                        error
                    } catch (error: IllegalArgumentException) {
                        error
                    } catch (error: IllegalStateException) {
                        error
                    }
                    if (phaseFailure != null) {
                        val error = SyncError(
                            SyncError.Kind.RECOVERY,
                            "remote acceptance metadata committed but its photo recovery phase was not recorded",
                            phaseFailure
                        )
                        record.state = SyncState.Error(error)
                        AcceptanceCommitResult(null, error)
                    } else {
                        record.metadata = next
                        record.durablePendingUpload = next.pendingUpload
                        record.pendingUpload = resume
                        record.state = if (resume == null) {
                            SyncState.Idle
                        } else {
                            SyncState.Dirty(resume.generation)
                        }
                        AcceptanceCommitResult(resume, null)
                    }
                }
                is MetadataWriteResult.Failed -> {
                    val error = committed.error.asSyncError()
                    record.state = SyncState.Error(error)
                    AcceptanceCommitResult(null, error)
                }
            }
        }
        if (acceptanceCommit == null) {
            rollbackRemoteAcceptance(
                binding,
                session,
                generation,
                rollbackState,
                photoTransaction,
                SyncError(SyncError.Kind.STALE_SESSION, "remote acceptance became stale before cursor commit"),
                publishError = false
            )
            return SyncOutcome.StaleSession
        }
        acceptanceCommit.error?.let {
            val finalError = rollbackRemoteAcceptance(
                binding,
                session,
                generation,
                rollbackState,
                photoTransaction,
                it,
                publishError = true
            )
            metadataPhaseCancellation?.let { cancelled -> throw cancelled }
            return SyncOutcome.Failed(finalError)
        }
        // The canonical local transaction and the photo transaction have both
        // succeeded.  Cleanup is normally best-effort; if a custom
        // transaction reports a real failure, reopen the rollback journal and
        // restore the pre-acceptance state before exposing failure.
        val cleanupFailure = try {
            withContext(NonCancellable) { photoTransaction?.commit() }
            null
        } catch (cancelled: CancellationException) {
            cancelled
        } catch (error: PhotoCanonicalRecoveryException) {
            error
        } catch (error: Stage5ValidationException) {
            error
        } catch (error: IOException) {
            error
        } catch (error: SecurityException) {
            error
        } catch (error: IllegalArgumentException) {
            error
        } catch (error: IllegalStateException) {
            error
        }
        cleanupFailure?.let { error ->
            if (photoTransaction?.hasAuthoritativeCommit() == true) {
                // The photo commit marker was already authoritative when
                // cleanup failed.  Rolling the canonical state back alone
                // would recreate the mixed new-canonical/old-photo window;
                // retain the bounded journal and surface recovery instead.
                return failed(
                    binding,
                    SyncError(
                        SyncError.Kind.RECOVERY,
                        "remote acceptance committed but photo cleanup evidence remains",
                        error
                    )
                )
            }
            val finalError = rollbackRemoteAcceptance(
                binding,
                session,
                generation,
                rollbackState,
                photoTransaction,
                SyncError(SyncError.Kind.LOCAL_PERSISTENCE, "remote photo transaction cleanup failed", error),
                publishError = true
            )
            if (error is CancellationException) throw error
            return SyncOutcome.Failed(finalError)
        }
        val postCommitCleanupFailure = try {
            withContext(NonCancellable) {
                bridge.cleanupPhotoContentAfterCommit(session, localSnapshot)
            }
            null
        } catch (cancelled: CancellationException) {
            // The authoritative commit has already happened; cancellation of
            // a cleanup bridge is recovery evidence, not a clean cancellation.
            cancelled
        } catch (error: PhotoCanonicalRecoveryException) {
            error
        } catch (error: Stage5ValidationException) {
            error
        } catch (error: IOException) {
            error
        } catch (error: SecurityException) {
            error
        } catch (error: IllegalArgumentException) {
            error
        } catch (error: IllegalStateException) {
            error
        }
        postCommitCleanupFailure?.let { error ->
            // Canonical state, metadata, and the photo commit marker are
            // already authoritative. Do not run the old-state rollback path;
            // report recovery evidence while leaving referenced/new files
            // intact for a retry of the bounded cleanup pass.
            return failed(
                binding,
                SyncError(
                    SyncError.Kind.RECOVERY,
                    "remote acceptance committed but post-commit photo cleanup is uncertain",
                    error
                )
            )
        }
        acceptanceCommit.pending?.let { pending ->
            if (isBindingCurrent(pending.binding)) enqueueFrozenUpload(pending).start()
        }
        return SyncOutcome.AppliedRemote(generation, downloaded)
    }

    /**
     * Restores every local authority after a remote acceptance aborts.  The
     * metadata store is written back as well as the in-memory record: a
     * process recreation must see the same accepted cursor/conflict state that
     * the live session exposes.  Any restoration failure is deliberately
     * promoted to RECOVERY instead of being hidden behind the original error.
     */
    private suspend fun rollbackRemoteAcceptance(
        binding: SyncBinding,
        session: DocumentSession,
        generation: Long,
        state: AcceptanceRollbackState,
        photoTransaction: PhotoContentTransaction?,
        original: SyncError,
        publishError: Boolean
    ): SyncError {
        val rollbackFailures = mutableListOf<Throwable>()
        var photoRollbackCompleted = false
        var rollbackMetadataIdentity: String? = null
        withContext(NonCancellable) {
            recordExpectedSyncFailure(rollbackFailures) {
                photoTransaction?.let { transaction ->
                    val identity = metadataStore.recoveryIdentity(state.metadata)
                    rollbackMetadataIdentity = identity
                    transaction.prepareCrossStoreRollback(identity)
                }
            }
            recordExpectedSyncFailure(rollbackFailures) {
                photoTransaction?.rollbackForCrossStoreCompensation()
                photoRollbackCompleted = true
            }
            if (photoRollbackCompleted) {
                // This hook exists only to make the process-boundary window
                // deterministic in tests. Evidence is still retained and the
                // remaining authorities are attempted below even if it fails.
                recordExpectedSyncFailure(rollbackFailures) {
                    bridge.afterPhotoRollbackBeforeCanonicalRestore()
                }
            }
            recordExpectedSyncFailure(rollbackFailures) {
                when (
                    val restored = bridge.restoreSnapshotWithinDocumentTransaction(
                        binding,
                        session,
                        state.durableSnapshot,
                        state.liveSnapshot
                    )
                ) {
                    SnapshotApplyResult.Applied -> Unit
                    SnapshotApplyResult.Stale -> error("document session became stale during rollback")
                    is SnapshotApplyResult.Failed -> error("canonical rollback failed: ${restored.error}")
                }
            }
            recordExpectedSyncFailure(rollbackFailures) {
                when (val restored = metadataStore.write(state.metadata)) {
                    MetadataWriteResult.Committed -> Unit
                    is MetadataWriteResult.Failed -> error("metadata rollback failed: ${restored.error}")
                }
            }
            if (rollbackFailures.isEmpty()) {
                recordExpectedSyncFailure(rollbackFailures) {
                    photoTransaction?.completeCrossStoreRollback(
                        rollbackMetadataIdentity ?: metadataStore.recoveryIdentity(state.metadata)
                    )
                }
            }
            recordExpectedSyncFailure(rollbackFailures) {
                // A failed rollback keeps evidence for restart, but it must
                // not keep a descriptor owned by the abandoned request alive
                // after the coordinator has recorded RECOVERY.
                photoTransaction?.releaseAfterFailure()
            }
        }

        val finalError = if (rollbackFailures.isEmpty()) {
            original
        } else {
            val primaryFailure = rollbackFailures.first()
            original.cause?.let { forwardFailure ->
                if (forwardFailure !== primaryFailure && primaryFailure.suppressed.none { it === forwardFailure }) {
                    primaryFailure.addSuppressed(forwardFailure)
                }
            }
            val recovery = SyncError(
                SyncError.Kind.RECOVERY,
                "remote acceptance failed and rollback was incomplete",
                primaryFailure
            )
            recovery.cause?.let { cause -> rollbackFailures.drop(1).forEach(cause::addSuppressed) }
            recovery
        }
        if (isBindingCurrent(binding)) {
            recordFor(binding.scope).mutex.withLock {
                if (isBindingCurrent(binding) && recordFor(binding.scope).generation == generation) {
                    val record = recordFor(binding.scope)
                    record.metadata = state.metadata
                    record.durablePendingUpload = state.metadata.pendingUpload
                    record.pendingUpload = state.metadata.pendingUpload?.rebase(binding, generation)
                    record.state = state.metadata.conflictCursor?.let {
                        SyncState.Conflict(it, state.metadata.conflictDetail)
                    } ?: SyncState.Error(finalError)
                }
            }
            if (publishError) bridge.onError(binding, finalError)
        }
        return finalError
    }

    private suspend fun failed(binding: SyncBinding, error: SyncError): SyncOutcome {
        if (isBindingCurrent(binding)) {
            val record = recordFor(binding.scope)
            record.mutex.withLock { if (isBindingCurrent(binding)) record.state = SyncState.Error(error) }
            bridge.onError(binding, error)
        }
        return SyncOutcome.Failed(error)
    }

    private suspend fun pendingAdoption(
        binding: SyncBinding,
        candidate: RemoteAdoptionCandidate
    ): SyncOutcome {
        if (candidate.accountId != binding.scope.accountId ||
            candidate.backupRootId != binding.scope.backupRootId ||
            binding.token.sourceFingerprint == null ||
            candidate.sourceFingerprint != binding.token.sourceFingerprint ||
            candidate.reference.appProperties[SYNC_DOCUMENT_ID_APP_PROPERTY] !=
                candidate.remoteDocumentId.value
        ) {
            return failed(
                binding,
                SyncError(
                    SyncError.Kind.VALIDATION,
                    "remote adoption candidate is not an exact account/root/source match"
                )
            )
        }
        val error = SyncError(
            SyncError.Kind.VALIDATION,
            "same-source remote document ${candidate.remoteDocumentId} requires explicit adoption"
        )
        val record = recordFor(binding.scope)
        val persisted = record.mutex.withLock {
            if (!isBindingCurrent(binding)) return@withLock false
            val old = record.metadata ?: SyncMetadata(scope = binding.scope)
            val next = old.copy(pendingAdoption = candidate)
            when (val written = metadataStore.write(next)) {
                MetadataWriteResult.Committed -> {
                    record.metadata = next
                    record.state = SyncState.Error(error)
                    true
                }
                is MetadataWriteResult.Failed -> {
                    record.state = SyncState.Error(written.error.asSyncError())
                    false
                }
            }
        }
        if (!persisted) {
        if (isBindingCurrent(binding)) bridge.onError(binding, error)
        return SyncOutcome.Failed(error)
    }
        if (isBindingCurrent(binding)) bridge.onPendingAdoption(binding, candidate)
        return SyncOutcome.PendingAdoption(candidate)
    }

    private suspend fun executeAdoptRemote(
        binding: SyncBinding,
        candidate: RemoteAdoptionCandidate
    ): SyncOutcome = documentSyncMutexFor(binding.scope.documentId).withLock {
        executeAdoptRemoteSerialized(binding, candidate)
    }

    /** Adoption shares the same per-document lock/order as upload/check/apply. */
    private suspend fun executeAdoptRemoteSerialized(
        binding: SyncBinding,
        candidate: RemoteAdoptionCandidate
    ): SyncOutcome {
        if (!isBindingCurrent(binding) || binding.token.sourceFingerprint == null) {
            return SyncOutcome.StaleSession
        }
        if (candidate.accountId != binding.scope.accountId ||
            candidate.backupRootId != binding.scope.backupRootId ||
            candidate.sourceFingerprint != binding.token.sourceFingerprint ||
            candidate.reference.appProperties[SYNC_DOCUMENT_ID_APP_PROPERTY] !=
                candidate.remoteDocumentId.value ||
            candidate.reference.appProperties[SYNC_SOURCE_FINGERPRINT_APP_PROPERTY] !=
                candidate.sourceFingerprint.toDriveProperty()
        ) {
            return failed(
                binding,
                SyncError(SyncError.Kind.VALIDATION, "selected adoption candidate does not match the active source")
            )
        }
        val record = recordFor(binding.scope)
        val generation = record.mutex.withLock {
            ensureLoadedLocked(binding.scope, record)?.let { return@withLock -1L }
            if (!isBindingCurrent(binding) || record.metadata?.pendingAdoption != candidate) {
                return@withLock -1L
            }
            ++record.generation
        }
        if (generation < 0L) return SyncOutcome.StaleSession
        record.mutationLease.advance(generation)
        val result = try {
            gateway.adopt(
                AdoptionRequest(
                    scope = binding.scope,
                    candidate = candidate,
                    localSourceFingerprint = requireNotNull(binding.token.sourceFingerprint),
                    generation = generation,
                    mutationLease = record.mutationLease,
                    isGenerationCurrent = { isGenerationCurrent(binding, generation) }
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            AdoptionResult.Rejected(
                DriveFailure.Validation("adoption adapter validation failed", error)
            )
        } catch (error: IOException) {
            AdoptionResult.Rejected(
                DriveFailure.Transfer("adopt remote document", error.message ?: error.toString(), error)
            )
        } catch (error: SecurityException) {
            AdoptionResult.Rejected(
                DriveFailure.Transfer("adopt remote document", error.message ?: error.toString(), error)
            )
        } catch (error: IllegalStateException) {
            AdoptionResult.Rejected(
                DriveFailure.Transfer("adopt remote document", error.message ?: error.toString(), error)
            )
        }
        return try {
            when (result) {
                is AdoptionResult.Adopted -> {
                    if (!isGenerationCurrent(binding, generation)) {
                        SyncOutcome.StaleSession
                    } else {
                        val commitError = record.mutex.withLock {
                            if (!isGenerationCurrent(binding, generation)) {
                                SyncError(SyncError.Kind.STALE_SESSION, "adoption became stale before metadata commit")
                            } else {
                                val old = record.metadata ?: SyncMetadata(scope = binding.scope)
                                val next = old.copy(
                                    remoteReference = result.remote.reference,
                                    acceptedCursor = result.remote.cursor,
                                    adoptedRemoteDocumentId = result.adoptedRemoteDocumentId,
                                    pendingAdoption = null,
                                    conflictCursor = null,
                                    conflictDetail = null
                                )
                                when (val written = metadataStore.write(next)) {
                                    MetadataWriteResult.Committed -> {
                                        record.metadata = next
                                        record.state = SyncState.Idle
                                        null
                                    }
                                    is MetadataWriteResult.Failed -> written.error.asSyncError()
                                }
                            }
                        }
                        if (commitError == null) {
                            SyncOutcome.Adopted(
                                generation,
                                result.remote,
                                result.adoptedRemoteDocumentId
                            )
                        } else {
                            if (isBindingCurrent(binding)) bridge.onError(binding, commitError)
                            if (commitError.kind == SyncError.Kind.STALE_SESSION) {
                                SyncOutcome.StaleSession
                            } else SyncOutcome.Failed(commitError)
                        }
                    }
                }
                is AdoptionResult.Rejected -> {
                    if (result.failure is DriveFailure.StaleGeneration ||
                        !isGenerationCurrent(binding, generation)
                    ) {
                        SyncOutcome.Stale
                    } else {
                        val error = result.failure.asSyncError()
                        record.mutex.withLock { record.state = SyncState.Error(error) }
                        if (isBindingCurrent(binding)) bridge.onError(binding, error)
                        SyncOutcome.Failed(error)
                    }
                }
            }
        } finally {
            result.mutationSession?.close()
        }
    }

    private fun isGenerationCurrent(binding: SyncBinding, generation: Long): Boolean =
        !closed && isBindingCurrent(binding) && recordFor(binding.scope).generation == generation

    /** Cancels and returns the actual completion job for lifecycle callers. */
    fun close(): Job {
        val toCancel = synchronized(workerLock) {
            if (closed) {
                null
            } else {
                closed = true
                synchronized(bindingLock) {
                    activeBindings.forEach { invalidatedEpochs += it.epoch }
                    activeBindings.clear()
                    currentAccountRoot = null
                    currentScopeByDocument.clear()
                }
                periodicByBinding.values.forEach(::cancelPeriodicHandle)
                periodicByBinding.clear()
                val tracked = synchronized(jobsByBinding) {
                    jobsByBinding.values.flatMap { it.toList() }.also { jobsByBinding.clear() }
                }
                val workers = records.values.mapNotNull { it.worker }.distinct()
                tracked to workers
            }
        }
        if (toCancel != null) {
            toCancel.first.forEach { it.cancel() }
            toCancel.second.forEach { it.cancel() }
            coordinatorJob.cancel()
        }
        return coordinatorJob
    }

    suspend fun closeAndJoin() = close().join()

    private sealed class UploadPreparation {
        data object Canceled : UploadPreparation()
        data object StaleSession : UploadPreparation()
        data class Failed(val error: SyncError) : UploadPreparation()
        data class Ready(
            val session: DocumentSession,
            val generation: Long,
            val frozenPending: PendingUpload?
        ) : UploadPreparation()
    }

    private sealed class BeginResult {
        data object Canceled : BeginResult()
        data object StaleSession : BeginResult()
        data class Blocked(val pending: PendingUpload) : BeginResult()
        data class Failed(val error: SyncError) : BeginResult()
        data class Ready(val pending: PendingUpload, val replay: PendingUpload?) : BeginResult()
    }

    private sealed class RemoteCheckPreparation {
        data object Canceled : RemoteCheckPreparation()
        data object StaleSession : RemoteCheckPreparation()
        data class Failed(val error: SyncError) : RemoteCheckPreparation()
        data class Ready(val generation: Long) : RemoteCheckPreparation()
    }

    private data class AcceptanceCommitResult(val pending: PendingUpload?, val error: SyncError?)

    private data class AcceptanceRollbackState(
        val durableSnapshot: DocumentSnapshotV1,
        val liveSnapshot: DocumentSnapshotV1,
        val metadata: SyncMetadata
    )

    /** Immediate mutations, including photo pin writes, cannot outrun local durability. */
    private fun SyncReason.requiresDurableLocalPersistence(): Boolean =
        this == SyncReason.IMMEDIATE || this == SyncReason.PHOTO

    private fun SnapshotApplyResult.toDocumentSaveResult(documentId: DocumentId): DocumentSaveResult =
        when (this) {
            SnapshotApplyResult.Applied -> DocumentSaveResult.Saved(documentId)
            SnapshotApplyResult.Stale -> DocumentSaveResult.Failed(
                LocalRepositoryError.InvalidSnapshot("the preserved local snapshot became stale")
            )
            is SnapshotApplyResult.Failed -> DocumentSaveResult.Failed(error)
        }

    private fun snapshotContainsPhotoBytes(snapshot: DocumentSnapshotV1): Boolean =
        snapshot.pages.values.any { page ->
            page.photoPins.any { pin -> pin.imageFileNames.isNotEmpty() }
        }

    private fun SyncMetadataError.asSyncError(): SyncError = SyncError(SyncError.Kind.METADATA, toString())

    private fun DriveFailure.asSyncError(): SyncError = when (this) {
        is DriveFailure.Validation -> SyncError(SyncError.Kind.VALIDATION, detail, cause)
        is DriveFailure.Conflict -> SyncError(SyncError.Kind.REMOTE, detail)
        is DriveFailure.Transfer -> SyncError(SyncError.Kind.REMOTE, "$operation: $detail", cause)
        is DriveFailure.NotFound -> SyncError(SyncError.Kind.REMOTE, detail)
        is DriveFailure.NotAuthenticated -> SyncError(SyncError.Kind.REMOTE, detail)
        is DriveFailure.Pagination -> SyncError(SyncError.Kind.REMOTE, detail, cause)
        is DriveFailure.StaleGeneration -> SyncError(SyncError.Kind.STALE_SESSION, "stale generation $generation")
        is DriveFailure.Unknown -> SyncError(SyncError.Kind.UNKNOWN, "$operation: $detail", cause)
    }
}

/**
 * Compose/ViewModel lifecycle finalizer. The caller must keep this suspend
 * path alive until it returns; [NonCancellable] ensures teardown can await
 * the coordinator's children even after the owner starts cancellation.
 */
suspend fun runNonCancellableFinalizers(vararg finalizers: suspend () -> Unit) {
    var firstFailure: Throwable? = null
    withContext(NonCancellable) {
        finalizers.forEach { finalizer ->
            try {
                finalizer()
            } catch (failure: Throwable) {
                if (firstFailure == null) {
                    firstFailure = failure
                } else {
                    val primary = firstFailure ?: return@forEach
                    if (failure !== primary && primary.suppressed.none { it === failure }) {
                        primary.addSuppressed(failure)
                    }
                }
            }
        }
    }
    firstFailure?.let { throw it }
}

suspend fun runSyncCoordinatorLifecycleFinalizer(
    coordinator: SyncCoordinator,
    afterCoordinatorClosed: suspend () -> Unit = {}
) {
    runNonCancellableFinalizers(
        { coordinator.closeAndJoin() },
        afterCoordinatorClosed
    )
}
