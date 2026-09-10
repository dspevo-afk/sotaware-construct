package com.example.myapplication.stage4

import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM-only current-format metadata owner for coordinator fixtures that carry
 * photo assets.  Unlike [InMemorySyncMetadataStore], this exercises the real
 * atomic metadata publication and pending-upload outbox with the explicit
 * test filesystem seam used by the other Stage 9B tests.
 */
internal class TestPersistentPhotoMetadataStore(
    val rootDirectory: File = Files.createTempDirectory("stage4-persistent-photo-metadata").toFile(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) : SyncMetadataStore, AutoCloseable {
    private val delegate = FileSyncMetadataStore(
        rootDirectory,
        dispatcher,
        TestPhotoPathOperationsFactory,
        pendingUploadDirectoryStreamFactory = TestOutboxDirectoryStreamFactory
    )
    private val latest = ConcurrentHashMap<SyncScope, SyncMetadata?>()

    override suspend fun read(scope: SyncScope): MetadataReadResult {
        val result = delegate.read(scope)
        if (result is MetadataReadResult.Loaded) {
            if (result.metadata == null) latest.remove(scope)
            else latest[scope] = result.metadata
        }
        return result
    }

    override suspend fun write(metadata: SyncMetadata): MetadataWriteResult {
        val result = delegate.write(metadata)
        if (result is MetadataWriteResult.Committed) {
            latest[metadata.scope] = metadata
        }
        return result
    }

    override fun recoveryIdentity(metadata: SyncMetadata): String =
        delegate.recoveryIdentity(metadata)

    /** Synchronous view used by coordinator assertions after a completed write. */
    fun snapshot(scope: SyncScope): SyncMetadata? = latest[scope]

    override fun close() {
        // A loaded pending-upload record owns a process-local lease.  The
        // coordinator normally releases it; this idempotent finalizer covers
        // a test that read a record directly before tearing down its root.
        latest.values.forEach { metadata ->
            metadata?.pendingUpload?.outboxLease?.close()
        }
        latest.clear()
        rootDirectory.deleteRecursively()
    }
}
