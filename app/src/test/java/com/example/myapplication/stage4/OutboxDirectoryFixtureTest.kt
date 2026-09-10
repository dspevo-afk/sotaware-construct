package com.example.myapplication.stage4

import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import com.example.myapplication.stage5.sha256Hex
import com.example.myapplication.stage9b.PhotoAssetSet
import com.example.myapplication.stage9b.testPhotoAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.SecureDirectoryStream

/** JVM controls for the explicit outbox directory-opening seam. */
class OutboxDirectoryFixtureTest {
    @Test
    fun fixtureStream_enforcesContainmentSingleNamesKindsAndClose() {
        val parent = Files.createTempDirectory("stage9b-outbox-stream").toFile()
        val root = parent.toPath().resolve("root")
        Files.createDirectories(root)
        val file = root.resolve("file")
        Files.write(file, byteArrayOf(1, 2, 3))
        try {
            val stream = TestOutboxDirectoryStream.open(root, parent.toPath())
            try {
                assertTrue(stream.listNames(4).contains("file"))
                assertThrows(IOException::class.java) { stream.readAttributes("../file") }
                assertThrows(IOException::class.java) { stream.readAttributes("nested/file") }
                assertThrows(IOException::class.java) { stream.openDirectory("file") }
                assertThrows(IOException::class.java) { stream.deleteDirectory("file") }
            } finally {
                stream.close()
            }
            assertThrows(IOException::class.java) { stream.listNames(4) }
            assertThrows(IOException::class.java) {
                TestOutboxDirectoryStream.open(parent.toPath().resolve("outside"), root)
            }

            // A symlink, when the host permits creating one, is observable via
            // NOFOLLOW attributes and cannot be opened as a directory.
            val target = parent.toPath().resolve("target")
            Files.createDirectories(target)
            val link = root.resolve("link")
            val symlinkCreated = runCatching {
                Files.createSymbolicLink(link, target)
            }.isSuccess
            if (symlinkCreated) {
                val reopened = TestOutboxDirectoryStream.open(root, parent.toPath())
                try {
                    assertTrue(reopened.readAttributes("link").isSymbolicLink)
                    assertThrows(IOException::class.java) { reopened.openDirectory("link") }
                } finally {
                    reopened.close()
                }
            }
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun explicitFixture_reclaimsKnownOrphans_butRetainsCurrentLeasedAndUnknown() = runBlocking {
        val root = Files.createTempDirectory("stage9b-outbox-reconcile").toFile()
        val scope = SyncScope("account", "root", DocumentId.new())
        val outbox = FilePendingUploadOutbox(
            root,
            TestPhotoPathOperationsFactory,
            TestOutboxDirectoryStreamFactory
        )
        val store = FileSyncMetadataStore(
            root,
            Dispatchers.IO,
            TestPhotoPathOperationsFactory,
            pendingUploadDirectoryStreamFactory = TestOutboxDirectoryStreamFactory
        )
        try {
            val current = pending(scope, generation = 1L)
            val currentReference = outbox.publish(scope, current)
            assertTrue(store.write(SyncMetadata(scope = scope, pendingUpload = current)) is MetadataWriteResult.Committed)

            val knownOrphan = pending(scope, generation = 2L)
            val knownOrphanReference = outbox.publish(scope, knownOrphan)
            val knownOrphanDirectory = contentDirectory(root, scope, knownOrphanReference.contentId)
            outbox.reconcile(scope, store.metadataFileFor(scope))
            assertFalse(Files.exists(knownOrphanDirectory, LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.isDirectory(contentDirectory(root, scope, currentReference.contentId)))

            val leased = pending(scope, generation = 3L, withPhoto = true)
            val leasedReference = outbox.publish(scope, leased)
            val loaded = outbox.load(
                scope = scope,
                reference = leasedReference,
                reason = leased.reason,
                sourceUri = leased.sourceUri,
                sourceFingerprint = leased.sourceFingerprint,
                generation = leased.generation,
                expectedCursor = leased.expectedCursor
            )
            try {
                outbox.reconcile(scope, store.metadataFileFor(scope))
                assertTrue(Files.isDirectory(contentDirectory(root, scope, leasedReference.contentId)))
            } finally {
                loaded.close()
            }
            outbox.reconcile(scope, store.metadataFileFor(scope))
            assertFalse(Files.exists(contentDirectory(root, scope, leasedReference.contentId), LinkOption.NOFOLLOW_LINKS))

            val unknown = pending(scope, generation = 4L)
            val unknownReference = outbox.publish(scope, unknown)
            val unknownDirectory = contentDirectory(root, scope, unknownReference.contentId)
            Files.write(unknownDirectory.resolve("unexpected-child"), byteArrayOf(9))
            outbox.reconcile(scope, store.metadataFileFor(scope))
            assertTrue(Files.isDirectory(unknownDirectory))
            Files.delete(unknownDirectory.resolve("unexpected-child"))
            outbox.reconcile(scope, store.metadataFileFor(scope))
            assertFalse(Files.exists(unknownDirectory, LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.isDirectory(contentDirectory(root, scope, currentReference.contentId)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun defaultOpener_doesNotTurnMissingSecureCapabilityIntoPathFallback() = runBlocking {
        val root = Files.createTempDirectory("stage9b-outbox-default").toFile()
        val scope = SyncScope("account", "root", DocumentId.new())
        val fixtureOutbox = FilePendingUploadOutbox(
            root,
            TestPhotoPathOperationsFactory,
            TestOutboxDirectoryStreamFactory
        )
        val store = FileSyncMetadataStore(
            root,
            Dispatchers.IO,
            TestPhotoPathOperationsFactory,
            pendingUploadDirectoryStreamFactory = TestOutboxDirectoryStreamFactory
        )
        try {
            val current = pending(scope, generation = 1L)
            fixtureOutbox.publish(scope, current)
            assertTrue(store.write(SyncMetadata(scope = scope, pendingUpload = current)) is MetadataWriteResult.Committed)
            val orphan = pending(scope, generation = 2L)
            val orphanReference = fixtureOutbox.publish(scope, orphan)
            val orphanDirectory = contentDirectory(root, scope, orphanReference.contentId)

            val supportsSecureDirectoryStream = Files.newDirectoryStream(root.toPath()).use {
                it is SecureDirectoryStream<*>
            }
            val defaultOutbox = FilePendingUploadOutbox(root, TestPhotoPathOperationsFactory)
            defaultOutbox.reconcile(scope, store.metadataFileFor(scope))
            if (supportsSecureDirectoryStream) {
                assertFalse(Files.exists(orphanDirectory, LinkOption.NOFOLLOW_LINKS))
            } else {
                // On providers without the native primitive, cleanup is
                // conservative. No path-based fallback is accepted here.
                assertTrue(Files.isDirectory(orphanDirectory, LinkOption.NOFOLLOW_LINKS))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun pending(
        scope: SyncScope,
        generation: Long,
        withPhoto: Boolean = false
    ): DurablePendingUpload {
        val source = DocumentSourceIdentityV1(
            sourceUri = "content://stage9b/outbox-fixture/${scope.documentId.value}",
            displayName = "fixture.pdf"
        )
        val snapshot = DocumentSnapshotV1(
            schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
            snapshotRevision = 0L,
            source = source,
            pages = if (withPhoto) {
                mapOf(
                    0 to PageSnapshotV1(
                        photoPins = listOf(
                            PhotoPinSnapshotV1(
                                x = .5f,
                                y = .5f,
                                id = "fixture-pin",
                                imageFileNames = listOf("fixture.jpg"),
                                imageNotes = emptyMap(),
                                imageShapes = emptyMap()
                            )
                        )
                    )
                )
            } else {
                emptyMap()
            }
        )
        return DurablePendingUpload(
            reason = SyncReason.MANUAL,
            sourceUri = source.sourceUri,
            sourceFingerprint = null,
            generation = generation,
            expectedCursor = null,
            snapshot = snapshot,
            photoFiles = if (withPhoto) {
                testPhotoAssets(mapOf("fixture.jpg" to Stage4PhotoFixture.incomingJpegBytes()))
            } else {
                PhotoAssetSet.EMPTY
            }
        )
    }

    private fun contentDirectory(root: java.io.File, scope: SyncScope, contentId: String) =
        root.toPath()
            .resolve(PENDING_UPLOAD_OUTBOX_DIRECTORY)
            .resolve(
                sha256Hex(
                    "${scope.accountId}\u0000${scope.backupRootId}\u0000${scope.documentId.value}"
                        .toByteArray(Charsets.UTF_8)
                )
            )
            .resolve(contentId)
}
