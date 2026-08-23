package com.example.myapplication.stage2

import com.example.myapplication.PageData
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.snapshotFromLegacyPageData
import kotlinx.coroutines.CancellationException
import java.util.LinkedHashMap

sealed class LegacyMigrationResult {
    data class Migrated(
        val documentId: DocumentId,
        val snapshot: DocumentSnapshotV1,
        val readBackVerified: Boolean
    ) : LegacyMigrationResult()

    data class AlreadyVerified(val documentId: DocumentId) : LegacyMigrationResult()

    object NoLegacyState : LegacyMigrationResult()

    /** A newer/current canonical snapshot was retained; stale legacy data was not applied. */
    data class SkippedCurrentSnapshot(val documentId: DocumentId) : LegacyMigrationResult()

    data class AmbiguousLegacyArtifact(
        val artifactName: String,
        val existingDocumentId: DocumentId
    ) : LegacyMigrationResult()

    data class Failed(val error: LocalRepositoryError) : LegacyMigrationResult()
}

/**
 * Imports the old Java-serialized markup file and separate scale preferences
 * through the canonical Stage 1 representation.  The legacy source is kept
 * deliberately injectable so migration tests can use deterministic fixtures.
 */
suspend fun LocalDocumentRepository.migrateLegacy(
    association: DocumentAssociation,
    legacySource: LegacyPersistenceSource,
    snapshotRevision: Long = 0L
): LegacyMigrationResult = runOnIo {
    val manifestEntry = manifestEntry(association.documentId)
        ?: return@runOnIo LegacyMigrationResult.Failed(
            LocalRepositoryError.LegacyMigrationFailure("manifest association missing")
        )
    if (manifestEntry.migrationVerified) {
        return@runOnIo LegacyMigrationResult.AlreadyVerified(association.documentId)
    }

    // The legacy reader is synchronous by design, so this call is deliberately
    // inside runOnIo rather than on the caller's coroutine context.
    val legacyResult = try {
        legacySource.read(association.source.sourceUri)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        return@runOnIo LegacyMigrationResult.Failed(
            LocalRepositoryError.LegacyMigrationFailure("legacy discovery failed: ${error.message}")
        )
    }
    val state = when (legacyResult) {
        is LegacyReadResult.Absent -> return@runOnIo LegacyMigrationResult.NoLegacyState
        is LegacyReadResult.Failed -> return@runOnIo LegacyMigrationResult.Failed(
            LocalRepositoryError.LegacyMigrationFailure(legacyResult.detail)
        )
        is LegacyReadResult.Found -> legacyResult.state
    }
    val expected = try {
        legacySnapshot(association, state, snapshotRevision)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        return@runOnIo LegacyMigrationResult.Failed(
            LocalRepositoryError.LegacyMigrationFailure("legacy snapshot conversion failed: ${error.message}")
        )
    }
    val artifactName = manifestEntry.legacyArtifactName

    // Claim, read, write, read back, and mark complete under one consistent
    // manifest->document lock order.  A second repository instance cannot
    // interleave a normal save or claim the same hash-collision artifact.
    withManifestAndDocumentLock(association.documentId) {
        val currentManifest = when (val read = readManifestLockedForMigration()) {
            is ManifestReadResult.Loaded -> read.entries
            is ManifestReadResult.Failed -> {
                return@withManifestAndDocumentLock LegacyMigrationResult.Failed(read.error)
            }
        }
        val currentEntry = currentManifest.firstOrNull { it.documentId == association.documentId }
            ?: return@withManifestAndDocumentLock LegacyMigrationResult.Failed(
                LocalRepositoryError.LegacyMigrationFailure("manifest association missing")
            )
        if (currentEntry.migrationVerified) {
            return@withManifestAndDocumentLock LegacyMigrationResult.AlreadyVerified(association.documentId)
        }

        if (state.markupArtifact != null) {
            when (val claim = claimLegacyArtifactLocked(association.documentId, artifactName)) {
                LegacyArtifactClaim.Claimed -> Unit
                is LegacyArtifactClaim.Ambiguous -> {
                    // A legacy hash collision has no embedded source identity.
                    // Do not attach the same binary to two canonical ids.
                    return@withManifestAndDocumentLock LegacyMigrationResult.AmbiguousLegacyArtifact(
                        artifactName,
                        claim.existingDocumentId
                    )
                }
                is LegacyArtifactClaim.Failed -> {
                    return@withManifestAndDocumentLock LegacyMigrationResult.Failed(claim.error)
                }
            }
        }

        when (val existing = loadLockedForMigration(
            documentId = association.documentId,
            expectedSourceUri = association.source.sourceUri,
            expectedFingerprint = association.sourceFingerprint
        )) {
            is DocumentLoadResult.Loaded -> {
                if (existing.snapshot != expected || existing.sourceFingerprint != association.sourceFingerprint) {
                    // A current repository snapshot exists but does not equal
                    // the stale legacy input.  Never overwrite it on retry.
                    return@withManifestAndDocumentLock LegacyMigrationResult.SkippedCurrentSnapshot(
                        association.documentId
                    )
                }
                val markError = markMigrationVerifiedLocked(
                    association.documentId,
                    association.sourceFingerprint
                )
                return@withManifestAndDocumentLock if (markError == null) {
                    LegacyMigrationResult.Migrated(
                        documentId = association.documentId,
                        snapshot = existing.snapshot,
                        readBackVerified = true
                    )
                } else {
                    LegacyMigrationResult.Failed(markError)
                }
            }
            is DocumentLoadResult.Failed -> {
                // Corruption is explicit and is never replaced by legacy data.
                return@withManifestAndDocumentLock LegacyMigrationResult.Failed(existing.error)
            }
            DocumentLoadResult.NotFound -> Unit
        }

        val saveResult = saveLockedForMigration(
            documentId = association.documentId,
            snapshot = expected,
            sourceFingerprint = association.sourceFingerprint
        )
        if (saveResult is DocumentSaveResult.Failed) {
            return@withManifestAndDocumentLock LegacyMigrationResult.Failed(saveResult.error)
        }

        val readBack = loadLockedForMigration(
            documentId = association.documentId,
            expectedSourceUri = association.source.sourceUri,
            expectedFingerprint = association.sourceFingerprint
        )
        val loaded = (readBack as? DocumentLoadResult.Loaded)
            ?: return@withManifestAndDocumentLock LegacyMigrationResult.Failed(
                when (readBack) {
                    is DocumentLoadResult.Failed -> readBack.error
                    DocumentLoadResult.NotFound -> LocalRepositoryError.LegacyMigrationFailure(
                        "migration read-back did not return a snapshot"
                    )
                    is DocumentLoadResult.Loaded -> error("unreachable")
                }
            )
        if (loaded.snapshot != expected || loaded.sourceFingerprint != association.sourceFingerprint) {
            return@withManifestAndDocumentLock LegacyMigrationResult.Failed(
                LocalRepositoryError.LegacyMigrationFailure("migration read-back field verification failed")
            )
        }

        // Mark completion only after field-by-field readback, while the same
        // document lock still excludes a concurrent normal save.
        val markError = markMigrationVerifiedLocked(
            association.documentId,
            association.sourceFingerprint
        )
        if (markError != null) return@withManifestAndDocumentLock LegacyMigrationResult.Failed(markError)
        LegacyMigrationResult.Migrated(
            documentId = association.documentId,
            snapshot = loaded.snapshot,
            readBackVerified = true
        )
    }
}

private fun legacySnapshot(
    association: DocumentAssociation,
    state: LegacyDocumentState,
    snapshotRevision: Long
): DocumentSnapshotV1 {
    require(snapshotRevision >= 0L) { "snapshot revision must be non-negative" }
    val pageIndices = (state.markups.keys + state.scales.keys).toSortedSet()
    val pageData = LinkedHashMap<Int, PageData>(pageIndices.size)
    pageIndices.forEach { pageIndex ->
        val markups = state.markups[pageIndex]
        pageData[pageIndex] = PageData(
            paths = markups?.paths.orEmpty(),
            measurements = markups?.measurements.orEmpty(),
            notes = markups?.notes.orEmpty(),
            photoPins = markups?.photoPins.orEmpty(),
            scale = state.scales[pageIndex],
            shapes = markups?.shapes.orEmpty()
        )
    }
    return snapshotFromLegacyPageData(
        pageData = pageData,
        source = association.source,
        snapshotRevision = snapshotRevision
    )
}
