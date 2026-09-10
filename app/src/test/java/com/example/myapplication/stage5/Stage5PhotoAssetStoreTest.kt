package com.example.myapplication.stage5

import com.example.myapplication.stage0.HighResolutionPhonePhotoFixture
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage4.PhotoRollbackException
import com.example.myapplication.stage4.StagedPhotoContentTransaction
import com.example.myapplication.stage9b.testPhotoAssets

import java.io.ByteArrayInputStream

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.io.RandomAccessFile


import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

class Stage5PhotoAssetStoreTest {
    @Test
    fun filenames_rejectTraversalAbsoluteDriveAndDotSegmentForms() {
        val rejected = listOf(
            "../escape.jpg",
            "..\\escape.jpg",
            "nested/escape.jpg",
            "nested\\escape.jpg",
            "/absolute.jpg",
            "\\absolute.jpg",
            "C:\\absolute.jpg",
            "C:/absolute.jpg",
            "\\\\server\\share\\photo.jpg",
            "./photo.jpg",
            "photo/../escape.jpg",
            "photo\\..\\escape.jpg",
            "photo\u0000.jpg",
            "CON.jpg",
            "prn.png",
            "AUX.jpeg",
            "NUL.webp",
            "COM1.jpg",
            "LPT9.jpg",
            "owner's file.jpg",
            "photo.txt"
        )
        rejected.forEach { name -> assertRejected("filename $name") { validatePhotoFileName(name) } }
    }

