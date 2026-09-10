package com.example.myapplication.stage9b

import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentId
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
import com.example.myapplication.stage4.RemoteDocumentMetadata
import com.example.myapplication.stage4.RemoteLookup
import com.example.myapplication.stage4.RemoteReference
import com.example.myapplication.stage4.SyncCoordinator
import com.example.myapplication.stage4.SyncMetadata
import com.example.myapplication.stage4.SyncOutcome
import com.example.myapplication.stage4.SyncReason
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage4.SyncSessionBridge
import com.example.myapplication.stage4.SYNC_DOCUMENT_ID_APP_PROPERTY
import com.example.myapplication.stage4.UploadRequest
import com.example.myapplication.stage4.UploadResult
import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Replays a file-backed pending upload through an actual coordinator conflict.
 * The replay lease must remain reachable through the conflict metadata write
 * until coordinator shutdown, while the sidecar remains recoverable bytes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayConflictLeaseRegressionTest {
    @Test
    fun replayConflictClose_releasesExactLease_andKeepsPendingBytesRecoverable() = runTest {
        val root = Files.createTempDirectory("stage9b-replay-conflict").toFile()
        var coordinator: SyncCoordinator? = null
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = FileSyncMetadataStore(root, Dispatchers.IO, TestPhotoPathOperationsFactory)
            val session = session()
            val scope = SyncScope("account", "root", session.token.documentId)
            val snapshot = snapshot(session)
            val bytes = Stage4PhotoFixture.jpegBytes()
            val assets = testPhotoAssets(mapOf("photo.jpg" to bytes))
            val pending = DurablePendingUpload(
                reason = SyncReason.MANUAL,
                sourceUri = session.token.sourceUri,
                sourceFingerprint = null,
                generation = 1L,
                expectedCursor = null,
                snapshot = snapshot,
                photoFiles = assets
            )
            assertEquals(
                MetadataWriteResult.Committed,
                store.write(SyncMetadata(scope = scope, pendingUpload = pending))
            )

            val outbox = FilePendingUploadOutbox(root, TestPhotoPathOperationsFactory)
            val contentId = outbox.referenceFor(scope, pending).contentId
            val ownerKey = outboxOwnerKey(root, scope, contentId)
            assertEquals("seed write must not leave a process-local claim", 0, PhotoAssetOwnershipRegistry.activeClaimCount(ownerKey))

            val remote = RemoteDocumentMetadata(
                scope = scope,
                displayName = "plan.pdf",
                reference = RemoteReference(
                    folderId = "folder",
                    snapshotFileId = "file",
                    appProperties = mapOf(SYNC_DOCUMENT_ID_APP_PROPERTY to scope.documentId.value)
                ),
                cursor = RemoteCursor("remote-1")
            )
            coordinator = SyncCoordinator(
                gateway = ConflictGateway(remote),
                metadataStore = store,
                bridge = ReplayBridge(session, snapshot, assets),
                parentScope = CoroutineScope(dispatcher + SupervisorJob()),
                dispatcher = dispatcher
            )
            val binding = requireNotNull(coordinator!!.bind(scope, session.token))

            val outcome = coordinator!!.enqueueUpload(binding, SyncReason.MANUAL).await()
            assertTrue("the replay must reach the gateway conflict branch", outcome is SyncOutcome.RemoteConflict)
            assertEquals("the conflict record must retain the loaded outbox lease", 1, PhotoAssetOwnershipRegistry.activeClaimCount(ownerKey))

            coordinator!!.closeAndJoin()
            assertEquals("terminal release must close that exact lease once", 0, PhotoAssetOwnershipRegistry.activeClaimCount(ownerKey))

            // The conflict is unacknowledged local work.  Closing the
            // coordinator releases ownership only; it must not delete the
            // durable sidecar that a later coordinator needs to replay.
            val loaded = store.read(scope) as MetadataReadResult.Loaded
            val loadedPending = requireNotNull(loaded.metadata?.pendingUpload)
            try {
                assertArrayEquals(
                    "pending sidecar bytes must remain recoverable after close",
                    bytes,
                    loadedPending.photoFiles.getValue("photo.jpg").open().use { it.readBytes() }
                )
            } finally {
                loadedPending.outboxLease?.close()
            }
            assertEquals(0, PhotoAssetOwnershipRegistry.activeClaimCount(ownerKey))
        } finally {
            coordinator?.closeAndJoin()
            root.deleteRecursively()
        }
    }

    private fun outboxOwnerKey(root: java.io.File, scope: SyncScope, contentId: String): String {
        val scopeKey = "${scope.accountId}\u0000${scope.backupRootId}\u0000${scope.documentId.value}"
        val scopeHash = com.example.myapplication.stage5.sha256Hex(
            scopeKey.toByteArray(StandardCharsets.UTF_8)
        )
        return "pending-upload-outbox:${root.toPath().toAbsolutePath().normalize()}:$scopeHash:$contentId"
    }

    private fun session(): DocumentSession {
        val documentId = DocumentId.new()
        val source = DocumentSourceIdentityV1("content://stage9b/replay-conflict", "plan.pdf")
        return DocumentSession(
            target = ResolvedDocumentTarget(DocumentAssociation(documentId, source, null)),
            token = DocumentSessionToken(documentId, source.sourceUri, null, 1L)
        )
    }

    private fun snapshot(session: DocumentSession): DocumentSnapshotV1 = DocumentSnapshotV1(
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

    private class ReplayBridge(
        private val session: DocumentSession,
        private var liveSnapshot: DocumentSnapshotV1,
        private val assets: PhotoAssetSet
    ) : SyncSessionBridge {
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

    private class ConflictGateway(
        private val remote: RemoteDocumentMetadata
    ) : DriveGateway {
        override suspend fun find(scope: SyncScope): RemoteLookup = RemoteLookup.NotFound

        override suspend fun upload(request: UploadRequest): UploadResult {
            val mutationSession = request.mutationLease.begin(
                request.generation,
                request.isGenerationCurrent
            ) ?: return UploadResult.Rejected(DriveFailure.StaleGeneration(request.generation))
            return mutationSession.mutate {
                UploadResult.Conflict(remote, mutationSession)
            }
        }

        override suspend fun download(
            scope: SyncScope,
            reference: RemoteReference,
            expectedCursor: RemoteCursor?
        ): DownloadResult = DownloadResult.NotFound
    }
}
