package com.example.myapplication.stage9b

import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.DurablePendingUpload
import com.example.myapplication.stage4.FilePendingUploadOutbox
import com.example.myapplication.stage4.FileSyncMetadataStore
import com.example.myapplication.stage4.MetadataReadResult
import com.example.myapplication.stage4.MetadataWriteResult
import com.example.myapplication.stage4.SyncMetadata
import com.example.myapplication.stage4.SyncReason
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import com.example.myapplication.stage5.validatePhotoBytes
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focused Stage 9B proof for file-backed pending-upload state. */
class StreamingOutboxTest {
    @Test
    fun outboxRoundTripReopensPhotoAssetWithoutAccumulatingAByteMap() {
        val root = Files.createTempDirectory("stage9b-streaming-outbox").toFile()
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val bytes = Stage4PhotoFixture.jpegBytes()
            val descriptor = validatePhotoBytes(bytes).descriptor
            val snapshot = photoSnapshot(scope.documentId)
            val assets = PhotoAssetSet.of(
                mapOf(
                    "photo.jpg" to object : PhotoAsset {
                        override val descriptor = descriptor
                        override fun open() = ByteArrayInputStream(bytes)
                    }
                )
            )
            val pending = DurablePendingUpload(
                reason = SyncReason.MANUAL,
                sourceUri = snapshot.source.sourceUri,
                sourceFingerprint = null,
                generation = 1L,
                expectedCursor = null,
                snapshot = snapshot,
                photoFiles = assets
            )
            val outbox = FilePendingUploadOutbox(root, TestPhotoPathOperationsFactory)
            val reference = outbox.publish(scope, pending)
            assertEquals(3, reference.schemaVersion)

            val loaded = outbox.load(
                scope = scope,
                reference = reference,
                reason = pending.reason,
                sourceUri = pending.sourceUri,
                sourceFingerprint = pending.sourceFingerprint,
                generation = pending.generation,
                expectedCursor = pending.expectedCursor
            )
            assertEquals(setOf("photo.jpg"), loaded.photoFiles.keys)
            val reopened = loaded.photoFiles.getValue("photo.jpg").open().use { it.readBytes() }
            assertArrayEquals(bytes, reopened)

            val manifest = Files.walk(root.toPath()).use { paths ->
                paths.filter { it.fileName.toString() == "manifest.json" }
                    .findFirst()
                    .orElseThrow()
            }
            val manifestText = String(Files.readAllBytes(manifest), Charsets.UTF_8)
            assertTrue(manifestText.contains("\"schemaVersion\":3"))
            assertFalse(manifestText.contains("base64"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun metadataUsesSchema2SidecarWireAndPreservesRetiredInput() = runBlocking {
        val root = Files.createTempDirectory("stage9b-metadata-wire").toFile()
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val store = FileSyncMetadataStore(root, Dispatchers.IO, TestPhotoPathOperationsFactory)
            assertEquals(MetadataWriteResult.Committed, store.write(SyncMetadata(scope = scope)))
            val metadataPath = store.metadataFileFor(scope).toPath()
            val current = String(Files.readAllBytes(metadataPath), Charsets.UTF_8)
            assertTrue(current.contains("\"schemaVersion\":2"))
            assertFalse(current.contains("pendingUploadSnapshotJson"))
            assertFalse(current.contains("pendingUploadPhotoFiles"))

            val retired = "{\"schemaVersion\":1,\"accountId\":\"account\",\"backupRootId\":\"root\",\"documentId\":\"${scope.documentId.value}\",\"pendingUploadSnapshotJson\":\"{}\"}"
            Files.write(metadataPath, retired.toByteArray(Charsets.UTF_8))
            assertTrue(store.read(scope) is MetadataReadResult.Failed)
            assertTrue(
                store.write(SyncMetadata(scope = scope, acceptedCursor = com.example.myapplication.stage4.RemoteCursor("new")))
                    is com.example.myapplication.stage4.MetadataWriteResult.Failed
            )
            assertEquals(retired, String(Files.readAllBytes(metadataPath), Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun photoSnapshot(documentId: DocumentId): DocumentSnapshotV1 = DocumentSnapshotV1(
        schemaVersion = com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
        snapshotRevision = 0L,
        source = DocumentSourceIdentityV1("content://stage9b/${documentId.value}", "plan.pdf"),
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
}
