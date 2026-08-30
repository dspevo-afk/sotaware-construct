package com.example.myapplication.stage4

import com.example.myapplication.stage5.CloseEnforcingPhotoPathOperationsFactory
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import com.example.myapplication.stage5.CreateThenFailPhotoPathOperationsFactory
import com.example.myapplication.stage5.FailOnPhotoMarkerDeleteFactory
import com.example.myapplication.stage5.FailOnPhotoMarkerCreateFactory
import com.example.myapplication.stage5.FailOnPhotoCommitMarkerReadbackFactory
import com.example.myapplication.stage5.AlwaysFailOnPhotoCommitMarkerReadFactory
import com.example.myapplication.stage5.PhotoCanonicalRecoveryException
import com.example.myapplication.stage5.PhotoCanonicalRecoveryMode
import com.example.myapplication.stage5.PhotoRecoveryAction
import com.example.myapplication.stage5.PhotoPathOperations
import com.example.myapplication.stage5.PhotoPathOperationsFactory
import com.example.myapplication.stage5.PhotoPathResolver
import com.example.myapplication.stage5.PhotoTransactionJournalEntry
import com.example.myapplication.stage5.Stage5ValidationException
import com.example.myapplication.stage5.photoCanonicalIdentity
import com.example.myapplication.stage5.sha256Hex
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage2.DocumentId
import java.io.File
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import kotlinx.coroutines.runBlocking

