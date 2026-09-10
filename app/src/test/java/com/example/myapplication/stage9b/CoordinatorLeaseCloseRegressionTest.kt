package com.example.myapplication.stage9b

import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage3.DocumentSession
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.ResolvedDocumentTarget
import com.example.myapplication.stage4.DriveFailure
import com.example.myapplication.stage4.DriveGateway
import com.example.myapplication.stage4.DownloadResult
import com.example.myapplication.stage4.DurablePendingUpload
import com.example.myapplication.stage4.FilePendingUploadOutbox
import com.example.myapplication.stage4.FileSyncMetadataStore
import com.example.myapplication.stage4.MetadataReadResult
import com.example.myapplication.stage4.MetadataWriteResult
import com.example.myapplication.stage4.RemoteCursor
import com.example.myapplication.stage4.RemoteLookup
import com.example.myapplication.stage4.RemoteReference
import com.example.myapplication.stage4.SyncCoordinator
import com.example.myapplication.stage4.SyncMetadata
import com.example.myapplication.stage4.SyncMetadataStore
import com.example.myapplication.stage4.SyncReason
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage4.SyncSessionBridge
import com.example.myapplication.stage4.UploadRequest
import com.example.myapplication.stage4.UploadResult
import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stage 9B ownership regressions for the coordinator's terminal sidecar lease
 * boundary.  These tests use the real current-format metadata/outbox path and
 * only synthetic in-memory photo sources.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatorLeaseCloseRegressionTest {
    @Test
    fun closeReleasesLoadedLease_once_preservesBytes_forRecreateAndScopeControl() = runTest {
        val root = Files.createTempDirectory("stage9b-coordinator-close").toFile()
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = FileSyncMetadataStore(root, Dispatchers.IO, TestPhotoPathOperationsFactory)
            val firstSession = session("lease-close-a")
            val firstScope = SyncScope("account", "root", firstSession.token.documentId)
            val bytes = Stage4PhotoFixture.jpegBytes()
            val assets = testPhotoAssets(mapOf("photo.jpg" to bytes))
            val pending = pending(firstSession, assets)
            assertEquals(
                MetadataWriteResult.Committed,
                store.write(SyncMetadata(scope = firstScope, pendingUpload = pending))
            )
            val firstManifest = requireNotNull(sidecarManifests(root).singleOrNull())
            val manifestBytes = Files.readAllBytes(firstManifest)
            val firstOwnerKey = outboxOwnerKey(
                root,
                firstScope,
                FilePendingUploadOutbox(root, TestPhotoPathOperationsFactory)
                    .referenceFor(firstScope, pending)
                    .contentId
            )

            val firstGateway = BlockingGateway(blockUpload = false)
            val firstCoordinator = coordinator(
                gateway = firstGateway,
                store = store,
                bridge = CoordinatorBridge(firstSession, DocumentSnapshotV1For(firstSession)),
                dispatcher = dispatcher
            )
            val firstBinding = requireNotNull(firstCoordinator.bind(firstScope, firstSession.token))
            firstCoordinator.enqueueRemoteCheck(firstBinding)
            runCurrent()
            firstGateway.entered.await()
            assertTrue(PhotoAssetOwnershipRegistry.isOwnerClaimed(firstOwnerKey))

            val close = firstCoordinator.close()
            runCurrent()
            assertFalse("close must wait for the active gateway owner", close.isCompleted)
            assertTrue(PhotoAssetOwnershipRegistry.isOwnerClaimed(firstOwnerKey))
            assertTrue(Files.exists(firstManifest))
            firstGateway.release.complete(Unit)
            close.join()
            assertFalse(PhotoAssetOwnershipRegistry.isOwnerClaimed(firstOwnerKey))
            assertArrayEquals(manifestBytes, Files.readAllBytes(firstManifest))

            // A fresh coordinator must be able to load the durable sidecar and
            // establish a new claim after the previous owner has terminated.
            val recreatedGateway = BlockingGateway(blockUpload = false)
            val recreated = coordinator(
                gateway = recreatedGateway,
                store = store,
                bridge = CoordinatorBridge(firstSession, DocumentSnapshotV1For(firstSession)),
                dispatcher = dispatcher
            )
            val recreatedBinding = requireNotNull(recreated.bind(firstScope, firstSession.token))
            val reopened = recreated.enqueueRemoteCheck(recreatedBinding)
            runCurrent()
            recreatedGateway.entered.await()
            assertTrue(PhotoAssetOwnershipRegistry.isOwnerClaimed(firstOwnerKey))
            val recreatedClose = recreated.close()
            runCurrent()
            assertFalse(recreatedClose.isCompleted)
            recreatedGateway.release.complete(Unit)
            recreatedClose.join()
            assertTrue(reopened.isCancelled || reopened.isCompleted)
            assertFalse(PhotoAssetOwnershipRegistry.isOwnerClaimed(firstOwnerKey))
            assertArrayEquals(manifestBytes, Files.readAllBytes(firstManifest))

            // Reconciliation is scope-local.  Clearing A must not sweep a
            // separately owned B sidecar as an unrelated cleanup shortcut.
            val secondSession = session("lease-close-b")
            val secondScope = SyncScope("account", "root", secondSession.token.documentId)
            assertEquals(
                MetadataWriteResult.Committed,
                store.write(
                    SyncMetadata(
                        scope = secondScope,
                        pendingUpload = pending(secondSession, assets)
                    )
                )
            )
            val secondManifest = sidecarManifests(root).single { it != firstManifest }
            val secondManifestBytes = Files.readAllBytes(secondManifest)
            assertEquals(
                MetadataWriteResult.Committed,
                store.write(SyncMetadata(scope = firstScope))
            )
            assertTrue("scope B sidecar must remain authoritative", Files.exists(secondManifest))
            assertEquals(
                MetadataWriteResult.Committed,
                store.write(SyncMetadata(scope = secondScope))
            )
            assertSafeCollectionOutcome(root.toPath(), firstManifest, manifestBytes)
            assertSafeCollectionOutcome(root.toPath(), secondManifest, secondManifestBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun closeWaitsForCanceledUploadReader_thenReopensDurablePendingBytes() = runTest {
        val root = Files.createTempDirectory("stage9b-coordinator-reader").toFile()
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = FileSyncMetadataStore(root, Dispatchers.IO, TestPhotoPathOperationsFactory)
            val session = session("lease-reader")
            val scope = SyncScope("account", "root", session.token.documentId)
            val snapshot = DocumentSnapshotV1For(session)
            val bytes = Stage4PhotoFixture.jpegBytes()
            val assets = testPhotoAssets(mapOf("photo.jpg" to bytes))
            val expectedPending = pending(session, assets)
            val ownerKey = outboxOwnerKey(
                root,
                scope,
                FilePendingUploadOutbox(root, TestPhotoPathOperationsFactory)
                    .referenceFor(scope, expectedPending)
                    .contentId
            )
            val gateway = BlockingGateway(blockUpload = true)
            val coordinator = coordinator(
                gateway = gateway,
                store = store,
                bridge = CoordinatorBridge(session, snapshot, assets),
                dispatcher = dispatcher
            )
            val binding = requireNotNull(coordinator.bind(scope, session.token))
            val upload = coordinator.enqueueUpload(binding, SyncReason.MANUAL)
            runCurrent()
            gateway.entered.await()
            val manifest = requireNotNull(sidecarManifests(root).singleOrNull())
            val manifestBytes = Files.readAllBytes(manifest)
            assertTrue(PhotoAssetOwnershipRegistry.isOwnerClaimed(ownerKey))

            val close = coordinator.close()
            runCurrent()
            assertFalse("terminal release cannot race the active reader", close.isCompleted)
            assertTrue(PhotoAssetOwnershipRegistry.isOwnerClaimed(ownerKey))
            assertFalse(gateway.readerClosed.get())

            gateway.release.complete(Unit)
            close.join()
            assertTrue(gateway.readerClosed.get())
            assertFalse(PhotoAssetOwnershipRegistry.isOwnerClaimed(ownerKey))
            assertTrue(upload.isCancelled || upload.isCompleted)
            assertArrayEquals(manifestBytes, Files.readAllBytes(manifest))

            // The canceled worker left a complete durable pending record. A
            // new owner can reopen and stream its bytes before any cleanup.
            val loaded = store.read(scope) as MetadataReadResult.Loaded
            val loadedPending = requireNotNull(loaded.metadata?.pendingUpload)
            assertArrayEquals(
                bytes,
                loadedPending.photoFiles.getValue("photo.jpg").open().use { it.readBytes() }
            )
            loadedPending.outboxLease?.close()
            assertEquals(MetadataWriteResult.Committed, store.write(SyncMetadata(scope = scope)))
            assertSafeCollectionOutcome(root.toPath(), manifest, manifestBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    /** Unsupported providers retain orphan evidence rather than use unsafe deletion. */
    private fun assertSafeCollectionOutcome(root: Path, manifest: Path, expected: ByteArray) {
        val secureDeletionAvailable = Files.newDirectoryStream(root).use {
            it is java.nio.file.SecureDirectoryStream<*>
        }
        if (secureDeletionAvailable) {
            assertFalse("unclaimed sidecar must be collected on a secure provider", Files.exists(manifest))
        } else {
            assertTrue("unsupported cleanup must retain the complete sidecar", Files.exists(manifest))
            assertArrayEquals(expected, Files.readAllBytes(manifest))
        }
    }

    private fun coordinator(
        gateway: DriveGateway,
        store: SyncMetadataStore,
        bridge: SyncSessionBridge,
        dispatcher: kotlinx.coroutines.test.TestDispatcher
    ): SyncCoordinator = SyncCoordinator(
        gateway = gateway,
        metadataStore = store,
        bridge = bridge,
        parentScope = CoroutineScope(dispatcher + SupervisorJob()),
        dispatcher = dispatcher
    )

    private fun pending(session: DocumentSession, assets: PhotoAssetSet): DurablePendingUpload =
        DurablePendingUpload(
            reason = SyncReason.MANUAL,
            sourceUri = session.token.sourceUri,
            sourceFingerprint = null,
            generation = 1L,
            expectedCursor = null,
            snapshot = DocumentSnapshotV1For(session),
            photoFiles = assets
        )

    private fun session(label: String): DocumentSession {
        val documentId = DocumentId.new()
        val source = DocumentSourceIdentityV1("content://stage9b/$label", "plan.pdf")
        val association = DocumentAssociation(documentId, source, null)
        return DocumentSession(
            target = ResolvedDocumentTarget(association),
            token = DocumentSessionToken(documentId, source.sourceUri, null, 1L)
        )
    }

    private fun sidecarManifests(root: java.io.File): List<Path> =
        Files.walk(root.toPath()).use { paths ->
            buildList {
                paths.filter { it.fileName?.toString() == "manifest.json" }.forEach { add(it) }
            }
        }

    private fun outboxOwnerKey(
        root: java.io.File,
        scope: SyncScope,
        contentId: String
    ): String {
        val scopeKey = "${scope.accountId}\u0000${scope.backupRootId}\u0000${scope.documentId.value}"
        val scopeHash = com.example.myapplication.stage5.sha256Hex(
            scopeKey.toByteArray(StandardCharsets.UTF_8)
        )
        return "pending-upload-outbox:${root.toPath().toAbsolutePath().normalize()}:$scopeHash:$contentId"
    }

    private class CoordinatorBridge(
        private val session: DocumentSession,
        initialSnapshot: DocumentSnapshotV1,
        private val assets: PhotoAssetSet = PhotoAssetSet.EMPTY
    ) : SyncSessionBridge {
        private var liveSnapshot = initialSnapshot

        override fun currentSession(scope: SyncScope): DocumentSession? =
            session.takeIf { it.token.documentId == scope.documentId }

        override suspend fun captureSnapshot(session: DocumentSession): DocumentSnapshotV1 = liveSnapshot

        override suspend fun captureDurableSnapshot(session: DocumentSession): DocumentSnapshotV1 = liveSnapshot

        override suspend fun persistSnapshot(
            session: DocumentSession,
            snapshot: DocumentSnapshotV1
        ): DocumentSaveResult {
            liveSnapshot = snapshot
            return DocumentSaveResult.Saved(session.token.documentId)
        }

        override fun isCurrent(token: DocumentSessionToken): Boolean = token == session.token

        override fun hasRequiredPhotoContent(snapshot: DocumentSnapshotV1): Boolean = assets.isNotEmpty()

        override suspend fun capturePhotoContent(snapshot: DocumentSnapshotV1): PhotoAssetCapture =
            PhotoAssetCapture.of(assets)

        override suspend fun capturePhotoContentForAdmission(
            session: DocumentSession,
            currentDurableSnapshot: DocumentSnapshotV1,
            currentLiveSnapshot: DocumentSnapshotV1
        ): PhotoAssetCapture = PhotoAssetCapture.of(assets)

        override suspend fun hasRequiredPhotoContentForAdmission(
            session: DocumentSession,
            currentDurableSnapshot: DocumentSnapshotV1,
            currentLiveSnapshot: DocumentSnapshotV1
        ): Boolean = assets.isNotEmpty()

        override fun applySnapshotReplace(session: DocumentSession, snapshot: DocumentSnapshotV1) {
            liveSnapshot = snapshot
        }
    }

    private class BlockingGateway(private val blockUpload: Boolean) : DriveGateway {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val readerClosed = AtomicBoolean(false)

        override suspend fun find(scope: SyncScope): RemoteLookup {
            if (blockUpload) error("find is not used by the upload reader case")
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            return RemoteLookup.NotFound
        }

        override suspend fun upload(request: UploadRequest): UploadResult {
            if (blockUpload) {
                val asset = request.photoFiles.values.single()
                asset.open().use { input ->
                    check(input.read() >= 0) { "test photo source was empty" }
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
                readerClosed.set(true)
            }
            return UploadResult.Rejected(DriveFailure.StaleGeneration(request.generation))
        }

        override suspend fun download(
            scope: SyncScope,
            reference: RemoteReference,
            expectedCursor: RemoteCursor?
        ): DownloadResult = DownloadResult.NotFound
    }
}

private fun DocumentSnapshotV1For(session: DocumentSession): DocumentSnapshotV1 = DocumentSnapshotV1(
    schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
    snapshotRevision = 0L,
    source = DocumentSourceIdentityV1(session.token.sourceUri, "plan.pdf"),
    pages = mapOf(
        0 to PageSnapshotV1(
            photoPins = listOf(
                PhotoPinSnapshotV1(
                    x = .5f,
                    y = .5f,
                    id = "pin-1",
                    imageFileNames = listOf("photo.jpg"),
                    imageNotes = emptyMap(),
                    imageShapes = emptyMap()
                )
            )
        )
    )
)
