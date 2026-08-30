package com.example.myapplication.stage4

import com.example.myapplication.stage1.DocumentSnapshotV1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import com.example.myapplication.stage5.PhotoPathResolver
import com.example.myapplication.stage5.PhotoCanonicalRecoveryException
import com.example.myapplication.stage5.PhotoCanonicalIdentity
import com.example.myapplication.stage5.PhotoCanonicalRecoveryRecord
import com.example.myapplication.stage5.PhotoCanonicalRecoveryMode
import com.example.myapplication.stage5.PhotoCommitMarkerProbe
import com.example.myapplication.stage5.PhotoDocumentCriticalSections
import com.example.myapplication.stage5.PhotoTransactionJournalEntry
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.Stage5ValidationException
import com.example.myapplication.stage5.photoCanonicalIdentity
import com.example.myapplication.stage5.photoTransactionContentDigest
import com.example.myapplication.stage5.readBoundedBytes
import com.example.myapplication.stage5.validateSnapshot
import com.example.myapplication.stage5.validatePhotoBytes
import com.example.myapplication.stage5.validatePhotoFileName

/**
 * Prepared photo bytes are not visible at their final names until the
 * canonical snapshot has passed the Stage 3 durable/apply boundary.  A
 * transaction keeps the previous files in same-directory backups so a later
 * move, repository save, cancellation, or in-memory apply failure can restore
 * the complete old set.
 */
interface PhotoContentTransaction {
    /**
     * Durably binds the file journal to the old and intended canonical
     * identities before publication. Legacy/test implementations may retain
     * the source-compatible no-op default; production staged transactions
     * write the cross-store recovery intent.
     */
    suspend fun prepareCanonicalRecovery(
        previous: PhotoCanonicalIdentity,
        intended: PhotoCanonicalIdentity
    ) = Unit

    /**
     * Remote acceptance uses a stronger, versioned intent that also records
     * the metadata phase.  Source-compatible test/migration transactions may
     * retain the two-argument implementation.
     */
    suspend fun prepareCanonicalRecovery(
        previous: PhotoCanonicalIdentity,
        intended: PhotoCanonicalIdentity,
        mode: PhotoCanonicalRecoveryMode
    ) = prepareCanonicalRecovery(previous, intended)

    /**
     * Records distinct durable and live prior authorities when an unsaved live
     * edit exists.  The two-identity overload remains the compatibility path
     * for older callers whose authorities were equal.
     */
    suspend fun prepareCanonicalRecovery(
        previousDurable: PhotoCanonicalIdentity,
        previousLive: PhotoCanonicalIdentity,
        intended: PhotoCanonicalIdentity
    ) = prepareCanonicalRecovery(previousDurable, intended)

    /** Remote acceptance variant carrying the exact durable/live prior pair. */
    suspend fun prepareCanonicalRecovery(
        previousDurable: PhotoCanonicalIdentity,
        previousLive: PhotoCanonicalIdentity,
        intended: PhotoCanonicalIdentity,
        mode: PhotoCanonicalRecoveryMode
    ) = prepareCanonicalRecovery(previousDurable, intended, mode)

    /**
     * Carries the exact live snapshot when the durable and live prior
     * authorities differ.  The default preserves source compatibility for
     * test/migration implementations that do not own a production resolver.
     */
    suspend fun prepareCanonicalRecovery(
        previousDurable: PhotoCanonicalIdentity,
        previousLive: PhotoCanonicalIdentity,
        previousLiveSnapshot: DocumentSnapshotV1,
        intended: PhotoCanonicalIdentity
    ) = prepareCanonicalRecovery(previousDurable, previousLive, intended)

    /** Remote-acceptance variant carrying the exact live snapshot payload. */
    suspend fun prepareCanonicalRecovery(
        previousDurable: PhotoCanonicalIdentity,
        previousLive: PhotoCanonicalIdentity,
        previousLiveSnapshot: DocumentSnapshotV1,
        intended: PhotoCanonicalIdentity,
        mode: PhotoCanonicalRecoveryMode
    ) = prepareCanonicalRecovery(previousDurable, previousLive, intended, mode)

