package com.example.myapplication.stage9a

import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.stage1.*
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.*
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage9b.NativeFixtureAssets
import com.example.myapplication.stage9b.PhotoAssetSet
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
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
            val snapshot = DocumentSnapshotV1(
                schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
                snapshotRevision = 0L,
                source = source,
                pages = mapOf(
                    0 to PageSnapshotV1(
                        notes = List(280) { index ->
                            NoteSnapshotV1(.25f, .5f, body, false, 0f, .02f, "note-$index")
                        }
                    )
                )
            )
            val scope = SyncScope("synthetic-account", "synthetic-root", DocumentId.new())
            val pending = DurablePendingUpload(SyncReason.MANUAL, source.sourceUri, null,
                1L, null, snapshot, PhotoAssetSet.EMPTY)
            val store = nativeMetadataStore(directory)
            val metadata = SyncMetadata(scope = scope, pendingUpload = pending)
            assertEquals(MetadataWriteResult.Committed, store.write(metadata))
            assertTrue(store.metadataFileFor(scope).length() < 64 * 1024)
            val reopened = nativeMetadataStore(directory).read(scope)
            assertTrue(reopened is MetadataReadResult.Loaded)
            val recovered = (reopened as MetadataReadResult.Loaded).metadata?.pendingUpload
            try { assertEquals(snapshot, recovered?.snapshot) }
            finally { recovered?.outboxLease?.close() }
        } finally { directory.deleteRecursively() }
    }

    @Test fun nativeStorePreservesFourFileBackedLargePhotosAcrossRecreation() = runBlocking {
        val directory = newDirectory()
        // Fixture bytes are packaged in the instrumentation APK, not the
        // target application's assets.  The helper copies them into its own
        // app-private, reopenable files before the durable store is exercised.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixtureContext = object : ContextWrapper(instrumentation.targetContext) {
            override fun getAssets(): AssetManager = instrumentation.context.assets
        }
        val references = (0 until 4).associate { index ->
            "native-photo-$index.jpg" to "stage7/photos/small_valid_photo.jpg"
        }
        val owned = NativeFixtureAssets.fileBackedLargePhotoAssets(fixtureContext, references)
        var loadedPending: DurablePendingUpload? = null
        try {
            val source = DocumentSourceIdentityV1("content://stage9a/native/photo", "fixture.pdf")
            val snapshot = DocumentSnapshotV1(
                DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
                0L,
                source,
                mapOf(
                    0 to PageSnapshotV1(
                        photoPins = listOf(
                            PhotoPinSnapshotV1(
                                .25f,
                                .5f,
                                "photo-pin-1",
                                references.keys.toList(),
                                emptyMap(),
                                emptyMap()
                            )
                        )
                    )
                )
            )
            val scope = SyncScope("synthetic-account", "synthetic-root", DocumentId.new())
            val pending = DurablePendingUpload(SyncReason.PHOTO, source.sourceUri, null,
                1L, null, snapshot, owned.assets)
            val store = nativeMetadataStore(directory)
            assertEquals(MetadataWriteResult.Committed, store.write(SyncMetadata(scope=scope,pendingUpload=pending)))
            assertTrue(store.metadataFileFor(scope).length() < 64 * 1024)
            val reopened = nativeMetadataStore(directory).read(scope) as MetadataReadResult.Loaded
            val recovered = requireNotNull(reopened.metadata?.pendingUpload).also { loadedPending = it }
            assertEquals(snapshot, recovered.snapshot)
            assertEquals(references.keys, recovered.photoFiles.keys)
            val perFileBoundary = Stage5Limits.MAX_PHOTO_BYTES.toLong()
            assertTrue(
                recovered.photoFiles.values.all {
                    it.descriptor.byteCount in (perFileBoundary - 3L)..perFileBoundary
                }
            )
            assertTrue(recovered.photoFiles.totalBytes <= Stage5Limits.MAX_TOTAL_PHOTO_BYTES)
            references.keys.forEach { name ->
                val expected = owned.assets.getValue(name).descriptor
                val actual = recovered.photoFiles.getValue(name)
                assertEquals(expected, actual.descriptor)
                actual.open().use { stream ->
                    assertEquals(actual.descriptor.byteCount, stream.countBytes())
                }
                actual.open().use { stream ->
                    assertEquals(actual.descriptor.sha256, stream.sha256())
                }
            }
        } finally {
            loadedPending?.outboxLease?.close()
            owned.close()
            directory.deleteRecursively()
        }
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

    private fun InputStream.countBytes(): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) return total
            if (count > 0) total += count
        }
    }

    private fun InputStream.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
