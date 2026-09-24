package com.example.myapplication.stage4

import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncAdoptionStateMachineTest {
    private val scope = SyncScope("account", "root", DocumentId.new())
    private val remoteDocumentId = DocumentId.new()
    private val fingerprint = SourceFingerprint.fromBytes("adoption-state-source".toByteArray())
    private val priorCursor = RemoteCursor("before-adoption", 10L)
    private val linkedCursor = RemoteCursor("linked-r1", 20L)
    private val linkedReference = RemoteReference(
        folderId = "folder-1",
        snapshotFileId = "snapshot-1",
        appProperties = mapOf(
            SYNC_DOCUMENT_ID_APP_PROPERTY to scope.documentId.value,
            SYNC_SOURCE_FINGERPRINT_APP_PROPERTY to fingerprint.toDriveProperty()
        )
    )
    private val candidate = RemoteAdoptionCandidate(
        accountId = scope.accountId,
        backupRootId = scope.backupRootId,
        remoteDocumentId = remoteDocumentId,
        sourceFingerprint = fingerprint,
        displayName = "plan.pdf",
        reference = RemoteReference(
            folderId = linkedReference.folderId,
            snapshotFileId = linkedReference.snapshotFileId,
            appProperties = mapOf(
                SYNC_DOCUMENT_ID_APP_PROPERTY to remoteDocumentId.value,
                SYNC_SOURCE_FINGERPRINT_APP_PROPERTY to fingerprint.toDriveProperty()
            )
        ),
        cursor = linkedCursor
    )
    private val linkedRemote = RemoteDocumentMetadata(
        scope = scope,
        displayName = candidate.displayName,
        reference = linkedReference,
        cursor = linkedCursor
    )

    @Test
    fun candidateLinkAndAcknowledgementKeepLocalApplyExplicitAndCursorUnchanged() {
        val original = SyncMetadata(scope = scope, acceptedCursor = priorCursor)
        val discovered = SyncAdoptionStateMachine.candidateDiscovered(original, candidate)

        assertNull(original.pendingAdoption)
        assertEquals(SyncAdoptionState.CandidatePending(candidate), SyncAdoptionStateMachine.state(discovered))

        val linked = SyncAdoptionStateMachine.remoteLinked(
            metadata = discovered,
            candidate = candidate,
            remote = linkedRemote,
            adoptedRemoteDocumentId = remoteDocumentId,
            sourceUri = "content://device/source",
            sourceFingerprint = fingerprint,
            pendingUpload = discovered.pendingUpload
        )
        val pending = (SyncAdoptionStateMachine.state(linked)
            as SyncAdoptionState.LinkedAwaitingLocalApply).pending

        assertEquals(SYNC_METADATA_SCHEMA_VERSION, linked.schemaVersion)
        assertEquals(priorCursor, linked.acceptedCursor)
        assertEquals(linkedCursor, linked.conflictCursor)
        assertEquals(candidate, linked.pendingAdoptionAcknowledgement)
        assertNull(linked.pendingAdoption)
        assertEquals(linkedRemote, pending.remote)
        assertEquals("content://device/source", pending.sourceUri)
        assertEquals(remoteDocumentId, pending.adoptedRemoteDocumentId)

        val acknowledged = SyncAdoptionStateMachine.adoptionAcknowledged(linked, candidate)
        assertTrue(acknowledged != null)
        assertNull(acknowledged?.pendingAdoptionAcknowledgement)
        assertEquals(pending, acknowledged?.pendingLocalApply)
        assertEquals(priorCursor, acknowledged?.acceptedCursor)
    }

    @Test
    fun pendingRefreshRequiresExactIntentAndDoesNotAdvanceAcceptedCursor() {
        val linked = linkedMetadata()
        val current = requireNotNull(linked.pendingLocalApply)
        val newerRemote = linkedRemote.copy(cursor = RemoteCursor("linked-r2", 30L))
        val refreshed = current.copy(remote = newerRemote)

        val updated = requireNotNull(
            SyncAdoptionStateMachine.refreshPendingLocalApply(
                metadata = linked,
                expected = current,
                refreshed = refreshed,
                pendingUpload = linked.pendingUpload
            )
        )
        assertEquals(priorCursor, updated.acceptedCursor)
        assertEquals(newerRemote.cursor, updated.conflictCursor)
        assertEquals(refreshed, updated.pendingLocalApply)
        assertNull(
            SyncAdoptionStateMachine.refreshPendingLocalApply(
                metadata = linked,
                expected = current.copy(sourceUri = "content://stale/source"),
                refreshed = refreshed,
                pendingUpload = linked.pendingUpload
            )
        )
    }

    @Test
    fun legacyUnknownRequiresExplicitIntentBeforeApplyProofAndCursorAdvance() {
        val legacy = SyncMetadata(
            scope = scope,
            remoteReference = linkedReference,
            acceptedCursor = priorCursor,
            conflictCursor = linkedCursor,
            conflictDetail = "legacy adoption needs reconciliation",
            adoptedRemoteDocumentId = remoteDocumentId
        )
        assertTrue(SyncAdoptionStateMachine.state(legacy) is SyncAdoptionState.LegacyAmbiguous)

        val pending = requireNotNull(
            SyncAdoptionStateMachine.beginLegacyReconciliation(
                metadata = legacy,
                remote = linkedRemote,
                sourceUri = "content://device/source",
                sourceFingerprint = fingerprint,
                pendingUpload = legacy.pendingUpload
            )
        )
        assertEquals(priorCursor, pending.acceptedCursor)
        assertEquals(false, pending.adoptedLocalApplyVerified)
        assertTrue(SyncAdoptionStateMachine.state(pending) is SyncAdoptionState.LinkedAwaitingLocalApply)
        assertNull(
            SyncAdoptionStateMachine.beginLegacyReconciliation(
                metadata = legacy.copy(conflictCursor = RemoteCursor("stale-r0")),
                remote = linkedRemote,
                sourceUri = "content://device/source",
                sourceFingerprint = fingerprint,
                pendingUpload = legacy.pendingUpload
            )
        )

        val applied = SyncAdoptionStateMachine.localApplyCommitted(
            metadata = pending,
            remoteReference = linkedRemote.reference,
            acceptedCursor = linkedRemote.cursor,
            pendingUpload = null
        )
        assertEquals(linkedCursor, applied.acceptedCursor)
        assertEquals(true, applied.adoptedLocalApplyVerified)
        assertNull(applied.pendingLocalApply)
        assertNull(applied.conflictCursor)
        assertTrue(SyncAdoptionStateMachine.state(applied) is SyncAdoptionState.Applied)
    }

    private fun linkedMetadata(): SyncMetadata = SyncAdoptionStateMachine.remoteLinked(
        metadata = SyncAdoptionStateMachine.candidateDiscovered(
            SyncMetadata(scope = scope, acceptedCursor = priorCursor),
            candidate
        ),
        candidate = candidate,
        remote = linkedRemote,
        adoptedRemoteDocumentId = remoteDocumentId,
        sourceUri = "content://device/source",
        sourceFingerprint = fingerprint,
        pendingUpload = null
    )
}
