package com.example.myapplication.stage9b

import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.DRIVE_PAYLOAD_SCHEMA_VERSION
import com.example.myapplication.stage4.DownloadResult
import com.example.myapplication.stage4.GoogleDriveGateway
import com.example.myapplication.stage4.RemoteCursor
import com.example.myapplication.stage4.RemoteReference
import com.example.myapplication.stage4.SYNC_DOCUMENT_ID_APP_PROPERTY
import com.example.myapplication.stage4.SYNC_SCHEMA_APP_PROPERTY
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.api.services.drive.Drive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Real Google adapter boundary coverage using only deterministic HTTP. */
class DriveGatewayDurabilityIntegrationTest {
    @Test
    fun malformedImmutableAssetFailsBeforeAnyRemoteMutation() = runBlocking {
        val scope = SyncScope("account", "root", DocumentId.new())
        val bytes = "remote-photo".toByteArray()
        val hash = sha256(bytes)
        val descriptor = RemoteAssetDescriptor("asset-1", bytes.size.toLong(), hash, "image/jpeg", 1, 1)
        val snapshot = DocumentSnapshotV1(
            schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
            snapshotRevision = 0L,
            source = DocumentSourceIdentityV1("content://remote/source", "plan.pdf"),
            pages = mapOf(
                0 to PageSnapshotV1(
                    photoPins = listOf(
                        PhotoPinSnapshotV1(
                            x = .5f,
                            y = .5f,
                            id = "pin-1",
                            imageFileNames = listOf("photo.jpg"),
                            imageNotes = emptyMap(),
                            imageShapes = emptyMap()
                        )
                    )
                )
            )
        )
        val manifest = RemoteManifestCodec.encode(
            scope = scope,
            displayName = "plan.pdf",
            snapshot = snapshot,
            assets = mapOf("photo.jpg" to descriptor)
        ).toString(Charsets.UTF_8)
        val writes = AtomicInteger()
        fun folderJson() = """{"id":"folder-1","name":"plan.pdf","parents":["root"],"appProperties":{"$SYNC_DOCUMENT_ID_APP_PROPERTY":"${scope.documentId.value}","$SYNC_SCHEMA_APP_PROPERTY":"$DRIVE_PAYLOAD_SCHEMA_VERSION","sotaware_account_id":"account","sotaware_backup_root_id":"root"}}"""
        fun fileJson() = """{"id":"file-1","name":"annotations.json","parents":["folder-1"],"appProperties":{"$SYNC_DOCUMENT_ID_APP_PROPERTY":"${scope.documentId.value}","$SYNC_SCHEMA_APP_PROPERTY":"$DRIVE_PAYLOAD_SCHEMA_VERSION","sotaware_account_id":"account","sotaware_backup_root_id":"root"},"headRevisionId":"r1"}"""
        fun malformedAssetJson() = """{"id":"asset-1","name":"sotaware-asset-$hash.jpg","mimeType":"image/jpeg","size":"${bytes.size}","sha256Checksum":"$hash","parents":["folder-1"],"appProperties":{"sotaware_account_id":"account","sotaware_backup_root_id":"root","sotaware_document_id":"${scope.documentId.value}","sotaware_manifest_schema":"$DRIVE_PAYLOAD_SCHEMA_VERSION","sotaware_asset_sha256":"${"0".repeat(64)}","sotaware_immutable_asset":"1"}}"""
        val transport = object : MockHttpTransport() {
            override fun buildRequest(method: String, url: String): LowLevelHttpRequest =
                object : MockLowLevelHttpRequest(url) {
                    override fun execute(): LowLevelHttpResponse {
                        if (method != "GET") writes.incrementAndGet()
                        val body = when {
                            method == "GET" && url.contains("/files/asset-1") -> malformedAssetJson()
                            method == "GET" && url.contains("/files/folder-1") -> folderJson()
                            method == "GET" && url.contains("/files/file-1") && url.contains("alt=media") -> manifest
                            method == "GET" && url.contains("/files/file-1") -> fileJson()
                            else -> return MockLowLevelHttpResponse().setStatusCode(404).setZeroContent()
                        }
                        return MockLowLevelHttpResponse().setStatusCode(200)
                            .setContentType("application/json").setContent(body)
                    }
                }
        }
        val root = Files.createTempDirectory("stage9b-gateway-malformed-asset")
        try {
            val service = Drive.Builder(transport, GsonFactory.getDefaultInstance(), null)
                .setApplicationName("Stage 9B malformed asset test")
                .setRootUrl("https://www.googleapis.com/")
                .setServicePath("drive/v3/")
                .build()
            val transfer = DriveImmutableAssetTransfer(
                service = service,
                accountId = scope.accountId,
                stateDirectory = root.resolve("state"),
                stagingDirectory = root.resolve("staging"),
                operationsFactory = TestPhotoPathOperationsFactory
            )
            val gateway = GoogleDriveGateway(service, scope.accountId, transfer)
            val reference = RemoteReference(
                folderId = "folder-1",
                snapshotFileId = "file-1",
                appProperties = mapOf(SYNC_DOCUMENT_ID_APP_PROPERTY to scope.documentId.value)
            )
            val result = gateway.download(scope, reference, RemoteCursor("r1"))
            assertTrue(result is DownloadResult.Failed)
            assertEquals(0, writes.get())
            assertEquals(0, root.resolve("staging").toFile().listFiles()?.size ?: 0)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