    /** Durable phase transition after the metadata authority has committed. */
    suspend fun markMetadataCommitted() = Unit

    /** Releases an owned resolver after recovery evidence has been retained. */
    fun releaseAfterFailure() = Unit

    /**
     * Records the exact old metadata authority before a cross-store rollback
     * begins. Production transactions persist this beside the photo journal;
     * compatibility transactions may retain the source-compatible no-op.
     */
    suspend fun prepareCrossStoreRollback(previousMetadataIdentity: String) = Unit

    /**
     * Restores photo bytes while retaining the journal for the enclosing
     * canonical/metadata rollback. The default preserves legacy test and
     * migration implementations that have no cross-store boundary.
     */
    suspend fun rollbackForCrossStoreCompensation() = rollback()

    /**
     * Publishes the durable proof that all authorities were restored, then
     * permits the retained photo journal to be cleaned up.
     */
    suspend fun completeCrossStoreRollback() = Unit

    /** Completes rollback only for the metadata tuple recorded in its evidence. */
    suspend fun completeCrossStoreRollback(previousMetadataIdentity: String) =
        completeCrossStoreRollback()

    /**
     * True after the transaction has durably established, or must
     * conservatively assume, its commit authority.  A proven pre-marker
     * failure remains eligible for complete old-state rollback; a marker
     * ambiguity retains the new state and recovery evidence instead.
     */
    fun hasAuthoritativeCommit(): Boolean = false

    suspend fun publish()
    suspend fun commit()
    suspend fun rollback()
}

/** A rollback could not restore every pre-transaction byte; callers must fail closed. */
class PhotoRollbackException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

data class PhotoContentPreparation(
    val result: com.example.myapplication.stage2.DocumentSaveResult,
    val transaction: PhotoContentTransaction? = null
)

/** Runs only filesystem failures that a photo cleanup operation is expected to produce. */
private fun <T> runExpectedPhotoOperation(
    operation: () -> T,
    onFailure: (Throwable) -> Unit
): T? {
    return try {
        operation()
    } catch (error: Stage5ValidationException) {
        onFailure(error)
        null
    } catch (error: IOException) {
        onFailure(error)
        null
    } catch (error: SecurityException) {
        onFailure(error)
        null
    }
}

private fun closePhotoResolverQuietly(resolver: PhotoPathResolver) {
    try {
        resolver.close()
    } catch (_: Stage5ValidationException) {
    } catch (_: IOException) {
    } catch (_: SecurityException) {
    }
}

