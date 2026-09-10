package com.example.myapplication.stage9b

import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage5.TestPhotoPathOperations
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import com.example.myapplication.stage5.PhotoDescriptor
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.gson.GsonBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Focused regressions for the bounded Drive transport durability seam. */
class DriveDurabilityRegressionTest {
    @Test
    fun reservationIsDurableAndDuplicateCallersReuseOneGeneratedId() {
        val root = Files.createTempDirectory("stage9b-reservation")
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val generated = AtomicInteger()
            val forcedDirectories = AtomicInteger()
            val transport = transport { method, url ->
                if (method == "GET" && url.contains("generateIds")) {
                    generated.incrementAndGet()
                    json("{\"ids\":[\"folder-1\"]}")
                } else {
                    MockLowLevelHttpResponse().setStatusCode(404).setZeroContent()
                }
            }
            val first = newTransfer(transport, scope, root) { forcedDirectories.incrementAndGet() }
            val firstId = first.reserveResourceId(scope, null, "root", "document-folder") { true }
            val secondId = first.reserveResourceId(scope, null, "root", "document-folder") { true }
            assertEquals("folder-1", firstId)
            assertEquals(firstId, secondId)
            assertEquals(1, generated.get())

            val recreated = newTransfer(transport, scope, root)
            assertEquals(firstId, recreated.reserveResourceId(scope, null, "root", "document-folder") { true })
            assertEquals(1, generated.get())
            assertTrue(forcedDirectories.get() > 0)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun ambiguousReservationReadbackRetainsIdForRecreation() {
        val root = Files.createTempDirectory("stage9b-reservation-readback")
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val generated = AtomicInteger()
            val failReadback = AtomicBoolean(true)
            val factory = FailReservationReadbackFactory(failReadback)
            val transport = transport { method, url ->
                if (method == "GET" && url.contains("generateIds")) {
                    generated.incrementAndGet()
                    json("{\"ids\":[\"folder-ambiguous\"]}")
                } else MockLowLevelHttpResponse().setStatusCode(404).setZeroContent()
            }
            val first = newTransfer(transport, scope, root, factory)
            try {
                first.reserveResourceId(scope, null, "root", "document-folder") { true }
                throw AssertionError("expected ambiguous read-back failure")
            } catch (_: DriveAssetTransferException) {
                // The atomic publication may have succeeded; the record must
                // remain available rather than triggering another ID request.
            }
            val recreated = newTransfer(transport, scope, root, factory)
            assertEquals(
                "folder-ambiguous",
                recreated.reserveResourceId(scope, null, "root", "document-folder") { true }
            )
            assertEquals(1, generated.get())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun directoryForceFailureAfterPublicationRetainsReservationForReopen() {
        val root = Files.createTempDirectory("stage9b-reservation-force")
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val generated = AtomicInteger()
            val transport = transport { method, url ->
                if (method == "GET" && url.contains("generateIds")) {
                    generated.incrementAndGet()
                    json("{\"ids\":[\"folder-forced\"]}")
                } else MockLowLevelHttpResponse().setStatusCode(404).setZeroContent()
            }
            val failForce = AtomicBoolean(true)
            val first = newTransfer(transport, scope, root) {
                if (failForce.compareAndSet(true, false)) throw IOException("injected directory fence failure")
            }
            assertThrows(DriveAssetTransferException::class.java) {
                first.reserveResourceId(scope, null, "root", "document-folder") { true }
            }
            val reopened = newTransfer(transport, scope, root)
            assertEquals(
                "folder-forced",
                reopened.reserveResourceId(scope, null, "root", "document-folder") { true }
            )
            assertEquals(1, generated.get())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun parentFolderIsPartOfStateAndReservationScope() {
        val root = Files.createTempDirectory("stage9b-parent-scope")
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val store = DriveTransferStateStore(
                stateDirectory = root.resolve("state"),
                accountId = scope.accountId,
                nowMillis = { 1L },
                gson = GsonBuilder().disableHtmlEscaping().create(),
                operationsFactory = TestPhotoPathOperationsFactory,
                directoryForce = {}
            )
            store.use {
                val first = it.identity(scope, null, "a".repeat(64), "folder-a")
                val descriptor = PhotoDescriptor(3L, sha256("abc".toByteArray()), "image/jpeg", 1, 1)
                it.putAsset(
                    first,
                    descriptor.sha256,
                    PersistedAssetState("asset-1", 3L, descriptor.sha256, descriptor.mimeType, 1, 1)
                )
                val otherFolder = it.identity(scope, null, "a".repeat(64), "folder-b")
                assertNull(it.load(otherFolder))
            }

            val generated = AtomicInteger()
            val transport = transport { method, url ->
                if (method == "GET" && url.contains("generateIds")) {
                    val id = "folder-${generated.incrementAndGet()}"
                    json("{\"ids\":[\"$id\"]}")
                } else MockLowLevelHttpResponse().setStatusCode(404).setZeroContent()
            }
            val transfer = newTransfer(transport, scope, root)
            val firstId = transfer.reserveResourceId(scope, null, "folder-a", "document-folder") { true }
            val secondId = transfer.reserveResourceId(scope, null, "folder-b", "document-folder") { true }
            assertFalse(firstId == secondId)
            assertEquals(2, generated.get())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun redirectIsRejectedWithoutASecondAuthenticatedRequest() = runBlocking {
        val root = Files.createTempDirectory("stage9b-redirect")
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val requests = AtomicInteger()
            val transport = transport { method, url ->
                requests.incrementAndGet()
                if (method == "GET" && url.contains("generateIds")) {
                    MockLowLevelHttpResponse().setStatusCode(302)
                        .addHeader("Location", "https://attacker.invalid/collect")
                        .setZeroContent()
                } else MockLowLevelHttpResponse().setStatusCode(404).setZeroContent()
            }
            val transfer = newTransfer(transport, scope, root)
            try {
                transfer.upload(
                    scope,
                    null,
                    "b".repeat(64),
                    "folder",
                    PhotoAssetSet.of(mapOf("photo.jpg" to byteAsset("photo".toByteArray())))
                )
                throw AssertionError("expected redirect rejection")
            } catch (failure: DriveAssetTransferException) {
                assertTrue(failure.message.orEmpty().contains("ID generation failed") || failure.message.orEmpty().contains("redirect"))
            }
            assertEquals(1, requests.get())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun downloadedSourceRemainsUsableUntilLastStreamClosesThenOwnedFileIsRemoved() = runBlocking {
        val root = Files.createTempDirectory("stage9b-download-cleanup")
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val bytes = "downloaded-photo".toByteArray()
            val hash = sha256(bytes)
            val descriptor = RemoteAssetDescriptor("asset-1", bytes.size.toLong(), hash, "image/jpeg", 1, 1)
            val transport = transport { method, url ->
                when {
                    method == "GET" && url.contains("/files/asset-1") && !url.contains("alt=media") ->
                        json(metadata(scope, descriptor, "folder"))
                    method == "GET" && url.contains("alt=media") ->
                        MockLowLevelHttpResponse().setStatusCode(200).setContent(bytes)
                    else -> MockLowLevelHttpResponse().setStatusCode(404).setZeroContent()
                }
            }
            val transfer = newTransfer(transport, scope, root)
            val assets = transfer.download(scope, "folder", mapOf("photo.jpg" to descriptor))
            val stream = assets.getValue("photo.jpg").open()
            val actual = ByteArray(bytes.size)
            var offset = 0
            while (offset < actual.size) {
                val read = stream.read(actual, offset, actual.size - offset)
                if (read < 0) throw AssertionError("downloaded stream ended early")
                offset += read
            }
            assertArrayEquals(bytes, actual)
            transfer.discardDownloadedAssets(assets)
            val stagedDirectory = root.resolve("staging")
            assertNotNull(stagedDirectory.toFile().listFiles()?.firstOrNull())
            stream.close()
            // Closing the last counted stream releases the owned staging file.
            assertEquals(0, stagedDirectory.toFile().listFiles()?.size ?: 0)
            transfer.discardDownloadedAssets(assets)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun durableStorageRejectsTraversalAndDoesNotCreateOutsideRoot() {
        val root = Files.createTempDirectory("stage9b-secure-storage")
        try {
            DurableDriveTransferStorage(
                directory = root,
                operationsFactory = TestPhotoPathOperationsFactory,
                directoryForce = {}
            ).use { storage ->
                assertThrows(IllegalArgumentException::class.java) {
                    storage.write("../escape", byteArrayOf(1), maxPayloadBytes = 16)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun newTransfer(
        transport: MockHttpTransport,
        scope: SyncScope,
        root: Path,
        operationsFactory: com.example.myapplication.stage5.PhotoPathOperationsFactory = TestPhotoPathOperationsFactory,
        directoryForce: () -> Unit = {}
    ): DriveImmutableAssetTransfer = DriveImmutableAssetTransfer(
        requestFactory = transport.createRequestFactory(),
        apiBaseUrl = "https://www.googleapis.com/drive/v3/",
        accountId = scope.accountId,
        stateDirectory = root.resolve("state"),
        stagingDirectory = root.resolve("staging"),
        operationsFactory = operationsFactory,
        directoryForce = directoryForce
    )

    private fun transport(handler: (String, String) -> LowLevelHttpResponse): MockHttpTransport =
        object : MockHttpTransport() {
            override fun buildRequest(method: String, url: String): LowLevelHttpRequest =
                object : MockLowLevelHttpRequest(url) {
                    override fun execute(): LowLevelHttpResponse = handler(method, url)
                }
        }

    private fun json(body: String): MockLowLevelHttpResponse = MockLowLevelHttpResponse()
        .setStatusCode(200)
        .setContentType("application/json")
        .setContent(body)

    private fun metadata(scope: SyncScope, descriptor: RemoteAssetDescriptor, parent: String): String =
        """{"id":"${descriptor.remoteAssetId}","name":"sotaware-asset-${descriptor.sha256}.jpg","mimeType":"${descriptor.mimeType}","sha256Checksum":"${descriptor.sha256}","parents":["$parent"],"appProperties":{"sotaware_account_id":"${scope.accountId}","sotaware_backup_root_id":"${scope.backupRootId}","sotaware_document_id":"${scope.documentId.value}","sotaware_manifest_schema":"3","sotaware_asset_sha256":"${descriptor.sha256}","sotaware_immutable_asset":"1"}}"""

    private fun byteAsset(bytes: ByteArray): PhotoAsset {
        val copy = bytes.copyOf()
        val descriptor = PhotoDescriptor(copy.size.toLong(), sha256(copy), "image/jpeg", 1, 1)
        return object : PhotoAsset {
            override val descriptor = descriptor
            override fun open(): InputStream = ByteArrayInputStream(copy.copyOf())
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Fails exactly one reservation slot read after the atomic move. */
    private class FailReservationReadbackFactory(
        private val failReadback: AtomicBoolean
    ) : com.example.myapplication.stage5.PhotoPathOperationsFactory {
        override fun open(root: Path): com.example.myapplication.stage5.PhotoPathOperations {
            val delegate = TestPhotoPathOperations(root)
            return object : com.example.myapplication.stage5.PhotoPathOperations {
                override fun exists(name: String): Boolean = delegate.exists(name)
                override fun isRegularFile(name: String): Boolean = delegate.isRegularFile(name)
                override fun size(name: String): Long = delegate.size(name)
                override fun openRead(name: String): InputStream {
                    if (name == ".resource-reservations.a" && failReadback.compareAndSet(true, false)) {
                        throw IOException("injected reservation read-back ambiguity")
                    }
                    return delegate.openRead(name)
                }
                override fun openNewOutput(name: String): FileChannel = delegate.openNewOutput(name)
                override fun move(source: String, target: String, replaceExisting: Boolean) =
                    delegate.move(source, target, replaceExisting)
                override fun delete(name: String) = delegate.delete(name)
                override fun close() = delegate.close()
            }
        }
    }
}
