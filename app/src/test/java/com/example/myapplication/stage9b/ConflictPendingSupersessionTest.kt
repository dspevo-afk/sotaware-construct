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
import com.example.myapplication.stage4.DurablePendingUpload
import com.example.myapplication.stage4.DriveFailure
import com.example.myapplication.stage4.DriveGateway
import com.example.myapplication.stage4.DownloadResult
import com.example.myapplication.stage4.FakeDriveGateway
import com.example.myapplication.stage4.InMemorySyncMetadataStore
import com.example.myapplication.stage4.MetadataReadResult
import com.example.myapplication.stage4.MetadataWriteResult
import com.example.myapplication.stage4.PendingUploadIntent
import com.example.myapplication.stage4.RemoteCursor
import com.example.myapplication.stage4.RemoteDocumentMetadata
import com.example.myapplication.stage4.RemoteLookup
import com.example.myapplication.stage4.RemoteReference
import com.example.myapplication.stage4.RemoteSnapshotEnvelope
import com.example.myapplication.stage4.SyncCoordinator
import com.example.myapplication.stage4.SyncMetadata
import com.example.myapplication.stage4.SyncMetadataError
import com.example.myapplication.stage4.SyncMetadataStore
import com.example.myapplication.stage4.SyncOutcome
import com.example.myapplication.stage4.SyncReason
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage4.SyncSessionBridge
import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage4.TestPersistentPhotoMetadataStore
import com.example.myapplication.stage4.UploadRequest
import com.example.myapplication.stage4.UploadResult
import com.example.myapplication.stage5.DefaultImageProbe
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.io.File

