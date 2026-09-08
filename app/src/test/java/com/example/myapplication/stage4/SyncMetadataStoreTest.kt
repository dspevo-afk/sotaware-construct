package com.example.myapplication.stage4

import com.example.myapplication.stage5.testFileSyncMetadataStore
import com.example.myapplication.stage5.PhotoPathOperations
import com.example.myapplication.stage5.PhotoPathOperationsFactory

import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMetadataStoreTest {
    @Test
    fun fileStore_absentMetadataRemainsNull() = runTest {
        val parent = Files.createTempDirectory("stage4-sync-metadata-absent")
        val root = parent.resolve("not-created").toFile()
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            assertEquals(
                MetadataReadResult.Loaded(null),
                testFileSyncMetadataStore(root).read(scope)
            )
            Files.createDirectory(root.toPath())
            assertEquals(
                MetadataReadResult.Loaded(null),
                testFileSyncMetadataStore(root).read(scope)
            )
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun fileStore_danglingMetadataSymlinkFailsClosed() = runTest {
        val root = Files.createTempDirectory("stage4-sync-metadata-dangling").toFile()
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val target = testFileSyncMetadataStore(root).metadataFileFor(scope).toPath()
            val created = try {
                Files.createSymbolicLink(target, root.toPath().resolve("missing-target.json"))
                true
            } catch (error: UnsupportedOperationException) {
                false
            } catch (error: SecurityException) {
                false
            } catch (error: java.io.IOException) {
                // Windows commonly requires a capability not available to the
                // test process; Unix failures remain real test failures.
                if (System.getProperty("os.name").startsWith("Windows")) false else throw error
            }
            assumeTrue("symbolic-link capability unavailable", created)

            val result = testFileSyncMetadataStore(root).read(scope)
            assertTrue(result is MetadataReadResult.Failed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fileStore_validMetadataSymlinkIsRejectedWithoutReadingOutside() = runTest {
        val root = Files.createTempDirectory("stage4-sync-metadata-link").toFile()
        val outside = Files.createTempFile("stage4-sync-metadata-outside", ".json")
        val sourceRoot = Files.createTempDirectory("stage4-sync-metadata-source").toFile()
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val metadata = SyncMetadata(scope = scope, acceptedCursor = RemoteCursor("r1"))
            assertEquals(
                MetadataWriteResult.Committed,
                testFileSyncMetadataStore(sourceRoot).write(metadata)
            )
            val source = testFileSyncMetadataStore(sourceRoot).metadataFileFor(scope).toPath()
            Files.write(outside, Files.readAllBytes(source))
            val target = testFileSyncMetadataStore(root).metadataFileFor(scope).toPath()
            val created = try {
                Files.createSymbolicLink(target, outside)
                true
            } catch (error: UnsupportedOperationException) {
                false
            } catch (error: SecurityException) {
                false
            } catch (error: java.io.IOException) {
                if (System.getProperty("os.name").startsWith("Windows")) false else throw error
            }
            assumeTrue("symbolic-link capability unavailable", created)

            assertTrue(testFileSyncMetadataStore(root).read(scope) is MetadataReadResult.Failed)
            assertTrue(
                testFileSyncMetadataStore(root).write(
                    metadata.copy(acceptedCursor = RemoteCursor("r2"))
                ) is MetadataWriteResult.Failed
            )
            assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(outside))
        } finally {
            root.deleteRecursively()
            sourceRoot.deleteRecursively()
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun fileStore_nonRegularMetadataTargetFailsClosed() = runTest {
        val root = Files.createTempDirectory("stage4-sync-metadata-nonregular").toFile()
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val target = testFileSyncMetadataStore(root).metadataFileFor(scope).toPath()
            Files.createDirectory(target)

            assertTrue(testFileSyncMetadataStore(root).read(scope) is MetadataReadResult.Failed)
            assertTrue(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fileStore_hostilePreexistingStagingEntryIsNotReusedOrTruncated() = runTest {
        val root = Files.createTempDirectory("stage4-sync-metadata-staging").toFile()
        val sentinel = "attacker-owned-staging".toByteArray()
        val stagingName = AtomicReference<String>()
        val factory = object : PhotoPathOperationsFactory {
            override fun open(rootPath: Path): PhotoPathOperations {
                return object : PhotoPathOperations {
                    private fun path(name: String): Path = rootPath.resolve(name)
                    private fun delegateOpen(name: String): FileChannel = FileChannel.open(
                        path(name),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS
                    )

                    override fun exists(name: String): Boolean =
                        Files.exists(path(name), LinkOption.NOFOLLOW_LINKS)

                    override fun isRegularFile(name: String): Boolean =
                        Files.isRegularFile(path(name), LinkOption.NOFOLLOW_LINKS)

                    override fun size(name: String): Long = Files.size(path(name))

                    override fun openRead(name: String): InputStream =
                        Files.newInputStream(path(name), LinkOption.NOFOLLOW_LINKS)

                    override fun openNewOutput(name: String): FileChannel {
                        stagingName.set(name)
                        Files.write(
                            path(name),
                            sentinel,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE
                        )
                        return delegateOpen(name)
                    }

                    override fun move(source: String, target: String, replaceExisting: Boolean) {
                        Files.move(
                            path(source),
                            path(target),
                            StandardCopyOption.ATOMIC_MOVE
                        )
                    }

                    override fun delete(name: String) {
                        Files.deleteIfExists(path(name))
                    }

                    override fun close() = Unit
                }
            }
        }
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val metadata = SyncMetadata(scope = scope, acceptedCursor = RemoteCursor("r1"))
            val result = FileSyncMetadataStore(root, Dispatchers.IO, factory).write(metadata)
            assertTrue(result is MetadataWriteResult.Failed)
            val name = requireNotNull(stagingName.get())
            assertArrayEquals(sentinel, Files.readAllBytes(root.toPath().resolve(name)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fileStore_readbackByteMismatchCannotReportCommitted() = runTest {
        val root = Files.createTempDirectory("stage4-sync-metadata-readback").toFile()
        val mutated = AtomicBoolean(false)
        val factory = object : PhotoPathOperationsFactory {
            override fun open(rootPath: Path): PhotoPathOperations {
                val delegate = com.example.myapplication.stage5.TestPhotoPathOperations(rootPath)
                return object : PhotoPathOperations {
                    override fun exists(name: String): Boolean = delegate.exists(name)
                    override fun isRegularFile(name: String): Boolean = delegate.isRegularFile(name)
                    override fun size(name: String): Long = delegate.size(name)
                    override fun openRead(name: String): InputStream {
                        val original = delegate.openRead(name).use { it.readBytes() }
                        if (!mutated.getAndSet(true) && name.endsWith(".json")) {
                            val replacement = original.copyOf()
                            replacement[0] = (replacement[0].toInt() xor 1).toByte()
                            Files.write(
                                rootPath.resolve(name),
                                replacement,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE
                            )
                            return replacement.inputStream()
                        }
                        return original.inputStream()
                    }
                    override fun openNewOutput(name: String): FileChannel = delegate.openNewOutput(name)
                    override fun move(source: String, target: String, replaceExisting: Boolean) =
                        delegate.move(source, target, replaceExisting)
                    override fun delete(name: String) = delegate.delete(name)
                    override fun close() = delegate.close()
                }
            }
        }
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val metadata = SyncMetadata(scope = scope, acceptedCursor = RemoteCursor("r1"))
            val result = FileSyncMetadataStore(root, Dispatchers.IO, factory).write(metadata)
            assertTrue(result is MetadataWriteResult.Failed)
            assertFalse(result is MetadataWriteResult.Committed)
        } finally {
            root.deleteRecursively()
        }
    }

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
            assertEquals(MetadataWriteResult.Committed, testFileSyncMetadataStore(root).write(metadata))

            val reread = testFileSyncMetadataStore(root).read(scope)
            assertEquals(MetadataReadResult.Loaded(metadata), reread)
            assertTrue(testFileSyncMetadataStore(root).metadataFileFor(scope).isFile)
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
            val store = testFileSyncMetadataStore(root)
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
            val store = testFileSyncMetadataStore(root)
            assertEquals(MetadataWriteResult.Committed, store.write(metadata))
            assertEquals(MetadataReadResult.Loaded(metadata), testFileSyncMetadataStore(root).read(scope))
        } finally {
            root.deleteRecursively()
        }
    }
}
