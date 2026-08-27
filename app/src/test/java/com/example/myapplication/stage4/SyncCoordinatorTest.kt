package com.example.myapplication.stage4

import com.example.myapplication.stage5.CloseEnforcingPhotoPathOperationsFactory
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import com.example.myapplication.stage5.PhotoCanonicalIdentity
import com.example.myapplication.stage5.PhotoCanonicalRecoveryException
import com.example.myapplication.stage5.PhotoCanonicalRecoveryMode
import com.example.myapplication.stage5.DocumentPhotoAssetStore
import com.example.myapplication.stage5.Stage5ValidationException
import com.example.myapplication.stage5.photoCanonicalIdentity
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.DrawnPathSnapshotV1
import com.example.myapplication.stage1.MeasurementSnapshotV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PageScaleSnapshotV1
import com.example.myapplication.stage1.NoteSnapshotV1
import com.example.myapplication.stage1.PhotoImageNoteSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage1.PointSnapshotV1
import com.example.myapplication.stage1.ShapeSnapshotV1
import com.example.myapplication.stage1.SnapshotShapeTypeV1
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage3.DocumentSession
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.ResolvedDocumentTarget
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.api.services.drive.Drive
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/* Source-compatibility helpers for the pre-binding characterization cases.
 * Production routes use the explicit SyncBinding overloads directly. */
private fun SyncCoordinator.enqueueUpload(
    scope: SyncScope,
    token: DocumentSessionToken,
    reason: SyncReason
): Deferred<SyncOutcome> = enqueueUpload(requireNotNull(bind(scope, token)), reason)

private fun SyncCoordinator.enqueueRemoteCheck(
    scope: SyncScope,
    token: DocumentSessionToken,
    reason: SyncReason = SyncReason.REMOTE_CHECK
): Deferred<SyncOutcome> = enqueueRemoteCheck(requireNotNull(bind(scope, token)), reason)

private fun SyncCoordinator.enqueueRemoteAcceptance(
    scope: SyncScope,
    token: DocumentSessionToken
): Deferred<SyncOutcome> = enqueueRemoteAcceptance(requireNotNull(bind(scope, token)))

@OptIn(ExperimentalCoroutinesApi::class)
class SyncCoordinatorTest {
    private val coordinators = mutableListOf<SyncCoordinator>()

    @After
    fun closeCoordinators() {
        coordinators.forEach { it.close() }
    }

    @Test
    fun sameNameDocuments_useIndependentRemoteIdsPropertiesAndDownloads() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val a = session("plan-a", "plan.pdf")
        val b = session("plan-b", "plan.pdf")
        bridge.setSession(a, snapshot(a, "A"))
        val aScope = scope(a, "account", "root")
        val aUpload = coordinator.enqueueUpload(aScope, a.token, SyncReason.IMMEDIATE).await()
        assertTrue(aUpload is SyncOutcome.Uploaded)

        bridge.setSession(b, snapshot(b, "B"))
        val bScope = scope(b, "account", "root")
        val bUpload = coordinator.enqueueUpload(bScope, b.token, SyncReason.MANUAL).await()
        assertTrue(bUpload is SyncOutcome.Uploaded)

        val aRemote = (drive.download(aScope, (aUpload as SyncOutcome.Uploaded).remote.reference) as DownloadResult.Downloaded).remote
        val bRemote = (drive.download(bScope, (bUpload as SyncOutcome.Uploaded).remote.reference) as DownloadResult.Downloaded).remote
        assertNotEquals(aRemote.reference.folderId, bRemote.reference.folderId)
        assertNotEquals(aRemote.reference.snapshotFileId, bRemote.reference.snapshotFileId)
        assertEquals(a.documentId(), aRemote.reference.appProperties[SYNC_DOCUMENT_ID_APP_PROPERTY])
        assertEquals(b.documentId(), bRemote.reference.appProperties[SYNC_DOCUMENT_ID_APP_PROPERTY])
        assertEquals("A", aRemote.snapshot.pages.getValue(0).notes.single().text)
        assertEquals("B", bRemote.snapshot.pages.getValue(0).notes.single().text)

