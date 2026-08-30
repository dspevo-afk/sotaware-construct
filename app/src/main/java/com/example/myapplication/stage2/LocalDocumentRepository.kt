package com.example.myapplication.stage2

import android.content.Context
import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage1.DocumentSnapshotV1
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

const val LOCAL_DOCUMENT_STORAGE_SCHEMA_VERSION: Int = 1
const val DOCUMENT_MANIFEST_SCHEMA_VERSION: Int = 1

private const val SNAPSHOT_RESTORE_INTENT_SCHEMA_VERSION: Int = 1
private const val SNAPSHOT_RESTORE_INTENT_FILE_NAME: String = "snapshot.restore.pending.json"
private const val SNAPSHOT_RESTORE_PAYLOAD_PREFIX: String = "snapshot.restore.payload."

/** A resolved source-to-document association. */
data class DocumentAssociation(
    val documentId: DocumentId,
    val source: com.example.myapplication.stage1.DocumentSourceIdentityV1,
    val sourceFingerprint: SourceFingerprint?,
    val legacyArtifactName: String
)

sealed class ResolveDocumentResult {
    data class Resolved(val association: DocumentAssociation) : ResolveDocumentResult()

    /**
     * The URI is still associated with the same DocumentId, but its content
     * fingerprint changed.  Callers must not load the old annotations as if
     * they belonged to the new source without an explicit later-stage policy.
     */
    data class SourceChanged(
        val documentId: DocumentId,
        val sourceUri: String,
        val previousFingerprint: SourceFingerprint,
        val currentFingerprint: SourceFingerprint
    ) : ResolveDocumentResult()

    /** An existing association cannot be safely reopened without a revision signal. */
    data class FingerprintUnavailable(
        val documentId: DocumentId,
        val sourceUri: String,
        val storedFingerprint: SourceFingerprint?
    ) : ResolveDocumentResult()

    /** A legacy/unfingerprinted snapshot exists and needs an explicit bind. */
    data class FingerprintNotBound(
        val documentId: DocumentId,
        val sourceUri: String,
        val currentFingerprint: SourceFingerprint
    ) : ResolveDocumentResult()

    data class Failed(val error: LocalRepositoryError) : ResolveDocumentResult()
}

sealed class ManifestReadResult {
    data class Loaded(
        val entries: List<DocumentManifestEntryV1>,
        val recoveredFromPrevious: Boolean
    ) : ManifestReadResult()

    data class Failed(val error: LocalRepositoryError) : ManifestReadResult()
}

data class DocumentManifestEntryV1(
    val documentId: DocumentId,
    val sourceUri: String,
    val displayName: String?,
    val providerMetadata: Map<String, String>,
    val sourceFingerprint: SourceFingerprint?,
    val migrationVerified: Boolean,
    val legacyMigrationClaimed: Boolean,
    val legacyArtifactName: String
)

sealed class DocumentLoadResult {
    data class Loaded(
        val documentId: DocumentId,
        val snapshot: DocumentSnapshotV1,
        val sourceFingerprint: SourceFingerprint?,
        val recoveredFromPrevious: Boolean
    ) : DocumentLoadResult()

    /** No accepted current or previous snapshot exists for this id. */
    object NotFound : DocumentLoadResult()

    data class Failed(val error: LocalRepositoryError) : DocumentLoadResult()
}

/**
 * One exact accepted snapshot slot, including the serialized envelope that
 * was on disk when it was captured.  The raw bytes let a compensating
 * transaction restore an originally absent/current/previous pair without
 * turning the incoming snapshot into a new previous-good record.
 */
data class DurableSnapshotSlot(
    val snapshot: DocumentSnapshotV1,
    val sourceFingerprint: SourceFingerprint?,
    internal val serializedBytes: ByteArray? = null
)

/** The exact current/previous durable slot pair for one document. */
data class DocumentDurableSnapshotState(
    val current: DurableSnapshotSlot?,
    val previous: DurableSnapshotSlot?
)

sealed class DocumentSaveResult {
    data class Saved(val documentId: DocumentId) : DocumentSaveResult()
    data class Failed(val error: LocalRepositoryError) : DocumentSaveResult()
}

sealed class LocalRepositoryError {
    data class IoFailure(
        val operation: String,
        val path: String?,
        val detail: String?
    ) : LocalRepositoryError()

    data class CorruptManifest(
        val path: String,
        val recoveryAttempted: Boolean,
        val detail: String?
    ) : LocalRepositoryError()

    data class CorruptSnapshot(
        val path: String,
        val recoveryAttempted: Boolean,
        val detail: String?
    ) : LocalRepositoryError()

    data class AssociationMismatch(
        val path: String,
        val expectedDocumentId: DocumentId,
        val actualDocumentId: DocumentId?
    ) : LocalRepositoryError()

    data class SourceChanged(
        val documentId: DocumentId,
        val sourceUri: String,
        val storedFingerprint: SourceFingerprint?,
        val currentFingerprint: SourceFingerprint
    ) : LocalRepositoryError()

    data class SourceAssociationMismatch(
        val documentId: DocumentId,
        val expectedSourceUri: String,
        val actualSourceUri: String
    ) : LocalRepositoryError()

    data class CommitUncertain(
        val operation: String,
        val path: String?,
        val detail: String?
    ) : LocalRepositoryError()

    data class InvalidSnapshot(val detail: String) : LocalRepositoryError()

    data class LegacyMigrationFailure(val detail: String) : LocalRepositoryError()
}

private class RepositorySourceChangedSignal(val error: LocalRepositoryError.SourceChanged) : Exception()
private class RepositoryAssociationMismatchSignal(val error: LocalRepositoryError.AssociationMismatch) : Exception()
private class RepositoryCommitUncertainSignal(val original: Exception) : Exception(original)

internal sealed class LegacyArtifactClaim {
    object Claimed : LegacyArtifactClaim()
    data class Ambiguous(val existingDocumentId: DocumentId) : LegacyArtifactClaim()
    data class Failed(val error: LocalRepositoryError) : LegacyArtifactClaim()
}

enum class RepositoryWritePhase {
    MANIFEST_STAGE_WRITTEN,
    MANIFEST_BEFORE_REPLACE,
    SNAPSHOT_STAGE_WRITTEN,
    SNAPSHOT_BEFORE_REPLACE,
    SNAPSHOT_AFTER_REPLACE,
    SNAPSHOT_RESTORE_BEFORE_SLOT_REPLACE,
    SNAPSHOT_RESTORE_AFTER_SLOT_REPLACE
}

fun interface RepositoryFailureInjector {
    fun onPhase(phase: RepositoryWritePhase, documentId: DocumentId?, stagedFile: File?)
}

object NoRepositoryFailureInjector : RepositoryFailureInjector {
    override fun onPhase(phase: RepositoryWritePhase, documentId: DocumentId?, stagedFile: File?) = Unit
}

/**
 * The sole local persistence authority for canonical document snapshots.
 *
 * The repository deliberately knows nothing about Drive, UI switching, sync
 * generations, or autosave policy.  It owns only local identity association,
 * durable snapshot IO, migration, corruption recovery, and per-document
 * serialization.
 */
