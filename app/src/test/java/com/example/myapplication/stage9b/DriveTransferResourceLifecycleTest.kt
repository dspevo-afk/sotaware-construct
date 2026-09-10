package com.example.myapplication.stage9b

import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage5.PhotoPathOperations
import com.example.myapplication.stage5.PhotoPathOperationsFactory
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import com.example.myapplication.stage5.validatePhotoBytes
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** Focused ownership checks for operation-scoped Drive transfer descriptors. */
class DriveTransferResourceLifecycleTest {
    @Test
    fun constructorAdmissionClosesBothRootsAndSecondRootFailureClosesFirst() {
        val root = Files.createTempDirectory("stage9b-transfer-admission")
        try {
            val successFactory = CountingOperationsFactory()
            newTransfer(root, successFactory)
            assertEquals(2, successFactory.opened.get())
            assertEquals(2, successFactory.closed.get())

            val failedFactory = CountingOperationsFactory(root.resolve("failed").resolve("staging"))
            assertThrows(IOException::class.java) {
                newTransfer(root.resolve("failed"), failedFactory)
            }
            assertEquals(2, failedFactory.opened.get())
            assertEquals(1, failedFactory.closed.get())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun completedAndFailedOperationsBalanceRealResolverLifetimes() = runBlocking {
        val (bytes, photoDescriptor) = validPhoto()
        val scope = SyncScope("account", "root", DocumentId.new())
        val root = Files.createTempDirectory("stage9b-transfer-lifetime")
        val factory = CountingOperationsFactory()
        try {
            val transfer = newTransfer(root, factory, AssetTransport(scope, bytes, photoDescriptor.sha256))
            val descriptor = RemoteAssetDescriptor(
                "asset-1",
                bytes.size.toLong(),
                photoDescriptor.sha256,
                photoDescriptor.mimeType,
                photoDescriptor.width,
                photoDescriptor.height
            )
            transfer.clearState(scope, null, "a".repeat(64), "folder")
            assertEquals(factory.opened.get(), factory.closed.get())
            assertThrows(DriveAssetStaleGenerationException::class.java) {
                transfer.reserveResourceId(scope, null, "folder", "document-folder") { false }
            }
            assertEquals(factory.opened.get(), factory.closed.get())

            val assets = transfer.download(scope, "folder", mapOf("photo.jpg" to descriptor))
            val owner = requireNotNull(transfer.ownershipFor(assets))
            assertEquals(factory.opened.get(), factory.closed.get() + 1)
            val stream = assets.getValue("photo.jpg").open()
            owner.release()
            assertEquals(factory.opened.get(), factory.closed.get() + 1)
            assertTrue(root.resolve("staging").toFile().listFiles()?.isNotEmpty() == true)
            stream.close()
            assertEquals(factory.opened.get(), factory.closed.get())
            transfer.discardDownloadedAssets(assets)
            assertEquals(factory.opened.get(), factory.closed.get())

            val badDescriptor = descriptor.copy(sha256 = "0".repeat(64))
            val failure = try {
                transfer.download(scope, "folder", mapOf("bad.jpg" to badDescriptor))
                null
            } catch (error: Throwable) {
                error
            }
            assertTrue(failure is DriveAssetTransferException)
            assertEquals(factory.opened.get(), factory.closed.get())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun newTransfer(
        root: Path,
        operationsFactory: PhotoPathOperationsFactory,
        transport: MockHttpTransport = MockHttpTransport()
    ): DriveImmutableAssetTransfer = DriveImmutableAssetTransfer(
        requestFactory = transport.createRequestFactory(),
        apiBaseUrl = "https://www.googleapis.com/drive/v3/",
        accountId = "account",
        stateDirectory = root.resolve("state"),
        stagingDirectory = root.resolve("staging"),
        operationsFactory = operationsFactory,
        directoryForce = {}
    )

    private fun validPhoto(): Pair<ByteArray, com.example.myapplication.stage5.PhotoDescriptor> {
        val validated = validatePhotoBytes(Stage4PhotoFixture.previousJpegBytes())
        return validated.bytes to validated.descriptor
    }

    private class CountingOperationsFactory(
        private val failRoot: Path? = null
    ) : PhotoPathOperationsFactory {
        val opened = AtomicInteger()
        val closed = AtomicInteger()

        override fun open(root: Path): PhotoPathOperations {
            opened.incrementAndGet()
            if (failRoot?.toAbsolutePath()?.normalize() == root.toAbsolutePath().normalize()) {
                throw IOException("injected second-root admission failure")
            }
            val delegate = TestPhotoPathOperationsFactory.open(root)
            return object : PhotoPathOperations {
                override fun exists(name: String): Boolean = delegate.exists(name)
                override fun isRegularFile(name: String): Boolean = delegate.isRegularFile(name)
                override fun size(name: String): Long = delegate.size(name)
                override fun openRead(name: String): InputStream = delegate.openRead(name)
                override fun openNewOutput(name: String): FileChannel = delegate.openNewOutput(name)
                override fun move(source: String, target: String, replaceExisting: Boolean) =
                    delegate.move(source, target, replaceExisting)
                override fun delete(name: String) = delegate.delete(name)
                override fun close() {
                    closed.incrementAndGet()
                    delegate.close()
                }
            }
        }
    }

    private class AssetTransport(
        private val scope: SyncScope,
        private val bytes: ByteArray,
        private val hash: String
    ) : MockHttpTransport() {
        override fun buildRequest(method: String, url: String): LowLevelHttpRequest =
            object : MockLowLevelHttpRequest(url) {
                override fun execute(): LowLevelHttpResponse {
                    return when {
                        method == "GET" && url.contains("alt=media") ->
                            MockLowLevelHttpResponse().setStatusCode(200).setContent(bytes)
                        method == "GET" && url.contains("/files/asset-1") ->
                            MockLowLevelHttpResponse().setStatusCode(200)
                                .setContentType("application/json")
                                .setContent(metadata())
                        else -> MockLowLevelHttpResponse().setStatusCode(404).setZeroContent()
                    }
                }
            }

        private fun metadata(): String =
            """{"id":"asset-1","name":"sotaware-asset-$hash.jpg","mimeType":"image/jpeg","size":"${bytes.size}","sha256Checksum":"$hash","parents":["folder"],"appProperties":{"sotaware_account_id":"${scope.accountId}","sotaware_backup_root_id":"${scope.backupRootId}","sotaware_document_id":"${scope.documentId.value}","sotaware_manifest_schema":"$DRIVE_MANIFEST_SCHEMA_VERSION","sotaware_asset_sha256":"$hash","sotaware_immutable_asset":"1"}}"""
    }
}