class StagedPhotoContentTransaction private constructor(
    private val resolver: PhotoPathResolver,
    private val entries: MutableList<Entry>,
    private val move: (Path, Path) -> Unit,
    private val delete: (Path) -> Unit,
    private val transactionIdentity: String
) : PhotoContentTransaction {
    private data class Entry(
        val staged: File,
        val target: File,
        val backup: File,
        val targetExisted: Boolean,
        var backupMoved: Boolean = false,
        var published: Boolean = false
    )

    @Volatile
    private var committed = false

    /** The marker may have been created, but its authoritative readback is ambiguous. */
    @Volatile
    private var commitAuthorityUncertain = false

    @Volatile
    private var rolledBack = false

    /** Photo bytes were restored, but the cross-store authorities are not yet proven old. */
    @Volatile
    private var rollbackEvidenceRetained = false

    /**
     * Once canonical recovery intent has been prepared, a publish failure is
     * part of a cross-store transaction.  Its photo rollback must therefore
     * retain the journal until the canonical owner proves the old state.
     */
    @Volatile
    private var canonicalRecoveryPrepared = false

    /**
     * A failed rollback is deliberately sticky and observable.  The
     * coordinator may perform a compensating rollback after publish() fails;
     * returning normally here would turn a partially restored photo set into
     * an ordinary local-persistence failure.
     */
    @Volatile
    private var rollbackFailure: PhotoRollbackException? = null

    /** The forward publish failure must remain available if compensation fails. */
    @Volatile
    private var publishFailure: Throwable? = null

    /**
     * A marker-write failure before the authoritative commit point must leave
     * this resolver open so the coordinator can restore the complete old
     * photo set. If a marker was actually written despite the failure, the
     * new tuple is retained as recovery evidence instead.
     */
    private fun retainNewAuthorityIfCommitMarkerWasWritten(error: Throwable): Nothing {
        when (val probe = resolver.probeAuthoritativePhotoTransactionCommitMarker(transactionIdentity)) {
            PhotoCommitMarkerProbe.Bound -> {
                committed = true
                throw PhotoCanonicalRecoveryException(
                    "photo transaction commit authority was written but cleanup could not be confirmed; evidence retained",
                    error
                )
            }
            PhotoCommitMarkerProbe.Absent -> throw error
            is PhotoCommitMarkerProbe.Ambiguous -> {
                // A marker that was written but cannot be read back is
                // conservatively treated as possibly authoritative.  The
                // new photo/canonical/metadata tuple must remain in place for
                // durable recovery, and the descriptor must not be handed to
                // a caller that could attempt an unsafe old-state rollback.
                commitAuthorityUncertain = true
                if (probe.error !== error && probe.error.suppressed.none { it === error }) {
                    probe.error.addSuppressed(error)
                }
                throw probe.error
            }
        }
    }

    override suspend fun prepareCanonicalRecovery(
        previous: PhotoCanonicalIdentity,
        intended: PhotoCanonicalIdentity
    ) = prepareCanonicalRecoveryInternal(previous, previous, null, intended, null)

    override suspend fun prepareCanonicalRecovery(
        previous: PhotoCanonicalIdentity,
        intended: PhotoCanonicalIdentity,
        mode: PhotoCanonicalRecoveryMode
    ) = prepareCanonicalRecoveryInternal(previous, previous, null, intended, mode)

    override suspend fun prepareCanonicalRecovery(
        previousDurable: PhotoCanonicalIdentity,
        previousLive: PhotoCanonicalIdentity,
        intended: PhotoCanonicalIdentity
    ) = prepareCanonicalRecoveryInternal(previousDurable, previousLive, null, intended, null)

    override suspend fun prepareCanonicalRecovery(
        previousDurable: PhotoCanonicalIdentity,
        previousLive: PhotoCanonicalIdentity,
        intended: PhotoCanonicalIdentity,
        mode: PhotoCanonicalRecoveryMode
    ) = prepareCanonicalRecoveryInternal(previousDurable, previousLive, null, intended, mode)

    override suspend fun prepareCanonicalRecovery(
        previousDurable: PhotoCanonicalIdentity,
        previousLive: PhotoCanonicalIdentity,
        previousLiveSnapshot: DocumentSnapshotV1,
        intended: PhotoCanonicalIdentity
    ) = prepareCanonicalRecoveryInternal(previousDurable, previousLive, previousLiveSnapshot, intended, null)

    override suspend fun prepareCanonicalRecovery(
        previousDurable: PhotoCanonicalIdentity,
        previousLive: PhotoCanonicalIdentity,
        previousLiveSnapshot: DocumentSnapshotV1,
        intended: PhotoCanonicalIdentity,
        mode: PhotoCanonicalRecoveryMode
    ) = prepareCanonicalRecoveryInternal(previousDurable, previousLive, previousLiveSnapshot, intended, mode)

    private suspend fun prepareCanonicalRecoveryInternal(
        previousDurable: PhotoCanonicalIdentity,
        previousLive: PhotoCanonicalIdentity,
        previousLiveSnapshot: DocumentSnapshotV1?,
        intended: PhotoCanonicalIdentity,
        mode: PhotoCanonicalRecoveryMode?
    ) = withContext(Dispatchers.IO) {
        PhotoDocumentCriticalSections.withLock(resolver.root.toPath()) {
            throwIfCommitAuthorityUncertain()
            check(!committed) { "photo transaction is already committed" }
            rollbackFailure?.let { throw it }
            if (rolledBack) error("photo transaction has already been rolled back")
            resolver.requirePhotoTransactionIdentity(transactionIdentity)
            previousLiveSnapshot?.let { snapshot ->
                validateSnapshot(snapshot)
                if (photoCanonicalIdentity(
                        com.example.myapplication.stage2.DocumentId.parse(previousLive.documentId),
                        snapshot
                    ) != previousLive
                ) {
                    throw Stage5ValidationException(
                        "photo canonical recovery live snapshot does not match its identity"
                    )
                }
            }
            if (previousDurable != previousLive && previousLiveSnapshot == null) {
                throw Stage5ValidationException(
                    "an unequal durable/live recovery pair requires its live snapshot"
                )
            }
            val journalEntries = entries.map { entry ->
                PhotoTransactionJournalEntry(
                    stagedName = entry.staged.name,
                    targetName = entry.target.name,
                    backupName = entry.backup.name,
                    targetExisted = entry.targetExisted
                )
            }
            fun readPhoto(path: Path, name: String): ByteArray? {
                if (!resolver.exists(path)) return null
                if (!resolver.isRegularFile(path)) {
                    throw Stage5ValidationException("photo transaction target is not a regular file: $name")
                }
                return resolver.openRead(path, "photo transaction content $name").use {
                    readBoundedBytes(it, Stage5Limits.MAX_PHOTO_BYTES, "photo transaction content $name")
                }
            }
            val previousPhotoDigest = photoTransactionContentDigest(journalEntries) { name ->
                val entry = entries.first { it.target.name == name }
                readPhoto(entry.target.toPath(), name)
            }
            val intendedPhotoDigest = photoTransactionContentDigest(journalEntries) { name ->
                val entry = entries.first { it.target.name == name }
                readPhoto(entry.staged.toPath(), name)
                    ?: throw IOException("staged photo content disappeared: $name")
            }
            resolver.beginPhotoCanonicalRecovery(
                PhotoCanonicalRecoveryRecord(
                    previous = previousDurable,
                    intended = intended,
                    previousPhotoDigest = previousPhotoDigest,
                    intendedPhotoDigest = intendedPhotoDigest,
                    mode = mode,
                    previousLive = previousLive
                ),
                previousLiveSnapshot
            )
            canonicalRecoveryPrepared = true
        }
    }

    override suspend fun markMetadataCommitted() = withContext(Dispatchers.IO) {
        PhotoDocumentCriticalSections.withLock(resolver.root.toPath()) {
            throwIfCommitAuthorityUncertain()
            check(!committed) { "photo transaction is already committed" }
            rollbackFailure?.let { throw it }
            if (rolledBack) error("photo transaction has already been rolled back")
            resolver.markPhotoCanonicalMetadataCommitted(transactionIdentity)
        }
    }

    override fun releaseAfterFailure() {
        closePhotoResolverQuietly(resolver)
    }

    override suspend fun prepareCrossStoreRollback(previousMetadataIdentity: String) =
        withContext(Dispatchers.IO) {
            PhotoDocumentCriticalSections.withLock(resolver.root.toPath()) {
                throwIfCommitAuthorityUncertain()
                check(!committed) { "photo transaction is already committed" }
                rollbackFailure?.let { throw it }
                if (rolledBack) error("photo transaction has already been rolled back")
                resolver.beginPhotoCanonicalRollback(transactionIdentity, previousMetadataIdentity)
            }
        }

    override fun hasAuthoritativeCommit(): Boolean = committed || commitAuthorityUncertain

    override suspend fun publish() = withContext(Dispatchers.IO) {
        PhotoDocumentCriticalSections.withLock(resolver.root.toPath()) {
            throwIfCommitAuthorityUncertain()
            resolver.requirePhotoTransactionIdentity(transactionIdentity)
            check(!committed) { "photo transaction is already committed" }
            rollbackFailure?.let { throw it }
            if (rolledBack) error("photo transaction has already been rolled back")
            try {
                entries.forEach { entry ->
                    if (entry.targetExisted) {
                        moveAtomically(entry.target.toPath(), entry.backup.toPath())
                        entry.backupMoved = true
                    }
                    moveAtomically(entry.staged.toPath(), entry.target.toPath())
                    entry.published = true
                }
            } catch (error: Stage5ValidationException) {
                failPublish(error)
            } catch (error: IOException) {
                failPublish(error)
            } catch (error: SecurityException) {
                failPublish(error)
            }
        }
    }

    override suspend fun commit() = withContext(Dispatchers.IO) {
        PhotoDocumentCriticalSections.withLock(resolver.root.toPath()) {
            throwIfCommitAuthorityUncertain()
            rollbackFailure?.let { throw it }
            if (committed || rolledBack) return@withLock
            try {
                resolver.requirePhotoTransactionIdentity(transactionIdentity)
                // The commit marker is force-written before cleanup. If the
                // process dies after this point, reopen treats the new files as
                // authoritative and only retries artifact cleanup.
                try {
                    resolver.markPhotoTransactionCommitted(transactionIdentity)
                } catch (error: PhotoCanonicalRecoveryException) {
                    retainNewAuthorityIfCommitMarkerWasWritten(error)
                } catch (error: Stage5ValidationException) {
                    retainNewAuthorityIfCommitMarkerWasWritten(error)
                } catch (error: IOException) {
                    retainNewAuthorityIfCommitMarkerWasWritten(error)
                } catch (error: SecurityException) {
                    retainNewAuthorityIfCommitMarkerWasWritten(error)
                } catch (error: IllegalArgumentException) {
                    retainNewAuthorityIfCommitMarkerWasWritten(error)
                } catch (error: IllegalStateException) {
                    retainNewAuthorityIfCommitMarkerWasWritten(error)
                }
                committed = true
                var cleanupComplete = true
                var cleanupFailure: Throwable? = null
                fun recordCleanupFailure(error: Throwable) {
                    cleanupComplete = false
                    if (cleanupFailure == null) {
                        cleanupFailure = error
                    } else {
                        cleanupFailure?.addSuppressed(error)
                    }
                }
                entries.forEach { entry ->
                    // Never remove a rollback artifact before the authoritative
                    // commit point. After it, a cleanup failure leaves the artifact in
                    // place and reports typed recovery instead of pretending the
                    // cross-store transaction completed cleanly.
                    val stagedClean = runExpectedPhotoOperation({
                        resolver.ensureContained(entry.staged.toPath())
                        delete(entry.staged.toPath())
                        true
                    }, ::recordCleanupFailure) == true
                    if (stagedClean) {
                        runExpectedPhotoOperation({
                            resolver.ensureContained(entry.backup.toPath())
                            delete(entry.backup.toPath())
                        }, ::recordCleanupFailure)
                    }
                }
                if (cleanupComplete) {
                    runExpectedPhotoOperation({
                        resolver.clearPhotoTransactionMarkers(transactionIdentity)
                    }, ::recordCleanupFailure)
                }
                if (!cleanupComplete) {
                    throw PhotoCanonicalRecoveryException(
                        "photo transaction committed but cleanup evidence remains",
                        cleanupFailure
                    )
                }
            } finally {
                // Close only after the authoritative marker is known to own
                // this journal. Before that point rollback must retain a live
                // descriptor so the coordinator can restore every old byte.
                if (committed || commitAuthorityUncertain) closePhotoResolverQuietly(resolver)
            }
        }
    }

    override suspend fun rollback() = withContext(Dispatchers.IO) {
        PhotoDocumentCriticalSections.withLock(resolver.root.toPath()) {
            throwIfCommitAuthorityUncertain()
            rollbackFailure?.let { throw it }
            rollbackInternal(retainEvidence = false)
        }
    }

    override suspend fun rollbackForCrossStoreCompensation() = withContext(Dispatchers.IO) {
        PhotoDocumentCriticalSections.withLock(resolver.root.toPath()) {
            throwIfCommitAuthorityUncertain()
            rollbackFailure?.let { throw it }
            rollbackInternal(retainEvidence = true)
        }
    }

    override suspend fun completeCrossStoreRollback() = completeCrossStoreRollbackInternal(null)

    override suspend fun completeCrossStoreRollback(previousMetadataIdentity: String) =
        completeCrossStoreRollbackInternal(previousMetadataIdentity)

    private suspend fun completeCrossStoreRollbackInternal(previousMetadataIdentity: String?) =
        withContext(Dispatchers.IO) {
            PhotoDocumentCriticalSections.withLock(resolver.root.toPath()) {
                throwIfCommitAuthorityUncertain()
                rollbackFailure?.let { throw it }
                if (committed || rolledBack) return@withLock
                if (!rollbackEvidenceRetained) {
                    rollbackInternal(retainEvidence = true)
                }
                try {
                    resolver.markPhotoCanonicalRollbackComplete(transactionIdentity, previousMetadataIdentity)
                    resolver.clearPhotoTransactionMarkers(transactionIdentity)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: PhotoCanonicalRecoveryException) {
                    val failure = PhotoRollbackException(
                        "cross-store photo rollback completion could not be recorded",
                        error
                    )
                    rollbackFailure = failure
                    throw failure
                } catch (error: Stage5ValidationException) {
                    val failure = PhotoRollbackException(
                        "cross-store photo rollback completion could not be recorded",
                        error
                    )
                    rollbackFailure = failure
                    throw failure
                } catch (error: IOException) {
                    val failure = PhotoRollbackException(
                        "cross-store photo rollback completion could not be recorded",
                        error
                    )
                    rollbackFailure = failure
                    throw failure
                } catch (error: SecurityException) {
                    val failure = PhotoRollbackException(
                        "cross-store photo rollback completion could not be recorded",
                        error
                    )
                    rollbackFailure = failure
                    throw failure
                } catch (error: IllegalArgumentException) {
                    val failure = PhotoRollbackException(
                        "cross-store photo rollback completion could not be recorded",
                        error
                    )
                    rollbackFailure = failure
                    throw failure
                } catch (error: IllegalStateException) {
                    val failure = PhotoRollbackException(
                        "cross-store photo rollback completion could not be recorded",
                        error
                    )
                    rollbackFailure = failure
                    throw failure
                }
                rollbackEvidenceRetained = false
                rolledBack = true
                closePhotoResolverQuietly(resolver)
            }
        }

    private fun rollbackInternal(retainEvidence: Boolean) {
        throwIfCommitAuthorityUncertain()
        rollbackFailure?.let { throw it }
        if (committed || rolledBack || rollbackEvidenceRetained) return
        resolver.requirePhotoTransactionIdentity(transactionIdentity)
        when (val probe = resolver.probeAuthoritativePhotoTransactionCommitMarker(transactionIdentity)) {
            PhotoCommitMarkerProbe.Absent -> Unit
            PhotoCommitMarkerProbe.Bound -> throw PhotoCanonicalRecoveryException(
                "photo transaction is already authoritative; rollback is unsafe"
            )
            is PhotoCommitMarkerProbe.Ambiguous -> throw probe.error
        }
        var firstFailure: Throwable? = null
        entries.asReversed().forEach { entry ->
            try {
                if (entry.published) {
                    resolver.ensureContained(entry.target.toPath())
                    delete(entry.target.toPath())
                }
                if (entry.backupMoved) {
                    moveAtomically(entry.backup.toPath(), entry.target.toPath())
                }
                resolver.ensureContained(entry.staged.toPath())
                delete(entry.staged.toPath())
            } catch (error: Stage5ValidationException) {
                if (firstFailure == null) {
                    firstFailure = error
                } else {
                    firstFailure?.addSuppressed(error)
                }
            } catch (error: IOException) {
                if (firstFailure == null) {
                    firstFailure = error
                } else {
                    firstFailure?.addSuppressed(error)
                }
            } catch (error: SecurityException) {
                if (firstFailure == null) {
                    firstFailure = error
                } else {
                    firstFailure?.addSuppressed(error)
                }
            }
        }
        if (firstFailure != null) {
            val failure = PhotoRollbackException(
                "photo content rollback could not restore every prior file",
                firstFailure
            )
            publishFailure?.let { original ->
                if (original !== failure && failure.suppressed.none { it === original }) {
                    failure.addSuppressed(original)
                }
            }
            rollbackFailure = failure
            // Do not mark the transaction successfully rolled back.  A
            // subsequent compensating call must observe this failure rather
            // than silently returning as though all bytes were restored.
            throw failure
        }
        if (retainEvidence) {
            // The coordinator must restore canonical durable/live state and
            // metadata before it can publish the rollback-complete marker.
            rollbackEvidenceRetained = true
            return
        }
        try {
            resolver.clearPhotoTransactionMarkers(transactionIdentity)
        } catch (error: Stage5ValidationException) {
            val failure = PhotoRollbackException(
                "photo transaction rollback evidence could not be cleared",
                error
            )
            rollbackFailure = failure
            throw failure
        } catch (error: IOException) {
            val failure = PhotoRollbackException(
                "photo transaction rollback evidence could not be cleared",
                error
            )
            rollbackFailure = failure
            throw failure
        } catch (error: SecurityException) {
            val failure = PhotoRollbackException(
                "photo transaction rollback evidence could not be cleared",
                error
            )
            rollbackFailure = failure
            throw failure
        }
        rolledBack = true
        closePhotoResolverQuietly(resolver)
    }

    private fun failPublish(error: Throwable): Nothing {
        publishFailure = error
        try {
            rollbackInternal(retainEvidence = canonicalRecoveryPrepared)
        } catch (rollback: PhotoRollbackException) {
            // Keep the rollback failure as the surfaced exception, while
            // retaining the original publish/move failure for recovery
            // diagnostics and coordinator error classification.
            if (rollback.suppressed.none { it === error }) {
                rollback.addSuppressed(error)
            }
            throw rollback
        }
        throw error
    }

    private fun moveAtomically(source: Path, target: Path) {
        resolver.ensureContained(source)
        resolver.ensureContained(target)
        try {
            move(source, target)
            resolver.ensureContained(target)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IOException("atomic photo replacement is unavailable", unsupported)
        }
    }

    private fun throwIfCommitAuthorityUncertain() {
        if (commitAuthorityUncertain) {
            throw PhotoCanonicalRecoveryException(
                "photo transaction commit authority is ambiguous; recovery evidence must be reconciled"
            )
        }
    }

    companion object {
        /** Stages and validates the full incoming photo set without publishing it. */
        fun stage(
            rootDirectory: File,
            photoFiles: Map<String, ByteArray>,
            move: ((Path, Path) -> Unit)? = null,
            delete: ((Path) -> Unit)? = null,
            trustedRootDirectory: File? = null
        ): StagedPhotoContentTransaction {
            return stageInternal(
                rootDirectory,
                photoFiles,
                move,
                delete,
                operationsFactory = null,
                trustedRootDirectory = trustedRootDirectory
            )
        }

        /** JVM tests inject an explicit provider; production has no path fallback. */
        internal fun stageForTesting(
            rootDirectory: File,
            photoFiles: Map<String, ByteArray>,
            operationsFactory: com.example.myapplication.stage5.PhotoPathOperationsFactory,
            move: ((Path, Path) -> Unit)? = null,
            delete: ((Path) -> Unit)? = null,
            trustedRootDirectory: File? = null
        ): StagedPhotoContentTransaction = stageInternal(
            rootDirectory,
            photoFiles,
            move,
            delete,
            operationsFactory,
            trustedRootDirectory
        )

        /** Stage 5 compatibility migration uses the same injected secure seam. */
        internal fun stageWithOperationsFactory(
            rootDirectory: File,
            photoFiles: Map<String, ByteArray>,
            operationsFactory: com.example.myapplication.stage5.PhotoPathOperationsFactory,
            trustedRootDirectory: File? = null
        ): StagedPhotoContentTransaction = stageInternal(
            rootDirectory,
            photoFiles,
            move = null,
            delete = null,
            operationsFactory = operationsFactory,
            trustedRootDirectory = trustedRootDirectory
        )

        private fun stageInternal(
            rootDirectory: File,
            photoFiles: Map<String, ByteArray>,
            move: ((Path, Path) -> Unit)?,
            delete: ((Path) -> Unit)?,
            operationsFactory: com.example.myapplication.stage5.PhotoPathOperationsFactory?,
            trustedRootDirectory: File?
        ): StagedPhotoContentTransaction {
            return PhotoDocumentCriticalSections.withLock(rootDirectory.toPath()) {
            val resolver = if (operationsFactory == null) {
                PhotoPathResolver(
                    rootDirectory,
                    trustedRootDirectory = trustedRootDirectory
                )
            } else {
                PhotoPathResolver(
                    rootDirectory,
                    createRoot = true,
                    operationsFactory = operationsFactory,
                    trustedRootDirectory = trustedRootDirectory
                )
            }
            val moveOperation: (Path, Path) -> Unit = move ?: { source, target ->
                resolver.atomicMove(source, target)
            }
            val deleteOperation: (Path) -> Unit = delete ?: { path -> resolver.deletePath(path) }
            val staged = mutableListOf<Entry>()
            var totalBytes = 0L
            var currentTemp: File? = null
            var transactionIdentity = ""
            try {
                photoFiles.forEach { (name, bytes) ->
                    validatePhotoFileName(name)
                    if (bytes.size > com.example.myapplication.stage5.Stage5Limits.MAX_PHOTO_BYTES) {
                        throw com.example.myapplication.stage5.Stage5ValidationException(
                            "photo content exceeds individual limit: $name"
                        )
                    }
                    if (totalBytes > com.example.myapplication.stage5.Stage5Limits.MAX_TOTAL_PHOTO_BYTES - bytes.size.toLong()) {
                        throw com.example.myapplication.stage5.Stage5ValidationException(
                            "photo content exceeds aggregate limit"
                        )
                    }
                    totalBytes += bytes.size.toLong()
                    validatePhotoBytes(bytes)
                    val target = resolver.resolve(name)
                    val temp = resolver.newInternalFile("stage5-photo", ".tmp")
                    currentTemp = temp
                    resolver.writeBytes(temp.toPath(), bytes, "photo staging $name")
                    val targetExisted = resolver.exists(target.toPath())
                    if (targetExisted && !resolver.isRegularFile(target.toPath())) {
                        throw IOException("photo target is not a regular file: $name")
                    }
                    staged += Entry(
                        staged = temp,
                        target = target,
                        backup = resolver.newInternalFile("stage5-photo", ".bak"),
                        targetExisted = targetExisted
                    )
                    currentTemp = null
                }
                if (staged.size > Stage5Limits.MAX_TOTAL_PHOTOS) {
                    throw com.example.myapplication.stage5.Stage5ValidationException(
                        "photo transaction entry count exceeds its limit"
                    )
                }
                transactionIdentity = resolver.beginPhotoTransaction(
                    staged.map { entry ->
                        PhotoTransactionJournalEntry(
                            stagedName = entry.staged.name,
                            targetName = entry.target.name,
                            backupName = entry.backup.name,
                            targetExisted = entry.targetExisted
                        )
                    }
                )
            } catch (error: Stage5ValidationException) {
                cleanupAfterStageFailure(resolver, currentTemp, staged, deleteOperation, error)
            } catch (error: IOException) {
                cleanupAfterStageFailure(resolver, currentTemp, staged, deleteOperation, error)
            } catch (error: SecurityException) {
                cleanupAfterStageFailure(resolver, currentTemp, staged, deleteOperation, error)
            }
            StagedPhotoContentTransaction(
                resolver,
                staged,
                moveOperation,
                deleteOperation,
                transactionIdentity
            )
            }
        }

        private fun cleanupAfterStageFailure(
            resolver: PhotoPathResolver,
            currentTemp: File?,
            staged: List<Entry>,
            deleteOperation: (Path) -> Unit,
            error: Throwable
        ): Nothing {
            var cleanupFailure: Throwable? = null
            fun recordCleanupFailure(failure: Throwable) {
                if (cleanupFailure == null) cleanupFailure = failure
                else cleanupFailure?.addSuppressed(failure)
            }
            currentTemp?.let { temp ->
                runExpectedPhotoOperation({
                    resolver.ensureContained(temp.toPath())
                    deleteOperation(temp.toPath())
                }, ::recordCleanupFailure)
            }
            staged.forEach { entry ->
                runExpectedPhotoOperation({
                    resolver.ensureContained(entry.staged.toPath())
                    deleteOperation(entry.staged.toPath())
                }, ::recordCleanupFailure)
            }
            runExpectedPhotoOperation({ resolver.close() }, ::recordCleanupFailure)
            cleanupFailure?.let { failure ->
                if (error.suppressed.none { it === failure }) error.addSuppressed(failure)
            }
            throw error
        }
    }
}
