package com.example.myapplication.stage9a

import com.example.myapplication.stage5.testFileSyncMetadataStore

import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.NoteSnapshotV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.DurablePendingUpload
import com.example.myapplication.stage4.FileSyncMetadataStore
import com.example.myapplication.stage4.MetadataReadResult
import com.example.myapplication.stage4.MetadataWriteResult
import com.example.myapplication.stage4.SyncMetadata
import com.example.myapplication.stage4.SyncReason
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.encodeBoundedJson
import com.example.myapplication.stage5.validateSnapshot
import com.example.myapplication.stage9b.PhotoAssetSet
import com.google.gson.GsonBuilder
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The outbox must protect bulky canonical state, not just Base64 photo bytes. */
class PendingSnapshotBoundaryTest {
    @Test
    fun validSnapshotLargerThanMetadataLimitSurvivesPendingUploadAndRestartWithoutPhotos() =
        verifyRoundTrip(noteCount = 300, minimumBytes = Stage5Limits.MAX_METADATA_BYTES)

    @Test
    fun snapshotMayExceedTheSinglePhotoLimitWhileRemainingInsideItsOwnJsonLimit() =
        verifyRoundTrip(noteCount = 900, minimumBytes = Stage5Limits.MAX_PHOTO_BYTES)

    private fun verifyRoundTrip(noteCount: Int, minimumBytes: Int) = runTest {
        val directory = Files.createTempDirectory("stage9a-pending-snapshot").toFile()
        try {
            val source = DocumentSourceIdentityV1("content://stage9a/large-canonical", "fixture.pdf")
            val text = "n".repeat(Stage5Limits.MAX_TEXT_CHARS)
            val snapshot = DocumentSnapshotV1(2, 0L, source, mapOf(0 to PageSnapshotV1(
                notes = List(noteCount) { index -> NoteSnapshotV1(.25f, .5f, text, false, 0f, 0.05f, "pending-note-$index") }
            )))
            validateSnapshot(snapshot)
            val encodedSize = encodeBoundedJson(GsonBuilder().disableHtmlEscaping().create(),
                snapshot, Stage5Limits.MAX_JSON_BYTES, "large canonical fixture").size
            assertTrue(encodedSize > minimumBytes)
            assertTrue(encodedSize < Stage5Limits.MAX_JSON_BYTES)
            val pending = DurablePendingUpload(SyncReason.MANUAL, source.sourceUri, null,
                1L, null, snapshot, PhotoAssetSet.EMPTY)
            val metadata = SyncMetadata(scope = SyncScope("account-a", "root-a", DocumentId.new()),
                pendingUpload = pending)
            val store = testFileSyncMetadataStore(directory)
            assertEquals(MetadataWriteResult.Committed, store.write(metadata))
            assertTrue("small metadata must reference rather than embed the bulky snapshot",
                store.metadataFileFor(metadata.scope).length() < 64 * 1024)
            val recreated = testFileSyncMetadataStore(directory)
            val result = recreated.read(metadata.scope)
            assertTrue(result is MetadataReadResult.Loaded)
            val restored = requireNotNull((result as MetadataReadResult.Loaded).metadata)
            assertEquals(snapshot, restored.pendingUpload?.snapshot)
            assertEquals(PhotoAssetSet.EMPTY, restored.pendingUpload?.photoFiles)
            assertEquals(store.recoveryIdentity(metadata), recreated.recoveryIdentity(restored))
        } finally {
            directory.deleteRecursively()
        }
    }
}
