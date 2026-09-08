package com.example.myapplication.stage4

import com.example.myapplication.stage1.*
import com.example.myapplication.stage2.DocumentId
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
        val folderReads = AtomicInteger(0)
        val delivered = AtomicReference<UploadResult?>()
        fun folderJson() = """{"id":"folder-1","name":"plan.pdf","parents":["review-root"],"appProperties":{"$SYNC_DOCUMENT_ID_APP_PROPERTY":"${scope.documentId.value}"}}"""
        fun fileJson() = """{"id":"file-1","name":"annotations.json","parents":["folder-1"],"appProperties":{"$SYNC_DOCUMENT_ID_APP_PROPERTY":"${scope.documentId.value}","$SYNC_SCHEMA_APP_PROPERTY":"1"},"headRevisionId":"r1"}"""
        val transport = object : MockHttpTransport() {
            override fun buildRequest(method: String, url: String): LowLevelHttpRequest =
                object : MockLowLevelHttpRequest(url) {
                    override fun execute(): LowLevelHttpResponse {
                        if (method != "GET" && url.contains("uploadType=resumable") && !url.contains("session=")) {
                            return MockLowLevelHttpResponse().setStatusCode(200)
                                .addHeader("Location", "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&session=1")
                                .setZeroContent()
                        }
                        val body = when {
                            method == "GET" && url.contains("mimeType") -> """{"files":[]}"""
                            method == "GET" && url.contains("/files/file-1") -> fileJson()
                            method == "GET" && url.contains("/files/folder-1") -> {
                                if (folderReads.incrementAndGet() == 2) {
                                    finalReadEntered.countDown()
                                    check(releaseFinalRead.await(10, TimeUnit.SECONDS)) { "review final-read gate was not released" }
                                }
                                folderJson()
                            }
                            method == "GET" -> """{"files":[]}"""
                            folderCreated.get() == 0 -> { folderCreated.incrementAndGet(); folderJson() }
                            else -> { fileCreated.incrementAndGet(); fileJson() }
                        }
                        return MockLowLevelHttpResponse().setStatusCode(200)
                            .setContentType("application/json").setContent(body)
                    }
                }
        }
        val gateway = GoogleDriveGateway(
            Drive.Builder(transport, GsonFactory.getDefaultInstance(), null)
                .setApplicationName("Read-only review regression")
                .setRootUrl("https://www.googleapis.com/").setServicePath("drive/v3/").build(),
            "review-account"
        )
        val snapshot = DocumentSnapshotV1(1, 0,
            DocumentSourceIdentityV1("content://review/cancellation", "plan.pdf"),
            mapOf(0 to PageSnapshotV1(notes=listOf(NoteSnapshotV1(1f,2f,"review",12f,false,0f)))))
        lease.advance(1)
        val requestJob = launch(Dispatchers.Default) {
            val result = gateway.upload(UploadRequest(scope, "plan.pdf", snapshot, null, 1,
                lease, { lease.isGenerationCurrent(1) }))
            delivered.set(result)
            result.mutationSession?.close()
        }
        try {
            assertTrue("real gateway must reach final read after remote mutation", finalReadEntered.await(10, TimeUnit.SECONDS))
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
        }
    }
}
