package com.example.myapplication.stage4

import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMetadataStoreTest {
    @Test
    fun fileStore_roundTripsScopedCursorAndStableIds_acrossStoreRecreation() = runTest {
        val root = Files.createTempDirectory("stage4-sync-metadata").toFile()
        try {
            val scope = SyncScope("account@example.com", "root-123", DocumentId.new())
            val metadata = SyncMetadata(
                scope = scope,
                remoteReference = RemoteReference(
                    folderId = "folder-1",
                    snapshotFileId = "file-1",
                    appProperties = mapOf(SYNC_DOCUMENT_ID_APP_PROPERTY to scope.documentId.value)
                ),
                acceptedCursor = RemoteCursor("remote-r5", 500L),
                conflictCursor = RemoteCursor("remote-r7", 700L),
                conflictDetail = "remote changed"
            )
            assertEquals(MetadataWriteResult.Committed, FileSyncMetadataStore(root).write(metadata))

            val reread = FileSyncMetadataStore(root).read(scope)
            assertEquals(MetadataReadResult.Loaded(metadata), reread)
            assertTrue(FileSyncMetadataStore(root).metadataFileFor(scope).isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedMetadataCommit_doesNotAdvanceAcceptedState() = runTest {
        val scope = SyncScope("account", "root", DocumentId.new())
        var failWrites = false
        val store = InMemorySyncMetadataStore {
            if (failWrites) SyncMetadataError.Injected("write", "injected failure") else null
        }
        val initial = SyncMetadata(scope = scope, acceptedCursor = RemoteCursor("remote-r5"))
        assertEquals(MetadataWriteResult.Committed, store.write(initial))
        failWrites = true
        val newer = initial.copy(acceptedCursor = RemoteCursor("remote-r6"))

        assertTrue(store.write(newer) is MetadataWriteResult.Failed)
        assertEquals(initial, store.snapshot(scope))
    }

    @Test
    fun fileStore_roundTripsPendingAdoptionAndOriginalRemoteDocumentId() = runTest {
        val root = Files.createTempDirectory("stage4-adoption-metadata").toFile()
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val remoteDocumentId = DocumentId.new()
            val fingerprint = com.example.myapplication.stage2.SourceFingerprint.fromBytes("source".toByteArray())
            val candidate = RemoteAdoptionCandidate(
                accountId = scope.accountId,
                backupRootId = scope.backupRootId,
                remoteDocumentId = remoteDocumentId,
                sourceFingerprint = fingerprint,
                displayName = "plan.pdf",
                reference = RemoteReference(
                    folderId = "folder-adoption",
                    snapshotFileId = "file-adoption",
                    appProperties = mapOf(
                        SYNC_DOCUMENT_ID_APP_PROPERTY to remoteDocumentId.value,
                        SYNC_SOURCE_FINGERPRINT_APP_PROPERTY to fingerprint.toDriveProperty()
                    )
                ),
                cursor = RemoteCursor("remote-r9", 900L)
            )
            val metadata = SyncMetadata(
                scope = scope,
                adoptedRemoteDocumentId = remoteDocumentId,
                pendingAdoption = candidate
            )
            val store = FileSyncMetadataStore(root)
            assertEquals(MetadataWriteResult.Committed, store.write(metadata))
            assertEquals(MetadataReadResult.Loaded(metadata), store.read(scope))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fileStore_roundTripsCompletePendingUploadSidecar_acrossRecreation() = runTest {
        val root = Files.createTempDirectory("stage4-pending-upload-metadata").toFile()
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val source = DocumentSourceIdentityV1("content://device/source", "plan.pdf")
            val snapshot = DocumentSnapshotV1(1, 7, source, emptyMap())
            val pending = DurablePendingUpload(
                reason = SyncReason.MANUAL,
                sourceUri = source.sourceUri,
                sourceFingerprint = null,
                generation = 4L,
                expectedCursor = RemoteCursor("remote-r3"),
                snapshot = snapshot,
                photoFiles = emptyMap()
            )
            val metadata = SyncMetadata(scope = scope, pendingUpload = pending)
            val store = FileSyncMetadataStore(root)
            assertEquals(MetadataWriteResult.Committed, store.write(metadata))
            assertEquals(MetadataReadResult.Loaded(metadata), FileSyncMetadataStore(root).read(scope))
        } finally {
            root.deleteRecursively()
        }
    }
}