        bridge.setSession(a, snapshot(a, "A-updated"))
        val aSecondUpload = coordinator.enqueueUpload(aScope, a.token, SyncReason.DEBOUNCED).await()
        assertTrue(aSecondUpload is SyncOutcome.Uploaded)
        val aSecondRemote = (aSecondUpload as SyncOutcome.Uploaded).remote
        assertEquals(aRemote.reference.folderId, aSecondRemote.reference.folderId)
        assertEquals(aRemote.reference.snapshotFileId, aSecondRemote.reference.snapshotFileId)
    }

    @Test
    fun sameControlledSourceAcrossDeviceIds_requiresExplicitAdoption_andDoesNotDuplicate() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val fingerprint = SourceFingerprint.fromBytes("controlled-source".toByteArray())
        val deviceA = sessionWithFingerprint("device-a", "content://provider/a", fingerprint)
        val deviceB = sessionWithFingerprint("device-b", "content://provider/b", fingerprint)
        val sharedRoot = "root-shared"
        val remoteScope = scope(deviceA, "account", sharedRoot)
        bridge.setSession(deviceA, snapshot(deviceA, "remote-device-a"))
        val seeded = drive.seed(
            remoteScope,
            "plan.pdf",
            snapshot(deviceA, "remote-device-a"),
            sourceFingerprint = fingerprint
        )
        val foldersBefore = drive.createdFolderCount.get()

        bridge.setSession(deviceB, snapshot(deviceB, "local-device-b"))
        val localScope = scope(deviceB, "account", sharedRoot)
        val outcome = coordinator.enqueueUpload(localScope, deviceB.token, SyncReason.MANUAL).await()

        assertTrue(outcome is SyncOutcome.PendingAdoption)
        val pending = outcome as SyncOutcome.PendingAdoption
        assertEquals(seeded.reference.folderId, pending.candidate.reference.folderId)
        assertEquals(deviceA.documentId(), pending.candidate.remoteDocumentId.value)
        assertEquals(foldersBefore, drive.createdFolderCount.get())
        assertNull(drive.record(localScope))
        assertEquals(pending.candidate, metadata.snapshot(localScope)?.pendingAdoption)
    }

    @Test
    fun explicitAdoption_linksStableRemoteIds_thenAcceptanceUsesTheLinkedDocument() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val fingerprint = SourceFingerprint.fromBytes("controlled-source".toByteArray())
        val deviceA = sessionWithFingerprint("device-a", "content://provider/a", fingerprint)
        val deviceB = sessionWithFingerprint("device-b", "content://provider/b", fingerprint)
        val root = "root-adoption"
        val remoteScope = scope(deviceA, "account", root)
        bridge.setSession(deviceA, snapshot(deviceA, "remote"))
        val seeded = drive.seed(
            remoteScope,
            "plan.pdf",
            snapshot(deviceA, "remote"),
            sourceFingerprint = fingerprint
        )

        bridge.setSession(deviceB, snapshot(deviceB, "local"))
        val localScope = scope(deviceB, "account", root)
        val binding = requireNotNull(coordinator.bind(localScope, deviceB.token))
        val pending = coordinator.enqueueUpload(binding, SyncReason.MANUAL).await()
            as SyncOutcome.PendingAdoption
        assertEquals(pending.candidate, metadata.snapshot(localScope)?.pendingAdoption)

        val adopted = coordinator.enqueueAdoptRemote(binding, pending.candidate).await()
        assertTrue(adopted is SyncOutcome.Adopted)
        val accepted = coordinator.enqueueRemoteAcceptance(binding).await()
        assertTrue(accepted is SyncOutcome.AppliedRemote)
        advanceUntilIdle()

        val linked = requireNotNull(drive.record(localScope))
        assertEquals(seeded.reference.folderId, linked.reference.folderId)
        assertEquals(seeded.reference.snapshotFileId, linked.reference.snapshotFileId)
        assertEquals(deviceB.documentId(), linked.reference.appProperties[SYNC_DOCUMENT_ID_APP_PROPERTY])
        assertEquals(DocumentId.parse(deviceA.documentId()), metadata.snapshot(localScope)?.adoptedRemoteDocumentId)
        assertNull(metadata.snapshot(localScope)?.pendingAdoption)
        assertEquals("remote", bridge.liveSnapshot.pages.getValue(0).notes.single().text)
    }

    @Test
    fun explicitAdoption_rejectsWrongFingerprint_withoutRemoteMutation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val fingerprint = SourceFingerprint.fromBytes("controlled-source".toByteArray())
        val wrongFingerprint = SourceFingerprint.fromBytes("different-source".toByteArray())
        val deviceA = sessionWithFingerprint("device-a", "content://provider/a", fingerprint)
        val deviceB = sessionWithFingerprint("device-b", "content://provider/b", fingerprint)
        val deviceC = sessionWithFingerprint("device-c", "content://provider/c", wrongFingerprint)
        val root = "root-adoption-wrong-fingerprint"
        val remoteScope = scope(deviceA, "account", root)
        bridge.setSession(deviceA, snapshot(deviceA, "remote"))
        drive.seed(remoteScope, "plan.pdf", snapshot(deviceA, "remote"), sourceFingerprint = fingerprint)
        bridge.setSession(deviceB, snapshot(deviceB, "local"))
        val localBinding = requireNotNull(coordinator.bind(scope(deviceB, "account", root), deviceB.token))
        val pending = coordinator.enqueueUpload(localBinding, SyncReason.MANUAL).await()
            as SyncOutcome.PendingAdoption
        val adoptsBefore = drive.calls.count { it.operation == "adopt" }

        bridge.setSession(deviceC, snapshot(deviceC, "wrong-local"))
        val wrongBinding = requireNotNull(coordinator.bind(scope(deviceC, "account", root), deviceC.token))
        val rejected = coordinator.enqueueAdoptRemote(wrongBinding, pending.candidate).await()

        assertTrue(rejected is SyncOutcome.Failed)
        assertEquals(adoptsBefore, drive.calls.count { it.operation == "adopt" })
        assertNotNull(drive.record(remoteScope))
        assertNull(drive.record(scope(deviceC, "account", root)))
    }

    @Test
    fun adoption_andSameDocumentUpload_shareTheDocumentMutex_andQueueInOrder() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val fingerprint = SourceFingerprint.fromBytes("controlled-source".toByteArray())
        val remoteDevice = sessionWithFingerprint("adoption-remote", "content://device-a/source", fingerprint)
        val localDevice = sessionWithFingerprint("adoption-local", "content://device-b/source", fingerprint)
        val root = "root-adoption-mutex"
        val remoteScope = scope(remoteDevice, "account", root)
        bridge.setSession(remoteDevice, snapshot(remoteDevice, "remote"))
        drive.seed(remoteScope, "plan.pdf", snapshot(remoteDevice, "remote"), sourceFingerprint = fingerprint)
        bridge.setSession(localDevice, snapshot(localDevice, "local"))
        val localScope = scope(localDevice, "account", root)
        val binding = requireNotNull(coordinator.bind(localScope, localDevice.token))
        val pending = coordinator.enqueueUpload(binding, SyncReason.MANUAL).await() as SyncOutcome.PendingAdoption

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        drive.beforeAdopt = {
            entered.complete(Unit)
            release.await()
        }
        val adoption = coordinator.enqueueAdoptRemote(binding, pending.candidate)
        runCurrent()
        entered.await()
        val callsBeforeQueuedUpload = drive.calls.toList()
        val queuedUpload = coordinator.enqueueUpload(binding, SyncReason.PERIODIC)
        runCurrent()
        assertEquals(callsBeforeQueuedUpload, drive.calls.toList())
        assertFalse(queuedUpload.isCompleted)

        release.complete(Unit)
        val adopted = adoption.await()
        val uploaded = queuedUpload.await()
        assertTrue(adopted is SyncOutcome.Adopted)
        assertTrue("adopted=$adopted uploaded=$uploaded", uploaded is SyncOutcome.Uploaded)
        assertEquals(1, drive.maxConcurrentFinalCommits)
        assertEquals(localDevice.documentId(), drive.record(localScope)?.reference?.appProperties?.get(SYNC_DOCUMENT_ID_APP_PROPERTY))
    }

    @Test
    fun conflictAcceptance_replaysDurablyPreservedLocalSnapshotAndPhotoBytes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("conflict-pending-local", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "initial"))
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        val acceptedBefore = requireNotNull(metadata.snapshot(syncScope)?.acceptedCursor)

        val remoteSnapshot = snapshotWithPhoto(session, "remote")
        val remote = drive.seed(
            syncScope,
            "plan.pdf",
            remoteSnapshot,
            photoFiles = mapOf("remote.jpg" to Stage4PhotoFixture.jpegBytes())
        )
        val localSnapshot = snapshotWithPhoto(session, "local")
        bridge.liveSnapshot = localSnapshot
        bridge.capturedPhotoContent = mapOf("local.jpg" to Stage4PhotoFixture.jpegBytes())
        bridge.persistedSnapshots.clear()

        val conflict = coordinator.enqueueUpload(binding, SyncReason.MANUAL).await()
        assertTrue(conflict is SyncOutcome.RemoteConflict)
        assertEquals(localSnapshot, bridge.persistedSnapshots.last())
        assertEquals(remote.cursor, metadata.snapshot(syncScope)?.conflictCursor)
        assertEquals(acceptedBefore, metadata.snapshot(syncScope)?.acceptedCursor)
        assertEquals(localSnapshot, metadata.snapshot(syncScope)?.pendingUpload?.snapshot)

        // Recreate the coordinator before acceptance.  The replay payload must
        // come from the scoped durable sidecar, not from remote-applied memory.
        coordinator.closeAndJoin()
        val recreated = coordinator(drive, metadata, bridge, dispatcher)
        val rebound = requireNotNull(recreated.bind(syncScope, session.token))
        val accepted = recreated.enqueueRemoteAcceptance(rebound).await()
        assertTrue(accepted is SyncOutcome.AppliedRemote)
        advanceUntilIdle()

        val finalRemote = requireNotNull(drive.record(syncScope))
        assertEquals(localSnapshot, finalRemote.snapshot)
        assertEquals(Stage4PhotoFixture.jpegBytes().toList(), finalRemote.photoFiles["local.jpg"]?.toList())
        assertEquals(finalRemote.cursor, metadata.snapshot(syncScope)?.acceptedCursor)
        assertNull(metadata.snapshot(syncScope)?.conflictCursor)
        assertEquals("local", bridge.liveSnapshot.pages.getValue(0).photoPins.single().id.removePrefix("photo-pin-"))
    }

    @Test
    fun queuedConflictRoutes_andRecreatedCoordinator_replayTheOriginalPendingPayloadOnlyForNewBinding() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("queued-pending-replay", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val baseline = snapshot(session, "baseline")
        bridge.setSession(session, baseline)
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)

        val remote = drive.seed(syncScope, "plan.pdf", snapshot(session, "remote"))
        val local = snapshotWithPhoto(session, "queued-local")
        bridge.liveSnapshot = local
        bridge.capturedPhotoContent = mapOf("queued-local.jpg" to Stage4PhotoFixture.jpegBytes())

        // Both routes are admitted before the worker drains them.  The
        // second route must observe the first route's durable pending record,
        // not replace it with a fresh capture or the remote snapshot.
        val first = coordinator.enqueueUpload(binding, SyncReason.MANUAL)
        val second = coordinator.enqueueUpload(binding, SyncReason.DEBOUNCED)
        assertTrue(first.await() is SyncOutcome.RemoteConflict)
        assertTrue(second.await() is SyncOutcome.BlockedByConflict)
        assertEquals(local, metadata.snapshot(syncScope)?.pendingUpload?.snapshot)
        assertEquals(remote.cursor, metadata.snapshot(syncScope)?.conflictCursor)

        coordinator.closeAndJoin()
        val recreated = coordinator(drive, metadata, bridge, dispatcher)
        assertTrue(recreated.enqueueRemoteAcceptance(binding).await() is SyncOutcome.StaleSession)
        val rebound = requireNotNull(recreated.bind(syncScope, session.token))
        assertTrue(recreated.enqueueRemoteAcceptance(rebound).await() is SyncOutcome.AppliedRemote)
        advanceUntilIdle()

        assertEquals(local, drive.record(syncScope)?.snapshot)
        assertEquals(Stage4PhotoFixture.jpegBytes().toList(), drive.record(syncScope)?.photoFiles?.get("queued-local.jpg")?.toList())
        assertEquals(drive.record(syncScope)?.cursor, metadata.snapshot(syncScope)?.acceptedCursor)
        assertNull(metadata.snapshot(syncScope)?.pendingUpload)
        assertNull(metadata.snapshot(syncScope)?.conflictCursor)
    }

    @Test
    fun remotePhotoApplyFailure_rollsBackPublishedBytesAndLeavesAcceptanceUnchanged() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("photo-apply-rollback", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "local"))
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        val acceptedBefore = metadata.snapshot(syncScope)?.acceptedCursor
        val remote = drive.seed(
            syncScope,
            "plan.pdf",
            snapshotWithPhoto(session, "remote"),
            photoFiles = mapOf("remote.jpg" to Stage4PhotoFixture.jpegBytes())
        )
        assertTrue(coordinator.enqueueRemoteCheck(binding).await() is SyncOutcome.RemoteConflict)

        val root = java.nio.file.Files.createTempDirectory("stage4-photo-apply").toFile()
        try {
            java.io.File(root, "remote.jpg").writeBytes("old-photo".toByteArray())
            bridge.preparedPhotoTransaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("remote.jpg" to Stage4PhotoFixture.jpegBytes()),
                TestPhotoPathOperationsFactory
            )
            bridge.failApply = true
            bridge.events.clear()

            val failed = coordinator.enqueueRemoteAcceptance(binding).await()

            assertTrue(failed is SyncOutcome.Failed)
            assertEquals("old-photo", java.io.File(root, "remote.jpg").readText())
            assertEquals("local", bridge.liveSnapshot.pages.getValue(0).notes.single().text)
            assertEquals(snapshot(session, "local"), bridge.durableSnapshot(DocumentId.parse(session.documentId())))
            assertEquals(acceptedBefore, metadata.snapshot(syncScope)?.acceptedCursor)
            assertEquals(remote.cursor, metadata.snapshot(syncScope)?.conflictCursor)
            assertTrue(bridge.events.none { it == "apply" })
            assertTrue(bridge.events.none { it == "post-cleanup" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun remotePhotoCommitMarkerFailure_rollsBackCanonicalPhotoAndMetadataWithLiveResolver() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("photo-commit-marker-rollback", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val localSnapshot = snapshot(session, "local")
        bridge.setSession(session, localSnapshot)
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        val acceptedBefore = requireNotNull(metadata.snapshot(syncScope)?.acceptedCursor)
        val remoteSnapshot = snapshotWithPhoto(session, "remote")
        val remote = drive.seed(
            syncScope,
            "plan.pdf",
            remoteSnapshot,
            photoFiles = mapOf("remote.jpg" to Stage4PhotoFixture.jpegBytes())
        )
        assertTrue(coordinator.enqueueRemoteCheck(binding).await() is SyncOutcome.RemoteConflict)

        val root = java.nio.file.Files.createTempDirectory("stage4-photo-commit-marker").toFile()
        val factory = CloseEnforcingPhotoPathOperationsFactory(failCommitMarker = true)
        try {
            java.io.File(root, "remote.jpg").writeBytes("old-photo".toByteArray())
            bridge.preparedPhotoTransaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("remote.jpg" to Stage4PhotoFixture.jpegBytes()),
                factory
            )

            val failed = coordinator.enqueueRemoteAcceptance(binding).await()

            assertTrue(failed is SyncOutcome.Failed)
            assertEquals(
                SyncError.Kind.LOCAL_PERSISTENCE,
                (failed as SyncOutcome.Failed).error.kind
            )
            assertEquals("old-photo", java.io.File(root, "remote.jpg").readText())
            assertEquals(localSnapshot, bridge.liveSnapshot)
            assertEquals(localSnapshot, bridge.durableSnapshot(DocumentId.parse(session.documentId())))
            assertEquals(acceptedBefore, metadata.snapshot(syncScope)?.acceptedCursor)
            assertEquals(remote.cursor, metadata.snapshot(syncScope)?.conflictCursor)
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") })
            assertEquals(1, factory.opened)
            assertEquals(1, factory.closed)
            assertEquals(0, factory.usedAfterClose)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun remoteAcceptance_metadataPhaseFailure_rollsBackBeforePhotoCommitAndKeepsMetadataOld() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("photo-metadata-phase-failure", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val local = snapshot(session, "local")
        bridge.setSession(session, local)
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        val acceptedBefore = requireNotNull(metadata.snapshot(syncScope)?.acceptedCursor)
        val remote = drive.seed(
            syncScope,
            "plan.pdf",
            snapshotWithPhoto(session, "remote"),
            photoFiles = mapOf("remote.jpg" to Stage4PhotoFixture.jpegBytes())
        )
        assertTrue(coordinator.enqueueRemoteCheck(binding).await() is SyncOutcome.RemoteConflict)
        bridge.events.clear()
        bridge.preparedPhotoTransaction = object : PhotoContentTransaction {
            override suspend fun prepareCanonicalRecovery(
                previous: PhotoCanonicalIdentity,
                intended: PhotoCanonicalIdentity,
                mode: PhotoCanonicalRecoveryMode
            ) {
                bridge.events += "intent"
            }

            override suspend fun publish() {
                bridge.events += "publish"
            }

            override suspend fun markMetadataCommitted() {
                bridge.events += "metadata-phase"
                throw PhotoCanonicalRecoveryException("injected metadata phase failure")
            }

            override suspend fun commit() {
                bridge.events += "commit"
            }

            override suspend fun rollback() {
                bridge.events += "rollback"
            }
        }

        val failed = coordinator.enqueueRemoteAcceptance(binding).await()

        assertTrue(failed is SyncOutcome.Failed)
        assertEquals(SyncError.Kind.RECOVERY, (failed as SyncOutcome.Failed).error.kind)
        assertTrue(bridge.events.indexOf("intent") >= 0)
        assertTrue(bridge.events.indexOf("publish") > bridge.events.indexOf("intent"))
        assertTrue(bridge.events.indexOf("apply") > bridge.events.indexOf("publish"))
        assertTrue(bridge.events.indexOf("metadata-phase") > bridge.events.indexOf("apply"))
        assertTrue(bridge.events.indexOf("rollback") > bridge.events.indexOf("metadata-phase"))
        assertTrue("metadata-phase failure must not commit photos", bridge.events.none { it == "commit" })
        assertEquals(local, bridge.durableSnapshot(session.token.documentId))
        assertEquals(local, bridge.liveSnapshot)
        assertEquals(acceptedBefore, metadata.snapshot(syncScope)?.acceptedCursor)
        assertEquals(remote.cursor, metadata.snapshot(syncScope)?.conflictCursor)
    }

    @Test
    fun uploadPhotoAdmission_keepsDurableAndLiveSnapshotsDistinct() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("photo-admission-authorities", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val durable = snapshotWithPhoto(session, "durable")
        val live = snapshotWithPhoto(session, "live")
        bridge.setSession(session, durable)
        bridge.liveSnapshot = live
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))

        assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        assertTrue(bridge.photoAdmissionSnapshots.isNotEmpty())
        assertTrue(bridge.photoAdmissionSnapshots.all { (observedDurable, observedLive) ->
            observedDurable == durable && observedLive == live
        })
    }

    @Test
    fun uploadPhotoAdmission_failsClosedWhenDurableSnapshotCannotBeCaptured() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("photo-admission-no-durable", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshotWithPhoto(session, "live"))
        bridge.failDurableCapture = true
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))

        val outcome = coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await()

        assertTrue(outcome is SyncOutcome.Failed)
        assertEquals(SyncError.Kind.LOCAL_PERSISTENCE, (outcome as SyncOutcome.Failed).error.kind)
        assertTrue(bridge.photoAdmissionSnapshots.isEmpty())
        assertTrue(bridge.persistedSnapshots.isEmpty())
    }

    @Test
    fun uploadPhotoAdmission_recoveryFailurePublishesTypedErrorBeforeRemoteMutation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("photo-admission-recovery", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshotWithPhoto(session, "recovery"))
        bridge.admissionFailure = PhotoCanonicalRecoveryException("ambiguous photo recovery")
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))

        val outcome = coordinator.enqueueUpload(binding, SyncReason.PHOTO).await()

        assertTrue(outcome is SyncOutcome.Failed)
        assertEquals(SyncError.Kind.RECOVERY, (outcome as SyncOutcome.Failed).error.kind)
        assertTrue(coordinator.status(syncScope)?.state is SyncState.Error)
        assertEquals(SyncError.Kind.RECOVERY, bridge.errors.single().kind)
        assertTrue(drive.calls.none { it.operation == "upload" })
        assertNull(metadata.snapshot(syncScope))
    }

    @Test
    fun uploadPhotoAdmission_validationFailurePublishesTypedErrorBeforeRemoteMutation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("photo-admission-validation", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshotWithPhoto(session, "validation"))
        bridge.admissionFailure = Stage5ValidationException("invalid required photo")
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))

        val outcome = coordinator.enqueueUpload(binding, SyncReason.PHOTO).await()

        assertTrue(outcome is SyncOutcome.Failed)
        assertEquals(SyncError.Kind.VALIDATION, (outcome as SyncOutcome.Failed).error.kind)
        assertTrue(coordinator.status(syncScope)?.state is SyncState.Error)
        assertEquals(SyncError.Kind.VALIDATION, bridge.errors.single().kind)
        assertTrue(drive.calls.none { it.operation == "upload" })
        assertNull(metadata.snapshot(syncScope))
    }

    @Test
    fun uploadPhotoAdmission_cancellationIsPreservedAndDoesNotPublishError() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("photo-admission-canceled", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshotWithPhoto(session, "canceled"))
        bridge.admissionFailure = CancellationException("photo admission canceled")
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))

        val request = coordinator.enqueueUpload(binding, SyncReason.PHOTO)
        val failure = runCatching { request.await() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(request.isCancelled)
        assertTrue(drive.calls.none { it.operation == "upload" })
        assertTrue(bridge.errors.isEmpty())
        assertNull(metadata.snapshot(syncScope))
    }

    @Test
    fun uploadPhotoAdmission_readsLegacyBytesButPersistenceFailurePublishesNoTarget() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("photo-admission-legacy-read", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val live = snapshotWithPhoto(session, "legacy-admission")
        val name = "legacy-admission.jpg"
        val filesRoot = java.nio.file.Files.createTempDirectory("stage4-legacy-admission-files").toFile()
        val legacyRoot = java.nio.file.Files.createTempDirectory("stage4-legacy-admission-legacy").toFile()
        val store = DocumentPhotoAssetStore(
            filesRoot,
            session.token.documentId,
            com.example.myapplication.stage5.DefaultImageProbe,
            TestPhotoPathOperationsFactory
        )
        try {
            val bytes = Stage4PhotoFixture.jpegBytes()
            java.io.File(legacyRoot, name).writeBytes(bytes)
            bridge.setSession(session, live)
            bridge.failPersist = true
            bridge.admissionCaptureHook = { _, currentLive ->
                store.readPhotoContentForAdmission(currentLive, legacyRoot)
            }
            val binding = requireNotNull(coordinator.bind(syncScope, session.token))

            val outcome = coordinator.enqueueUpload(binding, SyncReason.PHOTO).await()

            assertTrue(outcome is SyncOutcome.Failed)
            assertEquals(SyncError.Kind.LOCAL_PERSISTENCE, (outcome as SyncOutcome.Failed).error.kind)
            assertEquals(bytes.toList(), store.readPhotoContentForAdmission(live, legacyRoot).getValue(name).toList())
            assertTrue(store.resolveForRead(name) == null)
            assertTrue(java.io.File(legacyRoot, name).isFile)
        } finally {
            store.close()
            filesRoot.deleteRecursively()
            legacyRoot.deleteRecursively()
        }
    }

    @Test
    fun photoUpload_runsPostPersistCleanupOnlyAfterCanonicalPersistence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("photo-post-persist-cleanup", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshotWithPhoto(session, "post-persist"))
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))

        assertTrue(coordinator.enqueueUpload(binding, SyncReason.PHOTO).await() is SyncOutcome.Uploaded)

        assertTrue(bridge.events.indexOf("persist") >= 0)
        assertTrue(bridge.events.indexOf("post-cleanup") > bridge.events.indexOf("persist"))
    }

    @Test
    fun remoteAcceptance_recordsCanonicalIntentBeforePhotoPublishAndApply() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("photo-intent-order", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val local = snapshot(session, "local")
        bridge.setSession(session, local)
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        drive.seed(
            syncScope,
            "plan.pdf",
            snapshotWithPhoto(session, "remote"),
            photoFiles = mapOf("remote.jpg" to Stage4PhotoFixture.jpegBytes())
        )
        assertTrue(coordinator.enqueueRemoteCheck(binding).await() is SyncOutcome.RemoteConflict)
        bridge.events.clear()
        bridge.preparedPhotoTransaction = object : PhotoContentTransaction {
            override suspend fun prepareCanonicalRecovery(
                previous: PhotoCanonicalIdentity,
                intended: PhotoCanonicalIdentity
            ) {
                bridge.events += "intent"
            }

            override suspend fun publish() {
                bridge.events += "publish"
            }

            override suspend fun commit() {
                bridge.events += "commit"
            }

            override suspend fun rollback() {
                bridge.events += "rollback"
            }
        }

        assertTrue(coordinator.enqueueRemoteAcceptance(binding).await() is SyncOutcome.AppliedRemote)
        assertTrue(bridge.events.indexOf("intent") >= 0)
        assertTrue(bridge.events.indexOf("publish") > bridge.events.indexOf("intent"))
        assertTrue(bridge.events.indexOf("apply") > bridge.events.indexOf("publish"))
        assertTrue(bridge.events.indexOf("commit") > bridge.events.indexOf("apply"))
        assertTrue(bridge.events.indexOf("post-cleanup") > bridge.events.indexOf("commit"))
    }

    @Test
    fun remoteAcceptance_journalsDistinctDurableAndLivePriorAuthorities() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("photo-distinct-prior-authorities", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val durableBefore = snapshot(session, "durable-before")
        bridge.setSession(session, durableBefore)
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)

        val liveBefore = snapshot(session, "unsaved-live-before")
        bridge.liveSnapshot = liveBefore
        val remote = drive.seed(
            syncScope,
            "plan.pdf",
            snapshotWithPhoto(session, "remote"),
            photoFiles = mapOf("remote.jpg" to Stage4PhotoFixture.jpegBytes())
        )
        assertTrue(coordinator.enqueueRemoteCheck(binding).await() is SyncOutcome.RemoteConflict)

        var capturedDurable: PhotoCanonicalIdentity? = null
        var capturedLive: PhotoCanonicalIdentity? = null
        bridge.preparedPhotoTransaction = object : PhotoContentTransaction {
            override suspend fun prepareCanonicalRecovery(
                previousDurable: PhotoCanonicalIdentity,
                previousLive: PhotoCanonicalIdentity,
                intended: PhotoCanonicalIdentity,
                mode: PhotoCanonicalRecoveryMode
            ) {
                capturedDurable = previousDurable
                capturedLive = previousLive
            }

            override suspend fun publish() = Unit

            override suspend fun markMetadataCommitted() = Unit

            override suspend fun commit() = Unit

            override suspend fun rollback() = Unit
        }

        val accepted = coordinator.enqueueRemoteAcceptance(binding).await()

        assertTrue("remote acceptance should retain the typed success path", accepted is SyncOutcome.AppliedRemote)
        assertEquals(
            "durableBefore=${photoCanonicalIdentity(syncScope.documentId, durableBefore)} " +
                "liveBefore=${photoCanonicalIdentity(syncScope.documentId, liveBefore)} " +
                "durableMap=${bridge.durableSnapshot(session.token.documentId)?.let { photoCanonicalIdentity(syncScope.documentId, it) }} " +
                "captured=$capturedDurable",
            photoCanonicalIdentity(syncScope.documentId, durableBefore),
            capturedDurable
        )
        assertEquals(
            photoCanonicalIdentity(syncScope.documentId, liveBefore),
            capturedLive
        )
        assertNotEquals(capturedDurable, capturedLive)
        assertEquals(remote.cursor, metadata.snapshot(syncScope)?.acceptedCursor)
    }

    @Test
    fun remoteAcceptance_postCommitCleanupRemovesOldGeneratedOnlyAfterApply() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("photo-post-acceptance-gc", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val local = snapshot(session, "local")
        bridge.setSession(session, local)
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)

        val root = java.nio.file.Files.createTempDirectory("stage4-photo-post-acceptance-gc").toFile()
        val store = DocumentPhotoAssetStore(
            root,
            session.token.documentId,
            com.example.myapplication.stage5.DefaultImageProbe,
            TestPhotoPathOperationsFactory
        )
        try {
            val bytes = Stage4PhotoFixture.jpegBytes()
            val oldReference = store.publishNewPhoto(bytes)
            val acceptedReference = store.publishNewPhoto(bytes)
            store.releasePhotoPublication(oldReference)
            store.releasePhotoPublication(acceptedReference)
            val remoteSnapshot = snapshotWithPhoto(
                session,
                acceptedReference.removeSuffix(".jpg")
            )
            drive.seed(
                syncScope,
                "plan.pdf",
                remoteSnapshot,
                photoFiles = mapOf(acceptedReference to bytes)
            )
            bridge.postCommitCleanup = { accepted ->
                DocumentPhotoAssetStore(
                    root,
                    session.token.documentId,
                    com.example.myapplication.stage5.DefaultImageProbe,
                    TestPhotoPathOperationsFactory
                ).use { acceptedStore -> acceptedStore.cleanupAfterCanonicalCommit(accepted, accepted) }
            }

            assertTrue(coordinator.enqueueRemoteCheck(binding).await() is SyncOutcome.RemoteConflict)
            assertTrue(coordinator.enqueueRemoteAcceptance(binding).await() is SyncOutcome.AppliedRemote)
            assertFalse(java.io.File(store.resolver.root, oldReference).exists())
            assertTrue(java.io.File(store.resolver.root, acceptedReference).isFile)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun remoteAcceptance_metadataFailure_restoresDurableLiveAndConflictState_orFailsRecoveryClosed() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        var failWrites = false
        val metadata = InMemorySyncMetadataStore {
            if (failWrites) SyncMetadataError.Injected("metadata", "injected acceptance failure") else null
        }
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("metadata-rollback", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val local = snapshot(session, "local")
        bridge.setSession(session, local)
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        val acceptedBefore = requireNotNull(metadata.snapshot(syncScope)?.acceptedCursor)
        val remote = drive.seed(syncScope, "plan.pdf", snapshot(session, "remote"))
        assertTrue(coordinator.enqueueRemoteCheck(binding).await() is SyncOutcome.RemoteConflict)
        failWrites = true

        val failed = coordinator.enqueueRemoteAcceptance(binding).await()

        assertTrue(failed is SyncOutcome.Failed)
        assertEquals(SyncError.Kind.RECOVERY, (failed as SyncOutcome.Failed).error.kind)
        assertEquals(local, bridge.durableSnapshot(DocumentId.parse(session.documentId())))
        assertEquals(local, bridge.liveSnapshot)
        assertEquals(acceptedBefore, metadata.snapshot(syncScope)?.acceptedCursor)
        assertEquals(remote.cursor, metadata.snapshot(syncScope)?.conflictCursor)
    }

    @Test
    fun remoteAcceptance_cancellationAfterApply_restoresDurableLiveCursorAndConflict() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("cancel-after-apply", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val local = snapshot(session, "local")
        bridge.setSession(session, local)
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        val acceptedBefore = requireNotNull(metadata.snapshot(syncScope)?.acceptedCursor)
        val remote = drive.seed(syncScope, "plan.pdf", snapshot(session, "remote"))
        assertTrue(coordinator.enqueueRemoteCheck(binding).await() is SyncOutcome.RemoteConflict)
        val applied = CompletableDeferred<Unit>()
        bridge.afterApplyHook = {
            applied.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }

        val acceptance = coordinator.enqueueRemoteAcceptance(binding)
        runCurrent()
        applied.await()
        assertEquals("remote", bridge.liveSnapshot.pages.getValue(0).notes.single().text)
        coordinator.cancelForBindingAndJoin(binding)

        assertTrue(acceptance.isCancelled)
        assertEquals(local, bridge.durableSnapshot(DocumentId.parse(session.documentId())))
        assertEquals(local, bridge.liveSnapshot)
        assertEquals(acceptedBefore, metadata.snapshot(syncScope)?.acceptedCursor)
        assertEquals(remote.cursor, metadata.snapshot(syncScope)?.conflictCursor)
    }

    @Test
    fun remoteAcceptance_preCommitMarkerFailureRollsBackWithLivePhotoResolver() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("photo-marker-rollback", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val local = snapshot(session, "local")
        bridge.setSession(session, local)
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        val acceptedBefore = requireNotNull(metadata.snapshot(syncScope)?.acceptedCursor)
        val remote = drive.seed(
            syncScope,
            "plan.pdf",
            snapshotWithPhoto(session, "remote-marker-rollback"),
            photoFiles = mapOf("remote-marker-rollback.jpg" to Stage4PhotoFixture.jpegBytes())
        )
        assertTrue(coordinator.enqueueRemoteCheck(binding).await() is SyncOutcome.RemoteConflict)

        val root = java.nio.file.Files.createTempDirectory("stage4-photo-marker-rollback").toFile()
        val factory = CloseEnforcingPhotoPathOperationsFactory(failCommitMarker = true)
        try {
            val target = java.io.File(root, "remote-marker-rollback.jpg")
            val oldPhotoBytes = "old-photo-before-marker-failure".toByteArray()
            target.writeBytes(oldPhotoBytes)
            bridge.preparedPhotoTransaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("remote-marker-rollback.jpg" to Stage4PhotoFixture.jpegBytes()),
                factory
            )

            val failed = coordinator.enqueueRemoteAcceptance(binding).await()

            assertTrue("pre-commit marker failure must not succeed", failed is SyncOutcome.Failed)
            assertEquals(SyncError.Kind.LOCAL_PERSISTENCE, (failed as SyncOutcome.Failed).error.kind)
            assertEquals(oldPhotoBytes.toList(), target.readBytes().toList())
            assertEquals(local, bridge.durableSnapshot(session.token.documentId))
            assertEquals(local, bridge.liveSnapshot)
            assertEquals(acceptedBefore, metadata.snapshot(syncScope)?.acceptedCursor)
            assertEquals(remote.cursor, metadata.snapshot(syncScope)?.conflictCursor)
            assertEquals("the live rollback resolver must close after rollback", factory.opened, factory.closed)
            assertEquals("rollback must not use the resolver after close", 0, factory.usedAfterClose)
        } finally {
            bridge.preparedPhotoTransaction?.releaseAfterFailure()
            root.deleteRecursively()
        }
    }

    @Test
    fun remoteAcceptance_photoRollbackFailure_surfacesRecoveryAndKeepsCanonicalStateOld() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("photo-rollback-failure", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val local = snapshot(session, "local")
        bridge.setSession(session, local)
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        val acceptedBefore = requireNotNull(metadata.snapshot(syncScope)?.acceptedCursor)
        val remote = drive.seed(syncScope, "plan.pdf", snapshotWithPhoto(session, "remote"), photoFiles = mapOf("remote.jpg" to Stage4PhotoFixture.jpegBytes()))
        assertTrue(coordinator.enqueueRemoteCheck(binding).await() is SyncOutcome.RemoteConflict)
        bridge.preparedPhotoTransaction = object : PhotoContentTransaction {
            override suspend fun publish() = Unit
            override suspend fun commit() = Unit
            override suspend fun rollback() = throw PhotoRollbackException("injected photo rollback failure")
        }
        bridge.failApply = true

        val failed = coordinator.enqueueRemoteAcceptance(binding).await()

        assertTrue(failed is SyncOutcome.Failed)
        assertEquals(SyncError.Kind.RECOVERY, (failed as SyncOutcome.Failed).error.kind)
        assertEquals(local, bridge.durableSnapshot(DocumentId.parse(session.documentId())))
        assertEquals(local, bridge.liveSnapshot)
        assertEquals(acceptedBefore, metadata.snapshot(syncScope)?.acceptedCursor)
        assertEquals(remote.cursor, metadata.snapshot(syncScope)?.conflictCursor)
    }

    @Test
    fun remoteAcceptance_photoRollbackBoundaryFailure_retainsEvidenceUntilAuthorityRestore() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("photo-rollback-boundary", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val local = snapshot(session, "local")
        bridge.setSession(session, local)
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        val acceptedBefore = requireNotNull(metadata.snapshot(syncScope)?.acceptedCursor)
        val remote = drive.seed(
            syncScope,
            "plan.pdf",
            snapshotWithPhoto(session, "remote-boundary"),
            photoFiles = mapOf("remote-boundary.jpg" to Stage4PhotoFixture.jpegBytes())
        )
        assertTrue(coordinator.enqueueRemoteCheck(binding).await() is SyncOutcome.RemoteConflict)

        bridge.events.clear()
        bridge.failNextApply = true
        bridge.afterPhotoRollbackFailure = PhotoCanonicalRecoveryException(
            "injected process-boundary failure after photo rollback"
        )
        bridge.preparedPhotoTransaction = object : PhotoContentTransaction {
            override suspend fun publish() {
                bridge.events += "photo-publish"
            }

            override suspend fun rollback() {
                bridge.events += "photo-rollback"
            }

            override suspend fun rollbackForCrossStoreCompensation() {
                bridge.events += "photo-rollback"
            }

            override suspend fun completeCrossStoreRollback() {
                bridge.events += "photo-rollback-complete"
            }

            override suspend fun commit() {
                bridge.events += "photo-commit"
            }
        }

        val failed = coordinator.enqueueRemoteAcceptance(binding).await()

        assertTrue("a rollback-boundary failure must not report success", failed is SyncOutcome.Failed)
        assertEquals(SyncError.Kind.RECOVERY, (failed as SyncOutcome.Failed).error.kind)
        val rollbackIndex = bridge.events.indexOf("photo-rollback")
        val boundaryIndex = bridge.events.indexOf("photo-rollback-boundary")
        val restoreApplyIndex = bridge.events.lastIndexOf("apply")
        assertTrue(rollbackIndex >= 0)
        assertTrue("boundary failure must occur after photo rollback", boundaryIndex > rollbackIndex)
        assertTrue("canonical restoration must be attempted after the boundary", restoreApplyIndex > boundaryIndex)
        assertTrue("unresolved rollback must not publish completion", bridge.events.none { it == "photo-rollback-complete" })
        assertTrue("unresolved rollback must not commit photos", bridge.events.none { it == "photo-commit" })
        assertEquals(local, bridge.durableSnapshot(session.token.documentId))
        assertEquals(local, bridge.liveSnapshot)
        assertEquals(acceptedBefore, metadata.snapshot(syncScope)?.acceptedCursor)
        assertEquals(remote.cursor, metadata.snapshot(syncScope)?.conflictCursor)
    }

    @Test
    fun remoteAcceptance_photoPublishRollbackFailure_isRecoveryAndPreservesConflictState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("photo-publish-rollback-failure", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val local = snapshot(session, "local")
        bridge.setSession(session, local)
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        val acceptedBefore = requireNotNull(metadata.snapshot(syncScope)?.acceptedCursor)
        val remote = drive.seed(
            syncScope,
            "plan.pdf",
            snapshotWithPhoto(session, "remote"),
            photoFiles = mapOf("remote.jpg" to Stage4PhotoFixture.jpegBytes())
        )
        assertTrue(coordinator.enqueueRemoteCheck(binding).await() is SyncOutcome.RemoteConflict)

        val root = java.nio.file.Files.createTempDirectory("stage4-photo-publish-rollback").toFile()
        try {
            java.io.File(root, "remote.jpg").writeBytes("old-photo".toByteArray())
            var moveCount = 0
            bridge.preparedPhotoTransaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("remote.jpg" to Stage4PhotoFixture.jpegBytes()),
                TestPhotoPathOperationsFactory,
                move = { source, target ->
                    moveCount++
                    when (moveCount) {
                        2 -> throw java.io.IOException("injected publish move failure")
                        3 -> throw java.io.IOException("injected internal rollback move failure")
                        else -> java.nio.file.Files.move(
                            source,
                            target,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                }
            )

            val failed = coordinator.enqueueRemoteAcceptance(binding).await()

            assertTrue(failed is SyncOutcome.Failed)
            val error = (failed as SyncOutcome.Failed).error
            assertEquals(SyncError.Kind.RECOVERY, error.kind)
            val photoFailure = error.cause as? PhotoRollbackException
            assertNotNull(photoFailure)
            assertTrue(photoFailure!!.containsMessage("publish move failure"))
            assertFalse(java.io.File(root, "remote.jpg").exists())
            assertEquals(local, bridge.durableSnapshot(session.token.documentId))
            assertEquals(local, bridge.liveSnapshot)
            assertEquals(acceptedBefore, metadata.snapshot(syncScope)?.acceptedCursor)
            assertEquals(remote.cursor, metadata.snapshot(syncScope)?.conflictCursor)
            assertEquals(remote.snapshot, drive.record(syncScope)?.snapshot)
            assertEquals(remote.cursor, drive.record(syncScope)?.cursor)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sameDocumentVerifiedFingerprint_allowsAnotherProviderUri_withoutRebindingIdentity() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val fingerprint = SourceFingerprint.fromBytes("controlled-source".toByteArray())
        val local = sessionWithFingerprint("local", "content://provider/local", fingerprint)
        val syncScope = scope(local, "account", "root")
        bridge.setSession(local, snapshot(local, "local"))
        val remoteSource = DocumentSourceIdentityV1("content://other-device/provider", "plan.pdf")
        val remoteSnapshot = snapshot(local, "remote").copy(source = remoteSource)
        val remote = drive.seed(
            syncScope,
            "plan.pdf",
            remoteSnapshot,
            sourceFingerprint = fingerprint
        )

        val outcome = coordinator.enqueueRemoteAcceptance(syncScope, local.token).await()

        assertTrue(outcome is SyncOutcome.AppliedRemote)
        assertEquals("remote", bridge.liveSnapshot.pages.getValue(0).notes.single().text)
        assertEquals(local.token.sourceUri, bridge.liveSnapshot.source.sourceUri)
        assertEquals(remote.cursor, metadata.snapshot(syncScope)?.acceptedCursor)
    }

    @Test
    fun upload_rejectsSnapshotCapturedForAnotherSource_beforeGatewayMutation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val active = session("source-bound", "plan.pdf")
        val wrongSource = session("different-source", "plan.pdf")
        val syncScope = scope(active, "account", "root")
        bridge.setSession(active, snapshot(active, "local"))
        bridge.liveSnapshot = snapshot(wrongSource, "wrong-source")

        val outcome = coordinator.enqueueUpload(syncScope, active.token, SyncReason.IMMEDIATE).await()

        assertTrue(outcome is SyncOutcome.Failed)
        assertEquals(SyncError.Kind.VALIDATION, (outcome as SyncOutcome.Failed).error.kind)
        assertTrue(drive.calls.none { it.operation == "upload" })
        assertNull(metadata.snapshot(syncScope))
    }

    @Test
    fun provisionalTarget_cannotBindOrStartSynchronization() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        bridge.ready = false
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("provisional", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "cleared-placeholder"))

        assertNull(coordinator.bind(syncScope, session.token))
        assertFalse(coordinator.markDirtyForDocument(session.token.documentId, session.token))
        assertFalse(coordinator.markDirtyForDocument(session.token.documentId))
        assertEquals(0, drive.calls.size)
        assertNull(coordinator.status(syncScope))
    }

    @Test
    fun dirtyAdmission_isObservable_andOfflineMutationRetainsDirtyState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("dirty-state", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "dirty"))
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))

        coordinator.markDirty(binding)
        runCurrent()
        assertTrue(coordinator.status(syncScope)?.state is SyncState.Dirty)

        val gate = CompletableDeferred<Unit>()
        drive.beforeFinalCommit = { gate.await() }
        val upload = coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE)
        runCurrent()
        assertTrue(coordinator.status(syncScope)?.state is SyncState.Uploading)
        gate.complete(Unit)
        assertTrue(upload.await() is SyncOutcome.Uploaded)
        assertTrue(coordinator.status(syncScope)?.state is SyncState.Idle)

        coordinator.fenceForBinding(binding)
        assertTrue(coordinator.markDirtyForDocument(session.token.documentId, session.token))
        assertTrue(coordinator.status(syncScope)?.state is SyncState.Dirty)
        assertEquals(1, drive.calls.count { it.operation == "upload" })
    }

    @Test
    fun coordinatorWorker_serializesUploadThroughMetadataCommit() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("generation", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "generation-1"))
        val firstGate = CompletableDeferred<Unit>()
        drive.beforeFinalCommit = { request ->
            if (request.generation == 1L) firstGate.await()
        }

        val first = coordinator.enqueueUpload(syncScope, session.token, SyncReason.IMMEDIATE)
        runCurrent()
        assertTrue(coordinator.status(syncScope)?.state is SyncState.Uploading)
        bridge.liveSnapshot = snapshot(session, "generation-2")
        val second = coordinator.enqueueUpload(syncScope, session.token, SyncReason.MANUAL)
        runCurrent()
        assertFalse(second.isCompleted)
        assertEquals(1, drive.calls.count { it.operation == "upload" })
        assertEquals(1, bridge.capturedSnapshots.size)

        firstGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(first.await() is SyncOutcome.Uploaded)
        assertTrue(second.await() is SyncOutcome.Uploaded)

        val remote = drive.record(syncScope)
        assertEquals("generation-2", remote?.snapshot?.pages?.getValue(0)?.notes?.single()?.text)
        assertEquals("generation-2", bridge.liveSnapshot.pages.getValue(0).notes.single().text)
        assertEquals(metadata.snapshot(syncScope)?.acceptedCursor, remote?.cursor)
        assertEquals(1, drive.maxConcurrentFinalCommits)
        assertTrue(coordinator.status(syncScope)?.state is SyncState.Idle)
    }

    @Test
    fun staleGeneration_isRejectedBeforeFakeFinalMutation_whenAlreadyInvalidated() = runTest {
        val drive = FakeDriveGateway(idFactory = Ids())
        val session = session("stale-admission", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val lease = ScopeRemoteMutationLease()
        lease.advance(1L)
        lease.advance(2L)

        val result = drive.upload(
            UploadRequest(
                scope = syncScope,
                displayName = "plan.pdf",
                snapshot = snapshot(session, "old-generation"),
                expectedCursor = null,
                generation = 1L,
                mutationLease = lease,
                isGenerationCurrent = { lease.isGenerationCurrent(1L) }
            )
        )
        result.mutationSession?.close()

        assertTrue(result is UploadResult.Rejected)
        assertTrue((result as UploadResult.Rejected).failure is DriveFailure.StaleGeneration)
        assertNull(drive.record(syncScope))
        assertEquals(0, drive.createdFolderCount.get())
        assertEquals(0, drive.createdFileCount.get())
    }

    @Test
    fun coordinatorWorker_serializesRemoteCheckBehindUpload_withoutFalseConflict() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("queue-check", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "initial"))
        assertTrue(coordinator.enqueueUpload(syncScope, session.token, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)

        val uploadGate = CompletableDeferred<Unit>()
        drive.beforeFinalCommit = { request ->
            if (request.generation == 2L) uploadGate.await()
        }
        bridge.liveSnapshot = snapshot(session, "updated")
        val upload = coordinator.enqueueUpload(syncScope, session.token, SyncReason.MANUAL)
        runCurrent()
        val findsBeforeCheck = drive.calls.count { it.operation == "find" }
        val check = coordinator.enqueueRemoteCheck(syncScope, session.token)
        runCurrent()

        assertFalse(check.isCompleted)
        assertEquals(findsBeforeCheck, drive.calls.count { it.operation == "find" })
        assertEquals(2, drive.calls.count { it.operation == "upload" })

        uploadGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(upload.await() is SyncOutcome.Uploaded)
        assertEquals(SyncOutcome.RemoteUnchanged, check.await())
        assertTrue(coordinator.status(syncScope)?.state is SyncState.Idle)
        assertNull(metadata.snapshot(syncScope)?.conflictCursor)
    }

    @Test
    fun scopeWorkers_serializeWithinScope_butDifferentScopesProgressIndependently() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val firstSession = session("parallel-a", "plan.pdf")
        val secondSession = session("parallel-b", "plan.pdf")
        val firstScope = scope(firstSession, "account-a", "root")
        val secondScope = scope(secondSession, "account-b", "root")
        bridge.setSession(firstSession, snapshot(firstSession, "A"))
        bridge.setSession(secondSession, snapshot(secondSession, "B"))

        val firstInsideMutation = CompletableDeferred<Unit>()
        val releaseFirstMutation = CompletableDeferred<Unit>()
        drive.insideFinalMutation = { request ->
            if (request.scope == firstScope) {
                firstInsideMutation.complete(Unit)
                releaseFirstMutation.await()
            }
        }

        val first = coordinator.enqueueUpload(firstScope, firstSession.token, SyncReason.IMMEDIATE)
        runCurrent()
        firstInsideMutation.await()
        val second = coordinator.enqueueUpload(secondScope, secondSession.token, SyncReason.MANUAL)
        runCurrent()

        assertFalse(first.isCompleted)
        assertTrue(second.isCompleted)
        assertTrue(second.await() is SyncOutcome.Uploaded)
        assertEquals(1, drive.maxConcurrentFinalCommits(firstScope))
        assertEquals(1, drive.maxConcurrentFinalCommits(secondScope))
        assertTrue(drive.maxConcurrentFinalCommits >= 2)

        releaseFirstMutation.complete(Unit)
        advanceUntilIdle()
        assertTrue(first.await() is SyncOutcome.Uploaded)
        assertEquals("A", drive.record(firstScope)?.snapshot?.pages?.getValue(0)?.notes?.single()?.text)
        assertEquals("B", drive.record(secondScope)?.snapshot?.pages?.getValue(0)?.notes?.single()?.text)
    }

    @Test
    fun gatewayLease_waitsForActiveMutation_beforePublishingNewGeneration() = runTest {
        val drive = FakeDriveGateway(idFactory = Ids())
        val session = session("lease-boundary", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val lease = ScopeRemoteMutationLease()
        val firstInsideMutation = CompletableDeferred<Unit>()
        val releaseFirstMutation = CompletableDeferred<Unit>()
        drive.insideFinalMutation = { request ->
            if (request.generation == 1L) {
                firstInsideMutation.complete(Unit)
                releaseFirstMutation.await()
            }
        }

        lease.advance(1L)
        val first = async {
            val result = drive.upload(
                UploadRequest(
                    scope = syncScope,
                    displayName = "plan.pdf",
                    snapshot = snapshot(session, "generation-1"),
                    expectedCursor = null,
                    generation = 1L,
                    mutationLease = lease,
                    isGenerationCurrent = { lease.isGenerationCurrent(1L) }
                )
            )
            result.mutationSession?.close()
            result
        }
        firstInsideMutation.await()

        val secondAdvance = async { lease.advance(2L) }
        runCurrent()
        assertFalse(secondAdvance.isCompleted)

        releaseFirstMutation.complete(Unit)
        val firstResult = first.await()
        assertTrue(firstResult is UploadResult.Uploaded)
        secondAdvance.await()

        val secondResult = drive.upload(
            UploadRequest(
                scope = syncScope,
                displayName = "plan.pdf",
                snapshot = snapshot(session, "generation-2"),
                expectedCursor = (firstResult as UploadResult.Uploaded).remote.cursor,
                generation = 2L,
                mutationLease = lease,
                isGenerationCurrent = { lease.isGenerationCurrent(2L) }
            )
        )
        secondResult.mutationSession?.close()

        assertTrue(secondResult is UploadResult.Uploaded)
        val finalRecord = drive.record(syncScope)
        assertEquals("generation-2", finalRecord?.snapshot?.pages?.getValue(0)?.notes?.single()?.text)
        assertNotEquals((firstResult as UploadResult.Uploaded).remote.cursor, finalRecord?.cursor)
        assertEquals(
            (firstResult as UploadResult.Uploaded).remote.reference,
            (secondResult as UploadResult.Uploaded).remote.reference
        )
        assertEquals(1, drive.createdFolderCount.get())
        assertEquals(1, drive.createdFileCount.get())
        assertEquals(1, drive.maxConcurrentFinalCommits)
    }

    @Test
    fun googleGateway_executeMutation_isSerializedBeforeNewGenerationPublishes() = runTest {
        val session = session("google-lease-boundary", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val lease = ScopeRemoteMutationLease()
        val firstMutationEntered = CompletableDeferred<Unit>()
        val releaseFirstMutation = CountDownLatch(1)
        val folderCreates = AtomicInteger(0)
        val fileCreates = AtomicInteger(0)
        val fileUpdates = AtomicInteger(0)
        val revision = AtomicInteger(0)
        var etag = "etag-r1"
        var externalMutationAfterLookup = false
        val ifMatchValues = mutableListOf<String>()
        var folderCreated = false
        var fileCreated = false

        fun folderJson(): String =
            """{"id":"folder-1","name":"plan.pdf","parents":["root"],"appProperties":{"$SYNC_DOCUMENT_ID_APP_PROPERTY":"${syncScope.documentId.value}"}}"""

        fun fileJson(cursor: String): String =
            """{"id":"file-1","name":"annotations.json","parents":["folder-1"],"appProperties":{"$SYNC_DOCUMENT_ID_APP_PROPERTY":"${syncScope.documentId.value}","$SYNC_SCHEMA_APP_PROPERTY":"1"},"headRevisionId":"$cursor"}"""

        val transport = object : MockHttpTransport() {
            override fun buildRequest(method: String, url: String): LowLevelHttpRequest {
                return object : MockLowLevelHttpRequest(url) {
                    private var receivedIfMatch: String? = null
                    private val receivedHeaders = mutableListOf<String>()
                    private var responseEtag: String? = null

                    override fun addHeader(name: String, value: String) {
                        receivedHeaders += "$name=$value"
                        if (name.equals("If-Match", ignoreCase = true)) {
                            receivedIfMatch = value
                            ifMatchValues += value
                        }
                    }

                    override fun execute(): LowLevelHttpResponse {
                        if (method != "GET" && url.contains("uploadType=resumable") && !url.contains("session=")) {
                            if (fileCreated) {
                                if (receivedIfMatch != etag) {
                                    return MockLowLevelHttpResponse()
                                        .setStatusCode(412)
                                        .setContentType("application/json")
                                        .setContent("{\"error\":{\"code\":412,\"message\":\"precondition failed\"}}")
                                }
                            }
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .addHeader(
                                    "Location",
                                    "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&session=1"
                                )
                                .setZeroContent()
                        }
                        val response = when {
                            method == "GET" && url.contains("mimeType") -> {
                                val files = if (folderCreated) "\"files\":[${folderJson()}]" else "\"files\":[]"
                                "{$files}"
                            }
                            method == "GET" && url.contains("/files/file-1") -> {
                                responseEtag = etag
                                val observed = fileJson("r${revision.get()}")
                                if (externalMutationAfterLookup) {
                                    revision.incrementAndGet()
                                    etag = "etag-external"
                                    externalMutationAfterLookup = false
                                }
                                observed
                            }
                            method == "GET" && url.contains("/files/folder-1") -> {
                                folderJson()
                            }
                            method == "GET" -> {
                                val files = if (fileCreated) "\"files\":[${fileJson("r${revision.get()}")}]" else "\"files\":[]"
                                "{$files}"
                            }
                            !folderCreated -> {
                                folderCreates.incrementAndGet()
                                firstMutationEntered.complete(Unit)
                                check(releaseFirstMutation.await(5, TimeUnit.SECONDS)) {
                                    "generation 1 did not release its real Drive mutation"
                                }
                                folderCreated = true
                                folderJson()
                            }
                            else -> {
                                val cursor = "r${revision.incrementAndGet()}"
                                if (fileCreated) fileUpdates.incrementAndGet() else fileCreates.incrementAndGet()
                                fileCreated = true
                                etag = "etag-$cursor"
                                fileJson(cursor)
                            }
                        }
                        val httpResponse = MockLowLevelHttpResponse()
                            .setStatusCode(200)
                            .setContentType("application/json")
                            .setContent(response)
                        if (method == "GET" && url.contains("/files/file-1")) {
                            httpResponse.addHeader("ETag", responseEtag ?: etag)
                        }
                        return httpResponse
                    }
                }
            }
        }
        val gateway = GoogleDriveGateway(
            Drive.Builder(transport, GsonFactory.getDefaultInstance(), null)
                .setApplicationName("Stage 4 test")
                .setRootUrl("https://www.googleapis.com/")
                .setServicePath("drive/v3/")
                .build(),
            "account"
        )

        lease.advance(1L)
        val first = async {
            val result = gateway.upload(
                UploadRequest(
                    scope = syncScope,
                    displayName = "plan.pdf",
                    snapshot = snapshot(session, "generation-1"),
                    expectedCursor = null,
                    generation = 1L,
                    mutationLease = lease,
                    isGenerationCurrent = { lease.isGenerationCurrent(1L) }
                )
            )
            result.mutationSession?.close()
            result
        }
        firstMutationEntered.await()

        val secondAdvance = async { lease.advance(2L) }
        runCurrent()
        assertFalse(secondAdvance.isCompleted)

        releaseFirstMutation.countDown()
        val firstResult = first.await()
        assertTrue("first Google upload result: $firstResult", firstResult is UploadResult.Uploaded)
        val firstUploaded = firstResult as UploadResult.Uploaded
        secondAdvance.await()

        val secondResult = gateway.upload(
            UploadRequest(
                scope = syncScope,
                displayName = "plan.pdf",
                snapshot = snapshot(session, "generation-2"),
                expectedCursor = firstUploaded.remote.cursor,
                generation = 2L,
                mutationLease = lease,
                isGenerationCurrent = { lease.isGenerationCurrent(2L) }
            )
        )
        secondResult.mutationSession?.close()

        assertTrue("second Google upload result: $secondResult", secondResult is UploadResult.Uploaded)
        assertEquals(1, folderCreates.get())
        assertEquals(1, fileCreates.get())
        assertEquals(1, fileUpdates.get())
        assertEquals("r2", (secondResult as UploadResult.Uploaded).remote.cursor.revision)
        assertTrue(ifMatchValues.contains("etag-r1"))
        assertEquals(
            "generation-2",
            secondResult.remote.snapshot.pages.getValue(0).notes.single().text
        )
        assertEquals(
            firstUploaded.remote.reference,
            secondResult.remote.reference
        )

        externalMutationAfterLookup = true
        lease.advance(3L)
        val thirdResult = gateway.upload(
            UploadRequest(
                scope = syncScope,
                displayName = "plan.pdf",
                snapshot = snapshot(session, "generation-3-rejected-by-drive"),
                expectedCursor = (secondResult as UploadResult.Uploaded).remote.cursor,
                generation = 3L,
                mutationLease = lease,
                isGenerationCurrent = { lease.isGenerationCurrent(3L) }
            )
        )
        thirdResult.mutationSession?.close()

        assertTrue("external mutation result: $thirdResult", thirdResult is UploadResult.Conflict)
        assertEquals("r3", (thirdResult as UploadResult.Conflict).remote.cursor.revision)
        assertEquals(1, fileUpdates.get())
    }

    @Test
    fun googleUpload_rejectsMovedOrMismatchedMutationTargetsBeforeRemoteWrite() = runTest {
        val session = session("google-target-revalidation", "plan.pdf")
        val syncScope = scope(session, "account", "root")

        listOf(
            "moved folder" to true,
            "mismatched file identity" to false
        ).forEach { (label, movedFolder) ->
            val mismatchedFile = !movedFolder
            var remoteWrites = 0

            fun folderJson(moved: Boolean = false): String =
                """{"id":"folder-1","name":"plan.pdf","parents":["${if (moved) "other-root" else "root"}"],"appProperties":{"$SYNC_DOCUMENT_ID_APP_PROPERTY":"${syncScope.documentId.value}"}}"""

            fun fileJson(mismatched: Boolean = false): String =
                """{"id":"file-1","name":"annotations.json","parents":["folder-1"],"appProperties":{"$SYNC_DOCUMENT_ID_APP_PROPERTY":"${if (mismatched) "other-document" else syncScope.documentId.value}","$SYNC_SCHEMA_APP_PROPERTY":"1"},"headRevisionId":"r1"}"""

            val transport = object : MockHttpTransport() {
                override fun buildRequest(method: String, url: String): LowLevelHttpRequest =
                    object : MockLowLevelHttpRequest(url) {
                        override fun execute(): LowLevelHttpResponse {
                            if (method == "GET" && url.contains("mimeType")) {
                                return MockLowLevelHttpResponse()
                                    .setStatusCode(200)
                                    .setContentType("application/json")
                                    .setContent(
                                        "{\"files\":[${folderJson()}]}"
                                    )
                            }
                            if (method == "GET" && url.contains("/files/folder-1")) {
                                return MockLowLevelHttpResponse()
                                    .setStatusCode(200)
                                    .setContentType("application/json")
                                    .setContent(folderJson(movedFolder))
                            }
                            if (method == "GET" && url.contains("/files/file-1")) {
                                return MockLowLevelHttpResponse()
                                    .setStatusCode(200)
                                    .setContentType("application/json")
                                    .setContent(fileJson(mismatchedFile))
                            }
                            if (method == "GET") {
                                return MockLowLevelHttpResponse()
                                    .setStatusCode(200)
                                    .setContentType("application/json")
                                    .setContent("{\"files\":[${fileJson()}]}")
                            }
                            remoteWrites++
                            return MockLowLevelHttpResponse()
                                .setStatusCode(500)
                                .setContentType("application/json")
                                .setContent("{\"error\":{\"message\":\"unexpected remote write\"}}")
                        }
                    }
            }
            val gateway = GoogleDriveGateway(
                Drive.Builder(transport, GsonFactory.getDefaultInstance(), null)
                    .setApplicationName("Stage 5 target revalidation test")
                    .setRootUrl("https://www.googleapis.com/")
                    .setServicePath("drive/v3/")
                    .build(),
                "account"
            )
            val lease = ScopeRemoteMutationLease()
            lease.advance(1L)
            val result = gateway.upload(
                UploadRequest(
                    scope = syncScope,
                    displayName = "plan.pdf",
                    snapshot = snapshot(session, "target-revalidation"),
                    expectedCursor = RemoteCursor("r1"),
                    generation = 1L,
                    mutationLease = lease,
                    isGenerationCurrent = { lease.isGenerationCurrent(1L) }
                )
            )
            result.mutationSession?.close()

            assertTrue("$label result: $result", result is UploadResult.Rejected)
            assertTrue((result as UploadResult.Rejected).failure is DriveFailure.Validation)
            assertEquals("$label must not mutate Drive", 0, remoteWrites)
        }
    }

    @Test
    fun googleUpload_rejectsMissingOrMismatchedSourceIdentityBeforeRemoteWrite() = runTest {
        val fingerprint = SourceFingerprint.fromBytes("source-for-drive-identity".toByteArray())
        val otherFingerprint = SourceFingerprint.fromBytes("different-drive-source".toByteArray())
        val session = sessionWithFingerprint(
            "google-source-identity-revalidation",
            "content://drive/source-identity",
            fingerprint
        )
        val syncScope = scope(session, "account", "root")
        val expectedWire = fingerprint.toDriveProperty()
        val otherWire = otherFingerprint.toDriveProperty()
        val cases = listOf<Triple<String, String?, String?>>(
            Triple("missing folder fingerprint", null, expectedWire),
            Triple("mismatched folder fingerprint", otherWire, expectedWire),
            Triple("missing file fingerprint", expectedWire, null),
            Triple("mismatched file fingerprint", expectedWire, otherWire)
        )

        cases.forEach { (label, folderSource, fileSource) ->
            var remoteWrites = 0

            fun folderJson(): String {
                val sourceProperty = folderSource?.let {
                    ",\"$SYNC_SOURCE_FINGERPRINT_APP_PROPERTY\":\"$it\""
                }.orEmpty()
                return """{"id":"folder-1","name":"plan.pdf","parents":["root"],"appProperties":{"$SYNC_DOCUMENT_ID_APP_PROPERTY":"${syncScope.documentId.value}"$sourceProperty}}"""
            }

            fun fileJson(): String {
                val sourceProperty = fileSource?.let {
                    ",\"$SYNC_SOURCE_FINGERPRINT_APP_PROPERTY\":\"$it\""
                }.orEmpty()
                return """{"id":"file-1","name":"annotations.json","parents":["folder-1"],"appProperties":{"$SYNC_DOCUMENT_ID_APP_PROPERTY":"${syncScope.documentId.value}","$SYNC_SCHEMA_APP_PROPERTY":"1"$sourceProperty},"headRevisionId":"r1"}"""
            }

            val transport = object : MockHttpTransport() {
                override fun buildRequest(method: String, url: String): LowLevelHttpRequest =
                    object : MockLowLevelHttpRequest(url) {
                        override fun execute(): LowLevelHttpResponse {
                            if (method == "GET" && url.contains("mimeType")) {
                                return MockLowLevelHttpResponse()
                                    .setStatusCode(200)
                                    .setContentType("application/json")
                                    .setContent("{\"files\":[${folderJson()}]}")
                            }
                            if (method == "GET" && url.contains("/files/folder-1")) {
                                return MockLowLevelHttpResponse()
                                    .setStatusCode(200)
                                    .setContentType("application/json")
                                    .setContent(folderJson())
                            }
                            if (method == "GET") {
                                return MockLowLevelHttpResponse()
                                    .setStatusCode(200)
                                    .setContentType("application/json")
                                    .setContent("{\"files\":[${fileJson()}]}")
                            }
                            remoteWrites++
                            return MockLowLevelHttpResponse()
                                .setStatusCode(500)
                                .setContentType("application/json")
                                .setContent("{\"error\":{\"message\":\"unexpected remote write\"}}")
                        }
                    }
            }
            val gateway = GoogleDriveGateway(
                Drive.Builder(transport, GsonFactory.getDefaultInstance(), null)
                    .setApplicationName("Stage 5 source identity revalidation test")
                    .setRootUrl("https://www.googleapis.com/")
                    .setServicePath("drive/v3/")
                    .build(),
                "account"
            )
            val lease = ScopeRemoteMutationLease()
            lease.advance(1L)
            val result = gateway.upload(
                UploadRequest(
                    scope = syncScope,
                    displayName = "plan.pdf",
                    snapshot = snapshot(session, "source-identity-revalidation"),
                    expectedCursor = null,
                    sourceFingerprint = fingerprint,
                    generation = 1L,
                    mutationLease = lease,
                    isGenerationCurrent = { lease.isGenerationCurrent(1L) }
                )
            )
            result.mutationSession?.close()

            assertTrue("$label result: $result", result is UploadResult.Rejected)
            assertTrue((result as UploadResult.Rejected).failure is DriveFailure.Validation)
            assertEquals("$label must not mutate Drive", 0, remoteWrites)
        }
    }

    @Test
    fun googleUpload_revalidatesFolderAfterFinalFileResponse_beforeReturningUploaded() = runTest {
        val fingerprint = SourceFingerprint.fromBytes("source-for-final-folder-race".toByteArray())
        val session = sessionWithFingerprint(
            "google-final-folder-race",
            "content://drive/final-folder-race",
            fingerprint
        )
        val syncScope = scope(session, "account", "root")
        var directFolderReads = 0
        var fileMutationCompleted = false
        var remoteWrites = 0

        fun folderJson(moved: Boolean = false): String =
            """{"id":"folder-1","name":"plan.pdf","parents":["${if (moved) "other-root" else "root"}"],"appProperties":{"$SYNC_DOCUMENT_ID_APP_PROPERTY":"${syncScope.documentId.value}","$SYNC_SOURCE_FINGERPRINT_APP_PROPERTY":"${fingerprint.toDriveProperty()}"}}"""

        fun fileJson(revision: String): String =
            """{"id":"file-1","name":"annotations.json","parents":["folder-1"],"appProperties":{"$SYNC_DOCUMENT_ID_APP_PROPERTY":"${syncScope.documentId.value}","$SYNC_SCHEMA_APP_PROPERTY":"1","$SYNC_SOURCE_FINGERPRINT_APP_PROPERTY":"${fingerprint.toDriveProperty()}"},"headRevisionId":"$revision"}"""

        val transport = object : MockHttpTransport() {
            override fun buildRequest(method: String, url: String): LowLevelHttpRequest =
                object : MockLowLevelHttpRequest(url) {
                    override fun execute(): LowLevelHttpResponse {
                        if (method == "GET" && url.contains("mimeType")) {
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .setContentType("application/json")
                                .setContent("{\"files\":[${folderJson()}]}")
                        }
                        if (method == "GET" && url.contains("/files/folder-1")) {
                            directFolderReads++
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .setContentType("application/json")
                                .setContent(folderJson(fileMutationCompleted && directFolderReads >= 3))
                        }
                        if (method == "GET" && url.contains("/files/file-1")) {
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .setContentType("application/json")
                                .addHeader("ETag", "etag-r${if (fileMutationCompleted) "2" else "1"}")
                                .setContent(fileJson(if (fileMutationCompleted) "r2" else "r1"))
                        }
                        if (method == "GET") {
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .setContentType("application/json")
                                .setContent("{\"files\":[${fileJson("r1")}]}")
                        }
                        if (url.contains("uploadType=resumable") && !url.contains("session=")) {
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .addHeader(
                                    "Location",
                                    "https://www.googleapis.com/upload/drive/v3/files/file-1?uploadType=resumable&session=final-folder-race"
                                )
                                .setZeroContent()
                        }
                        if (url.contains("session=")) {
                            fileMutationCompleted = true
                            remoteWrites++
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .setContentType("application/json")
                                .setContent(fileJson("r2"))
                        }
                        remoteWrites++
                        return MockLowLevelHttpResponse()
                            .setStatusCode(500)
                            .setContentType("application/json")
                            .setContent("{\"error\":{\"message\":\"unexpected remote write\"}}")
                    }
                }
        }
        val gateway = GoogleDriveGateway(
            Drive.Builder(transport, GsonFactory.getDefaultInstance(), null)
                .setApplicationName("Stage 5 final folder race test")
                .setRootUrl("https://www.googleapis.com/")
                .setServicePath("drive/v3/")
                .build(),
            "account"
        )
        val lease = ScopeRemoteMutationLease()
        lease.advance(1L)
        val result = gateway.upload(
            UploadRequest(
                scope = syncScope,
                displayName = "plan.pdf",
                snapshot = snapshot(session, "final-folder-race"),
                expectedCursor = RemoteCursor("r1"),
                sourceFingerprint = fingerprint,
                generation = 1L,
                mutationLease = lease,
                isGenerationCurrent = { lease.isGenerationCurrent(1L) }
            )
        )
        result.mutationSession?.close()

        assertTrue("result: $result", result is UploadResult.Rejected)
        assertTrue((result as UploadResult.Rejected).failure is DriveFailure.Validation)
        assertTrue("final folder must be read after the final file (reads=$directFolderReads)", directFolderReads >= 3)
        assertTrue("the mutation was observed by the race fixture (writes=$remoteWrites)", remoteWrites >= 1)
    }

    @Test
    fun googleAdoption_rewritesEmbeddedDocumentId_beforeDownload_andFailsClosedOnExternalRevision() = runTest {
        val session = sessionWithFingerprint(
            "google-adoption",
            "content://device-b/source",
            SourceFingerprint.fromBytes("controlled-source".toByteArray())
        )
        val localScope = scope(session, "account", "root")
        val remoteDocumentId = DocumentId.new()
        val fingerprint = requireNotNull(session.token.sourceFingerprint)
        var folderDocumentId = remoteDocumentId.value
        var fileDocumentId = remoteDocumentId.value
        var fileRevision = "r1"
        var folderEtag = "folder-e1"
        var fileEtag = "file-e1"
        var externalRevisionBeforeFileUpdate = false
        val originalSnapshot = snapshot(session, "remote").copy(
            source = DocumentSourceIdentityV1("content://device-a/source", "plan.pdf")
        )
        val originalPayload = Gson().toJson(
            mapOf(
                "accountId" to localScope.accountId,
                "backupRootId" to localScope.backupRootId,
                "documentId" to remoteDocumentId.value,
                "displayName" to "plan.pdf",
                "snapshot" to originalSnapshot,
                "sourceFingerprint" to fingerprint.toDriveProperty(),
                "photoFiles" to emptyMap<String, String>()
            )
        )
        var filePayload = originalPayload

        fun folderJson() = """{"id":"folder-1","name":"plan.pdf","parents":["root"],"appProperties":{"$SYNC_DOCUMENT_ID_APP_PROPERTY":"$folderDocumentId","$SYNC_SOURCE_FINGERPRINT_APP_PROPERTY":"${fingerprint.toDriveProperty()}"}}"""
        fun fileJson() = """{"id":"file-1","name":"annotations.json","parents":["folder-1"],"appProperties":{"$SYNC_DOCUMENT_ID_APP_PROPERTY":"$fileDocumentId","$SYNC_SCHEMA_APP_PROPERTY":"1","$SYNC_SOURCE_FINGERPRINT_APP_PROPERTY":"${fingerprint.toDriveProperty()}"},"headRevisionId":"$fileRevision"}"""

        val transport = object : MockHttpTransport() {
            override fun buildRequest(method: String, url: String): LowLevelHttpRequest {
                return object : MockLowLevelHttpRequest(url) {
                    private var ifMatch: String? = null

                    override fun addHeader(name: String, value: String) {
                        if (name.equals("If-Match", ignoreCase = true)) ifMatch = value
                    }

                    override fun execute(): LowLevelHttpResponse {
                        if (method == "GET" && url.contains("alt=media")) {
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .setContentType("application/json")
                                .setContent(filePayload)
                        }
                        if (method == "GET" && url.contains("/files/folder-1")) {
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .setContentType("application/json")
                                .addHeader("ETag", folderEtag)
                                .setContent(folderJson())
                        }
                        if (method == "GET" && url.contains("/files/file-1")) {
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .setContentType("application/json")
                                .addHeader("ETag", fileEtag)
                                .setContent(fileJson())
                        }
                        if (method != "GET" && url.contains("uploadType=resumable") && !url.contains("session=")) {
                            if (externalRevisionBeforeFileUpdate) {
                                externalRevisionBeforeFileUpdate = false
                                fileRevision = "r-external"
                                fileEtag = "file-external"
                            }
                            if (!url.contains("session=") && ifMatch != fileEtag) {
                                return MockLowLevelHttpResponse()
                                    .setStatusCode(412)
                                    .setContentType("application/json")
                                    .setContent("{\"error\":{\"code\":412,\"message\":\"precondition failed\"}}")
                            }
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .addHeader(
                                    "Location",
                                    "https://www.googleapis.com/upload/drive/v3/files/file-1?uploadType=resumable&session=adoption-1"
                                )
                                .setZeroContent()
                        }
                        if (method != "GET" && url.contains("/files/file-1")) {
                            if (!url.contains("session=") && ifMatch != fileEtag) {
                                return MockLowLevelHttpResponse()
                                    .setStatusCode(412)
                                    .setContentType("application/json")
                                    .setContent("{\"error\":{\"code\":412,\"message\":\"precondition failed\"}}")
                            }
                            fileDocumentId = localScope.documentId.value
                            filePayload = originalPayload.replace(remoteDocumentId.value, localScope.documentId.value)
                            fileRevision = "r2"
                            fileEtag = "file-e2"
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .setContentType("application/json")
                                .addHeader("ETag", fileEtag)
                                .setContent(fileJson())
                        }
                        if (method != "GET" && url.contains("/files/folder-1")) {
                            if (ifMatch != folderEtag) {
                                return MockLowLevelHttpResponse()
                                    .setStatusCode(412)
                                    .setContentType("application/json")
                                    .setContent("{\"error\":{\"code\":412,\"message\":\"precondition failed\"}}")
                            }
                            folderDocumentId = localScope.documentId.value
                            folderEtag = "folder-e2"
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .setContentType("application/json")
                                .addHeader("ETag", folderEtag)
                                .setContent(folderJson())
                        }
                        return MockLowLevelHttpResponse().setStatusCode(404).setContent("{}")
                    }
                }
            }
        }
        val gateway = GoogleDriveGateway(
            Drive.Builder(transport, GsonFactory.getDefaultInstance(), null)
                .setApplicationName("Stage 4 adoption test")
                .setRootUrl("https://www.googleapis.com/")
                .setServicePath("drive/v3/")
                .build(),
            "account"
        )
        val candidate = RemoteAdoptionCandidate(
            accountId = localScope.accountId,
            backupRootId = localScope.backupRootId,
            remoteDocumentId = remoteDocumentId,
            sourceFingerprint = fingerprint,
            displayName = "plan.pdf",
            reference = RemoteReference(
                folderId = "folder-1",
                snapshotFileId = "file-1",
                appProperties = mapOf(
                    SYNC_DOCUMENT_ID_APP_PROPERTY to remoteDocumentId.value,
                    SYNC_SOURCE_FINGERPRINT_APP_PROPERTY to fingerprint.toDriveProperty()
                )
            ),
            cursor = RemoteCursor("r1")
        )
        val lease = ScopeRemoteMutationLease()
        lease.advance(1L)
        val adopted = gateway.adopt(
            AdoptionRequest(
                scope = localScope,
                candidate = candidate,
                localSourceFingerprint = fingerprint,
                generation = 1L,
                mutationLease = lease,
                isGenerationCurrent = { lease.isGenerationCurrent(1L) }
            )
        )
        adopted.mutationSession?.close()
        assertTrue("adoption result: $adopted", adopted is AdoptionResult.Adopted)
        val adoptedMetadata = (adopted as AdoptionResult.Adopted).remote
        val downloaded = gateway.download(localScope, adoptedMetadata.reference)
        assertTrue("download result: $downloaded", downloaded is DownloadResult.Downloaded)
        assertEquals(localScope.documentId.value, fileDocumentId)
        assertEquals("remote", (downloaded as DownloadResult.Downloaded).remote.snapshot.pages.getValue(0).notes.single().text)

        folderDocumentId = remoteDocumentId.value
        fileDocumentId = remoteDocumentId.value
        fileRevision = "r1"
        folderEtag = "folder-reset"
        fileEtag = "file-reset"
        filePayload = originalPayload
        externalRevisionBeforeFileUpdate = true
        lease.advance(2L)
        val conflict = gateway.adopt(
            AdoptionRequest(
                scope = localScope,
                candidate = candidate,
                localSourceFingerprint = fingerprint,
                generation = 2L,
                mutationLease = lease,
                isGenerationCurrent = { lease.isGenerationCurrent(2L) }
            )
        )
        conflict.mutationSession?.close()
        assertTrue("external adoption result: $conflict", conflict is AdoptionResult.Rejected)
        assertTrue((conflict as AdoptionResult.Rejected).failure is DriveFailure.Conflict)
        assertEquals(remoteDocumentId.value, fileDocumentId)
        assertEquals(originalPayload, filePayload)
    }

    @Test
    fun everyUploadReason_isBlockedByPersistedConflict_withoutGatewayMutation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("conflict", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "local"))
        assertTrue(coordinator.enqueueUpload(syncScope, session.token, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        drive.seed(syncScope, "plan.pdf", snapshot(session, "remote"))
        assertTrue(coordinator.enqueueRemoteCheck(syncScope, session.token).await() is SyncOutcome.RemoteConflict)
        val uploadCallCount = drive.calls.count { it.operation == "upload" }

        SyncReason.values()
            .filter { it != SyncReason.REMOTE_CHECK && it != SyncReason.REMOTE_ACCEPTANCE }
            .forEach { reason ->
                val outcome = coordinator.enqueueUpload(syncScope, session.token, reason).await()
                assertEquals(SyncOutcome.BlockedByConflict, outcome)
            }

        assertEquals(uploadCallCount, drive.calls.count { it.operation == "upload" })
        assertEquals(SyncState.Conflict::class, coordinator.status(syncScope)?.state!!::class)
        assertEquals(metadata.snapshot(syncScope)?.conflictCursor, coordinator.status(syncScope)?.conflictCursor)
    }

    @Test
    fun remoteAcceptance_persistsBeforeMemory_andFailureLeavesMemoryCursorAndConflict() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("durable", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "local"))
        coordinator.enqueueUpload(syncScope, session.token, SyncReason.IMMEDIATE).await()
        bridge.events.clear()
        val remote = drive.seed(syncScope, "plan.pdf", snapshot(session, "remote"))
        coordinator.enqueueRemoteCheck(syncScope, session.token).await()
        val acceptedBefore = metadata.snapshot(syncScope)?.acceptedCursor
        bridge.failPersist = true

        val failed = coordinator.enqueueRemoteAcceptance(syncScope, session.token).await()
        assertTrue(failed is SyncOutcome.Failed)
        assertEquals(SyncError.Kind.RECOVERY, (failed as SyncOutcome.Failed).error.kind)
        // The injected persistence failure also prevents the rollback write;
        // both attempted writes are observable, while the durable fixture
        // remains the pre-acceptance local snapshot.
        assertEquals(listOf("persist", "photo-rollback-boundary", "persist"), bridge.events)
        assertEquals("local", bridge.liveSnapshot.pages.getValue(0).notes.single().text)
        assertEquals(snapshot(session, "local"), bridge.durableSnapshot(session.token.documentId))
        assertEquals(acceptedBefore, metadata.snapshot(syncScope)?.acceptedCursor)
        assertEquals(remote.cursor, metadata.snapshot(syncScope)?.conflictCursor)

        bridge.failPersist = false
        bridge.events.clear()
        val applied = coordinator.enqueueRemoteAcceptance(syncScope, session.token).await()
        assertTrue(applied is SyncOutcome.AppliedRemote)
        assertEquals(listOf("persist", "apply", "post-cleanup"), bridge.events)
        assertEquals("remote", bridge.liveSnapshot.pages.getValue(0).notes.single().text)
        assertEquals(remote.cursor, metadata.snapshot(syncScope)?.acceptedCursor)
        assertNull(metadata.snapshot(syncScope)?.conflictCursor)
    }

    @Test
    fun remoteAcceptance_isTrueReplacement_forSparseAndEmptySnapshots() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("replacement", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshotWithPages(session, "local", listOf(0, 2)))
        coordinator.enqueueUpload(syncScope, session.token, SyncReason.IMMEDIATE).await()
        drive.seed(syncScope, "plan.pdf", snapshotWithPages(session, "remote", listOf(0)))
        coordinator.enqueueRemoteCheck(syncScope, session.token).await()
        assertTrue(coordinator.enqueueRemoteAcceptance(syncScope, session.token).await() is SyncOutcome.AppliedRemote)
        assertEquals(setOf(0), bridge.liveSnapshot.pages.keys)

        drive.seed(syncScope, "plan.pdf", emptySnapshot(session))
        coordinator.enqueueRemoteCheck(syncScope, session.token).await()
        assertTrue(coordinator.enqueueRemoteAcceptance(syncScope, session.token).await() is SyncOutcome.AppliedRemote)
        assertTrue(bridge.liveSnapshot.pages.isEmpty())
    }

    @Test
    fun lifecycleCancellation_preventsFinalMutationAndLatePublication() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("cancel", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "local"))
        val gate = CompletableDeferred<Unit>()
        drive.beforeFinalCommit = { gate.await() }
        val request = coordinator.enqueueUpload(syncScope, session.token, SyncReason.PERIODIC)
        runCurrent()
        coordinator.cancelForSessionAndJoin(session.token)
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(drive.record(syncScope))
        assertTrue(bridge.events.none { it == "apply" })
        assertTrue(request.isCancelled || request.isCompleted)
    }

    @Test
    fun periodicHandle_noRemoteStateQueuesCanonicalUpload() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("periodic-no-remote", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val canonical = snapshot(session, "canonical-periodic")
        bridge.setSession(session, canonical)
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))

        val handle = requireNotNull(coordinator.startPeriodic(binding, intervalMillis = 10))
        runCurrent()
        advanceTimeBy(10)
        runCurrent()

        assertEquals(1, drive.calls.count { it.operation == "upload" })
        assertEquals(canonical, drive.record(syncScope)?.snapshot)
        assertEquals(drive.record(syncScope)?.cursor, metadata.snapshot(syncScope)?.acceptedCursor)

        coordinator.stopPeriodicForBinding(binding)
        runCurrent()
        handle.join()
    }

    @Test
    fun periodicHandle_unchangedRemoteQueuesCanonicalUpload() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("periodic-unchanged", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "before-periodic"))
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        assertTrue(coordinator.enqueueUpload(binding, SyncReason.MANUAL).await() is SyncOutcome.Uploaded)
        val canonical = snapshot(session, "after-periodic")
        bridge.liveSnapshot = canonical
        val uploadsBeforePeriodic = drive.calls.count { it.operation == "upload" }

        val handle = requireNotNull(coordinator.startPeriodic(binding, intervalMillis = 10))
        runCurrent()
        advanceTimeBy(10)
        runCurrent()

        assertEquals(uploadsBeforePeriodic + 1, drive.calls.count { it.operation == "upload" })
        assertEquals(canonical, drive.record(syncScope)?.snapshot)
        assertEquals(drive.record(syncScope)?.cursor, metadata.snapshot(syncScope)?.acceptedCursor)

        coordinator.stopPeriodicForBinding(binding)
        runCurrent()
        handle.join()
    }

    @Test
    fun periodicHandle_conflictSuppressesUpload() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("periodic-conflict", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "local"))
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        assertTrue(coordinator.enqueueUpload(binding, SyncReason.MANUAL).await() is SyncOutcome.Uploaded)
        val remote = drive.seed(syncScope, "plan.pdf", snapshot(session, "newer-remote"))
        val uploadsBeforePeriodic = drive.calls.count { it.operation == "upload" }

        val handle = requireNotNull(coordinator.startPeriodic(binding, intervalMillis = 10))
        runCurrent()
        advanceTimeBy(10)
        runCurrent()

        assertEquals(uploadsBeforePeriodic, drive.calls.count { it.operation == "upload" })
        assertTrue(coordinator.status(syncScope)?.state is SyncState.Conflict)
        assertEquals(remote.cursor, metadata.snapshot(syncScope)?.conflictCursor)

        coordinator.stopPeriodicForBinding(binding)
        runCurrent()
        handle.join()
    }

    @Test
    fun periodicHandle_cancellationFencesSuspendedUploadAndStopsFutureTicks() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("periodic-cancel", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "local"))
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        val gatewayEntered = CompletableDeferred<Unit>()
        val releaseGateway = CompletableDeferred<Unit>()
        drive.beforeFinalCommit = {
            gatewayEntered.complete(Unit)
            releaseGateway.await()
        }

        val handle = requireNotNull(coordinator.startPeriodic(binding, intervalMillis = 10))
        runCurrent()
        advanceTimeBy(10)
        runCurrent()
        gatewayEntered.await()
        val uploadsBeforeStop = drive.calls.count { it.operation == "upload" }

        coordinator.stopPeriodicForBinding(binding)
        releaseGateway.complete(Unit)
        advanceUntilIdle()
        handle.join()
        advanceTimeBy(50)
        runCurrent()

        assertTrue(handle.isCancelled)
        assertEquals(uploadsBeforeStop, drive.calls.count { it.operation == "upload" })
        assertNull(drive.record(syncScope))
        assertNull(metadata.snapshot(syncScope)?.acceptedCursor)
    }

    @Test
    fun metadata_isolatedByAccountRootAndDocument_acrossCoordinatorRecreation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val session = session("same-name", "plan.pdf")
        bridge.setSession(session, snapshot(session, "one"))
        val first = coordinator(drive, metadata, bridge, dispatcher)
        val one = scope(session, "account-a", "root-1")
        val two = scope(session, "account-b", "root-1")
        val three = scope(session, "account-a", "root-2")
        first.enqueueUpload(one, session.token, SyncReason.MANUAL).await()
        first.enqueueUpload(two, session.token, SyncReason.MANUAL).await()
        first.enqueueUpload(three, session.token, SyncReason.MANUAL).await()
        first.close()

        val recreated = coordinator(drive, metadata, bridge, dispatcher)
        assertTrue(recreated.enqueueUpload(one, session.token, SyncReason.MANUAL).await() is SyncOutcome.Uploaded)
        assertNotEquals(metadata.snapshot(one)?.acceptedCursor, metadata.snapshot(two)?.acceptedCursor)
        assertNotEquals(metadata.snapshot(one)?.remoteReference?.folderId, metadata.snapshot(two)?.remoteReference?.folderId)
        assertNotNull(metadata.snapshot(three)?.remoteReference)
    }

    @Test
    fun fakePagination_isReal_andReadsDoNotCreateFolders() = runTest {
        val drive = FakeDriveGateway(idFactory = Ids())
        drive.pageSize = 1
        val session = session("target", "plan.pdf")
        val target = scope(session, "account", "root")
        val otherA = scope(session("other-a", "plan.pdf"), "account", "root")
        val otherB = scope(session("other-b", "plan.pdf"), "account", "root")
        drive.seed(otherA, "plan.pdf", snapshot(session("other-a", "plan.pdf"), "a"))
        drive.seed(otherB, "plan.pdf", snapshot(session("other-b", "plan.pdf"), "b"))
        val foldersBeforeRead = drive.createdFolderCount.get()
        assertEquals(RemoteLookup.NotFound, drive.find(target))
        assertEquals(foldersBeforeRead, drive.createdFolderCount.get())
        drive.seed(target, "plan.pdf", snapshot(session, "target"))
        val found = drive.find(target)
        assertTrue(found is RemoteLookup.Found)
        assertTrue(drive.folderPageTokens.size > 1)
        assertTrue(drive.filePageTokens.isNotEmpty())
    }

    @Test
    fun allUploadReasons_captureTheSameCompleteCanonicalSnapshot() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("canonical", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val complete = DocumentSnapshotV1(
            schemaVersion = 1,
            snapshotRevision = 8,
            source = session.target.association.source,
            pages = mapOf(
                0 to PageSnapshotV1(
                    paths = listOf(DrawnPathSnapshotV1(listOf(PointSnapshotV1(1f, 2f)), 0xFF00FF, 2f, false)),
                    measurements = listOf(MeasurementSnapshotV1(PointSnapshotV1(3f, 4f), PointSnapshotV1(5f, 6f), "12'")),
                    notes = listOf(NoteSnapshotV1(1f, 2f, "complete", 12f, true, 3f)),
                    photoPins = listOf(
                        PhotoPinSnapshotV1(
                            0.2f,
                            0.3f,
                            "pin-1",
                            listOf("photo-1.jpg"),
                            mapOf("photo-1.jpg" to listOf(PhotoImageNoteSnapshotV1(0.4f, 0.5f, "image", 11f, false, 1f, 0.02f, "image-note-1"))),
                            mapOf("photo-1.jpg" to listOf(ShapeSnapshotV1(0.5f, 0.6f, 0.2f, 0.3f, 2f, SnapshotShapeTypeV1.CIRCLE, 0x00FF00, 1f, false, 0.01f, 0.2f, 0.3f, "image-shape-1")))
                        )
                    ),
                    scale = PageScaleSnapshotV1(42f),
                    shapes = listOf(ShapeSnapshotV1(10f, 20f, 30f, 40f, 5f, SnapshotShapeTypeV1.RECTANGLE, 0x0000FF, 2f, true, 0.01f, 0.2f, 0.3f, "shape-1"))
                )
            )
        )
        bridge.setSession(session, complete)
        val reasons = listOf(
            SyncReason.IMMEDIATE,
            SyncReason.DEBOUNCED,
            SyncReason.MANUAL,
            SyncReason.PERIODIC,
            SyncReason.PHOTO,
            SyncReason.IMPORT,
            SyncReason.LIFECYCLE
        )
        reasons.forEach { reason ->
            assertTrue(coordinator.enqueueUpload(syncScope, session.token, reason).await() is SyncOutcome.Uploaded)
        }
        assertEquals(reasons.size, bridge.capturedSnapshots.size)
        assertTrue(bridge.capturedSnapshots.all { it == complete })
        assertEquals(listOf(complete, complete), bridge.persistedSnapshots)
        val remote = drive.record(syncScope)?.snapshot?.pages?.getValue(0)
        assertEquals(complete.pages.getValue(0), remote)
        assertEquals(setOf("photo-1.jpg"), drive.record(syncScope)?.photoFiles?.keys)
        assertTrue(drive.record(syncScope)?.photoFiles?.getValue("photo-1.jpg")!!.isNotEmpty())
    }

    @Test
    fun photoUpload_withoutCompleteBytes_failsClosed_beforeRemoteMutation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        bridge.photoContentAvailable = false
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("photo-missing", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshotWithPhoto(session, "photo-missing"))

        val outcome = coordinator.enqueueUpload(syncScope, session.token, SyncReason.PHOTO).await()

        assertTrue(outcome is SyncOutcome.Failed)
        assertEquals(SyncError.Kind.VALIDATION, (outcome as SyncOutcome.Failed).error.kind)
        assertEquals(0, drive.calls.count { it.operation == "upload" })
        assertEquals(0, drive.createdFolderCount.get())
        assertNull(drive.record(syncScope))
        assertNull(metadata.snapshot(syncScope))
    }

    @Test
    fun immediateAndPhotoUploads_requireDurableFrozenSnapshotBeforeRemoteMutation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("immediate-photo-durability", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        val baseline = snapshot(session, "remote-before-failure")
        bridge.setSession(session, baseline)
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        assertTrue(coordinator.enqueueUpload(binding, SyncReason.MANUAL).await() is SyncOutcome.Uploaded)
        val remoteBeforeFailure = requireNotNull(drive.record(syncScope))
        val acceptedBeforeFailure = requireNotNull(metadata.snapshot(syncScope)?.acceptedCursor)
        val frozen = snapshot(session, "must-not-reach-drive")
        bridge.liveSnapshot = frozen
        bridge.capturedSnapshots.clear()
        bridge.persistedSnapshots.clear()
        bridge.events.clear()
        bridge.failPersist = true
        val uploadsBeforeFailure = drive.calls.count { it.operation == "upload" }

        listOf(SyncReason.IMMEDIATE, SyncReason.PHOTO).forEach { reason ->
            val outcome = coordinator.enqueueUpload(binding, reason).await()
            assertTrue(outcome is SyncOutcome.Failed)
            assertEquals(
                SyncError.Kind.LOCAL_PERSISTENCE,
                (outcome as SyncOutcome.Failed).error.kind
            )
        }

        assertEquals(listOf(frozen, frozen), bridge.capturedSnapshots)
        assertEquals(listOf(frozen, frozen), bridge.persistedSnapshots)
        assertEquals(listOf("persist", "persist"), bridge.events)
        assertEquals(uploadsBeforeFailure, drive.calls.count { it.operation == "upload" })
        assertEquals(remoteBeforeFailure, drive.record(syncScope))
        assertEquals(acceptedBeforeFailure, metadata.snapshot(syncScope)?.acceptedCursor)
    }

    @Test
    fun transferFailure_doesNotAdvanceRemoteOrAcceptedCursor() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("transfer-failure", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "before"))
        assertTrue(coordinator.enqueueUpload(syncScope, session.token, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        val before = drive.record(syncScope)!!
        drive.failUpload = DriveFailure.Transfer("upload", "injected")

        val failed = coordinator.enqueueUpload(syncScope, session.token, SyncReason.MANUAL).await()

        assertTrue(failed is SyncOutcome.Failed)
        assertEquals(before, drive.record(syncScope))
        assertEquals(before.cursor, metadata.snapshot(syncScope)?.acceptedCursor)
        assertTrue(coordinator.status(syncScope)?.state is SyncState.Error)
    }

    @Test
    fun acceptedCursor_doesNotRecreateADeletedRemoteDocument() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("deleted-remote", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "before"))
        assertTrue(coordinator.enqueueUpload(syncScope, session.token, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        val acceptedBefore = metadata.snapshot(syncScope)?.acceptedCursor
        val createsBefore = drive.createdFolderCount.get()

        drive.removeRemoteForTesting(syncScope)
        bridge.liveSnapshot = snapshot(session, "should-not-recreate")

        val failed = coordinator.enqueueUpload(syncScope, session.token, SyncReason.MANUAL).await()

        assertTrue(failed is SyncOutcome.Failed)
        assertNull(drive.record(syncScope))
        assertEquals(createsBefore, drive.createdFolderCount.get())
        assertEquals(acceptedBefore, metadata.snapshot(syncScope)?.acceptedCursor)
    }

    @Test
    fun invalidRemotePayload_isRejectedBeforeDurableApply() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("invalid-remote", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "local"))
        assertTrue(coordinator.enqueueUpload(syncScope, session.token, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        val acceptedBefore = metadata.snapshot(syncScope)?.acceptedCursor
        val remote = drive.seed(syncScope, "plan.pdf", snapshot(session, "remote"))
        coordinator.enqueueRemoteCheck(syncScope, session.token).await()
        drive.replaceRemoteSnapshotForTesting(
            syncScope,
            snapshot(session, "invalid").copy(
                pages = mapOf(0 to PageSnapshotV1(notes = listOf(NoteSnapshotV1(Float.NaN, 0f, "invalid", 12f, false, 0f))))
            )
        )
        bridge.events.clear()

        val failed = coordinator.enqueueRemoteAcceptance(syncScope, session.token).await()

        assertTrue(failed is SyncOutcome.Failed)
        assertTrue(bridge.events.isEmpty())
        assertEquals("local", bridge.liveSnapshot.pages.getValue(0).notes.single().text)
        assertEquals(remote.cursor, metadata.snapshot(syncScope)?.conflictCursor)
        assertEquals(acceptedBefore, metadata.snapshot(syncScope)?.acceptedCursor)
    }

    @Test
    fun lifecycleCancellation_duringDownload_preventsApplyAndCursorAdvance() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("cancel-download", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "local"))
        assertTrue(coordinator.enqueueUpload(syncScope, session.token, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        val acceptedBefore = metadata.snapshot(syncScope)?.acceptedCursor
        val remote = drive.seed(syncScope, "plan.pdf", snapshot(session, "remote"))
        coordinator.enqueueRemoteCheck(syncScope, session.token).await()
        val gate = CompletableDeferred<Unit>()
        drive.beforeDownload = { _, _ -> gate.await() }
        val acceptance = coordinator.enqueueRemoteAcceptance(syncScope, session.token)
        runCurrent()

        coordinator.cancelForSessionAndJoin(session.token)
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(acceptance.isCancelled || acceptance.isCompleted)
        assertTrue(bridge.events.none { it == "apply" })
        assertEquals("local", bridge.liveSnapshot.pages.getValue(0).notes.single().text)
        assertEquals(remote.cursor, metadata.snapshot(syncScope)?.conflictCursor)
        assertEquals(acceptedBefore, metadata.snapshot(syncScope)?.acceptedCursor)
    }

    @Test
    fun staleBinding_cannotPublishAfterAccountRootRebind_evenWhenIdsReturn() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("binding-epoch", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "old"))
        val oldBinding = requireNotNull(coordinator.bind(syncScope, session.token))
        val gate = CompletableDeferred<Unit>()
        drive.beforeFinalCommit = { gate.await() }

        val oldUpload = coordinator.enqueueUpload(oldBinding, SyncReason.IMMEDIATE)
        runCurrent()
        coordinator.cancelForSessionAndJoin(session.token)
        val newBinding = requireNotNull(coordinator.bind(syncScope, session.token))
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(oldUpload.isCancelled || oldUpload.isCompleted)
        assertNull(drive.record(syncScope))
        assertTrue(coordinator.enqueueUpload(oldBinding, SyncReason.MANUAL).await() is SyncOutcome.StaleSession)

        bridge.liveSnapshot = snapshot(session, "new")
        assertTrue(coordinator.enqueueUpload(newBinding, SyncReason.MANUAL).await() is SyncOutcome.Uploaded)
        assertEquals("new", drive.record(syncScope)?.snapshot?.pages?.getValue(0)?.notes?.single()?.text)
        assertTrue(bridge.events.none { it == "apply" })
    }

    @Test
    fun authoritativeScopeProvider_rejectsStaleClosure_withoutFencingNewBinding() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val session = session("scope-provider", "plan.pdf")
        bridge.setSession(session, snapshot(session, "current"))
        val firstScope = scope(session, "account-a", "root-a")
        val secondScope = scope(session, "account-b", "root-b")
        var currentScope: SyncScope? = firstScope
        val coordinator = SyncCoordinator(
            gateway = drive,
            metadataStore = metadata,
            bridge = bridge,
            parentScope = CoroutineScope(dispatcher + SupervisorJob()),
            dispatcher = dispatcher,
            currentScopeProvider = { currentScope }
        ).also(coordinators::add)
        val firstBinding = requireNotNull(coordinator.bind(firstScope, session.token))

        currentScope = secondScope
        val secondBinding = requireNotNull(coordinator.bind(secondScope, session.token))

        coordinator.updateCurrentScope(firstScope)
        assertFalse(coordinator.admit(firstBinding, firstScope))
        assertTrue(coordinator.isBindingCurrent(secondBinding))
        assertTrue(coordinator.enqueueUpload(secondBinding, SyncReason.MANUAL).await() is SyncOutcome.Uploaded)
        assertEquals(1, drive.calls.count { it.operation == "upload" })
        assertEquals(secondScope, drive.record(secondScope)?.scope)
        assertNull(drive.record(firstScope))
    }

    @Test
    fun remoteRevisionChangeDuringDownload_rejectsAcceptanceWithoutCursorAdvance() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("cursor-revalidation", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "local"))
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        val acceptedBefore = metadata.snapshot(syncScope)?.acceptedCursor
        val remote = drive.seed(syncScope, "plan.pdf", snapshot(session, "remote"))
        assertTrue(coordinator.enqueueRemoteCheck(binding).await() is SyncOutcome.RemoteConflict)
        drive.mutateRevisionDuringDownload = true
        bridge.events.clear()

        val failed = coordinator.enqueueRemoteAcceptance(binding).await()

        assertTrue(failed is SyncOutcome.Failed)
        assertTrue(bridge.events.isEmpty())
        assertEquals("local", bridge.liveSnapshot.pages.getValue(0).notes.single().text)
        assertEquals(acceptedBefore, metadata.snapshot(syncScope)?.acceptedCursor)
        assertEquals(remote.cursor, metadata.snapshot(syncScope)?.conflictCursor)
    }

    @Test
    fun closeHandle_joinsSuspendedUpload_beforeReturning() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, dispatcher)
        val session = session("close-join", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "local"))
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        val gate = CompletableDeferred<Unit>()
        drive.beforeFinalCommit = { gate.await() }
        val upload = coordinator.enqueueUpload(binding, SyncReason.LIFECYCLE)
        runCurrent()

        val closeHandle = coordinator.close()
        closeHandle.join()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(upload.isCancelled || upload.isCompleted)
        assertNull(drive.record(syncScope))
        assertNull(metadata.snapshot(syncScope))
        assertTrue(bridge.events.none { it == "apply" })
    }

    @Test
    fun lifecycleFinalizer_awaitsSuspendedGateway_beforeReturning() = runTest {
        val drive = FakeDriveGateway(idFactory = Ids())
        val metadata = InMemorySyncMetadataStore()
        val bridge = FakeBridge()
        val coordinator = coordinator(drive, metadata, bridge, StandardTestDispatcher(testScheduler))
        val session = session("lifecycle-finalizer", "plan.pdf")
        val syncScope = scope(session, "account", "root")
        bridge.setSession(session, snapshot(session, "local"))
        val binding = requireNotNull(coordinator.bind(syncScope, session.token))
        val gatewayEntered = CompletableDeferred<Unit>()
        val releaseGateway = CompletableDeferred<Unit>()
        drive.insideFinalMutation = {
            gatewayEntered.complete(Unit)
            withContext(NonCancellable) {
                releaseGateway.await()
            }
            throw CancellationException("lifecycle finalizer canceled suspended gateway")
        }

        val upload = coordinator.enqueueUpload(binding, SyncReason.LIFECYCLE)
        gatewayEntered.await()
        val finalizer = async {
            runSyncCoordinatorLifecycleFinalizer(coordinator)
        }
        runCurrent()
        assertFalse(finalizer.isCompleted)

        releaseGateway.complete(Unit)
        finalizer.await()
        advanceUntilIdle()

        assertTrue(upload.isCancelled || upload.isCompleted)
        assertNull(drive.record(syncScope))
        assertNull(metadata.snapshot(syncScope))
        assertTrue(bridge.events.isEmpty())
    }

    private fun coordinator(
        drive: DriveGateway,
        metadata: SyncMetadataStore,
        bridge: FakeBridge,
        dispatcher: TestDispatcher
    ): SyncCoordinator = SyncCoordinator(
        gateway = drive,
        metadataStore = metadata,
        bridge = bridge,
        parentScope = CoroutineScope(dispatcher + SupervisorJob()),
        dispatcher = dispatcher
    ).also(coordinators::add)

    private fun scope(session: DocumentSession, account: String, root: String): SyncScope =
        SyncScope(account, root, session.token.documentId)

    private fun session(id: String, displayName: String): DocumentSession {
        val documentId = DocumentId.new()
        val source = DocumentSourceIdentityV1("content://$id", displayName)
        val association = DocumentAssociation(documentId, source, null, "legacy-$id.bin")
        return DocumentSession(
            target = ResolvedDocumentTarget(association),
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
        val association = DocumentAssociation(documentId, source, fingerprint, "legacy-$id.bin")
        return DocumentSession(
            target = ResolvedDocumentTarget(association),
            token = DocumentSessionToken(documentId, source.sourceUri, fingerprint, 1L)
        )
    }

    private fun DocumentSession.documentId(): String = token.documentId.value

    private fun snapshot(session: DocumentSession, marker: String): DocumentSnapshotV1 =
        DocumentSnapshotV1(
            schemaVersion = 1,
            snapshotRevision = 0,
            source = session.target.association.source,
            pages = mapOf(0 to PageSnapshotV1(notes = listOf(NoteSnapshotV1(1f, 2f, marker, 12f, false, 0f))))
        )

    private fun snapshotWithPages(
        session: DocumentSession,
        marker: String,
        pages: List<Int>
    ): DocumentSnapshotV1 = DocumentSnapshotV1(
        schemaVersion = 1,
        snapshotRevision = 0,
        source = session.target.association.source,
        pages = pages.associateWith { index ->
            PageSnapshotV1(notes = listOf(NoteSnapshotV1(index.toFloat(), 2f, "$marker-$index", 12f, false, 0f)))
        }
    )

    private fun emptySnapshot(session: DocumentSession): DocumentSnapshotV1 =
        DocumentSnapshotV1(1, 0, session.target.association.source, emptyMap())

    private fun snapshotWithPhoto(session: DocumentSession, marker: String): DocumentSnapshotV1 =
        DocumentSnapshotV1(
            schemaVersion = 1,
            snapshotRevision = 0,
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

    private fun Throwable.containsMessage(
        fragment: String,
        seen: MutableSet<Throwable> = mutableSetOf()
    ): Boolean {
        if (!seen.add(this)) return false
        if (message?.contains(fragment) == true) return true
        if (cause?.containsMessage(fragment, seen) == true) return true
        return suppressed.any { it.containsMessage(fragment, seen) }
    }

    private class Ids : () -> String {
        private var next = 0
        override fun invoke(): String = "id-${next++}"
    }

    private class FakeBridge : SyncSessionBridge {
        lateinit var session: DocumentSession
        lateinit var liveSnapshot: DocumentSnapshotV1
        private var hasPrimarySession = false
        private val sessionsByDocument = mutableMapOf<DocumentId, DocumentSession>()
        private val snapshotsByDocument = mutableMapOf<DocumentId, DocumentSnapshotV1>()
        var failPersist: Boolean = false
        var failDurableCapture: Boolean = false
        var failApply: Boolean = false
        var failNextApply: Boolean = false
        var photoContentAvailable: Boolean = true
        var admissionFailure: Throwable? = null
        var capturedPhotoContent: Map<String, ByteArray>? = null
        var admissionCaptureHook: (suspend (DocumentSnapshotV1, DocumentSnapshotV1) -> Map<String, ByteArray>)? = null
        var preparedPhotoTransaction: PhotoContentTransaction? = null
        var postCommitCleanup: ((DocumentSnapshotV1) -> Unit)? = null
        var ready: Boolean = true
        var afterApplyHook: (suspend () -> Unit)? = null
        var afterPhotoRollbackFailure: Throwable? = null
        val events = mutableListOf<String>()
        val capturedSnapshots = mutableListOf<DocumentSnapshotV1>()
        val persistedSnapshots = mutableListOf<DocumentSnapshotV1>()
        val photoAdmissionSnapshots = mutableListOf<Pair<DocumentSnapshotV1, DocumentSnapshotV1>>()
        val postCommitCleanupSnapshots = mutableListOf<DocumentSnapshotV1>()
        val errors = mutableListOf<SyncError>()

        fun setSession(session: DocumentSession, snapshot: DocumentSnapshotV1) {
            this.session = session
            this.liveSnapshot = snapshot
            hasPrimarySession = true
            sessionsByDocument[session.token.documentId] = session
            snapshotsByDocument[session.token.documentId] = snapshot
        }

        fun durableSnapshot(documentId: DocumentId): DocumentSnapshotV1? =
            snapshotsByDocument[documentId]

        override fun currentSession(scope: SyncScope): DocumentSession? =
            sessionsByDocument[scope.documentId]

        override suspend fun captureSnapshot(session: DocumentSession): DocumentSnapshotV1? {
            if (!isCurrent(session.token)) return null
            val captured = if (hasPrimarySession && this@FakeBridge.session.token.documentId == session.token.documentId
            ) {
                liveSnapshot
            } else {
                snapshotsByDocument[session.token.documentId] ?: return null
            }
            capturedSnapshots += captured
            return captured
        }

        override suspend fun captureDurableSnapshot(session: DocumentSession): DocumentSnapshotV1? {
            if (failDurableCapture) throw IllegalStateException("injected durable snapshot failure")
            return snapshotsByDocument[session.token.documentId]
        }

        override suspend fun persistSnapshot(
            session: DocumentSession,
            snapshot: DocumentSnapshotV1
        ): DocumentSaveResult {
            events += "persist"
            persistedSnapshots += snapshot
            return if (failPersist) {
                DocumentSaveResult.Failed(
                    com.example.myapplication.stage2.LocalRepositoryError.IoFailure(
                        "test", null, "injected"
                    )
                )
            } else {
                snapshotsByDocument[session.token.documentId] = snapshot
                DocumentSaveResult.Saved(session.token.documentId)
            }
        }

        override fun isCurrent(token: DocumentSessionToken): Boolean =
            sessionsByDocument[token.documentId]?.token == token

        override fun isReady(token: DocumentSessionToken): Boolean =
            ready && isCurrent(token)

        override fun hasRequiredPhotoContent(snapshot: DocumentSnapshotV1): Boolean =
            photoContentAvailable

        override suspend fun capturePhotoContent(snapshot: DocumentSnapshotV1): Map<String, ByteArray> =
            if (photoContentAvailable) {
                requiredPhotoFileNames(snapshot).associateWith { name ->
                    capturedPhotoContent?.get(name)?.copyOf() ?: Stage4PhotoFixture.jpegBytes()
                }
            } else emptyMap()

        override suspend fun capturePhotoContentForAdmission(
            session: DocumentSession,
            currentDurableSnapshot: DocumentSnapshotV1,
            currentLiveSnapshot: DocumentSnapshotV1
        ): Map<String, ByteArray> {
            photoAdmissionSnapshots += currentDurableSnapshot to currentLiveSnapshot
            return admissionCaptureHook?.invoke(currentDurableSnapshot, currentLiveSnapshot)
                ?: capturePhotoContent(currentLiveSnapshot)
        }

        override suspend fun hasRequiredPhotoContentForAdmission(
            session: DocumentSession,
            currentDurableSnapshot: DocumentSnapshotV1,
            currentLiveSnapshot: DocumentSnapshotV1
        ): Boolean {
            photoAdmissionSnapshots += currentDurableSnapshot to currentLiveSnapshot
            admissionFailure?.let { throw it }
            return photoContentAvailable
        }

        override fun onError(binding: SyncBinding, error: SyncError) {
            errors += error
        }

        override suspend fun persistPhotoContent(
            session: DocumentSession,
            remote: RemoteSnapshotEnvelope
        ): DocumentSaveResult {
            validatedPhotoFiles(remote.snapshot, remote.photoFiles)
            if (remote.photoFiles.isNotEmpty()) events += "photos"
            return DocumentSaveResult.Saved(session.token.documentId)
        }

        override suspend fun preparePhotoContent(
            session: DocumentSession,
            remote: RemoteSnapshotEnvelope
        ): PhotoContentPreparation = PhotoContentPreparation(
            result = persistPhotoContent(session, remote),
            transaction = preparedPhotoTransaction
        )

        override fun applySnapshotReplace(session: DocumentSession, snapshot: DocumentSnapshotV1) {
            check(isCurrent(session.token))
            check(!failApply && !failNextApply) {
                failNextApply = false
                "injected apply failure"
            }
            events += "apply"
            snapshotsByDocument[session.token.documentId] = snapshot
            if (hasPrimarySession && this@FakeBridge.session.token.documentId == session.token.documentId
            ) {
                liveSnapshot = snapshot
            }
        }

        override suspend fun afterSnapshotAppliedWithinDocumentTransaction() {
            afterApplyHook?.invoke()
        }

        override suspend fun afterPhotoRollbackBeforeCanonicalRestore() {
            events += "photo-rollback-boundary"
            afterPhotoRollbackFailure?.let { throw it }
        }

        override suspend fun cleanupPhotoContentAfterCommit(
            session: DocumentSession,
            acceptedSnapshot: DocumentSnapshotV1
        ) {
            events += "post-cleanup"
            postCommitCleanupSnapshots += acceptedSnapshot
            postCommitCleanup?.invoke(acceptedSnapshot)
        }
    }
}
