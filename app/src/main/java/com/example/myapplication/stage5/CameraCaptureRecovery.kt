package com.example.myapplication.stage5

import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint

/** Stable identity available when a newly created session is ready to drain. */
data class CameraCaptureStableIdentity(
    val documentId: DocumentId,
    val sourceUri: String,
    val sourceFingerprint: SourceFingerprint?
) {
    init {
        requireCameraSourceUri(sourceUri)
    }
}

enum class CameraCaptureRecoveryDisposition {
    NONE,
    CAMERA_PENDING,
    RESULT_AVAILABLE,
    RESULT_CANCELLED,
    PROCESSING,
    PUBLISHED_UNCOMMITTED,
    COMMITTED,
    DISCARDED,
    IDENTITY_MISMATCH,
    CORRUPT,
    IO_FAILURE
}

/** Result of inspecting the durable operation without mutating it. */
data class CameraCaptureRecoveryState(
    val disposition: CameraCaptureRecoveryDisposition,
    val operation: CameraCaptureOperationRecord? = null,
    val error: CameraCaptureOperationException? = null
)

/**
 * Read-only recovery adapter used by the document/session owner.  It never
 * selects a live pin or mutates the canonical snapshot; the caller must do
 * that under its document transaction barrier using [operation.pinId].
 */
class CameraCaptureRecovery(
    private val operationStore: CameraCaptureOperationStore
) {
    /**
     * Separate from [inspect] so ordinary reads remain fail-closed when a
     * staged revision is present. The caller should invoke this before
     * inspecting the operation after process death or interrupted publication.
     */
    fun reconcileInterruptedJournal(): CameraCaptureJournalReconciliation =
        operationStore.reconcileInterruptedJournal()

    fun reconcileStagedJournal(): CameraCaptureJournalReconciliation =
        operationStore.reconcileStagedJournal()

    fun inspect(identity: CameraCaptureStableIdentity? = null): CameraCaptureRecoveryState {
        val operation = try {
            operationStore.readOperation()
        } catch (error: CameraCaptureOperationException) {
            return CameraCaptureRecoveryState(
                disposition = if (error is CameraCaptureOperationCorruptException) {
                    CameraCaptureRecoveryDisposition.CORRUPT
                } else {
                    CameraCaptureRecoveryDisposition.IO_FAILURE
                },
                error = error
            )
        }
        if (operation == null) {
            return CameraCaptureRecoveryState(CameraCaptureRecoveryDisposition.NONE)
        }
        if (identity != null && !operationStore.matchesStableIdentity(
                operation,
                identity.documentId,
                identity.sourceUri,
                identity.sourceFingerprint
            )
        ) {
            return CameraCaptureRecoveryState(
                disposition = CameraCaptureRecoveryDisposition.IDENTITY_MISMATCH,
                operation = operation
            )
        }
        return CameraCaptureRecoveryState(
            disposition = operation.status.toRecoveryDisposition(),
            operation = operation
        )
    }

    /**
     * Allows process-death rebinding only to the same durable document/source
     * identity.  Session generations are intentionally excluded here because a
     * new session receives a fresh generation after reopening.
     */
    fun canRebind(
        operation: CameraCaptureOperationRecord,
        identity: CameraCaptureStableIdentity
    ): Boolean = operationStore.matchesStableIdentity(
        operation,
        identity.documentId,
        identity.sourceUri,
        identity.sourceFingerprint
    )

    /**
     * Admission fence for a ready session.  An operation created by this
     * process must still belong to the exact session generation that created
     * it; otherwise an A -> B -> A switch could attach an old A result to the
     * new A session.  A different operation-owner instance is the explicit
     * host-recreation/process-death recovery case, where stable
     * document/source identity is the only durable session binding available.
     */
    fun canApplyToSession(
        operation: CameraCaptureOperationRecord,
        identity: CameraCaptureStableIdentity,
        ownerInstanceId: String,
        sessionGeneration: Long
    ): Boolean {
        if (!canRebind(operation, identity)) return false
        if (operation.processInstanceId != ownerInstanceId) return true
        return matchesExactSession(
            operation = operation,
            identity = identity,
            sessionGeneration = sessionGeneration,
            pageIndex = operation.pageIndex,
            pinId = operation.pinId
        )
    }

    /** Exact in-process fence; the caller must additionally check live pin state. */
    fun matchesExactSession(
        operation: CameraCaptureOperationRecord,
        identity: CameraCaptureStableIdentity,
        sessionGeneration: Long,
        pageIndex: Int,
        pinId: String
    ): Boolean = canRebind(operation, identity) &&
        operation.sessionGeneration == sessionGeneration &&
        operation.pageIndex == pageIndex &&
        operation.pinId == pinId

    /**
     * Returns every reserved publication name that must remain protected while
     * recovery is unresolved.  The set is conservative for DISCARDED records
     * because a crash can occur after publication but before that disposition.
     */
    fun retainedPublishedPhotoNames(): Set<String> {
        val operation = operationStore.readOperation() ?: return emptySet()
        return when (operation.status) {
            CameraCaptureOperationStatus.RESULT_AVAILABLE,
            CameraCaptureOperationStatus.PROCESSING,
            CameraCaptureOperationStatus.PUBLISHED,
            CameraCaptureOperationStatus.COMMITTED,
            CameraCaptureOperationStatus.DISCARDED ->
                operation.publishedPhotoFileName?.let(::setOf).orEmpty()
            CameraCaptureOperationStatus.PREPARED,
            CameraCaptureOperationStatus.LAUNCHED,
            CameraCaptureOperationStatus.RESULT_CANCELLED -> emptySet()
        }
    }
}

private fun CameraCaptureOperationStatus.toRecoveryDisposition(): CameraCaptureRecoveryDisposition =
    when (this) {
        CameraCaptureOperationStatus.PREPARED,
        CameraCaptureOperationStatus.LAUNCHED -> CameraCaptureRecoveryDisposition.CAMERA_PENDING
        CameraCaptureOperationStatus.RESULT_AVAILABLE -> CameraCaptureRecoveryDisposition.RESULT_AVAILABLE
        CameraCaptureOperationStatus.RESULT_CANCELLED -> CameraCaptureRecoveryDisposition.RESULT_CANCELLED
        CameraCaptureOperationStatus.PROCESSING -> CameraCaptureRecoveryDisposition.PROCESSING
        CameraCaptureOperationStatus.PUBLISHED -> CameraCaptureRecoveryDisposition.PUBLISHED_UNCOMMITTED
        CameraCaptureOperationStatus.COMMITTED -> CameraCaptureRecoveryDisposition.COMMITTED
        CameraCaptureOperationStatus.DISCARDED -> CameraCaptureRecoveryDisposition.DISCARDED
    }
