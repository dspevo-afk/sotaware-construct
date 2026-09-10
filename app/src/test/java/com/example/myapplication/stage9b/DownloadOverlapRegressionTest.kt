package com.example.myapplication.stage9b

import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage5.PhotoDescriptor
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Regressions for per-attempt download and verification staging ownership. */
class DownloadOverlapRegressionTest {
    @Test
    fun releasingOneOverlappingDownloadCannotDeleteAnotherOwnersOpenBytes() = runBlocking {
        val bytes = realPhotoBytes()
        val scope = SyncScope("account", "root", DocumentId.new())
        val descriptor = photoDescriptor(bytes)
        val firstMediaStarted = CountDownLatch(1)
        val mediaStarted = CountDownLatch(2)
        val mediaGate = CountDownLatch(1)
        val transport = transport { method, url ->
            when {
                method == "GET" && url.contains("alt=media") -> {
                    firstMediaStarted.countDown()
                    mediaStarted.countDown()
                    check(mediaGate.await(10, TimeUnit.SECONDS)) { "media gate was not released" }
                    MockLowLevelHttpResponse().setStatusCode(200).setContent(bytes)
                }
                method == "GET" && url.contains("/files/asset-1") ->
                    json(metadata(scope, descriptor, "folder"))
                else -> MockLowLevelHttpResponse().setStatusCode(404).setZeroContent()
            }
        }
        val root = Files.createTempDirectory("stage9b-download-overlap-release")
        try {
            val transfer = newTransfer(transport, scope, root)
            val descriptors = mapOf("photo.jpg" to remoteDescriptor(descriptor))
            val first = async(Dispatchers.IO) {
                transfer.download(scope, "folder", descriptors)
            }
            assertTrue(firstMediaStarted.await(10, TimeUnit.SECONDS))
            val second = async(Dispatchers.IO) {
                transfer.download(scope, "folder", descriptors)
            }
            assertTrue(mediaStarted.await(10, TimeUnit.SECONDS))
            mediaGate.countDown()

            val firstAssets = first.await()
            val secondAssets = second.await()
            val firstOwner = requireNotNull(transfer.ownershipFor(firstAssets))
            val secondOwner = requireNotNull(transfer.ownershipFor(secondAssets))
            val firstStream = firstAssets.getValue("photo.jpg").open()
            val secondStream = secondAssets.getValue("photo.jpg").open()

            assertEquals(2, root.resolve("staging").toFile().listFiles()?.size ?: 0)
            firstOwner.release()
            assertTrue(firstOwner.isReleased)
            assertFalse(secondOwner.isReleased)

            // Close the first owner while the other owner's stream remains open.
            assertArrayEquals(bytes, firstStream.readBytes())
            firstStream.close()
            assertArrayEquals(bytes, secondStream.readBytes())
            secondStream.close()

            secondOwner.release()
            transfer.discardDownloadedAssets(firstAssets)
            transfer.discardDownloadedAssets(secondAssets)
            assertEquals(0, root.resolve("staging").toFile().listFiles()?.size ?: 0)
        } finally {
            mediaGate.countDown()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedDownloadNeverSweepsAValidPreexistingStagingFile() = runBlocking {
        val bytes = realPhotoBytes()
        val scope = SyncScope("account", "root", DocumentId.new())
        val descriptor = photoDescriptor(bytes)
        val transport = transport { method, url ->
            when {
                method == "GET" && url.contains("alt=media") ->
                    MockLowLevelHttpResponse().setStatusCode(503).setZeroContent()
                method == "GET" && url.contains("/files/asset-1") ->
                    json(metadata(scope, descriptor, "folder"))
                else -> MockLowLevelHttpResponse().setStatusCode(404).setZeroContent()
            }
        }
        val root = Files.createTempDirectory("stage9b-download-preexisting")
        try {
            val transfer = newTransfer(transport, scope, root)
            val sentinel = root.resolve("staging").resolve(".user-owned.part")
            val sentinelBytes = "keep-this-file".toByteArray()
            Files.write(sentinel, sentinelBytes)

            val failure = try {
                transfer.download(scope, "folder", mapOf("photo.jpg" to remoteDescriptor(descriptor)))
                null
            } catch (error: Throwable) {
                error
            }
            assertTrue(failure is DriveAssetTransferException)
            assertArrayEquals(sentinelBytes, Files.readAllBytes(sentinel))
            assertEquals(1, root.resolve("staging").toFile().listFiles()?.size ?: 0)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cancellationOfOneOverlappingDownloadLeavesOtherOwnerOpenAndValid() = runBlocking {
        val bytes = realPhotoBytes()
        val scope = SyncScope("account", "root", DocumentId.new())
        val descriptor = photoDescriptor(bytes)
        val firstReadBlocked = CountDownLatch(1)
        val releaseFirstRead = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val mediaCalls = AtomicInteger()
        val transport = transport { method, url ->
            when {
                method == "GET" && url.contains("alt=media") -> {
                    if (mediaCalls.incrementAndGet() == 1) {
                        MockLowLevelHttpResponse().setStatusCode(200).setContent(
                            CancellationAwareInputStream(
                                bytes,
                                firstReadBlocked,
                                releaseFirstRead,
                                cancelled
                            )
                        )
                    } else {
                        MockLowLevelHttpResponse().setStatusCode(200).setContent(bytes)
                    }
                }
                method == "GET" && url.contains("/files/asset-1") ->
                    json(metadata(scope, descriptor, "folder"))
                else -> MockLowLevelHttpResponse().setStatusCode(404).setZeroContent()
            }
        }
        val root = Files.createTempDirectory("stage9b-download-overlap-cancel")
        try {
            val transfer = newTransfer(transport, scope, root)
            val descriptors = mapOf("photo.jpg" to remoteDescriptor(descriptor))
            val first = async(Dispatchers.IO) {
                transfer.download(scope, "folder", descriptors)
            }
            assertTrue(firstReadBlocked.await(10, TimeUnit.SECONDS))

            // The first request has a real partial file open before the second
            // request starts. The second owner must not depend on that path.
            val second = async(Dispatchers.IO) {
                transfer.download(scope, "folder", descriptors)
            }
            val secondAssets = second.await()
            val secondOwner = requireNotNull(transfer.ownershipFor(secondAssets))
            val secondStream = secondAssets.getValue("photo.jpg").open()

            cancelled.set(true)
            first.cancel()
            releaseFirstRead.countDown()
            val firstFailure = try {
                first.await()
                null
            } catch (error: Throwable) {
                first.join()
                error
            }
            assertTrue(firstFailure is CancellationException)
            assertFalse(secondOwner.isReleased)
            assertArrayEquals(bytes, secondStream.readBytes())
            secondStream.close()

            secondOwner.release()
            transfer.discardDownloadedAssets(secondAssets)
            assertEquals(0, root.resolve("staging").toFile().listFiles()?.size ?: 0)
        } finally {
            cancelled.set(true)
            releaseFirstRead.countDown()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedVerificationDoesNotDeleteOverlappingSuccessfulScratch() = runBlocking {
        val bytes = realPhotoBytes()
        val badBytes = bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val scope = SyncScope("account", "root", DocumentId.new())
        val descriptor = photoDescriptor(bytes)
        val firstMediaStarted = CountDownLatch(1)
        val secondPartialReady = CountDownLatch(1)
        val releaseFirstMedia = CountDownLatch(1)
        val releaseSecondMedia = CountDownLatch(1)
        val mediaCalls = AtomicInteger()
        val transport = transport { method, url ->
            when {
                method == "GET" && url.contains("alt=media") -> {
                    when (mediaCalls.incrementAndGet()) {
                        1 -> {
                            firstMediaStarted.countDown()
                            check(releaseFirstMedia.await(10, TimeUnit.SECONDS)) {
                                "first verification media gate was not released"
                            }
                            MockLowLevelHttpResponse().setStatusCode(200).setContent(badBytes)
                        }
                        else -> MockLowLevelHttpResponse().setStatusCode(200).setContent(
                            PartialThenGateInputStream(
                                bytes,
                                secondPartialReady,
                                releaseSecondMedia
                            )
                        )
                    }
                }
                method == "GET" && url.contains("generateIds") ->
                    MockLowLevelHttpResponse().setStatusCode(503).setZeroContent()
                method == "GET" && url.contains("/files/asset-1") ->
                    json(metadataWithoutOutputDigest(scope, descriptor, "folder"))
                else -> MockLowLevelHttpResponse().setStatusCode(404).setZeroContent()
            }
        }
        val root = Files.createTempDirectory("stage9b-verification-overlap")
        try {
            supervisorScope {
                val transfer = newTransfer(transport, scope, root)
                val assets = PhotoAssetSet.of(mapOf("photo.jpg" to byteAsset(bytes)))
                val existing = mapOf(
                    "photo.jpg" to RemoteAssetDescriptor(
                        "asset-1", descriptor.byteCount, descriptor.sha256,
                        descriptor.mimeType, descriptor.width, descriptor.height
                    )
                )
                val first = async(Dispatchers.IO) {
                    transfer.upload(scope, null, "a".repeat(64), "folder", assets, existing)
                }
                assertTrue(firstMediaStarted.await(10, TimeUnit.SECONDS))
                val second = async(Dispatchers.IO) {
                    transfer.upload(scope, null, "a".repeat(64), "folder", assets, existing)
                }
                // Ensure the successful request owns a partial verification file
                // while the first request is failing and cleaning its own scratch.
                assertTrue(secondPartialReady.await(10, TimeUnit.SECONDS))
                releaseFirstMedia.countDown()
                val firstFailure = try {
                    first.await()
                    null
                } catch (error: Throwable) {
                    error
                }
                assertTrue(firstFailure is DriveAssetTransferException)

                releaseSecondMedia.countDown()
                val result = second.await()
                assertEquals("asset-1", result.descriptors.getValue("photo.jpg").remoteAssetId)
                assertEquals(0L, result.uploadedBytes)
                assertEquals(bytes.size.toLong(), result.reusedBytes)
                assertEquals(0, root.resolve("staging").toFile().listFiles()?.size ?: 0)
            }
        } finally {
            releaseFirstMedia.countDown()
            releaseSecondMedia.countDown()
            root.toFile().deleteRecursively()
        }
    }

    private fun newTransfer(
        transport: MockHttpTransport,
        scope: SyncScope,
        root: java.nio.file.Path
    ): DriveImmutableAssetTransfer = DriveImmutableAssetTransfer(
        transport.createRequestFactory(),
        "https://www.googleapis.com/drive/v3/",
        scope.accountId,
        root.resolve("state"),
        root.resolve("staging"),
        operationsFactory = com.example.myapplication.stage5.TestPhotoPathOperationsFactory,
        directoryForce = {} // synthetic protocol fixture, not native directory-fsync proof
    )

    private fun realPhotoBytes(): ByteArray {
        val relative = "src/androidTest/assets/stage7/photos/small_valid_photo.jpg"
        val fixture = listOf(java.io.File(relative), java.io.File("app", relative))
            .firstOrNull { it.isFile } ?: throw AssertionError("real JPEG fixture is missing")
        return fixture.readBytes()
    }

    private fun photoDescriptor(bytes: ByteArray): PhotoDescriptor =
        com.example.myapplication.stage5.validatePhotoBytes(bytes).descriptor

    private fun remoteDescriptor(value: PhotoDescriptor) = RemoteAssetDescriptor(
        "asset-1", value.byteCount, value.sha256, value.mimeType, value.width, value.height
    )

    private fun byteAsset(bytes: ByteArray): PhotoAsset {
        val copy = bytes.copyOf()
        val frozenDescriptor = photoDescriptor(copy)
        return object : PhotoAsset {
            override val descriptor = frozenDescriptor
            override fun open() = copy.copyOf().inputStream()
        }
    }

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

    private fun metadata(scope: SyncScope, descriptor: PhotoDescriptor, parent: String): String =
        metadataFields(scope, descriptor, parent, includeOutputDigest = true)

    private fun metadataWithoutOutputDigest(
        scope: SyncScope,
        descriptor: PhotoDescriptor,
        parent: String
    ): String = metadataFields(scope, descriptor, parent, includeOutputDigest = false)

    private fun metadataFields(
        scope: SyncScope,
        descriptor: PhotoDescriptor,
        parent: String,
        includeOutputDigest: Boolean
    ): String {
        val outputDigest = if (includeOutputDigest) {
            ",\"size\":\"${descriptor.byteCount}\",\"sha256Checksum\":\"${descriptor.sha256}\""
        } else ""
        return """
            {"id":"asset-1","name":"sotaware-asset-${descriptor.sha256}.jpg","mimeType":"${descriptor.mimeType}"$outputDigest,"parents":["$parent"],"appProperties":{"sotaware_account_id":"${scope.accountId}","sotaware_backup_root_id":"${scope.backupRootId}","sotaware_document_id":"${scope.documentId.value}","sotaware_manifest_schema":"3","sotaware_asset_sha256":"${descriptor.sha256}","sotaware_immutable_asset":"1"}}
        """.trimIndent()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class CancellationAwareInputStream(
        private val bytes: ByteArray,
        private val blocked: CountDownLatch,
        private val release: CountDownLatch,
        private val cancelled: AtomicBoolean
    ) : InputStream() {
        private var position = 0
        private var firstChunk = true

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            if (firstChunk) {
                firstChunk = false
                buffer[offset] = bytes[position++]
                return 1
            }
            blocked.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "cancellation gate was not released" }
            if (cancelled.get()) throw CancellationException("synthetic download cancellation")
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun close() = Unit
    }

    private class PartialThenGateInputStream(
        private val bytes: ByteArray,
        private val partialReady: CountDownLatch,
        private val release: CountDownLatch
    ) : InputStream() {
        private var position = 0
        private var firstChunk = true

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            if (firstChunk) {
                firstChunk = false
                buffer[offset] = bytes[position++]
                partialReady.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "verification gate was not released" }
                val additional = minOf(length - 1, bytes.size - position)
                if (additional > 0) {
                    bytes.copyInto(buffer, offset + 1, position, position + additional)
                    position += additional
                }
                return 1 + additional
            }
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun close() = Unit
    }
}
