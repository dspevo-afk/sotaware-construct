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
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.io.RandomAccessFile
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
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
                mapOf("photo.jpg" to HighResolutionPhonePhotoFixture.jpegBytes()),
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
    fun committedCleanupFailure_keepsBackupEvidenceAfterAuthoritativeCommit() {
        val root = Files.createTempDirectory("stage5-cleanup-failure").toFile()
        try {
            val target = File(root, "photo.jpg")
            val oldBytes = HighResolutionPhonePhotoFixture.jpegBytes()
            target.writeBytes(oldBytes)
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("photo.jpg" to HighResolutionPhonePhotoFixture.jpegBytes()),
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
    fun legacyGlobalPhoto_isExplicitlyClaimedPerDocumentAndNeverCrossRead() {
        val root = Files.createTempDirectory("stage5-legacy-isolation").toFile()
        try {
            val legacy = File(root, "same-name.jpg")
            val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
            legacy.writeBytes(bytes)
            val first = DocumentPhotoAssetStore(root, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)
            val second = DocumentPhotoAssetStore(root, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)

            val firstPath = first.migrateLegacyPhoto(legacy.name, root)
            assertTrue(firstPath.isFile)
            assertEquals(bytes.toList(), first.read(legacy.name).toList())
            assertTrue(second.resolveForRead(legacy.name) == null)
            assertNotEquals(first.resolver.root.canonicalPath, second.resolver.root.canonicalPath)

            val secondPath = second.migrateLegacyPhoto(legacy.name, root)
            assertTrue(secondPath.isFile)
            assertEquals(bytes.toList(), second.read(legacy.name).toList())
            assertTrue(legacy.isFile)
            assertNotEquals(firstPath.canonicalPath, secondPath.canonicalPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun multiPhotoLegacyClaim_failsBeforePublishingAnyRequiredPhoto() {
        val root = Files.createTempDirectory("stage5-legacy-multi-photo").toFile()
        try {
            val firstLegacy = File(root, "first.jpg")
            firstLegacy.writeBytes(HighResolutionPhonePhotoFixture.jpegBytes())
            val snapshot = DocumentSnapshotV1(
                schemaVersion = 1,
                snapshotRevision = 0L,
                source = DocumentSourceIdentityV1("content://stage5/source", "plan.pdf"),
                pages = mapOf(
                    0 to PageSnapshotV1(
                        photoPins = listOf(
                            PhotoPinSnapshotV1(
                                x = 0.5f,
                                y = 0.5f,
                                id = "pin-stage5",
                                imageFileNames = listOf("first.jpg", "missing.jpg"),
                                imageNotes = emptyMap(),
                                imageShapes = emptyMap()
                            )
                        )
                    )
                )
            )
            val store = DocumentPhotoAssetStore(root, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)

            assertRejected { runBlocking { store.migrateLegacyPhotos(snapshot, root) } }
            assertTrue(store.resolveForRead("first.jpg") == null)
            assertTrue(firstLegacy.isFile)
            assertTrue(store.resolver.root.listFiles().orEmpty().none { it.name.startsWith("photo-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun withMigratedLegacyPhotos_successfullyCommitsTwoPhotosAndPreservesLegacyOriginals() {
        val filesRoot = Files.createTempDirectory("stage5-migration-success-files").toFile()
        val legacyRoot = Files.createTempDirectory("stage5-migration-success-legacy").toFile()
        val names = listOf("first.jpg", "second.jpg")
        val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
        val store = DocumentPhotoAssetStore(filesRoot, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)
        try {
            names.forEach { File(legacyRoot, it).writeBytes(bytes) }
            val result = runBlocking {
                store.withMigratedLegacyPhotos(snapshotForPhotoNames(names), legacyRoot) { prepared ->
                    assertEquals(names.toSet(), prepared.keys)
                    "committed"
                }
            }
            assertEquals("committed", result)
            names.forEach { name ->
                assertTrue(store.resolveForRead(name)?.isFile == true)
                assertEquals(bytes.toList(), File(legacyRoot, name).readBytes().toList())
            }
            assertNoPhotoTransactionArtifacts(store)
        } finally {
            store.close()
            filesRoot.deleteRecursively()
            legacyRoot.deleteRecursively()
        }
    }

    @Test
    fun explicitPngMigrationUsesDefaultProbeAndPreservesExactBytesAndDescriptor() {
        val filesRoot = Files.createTempDirectory("stage5-png-migration-files").toFile()
        val legacyRoot = Files.createTempDirectory("stage5-png-migration-legacy").toFile()
        val name = "migrated.png"
        val bytes = realPngBytes()
        val expectedDescriptor = validatePhotoBytes(bytes).descriptor
        val store = DocumentPhotoAssetStore(
            filesRoot,
            DocumentId.new(),
            DefaultImageProbe,
            TestPhotoPathOperationsFactory
        )
        try {
            File(legacyRoot, name).writeBytes(bytes)

            val published = store.migrateLegacyPhoto(name, legacyRoot)

            assertTrue(published.isFile)
            assertEquals(bytes.toList(), store.read(name).toList())
            assertEquals(expectedDescriptor, validatePhotoBytes(store.read(name)).descriptor)
            assertEquals(bytes.toList(), File(legacyRoot, name).readBytes().toList())
        } finally {
            store.close()
            filesRoot.deleteRecursively()
            legacyRoot.deleteRecursively()
        }
    }

    @Test
    fun legacyMigration_recordsCanonicalIntentBeforeCallbackAndClearsItAfterCommit() {
        val root = Files.createTempDirectory("stage5-legacy-cross-store-intent").toFile()
        val documentId = DocumentId.new()
        val store = DocumentPhotoAssetStore(root, documentId, DefaultImageProbe, TestPhotoPathOperationsFactory)
        val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
        val intended = snapshotForPhotoNames(listOf("legacy.jpg"))
        val previous = intended.copy(pages = emptyMap())
        try {
            File(root, "legacy.jpg").writeBytes(bytes)
            runBlocking {
                store.withMigratedLegacyPhotos(
                    snapshot = intended,
                    legacyRoot = root,
                    previousCanonicalSnapshot = previous
                ) {
                    assertTrue(File(store.resolver.root, ".stage5-photo-canonical.intent").isFile)
                    assertTrue(store.resolver.resolve("legacy.jpg").isFile)
                    true
                }
            }
            assertTrue(File(store.resolver.root, "legacy.jpg").isFile)
            assertTrue(
                store.resolver.root.listFiles().orEmpty().none {
                    it.name == ".stage5-photo-canonical.intent"
                }
            )
            assertTrue(File(root, "legacy.jpg").isFile)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun withMigratedLegacyPhotos_callbackFailureRollsBackAllTargetsAndPreservesOriginals() {
        val filesRoot = Files.createTempDirectory("stage5-migration-callback-failure-files").toFile()
        val legacyRoot = Files.createTempDirectory("stage5-migration-callback-failure-legacy").toFile()
        val names = listOf("first.jpg", "second.jpg")
        val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
        val store = DocumentPhotoAssetStore(filesRoot, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)
        try {
            names.forEach { File(legacyRoot, it).writeBytes(bytes) }
            val oldGenerated = store.publishNewPhoto(bytes).also(store::releasePhotoPublication)
            assertRejected("migration callback failure") {
                runBlocking {
                    store.withMigratedLegacyPhotos(snapshotForPhotoNames(names), legacyRoot) {
                        throw IllegalStateException("injected apply failure")
                    }
                }
            }
            names.forEach { name ->
                assertTrue(store.resolveForRead(name) == null)
                assertEquals(bytes.toList(), File(legacyRoot, name).readBytes().toList())
            }
            assertTrue(store.resolver.resolve(oldGenerated).isFile)
            assertNoPhotoTransactionArtifacts(store)
        } finally {
            store.close()
            filesRoot.deleteRecursively()
            legacyRoot.deleteRecursively()
        }
    }

    @Test
    fun withMigratedLegacyPhotos_commitResultFalseReturnsFalseAndLeavesNoPublishedTargets() {
        val filesRoot = Files.createTempDirectory("stage5-migration-rejected-files").toFile()
        val legacyRoot = Files.createTempDirectory("stage5-migration-rejected-legacy").toFile()
        val names = listOf("first.jpg", "second.jpg")
        val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
        val store = DocumentPhotoAssetStore(filesRoot, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)
        try {
            names.forEach { File(legacyRoot, it).writeBytes(bytes) }
            val oldGenerated = store.publishNewPhoto(bytes).also(store::releasePhotoPublication)
            val result: Boolean = runBlocking {
                store.withMigratedLegacyPhotos(
                    snapshotForPhotoNames(names),
                    legacyRoot,
                    commitResult = { it }
                ) { false }
            }
            assertFalse(result)
            names.forEach { name ->
                assertTrue(store.resolveForRead(name) == null)
                assertEquals(bytes.toList(), File(legacyRoot, name).readBytes().toList())
            }
            assertTrue(store.resolver.resolve(oldGenerated).isFile)
            assertNoPhotoTransactionArtifacts(store)
        } finally {
            store.close()
            filesRoot.deleteRecursively()
            legacyRoot.deleteRecursively()
        }
    }

    @Test
    fun migrationCanonicalRestoreFailure_retainsV2EvidenceUntilFreshOldTupleProof() {
        val filesRoot = Files.createTempDirectory("stage5-migration-canonical-restore-failure-files").toFile()
        val legacyRoot = Files.createTempDirectory("stage5-migration-canonical-restore-failure-legacy").toFile()
        val documentId = DocumentId.new()
        val store = DocumentPhotoAssetStore(
            filesRoot,
            documentId,
            DefaultImageProbe,
            TestPhotoPathOperationsFactory
        )
        val name = "incoming.png"
        val oldPhotoName = "old-photo.jpg"
        val oldBytes = HighResolutionPhonePhotoFixture.jpegBytes()
        val incomingBytes = realPngBytes()
        val previous = snapshotForPhotoNames(listOf(oldPhotoName))
        val intended = snapshotForPhotoNames(listOf(name))
        var canonical = previous
        try {
            store.resolver.resolve(oldPhotoName).writeBytes(oldBytes)
            File(legacyRoot, name).writeBytes(incomingBytes)

            var failure: PhotoCanonicalRecoveryException? = null
            try {
                runBlocking {
                    store.withMigratedLegacyPhotos(
                        snapshot = intended,
                        legacyRoot = legacyRoot,
                        previousCanonicalSnapshot = previous,
                        commitResult = { false },
                        canonicalRollbackProven = { canonical == previous }
                    ) {
                        // Model Stage 3's durable save/apply having accepted
                        // the incoming state before its restore attempt fails.
                        canonical = intended
                        true
                    }
                }
            } catch (error: PhotoCanonicalRecoveryException) {
                failure = error
            }

            assertTrue("uncertain canonical restoration must be typed", failure != null)
            assertEquals(intended, canonical)
            assertEquals(oldBytes.toList(), store.resolver.resolve(oldPhotoName).readBytes().toList())
            assertFalse(store.resolver.resolve(name).exists())
            assertEquals(incomingBytes.toList(), File(legacyRoot, name).readBytes().toList())

            val journalBytes = File(store.resolver.root, ".stage5-photo-transaction.marker").readBytes()
            val intentBytes = File(store.resolver.root, ".stage5-photo-canonical.intent").readBytes()
            assertTrue(intentBytes.toString(Charsets.US_ASCII).startsWith("SOTAWARE_STAGE5_PHOTO_CANONICAL_V2\n"))

            val reopened = DocumentPhotoAssetStore(
                filesRoot,
                documentId,
                DefaultImageProbe,
                TestPhotoPathOperationsFactory
            )
            try {
                var blocked = false
                try {
                    reopened.reconcilePhotoContent(intended, intended)
                } catch (_: PhotoCanonicalRecoveryException) {
                    blocked = true
                }
                assertTrue("mixed canonical/photo state must remain blocked", blocked)
                assertEquals(journalBytes.toList(), File(reopened.resolver.root, ".stage5-photo-transaction.marker").readBytes().toList())
                assertEquals(intentBytes.toList(), File(reopened.resolver.root, ".stage5-photo-canonical.intent").readBytes().toList())
                assertEquals(oldBytes.toList(), File(reopened.resolver.root, oldPhotoName).readBytes().toList())

                canonical = previous
                reopened.reconcilePhotoContent(previous, previous)
                assertNoPhotoTransactionArtifacts(reopened)
                assertEquals(oldBytes.toList(), File(reopened.resolver.root, oldPhotoName).readBytes().toList())
            } finally {
                reopened.close()
            }
        } finally {
            store.close()
            filesRoot.deleteRecursively()
            legacyRoot.deleteRecursively()
        }
    }

    @Test
    fun migrationCancellationWithUncertainCanonicalRestore_retainsEvidenceAndRethrowsCancellation() {
        val filesRoot = Files.createTempDirectory("stage5-migration-cancelled-restore-files").toFile()
        val legacyRoot = Files.createTempDirectory("stage5-migration-cancelled-restore-legacy").toFile()
        val documentId = DocumentId.new()
        val store = DocumentPhotoAssetStore(
            filesRoot,
            documentId,
            DefaultImageProbe,
            TestPhotoPathOperationsFactory
        )
        val name = "cancelled.jpg"
        val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
        val previous = snapshotForPhotoNames(emptyList())
        val intended = snapshotForPhotoNames(listOf(name))
        var canonical = previous
        try {
            File(legacyRoot, name).writeBytes(bytes)
            var cancellation: CancellationException? = null
            try {
                runBlocking {
                    store.withMigratedLegacyPhotos<Boolean>(
                        snapshot = intended,
                        legacyRoot = legacyRoot,
                        previousCanonicalSnapshot = previous,
                        canonicalRollbackProven = { canonical == previous }
                    ) {
                        canonical = intended
                        throw CancellationException("injected canonical apply cancellation")
                    }
                }
            } catch (error: CancellationException) {
                cancellation = error
            }

            assertTrue("cancellation must remain cancellation", cancellation != null)
            assertEquals(intended, canonical)
            assertFalse(store.resolver.resolve(name).exists())
            assertEquals(bytes.toList(), File(legacyRoot, name).readBytes().toList())
            assertTrue(File(store.resolver.root, ".stage5-photo-transaction.marker").isFile)
            assertTrue(File(store.resolver.root, ".stage5-photo-canonical.intent").isFile)

            val reopened = DocumentPhotoAssetStore(
                filesRoot,
                documentId,
                DefaultImageProbe,
                TestPhotoPathOperationsFactory
            )
            try {
                var blocked = false
                try {
                    reopened.reconcilePhotoContent(intended, intended)
                } catch (_: PhotoCanonicalRecoveryException) {
                    blocked = true
                }
                assertTrue("canceled mixed state must fail closed on reopen", blocked)
                canonical = previous
                reopened.reconcilePhotoContent(previous, previous)
                assertNoPhotoTransactionArtifacts(reopened)
            } finally {
                reopened.close()
            }
        } finally {
            store.close()
            filesRoot.deleteRecursively()
            legacyRoot.deleteRecursively()
        }
    }

    @Test
    fun migrationRejectedResult_clearsEvidenceOnlyAfterExactOldCanonicalProof() {
        val filesRoot = Files.createTempDirectory("stage5-migration-proven-rollback-files").toFile()
        val legacyRoot = Files.createTempDirectory("stage5-migration-proven-rollback-legacy").toFile()
        val store = DocumentPhotoAssetStore(
            filesRoot,
            DocumentId.new(),
            DefaultImageProbe,
            TestPhotoPathOperationsFactory
        )
        val name = "rejected.jpg"
        val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
        val previous = snapshotForPhotoNames(emptyList())
        val intended = snapshotForPhotoNames(listOf(name))
        var canonical = previous
        try {
            File(legacyRoot, name).writeBytes(bytes)
            val result: Boolean = runBlocking {
                store.withMigratedLegacyPhotos(
                    snapshot = intended,
                    legacyRoot = legacyRoot,
                    previousCanonicalSnapshot = previous,
                    commitResult = { false },
                    canonicalRollbackProven = { canonical == previous }
                ) {
                    canonical = intended
                    // Stage 3 has already restored both authorities before
                    // the migration wrapper is allowed to clear evidence.
                    canonical = previous
                    false
                }
            }

            assertFalse(result)
            assertEquals(previous, canonical)
            assertFalse(store.resolver.resolve(name).exists())
            assertEquals(bytes.toList(), File(legacyRoot, name).readBytes().toList())
            assertNoPhotoTransactionArtifacts(store)
        } finally {
            store.close()
            filesRoot.deleteRecursively()
            legacyRoot.deleteRecursively()
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
                mapOf("photo.jpg" to incomingBytes),
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
                mapOf("photo.jpg" to incomingBytes),
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
                mapOf("photo.jpg" to incomingBytes),
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
                    mapOf("photo.jpg" to Stage4PhotoFixture.incomingJpegBytes()),
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
    fun withMigratedLegacyPhotos_midPublicationFailureRollsBackEveryTarget() {
        val filesRoot = Files.createTempDirectory("stage5-migration-mid-failure-files").toFile()
        val legacyRoot = Files.createTempDirectory("stage5-migration-mid-failure-legacy").toFile()
        val names = listOf("first.jpg", "second.jpg")
        val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
        val factory = FailOnPhotoMoveFactory(failOnMove = 2)
        val store = DocumentPhotoAssetStore(filesRoot, DocumentId.new(), DefaultImageProbe, factory)
        try {
            names.forEach { File(legacyRoot, it).writeBytes(bytes) }
            assertRejected("mid-publication failure") {
                runBlocking {
                    store.withMigratedLegacyPhotos(snapshotForPhotoNames(names), legacyRoot) { true }
                }
            }
            names.forEach { name ->
                assertTrue(store.resolveForRead(name) == null)
                assertEquals(bytes.toList(), File(legacyRoot, name).readBytes().toList())
            }
            assertNoPhotoTransactionArtifacts(store)
        } finally {
            store.close()
            filesRoot.deleteRecursively()
            legacyRoot.deleteRecursively()
        }
    }

    @Test
    fun migrateLegacyPhoto_missingSourceClosesLegacyResolverBeforeFailure() {
        val filesRoot = Files.createTempDirectory("stage5-migration-close-files").toFile()
        val legacyRoot = Files.createTempDirectory("stage5-migration-close-legacy").toFile()
        val factory = CountingPhotoPathOperationsFactory()
        val store = DocumentPhotoAssetStore(filesRoot, DocumentId.new(), DefaultImageProbe, factory)
        try {
            assertRejected("missing legacy photo") {
                store.migrateLegacyPhoto("missing.jpg", legacyRoot)
            }
            assertTrue("legacy resolver must close on failure", factory.closed >= 1)
        } finally {
            store.close()
            filesRoot.deleteRecursively()
            legacyRoot.deleteRecursively()
        }
    }

    @Test
    fun invalidDocumentTargetIsQuarantinedBeforeLegacyRetryAndOriginalRemains() {
        val filesRoot = Files.createTempDirectory("stage5-migration-quarantine-files").toFile()
        val legacyRoot = Files.createTempDirectory("stage5-migration-quarantine-legacy").toFile()
        val name = "photo.jpg"
        val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
        val store = DocumentPhotoAssetStore(filesRoot, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)
        try {
            File(legacyRoot, name).writeBytes(bytes)
            store.resolver.resolve(name).writeBytes(byteArrayOf(1, 2, 3))
            store.migrateLegacyPhoto(name, legacyRoot)
            assertEquals(bytes.toList(), store.read(name).toList())
            assertTrue(File(legacyRoot, name).isFile)
            assertTrue(store.resolver.root.listFiles().orEmpty().any { it.name.endsWith(".bad") })
        } finally {
            store.close()
            filesRoot.deleteRecursively()
            legacyRoot.deleteRecursively()
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
            val legacy = store.resolver.resolve("legacy.jpg")
            legacy.writeBytes(bytes)
            val backup = store.resolver.newInternalFile("stage5-photo", ".bak")
            backup.writeBytes(bytes)
            val capture = store.newCaptureFile()
            capture.setLastModified(System.currentTimeMillis() - Stage5Limits.MAX_CAPTURE_AGE_MILLIS - 1L)

            assertEquals(0, store.cleanupUnreferencedGeneratedPhotos(setOf(referenced), setOf(unreferenced)))
            assertTrue(store.resolver.resolve(unreferenced).isFile)
            assertEquals(1, store.cleanupUnreferencedGeneratedPhotos(setOf(referenced)))
            assertTrue(store.resolver.resolve(referenced).isFile)
            assertFalse(store.resolver.resolve(unreferenced).exists())
            assertTrue(legacy.isFile)
            assertTrue(backup.isFile)
            assertEquals(1, store.cleanupOrphanedCaptureFiles())
            assertFalse(capture.exists())
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun activeCanonicalPhotoAdmission_isDocumentScopedAndPostCommitGcRetainsReferencedAndLegacyFiles() {
        val root = Files.createTempDirectory("stage5-photo-admission-gc").toFile()
        val store = DocumentPhotoAssetStore(root, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)
        try {
            val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
            val referenced = store.publishNewPhoto(bytes)
            val orphan = store.publishNewPhoto(bytes)
            store.releasePhotoPublication(referenced)
            store.releasePhotoPublication(orphan)
            val legacy = store.resolver.resolve("legacy.jpg")
            legacy.writeBytes(bytes)
            val snapshot = snapshotForPhotoNames(listOf(referenced, "legacy.jpg"))

            // This is the same production admission used by the active
            // Stage 4 bridge's hasRequiredPhotoContent path.  Admission
            // reconciles journals and reads content, but does not collect
            // against a potentially stale authority pair.
            assertTrue(store.hasRequiredPhotoContent(snapshot, snapshot))
            assertTrue(store.resolver.resolve(referenced).isFile)
            assertTrue(legacy.isFile)
            assertTrue(store.resolver.resolve(orphan).isFile)

            store.cleanupAfterCanonicalCommit(snapshot, snapshot)
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
            store.cleanupAfterCanonicalCommit(capturedDurable, freshLive)

            assertFalse(store.resolver.resolve(oldOrphan).exists())
            assertTrue(store.resolver.resolve(attachedReference).isFile)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun activeAdmission_doesNotCrossBindUnclaimedGlobalLegacyBasename() {
        val filesRoot = Files.createTempDirectory("stage5-admission-read-files").toFile()
        val legacyRoot = Files.createTempDirectory("stage5-admission-read-legacy").toFile()
        val store = DocumentPhotoAssetStore(filesRoot, DocumentId.new(), DefaultImageProbe, TestPhotoPathOperationsFactory)
        try {
            val name = "legacy-admission.jpg"
            val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
            File(legacyRoot, name).writeBytes(bytes)

            val snapshot = snapshotForPhotoNames(listOf(name))
            try {
                store.readPhotoContentForAdmission(snapshot)
                throw AssertionError("active admission must reject an unclaimed global legacy basename")
            } catch (_: Stage5ValidationException) {
                // The active route is document-scoped and fails closed.
            }
            assertFalse(store.hasRequiredPhotoContent(snapshot, snapshot))
            // Admission neither publishes a document target nor mutates or
            // deletes the unrelated global legacy original.
            assertTrue(store.resolveForRead(name) == null)
            assertNoPhotoTransactionArtifacts(store)
            assertTrue(File(legacyRoot, name).isFile)
            assertEquals(bytes.toList(), File(legacyRoot, name).readBytes().toList())
        } finally {
            store.close()
            filesRoot.deleteRecursively()
            legacyRoot.deleteRecursively()
        }
    }

    @Test
    fun migrationCommitMarkerFailureAfterCanonicalApplyLeavesIntentForSafeReopenRecovery() {
        val filesRoot = Files.createTempDirectory("stage5-migration-marker-failure-files").toFile()
        val legacyRoot = Files.createTempDirectory("stage5-migration-marker-failure-legacy").toFile()
        val documentId = DocumentId.new()
        val factory = CloseEnforcingPhotoPathOperationsFactory(failCommitMarker = true)
        val store = DocumentPhotoAssetStore(
            filesRoot,
            documentId,
            DefaultImageProbe,
            factory
        )
        val name = "new-photo.jpg"
        val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
        val intended = snapshotForPhotoNames(listOf(name))
        val previous = snapshotForPhotoNames(emptyList())
        try {
            File(legacyRoot, name).writeBytes(bytes)
            var failure: PhotoCanonicalRecoveryException? = null
            try {
                runBlocking {
                    store.withMigratedLegacyPhotos(
                        snapshot = intended,
                        legacyRoot = legacyRoot,
                        previousCanonicalSnapshot = previous
                    ) { true }
                }
            } catch (error: PhotoCanonicalRecoveryException) {
                failure = error
            }

            assertTrue(failure != null)
            val documentRoot = store.resolver.root
            assertEquals(bytes.toList(), File(documentRoot, name).readBytes().toList())
            assertTrue(File(documentRoot, ".stage5-photo-transaction.marker").isFile)
            assertTrue(File(documentRoot, ".stage5-photo-canonical.intent").isFile)
            assertTrue(File(legacyRoot, name).isFile)
            // The store resolver remains usable for the owning scope, while
            // the separately-created staged transaction resolver is released
            // after the pre-authoritative marker failure.
            assertEquals(bytes.toList(), store.read(name).toList())
            assertEquals(factory.opened - 1, factory.closed)

            val reopened = PhotoPathResolver(
                documentRoot,
                createRoot = true,
                operationsFactory = TestPhotoPathOperationsFactory
            )
            try {
                assertEquals(
                    PhotoRecoveryAction.FINALIZED,
                    reopened.reconcilePhotoTransaction(
                        photoCanonicalIdentity(documentId, intended),
                        photoCanonicalIdentity(documentId, intended)
                    )
                )
                assertEquals(bytes.toList(), reopened.resolve(name).readBytes().toList())
                assertNoPhotoTransactionArtifacts(store)
            } finally {
                reopened.close()
            }
        } finally {
            store.close()
            assertEquals(factory.opened, factory.closed)
            filesRoot.deleteRecursively()
            legacyRoot.deleteRecursively()
        }
    }

    @Test
    fun migrationCommitMarkerFailure_canBeReconciledByTheStillOpenStoreResolver() {
        val filesRoot = Files.createTempDirectory("stage5-migration-same-store-files").toFile()
        val legacyRoot = Files.createTempDirectory("stage5-migration-same-store-legacy").toFile()
        val documentId = DocumentId.new()
        val factory = CloseEnforcingPhotoPathOperationsFactory(failCommitMarker = true)
        val store = DocumentPhotoAssetStore(
            filesRoot,
            documentId,
            DefaultImageProbe,
            factory
        )
        val name = "same-store-photo.jpg"
        val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
        val intended = snapshotForPhotoNames(listOf(name))
        val previous = snapshotForPhotoNames(emptyList())
        try {
            File(legacyRoot, name).writeBytes(bytes)
            var failed = false
            try {
                runBlocking {
                    store.withMigratedLegacyPhotos(
                        snapshot = intended,
                        legacyRoot = legacyRoot,
                        previousCanonicalSnapshot = previous
                    ) { true }
                }
            } catch (_: PhotoCanonicalRecoveryException) {
                failed = true
            }
            assertTrue("migration marker failure must be typed", failed)
            assertEquals("the store-owned resolver remains live", factory.opened - 1, factory.closed)

            // The staging transaction owns and releases its separate resolver;
            // the existing store can still reconcile the durable intent after
            // the failure instead of exposing an unowned pending transaction.
            store.reconcilePhotoContent(intended, intended)
            assertEquals(bytes.toList(), store.read(name).toList())
            assertNoPhotoTransactionArtifacts(store)
            assertEquals("the store resolver must close exactly once", factory.opened - 1, factory.closed)
        } finally {
            store.close()
            assertEquals(factory.opened, factory.closed)
            filesRoot.deleteRecursively()
            legacyRoot.deleteRecursively()
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
            store.cleanupAfterCanonicalCommit(durable, live)
            assertTrue(store.resolver.resolve(durableReference).isFile)
            assertTrue(store.resolver.resolve(liveReference).isFile)
            store.cleanupAfterCanonicalCommit(live, live)
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
            store.cleanupAfterCanonicalCommit(accepted, accepted)

            assertFalse(store.resolver.resolve(oldReference).exists())
            assertTrue(store.resolver.resolve(acceptedReference).isFile)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aggregatePhotoLimit_failsLegacyMigrationAndReadBeforeAnyPublication() {
        val root = Files.createTempDirectory("stage5-photo-aggregate").toFile()
        val names = (0 until 5).map { "photo-$it.jpg" }
        val snapshot = DocumentSnapshotV1(
            schemaVersion = 1,
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
        try {
            names.forEach { File(root, it).writeBytes(byteArrayOf(1, 2, 3)) }
            val migrationStore = DocumentPhotoAssetStore(
                root,
                DocumentId.new(),
                injectedProbe,
                ReportedSizePhotoPathOperationsFactory(inflatedSize)
            )
            val migrationRoot = migrationStore.resolver.root
            try {
                assertRejected("aggregate legacy migration") {
                    runBlocking { migrationStore.migrateLegacyPhotos(snapshot, root) }
                }
                assertTrue(migrationRoot.listFiles().orEmpty().none { it.name.startsWith("photo-") })
                assertTrue(names.all { File(root, it).isFile })
            } finally {
                migrationStore.close()
            }

            val readStore = DocumentPhotoAssetStore(
                root,
                DocumentId.new(),
                injectedProbe,
                ReportedSizePhotoPathOperationsFactory(inflatedSize)
            )
            try {
                names.forEach { readStore.resolver.root.resolve(it).writeBytes(byteArrayOf(4, 5, 6)) }
                assertRejected("aggregate document photo read") {
                    readStore.readReferencedPhotos(snapshot)
                }
            } finally {
                readStore.close()
            }
        } finally {
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
        schemaVersion = 1,
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

    private fun realPngBytes(): ByteArray {
        val image = BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xFFFF0000.toInt())
        image.setRGB(1, 0, 0xFF00FF00.toInt())
        image.setRGB(0, 1, 0xFF0000FF.toInt())
        image.setRGB(1, 1, 0xFFFFFFFF.toInt())
        image.setRGB(0, 2, 0x00000000)
        image.setRGB(1, 2, 0xFF123456.toInt())
        return ByteArrayOutputStream().use { output ->
            assertTrue("JVM must provide a PNG encoder", ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }

    private fun assertNoPhotoTransactionArtifacts(store: DocumentPhotoAssetStore) {
        assertTrue(
            store.resolver.root.listFiles().orEmpty().none { file ->
                file.name.startsWith(".stage5-photo-") ||
                    file.name.startsWith(".legacy-migrate-")
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
