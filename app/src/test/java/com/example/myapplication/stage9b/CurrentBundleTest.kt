package com.example.myapplication.stage9b

import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage2.DocumentDurableSnapshotState
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DurableSnapshotSlot
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage3.SessionSnapshotApplyResult
import com.example.myapplication.stage4.PhotoContentTransaction
import com.example.myapplication.stage5.PhotoDescriptor
import com.example.myapplication.stage5.sha256Hex
import com.example.myapplication.stage6.BundleDocumentIdentityPolicy
import com.example.myapplication.stage6.BundleExportInput
import com.example.myapplication.stage6.BundleImportResult
import com.example.myapplication.stage6.DocumentBundleImportHost
import com.example.myapplication.stage6.DocumentBundleService
import com.example.myapplication.stage6.ReboundDocumentBundle
import com.example.myapplication.stage6.SOTAWARE_BUNDLE_FORMAT_VERSION
import com.example.myapplication.stage6.SOTAWARE_BUNDLE_MANIFEST_ENTRY
import com.example.myapplication.stage6.SOTAWARE_BUNDLE_SNAPSHOT_ENTRY
import com.example.myapplication.stage6.SOTAWARE_BUNDLE_SNAPSHOT_SCHEMA_VERSION
import com.example.myapplication.stage6.VerifiedBundleTarget
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/** Focused current-format bundle qualification; legacy formats are intentionally absent. */
class CurrentBundleTest {
    private val source = DocumentSourceIdentityV1(
        sourceUri = "content://provider/current-plan.pdf",
        displayName = "current-plan.pdf",
        providerMetadata = mapOf("authority" to "provider")
    )
    private val sourceFingerprint = SourceFingerprint.fromBytes("current-pdf".toByteArray())