    @Test
    fun resolver_usesCanonicalContainmentAndRejectsSiblingPrefixAndSymlinkEscapes() {
        val root = Files.createTempDirectory("stage5-path-root").toFile()
        try {
            val resolver = PhotoPathResolver(root, createRoot = true, operationsFactory = TestPhotoPathOperationsFactory)
            val sibling = requireNotNull(root.parentFile).resolve("${root.name}-sibling").toPath()
            assertRejected { resolver.ensureContained(sibling, "sibling prefix") }
            assertRejected { resolver.resolve("../${root.name}-sibling/photo.jpg") }

            val outside = Files.createTempFile("stage5-outside", ".jpg")
            val childLink = root.toPath().resolve("linked.jpg")
            try {
                Files.createSymbolicLink(childLink, outside)
            } catch (error: FileSystemException) {
                if (isWindowsSymlinkPrivilegeFailure(error)) {
                    assumeNoException("Windows symbolic-link privilege is unavailable", error)
                    return
                }
                throw error
            }
            assertRejected { resolver.resolve("linked.jpg") }
            Files.deleteIfExists(childLink)
            Files.deleteIfExists(outside)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun documentPhotoStore_allowsTrustedAndroidAncestorButRejectsSymlinkBelowFilesDir() {
        val container = Files.createTempDirectory("stage5-app-private-boundary").toFile()
        val actualFiles = Files.createDirectory(container.toPath().resolve("files")).toFile()
        val androidAlias = container.toPath().resolve("android-data")
        val outside = Files.createTempDirectory("stage5-app-private-outside").toFile()
        try {
            try {
                Files.createSymbolicLink(androidAlias, container.toPath())
            } catch (error: FileSystemException) {
                if (isWindowsSymlinkPrivilegeFailure(error)) {
                    assumeNoException("Windows symbolic-link privilege is unavailable", error)
                    return
                }
                throw error
            }

            // The supplied filesDir is the trusted Android boundary. A
            // provider-managed alias above it must not be mistaken for an
            // untrusted photo-root component.
            val presentedFilesDir = androidAlias.resolve("files").toFile()
            val documentId = DocumentId.new()
            val store = DocumentPhotoAssetStore(
                presentedFilesDir,
                documentId,
                DefaultImageProbe,
                TestPhotoPathOperationsFactory
            )
            try {
                assertTrue(store.resolver.root.isDirectory)
                assertTrue(store.resolver.root.path.replace('\\', '/').endsWith(
                    "/documents/${documentId.value}/photos"
                ))
            } finally {
                store.close()
            }

            // A symlink introduced inside filesDir remains a hard rejection.
            val hostileComponent = actualFiles.toPath().resolve("hostile-documents")
            Files.createSymbolicLink(hostileComponent, outside.toPath())
            assertRejected("photo root symlink below trusted filesDir") {
                PhotoPathResolver(
                    presentedFilesDir.toPath().resolve("hostile-documents/photos").toFile(),
                    createRoot = true,
                    operationsFactory = TestPhotoPathOperationsFactory,
                    trustedRootDirectory = presentedFilesDir
                )
            }
            Files.deleteIfExists(hostileComponent)
        } finally {
            Files.deleteIfExists(androidAlias)
            actualFiles.deleteRecursively()
            outside.deleteRecursively()
            container.deleteRecursively()
        }
    }

    @Test
    fun parentReplacementInjection_failsClosedWithoutRedirectingOutsideDocumentRoot() {
        val root = Files.createTempDirectory("stage5-parent-replacement").toFile()
        val outside = Files.createTempDirectory("stage5-parent-replacement-outside").toFile()
        try {
            val injection = ParentReplacementFailClosedFactory()
            val resolver = PhotoPathResolver(
                root,
                createRoot = true,
                operationsFactory = injection
            )
            val target = resolver.resolve("photo.jpg").toPath()
            val outsideTarget = outside.resolve("photo.jpg")
            val sentinel = byteArrayOf(7, 8, 9)
            outsideTarget.writeBytes(sentinel)
            injection.parentWasReplaced = true

            assertRejected("descriptor-relative operation after parent replacement") {
                resolver.openNewOutput(target, "injected parent replacement")
            }
            assertEquals(sentinel.toList(), outsideTarget.readBytes().toList())
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun generatedPhotoNames_areInternalFixedExtensionAndDocumentScoped() {
        val root = Files.createTempDirectory("stage5-generated").toFile()
        try {
            val resolver = PhotoPathResolver(root, createRoot = true, operationsFactory = TestPhotoPathOperationsFactory)
            val first = resolver.newPhotoReference()
            val second = resolver.newPhotoReference()
            assertTrue(first.matches(Regex("photo-[0-9a-f-]+\\.jpg")))
            assertTrue(second.matches(Regex("photo-[0-9a-f-]+\\.jpg")))
            assertNotEquals(first, second)
            assertEquals(root.canonicalFile, requireNotNull(resolver.resolve(first).parentFile).canonicalFile)

            val documentId = DocumentId.new()
            val store = DocumentPhotoAssetStore(root, documentId, DefaultImageProbe, TestPhotoPathOperationsFactory)
            val published = store.publishNewPhoto(HighResolutionPhonePhotoFixture.jpegBytes())
            assertTrue(published.matches(Regex("photo-[0-9a-f-]+\\.jpg")))
            assertTrue(store.resolver.root.path.replace('\\', '/').endsWith(
                "/documents/${documentId.value}/photos"
            ))
            assertTrue(store.resolver.resolve(published).isFile)
            store.releasePhotoPublication(published)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun publication_validatesBeforeAtomicPublishAndLeavesNoPartialFinalFile() {
        val root = Files.createTempDirectory("stage5-publish").toFile()
        try {
            val store = DocumentPhotoAssetStore(root, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)
            val reference = store.publishNewPhoto(HighResolutionPhonePhotoFixture.jpegBytes())
            val target = store.resolver.resolve(reference)
            val goodBytes = target.readBytes()
            store.releasePhotoPublication(reference)
            assertTrue(goodBytes.isNotEmpty())
            assertTrue(store.resolver.root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })

            assertRejected {
                store.publishNewPhoto(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))
            }
            assertEquals(goodBytes.toList(), target.readBytes().toList())
            assertTrue(store.resolver.root.listFiles().orEmpty().none { it.name.matches(Regex("photo-[0-9a-f-]+\\.jpg")) && it != target })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nullAndPartialCameraSources_doNotCreatePublishedPhotoReferences() {
        val root = Files.createTempDirectory("stage5-camera").toFile()
        try {
            val store = DocumentPhotoAssetStore(root, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)
            assertRejected { store.publishNewPhoto(ByteArrayInputStream(ByteArray(0))) }
            assertRejected {
                store.publishNewPhoto(object : InputStream() {
                    private var count = 0
                    override fun read(): Int {
                        if (count++ < 4) return 0xFF
                        throw IOException("camera stream interrupted")
                    }
                })
            }
            assertTrue(store.resolver.root.listFiles().orEmpty().none { it.name.startsWith("photo-") })
            assertTrue(store.resolver.root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun atomicMoveFailure_preservesLastKnownGoodPhotoAndCleansStaging() {
        val root = Files.createTempDirectory("stage5-atomic-failure").toFile()
        try {
            val target = File(root, "photo.jpg")
            val oldBytes = HighResolutionPhonePhotoFixture.jpegBytes()
            target.writeBytes(oldBytes)
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                testPhotoAssets(mapOf("photo.jpg" to HighResolutionPhonePhotoFixture.jpegBytes())),
                TestPhotoPathOperationsFactory,
                move = { _, _ -> throw AtomicMoveNotSupportedException("source", "target", "injected") }
            )
            assertRejected { runBlocking { transaction.publish() } }
            assertEquals(oldBytes.toList(), target.readBytes().toList())
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stagedPhotoTransaction_midPublicationFailure_rollsBackEveryTarget() {
        val root = Files.createTempDirectory("stage5-atomic-multi-failure").toFile()
        val oldBytes = Stage4PhotoFixture.previousJpegBytes()
        val incomingBytes = Stage4PhotoFixture.incomingJpegBytes()
        val names = listOf("first.jpg", "second.jpg")
        names.forEach { name -> File(root, name).writeBytes(oldBytes) }
        val transaction = StagedPhotoContentTransaction.stageForTesting(
            root,
            testPhotoAssets(names.associateWith { incomingBytes }),
            FailOnPhotoMoveFactory(failOnMove = 2)
        )
        try {
            assertRejected("mid-publication failure") {
                runBlocking { transaction.publish() }
            }
            names.forEach { name ->
                assertEquals(oldBytes.toList(), File(root, name).readBytes().toList())
            }
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") })
        } finally {
            transaction.releaseAfterFailure()
            root.deleteRecursively()
        }
    }

    @Test
    fun committedCleanupFailure_keepsBackupEvidenceAfterAuthoritativeCommit() {
        val root = Files.createTempDirectory("stage5-cleanup-failure").toFile()
        try {
            val target = File(root, "photo.jpg")
            val oldBytes = HighResolutionPhonePhotoFixture.jpegBytes()
            target.writeBytes(oldBytes)
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                testPhotoAssets(mapOf("photo.jpg" to HighResolutionPhonePhotoFixture.jpegBytes())),
                TestPhotoPathOperationsFactory,
                delete = { path ->
                    if (path.fileName.toString().endsWith(".bak")) {
                        throw IOException("injected backup cleanup failure")
                    }
                    Files.deleteIfExists(path)
                }
            )

            var recovery = false
            try {
                runBlocking {
                    transaction.publish()
                    transaction.commit()
                }
            } catch (error: PhotoCanonicalRecoveryException) {
                recovery = true
            }
            assertTrue(recovery)

            val backups = root.listFiles().orEmpty().filter { it.name.endsWith(".bak") }
            assertEquals(1, backups.size)
            assertEquals(oldBytes.toList(), backups.single().readBytes().toList())
            assertTrue(target.isFile)
            assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            root.deleteRecursively()
        }
    }











    @Test
    fun recoveryAfterRestart_acceptsDistinctDurableAndLivePriorAuthorities() = runBlocking {
        val root = Files.createTempDirectory("stage5-distinct-prior-authorities").toFile()
        val documentId = DocumentId.new()
        val oldBytes = Stage4PhotoFixture.previousJpegBytes()
        val incomingBytes = Stage4PhotoFixture.incomingJpegBytes()
        val previousDurable = snapshotForPhotoNames(listOf("photo.jpg")).copy(snapshotRevision = 1L)
        val previousLive = previousDurable.copy(snapshotRevision = 2L)
        val intended = previousDurable.copy(snapshotRevision = 3L)
        val durableIdentity = photoCanonicalIdentity(documentId, previousDurable)
        val liveIdentity = photoCanonicalIdentity(documentId, previousLive)
        val intendedIdentity = photoCanonicalIdentity(documentId, intended)
        try {
            File(root, "photo.jpg").writeBytes(oldBytes)
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                testPhotoAssets(mapOf("photo.jpg" to incomingBytes)),
                TestPhotoPathOperationsFactory
            )
            transaction.prepareCanonicalRecovery(
                durableIdentity,
                liveIdentity,
                previousLive,
                intendedIdentity
            )
            val journalBytes = File(root, ".stage5-photo-transaction.marker").readBytes()
            val intentBytes = File(root, ".stage5-photo-canonical.intent").readBytes()
            transaction.publish()
            transaction.releaseAfterFailure()

            val reopened = PhotoPathResolver(
                root,
                createRoot = true,
                operationsFactory = TestPhotoPathOperationsFactory
            )
            try {
                assertEquals(incomingBytes.toList(), File(root, "photo.jpg").readBytes().toList())

                var wrongPairRejected = false
                try {
                    reopened.reconcilePhotoTransaction(durableIdentity, durableIdentity)
                } catch (_: PhotoCanonicalRecoveryException) {
                    wrongPairRejected = true
                }
                assertTrue("a forged equal pair must not authorize recovery", wrongPairRejected)
                assertEquals(journalBytes.toList(), File(root, ".stage5-photo-transaction.marker").readBytes().toList())
                assertEquals(intentBytes.toList(), File(root, ".stage5-photo-canonical.intent").readBytes().toList())
                assertEquals(incomingBytes.toList(), File(root, "photo.jpg").readBytes().toList())

                assertEquals(
                    PhotoRecoveryAction.ROLLED_BACK,
                    reopened.reconcilePhotoTransaction(durableIdentity, liveIdentity)
                )
                assertEquals(oldBytes.toList(), File(root, "photo.jpg").readBytes().toList())
                assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") })
            } finally {
                reopened.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun remoteAcceptanceRecoveryAfterRestart_preservesDistinctPriorPairAndMetadataBinding() = runBlocking {
        val root = Files.createTempDirectory("stage5-distinct-remote-prior-authorities").toFile()
        val documentId = DocumentId.new()
        val oldBytes = Stage4PhotoFixture.previousJpegBytes()
        val incomingBytes = Stage4PhotoFixture.incomingJpegBytes()
        val previousDurable = snapshotForPhotoNames(listOf("photo.jpg")).copy(snapshotRevision = 11L)
        val previousLive = previousDurable.copy(snapshotRevision = 12L)
        val intended = previousDurable.copy(snapshotRevision = 13L)
        val durableIdentity = photoCanonicalIdentity(documentId, previousDurable)
        val liveIdentity = photoCanonicalIdentity(documentId, previousLive)
        val intendedIdentity = photoCanonicalIdentity(documentId, intended)
        val oldMetadataIdentity = "a".repeat(64)
        try {
            File(root, "photo.jpg").writeBytes(oldBytes)
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                testPhotoAssets(mapOf("photo.jpg" to incomingBytes)),
                TestPhotoPathOperationsFactory
            )
            transaction.prepareCanonicalRecovery(
                durableIdentity,
                liveIdentity,
                previousLive,
                intendedIdentity,
                PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE
            )
            val intentBytes = File(root, ".stage5-photo-canonical.intent").readBytes()
            transaction.publish()
            transaction.prepareCrossStoreRollback(oldMetadataIdentity)
            transaction.rollbackForCrossStoreCompensation()
            transaction.releaseAfterFailure()

            val reopened = PhotoPathResolver(
                root,
                createRoot = true,
                operationsFactory = TestPhotoPathOperationsFactory
            )
            try {
                var wrongPairRejected = false
                try {
                    reopened.reconcilePhotoTransaction(
                        durableIdentity,
                        durableIdentity,
                        oldMetadataIdentity
                    )
                } catch (_: PhotoCanonicalRecoveryException) {
                    wrongPairRejected = true
                }
                assertTrue("remote recovery must reject a forged prior-live identity", wrongPairRejected)
                assertEquals(intentBytes.toList(), File(root, ".stage5-photo-canonical.intent").readBytes().toList())
                assertEquals(oldBytes.toList(), File(root, "photo.jpg").readBytes().toList())

                assertEquals(
                    PhotoRecoveryAction.ROLLED_BACK,
                    reopened.reconcilePhotoTransaction(
                        durableIdentity,
                        liveIdentity,
                        oldMetadataIdentity
                    )
                )
                assertEquals(oldBytes.toList(), File(root, "photo.jpg").readBytes().toList())
                assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") })
            } finally {
                reopened.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun coldRestart_rehydratesExactUnequalPriorLiveSnapshotBeforePhotoRecovery() = runBlocking {
        val root = Files.createTempDirectory("stage5-cold-live-rehydrate").toFile()
        val documentId = DocumentId.new()
        val oldBytes = Stage4PhotoFixture.previousJpegBytes()
        val incomingBytes = Stage4PhotoFixture.incomingJpegBytes()
        val previousDurable = snapshotForPhotoNames(listOf("photo.jpg")).copy(snapshotRevision = 21L)
        val previousLive = previousDurable.copy(snapshotRevision = 22L)
        val intended = previousDurable.copy(snapshotRevision = 23L)
        val durableIdentity = photoCanonicalIdentity(documentId, previousDurable)
        val liveIdentity = photoCanonicalIdentity(documentId, previousLive)
        val intendedIdentity = photoCanonicalIdentity(documentId, intended)
        try {
            File(root, "photo.jpg").writeBytes(oldBytes)
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                testPhotoAssets(mapOf("photo.jpg" to incomingBytes)),
                TestPhotoPathOperationsFactory
            )
            transaction.prepareCanonicalRecovery(
                durableIdentity,
                liveIdentity,
                previousLive,
                intendedIdentity
            )
            val journalBytes = File(root, ".stage5-photo-transaction.marker").readBytes()
            val intentBytes = File(root, ".stage5-photo-canonical.intent").readBytes()
            val liveArtifact = File(root, ".stage5-photo-canonical.live").readBytes()
            transaction.publish()
            transaction.releaseAfterFailure()

            val reopened = PhotoPathResolver(
                root,
                createRoot = true,
                operationsFactory = TestPhotoPathOperationsFactory
            )
            try {
                assertRejected("cold restart must reject a substituted durable/live pair") {
                    reopened.reconcilePhotoTransaction(durableIdentity, durableIdentity)
                }
                assertEquals(journalBytes.toList(), File(root, ".stage5-photo-transaction.marker").readBytes().toList())
                assertEquals(intentBytes.toList(), File(root, ".stage5-photo-canonical.intent").readBytes().toList())
                assertEquals(liveArtifact.toList(), File(root, ".stage5-photo-canonical.live").readBytes().toList())
                assertEquals(incomingBytes.toList(), File(root, "photo.jpg").readBytes().toList())

                assertEquals(
                    previousLive,
                    reopened.rehydratePreviousLiveCanonicalSnapshot(durableIdentity)
                )
                assertEquals(
                    PhotoRecoveryAction.ROLLED_BACK,
                    reopened.reconcilePhotoTransaction(durableIdentity, liveIdentity)
                )
                assertEquals(oldBytes.toList(), File(root, "photo.jpg").readBytes().toList())
                assertFalse(File(root, ".stage5-photo-canonical.live").exists())
                assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") })
            } finally {
                reopened.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun coldRestart_rejectsMissingOrTamperedUnequalPriorLiveSnapshotEvidence() = runBlocking {
        listOf(
            "tampered",
            "wrong-live-identity",
            "wrong-document",
            "wrong-source",
            "missing",
            "oversized"
        ).forEach { corruption ->
            val root = Files.createTempDirectory("stage5-cold-live-$corruption").toFile()
            val documentId = DocumentId.new()
            val previousDurable = snapshotForPhotoNames(listOf("photo.jpg")).copy(snapshotRevision = 31L)
            val previousLive = previousDurable.copy(snapshotRevision = 32L)
            val intended = previousDurable.copy(snapshotRevision = 33L)
            val durableIdentity = photoCanonicalIdentity(documentId, previousDurable)
            val liveIdentity = photoCanonicalIdentity(documentId, previousLive)
            val intendedIdentity = photoCanonicalIdentity(documentId, intended)
            try {
                File(root, "photo.jpg").writeBytes(Stage4PhotoFixture.previousJpegBytes())
                val transaction = StagedPhotoContentTransaction.stageForTesting(
                    root,
                    testPhotoAssets(mapOf("photo.jpg" to Stage4PhotoFixture.incomingJpegBytes())),
                    TestPhotoPathOperationsFactory
                )
                transaction.prepareCanonicalRecovery(
                    durableIdentity,
                    liveIdentity,
                    previousLive,
                    intendedIdentity
                )
                val journalBytes = File(root, ".stage5-photo-transaction.marker").readBytes()
                val intentBytes = File(root, ".stage5-photo-canonical.intent").readBytes()
                val artifact = File(root, ".stage5-photo-canonical.live")
                when (corruption) {
                    "tampered" -> {
                        val corrupted = artifact.readBytes()
                        corrupted[corrupted.lastIndex - 1] =
                            if (corrupted[corrupted.lastIndex - 1].toInt() == 'A'.code) 'B'.code.toByte() else 'A'.code.toByte()
                        artifact.writeBytes(corrupted)
                    }
                    "wrong-live-identity", "wrong-document", "wrong-source" -> {
                        val lines = artifact.readText(StandardCharsets.US_ASCII)
                            .trimEnd('\n')
                            .split('\n')
                            .toMutableList()
                        when (corruption) {
                            "wrong-live-identity" -> lines[3] = "0".repeat(64)
                            "wrong-document" -> lines[2] = Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(DocumentId.new().value.toByteArray())
                            else -> lines[4] = Base64.getUrlEncoder().withoutPadding()
                                .encodeToString("content://stage5/other-source".toByteArray())
                        }
                        artifact.writeText(lines.joinToString("\n") + "\n", StandardCharsets.US_ASCII)
                    }
                    "missing" -> Files.delete(artifact.toPath())
                    "oversized" -> RandomAccessFile(artifact, "rw").use {
                        it.setLength(MAX_PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT_BYTES + 1L)
                    }
                }
                val retainedArtifactBytes = artifact.takeIf { it.isFile && corruption != "oversized" }
                    ?.readBytes()
                val retainedArtifactSize = artifact.takeIf { it.isFile }?.length()
                transaction.publish()
                transaction.releaseAfterFailure()

                val reopened = PhotoPathResolver(
                    root,
                    createRoot = true,
                    operationsFactory = TestPhotoPathOperationsFactory
                )
                try {
                    assertRejected("$corruption live snapshot evidence") {
                        reopened.rehydratePreviousLiveCanonicalSnapshot(durableIdentity)
                    }
                    assertEquals(journalBytes.toList(), File(root, ".stage5-photo-transaction.marker").readBytes().toList())
                    assertEquals(intentBytes.toList(), File(root, ".stage5-photo-canonical.intent").readBytes().toList())
                    assertEquals(
                        Stage4PhotoFixture.incomingJpegBytes().toList(),
                        File(root, "photo.jpg").readBytes().toList()
                    )
                    if (corruption == "missing") {
                        assertFalse(artifact.exists())
                    } else if (corruption == "oversized") {
                        assertEquals(retainedArtifactSize, artifact.length())
                        assertTrue(
                            "oversized live snapshot evidence must remain oversized",
                            artifact.length() > MAX_PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT_BYTES
                        )
                    } else {
                        assertEquals(retainedArtifactBytes!!.toList(), artifact.readBytes().toList())
                    }
                } finally {
                    reopened.close()
                }
            } finally {
                root.deleteRecursively()
            }
        }
    }




    @Test
    fun explicitPhotoGc_removesOnlyUnreferencedGeneratedPublicationsAndStaleCaptureTemps() {
        val root = Files.createTempDirectory("stage5-photo-gc").toFile()
        val store = DocumentPhotoAssetStore(root, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)
        try {
            val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
            val referenced = store.publishNewPhoto(bytes)
            val unreferenced = store.publishNewPhoto(bytes)
            store.releasePhotoPublication(referenced)
            store.releasePhotoPublication(unreferenced)
            val unmanaged = store.resolver.resolve("unmanaged.jpg")
            unmanaged.writeBytes(bytes)
            val backup = store.resolver.newInternalFile("stage5-photo", ".bak")
            backup.writeBytes(bytes)
            val capture = store.newCaptureFile()
            capture.setLastModified(System.currentTimeMillis() - Stage5Limits.MAX_CAPTURE_AGE_MILLIS - 1L)

            assertEquals(0, store.cleanupUnreferencedGeneratedPhotos(setOf(referenced), setOf(unreferenced)))
            assertTrue(store.resolver.resolve(unreferenced).isFile)
            assertEquals(1, store.cleanupUnreferencedGeneratedPhotos(setOf(referenced)))
            assertTrue(store.resolver.resolve(referenced).isFile)
            assertFalse(store.resolver.resolve(unreferenced).exists())
            assertTrue(unmanaged.isFile)
            assertTrue(backup.isFile)
            assertEquals(1, store.cleanupOrphanedCaptureFiles())
            assertFalse(capture.exists())
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun activeCanonicalPhotoAdmission_isDocumentScopedAndPostCommitGcRetainsReferencedAndUnmanagedFiles() {
        val root = Files.createTempDirectory("stage5-photo-admission-gc").toFile()
        val store = DocumentPhotoAssetStore(root, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)
        try {
            val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
            val referenced = store.publishNewPhoto(bytes)
            val orphan = store.publishNewPhoto(bytes)
            store.releasePhotoPublication(referenced)
            store.releasePhotoPublication(orphan)
            val unmanaged = store.resolver.resolve("unmanaged.jpg")
            unmanaged.writeBytes(bytes)
            val snapshot = snapshotForPhotoNames(listOf(referenced, "unmanaged.jpg"))

            // This is the same production admission used by the active
            // Stage 4 bridge's hasRequiredPhotoContent path.  Admission
            // reconciles journals and reads content, but does not collect
            // against a potentially stale authority pair.
            assertTrue(store.hasRequiredPhotoContent(snapshot, snapshot))
            assertTrue(store.resolver.resolve(referenced).isFile)
            assertTrue(unmanaged.isFile)
            assertTrue(store.resolver.resolve(orphan).isFile)

            store.cleanupAfterCanonicalCommit(
                PhotoRetentionAuthority(
                    currentDurableSnapshot = snapshot,
                    currentLiveSnapshot = snapshot
                )
            )
            assertFalse(store.resolver.resolve(orphan).exists())
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun postCommitCleanup_protectsPhotoAttachedAfterAdmissionCaptureAndRelease() {
        val root = Files.createTempDirectory("stage5-photo-fresh-authority").toFile()
        val store = DocumentPhotoAssetStore(root, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)
        try {
            val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
            val oldOrphan = store.publishNewPhoto(bytes).also(store::releasePhotoPublication)

            // Admission captured this pair before the camera result arrived.
            val capturedDurable = snapshotForPhotoNames(emptyList())
            val capturedLive = snapshotForPhotoNames(emptyList())

            // The camera then published, attached, and released its reservation.
            val attachedReference = store.publishNewPhoto(bytes)
            val freshLive = snapshotForPhotoNames(listOf(attachedReference))
            store.releasePhotoPublication(attachedReference)

            // Post-commit cleanup uses the fresh live authority, not the stale
            // admission capture. The old orphan is still collectible.
            store.cleanupAfterCanonicalCommit(
                PhotoRetentionAuthority(
                    currentDurableSnapshot = capturedDurable,
                    currentLiveSnapshot = freshLive
                )
            )

            assertFalse(store.resolver.resolve(oldOrphan).exists())
            assertTrue(store.resolver.resolve(attachedReference).isFile)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun activeAdmission_doesNotReadAnUnclaimedPhotoOutsideTheDocumentRoot() {
        val filesRoot = Files.createTempDirectory("stage5-admission-read-files").toFile()
        val externalRoot = Files.createTempDirectory("stage5-admission-read-external").toFile()
        val store = DocumentPhotoAssetStore(filesRoot, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)
        try {
            val name = "external-admission.jpg"
            val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
            File(externalRoot, name).writeBytes(bytes)

            val snapshot = snapshotForPhotoNames(listOf(name))
            assertFalse(store.hasRequiredPhotoContent(snapshot, snapshot))
            // Admission is document-scoped and fails closed. It neither
            // publishes a document target nor mutates the external source.
            assertTrue(store.resolveForRead(name) == null)
            assertNoPhotoTransactionArtifacts(store)
            assertTrue(File(externalRoot, name).isFile)
            assertEquals(bytes.toList(), File(externalRoot, name).readBytes().toList())
        } finally {
            store.close()
            filesRoot.deleteRecursively()
            externalRoot.deleteRecursively()
        }
    }



    @Test
    fun mixedDurableAndLiveAdmission_protectsBothPhotoSetsUntilAcceptedCleanup() {
        val root = Files.createTempDirectory("stage5-photo-mixed-authorities").toFile()
        val store = DocumentPhotoAssetStore(root, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)
        try {
            val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
            val durableReference = store.publishNewPhoto(bytes)
            val liveReference = store.publishNewPhoto(bytes)
            store.releasePhotoPublication(durableReference)
            store.releasePhotoPublication(liveReference)
            val durable = snapshotForPhotoNames(listOf(durableReference))
            val live = snapshotForPhotoNames(listOf(liveReference))

            store.reconcilePhotoContent(durable, live)

            assertTrue(store.resolver.resolve(durableReference).isFile)
            assertTrue(store.resolver.resolve(liveReference).isFile)
            store.cleanupAfterCanonicalCommit(
                PhotoRetentionAuthority(
                    currentDurableSnapshot = durable,
                    currentLiveSnapshot = live
                )
            )
            assertTrue(store.resolver.resolve(durableReference).isFile)
            assertTrue(store.resolver.resolve(liveReference).isFile)
            store.cleanupAfterCanonicalCommit(
                PhotoRetentionAuthority(
                    currentDurableSnapshot = live,
                    currentLiveSnapshot = live
                )
            )
            assertFalse(store.resolver.resolve(durableReference).exists())
            assertTrue(store.resolver.resolve(liveReference).isFile)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun publicationReservation_preventsAdmissionGcUntilAttachmentReleasesIt() {
        val root = Files.createTempDirectory("stage5-photo-publication-reservation").toFile()
        val store = DocumentPhotoAssetStore(root, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)
        try {
            val reference = store.publishNewPhoto(HighResolutionPhonePhotoFixture.jpegBytes())

            assertEquals(0, store.cleanupUnreferencedGeneratedPhotos(emptySet()))
            assertTrue(store.resolver.resolve(reference).isFile)

            store.releasePhotoPublication(reference)
            assertEquals(1, store.cleanupUnreferencedGeneratedPhotos(emptySet()))
            assertFalse(store.resolver.resolve(reference).exists())
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun postCanonicalCommitCleanup_removesOldGeneratedButRetainsAcceptedReference() {
        val root = Files.createTempDirectory("stage5-photo-post-commit-gc").toFile()
        val store = DocumentPhotoAssetStore(root, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)
        try {
            val oldReference = store.publishNewPhoto(HighResolutionPhonePhotoFixture.jpegBytes())
            val acceptedReference = store.publishNewPhoto(HighResolutionPhonePhotoFixture.jpegBytes())
            store.releasePhotoPublication(oldReference)
            store.releasePhotoPublication(acceptedReference)

            val accepted = snapshotForPhotoNames(listOf(acceptedReference))
            store.cleanupAfterCanonicalCommit(
                PhotoRetentionAuthority(
                    currentDurableSnapshot = accepted,
                    currentLiveSnapshot = accepted
                )
            )

            assertFalse(store.resolver.resolve(oldReference).exists())
            assertTrue(store.resolver.resolve(acceptedReference).isFile)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aggregatePhotoLimit_failsDocumentAdmissionBeforeAnyReadOrPublication() {
        val root = Files.createTempDirectory("stage5-photo-aggregate").toFile()
        val names = (0 until 5).map { "photo-$it.jpg" }
        val snapshot = DocumentSnapshotV1(
            schemaVersion = 2,
            snapshotRevision = 0L,
            source = DocumentSourceIdentityV1("content://stage5/aggregate", "plan.pdf"),
            pages = mapOf(
                0 to PageSnapshotV1(
                    photoPins = names.mapIndexed { index, name ->
                        PhotoPinSnapshotV1(
                            x = 0.1f + index / 10f,
                            y = 0.5f,
                            id = "aggregate-pin-$index",
                            imageFileNames = listOf(name),
                            imageNotes = emptyMap(),
                            imageShapes = emptyMap()
                        )
                    }
                )
            )
        )
        val injectedProbe = object : PhotoDecodeProbe {
            override fun probe(bytes: ByteArray): ImageInfo = ImageInfo("image/jpeg", 1, 1)
        }
        val inflatedSize = Stage5Limits.MAX_PHOTO_BYTES.toLong() - 1L
        val store = DocumentPhotoAssetStore(
            root,
            DocumentId.new(),
            injectedProbe,
            ReportedSizePhotoPathOperationsFactory(inflatedSize)
        )
        try {
            names.forEach { store.resolver.resolve(it).writeBytes(byteArrayOf(1, 2, 3)) }
            assertRejected("aggregate document photo admission") {
                store.capturePhotoAssets(snapshot)
            }
            assertTrue(store.resolver.root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") })
            assertTrue(names.all { store.resolver.resolve(it).isFile })
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupCannotEscapeContainedPhotoRoot() {
        val root = Files.createTempDirectory("stage5-cleanup").toFile()
        val outside = File(requireNotNull(root.parentFile), "outside-${UUID.randomUUID()}.jpg")
        outside.writeBytes(byteArrayOf(1))
        try {
            val store = DocumentPhotoAssetStore(root, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)
            assertRejected { store.cleanup("../${outside.name}") }
            assertTrue(outside.exists())
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }

    private fun snapshotForPhotoNames(names: List<String>): DocumentSnapshotV1 = DocumentSnapshotV1(
        schemaVersion = 2,
        snapshotRevision = 0L,
        source = DocumentSourceIdentityV1("content://stage5/${names.joinToString("-")}", "plan.pdf"),
        pages = mapOf(
            0 to PageSnapshotV1(
                photoPins = listOf(
                    PhotoPinSnapshotV1(
                        x = 0.5f,
                        y = 0.5f,
                        id = "migration-pin",
                        imageFileNames = names,
                        imageNotes = emptyMap(),
                        imageShapes = emptyMap()
                    )
                )
            )
        )
    )


    private fun assertNoPhotoTransactionArtifacts(store: DocumentPhotoAssetStore) {
        assertTrue(
            store.resolver.root.listFiles().orEmpty().none { file ->
                file.name.startsWith(".stage5-photo-")
            }
        )
    }

    private fun isWindowsSymlinkPrivilegeFailure(error: FileSystemException): Boolean {
        val isWindows = System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)
        val details = listOfNotNull(error.message, error.reason)
        return isWindows && details.any {
            it.contains("A required privilege is not held by the client", ignoreCase = true)
        }
    }

    private fun assertRejected(label: String = "operation", block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: Stage5ValidationException) {
            rejected = true
        } catch (_: PhotoCanonicalRecoveryException) {
            rejected = true
        } catch (_: PhotoRollbackException) {
            rejected = true
        } catch (_: IOException) {
            rejected = true
        } catch (_: SecurityException) {
            rejected = true
        } catch (_: IllegalArgumentException) {
            rejected = true
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue("$label must be rejected", rejected)
    }
}
