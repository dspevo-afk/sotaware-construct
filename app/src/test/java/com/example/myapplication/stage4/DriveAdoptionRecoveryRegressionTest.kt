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

/** Persistent recovery through a new gateway/transport owner, not a replayed PUT. */
class DriveAdoptionRecoveryRegressionTest {
    @Test fun manifestCommitThenOutageRecoversAfterGatewayRecreation() = exercise(Fault.OUTAGE, "file-1")
    @Test fun assetCommitThenOutageRecoversAfterGatewayRecreation() = exercise(Fault.OUTAGE, "asset-1")
    @Test fun folderCommitThenOutageRecoversAfterGatewayRecreation() = exercise(Fault.OUTAGE, "folder-1")
    @Test fun unknownManifestOwnershipDuringOutageCannotBeOverwrittenOnRetry() = exercise(Fault.OUTAGE_EXTERNAL, "file-1")
    @Test fun unknownAssetOwnershipDuringOutageCannotBeOverwrittenOnRetry() = exercise(Fault.OUTAGE_EXTERNAL, "asset-1")
    @Test fun unknownFolderOwnershipDuringOutageCannotBeOverwrittenOnRetry() = exercise(Fault.OUTAGE_EXTERNAL, "folder-1")
    @Test fun validButChangedFinalManifestIsNotAccepted() = exercise(Fault.FINAL_MANIFEST, "asset-1")
    @Test fun staleFolderAcknowledgementIsNotAStableFinalReadback() = exercise(Fault.FINAL_FOLDER, "folder-1")
    @Test fun changedAssetAfterAcknowledgementIsNotAccepted() = exercise(Fault.FINAL_ASSET, "asset-1")
    @Test fun movedAssetAcknowledgementCannotAuthorizeAdoption() = exercise(Fault.ASSET_PARENT, "asset-1")
    @Test fun unchangedFinalReadbackControl() = exercise(Fault.NONE, "unused")

    @Test fun uncommittedManifestRetryRejectsRevisionOnlyExternalEdit() = exercise(Fault.BEFORE_EXTERNAL_REVISION, "file-1")
    @Test fun uncommittedManifestRetryRejectsEtagOnlyExternalEdit() = exercise(Fault.BEFORE_EXTERNAL_ETAG, "file-1")
    @Test fun unchangedUncommittedManifestCanRetry() = exercise(Fault.BEFORE_COMMIT, "file-1")
    @Test fun verifiedManifestCompensationCanRetry() = exercise(Fault.BEFORE_COMMIT, "asset-1")
    @Test fun revisionOnlyEditAfterCompensationCannotRetry() = exercise(Fault.BEFORE_EXTERNAL_REVISION, "asset-1")
    @Test fun etagOnlyEditAfterCompensationCannotRetry() = exercise(Fault.BEFORE_EXTERNAL_ETAG, "asset-1")
    @Test fun completedRecoveryRejectsAnAdditionalAssetParent() = exercise(Fault.OUTAGE_EXTRA_PARENT, "folder-1")
    @Test fun initialAssetWithAnAdditionalParentCannotStartAdoption() = exercise(Fault.INITIAL_EXTRA_PARENT, "unused")
    @Test fun acknowledgedAssetWithAnAdditionalParentCannotAuthorizeAdoption() = exercise(Fault.ASSET_EXTRA_PARENT, "asset-1")
    @Test fun noncanonicalManifestRecoversAfterCommittedManifestOutage() = exercise(Fault.OUTAGE, "file-1", noncanonical = true)
    @Test fun noncanonicalManifestRecoversAfterCommittedAssetOutage() = exercise(Fault.OUTAGE, "asset-1", noncanonical = true)
    @Test fun noncanonicalUncommittedManifestCanRetry() = exercise(Fault.BEFORE_COMMIT, "file-1", noncanonical = true)

