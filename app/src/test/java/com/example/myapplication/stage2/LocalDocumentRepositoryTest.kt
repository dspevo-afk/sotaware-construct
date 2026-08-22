package com.example.myapplication.stage2

import com.example.myapplication.stage0.LegacyStateFixture
import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.snapshotFromLegacyPageData
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class LocalDocumentRepositoryTest {
    private val roots = mutableListOf<File>()

    @After
    fun cleanup() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun documentId_isCanonicalUuidAndStableAcrossRepositoryReload() = runBlocking {
        val firstRepository = repository()
        val source = source("content://documents/plan.pdf")
        val first = resolve(firstRepository, source, fingerprint("A"))
        assertEquals(first.documentId, resolve(firstRepository, source, fingerprint("A")).documentId)

        val secondRepository = LocalDocumentRepository(firstRepositoryRoot())
        val reloaded = resolve(secondRepository, source, fingerprint("A"))
        assertEquals(first.documentId, reloaded.documentId)
        assertEquals(first.documentId.value, first.documentId.value.lowercase())
        assertEquals(first.documentId.value, java.util.UUID.fromString(first.documentId.value).toString())
    }

    @Test
    fun sameNameDifferentUris_getDifferentIdsAndIndependentSnapshots() = runBlocking {
        val repository = repository()
        val sourceA = source("content://provider/a", "plan.pdf")
        val sourceB = source("content://provider/b", "plan.pdf")
        val associationA = resolve(repository, sourceA, fingerprint("A"))
        val associationB = resolve(repository, sourceB, fingerprint("B"))
        assertNotEquals(associationA.documentId, associationB.documentId)

        val snapshotA = emptySnapshot(sourceA).copy(snapshotRevision = 11)
        val snapshotB = fullSnapshot(sourceB).copy(snapshotRevision = 12)
        assertTrue(repository.save(associationA, snapshotA) is DocumentSaveResult.Saved)
        assertTrue(repository.save(associationB, snapshotB) is DocumentSaveResult.Saved)

        val loadedA = repository.load(associationA) as DocumentLoadResult.Loaded
        val loadedB = repository.load(associationB) as DocumentLoadResult.Loaded
        assertEquals(snapshotA, loadedA.snapshot)
        assertEquals(snapshotB, loadedB.snapshot)
    }

    @Test
    fun sameUriWithChangedContent_isDetectedAndNeverLoadedAsOldContent() = runBlocking {
        val repository = repository()
        val source = source("content://provider/reused")
        val oldFingerprint = fingerprint("old bytes")
        val newFingerprint = fingerprint("new bytes")
        val association = resolve(repository, source, oldFingerprint)
        val oldSnapshot = emptySnapshot(source)
        assertTrue(repository.save(association, oldSnapshot) is DocumentSaveResult.Saved)

        val resolved = repository.resolveOrCreate(source, newFingerprint)
        assertTrue(resolved is ResolveDocumentResult.SourceChanged)
        val load = repository.load(association.documentId, source.sourceUri, newFingerprint)
        assertTrue(load is DocumentLoadResult.Failed)
        assertTrue((load as DocumentLoadResult.Failed).error is LocalRepositoryError.SourceChanged)
    }

    @Test
    fun existingFingerprintWithUnreadableSource_isNotSilentlyReopened() = runBlocking {
        val repository = repository()
        val source = source("content://provider/unreadable")
        val association = resolve(repository, source, fingerprint("known bytes"))
        val result = repository.resolveOrCreate(source, currentFingerprint = null)
        assertTrue(result is ResolveDocumentResult.FingerprintUnavailable)
        assertEquals(association.documentId, (result as ResolveDocumentResult.FingerprintUnavailable).documentId)
    }

    @Test
    fun existingUnfingerprintedSnapshotWithUnreadableSource_isAlsoExplicitlyRejected() = runBlocking {
        val repository = repository()
        val source = source("content://provider/unfingerprinted")
        val association = resolve(repository, source, null)
        assertTrue(repository.save(association, emptySnapshot(source)) is DocumentSaveResult.Saved)

        val result = repository.resolveOrCreate(source, currentFingerprint = null)
        assertTrue(result is ResolveDocumentResult.FingerprintUnavailable)
        assertEquals(null, (result as ResolveDocumentResult.FingerprintUnavailable).storedFingerprint)
    }

    @Test
    fun identicalBytesFromDifferentUris_doNotCollapseIdentity() = runBlocking {
        val repository = repository()
        val bytes = fingerprint("same PDF bytes")
        val first = resolve(repository, source("content://provider/one", "plan.pdf"), bytes)
        val second = resolve(repository, source("content://provider/two", "plan.pdf"), bytes)
        assertNotEquals(first.documentId, second.documentId)
    }

    @Test
    fun manifestRoundTrip_preservesProviderFingerprintAndMigrationFields() = runBlocking {
        val repository = repository()
        val source = source("content://provider/manifest", "plan.pdf")
        val fingerprint = fingerprint("manifest bytes")
        val association = resolve(repository, source, fingerprint)
        val manifest = repository.readManifest() as ManifestReadResult.Loaded
        val entry = manifest.entries.single()
        assertEquals(association.documentId, entry.documentId)
        assertEquals(source.sourceUri, entry.sourceUri)
        assertEquals(source.displayName, entry.displayName)
        assertEquals(source.providerMetadata, entry.providerMetadata)
        assertEquals(fingerprint, entry.sourceFingerprint)
        assertFalse(entry.migrationVerified)
        assertFalse(entry.legacyMigrationClaimed)
        assertEquals("markups_${source.sourceUri.hashCode()}.bin", entry.legacyArtifactName)

        val reloaded = LocalDocumentRepository(firstRepositoryRoot())
            .readManifest() as ManifestReadResult.Loaded
        assertEquals(manifest.entries, reloaded.entries)
    }

    @Test
    fun manifestInterruptedReplacement_keepsPreviousMappingAndDoesNotRemapIt() = runBlocking {
        val injector = ArmableFailureInjector()
        val repository = repository(injector)
        val sourceA = source("content://provider/a")
        val sourceB = source("content://provider/b")
        val associationA = resolve(repository, sourceA, fingerprint("A"))
        injector.arm(RepositoryWritePhase.MANIFEST_BEFORE_REPLACE)
        val failed = repository.resolveOrCreate(sourceB, fingerprint("B"))
        assertTrue(failed is ResolveDocumentResult.Failed)

        val reloaded = LocalDocumentRepository(firstRepositoryRoot())
        val associationAAfter = resolve(reloaded, sourceA, fingerprint("A"))
        assertEquals(associationA.documentId, associationAAfter.documentId)
        val associationBAfter = resolve(reloaded, sourceB, fingerprint("B"))
        assertNotEquals(associationA.documentId, associationBAfter.documentId)
    }

    @Test
    fun corruptCurrentManifest_recoversPreviousManifestAndQuarantinesCorruptBytes() = runBlocking {
        val repository = repository()
        val sourceA = source("content://provider/a")
        val sourceB = source("content://provider/b")
        resolve(repository, sourceA, fingerprint("A"))
        resolve(repository, sourceB, fingerprint("B"))
        val manifestFile = File(firstRepositoryRoot(), "document-manifest.json")
        manifestFile.writeText("{ not valid manifest", Charsets.UTF_8)

        val result = repository.readManifest() as ManifestReadResult.Loaded
        assertTrue(result.recoveredFromPrevious)
        assertEquals(listOf(sourceA.sourceUri), result.entries.map { it.sourceUri })
        assertTrue(File(firstRepositoryRoot(), "quarantine").walk().any { it.isFile })
    }

    @Test
    fun corruptManifestWithoutRecovery_isExplicitFailureAndDoesNotAllocateNewId() = runBlocking {
        val repository = repository()
        val source = source("content://provider/a")
        resolve(repository, source, fingerprint("A"))
        File(firstRepositoryRoot(), "document-manifest.json").writeText("truncated", Charsets.UTF_8)

        val result = repository.resolveOrCreate(source("content://provider/new"), fingerprint("N"))
        assertTrue(result is ResolveDocumentResult.Failed)
        assertTrue((result as ResolveDocumentResult.Failed).error is LocalRepositoryError.CorruptManifest)
    }

    @Test
    fun syntacticallyValidEmptyManifest_isNotAcceptedAsAReset() = runBlocking {
        val repository = repository()
        val source = source("content://provider/known")
        val original = resolve(repository, source, fingerprint("known"))
        resolve(repository, source("content://provider/previous"), fingerprint("previous"))
        File(firstRepositoryRoot(), "document-manifest.json").writeText(
            "{\"schemaVersion\":1,\"entries\":[]}",
            Charsets.UTF_8
        )

        val result = repository.resolveOrCreate(source("content://provider/new"), fingerprint("new"))
        assertTrue(result is ResolveDocumentResult.Failed)
        assertTrue((result as ResolveDocumentResult.Failed).error is LocalRepositoryError.CorruptManifest)
        val knownAgain = resolve(repository, source, fingerprint("known"))
        assertEquals(original.documentId, knownAgain.documentId)
    }

    @Test
    fun fullyPopulatedSnapshot_roundTripsEveryCanonicalDomain() = runBlocking {
        val repository = repository()
        val source = source("content://provider/full")
        val association = resolve(repository, source, fingerprint("full"))
        val expected = fullSnapshot(source).copy(snapshotRevision = 99)
        assertTrue(repository.save(association, expected) is DocumentSaveResult.Saved)

        val actual = (repository.load(association) as DocumentLoadResult.Loaded).snapshot
        assertEquals(expected, actual)
        assertEquals(setOf(0, 2), actual.pages.keys)
        assertEquals(1, actual.pages.getValue(0).paths.size)
        assertEquals(1, actual.pages.getValue(0).measurements.size)
        assertEquals(1, actual.pages.getValue(0).notes.size)
        assertEquals(1, actual.pages.getValue(0).shapes.size)
        assertEquals(1, actual.pages.getValue(0).photoPins.size)
        assertNotNull(actual.pages.getValue(0).photoPins.single().imageNotes[LegacyStateFixture.PHOTO_ONE])
        assertNotNull(actual.pages.getValue(0).photoPins.single().imageShapes[LegacyStateFixture.PHOTO_ONE])
        assertEquals(18.5f, actual.pages.getValue(2).scale?.pixelsPerFoot)
    }

    @Test
    fun sparsePages_scaleOnlyShapeOnlyPhotoOnlyAndEmptyDocumentRemainDistinct() = runBlocking {
        val repository = repository()
        val source = source("content://provider/sparse")
        val association = resolve(repository, source, fingerprint("sparse"))
        val full = fullSnapshot(source)
        val fullPage = full.pages.getValue(0)
        val expected = DocumentSnapshotV1(
            schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
            snapshotRevision = 0,
            source = source,
            pages = mapOf(
                1 to PageSnapshotV1(scale = full.pages.getValue(2).scale),
                2 to PageSnapshotV1(shapes = fullPage.shapes),
                3 to PageSnapshotV1(photoPins = fullPage.photoPins)
            )
        )
        assertTrue(repository.save(association, expected) is DocumentSaveResult.Saved)
        val actual = (repository.load(association) as DocumentLoadResult.Loaded).snapshot
        assertEquals(expected, actual)
        assertTrue(actual.pages.getValue(1).paths.isEmpty())
        assertTrue(actual.pages.getValue(2).scale == null)
        assertTrue(actual.pages.getValue(3).photoPins.single().imageShapes.isNotEmpty())

        val emptySource = source("content://provider/empty")
        val emptyAssociation = resolve(repository, emptySource, fingerprint("empty"))
        val empty = emptySnapshot(emptySource)
        assertTrue(repository.save(emptyAssociation, empty) is DocumentSaveResult.Saved)
        val loadedEmpty = repository.load(emptyAssociation) as DocumentLoadResult.Loaded
        assertTrue(loadedEmpty.snapshot.pages.isEmpty())
    }

    @Test
    fun interruptedSnapshotWrite_preservesPreviousCompleteSnapshot() = runBlocking {
        val injector = ArmableFailureInjector()
        val repository = repository(injector)
        val source = source("content://provider/interrupted")
        val association = resolve(repository, source, fingerprint("source"))
        val snapshotA = fullSnapshot(source).copy(snapshotRevision = 1)
        val snapshotB = emptySnapshot(source).copy(snapshotRevision = 2)
        assertTrue(repository.save(association, snapshotA) is DocumentSaveResult.Saved)
        injector.arm(RepositoryWritePhase.SNAPSHOT_BEFORE_REPLACE)
        assertTrue(repository.save(association, snapshotB) is DocumentSaveResult.Failed)

        val loaded = repository.load(association) as DocumentLoadResult.Loaded
        assertEquals(snapshotA, loaded.snapshot)
    }

    @Test
    fun truncatedTemporarySnapshot_isRejectedBeforeItCanReplaceCurrent() = runBlocking {
        var armed = false
        val injector = object : RepositoryFailureInjector {
            override fun onPhase(phase: RepositoryWritePhase, documentId: DocumentId?, stagedFile: File?) {
                if (armed && phase == RepositoryWritePhase.SNAPSHOT_STAGE_WRITTEN) {
                    stagedFile!!.writeText("truncated temporary payload", Charsets.UTF_8)
                    armed = false
                }
            }
        }
        val repository = repository(injector)
        val source = source("content://provider/truncated")
        val association = resolve(repository, source, fingerprint("source"))
        val snapshotA = fullSnapshot(source).copy(snapshotRevision = 1)
        val snapshotB = emptySnapshot(source).copy(snapshotRevision = 2)
        assertTrue(repository.save(association, snapshotA) is DocumentSaveResult.Saved)
        armed = true
        assertTrue(repository.save(association, snapshotB) is DocumentSaveResult.Failed)

        assertEquals(snapshotA, (repository.load(association) as DocumentLoadResult.Loaded).snapshot)
    }

    @Test
    fun corruptCurrentWithValidPrevious_recoversPreviousAndKeepsCorruptArtifact() = runBlocking {
        val repository = repository()
        val source = source("content://provider/recover")
        val association = resolve(repository, source, fingerprint("source"))
        val snapshotA = fullSnapshot(source).copy(snapshotRevision = 1)
        val snapshotB = emptySnapshot(source).copy(snapshotRevision = 2)
        repository.save(association, snapshotA)
        repository.save(association, snapshotB)
        repository.currentSnapshotFile(association.documentId).writeText("corrupt current", Charsets.UTF_8)

        val loaded = repository.load(association) as DocumentLoadResult.Loaded
        assertTrue(loaded.recoveredFromPrevious)
        assertEquals(snapshotA, loaded.snapshot)
        assertTrue(repository.snapshotQuarantineDirectory(association.documentId).walk().any { it.isFile })
    }

    @Test
    fun corruptCurrentWithoutPrevious_returnsTypedFailureNotEmptyDocument() = runBlocking {
        val repository = repository()
        val source = source("content://provider/no-recovery")
        val association = resolve(repository, source, fingerprint("source"))
        repository.save(association, fullSnapshot(source))
        repository.currentSnapshotFile(association.documentId).writeText("corrupt", Charsets.UTF_8)

        val result = repository.load(association)
        assertTrue(result is DocumentLoadResult.Failed)
        assertTrue((result as DocumentLoadResult.Failed).error is LocalRepositoryError.CorruptSnapshot)
    }

    @Test
    fun orphanedSnapshotStagingFile_isQuarantinedAndNeverReportedAsNotFound() = runBlocking {
        val repository = repository()
        val source = source("content://provider/orphaned-staging")
        val association = resolve(repository, source, fingerprint("source"))
        val documentDirectory = repository.currentSnapshotFile(association.documentId).parentFile!!
            .apply { mkdirs() }
        File(documentDirectory, "snapshot.orphaned.tmp").writeText("unaccepted", Charsets.UTF_8)

        val result = repository.load(association)
        assertTrue(result is DocumentLoadResult.Failed)
        assertTrue((result as DocumentLoadResult.Failed).error is LocalRepositoryError.CorruptSnapshot)
        assertTrue(repository.snapshotQuarantineDirectory(association.documentId).walk().any { it.isFile })
    }

    @Test
    fun mismatchedCurrentWithValidPrevious_recoversPreviousInsteadOfBlankingDocument() = runBlocking {
        val repository = repository()
        val source = source("content://provider/mismatched-current")
        val otherSource = source("content://provider/other")
        val association = resolve(repository, source, fingerprint("source"))
        val otherAssociation = resolve(repository, otherSource, fingerprint("other"))
        val previous = fullSnapshot(source).copy(snapshotRevision = 1)
        val current = emptySnapshot(source).copy(snapshotRevision = 2)
        repository.save(association, previous)
        repository.save(association, current)
        repository.save(otherAssociation, fullSnapshot(otherSource))
        repository.currentSnapshotFile(otherAssociation.documentId).copyTo(
            repository.currentSnapshotFile(association.documentId),
            overwrite = true
        )

        val loaded = repository.load(association)
        assertTrue(loaded is DocumentLoadResult.Loaded)
        assertTrue((loaded as DocumentLoadResult.Loaded).recoveredFromPrevious)
        assertEquals(previous, loaded.snapshot)
    }

    @Test
    fun corruptPrevious_doesNotOverrideValidCurrent() = runBlocking {
        val repository = repository()
        val source = source("content://provider/current")
        val association = resolve(repository, source, fingerprint("source"))
        val current = emptySnapshot(source).copy(snapshotRevision = 2)
        repository.save(association, fullSnapshot(source).copy(snapshotRevision = 1))
        repository.save(association, current)
        repository.previousSnapshotFile(association.documentId).writeText("corrupt previous", Charsets.UTF_8)
        val loaded = repository.load(association) as DocumentLoadResult.Loaded
        assertEquals(current, loaded.snapshot)
        assertFalse(loaded.recoveredFromPrevious)
    }

    @Test
    fun copiedSnapshotIntoAnotherDocumentStorage_returnsAssociationMismatch() = runBlocking {
        val repository = repository()
        val sourceA = source("content://provider/a")
        val sourceB = source("content://provider/b")
        val associationA = resolve(repository, sourceA, fingerprint("A"))
        val associationB = resolve(repository, sourceB, fingerprint("B"))
        repository.save(associationA, fullSnapshot(sourceA))
        repository.currentSnapshotFile(associationA.documentId).copyTo(
            repository.currentSnapshotFile(associationB.documentId),
            overwrite = true
        )
        val result = repository.load(associationB)
        assertTrue(result is DocumentLoadResult.Failed)
        assertTrue((result as DocumentLoadResult.Failed).error is LocalRepositoryError.AssociationMismatch)
    }

    @Test
    fun concurrentWritesToOneDocument_areSerializedAsCompleteSnapshots() = runBlocking {
        val repository = repository()
        val source = source("content://provider/concurrent")
        val association = resolve(repository, source, fingerprint("source"))
        val snapshots = (1..12).map { revision ->
            fullSnapshot(source).copy(snapshotRevision = revision.toLong())
        }
        coroutineScope {
            snapshots.map { snapshot ->
                async { repository.save(association, snapshot) }
            }.awaitAll()
        }
        val loaded = repository.load(association)
        assertTrue(loaded is DocumentLoadResult.Loaded)
        assertTrue(snapshots.contains((loaded as DocumentLoadResult.Loaded).snapshot))
    }

    @Test
    fun differentDocuments_doNotShareStorageOrLock() = runBlocking {
        val repository = repository()
        val a = resolve(repository, source("content://provider/a"), fingerprint("A"))
        val b = resolve(repository, source("content://provider/b"), fingerprint("B"))
        val results = coroutineScope {
            listOf(
                async { repository.save(a, fullSnapshot(a.source).copy(snapshotRevision = 1)) },
                async { repository.save(b, fullSnapshot(b.source).copy(snapshotRevision = 2)) }
            ).awaitAll()
        }
        assertTrue(results.all { it is DocumentSaveResult.Saved })
        assertEquals(1, (repository.load(a) as DocumentLoadResult.Loaded).snapshot.snapshotRevision)
        assertEquals(2, (repository.load(b) as DocumentLoadResult.Loaded).snapshot.snapshotRevision)
    }

    private fun repository(injector: RepositoryFailureInjector = NoRepositoryFailureInjector): LocalDocumentRepository {
        val root = Files.createTempDirectory("stage2-repository").toFile()
        roots += root
        return LocalDocumentRepository(root, failureInjector = injector)
    }

    private fun firstRepositoryRoot(): File = roots.last()

    private suspend fun resolve(
        repository: LocalDocumentRepository,
        source: DocumentSourceIdentityV1,
        fingerprint: SourceFingerprint?
    ): DocumentAssociation = when (val result = repository.resolveOrCreate(source, fingerprint)) {
        is ResolveDocumentResult.Resolved -> result.association
        else -> error("unexpected resolution: $result")
    }

    private fun source(uri: String, name: String = "plan.pdf") =
        DocumentSourceIdentityV1(uri, name, mapOf("authority" to "provider"))

    private fun fingerprint(text: String): SourceFingerprint =
        SourceFingerprint.fromBytes(text.toByteArray(Charsets.UTF_8))

    private fun fullSnapshot(source: DocumentSourceIdentityV1): DocumentSnapshotV1 =
        snapshotFromLegacyPageData(LegacyStateFixture.fullyPopulatedPageData(), source)

    private fun emptySnapshot(source: DocumentSourceIdentityV1): DocumentSnapshotV1 =
        DocumentSnapshotV1(DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION, 0L, source, emptyMap())

    private class ArmableFailureInjector : RepositoryFailureInjector {
        private var armedPhase: RepositoryWritePhase? = null
        private val fired = AtomicBoolean(false)

        fun arm(phase: RepositoryWritePhase) {
            armedPhase = phase
            fired.set(false)
        }

        override fun onPhase(phase: RepositoryWritePhase, documentId: DocumentId?, stagedFile: File?) {
            if (phase == armedPhase && fired.compareAndSet(false, true)) {
                throw IOException("injected failure at $phase")
            }
        }
    }
}