class PhotoContentTransactionTest {
    @Test
    fun staging_withExplicitTrustedRootAcceptsProviderAncestorAlias_butStrictStagingRejects() {
        val container = Files.createTempDirectory("stage4-photo-trusted-boundary").toFile()
        val actualFiles = Files.createDirectory(container.toPath().resolve("files")).toFile()
        val androidAlias = container.toPath().resolve("android-data")
        val documentId = DocumentId.new()
        try {
            try {
                Files.createSymbolicLink(androidAlias, container.toPath())
            } catch (error: FileSystemException) {
                assumeNoException("Windows symbolic-link privilege is unavailable", error)
                return
            }

            val presentedFilesDir = androidAlias.resolve("files").toFile()
            val photoRoot = File(
                presentedFilesDir,
                "documents/${documentId.value}/photos"
            )
            val recordingFactory = RecordingTrustedRootFactory()
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                photoRoot,
                mapOf("photo.jpg" to Stage4PhotoFixture.jpegBytes()),
                recordingFactory,
                trustedRootDirectory = presentedFilesDir
            )
            try {
                assertEquals(
                    presentedFilesDir.toPath().toAbsolutePath().normalize(),
                    recordingFactory.trustedRoot
                )
            } finally {
                runBlocking { transaction.rollback() }
            }

            var strictRejected = false
            try {
                StagedPhotoContentTransaction.stageForTesting(
                    photoRoot,
                    mapOf("photo.jpg" to Stage4PhotoFixture.jpegBytes()),
                    TestPhotoPathOperationsFactory
                )
            } catch (_: Stage5ValidationException) {
                strictRejected = true
            }
            assertTrue(
                "generic staging without an explicit trusted root must reject the alias",
                strictRejected
            )
        } finally {
            Files.deleteIfExists(androidAlias)
            actualFiles.deleteRecursively()
            container.deleteRecursively()
        }
    }

    @Test
    fun crossStoreRecovery_afterCanonicalDurability_finalizesPublishedPhotoSet() = runBlocking {
        val root = Files.createTempDirectory("stage4-photo-cross-store-finalize").toFile()
        val documentId = DocumentId.new()
        val previous = snapshotForPhoto("old.jpg", "previous")
        val intended = snapshotForPhoto("new.jpg", "intended")
        try {
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("new.jpg" to Stage4PhotoFixture.jpegBytes()),
                TestPhotoPathOperationsFactory
            )
            transaction.prepareCanonicalRecovery(
                photoCanonicalIdentity(documentId, previous),
                photoCanonicalIdentity(documentId, intended)
            )
            transaction.publish()

            // Simulated process death after canonical durability/live apply but
            // before the photo COMMITTED marker.
            val reopened = PhotoPathResolver(
                root,
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
                assertTrue(File(root, "new.jpg").isFile)
                assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") })
            } finally {
                reopened.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun crossStoreRecovery_beforeCanonicalDurability_rollsBackPublishedPhotoSet() = runBlocking {
        val root = Files.createTempDirectory("stage4-photo-cross-store-rollback").toFile()
        val documentId = DocumentId.new()
        val previous = snapshotForPhoto("old.jpg", "previous")
        val intended = snapshotForPhoto("new.jpg", "intended")
        try {
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("new.jpg" to Stage4PhotoFixture.jpegBytes()),
                TestPhotoPathOperationsFactory
            )
            transaction.prepareCanonicalRecovery(
                photoCanonicalIdentity(documentId, previous),
                photoCanonicalIdentity(documentId, intended)
            )
            transaction.publish()

            // Simulated process death before canonical persistence/apply.
            val reopened = PhotoPathResolver(
                root,
                createRoot = true,
                operationsFactory = TestPhotoPathOperationsFactory
            )
            try {
                assertEquals(
                    PhotoRecoveryAction.ROLLED_BACK,
                    reopened.reconcilePhotoTransaction(
                        photoCanonicalIdentity(documentId, previous),
                        photoCanonicalIdentity(documentId, previous)
                    )
                )
                assertFalse(File(root, "new.jpg").exists())
                assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") })
            } finally {
                reopened.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun commitMarkerReadbackFailure_isAmbiguousAndRetainsAuthoritativeEvidence() = runBlocking {
        val root = Files.createTempDirectory("stage4-photo-commit-marker-readback").toFile()
        val documentId = DocumentId.new()
        val previous = snapshotForPhoto("photo.jpg", "readback-previous")
        val intended = snapshotForPhoto("photo.jpg", "readback-intended")
        val oldBytes = Stage4PhotoFixture.previousJpegBytes()
        val newBytes = Stage4PhotoFixture.incomingJpegBytes()
        val factory = FailOnPhotoCommitMarkerReadbackFactory()
        try {
            File(root, "photo.jpg").writeBytes(oldBytes)
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("photo.jpg" to newBytes),
                factory
            )
            transaction.prepareCanonicalRecovery(
                photoCanonicalIdentity(documentId, previous),
                photoCanonicalIdentity(documentId, intended)
            )
            transaction.publish()

            var failed = false
            try {
                transaction.commit()
            } catch (_: PhotoCanonicalRecoveryException) {
                failed = true
            }
            assertTrue("unreadable commit-marker readback must be typed recovery", failed)
            assertEquals(newBytes.toList(), File(root, "photo.jpg").readBytes().toList())
            assertTrue(File(root, ".stage5-photo-transaction.marker").isFile)
            assertTrue(File(root, ".stage5-photo-transaction.commit").isFile)
            assertTrue(File(root, ".stage5-photo-canonical.intent").isFile)

            transaction.releaseAfterFailure()
            val reopened = PhotoPathResolver(
                root,
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
                assertEquals(newBytes.toList(), File(root, "photo.jpg").readBytes().toList())
                assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") })
            } finally {
                reopened.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun persistentCommitMarkerReadbackAmbiguity_retainsExactEvidenceUntilFreshSafeRecovery() = runBlocking {
        val root = Files.createTempDirectory("stage4-photo-persistent-commit-marker-readback").toFile()
        val documentId = DocumentId.new()
        val previous = snapshotForPhoto("photo.jpg", "persistent-readback-previous")
        val intended = snapshotForPhoto("photo.jpg", "persistent-readback-intended")
        val oldBytes = Stage4PhotoFixture.previousJpegBytes()
        val newBytes = Stage4PhotoFixture.incomingJpegBytes()
        val factory = AlwaysFailOnPhotoCommitMarkerReadFactory()
        try {
            File(root, "photo.jpg").writeBytes(oldBytes)
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("photo.jpg" to newBytes),
                factory
            )
            transaction.prepareCanonicalRecovery(
                photoCanonicalIdentity(documentId, previous),
                photoCanonicalIdentity(documentId, intended)
            )
            transaction.publish()
            val journalBytes = File(root, ".stage5-photo-transaction.marker").readBytes()
            val intentBytes = File(root, ".stage5-photo-canonical.intent").readBytes()
            val expectedCommit = "COMMITTED\n${sha256Hex(journalBytes)}\n".toByteArray()

            var failure: PhotoCanonicalRecoveryException? = null
            try {
                transaction.commit()
            } catch (error: PhotoCanonicalRecoveryException) {
                failure = error
            }
            assertTrue("persistent marker ambiguity must be typed recovery", failure != null)
            assertTrue(
                "persistent marker ambiguity must conservatively retain new authority",
                transaction.hasAuthoritativeCommit()
            )
            assertEquals(newBytes.toList(), File(root, "photo.jpg").readBytes().toList())
            assertEquals(journalBytes.toList(), File(root, ".stage5-photo-transaction.marker").readBytes().toList())
            assertEquals(intentBytes.toList(), File(root, ".stage5-photo-canonical.intent").readBytes().toList())
            assertEquals(expectedCommit.toList(), File(root, ".stage5-photo-transaction.commit").readBytes().toList())

            transaction.releaseAfterFailure()
            assertEquals(factory.opened, factory.closed)
            assertEquals(0, factory.usedAfterClose)

            val freshFactory = AlwaysFailOnPhotoCommitMarkerReadFactory()
            var freshFailed = false
            try {
                PhotoPathResolver(
                    root,
                    createRoot = true,
                    operationsFactory = freshFactory
                )
            } catch (_: PhotoCanonicalRecoveryException) {
                freshFailed = true
            }
            assertTrue("persistent marker ambiguity must block fresh recovery", freshFailed)
            assertEquals(freshFactory.opened, freshFactory.closed)
            assertEquals(0, freshFactory.usedAfterClose)

            val recovered = PhotoPathResolver(
                root,
                createRoot = true,
                operationsFactory = TestPhotoPathOperationsFactory
            )
            try {
                assertEquals(
                    PhotoRecoveryAction.FINALIZED,
                    recovered.reconcilePhotoTransaction(
                        photoCanonicalIdentity(documentId, intended),
                        photoCanonicalIdentity(documentId, intended)
                    )
                )
                assertEquals(newBytes.toList(), File(root, "photo.jpg").readBytes().toList())
                assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") })
            } finally {
                recovered.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun digestlessV3CanonicalIntent_failsFreshRecoveryClosedAndRetainsEvidence() = runBlocking {
        val root = Files.createTempDirectory("stage4-photo-digestless-v3-intent").toFile()
        val documentId = DocumentId.new()
        val previous = snapshotForPhoto("photo.jpg", "digestless-v3-previous")
        val intended = snapshotForPhoto("photo.jpg", "digestless-v3-intended")
        val oldBytes = Stage4PhotoFixture.previousJpegBytes()
        val newBytes = Stage4PhotoFixture.incomingJpegBytes()
        try {
            File(root, "photo.jpg").writeBytes(oldBytes)
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("photo.jpg" to newBytes),
                TestPhotoPathOperationsFactory
            )
            transaction.prepareCanonicalRecovery(
                photoCanonicalIdentity(documentId, previous),
                photoCanonicalIdentity(documentId, intended),
                PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE
            )
            transaction.publish()
            val journalBytes = File(root, ".stage5-photo-transaction.marker").readBytes()
            val journalIdentity = sha256Hex(journalBytes)
            val digestlessIntent = listOf(
                "SOTAWARE_STAGE5_PHOTO_CANONICAL_V3",
                journalIdentity,
                "REMOTE_ACCEPTANCE",
                encodeRecoveryValueForTest(documentId.value),
                encodeRecoveryValueForTest(photoCanonicalIdentity(documentId, previous).snapshotDigest),
                encodeRecoveryValueForTest(previous.source.sourceUri),
                encodeRecoveryValueForTest(documentId.value),
                encodeRecoveryValueForTest(photoCanonicalIdentity(documentId, intended).snapshotDigest),
                encodeRecoveryValueForTest(intended.source.sourceUri)
            ).joinToString("\n", postfix = "\n").toByteArray()
            transaction.releaseAfterFailure()
            File(root, ".stage5-photo-canonical.intent").writeBytes(digestlessIntent)

            var failed = false
            try {
                PhotoPathResolver(
                    root,
                    createRoot = true,
                    operationsFactory = TestPhotoPathOperationsFactory
                )
            } catch (_: PhotoCanonicalRecoveryException) {
                failed = true
            }
            assertTrue("digest-less V3 intent must fail fresh recovery", failed)
            assertEquals(journalBytes.toList(), File(root, ".stage5-photo-transaction.marker").readBytes().toList())
            assertEquals(digestlessIntent.toList(), File(root, ".stage5-photo-canonical.intent").readBytes().toList())
            assertEquals(newBytes.toList(), File(root, "photo.jpg").readBytes().toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedCommitMarker_isAmbiguousAndCannotAuthorizeRollback() = runBlocking {
        val root = Files.createTempDirectory("stage4-photo-malformed-commit-marker").toFile()
        val documentId = DocumentId.new()
        val previous = snapshotForPhoto("photo.jpg", "malformed-previous")
        val intended = snapshotForPhoto("photo.jpg", "malformed-intended")
        val oldBytes = Stage4PhotoFixture.previousJpegBytes()
        val newBytes = Stage4PhotoFixture.incomingJpegBytes()
        val factory = CloseEnforcingPhotoPathOperationsFactory()
        try {
            File(root, "photo.jpg").writeBytes(oldBytes)
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("photo.jpg" to newBytes),
                factory
            )
            transaction.prepareCanonicalRecovery(
                photoCanonicalIdentity(documentId, previous),
                photoCanonicalIdentity(documentId, intended)
            )
            transaction.publish()
            val malformed = "COMMITTED\n".toByteArray()
            File(root, ".stage5-photo-transaction.commit").writeBytes(malformed)

            var failed = false
            try {
                transaction.rollback()
            } catch (_: PhotoCanonicalRecoveryException) {
                failed = true
            }
            assertTrue("malformed commit marker must block rollback", failed)
            assertEquals(newBytes.toList(), File(root, "photo.jpg").readBytes().toList())
            assertEquals(malformed.toList(), File(root, ".stage5-photo-transaction.commit").readBytes().toList())
            transaction.releaseAfterFailure()
            assertEquals(factory.opened, factory.closed)
            assertEquals(0, factory.usedAfterClose)

            var reopenedFailed = false
            try {
                PhotoPathResolver(
                    root,
                    createRoot = true,
                    operationsFactory = TestPhotoPathOperationsFactory
                )
            } catch (_: PhotoCanonicalRecoveryException) {
                reopenedFailed = true
            }
            assertTrue("fresh recovery must retain ambiguous marker evidence", reopenedFailed)
            assertEquals(malformed.toList(), File(root, ".stage5-photo-transaction.commit").readBytes().toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun crossStoreRecovery_mixedCanonicalAuthorities_failsClosedAndRetainsEvidence() = runBlocking {
        val root = Files.createTempDirectory("stage4-photo-cross-store-ambiguous").toFile()
        val documentId = DocumentId.new()
        val previous = snapshotForPhoto("old.jpg", "previous")
        val intended = snapshotForPhoto("new.jpg", "intended")
        try {
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("new.jpg" to Stage4PhotoFixture.jpegBytes()),
                TestPhotoPathOperationsFactory
            )
            transaction.prepareCanonicalRecovery(
                photoCanonicalIdentity(documentId, previous),
                photoCanonicalIdentity(documentId, intended)
            )
            transaction.publish()

            val reopened = PhotoPathResolver(
                root,
                createRoot = true,
                operationsFactory = TestPhotoPathOperationsFactory
            )
            try {
                var failedClosed = false
                try {
                    reopened.reconcilePhotoTransaction(
                        photoCanonicalIdentity(documentId, previous),
                        photoCanonicalIdentity(documentId, intended)
                    )
                } catch (_: PhotoCanonicalRecoveryException) {
                    failedClosed = true
                }
                assertTrue(failedClosed)
                assertTrue(File(root, "new.jpg").isFile)
                assertTrue(File(root, ".stage5-photo-canonical.intent").isFile)
            } finally {
                reopened.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reopenAfterInterruptedReplacement_restoresPreviousGoodBytesAndClearsJournal() {
        val root = Files.createTempDirectory("stage4-photo-reopen").toFile()
        val oldBytes = "previous-good".toByteArray()
        try {
            val resolver = PhotoPathResolver(root, createRoot = true, operationsFactory = TestPhotoPathOperationsFactory)
            val target = resolver.resolve("photo.jpg")
            target.writeBytes(oldBytes)
            val staged = resolver.newInternalFile("stage5-photo", ".tmp")
            val backup = resolver.newInternalFile("stage5-photo", ".bak")
            resolver.writeBytes(staged.toPath(), Stage4PhotoFixture.jpegBytes(), "interrupted staging")
            resolver.beginPhotoTransaction(
                listOf(
                    PhotoTransactionJournalEntry(
                        stagedName = staged.name,
                        targetName = target.name,
                        backupName = backup.name,
                        targetExisted = true
                    )
                )
            )
            resolver.atomicMove(target.toPath(), backup.toPath())
            resolver.close()

            val reopened = PhotoPathResolver(root, createRoot = true, operationsFactory = TestPhotoPathOperationsFactory)
            try {
                assertEquals(oldBytes.toList(), reopened.resolve("photo.jpg").readBytes().toList())
                assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") })
            } finally {
                reopened.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reopenWithCommittedMarkerAndMissingTarget_failsClosedAndRetainsEvidence() {
        val root = Files.createTempDirectory("stage4-photo-ambiguous-reopen").toFile()
        try {
            val resolver = PhotoPathResolver(root, createRoot = true, operationsFactory = TestPhotoPathOperationsFactory)
            val target = resolver.resolve("photo.jpg")
            target.writeBytes(Stage4PhotoFixture.jpegBytes())
            val staged = resolver.newInternalFile("stage5-photo", ".tmp")
            val backup = resolver.newInternalFile("stage5-photo", ".bak")
            resolver.writeBytes(staged.toPath(), Stage4PhotoFixture.jpegBytes(), "committed staging")
            resolver.beginPhotoTransaction(
                listOf(
                    PhotoTransactionJournalEntry(staged.name, target.name, backup.name, targetExisted = true)
                )
            )
            resolver.markPhotoTransactionCommitted()
            Files.delete(target.toPath())
            resolver.close()

            var failedClosed = false
            try {
                PhotoPathResolver(root, createRoot = true, operationsFactory = TestPhotoPathOperationsFactory)
            } catch (_: Stage5ValidationException) {
                failedClosed = true
            } catch (_: PhotoCanonicalRecoveryException) {
                failedClosed = true
            }
            assertTrue(failedClosed)
            assertTrue(File(root, ".stage5-photo-transaction.commit").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun staleTransactionCannotCommitOrClearAReplacedJournal() = runBlocking {
        val root = Files.createTempDirectory("stage4-photo-stale-transaction").toFile()
        try {
            val stale = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("stale.jpg" to Stage4PhotoFixture.jpegBytes()),
                TestPhotoPathOperationsFactory
            )
            // Simulate the original journal being replaced after the stale
            // transaction object lost ownership of its durable evidence.
            Files.delete(File(root, ".stage5-photo-transaction.marker").toPath())
            val current = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("current.jpg" to Stage4PhotoFixture.jpegBytes()),
                TestPhotoPathOperationsFactory
            )
            val marker = File(root, ".stage5-photo-transaction.marker")
                .readBytes()

            var failedClosed = false
            try {
                stale.commit()
            } catch (_: PhotoCanonicalRecoveryException) {
                failedClosed = true
            }

            assertTrue(failedClosed)
            assertTrue(marker.contentEquals(File(root, ".stage5-photo-transaction.marker").readBytes()))

            current.rollback()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reopenedCanonicalIntentRejectsReplacedJournalWithoutMutatingEvidence() = runBlocking {
        val root = Files.createTempDirectory("stage4-photo-intent-journal-replacement").toFile()
        val documentId = DocumentId.new()
        val previous = snapshotForPhoto("photo.jpg", "previous")
        val intended = snapshotForPhoto("photo.jpg", "intended")
        try {
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("photo.jpg" to Stage4PhotoFixture.jpegBytes()),
                TestPhotoPathOperationsFactory
            )
            transaction.prepareCanonicalRecovery(
                photoCanonicalIdentity(documentId, previous),
                photoCanonicalIdentity(documentId, intended)
            )

            val journalFile = File(root, ".stage5-photo-transaction.marker")
            val intentFile = File(root, ".stage5-photo-canonical.intent")
            val originalIntent = intentFile.readBytes()
            val stagedFile = requireNotNull(
                root.listFiles().orEmpty().single { it.name.matches(Regex("\\.stage5-photo-[A-Za-z0-9-]+\\.tmp")) }
            )
            val stagedBytes = stagedFile.readBytes()
            val unrelated = File(root, "unrelated.jpg").apply {
                writeBytes("unrelated-before".toByteArray())
            }
            val unrelatedBytes = unrelated.readBytes()

            // This is syntactically valid evidence for another transaction,
            // but its digest no longer matches the intent prepared above.
            val replacementJournal = """
                SOTAWARE_STAGE5_PHOTO_TRANSACTION_V1
                PREPARED
                1
                .stage5-photo-replaced-11111111.tmp	unrelated.jpg	.stage5-photo-replaced-22222222.bak	1
            """.trimIndent().plus("\n").toByteArray()
            journalFile.writeBytes(replacementJournal)

            var failedClosed = false
            try {
                PhotoPathResolver(
                    root,
                    createRoot = true,
                    operationsFactory = TestPhotoPathOperationsFactory
                )
                throw AssertionError("replaced journal must not be paired with the old intent")
            } catch (_: PhotoCanonicalRecoveryException) {
                failedClosed = true
            }

            assertTrue("replaced journal must fail closed", failedClosed)
            assertEquals(replacementJournal.toList(), journalFile.readBytes().toList())
            assertEquals(originalIntent.toList(), intentFile.readBytes().toList())
            assertEquals(stagedBytes.toList(), stagedFile.readBytes().toList())
            assertEquals(unrelatedBytes.toList(), unrelated.readBytes().toList())
            assertFalse(File(root, "photo.jpg").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyCanonicalIntentRemainsEvidenceWhenJournalOwnershipCannotBeProven() {
        val root = Files.createTempDirectory("stage4-photo-legacy-intent").toFile()
        val documentId = DocumentId.new()
        val previous = photoCanonicalIdentity(documentId, snapshotForPhoto("photo.jpg", "previous"))
        val intended = photoCanonicalIdentity(documentId, snapshotForPhoto("photo.jpg", "intended"))
        fun encode(value: String): String = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
        try {
            val resolver = PhotoPathResolver(
                root,
                createRoot = true,
                operationsFactory = TestPhotoPathOperationsFactory
            )
            val journalEntry = PhotoTransactionJournalEntry(
                stagedName = ".stage5-photo-legacy-11111111.tmp",
                targetName = "photo.jpg",
                backupName = ".stage5-photo-legacy-22222222.bak",
                targetExisted = false
            )
            resolver.beginPhotoTransaction(listOf(journalEntry))
            resolver.close()

            val journalFile = File(root, ".stage5-photo-transaction.marker")
            val intentFile = File(root, ".stage5-photo-canonical.intent")
            val journalBytes = journalFile.readBytes()
            val legacyIntent = listOf(
                "SOTAWARE_STAGE5_PHOTO_CANONICAL_V1",
                encode(previous.documentId),
                encode(previous.snapshotDigest),
                encode(previous.sourceUri),
                encode(intended.documentId),
                encode(intended.snapshotDigest),
                encode(intended.sourceUri)
            ).joinToString("\n", postfix = "\n").toByteArray(Charsets.US_ASCII)
            intentFile.writeBytes(legacyIntent)

            var failedClosed = false
            try {
                PhotoPathResolver(
                    root,
                    createRoot = true,
                    operationsFactory = TestPhotoPathOperationsFactory
                )
                throw AssertionError("legacy intent must not claim an unowned journal")
            } catch (_: PhotoCanonicalRecoveryException) {
                failedClosed = true
            }

            assertTrue("legacy intent must fail closed", failedClosed)
            assertEquals(journalBytes.toList(), journalFile.readBytes().toList())
            assertEquals(legacyIntent.toList(), intentFile.readBytes().toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun bareLegacyCommittedMarkerCannotFinalizeAReplacedJournal() {
        val root = Files.createTempDirectory("stage4-photo-bare-commit-replacement").toFile()
        try {
            val resolver = PhotoPathResolver(
                root,
                createRoot = true,
                operationsFactory = TestPhotoPathOperationsFactory
            )
            val staged = resolver.newInternalFile("stage5-photo", ".tmp")
            val backup = resolver.newInternalFile("stage5-photo", ".bak")
            resolver.writeBytes(staged.toPath(), Stage4PhotoFixture.jpegBytes(), "legacy staged bytes")
            resolver.beginPhotoTransaction(
                listOf(PhotoTransactionJournalEntry(staged.name, "photo.jpg", backup.name, false))
            )
            resolver.close()

            val journalFile = File(root, ".stage5-photo-transaction.marker")
            val replacedJournal = """
                SOTAWARE_STAGE5_PHOTO_TRANSACTION_V1
                PREPARED
                1
                .stage5-photo-replaced-11111111.tmp	unrelated.jpg	.stage5-photo-replaced-22222222.bak	0
            """.trimIndent().plus("\n").toByteArray(Charsets.US_ASCII)
            journalFile.writeBytes(replacedJournal)
            val commitFile = File(root, ".stage5-photo-transaction.commit")
            commitFile.writeBytes("COMMITTED\n".toByteArray(Charsets.US_ASCII))
            val unrelated = File(root, "unrelated.jpg").apply {
                writeBytes("unrelated-before".toByteArray())
            }
            val journalBefore = journalFile.readBytes()
            val commitBefore = commitFile.readBytes()
            val unrelatedBefore = unrelated.readBytes()

            var failedClosed = false
            try {
                PhotoPathResolver(
                    root,
                    createRoot = true,
                    operationsFactory = TestPhotoPathOperationsFactory
                )
            } catch (_: PhotoCanonicalRecoveryException) {
                failedClosed = true
            }
            assertTrue("bare legacy commit must not own a replaced journal", failedClosed)
            assertEquals(journalBefore.toList(), journalFile.readBytes().toList())
            assertEquals(commitBefore.toList(), commitFile.readBytes().toList())
            assertEquals(unrelatedBefore.toList(), unrelated.readBytes().toList())
            assertFalse(File(root, "photo.jpg").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun versionedCommittedMarkerStillFinalizesItsExactJournal() {
        val root = Files.createTempDirectory("stage4-photo-versioned-commit").toFile()
        try {
            val resolver = PhotoPathResolver(root, createRoot = true, operationsFactory = TestPhotoPathOperationsFactory)
            val target = resolver.resolve("photo.jpg")
            val staged = resolver.newInternalFile("stage5-photo", ".tmp")
            val backup = resolver.newInternalFile("stage5-photo", ".bak")
            resolver.writeBytes(staged.toPath(), Stage4PhotoFixture.jpegBytes(), "versioned staged bytes")
            val identity = resolver.beginPhotoTransaction(
                listOf(PhotoTransactionJournalEntry(staged.name, target.name, backup.name, false))
            )
            resolver.atomicMove(staged.toPath(), target.toPath())
            resolver.markPhotoTransactionCommitted(identity)
            resolver.close()

            val reopened = PhotoPathResolver(root, createRoot = true, operationsFactory = TestPhotoPathOperationsFactory)
            try {
                assertTrue(reopened.resolve("photo.jpg").isFile)
                assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") })
            } finally {
                reopened.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preCommitMarkerFailureKeepsResolverOpenForCompleteOldPhotoRollback() = runBlocking {
        val root = Files.createTempDirectory("stage4-photo-marker-close-boundary").toFile()
        val factory = CloseEnforcingPhotoPathOperationsFactory(failCommitMarker = true)
        val oldBytes = "old-photo-bytes".toByteArray()
        val documentId = DocumentId.new()
        val previous = snapshotForPhoto("photo.jpg", "previous")
        val intended = snapshotForPhoto("photo.jpg", "intended")
        try {
            File(root, "photo.jpg").writeBytes(oldBytes)
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("photo.jpg" to Stage4PhotoFixture.jpegBytes()),
                factory
            )
            transaction.prepareCanonicalRecovery(
                photoCanonicalIdentity(documentId, previous),
                photoCanonicalIdentity(documentId, intended)
            )
            transaction.publish()

            var failed = false
            try {
                transaction.commit()
            } catch (_: IOException) {
                failed = true
            }
            assertTrue("commit marker failure must be surfaced", failed)

            // The same live descriptor must still be capable of restoring the
            // old target and clearing both transaction markers.
            transaction.rollback()
            assertEquals(oldBytes.toList(), File(root, "photo.jpg").readBytes().toList())
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") })
            assertEquals(1, factory.opened)
            assertEquals(1, factory.closed)
            assertEquals(0, factory.usedAfterClose)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun partialCommitAndRollbackMarkerCleanup_isRestartSafeForEveryMarker() = runBlocking {
        val markerNames = listOf(
            ".stage5-photo-canonical.intent",
            ".stage5-photo-metadata.commit",
            ".stage5-photo-transaction.commit",
            ".stage5-photo-transaction.marker",
            ".stage5-photo-transaction.cleanup"
        )
        markerNames.forEach { markerName ->
            exerciseV3MarkerCleanupFailure(markerName, commit = true)
            exerciseV3MarkerCleanupFailure(markerName, commit = false)
        }
    }

    @Test
    fun unequalLiveSnapshotCleanup_isRestartSafeBeforeAndAfterSidecarDeletion() = runBlocking {
        listOf(
            ".stage5-photo-canonical.live" to true,
            ".stage5-photo-canonical.intent" to false
        ).forEach { (failedMarker, sidecarMustRemain) ->
            listOf(true, false).forEach { commit ->
                val root = Files.createTempDirectory(
                    if (commit) "stage4-photo-live-cleanup-commit" else "stage4-photo-live-cleanup-rollback"
                ).toFile()
                try {
                    val oldBytes = Stage4PhotoFixture.previousJpegBytes()
                    val newBytes = Stage4PhotoFixture.incomingJpegBytes()
                    val documentId = DocumentId.new()
                    val previous = snapshotForPhoto(
                        "photo.jpg",
                        if (commit) "live-cleanup-commit-durable" else "live-cleanup-rollback-durable"
                    )
                    val previousLive = snapshotForPhoto(
                        "photo.jpg",
                        if (commit) "live-cleanup-commit-live" else "live-cleanup-rollback-live"
                    )
                    val intended = snapshotForPhoto(
                        "photo.jpg",
                        if (commit) "live-cleanup-commit-intended" else "live-cleanup-rollback-intended"
                    )
                    File(root, "photo.jpg").writeBytes(oldBytes)
                    val operationsFactory = FailOnPhotoMarkerDeleteFactory(failedMarker)
                    val transaction = StagedPhotoContentTransaction.stageForTesting(
                        root,
                        mapOf("photo.jpg" to newBytes),
                        operationsFactory
                    )
                    transaction.prepareCanonicalRecovery(
                        photoCanonicalIdentity(documentId, previous),
                        photoCanonicalIdentity(documentId, previousLive),
                        previousLive,
                        photoCanonicalIdentity(documentId, intended),
                        PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE
                    )
                    transaction.publish()
                    transaction.markMetadataCommitted()
                    val journalBytes = File(root, ".stage5-photo-transaction.marker").readBytes()
                    val intentBytes = File(root, ".stage5-photo-canonical.intent").readBytes()
                    val metadataBytes = File(root, ".stage5-photo-metadata.commit").readBytes()
                    val liveArtifactBytes = File(root, ".stage5-photo-canonical.live").readBytes()
                    val commitBytes = "COMMITTED\n${sha256Hex(journalBytes)}\n"
                        .toByteArray()

                    var failure: Throwable? = null
                    try {
                        if (commit) transaction.commit() else transaction.rollback()
                    } catch (error: PhotoCanonicalRecoveryException) {
                        failure = error
                    } catch (error: PhotoRollbackException) {
                        failure = error
                    }
                    assertTrue(
                        "${if (commit) "commit" else "rollback"} must surface sidecar cleanup failure",
                        failure != null
                    )
                    if (commit) {
                        assertTrue(failure is PhotoCanonicalRecoveryException)
                    } else {
                        assertTrue(failure is PhotoRollbackException)
                    }

                    val phase = File(root, ".stage5-photo-canonical.live.cleanup")
                    assertTrue("sidecar cleanup phase must be retained", phase.isFile)
                    assertEquals(
                        "SOTAWARE_STAGE5_PHOTO_CANONICAL_LIVE_CLEANUP_V1\n" +
                            "${sha256Hex(journalBytes)}\n",
                        phase.readText()
                    )
                    assertEquals(
                        "photo bytes must remain the expected ${if (commit) "new" else "old"} state",
                        (if (commit) newBytes else oldBytes).toList(),
                        File(root, "photo.jpg").readBytes().toList()
                    )
                    if (sidecarMustRemain) {
                        assertEquals(
                            "live sidecar bytes must remain exact before deletion",
                            liveArtifactBytes.toList(),
                            File(root, ".stage5-photo-canonical.live").readBytes().toList()
                        )
                    } else {
                        assertFalse("sidecar must already be deleted after its phase", File(root, ".stage5-photo-canonical.live").exists())
                    }
                    assertEquals(journalBytes.toList(), File(root, ".stage5-photo-transaction.marker").readBytes().toList())
                    assertEquals(intentBytes.toList(), File(root, ".stage5-photo-canonical.intent").readBytes().toList())
                    assertEquals(metadataBytes.toList(), File(root, ".stage5-photo-metadata.commit").readBytes().toList())
                    if (commit) {
                        assertEquals(commitBytes.toList(), File(root, ".stage5-photo-transaction.commit").readBytes().toList())
                    } else {
                        assertFalse(File(root, ".stage5-photo-transaction.commit").exists())
                    }
                    assertEquals(
                        "SOTAWARE_STAGE5_PHOTO_CLEANUP_V1\n${sha256Hex(journalBytes)}\n",
                        File(root, ".stage5-photo-transaction.cleanup").readText()
                    )

                    transaction.releaseAfterFailure()
                    assertEquals(operationsFactory.opened, operationsFactory.closed)
                    assertEquals(0, operationsFactory.usedAfterClose)

                    val reopened = PhotoPathResolver(
                        root,
                        createRoot = true,
                        operationsFactory = TestPhotoPathOperationsFactory
                    )
                    try {
                        reopened.requireCanonicalRecoveryResolved()
                        assertEquals(
                            (if (commit) newBytes else oldBytes).toList(),
                            File(root, "photo.jpg").readBytes().toList()
                        )
                        assertTrue(
                            "fresh recovery must remove the phase and every owner",
                            root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") }
                        )
                    } finally {
                        reopened.close()
                    }
                } finally {
                    root.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun unequalLiveSnapshotCleanupPhaseFailure_leavesOnlyBoundTerminalEvidence() = runBlocking {
        val root = Files.createTempDirectory("stage4-photo-live-cleanup-phase").toFile()
        try {
            val oldBytes = Stage4PhotoFixture.previousJpegBytes()
            val newBytes = Stage4PhotoFixture.incomingJpegBytes()
            val documentId = DocumentId.new()
            val previous = snapshotForPhoto("photo.jpg", "live-phase-durable")
            val previousLive = snapshotForPhoto("photo.jpg", "live-phase-live")
            val intended = snapshotForPhoto("photo.jpg", "live-phase-intended")
            File(root, "photo.jpg").writeBytes(oldBytes)
            val operationsFactory = FailOnPhotoMarkerDeleteFactory(
                ".stage5-photo-canonical.live.cleanup"
            )
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("photo.jpg" to newBytes),
                operationsFactory
            )
            transaction.prepareCanonicalRecovery(
                photoCanonicalIdentity(documentId, previous),
                photoCanonicalIdentity(documentId, previousLive),
                previousLive,
                photoCanonicalIdentity(documentId, intended),
                PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE
            )
            transaction.publish()
            transaction.markMetadataCommitted()
            val journalBytes = File(root, ".stage5-photo-transaction.marker").readBytes()

            var failure: Throwable? = null
            try {
                transaction.commit()
            } catch (error: PhotoCanonicalRecoveryException) {
                failure = error
            }
            assertTrue("phase deletion failure must be typed", failure is PhotoCanonicalRecoveryException)
            assertEquals(
                "SOTAWARE_STAGE5_PHOTO_CANONICAL_LIVE_CLEANUP_V1\n${sha256Hex(journalBytes)}\n",
                File(root, ".stage5-photo-canonical.live.cleanup").readText()
            )
            assertFalse(File(root, ".stage5-photo-canonical.live").exists())
            assertFalse(File(root, ".stage5-photo-canonical.intent").exists())
            assertFalse(File(root, ".stage5-photo-metadata.commit").exists())
            assertFalse(File(root, ".stage5-photo-transaction.commit").exists())
            assertFalse(File(root, ".stage5-photo-transaction.marker").exists())
            assertFalse(File(root, ".stage5-photo-transaction.cleanup").exists())
            assertEquals(newBytes.toList(), File(root, "photo.jpg").readBytes().toList())

            transaction.releaseAfterFailure()
            assertEquals(operationsFactory.opened, operationsFactory.closed)
            assertEquals(0, operationsFactory.usedAfterClose)

            val reopened = PhotoPathResolver(
                root,
                createRoot = true,
                operationsFactory = TestPhotoPathOperationsFactory
            )
            try {
                reopened.requireCanonicalRecoveryResolved()
                assertTrue(
                    "fresh recovery must remove the terminal phase marker",
                    root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") }
                )
                assertEquals(newBytes.toList(), File(root, "photo.jpg").readBytes().toList())
            } finally {
                reopened.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unequalLiveSnapshotCleanupPhase_survivesProcessDeathBeforeCleanupMarker() = runBlocking {
        val root = Files.createTempDirectory("stage4-photo-live-cleanup-before-marker").toFile()
        try {
            val oldBytes = Stage4PhotoFixture.previousJpegBytes()
            val newBytes = Stage4PhotoFixture.incomingJpegBytes()
            val documentId = DocumentId.new()
            val previous = snapshotForPhoto("photo.jpg", "live-before-marker-durable")
            val previousLive = snapshotForPhoto("photo.jpg", "live-before-marker-live")
            val intended = snapshotForPhoto("photo.jpg", "live-before-marker-intended")
            File(root, "photo.jpg").writeBytes(oldBytes)
            val operationsFactory = FailOnPhotoMarkerCreateFactory(
                ".stage5-photo-transaction.cleanup"
            )
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("photo.jpg" to newBytes),
                operationsFactory
            )
            transaction.prepareCanonicalRecovery(
                photoCanonicalIdentity(documentId, previous),
                photoCanonicalIdentity(documentId, previousLive),
                previousLive,
                photoCanonicalIdentity(documentId, intended),
                PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE
            )
            transaction.publish()
            transaction.markMetadataCommitted()
            val journalBytes = File(root, ".stage5-photo-transaction.marker").readBytes()
            val intentBytes = File(root, ".stage5-photo-canonical.intent").readBytes()
            val metadataBytes = File(root, ".stage5-photo-metadata.commit").readBytes()
            val liveBytes = File(root, ".stage5-photo-canonical.live").readBytes()

            var failure: Throwable? = null
            try {
                transaction.commit()
            } catch (error: PhotoCanonicalRecoveryException) {
                failure = error
            }
            assertTrue("cleanup-marker creation failure must be typed", failure is PhotoCanonicalRecoveryException)
            assertEquals(
                "SOTAWARE_STAGE5_PHOTO_CANONICAL_LIVE_CLEANUP_V1\n${sha256Hex(journalBytes)}\n",
                File(root, ".stage5-photo-canonical.live.cleanup").readText()
            )
            assertEquals(journalBytes.toList(), File(root, ".stage5-photo-transaction.marker").readBytes().toList())
            assertEquals(intentBytes.toList(), File(root, ".stage5-photo-canonical.intent").readBytes().toList())
            assertEquals(metadataBytes.toList(), File(root, ".stage5-photo-metadata.commit").readBytes().toList())
            assertEquals(liveBytes.toList(), File(root, ".stage5-photo-canonical.live").readBytes().toList())
            assertEquals(newBytes.toList(), File(root, "photo.jpg").readBytes().toList())
            assertFalse(File(root, ".stage5-photo-transaction.cleanup").exists())

            transaction.releaseAfterFailure()
            val reopened = PhotoPathResolver(
                root,
                createRoot = true,
                operationsFactory = TestPhotoPathOperationsFactory
            )
            try {
                reopened.requireCanonicalRecoveryResolved()
                assertEquals(newBytes.toList(), File(root, "photo.jpg").readBytes().toList())
                assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") })
            } finally {
                reopened.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedLiveSnapshotCleanupPhase_failsClosedAndRetainsExactEvidence() {
        val root = Files.createTempDirectory("stage4-photo-live-cleanup-malformed").toFile()
        try {
            val phase = File(root, ".stage5-photo-canonical.live.cleanup")
            val phaseBytes = "SOTAWARE_STAGE5_PHOTO_CANONICAL_LIVE_CLEANUP_V1\nnot-a-journal-owner\n"
                .toByteArray()
            phase.writeBytes(phaseBytes)
            var failure: Throwable? = null
            try {
                PhotoPathResolver(
                    root,
                    createRoot = true,
                    operationsFactory = TestPhotoPathOperationsFactory
                )
            } catch (error: PhotoCanonicalRecoveryException) {
                failure = error
            }
            assertTrue("malformed cleanup phase must fail closed", failure != null)
            assertEquals(phaseBytes.toList(), phase.readBytes().toList())
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun exerciseV3MarkerCleanupFailure(
        markerName: String,
        commit: Boolean
    ) {
        val root = Files.createTempDirectory(
            if (commit) "stage4-photo-v3-partial-commit" else "stage4-photo-v3-partial-rollback"
        ).toFile()
        try {
            val oldBytes = Stage4PhotoFixture.previousJpegBytes()
            val newBytes = Stage4PhotoFixture.incomingJpegBytes()
            assertFalse(oldBytes.contentEquals(newBytes))
            assertTrue(sha256Hex(oldBytes) != sha256Hex(newBytes))
            val documentId = DocumentId.new()
            val previous = snapshotForPhoto(
                "photo.jpg",
                if (commit) "partial-v3-commit-previous" else "partial-v3-rollback-previous"
            )
            val intended = snapshotForPhoto(
                "photo.jpg",
                if (commit) "partial-v3-commit-intended" else "partial-v3-rollback-intended"
            )
            File(root, "photo.jpg").writeBytes(oldBytes)
            val operationsFactory = FailOnPhotoMarkerDeleteFactory(markerName)
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("photo.jpg" to newBytes),
                operationsFactory
            )
            transaction.prepareCanonicalRecovery(
                photoCanonicalIdentity(documentId, previous),
                photoCanonicalIdentity(documentId, intended),
                PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE
            )
            transaction.publish()
            transaction.markMetadataCommitted()

            val journalBytes = File(root, ".stage5-photo-transaction.marker").readBytes()
            val intentBytes = File(root, ".stage5-photo-canonical.intent").readBytes()
            val metadataBytes = File(root, ".stage5-photo-metadata.commit").readBytes()
            val transactionIdentity = sha256Hex(journalBytes)

            var failure: Throwable? = null
            try {
                if (commit) transaction.commit() else transaction.rollback()
            } catch (error: PhotoCanonicalRecoveryException) {
                failure = error
            } catch (error: PhotoRollbackException) {
                failure = error
            }
            assertTrue(
                "${if (commit) "commit" else "rollback"} cleanup failure must surface for $markerName",
                failure != null
            )
            if (commit) {
                assertTrue(failure is PhotoCanonicalRecoveryException)
            } else {
                assertTrue(failure is PhotoRollbackException)
            }
            assertV3MarkerEvidenceAfterFailure(
                root = root,
                failedMarker = markerName,
                commit = commit,
                journalBytes = journalBytes,
                intentBytes = intentBytes,
                metadataBytes = metadataBytes,
                transactionIdentity = transactionIdentity
            )
            assertEquals(
                "${if (commit) "committed" else "rolled-back"} photo bytes must stay authoritative for $markerName",
                if (commit) newBytes.toList() else oldBytes.toList(),
                File(root, "photo.jpg").readBytes().toList()
            )

            transaction.releaseAfterFailure()
            assertEquals(
                "every failed ${if (commit) "commit" else "rollback"} resolver must close",
                operationsFactory.opened,
                operationsFactory.closed
            )
            assertEquals(
                "failed ${if (commit) "commit" else "rollback"} cleanup must not use a closed resolver",
                0,
                operationsFactory.usedAfterClose
            )

            // A new resolver performs the durable cleanup reconciliation from
            // the retained V3 journal/evidence. The test creates a new
            // transaction for every marker and a new resolver for recovery.
            val reopened = PhotoPathResolver(
                root,
                createRoot = true,
                operationsFactory = TestPhotoPathOperationsFactory
            )
            try {
                reopened.requireCanonicalRecoveryResolved()
                assertEquals(
                    if (commit) newBytes.toList() else oldBytes.toList(),
                    File(root, "photo.jpg").readBytes().toList()
                )
                assertTrue(
                    "V3 cleanup must remove every marker after restart for $markerName",
                    root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") }
                )
            } finally {
                reopened.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertV3MarkerEvidenceAfterFailure(
        root: File,
        failedMarker: String,
        commit: Boolean,
        journalBytes: ByteArray,
        intentBytes: ByteArray,
        metadataBytes: ByteArray,
        transactionIdentity: String
    ) {
        val deletionOrder = listOf(
            ".stage5-photo-canonical.intent",
            ".stage5-photo-metadata.commit",
            ".stage5-photo-transaction.commit",
            ".stage5-photo-transaction.marker",
            ".stage5-photo-transaction.cleanup"
        )
        val failedIndex = deletionOrder.indexOf(failedMarker)
        require(failedIndex >= 0) { "unknown photo marker: $failedMarker" }
        val expectedBytes = linkedMapOf(
            ".stage5-photo-canonical.intent" to intentBytes,
            ".stage5-photo-metadata.commit" to metadataBytes,
            ".stage5-photo-transaction.commit" to if (commit) {
                "COMMITTED\n$transactionIdentity\n".toByteArray()
            } else {
                null
            },
            ".stage5-photo-transaction.marker" to journalBytes
        )
        expectedBytes.forEach { (name, bytes) ->
            val shouldRemain = bytes != null &&
                (deletionOrder.indexOf(name) >= failedIndex ||
                    (failedMarker == ".stage5-photo-transaction.cleanup" &&
                        name == ".stage5-photo-transaction.marker"))
            val file = File(root, name)
            if (shouldRemain) {
                assertTrue("retained V3 marker missing: $name", file.isFile)
                assertEquals("retained V3 marker changed: $name", bytes!!.toList(), file.readBytes().toList())
            } else {
                assertFalse("deleted V3 marker unexpectedly remains: $name", file.exists())
            }
        }
        val cleanup = File(root, ".stage5-photo-transaction.cleanup")
        assertTrue("cleanup evidence must remain after $failedMarker", cleanup.isFile)
        assertEquals(
            "SOTAWARE_STAGE5_PHOTO_CLEANUP_V1\n$transactionIdentity\n".toByteArray().toList(),
            cleanup.readBytes().toList()
        )
    }

    @Test
    fun remoteAcceptanceRecovery_requiresMetadataPhaseBeforeFinalizingAfterRestart() = runBlocking {
        val root = Files.createTempDirectory("stage4-photo-metadata-phase").toFile()
        val documentId = DocumentId.new()
        val previous = snapshotForPhoto("photo.jpg", "metadata-previous")
        val intended = snapshotForPhoto("photo.jpg", "metadata-intended")
        try {
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf("photo.jpg" to Stage4PhotoFixture.jpegBytes()),
                TestPhotoPathOperationsFactory
            )
            transaction.prepareCanonicalRecovery(
                photoCanonicalIdentity(documentId, previous),
                photoCanonicalIdentity(documentId, intended),
                PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE
            )
            transaction.publish()
            val journalBefore = File(root, ".stage5-photo-transaction.marker").readBytes()
            val intentBefore = File(root, ".stage5-photo-canonical.intent").readBytes()

            val beforeMetadata = PhotoPathResolver(
                root,
                createRoot = true,
                operationsFactory = TestPhotoPathOperationsFactory
            )
            try {
                var failedClosed = false
                try {
                    beforeMetadata.reconcilePhotoTransaction(
                        photoCanonicalIdentity(documentId, intended),
                        photoCanonicalIdentity(documentId, intended)
                    )
                } catch (_: PhotoCanonicalRecoveryException) {
                    failedClosed = true
                }
                assertTrue("restart before metadata phase must fail closed", failedClosed)
                assertEquals(journalBefore.toList(), File(root, ".stage5-photo-transaction.marker").readBytes().toList())
                assertEquals(intentBefore.toList(), File(root, ".stage5-photo-canonical.intent").readBytes().toList())
                assertTrue(File(root, "photo.jpg").isFile)
            } finally {
                beforeMetadata.close()
            }

            val beforeMetadataOld = PhotoPathResolver(
                root,
                createRoot = true,
                operationsFactory = TestPhotoPathOperationsFactory
            )
            try {
                var oldStateBlocked = false
                try {
                    beforeMetadataOld.reconcilePhotoTransaction(
                        photoCanonicalIdentity(documentId, previous),
                        photoCanonicalIdentity(documentId, previous)
                    )
                } catch (_: PhotoCanonicalRecoveryException) {
                    oldStateBlocked = true
                }
                assertTrue("restart without metadata phase must not infer old metadata", oldStateBlocked)
                assertEquals(journalBefore.toList(), File(root, ".stage5-photo-transaction.marker").readBytes().toList())
                assertEquals(intentBefore.toList(), File(root, ".stage5-photo-canonical.intent").readBytes().toList())
                assertTrue(File(root, "photo.jpg").isFile)
            } finally {
                beforeMetadataOld.close()
            }

            transaction.markMetadataCommitted()
            transaction.releaseAfterFailure()
            val afterMetadata = PhotoPathResolver(
                root,
                createRoot = true,
                operationsFactory = TestPhotoPathOperationsFactory
            )
            try {
                assertEquals(
                    PhotoRecoveryAction.FINALIZED,
                    afterMetadata.reconcilePhotoTransaction(
                        photoCanonicalIdentity(documentId, intended),
                        photoCanonicalIdentity(documentId, intended)
                    )
                )
                assertTrue(File(root, "photo.jpg").isFile)
                assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") })
            } finally {
                afterMetadata.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptPhotoTransactionMarkers_rejectDuplicateAndCrossCollidingInternalNames() {
        val cases = listOf(
            "duplicate staged" to listOf(
                ".stage5-photo-staged-11111111.tmp\tphoto-a.jpg\t.stage5-photo-backup-11111111.bak\t0",
                ".stage5-photo-staged-11111111.tmp\tphoto-b.jpg\t.stage5-photo-backup-22222222.bak\t0"
            ),
            "duplicate backup" to listOf(
                ".stage5-photo-staged-11111111.tmp\tphoto-a.jpg\t.stage5-photo-backup-11111111.bak\t0",
                ".stage5-photo-staged-22222222.tmp\tphoto-b.jpg\t.stage5-photo-backup-11111111.bak\t0"
            ),
            "staged-backup collision" to listOf(
                ".stage5-photo-shared-11111111.bak\tphoto-a.jpg\t.stage5-photo-backup-11111111.bak\t0",
                ".stage5-photo-staged-22222222.tmp\tphoto-b.jpg\t.stage5-photo-shared-11111111.bak\t0"
            )
        )
        cases.forEach { (label, entries) ->
            val root = Files.createTempDirectory("stage4-photo-marker-$label").toFile()
            try {
                val marker = buildString {
                    append("SOTAWARE_STAGE5_PHOTO_TRANSACTION_V1\n")
                    append("PREPARED\n2\n")
                    entries.forEach { append(it).append('\n') }
                }.toByteArray()
                val markerFile = File(root, ".stage5-photo-transaction.marker")
                markerFile.writeBytes(marker)

                var rejected = false
                try {
                    PhotoPathResolver(
                        root,
                        createRoot = true,
                        operationsFactory = TestPhotoPathOperationsFactory
                    )
                    throw AssertionError("$label marker must be rejected")
                } catch (_: Stage5ValidationException) {
                    rejected = true
                }
                assertTrue("$label marker must fail closed", rejected)
                assertTrue("$label marker evidence must remain", markerFile.isFile)
                assertEquals(marker.toList(), markerFile.readBytes().toList())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun stagingWriteFailure_cleansCurrentTempBeforeEntryIsRecorded() {
        val root = Files.createTempDirectory("stage4-photo-current-temp").toFile()
        try {
            var failed = false
            try {
                StagedPhotoContentTransaction.stageForTesting(
                    root,
                    mapOf("photo.jpg" to Stage4PhotoFixture.jpegBytes()),
                    CreateThenFailPhotoPathOperationsFactory()
                )
            } catch (_: IOException) {
                failed = true
            }

            assertTrue(failed)
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".stage5-photo-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun laterPhotoMoveFailure_restoresEveryPreviousPhotoByte() {
        val root = Files.createTempDirectory("stage4-photo-rollback").toFile()
        try {
            File(root, "first.jpg").writeBytes("old-first".toByteArray())
            File(root, "second.jpg").writeBytes("old-second".toByteArray())
            var moveCount = 0
            val transaction = StagedPhotoContentTransaction.stageForTesting(
                root,
                mapOf(
                    "first.jpg" to Stage4PhotoFixture.jpegBytes(),
                    "second.jpg" to Stage4PhotoFixture.jpegBytes()
                ),
                TestPhotoPathOperationsFactory,
                move = { source: Path, target: Path ->
                    moveCount++
                    if (moveCount == 3) throw IOException("injected later photo move failure")
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
            )

            var failed = false
            try {
                kotlinx.coroutines.runBlocking { transaction.publish() }
            } catch (_: IOException) {
                failed = true
            }

            assertTrue(failed)
            assertEquals("old-first", File(root, "first.jpg").readText())
            assertEquals("old-second", File(root, "second.jpg").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun snapshotForPhoto(name: String, marker: String): DocumentSnapshotV1 =
        DocumentSnapshotV1(
            schemaVersion = 1,
            snapshotRevision = 0L,
            source = DocumentSourceIdentityV1("content://stage4/cross-store", "plan.pdf"),
            pages = mapOf(
                0 to PageSnapshotV1(
                    notes = listOf(
                        com.example.myapplication.stage1.NoteSnapshotV1(
                            1f, 2f, marker, 12f, false, 0f
                        )
                    ),
                    photoPins = listOf(
                        PhotoPinSnapshotV1(
                            x = 0.5f,
                            y = 0.5f,
                            id = "cross-store-pin-$marker",
                            imageFileNames = listOf(name),
                            imageNotes = emptyMap(),
                            imageShapes = emptyMap()
                        )
                    )
                )
            )
        )

    private fun encodeRecoveryValueForTest(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
}

private class RecordingTrustedRootFactory : PhotoPathOperationsFactory {
    var trustedRoot: Path? = null

    override fun open(root: Path): PhotoPathOperations = TestPhotoPathOperationsFactory.open(root)

    override fun open(root: Path, trustedRoot: Path?): PhotoPathOperations {
        this.trustedRoot = trustedRoot
        return TestPhotoPathOperationsFactory.open(root)
    }
}
