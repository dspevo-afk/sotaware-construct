package com.example.myapplication.stage9b

import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage5.PhotoDescriptor
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import com.example.myapplication.stage5.validatePhotoBytes
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic HTTP coverage for the production resumable Drive adapter. */
class DriveAssetTransferTest {
    @Test
    fun resumed308AcknowledgementReopensSourceAndAmbiguousCompletionReadsBack() = runBlocking {
        val (bytes, descriptor) = validPhoto()
        val hash = descriptor.sha256
        val scope = SyncScope("account", "root", DocumentId.new())
        val calls = AtomicInteger()
        val opens = AtomicInteger()
        val transport = transport { request ->
            val method = request.method
            val url = request.url
            when {
                method == "GET" && url.contains("generateIds") -> json("{\"ids\":[\"asset-1\"]}")
                method == "POST" && url.contains("uploadType=resumable") -> {
                    MockLowLevelHttpResponse().setStatusCode(200)
                        .addHeader("Location", "https://www.googleapis.com/upload/drive/v3/files?session=one")
                        .setZeroContent()
                }
                method == "PUT" && url.contains("session=one") && calls.incrementAndGet() == 1 ->
                    MockLowLevelHttpResponse().setStatusCode(308).addHeader("Range", "bytes=0-3").setZeroContent()
                method == "PUT" && url.contains("session=one") ->
                    MockLowLevelHttpResponse().setStatusCode(200).setZeroContent()
                method == "GET" && url.contains("/files/asset-1") -> json(metadata(scope, hash, bytes.size.toLong()))
                else -> MockLowLevelHttpResponse().setStatusCode(404).setZeroContent()
            }
        }
        val root = Files.createTempDirectory("stage9b-drive-transfer")
        try {
            val asset = object : PhotoAsset {
                override val descriptor = descriptor
                override fun open() = ByteArrayInputStream(bytes.copyOf()).also { opens.incrementAndGet() }
            }
            val transfer = newTransfer(transport, root, scope.accountId)
            val result = transfer.upload(
                scope,
                null,
                "a".repeat(64),
                "folder",
                PhotoAssetSet.of(mapOf("photo.jpg" to asset))
            )
            assertEquals(bytes.size.toLong() * 2L - 4L, result.uploadedBytes)
            assertEquals("asset-1", result.descriptors.getValue("photo.jpg").remoteAssetId)
            assertEquals(2, calls.get())
            assertEquals(3, opens.get()) // preflight plus one reopen per acknowledged range

            val putRequests = transport.requests.filter {
                it.method == "PUT" && it.url.contains("session=one")
            }
            assertEquals(2, putRequests.size)
            assertArrayEquals(bytes, requireNotNull(putRequests[0].body))
            assertArrayEquals(bytes.copyOfRange(4, bytes.size), requireNotNull(putRequests[1].body))
            assertTrue(!requireNotNull(putRequests[0].body).contentEquals(requireNotNull(putRequests[1].body)))
            assertEquals(
                "bytes 0-${bytes.lastIndex}/${bytes.size}",
                putRequests[0].header("Content-Range")
            )
            assertEquals(
                "bytes 4-${bytes.lastIndex}/${bytes.size}",
                putRequests[1].header("Content-Range")
            )

            val sessionRequest = requireNotNull(transport.requests.firstOrNull {
                it.method == "POST" && it.url.contains("uploadType=resumable")
            })
            val sessionBody = String(requireNotNull(sessionRequest.body), Charsets.UTF_8)
            assertTrue(sessionBody.contains("\"id\":\"asset-1\""))
            assertTrue(sessionBody.contains("\"name\":\"sotaware-asset-$hash.jpg\""))
            assertTrue(sessionBody.contains("\"mimeType\":\"image/jpeg\""))
            assertTrue(sessionBody.contains("\"parents\":[\"folder\"]"))
            assertTrue(sessionBody.contains("\"sotaware_asset_sha256\":\"$hash\""))

            val finalPutRequest = putRequests.last()
            val finalPutIndex = transport.requests.indexOfFirst { it === finalPutRequest }
            val readbackIndex = transport.requests.indexOfLast {
                it.method == "GET" && it.url.contains("/files/asset-1")
            }
            assertTrue("completion metadata must be read after the final PUT", readbackIndex > finalPutIndex)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun existingAssetMustMatchScopeAndChecksumBeforeReuse() = runBlocking {
        val (bytes, photoDescriptor) = validPhoto()
        val hash = photoDescriptor.sha256
        val scope = SyncScope("account", "root", DocumentId.new())
        val generated = AtomicInteger()
        val transport = transport { request ->
            val method = request.method
            val url = request.url
            if (method == "GET" && url.contains("generateIds")) {
                generated.incrementAndGet()
                MockLowLevelHttpResponse().setStatusCode(503).setZeroContent()
            } else if (method == "GET" && url.contains("/files/asset-1")) {
                json(metadata(scope, "0".repeat(64), bytes.size.toLong()))
            } else MockLowLevelHttpResponse().setStatusCode(404).setZeroContent()
        }
        val root = Files.createTempDirectory("stage9b-drive-scope")
        try {
            val transfer = newTransfer(transport, root, scope.accountId)
            val descriptor = RemoteAssetDescriptor(
                "asset-1",
                bytes.size.toLong(),
                hash,
                photoDescriptor.mimeType,
                photoDescriptor.width,
                photoDescriptor.height
            )
            var error: DriveAssetTransferException? = null
            try {
                transfer.upload(
                    scope, null, "b".repeat(64), "folder",
                    PhotoAssetSet.of(mapOf("photo.jpg" to byteAsset(bytes))),
                    existing = mapOf("photo.jpg" to descriptor)
                )
            } catch (failure: DriveAssetTransferException) {
                error = failure
            }
            assertTrue(error?.message.orEmpty().contains("ID generation failed"))
            assertEquals(1, generated.get())
            assertTrue(transport.requests.none { it.method == "POST" || it.method == "PUT" })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun downloadedAssetsHaveAnExplicitIdempotentOwnerAndRejectUseAfterRelease() = runBlocking {
        val (bytes, photoDescriptor) = validPhoto()
        val hash = photoDescriptor.sha256
        val scope = SyncScope("account", "root", DocumentId.new())
        val transport = transport { request ->
            val method = request.method
            val url = request.url
            when {
                method == "GET" && url.contains("alt=media") ->
                    MockLowLevelHttpResponse().setStatusCode(200).setContent(bytes)
                method == "GET" && url.contains("/files/asset-1") ->
                    json(metadata(scope, hash, bytes.size.toLong()))
                else -> MockLowLevelHttpResponse().setStatusCode(404).setZeroContent()
            }
        }
        val root = Files.createTempDirectory("stage9b-download-owner")
        try {
            val transfer = newTransfer(transport, root, scope.accountId)
            val descriptor = RemoteAssetDescriptor(
                "asset-1",
                bytes.size.toLong(),
                hash,
                photoDescriptor.mimeType,
                photoDescriptor.width,
                photoDescriptor.height
            )
            val assets = transfer.download(scope, "folder", mapOf("photo.jpg" to descriptor))
            val owner = requireNotNull(transfer.ownershipFor(assets))
            val stream = assets.getValue("photo.jpg").open()
            owner.release()
            assertTrue(owner.isReleased)
            assertThrows(IOException::class.java) {
                assets.getValue("photo.jpg").open().use { it.read() }
            }
            assertTrue(root.resolve("staging").toFile().listFiles()?.isNotEmpty() == true)
            stream.close()
            owner.release()
            transfer.discardDownloadedAssets(assets)
            assertEquals(0, root.resolve("staging").toFile().listFiles()?.size ?: 0)
        } finally {
            root.toFile().deleteRecursively()
        }
    }


    @Test
    fun providerInt64StringAllowsVerifiedReuseWithZeroUploadBytes() = runBlocking {
        val (bytes, photo) = validPhoto()
        val scope = SyncScope("account", "root", DocumentId.new())
        val remote = RemoteAssetDescriptor("asset-1", photo.byteCount, photo.sha256,
            photo.mimeType, photo.width, photo.height)
        val transport = transport { request ->
            check(request.method == "GET" && request.url.contains("/files/asset-1")) {
                "verified immutable reuse must not create or upload another asset"
            }
            json(metadata(scope, photo.sha256, photo.byteCount))
        }
        val root = Files.createTempDirectory("stage9b-provider-int64-reuse")
        try {
            val result = newTransfer(transport, root, scope.accountId).upload(
                scope, null, "c".repeat(64), "folder",
                PhotoAssetSet.of(mapOf("photo.jpg" to byteAsset(bytes))),
                existing = mapOf("photo.jpg" to remote)
            )
            assertEquals(0L, result.uploadedBytes)
            assertEquals(photo.byteCount, result.reusedBytes)
            assertEquals(remote, result.descriptors.getValue("photo.jpg"))
            assertTrue(transport.requests.isNotEmpty())
            assertTrue(transport.requests.all { it.method == "GET" })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidProviderInt64SizeCannotAuthorizeAssetDownload() {
        val (bytes, photo) = validPhoto()
        val scope = SyncScope("account", "root", DocumentId.new())
        val remote = RemoteAssetDescriptor("asset-1", photo.byteCount, photo.sha256,
            photo.mimeType, photo.width, photo.height)
        val invalidLiterals = listOf(
            "null", "true", "false", "[]", "{}", bytes.size.toString(),
            "\"\"", "\"1.0\"", "\"1e3\"", "\"+1\"", "\"01\"", "\"-1\"",
            "\" 1 \"", "\"9223372036854775808\""
        )
        invalidLiterals.forEach { literal ->
            val root = Files.createTempDirectory("stage9b-provider-int64-reject")
            try {
                val wire = metadata(scope, photo.sha256, photo.byteCount)
                    .replace("\"size\":\"${photo.byteCount}\"", "\"size\":$literal")
                val transport = transport { request ->
                    when {
                        request.method == "GET" && request.url.contains("alt=media") ->
                            MockLowLevelHttpResponse().setStatusCode(200).setContent(bytes)
                        request.method == "GET" && request.url.contains("/files/asset-1") -> json(wire)
                        else -> throw AssertionError("unexpected provider request")
                    }
                }
                assertThrows("invalid provider size $literal was accepted", DriveAssetTransferException::class.java) {
                    runBlocking {
                        val transfer = newTransfer(transport, root, scope.accountId)
                        val unexpected = transfer.download(scope, "folder", mapOf("photo.jpg" to remote))
                        transfer.discardDownloadedAssets(unexpected)
                    }
                }
                assertTrue(transport.requests.all { it.method == "GET" })
                assertEquals(0, root.resolve("staging").toFile().listFiles()?.size ?: 0)
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    private fun validPhoto(): Pair<ByteArray, PhotoDescriptor> {
        val validated = validatePhotoBytes(Stage4PhotoFixture.previousJpegBytes())
        assertTrue("fixture must contain a complete image", validated.bytes.size > 4)
        assertEquals("image/jpeg", validated.descriptor.mimeType)
        return validated.bytes to validated.descriptor
    }

    private fun newTransfer(
        transport: RecordingTransport,
        root: java.nio.file.Path,
        accountId: String
    ): DriveImmutableAssetTransfer = DriveImmutableAssetTransfer(
        transport.createRequestFactory(),
        "https://www.googleapis.com/drive/v3/",
        accountId,
        root.resolve("state"),
        root.resolve("staging"),
        operationsFactory = TestPhotoPathOperationsFactory,
        // Synthetic HTTP tests do not qualify native directory fsync. The
        // explicit seam avoids selecting a production path fallback.
        directoryForce = {}
    )

    private fun byteAsset(bytes: ByteArray): PhotoAsset {
        val copy = bytes.copyOf()
        val descriptor = validatePhotoBytes(copy).descriptor
        return object : PhotoAsset {
            override val descriptor = descriptor
            override fun open() = ByteArrayInputStream(copy.copyOf())
        }
    }

    private fun transport(handler: (RecordedRequest) -> LowLevelHttpResponse): RecordingTransport =
        RecordingTransport(handler)

    private class RecordingTransport(
        private val handler: (RecordedRequest) -> LowLevelHttpResponse
    ) : MockHttpTransport() {
        val requests = mutableListOf<RecordedRequest>()

        override fun buildRequest(method: String, url: String): LowLevelHttpRequest =
            object : MockLowLevelHttpRequest(url) {
                private val headers = linkedMapOf<String, String>()

                override fun addHeader(name: String, value: String) {
                    headers[name] = value
                }

                override fun execute(): LowLevelHttpResponse {
                    val body = getStreamingContent()?.let { content ->
                        ByteArrayOutputStream().also { output -> content.writeTo(output) }.toByteArray()
                    }
                    val request = RecordedRequest(method, url, headers.toMap(), body)
                    synchronized(requests) { requests += request }
                    return handler(request)
                }
            }
    }

    private data class RecordedRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: ByteArray?
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private fun json(body: String): MockLowLevelHttpResponse = MockLowLevelHttpResponse()
        .setStatusCode(200).setContentType("application/json").setContent(body)

    private fun metadata(scope: SyncScope, hash: String, size: Long): String =
        """{"id":"asset-1","name":"sotaware-asset-$hash.jpg","mimeType":"image/jpeg","size":"$size","sha256Checksum":"$hash","parents":["folder"],"appProperties":{"sotaware_account_id":"${scope.accountId}","sotaware_backup_root_id":"${scope.backupRootId}","sotaware_document_id":"${scope.documentId.value}","sotaware_manifest_schema":"$DRIVE_MANIFEST_SCHEMA_VERSION","sotaware_asset_sha256":"$hash","sotaware_immutable_asset":"1"}}"""
}