/** Regressions for pending conflict provenance and outbox asset ownership. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConflictPendingSupersessionTest {
    @Test
    fun matchingCurrentPendingOwner_readbackIsAcceptedAsTheSameHandoff() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = IdFactory())
        val metadata = InMemorySyncMetadataStore()
        val session = session("matching-current-owner")
        val scope = SyncScope("account", "root", session.token.documentId)
        val baseline = textSnapshot(session, "baseline")
        val bridge = TestBridge(session, baseline)
        val coordinator = newCoordinator(drive, metadata, bridge, dispatcher)
        try {
            val binding = requireNotNull(coordinator.bind(scope, session.token))
            assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
            val remote = drive.seed(scope, "plan.pdf", textSnapshot(session, "remote"))
            val local = textSnapshot(session, "same-pending")
            bridge.liveSnapshot = local
            assertTrue(coordinator.enqueueUpload(binding, SyncReason.MANUAL).await() is SyncOutcome.RemoteConflict)

            // Reusing the exact incumbent pending owner is a valid read-back
            // CAS, not a concurrent replacement.
            bridge.liveSnapshot = local
            assertTrue(coordinator.enqueueUpload(binding, SyncReason.MANUAL).await() is SyncOutcome.BlockedByConflict)
            assertEquals(local, metadata.snapshot(scope)?.pendingUpload?.snapshot)
            assertEquals(remote.cursor, metadata.snapshot(scope)?.conflictCursor)
        } finally {
            coordinator.closeAndJoin()
        }
    }

    @Test
    fun conflictedAutomaticRetry_supersedesOlderPending_andAcceptanceReplaysNewestLiveSnapshot() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = IdFactory())
        val metadata = InMemorySyncMetadataStore()
        val session = session("conflict-supersession")
        val scope = SyncScope("account", "root", session.token.documentId)
        val baseline = textSnapshot(session, "baseline")
        val bridge = TestBridge(session, baseline)
        val coordinator = newCoordinator(drive, metadata, bridge, dispatcher)
        try {
            val binding = requireNotNull(coordinator.bind(scope, session.token))
            assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)

            val remote = drive.seed(scope, "plan.pdf", textSnapshot(session, "remote"))
            val firstLocal = textSnapshot(session, "L1")
            bridge.liveSnapshot = firstLocal
            assertTrue(coordinator.enqueueUpload(binding, SyncReason.MANUAL).await() is SyncOutcome.RemoteConflict)

            val newerLocal = textSnapshot(session, "L2")
            bridge.liveSnapshot = newerLocal
            assertTrue(coordinator.enqueueUpload(binding, SyncReason.MANUAL).await() is SyncOutcome.BlockedByConflict)

            val pending = requireNotNull(metadata.snapshot(scope)?.pendingUpload)
            assertEquals("new live edits must replace the old conflicted retry", newerLocal, pending.snapshot)
            assertEquals(PendingUploadIntent.AUTOMATIC_RETRY, pending.pendingUploadIntent)
            assertEquals(remote.cursor, metadata.snapshot(scope)?.conflictCursor)
            assertEquals(newerLocal, bridge.durableSnapshot)

            assertTrue(coordinator.enqueueRemoteAcceptance(binding).await() is SyncOutcome.AppliedRemote)
            advanceUntilIdle()

            assertEquals(newerLocal, drive.record(scope)?.snapshot)
            assertNull(metadata.snapshot(scope)?.pendingUpload)
            assertNull(metadata.snapshot(scope)?.conflictCursor)
            assertEquals(newerLocal, bridge.liveSnapshot)
        } finally {
            coordinator.closeAndJoin()
        }
    }

    @Test
    fun conflictedAutomaticRetry_failedSupersession_retainsOldPendingAndNewLiveState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = IdFactory())
        val metadata = FailingMetadataStore()
        val session = session("conflict-supersession-failure")
        val scope = SyncScope("account", "root", session.token.documentId)
        val oldPending = textSnapshot(session, "L1")
        val newerLocal = textSnapshot(session, "L2")
        val bridge = TestBridge(session, newerLocal, durableSnapshot = oldPending)
        assertEquals(
            MetadataWriteResult.Committed,
            metadata.write(
                SyncMetadata(
                    scope = scope,
                    conflictCursor = RemoteCursor("remote-1"),
                    pendingUpload = DurablePendingUpload(
                        reason = SyncReason.MANUAL,
                        sourceUri = session.token.sourceUri,
                        sourceFingerprint = null,
                        generation = 1L,
                        expectedCursor = null,
                        snapshot = oldPending
                    )
                )
            )
        )
        metadata.failNextPendingSnapshot = newerLocal

        val coordinator = newCoordinator(drive, metadata, bridge, dispatcher)
        try {
            val binding = requireNotNull(coordinator.bind(scope, session.token))
            val outcome = coordinator.enqueueUpload(binding, SyncReason.MANUAL).await()

            assertTrue(outcome is SyncOutcome.Failed)
            assertEquals(oldPending, metadata.snapshot(scope)?.pendingUpload?.snapshot)
            assertEquals(RemoteCursor("remote-1"), metadata.snapshot(scope)?.conflictCursor)
            assertEquals(newerLocal, bridge.liveSnapshot)
            assertEquals(newerLocal, bridge.durableSnapshot)
            assertTrue(drive.calls.none { it.operation == "upload" })
        } finally {
            coordinator.closeAndJoin()
        }
    }

    @Test
    fun conflictedSupersession_publicationCancellation_readsBackNewOwnerBeforeRethrow() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = IdFactory())
        val metadata = CancelAfterPendingPublicationStore()
        val session = session("supersession-cancellation")
        val scope = SyncScope("account", "root", session.token.documentId)
        val oldPending = textSnapshot(session, "L1")
        val newerLocal = textSnapshot(session, "L2")
        assertEquals(
            MetadataWriteResult.Committed,
            metadata.write(
                SyncMetadata(
                    scope = scope,
                    conflictCursor = RemoteCursor("remote-1"),
                    pendingUpload = DurablePendingUpload(
                        reason = SyncReason.MANUAL,
                        sourceUri = session.token.sourceUri,
                        sourceFingerprint = null,
                        generation = 1L,
                        expectedCursor = null,
                        snapshot = oldPending
                    )
                )
            )
        )
        metadata.cancelSnapshot = newerLocal
        val bridge = TestBridge(session, newerLocal, durableSnapshot = newerLocal)
        val coordinator = newCoordinator(drive, metadata, bridge, dispatcher)
        try {
            val binding = requireNotNull(coordinator.bind(scope, session.token))
            var cancelled = false
            try {
                coordinator.enqueueUpload(binding, SyncReason.MANUAL).await()
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue("publication cancellation must remain observable", cancelled)
            assertEquals(newerLocal, metadata.snapshot(scope)?.pendingUpload?.snapshot)
            assertEquals(RemoteCursor("remote-1"), metadata.snapshot(scope)?.conflictCursor)
            assertEquals(newerLocal, bridge.liveSnapshot)
            assertTrue(drive.calls.none { it.operation == "upload" })
        } finally {
            coordinator.closeAndJoin()
        }
    }

    @Test
    fun conflictedPhotoSupersession_failureRetainsIncumbentLeaseAndBytes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val metadataDelegate = TestPersistentPhotoMetadataStore(dispatcher = dispatcher)
        val metadata = FailingPhotoMetadataStore(metadataDelegate)
        val poolRoot = Files.createTempDirectory("stage9b-failed-photo-pool").toFile()
        val pool = photoPool(poolRoot)
        val drive = FakeDriveGateway(idFactory = IdFactory())
        val session = session("failed-photo-supersession")
        val scope = SyncScope("account", "root", session.token.documentId)
        val bridge = TestBridge(session, textSnapshot(session, "baseline"))
        val coordinator = newCoordinator(drive, metadata, bridge, dispatcher)
        try {
            val binding = requireNotNull(coordinator.bind(scope, session.token))
            assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
            val remote = drive.seed(scope, "plan.pdf", textSnapshot(session, "remote"))

            val firstBytes = Stage4PhotoFixture.jpegBytes()
            val first = photoSnapshot(session, "L1")
            bridge.liveSnapshot = first
            bridge.photoCaptureFactory = { pool.capture(testPhotoAssets(mapOf("L1.jpg" to firstBytes))) }
            assertTrue(coordinator.enqueueUpload(binding, SyncReason.PHOTO).await() is SyncOutcome.RemoteConflict)
            val incumbent = requireNotNull(metadata.snapshot(scope)?.pendingUpload)
            val incumbentLease = requireNotNull(incumbent.outboxLease)

            val second = photoSnapshot(session, "L2")
            bridge.liveSnapshot = second
            bridge.photoCaptureFactory = { pool.capture(testPhotoAssets(mapOf("L2.jpg" to firstBytes))) }
            metadata.failSnapshot = second
            assertTrue(coordinator.enqueueUpload(binding, SyncReason.PHOTO).await() is SyncOutcome.Failed)

            val retained = requireNotNull(metadata.snapshot(scope)?.pendingUpload)
            assertEquals(first, retained.snapshot)
            assertEquals(remote.cursor, metadata.snapshot(scope)?.conflictCursor)
            assertTrue("failed supersession must not close the incumbent lease", !incumbentLease.isReleased)
            assertArrayEquals(
                firstBytes,
                retained.photoFiles.getValue("L1.jpg").open().use { it.readBytes() }
            )
        } finally {
            coordinator.closeAndJoin()
            pool.close()
            metadata.close()
            poolRoot.deleteRecursively()
        }
    }

    @Test
    fun acceptedPhotoCycles_retireOutboxClaimsBeforeRetentionBound() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val metadata = TestPersistentPhotoMetadataStore(dispatcher = dispatcher)
        val poolRoot = Files.createTempDirectory("stage9b-photo-cycles-pool").toFile()
        val pool = photoPool(poolRoot)
        val drive = FakeDriveGateway(idFactory = IdFactory())
        val session = session("photo-cycles")
        val scope = SyncScope("account", "root", session.token.documentId)
        val baseline = textSnapshot(session, "baseline")
        val bridge = TestBridge(session, baseline)
        val coordinator = newCoordinator(drive, metadata, bridge, dispatcher)
        try {
            val binding = requireNotNull(coordinator.bind(scope, session.token))
            assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
            repeat(6) { index ->
                val marker = "photo-$index"
                val bytes = Stage4PhotoFixture.jpegBytes()
                bridge.liveSnapshot = photoSnapshot(session, marker)
                bridge.photoCaptureFactory = {
                    pool.capture(testPhotoAssets(mapOf("$marker.jpg" to bytes)))
                }
                assertTrue(coordinator.enqueueUpload(binding, SyncReason.PHOTO).await() is SyncOutcome.Uploaded)
                assertEquals(0, outboxContentDirectoryCount(metadata.rootDirectory))
                pool.cleanupUnreachable()
            }
            assertEquals(0, pool.assetCount)
        } finally {
            coordinator.closeAndJoin()
            pool.close()
            metadata.close()
            poolRoot.deleteRecursively()
        }
    }

    @Test
    fun acceptedConflict_recreationBeforeReplay_preservesExplicitReplayIntent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = IdFactory())
        val metadata = InMemorySyncMetadataStore()
        val session = session("explicit-replay-recreation")
        val scope = SyncScope("account", "root", session.token.documentId)
        val baseline = textSnapshot(session, "baseline")
        val bridge = TestBridge(session, baseline)
        val firstCoordinator = newCoordinator(drive, metadata, bridge, dispatcher)
        val firstBinding = requireNotNull(firstCoordinator.bind(scope, session.token))
        assertTrue(firstCoordinator.enqueueUpload(firstBinding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)
        drive.seed(scope, "plan.pdf", textSnapshot(session, "remote"))
        val local = textSnapshot(session, "local")
        bridge.liveSnapshot = local
        assertTrue(firstCoordinator.enqueueUpload(firstBinding, SyncReason.MANUAL).await() is SyncOutcome.RemoteConflict)
        val replayEntered = CompletableDeferred<Unit>()
        val replayGate = CompletableDeferred<Unit>()
        bridge.replayPersistenceSnapshot = local
        bridge.replayPersistenceEntered = replayEntered
        bridge.replayPersistenceGate = replayGate
        assertTrue(firstCoordinator.enqueueRemoteAcceptance(firstBinding).await() is SyncOutcome.AppliedRemote)
        replayEntered.await()
        assertEquals(
            PendingUploadIntent.EXPLICIT_CONFLICT_REPLAY,
            metadata.snapshot(scope)?.pendingUpload?.pendingUploadIntent
        )
        firstCoordinator.closeAndJoin()
        bridge.replayPersistenceSnapshot = null
        bridge.replayPersistenceEntered = null
        bridge.replayPersistenceGate = null

        val recreated = newCoordinator(drive, metadata, bridge, dispatcher)
        try {
            val rebound = requireNotNull(recreated.bind(scope, session.token))
            assertTrue(recreated.enqueueUpload(rebound, SyncReason.MANUAL).await() is SyncOutcome.Uploaded)
            assertEquals(local, drive.record(scope)?.snapshot)
            assertNull(metadata.snapshot(scope)?.pendingUpload)
        } finally {
            recreated.closeAndJoin()
        }
    }

    @Test
    fun durablePending_changedSourceFingerprint_isRejectedBeforeRemoteMutation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = IdFactory())
        val metadata = InMemorySyncMetadataStore()
        val fingerprintA = SourceFingerprint(SourceFingerprint.SHA256_ALGORITHM, "a".repeat(64), 10L)
        val fingerprintB = SourceFingerprint(SourceFingerprint.SHA256_ALGORITHM, "b".repeat(64), 10L)
        val session = session("source-fingerprint", fingerprint = fingerprintB)
        val scope = SyncScope("account", "root", session.token.documentId)
        val old = textSnapshot(session, "old")
        assertEquals(
            MetadataWriteResult.Committed,
            metadata.write(
                SyncMetadata(
                    scope = scope,
                    pendingUpload = DurablePendingUpload(
                        reason = SyncReason.MANUAL,
                        sourceUri = session.token.sourceUri,
                        sourceFingerprint = fingerprintA,
                        generation = 1L,
                        expectedCursor = null,
                        snapshot = old
                    )
                )
            )
        )
        val bridge = TestBridge(session, old)
        val coordinator = newCoordinator(drive, metadata, bridge, dispatcher)
        try {
            val binding = requireNotNull(coordinator.bind(scope, session.token))
            assertTrue(coordinator.enqueueUpload(binding, SyncReason.MANUAL).await() is SyncOutcome.Failed)
            assertTrue(drive.calls.none { it.operation == "upload" })
            assertEquals(fingerprintA, metadata.snapshot(scope)?.pendingUpload?.sourceFingerprint)
        } finally {
            coordinator.closeAndJoin()
        }
    }

    @Test
    fun durablePending_rebindAllowsNewSessionGenerationForSameStableIdentity() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val drive = FakeDriveGateway(idFactory = IdFactory())
        val metadata = InMemorySyncMetadataStore()
        val fingerprint = SourceFingerprint(SourceFingerprint.SHA256_ALGORITHM, "c".repeat(64), 10L)
        val original = session("same-stable-identity", fingerprint = fingerprint, generation = 1L)
        val rebound = session(
            "same-stable-identity",
            fingerprint = fingerprint,
            generation = 2L,
            documentId = original.token.documentId
        )
        val scope = SyncScope("account", "root", original.token.documentId)
        val pendingSnapshot = textSnapshot(original, "rebind")
        assertEquals(
            MetadataWriteResult.Committed,
            metadata.write(
                SyncMetadata(
                    scope = scope,
                    pendingUpload = DurablePendingUpload(
                        reason = SyncReason.MANUAL,
                        sourceUri = original.token.sourceUri,
                        sourceFingerprint = fingerprint,
                        generation = 1L,
                        expectedCursor = null,
                        snapshot = pendingSnapshot
                    )
                )
            )
        )
        val bridge = TestBridge(rebound, pendingSnapshot)
        val coordinator = newCoordinator(drive, metadata, bridge, dispatcher)
        try {
            val binding = requireNotNull(coordinator.bind(scope, rebound.token))
            assertTrue(coordinator.enqueueUpload(binding, SyncReason.MANUAL).await() is SyncOutcome.Uploaded)
            assertEquals(pendingSnapshot, drive.record(scope)?.snapshot)
        } finally {
            coordinator.closeAndJoin()
        }
    }

    @Test
    fun remoteCheck_rejectsMisboundScopeAndReferenceBeforeCursorPublication() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val metadata = InMemorySyncMetadataStore()
        val session = session("remote-identity-fence")
        val scope = SyncScope("account", "root", session.token.documentId)
        val foreignSession = session("foreign-remote")
        val foreignScope = SyncScope("other-account", "other-root", foreignSession.token.documentId)
        val foreign = RemoteDocumentMetadata(
            scope = foreignScope,
            displayName = "foreign.pdf",
            reference = RemoteReference(
                folderId = "foreign-folder",
                snapshotFileId = "foreign-file",
                appProperties = mapOf(
                    com.example.myapplication.stage4.SYNC_DOCUMENT_ID_APP_PROPERTY to
                        foreignScope.documentId.value,
                    "sotaware_account_id" to foreignScope.accountId,
                    "sotaware_backup_root_id" to foreignScope.backupRootId
                )
            ),
            cursor = RemoteCursor("foreign-revision")
        )
        val bridge = TestBridge(session, textSnapshot(session, "local"))
        val coordinator = SyncCoordinator(
            gateway = MisboundFindGateway(foreign),
            metadataStore = metadata,
            bridge = bridge,
            parentScope = CoroutineScope(dispatcher + SupervisorJob()),
            dispatcher = dispatcher
        )
        try {
            val binding = requireNotNull(coordinator.bind(scope, session.token))
            val outcome = coordinator.enqueueRemoteCheck(binding).await()
            assertTrue("a foreign scope must fail closed", outcome is SyncOutcome.Failed)
            assertNull(metadata.snapshot(scope)?.remoteReference)
            assertNull(metadata.snapshot(scope)?.acceptedCursor)
            assertNull(metadata.snapshot(scope)?.conflictCursor)
        } finally {
            coordinator.closeAndJoin()
        }
    }


    @Test
    fun freshConflict_photoPending_reopensOutboxBeforeCaptureRelease_andSurvivesPoolCollection() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val metadata = TestPersistentPhotoMetadataStore(dispatcher = dispatcher)
        val poolRoot = Files.createTempDirectory("stage9b-conflict-pool").toFile()
        val pool = ImmutablePhotoAssetPool(
            rootDirectory = poolRoot,
            imageProbe = DefaultImageProbe,
            maxAssetCount = IMMUTABLE_PHOTO_POOL_MAX_ASSET_COUNT,
            maxTotalBytes = IMMUTABLE_PHOTO_POOL_MAX_TOTAL_BYTES,
            operationsFactory = TestPhotoPathOperationsFactory,
            trustedRootDirectory = null
        )
        val drive = FakeDriveGateway(idFactory = IdFactory())
        val session = session("fresh-conflict-photo")
        val scope = SyncScope("account", "root", session.token.documentId)
        val baseline = textSnapshot(session, "baseline")
        val bridge = TestBridge(session, baseline)
        var coordinator: SyncCoordinator? = null
        var recreated: SyncCoordinator? = null
        try {
            coordinator = newCoordinator(drive, metadata, bridge, dispatcher)
            val binding = requireNotNull(coordinator.bind(scope, session.token))
            assertTrue(coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await() is SyncOutcome.Uploaded)

            val remote = drive.seed(scope, "plan.pdf", textSnapshot(session, "remote"))
            assertTrue(coordinator.enqueueRemoteCheck(binding).await() is SyncOutcome.RemoteConflict)

            val bytes = Stage4PhotoFixture.jpegBytes()
            val local = photoSnapshot(session, "local")
            val poolAssets = testPhotoAssets(mapOf("local.jpg" to bytes))
            var capture: PhotoAssetCapture? = null
            bridge.photoCaptureFactory = {
                pool.capture(poolAssets).also { capture = it }
            }
            bridge.liveSnapshot = local

            assertTrue(coordinator.enqueueUpload(binding, SyncReason.MANUAL).await() is SyncOutcome.BlockedByConflict)
            assertTrue("the transient capture must be released after outbox adoption", capture?.isReleased == true)

            val pending = requireNotNull(metadata.snapshot(scope)?.pendingUpload)
            assertEquals(local, pending.snapshot)
            assertEquals(PendingUploadIntent.AUTOMATIC_RETRY, pending.pendingUploadIntent)
            assertTrue("pending photo source must be owned by the reopened outbox", pending.outboxLease != null)
            assertArrayEquals(bytes, pending.photoFiles.getValue("local.jpg").open().use { it.readBytes() })
            assertEquals(remote.cursor, metadata.snapshot(scope)?.conflictCursor)

            // Closing releases only the coordinator's outbox claim.  The
            // published sidecar remains durable and independent of the pool.
            requireNotNull(coordinator).closeAndJoin()
            assertTrue("the transient capture must stay released", capture?.isReleased == true)
            pool.close()
            val reopenedPending = requireNotNull(metadata.snapshot(scope)?.pendingUpload)
            assertArrayEquals(
                bytes,
                reopenedPending.photoFiles.getValue("local.jpg").open().use { it.readBytes() }
            )

            val resumed = newCoordinator(drive, metadata, bridge, dispatcher)
            recreated = resumed
            val rebound = requireNotNull(resumed.bind(scope, session.token))
            assertTrue(resumed.enqueueRemoteAcceptance(rebound).await() is SyncOutcome.AppliedRemote)
            advanceUntilIdle()

            val finalRemote = requireNotNull(drive.record(scope))
            assertEquals(local, finalRemote.snapshot)
            assertArrayEquals(
                bytes,
                finalRemote.photoFiles.getValue("local.jpg").open().use { it.readBytes() }
            )
            assertNull(metadata.snapshot(scope)?.pendingUpload)
            assertNull(metadata.snapshot(scope)?.conflictCursor)
        } finally {
            recreated?.closeAndJoin()
            coordinator?.closeAndJoin()
            pool.close()
            metadata.close()
            poolRoot.deleteRecursively()
        }
    }

    @Test
    fun metadataFingerprintProof_allowsLookupAndAcceptanceWithoutInventingAnEnvelope() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val verified = SourceFingerprint.fromBytes("verified remote source".toByteArray())
        for (fingerprint in listOf(null, verified)) {
            val session = session("metadata-fingerprint-proof", fingerprint)
            val scope = SyncScope("account", "root", session.token.documentId)
            val bridge = TestBridge(session, textSnapshot(session, "local"))
            val metadata = InMemorySyncMetadataStore()
            val drive = FakeDriveGateway(idFactory = IdFactory())
            val remote = drive.seed(scope, "plan.pdf", textSnapshot(session, "remote"),
                sourceFingerprint = fingerprint)
            val coordinator = newCoordinator(drive, metadata, bridge, dispatcher)
            try {
                val binding = requireNotNull(coordinator.bind(scope, session.token))
                val lookup = coordinator.enqueueRemoteCheck(binding).await()
                assertTrue("metadata-only lookup: $lookup", lookup is SyncOutcome.RemoteConflict)
                val accepted = coordinator.enqueueRemoteAcceptance(binding).await()
                assertTrue("matching envelope: $accepted", accepted is SyncOutcome.AppliedRemote)
                assertEquals(remote.snapshot, bridge.liveSnapshot)
                assertEquals(remote.cursor, metadata.snapshot(scope)?.acceptedCursor)
            } finally { coordinator.closeAndJoin() }
        }
    }

    @Test
    fun acceptanceRejectsMisboundEnvelopesAfterAValidMetadataLookup() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val verified = SourceFingerprint.fromBytes("verified envelope".toByteArray())
        val foreign = SourceFingerprint.fromBytes("foreign envelope".toByteArray())
        val cases = listOf(Triple(verified, null, false), Triple(verified, foreign, false),
            Triple(null, verified, false), Triple(verified, verified, true))
        for ((expected, actual, wrongReference) in cases) {
            val session = session("envelope-fence", expected)
            val scope = SyncScope("account", "root", session.token.documentId)
            val baseline = textSnapshot(session, "local")
            val bridge = TestBridge(session, baseline)
            val metadata = InMemorySyncMetadataStore()
            val drive = FakeDriveGateway(idFactory = IdFactory())
            drive.seed(scope, "plan.pdf", textSnapshot(session, "remote"), sourceFingerprint = expected)
            var downloaded = false
            val gateway = object : DriveGateway by drive {
                override suspend fun download(scope: SyncScope, reference: RemoteReference,
                    expectedCursor: RemoteCursor?): DownloadResult {
                    val result = drive.download(scope, reference, expectedCursor) as DownloadResult.Downloaded
                    downloaded = true
                    return result.copy(remote = result.remote.copy(sourceFingerprint = actual,
                        reference = if (wrongReference) reference.copy(snapshotFileId = "foreign-file") else reference))
                }
            }
            val coordinator = SyncCoordinator(gateway, metadata, bridge,
                parentScope = CoroutineScope(dispatcher + SupervisorJob()), dispatcher = dispatcher)
            try {
                val binding = requireNotNull(coordinator.bind(scope, session.token))
                val outcome = coordinator.enqueueRemoteAcceptance(binding).await()
                assertTrue("the valid metadata lookup must reach the envelope", downloaded)
                assertTrue("misbound envelope: $outcome", outcome is SyncOutcome.Failed)
                assertEquals(com.example.myapplication.stage4.SyncError.Kind.VALIDATION,
                    (outcome as SyncOutcome.Failed).error.kind)
                assertEquals(baseline, bridge.liveSnapshot)
                assertEquals(baseline, bridge.durableSnapshot)
                assertNull(metadata.snapshot(scope)?.acceptedCursor)
                assertNull(metadata.snapshot(scope)?.remoteReference)
            } finally { coordinator.closeAndJoin() }
        }
    }

    @Test
    fun uploadedEnvelopeMustMatchSourceBeforeAcceptedCursorPublication() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val verified = SourceFingerprint.fromBytes("verified upload".toByteArray())
        val foreign = SourceFingerprint.fromBytes("foreign upload".toByteArray())
        for ((expected, actual) in listOf(verified to null, verified to foreign, null to verified)) {
            val session = session("uploaded-envelope-fence", expected)
            val scope = SyncScope("account", "root", session.token.documentId)
            val baseline = textSnapshot(session, "local")
            val bridge = TestBridge(session, baseline)
            val metadata = InMemorySyncMetadataStore()
            val drive = FakeDriveGateway(idFactory = IdFactory())
            var uploaded = false
            val gateway = object : DriveGateway by drive {
                override suspend fun upload(request: UploadRequest): UploadResult {
                    val result = drive.upload(request) as UploadResult.Uploaded
                    uploaded = true
                    return result.copy(remote = result.remote.copy(sourceFingerprint = actual))
                }
            }
            val coordinator = SyncCoordinator(gateway, metadata, bridge,
                parentScope = CoroutineScope(dispatcher + SupervisorJob()), dispatcher = dispatcher)
            try {
                val binding = requireNotNull(coordinator.bind(scope, session.token))
                val outcome = coordinator.enqueueUpload(binding, SyncReason.IMMEDIATE).await()
                assertTrue("the upload must reach finalization", uploaded)
                assertTrue("wrong upload envelope: $outcome", outcome is SyncOutcome.Failed)
                assertEquals(com.example.myapplication.stage4.SyncError.Kind.VALIDATION,
                    (outcome as SyncOutcome.Failed).error.kind)
                assertEquals(baseline, bridge.liveSnapshot)
                assertNull(metadata.snapshot(scope)?.acceptedCursor)
                assertNull(metadata.snapshot(scope)?.remoteReference)
                assertTrue("uncertain upload must retain its recovery payload",
                    metadata.snapshot(scope)?.pendingUpload != null)
            } finally { coordinator.closeAndJoin() }
        }
    }

    private fun newCoordinator(
        drive: FakeDriveGateway,
        metadata: SyncMetadataStore,
        bridge: TestBridge,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): SyncCoordinator = SyncCoordinator(
        gateway = drive,
        metadataStore = metadata,
        bridge = bridge,
        parentScope = CoroutineScope(dispatcher + SupervisorJob()),
        dispatcher = dispatcher
    )

    private fun session(
        id: String,
        fingerprint: SourceFingerprint? = null,
        generation: Long = 1L,
        documentId: DocumentId = DocumentId.new()
    ): DocumentSession {
        val source = DocumentSourceIdentityV1("content://stage9b/$id", "plan.pdf")
        return DocumentSession(
            target = ResolvedDocumentTarget(DocumentAssociation(documentId, source, null)),
            token = DocumentSessionToken(documentId, source.sourceUri, fingerprint, generation)
        )
    }

    private fun photoPool(rootDirectory: File): ImmutablePhotoAssetPool = ImmutablePhotoAssetPool(
        rootDirectory = rootDirectory,
        imageProbe = DefaultImageProbe,
        maxAssetCount = IMMUTABLE_PHOTO_POOL_MAX_ASSET_COUNT,
        maxTotalBytes = IMMUTABLE_PHOTO_POOL_MAX_TOTAL_BYTES,
        operationsFactory = TestPhotoPathOperationsFactory,
        trustedRootDirectory = null
    )

    private fun outboxContentDirectoryCount(rootDirectory: File): Int {
        val outbox = rootDirectory.toPath().resolve("pending_upload_outbox")
        if (!Files.isDirectory(outbox)) return 0
        Files.walk(outbox).use { paths ->
            return paths.filter { path ->
                Files.isDirectory(path) &&
                    path.parent?.parent == outbox &&
                    path.fileName.toString().matches(Regex("[0-9a-f]{64}"))
            }.count().toInt()
        }
    }

    private fun textSnapshot(session: DocumentSession, marker: String): DocumentSnapshotV1 =
        DocumentSnapshotV1(
            schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
            snapshotRevision = 0L,
            source = session.target.association.source,
            pages = mapOf(
                0 to PageSnapshotV1(
                    notes = listOf(NoteSnapshotV1(0.1f, 0.2f, marker, false, 0f, 0.05f, "note-$marker"))
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

    private class IdFactory : () -> String {
        private var next = 0
        override fun invoke(): String = "id-${next++}"
    }

    private class MisboundFindGateway(
        private val foreign: RemoteDocumentMetadata
    ) : DriveGateway {
        override suspend fun find(scope: SyncScope): RemoteLookup = RemoteLookup.Found(foreign)

        override suspend fun upload(request: UploadRequest): UploadResult = UploadResult.Rejected(
            DriveFailure.Validation("identity-fence test does not upload")
        )

        override suspend fun download(
            scope: SyncScope,
            reference: RemoteReference,
            expectedCursor: RemoteCursor?
        ): DownloadResult = DownloadResult.Failed(
            DriveFailure.Validation("identity-fence test does not download")
        )
    }

    private class FailingMetadataStore : SyncMetadataStore {
        private val delegate = InMemorySyncMetadataStore()
        var failNextPendingSnapshot: DocumentSnapshotV1? = null

        override suspend fun read(scope: SyncScope): MetadataReadResult = delegate.read(scope)

        override suspend fun write(metadata: SyncMetadata): MetadataWriteResult {
            val expected = failNextPendingSnapshot
            if (expected != null && metadata.pendingUpload?.snapshot == expected) {
                failNextPendingSnapshot = null
                return MetadataWriteResult.Failed(
                    SyncMetadataError.Injected("pending upload", "injected supersession failure")
                )
            }
            return delegate.write(metadata)
        }

        fun snapshot(scope: SyncScope): SyncMetadata? = delegate.snapshot(scope)
    }

    private class CancelAfterPendingPublicationStore : SyncMetadataStore {
        private val delegate = InMemorySyncMetadataStore()
        var cancelSnapshot: DocumentSnapshotV1? = null

        override suspend fun read(scope: SyncScope): MetadataReadResult = delegate.read(scope)

        override suspend fun write(metadata: SyncMetadata): MetadataWriteResult {
            val result = delegate.write(metadata)
            val expected = cancelSnapshot
            if (result is MetadataWriteResult.Committed &&
                expected != null && metadata.pendingUpload?.snapshot == expected
            ) {
                cancelSnapshot = null
                throw CancellationException("injected cancellation after pending publication")
            }
            return result
        }

        fun snapshot(scope: SyncScope): SyncMetadata? = delegate.snapshot(scope)
    }

    private class FailingPhotoMetadataStore(
        private val delegate: TestPersistentPhotoMetadataStore
    ) : SyncMetadataStore, AutoCloseable {
        var failSnapshot: DocumentSnapshotV1? = null

        override suspend fun read(scope: SyncScope): MetadataReadResult = delegate.read(scope)

        override suspend fun write(metadata: SyncMetadata): MetadataWriteResult {
            val expected = failSnapshot
            if (expected != null && metadata.pendingUpload?.snapshot == expected) {
                failSnapshot = null
                return MetadataWriteResult.Failed(
                    SyncMetadataError.Injected("pending upload", "injected photo supersession failure")
                )
            }
            return delegate.write(metadata)
        }

        override fun recoveryIdentity(metadata: SyncMetadata): String = delegate.recoveryIdentity(metadata)

        fun snapshot(scope: SyncScope): SyncMetadata? = delegate.snapshot(scope)

        override fun close() = delegate.close()
    }

    private class TestBridge(
        var session: DocumentSession,
        var liveSnapshot: DocumentSnapshotV1,
        var durableSnapshot: DocumentSnapshotV1 = liveSnapshot
    ) : SyncSessionBridge {
        var photoCaptureFactory: (DocumentSnapshotV1) -> PhotoAssetCapture = {
            PhotoAssetCapture.empty()
        }
        var replayPersistenceSnapshot: DocumentSnapshotV1? = null
        var replayPersistenceEntered: CompletableDeferred<Unit>? = null
        var replayPersistenceGate: CompletableDeferred<Unit>? = null

        override fun currentSession(scope: SyncScope): DocumentSession? =
            session.takeIf { it.token.documentId == scope.documentId }

        override suspend fun captureSnapshot(session: DocumentSession): DocumentSnapshotV1 = liveSnapshot

        override suspend fun captureDurableSnapshot(session: DocumentSession): DocumentSnapshotV1 = durableSnapshot

        override suspend fun persistSnapshot(
            session: DocumentSession,
            snapshot: DocumentSnapshotV1
        ): DocumentSaveResult {
            if (replayPersistenceGate != null && snapshot == replayPersistenceSnapshot) {
                replayPersistenceEntered?.complete(Unit)
                replayPersistenceGate!!.await()
            }
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
}
