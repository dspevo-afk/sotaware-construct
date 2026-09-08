package com.example.myapplication.stage9a

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.stage1.*
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.*
import com.example.myapplication.stage5.sha256Hex
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android descriptor backend; no Windows filesystem test adapter. */
@RunWith(AndroidJUnit4::class)
class PendingOutboxSecurityInstrumentedTest {
    @Test fun linkedStagingCannotTruncateAnUnrelatedFileAndPublishedLinksCannotBeOverwritten() {
        val owner = directory()
        try {
            val metadata = File(owner, "metadata").apply { check(mkdirs()) }
            val sentinel = File(owner, "sentinel.txt").apply { writeText("outside-outbox-preserved") }
            val scope = SyncScope("synthetic-account", "synthetic-root", DocumentId.new())
            val pending = pending("first")
            val outbox = FilePendingUploadOutbox(metadata)
            val content = contentDirectory(metadata, scope, pending).apply { check(mkdirs()) }
            try {
                Files.createLink(File(content, ".snapshot.json.tmp").toPath(), sentinel.toPath())
            } catch (denied: java.nio.file.AccessDeniedException) {
                org.junit.Assume.assumeNoException(
                    "Android sandbox prohibits hard-link fixture creation; the real Windows hard-link regression remains required",
                    denied
                )
            }
            outbox.publish(scope, pending)
            assertEquals("outside-outbox-preserved", sentinel.readText())
            assertFalse(Files.isSameFile(File(content, "snapshot.json").toPath(), sentinel.toPath()))

            val next = pending("second")
            val other = contentDirectory(metadata, scope, next).apply { check(mkdirs()) }
            Files.createLink(File(other, "snapshot.json").toPath(), sentinel.toPath())
            assertTrue(runCatching { outbox.publish(scope, next) }.isFailure)
            assertEquals("outside-outbox-preserved", sentinel.readText())
            assertTrue(Files.isSameFile(File(other, "snapshot.json").toPath(), sentinel.toPath()))
        } finally { owner.deleteRecursively() }
    }

    @Test fun anExistingPublishedPayloadIsNeverOverwritten() {
        val owner = directory()
        try {
            val metadata = File(owner, "metadata").apply { check(mkdirs()) }
            val scope = SyncScope("synthetic-account", "synthetic-root", DocumentId.new())
            val pending = pending("fixture")
            val content = contentDirectory(metadata, scope, pending).apply { check(mkdirs()) }
            val existing = File(content, "snapshot.json").apply { writeText("existing-bytes-preserved") }
            assertTrue(runCatching { FilePendingUploadOutbox(metadata).publish(scope, pending) }.isFailure)
            assertEquals("existing-bytes-preserved", existing.readText())
            assertFalse(File(content, PENDING_UPLOAD_OUTBOX_MANIFEST).exists())
        } finally { owner.deleteRecursively() }
    }

    @Test fun redirectedOutboxParentIsRejectedWithoutWritingOutsideItsNamespace() {
        val owner = directory()
        try {
            val metadata = File(owner, "metadata").apply { check(mkdirs()) }
            val outside = File(owner, "outside").apply { check(mkdirs()) }
            val sentinel = File(outside, "sentinel.txt").apply { writeText("unchanged") }
            Files.createSymbolicLink(File(metadata, PENDING_UPLOAD_OUTBOX_DIRECTORY).toPath(), outside.toPath())
            val scope = SyncScope("synthetic-account", "synthetic-root", DocumentId.new())
            assertTrue(runCatching { FilePendingUploadOutbox(metadata).publish(scope, pending("fixture")) }.isFailure)
            assertEquals("unchanged", sentinel.readText())
            assertEquals(listOf("sentinel.txt"), outside.listFiles()!!.map { it.name })
        } finally { owner.deleteRecursively() }
    }

    @Test fun nativeCleanupReclaimsOnlyValidatedOldContentAndRetainsAmbiguousEvidence() = runBlocking {
        val owner = directory()
        try {
            val metadata = File(owner, "metadata").apply { check(mkdirs()) }
            val sentinel = File(owner, "sentinel.txt").apply { writeText("outside-outbox-preserved") }
            val scope = SyncScope("synthetic-account", "synthetic-root", DocumentId.new())
            val store = nativeMetadataStore(metadata)
            val first = pending("first")
            val second = pending("second")
            val third = pending("third")
            val firstDirectory = contentDirectory(metadata, scope, first)
            val secondDirectory = contentDirectory(metadata, scope, second)
            assertEquals(MetadataWriteResult.Committed, store.write(SyncMetadata(scope=scope,pendingUpload=first)))
            assertTrue(firstDirectory.isDirectory)
            assertEquals(MetadataWriteResult.Committed, store.write(SyncMetadata(scope=scope,pendingUpload=second)))
            assertFalse("descriptor-relative cleanup must reclaim a proved unreferenced generation", firstDirectory.exists())
            val extra = File(secondDirectory, "unrecognized-evidence.txt").apply { writeText("retain") }
            assertEquals(MetadataWriteResult.Committed, store.write(SyncMetadata(scope=scope,pendingUpload=third)))
            assertTrue("ambiguous old content must remain available for recovery", extra.isFile)
            assertEquals("retain", extra.readText())
            val restored = (nativeMetadataStore(metadata).read(scope) as MetadataReadResult.Loaded).metadata
            assertEquals(third.snapshot, restored?.pendingUpload?.snapshot)
            assertEquals("outside-outbox-preserved", sentinel.readText())
        } finally { owner.deleteRecursively() }
    }

    private fun nativeMetadataStore(directory: File): FileSyncMetadataStore =
        FileSyncMetadataStore(
            directory,
            kotlinx.coroutines.Dispatchers.IO,
            null,
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        )

    private fun pending(text: String): DurablePendingUpload {
        val source = DocumentSourceIdentityV1("content://stage9a/outbox-security", "fixture.pdf")
        val snapshot = DocumentSnapshotV1(1,0,source,mapOf(0 to PageSnapshotV1(
            notes=listOf(NoteSnapshotV1(.2f,.3f,text,16f,false,0f))
        )))
        return DurablePendingUpload(SyncReason.MANUAL,source.sourceUri,null,1,null,snapshot,emptyMap())
    }

    private fun contentDirectory(root: File, scope: SyncScope, pending: DurablePendingUpload): File {
        val key = listOf(scope.accountId,scope.backupRootId,scope.documentId.value)
            .joinToString(0.toChar().toString())
        val hash = sha256Hex(key.toByteArray(Charsets.UTF_8))
        val id = FilePendingUploadOutbox(root).referenceFor(scope,pending).contentId
        return File(File(File(root,PENDING_UPLOAD_OUTBOX_DIRECTORY),hash),id)
    }

    private fun directory(): File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
        "stage9a-outbox-security-${UUID.randomUUID()}"
    ).apply { check(mkdirs()) }
}
