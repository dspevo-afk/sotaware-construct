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


import com.example.myapplication.stage2.SourceFingerprint
import com.google.gson.Gson

/** Real Google adapter, coroutine cancellation and reopened file metadata. */
class GoogleDriveAdoptionHandoffIntegrationTest {
    @Test fun successfulAdoption_recordsCursorAndAllowsNextCheck() = exercise(false)
    @Test fun cancelledAdoption_recordsCursorAndAllowsNextCheck() = exercise(true)

    private fun exercise(cancelAfterCommit: Boolean) = runBlocking {
        val held = AtomicReference<RemoteMutationSession?>()
        val delivered = AtomicReference<AdoptionResult?>()
        val finalReadEntered = CountDownLatch(1)
        val releaseFinalRead = CountDownLatch(1)
        val fileWrites = AtomicInteger(0)
        val folderWrites = AtomicInteger(0)
        val session = sessionWithFingerprint(
            "google-adoption",
            "content://device-b/source",
            SourceFingerprint.fromBytes("controlled-source".toByteArray())
        )
        val scope = scope(session, "account", "root")
        val remoteDocumentId = DocumentId.new()
        val fingerprint = requireNotNull(session.token.sourceFingerprint)
        var folderDocumentId = remoteDocumentId.value
        var fileDocumentId = remoteDocumentId.value
        var fileRevision = "r1"
        var folderEtag = "folder-e1"
        var fileEtag = "file-e1"
        var externalRevisionBeforeFileUpdate = false
        val originalSnapshot = snapshot(session, "remote").copy(
            source = DocumentSourceIdentityV1("content://device-a/source", "plan.pdf")
        )
        val originalPayload = Gson().toJson(
            mapOf(
                "accountId" to scope.accountId,
                "backupRootId" to scope.backupRootId,
                "documentId" to remoteDocumentId.value,
                "displayName" to "plan.pdf",
                "snapshot" to originalSnapshot,
                "sourceFingerprint" to fingerprint.toDriveProperty(),
                "photoFiles" to emptyMap<String, String>()
            )
        )
        var filePayload = originalPayload

        fun folderJson() = """{"id":"folder-1","name":"plan.pdf","parents":["root"],"appProperties":{"$SYNC_DOCUMENT_ID_APP_PROPERTY":"$folderDocumentId","$SYNC_SOURCE_FINGERPRINT_APP_PROPERTY":"${fingerprint.toDriveProperty()}"}}"""
        fun fileJson() = """{"id":"file-1","name":"annotations.json","parents":["folder-1"],"appProperties":{"$SYNC_DOCUMENT_ID_APP_PROPERTY":"$fileDocumentId","$SYNC_SCHEMA_APP_PROPERTY":"1","$SYNC_SOURCE_FINGERPRINT_APP_PROPERTY":"${fingerprint.toDriveProperty()}"},"headRevisionId":"$fileRevision"}"""

        val transport = object : MockHttpTransport() {
            override fun buildRequest(method: String, url: String): LowLevelHttpRequest {
                return object : MockLowLevelHttpRequest(url) {
                    private var ifMatch: String? = null

                    override fun addHeader(name: String, value: String) {
                        if (name.equals("If-Match", ignoreCase = true)) ifMatch = value
                    }

                    override fun execute(): LowLevelHttpResponse {
                        if (method == "GET" && url.contains("alt=media")) {
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .setContentType("application/json")
                                .setContent(filePayload)
                        }
                        if (method == "GET" && url.contains("/files/folder-1")) {
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .setContentType("application/json")
                                .addHeader("ETag", folderEtag)
                                .setContent(folderJson())
                        }
                        if (method == "GET" && url.contains("/files/file-1")) {
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .setContentType("application/json")
                                .addHeader("ETag", fileEtag)
                                .setContent(fileJson())
                        }
                        if (method != "GET" && url.contains("uploadType=resumable") && !url.contains("session=")) {
                            if (externalRevisionBeforeFileUpdate) {
                                externalRevisionBeforeFileUpdate = false
                                fileRevision = "r-external"
                                fileEtag = "file-external"
                            }
                            if (!url.contains("session=") && ifMatch != fileEtag) {
                                return MockLowLevelHttpResponse()
                                    .setStatusCode(412)
                                    .setContentType("application/json")
                                    .setContent("{\"error\":{\"code\":412,\"message\":\"precondition failed\"}}")
                            }
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .addHeader(
                                    "Location",
                                    "https://www.googleapis.com/upload/drive/v3/files/file-1?uploadType=resumable&session=adoption-1"
                                )
                                .setZeroContent()
                        }
                        if (method != "GET" && url.contains("/files/file-1")) {
                            if (!url.contains("session=") && ifMatch != fileEtag) {
                                return MockLowLevelHttpResponse()
                                    .setStatusCode(412)
                                    .setContentType("application/json")
                                    .setContent("{\"error\":{\"code\":412,\"message\":\"precondition failed\"}}")
                            }
                            fileWrites.incrementAndGet()
                            fileDocumentId = scope.documentId.value
                            filePayload = originalPayload.replace(remoteDocumentId.value, scope.documentId.value)
                            fileRevision = "r2"
                            fileEtag = "file-e2"
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .setContentType("application/json")
                                .addHeader("ETag", fileEtag)
                                .setContent(fileJson())
                        }
                        if (method != "GET" && url.contains("/files/folder-1")) {
                            if (ifMatch != folderEtag) {
                                return MockLowLevelHttpResponse()
                                    .setStatusCode(412)
                                    .setContentType("application/json")
                                    .setContent("{\"error\":{\"code\":412,\"message\":\"precondition failed\"}}")
                            }
                            folderDocumentId = scope.documentId.value
                            folderEtag = "folder-e2"
                            folderWrites.incrementAndGet()
                            finalReadEntered.countDown()
                            check(releaseFinalRead.await(10, TimeUnit.SECONDS)) { "adoption handoff gate was not released" }
                            return MockLowLevelHttpResponse()
                                .setStatusCode(200)
                                .setContentType("application/json")
                                .addHeader("ETag", folderEtag)
                                .setContent(folderJson())
                        }
                        if (method == "GET" && url.contains("mimeType")) {
                            return MockLowLevelHttpResponse().setStatusCode(200).setContentType("application/json")
                                .setContent("""{"files":[${folderJson()}]}""")
                        }
                        if (method == "GET" && url.contains("/files?")) {
                            return MockLowLevelHttpResponse().setStatusCode(200).setContentType("application/json")
                                .setContent("""{"files":[${fileJson()}]}""")
                        }
                        return MockLowLevelHttpResponse().setStatusCode(404).setContent("{}")
                    }
                }
            }
        }
        val gateway = GoogleDriveGateway(
            Drive.Builder(transport, GsonFactory.getDefaultInstance(), null)
                .setApplicationName("Stage 4 adoption test")
                .setRootUrl("https://www.googleapis.com/")
                .setServicePath("drive/v3/")
                .build(),
            "account"
        )
        val candidate = RemoteAdoptionCandidate(
            accountId = scope.accountId,
            backupRootId = scope.backupRootId,
            remoteDocumentId = remoteDocumentId,
            sourceFingerprint = fingerprint,
            displayName = "plan.pdf",
            reference = RemoteReference(
                folderId = "folder-1",
                snapshotFileId = "file-1",
                appProperties = mapOf(
                    SYNC_DOCUMENT_ID_APP_PROPERTY to remoteDocumentId.value,
                    SYNC_SOURCE_FINGERPRINT_APP_PROPERTY to fingerprint.toDriveProperty()
                )
            ),
            cursor = RemoteCursor("r1")
        )
        val snapshot = snapshot(session, "local")
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
            override suspend fun adopt(request: AdoptionRequest): AdoptionResult {
                val originalLease = request.mutationLease
                val observedLease = object : RemoteMutationLease by originalLease {
                    override suspend fun begin(generation: Long, isGenerationCurrent: () -> Boolean) =
                        originalLease.begin(generation, isGenerationCurrent).also { held.set(it) }
                }
                return gateway.adopt(request.copy(mutationLease = observedLease)).also { delivered.set(it) }
            }
        }
        val directory = Files.createTempDirectory("drive-adoption-handoff-metadata").toFile()
        val metadata = testFileSyncMetadataStore(directory)
        assertEquals(MetadataWriteResult.Committed, metadata.write(SyncMetadata(scope = scope, pendingAdoption = candidate)))
        val coordinator = SyncCoordinator(observingGateway, metadata, bridge, this, Dispatchers.Default)
        val binding = requireNotNull(coordinator.bind(scope, session.token))
        val adoption = coordinator.enqueueAdoptRemote(binding, candidate)
        try {
            assertTrue("real gateway must reach final read after the remote write", finalReadEntered.await(10, TimeUnit.SECONDS))
            assertEquals(1, fileWrites.get())
            if (cancelAfterCommit) coordinator.fenceForBinding(binding)
            val joinedCancellation = if (cancelAfterCommit) async(Dispatchers.Default) {
                coordinator.cancelForBindingAndJoin(binding)
            } else null
            releaseFinalRead.countDown()
            withTimeout(10000) { joinedCancellation?.await(); adoption.join() }
            if (cancelAfterCommit) assertTrue(adoption.isCancelled)
            else assertTrue(adoption.await() is SyncOutcome.Adopted)
            val result = delivered.get()
            assertTrue("completed gateway result must reach finalization", result is AdoptionResult.Adopted)
            val adopted = (result as AdoptionResult.Adopted).remote
            // Reopen the actual file-backed metadata owner, not an in-memory fake.
            val reopened = testFileSyncMetadataStore(directory).read(scope) as MetadataReadResult.Loaded
            val saved = requireNotNull(reopened.metadata)
            assertEquals(adopted.cursor, saved.acceptedCursor)
            assertEquals(adopted.reference, saved.remoteReference)
            assertNull(saved.pendingUpload)
            assertNull(saved.conflictCursor)
            assertNull(saved.pendingAdoption)
            assertEquals(remoteDocumentId, saved.adoptedRemoteDocumentId)
            val rebound = requireNotNull(coordinator.bind(scope, session.token))
            val nextCheck = withTimeout(5000) { coordinator.enqueueRemoteCheck(rebound).await() }
            assertEquals("same coordinator/scope must remain usable", SyncOutcome.RemoteUnchanged, nextCheck)
            assertEquals("accepted adoption must not be replayed", 1, fileWrites.get())
            assertEquals(1, folderWrites.get())
            println("HANDOFF_INTEGRATION adoption cancel=$cancelAfterCommit cursor=${saved.acceptedCursor} next=$nextCheck")
        } finally {
            releaseFinalRead.countDown()
            withContext(NonCancellable) { coordinator.closeAndJoin() }
            held.get()?.close()
            directory.deleteRecursively()
        }
    }
    private fun sessionWithFingerprint(
        id: String,
        sourceUri: String,
        fingerprint: SourceFingerprint
    ): DocumentSession {
        val documentId = DocumentId.new()
        val source = DocumentSourceIdentityV1(sourceUri, "plan.pdf")
        val association = DocumentAssociation(documentId, source, fingerprint, "legacy-$id.bin")
        return DocumentSession(
            target = ResolvedDocumentTarget(association),
            token = DocumentSessionToken(documentId, source.sourceUri, fingerprint, 1L)
        )
    }

    private fun DocumentSession.documentId(): String = token.documentId.value

    private fun snapshot(session: DocumentSession, marker: String): DocumentSnapshotV1 =
        DocumentSnapshotV1(
            schemaVersion = 1,
            snapshotRevision = 0,
            source = session.target.association.source,
            pages = mapOf(0 to PageSnapshotV1(notes = listOf(NoteSnapshotV1(1f, 2f, marker, 12f, false, 0f))))
        )

    private fun scope(session: DocumentSession, account: String, root: String): SyncScope =
        SyncScope(account, root, session.token.documentId)
}
