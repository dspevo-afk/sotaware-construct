package com.example.myapplication.stage9b

import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.NoteSnapshotV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage3.DocumentSession
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.ResolvedDocumentTarget
import com.example.myapplication.stage4.AdoptionRequest
import com.example.myapplication.stage4.AdoptionResult
import com.example.myapplication.stage4.DriveGateway
import com.example.myapplication.stage4.DurablePendingUpload
import com.example.myapplication.stage4.FakeDriveGateway
import com.example.myapplication.stage4.InMemorySyncMetadataStore
import com.example.myapplication.stage4.MetadataReadResult
import com.example.myapplication.stage4.MetadataWriteResult
import com.example.myapplication.stage4.RemoteDocumentMetadata
import com.example.myapplication.stage4.RemoteCursor
import com.example.myapplication.stage4.RemoteSnapshotEnvelope
import com.example.myapplication.stage4.SyncCoordinator
import com.example.myapplication.stage4.SyncError
import com.example.myapplication.stage4.SyncMetadata
import com.example.myapplication.stage4.SyncMetadataStore
import com.example.myapplication.stage4.SyncOutcome
import com.example.myapplication.stage4.SyncReason
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage4.SyncSessionBridge
import com.example.myapplication.stage4.SYNC_DOCUMENT_ID_APP_PROPERTY
import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage4.TestPersistentPhotoMetadataStore
import com.example.myapplication.stage4.UploadRequest
import com.example.myapplication.stage4.UploadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import com.example.myapplication.stage4.PendingUploadIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Public coordinator regressions for stable remote-result identity continuity. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteResultIdentityContinuityTest {
    @Test
    fun existingUpload_wrongFolder_retainsPendingAndAcceptedIdentity() = runTest {
        assertExistingUploadIdentity(ResultMutation.FOLDER, StandardTestDispatcher(testScheduler))
    }

    @Test
    fun existingUpload_wrongManifestFile_retainsPendingAndAcceptedIdentity() = runTest {
        assertExistingUploadIdentity(ResultMutation.MANIFEST_FILE, StandardTestDispatcher(testScheduler))
    }

    @Test
    fun existingUpload_matchingIds_updatesAcceptedCursorAndKeepsResourceIdentity() = runTest {
        assertExistingUploadIdentity(ResultMutation.NONE, StandardTestDispatcher(testScheduler))
    }

    @Test
    fun initialUpload_withoutAcceptedResource_acceptsReturnedIdentity() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = IdFactory())
        val metadata = InMemorySyncMetadataStore()
        val session = session("initial-upload")
        val scope = SyncScope("account", "root", session.token.documentId)
        val local = snapshot(session, "initial")
        val bridge = TestBridge(session, local)
        val coordinator = newCoordinator(drive, metadata, bridge, dispatcher)
        try {
            val binding = requireNotNull(coordinator.bind(scope, session.token))
            val outcome = coordinator.enqueueUpload(binding, SyncReason.MANUAL).await()

            assertTrue(outcome is SyncOutcome.Uploaded)
            val remote = (outcome as SyncOutcome.Uploaded).remote
            val accepted = requireNotNull(metadata.snapshot(scope))
            assertEquals(remote.reference.folderId, accepted.remoteReference?.folderId)
            assertEquals(remote.reference.snapshotFileId, accepted.remoteReference?.snapshotFileId)
            assertEquals(remote.cursor, accepted.acceptedCursor)
            assertNull(accepted.pendingUpload)
        } finally {
            coordinator.closeAndJoin()
        }
    }

    @Test
    fun adoption_wrongFolder_retainsCandidatePendingAndNoAcceptedIdentity() = runTest {
        assertAdoptionIdentity(ResultMutation.FOLDER, StandardTestDispatcher(testScheduler))
    }

    @Test
    fun adoption_wrongManifestFile_retainsCandidatePendingAndNoAcceptedIdentity() = runTest {
        assertAdoptionIdentity(ResultMutation.MANIFEST_FILE, StandardTestDispatcher(testScheduler))
    }

    @Test
    fun adoption_wrongAdoptedDocumentId_retainsCandidatePendingAndNoAcceptedIdentity() = runTest {
        assertAdoptionIdentity(ResultMutation.ADOPTED_DOCUMENT, StandardTestDispatcher(testScheduler))
    }

    @Test
    fun adoption_matchingIdsAndDocumentId_commitsLinkedIdentity() = runTest {
        assertAdoptionIdentity(ResultMutation.NONE, StandardTestDispatcher(testScheduler))
    }

    @Test
    fun conflictAcceptanceReplay_wrongFolderOrManifestFile_retainsAuthorityPendingAndLease() = runTest {
        listOf(ResultMutation.FOLDER, ResultMutation.MANIFEST_FILE).forEach { mutation ->
            assertConflictAcceptanceReplayIdentity(
                mutation = mutation,
                dispatcher = StandardTestDispatcher(testScheduler)
            )
        }
    }

    @Test
    fun conflictAcceptanceReplay_matchingIds_commitsLocalReplayAndRetiresLease() = runTest {
        assertConflictAcceptanceReplayIdentity(
            mutation = ResultMutation.NONE,
            dispatcher = StandardTestDispatcher(testScheduler)
        )
    }

    @Test
    fun postAdoptionReplay_wrongFolderOrManifestFile_retainsAdoptedAuthorityPendingAndLease() = runTest {
        listOf(ResultMutation.FOLDER, ResultMutation.MANIFEST_FILE).forEach { mutation ->
            assertPostAdoptionReplayIdentity(
                mutation = mutation,
                dispatcher = StandardTestDispatcher(testScheduler)
            )
        }
    }

    @Test
    fun postAdoptionReplay_matchingIds_commitsLocalReplayAndRetiresLease() = runTest {
        assertPostAdoptionReplayIdentity(
            mutation = ResultMutation.NONE,
            dispatcher = StandardTestDispatcher(testScheduler)
        )
    }

    @Test
    fun acceptedCursorWithoutReference_failsBeforeUploadAndRetainsDurablePending() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val metadata = TestPersistentPhotoMetadataStore(dispatcher = dispatcher)
        val drive = FakeDriveGateway(idFactory = IdFactory())
        val gateway = CountingGateway(drive)
        val session = session("pairless-accepted-cursor")
        val scope = SyncScope("account", "root", session.token.documentId)
        val local = photoSnapshot(session, "pairless")
        val bytes = Stage4PhotoFixture.jpegBytes()
        val assets = testPhotoAssets(mapOf("pairless.jpg" to bytes))
        val pending = DurablePendingUpload(
            reason = SyncReason.MANUAL,
            sourceUri = session.token.sourceUri,
            sourceFingerprint = null,
            generation = 1L,
            expectedCursor = null,
            snapshot = local,
            photoFiles = assets,
            pendingUploadIntent = PendingUploadIntent.AUTOMATIC_RETRY
        )
        assertEquals(
            MetadataWriteResult.Committed,
            metadata.write(
                SyncMetadata(
                    scope = scope,
                    acceptedCursor = com.example.myapplication.stage4.RemoteCursor("orphaned-cursor"),
                    remoteReference = null,
                    pendingUpload = pending
                )
            )
        )
        val bridge = TestBridge(session, local)
        var coordinator: SyncCoordinator? = null
        var loadedLease: PhotoAssetLease? = null
        try {
            coordinator = newCoordinator(gateway, metadata, bridge, dispatcher)
            val binding = requireNotNull(coordinator.bind(scope, session.token))
            val outcome = coordinator.enqueueUpload(binding, SyncReason.MANUAL).await()

            assertTrue("pairless accepted state must fail closed", outcome is SyncOutcome.Failed)
            assertEquals(
                SyncError.Kind.METADATA,
                (outcome as SyncOutcome.Failed).error.kind
            )
            assertEquals("gateway upload must not be reached", 0, gateway.uploadCalls)
            val retained = requireNotNull(metadata.snapshot(scope))
            loadedLease = retained.pendingUpload?.outboxLease
            assertEquals(com.example.myapplication.stage4.RemoteCursor("orphaned-cursor"), retained.acceptedCursor)
            assertNull(retained.remoteReference)
            assertEquals(local, retained.pendingUpload?.snapshot)
            assertEquals(assets.descriptors, retained.pendingUpload?.photoFiles?.descriptors)
        } finally {
            try {
                coordinator?.closeAndJoin()
                assertTrue(
                    "coordinator termination must release the pending outbox lease",
                    loadedLease?.isReleased == true
                )
                // Re-read the durable record through the real file-backed store;
                // the pairless cursor and recovery payload must survive shutdown.
                val reread = metadata.read(scope) as MetadataReadResult.Loaded
                val durable = requireNotNull(reread.metadata)
                try {
                    assertEquals(com.example.myapplication.stage4.RemoteCursor("orphaned-cursor"), durable.acceptedCursor)
                    assertNull(durable.remoteReference)
                    assertEquals(local, durable.pendingUpload?.snapshot)
                    assertEquals(
                        bytes.toList(),
                        durable.pendingUpload?.photoFiles?.getValue("pairless.jpg")?.open()?.use { it.readBytes().toList() }
                    )
                } finally {
                    durable.pendingUpload?.outboxLease?.close()
                }
            } finally {
                metadata.close()
            }
        }
    }

    private suspend fun assertExistingUploadIdentity(
        mutation: ResultMutation,
        dispatcher: TestDispatcher
    ) {
        val drive = FakeDriveGateway(idFactory = IdFactory())
        val metadata = InMemorySyncMetadataStore()
        val session = session("existing-upload-${mutation.name}")
        val scope = SyncScope("account", "root", session.token.documentId)
        val baseline = snapshot(session, "baseline")
        val seeded = drive.seed(scope, "plan.pdf", baseline)
        assertEquals(
            MetadataWriteResult.Committed,
            metadata.write(
                SyncMetadata(
                    scope = scope,
                    remoteReference = seeded.reference,
                    acceptedCursor = seeded.cursor
                )
            )
        )

        val bridge = TestBridge(session, snapshot(session, "local"))
        val coordinator = newCoordinator(
            IdentityMutatingGateway(drive, uploadMutation = mutation),
            metadata,
            bridge,
            dispatcher
        )
        try {
            val binding = requireNotNull(coordinator.bind(scope, session.token))
            val outcome = coordinator.enqueueUpload(binding, SyncReason.MANUAL).await()
            val accepted = requireNotNull(metadata.snapshot(scope))

            if (mutation == ResultMutation.NONE) {
                assertTrue(outcome is SyncOutcome.Uploaded)
                assertEquals(seeded.reference.folderId, accepted.remoteReference?.folderId)
                assertEquals(seeded.reference.snapshotFileId, accepted.remoteReference?.snapshotFileId)
                assertTrue(accepted.acceptedCursor != seeded.cursor)
                assertNull(accepted.pendingUpload)
            } else {
                assertValidationFailure(outcome)
                assertEquals(seeded.reference.folderId, accepted.remoteReference?.folderId)
                assertEquals(seeded.reference.snapshotFileId, accepted.remoteReference?.snapshotFileId)
                assertEquals(seeded.cursor, accepted.acceptedCursor)
                assertEquals("local", accepted.pendingUpload?.snapshot?.pages?.get(0)?.notes?.single()?.text)
                assertEquals(seeded.cursor, accepted.pendingUpload?.expectedCursor)
            }
        } finally {
            coordinator.closeAndJoin()
        }
    }

    private suspend fun assertAdoptionIdentity(
        mutation: ResultMutation,
        dispatcher: TestDispatcher
    ) {
        val drive = FakeDriveGateway(idFactory = IdFactory())
        val metadata = InMemorySyncMetadataStore()
        val fingerprint = SourceFingerprint.fromBytes("adoption-source".toByteArray())
        val remoteSession = sessionWithFingerprint(
            "adoption-remote-${mutation.name}",
            "content://remote/${mutation.name}",
            fingerprint
        )
        val localSession = sessionWithFingerprint(
            "adoption-local-${mutation.name}",
            "content://local/${mutation.name}",
            fingerprint
        )
        val root = "adoption-root-${mutation.name}"
        val remoteScope = SyncScope("account", root, remoteSession.token.documentId)
        val seeded = drive.seed(
            remoteScope,
            "plan.pdf",
            snapshot(remoteSession, "remote"),
            sourceFingerprint = fingerprint
        )
        val localScope = SyncScope("account", root, localSession.token.documentId)
        val bridge = TestBridge(localSession, snapshot(localSession, "local"))
        val coordinator = newCoordinator(
            IdentityMutatingGateway(drive, adoptionMutation = mutation),
            metadata,
            bridge,
            dispatcher
        )
        try {
            val binding = requireNotNull(coordinator.bind(localScope, localSession.token))
            val pendingOutcome = coordinator.enqueueUpload(binding, SyncReason.MANUAL).await()
            assertTrue(pendingOutcome is SyncOutcome.PendingAdoption)
            val candidate = (pendingOutcome as SyncOutcome.PendingAdoption).candidate
            assertEquals(seeded.reference.folderId, candidate.reference.folderId)
            assertEquals(seeded.reference.snapshotFileId, candidate.reference.snapshotFileId)
            assertEquals(candidate, metadata.snapshot(localScope)?.pendingAdoption)

            val outcome = coordinator.enqueueAdoptRemote(binding, candidate).await()
            val accepted = requireNotNull(metadata.snapshot(localScope))
            if (mutation == ResultMutation.NONE) {
                assertTrue(outcome is SyncOutcome.Adopted)
                assertEquals(candidate.reference.folderId, accepted.remoteReference?.folderId)
                assertEquals(candidate.reference.snapshotFileId, accepted.remoteReference?.snapshotFileId)
                assertEquals(
                    localSession.token.documentId.value,
                    accepted.remoteReference?.appProperties?.get(SYNC_DOCUMENT_ID_APP_PROPERTY)
                )
                assertEquals(candidate.remoteDocumentId, accepted.adoptedRemoteDocumentId)
                assertNull(accepted.pendingAdoption)
                assertEquals("local", accepted.pendingUpload?.snapshot?.pages?.get(0)?.notes?.single()?.text)
            } else {
                assertValidationFailure(outcome)
                assertNull(accepted.remoteReference)
                assertNull(accepted.acceptedCursor)
                assertNull(accepted.adoptedRemoteDocumentId)
                assertEquals(candidate, accepted.pendingAdoption)
                assertEquals("local", accepted.pendingUpload?.snapshot?.pages?.get(0)?.notes?.single()?.text)
            }
        } finally {
            coordinator.closeAndJoin()
        }
    }

    private suspend fun assertConflictAcceptanceReplayIdentity(
        mutation: ResultMutation,
        dispatcher: TestDispatcher
    ) {
        val metadata = TestPersistentPhotoMetadataStore(dispatcher = dispatcher)
        val drive = FakeDriveGateway(idFactory = IdFactory())
        val gateway = ReplayIdentityGateway(drive, replayMutation = mutation)
        val session = session("conflict-replay-${mutation.name}")
        val scope = SyncScope("account", "root", session.token.documentId)
        val bridge = TestBridge(session, snapshot(session, "baseline"))
        val coordinator = newCoordinator(gateway, metadata, bridge, dispatcher)
        var pendingLease: PhotoAssetLease? = null
        try {
            val binding = requireNotNull(coordinator.bind(scope, session.token))
            assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
            val seeded = drive.seed(scope, "plan.pdf", snapshot(session, "remote"))

            val local = photoSnapshot(session, "local")
            bridge.liveSnapshot = local
            bridge.photoCaptureFactory = {
                PhotoAssetCapture.of(
                    testPhotoAssets(mapOf("local.jpg" to Stage4PhotoFixture.jpegBytes()))
                )
            }
            assertTrue(
                coordinator.enqueueUpload(binding, SyncReason.MANUAL).await() is SyncOutcome.RemoteConflict
            )
            pendingLease = metadata.snapshot(scope)?.pendingUpload?.outboxLease
            assertTrue("conflicted photo work must retain an outbox lease", pendingLease != null)

            val accepted = coordinator.enqueueRemoteAcceptance(binding).await()
            assertTrue(accepted is SyncOutcome.AppliedRemote)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, gateway.replayUploadCount)
            assertEquals(seeded.cursor, gateway.replayExpectedCursor)

            val current = requireNotNull(metadata.snapshot(scope))
            if (mutation == ResultMutation.NONE) {
                assertTrue(gateway.lastReplayRemote != null)
                val finalRemote = requireNotNull(drive.record(scope))
                assertEquals(seeded.reference, finalRemote.reference)
                assertEquals(finalRemote.reference, current.remoteReference)
                assertEquals(finalRemote.cursor, current.acceptedCursor)
                assertTrue(current.acceptedCursor != seeded.cursor)
                assertEquals(local, finalRemote.snapshot)
                assertNull(current.pendingUpload)
                assertNull(current.conflictCursor)
                assertTrue(pendingLease?.isReleased == true)
            } else {
                assertTrue("the gateway must return the mutated replay envelope", gateway.lastReplayRemote != null)
                assertEquals(
                    if (mutation == ResultMutation.FOLDER) "unrelated-folder" else seeded.reference.folderId,
                    gateway.lastReplayRemote?.reference?.folderId
                )
                assertEquals(
                    if (mutation == ResultMutation.MANIFEST_FILE) "unrelated-manifest" else seeded.reference.snapshotFileId,
                    gateway.lastReplayRemote?.reference?.snapshotFileId
                )
                assertEquals(seeded.reference, current.remoteReference)
                assertEquals(seeded.cursor, current.acceptedCursor)
                assertEquals(local, current.pendingUpload?.snapshot)
                assertEquals(PendingUploadIntent.EXPLICIT_CONFLICT_REPLAY, current.pendingUpload?.pendingUploadIntent)
                assertNull(current.conflictCursor)
                assertFalse("rejected replay must retain recovery ownership", pendingLease?.isReleased == true)
            }
        } finally {
            try {
                coordinator.closeAndJoin()
                assertTrue(
                    "all replay leases must close at coordinator termination",
                    pendingLease?.isReleased == true
                )
            } finally {
                metadata.close()
            }
        }
    }

    private suspend fun assertPostAdoptionReplayIdentity(
        mutation: ResultMutation,
        dispatcher: TestDispatcher
    ) {
        val metadata = TestPersistentPhotoMetadataStore(dispatcher = dispatcher)
        val drive = FakeDriveGateway(idFactory = IdFactory())
        val gateway = ReplayIdentityGateway(drive, replayMutation = mutation)
        val fingerprint = SourceFingerprint.fromBytes("post-adoption-source".toByteArray())
        val remoteSession = sessionWithFingerprint(
            "post-adoption-remote-${mutation.name}",
            "content://post-adoption/remote/${mutation.name}",
            fingerprint
        )
        val localSession = sessionWithFingerprint(
            "post-adoption-local-${mutation.name}",
            "content://post-adoption/local/${mutation.name}",
            fingerprint
        )
        val root = "post-adoption-root-${mutation.name}"
        val remoteScope = SyncScope("account", root, remoteSession.token.documentId)
        val seeded = drive.seed(
            remoteScope,
            "plan.pdf",
            snapshot(remoteSession, "remote"),
            sourceFingerprint = fingerprint
        )
        val scope = SyncScope("account", root, localSession.token.documentId)
        val local = photoSnapshot(localSession, "local")
        val bridge = TestBridge(localSession, local)
        bridge.photoCaptureFactory = {
            PhotoAssetCapture.of(
                testPhotoAssets(mapOf("local.jpg" to Stage4PhotoFixture.jpegBytes()))
            )
        }
        val coordinator = newCoordinator(gateway, metadata, bridge, dispatcher)
        var pendingLease: PhotoAssetLease? = null
        try {
            val binding = requireNotNull(coordinator.bind(scope, localSession.token))
            val pending = coordinator.enqueueUpload(binding, SyncReason.MANUAL).await()
            assertTrue(pending is SyncOutcome.PendingAdoption)
            val candidate = (pending as SyncOutcome.PendingAdoption).candidate
            pendingLease = metadata.snapshot(scope)?.pendingUpload?.outboxLease
            assertTrue("adoption candidate must retain pending outbox ownership", pendingLease != null)

            val adopted = coordinator.enqueueAdoptRemote(binding, candidate).await()
            assertTrue(adopted is SyncOutcome.Adopted)
            val adoptedMetadata = requireNotNull(metadata.snapshot(scope))
            assertEquals(seeded.reference.folderId, adoptedMetadata.remoteReference?.folderId)
            assertEquals(seeded.reference.snapshotFileId, adoptedMetadata.remoteReference?.snapshotFileId)
            assertEquals(
                localSession.token.documentId.value,
                adoptedMetadata.remoteReference?.appProperties?.get(SYNC_DOCUMENT_ID_APP_PROPERTY)
            )
            assertEquals(seeded.cursor, adoptedMetadata.acceptedCursor)
            assertEquals(local, adoptedMetadata.pendingUpload?.snapshot)

            val replayOutcome = coordinator.enqueueUpload(binding, SyncReason.MANUAL).await()
            assertEquals(1, gateway.replayUploadCount)
            assertEquals(seeded.cursor, gateway.replayExpectedCursor)
            val current = requireNotNull(metadata.snapshot(scope))
            if (mutation == ResultMutation.NONE) {
                assertTrue(replayOutcome is SyncOutcome.Uploaded)
                assertTrue(gateway.lastReplayRemote != null)
                assertEquals(seeded.reference.folderId, current.remoteReference?.folderId)
                assertEquals(seeded.reference.snapshotFileId, current.remoteReference?.snapshotFileId)
                assertEquals(
                    localSession.token.documentId.value,
                    current.remoteReference?.appProperties?.get(SYNC_DOCUMENT_ID_APP_PROPERTY)
                )
                assertTrue(current.acceptedCursor != seeded.cursor)
                assertEquals(local, drive.record(scope)?.snapshot)
                assertNull(current.pendingUpload)
                assertTrue("accepted replay must retire its outbox lease", pendingLease?.isReleased == true)
            } else {
                assertValidationFailure(replayOutcome)
                assertTrue("the gateway must return the mutated replay envelope", gateway.lastReplayRemote != null)
                assertEquals(
                    if (mutation == ResultMutation.FOLDER) "unrelated-folder" else seeded.reference.folderId,
                    gateway.lastReplayRemote?.reference?.folderId
                )
                assertEquals(
                    if (mutation == ResultMutation.MANIFEST_FILE) "unrelated-manifest" else seeded.reference.snapshotFileId,
                    gateway.lastReplayRemote?.reference?.snapshotFileId
                )
                assertEquals(seeded.reference.folderId, current.remoteReference?.folderId)
                assertEquals(seeded.reference.snapshotFileId, current.remoteReference?.snapshotFileId)
                assertEquals(
                    localSession.token.documentId.value,
                    current.remoteReference?.appProperties?.get(SYNC_DOCUMENT_ID_APP_PROPERTY)
                )
                assertEquals(seeded.cursor, current.acceptedCursor)
                assertEquals(local, current.pendingUpload?.snapshot)
                assertEquals(PendingUploadIntent.AUTOMATIC_RETRY, current.pendingUpload?.pendingUploadIntent)
                assertFalse("rejected replay must retain recovery ownership", pendingLease?.isReleased == true)
            }
        } finally {
            try {
                coordinator.closeAndJoin()
                assertTrue(
                    "all adoption replay leases must close at coordinator termination",
                    pendingLease?.isReleased == true
                )
            } finally {
                metadata.close()
            }
        }
    }

    private fun assertValidationFailure(outcome: SyncOutcome) {
        assertTrue("expected a validation failure, got $outcome", outcome is SyncOutcome.Failed)
        assertEquals(SyncError.Kind.VALIDATION, (outcome as SyncOutcome.Failed).error.kind)
    }

    private fun newCoordinator(
        gateway: DriveGateway,
        metadata: SyncMetadataStore,
        bridge: TestBridge,
        dispatcher: TestDispatcher
    ): SyncCoordinator = SyncCoordinator(
        gateway = gateway,
        metadataStore = metadata,
        bridge = bridge,
        parentScope = CoroutineScope(dispatcher + SupervisorJob()),
        dispatcher = dispatcher
    )

    private enum class ResultMutation {
        NONE,
        FOLDER,
        MANIFEST_FILE,
        ADOPTED_DOCUMENT
    }

    private class IdentityMutatingGateway(
        private val delegate: FakeDriveGateway,
        private val uploadMutation: ResultMutation = ResultMutation.NONE,
        private val adoptionMutation: ResultMutation = ResultMutation.NONE
    ) : DriveGateway by delegate {
        override suspend fun upload(request: UploadRequest): UploadResult =
            when (val result = delegate.upload(request)) {
                is UploadResult.Uploaded -> result.copy(
                    remote = mutate(result.remote, uploadMutation)
                )
                else -> result
            }

        override suspend fun adopt(request: AdoptionRequest): AdoptionResult =
            when (val result = delegate.adopt(request)) {
                is AdoptionResult.Adopted -> result.copy(
                    remote = mutate(result.remote, adoptionMutation),
                    adoptedRemoteDocumentId = if (adoptionMutation == ResultMutation.ADOPTED_DOCUMENT) {
                        DocumentId.new()
                    } else {
                        result.adoptedRemoteDocumentId
                    }
                )
                else -> result
            }

        private fun mutate(
            remote: RemoteSnapshotEnvelope,
            mutation: ResultMutation
        ): RemoteSnapshotEnvelope = when (mutation) {
            ResultMutation.FOLDER -> remote.copy(
                reference = remote.reference.copy(folderId = "unrelated-folder")
            )
            ResultMutation.MANIFEST_FILE -> remote.copy(
                reference = remote.reference.copy(snapshotFileId = "unrelated-manifest")
            )
            else -> remote
        }

        private fun mutate(
            remote: RemoteDocumentMetadata,
            mutation: ResultMutation
        ): RemoteDocumentMetadata = when (mutation) {
            ResultMutation.FOLDER -> remote.copy(
                reference = remote.reference.copy(folderId = "unrelated-folder")
            )
            ResultMutation.MANIFEST_FILE -> remote.copy(
                reference = remote.reference.copy(snapshotFileId = "unrelated-manifest")
            )
            else -> remote
        }
    }

    /** Mutates only the first successful upload after conflict/adoption. */
    private class ReplayIdentityGateway(
        private val delegate: FakeDriveGateway,
        private val replayMutation: ResultMutation
    ) : DriveGateway by delegate {
        var replayUploadCount: Int = 0
            private set
        var replayExpectedCursor: RemoteCursor? = null
            private set
        var lastReplayRemote: RemoteSnapshotEnvelope? = null
            private set

        private var conflictObserved = false
        private var adoptionCompleted = false

        override suspend fun upload(request: UploadRequest): UploadResult {
            val result = delegate.upload(request)
            return when (result) {
                is UploadResult.Conflict -> {
                    conflictObserved = true
                    result
                }
                is UploadResult.Uploaded -> if (conflictObserved || adoptionCompleted) {
                    replayUploadCount++
                    replayExpectedCursor = request.expectedCursor
                    val mutated = result.copy(remote = mutate(result.remote, replayMutation))
                    lastReplayRemote = mutated.remote
                    mutated
                } else {
                    result
                }
                else -> result
            }
        }

        override suspend fun adopt(request: AdoptionRequest): AdoptionResult {
            val result = delegate.adopt(request)
            if (result is AdoptionResult.Adopted) adoptionCompleted = true
            return result
        }

        private fun mutate(remote: RemoteSnapshotEnvelope, mutation: ResultMutation): RemoteSnapshotEnvelope =
            when (mutation) {
                ResultMutation.FOLDER -> remote.copy(
                    reference = remote.reference.copy(folderId = "unrelated-folder")
                )
                ResultMutation.MANIFEST_FILE -> remote.copy(
                    reference = remote.reference.copy(snapshotFileId = "unrelated-manifest")
                )
                else -> remote
            }
    }

    private class CountingGateway(private val delegate: FakeDriveGateway) : DriveGateway by delegate {
        var uploadCalls: Int = 0
            private set

        override suspend fun upload(request: UploadRequest): UploadResult {
            uploadCalls++
            return delegate.upload(request)
        }
    }

    private class TestBridge(
        private val session: DocumentSession,
        var liveSnapshot: DocumentSnapshotV1,
        private var durableSnapshot: DocumentSnapshotV1 = liveSnapshot
    ) : SyncSessionBridge {
        var photoCaptureFactory: (DocumentSnapshotV1) -> PhotoAssetCapture = {
            PhotoAssetCapture.empty()
        }

        override fun currentSession(scope: SyncScope): DocumentSession? =
            session.takeIf { it.token.documentId == scope.documentId }

        override suspend fun captureSnapshot(session: DocumentSession): DocumentSnapshotV1 = liveSnapshot

        override suspend fun captureDurableSnapshot(session: DocumentSession): DocumentSnapshotV1 = durableSnapshot

        override suspend fun persistSnapshot(
            session: DocumentSession,
            snapshot: DocumentSnapshotV1
        ): DocumentSaveResult {
            durableSnapshot = snapshot
            return DocumentSaveResult.Saved(session.token.documentId)
        }

        override fun isCurrent(token: DocumentSessionToken): Boolean = token == session.token

        override fun hasRequiredPhotoContent(snapshot: DocumentSnapshotV1): Boolean = true

        override suspend fun capturePhotoContent(snapshot: DocumentSnapshotV1): PhotoAssetCapture =
            photoCaptureFactory(snapshot)

        override suspend fun capturePhotoContentForAdmission(
            session: DocumentSession,
            currentDurableSnapshot: DocumentSnapshotV1,
            currentLiveSnapshot: DocumentSnapshotV1
        ): PhotoAssetCapture = photoCaptureFactory(currentLiveSnapshot)

        override suspend fun hasRequiredPhotoContentForAdmission(
            session: DocumentSession,
            currentDurableSnapshot: DocumentSnapshotV1,
            currentLiveSnapshot: DocumentSnapshotV1
        ): Boolean = true

        override suspend fun persistPhotoContent(
            session: DocumentSession,
            remote: RemoteSnapshotEnvelope
        ): DocumentSaveResult = DocumentSaveResult.Saved(session.token.documentId)

        override fun applySnapshotReplace(session: DocumentSession, snapshot: DocumentSnapshotV1) {
            liveSnapshot = snapshot
        }
    }

    private class IdFactory : () -> String {
        private var next = 0

        override fun invoke(): String = "id-${next++}"
    }

    private fun session(id: String): DocumentSession {
        val documentId = DocumentId.new()
        val source = DocumentSourceIdentityV1("content://$id", "plan.pdf")
        return DocumentSession(
            target = ResolvedDocumentTarget(DocumentAssociation(documentId, source, null)),
            token = DocumentSessionToken(documentId, source.sourceUri, null, 1L)
        )
    }

    private fun sessionWithFingerprint(
        id: String,
        sourceUri: String,
        fingerprint: SourceFingerprint
    ): DocumentSession {
        val documentId = DocumentId.new()
        val source = DocumentSourceIdentityV1(sourceUri, "plan.pdf")
        return DocumentSession(
            target = ResolvedDocumentTarget(DocumentAssociation(documentId, source, fingerprint)),
            token = DocumentSessionToken(documentId, source.sourceUri, fingerprint, 1L)
        )
    }

    private fun snapshot(session: DocumentSession, marker: String): DocumentSnapshotV1 =
        DocumentSnapshotV1(
            schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
            snapshotRevision = 0L,
            source = session.target.association.source,
            pages = mapOf(
                0 to PageSnapshotV1(
                    notes = listOf(
                        NoteSnapshotV1(
                            x = 0.1f,
                            y = 0.2f,
                            text = marker,
                            isBold = false,
                            rotation = 0f,
                            fontSizeRatio = 0.05f,
                            id = "note-$marker"
                        )
                    )
                )
            )
        )

    private fun photoSnapshot(session: DocumentSession, marker: String): DocumentSnapshotV1 =
        DocumentSnapshotV1(
            schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
            snapshotRevision = 0L,
            source = session.target.association.source,
            pages = mapOf(
                0 to PageSnapshotV1(
                    photoPins = listOf(
                        PhotoPinSnapshotV1(
                            x = 0.2f,
                            y = 0.3f,
                            id = "photo-pin-$marker",
                            imageFileNames = listOf("$marker.jpg"),
                            imageNotes = emptyMap(),
                            imageShapes = emptyMap()
                        )
                    )
                )
            )
        )
}