    @Test fun freshSelectionAfterNoCommit412CanProceed() = exercise(Fault.BEFORE_EXTERNAL_REVISION, "file-1", noCommitStatus = 412, selection = Selection.ACCEPT)
    @Test fun freshSelectionAfterNoCommit503CanProceed() = exercise(Fault.BEFORE_EXTERNAL_REVISION, "file-1", selection = Selection.ACCEPT)
    @Test fun freshSelectionAfterVerifiedCompensationCanProceed() = exercise(Fault.BEFORE_EXTERNAL_REVISION, "asset-1", selection = Selection.ACCEPT)
    @Test fun freshSelectionOfNoncanonicalOriginalCanProceed() = exercise(Fault.BEFORE_EXTERNAL_REVISION, "file-1", noncanonical = true, selection = Selection.ACCEPT)
    @Test fun reselectionCannotDiscardChangedContent() = exercise(Fault.BEFORE_EXTERNAL_REVISION, "file-1", selection = Selection.CONTENT_CHANGED)
    @Test fun reselectionCannotDiscardChangedAssetOwner() = exercise(Fault.BEFORE_EXTERNAL_REVISION, "file-1", selection = Selection.OWNER_CHANGED)
    @Test fun reselectionCannotDiscardAdditionalAssetParent() = exercise(Fault.BEFORE_EXTERNAL_REVISION, "file-1", selection = Selection.PARENT_CHANGED)
    @Test fun reselectionCannotSwitchRemoteResources() = exercise(Fault.BEFORE_EXTERNAL_REVISION, "file-1", selection = Selection.WRONG_RESOURCE)
    @Test fun reselectionCannotReuseStaleAuthorization() = exercise(Fault.BEFORE_EXTERNAL_REVISION, "file-1", selection = Selection.STALE)
    @Test fun reselectionPersistsBeforeAnotherUncommittedFailure() = exercise(Fault.BEFORE_EXTERNAL_REVISION, "file-1", selection = Selection.INTERRUPTED)
    @Test fun partialCommitCannotBeReauthorizedByReselection() = exercise(Fault.OUTAGE, "file-1", selection = Selection.PARTIAL)
    @Test fun completeUnacknowledgedCommitCannotBeReauthorizedByReselection() = exercise(Fault.OUTAGE, "folder-1", selection = Selection.PARTIAL)

    @Test fun definitiveManifestConflictPermitsFreshChangedContentSelection() = exercise(Fault.BEFORE_EXTERNAL_REVISION, "file-1", noCommitStatus = 412, selection = Selection.DEFINITE_CHANGED)

