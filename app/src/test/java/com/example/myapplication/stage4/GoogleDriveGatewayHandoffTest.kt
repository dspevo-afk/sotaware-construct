package com.example.myapplication.stage4

import com.example.myapplication.stage1.*
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import com.example.myapplication.stage9b.DRIVE_MANIFEST_SCHEMA_VERSION
import com.example.myapplication.stage9b.DriveImmutableAssetTransfer
import com.example.myapplication.stage9b.RemoteManifestCodec
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.api.services.drive.Drive
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Regression tests: real production gateway and lease, synthetic HTTP only. */
class GoogleDriveGatewayHandoffTest {
    @Test fun successfulUpload_releasesLease_control() = exercise(false)
    @Test fun cancelledUploadAfterRemoteCommit_mustNotLeakMutationLease() = exercise(true)

    private fun exercise(cancelAfterCommit: Boolean) = runBlocking {
        val scope = SyncScope("review-account", "review-root", DocumentId.new())
        val realLease = ScopeRemoteMutationLease()
        val held = AtomicReference<RemoteMutationSession?>()
        val lease = object : RemoteMutationLease {
            override suspend fun advance(generation: Long) = realLease.advance(generation)
            override fun isGenerationCurrent(generation: Long) = realLease.isGenerationCurrent(generation)
            override suspend fun begin(generation: Long, isGenerationCurrent: () -> Boolean): RemoteMutationSession? =
                realLease.begin(generation, isGenerationCurrent).also { held.set(it) }
        }
        val finalReadEntered = CountDownLatch(1)
        val releaseFinalRead = CountDownLatch(1)
        val folderCreated = AtomicInteger(0)
        val fileCreated = AtomicInteger(0)
        val generatedIdCalls = AtomicInteger(0)
        val folderReads = AtomicInteger(0)
        val delivered = AtomicReference<UploadResult?>()
        val snapshot = DocumentSnapshotV1(2, 0,
            DocumentSourceIdentityV1("content://review/cancellation", "plan.pdf"),
            mapOf(0 to PageSnapshotV1(notes=listOf(NoteSnapshotV1(0.1f, 0.2f, "review", false, 0f, 0.05f, "review-note")))))
        val manifestPayload = RemoteManifestCodec.encode(scope, "plan.pdf", snapshot, emptyMap())
            .toString(Charsets.UTF_8)
        fun folderJson() = """{"id":"folder-1","name":"plan.pdf","parents":["review-root"],"appProperties":{"$SYNC_DOCUMENT_ID_APP_PROPERTY":"${scope.documentId.value}","$SYNC_SCHEMA_APP_PROPERTY":"$DRIVE_MANIFEST_SCHEMA_VERSION","sotaware_account_id":"${scope.accountId}","sotaware_backup_root_id":"${scope.backupRootId}"}}"""
        fun fileJson() = """{"id":"file-1","name":"annotations.json","parents":["folder-1"],"appProperties":{"$SYNC_DOCUMENT_ID_APP_PROPERTY":"${scope.documentId.value}","$SYNC_SCHEMA_APP_PROPERTY":"$DRIVE_MANIFEST_SCHEMA_VERSION","sotaware_account_id":"${scope.accountId}","sotaware_backup_root_id":"${scope.backupRootId}"},"headRevisionId":"r1"}"""
        val transport = object : MockHttpTransport() {
            override fun buildRequest(method: String, url: String): LowLevelHttpRequest =
                object : MockLowLevelHttpRequest(url) {
                    override fun execute(): LowLevelHttpResponse {
                        if (method == "GET" && url.contains("alt=media")) {
                            if (fileCreated.get() == 0) return MockLowLevelHttpResponse().setStatusCode(404).setContent("{}")
                            return MockLowLevelHttpResponse().setStatusCode(200)
                                .setContentType("application/json").setContent(manifestPayload)
                        }
                        if (method == "GET" && url.contains("generateIds")) {
                            val generatedId = if (generatedIdCalls.getAndIncrement() == 0) "folder-1" else "file-1"
                            return MockLowLevelHttpResponse().setStatusCode(200)
                                .setContentType("application/json").setContent("{\"ids\":[\"$generatedId\"]}")
                        }
                        if (method != "GET" && url.contains("uploadType=resumable") && !url.contains("session=")) {
                            return MockLowLevelHttpResponse().setStatusCode(200)
                                .addHeader("Location", "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&session=1")
                                .setZeroContent()
                        }
                        if (method == "PUT" && url.contains("session=1")) {
                            val uploaded = java.io.ByteArrayOutputStream()
                            streamingContent.writeTo(uploaded)
                            val wireBytes = if (contentEncoding == "gzip") {
                                java.util.zip.GZIPInputStream(uploaded.toByteArray().inputStream()).use { it.readBytes() }
                            } else uploaded.toByteArray()
                            assertEquals(manifestPayload, wireBytes.toString(Charsets.UTF_8))
                            assertEquals("one actual authoritative media publication", 1, fileCreated.incrementAndGet())
                            return MockLowLevelHttpResponse().setStatusCode(200)
                                .addHeader("ETag", "file-e1").setContentType("application/json").setContent(fileJson())
                        }
                        val body = when {
                            method == "GET" && url.contains("/files?") && url.contains("mimeType") -> """{"files":[]}"""
                            method == "GET" && url.contains("/files/file-1") -> {
                                if (fileCreated.get() == 0) return MockLowLevelHttpResponse().setStatusCode(404).setContent("{}")
                                fileJson()
                            }
                            method == "GET" && url.contains("/files/folder-1") -> {
                                if (folderCreated.get() == 0) return MockLowLevelHttpResponse().setStatusCode(404).setContent("{}")
                                if (fileCreated.get() > 0 && folderReads.incrementAndGet() == 1) {
                                    finalReadEntered.countDown()
                                    check(releaseFinalRead.await(10, TimeUnit.SECONDS)) { "review final-read gate was not released" }
                                }
                                folderJson()
                            }
                            method == "GET" && url.contains("/files?") -> """{"files":[]}"""
                            method == "POST" && url.contains("/files") && !url.contains("uploadType=resumable") &&
                                folderCreated.get() == 0 -> {
                                folderCreated.incrementAndGet()
                                folderJson()
                            }
                            method == "POST" && url.contains("/files") && !url.contains("uploadType=resumable") &&
                                folderCreated.get() > 0 && fileCreated.get() == 0 -> {
                                fileCreated.incrementAndGet()
                                fileJson()
                            }
                            else -> return MockLowLevelHttpResponse().setStatusCode(404).setContent("{}")
                        }
                        return MockLowLevelHttpResponse().setStatusCode(200)
                            .setContentType("application/json").setContent(body)
                    }
                }
        }
        val transferRoot = Files.createTempDirectory("drive-gateway-handoff-transfer")
        val gateway = GoogleDriveGateway(
            Drive.Builder(transport, GsonFactory.getDefaultInstance(), null)
                .setApplicationName("Read-only review regression")
                .setRootUrl("https://www.googleapis.com/").setServicePath("drive/v3/").build(),
            "review-account",
            DriveImmutableAssetTransfer(
                service = Drive.Builder(transport, GsonFactory.getDefaultInstance(), null)
                    .setApplicationName("Read-only review transfer")
                    .setRootUrl("https://www.googleapis.com/")
                    .setServicePath("drive/v3/")
                    .build(),
                accountId = "review-account",
                stateDirectory = transferRoot.resolve("state"),
                stagingDirectory = transferRoot.resolve("staging"),
                operationsFactory = TestPhotoPathOperationsFactory,
                // Synthetic HTTP fixture, not Windows directory-fsync qualification.
                directoryForce = {}
            )
        )
        lease.advance(1)
        val requestJob = launch(Dispatchers.Default) {
            val result = gateway.upload(UploadRequest(scope, "plan.pdf", snapshot, null, 1,
                lease, { lease.isGenerationCurrent(1) }))
            delivered.set(result)
            result.mutationSession?.close()
        }
        try {
            val reachedFinalRead = finalReadEntered.await(10, TimeUnit.SECONDS)
            assertTrue("real gateway must reach final read after remote mutation; actual=${delivered.get()}", reachedFinalRead)
            assertEquals(1, fileCreated.get())
            if (cancelAfterCommit) requestJob.cancel()
            releaseFinalRead.countDown()
            withTimeout(5000) { requestJob.join() }
            val reacquired = withTimeoutOrNull(500) { lease.advance(2); true } ?: false
            println("REVIEW_EVIDENCE cancel=$cancelAfterCommit remoteFileCreates=${fileCreated.get()} resultDelivered=${delivered.get()!=null} nextLeaseAcquired=$reacquired")
            if (!cancelAfterCommit) assertTrue(delivered.get() is UploadResult.Uploaded)
            assertTrue("Remote commit completed but cancellation lost gateway result and stranded its mutation lease", reacquired)
        } finally {
            releaseFinalRead.countDown()
            requestJob.cancelAndJoin()
            held.get()?.close()
            transferRoot.toFile().deleteRecursively()
        }
    }
}
