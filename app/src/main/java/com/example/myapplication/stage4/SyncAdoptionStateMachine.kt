package com.example.myapplication.stage4

import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint

/**
 * Derived adoption phase. The durable representation remains SyncMetadata
 * schema 2; this type is only the coordinator's explicit interpretation of it.
 */
internal sealed interface SyncAdoptionState {
    data object None : SyncAdoptionState

    data class CandidatePending(
        val candidate: RemoteAdoptionCandidate
    ) : SyncAdoptionState

    data class LinkedAwaitingLocalApply(
        val pending: PendingLocalApply
    ) : SyncAdoptionState

    data class Applied(
        val adoptedRemoteDocumentId: DocumentId,
        val acceptedCursor: RemoteCursor
    ) : SyncAdoptionState

    /** A v2 adoption row without an apply proof must be reconciled explicitly. */
    data class LegacyAmbiguous(
        val adoptedRemoteDocumentId: DocumentId,
        val acceptedCursor: RemoteCursor?,
        val remoteReference: RemoteReference?
    ) : SyncAdoptionState
}

/** Pure v2 metadata transitions for the remote-link/local-apply lifecycle. */
internal object SyncAdoptionStateMachine {
    fun state(metadata: SyncMetadata?): SyncAdoptionState {
        metadata ?: return SyncAdoptionState.None
        metadata.pendingLocalApply?.let {
            return SyncAdoptionState.LinkedAwaitingLocalApply(it)
        }
        val adoptedId = metadata.adoptedRemoteDocumentId
        if (adoptedId != null && metadata.adoptedLocalApplyVerified == null) {
            return SyncAdoptionState.LegacyAmbiguous(
                adoptedRemoteDocumentId = adoptedId,
                acceptedCursor = metadata.acceptedCursor,
                remoteReference = metadata.remoteReference
            )
        }
        metadata.pendingAdoption?.let {
            return SyncAdoptionState.CandidatePending(it)
        }
        if (adoptedId != null && metadata.adoptedLocalApplyVerified == true) {
            return SyncAdoptionState.Applied(adoptedId, requireNotNull(metadata.acceptedCursor))
        }
        return SyncAdoptionState.None
    }

    fun candidateDiscovered(
        metadata: SyncMetadata,
        candidate: RemoteAdoptionCandidate
    ): SyncMetadata = metadata.copy(pendingAdoption = candidate)

    /** Called only after Drive has committed the remote identity rewrite. */
    fun remoteLinked(
        metadata: SyncMetadata,
        candidate: RemoteAdoptionCandidate,
        remote: RemoteDocumentMetadata,
        adoptedRemoteDocumentId: DocumentId,
        sourceUri: String,
        sourceFingerprint: SourceFingerprint,
        pendingUpload: DurablePendingUpload?
    ): SyncMetadata {
        require(adoptedRemoteDocumentId == candidate.remoteDocumentId) {
            "adoption result DocumentId does not match the selected candidate"
        }
        val pending = PendingLocalApply(
            sourceUri = sourceUri,
            sourceFingerprint = sourceFingerprint,
            adoptedRemoteDocumentId = adoptedRemoteDocumentId,
            remote = remote
        )
        return metadata.copy(
            remoteReference = remote.reference,
            // Linking is not evidence that canonical/photo state was applied.
            acceptedCursor = metadata.acceptedCursor,
            adoptedRemoteDocumentId = adoptedRemoteDocumentId,
            adoptedLocalApplyVerified = false,
            pendingAdoptionAcknowledgement = candidate,
            pendingAdoption = null,
            pendingLocalApply = pending,
            conflictCursor = remote.cursor,
            conflictDetail = "adopted remote revision ${remote.cursor.revision} is awaiting local acceptance",
            pendingUpload = pendingUpload
        )
    }

    fun refreshPendingLocalApply(
        metadata: SyncMetadata,
        expected: PendingLocalApply,
        refreshed: PendingLocalApply,
        pendingUpload: DurablePendingUpload?
    ): SyncMetadata? {
        val state = state(metadata) as? SyncAdoptionState.LinkedAwaitingLocalApply ?: return null
        if (state.pending != expected || refreshed.adoptedRemoteDocumentId != expected.adoptedRemoteDocumentId) {
            return null
        }
        return metadata.copy(
            remoteReference = refreshed.remote.reference,
            conflictCursor = refreshed.remote.cursor,
            conflictDetail = "adopted remote revision ${refreshed.remote.cursor.revision} is awaiting local acceptance",
            pendingLocalApply = refreshed,
            pendingUpload = pendingUpload
        )
    }

    fun beginLegacyReconciliation(
        metadata: SyncMetadata,
        remote: RemoteDocumentMetadata,
        sourceUri: String,
        sourceFingerprint: SourceFingerprint,
        pendingUpload: DurablePendingUpload?
    ): SyncMetadata? {
        val state = state(metadata) as? SyncAdoptionState.LegacyAmbiguous ?: return null
        if (metadata.conflictCursor != remote.cursor) return null
        val pending = PendingLocalApply(
            sourceUri = sourceUri,
            sourceFingerprint = sourceFingerprint,
            adoptedRemoteDocumentId = state.adoptedRemoteDocumentId,
            remote = remote
        )
        return metadata.copy(
            remoteReference = remote.reference,
            adoptedLocalApplyVerified = false,
            pendingLocalApply = pending,
            conflictCursor = remote.cursor,
            conflictDetail = "legacy adopted remote revision ${remote.cursor.revision} is awaiting local acceptance",
            pendingUpload = pendingUpload
        )
    }

    /**
     * Clears only the Drive-journal retry marker after remote acknowledgement;
     * it leaves the local-apply intent and cursor untouched.
     */
    fun adoptionAcknowledged(
        metadata: SyncMetadata,
        candidate: RemoteAdoptionCandidate
    ): SyncMetadata? = if (metadata.pendingAdoptionAcknowledgement == candidate) {
        metadata.copy(pendingAdoptionAcknowledgement = null)
    } else {
        null
    }

    /** Build the metadata half of acceptance after canonical/photo commit. */
    fun localApplyCommitted(
        metadata: SyncMetadata,
        remoteReference: RemoteReference,
        acceptedCursor: RemoteCursor,
        pendingUpload: DurablePendingUpload?
    ): SyncMetadata = metadata.copy(
        remoteReference = remoteReference,
        acceptedCursor = acceptedCursor,
        conflictCursor = null,
        conflictDetail = null,
        adoptedLocalApplyVerified = if (metadata.adoptedRemoteDocumentId != null) true
        else metadata.adoptedLocalApplyVerified,
        pendingLocalApply = null,
        pendingUpload = pendingUpload
    )
}
