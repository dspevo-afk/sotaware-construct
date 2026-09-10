package com.example.myapplication.stage4

import com.example.myapplication.stage1.*
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import com.example.myapplication.stage5.validatePhotoBytes
import com.example.myapplication.stage9b.*
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.api.services.drive.Drive
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files

/** Real gateway and conditional transport; the fake applies the actual request body. */
class DriveAdoptionAmbiguousManifestTest {
    @Test fun ordinaryManifestAdoptionControl() = exercise(Fault.NONE)
    @Test fun committedManifestServerFailureIsReconciled() = exercise(Fault.HTTP_COMMITTED)
    @Test fun committedManifestInvalidEtagIsReconciled() = exercise(Fault.MALFORMED_ACK)
    @Test fun committedManifestLostResponseIsReconciled() = exercise(Fault.LOST)
    @Test fun committedManifestCloseFailureIsReconciled() = exercise(Fault.CLOSE)
    @Test fun reconciledManifestRollsBackWithFreshEtagWhenFolderFails() = exercise(Fault.HTTP_COMMITTED, failFolder = true)
    @Test fun manifestRollbackDoesNotOverwriteLaterExternalEdit() = exercise(Fault.HTTP_COMMITTED, failFolder = true, externalBeforeRollback = true)
    @Test fun uncommittedManifestFailureNeverReplaysPut() = exercise(Fault.HTTP_UNCOMMITTED)
    @Test fun failureBeforeManifestCommitNeverChangesOwners() = exercise(Fault.BEFORE_COMMIT)
    @Test fun explicitManifestPreconditionFailureIsTerminal() = exercise(Fault.PRECONDITION)
    @Test fun changedManifestOwnerAfterLostResponseIsPreserved() = exercise(Fault.EXTERNAL_OWNER)
    @Test fun changedManifestContentAfterLostResponseIsPreserved() = exercise(Fault.EXTERNAL_CONTENT)
    @Test fun movedManifestAfterLostResponseIsPreserved() = exercise(Fault.EXTERNAL_PARENT)
    @Test fun successfulManifestReplyWithWrongOwnerRequiresReadback() = exercise(Fault.EXTERNAL_ACK_SCOPE)

    private enum class Fault { NONE, LOST, CLOSE, BEFORE_COMMIT, PRECONDITION, EXTERNAL_OWNER, EXTERNAL_CONTENT, EXTERNAL_PARENT, HTTP_COMMITTED, HTTP_UNCOMMITTED, MALFORMED_ACK, EXTERNAL_ACK_SCOPE }