class LocalDocumentRepository(
    private val rootDirectory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val failureInjector: RepositoryFailureInjector = NoRepositoryFailureInjector,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        failureInjector: RepositoryFailureInjector = NoRepositoryFailureInjector,
        clockMillis: () -> Long = { System.currentTimeMillis() }
    ) : this(
        rootDirectory = File(context.filesDir, "local_documents"),
        ioDispatcher = ioDispatcher,
        failureInjector = failureInjector,
        clockMillis = clockMillis
    )

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val processRootKey: String = try {
        rootDirectory.canonicalPath
    } catch (_: IOException) {
        rootDirectory.absolutePath
    }
    private val manifestMutex = PROCESS_MANIFEST_MUTEXES.computeIfAbsent(processRootKey) { Mutex() }

    private val manifestFile: File get() = File(rootDirectory, "document-manifest.json")
    private val previousManifestFile: File get() = File(rootDirectory, "document-manifest.previous.json")
    private val manifestStagingFile: File get() = File(rootDirectory, "document-manifest.staging.tmp")
    private val documentsDirectory: File get() = File(rootDirectory, "documents")
    private val quarantineDirectory: File get() = File(rootDirectory, "quarantine")

    private companion object {
        val PROCESS_MANIFEST_MUTEXES = ConcurrentHashMap<String, Mutex>()
        val PROCESS_DOCUMENT_MUTEXES = ConcurrentHashMap<String, Mutex>()
    }

    fun currentSnapshotFile(documentId: DocumentId): File =
        File(documentDirectory(documentId), "snapshot.json")

    fun previousSnapshotFile(documentId: DocumentId): File =
        File(documentDirectory(documentId), "snapshot.previous.json")

    fun snapshotQuarantineDirectory(documentId: DocumentId): File =
        File(documentDirectory(documentId), "quarantine")

    /** Resolve an exact source URI, allocating an id only when no mapping exists. */
    suspend fun resolveOrCreate(
        source: com.example.myapplication.stage1.DocumentSourceIdentityV1,
        currentFingerprint: SourceFingerprint?
    ): ResolveDocumentResult = withContext(ioDispatcher) {
        manifestMutex.withLock {
            try {
                ensureDirectories()
                val manifestRead = readManifestLocked()
                val manifest = when (manifestRead) {
                    is ManifestReadResult.Loaded -> manifestRead.entries.toMutableList()
                    is ManifestReadResult.Failed -> return@withLock ResolveDocumentResult.Failed(manifestRead.error)
                }
                val manifestRecovered = (manifestRead as ManifestReadResult.Loaded).recoveredFromPrevious
                val existingIndex = manifest.indexOfFirst { it.sourceUri == source.sourceUri }
                if (existingIndex >= 0) {
                    val existing = manifest[existingIndex]
                    val previousFingerprint = existing.sourceFingerprint
                    if (currentFingerprint == null) {
                        return@withLock ResolveDocumentResult.FingerprintUnavailable(
                            documentId = existing.documentId,
                            sourceUri = source.sourceUri,
                            storedFingerprint = previousFingerprint
                        )
                    }
                    if (previousFingerprint == null) {
                        val existingSnapshot = documentMutex(existing.documentId).withLock {
                            loadLocked(existing.documentId, source.sourceUri, expectedFingerprint = null)
                        }
                        when (existingSnapshot) {
                            is DocumentLoadResult.Loaded -> {
                                val storedSnapshotFingerprint = existingSnapshot.sourceFingerprint
                                if (storedSnapshotFingerprint == null) {
                                    return@withLock ResolveDocumentResult.FingerprintNotBound(
                                        documentId = existing.documentId,
                                        sourceUri = source.sourceUri,
                                        currentFingerprint = currentFingerprint
                                    )
                                }
                                if (storedSnapshotFingerprint != currentFingerprint) {
                                    return@withLock ResolveDocumentResult.SourceChanged(
                                        documentId = existing.documentId,
                                        sourceUri = source.sourceUri,
                                        previousFingerprint = storedSnapshotFingerprint,
                                        currentFingerprint = currentFingerprint
                                    )
                                }
                            }
                            is DocumentLoadResult.Failed -> return@withLock ResolveDocumentResult.Failed(existingSnapshot.error)
                            DocumentLoadResult.NotFound -> Unit
                        }
                    }
                    if (previousFingerprint != null && previousFingerprint != currentFingerprint) {
                        return@withLock ResolveDocumentResult.SourceChanged(
                            documentId = existing.documentId,
                            sourceUri = source.sourceUri,
                            previousFingerprint = previousFingerprint,
                            currentFingerprint = currentFingerprint
                        )
                    }

                    val updated = existing.copy(
                        displayName = source.displayName ?: existing.displayName,
                        providerMetadata = source.providerMetadata,
                        sourceFingerprint = currentFingerprint ?: previousFingerprint
                    )
                    if (updated != existing && !manifestRecovered) {
                        manifest[existingIndex] = updated
                        writeManifestLocked(manifest)
                    }
                    return@withLock ResolveDocumentResult.Resolved(
                        DocumentAssociation(
                            documentId = existing.documentId,
                            source = source.copy(
                                displayName = source.displayName ?: existing.displayName,
                                providerMetadata = source.providerMetadata
                            ),
                            sourceFingerprint = currentFingerprint ?: previousFingerprint,
                            legacyArtifactName = existing.legacyArtifactName
                        )
                    )
                }

                if (manifestRecovered) {
                    return@withLock ResolveDocumentResult.Failed(
                        LocalRepositoryError.CorruptManifest(
                            path = manifestFile.path,
                            recoveryAttempted = true,
                            detail = "Manifest recovered from previous state; refusing to allocate an unverified new association"
                        )
                    )
                }

                val documentId = DocumentId.new()
                val entry = DocumentManifestEntryV1(
                    documentId = documentId,
                    sourceUri = source.sourceUri,
                    displayName = source.displayName,
                    providerMetadata = source.providerMetadata,
                    sourceFingerprint = currentFingerprint,
                    migrationVerified = false,
                    legacyMigrationClaimed = false,
                    legacyArtifactName = legacyArtifactNameFor(source.sourceUri)
                )
                manifest += entry
                writeManifestLocked(manifest)
                ResolveDocumentResult.Resolved(
                    DocumentAssociation(
                        documentId = documentId,
                        source = source,
                        sourceFingerprint = currentFingerprint,
                        legacyArtifactName = entry.legacyArtifactName
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ResolveDocumentResult.Failed(
                    LocalRepositoryError.IoFailure("resolve document", manifestFile.path, error.message)
                )
            }
        }
    }

    suspend fun readManifest(): ManifestReadResult = withContext(ioDispatcher) {
        manifestMutex.withLock {
            try {
                ensureDirectories()
                readManifestLocked()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ManifestReadResult.Failed(
                    LocalRepositoryError.IoFailure("read manifest", manifestFile.path, error.message)
                )
            }
        }
    }

    suspend fun save(
        association: DocumentAssociation,
        snapshot: DocumentSnapshotV1
    ): DocumentSaveResult {
        if (snapshot.source.sourceUri != association.source.sourceUri) {
            return DocumentSaveResult.Failed(
                LocalRepositoryError.SourceAssociationMismatch(
                    documentId = association.documentId,
                    expectedSourceUri = association.source.sourceUri,
                    actualSourceUri = snapshot.source.sourceUri
                )
            )
        }
        return save(
            documentId = association.documentId,
            snapshot = snapshot,
            sourceFingerprint = association.sourceFingerprint
        )
    }

    suspend fun save(
        documentId: DocumentId,
        snapshot: DocumentSnapshotV1,
        sourceFingerprint: SourceFingerprint? = null
    ): DocumentSaveResult = withContext(ioDispatcher) {
        documentMutex(documentId).withLock {
            try {
                ensureDirectories()
                val failure = writeSnapshotLocked(documentId, snapshot, sourceFingerprint)
                if (failure == null) {
                    DocumentSaveResult.Saved(documentId)
                } else {
                    DocumentSaveResult.Failed(failure)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DocumentSaveResult.Failed(
                    LocalRepositoryError.IoFailure(
                        "save snapshot",
                        currentSnapshotFile(documentId).path,
                        error.message
                    )
                )
            }
        }
    }

    suspend fun load(
        association: DocumentAssociation
    ): DocumentLoadResult = load(
        documentId = association.documentId,
        expectedSourceUri = association.source.sourceUri,
        expectedFingerprint = association.sourceFingerprint
    )

    suspend fun load(
        documentId: DocumentId,
        expectedSourceUri: String? = null,
        expectedFingerprint: SourceFingerprint? = null
    ): DocumentLoadResult = withContext(ioDispatcher) {
        documentMutex(documentId).withLock {
            try {
                ensureDirectories()
                loadLocked(documentId, expectedSourceUri, expectedFingerprint)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DocumentLoadResult.Failed(
                    LocalRepositoryError.IoFailure(
                        "load snapshot",
                        currentSnapshotFile(documentId).path,
                        error.message
                    )
                )
            }
        }
    }

    /**
     * Captures both accepted durable slots without promoting or otherwise
     * changing them.  This is intentionally separate from [load], whose
     * recovery behavior may promote a previous-good snapshot to current.
     */
    suspend fun captureDurableSnapshotState(
        association: DocumentAssociation
    ): DocumentDurableSnapshotState = withContext(ioDispatcher) {
        documentMutex(association.documentId).withLock {
            ensureDirectories()
            recoverPendingSnapshotRestoreLocked(association.documentId)?.let { failure ->
                throw IOException("pending durable snapshot restore could not be recovered: $failure")
            }
            DocumentDurableSnapshotState(
                current = readDurableSnapshotSlotLocked(
                    currentSnapshotFile(association.documentId),
                    association
                ),
                previous = readDurableSnapshotSlotLocked(
                    previousSnapshotFile(association.documentId),
                    association
                )
            )
        }
    }

    /**
     * Restores the exact captured current/previous pair.  Unlike [save], this
     * method does not preserve the incoming current as previous-good and can
     * restore a pair where either or both slots were absent.
     */
    suspend fun restoreDurableSnapshotState(
        association: DocumentAssociation,
        state: DocumentDurableSnapshotState
    ): DocumentSaveResult = withContext(ioDispatcher) {
        documentMutex(association.documentId).withLock {
            val directory = documentDirectory(association.documentId)
            val intentFile = snapshotRestoreIntentFile(association.documentId)
            val stagedPayloads = mutableListOf<File>()
            try {
                ensureDirectories()
                require(directory.exists() || directory.mkdirs()) {
                    "Unable to create ${directory.path}"
                }
                recoverPendingSnapshotRestoreLocked(association.documentId)?.let { failure ->
                    return@withLock DocumentSaveResult.Failed(failure)
                }
                val transactionId = UUID.randomUUID().toString()
                val stagedCurrent = stageDurableSnapshotSlotLocked(
                    directory,
                    "current",
                    state.current,
                    association,
                    transactionId
                )
                stagedCurrent?.let(stagedPayloads::add)
                val stagedPrevious = stageDurableSnapshotSlotLocked(
                    directory,
                    "previous",
                    state.previous,
                    association,
                    transactionId
                )
                stagedPrevious?.let(stagedPayloads::add)
                val intent = SnapshotRestoreIntentJson(
                    schemaVersion = SNAPSHOT_RESTORE_INTENT_SCHEMA_VERSION,
                    documentId = association.documentId.value,
                    currentPayload = stagedCurrent?.name,
                    previousPayload = stagedPrevious?.name
                )
                writeSnapshotRestoreIntentLocked(directory, intent)
                applySnapshotRestoreIntentLocked(association.documentId, intent)
                clearSnapshotRestoreIntentLocked(intent)
                DocumentSaveResult.Saved(association.documentId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                if (intentFile.exists()) {
                    DocumentSaveResult.Failed(
                        LocalRepositoryError.CommitUncertain(
                            "restore durable snapshot state",
                            intentFile.path,
                            error.message
                        )
                    )
                } else {
                    DocumentSaveResult.Failed(
                        LocalRepositoryError.InvalidSnapshot(error.message ?: "invalid durable snapshot state")
                    )
                }
            } catch (error: Exception) {
                if (intentFile.exists()) {
                    DocumentSaveResult.Failed(
                        LocalRepositoryError.CommitUncertain(
                            "restore durable snapshot state",
                            intentFile.path,
                            error.message
                        )
                    )
                } else {
                    DocumentSaveResult.Failed(
                        LocalRepositoryError.IoFailure(
                            "restore durable snapshot state",
                            currentSnapshotFile(association.documentId).path,
                            error.message
                        )
                    )
                }
            } finally {
                // Once the durable intent is present it is the recovery
                // authority; never remove its payloads on a failed apply.
                if (!intentFile.exists()) {
                    stagedPayloads.forEach { staged ->
                        try {
                            Files.deleteIfExists(staged.toPath())
                        } catch (_: Exception) {
                            // The repository's existing orphan-staging
                            // quarantine will retain an unremoved artifact.
                        }
                    }
                }
            }
        }
    }

    private fun documentMutex(documentId: DocumentId): Mutex =
        PROCESS_DOCUMENT_MUTEXES.computeIfAbsent(
            "$processRootKey|${documentId.value}"
        ) { Mutex() }

    private fun documentDirectory(documentId: DocumentId): File =
        File(documentsDirectory, documentId.value)

    private fun ensureDirectories() {
        require(rootDirectory.exists() || rootDirectory.mkdirs()) { "Unable to create ${rootDirectory.path}" }
        require(documentsDirectory.exists() || documentsDirectory.mkdirs()) {
            "Unable to create ${documentsDirectory.path}"
        }
        require(quarantineDirectory.exists() || quarantineDirectory.mkdirs()) {
            "Unable to create ${quarantineDirectory.path}"
        }
    }

    private fun readManifestLocked(): ManifestReadResult {
        val currentWasPresent = manifestFile.exists()
        val previousWasPresent = previousManifestFile.exists()
        val current = if (currentWasPresent) readManifestFile(manifestFile) else null
        val previous = if (previousWasPresent) readManifestFile(previousManifestFile) else null
        if (current != null) {
            if (previous != null && !isManifestExtension(previous, current)) {
                quarantineFile(manifestFile, "manifest-regressed-current")
                return ManifestReadResult.Loaded(previous, recoveredFromPrevious = true)
            }
            if (previousWasPresent && previous == null && current.isEmpty()) {
                quarantineFile(manifestFile, "manifest-empty-current")
                quarantineFile(previousManifestFile, "manifest-invalid-previous")
                return ManifestReadResult.Failed(
                    LocalRepositoryError.CorruptManifest(
                        path = manifestFile.path,
                        recoveryAttempted = true,
                        detail = "Empty manifest could not be compared with an invalid previous manifest"
                    )
                )
            }
            if (previousWasPresent && previous == null) quarantineFile(previousManifestFile, "manifest-invalid-previous")
            return ManifestReadResult.Loaded(current, recoveredFromPrevious = false)
        }

        if (currentWasPresent) quarantineFile(manifestFile, "manifest-current")

        if (previous != null) return ManifestReadResult.Loaded(previous, recoveredFromPrevious = true)

        if (previousWasPresent) quarantineFile(previousManifestFile, "manifest-previous")
        if (manifestStagingFile.exists()) {
            quarantineFile(manifestStagingFile, "manifest-staging")
            return ManifestReadResult.Failed(
                LocalRepositoryError.CorruptManifest(
                    path = manifestFile.path,
                    recoveryAttempted = true,
                    detail = "No valid manifest remained after an incomplete write"
                )
            )
        }

        if (currentWasPresent || previousWasPresent) {
            return ManifestReadResult.Failed(
                LocalRepositoryError.CorruptManifest(
                    path = manifestFile.path,
                    recoveryAttempted = true,
                    detail = "Manifest and previous manifest were invalid"
                )
            )
        }

        // No manifest has ever been accepted.  This is the only path that
        // permits resolveOrCreate() to allocate a new mapping.
        return ManifestReadResult.Loaded(emptyList(), recoveredFromPrevious = false)
    }

    private fun isManifestExtension(
        previous: List<DocumentManifestEntryV1>,
        current: List<DocumentManifestEntryV1>
    ): Boolean {
        val currentByUri = current.associateBy { it.sourceUri }
        return previous.all { old ->
            val next = currentByUri[old.sourceUri]
            next != null &&
                next.documentId == old.documentId &&
                next.legacyArtifactName == old.legacyArtifactName
        }
    }

    private fun readManifestFile(file: File): List<DocumentManifestEntryV1>? {
        return try {
            val json = file.readText(Charsets.UTF_8)
            val dto = gson.fromJson(json, ManifestJson::class.java)
            val schemaVersion = requireNotNull(dto.schemaVersion) { "manifest schemaVersion missing" }
            require(schemaVersion == DOCUMENT_MANIFEST_SCHEMA_VERSION) { "unsupported manifest schema" }
            val entries = requireNotNull(dto.entries) { "manifest entries missing" }
            val result = entries.map { entry ->
                val documentId = DocumentId.parse(requireNotNull(entry.documentId))
                val sourceUri = requireNotNull(entry.sourceUri)
                require(sourceUri.isNotBlank()) { "manifest sourceUri blank" }
                val metadata = requireNotNull(entry.providerMetadata) { "manifest providerMetadata missing" }
                val fingerprint = entry.sourceFingerprint?.let {
                    SourceFingerprint(it.algorithm, it.digestHex, it.byteCount)
                }
                val migrationVerified = requireNotNull(entry.migrationVerified) {
                    "manifest migrationVerified missing"
                }
                val legacyMigrationClaimed = requireNotNull(entry.legacyMigrationClaimed) {
                    "manifest legacyMigrationClaimed missing"
                }
                val legacyArtifactName = requireNotNull(entry.legacyArtifactName) {
                    "manifest legacyArtifactName missing"
                }
                require(legacyArtifactName.isNotBlank()) { "manifest legacyArtifactName blank" }
                DocumentManifestEntryV1(
                    documentId = documentId,
                    sourceUri = sourceUri,
                    displayName = entry.displayName,
                    providerMetadata = metadata,
                    sourceFingerprint = fingerprint,
                    migrationVerified = migrationVerified,
                    legacyMigrationClaimed = legacyMigrationClaimed,
                    legacyArtifactName = legacyArtifactName
                )
            }
            require(result.map { it.sourceUri }.toSet().size == result.size) {
                "manifest contains duplicate source URIs"
            }
            require(result.map { it.documentId }.toSet().size == result.size) {
                "manifest contains duplicate document ids"
            }
            require(result.isNotEmpty()) { "manifest cannot be empty once created" }
            result
        } catch (_: Exception) {
            null
        }
    }

    private fun writeManifestLocked(entries: List<DocumentManifestEntryV1>) {
        val dto = ManifestJson(
            schemaVersion = DOCUMENT_MANIFEST_SCHEMA_VERSION,
            entries = entries.map { entry ->
                ManifestEntryJson(
                    documentId = entry.documentId.value,
                    sourceUri = entry.sourceUri,
                    displayName = entry.displayName,
                    providerMetadata = entry.providerMetadata,
                    sourceFingerprint = entry.sourceFingerprint,
                    migrationVerified = entry.migrationVerified,
                    legacyMigrationClaimed = entry.legacyMigrationClaimed,
                    legacyArtifactName = entry.legacyArtifactName
                )
            }
        )
        writeAcceptedFile(
            current = manifestFile,
            previous = previousManifestFile,
            staging = manifestStagingFile,
            contents = gson.toJson(dto).toByteArray(Charsets.UTF_8),
            documentId = null,
            stagePhase = RepositoryWritePhase.MANIFEST_STAGE_WRITTEN,
            beforeReplacePhase = RepositoryWritePhase.MANIFEST_BEFORE_REPLACE,
            validate = { file -> readManifestFile(file) ?: error("manifest read-back validation failed") }
        )
    }

    private fun writeSnapshotLocked(
        documentId: DocumentId,
        snapshot: DocumentSnapshotV1,
        sourceFingerprint: SourceFingerprint?
    ): LocalRepositoryError? {
        recoverPendingSnapshotRestoreLocked(documentId)?.let { return it }
        return try {
            validateSnapshot(snapshot)
            sourceFingerprint?.let { SourceFingerprint(it.algorithm, it.digestHex, it.byteCount) }
            val directory = documentDirectory(documentId)
            require(directory.exists() || directory.mkdirs()) { "Unable to create ${directory.path}" }
            val current = currentSnapshotFile(documentId)
            val previous = previousSnapshotFile(documentId)
            val staging = File(directory, "snapshot.${UUID.randomUUID()}.tmp")
            val envelope = SnapshotEnvelopeJson(
                storageSchemaVersion = LOCAL_DOCUMENT_STORAGE_SCHEMA_VERSION,
                documentId = documentId.value,
                sourceFingerprint = sourceFingerprint,
                snapshot = snapshot
            )
            writeAcceptedFile(
                current = current,
                previous = previous,
                staging = staging,
                contents = gson.toJson(envelope).toByteArray(Charsets.UTF_8),
                documentId = documentId,
                stagePhase = RepositoryWritePhase.SNAPSHOT_STAGE_WRITTEN,
                beforeReplacePhase = RepositoryWritePhase.SNAPSHOT_BEFORE_REPLACE,
                validate = { file ->
                    val record = readSnapshotFile(file)
                    require(record.documentId == documentId) { "snapshot document id mismatch" }
                    validateSnapshot(record.snapshot)
                    record
                }
            )
            null
        } catch (error: RepositoryCommitUncertainSignal) {
            LocalRepositoryError.CommitUncertain(
                operation = "write snapshot",
                path = currentSnapshotFile(documentId).path,
                detail = error.original.message
            )
        } catch (error: IllegalArgumentException) {
            LocalRepositoryError.InvalidSnapshot(error.message ?: "invalid snapshot")
        } catch (error: Exception) {
            LocalRepositoryError.IoFailure(
                operation = "write snapshot",
                path = currentSnapshotFile(documentId).path,
                detail = error.message
            )
        }
    }

    /**
     * Writes bytes to a staging file, validates that staging file, preserves
     * the current accepted file as previous, then replaces current in one
     * filesystem rename.  Current is never truncated before the replacement
     * is complete.
     */
    private fun <T> writeAcceptedFile(
        current: File,
        previous: File,
        staging: File,
        contents: ByteArray,
        documentId: DocumentId?,
        stagePhase: RepositoryWritePhase,
        beforeReplacePhase: RepositoryWritePhase,
        validate: (File) -> T
    ) {
        try {
            writeAndSync(staging, contents)
            failureInjector.onPhase(stagePhase, documentId, staging)
            validate(staging)

            if (current.exists()) {
                // A malformed current file is quarantined rather than copied
                // into the recovery slot.  A valid current file remains in
                // place until the final replacement succeeds.
                val currentIsValid = try {
                    validate(current)
                    true
                } catch (_: Exception) {
                    false
                }
                if (currentIsValid) {
                    preserveAsPrevious(current, previous)
                } else {
                    quarantineFile(current, "invalid-current-before-write")
                }
            }

            failureInjector.onPhase(beforeReplacePhase, documentId, staging)
            try {
                replaceAtomically(staging, current)
                if (documentId != null) {
                    failureInjector.onPhase(RepositoryWritePhase.SNAPSHOT_AFTER_REPLACE, documentId, staging)
                }
                validate(current)
            } catch (error: Exception) {
                quarantineFile(current, "failed-read-back")
                if (previous.exists()) restorePrevious(previous, current)
                throw RepositoryCommitUncertainSignal(error)
            }
        } catch (error: Exception) {
            if (staging.exists()) quarantineFile(staging, "interrupted-write")
            throw error
        }
    }

    private fun loadLocked(
        documentId: DocumentId,
        expectedSourceUri: String?,
        expectedFingerprint: SourceFingerprint?
    ): DocumentLoadResult {
        recoverPendingSnapshotRestoreLocked(documentId)?.let { failure ->
            return DocumentLoadResult.Failed(failure)
        }
        val current = currentSnapshotFile(documentId)
        val previous = previousSnapshotFile(documentId)

        var currentRecord: SnapshotRecord? = null
        var currentWasCorrupt = false
        var currentAssociationError: LocalRepositoryError.AssociationMismatch? = null
        if (current.exists()) {
            currentRecord = try {
                readSnapshotFile(current).also {
                    validateRecord(it, documentId, expectedSourceUri, expectedFingerprint)
                }
            } catch (error: RepositorySourceChangedSignal) {
                return DocumentLoadResult.Failed(error.error)
            } catch (error: RepositoryAssociationMismatchSignal) {
                currentAssociationError = error.error
                currentWasCorrupt = true
                quarantineFile(current, "mismatched-current")
                null
            } catch (error: Exception) {
                currentWasCorrupt = true
                quarantineFile(current, "corrupt-current")
                null
            }
        }
        if (currentRecord != null) {
            return DocumentLoadResult.Loaded(
                documentId = documentId,
                snapshot = currentRecord.snapshot,
                sourceFingerprint = currentRecord.sourceFingerprint,
                recoveredFromPrevious = false
            )
        }

        var previousRecord: SnapshotRecord? = null
        var previousAssociationError: LocalRepositoryError.AssociationMismatch? = null
        if (previous.exists()) {
            previousRecord = try {
                readSnapshotFile(previous).also {
                    validateRecord(it, documentId, expectedSourceUri, expectedFingerprint)
                }
            } catch (error: RepositorySourceChangedSignal) {
                return DocumentLoadResult.Failed(error.error)
            } catch (error: RepositoryAssociationMismatchSignal) {
                previousAssociationError = error.error
                quarantineFile(previous, "mismatched-previous")
                null
            } catch (_: Exception) {
                quarantineFile(previous, "corrupt-previous")
                null
            }
        }
        if (previousRecord != null) {
            // Recovery is explicit in the result, while promotion makes the
            // next load start from the recovered accepted state.
            try {
                restorePrevious(previous, current)
            } catch (_: Exception) {
                // The valid previous copy remains available even if promotion
                // itself fails; do not turn recovery into a blank result.
            }
            return DocumentLoadResult.Loaded(
                documentId = documentId,
                snapshot = previousRecord.snapshot,
                sourceFingerprint = previousRecord.sourceFingerprint,
                recoveredFromPrevious = true
            )
        }

        val unacceptedStagingFiles = documentDirectory(documentId).listFiles()
            ?.filter { it.name.endsWith(".tmp") || it.name.endsWith(".recovery.tmp") }
            .orEmpty()
        if (!current.exists() && !previous.exists() && !currentWasCorrupt && unacceptedStagingFiles.isEmpty()) {
            return DocumentLoadResult.NotFound
        }
        unacceptedStagingFiles.forEach { quarantineFile(it, "unaccepted-staging") }
        currentAssociationError?.let { return DocumentLoadResult.Failed(it) }
        previousAssociationError?.let { return DocumentLoadResult.Failed(it) }
        return DocumentLoadResult.Failed(
            LocalRepositoryError.CorruptSnapshot(
                path = current.path,
                recoveryAttempted = true,
                detail = "Current and previous snapshots were unavailable or invalid"
            )
        )
    }

    private fun validateRecord(
        record: SnapshotRecord,
        expectedDocumentId: DocumentId,
        expectedSourceUri: String?,
        expectedFingerprint: SourceFingerprint?
    ) {
        if (record.documentId != expectedDocumentId) {
            throw RepositoryAssociationMismatchSignal(
                LocalRepositoryError.AssociationMismatch(
                    path = currentSnapshotFile(expectedDocumentId).path,
                    expectedDocumentId = expectedDocumentId,
                    actualDocumentId = record.documentId
                )
            )
        }
        validateSnapshot(record.snapshot)
        if (expectedSourceUri != null && record.snapshot.source.sourceUri != expectedSourceUri) {
            throw RepositoryAssociationMismatchSignal(
                LocalRepositoryError.AssociationMismatch(
                    path = currentSnapshotFile(expectedDocumentId).path,
                    expectedDocumentId = expectedDocumentId,
                    actualDocumentId = record.documentId
                )
            )
        }
        if (expectedFingerprint != null && record.sourceFingerprint != expectedFingerprint) {
            throw RepositorySourceChangedSignal(
                LocalRepositoryError.SourceChanged(
                    documentId = expectedDocumentId,
                    sourceUri = expectedSourceUri ?: record.snapshot.source.sourceUri,
                    storedFingerprint = record.sourceFingerprint,
                    currentFingerprint = expectedFingerprint
                )
            )
        }
    }

    private fun readSnapshotFile(file: File): SnapshotRecord {
        val envelope = gson.fromJson(file.readText(Charsets.UTF_8), SnapshotEnvelopeJson::class.java)
        val storageVersion = requireNotNull(envelope.storageSchemaVersion) { "snapshot storage schema missing" }
        require(storageVersion == LOCAL_DOCUMENT_STORAGE_SCHEMA_VERSION) { "unsupported snapshot storage schema" }
        val documentId = DocumentId.parse(requireNotNull(envelope.documentId))
        val snapshot = requireNotNull(envelope.snapshot) { "snapshot payload missing" }
        val fingerprint = envelope.sourceFingerprint?.let {
            SourceFingerprint(it.algorithm, it.digestHex, it.byteCount)
        }
        validateSnapshot(snapshot)
        return SnapshotRecord(documentId, snapshot, fingerprint)
    }

    private fun readDurableSnapshotSlotLocked(
        file: File,
        association: DocumentAssociation
    ): DurableSnapshotSlot? {
        if (!file.exists()) return null
        val bytes = Files.readAllBytes(file.toPath())
        val record = readSnapshotFile(file)
        validateRecord(
            record,
            association.documentId,
            association.source.sourceUri,
            association.sourceFingerprint
        )
        return DurableSnapshotSlot(
            snapshot = record.snapshot,
            sourceFingerprint = record.sourceFingerprint,
            serializedBytes = bytes.copyOf()
        )
    }

    private fun stageDurableSnapshotSlotLocked(
        directory: File,
        slotName: String,
        slot: DurableSnapshotSlot?,
        association: DocumentAssociation,
        transactionId: String
    ): File? {
        if (slot == null) return null
        validateSnapshot(slot.snapshot)
        require(slot.snapshot.source.sourceUri == association.source.sourceUri) {
            "durable snapshot source association changed"
        }
        if (association.sourceFingerprint != null) {
            require(slot.sourceFingerprint == association.sourceFingerprint) {
                "durable snapshot source fingerprint changed"
            }
        }
        val bytes = slot.serializedBytes ?: gson.toJson(
            SnapshotEnvelopeJson(
                storageSchemaVersion = LOCAL_DOCUMENT_STORAGE_SCHEMA_VERSION,
                documentId = association.documentId.value,
                sourceFingerprint = slot.sourceFingerprint,
                snapshot = slot.snapshot
            )
        ).toByteArray(Charsets.UTF_8)
        val staging = File(directory, "snapshot.restore.payload.$slotName.$transactionId.tmp")
        try {
            writeAndSync(staging, bytes)
            val record = readSnapshotFile(staging)
            require(record.documentId == association.documentId) { "durable snapshot document id changed" }
            require(record.snapshot == slot.snapshot) { "durable snapshot payload changed" }
            require(record.sourceFingerprint == slot.sourceFingerprint) {
                "durable snapshot fingerprint changed"
            }
            validateRecord(
                record,
                association.documentId,
                association.source.sourceUri,
                association.sourceFingerprint
            )
            return staging
        } catch (error: Throwable) {
            if (staging.exists()) Files.deleteIfExists(staging.toPath())
            throw error
        }
    }

    private fun snapshotRestoreIntentFile(documentId: DocumentId): File =
        File(documentDirectory(documentId), SNAPSHOT_RESTORE_INTENT_FILE_NAME)

    /**
     * Replays a pending exact restore before any caller can observe the
     * current/previous pair.  The intent names the requested pair, so replay
     * is idempotent and safe after a process dies between slot replacements.
     */
    private fun recoverPendingSnapshotRestoreLocked(documentId: DocumentId): LocalRepositoryError? {
        val intentFile = snapshotRestoreIntentFile(documentId)
        if (!intentFile.exists()) return null
        return try {
            val intent = readSnapshotRestoreIntent(intentFile, documentId)
            applySnapshotRestoreIntentLocked(documentId, intent)
            clearSnapshotRestoreIntentLocked(intent)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            LocalRepositoryError.CommitUncertain(
                operation = "recover durable snapshot restore",
                path = intentFile.path,
                detail = error.message
            )
        }
    }

    private fun writeSnapshotRestoreIntentLocked(
        directory: File,
        intent: SnapshotRestoreIntentJson
    ) {
        val intentFile = File(directory, SNAPSHOT_RESTORE_INTENT_FILE_NAME)
        val staging = File(directory, "snapshot.restore.intent.${UUID.randomUUID()}.tmp")
        try {
            writeAndSync(staging, gson.toJson(intent).toByteArray(Charsets.UTF_8))
            require(readSnapshotRestoreIntent(staging, DocumentId.parse(intent.documentId!!)) == intent) {
                "durable snapshot restore intent did not validate"
            }
            replaceAtomically(staging, intentFile)
        } finally {
            if (staging.exists()) Files.deleteIfExists(staging.toPath())
        }
    }

    private fun readSnapshotRestoreIntent(
        file: File,
        expectedDocumentId: DocumentId
    ): SnapshotRestoreIntentJson {
        val intent = requireNotNull(
            gson.fromJson(file.readText(Charsets.UTF_8), SnapshotRestoreIntentJson::class.java)
        ) { "durable snapshot restore intent missing" }
        require(intent.schemaVersion == SNAPSHOT_RESTORE_INTENT_SCHEMA_VERSION) {
            "unsupported durable snapshot restore intent schema"
        }
        val documentId = DocumentId.parse(requireNotNull(intent.documentId))
        require(documentId == expectedDocumentId) {
            "durable snapshot restore intent document id changed"
        }
        validateSnapshotRestorePayloadName(intent.currentPayload)
        validateSnapshotRestorePayloadName(intent.previousPayload)
        return intent
    }

    private fun validateSnapshotRestorePayloadName(name: String?) {
        if (name == null) return
        require(
            name.startsWith(SNAPSHOT_RESTORE_PAYLOAD_PREFIX) &&
                name.endsWith(".tmp") &&
                !name.contains('/') &&
                !name.contains('\\') &&
                name == File(name).name
        ) { "invalid durable snapshot restore payload name" }
    }

    private fun restorePayloadFile(directory: File, name: String): File {
        validateSnapshotRestorePayloadName(name)
        val file = File(directory, name)
        require(file.parentFile?.canonicalFile == directory.canonicalFile) {
            "durable snapshot restore payload escaped its document directory"
        }
        require(file.isFile) { "durable snapshot restore payload is missing" }
        return file
    }

    private fun applySnapshotRestoreIntentLocked(
        documentId: DocumentId,
        intent: SnapshotRestoreIntentJson
    ) {
        val directory = documentDirectory(documentId)
        replaceSnapshotSlotFromPayload(
            documentId,
            intent.currentPayload,
            currentSnapshotFile(documentId),
            directory
        )
        replaceSnapshotSlotFromPayload(
            documentId,
            intent.previousPayload,
            previousSnapshotFile(documentId),
            directory
        )
        require(
            snapshotSlotMatches(currentSnapshotFile(documentId), intent.currentPayload, directory) &&
                snapshotSlotMatches(previousSnapshotFile(documentId), intent.previousPayload, directory)
        ) { "durable snapshot restore did not verify the exact slot pair" }
    }

    private fun replaceSnapshotSlotFromPayload(
        documentId: DocumentId,
        payloadName: String?,
        target: File,
        directory: File
    ) {
        if (payloadName == null) {
            failureInjector.onPhase(
                RepositoryWritePhase.SNAPSHOT_RESTORE_BEFORE_SLOT_REPLACE,
                documentId,
                null
            )
            Files.deleteIfExists(target.toPath())
            failureInjector.onPhase(
                RepositoryWritePhase.SNAPSHOT_RESTORE_AFTER_SLOT_REPLACE,
                documentId,
                target
            )
            return
        }
        val payload = restorePayloadFile(directory, payloadName)
        val replacement = File(directory, "snapshot.restore.apply.${UUID.randomUUID()}.tmp")
        try {
            copyAndSync(payload, replacement)
            failureInjector.onPhase(
                RepositoryWritePhase.SNAPSHOT_RESTORE_BEFORE_SLOT_REPLACE,
                documentId,
                replacement
            )
            replaceAtomically(replacement, target)
            failureInjector.onPhase(
                RepositoryWritePhase.SNAPSHOT_RESTORE_AFTER_SLOT_REPLACE,
                documentId,
                target
            )
        } finally {
            if (replacement.exists()) Files.deleteIfExists(replacement.toPath())
        }
    }

    private fun snapshotSlotMatches(file: File, payloadName: String?, directory: File): Boolean {
        if (payloadName == null) return !file.exists()
        val payload = restorePayloadFile(directory, payloadName)
        return file.isFile && Files.readAllBytes(file.toPath()).contentEquals(
            Files.readAllBytes(payload.toPath())
        )
    }

    /** The intent is evidence until the exact pair has been read back. */
    private fun clearSnapshotRestoreIntentLocked(intent: SnapshotRestoreIntentJson) {
        val intentFile = File(documentDirectory(DocumentId.parse(intent.documentId!!)), SNAPSHOT_RESTORE_INTENT_FILE_NAME)
        try {
            if (!Files.deleteIfExists(intentFile.toPath())) return
        } catch (_: Throwable) {
            return
        }
        val directory = intentFile.parentFile ?: return
        listOfNotNull(intent.currentPayload, intent.previousPayload).forEach { name ->
            try {
                Files.deleteIfExists(File(directory, name).toPath())
            } catch (_: Throwable) {
                // The durable pair is already verified; an orphaned temporary
                // payload is harmless and remains recoverable by normal cleanup.
            }
        }
    }

    private fun validateSnapshot(snapshot: DocumentSnapshotV1) {
        require(snapshot.schemaVersion == DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION) {
            "unsupported document snapshot schema"
        }
        require(snapshot.snapshotRevision >= 0L) { "snapshot revision must be non-negative" }
        val source = requireNotNull(snapshot.source) { "snapshot source missing" }
        require(source.sourceUri.isNotBlank()) { "snapshot source URI blank" }
        source.providerMetadata.entries.forEach { (key, value) ->
            require(key.isNotBlank()) { "snapshot provider metadata key blank" }
            requireNotNull(value) { "snapshot provider metadata value missing" }
        }
        snapshot.pages.entries.forEach { (pageIndex, page) ->
            require(pageIndex >= 0) { "negative page index" }
            requireNotNull(page) { "page payload missing" }
            page.paths.forEach { path ->
                path.points.forEach { point -> require(point.x.isFinite() && point.y.isFinite()) { "invalid path point" } }
                require(path.strokeWidth.isFinite()) { "invalid path stroke width" }
            }
            page.measurements.forEach { measurement ->
                require(measurement.p1.x.isFinite() && measurement.p1.y.isFinite()) { "invalid measurement point" }
                require(measurement.p2.x.isFinite() && measurement.p2.y.isFinite()) { "invalid measurement point" }
            }
            page.notes.forEach { note ->
                require(note.x.isFinite() && note.y.isFinite() && note.fontSize.isFinite() && note.rotation.isFinite()) {
                    "invalid note"
                }
            }
            page.scale?.let { require(it.pixelsPerFoot.isFinite() && it.pixelsPerFoot > 0f) { "invalid page scale" } }
            page.shapes.forEach { shape -> validateShape(shape) }
            page.photoPins.forEach { pin ->
                require(pin.x.isFinite() && pin.y.isFinite()) { "invalid photo pin" }
                pin.imageNotes.values.forEach { notes ->
                    notes.forEach { note ->
                        require(note.x.isFinite() && note.y.isFinite() && note.fontSize.isFinite() && note.rotation.isFinite() && note.fontSizeRatio.isFinite()) {
                            "invalid image note"
                        }
                    }
                }
                pin.imageShapes.values.forEach { shapes -> shapes.forEach(::validateShape) }
            }
        }
    }

    private fun validateShape(shape: com.example.myapplication.stage1.ShapeSnapshotV1) {
        require(
            shape.x.isFinite() && shape.y.isFinite() && shape.width.isFinite() && shape.height.isFinite() &&
                shape.rotation.isFinite() && shape.strokeWidth.isFinite() && shape.strokeWidthRatio.isFinite() &&
                shape.widthRatio.isFinite() && shape.heightRatio.isFinite()
        ) { "invalid shape" }
    }

    private fun preserveAsPrevious(current: File, previous: File) {
        val staging = File(previous.parentFile, "${previous.name}.${UUID.randomUUID()}.tmp")
        try {
            copyAndSync(current, staging)
            replaceAtomically(staging, previous)
        } finally {
            if (staging.exists()) quarantineFile(staging, "previous-copy")
        }
    }

    private fun restorePrevious(previous: File, current: File) {
        val staging = File(current.parentFile, "${current.name}.${UUID.randomUUID()}.recovery.tmp")
        try {
            copyAndSync(previous, staging)
            replaceAtomically(staging, current)
        } finally {
            if (staging.exists()) quarantineFile(staging, "recovery-copy")
        }
    }

    private fun writeAndSync(file: File, bytes: ByteArray) {
        file.parentFile?.let { parent -> require(parent.exists() || parent.mkdirs()) }
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun copyAndSync(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun replaceAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // A plain replace can expose a missing or partial current file
            // after a process crash.  Fail closed instead; the caller keeps
            // the previous-good copy and surfaces a typed write failure.
            throw IOException("Atomic replacement is not supported for ${destination.path}")
        }
    }

    private fun quarantineFile(file: File, label: String) {
        if (!file.exists()) return
        val parent = file.parentFile ?: quarantineDirectory
        val localQuarantine = if (parent == rootDirectory || parent == quarantineDirectory) {
            quarantineDirectory
        } else {
            File(parent, "quarantine")
        }
        if (!localQuarantine.exists()) localQuarantine.mkdirs()
        val target = File(
            localQuarantine,
            "$label-${clockMillis()}-${UUID.randomUUID()}-${file.name}"
        )
        try {
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // The original artifact remains in place if quarantine itself is
            // unavailable.  It is never deleted as part of error handling.
        }
    }

    /** Runs synchronous provider/legacy reads on the repository's injected IO dispatcher. */
    internal suspend fun <T> runOnIo(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

    /**
     * Migration takes the same lock order as source resolution: manifest then
     * document.  The locks remain held across claim, snapshot read/write,
     * read-back, and completion marking so a normal save cannot interleave.
     */
    internal suspend fun <T> withManifestAndDocumentLock(
        documentId: DocumentId,
        block: () -> T
    ): T = withContext(ioDispatcher) {
        manifestMutex.withLock {
            documentMutex(documentId).withLock {
                ensureDirectories()
                block()
            }
        }
    }

    internal fun loadLockedForMigration(
        documentId: DocumentId,
        expectedSourceUri: String?,
        expectedFingerprint: SourceFingerprint?
    ): DocumentLoadResult = loadLocked(documentId, expectedSourceUri, expectedFingerprint)

    internal fun readManifestLockedForMigration(): ManifestReadResult = readManifestLocked()

    internal fun saveLockedForMigration(
        documentId: DocumentId,
        snapshot: DocumentSnapshotV1,
        sourceFingerprint: SourceFingerprint?
    ): DocumentSaveResult {
        val failure = writeSnapshotLocked(documentId, snapshot, sourceFingerprint)
        return if (failure == null) {
            DocumentSaveResult.Saved(documentId)
        } else {
            DocumentSaveResult.Failed(failure)
        }
    }

    internal fun claimLegacyArtifactLocked(
        documentId: DocumentId,
        artifactName: String
    ): LegacyArtifactClaim {
        val read = readManifestLocked()
        val entries = when (read) {
            is ManifestReadResult.Loaded -> read.entries.toMutableList()
            is ManifestReadResult.Failed -> return LegacyArtifactClaim.Failed(read.error)
        }
        val index = entries.indexOfFirst { it.documentId == documentId }
        if (index < 0) {
            return LegacyArtifactClaim.Failed(
                LocalRepositoryError.LegacyMigrationFailure("manifest association missing")
            )
        }
        val existing = entries[index]
        if (existing.legacyArtifactName != artifactName) {
            return LegacyArtifactClaim.Failed(
                LocalRepositoryError.LegacyMigrationFailure("legacy artifact association changed")
            )
        }
        val otherOwner = entries.firstOrNull {
            it.documentId != documentId &&
                it.legacyArtifactName == artifactName &&
                it.legacyMigrationClaimed
        }
        if (otherOwner != null) return LegacyArtifactClaim.Ambiguous(otherOwner.documentId)
        if (!existing.legacyMigrationClaimed) {
            entries[index] = existing.copy(legacyMigrationClaimed = true)
            try {
                writeManifestLocked(entries)
            } catch (error: Exception) {
                return LegacyArtifactClaim.Failed(
                    manifestWriteError("claim legacy artifact", error)
                )
            }
        }
        return LegacyArtifactClaim.Claimed
    }

    internal fun markMigrationVerifiedLocked(
        documentId: DocumentId,
        sourceFingerprint: SourceFingerprint?
    ): LocalRepositoryError? {
        val read = readManifestLocked()
        val entries = when (read) {
            is ManifestReadResult.Loaded -> read.entries.toMutableList()
            is ManifestReadResult.Failed -> return read.error
        }
        val index = entries.indexOfFirst { it.documentId == documentId }
        if (index < 0) return LocalRepositoryError.LegacyMigrationFailure("manifest association missing")
        val existing = entries[index]
        entries[index] = existing.copy(
            migrationVerified = true,
            sourceFingerprint = sourceFingerprint ?: existing.sourceFingerprint
        )
        return try {
            writeManifestLocked(entries)
            null
        } catch (error: Exception) {
            manifestWriteError("mark migration verified", error)
        }
    }

    internal suspend fun markMigrationVerified(
        documentId: DocumentId,
        sourceFingerprint: SourceFingerprint?
    ): LocalRepositoryError? = withContext(ioDispatcher) {
        manifestMutex.withLock {
            markMigrationVerifiedLocked(documentId, sourceFingerprint)
        }
    }

    internal suspend fun manifestEntry(documentId: DocumentId): DocumentManifestEntryV1? =
        when (val result = readManifest()) {
            is ManifestReadResult.Loaded -> result.entries.firstOrNull { it.documentId == documentId }
            is ManifestReadResult.Failed -> null
        }

    private fun manifestWriteError(operation: String, error: Exception): LocalRepositoryError =
        if (error is RepositoryCommitUncertainSignal) {
            LocalRepositoryError.CommitUncertain(operation, manifestFile.path, error.original.message)
        } else {
            LocalRepositoryError.IoFailure(operation, manifestFile.path, error.message)
        }

    private fun legacyArtifactNameFor(sourceUri: String): String =
        "markups_${sourceUri.hashCode()}.bin"

    private data class SnapshotRecord(
        val documentId: DocumentId,
        val snapshot: DocumentSnapshotV1,
        val sourceFingerprint: SourceFingerprint?
    )

    private data class ManifestJson(
        val schemaVersion: Int?,
        val entries: List<ManifestEntryJson>?
    )

    private data class ManifestEntryJson(
        val documentId: String?,
        val sourceUri: String?,
        val displayName: String?,
        val providerMetadata: Map<String, String>?,
        val sourceFingerprint: SourceFingerprint?,
        val migrationVerified: Boolean?,
        val legacyMigrationClaimed: Boolean?,
        val legacyArtifactName: String?
    )

    private data class SnapshotEnvelopeJson(
        val storageSchemaVersion: Int?,
        val documentId: String?,
        val sourceFingerprint: SourceFingerprint?,
        val snapshot: DocumentSnapshotV1?
    )

    private data class SnapshotRestoreIntentJson(
        val schemaVersion: Int?,
        val documentId: String?,
        val currentPayload: String?,
        val previousPayload: String?
    )
}
