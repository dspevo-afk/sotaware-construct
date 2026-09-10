package com.example.myapplication.stage9a

import com.example.myapplication.stage5.testFileSyncMetadataStore

import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.DurablePendingUpload
import com.example.myapplication.stage4.FilePendingUploadOutbox
import com.example.myapplication.stage4.FileSyncMetadataStore
import com.example.myapplication.stage4.MetadataReadResult
import com.example.myapplication.stage4.MetadataWriteResult
import com.example.myapplication.stage4.PENDING_UPLOAD_OUTBOX_DIRECTORY
import com.example.myapplication.stage4.PENDING_UPLOAD_OUTBOX_MANIFEST
import com.example.myapplication.stage4.PENDING_UPLOAD_OUTBOX_MANIFEST_TEMP
import com.example.myapplication.stage4.SyncMetadata
import com.example.myapplication.stage4.SyncReason
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage5.ImageIoPhotoDecodeProbe
import com.example.myapplication.stage5.ParentReplacementFailClosedFactory
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import com.example.myapplication.stage5.encodeBoundedJson
import com.example.myapplication.stage5.sha256Hex
import com.example.myapplication.stage5.validatePhotoBytes
import com.example.myapplication.stage9b.readTestBytes
import com.example.myapplication.stage9b.testPhotoAssets
import com.google.gson.GsonBuilder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.imageio.ImageIO

