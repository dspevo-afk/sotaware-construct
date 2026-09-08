package com.example.myapplication.stage9a

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.stage1.*
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.*
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.sha256Hex
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native filesystem/decoder evidence, independent from the Windows directory-fsync limitation. */
@RunWith(AndroidJUnit4::class)
class PendingOutboxInstrumentedTest {
    @Test fun nativeStoreReopensCanonicalSnapshotLargerThanSmallMetadataLimit() = runBlocking {
        val directory = newDirectory()
        try {
            val source = DocumentSourceIdentityV1("content://stage9a/native/snapshot", "fixture.pdf")
            val body = "n".repeat(Stage5Limits.MAX_TEXT_CHARS)
            val snapshot = DocumentSnapshotV1(1, 0L, source, mapOf(0 to PageSnapshotV1(
                notes = List(280) { NoteSnapshotV1(.25f, .5f, body, 16f, false, 0f) }
            )))
            val scope = SyncScope("synthetic-account", "synthetic-root", DocumentId.new())
            val pending = DurablePendingUpload(SyncReason.MANUAL, source.sourceUri, null,
                1L, null, snapshot, emptyMap())
            val store = nativeMetadataStore(directory)
            val metadata = SyncMetadata(scope = scope, pendingUpload = pending)
            assertEquals(MetadataWriteResult.Committed, store.write(metadata))
            assertTrue(store.metadataFileFor(scope).length() < 64 * 1024)
            val reopened = nativeMetadataStore(directory).read(scope)
            assertTrue(reopened is MetadataReadResult.Loaded)
            assertEquals(snapshot, (reopened as MetadataReadResult.Loaded).metadata?.pendingUpload?.snapshot)
        } finally { directory.deleteRecursively() }
    }

    @Test fun nativeStorePreservesLargePhotoBytesAcrossRecreation() = runBlocking {
        val directory = newDirectory()
        try {
            val side = 1600
            val pixels = IntArray(side * side)
            val random = java.util.Random(675546L)
            for (index in pixels.indices) pixels[index] = 0xff000000.toInt() or random.nextInt(1 shl 24)
            val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
            val bytes = try {
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
            } finally { bitmap.recycle() }
            assertTrue(bytes.size > 6 * 1024 * 1024)
            assertTrue(bytes.size <= Stage5Limits.MAX_PHOTO_BYTES)
            val name = "native-fixture.png"
            val source = DocumentSourceIdentityV1("content://stage9a/native/photo", "fixture.pdf")
            val snapshot = DocumentSnapshotV1(1, 0L, source, mapOf(0 to PageSnapshotV1(
                photoPins = listOf(PhotoPinSnapshotV1(.25f,.5f,UUID.randomUUID().toString(),
                    listOf(name), emptyMap(), emptyMap()))
            )))
            val scope = SyncScope("synthetic-account", "synthetic-root", DocumentId.new())
            val pending = DurablePendingUpload(SyncReason.PHOTO, source.sourceUri, null,
                1L, null, snapshot, mapOf(name to bytes))
            val store = nativeMetadataStore(directory)
            assertEquals(MetadataWriteResult.Committed, store.write(SyncMetadata(scope=scope,pendingUpload=pending)))
            assertTrue(store.metadataFileFor(scope).length() < 64 * 1024)
            val reopened = nativeMetadataStore(directory).read(scope) as MetadataReadResult.Loaded
            val recovered = requireNotNull(reopened.metadata?.pendingUpload)
            assertEquals(snapshot, recovered.snapshot)
            assertArrayEquals(bytes, recovered.photoFiles[name])
            assertEquals(sha256Hex(bytes), sha256Hex(requireNotNull(recovered.photoFiles[name])))
        } finally { directory.deleteRecursively() }
    }

    @Test fun contextMetadataStoreUsesFilesDirAsTrustedAndroidBoundary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = SyncScope("context-account", "context-root", DocumentId.new())
        val store = FileSyncMetadataStore(context)
        val metadata = SyncMetadata(scope = scope, acceptedCursor = RemoteCursor("context-r1", 1L))
        try {
            assertEquals(MetadataWriteResult.Committed, store.write(metadata))
            assertEquals(MetadataReadResult.Loaded(metadata), FileSyncMetadataStore(context).read(scope))
        } finally {
            store.metadataFileFor(scope).delete()
        }
    }

    @Test fun nativeMetadataStoreReplacesExistingAuthorityAtomically() = runBlocking {
        val directory = newDirectory()
        try {
            val scope = SyncScope("synthetic-account", "synthetic-root", DocumentId.new())
            val store = nativeMetadataStore(directory)
            val first = SyncMetadata(scope = scope, acceptedCursor = RemoteCursor("r1", 1L))
            val second = SyncMetadata(scope = scope, acceptedCursor = RemoteCursor("r2", 2L))
            assertEquals(MetadataWriteResult.Committed, store.write(first))
            assertEquals(MetadataWriteResult.Committed, store.write(second))
            val reopened = nativeMetadataStore(directory).read(scope)
            assertEquals(MetadataReadResult.Loaded(second), reopened)
        } finally { directory.deleteRecursively() }
    }

    private fun nativeMetadataStore(directory: File): FileSyncMetadataStore =
        FileSyncMetadataStore(
            directory,
            kotlinx.coroutines.Dispatchers.IO,
            null,
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        )

    private fun newDirectory(): File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
        "stage9a-native-outbox-${UUID.randomUUID()}"
    ).apply { check(mkdirs()) }
}