    private enum class Selection { DEFINITE_CHANGED, ACCEPT, CONTENT_CHANGED, OWNER_CHANGED, PARENT_CHANGED, WRONG_RESOURCE, STALE, INTERRUPTED, PARTIAL }
    private enum class Fault { NONE, OUTAGE, OUTAGE_EXTERNAL, FINAL_MANIFEST, FINAL_FOLDER, FINAL_ASSET, ASSET_PARENT,
        BEFORE_COMMIT, BEFORE_EXTERNAL_REVISION, BEFORE_EXTERNAL_ETAG, OUTAGE_EXTRA_PARENT, INITIAL_EXTRA_PARENT, ASSET_EXTRA_PARENT }
    private fun exercise(fault: Fault, faultId: String, noncanonical: Boolean = false, noCommitStatus: Int = 503, selection: Selection? = null) = runBlocking {
        val scope = SyncScope("account", "root", DocumentId.new())
        val oldId = DocumentId.new()
        val oldScope = scope.copy(documentId = oldId)
        val fingerprint = SourceFingerprint.fromBytes("recovery-source".toByteArray())
        val photo = validatePhotoBytes(Stage4PhotoFixture.previousJpegBytes()).descriptor
        val descriptor = RemoteAssetDescriptor("asset-1", photo.byteCount, photo.sha256, photo.mimeType, photo.width, photo.height)
        val snapshot = DocumentSnapshotV1(schemaVersion = 2, snapshotRevision = 1L,
            source = DocumentSourceIdentityV1("content://synthetic/recovery", "plan.pdf"),
            pages = mapOf(0 to PageSnapshotV1(photoPins = listOf(PhotoPinSnapshotV1(
                x = .5f, y = .5f, id = "pin", imageFileNames = listOf("photo.jpg"), imageNotes = emptyMap(), imageShapes = emptyMap())))))
        val canonicalBytes = RemoteManifestCodec.encode(oldScope, "plan.pdf", snapshot, mapOf("photo.jpg" to descriptor), fingerprint)
        val originalBytes = if (noncanonical) {
            val parsed = JsonParser.parseString(canonicalBytes.toString(Charsets.UTF_8)).asJsonObject
            val reversed = JsonObject().apply { parsed.entrySet().toList().asReversed().forEach { add(it.key, it.value) } }
            (com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(reversed) + "\n").toByteArray()
        } else canonicalBytes
        assertEquals(RemoteManifestCodec.canonicalDigest(canonicalBytes), RemoteManifestCodec.decode(originalBytes, oldScope, fingerprint).canonicalDigest)
        if (noncanonical) assertFalse(canonicalBytes.contentEquals(originalBytes))
        var manifestBytes = originalBytes.copyOf()
        val originalProperties = mapOf(SYNC_DOCUMENT_ID_APP_PROPERTY to oldId.value, SYNC_SCHEMA_APP_PROPERTY to "3",
            "sotaware_account_id" to "account", "sotaware_backup_root_id" to "root",
            SYNC_SOURCE_FINGERPRINT_APP_PROPERTY to fingerprint.toDriveProperty())
        val properties = linkedMapOf("folder-1" to originalProperties.toMutableMap(), "file-1" to originalProperties.toMutableMap(),
            "asset-1" to (originalProperties - SYNC_SCHEMA_APP_PROPERTY + mapOf("sotaware_manifest_schema" to "3",
                "sotaware_asset_sha256" to photo.sha256, "sotaware_immutable_asset" to "1")).toMutableMap())
        val revisions = mutableMapOf("folder-1" to 1, "file-1" to 1, "asset-1" to 1)
        var assetParent = "folder-1"
        var extraAssetParent = fault == Fault.INITIAL_EXTRA_PARENT
        var fileEtagOffset = 0
        var assetHash = photo.sha256
        var outage = false
        var faultTriggered = false
        var rejectReselectedWrite = false
        val writes = mutableListOf<Pair<String, String?>>()
        var externalBytes: ByteArray? = null
        fun etag(id: String) = "\"$id-e${revisions.getValue(id) + if (id == "file-1") fileEtagOffset else 0}\""
        fun json(body: String, status: Int = 200) = MockLowLevelHttpResponse().setStatusCode(status).setContentType("application/json").setContent(body)
        fun metadata(id: String) = JsonObject().apply {
            addProperty("id", id)
            addProperty("name", when(id) { "file-1" -> "annotations.json"; "asset-1" -> "sotaware-asset-${photo.sha256}.jpg"; else -> "plan.pdf" })
            addProperty("mimeType", when(id) { "folder-1" -> "application/vnd.google-apps.folder"; "asset-1" -> photo.mimeType; else -> "application/json" })
            add("parents", JsonArray().apply {
                add(when(id) { "folder-1" -> "root"; "asset-1" -> assetParent; else -> "folder-1" })
                if (id == "asset-1" && extraAssetParent) add("external-folder")
            })
            add("appProperties", JsonObject().apply { properties.getValue(id).forEach { (k,v) -> addProperty(k,v) } })
            if (id == "file-1") addProperty("headRevisionId", "r${revisions.getValue(id)}")
            if (id == "asset-1") { addProperty("size", photo.byteCount.toString()); addProperty("sha256Checksum", assetHash) }
        }.toString()
        val transport = object : MockHttpTransport() {
            override fun buildRequest(method: String, url: String): LowLevelHttpRequest = object : MockLowLevelHttpRequest(url) {
                var ifMatch: String? = null
                override fun addHeader(name: String, value: String) { if (name.equals("If-Match", true)) ifMatch = value }
                override fun execute(): LowLevelHttpResponse {
                    if (outage) throw IOException("synthetic persistent network outage")
                    val id = url.substringBefore('?').substringAfterLast('/')
                    check(id in properties)
                    if (method == "GET") {
                        if (url.contains("alt=media")) { check(id == "file-1"); return json(manifestBytes.toString(Charsets.UTF_8)) }
                        return json(driveProviderWire(url, metadata(id), etag(id)))
                    }
                    assertEquals("PUT", method)
                    writes += id to ifMatch
                    if (ifMatch != etag(id)) return json("{}", 412)
                    if (rejectReselectedWrite) {
                        rejectReselectedWrite = false
                        return json("{}", 503)
                    }
                    if (!faultTriggered && id == faultId && fault in setOf(Fault.BEFORE_COMMIT, Fault.BEFORE_EXTERNAL_REVISION, Fault.BEFORE_EXTERNAL_ETAG)) {
                        faultTriggered = true
                        if (noCommitStatus == 412) {
                            revisions[id] = revisions.getValue(id) + 1
                            if (selection == Selection.DEFINITE_CHANGED) {
                                manifestBytes = RemoteManifestCodec.encode(oldScope, "plan.pdf", snapshot.copy(snapshotRevision = 2L),
                                    mapOf("photo.jpg" to descriptor), fingerprint)
                                properties.getValue("file-1")["sotaware_manifest_digest"] = RemoteManifestCodec.canonicalDigest(manifestBytes)
                            }
                        }
                        return json("{}", noCommitStatus) // Provider did not apply any part of this mutation.
                    }
                    val body = ByteArrayOutputStream().also { streamingContent.writeTo(it) }.toString("UTF-8")
                    val parts = if (contentType.startsWith("multipart/related")) {
                        val boundary = contentType.substringAfter("boundary=").trim().trim('"')
                        body.split("--$boundary").drop(1).filter { !it.startsWith("--") }
                            .map { it.substringAfter("\r\n\r\n").removeSuffix("\r\n") }
                    } else listOf(body)
                    properties[id] = JsonParser.parseString(parts[0]).asJsonObject["properties"].asJsonArray.associate { entry ->
                        val p = entry.asJsonObject; assertEquals("PRIVATE", p["visibility"].asString)
                        p["key"].asString to p["value"].asString
                    }.toMutableMap()
                    if (id == "file-1") { assertEquals(2, parts.size); manifestBytes = parts[1].toByteArray() }
                    else assertEquals(1, parts.size)
                    revisions[id] = revisions.getValue(id) + 1
                    val acknowledgement = driveProviderWire(url, metadata(id), etag(id))
                    if (!faultTriggered && id == faultId) {
                        faultTriggered = true
                        when(fault) {
                            Fault.OUTAGE, Fault.OUTAGE_EXTERNAL, Fault.OUTAGE_EXTRA_PARENT -> { outage = true; throw IOException("synthetic committed PUT then offline") }
                            Fault.FINAL_MANIFEST -> {
                                manifestBytes = RemoteManifestCodec.encode(scope, "plan.pdf", snapshot.copy(snapshotRevision = 2L), mapOf("photo.jpg" to descriptor), fingerprint)
                                externalBytes = manifestBytes.copyOf()
                                properties.getValue("file-1")["sotaware_manifest_digest"] = RemoteManifestCodec.canonicalDigest(manifestBytes)
                                revisions["file-1"] = revisions.getValue("file-1") + 1
                            }
                            Fault.FINAL_FOLDER -> { properties.getValue(id)[SYNC_DOCUMENT_ID_APP_PROPERTY] = "external-owner"; revisions[id] = revisions.getValue(id) + 1 }
                            Fault.FINAL_ASSET -> { assetHash = "0".repeat(64); revisions[id] = revisions.getValue(id) + 1 }
                            Fault.ASSET_EXTRA_PARENT -> { extraAssetParent = true; revisions[id] = revisions.getValue(id) + 1; return json(driveProviderWire(url, metadata(id), etag(id))) }
                            Fault.ASSET_PARENT -> { assetParent = "external-folder"; revisions[id] = revisions.getValue(id) + 1; return json(driveProviderWire(url, metadata(id), etag(id))) }
                            else -> Unit
                        }
                    }
                    return json(acknowledgement)
                }
            }
        }
        val root = Files.createTempDirectory("stage9b-adoption-recovery")
        var result: AdoptionResult? = null
        try {
            val service = Drive.Builder(transport, GsonFactory.getDefaultInstance(), null).setApplicationName("Stage9B recovery regression").build()
            fun transfer() = DriveImmutableAssetTransfer(service, "account", root.resolve("state"), root.resolve("staging"),
                operationsFactory = TestPhotoPathOperationsFactory, directoryForce = {})
            val candidate = RemoteAdoptionCandidate("account", "root", oldId, fingerprint, "plan.pdf",
                RemoteReference("folder-1", "file-1", originalProperties), RemoteCursor("r1"))
            suspend fun attempt(selected: RemoteAdoptionCandidate = candidate): AdoptionResult {
                val lease = ScopeRemoteMutationLease().apply { advance(1L) }
                return GoogleDriveGateway(service, "account", transfer()).adopt(AdoptionRequest(scope, selected, fingerprint, 1L, lease, { true }))
            }
            result = attempt()
            if (selection == Selection.DEFINITE_CHANGED) {
                assertTrue(result.toString(), result is AdoptionResult.Rejected)
                result.mutationSession?.close()
                assertNull("a definitively rejected first PUT must retire its no-mutation intent", transfer().readAdoptionRecovery(scope, fingerprint))
                val externalContent = manifestBytes.copyOf()
                val externalProperties = properties.mapValues { it.value.toMap() }
                val writesBeforeRetry = writes.size
                result = attempt(candidate)
                assertTrue("retirement is not permission to retry the stale selection", result is AdoptionResult.Rejected)
                result.mutationSession?.close()
                assertEquals(writesBeforeRetry, writes.size)
                assertArrayEquals(externalContent, manifestBytes)
                assertEquals(externalProperties, properties)
                val selected = candidate.copy(cursor = RemoteCursor("r${revisions.getValue("file-1")}"),
                    reference = candidate.reference.copy(appProperties = properties.getValue("file-1").toMap()))
                result = attempt(selected)
                assertTrue("fresh selection must preserve and adopt the externally updated snapshot: $result", result is AdoptionResult.Adopted)
                assertEquals(snapshot.copy(snapshotRevision = 2L), RemoteManifestCodec.decode(manifestBytes, scope, fingerprint).manifest.snapshot)
                properties.values.forEach { assertEquals(scope.documentId.value, it[SYNC_DOCUMENT_ID_APP_PROPERTY]) }
                assertEquals(selected, transfer().readAdoptionRecovery(scope, fingerprint)?.candidate)
                transfer().acknowledgeAdoptionRecovery(scope, selected, (result as AdoptionResult.Adopted).remote)
                assertNull(transfer().readAdoptionRecovery(scope, fingerprint))
            } else if (fault in setOf(Fault.BEFORE_COMMIT, Fault.BEFORE_EXTERNAL_REVISION, Fault.BEFORE_EXTERNAL_ETAG)) {
                assertTrue(result.toString(), result is AdoptionResult.Rejected)
                result.mutationSession?.close()
                if (noCommitStatus == 412) assertNull(transfer().readAdoptionRecovery(scope, fingerprint))
                else assertNotNull(transfer().readAdoptionRecovery(scope, fingerprint))
                val before = writes.size
                assertArrayEquals(originalBytes, manifestBytes)
                properties.values.forEach { assertEquals(oldId.value, it[SYNC_DOCUMENT_ID_APP_PROPERTY]) }
                if (faultId == "asset-1") assertTrue("compensation must actually have advanced the manifest", revisions.getValue("file-1") > 1)
                if (fault == Fault.BEFORE_EXTERNAL_REVISION) revisions["file-1"] = revisions.getValue("file-1") + 1
                if (fault == Fault.BEFORE_EXTERNAL_ETAG) fileEtagOffset++
                result = attempt()
                if (fault == Fault.BEFORE_COMMIT) assertTrue("verified original/compensated state can retry: $result", result is AdoptionResult.Adopted)
                else {
                    assertTrue("identical bytes are not permission to bypass a newer revision: $result", result is AdoptionResult.Rejected)
                    assertEquals("must reject before a new PUT", before, writes.size)
                    assertArrayEquals(originalBytes, manifestBytes)
                    properties.values.forEach { assertEquals(oldId.value, it[SYNC_DOCUMENT_ID_APP_PROPERTY]) }
                    if (noCommitStatus == 412) assertNull(transfer().readAdoptionRecovery(scope, fingerprint))
                    else assertNotNull(transfer().readAdoptionRecovery(scope, fingerprint))
                    if (selection != null) {
                        result.mutationSession?.close()
                        val retained = transfer().readAdoptionRecovery(scope, fingerprint)
                        var refreshed = candidate.copy(cursor = RemoteCursor("r${revisions.getValue("file-1")}"))
                        when (selection) {
                            Selection.CONTENT_CHANGED -> manifestBytes = RemoteManifestCodec.encode(oldScope, "plan.pdf",
                                snapshot.copy(snapshotRevision = 2L), mapOf("photo.jpg" to descriptor), fingerprint)
                            Selection.OWNER_CHANGED -> properties.getValue("asset-1")[SYNC_DOCUMENT_ID_APP_PROPERTY] = "external-owner"
                            Selection.PARENT_CHANGED -> extraAssetParent = true
                            Selection.WRONG_RESOURCE -> refreshed = refreshed.copy(reference = refreshed.reference.copy(snapshotFileId = "other-file"))
                            Selection.STALE -> refreshed = candidate
                            Selection.INTERRUPTED -> rejectReselectedWrite = true
                            else -> Unit
                        }
                        val preservedBytes = manifestBytes.copyOf()
                        val preservedProperties = properties.mapValues { it.value.toMap() }
                        result = attempt(refreshed)
                        if (selection == Selection.INTERRUPTED) {
                            assertTrue(result.toString(), result is AdoptionResult.Rejected)
                            result.mutationSession?.close()
                            assertEquals(refreshed, transfer().readAdoptionRecovery(scope, fingerprint)?.candidate)
                            val interruptedWrites = writes.size
                            result = attempt(candidate)
                            assertTrue("retired selection cannot recover the newly authorized intent", result is AdoptionResult.Rejected)
                            result.mutationSession?.close()
                            assertEquals(interruptedWrites, writes.size)
                            result = attempt(refreshed)
                        }
                        if (selection in setOf(Selection.ACCEPT, Selection.INTERRUPTED)) {
                            assertTrue("fresh authorized selection of exact original state must proceed: $result", result is AdoptionResult.Adopted)
                            properties.values.forEach { assertEquals(scope.documentId.value, it[SYNC_DOCUMENT_ID_APP_PROPERTY]) }
                            assertEquals(snapshot, RemoteManifestCodec.decode(manifestBytes, scope, fingerprint).manifest.snapshot)
                            assertEquals(refreshed, transfer().readAdoptionRecovery(scope, fingerprint)?.candidate)
                            transfer().acknowledgeAdoptionRecovery(scope, refreshed, (result as AdoptionResult.Adopted).remote)
                            assertNull(transfer().readAdoptionRecovery(scope, fingerprint))
                        } else {
                            assertTrue("unsafe reselection must fail closed: $result", result is AdoptionResult.Rejected)
                            assertEquals(before, writes.size)
                            assertEquals(retained, transfer().readAdoptionRecovery(scope, fingerprint))
                            assertArrayEquals(preservedBytes, manifestBytes)
                            assertEquals(preservedProperties, properties)
                        }
                    }
                }
            } else if (fault in setOf(Fault.OUTAGE, Fault.OUTAGE_EXTERNAL, Fault.OUTAGE_EXTRA_PARENT)) {
                assertTrue(result.toString(), result is AdoptionResult.Rejected)
                result.mutationSession?.close()
                assertNotNull("durable recovery must survive loss of the gateway and every readback", transfer().readAdoptionRecovery(scope, fingerprint))
                val before = writes.size
                result = attempt()
                assertTrue(result is AdoptionResult.Rejected)
                result.mutationSession?.close()
                assertEquals("offline retry cannot blindly replay any PUT", before, writes.size)
                outage = false
                if (selection == Selection.PARTIAL) {
                    val retained = transfer().readAdoptionRecovery(scope, fingerprint)
                    result = attempt(candidate.copy(cursor = RemoteCursor("r${revisions.getValue("file-1")}")))
                    assertTrue("a partial or completed unacknowledged adoption must retain its original intent", result is AdoptionResult.Rejected)
                    result.mutationSession?.close()
                    assertEquals(before, writes.size)
                    assertEquals(retained, transfer().readAdoptionRecovery(scope, fingerprint))
                }
                if (fault == Fault.OUTAGE_EXTERNAL) {
                    properties.getValue(faultId)[SYNC_DOCUMENT_ID_APP_PROPERTY] = "external-owner"
                    revisions[faultId] = revisions.getValue(faultId) + 1
                }
                if (fault == Fault.OUTAGE_EXTRA_PARENT) {
                    extraAssetParent = true
                    revisions["asset-1"] = revisions.getValue("asset-1") + 1
                }
                result = attempt()
                if (fault in setOf(Fault.OUTAGE_EXTERNAL, Fault.OUTAGE_EXTRA_PARENT)) {
                    assertTrue(result.toString(), result is AdoptionResult.Rejected)
                    assertEquals(before, writes.size)
                    if (fault == Fault.OUTAGE_EXTERNAL) assertEquals("external-owner", properties.getValue(faultId)[SYNC_DOCUMENT_ID_APP_PROPERTY])
                    else assertTrue(extraAssetParent)
                    assertNotNull(transfer().readAdoptionRecovery(scope, fingerprint))
                } else {
                    assertTrue("a new gateway must reconcile the durable intent: $result", result is AdoptionResult.Adopted)
                    properties.values.forEach { assertEquals(scope.documentId.value, it[SYNC_DOCUMENT_ID_APP_PROPERTY]) }
                    assertEquals(snapshot, RemoteManifestCodec.decode(manifestBytes, scope, fingerprint).manifest.snapshot)
                    assertNotNull(transfer().readAdoptionRecovery(scope, fingerprint))
                    transfer().acknowledgeAdoptionRecovery(scope, candidate, (result as AdoptionResult.Adopted).remote)
                    assertNull(transfer().readAdoptionRecovery(scope, fingerprint))
                }
            } else if (fault == Fault.NONE) {
                assertTrue(result.toString(), result is AdoptionResult.Adopted)
            } else {
                assertTrue("external changes must not be reported adopted: $result", result is AdoptionResult.Rejected)
                if (fault == Fault.FINAL_MANIFEST) assertArrayEquals(externalBytes, manifestBytes)
                if (fault == Fault.FINAL_FOLDER) assertEquals("external-owner", properties.getValue("folder-1")[SYNC_DOCUMENT_ID_APP_PROPERTY])
                if (fault == Fault.FINAL_ASSET) assertEquals("0".repeat(64), assetHash)
                if (fault == Fault.ASSET_PARENT) assertEquals("external-folder", assetParent)
                if (fault in setOf(Fault.INITIAL_EXTRA_PARENT, Fault.ASSET_EXTRA_PARENT)) assertTrue(extraAssetParent)
                if (fault == Fault.INITIAL_EXTRA_PARENT) assertTrue("preflight must reject before writing", writes.isEmpty())
            }
        } finally { result?.mutationSession?.close(); check(root.toFile().deleteRecursively()) }
    }
}