class PendingUploadDurabilityRegressionTest {
    @Test
    fun validatedLargePngPendingUpload_persistsAndReconstructsAcrossFileStoreRecreation() = runTest {
        val root = Files.createTempDirectory("stage9a-pending-upload").toFile()
        try {
            val photoBytes = seededNoisePng()
            val validated = validatePhotoBytes(photoBytes, imageProbe = ImageIoPhotoDecodeProbe)

            assertEquals("image/png", validated.descriptor.mimeType)
            assertEquals(1600, validated.descriptor.width)
            assertEquals(1600, validated.descriptor.height)
            assertTrue("fixture should be approximately 7.3 MiB", photoBytes.size > 7 * 1024 * 1024)
            assertTrue("fixture must remain below the per-photo cap", photoBytes.size < 8 * 1024 * 1024)
            assertTrue(validated.bytes.contentEquals(photoBytes))

            // Two separately named entries keep the aggregate comfortably below
            // the 100 MiB photo budget while making inline Base64 metadata much
            // larger than the existing 8 MiB metadata ceiling.
            val photoFiles = linkedMapOf(
                "noise-a.png" to photoBytes,
                "noise-b.png" to photoBytes.copyOf()
            )
            val aggregatePhotoBytes = photoFiles.values.sumOf { it.size.toLong() }
            val estimatedBase64Chars = photoFiles.values.sumOf { bytes ->
                ((bytes.size.toLong() + 2L) / 3L) * 4L
            }
            assertEquals(2, photoFiles.size)
            assertTrue(aggregatePhotoBytes < Stage5Limits.MAX_TOTAL_PHOTO_BYTES)
            assertTrue(estimatedBase64Chars > Stage5Limits.MAX_METADATA_BYTES.toLong())

            val documentId = DocumentId.new()
            val scope = SyncScope("stage9a-account", "stage9a-root", documentId)
            val isolatedScope = SyncScope("other-account", "other-root", documentId)
            val source = DocumentSourceIdentityV1(
                sourceUri = "content://stage9a/large-photo-source",
                displayName = "stage9a.pdf"
            )
            val snapshot = DocumentSnapshotV1(
                schemaVersion = 2,
                snapshotRevision = 9L,
                source = source,
                pages = mapOf(
                    0 to PageSnapshotV1(
                        photoPins = listOf(
                            PhotoPinSnapshotV1(
                                x = 0.5f,
                                y = 0.5f,
                                id = "stage9a-pin",
                                imageFileNames = photoFiles.keys.toList(),
                                imageNotes = emptyMap(),
                                imageShapes = emptyMap()
                            )
                        )
                    )
                )
            )
            val pending = DurablePendingUpload(
                reason = SyncReason.PHOTO,
                sourceUri = source.sourceUri,
                sourceFingerprint = null,
                generation = 1L,
                expectedCursor = null,
                snapshot = snapshot,
                photoFiles = testPhotoAssets(photoFiles)
            )
            val metadata = SyncMetadata(scope = scope, pendingUpload = pending)
            val store = testFileSyncMetadataStore(root)

            assertNotEquals(store.metadataFileFor(scope), store.metadataFileFor(isolatedScope))

            // The complete pending state is now published through the sidecar;
            // the bounded metadata record contains only its verified pointer.
            assertEquals(MetadataWriteResult.Committed, store.write(metadata))
            val persistedTree = GsonBuilder().create().fromJson(
                store.metadataFileFor(scope).readText(),
                com.google.gson.JsonObject::class.java
            )
            assertNotNull(persistedTree.get("pendingUploadPhotoSidecar"))
            assertNull(persistedTree.get("pendingUploadPhotoFiles"))

            val rereadResult = testFileSyncMetadataStore(root).read(scope)
            assertTrue(rereadResult is MetadataReadResult.Loaded)
            val reread = requireNotNull((rereadResult as MetadataReadResult.Loaded).metadata)
            try {
                val rereadPending = requireNotNull(reread.pendingUpload)
                assertEquals(scope, reread.scope)
                assertEquals(snapshot, rereadPending.snapshot)
                assertEquals(photoFiles.keys, rereadPending.photoFiles.keys)
                rereadPending.photoFiles.forEach { (name, asset) ->
                    val bytes = asset.readTestBytes()
                    assertTrue("reconstructed $name differs", bytes.contentEquals(photoBytes))
                    assertEquals(sha256Hex(photoBytes), sha256Hex(bytes))
                }
                assertEquals(
                    store.recoveryIdentity(metadata),
                    testFileSyncMetadataStore(root).recoveryIdentity(reread)
                )

                // The same DocumentId must not let a different account/root observe
                // this pending upload or its future sidecar.
                assertEquals(
                    MetadataReadResult.Loaded(null),
                    testFileSyncMetadataStore(root).read(isolatedScope)
                )
            } finally {
                reread.pendingUpload?.outboxLease?.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun retiredInlinePendingUpload_isRejectedAndOriginalBytesRemain() = runTest {
        val root = Files.createTempDirectory("stage9a-inline-compat").toFile()
        try {
            val scope = SyncScope("legacy-account", "legacy-root", DocumentId.new())
            val source = DocumentSourceIdentityV1(
                sourceUri = "content://stage9a/legacy-inline",
                displayName = "legacy.pdf"
            )
            val photoBytes = tinyPng()
            val snapshot = snapshotWithPhoto(source, "legacy.png")
            val gson = GsonBuilder().disableHtmlEscaping().create()
            val legacyRecord = linkedMapOf<String, Any?>(
                "schemaVersion" to 1,
                "accountId" to scope.accountId,
                "backupRootId" to scope.backupRootId,
                "documentId" to scope.documentId.value,
                "pendingUploadReason" to SyncReason.PHOTO.name,
                "pendingUploadSourceUri" to source.sourceUri,
                "pendingUploadGeneration" to 3L,
                "pendingUploadSnapshotJson" to String(
                    encodeBoundedJson(gson, snapshot, Stage5Limits.MAX_JSON_BYTES, "legacy snapshot"),
                    Charsets.UTF_8
                ),
                "pendingUploadPhotoFiles" to mapOf(
                    "legacy.png" to java.util.Base64.getEncoder().encodeToString(photoBytes)
                )
            )
            val target = testFileSyncMetadataStore(root).metadataFileFor(scope)
            target.parentFile?.mkdirs()
            target.writeText(gson.toJson(legacyRecord))

            val originalBytes = target.readBytes()
            val result = testFileSyncMetadataStore(root).read(scope)
            assertTrue(result is MetadataReadResult.Failed)
            assertArrayEquals(originalBytes, target.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun retiredInlineRecoveryCandidate_isRejectedWithoutOverwritingIncumbent() = runTest {
        val root = Files.createTempDirectory("stage9a-inline-identity").toFile()
        try {
            val scope = SyncScope("legacy-identity-account", "legacy-identity-root", DocumentId.new())
            val source = DocumentSourceIdentityV1(
                sourceUri = "content://stage9a/legacy-identity",
                displayName = "legacy-identity.pdf"
            )
            val photoBytes = tinyPng()
            val snapshot = snapshotWithPhoto(source, "legacy-identity.png")
            val gson = GsonBuilder().disableHtmlEscaping().create()
            val legacyRecord = linkedMapOf<String, Any?>(
                "schemaVersion" to 1,
                "accountId" to scope.accountId,
                "backupRootId" to scope.backupRootId,
                "documentId" to scope.documentId.value,
                "pendingUploadReason" to SyncReason.PHOTO.name,
                "pendingUploadSourceUri" to source.sourceUri,
                "pendingUploadGeneration" to 7L,
                "pendingUploadSnapshotJson" to String(
                    encodeBoundedJson(gson, snapshot, Stage5Limits.MAX_JSON_BYTES, "legacy snapshot"),
                    Charsets.UTF_8
                ),
                "pendingUploadPhotoFiles" to mapOf(
                    "legacy-identity.png" to java.util.Base64.getEncoder().encodeToString(photoBytes)
                )
            )
            val target = testFileSyncMetadataStore(root).metadataFileFor(scope)
            target.parentFile?.mkdirs()
            val legacyBytes = gson.toJson(legacyRecord).toByteArray(StandardCharsets.UTF_8)
            target.writeBytes(legacyBytes)
            val store = testFileSyncMetadataStore(root)
            val originalIdentity = sha256Hex(legacyBytes)
            assertTrue(store.read(scope) is MetadataReadResult.Failed)
            assertEquals(
                MetadataWriteResult.Failed::class,
                store.write(SyncMetadata(scope = scope))::class
            )
            assertEquals(originalIdentity, sha256Hex(target.readBytes()))
            assertArrayEquals(legacyBytes, target.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sidecarMissingOrUnpublished_isFailedAndRetainedForRecovery() = runTest {
        val root = Files.createTempDirectory("stage9a-sidecar-failure").toFile()
        try {
            val fixture = fixtureWithPhoto()
            val store = testFileSyncMetadataStore(root)
            assertEquals(MetadataWriteResult.Committed, store.write(fixture.metadata))
            val contentDirectory = sidecarContentDirectory(root, fixture.scope)
            val manifest = File(contentDirectory, PENDING_UPLOAD_OUTBOX_MANIFEST)
            val manifestBytes = manifest.readBytes()

            // A directory containing bytes but no published marker is not a
            // readable sidecar, and the failed read must not purge evidence.
            manifest.delete()
            File(contentDirectory, PENDING_UPLOAD_OUTBOX_MANIFEST_TEMP).writeBytes(manifestBytes)
            val unpublished = testFileSyncMetadataStore(root).read(fixture.scope)
            assertTrue(unpublished is MetadataReadResult.Failed)
            assertTrue(File(contentDirectory, PENDING_UPLOAD_OUTBOX_MANIFEST_TEMP).isFile)

            // A published marker with altered bytes is equally untrusted. The
            // metadata pointer and corrupt evidence remain; immutable content
            // is never overwritten by a retry.
            manifest.writeBytes(manifestBytes.copyOf().also { it[it.lastIndex] = '{'.code.toByte() })
            val corrupt = testFileSyncMetadataStore(root).read(fixture.scope)
            assertTrue(corrupt is MetadataReadResult.Failed)
            assertTrue(manifest.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sidecarPointerHashMismatch_isRejectedEvenWhenFilesAreOtherwiseValid() = runTest {
        val root = Files.createTempDirectory("stage9a-sidecar-hash").toFile()
        try {
            val fixture = fixtureWithPhoto()
            val store = testFileSyncMetadataStore(root)
            assertEquals(MetadataWriteResult.Committed, store.write(fixture.metadata))
            val target = store.metadataFileFor(fixture.scope)
            val gson = GsonBuilder().create()
            val tree = gson.fromJson(target.readText(), com.google.gson.JsonObject::class.java)
            tree.getAsJsonObject("pendingUploadPhotoSidecar")
                .addProperty("manifestSha256", "0".repeat(64))
            target.writeText(gson.toJson(tree))

            val result = testFileSyncMetadataStore(root).read(fixture.scope)
            assertTrue(result is MetadataReadResult.Failed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun uncertainMetadataAuthority_refusesAnUnboundedNewCandidate() = runTest {
        val root = Files.createTempDirectory("stage9a-sidecar-retention").toFile()
        try {
            val fixture = fixtureWithPhoto()
            val scopeDirectory = sidecarScopeDirectory(root, fixture.scope)
            check(scopeDirectory.mkdirs())
            repeat(4) { index ->
                check(File(scopeDirectory, "a".repeat(63) + index.toString(16)).mkdirs())
            }

            val result = testFileSyncMetadataStore(root).write(fixture.metadata)
            assertTrue("unknown authority must fail closed at the retention bound", result is MetadataWriteResult.Failed)
            assertTrue("failed publication must not create metadata", !testFileSyncMetadataStore(root).metadataFileFor(fixture.scope).exists())
            assertEquals(4, scopeDirectory.listFiles()?.count { it.isDirectory } ?: 0)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preexistingHardLinkedSnapshotStaging_neverTruncatesTheUnrelatedSentinel() = runTest {
        val root = Files.createTempDirectory("stage9a-outbox-hardlink").toFile()
        val sentinel = File(root.parentFile, "stage9a-sentinel-${System.nanoTime()}.txt")
        try {
            sentinel.writeText("sentinel-preserved", StandardCharsets.UTF_8)
            val fixture = fixtureWithPhoto()
            val outbox = FilePendingUploadOutbox(root, TestPhotoPathOperationsFactory)
            val reference = outbox.referenceFor(fixture.scope, fixture.metadata.pendingUpload!!)
            val content = File(sidecarScopeDirectory(root, fixture.scope), reference.contentId)
            check(content.mkdirs())
            val staging = File(content, ".snapshot.json.tmp")
            Files.createLink(staging.toPath(), sentinel.toPath())

            outbox.publish(fixture.scope, fixture.metadata.pendingUpload!!)

            assertEquals("sentinel-preserved", sentinel.readText(StandardCharsets.UTF_8))
        } finally {
            sentinel.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun parentReplacementSeam_failsClosedBeforePayloadWriteOrRead() = runTest {
        val root = Files.createTempDirectory("stage9a-outbox-parent").toFile()
        val sentinel = File(root.parentFile, "stage9a-parent-sentinel-${System.nanoTime()}.txt")
        try {
            sentinel.writeText("parent-sentinel-preserved", StandardCharsets.UTF_8)
            val fixture = fixtureWithPhoto()
            val factory = ParentReplacementFailClosedFactory()
            val outbox = FilePendingUploadOutbox(root, factory)
            val reference = outbox.referenceFor(fixture.scope, fixture.metadata.pendingUpload!!)
            val content = File(sidecarScopeDirectory(root, fixture.scope), reference.contentId)
            check(content.mkdirs())

            factory.parentWasReplaced = true
            val writeFailure = runCatching {
                outbox.publish(fixture.scope, fixture.metadata.pendingUpload!!)
            }.exceptionOrNull()
            assertNotNull(writeFailure)
            assertTrue(!File(content, "snapshot.json").exists())
            assertEquals("parent-sentinel-preserved", sentinel.readText(StandardCharsets.UTF_8))

            val readFailure = runCatching {
                outbox.load(
                    scope = fixture.scope,
                    reference = reference,
                    reason = fixture.metadata.pendingUpload!!.reason,
                    sourceUri = fixture.metadata.pendingUpload!!.sourceUri,
                    sourceFingerprint = fixture.metadata.pendingUpload!!.sourceFingerprint,
                    generation = fixture.metadata.pendingUpload!!.generation,
                    expectedCursor = fixture.metadata.pendingUpload!!.expectedCursor
                )
            }.exceptionOrNull()
            assertNotNull(readFailure)
            assertEquals("parent-sentinel-preserved", sentinel.readText(StandardCharsets.UTF_8))
        } finally {
            sentinel.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun productionOutbox_requiresDescriptorRelativeProvider() = runTest {
        val root = Files.createTempDirectory("stage9a-outbox-secure-provider").toFile()
        try {
            val fixture = fixtureWithPhoto()
            try {
                FilePendingUploadOutbox(root).publish(fixture.scope, fixture.metadata.pendingUpload!!)
            } catch (error: IOException) {
                val unsupported = generateSequence<Throwable>(error) { it.cause }
                    .mapNotNull { it.message }
                    .any { it.contains("SecureDirectoryStream") || it.contains("secure photo filesystem") }
                if (unsupported) {
                    assumeNoException("JVM provider lacks the required secure directory primitive", error)
                    return@runTest
                }
                throw error
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /** Deterministic incompressible RGB noise, approximately 7.3 MiB as PNG. */
    private fun seededNoisePng(): ByteArray {
        val width = 1600
        val height = 1600
        val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        var state = 0x13579BDF
        val row = IntArray(width)

        fun nextByte(): Int {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return (state ushr 24) and 0xFF
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                row[x] = (nextByte() shl 16) or
                    (nextByte() shl 8) or
                    nextByte()
            }
            image.setRGB(0, y, width, 1, row, 0, width)
        }

        val output = ByteArrayOutputStream(width * height * 3 + height)
        check(ImageIO.write(image, "png", output)) { "JVM PNG writer is required" }
        val bytes = output.toByteArray()
        image.flush()
        return bytes
    }

    private fun tinyPng(): ByteArray {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, 0x112233)
        image.setRGB(1, 0, 0x445566)
        image.setRGB(2, 0, 0x778899)
        image.setRGB(0, 1, 0x99AABB)
        image.setRGB(1, 1, 0xCCDDEE)
        image.setRGB(2, 1, 0x102030)
        val output = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", output)) { "JVM PNG writer is required" }
        image.flush()
        return output.toByteArray()
    }

    private fun snapshotWithPhoto(
        source: DocumentSourceIdentityV1,
        fileName: String
    ): DocumentSnapshotV1 = DocumentSnapshotV1(
        schemaVersion = 2,
        snapshotRevision = 1L,
        source = source,
        pages = mapOf(
            0 to PageSnapshotV1(
                photoPins = listOf(
                    PhotoPinSnapshotV1(
                        x = 0.25f,
                        y = 0.75f,
                        id = "stage9a-inline-pin",
                        imageFileNames = listOf(fileName),
                        imageNotes = emptyMap(),
                        imageShapes = emptyMap()
                    )
                )
            )
        )
    )

    private fun fixtureWithPhoto(): PhotoFixture {
        val scope = SyncScope("failure-account", "failure-root", DocumentId.new())
        val source = DocumentSourceIdentityV1(
            sourceUri = "content://stage9a/failure",
            displayName = "failure.pdf"
        )
        val bytes = tinyPng()
        val snapshot = snapshotWithPhoto(source, "failure.png")
        val pending = DurablePendingUpload(
            reason = SyncReason.PHOTO,
            sourceUri = source.sourceUri,
            sourceFingerprint = null,
            generation = 1L,
            expectedCursor = null,
            snapshot = snapshot,
            photoFiles = testPhotoAssets(mapOf("failure.png" to bytes))
        )
        return PhotoFixture(scope, SyncMetadata(scope = scope, pendingUpload = pending))
    }

    private fun sidecarContentDirectory(root: File, scope: SyncScope): File {
        val scopeDirectory = sidecarScopeDirectory(root, scope)
        val metadata = testFileSyncMetadataStore(root).metadataFileFor(scope)
        val gson = GsonBuilder().create()
        val tree = gson.fromJson(metadata.readText(), com.google.gson.JsonObject::class.java)
        val contentId = tree.getAsJsonObject("pendingUploadPhotoSidecar")
            .get("contentId")
            .asString
        return File(scopeDirectory, contentId)
    }

    private fun sidecarScopeDirectory(root: File, scope: SyncScope): File {
        val scopeKey = "${scope.accountId}\u0000${scope.backupRootId}\u0000${scope.documentId.value}"
        val scopeHash = sha256Hex(scopeKey.toByteArray(StandardCharsets.UTF_8))
        return File(root, "$PENDING_UPLOAD_OUTBOX_DIRECTORY/$scopeHash")
    }

    private data class PhotoFixture(val scope: SyncScope, val metadata: SyncMetadata)
}