    @Test
    fun inputCloseFailureReleasesDecodedOwnedPhotoStaging() {
        val staging = Files.createTempDirectory("stage9b-close-failure").toFile()
        try {
            val service = DocumentBundleService(stagingDirectory = staging,
                operationsFactory = com.example.myapplication.stage5.TestPhotoPathOperationsFactory)
            val name = "photo-close.png"
            val assets = PhotoAssetSet.of(mapOf(name to syntheticAsset(pngBytes(Color(50, 120, 190)))))
            val archive = encodeFixture(service, BundleExportInput(DocumentId.new(), source, sourceFingerprint, snapshot(listOf(name)), assets))
            org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                service.readBundleFrom {
                    object : ByteArrayInputStream(archive) {
                        override fun close() { super.close(); throw java.io.IOException("synthetic input close failure") }
                    }
                }
            }
            assertTrue("failed source close must release only the decoded staging", staging.listFiles().orEmpty().isEmpty())
        } finally { staging.deleteRecursively() }
    }

    @Test
    fun currentFormat_roundTripsSnapshotDescriptorsAndReopenablePhotoStreams() {
        val photoBytes = pngBytes(Color(220, 40, 40))
        val name = "photo-current.png"
        val assets = PhotoAssetSet.of(mapOf(name to syntheticAsset(photoBytes)))
        val snapshot = snapshot(listOf(name))
        val staging = Files.createTempDirectory("stage9b-current-roundtrip").toFile()
        try {
            val service = DocumentBundleService(stagingDirectory = staging, operationsFactory = com.example.myapplication.stage5.TestPhotoPathOperationsFactory)
            val archive = encodeFixture(service,
                BundleExportInput(
                    exportedDocumentId = DocumentId.new(),
                    source = source,
                    sourceFingerprint = sourceFingerprint,
                    snapshot = snapshot,
                    photoFiles = assets
                )
            )

            val entries = readEntries(archive)
            assertTrue(entries.containsKey(SOTAWARE_BUNDLE_MANIFEST_ENTRY))
            assertTrue(entries.containsKey(SOTAWARE_BUNDLE_SNAPSHOT_ENTRY))
            assertTrue(entries.containsKey("photos/$name"))
            val manifest = JsonParser.parseString(
                entries.getValue(SOTAWARE_BUNDLE_MANIFEST_ENTRY).toString(Charsets.UTF_8)
            ).asJsonObject
            assertEquals(SOTAWARE_BUNDLE_FORMAT_VERSION, manifest["formatVersion"].asInt)
            assertEquals(SOTAWARE_BUNDLE_SNAPSHOT_SCHEMA_VERSION, manifest["snapshotSchemaVersion"].asInt)
            assertEquals(1, manifest["photos"].asJsonArray.size())
            assertFalse(manifest.toString().contains("base64", ignoreCase = true))

            val decoded = service.readBundle(ByteArrayInputStream(archive))
            decoded.use {
                assertEquals(snapshot, decoded.snapshot)
                assertEquals(setOf(name), decoded.photoFiles.keys)
                assertEquals(photoBytes.toList(), decoded.photoFiles.getValue(name).open().use { it.readBytes().toList() })
                // The returned source is reopenable until the explicit owner release.
                assertEquals(photoBytes.toList(), decoded.photoFiles.getValue(name).open().use { it.readBytes().toList() })
                assertTrue(staging.listFiles()?.any { it.name.startsWith(".sotaware-bundle-photo-") } == true)
            }
            assertTrue(staging.listFiles().orEmpty().none { it.name.startsWith(".sotaware-bundle-") })
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun retiredFormat_isRejectedWithoutChangingInputOrCurrentSession() {
        val bytes = pngBytes(Color(40, 80, 220))
        val name = "photo-retired.png"
        val serviceStaging = Files.createTempDirectory("stage9b-current-reject").toFile()
        try {
            val service = DocumentBundleService(stagingDirectory = serviceStaging, operationsFactory = com.example.myapplication.stage5.TestPhotoPathOperationsFactory)
            val current = encodeFixture(service,
                BundleExportInput(
                    DocumentId.new(),
                    source,
                    sourceFingerprint,
                    snapshot(listOf(name)),
                    PhotoAssetSet.of(mapOf(name to syntheticAsset(bytes)))
                )
            )
            val retired = rewriteManifest(current) { manifest ->
                manifest.addProperty("formatVersion", 1)
            }
            val before = retired.copyOf()
            var rejected = false
            try {
                service.readBundle(ByteArrayInputStream(retired))
            } catch (_: java.io.IOException) {
                rejected = true
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("retired bundle must be rejected explicitly", rejected)
            assertEquals(before.toList(), retired.toList())
            // Parsing is side-effect free; no canonical host/session is involved
            // and owned flat staging artifacts are cleaned after rejection.
            assertTrue(serviceStaging.listFiles().orEmpty().none { it.isDirectory })
        } finally {
            serviceStaging.deleteRecursively()
        }
    }

    @Test
    fun retainedPhotoSourcesRemainUsableUntilCloseThenRejectAndCleanup() {
        val bytes = pngBytes(Color(30, 180, 90))
        val name = "photo-lifecycle.png"
        val staging = Files.createTempDirectory("stage9b-current-lifecycle").toFile()
        try {
            val service = DocumentBundleService(stagingDirectory = staging, operationsFactory = com.example.myapplication.stage5.TestPhotoPathOperationsFactory)
            val archive = encodeFixture(
                service,
                BundleExportInput(DocumentId.new(), source, sourceFingerprint, snapshot(listOf(name)),
                    PhotoAssetSet.of(mapOf(name to syntheticAsset(bytes))))
            )
            repeat(3) {
                val decoded = service.readBundle(ByteArrayInputStream(archive))
                assertEquals(bytes.toList(), decoded.photoFiles.getValue(name).open().use { it.readBytes().toList() })
                decoded.close()
                var rejected = false
                try {
                    decoded.photoFiles.getValue(name).open().use { it.readBytes() }
                } catch (_: java.io.IOException) {
                    // Expected: closing the explicit lease invalidates future opens.
                    rejected = true
                }
                assertTrue("closed bundle source must be rejected", rejected)
                assertTrue(staging.listFiles().orEmpty().none { it.name.startsWith(".sotaware-bundle-") })
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun failedReadCleansOwnedStagingWithoutTouchingUnknownFiles() {
        val staging = Files.createTempDirectory("stage9b-current-failed-read").toFile()
        val sentinel = File(staging, "user-owned.keep").apply { writeText("keep") }
        try {
            val service = DocumentBundleService(stagingDirectory = staging, operationsFactory = com.example.myapplication.stage5.TestPhotoPathOperationsFactory)
            val malformed = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
            var rejected = false
            try {
                service.readBundle(ByteArrayInputStream(malformed))
            } catch (_: java.io.IOException) {
                rejected = true
            } catch (_: IllegalArgumentException) {
                // Rejection is the expected result; cleanup is asserted below.
                rejected = true
            }
            assertTrue("truncated bundle must be rejected", rejected)
            assertTrue(sentinel.exists())
            assertTrue(staging.listFiles().orEmpty().none { it.name.startsWith(".sotaware-bundle-") })
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun zipWriterFinishesWithoutClosingCallerOwnedStream() {
        val staging = Files.createTempDirectory("stage9b-current-writer").toFile()
        try {
            val delegate = ByteArrayOutputStream()
            var closed = false
            val output = object : FilterOutputStream(delegate) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
            DocumentBundleService(stagingDirectory = staging, operationsFactory = com.example.myapplication.stage5.TestPhotoPathOperationsFactory).writeBundle(
                output,
                BundleExportInput(DocumentId.new(), source, sourceFingerprint, snapshot(emptyList()), PhotoAssetSet.EMPTY)
            )
            assertFalse("ZIP writer must not close the caller stream", closed)
            assertTrue(delegate.size() > 0)
            output.close()
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun malformedAsset_failsBeforeCanonicalApplyAndLeavesHostSnapshotUntouched() = runBlocking {
        val name = "photo-malformed.png"
        val snapshot = snapshot(listOf(name))
        val descriptor = PhotoDescriptor(
            byteCount = 4,
            sha256 = sha256Hex("bad!".toByteArray()),
            mimeType = "image/png",
            width = 1,
            height = 1
        )
        val malformed = PhotoAssetSet.of(mapOf(name to object : PhotoAsset {
            override val descriptor: PhotoDescriptor = descriptor
            override fun open(): InputStream = "bad!".byteInputStream()
        }))
        val target = VerifiedBundleTarget(DocumentId.new(), source, sourceFingerprint)
        val host = RecordingHost(target.documentId, snapshot(emptyList()))
        val rebound = ReboundDocumentBundle(
            target = target,
            snapshot = snapshot,
            photoFiles = malformed,
            identityPolicy = BundleDocumentIdentityPolicy.VERIFIED_TARGET_COPY
        )
        val staging = Files.createTempDirectory("stage9b-current-malformed").toFile()
        try {
            val result = DocumentBundleService(stagingDirectory = staging, operationsFactory = com.example.myapplication.stage5.TestPhotoPathOperationsFactory)
                .applyReboundBundleWithinDocumentTransaction(rebound, host, NoopPhotoTransaction())
            assertTrue(result is BundleImportResult.Failed)
            assertEquals(0, host.persistCalls)
            assertEquals(snapshot(emptyList()), host.live)
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun manyAssets_exportReopensEachSourceAndUsesAtMost64KiBReadRequests() {
        val bytes = jpegBytes()
        val opens = AtomicInteger(0)
        val maxReadRequest = AtomicInteger(0)
        val names = (0 until 40).map { "photo-${it.toString().padStart(3, '0')}.jpg" }
        val assets = PhotoAssetSet.of(
            names.associateWith {
                trackingAsset(bytes, opens, maxReadRequest)
            }
        )
        val staging = Files.createTempDirectory("stage9b-current-many").toFile()
        try {
            val service = DocumentBundleService(stagingDirectory = staging, operationsFactory = com.example.myapplication.stage5.TestPhotoPathOperationsFactory)
            val archive = encodeFixture(service,
                BundleExportInput(DocumentId.new(), source, sourceFingerprint, snapshot(names), assets)
            )
            assertTrue(archive.isNotEmpty())
            // prepareExport validates once, then each ZIP entry is measured and
            // copied from a fresh immutable stream. No whole-set bytes are used.
            assertTrue(opens.get() >= names.size * 3)
            assertTrue("read request exceeded transfer buffer", maxReadRequest.get() <= 64 * 1024)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun snapshot(photoNames: List<String>): DocumentSnapshotV1 {
        val pages = photoNames.chunked(32).mapIndexed { index, names ->
            index to PageSnapshotV1(
                photoPins = if (names.isEmpty()) emptyList() else listOf(
                    PhotoPinSnapshotV1(
                        x = 0.25f,
                        y = 0.25f,
                        id = "pin-$index",
                        imageFileNames = names,
                        imageNotes = emptyMap(),
                        imageShapes = emptyMap()
                    )
                )
            )
        }.toMap()
        return DocumentSnapshotV1(
            schemaVersion = SOTAWARE_BUNDLE_SNAPSHOT_SCHEMA_VERSION,
            snapshotRevision = 7L,
            source = source,
            pages = pages
        )
    }

    private fun syntheticAsset(bytes: ByteArray): PhotoAsset {
        val descriptor = PhotoDescriptor(
            byteCount = bytes.size.toLong(),
            sha256 = sha256Hex(bytes),
            mimeType = "image/png",
            width = 1,
            height = 1
        )
        return object : PhotoAsset {
            override val descriptor: PhotoDescriptor = descriptor
            override fun open(): InputStream = bytes.inputStream()
        }
    }

    private fun trackingAsset(
        bytes: ByteArray,
        opens: AtomicInteger,
        maxReadRequest: AtomicInteger
    ): PhotoAsset {
        val descriptor = PhotoDescriptor(
            bytes.size.toLong(),
            sha256Hex(bytes),
            "image/jpeg",
            512,
            512
        )
        return object : PhotoAsset {
            override val descriptor: PhotoDescriptor = descriptor
            override fun open(): InputStream {
                opens.incrementAndGet()
                return object : FilterInputStream(bytes.inputStream()) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        maxReadRequest.updateAndGet { old -> maxOf(old, length) }
                        return super.read(buffer, offset, length)
                    }
                }
            }
        }
    }

    private fun readEntries(archive: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    /** Test fixture boundary: the production service only writes to a caller stream. */
    private fun encodeFixture(
        service: DocumentBundleService,
        input: BundleExportInput
    ): ByteArray = ByteArrayOutputStream().also { output ->
        service.writeBundle(output, input)
    }.toByteArray()

    private fun rewriteManifest(
        archive: ByteArray,
        mutate: (com.google.gson.JsonObject) -> Unit
    ): ByteArray {
        val entries = readEntries(archive).toMutableMap()
        val manifest = JsonParser.parseString(
            entries.getValue(SOTAWARE_BUNDLE_MANIFEST_ENTRY).toString(Charsets.UTF_8)
        ).asJsonObject
        mutate(manifest)
        entries[SOTAWARE_BUNDLE_MANIFEST_ENTRY] = manifest.toString().toByteArray()
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, payload) ->
                    val crc = CRC32().also { it.update(payload) }
                    zip.putNextEntry(ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = payload.size.toLong()
                        compressedSize = payload.size.toLong()
                        this.crc = crc.value
                        time = 0L
                    })
                    zip.write(payload)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun pngBytes(color: Color): ByteArray {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, color.rgb)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun jpegBytes(): ByteArray {
        val image = BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                image.setRGB(x, y, Color((x * 17) and 0xff, (y * 31) and 0xff, (x * y) and 0xff).rgb)
            }
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private class RecordingHost(
        override val documentId: DocumentId,
        var live: DocumentSnapshotV1
    ) : DocumentBundleImportHost {
        var persistCalls: Int = 0

        override suspend fun captureCurrentLiveSnapshot(): DocumentSnapshotV1 = live

        override suspend fun captureCurrentDurableSnapshot(): DocumentSnapshotV1 = live

        override suspend fun captureCurrentDurableState(): DocumentDurableSnapshotState =
            DocumentDurableSnapshotState(DurableSnapshotSlot(live, null), null)

        override suspend fun persistAndApply(snapshot: DocumentSnapshotV1): SessionSnapshotApplyResult {
            persistCalls++
            live = snapshot
            return SessionSnapshotApplyResult.Applied
        }

        override suspend fun restore(
            durableSnapshot: DocumentSnapshotV1,
            liveSnapshot: DocumentSnapshotV1
        ): SessionSnapshotApplyResult {
            live = liveSnapshot
            return SessionSnapshotApplyResult.Applied
        }

        override suspend fun restore(
            durableState: DocumentDurableSnapshotState,
            liveSnapshot: DocumentSnapshotV1
        ): SessionSnapshotApplyResult {
            live = liveSnapshot
            return SessionSnapshotApplyResult.Applied
        }
    }

    private class NoopPhotoTransaction : PhotoContentTransaction {
        override suspend fun publish() = Unit
        override suspend fun commit() = Unit
        override suspend fun rollback() = Unit
    }
}
