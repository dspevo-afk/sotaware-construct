package com.example.myapplication.stage2

import com.example.myapplication.PageMarkups
import com.example.myapplication.PageScale
import com.example.myapplication.stage0.LegacyStateFixture
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.snapshotFromLegacyPageData
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LegacyMigrationTest {
    private val roots = mutableListOf<File>()

    @After
    fun cleanup() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun fullyPopulatedLegacyMigration_preservesEveryFieldAndLegacyArtifact() = runBlocking {
        val root = tempRoot()
        val repository = LocalDocumentRepository(root)
        val source = source("content://legacy/full")
        val legacyFile = File(root, "markups_${source.sourceUri.hashCode()}.bin")
        val originalBytes = "legacy binary bytes preserved".toByteArray()
        legacyFile.writeBytes(originalBytes)
        val state = LegacyDocumentState(
            markups = LegacyStateFixture.fullyPopulatedLegacyMarkups(),
            scales = mapOf(0 to PageScale(42.75f), 2 to PageScale(18.5f)),
            markupArtifact = legacyFile,
            scalePreferenceKeys = setOf("${source.sourceUri}_0", "${source.sourceUri}_2")
        )
        val legacy = FakeLegacySource(mapOf(source.sourceUri to LegacyReadResult.Found(state)))
        val association = resolve(repository, source, SourceFingerprint.fromBytes("pdf".toByteArray()))

        val migrated = repository.migrateLegacy(association, legacy)
        assertTrue(migrated is LegacyMigrationResult.Migrated)
        assertTrue((migrated as LegacyMigrationResult.Migrated).readBackVerified)

        val expected = snapshotFromLegacyPageData(
            LegacyStateFixture.fullyPopulatedPageData(),
            source
        )
        val loaded = repository.load(association) as DocumentLoadResult.Loaded
        assertEquals(expected, loaded.snapshot)
        assertEquals(setOf(0, 2), loaded.snapshot.pages.keys)
        assertEquals(42.75f, loaded.snapshot.pages.getValue(0).scale?.pixelsPerFoot)
        assertEquals(18.5f, loaded.snapshot.pages.getValue(2).scale?.pixelsPerFoot)
        assertTrue(legacyFile.exists())
        assertEquals(originalBytes.toList(), legacyFile.readBytes().toList())
        val manifest = repository.readManifest() as ManifestReadResult.Loaded
        assertTrue(manifest.entries.single().migrationVerified)
    }

    @Test
    fun scaleOnlyLegacyState_becomesARealCanonicalPage() = runBlocking {
        val root = tempRoot()
        val repository = LocalDocumentRepository(root)
        val source = source("content://legacy/scale-only")
        val state = LegacyDocumentState(
            markups = emptyMap(),
            scales = mapOf(7 to PageScale(9.5f)),
            markupArtifact = null,
            scalePreferenceKeys = setOf("${source.sourceUri}_7")
        )
        val association = resolve(repository, source, null)
        val result = repository.migrateLegacy(
            association,
            FakeLegacySource(mapOf(source.sourceUri to LegacyReadResult.Found(state)))
        )
        assertTrue(result is LegacyMigrationResult.Migrated)
        val loaded = repository.load(association) as DocumentLoadResult.Loaded
        assertEquals(setOf(7), loaded.snapshot.pages.keys)
        assertEquals(9.5f, loaded.snapshot.pages.getValue(7).scale?.pixelsPerFoot)
        assertTrue(loaded.snapshot.pages.getValue(7).paths.isEmpty())
        assertTrue(loaded.snapshot.pages.getValue(7).shapes.isEmpty())
    }

    @Test
    fun migrationIsIdempotent_reusesIdAndDoesNotDuplicateOrRewriteSnapshot() = runBlocking {
        val root = tempRoot()
        val repository = LocalDocumentRepository(root)
        val source = source("content://legacy/idempotent")
        val state = LegacyDocumentState(
            markups = LegacyStateFixture.fullyPopulatedLegacyMarkups(),
            scales = mapOf(0 to PageScale(42.75f), 2 to PageScale(18.5f)),
            markupArtifact = File(root, "legacy.bin"),
            scalePreferenceKeys = emptySet()
        )
        state.markupArtifact!!.writeBytes("unchanged".toByteArray())
        val legacy = FakeLegacySource(mapOf(source.sourceUri to LegacyReadResult.Found(state)))
        val association = resolve(repository, source, null)
        val first = repository.migrateLegacy(association, legacy)
        val snapshotFileBytes = repository.currentSnapshotFile(association.documentId).readBytes().toList()
        val second = repository.migrateLegacy(association, legacy)
        assertTrue(first is LegacyMigrationResult.Migrated)
        assertTrue(second is LegacyMigrationResult.AlreadyVerified)
        assertEquals(snapshotFileBytes, repository.currentSnapshotFile(association.documentId).readBytes().toList())
        assertEquals(association.documentId, (repository.readManifest() as ManifestReadResult.Loaded).entries.single().documentId)
        assertEquals(1, (repository.readManifest() as ManifestReadResult.Loaded).entries.size)
    }

    @Test
    fun staleLegacyInput_cannotOverwriteExistingCanonicalSnapshot() = runBlocking {
        val root = tempRoot()
        val repository = LocalDocumentRepository(root)
        val source = source("content://legacy/stale")
        val association = resolve(repository, source, null)
        val newer = snapshotFromLegacyPageData(emptyMap(), source).copy(snapshotRevision = 44)
        repository.save(association, newer)
        val legacyState = LegacyDocumentState(
            markups = LegacyStateFixture.fullyPopulatedLegacyMarkups(),
            scales = mapOf(0 to PageScale(42.75f)),
            markupArtifact = File(root, "legacy-stale.bin"),
            scalePreferenceKeys = emptySet()
        )
        val result = repository.migrateLegacy(
            association,
            FakeLegacySource(mapOf(source.sourceUri to LegacyReadResult.Found(legacyState)))
        )
        assertTrue(result is LegacyMigrationResult.SkippedCurrentSnapshot)
        assertEquals(newer, (repository.load(association) as DocumentLoadResult.Loaded).snapshot)
        assertFalse((repository.readManifest() as ManifestReadResult.Loaded).entries.single().migrationVerified)
    }

    @Test
    fun migrationFailureBeforeReplacement_doesNotMarkCompleteOrDeleteLegacy() = runBlocking {
        val root = tempRoot()
        val injector = object : RepositoryFailureInjector {
            override fun onPhase(phase: RepositoryWritePhase, documentId: DocumentId?, stagedFile: File?) {
                if (phase == RepositoryWritePhase.SNAPSHOT_BEFORE_REPLACE) error("injected migration failure")
            }
        }
        val repository = LocalDocumentRepository(root, failureInjector = injector)
        val source = source("content://legacy/failure")
        val artifact = File(root, "legacy-failure.bin").apply { writeText("preserve") }
        val state = LegacyDocumentState(
            markups = LegacyStateFixture.fullyPopulatedLegacyMarkups(),
            scales = emptyMap(),
            markupArtifact = artifact,
            scalePreferenceKeys = emptySet()
        )
        val association = resolve(repository, source, null)
        val result = repository.migrateLegacy(
            association,
            FakeLegacySource(mapOf(source.sourceUri to LegacyReadResult.Found(state)))
        )
        assertTrue(result is LegacyMigrationResult.Failed)
        assertTrue(artifact.exists())
        assertFalse((repository.readManifest() as ManifestReadResult.Loaded).entries.single().migrationVerified)
    }

    @Test
    fun migrationReadBackCorruption_doesNotMarkManifestComplete() = runBlocking {
        val root = tempRoot()
        val injector = object : RepositoryFailureInjector {
            override fun onPhase(phase: RepositoryWritePhase, documentId: DocumentId?, stagedFile: File?) {
                if (phase == RepositoryWritePhase.SNAPSHOT_AFTER_REPLACE) {
                    File(stagedFile!!.parentFile, "snapshot.json").writeText("corrupt after replace")
                }
            }
        }
        val repository = LocalDocumentRepository(root, failureInjector = injector)
        val source = source("content://legacy/readback")
        val artifact = File(root, "legacy-readback.bin").apply { writeText("preserve") }
        val state = LegacyDocumentState(
            markups = LegacyStateFixture.fullyPopulatedLegacyMarkups(),
            scales = mapOf(0 to PageScale(42.75f)),
            markupArtifact = artifact,
            scalePreferenceKeys = emptySet()
        )
        val association = resolve(repository, source, null)
        val result = repository.migrateLegacy(
            association,
            FakeLegacySource(mapOf(source.sourceUri to LegacyReadResult.Found(state)))
        )
        assertTrue(result is LegacyMigrationResult.Failed)
        assertTrue(artifact.exists())
        assertFalse((repository.readManifest() as ManifestReadResult.Loaded).entries.single().migrationVerified)
    }

    @Test
    fun legacyHashCollision_isReportedInsteadOfAttachingOneBinaryToBothDocuments() = runBlocking {
        assertEquals(legacyMarkupFileName("Aa"), legacyMarkupFileName("BB"))
        val root = tempRoot()
        val repository = LocalDocumentRepository(root)
        val firstSource = source("Aa")
        val secondSource = source("BB")
        val sharedArtifact = File(root, legacyMarkupFileName(firstSource.sourceUri)).apply { writeText("ambiguous") }
        val state = LegacyDocumentState(
            markups = LegacyStateFixture.fullyPopulatedLegacyMarkups(),
            scales = emptyMap(),
            markupArtifact = sharedArtifact,
            scalePreferenceKeys = emptySet()
        )
        val legacy = FakeLegacySource(
            mapOf(
                firstSource.sourceUri to LegacyReadResult.Found(state),
                secondSource.sourceUri to LegacyReadResult.Found(state)
            )
        )
        val first = resolve(repository, firstSource, null)
        val second = resolve(repository, secondSource, null)
        assertTrue(repository.migrateLegacy(first, legacy) is LegacyMigrationResult.Migrated)
        val secondResult = repository.migrateLegacy(second, legacy)
        assertTrue(secondResult is LegacyMigrationResult.AmbiguousLegacyArtifact)
        assertTrue(sharedArtifact.exists())
    }

    @Test
    fun interruptedMigrationClaim_blocksCollisionBeforeFirstOwnerIsVerified() = runBlocking {
        assertEquals(legacyMarkupFileName("Aa"), legacyMarkupFileName("BB"))
        val root = tempRoot()
        val injector = object : RepositoryFailureInjector {
            override fun onPhase(phase: RepositoryWritePhase, documentId: DocumentId?, stagedFile: File?) {
                if (phase == RepositoryWritePhase.SNAPSHOT_BEFORE_REPLACE) {
                    error("injected migration failure")
                }
            }
        }
        val repository = LocalDocumentRepository(root, failureInjector = injector)
        val firstSource = source("Aa")
        val secondSource = source("BB")
        val sharedArtifact = File(root, legacyMarkupFileName(firstSource.sourceUri)).apply { writeText("ambiguous") }
        val state = LegacyDocumentState(
            markups = LegacyStateFixture.fullyPopulatedLegacyMarkups(),
            scales = emptyMap(),
            markupArtifact = sharedArtifact,
            scalePreferenceKeys = emptySet()
        )
        val legacy = FakeLegacySource(
            mapOf(
                firstSource.sourceUri to LegacyReadResult.Found(state),
                secondSource.sourceUri to LegacyReadResult.Found(state)
            )
        )
        val first = resolve(repository, firstSource, null)
        val second = resolve(repository, secondSource, null)
        assertTrue(repository.migrateLegacy(first, legacy) is LegacyMigrationResult.Failed)
        val secondResult = repository.migrateLegacy(second, legacy)
        assertTrue(secondResult is LegacyMigrationResult.AmbiguousLegacyArtifact)
        val entries = (repository.readManifest() as ManifestReadResult.Loaded).entries
        assertTrue(entries.single { it.documentId == first.documentId }.legacyMigrationClaimed)
        assertFalse(entries.single { it.documentId == first.documentId }.migrationVerified)
        assertTrue(sharedArtifact.exists())
    }

    private fun tempRoot(): File = Files.createTempDirectory("stage2-migration").toFile().also { roots += it }

    private fun source(uri: String) = DocumentSourceIdentityV1(
        sourceUri = uri,
        displayName = "plan.pdf",
        providerMetadata = mapOf("authority" to "legacy")
    )

    private suspend fun resolve(
        repository: LocalDocumentRepository,
        source: DocumentSourceIdentityV1,
        fingerprint: SourceFingerprint?
    ): DocumentAssociation = when (val result = repository.resolveOrCreate(source, fingerprint)) {
        is ResolveDocumentResult.Resolved -> result.association
        else -> error("unexpected resolution: $result")
    }

    private class FakeLegacySource(
        private val states: Map<String, LegacyReadResult>
    ) : LegacyPersistenceSource {
        override fun read(sourceUri: String): LegacyReadResult = states[sourceUri] ?: LegacyReadResult.Absent
    }
}
