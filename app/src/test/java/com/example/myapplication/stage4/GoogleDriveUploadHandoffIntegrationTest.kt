package com.example.myapplication.stage4

import com.example.myapplication.stage1.*
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage3.DocumentSession
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.ResolvedDocumentTarget
import com.example.myapplication.stage5.testFileSyncMetadataStore
import java.nio.file.Files
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

/** Real Google adapter, coroutine cancellation and reopened file metadata. */
class GoogleDriveUploadHandoffIntegrationTest {
    @Test fun successfulUpload_recordsCursorAndAllowsNextCheck() = exercise(false)
    @Test fun cancelledUpload_recordsCursorAndAllowsNextCheck() = exercise(true)

    private fun exercise(cancelAfterCommit: Boolean) = runBlocking {
        val scope = SyncScope("review-account", "review-root", DocumentId.new())
        val held = AtomicReference<RemoteMutationSession?>()
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
                            method == "GET" && url.contains("mimeType") -> if (folderCreated.get() > 0) """{"files":[${folderJson()}]}""" else """{"files":[]}"""
                            method == "GET" && url.contains("/files/file-1") -> fileJson()
                            method == "GET" && url.contains("/files/folder-1") -> {
                                if (folderReads.incrementAndGet() == 2) {
                                    finalReadEntered.countDown()
                                    check(releaseFinalRead.await(10, TimeUnit.SECONDS)) { "review final-read gate was not released" }
                                }
                                folderJson()
                            }
                            method == "GET" -> if (fileCreated.get() > 0) """{"files":[${fileJson()}]}""" else """{"files":[]}"""
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
        val session = DocumentSession(
            ResolvedDocumentTarget(DocumentAssociation(scope.documentId, snapshot.source, null, "review-legacy.bin")),
            DocumentSessionToken(scope.documentId, snapshot.source.sourceUri, null, 1L)
        )
        val bridge = object : SyncSessionBridge {
            override fun currentSession(scope: SyncScope) = session
            override suspend fun captureSnapshot(session: DocumentSession) = snapshot
            override suspend fun captureDurableSnapshot(session: DocumentSession) = snapshot
            override suspend fun persistSnapshot(session: DocumentSession, snapshot: DocumentSnapshotV1) =
                DocumentSaveResult.Saved(session.token.documentId)
            override fun isCurrent(token: DocumentSessionToken) = token == session.token
            override fun applySnapshotReplace(session: DocumentSession, snapshot: DocumentSnapshotV1) = Unit
        }
        val observingGateway = object : DriveGateway by gateway {
            override suspend fun upload(request: UploadRequest): UploadResult {
                val originalLease = request.mutationLease
                val observedLease = object : RemoteMutationLease by originalLease {
                    override suspend fun begin(generation: Long, isGenerationCurrent: () -> Boolean) =
                        originalLease.begin(generation, isGenerationCurrent).also { held.set(it) }
                }
                return gateway.upload(request.copy(mutationLease = observedLease)).also { delivered.set(it) }
            }
        }
        val directory = Files.createTempDirectory("drive-upload-handoff-metadata").toFile()
        val metadata = testFileSyncMetadataStore(directory)
        val coordinator = SyncCoordinator(observingGateway, metadata, bridge, this, Dispatchers.Default)
        val binding = requireNotNull(coordinator.bind(scope, session.token))
        val upload = coordinator.enqueueUpload(binding, SyncReason.MANUAL)
        try {
            assertTrue("real gateway must reach final read after the remote write", finalReadEntered.await(10, TimeUnit.SECONDS))
            assertEquals(1, fileCreated.get())
            if (cancelAfterCommit) coordinator.fenceForBinding(binding)
            val joinedCancellation = if (cancelAfterCommit) async(Dispatchers.Default) {
                coordinator.cancelForBindingAndJoin(binding)
            } else null
            releaseFinalRead.countDown()
            withTimeout(10000) { joinedCancellation?.await(); upload.join() }
            if (cancelAfterCommit) assertTrue(upload.isCancelled)
            else assertTrue(upload.await() is SyncOutcome.Uploaded)
            val result = delivered.get()
            assertTrue("completed gateway result must reach finalization", result is UploadResult.Uploaded)
            val uploaded = (result as UploadResult.Uploaded).remote
            // Reopen the actual file-backed metadata owner, not an in-memory fake.
            val reopened = testFileSyncMetadataStore(directory).read(scope) as MetadataReadResult.Loaded
            val saved = requireNotNull(reopened.metadata)
            assertEquals(uploaded.cursor, saved.acceptedCursor)
            assertEquals(uploaded.reference, saved.remoteReference)
            assertNull(saved.pendingUpload)
            assertNull(saved.conflictCursor)
            val rebound = requireNotNull(coordinator.bind(scope, session.token))
            val nextCheck = withTimeout(5000) { coordinator.enqueueRemoteCheck(rebound).await() }
            assertEquals("same coordinator/scope must remain usable", SyncOutcome.RemoteUnchanged, nextCheck)
            assertEquals("accepted upload must not be replayed", 1, fileCreated.get())
            assertEquals(1, folderCreated.get())
            println("HANDOFF_INTEGRATION upload cancel=$cancelAfterCommit cursor=${saved.acceptedCursor} next=$nextCheck")
        } finally {
            releaseFinalRead.countDown()
            withContext(NonCancellable) { coordinator.closeAndJoin() }
            held.get()?.close()
            directory.deleteRecursively()
        }
    }
}