    private fun exercise(fault: Fault, failFolder: Boolean = false, externalBeforeRollback: Boolean = false) = runBlocking {
        val scope = SyncScope("account", "root", DocumentId.new())
        val oldId = DocumentId.new()
        val fingerprint = SourceFingerprint.fromBytes("synthetic-source".toByteArray())
        val photo = validatePhotoBytes(Stage4PhotoFixture.previousJpegBytes()).descriptor
        val descriptor = RemoteAssetDescriptor("asset-1", photo.byteCount, photo.sha256,
            photo.mimeType, photo.width, photo.height)
        val snapshot = DocumentSnapshotV1(schemaVersion = 2, snapshotRevision = 1L,
            source = DocumentSourceIdentityV1("content://synthetic/source", "plan.pdf"),
            pages = mapOf(0 to PageSnapshotV1(photoPins = listOf(PhotoPinSnapshotV1(
                x = .5f, y = .5f, id = "pin-1", imageFileNames = listOf("photo.jpg"), imageNotes = emptyMap(), imageShapes = emptyMap())))))
        val originalBytes = RemoteManifestCodec.encode(
            SyncScope(scope.accountId, scope.backupRootId, oldId), "plan.pdf", snapshot,
            mapOf("photo.jpg" to descriptor), fingerprint)
        var manifestBytes = originalBytes.copyOf()
        val oldProperties = mapOf(SYNC_DOCUMENT_ID_APP_PROPERTY to oldId.value,
            SYNC_SCHEMA_APP_PROPERTY to "3", "sotaware_account_id" to "account",
            "sotaware_backup_root_id" to "root",
            SYNC_SOURCE_FINGERPRINT_APP_PROPERTY to fingerprint.toDriveProperty())
        val properties = linkedMapOf(
            "folder-1" to oldProperties.toMutableMap(), "file-1" to oldProperties.toMutableMap(),
            "asset-1" to (oldProperties - SYNC_SCHEMA_APP_PROPERTY + mapOf(
                "sotaware_manifest_schema" to "3", "sotaware_asset_sha256" to photo.sha256,
                "sotaware_immutable_asset" to "1")).toMutableMap())
        val originalAssetProperties = properties.getValue("asset-1").toMap()
        val revisions = mutableMapOf("folder-1" to 1, "file-1" to 1, "asset-1" to 1)
        val writes = mutableListOf<Pair<String, String?>>()
        var manifestAttempts = 0
        var faultTriggered = false
        var assetOutputHash = photo.sha256
        var manifestParent = "folder-1"
        fun etag(id: String) = "\"$id-e${revisions.getValue(id)}\""
        fun json(body: String, status: Int = 200) = MockLowLevelHttpResponse()
            .setStatusCode(status).setContentType("application/json").setContent(body)
        fun metadata(id: String): String = JsonObject().apply {
            addProperty("id", id)
            addProperty("name", when (id) { "file-1" -> "annotations.json"; "asset-1" -> "sotaware-asset-${photo.sha256}.jpg"; else -> "plan.pdf" })
            addProperty("mimeType", when (id) { "folder-1" -> "application/vnd.google-apps.folder"; "asset-1" -> photo.mimeType; else -> "application/json" })
            add("parents", JsonArray().apply { add(if (id == "folder-1") "root" else if (id == "file-1") manifestParent else "folder-1") })
            add("appProperties", JsonObject().apply { properties.getValue(id).forEach { (k, v) -> addProperty(k, v) } })
            if (id == "file-1") addProperty("headRevisionId", "r${revisions.getValue(id)}")
            if (id == "asset-1") { addProperty("size", photo.byteCount.toString()); addProperty("sha256Checksum", assetOutputHash) }
        }.toString()
        val transport = object : MockHttpTransport() {
            override fun buildRequest(method: String, url: String): LowLevelHttpRequest = object : MockLowLevelHttpRequest(url) {
                private var ifMatch: String? = null
                override fun addHeader(name: String, value: String) {
                    if (name.equals("If-Match", true)) ifMatch = value
                }
                override fun execute(): LowLevelHttpResponse {
                    val id = url.substringBefore('?').substringAfterLast('/')
                    check(id in properties) { "Unexpected fixture resource: $id" }
                    if (method == "GET") {
                        if (url.contains("alt=media")) {
                            check(id == "file-1") { "Provider checksum should verify immutable bytes" }
                            return json(manifestBytes.toString(Charsets.UTF_8))
                        }
                        return json(driveProviderWire(url, metadata(id), etag(id)))
                    }
                    assertEquals("PUT", method)
                    assertTrue(url.contains("/drive/v2/files/"))
                    writes += id to ifMatch
                    if (ifMatch != etag(id)) return json("{}", 412)
                    if (id == "folder-1" && failFolder) {
                        if (externalBeforeRollback) {
                            properties.getValue("file-1")[SYNC_DOCUMENT_ID_APP_PROPERTY] = "external-owner"
                            revisions["file-1"] = 3
                        }
                        return json("{}", 412)
                    }
                    if (id == "file-1") {
                        manifestAttempts++
                        if (!faultTriggered) {
                            faultTriggered = true
                            if (fault == Fault.BEFORE_COMMIT) throw IOException("synthetic failure before commit")
                            if (fault == Fault.PRECONDITION) return json("{}", 412)
                            if (fault == Fault.HTTP_UNCOMMITTED) return json("{}", 503)
                        }
                    }
                    val body = ByteArrayOutputStream().also { streamingContent.writeTo(it) }.toString("UTF-8")
                    val parts = if (contentType.startsWith("multipart/related")) {
                        val boundary = contentType.substringAfter("boundary=").trim().trim('"')
                        body.split("--$boundary").drop(1).filter { !it.startsWith("--") }
                            .map { it.substringAfter("\r\n\r\n").removeSuffix("\r\n") }
                    } else listOf(body)
                    val submitted = JsonParser.parseString(parts.first()).asJsonObject["properties"].asJsonArray
                    properties[id] = submitted.associate { entry ->
                        val p = entry.asJsonObject
                        assertEquals("PRIVATE", p["visibility"].asString)
                        p["key"].asString to p["value"].asString
                    }.toMutableMap()
                    if (id == "file-1") {
                        assertEquals(2, parts.size)
                        manifestBytes = parts[1].toByteArray(Charsets.UTF_8)
                    } else assertEquals(1, parts.size)
                    revisions[id] = revisions.getValue(id) + 1
                    if (id == "file-1" && manifestAttempts == 1) {
                        when (fault) {
                            Fault.LOST -> throw IOException("synthetic lost committed response")
                            Fault.HTTP_COMMITTED -> return json("{}", 503)
                            Fault.MALFORMED_ACK -> return json(driveProviderWire(url, metadata(id), etag(id)).let {
                                JsonParser.parseString(it).asJsonObject.apply { addProperty("etag", "unquoted-invalid-tag") }.toString()
                            })
                            Fault.EXTERNAL_ACK_SCOPE -> { properties.getValue(id)[SYNC_DOCUMENT_ID_APP_PROPERTY] = "external-owner"; revisions[id] = 3 }
                            Fault.EXTERNAL_OWNER -> { properties.getValue(id)[SYNC_DOCUMENT_ID_APP_PROPERTY] = "external-owner"; revisions[id] = 3; throw IOException("synthetic lost response") }
                            Fault.EXTERNAL_CONTENT -> { manifestBytes = "{\"external\":true}".toByteArray(); revisions[id] = 3; throw IOException("synthetic lost response") }
                            Fault.EXTERNAL_PARENT -> { manifestParent = "external-folder"; revisions[id] = 3; throw IOException("synthetic lost response") }
                            Fault.CLOSE -> return object : MockLowLevelHttpResponse() {
                                override fun disconnect() { throw IOException("synthetic committed response cleanup failure") }
                            }.setStatusCode(200).setContentType("application/json")
                                .setContent(driveProviderWire(url, metadata(id), etag(id)))
                            else -> Unit
                        }
                    }
                    return json(driveProviderWire(url, metadata(id), etag(id)))
                }
            }
        }
        val root = Files.createTempDirectory("stage9b-adoption-ambiguous-manifest")
        var result: AdoptionResult? = null
        try {
            val service = Drive.Builder(transport, GsonFactory.getDefaultInstance(), null)
                .setApplicationName("Stage9B synthetic adoption delta").build()
            val gateway = GoogleDriveGateway(service, "account", DriveImmutableAssetTransfer(service = service,
                accountId = "account", stateDirectory = root.resolve("state"), stagingDirectory = root.resolve("staging"),
                operationsFactory = TestPhotoPathOperationsFactory, directoryForce = {}))
            val candidate = RemoteAdoptionCandidate("account", "root", oldId, fingerprint, "plan.pdf",
                RemoteReference("folder-1", "file-1", oldProperties), RemoteCursor("r1"))
            val lease = ScopeRemoteMutationLease().apply { advance(1L) }
            result = gateway.adopt(AdoptionRequest(scope, candidate, fingerprint, 1L, lease, { true }))
            assertTrue("the manifest mutation must be reached: $result", faultTriggered)
            if (!failFolder && fault in setOf(Fault.NONE, Fault.LOST, Fault.CLOSE, Fault.HTTP_COMMITTED, Fault.MALFORMED_ACK)) {
                assertTrue("verified committed manifest must not strand adoption: $result", result is AdoptionResult.Adopted)
                properties.values.forEach { assertEquals(scope.documentId.value, it[SYNC_DOCUMENT_ID_APP_PROPERTY]) }
                assertEquals(1, manifestAttempts)
                assertEquals(3, writes.size)
                assertEquals(mapOf("photo.jpg" to descriptor), RemoteManifestCodec.decode(manifestBytes, scope, fingerprint).manifest.assets)
            } else {
                assertTrue("unverified manifest must fail closed", result is AdoptionResult.Rejected)
                assertEquals(oldId.value, properties.getValue("folder-1")[SYNC_DOCUMENT_ID_APP_PROPERTY])
                assertEquals(originalAssetProperties, properties.getValue("asset-1"))
                if (failFolder) {
                    assertEquals(listOf("\"file-1-e1\"", "\"file-1-e2\""), writes.filter { it.first == "file-1" }.map { it.second })
                    if (externalBeforeRollback) {
                        assertEquals("external-owner", properties.getValue("file-1")[SYNC_DOCUMENT_ID_APP_PROPERTY])
                    } else assertArrayEquals(originalBytes, manifestBytes)
                } else {
                    assertEquals(1, manifestAttempts)
                    assertEquals("no other owner may change after an unverified manifest", 1, writes.size)
                    if (fault in setOf(Fault.BEFORE_COMMIT, Fault.PRECONDITION, Fault.HTTP_UNCOMMITTED)) {
                        assertArrayEquals(originalBytes, manifestBytes)
                        assertEquals(oldProperties, properties.getValue("file-1"))
                    }
                    if (fault in setOf(Fault.EXTERNAL_OWNER, Fault.EXTERNAL_ACK_SCOPE)) assertEquals("external-owner", properties.getValue("file-1")[SYNC_DOCUMENT_ID_APP_PROPERTY])
                    if (fault == Fault.EXTERNAL_CONTENT) assertEquals("{\"external\":true}", manifestBytes.toString(Charsets.UTF_8))
                    if (fault == Fault.EXTERNAL_PARENT) assertEquals("external-folder", manifestParent)
                }
            }
        } finally {
            result?.mutationSession?.close()
            check(root.toFile().deleteRecursively())
        }
    }
}
