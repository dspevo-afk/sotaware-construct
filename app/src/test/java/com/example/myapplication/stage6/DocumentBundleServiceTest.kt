package com.example.myapplication.stage6

import com.example.myapplication.PageData
import com.example.myapplication.restoreDocumentSessionTokenState
import com.example.myapplication.saveDocumentSessionTokenState
import com.example.myapplication.withVerifiedStage6ImportDocument
import com.example.myapplication.stage0.LegacyStateFixture
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.snapshotFromLegacyPageData
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentLoadResult
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.LocalDocumentRepository
import com.example.myapplication.stage2.LocalRepositoryError
import com.example.myapplication.stage2.RepositoryFailureInjector
import com.example.myapplication.stage2.RepositoryWritePhase
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage3.DocumentTransactionBarrier
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.SessionSnapshotApplyResult
import com.example.myapplication.stage4.PhotoContentTransaction
import com.example.myapplication.stage4.StagedPhotoContentTransaction
import com.example.myapplication.stage5.DefaultImageProbe
import com.example.myapplication.stage5.DocumentPhotoAssetStore
import com.example.myapplication.stage5.LegacyPageDataCodec
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.Stage5ValidationException
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import com.example.myapplication.stage5.readReferencedPhotos
import com.example.myapplication.stage5.sha256Hex
import com.example.myapplication.stage5.validatePhotoSet
import com.example.myapplication.stage5.validateSnapshot
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.LinkedHashMap
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class DocumentBundleServiceTest {
    private val source = DocumentSourceIdentityV1(
        sourceUri = "content://provider/plan-a.pdf",
        displayName = "plan.pdf",
        providerMetadata = mapOf("authority" to "provider")
    )
    private val sourceBytes = "verified-pdf-revision".toByteArray()
    private val fingerprint = SourceFingerprint.fromBytes(sourceBytes)
    private val photoOne = pngBytes(Color(220, 40, 40))
    private val photoTwo = pngBytes(Color(40, 80, 220))

    @Test
    fun pickerAnchorSavedState_roundTripsExactSessionIdentity() {
        val token = DocumentSessionToken(
            documentId = DocumentId.new(),
            sourceUri = "content://provider/plan-a.pdf?display=plan%2Fone",
            sourceFingerprint = fingerprint,
            generation = 41L
        )

        assertEquals(token, restoreDocumentSessionTokenState(saveDocumentSessionTokenState(token)))
        assertNull(restoreDocumentSessionTokenState(listOf("malformed")))
    }

    @Test
    fun fullyPopulatedBundle_roundTripsAllDomainsAndPhotoBytesThroughFreshRepository() = runBlocking {
        val snapshot = snapshotFromLegacyPageData(LegacyStateFixture.fullyPopulatedPageData(), source)
        val exportedId = DocumentId.new()
        val photoFiles = mapOf(
            LegacyStateFixture.PHOTO_ONE to photoOne,
            LegacyStateFixture.PHOTO_TWO to photoTwo
        )
        val service = DocumentBundleService()
        val bytes = service.encodeToByteArray(
            BundleExportInput(exportedId, source, fingerprint, snapshot, photoFiles)
        )
        val decoded = service.readBundle(ByteArrayInputStream(bytes))

        assertEquals(snapshot, decoded.snapshot)
        assertEquals(setOf(0, 2), decoded.snapshot.pages.keys)
        assertEquals(photoFiles.keys, decoded.photoFiles.keys)
        photoFiles.forEach { (name, expected) -> assertArrayEquals(expected, decoded.photoFiles.getValue(name)) }
        assertEquals(exportedId.value, decoded.manifest.exportedDocumentId)
        assertEquals(2, decoded.manifest.photos.size)

        // A fresh install allocates a new app identity for the verified source;
        // the exported ID is metadata only and is never reused.  Exercise the
        // real durable snapshot repository and document-scoped photo store so
        // this is a complete fresh-root round trip, not a snapshot-only check.
        val freshRoot = Files.createTempDirectory("stage6-fresh-repository").toFile()
        try {
            val freshRepository = LocalDocumentRepository(freshRoot)
            val targetSource = source.copy(sourceUri = "content://provider/copied-plan.pdf")
            val association = (freshRepository.resolveOrCreate(targetSource, fingerprint)
                as com.example.myapplication.stage2.ResolveDocumentResult.Resolved).association
            val targetId = association.documentId
            val rebound = service.rebindToVerifiedTarget(
                decoded,
                VerifiedBundleTarget(targetId, targetSource, fingerprint)
            )
            assertNotEquals(exportedId, association.documentId)
            assertEquals(targetId, association.documentId)
            assertEquals(targetSource, rebound.snapshot.source)

            DocumentPhotoAssetStore(
                freshRoot,
                targetId,
                DefaultImageProbe,
                TestPhotoPathOperationsFactory
            ).use { photoStore ->
                val host = RepositoryImportHost(
                    repository = freshRepository,
                    association = association,
                    initialSnapshot = emptySnapshot(targetSource)
                )
                val photoTransaction = StagedPhotoContentTransaction.stageForTesting(
                    photoStore.resolver.root,
                    rebound.photoFiles,
                    TestPhotoPathOperationsFactory
                )
                assertEquals(
                    BundleImportResult.Applied,
                    service.applyReboundBundleWithinDocumentTransaction(
                        rebound,
                        host,
                        photoTransaction
                    )
                )
            }

            // Reopen both authorities from the same root, as a new process or
            // fresh installation would, and verify exact bytes and fields.
            val reopenedRepository = LocalDocumentRepository(freshRoot)
            val loaded = reopenedRepository.load(association) as DocumentLoadResult.Loaded
            assertEquals(rebound.snapshot, loaded.snapshot)
            DocumentPhotoAssetStore(
                freshRoot,
                targetId,
                DefaultImageProbe,
                TestPhotoPathOperationsFactory
            ).use { reopenedPhotoStore ->
                val reopenedPhotos = reopenedPhotoStore.readReferencedPhotos(loaded.snapshot)
                assertEquals(photoFiles.keys, reopenedPhotos.keys)
                photoFiles.forEach { (name, expected) ->
                    val actual = reopenedPhotos.getValue(name)
                    assertArrayEquals(expected, actual)
                    assertEquals(sha256Hex(expected), sha256Hex(actual))
                    assertEquals(expected.size, actual.size)
                }
            }
        } finally {
            freshRoot.deleteRecursively()
        }
    }

    @Test
    fun freshRepositoryNotFoundDurableSnapshotFallsBackToLiveForCompleteImport() = runBlocking {
        val freshRoot = Files.createTempDirectory("stage6-fresh-notfound").toFile()
        try {
            val repository = LocalDocumentRepository(freshRoot)
            val targetSource = source.copy(sourceUri = "content://provider/fresh-notfound-plan.pdf")
            val association = (repository.resolveOrCreate(targetSource, fingerprint)
                as com.example.myapplication.stage2.ResolveDocumentResult.Resolved).association
            assertEquals(DocumentLoadResult.NotFound, repository.load(association))

            val service = DocumentBundleService()
            val decoded = service.readBundle(
                ByteArrayInputStream(
                    service.encodeToByteArray(photoBundle())
                )
            )
            val rebound = service.rebindToVerifiedTarget(
                decoded,
                VerifiedBundleTarget(association.documentId, targetSource, fingerprint)
            )

            DocumentPhotoAssetStore(
                freshRoot,
                association.documentId,
                DefaultImageProbe,
                TestPhotoPathOperationsFactory
            ).use { photoStore ->
                val host = RepositoryNullableDurableImportHost(
                    repository = repository,
                    association = association,
                    initialSnapshot = emptySnapshot(targetSource)
                )
                assertEquals(null, host.captureCurrentDurableSnapshot())
                val photoTransaction = StagedPhotoContentTransaction.stageForTesting(
                    photoStore.resolver.root,
                    rebound.photoFiles,
                    TestPhotoPathOperationsFactory
                )

                assertEquals(
                    BundleImportResult.Applied,
                    service.applyReboundBundleWithinDocumentTransaction(
                        rebound,
                        host,
                        photoTransaction
                    )
                )
                assertEquals(rebound.snapshot, host.live)
                assertEquals(rebound.snapshot, host.durable)
            }

            val reopened = LocalDocumentRepository(freshRoot).load(association)
                as DocumentLoadResult.Loaded
            assertEquals(rebound.snapshot, reopened.snapshot)
            DocumentPhotoAssetStore(
                freshRoot,
                association.documentId,
                DefaultImageProbe,
                TestPhotoPathOperationsFactory
            ).use { reopenedPhotoStore ->
                val reopenedPhotos = reopenedPhotoStore.readReferencedPhotos(reopened.snapshot)
                assertEquals(photoBundle().photoFiles.keys, reopenedPhotos.keys)
                photoBundle().photoFiles.forEach { (name, expected) ->
                    assertArrayEquals(expected, reopenedPhotos.getValue(name))
                }
            }
        } finally {
            freshRoot.deleteRecursively()
        }
    }

    @Test
    fun failedFreshImportRestoresAbsentDurableSlotsAndRetainsPhotoEvidenceUntilProof() = runBlocking {
        val freshRoot = Files.createTempDirectory("stage6-fresh-failed-import").toFile()
        try {
            val repository = LocalDocumentRepository(freshRoot)
            val targetSource = source.copy(sourceUri = "content://provider/fresh-failed-import-plan.pdf")
            val association = (repository.resolveOrCreate(targetSource, fingerprint)
                as com.example.myapplication.stage2.ResolveDocumentResult.Resolved).association
            assertEquals(DocumentLoadResult.NotFound, repository.load(association))

            val service = DocumentBundleService()
            val decoded = service.readBundle(ByteArrayInputStream(service.encodeToByteArray(photoBundle())))
            val rebound = service.rebindToVerifiedTarget(
                decoded,
                VerifiedBundleTarget(association.documentId, targetSource, fingerprint)
            )
            var evidenceObservedDuringCanonicalRestore = false
            var moveCount = 0

            DocumentPhotoAssetStore(
                freshRoot,
                association.documentId,
                DefaultImageProbe,
                TestPhotoPathOperationsFactory
            ).use { photoStore ->
                val host = RepositoryNullableDurableImportHost(
                    repository = repository,
                    association = association,
                    initialSnapshot = emptySnapshot(targetSource),
                    beforeExactRestore = {
                        evidenceObservedDuringCanonicalRestore =
                            File(photoStore.resolver.root, ".stage5-photo-transaction.marker").isFile &&
                                File(photoStore.resolver.root, ".stage5-photo-canonical.intent").isFile
                    }
                )
                val photoTransaction = StagedPhotoContentTransaction.stageForTesting(
                    photoStore.resolver.root,
                    rebound.photoFiles,
                    TestPhotoPathOperationsFactory,
                    move = { from, to ->
                        moveCount++
                        if (moveCount == 2) throw IOException("injected staged photo publish failure")
                        Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    }
                )

                val result = service.applyReboundBundleWithinDocumentTransaction(
                    rebound,
                    host,
                    photoTransaction
                )
                assertTrue(result is BundleImportResult.Failed)
                assertTrue("photo recovery evidence must outlive publish failure", evidenceObservedDuringCanonicalRestore)
                assertTrue("exact host restore must be used", host.exactRestoreCalled)
                assertEquals(DocumentLoadResult.NotFound, repository.load(association))
                assertFalse(repository.currentSnapshotFile(association.documentId).exists())
                assertFalse(repository.previousSnapshotFile(association.documentId).exists())
                assertFalse(photoStore.resolver.resolve(LegacyStateFixture.PHOTO_ONE).exists())
                assertFalse(photoStore.resolver.resolve(LegacyStateFixture.PHOTO_TWO).exists())
                val remainingPhotoArtifacts = photoStore.resolver.root.listFiles().orEmpty()
                    .filter { it.name.startsWith(".stage5-photo-") }
                assertTrue(
                    "complete rollback proof must clean the transaction evidence: $remainingPhotoArtifacts",
                    remainingPhotoArtifacts.isEmpty()
                )
            }
            assertTrue("test must exercise a partial publish", moveCount >= 2)
        } finally {
            freshRoot.deleteRecursively()
        }
    }

    @Test
    fun exactDurableRestore_replaysTheWholePairAfterAMidSlotFailure() = runBlocking {
        val root = Files.createTempDirectory("stage6-exact-restore-pair").toFile()
        try {
            var failAfterCurrent = true
            val repository = LocalDocumentRepository(
                rootDirectory = root,
                failureInjector = object : RepositoryFailureInjector {
                    override fun onPhase(
                        phase: RepositoryWritePhase,
                        documentId: DocumentId?,
                        stagedFile: File?
                    ) {
                        if (failAfterCurrent && phase == RepositoryWritePhase.SNAPSHOT_RESTORE_AFTER_SLOT_REPLACE) {
                            failAfterCurrent = false
                            throw IOException("injected exact restore pair failure")
                        }
                    }
                }
            )
            val association = (repository.resolveOrCreate(source, fingerprint)
                as com.example.myapplication.stage2.ResolveDocumentResult.Resolved).association
            val previousSnapshot = emptySnapshot(source).copy(snapshotRevision = 11)
            val currentSnapshot = photoBundle().snapshot.copy(snapshotRevision = 12)
            assertTrue(repository.save(association, previousSnapshot) is DocumentSaveResult.Saved)
            assertTrue(repository.save(association, currentSnapshot) is DocumentSaveResult.Saved)
            val expectedCurrentBytes = repository.currentSnapshotFile(association.documentId).readBytes()
            val expectedPreviousBytes = repository.previousSnapshotFile(association.documentId).readBytes()
            val captured = repository.captureDurableSnapshotState(association)

            assertTrue(
                repository.save(
                    association,
                    emptySnapshot(source).copy(snapshotRevision = 13)
                ) is DocumentSaveResult.Saved
            )
            val failed = repository.restoreDurableSnapshotState(association, captured)
            assertTrue("the injected pair failure must be surfaced", failed is DocumentSaveResult.Failed)
            assertTrue(
                (failed as DocumentSaveResult.Failed).error is LocalRepositoryError.CommitUncertain
            )
            assertTrue(
                "the pending restore intent must remain after a partial pair replacement",
                File(repository.currentSnapshotFile(association.documentId).parentFile, "snapshot.restore.pending.json").isFile
            )

            val reopened = LocalDocumentRepository(root)
            val recovered = reopened.captureDurableSnapshotState(association)
            assertEquals(captured.current?.snapshot, recovered.current?.snapshot)
            assertEquals(captured.previous?.snapshot, recovered.previous?.snapshot)
            assertArrayEquals(expectedCurrentBytes, reopened.currentSnapshotFile(association.documentId).readBytes())
            assertArrayEquals(expectedPreviousBytes, reopened.previousSnapshotFile(association.documentId).readBytes())
            assertFalse(
                File(reopened.currentSnapshotFile(association.documentId).parentFile, "snapshot.restore.pending.json").exists()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun freshRepositoryNotFoundLegacyImportThroughProductionSeamRoundTrips() = runBlocking {
        val freshRoot = Files.createTempDirectory("stage6-fresh-legacy-notfound").toFile()
        try {
            val repository = LocalDocumentRepository(freshRoot)
            val targetSource = source.copy(sourceUri = "content://provider/fresh-legacy-notfound-plan.pdf")
            val association = (repository.resolveOrCreate(targetSource, fingerprint)
                as com.example.myapplication.stage2.ResolveDocumentResult.Resolved).association
            assertEquals(DocumentLoadResult.NotFound, repository.load(association))

            val legacy = LegacyStateFixture.fullyPopulatedPageData()
            val parsedLegacy = LegacyPageDataCodec.decode(LegacyPageDataCodec.encode(legacy))
            val importedSnapshot = snapshotFromLegacyPageData(parsedLegacy, targetSource).also(::validateSnapshot)
            legacy.values
                .flatMap { page -> page.photoPins.flatMap { it.imageFileNames } }
                .distinct()
                .forEach { name ->
                    java.io.File(freshRoot, name).writeBytes(
                        when (name) {
                            LegacyStateFixture.PHOTO_ONE -> photoOne
                            LegacyStateFixture.PHOTO_TWO -> photoTwo
                            else -> error("unexpected legacy fixture photo: $name")
                        }
                    )
                }

            val previousLiveSnapshot = emptySnapshot(targetSource)
            val host = RepositoryNullableDurableImportHost(
                repository = repository,
                association = association,
                initialSnapshot = previousLiveSnapshot
            )
            val barrier = DocumentTransactionBarrier()
            val applied = withVerifiedStage6ImportDocument(
                transactionBarrier = barrier,
                documentId = association.documentId,
                sessionSourceUri = targetSource.sourceUri,
                associationDocumentId = association.documentId,
                associationSourceUri = association.source.sourceUri,
                targetSourceUri = importedSnapshot.source.sourceUri,
                sessionSourceFingerprint = fingerprint,
                associationSourceFingerprint = association.sourceFingerprint,
                targetSourceFingerprint = fingerprint,
                currentSourceFingerprint = { fingerprint }
            ) {
                val currentLiveSnapshot = host.captureCurrentLiveSnapshot()
                val currentDurableSnapshot = host.captureCurrentDurableSnapshot() ?: currentLiveSnapshot
                DocumentPhotoAssetStore(
                    freshRoot,
                    association.documentId,
                    DefaultImageProbe,
                    TestPhotoPathOperationsFactory
                ).use { photoStore ->
                    photoStore.reconcilePhotoContent(currentDurableSnapshot, currentLiveSnapshot)
                    val result = photoStore.withMigratedLegacyPhotos(
                        snapshot = importedSnapshot,
                        legacyRoot = freshRoot,
                        previousCanonicalSnapshot = currentDurableSnapshot,
                        previousLiveCanonicalSnapshot = currentLiveSnapshot,
                        commitResult = { value -> value is SessionSnapshotApplyResult.Applied },
                        canonicalRollbackProven = {
                            val durableRestored = when (val loaded = repository.load(association)) {
                                is DocumentLoadResult.Loaded -> loaded.snapshot == currentDurableSnapshot
                                DocumentLoadResult.NotFound,
                                is DocumentLoadResult.Failed -> false
                            }
                            durableRestored && host.live == currentLiveSnapshot
                        }
                    ) { migratedPhotos ->
                        validatePhotoSet(importedSnapshot, migratedPhotos)
                        host.persistAndApply(importedSnapshot)
                    }
                    if (result is SessionSnapshotApplyResult.Applied) {
                        photoStore.cleanupAfterCanonicalCommit(importedSnapshot, host.live)
                    }
                    result
                }
            }

            assertEquals(SessionSnapshotApplyResult.Applied, applied)
            assertEquals(importedSnapshot, host.live)
            assertEquals(importedSnapshot, host.captureCurrentDurableSnapshot())

            val reopened = LocalDocumentRepository(freshRoot).load(association)
                as DocumentLoadResult.Loaded
            assertEquals(importedSnapshot, reopened.snapshot)
            DocumentPhotoAssetStore(
                freshRoot,
                association.documentId,
                DefaultImageProbe,
                TestPhotoPathOperationsFactory
            ).use { reopenedPhotoStore ->
                val reopenedPhotos = reopenedPhotoStore.readReferencedPhotos(reopened.snapshot)
                assertEquals(setOf(LegacyStateFixture.PHOTO_ONE, LegacyStateFixture.PHOTO_TWO), reopenedPhotos.keys)
                assertArrayEquals(photoOne, reopenedPhotos.getValue(LegacyStateFixture.PHOTO_ONE))
                assertArrayEquals(photoTwo, reopenedPhotos.getValue(LegacyStateFixture.PHOTO_TWO))
            }
        } finally {
            freshRoot.deleteRecursively()
        }
    }

    @Test
    fun exportUsesLiveSnapshotEvenWhenDurableSnapshotDiffers() {
        val durable = snapshotFromLegacyPageData(
            LegacyStateFixture.fullyPopulatedPageData().filterKeys { it == 2 },
            source
        )
        val live = snapshotFromLegacyPageData(LegacyStateFixture.fullyPopulatedPageData(), source)
        val service = DocumentBundleService()
        val decoded = service.readBundle(
            ByteArrayInputStream(
                service.encodeToByteArray(
                    BundleExportInput(
                        exportedDocumentId = DocumentId.new(),
                        source = live.source,
                        sourceFingerprint = fingerprint,
                        snapshot = live,
                        photoFiles = mapOf(
                            LegacyStateFixture.PHOTO_ONE to photoOne,
                            LegacyStateFixture.PHOTO_TWO to photoTwo
                        )
                    )
                )
            )
        )

        assertNotEquals(durable, live)
        assertEquals(live, decoded.snapshot)
        assertTrue(decoded.snapshot.pages.getValue(0).notes.isNotEmpty())
        assertTrue(decoded.snapshot.pages.getValue(0).photoPins.isNotEmpty())
    }

    @Test
    fun legacyV0JsonRemainsReadableThroughTheTypedImportCodec() {
        val legacy = LegacyStateFixture.fullyPopulatedPageData()
        val decoded = LegacyPageDataCodec.decode(LegacyPageDataCodec.encode(legacy))

        assertEquals(legacy, decoded)
        assertEquals(
            snapshotFromLegacyPageData(legacy, source),
            snapshotFromLegacyPageData(decoded, source)
        )
    }

    @Test
    fun exportSourceRevisionMustBeReverifiedAtTheCaptureBoundary() {
        val snapshot = emptySnapshot(source)
        assertEquals(
            fingerprint,
            verifyBundleExportSourceFingerprint(
                sessionSourceUri = source.sourceUri,
                sessionSourceFingerprint = fingerprint,
                snapshot = snapshot,
                currentSourceFingerprint = fingerprint
            )
        )
        assertRejected {
            verifyBundleExportSourceFingerprint(
                sessionSourceUri = source.sourceUri,
                sessionSourceFingerprint = fingerprint,
                snapshot = snapshot,
                currentSourceFingerprint = SourceFingerprint.fromBytes("changed".toByteArray())
            )
        }
        assertRejected {
            verifyBundleExportSourceFingerprint(
                sessionSourceUri = source.sourceUri,
                sessionSourceFingerprint = fingerprint,
                snapshot = snapshot,
                currentSourceFingerprint = null
            )
        }
    }

    @Test
    fun changedImportSourceRevisionStopsInsideBundleBarrierBeforePublication() = runBlocking {
        val service = DocumentBundleService()
        val decoded = service.readBundle(
            ByteArrayInputStream(
                service.encodeToByteArray(photoBundle())
            )
        )
        val target = VerifiedBundleTarget(DocumentId.new(), source, fingerprint)
        val rebound = service.rebindToVerifiedTarget(decoded, target)
        val old = emptySnapshot(source)
        val host = FakeImportHost(target.documentId, old, old)
        val barrier = DocumentTransactionBarrier()
        val stagingRoot = Files.createTempDirectory("stage6-barrier-source-race").toFile()
        val changedFingerprint = SourceFingerprint.fromBytes("changed-source".toByteArray())
        var stagingAttempted = false
        try {
            val failure = try {
                withVerifiedStage6ImportDocument(
                    transactionBarrier = barrier,
                    documentId = target.documentId,
                    sessionSourceUri = source.sourceUri,
                    associationDocumentId = target.documentId,
                    associationSourceUri = target.source.sourceUri,
                    targetSourceUri = rebound.snapshot.source.sourceUri,
                    sessionSourceFingerprint = fingerprint,
                    associationSourceFingerprint = fingerprint,
                    targetSourceFingerprint = rebound.target.sourceFingerprint,
                    currentSourceFingerprint = { changedFingerprint }
                ) {
                    // This is the same production transaction body as the
                    // MainActivity bundle route: parse/rebind happened before
                    // the seam, and staging/apply follow its barrier gate.
                    stagingAttempted = true
                    val photoTransaction = StagedPhotoContentTransaction.stageForTesting(
                        stagingRoot,
                        rebound.photoFiles,
                        TestPhotoPathOperationsFactory
                    )
                    service.applyReboundBundleWithinDocumentTransaction(
                        rebound,
                        host,
                        photoTransaction
                    )
                }
                throw AssertionError("a changed source must be rejected before bundle application")
            } catch (error: DocumentBundleException) {
                error
            }

            assertTrue(failure.message.orEmpty().contains("source revision changed"))
            assertFalse(stagingAttempted)
            assertEquals(0, host.persistCalled)
            assertEquals(
                0L,
                Files.walk(stagingRoot.toPath()).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.count()
                }
            )

            val equalHost = FakeImportHost(target.documentId, old, old)
            val equalPhotos = FakePhotoTransaction()
            assertEquals(
                BundleImportResult.Applied,
                withVerifiedStage6ImportDocument(
                    transactionBarrier = barrier,
                    documentId = target.documentId,
                    sessionSourceUri = source.sourceUri,
                    associationDocumentId = target.documentId,
                    associationSourceUri = target.source.sourceUri,
                    targetSourceUri = rebound.snapshot.source.sourceUri,
                    sessionSourceFingerprint = fingerprint,
                    associationSourceFingerprint = fingerprint,
                    targetSourceFingerprint = rebound.target.sourceFingerprint,
                    currentSourceFingerprint = { fingerprint }
                ) {
                    service.applyReboundBundleWithinDocumentTransaction(
                        rebound,
                        equalHost,
                        equalPhotos
                    )
                }
            )
            assertEquals(1, equalHost.persistCalled)
            assertTrue(equalPhotos.publishCalled)
            assertTrue(equalPhotos.commitCalled)
        } finally {
            stagingRoot.deleteRecursively()
        }
    }

    @Test
    fun changedLegacyImportSourceRevisionStopsInsideBarrierBeforeMigrationOrPersistence() = runBlocking {
        val legacyJson = LegacyPageDataCodec.encode(LegacyStateFixture.fullyPopulatedPageData())
        val parsedLegacy = LegacyPageDataCodec.decode(legacyJson)
        val importedSnapshot = snapshotFromLegacyPageData(parsedLegacy, source).also(::validateSnapshot)
        val targetDocumentId = DocumentId.new()
        val previousSnapshot = emptySnapshot(source)
        val host = FakeImportHost(targetDocumentId, previousSnapshot, previousSnapshot)
        val barrier = DocumentTransactionBarrier()
        val legacyRoot = Files.createTempDirectory("stage6-legacy-source-race").toFile()
        val changedFingerprint = SourceFingerprint.fromBytes("changed-legacy-source".toByteArray())
        var legacyPhotoWorkAttempted = false
        var photoPublicationCallbackCalled = false
        try {
            LegacyStateFixture.fullyPopulatedPageData().values
                .flatMap { page -> page.photoPins.flatMap { it.imageFileNames } }
                .distinct()
                .forEach { name ->
                    java.io.File(legacyRoot, name).writeBytes(
                        when (name) {
                            LegacyStateFixture.PHOTO_ONE -> photoOne
                            LegacyStateFixture.PHOTO_TWO -> photoTwo
                            else -> error("unexpected legacy fixture photo: $name")
                        }
                    )
                }

            val failure = try {
                withVerifiedStage6ImportDocument(
                    transactionBarrier = barrier,
                    documentId = targetDocumentId,
                    sessionSourceUri = source.sourceUri,
                    associationDocumentId = targetDocumentId,
                    associationSourceUri = source.sourceUri,
                    targetSourceUri = importedSnapshot.source.sourceUri,
                    sessionSourceFingerprint = fingerprint,
                    associationSourceFingerprint = fingerprint,
                    targetSourceFingerprint = fingerprint,
                    currentSourceFingerprint = { changedFingerprint }
                ) {
                    // The actual production legacy transaction body follows
                    // the seam's barrier/source gate before migration or
                    // canonical apply.
                    legacyPhotoWorkAttempted = true
                    DocumentPhotoAssetStore(
                        legacyRoot,
                        targetDocumentId,
                        DefaultImageProbe,
                        TestPhotoPathOperationsFactory
                    ).use { store ->
                        store.reconcilePhotoContent(previousSnapshot, previousSnapshot)
                        store.withMigratedLegacyPhotos(
                            snapshot = importedSnapshot,
                            legacyRoot = legacyRoot,
                            previousCanonicalSnapshot = previousSnapshot,
                            previousLiveCanonicalSnapshot = previousSnapshot,
                            commitResult = { result -> result is SessionSnapshotApplyResult.Applied },
                            canonicalRollbackProven = { true }
                        ) { migratedPhotos ->
                            photoPublicationCallbackCalled = true
                            validatePhotoSet(importedSnapshot, migratedPhotos)
                            host.persistAndApply(importedSnapshot)
                        }
                    }
                }
                throw AssertionError("a changed source must be rejected before legacy photo migration")
            } catch (error: DocumentBundleException) {
                error
            }

            assertTrue(failure.message.orEmpty().contains("source revision changed"))
            assertFalse(legacyPhotoWorkAttempted)
            assertFalse(photoPublicationCallbackCalled)
            assertEquals(0, host.persistCalled)
            assertEquals(
                0L,
                Files.walk(legacyRoot.toPath()).use { paths ->
                    paths.filter { path ->
                        Files.isRegularFile(path) && path.parent != legacyRoot.toPath()
                    }.count()
                }
            )
        } finally {
            legacyRoot.deleteRecursively()
        }
    }

    @Test
    fun cancellableImportStopsDuringArchiveStagingAndCleansItsTemporaryRoot() = runBlocking {
        val stagingRoot = Files.createTempDirectory("stage6-cancelled-import").toFile()
        try {
            val service = DocumentBundleService(stagingDirectory = stagingRoot)
            val archive = service.encodeToByteArray(photoBundle())
            val firstRead = CompletableDeferred<Unit>()
            val input = object : java.io.InputStream() {
                private var offset = 0

                override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                    firstRead.complete(Unit)
                    Thread.sleep(5L)
                    if (offset >= archive.size) return -1
                    buffer[off] = archive[offset++]
                    return 1
                }

                override fun read(): Int {
                    firstRead.complete(Unit)
                    Thread.sleep(5L)
                    return if (offset >= archive.size) -1 else archive[offset++].toInt() and 0xFF
                }
            }
            val job = launch(Dispatchers.IO) {
                service.readBundleCancellable(input)
                fail("cancellable import must not complete after cancellation")
            }
            firstRead.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            input.close()
            assertEquals(0L, Files.list(stagingRoot.toPath()).use { it.count() })
        } finally {
            stagingRoot.deleteRecursively()
        }
    }

    @Test
    fun stagingCleanupFailureDoesNotReplacePrimaryFailureOrSuccessfulRead() {
        val stagingRoot = Files.createTempDirectory("stage6-cleanup-failure").toFile()
        try {
            val cleanupFailure = IOException("injected staging cleanup failure")
            val service = DocumentBundleService(
                stagingDirectory = stagingRoot,
                cleanupStagingDirectoryOverride = { throw cleanupFailure }
            )
            val primary = try {
                service.readBundle(ByteArrayInputStream(byteArrayOf(0x01)))
                fail("malformed archive must fail")
            } catch (error: Throwable) {
                error
            }
            assertTrue(primary is Stage5ValidationException)
            val primaryThrowable = primary as Throwable
            assertTrue(primaryThrowable.getSuppressed().any { it === cleanupFailure })

            val successfulCleanupFailure = IOException("injected cleanup failure after success")
            val successfulRead = try {
                DocumentBundleService(
                    stagingDirectory = stagingRoot,
                    cleanupStagingDirectoryOverride = { throw successfulCleanupFailure }
                ).readBundle(ByteArrayInputStream(service.encodeToByteArray(photoBundle())))
                fail("cleanup failure must not be reported as a successful read")
            } catch (error: Throwable) {
                error
            }
            assertTrue(successfulRead === successfulCleanupFailure)
        } finally {
            stagingRoot.deleteRecursively()
        }
    }

    @Test
    fun perEntryCleanupFailureIsSuppressedOnPrimaryReadFailure() {
        val cleanupFailure = IOException("injected failed-entry cleanup failure")
        val archive = deflatedZipEntries(
            linkedMapOf(SOTAWARE_BUNDLE_MANIFEST_ENTRY to "bad".toByteArray())
        ).also { bytes ->
            val central = findCentralEntry(bytes, SOTAWARE_BUNDLE_MANIFEST_ENTRY.toByteArray())
            val local = readU32(bytes, central + 42).toInt()
            assertTrue("fixture must carry final deflated claims", readU16(bytes, central + 8) and 0x08 == 0)
            // Keep local and central headers mutually consistent, but claim
            // fewer uncompressed bytes than the valid deflated payload. The
            // bounded copy fails after the entry temp is created, before any
            // closeEntry() call or CRC finalization can drain the entry.
            writeU32(bytes, central + 24, 1L)
            writeU32(bytes, local + 22, 1L)
        }

        val thrown = try {
            DocumentBundleService(
                deleteStagedEntryOverride = { throw cleanupFailure }
            ).readBundle(ByteArrayInputStream(archive))
            fail("a mismatched uncompressed-size claim must fail")
        } catch (error: Throwable) {
            error
        }

        assertTrue(thrown is Stage5ValidationException)
        assertTrue((thrown as Throwable).getSuppressed().any { it === cleanupFailure })
    }

    @Test
    fun forgedSmallDataDescriptorClaimsAreRejectedBeforeZipExtraction() {
        val actual = ByteArray(64 * 1024) { (it * 31 and 0xFF).toByte() }
        val archive = dataDescriptorZipEntry(SOTAWARE_BUNDLE_MANIFEST_ENTRY, actual)
        val central = findCentralEntry(archive, SOTAWARE_BUNDLE_MANIFEST_ENTRY.toByteArray())
        assertTrue("fixture must use a data descriptor", readU16(archive, central + 8) and 0x08 != 0)
        // Keep the descriptor payload valid, but make all central claims
        // deceptively small.  Rejection must occur during central scanning,
        // before a ZipInputStream can inflate or drain this entry.
        writeU32(archive, central + 16, 0L)
        writeU32(archive, central + 20, 1L)
        writeU32(archive, central + 24, 1L)

        var zipFactoryCalls = 0
        var closeEntryCalls = 0
        val service = DocumentBundleService(
            zipInputStreamFactory = { input ->
                zipFactoryCalls++
                object : ZipInputStream(input, Charsets.UTF_8) {
                    override fun closeEntry() {
                        closeEntryCalls++
                        super.closeEntry()
                    }
                }
            }
        )

        assertRejected { service.readBundle(ByteArrayInputStream(archive)) }
        assertEquals("a data-descriptor archive must be rejected before extraction", 0, zipFactoryCalls)
        assertEquals("rejection must not invoke closeEntry", 0, closeEntryCalls)
    }

    @Test
    fun rejectedCentralClaimAbortsZipWithoutDrainingTheRejectedEntry() {
        val actual = ByteArray(512) { (it and 0x7F).toByte() }
        val archive = dataDescriptorZipEntry(SOTAWARE_BUNDLE_MANIFEST_ENTRY, actual)
        val central = findCentralEntry(archive, SOTAWARE_BUNDLE_MANIFEST_ENTRY.toByteArray())
        assertTrue("fixture must use a data descriptor", readU16(archive, central + 8) and 0x08 != 0)
        writeU32(archive, central + 24, actual.size.toLong() + 1L)

        var closeEntryCalls = 0
        val service = DocumentBundleService(
            zipInputStreamFactory = { input ->
                object : ZipInputStream(input, Charsets.UTF_8) {
                    override fun closeEntry() {
                        closeEntryCalls++
                        super.closeEntry()
                    }
                }
            }
        )

        assertRejected { service.readBundle(ByteArrayInputStream(archive)) }
        assertEquals(
            "a rejected bounded entry must not be drained by closeEntry",
            0,
            closeEntryCalls
        )
    }

    @Test
    fun applyEmptyBundleReplacesAbsentPagesAndDomainsWithoutGhosts() = runBlocking {
        val service = DocumentBundleService()
        val old = snapshotFromLegacyPageData(LegacyStateFixture.fullyPopulatedPageData(), source)
        val incoming = emptySnapshot(source)
        val target = VerifiedBundleTarget(DocumentId.new(), source, fingerprint)
        val host = FakeImportHost(target.documentId, old, old)

        val result = service.applyReboundBundleWithinDocumentTransaction(
            ReboundDocumentBundle(target, incoming, emptyMap()),
            host,
            photoTransaction = null
        )

        assertEquals(BundleImportResult.Applied, result)
        assertEquals(incoming, host.live)
        assertEquals(incoming, host.durable)
        assertTrue(host.live.pages.isEmpty())
        assertTrue(host.durable.pages.isEmpty())
    }

    @Test
    fun rebindRequiresMatchingSourceRevisionAndDoesNotReuseExportedId() {
        val snapshot = emptySnapshot(source)
        val service = DocumentBundleService()
        val exportedId = DocumentId.new()
        val decoded = service.readBundle(
            ByteArrayInputStream(
                service.encodeToByteArray(
                    BundleExportInput(exportedId, source, fingerprint, snapshot, emptyMap())
                )
            )
        )
        val targetId = DocumentId.new()
        val target = VerifiedBundleTarget(targetId, source.copy(sourceUri = "content://provider/fresh"), fingerprint)
        val rebound = service.rebindToVerifiedTarget(decoded, target)
        assertEquals(targetId, rebound.target.documentId)
        assertNotEquals(exportedId, rebound.target.documentId)
        assertEquals(BundleDocumentIdentityPolicy.VERIFIED_TARGET_COPY, rebound.identityPolicy)
        assertEquals(target.source, rebound.snapshot.source)

        val sameDocument = service.rebindToVerifiedTarget(
            decoded,
            VerifiedBundleTarget(exportedId, source, fingerprint)
        )
        assertEquals(BundleDocumentIdentityPolicy.SAME_DOCUMENT_RESTORE, sameDocument.identityPolicy)
        assertEquals(exportedId, sameDocument.target.documentId)

        assertRejected {
            service.rebindToVerifiedTarget(
                decoded,
                target.copy(sourceFingerprint = SourceFingerprint.fromBytes("different".toByteArray()))
            )
        }
    }

    @Test
    fun photoManifestAndEntrySetFailuresAreRejected() {
        val service = DocumentBundleService()
        val valid = service.encodeToByteArray(photoBundle())
        val entries = unzipEntries(valid)

        val missing = LinkedHashMap(entries).apply { remove("photos/${LegacyStateFixture.PHOTO_TWO}") }
        assertRejected { service.readBundle(ByteArrayInputStream(zipEntries(missing))) }

        val extra = LinkedHashMap(entries).apply { put("photos/extra.png", photoOne) }
        assertRejected { service.readBundle(ByteArrayInputStream(zipEntries(extra))) }

        val manifestRoot = JsonParser.parseString(entries.getValue(SOTAWARE_BUNDLE_MANIFEST_ENTRY).toString(Charsets.UTF_8)).asJsonObject
        manifestRoot.getAsJsonArray("photos").get(0).asJsonObject.addProperty("sha256", "0".repeat(64))
        val hashMismatch = LinkedHashMap(entries).apply {
            put(SOTAWARE_BUNDLE_MANIFEST_ENTRY, manifestRoot.toString().toByteArray())
        }
        assertRejected { service.readBundle(ByteArrayInputStream(zipEntries(hashMismatch))) }

        val sizeRoot = JsonParser.parseString(entries.getValue(SOTAWARE_BUNDLE_MANIFEST_ENTRY).toString(Charsets.UTF_8)).asJsonObject
        val size = sizeRoot.getAsJsonArray("photos").get(0).asJsonObject.get("byteCount").asLong
        sizeRoot.getAsJsonArray("photos").get(0).asJsonObject.addProperty("byteCount", size + 1L)
        val sizeMismatch = LinkedHashMap(entries).apply {
            put(SOTAWARE_BUNDLE_MANIFEST_ENTRY, sizeRoot.toString().toByteArray())
        }
        assertRejected { service.readBundle(ByteArrayInputStream(zipEntries(sizeMismatch))) }
    }

    @Test
    fun malformedUnsupportedAndUnsafeArchivesAreRejectedBeforePublication() {
        val service = DocumentBundleService()
        val valid = service.encodeToByteArray(photoBundle())
        val entries = unzipEntries(valid)

        assertRejected {
            service.readBundle(ByteArrayInputStream(zipEntries(entries + (SOTAWARE_BUNDLE_MANIFEST_ENTRY to "{}".toByteArray()))))
        }
        val unsupportedManifest = entries.getValue(SOTAWARE_BUNDLE_MANIFEST_ENTRY)
            .toString(Charsets.UTF_8)
            .replace("\"formatVersion\":1", "\"formatVersion\":2")
            .toByteArray()
        assertRejected {
            service.readBundle(
                ByteArrayInputStream(
                    zipEntries(LinkedHashMap(entries).apply { put(SOTAWARE_BUNDLE_MANIFEST_ENTRY, unsupportedManifest) })
                )
            )
        }
        val unknownManifest = entries.getValue(SOTAWARE_BUNDLE_MANIFEST_ENTRY)
            .toString(Charsets.UTF_8)
            .replaceFirst("{", "{\"unknown\":true,")
            .toByteArray()
        assertRejected {
            service.readBundle(
                ByteArrayInputStream(
                    zipEntries(LinkedHashMap(entries).apply { put(SOTAWARE_BUNDLE_MANIFEST_ENTRY, unknownManifest) })
                )
            )
        }
        assertRejected {
            service.readBundle(ByteArrayInputStream(zipEntries(linkedMapOf("photos/../evil.png" to photoOne))))
        }
        assertRejected {
            service.readBundle(ByteArrayInputStream(zipEntries(linkedMapOf("/absolute.png" to photoOne))))
        }
        assertRejected {
            service.readBundle(ByteArrayInputStream(valid.copyOf(valid.size - 1)))
        }
        assertRejected {
            service.readBundle(
                ByteArrayInputStream(
                    zipEntries(
                        linkedMapOf(
                            "manifest.json" to entries.getValue("manifest.json"),
                            "snapshot.json" to entries.getValue("snapshot.json"),
                            "photos/nested.jpg" to valid
                        )
                    )
                )
            )
        }
        val duplicate = zipEntries(
            linkedMapOf(
                "photos/a.jpg" to photoOne,
                "photos/b.jpg" to photoTwo
            )
        ).also { replaceCentralName(it, "photos/b.jpg", "photos/a.jpg") }
        assertRejected { service.readBundle(ByteArrayInputStream(duplicate)) }

        val symlinkLike = zipEntries(linkedMapOf("photos/link.jpg" to photoOne)).also { archive ->
            val central = findCentralEntry(archive, "photos/link.jpg".toByteArray())
            // Mark the entry as UNIX-made so the high mode/type bits are
            // interpreted consistently, then encode a symlink type.
            writeU16(archive, central + 4, (3 shl 8) or 20)
            writeU32(archive, central + 38, 0xA0000000L)
        }
        assertRejected { service.readBundle(ByteArrayInputStream(symlinkLike)) }

        val malformedUnixAttributes = zipEntries(linkedMapOf("photos/malformed.jpg" to photoOne)).also { archive ->
            val central = findCentralEntry(archive, "photos/malformed.jpg".toByteArray())
            writeU16(archive, central + 4, (3 shl 8) or 20)
            writeU32(archive, central + 38, 0L)
        }
        assertRejected { service.readBundle(ByteArrayInputStream(malformedUnixAttributes)) }
    }

    @Test
    fun archiveResourceLimitsRejectZipBombAndOversizedCentralDirectoryClaims() {
        val service = DocumentBundleService()
        val repetitive = ByteArray(32 * 1024)
        val compressed = zipEntries(linkedMapOf("manifest.json" to repetitive))
        assertRejected { service.readBundle(ByteArrayInputStream(compressed)) }

        val zip64Extra = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                val entry = ZipEntry(SOTAWARE_BUNDLE_MANIFEST_ENTRY).apply {
                    extra = byteArrayOf(0x01, 0x00, 0x00, 0x00)
                }
                zip.putNextEntry(entry)
                zip.write("{}".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        assertRejected { service.readBundle(ByteArrayInputStream(zip64Extra)) }

        val valid = service.encodeToByteArray(photoBundle())
        val entries = unzipEntries(valid)
        val oversized = zipEntries(entries).also { archive ->
            val nameBytes = "photos/${LegacyStateFixture.PHOTO_ONE}".toByteArray()
            val central = findCentralEntry(archive, nameBytes)
            writeU32(archive, central + 24, (Stage5Limits.MAX_PHOTO_BYTES + 1).toLong())
        }
        assertRejected { service.readBundle(ByteArrayInputStream(oversized)) }
    }

    @Test
    fun writeAndReadSuccessRequireFlushAndCloseToSucceed() {
        val service = DocumentBundleService()
        val input = photoBundle()
        assertRejected {
            service.writeBundleAndClose({ FailingOutputStream(failOnFlush = true) }, input)
        }
        assertRejected {
            service.writeBundleAndClose({ FailingOutputStream(failOnClose = true) }, input)
        }
        assertRejected {
            service.readBundleFrom { CloseFailInputStream(service.encodeToByteArray(input)) }
        }
    }

    @Test
    fun exportPreOpenFailureLeavesExistingDestinationUntouched() {
        val service = DocumentBundleService()
        val destination = ByteArrayOutputStream().apply { write("existing-destination".toByteArray()) }
        val before = destination.toByteArray()
        var openCalls = 0
        val invalidInput = photoBundle().copy(
            snapshot = photoBundle().snapshot.copy(
                source = source.copy(sourceUri = "content://provider/not-the-export-source.pdf")
            )
        )

        assertRejected {
            service.writeBundleAndClose(
                openOutput = {
                    openCalls++
                    destination.reset()
                    destination
                },
                input = invalidInput
            )
        }

        assertEquals("invalid preparation must not open SAF", 0, openCalls)
        assertArrayEquals(before, destination.toByteArray())
    }

    @Test
    fun applyServiceRollsBackCanonicalAndPhotosOnRepositoryOrPhotoFailure() = runBlocking {
        val service = DocumentBundleService()
        val old = emptySnapshot(source)
        val incoming = snapshotFromLegacyPageData(LegacyStateFixture.fullyPopulatedPageData(), source)
        val target = VerifiedBundleTarget(DocumentId.new(), source, fingerprint)
        val rebound = ReboundDocumentBundle(
            target = target,
            snapshot = incoming,
            photoFiles = mapOf(
                LegacyStateFixture.PHOTO_ONE to photoOne,
                LegacyStateFixture.PHOTO_TWO to photoTwo
            )
        )

        val repositoryFailureHost = FakeImportHost(target.documentId, old, old).apply {
            persistResult = SessionSnapshotApplyResult.Failed(
                LocalRepositoryError.InvalidSnapshot("injected repository failure")
            )
        }
        val repositoryFailurePhotos = FakePhotoTransaction()
        val failed = service.applyReboundBundleWithinDocumentTransaction(
            rebound,
            repositoryFailureHost,
            repositoryFailurePhotos
        )
        assertTrue(failed is BundleImportResult.Failed)
        assertEquals(old, repositoryFailureHost.live)
        assertEquals(old, repositoryFailureHost.durable)
        assertTrue(repositoryFailurePhotos.rollbackForCrossStoreCalled)
        assertTrue(repositoryFailurePhotos.completeCrossStoreRollbackCalled)
        assertFalse(repositoryFailurePhotos.rollbackCalled)
        assertTrue(repositoryFailureHost.restoreCalled > 0)

        val photoFailureHost = FakeImportHost(target.documentId, old, old)
        val photoFailure = FakePhotoTransaction().apply { publishFailure = IOException("injected photo failure") }
        val photoFailed = service.applyReboundBundleWithinDocumentTransaction(
            rebound,
            photoFailureHost,
            photoFailure
        )
        assertTrue(photoFailed is BundleImportResult.Failed)
        assertEquals(old, photoFailureHost.live)
        assertEquals(old, photoFailureHost.durable)
        assertTrue(photoFailure.rollbackForCrossStoreCalled)
        assertTrue(photoFailure.completeCrossStoreRollbackCalled)
        assertFalse(photoFailure.rollbackCalled)
        assertTrue(photoFailureHost.restoreCalled > 0)
    }

    @Test
    fun applyServiceRollsBackAlreadyStagedPhotosWhenPreflightRejectsBundle() = runBlocking {
        val service = DocumentBundleService()
        val target = VerifiedBundleTarget(DocumentId.new(), source, fingerprint)
        val host = FakeImportHost(target.documentId, emptySnapshot(source), emptySnapshot(source))
        val staged = FakePhotoTransaction()

        val result = service.applyReboundBundleWithinDocumentTransaction(
            ReboundDocumentBundle(target, emptySnapshot(source), mapOf("unexpected.png" to photoOne)),
            host,
            staged
        )

        assertTrue(result is BundleImportResult.Failed)
        assertTrue(staged.rollbackCalled)
        assertEquals(emptySnapshot(source), host.live)
        assertEquals(emptySnapshot(source), host.durable)
        assertEquals(0, host.restoreCalled)
    }

    @Test
    fun applyServiceReturnsStaleAndRethrowsCancellationWithoutLeavingNewState() = runBlocking {
        val service = DocumentBundleService()
        val old = emptySnapshot(source)
        val incoming = snapshotFromLegacyPageData(LegacyStateFixture.fullyPopulatedPageData(), source)
        val target = VerifiedBundleTarget(DocumentId.new(), source, fingerprint)
        val rebound = ReboundDocumentBundle(
            target,
            incoming,
            mapOf(LegacyStateFixture.PHOTO_ONE to photoOne, LegacyStateFixture.PHOTO_TWO to photoTwo)
        )

        val staleHost = FakeImportHost(target.documentId, old, old).apply {
            persistResult = SessionSnapshotApplyResult.Stale
        }
        val stalePhotos = FakePhotoTransaction()
        assertTrue(
            service.applyReboundBundleWithinDocumentTransaction(rebound, staleHost, stalePhotos) is BundleImportResult.Stale
        )
        assertEquals(old, staleHost.live)
        assertEquals(old, staleHost.durable)
        assertTrue(stalePhotos.rollbackForCrossStoreCalled)
        assertTrue(stalePhotos.completeCrossStoreRollbackCalled)
        assertFalse(stalePhotos.rollbackCalled)

        val canceledHost = FakeImportHost(target.documentId, old, old).apply {
            persistFailure = CancellationException("injected cancellation")
        }
        val canceledPhotos = FakePhotoTransaction()
        try {
            service.applyReboundBundleWithinDocumentTransaction(rebound, canceledHost, canceledPhotos)
            fail("cancellation must be rethrown")
        } catch (_: CancellationException) {
            assertEquals(old, canceledHost.live)
            assertEquals(old, canceledHost.durable)
            assertTrue(canceledPhotos.rollbackForCrossStoreCalled)
            assertTrue(canceledPhotos.completeCrossStoreRollbackCalled)
            assertFalse(canceledPhotos.rollbackCalled)
            assertTrue(canceledHost.restoreCalled > 0)
        }
    }

    @Test
    fun cancellationAfterCanonicalApplyIsObservedBeforePhotoPublication() = runBlocking {
        val service = DocumentBundleService()
        val old = emptySnapshot(source)
        val incoming = snapshotFromLegacyPageData(LegacyStateFixture.fullyPopulatedPageData(), source)
        val target = VerifiedBundleTarget(DocumentId.new(), source, fingerprint)
        val rebound = ReboundDocumentBundle(
            target,
            incoming,
            mapOf(LegacyStateFixture.PHOTO_ONE to photoOne, LegacyStateFixture.PHOTO_TWO to photoTwo)
        )
        val host = FakeImportHost(target.documentId, old, old).apply {
            cancelAfterApplied = true
        }
        val photos = FakePhotoTransaction()
        val thrown = CompletableDeferred<Throwable>()
        val job = launch {
            try {
                service.applyReboundBundleWithinDocumentTransaction(rebound, host, photos)
                thrown.complete(AssertionError("cancellation must not return Applied"))
            } catch (error: Throwable) {
                thrown.complete(error)
            }
        }
        job.join()

        val error = thrown.await()
        assertTrue(error is CancellationException)
        assertEquals(old, host.live)
        assertEquals(old, host.durable)
        assertFalse(photos.publishCalled)
        assertFalse(photos.commitCalled)
        assertTrue(photos.rollbackForCrossStoreCalled)
        assertTrue(photos.completeCrossStoreRollbackCalled)
        assertFalse(photos.rollbackCalled)
    }

    @Test
    fun cancellationDuringNonCancellablePhotoCommitIsRethrownWithoutUnsafeRollback() = runBlocking {
        val service = DocumentBundleService()
        val old = emptySnapshot(source)
        val incoming = snapshotFromLegacyPageData(LegacyStateFixture.fullyPopulatedPageData(), source)
        val target = VerifiedBundleTarget(DocumentId.new(), source, fingerprint)
        val rebound = ReboundDocumentBundle(
            target,
            incoming,
            mapOf(LegacyStateFixture.PHOTO_ONE to photoOne, LegacyStateFixture.PHOTO_TWO to photoTwo)
        )
        val host = FakeImportHost(target.documentId, old, old)
        val photos = FakePhotoTransaction()
        val thrown = CompletableDeferred<Throwable>()
        val job = launch {
            val childContext = currentCoroutineContext()
            photos.cancelDuringCommit = {
                childContext.cancel(CancellationException("injected cancellation during photo commit"))
            }
            try {
                service.applyReboundBundleWithinDocumentTransaction(rebound, host, photos)
                thrown.complete(AssertionError("cancellation must not return Applied"))
            } catch (error: Throwable) {
                thrown.complete(error)
            }
        }
        job.join()

        val error = thrown.await()
        assertTrue(error is CancellationException)
        // The NonCancellable commit completed, so the new canonical/photo
        // tuple remains authoritative and its resolver is released for
        // recovery; an unsafe old-state rollback is forbidden.
        assertEquals(incoming, host.live)
        assertEquals(incoming, host.durable)
        assertTrue(photos.publishCalled)
        assertTrue(photos.commitCalled)
        assertTrue(photos.authoritativeCommit)
        assertTrue(photos.releaseAfterFailureCalled)
        assertFalse(photos.rollbackForCrossStoreCalled)
        assertFalse(photos.completeCrossStoreRollbackCalled)
        assertFalse(photos.rollbackCalled)
    }

    @Test
    fun applyServiceRetainsPhotoEvidenceAcrossMutatingStaleAndRestoreFailure() = runBlocking {
        val service = DocumentBundleService()
        val old = emptySnapshot(source)
        val incoming = snapshotFromLegacyPageData(LegacyStateFixture.fullyPopulatedPageData(), source)
        val target = VerifiedBundleTarget(DocumentId.new(), source, fingerprint)
        val rebound = ReboundDocumentBundle(
            target,
            incoming,
            mapOf(LegacyStateFixture.PHOTO_ONE to photoOne, LegacyStateFixture.PHOTO_TWO to photoTwo)
        )

        val staleHost = FakeImportHost(target.documentId, old, old).apply {
            mutateBeforeReturningStale = true
            persistResult = SessionSnapshotApplyResult.Stale
        }
        val stalePhotos = FakePhotoTransaction()
        val stale = service.applyReboundBundleWithinDocumentTransaction(rebound, staleHost, stalePhotos)
        assertEquals(BundleImportResult.Stale, stale)
        assertEquals(old, staleHost.live)
        assertEquals(old, staleHost.durable)
        assertTrue(stalePhotos.rollbackForCrossStoreCalled)
        assertTrue(stalePhotos.completeCrossStoreRollbackCalled)
        assertFalse(stalePhotos.rollbackCalled)

        val restoreFailureHost = FakeImportHost(target.documentId, old, old).apply {
            mutateBeforeReturningStale = true
            persistResult = SessionSnapshotApplyResult.Stale
            restoreResult = SessionSnapshotApplyResult.Failed(
                LocalRepositoryError.InvalidSnapshot("injected restore failure")
            )
        }
        val restoreFailurePhotos = FakePhotoTransaction()
        val failed = service.applyReboundBundleWithinDocumentTransaction(
            rebound,
            restoreFailureHost,
            restoreFailurePhotos
        )
        assertTrue(failed is BundleImportResult.Failed)
        assertTrue(restoreFailurePhotos.rollbackForCrossStoreCalled)
        assertFalse(restoreFailurePhotos.completeCrossStoreRollbackCalled)
        assertTrue(restoreFailurePhotos.releaseAfterFailureCalled)
        assertFalse(restoreFailurePhotos.rollbackCalled)
        // The service must not report Stale/Applied after canonical restore
        // proof failed, even though the fake host still contains the mutation.
        assertEquals(incoming, restoreFailureHost.live)
        assertEquals(incoming, restoreFailureHost.durable)
    }

    private fun photoBundle(): BundleExportInput {
        val snapshot = snapshotFromLegacyPageData(
            mapOf(
                0 to PageData(
                    paths = emptyList(),
                    measurements = emptyList(),
                    notes = emptyList(),
                    photoPins = listOf(
                        com.example.myapplication.PhotoPin(
                            x = 0.5f,
                            y = 0.5f,
                            id = "photo-pin",
                            imageFileNames = mutableListOf(LegacyStateFixture.PHOTO_ONE, LegacyStateFixture.PHOTO_TWO),
                            imageNotes = mutableMapOf(),
                            imageShapes = mutableMapOf()
                        )
                    ),
                    scale = null,
                    shapes = emptyList()
                )
            ),
            source
        )
        return BundleExportInput(
            exportedDocumentId = DocumentId.new(),
            source = source,
            sourceFingerprint = fingerprint,
            snapshot = snapshot,
            photoFiles = mapOf(
                LegacyStateFixture.PHOTO_ONE to photoOne,
                LegacyStateFixture.PHOTO_TWO to photoTwo
            )
        )
    }

    private fun emptySnapshot(actualSource: DocumentSourceIdentityV1) = DocumentSnapshotV1(
        schemaVersion = 1,
        snapshotRevision = 0L,
        source = actualSource,
        pages = emptyMap()
    )

    private fun pngBytes(color: Color): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 2) for (y in 0 until 2) image.setRGB(x, y, color.rgb)
        return ByteArrayOutputStream().also { output ->
            check(ImageIO.write(image, "png", output))
        }.toByteArray()
    }

    private fun unzipEntries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    private fun zipEntries(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun storedZipEntries(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    val checksum = CRC32().also { it.update(bytes) }
                    val entry = ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        crc = checksum.value
                    }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun deflatedZipEntries(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    val checksum = CRC32().also { it.update(bytes) }
                    val compressed = rawDeflate(bytes)
                    val entry = ZipEntry(name).apply {
                        method = ZipEntry.DEFLATED
                        size = bytes.size.toLong()
                        compressedSize = compressed.size.toLong()
                        crc = checksum.value
                    }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun rawDeflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        return try {
            deflater.setInput(bytes)
            deflater.finish()
            ByteArrayOutputStream().also { output ->
                val buffer = ByteArray(8 * 1024)
                while (!deflater.finished()) {
                    val written = deflater.deflate(buffer)
                    if (written == 0) fail("deflater made no progress")
                    output.write(buffer, 0, written)
                }
            }.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun dataDescriptorZipEntry(name: String, bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
                zip.write(bytes)
                zip.closeEntry()
            }
        }.toByteArray()

    private fun findCentralEntry(archive: ByteArray, name: ByteArray): Int {
        for (position in 0..archive.size - 46) {
            if (readU32(archive, position) == 0x02014b50L &&
                readU16(archive, position + 28) == name.size &&
                archive.copyOfRange(position + 46, position + 46 + name.size).contentEquals(name)
            ) return position
        }
        fail("central directory entry not found")
        return -1
    }

    private fun replaceCentralName(archive: ByteArray, oldName: String, newName: String) {
        val oldBytes = oldName.toByteArray()
        val newBytes = newName.toByteArray()
        assertEquals(oldBytes.size, newBytes.size)
        val position = findCentralEntry(archive, oldBytes)
        newBytes.copyInto(archive, position + 46)
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) bytes[offset + index] = (value ushr (8 * index)).toByte()
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("operation should have been rejected")
        } catch (_: Stage5ValidationException) {
        } catch (_: IOException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    private class FailingOutputStream(
        private val failOnFlush: Boolean = false,
        private val failOnClose: Boolean = false
    ) : ByteArrayOutputStream() {
        override fun flush() {
            if (failOnFlush) throw IOException("injected flush failure")
            super.flush()
        }

        override fun close() {
            if (failOnClose) throw IOException("injected close failure")
            super.close()
        }
    }

    private class CloseFailInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        override fun close() {
            throw IOException("injected input close failure")
        }
    }

    private class FakePhotoTransaction : PhotoContentTransaction {
        var rollbackCalled = false
        var rollbackForCrossStoreCalled = false
        var completeCrossStoreRollbackCalled = false
        var releaseAfterFailureCalled = false
        var authoritativeCommit = false
        var publishFailure: Throwable? = null
        var publishCalled = false
        var commitCalled = false
        var cancelDuringCommit: (() -> Unit)? = null

        override suspend fun publish() {
            publishCalled = true
            publishFailure?.let { throw it }
        }

        override suspend fun commit() {
            commitCalled = true
            cancelDuringCommit?.let { cancel ->
                authoritativeCommit = true
                cancel()
            }
        }

        override suspend fun rollback() {
            rollbackCalled = true
        }

        override suspend fun rollbackForCrossStoreCompensation() {
            rollbackForCrossStoreCalled = true
        }

        override suspend fun completeCrossStoreRollback() {
            completeCrossStoreRollbackCalled = true
        }

        override fun hasAuthoritativeCommit(): Boolean = authoritativeCommit

        override fun releaseAfterFailure() {
            releaseAfterFailureCalled = true
        }
    }

    private class FakeImportHost(
        override val documentId: DocumentId,
        initialLive: DocumentSnapshotV1,
        initialDurable: DocumentSnapshotV1
    ) : DocumentBundleImportHost {
        var live: DocumentSnapshotV1 = initialLive
        var durable: DocumentSnapshotV1 = initialDurable
        var persistResult: SessionSnapshotApplyResult = SessionSnapshotApplyResult.Applied
        var persistFailure: Throwable? = null
        var persistCalled = 0
        var restoreCalled: Int = 0
        var mutateBeforeReturningStale = false
        var restoreResult: SessionSnapshotApplyResult = SessionSnapshotApplyResult.Applied
        var cancelAfterApplied = false

        override suspend fun captureCurrentLiveSnapshot(): DocumentSnapshotV1 = live

        override suspend fun captureCurrentDurableSnapshot(): DocumentSnapshotV1 = durable

        override suspend fun persistAndApply(snapshot: DocumentSnapshotV1): SessionSnapshotApplyResult {
            persistCalled++
            persistFailure?.let { throw it }
            if (mutateBeforeReturningStale) {
                durable = snapshot
                live = snapshot
            }
            return when (persistResult) {
                SessionSnapshotApplyResult.Applied -> {
                    durable = snapshot
                    live = snapshot
                    if (cancelAfterApplied) {
                        currentCoroutineContext().cancel(CancellationException("injected cancellation after apply"))
                    }
                    SessionSnapshotApplyResult.Applied
                }
                SessionSnapshotApplyResult.Stale -> SessionSnapshotApplyResult.Stale
                is SessionSnapshotApplyResult.Failed -> persistResult
            }
        }

        override suspend fun restore(
            durableSnapshot: DocumentSnapshotV1,
            liveSnapshot: DocumentSnapshotV1
        ): SessionSnapshotApplyResult {
            restoreCalled++
            if (restoreResult == SessionSnapshotApplyResult.Applied) {
                durable = durableSnapshot
                live = liveSnapshot
            }
            return restoreResult
        }
    }

    private class RepositoryImportHost(
        private val repository: LocalDocumentRepository,
        private val association: DocumentAssociation,
        initialSnapshot: DocumentSnapshotV1
    ) : DocumentBundleImportHost {
        override val documentId: DocumentId = association.documentId
        var live: DocumentSnapshotV1 = initialSnapshot
        var durable: DocumentSnapshotV1 = initialSnapshot

        override suspend fun captureCurrentLiveSnapshot(): DocumentSnapshotV1 = live

        override suspend fun captureCurrentDurableSnapshot(): DocumentSnapshotV1 = durable

        override suspend fun persistAndApply(snapshot: DocumentSnapshotV1): SessionSnapshotApplyResult {
            return when (val saved = repository.save(association, snapshot)) {
                is DocumentSaveResult.Saved -> {
                    durable = snapshot
                    live = snapshot
                    SessionSnapshotApplyResult.Applied
                }
                is DocumentSaveResult.Failed -> SessionSnapshotApplyResult.Failed(saved.error)
            }
        }

        override suspend fun restore(
            durableSnapshot: DocumentSnapshotV1,
            liveSnapshot: DocumentSnapshotV1
        ): SessionSnapshotApplyResult {
            return when (val saved = repository.save(association, durableSnapshot)) {
                is DocumentSaveResult.Saved -> {
                    durable = durableSnapshot
                    live = liveSnapshot
                    SessionSnapshotApplyResult.Applied
                }
                is DocumentSaveResult.Failed -> SessionSnapshotApplyResult.Failed(saved.error)
            }
        }
    }

    private class RepositoryNullableDurableImportHost(
        private val repository: LocalDocumentRepository,
        private val association: DocumentAssociation,
        initialSnapshot: DocumentSnapshotV1,
        private val beforeExactRestore: (() -> Unit)? = null
    ) : DocumentBundleImportHost {
        override val documentId: DocumentId = association.documentId
        var live: DocumentSnapshotV1 = initialSnapshot
        var durable: DocumentSnapshotV1? = null
        var exactRestoreCalled = false

        override suspend fun captureCurrentLiveSnapshot(): DocumentSnapshotV1 = live

        override suspend fun captureCurrentDurableSnapshot(): DocumentSnapshotV1? =
            when (val loaded = repository.load(association)) {
                is DocumentLoadResult.Loaded -> loaded.snapshot
                DocumentLoadResult.NotFound -> null
                is DocumentLoadResult.Failed -> throw DocumentBundleException(
                    "current durable snapshot could not be read during bundle import",
                    IllegalStateException(loaded.error.toString())
                )
            }

        override suspend fun captureCurrentDurableState() =
            repository.captureDurableSnapshotState(association)

        override suspend fun persistAndApply(snapshot: DocumentSnapshotV1): SessionSnapshotApplyResult {
            return when (val saved = repository.save(association, snapshot)) {
                is DocumentSaveResult.Saved -> {
                    durable = snapshot
                    live = snapshot
                    SessionSnapshotApplyResult.Applied
                }
                is DocumentSaveResult.Failed -> SessionSnapshotApplyResult.Failed(saved.error)
            }
        }

        override suspend fun restore(
            durableSnapshot: DocumentSnapshotV1,
            liveSnapshot: DocumentSnapshotV1
        ): SessionSnapshotApplyResult {
            return when (val saved = repository.save(association, durableSnapshot)) {
                is DocumentSaveResult.Saved -> {
                    durable = durableSnapshot
                    live = liveSnapshot
                    SessionSnapshotApplyResult.Applied
                }
                is DocumentSaveResult.Failed -> SessionSnapshotApplyResult.Failed(saved.error)
            }
        }

        override suspend fun restore(
            durableState: com.example.myapplication.stage2.DocumentDurableSnapshotState,
            liveSnapshot: DocumentSnapshotV1
        ): SessionSnapshotApplyResult {
            exactRestoreCalled = true
            beforeExactRestore?.invoke()
            return when (val restored = repository.restoreDurableSnapshotState(association, durableState)) {
                is DocumentSaveResult.Saved -> {
                    durable = durableState.current?.snapshot ?: durableState.previous?.snapshot
                    live = liveSnapshot
                    SessionSnapshotApplyResult.Applied
                }
                is DocumentSaveResult.Failed -> SessionSnapshotApplyResult.Failed(restored.error)
            }
        }
    }
}
