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
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Version of the only snapshot envelope accepted by this repository. */
const val LOCAL_DOCUMENT_STORAGE_SCHEMA_VERSION: Int = 2

/** Version of the only local document manifest accepted by this repository. */
const val DOCUMENT_MANIFEST_SCHEMA_VERSION: Int = 2

private const val SNAPSHOT_RESTORE_INTENT_SCHEMA_VERSION: Int = 2
private const val SNAPSHOT_RESTORE_INTENT_FILE_NAME: String = "snapshot.restore.pending.json"
private const val SNAPSHOT_RESTORE_PAYLOAD_PREFIX: String = "snapshot.restore.payload."
private const val SNAPSHOT_ACCEPTED_STATE_SCHEMA_VERSION: Int = 1
private const val SNAPSHOT_ACCEPTED_STATE_FILE_NAME: String = "snapshot.accepted.state.json"
private const val SNAPSHOT_ACCEPTED_STATE_PENDING: String = "PENDING"
private const val SNAPSHOT_ACCEPTED_STATE_ACCEPTED: String = "ACCEPTED"

/** A resolved source-to-document association. */
data class DocumentAssociation(
    val documentId: DocumentId,
    val source: com.example.myapplication.stage1.DocumentSourceIdentityV1,
    val sourceFingerprint: SourceFingerprint?
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

    /** An unfingerprinted snapshot exists and needs an explicit bind. */
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
    val sourceFingerprint: SourceFingerprint?
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
    val previous: DurableSnapshotSlot?,
    /** Whether this association has ever reached an accepted durable state. */
    val accepted: Boolean = current != null || previous != null
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

    /**
     * The bytes identify a retired/future format rather than malformed data.
     * Rejection is deliberately distinct from corruption so callers never
     * quarantine, replace, or treat the unsupported artifact as an empty file.
     */
    data class UnsupportedFormat(
        val path: String,
        val format: String,
        val actualVersion: Int?,
        val expectedVersion: Int,
        val detail: String?
    ) : LocalRepositoryError()
}

private class RepositorySourceChangedSignal(val error: LocalRepositoryError.SourceChanged) : Exception()
private class RepositoryAssociationMismatchSignal(val error: LocalRepositoryError.AssociationMismatch) : Exception()
private class RepositoryCommitUncertainSignal(val original: Exception) : Exception(original)
private class RepositorySnapshotBeforeReplaceSignal(val original: Exception) : Exception(original)

private class UnsupportedFormatSignal(
    val format: String,
    val actualVersion: Int?,
    val expectedVersion: Int,
    message: String
) : Exception(message)

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
 * durable snapshot IO, corruption recovery, and per-document serialization.
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

        /**
         * Manifest admission reuses the existing Stage 5 envelope budgets. A
         * manifest entry is small metadata, but an unbounded association list
         * is still an allocation/DoS risk before a caller can ever use it.
         */
        val MANIFEST_INTEGER_REGEX = Regex("-?(0|[1-9][0-9]*)")
        val MANIFEST_SHA256_REGEX = Regex("[0-9a-fA-F]{64}")
        val MANIFEST_ROOT_FIELDS = setOf("schemaVersion", "entries")
        val MANIFEST_ENTRY_FIELDS = setOf(
            "documentId",
            "sourceUri",
            "displayName",
            "providerMetadata",
            "sourceFingerprint"
        )
        val MANIFEST_ENTRY_REQUIRED_FIELDS = setOf("documentId", "sourceUri", "providerMetadata")
        val MANIFEST_FINGERPRINT_FIELDS = setOf("algorithm", "digestHex", "byteCount")
        val MANIFEST_FINGERPRINT_REQUIRED_FIELDS = MANIFEST_FINGERPRINT_FIELDS
        val MANIFEST_RETIRED_FIELDS = setOf(
            "migrationVerified",
            "legacyMigrationClaimed",
            "legacyArtifactName"
        )
    }

    fun currentSnapshotFile(documentId: DocumentId): File =
        File(documentDirectory(documentId), "snapshot.json")

    fun previousSnapshotFile(documentId: DocumentId): File =
        File(documentDirectory(documentId), "snapshot.previous.json")

    fun snapshotQuarantineDirectory(documentId: DocumentId): File =
        File(documentDirectory(documentId), "quarantine")

    /** The durable acceptance journal is exposed for focused recovery probes. */
    fun acceptedSnapshotStateFile(documentId: DocumentId): File =
        File(documentDirectory(documentId), SNAPSHOT_ACCEPTED_STATE_FILE_NAME)

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
                            sourceFingerprint = currentFingerprint ?: previousFingerprint
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
                    sourceFingerprint = currentFingerprint
                )
                manifest += entry
                writeManifestLocked(manifest)
                ResolveDocumentResult.Resolved(
                    DocumentAssociation(
                        documentId = documentId,
                        source = source,
                        sourceFingerprint = currentFingerprint
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
            val acceptedState = readAcceptedSnapshotStateLocked(association.documentId)
            require(acceptedState != AcceptedSnapshotState.PENDING) {
                "accepted snapshot state is unresolved"
            }
            val current = readDurableSnapshotSlotLocked(
                currentSnapshotFile(association.documentId),
                association
            )
            val previous = readDurableSnapshotSlotLocked(
                previousSnapshotFile(association.documentId),
                association
            )
            if (acceptedState == AcceptedSnapshotState.ABSENT &&
                (current != null || previous != null)
            ) {
                ensureAcceptedSnapshotStateLocked(association.documentId, acceptedState)?.let { failure ->
                    throw IOException("accepted snapshot state could not be established: $failure")
                }
            }
            DocumentDurableSnapshotState(
                current = current,
                previous = previous,
                accepted = acceptedState == AcceptedSnapshotState.ACCEPTED ||
                    current != null || previous != null
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
                val acceptedState = try {
                    readAcceptedSnapshotStateLocked(association.documentId)
                } catch (unsupported: UnsupportedFormatSignal) {
                    return@withLock DocumentSaveResult.Failed(
                        unsupportedSnapshotError(acceptedSnapshotStateFile(association.documentId), unsupported)
                    )
                } catch (error: RepositoryAssociationMismatchSignal) {
                    return@withLock DocumentSaveResult.Failed(error.error)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return@withLock DocumentSaveResult.Failed(
                        LocalRepositoryError.CorruptSnapshot(
                            path = acceptedSnapshotStateFile(association.documentId).path,
                            recoveryAttempted = true,
                            detail = "accepted snapshot state is invalid: ${error.message}"
                        )
                    )
                }
                if (acceptedState == AcceptedSnapshotState.PENDING) {
                    return@withLock DocumentSaveResult.Failed(
                        LocalRepositoryError.CommitUncertain(
                            operation = "restore durable snapshot state",
                            path = acceptedSnapshotStateFile(association.documentId).path,
                            detail = "accepted snapshot transition remains unresolved"
                        )
                    )
                }
                // Rollback is a write route too: never replace an
                // unsupported slot merely because a caller supplied a valid
                // captured pair. The old bytes remain available for an
                // explicit user-directed format decision.
                unsupportedSnapshotSlotLocked(association.documentId)?.let { failure ->
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
                    previousPayload = stagedPrevious?.name,
                    accepted = state.accepted
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
        // An unsupported format is an explicit, non-destructive failure. Do
        // not let the normal corruption recovery path quarantine or replace
        // those bytes, and do not fall back to a previous manifest that could
        // make an old current manifest look like an empty/new repository.
        val current = if (currentWasPresent) {
            try {
                readManifestFile(manifestFile)
            } catch (unsupported: UnsupportedFormatSignal) {
                return ManifestReadResult.Failed(unsupportedManifestError(manifestFile, unsupported))
            }
        } else {
            null
        }
        val previous = if (previousWasPresent) {
            try {
                readManifestFile(previousManifestFile)
            } catch (unsupported: UnsupportedFormatSignal) {
                return ManifestReadResult.Failed(unsupportedManifestError(previousManifestFile, unsupported))
            }
        } else {
            null
        }
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
                next.documentId == old.documentId
        }
    }

    private fun readManifestFile(file: File): List<DocumentManifestEntryV1>? {
        return try {
            val root = FileInputStream(file).use {
                com.example.myapplication.stage5.parseBoundedJsonObject(it, com.example.myapplication.stage5.Stage5Limits.MAX_METADATA_BYTES, "document manifest")
            }
            require(root.isJsonObject) { "manifest root must be an object" }
            val rootObject = root.asJsonObject
            // Inspect the version before rejecting any other member. A
            // future-format manifest may legitimately carry fields this
            // reader does not know; classify it as unsupported so its bytes
            // stay in place instead of entering corruption quarantine.
            val schemaElement = requireNotNull(rootObject.get("schemaVersion")) {
                "manifest schemaVersion missing"
            }
            val schemaVersionRaw = requireManifestIntegerToken(schemaElement, "manifest.schemaVersion")
            val schemaVersionValue = schemaVersionRaw.toLongOrNull()
            if (schemaVersionValue == null) {
                throw UnsupportedFormatSignal(
                    format = "document manifest",
                    actualVersion = null,
                    expectedVersion = DOCUMENT_MANIFEST_SCHEMA_VERSION,
                    message = "unsupported manifest schema: $schemaVersionRaw"
                )
            }
            if (schemaVersionValue != DOCUMENT_MANIFEST_SCHEMA_VERSION.toLong()) {
                throw UnsupportedFormatSignal(
                    format = "document manifest",
                    actualVersion = schemaVersionValue
                        .takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
                        ?.toInt(),
                    expectedVersion = DOCUMENT_MANIFEST_SCHEMA_VERSION,
                    message = "unsupported manifest schema: $schemaVersionValue"
                )
            }
            val schemaVersion = DOCUMENT_MANIFEST_SCHEMA_VERSION
            if (rootObject.entrySet().any { (key, value) ->
                    key in MANIFEST_RETIRED_FIELDS ||
                        (key == "entries" && value.isJsonArray && value.asJsonArray.any { entry ->
                            entry.isJsonObject && entry.asJsonObject.keySet().any(MANIFEST_RETIRED_FIELDS::contains)
                        })
                }
            ) {
                throw UnsupportedFormatSignal(
                    format = "document manifest with retired fields",
                    actualVersion = schemaVersion,
                    expectedVersion = DOCUMENT_MANIFEST_SCHEMA_VERSION,
                    message = "manifest contains retired migration fields"
                )
            }
            requireManifestExactFields(
                objectValue = rootObject,
                allowed = MANIFEST_ROOT_FIELDS,
                required = MANIFEST_ROOT_FIELDS,
                label = "manifest"
            )

            val entriesElement = requireNotNull(rootObject.get("entries")) {
                "manifest entries missing"
            }
            require(entriesElement.isJsonArray) { "manifest entries must be an array" }
            val entriesArray = entriesElement.asJsonArray
            require(entriesArray.size() > 0) { "manifest cannot be empty once created" }
            require(entriesArray.size() <= com.example.myapplication.stage5.Stage5Limits.MAX_REMOTE_DESCRIPTOR_COUNT) {
                "manifest entry count exceeds its limit"
            }

            // All raw field, type, and budget checks happen before Gson is
            // allowed to materialize nullable DTO defaults. The byte ceiling
            // above remains the aggregate wire budget; this decoded-character
            // budget keeps a future encoding/parser from bypassing it.
            var totalStringChars = 0L
            val rawDocumentIds = HashSet<String>()
            val rawSourceUris = HashSet<String>()
            fun countManifestString(value: String, label: String) {
                totalStringChars = try {
                    Math.addExact(totalStringChars, value.length.toLong())
                } catch (_: ArithmeticException) {
                    throw IllegalArgumentException("$label string budget overflow")
                }
                require(totalStringChars <= com.example.myapplication.stage5.Stage5Limits.MAX_METADATA_BYTES.toLong()) {
                    "manifest string budget exceeds its limit"
                }
            }

            entriesArray.forEachIndexed { index, entryElement ->
                val label = "manifest.entries[$index]"
                require(entryElement.isJsonObject) { "$label must be an object" }
                val entryObject = entryElement.asJsonObject
                requireManifestExactFields(
                    objectValue = entryObject,
                    allowed = MANIFEST_ENTRY_FIELDS,
                    required = MANIFEST_ENTRY_REQUIRED_FIELDS,
                    label = label
                )

                val documentIdValue = requireNotNull(requireManifestString(
                    element = entryObject.get("documentId"),
                    label = "$label.documentId",
                    maxChars = com.example.myapplication.stage5.Stage5Limits.MAX_ID_CHARS,
                    required = true
                ))
                // Reuse the canonical Stage 2 UUID parser, but only after the
                // raw JSON type/length has been admitted.
                val documentId = DocumentId.parse(documentIdValue)
                require(rawDocumentIds.add(documentId.value)) {
                    "$label.documentId duplicates another manifest entry"
                }
                countManifestString(documentIdValue, "$label.documentId")

                val sourceUri = requireNotNull(requireManifestString(
                    element = entryObject.get("sourceUri"),
                    label = "$label.sourceUri",
                    maxChars = com.example.myapplication.stage5.Stage5Limits.MAX_STRING_CHARS,
                    required = true
                ))
                require(sourceUri.isNotBlank()) { "$label.sourceUri is blank" }
                require(rawSourceUris.add(sourceUri)) {
                    "$label.sourceUri duplicates another manifest entry"
                }
                countManifestString(sourceUri, "$label.sourceUri")

                val displayName = requireManifestString(
                    element = entryObject.get("displayName"),
                    label = "$label.displayName",
                    maxChars = com.example.myapplication.stage5.Stage5Limits.MAX_STRING_CHARS,
                    required = false
                )
                displayName?.let { countManifestString(it, "$label.displayName") }

                val providerMetadataElement = requireNotNull(entryObject.get("providerMetadata")) {
                    "$label.providerMetadata is missing"
                }
                require(providerMetadataElement.isJsonObject) {
                    "$label.providerMetadata must be an object"
                }
                val providerMetadata = providerMetadataElement.asJsonObject
                require(providerMetadata.size() <= com.example.myapplication.stage5.Stage5Limits.MAX_PROVIDER_PROPERTIES) {
                    "$label.providerMetadata exceeds its entry limit"
                }
                providerMetadata.entrySet().forEach { (key, value) ->
                    require(key.isNotBlank() && key.length <= com.example.myapplication.stage5.Stage5Limits.MAX_STRING_CHARS) {
                        "$label.providerMetadata contains an unsafe key"
                    }
                    countManifestString(key, "$label.providerMetadata key")
                    val metadataValue = requireNotNull(requireManifestString(
                        element = value,
                        label = "$label.providerMetadata[$key]",
                        maxChars = com.example.myapplication.stage5.Stage5Limits.MAX_STRING_CHARS,
                        required = true,
                        nonBlank = false
                    ))
                    countManifestString(metadataValue, "$label.providerMetadata[$key]")
                }

                val fingerprintElement = entryObject.get("sourceFingerprint")
                if (fingerprintElement != null && !fingerprintElement.isJsonNull) {
                    require(fingerprintElement.isJsonObject) {
                        "$label.sourceFingerprint must be an object or null"
                    }
                    val fingerprintObject = fingerprintElement.asJsonObject
                    requireManifestExactFields(
                        objectValue = fingerprintObject,
                        allowed = MANIFEST_FINGERPRINT_FIELDS,
                        required = MANIFEST_FINGERPRINT_REQUIRED_FIELDS,
                        label = "$label.sourceFingerprint"
                    )
                    val algorithm = requireNotNull(requireManifestString(
                        element = fingerprintObject.get("algorithm"),
                        label = "$label.sourceFingerprint.algorithm",
                        maxChars = com.example.myapplication.stage5.Stage5Limits.MAX_STRING_CHARS,
                        required = true
                    ))
                    require(algorithm.equals(SourceFingerprint.SHA256_ALGORITHM, ignoreCase = true)) {
                        "$label.sourceFingerprint.algorithm is unsupported"
                    }
                    countManifestString(algorithm, "$label.sourceFingerprint.algorithm")

                    val digest = requireNotNull(requireManifestString(
                        element = fingerprintObject.get("digestHex"),
                        label = "$label.sourceFingerprint.digestHex",
                        maxChars = 64,
                        required = true
                    ))
                    require(digest.matches(MANIFEST_SHA256_REGEX)) {
                        "$label.sourceFingerprint.digestHex is invalid"
                    }
                    countManifestString(digest, "$label.sourceFingerprint.digestHex")

                    val byteCount = requireManifestInteger(
                        element = fingerprintObject.get("byteCount"),
                        label = "$label.sourceFingerprint.byteCount",
                        min = 0L,
                        max = Long.MAX_VALUE
                    )
                    // Run the existing Stage 2 constructor as the final raw
                    // consistency check. Gson must not be the source of these
                    // invariants because it can bypass Kotlin init blocks.
                    SourceFingerprint(algorithm, digest, byteCount)
                }
            }

            val dto = gson.fromJson(root, ManifestJson::class.java)
            require(dto.schemaVersion == DOCUMENT_MANIFEST_SCHEMA_VERSION) {
                "manifest schemaVersion missing or invalid"
            }
            val entries = requireNotNull(dto.entries) { "manifest entries missing" }
            val result = entries.map { entry ->
                val documentId = DocumentId.parse(requireNotNull(entry.documentId))
                val sourceUri = requireNotNull(entry.sourceUri)
                require(sourceUri.isNotBlank()) { "manifest sourceUri blank" }
                val metadata = requireNotNull(entry.providerMetadata) { "manifest providerMetadata missing" }
                val fingerprint = entry.sourceFingerprint?.let {
                    SourceFingerprint(it.algorithm, it.digestHex, it.byteCount)
                }
                DocumentManifestEntryV1(
                    documentId = documentId,
                    sourceUri = sourceUri,
                    displayName = entry.displayName,
                    providerMetadata = metadata,
                    sourceFingerprint = fingerprint
                )
            }
            require(result.map { it.sourceUri }.toSet().size == result.size) {
                "manifest contains duplicate source URIs"
            }
            require(result.map { it.documentId }.toSet().size == result.size) {
                "manifest contains duplicate document ids"
            }
            result
        } catch (unsupported: UnsupportedFormatSignal) {
            throw unsupported
        } catch (_: Exception) {
            null
        }
    }

    private fun requireManifestExactFields(
        objectValue: com.google.gson.JsonObject,
        allowed: Set<String>,
        required: Set<String>,
        label: String
    ) {
        val unknown = objectValue.keySet().firstOrNull { it !in allowed }
        require(unknown == null) { "$label contains unsupported field: $unknown" }
        val missing = required.firstOrNull { !objectValue.has(it) || objectValue.get(it).isJsonNull }
        require(missing == null) { "$label is missing or null: $missing" }
    }

    private fun requireManifestString(
        element: com.google.gson.JsonElement?,
        label: String,
        maxChars: Int,
        required: Boolean,
        nonBlank: Boolean = required
    ): String? {
        if (element == null || element.isJsonNull) {
            require(!required) { "$label is missing or null" }
            return null
        }
        require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            "$label must be a JSON string"
        }
        val value = element.asString
        require(value.length <= maxChars) { "$label exceeds $maxChars characters" }
        if (nonBlank) require(value.isNotBlank()) { "$label is blank" }
        return value
    }

    private fun requireManifestInteger(
        element: com.google.gson.JsonElement?,
        label: String,
        min: Long,
        max: Long
    ): Long {
        val raw = requireManifestIntegerToken(element, label)
        val value = raw.toLongOrNull() ?: throw IllegalArgumentException("$label is outside long range")
        require(value in min..max) { "$label is outside its range" }
        return value
    }

    private fun requireManifestIntegerToken(
        element: com.google.gson.JsonElement?,
        label: String
    ): String {
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
            "$label must be a JSON integer"
        }
        val raw = element.asString
        require(MANIFEST_INTEGER_REGEX.matches(raw)) { "$label must be a finite integer" }
        return raw
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
                    sourceFingerprint = entry.sourceFingerprint
                )
            }
        )
        writeAcceptedFile(
            current = manifestFile,
            previous = previousManifestFile,
            staging = manifestStagingFile,
            contents = com.example.myapplication.stage5.encodeBoundedJson(gson, dto, com.example.myapplication.stage5.Stage5Limits.MAX_METADATA_BYTES, "document manifest"),
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
        val acceptedState = try {
            readAcceptedSnapshotStateLocked(documentId)
        } catch (unsupported: UnsupportedFormatSignal) {
            return unsupportedSnapshotError(acceptedSnapshotStateFile(documentId), unsupported)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return LocalRepositoryError.CorruptSnapshot(
                path = acceptedSnapshotStateFile(documentId).path,
                recoveryAttempted = true,
                detail = "accepted snapshot state could not be read: ${error.message}"
            )
        }
        if (acceptedState == AcceptedSnapshotState.PENDING) {
            return LocalRepositoryError.CommitUncertain(
                operation = "write snapshot",
                path = acceptedSnapshotStateFile(documentId).path,
                detail = "an earlier accepted snapshot transition remains unresolved"
            )
        }
        // A save is also an authoritative write route.  If either accepted
        // slot is from a retired/future format, fail before staging anything
        // so an explicit format decision is required and both original byte
        // sequences remain untouched.
        unsupportedSnapshotSlotLocked(documentId)?.let { return it }
        val needsAcceptanceJournal = acceptedState == AcceptedSnapshotState.ABSENT
        val hadAcceptedEvidence = currentSnapshotFile(documentId).exists() ||
            previousSnapshotFile(documentId).exists() ||
            hasRetainedSnapshotEvidenceLocked(documentId)
        if (needsAcceptanceJournal) {
            try {
                writeAcceptedSnapshotStateLocked(documentId, AcceptedSnapshotState.PENDING)
            } catch (unsupported: UnsupportedFormatSignal) {
                return unsupportedSnapshotError(acceptedSnapshotStateFile(documentId), unsupported)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return LocalRepositoryError.CommitUncertain(
                    operation = "begin snapshot acceptance",
                    path = acceptedSnapshotStateFile(documentId).path,
                    detail = error.message
                )
            }
        }
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
                contents = com.example.myapplication.stage5.encodeBoundedJson(gson, envelope, com.example.myapplication.stage5.Stage5Limits.MAX_JSON_BYTES, "local snapshot envelope"),
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
            if (needsAcceptanceJournal) {
                try {
                    writeAcceptedSnapshotStateLocked(documentId, AcceptedSnapshotState.ACCEPTED)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // The snapshot slot has already been durably replaced,
                    // but acceptance could not be published. Retain PENDING
                    // evidence and force the next open through recovery.
                    throw RepositoryCommitUncertainSignal(error)
                }
            }
            null
        } catch (error: RepositoryCommitUncertainSignal) {
            LocalRepositoryError.CommitUncertain(
                operation = "write snapshot",
                path = currentSnapshotFile(documentId).path,
                detail = error.original.message
            )
        } catch (error: RepositorySnapshotBeforeReplaceSignal) {
            val failure = LocalRepositoryError.IoFailure(
                operation = "write snapshot",
                path = currentSnapshotFile(documentId).path,
                detail = error.original.message
            )
            if (needsAcceptanceJournal) {
                if (hadAcceptedEvidence) {
                    retainAcceptanceJournalAfterKnownFailure(documentId) ?: failure
                } else {
                    clearAcceptanceJournalAfterKnownFailure(documentId) ?: failure
                }
            } else {
                failure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: UnsupportedFormatSignal) {
            unsupportedSnapshotError(currentSnapshotFile(documentId), error)
        } catch (error: IllegalArgumentException) {
            val failure = LocalRepositoryError.InvalidSnapshot(error.message ?: "invalid snapshot")
            if (needsAcceptanceJournal) {
                if (hadAcceptedEvidence) {
                    retainAcceptanceJournalAfterKnownFailure(documentId) ?: failure
                } else {
                    clearAcceptanceJournalAfterKnownFailure(documentId) ?: failure
                }
            } else {
                failure
            }
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
        var replacementStarted = false
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
                } catch (unsupported: UnsupportedFormatSignal) {
                    throw unsupported
                } catch (_: Exception) {
                    false
                }
                if (currentIsValid) {
                    // Do not destroy a retired/future previous-good slot while
                    // advancing a valid current snapshot. Other malformed
                    // previous bytes retain the established replace policy.
                    if (previous.exists()) {
                        try {
                            validate(previous)
                        } catch (unsupported: UnsupportedFormatSignal) {
                            throw unsupported
                        } catch (_: Exception) {
                            // A corrupt previous slot is replaceable; the
                            // current slot remains the authoritative source.
                        }
                    }
                    preserveAsPrevious(current, previous)
                } else {
                    quarantineFile(current, "invalid-current-before-write")
                }
            }

            failureInjector.onPhase(beforeReplacePhase, documentId, staging)
            try {
                replacementStarted = true
                replaceAtomically(staging, current)
                if (documentId != null) {
                    failureInjector.onPhase(RepositoryWritePhase.SNAPSHOT_AFTER_REPLACE, documentId, staging)
                }
                validate(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                quarantineFile(current, "failed-read-back")
                if (previous.exists()) restorePrevious(previous, current)
                throw RepositoryCommitUncertainSignal(error)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: UnsupportedFormatSignal) {
            // A retired/future current artifact is preserved byte-for-byte;
            // discard only the newly staged candidate. The unsupported input
            // itself is never deleted or moved to quarantine.
            try {
                Files.deleteIfExists(staging.toPath())
            } catch (_: Exception) {
                // If cleanup is unavailable, leave the staging artifact for
                // the normal orphan-staging recovery path.
            }
            throw error
        } catch (error: Exception) {
            if (staging.exists()) quarantineFile(staging, "interrupted-write")
            if (!replacementStarted) {
                throw RepositorySnapshotBeforeReplaceSignal(error)
            }
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
        val acceptedState = try {
            readAcceptedSnapshotStateLocked(documentId)
        } catch (unsupported: UnsupportedFormatSignal) {
            return DocumentLoadResult.Failed(
                unsupportedSnapshotError(acceptedSnapshotStateFile(documentId), unsupported)
            )
        } catch (error: RepositoryAssociationMismatchSignal) {
            return DocumentLoadResult.Failed(error.error)
        } catch (error: Exception) {
            return DocumentLoadResult.Failed(
                LocalRepositoryError.CorruptSnapshot(
                    path = acceptedSnapshotStateFile(documentId).path,
                    recoveryAttempted = true,
                    detail = "accepted snapshot state is invalid: ${error.message}"
                )
            )
        }
        if (acceptedState == AcceptedSnapshotState.PENDING) {
            return DocumentLoadResult.Failed(
                LocalRepositoryError.CommitUncertain(
                    operation = "load snapshot",
                    path = acceptedSnapshotStateFile(documentId).path,
                    detail = "accepted snapshot transition remains unresolved"
                )
            )
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
            } catch (unsupported: UnsupportedFormatSignal) {
                return DocumentLoadResult.Failed(unsupportedSnapshotError(current, unsupported))
            } catch (error: RepositorySourceChangedSignal) {
                ensureAcceptedSnapshotStateLocked(documentId, acceptedState)?.let { failure ->
                    return DocumentLoadResult.Failed(failure)
                }
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
            ensureAcceptedSnapshotStateLocked(documentId, acceptedState)?.let { failure ->
                return DocumentLoadResult.Failed(failure)
            }
            return DocumentLoadResult.Loaded(
                documentId = documentId,
                snapshot = currentRecord.snapshot,
                sourceFingerprint = currentRecord.sourceFingerprint,
                recoveredFromPrevious = false
            )
        }

        var previousRecord: SnapshotRecord? = null
        var previousWasCorrupt = false
        var previousAssociationError: LocalRepositoryError.AssociationMismatch? = null
        if (previous.exists()) {
            previousRecord = try {
                readSnapshotFile(previous).also {
                    validateRecord(it, documentId, expectedSourceUri, expectedFingerprint)
                }
            } catch (unsupported: UnsupportedFormatSignal) {
                return DocumentLoadResult.Failed(unsupportedSnapshotError(previous, unsupported))
            } catch (error: RepositorySourceChangedSignal) {
                ensureAcceptedSnapshotStateLocked(documentId, acceptedState)?.let { failure ->
                    return DocumentLoadResult.Failed(failure)
                }
                return DocumentLoadResult.Failed(error.error)
            } catch (error: RepositoryAssociationMismatchSignal) {
                previousAssociationError = error.error
                previousWasCorrupt = true
                quarantineFile(previous, "mismatched-previous")
                null
            } catch (_: Exception) {
                previousWasCorrupt = true
                quarantineFile(previous, "corrupt-previous")
                null
            }
        }
        if (previousRecord != null) {
            ensureAcceptedSnapshotStateLocked(documentId, acceptedState)?.let { failure ->
                return DocumentLoadResult.Failed(failure)
            }
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
        val retainedSnapshotEvidence = hasRetainedSnapshotEvidenceLocked(documentId)
        if (!current.exists() && !previous.exists() && !currentWasCorrupt && !previousWasCorrupt &&
            unacceptedStagingFiles.isEmpty() && !retainedSnapshotEvidence &&
            acceptedState == AcceptedSnapshotState.ABSENT
        ) {
            return DocumentLoadResult.NotFound
        }
        if (currentWasCorrupt || previousWasCorrupt) {
            ensureAcceptedSnapshotStateLocked(documentId, acceptedState)?.let { failure ->
                return DocumentLoadResult.Failed(failure)
            }
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
        val root = FileInputStream(file).use {
            com.example.myapplication.stage5.parseBoundedJsonObject(
                it,
                com.example.myapplication.stage5.Stage5Limits.MAX_JSON_BYTES,
                "local snapshot envelope"
            )
        }
        return readSnapshotRecord(root)
    }

    /** Parses already-admitted bytes without making another full file copy. */
    private fun readSnapshotBytes(bytes: ByteArray): SnapshotRecord =
        readSnapshotRecord(
            parseBoundedJsonObjectBytes(
                bytes,
                com.example.myapplication.stage5.Stage5Limits.MAX_JSON_BYTES,
                "local snapshot envelope"
            )
        )

    /**
     * Inspect version fields in the raw tree before Gson materializes the
     * Kotlin DTO. Gson may bypass constructors (and their init checks), so
     * this keeps an embedded retired snapshot schema an explicit format
     * rejection instead of allowing it to fall into corruption recovery.
     */
    private fun readSnapshotRecord(root: JsonObject): SnapshotRecord {
        require(root.isJsonObject) { "snapshot envelope root must be an object" }
        val rootObject = root.asJsonObject
        val storageElement = requireNotNull(rootObject.get("storageSchemaVersion")) {
            "snapshot storage schema missing"
        }
        require(storageElement.isJsonPrimitive && storageElement.asJsonPrimitive.isNumber) {
            "snapshot storage schema is not numeric"
        }
        val storageVersion = storageElement.asBigDecimal.intValueExact()
        if (storageVersion != LOCAL_DOCUMENT_STORAGE_SCHEMA_VERSION) {
            throw UnsupportedFormatSignal(
                format = "local snapshot envelope",
                actualVersion = storageVersion,
                expectedVersion = LOCAL_DOCUMENT_STORAGE_SCHEMA_VERSION,
                message = "unsupported snapshot storage schema: $storageVersion"
            )
        }
        val snapshotElement = requireNotNull(rootObject.get("snapshot")) {
            "snapshot payload missing"
        }
        require(snapshotElement.isJsonObject) { "snapshot payload must be an object" }
        val snapshotSchemaElement = requireNotNull(snapshotElement.asJsonObject.get("schemaVersion")) {
            "snapshot schema missing"
        }
        require(snapshotSchemaElement.isJsonPrimitive && snapshotSchemaElement.asJsonPrimitive.isNumber) {
            "snapshot schema is not numeric"
        }
        val snapshotSchema = snapshotSchemaElement.asBigDecimal.intValueExact()
        if (snapshotSchema != DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION) {
            throw UnsupportedFormatSignal(
                format = "canonical document snapshot",
                actualVersion = snapshotSchema,
                expectedVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
                message = "unsupported document snapshot schema: $snapshotSchema"
            )
        }
        require(rootObject.keySet().all { it in setOf("storageSchemaVersion", "documentId", "sourceFingerprint", "snapshot") }) { "unknown local snapshot envelope field" }
        com.example.myapplication.stage5.validateCanonicalSnapshotTree(snapshotElement.asJsonObject, "local snapshot")
        val envelope = gson.fromJson(root, SnapshotEnvelopeJson::class.java)
        val documentId = DocumentId.parse(requireNotNull(envelope.documentId))
        val snapshot = requireNotNull(envelope.snapshot) { "snapshot payload missing" }
        val fingerprint = envelope.sourceFingerprint?.let {
            SourceFingerprint(it.algorithm, it.digestHex, it.byteCount)
        }
        validateSnapshot(snapshot)
        return SnapshotRecord(documentId, snapshot, fingerprint)
    }

    private fun parseBoundedJsonObjectBytes(
        bytes: ByteArray,
        maxBytes: Int,
        label: String
    ): JsonObject {
        require(bytes.size <= maxBytes) { "$label exceeds $maxBytes bytes" }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("$label is not valid UTF-8", error)
        }
        com.example.myapplication.stage5.validateNoDuplicateJsonMembers(bytes, label)
        val root = try {
            JsonParser.parseString(text)
        } catch (error: Exception) {
            throw IllegalArgumentException("$label is malformed", error)
        }
        require(root.isJsonObject) { "$label root must be an object" }
        return root.asJsonObject
    }

    private fun readDurableSnapshotSlotLocked(
        file: File,
        association: DocumentAssociation
    ): DurableSnapshotSlot? {
        if (!file.exists()) return null
        require(file.isFile) { "durable snapshot slot is not a regular file" }
        val bytes = FileInputStream(file).use {
            com.example.myapplication.stage5.readBoundedBytes(
                it,
                com.example.myapplication.stage5.Stage5Limits.MAX_JSON_BYTES,
                "durable snapshot slot"
            )
        }
        val record = readSnapshotBytes(bytes)
        validateRecord(
            record,
            association.documentId,
            association.source.sourceUri,
            association.sourceFingerprint
        )
        return DurableSnapshotSlot(
            snapshot = record.snapshot,
            sourceFingerprint = record.sourceFingerprint,
            serializedBytes = bytes
        )
    }

    private fun unsupportedSnapshotSlotLocked(documentId: DocumentId): LocalRepositoryError.UnsupportedFormat? {
        listOf(
            currentSnapshotFile(documentId),
            previousSnapshotFile(documentId)
        ).forEach { file ->
            if (!file.exists()) return@forEach
            try {
                readSnapshotFile(file)
            } catch (unsupported: UnsupportedFormatSignal) {
                return unsupportedSnapshotError(file, unsupported)
            } catch (_: Exception) {
                // Existing corruption retains the established restore policy;
                // only a retired/future format is non-destructively blocked.
            }
        }
        return null
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
        ).toByteArray(Charsets.UTF_8).also {
            require(it.size <= com.example.myapplication.stage5.Stage5Limits.MAX_JSON_BYTES) {
                "durable snapshot slot exceeds its bounded JSON size"
            }
        }
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

    private enum class AcceptedSnapshotState {
        ABSENT,
        PENDING,
        ACCEPTED
    }

    private fun ensureAcceptedSnapshotStateLocked(
        documentId: DocumentId,
        currentState: AcceptedSnapshotState
    ): LocalRepositoryError? {
        if (currentState == AcceptedSnapshotState.ACCEPTED) return null
        if (currentState == AcceptedSnapshotState.PENDING) {
            return LocalRepositoryError.CommitUncertain(
                operation = "establish accepted snapshot state",
                path = acceptedSnapshotStateFile(documentId).path,
                detail = "accepted snapshot transition remains unresolved"
            )
        }
        return try {
            writeAcceptedSnapshotStateLocked(documentId, AcceptedSnapshotState.ACCEPTED)
            null
        } catch (unsupported: UnsupportedFormatSignal) {
            unsupportedSnapshotError(acceptedSnapshotStateFile(documentId), unsupported)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LocalRepositoryError.CommitUncertain(
                operation = "establish accepted snapshot state",
                path = acceptedSnapshotStateFile(documentId).path,
                detail = error.message
            )
        }
    }

    /**
     * Quarantined current/previous bytes are durable evidence that this id had
     * snapshot state, even if a process died before its acceptance journal was
     * published.  Staged candidates are deliberately excluded: a failed first
     * save never becomes an accepted document merely because its temp bytes
     * were retained for diagnostics.
     */
    private fun hasRetainedSnapshotEvidenceLocked(documentId: DocumentId): Boolean {
        val quarantine = snapshotQuarantineDirectory(documentId)
        if (!quarantine.exists()) return false
        if (!quarantine.isDirectory) {
            throw IOException("snapshot quarantine is not a directory: ${quarantine.path}")
        }
        val files = quarantine.listFiles()
            ?: throw IOException("snapshot quarantine could not be enumerated: ${quarantine.path}")
        // `interrupted-write` is the one staging label the repository itself
        // writes after a known pre-replacement failure and then explicitly
        // clears the acceptance journal for.  Temp bytes discovered during a
        // later load (`unaccepted-staging`) and acceptance-journal staging
        // failures remain unresolved evidence and must keep NotFound blocked.
        val knownCleanFailureLabels = setOf("interrupted-write")
        return files.any { file ->
            !file.isFile || knownCleanFailureLabels.none { label -> file.name.startsWith("$label-") }
        }
    }

    private fun readAcceptedSnapshotStateLocked(documentId: DocumentId): AcceptedSnapshotState {
        val file = acceptedSnapshotStateFile(documentId)
        if (!file.exists()) return AcceptedSnapshotState.ABSENT
        require(file.isFile) { "accepted snapshot state is not a regular file" }
        val root = FileInputStream(file).use {
            com.example.myapplication.stage5.parseBoundedJsonObject(
                it,
                com.example.myapplication.stage5.Stage5Limits.MAX_METADATA_BYTES,
                "accepted snapshot state"
            )
        }
        require(root.keySet() == setOf("schemaVersion", "documentId", "state")) {
            "accepted snapshot state contains unsupported fields"
        }
        val schemaElement = requireNotNull(root.get("schemaVersion")) {
            "accepted snapshot state schema is missing"
        }
        require(schemaElement.isJsonPrimitive && schemaElement.asJsonPrimitive.isNumber) {
            "accepted snapshot state schema is not numeric"
        }
        val schemaVersion = schemaElement.asBigDecimal.intValueExact()
        if (schemaVersion != SNAPSHOT_ACCEPTED_STATE_SCHEMA_VERSION) {
            throw UnsupportedFormatSignal(
                format = "accepted snapshot state",
                actualVersion = schemaVersion,
                expectedVersion = SNAPSHOT_ACCEPTED_STATE_SCHEMA_VERSION,
                message = "unsupported accepted snapshot state schema: $schemaVersion"
            )
        }
        val documentIdElement = requireNotNull(root.get("documentId")) {
            "accepted snapshot state document id is missing"
        }
        require(documentIdElement.isJsonPrimitive && documentIdElement.asJsonPrimitive.isString) {
            "accepted snapshot state document id is not a string"
        }
        require(documentIdElement.asString.length <= com.example.myapplication.stage5.Stage5Limits.MAX_ID_CHARS) {
            "accepted snapshot state document id exceeds its limit"
        }
        val actualDocumentId = DocumentId.parse(documentIdElement.asString)
        if (actualDocumentId != documentId) {
            throw RepositoryAssociationMismatchSignal(
                LocalRepositoryError.AssociationMismatch(
                    path = file.path,
                    expectedDocumentId = documentId,
                    actualDocumentId = actualDocumentId
                )
            )
        }
        val stateElement = requireNotNull(root.get("state")) {
            "accepted snapshot state value is missing"
        }
        require(stateElement.isJsonPrimitive && stateElement.asJsonPrimitive.isString) {
            "accepted snapshot state value is not a string"
        }
        return when (stateElement.asString) {
            SNAPSHOT_ACCEPTED_STATE_PENDING -> AcceptedSnapshotState.PENDING
            SNAPSHOT_ACCEPTED_STATE_ACCEPTED -> AcceptedSnapshotState.ACCEPTED
            else -> error("accepted snapshot state value is unsupported")
        }
    }

    private fun writeAcceptedSnapshotStateLocked(
        documentId: DocumentId,
        state: AcceptedSnapshotState
    ) {
        require(state != AcceptedSnapshotState.ABSENT) {
            "ABSENT is represented by removing the accepted snapshot state"
        }
        val directory = documentDirectory(documentId)
        require(directory.exists() || directory.mkdirs()) {
            "Unable to create ${directory.path}"
        }
        val target = acceptedSnapshotStateFile(documentId)
        val staging = File(directory, "snapshot.accepted.state.${UUID.randomUUID()}.tmp")
        try {
            val encoded = com.example.myapplication.stage5.encodeBoundedJson(
                gson,
                SnapshotAcceptedStateJson(
                    schemaVersion = SNAPSHOT_ACCEPTED_STATE_SCHEMA_VERSION,
                    documentId = documentId.value,
                    state = when (state) {
                        AcceptedSnapshotState.PENDING -> SNAPSHOT_ACCEPTED_STATE_PENDING
                        AcceptedSnapshotState.ACCEPTED -> SNAPSHOT_ACCEPTED_STATE_ACCEPTED
                        AcceptedSnapshotState.ABSENT -> error("unreachable")
                    }
                ),
                com.example.myapplication.stage5.Stage5Limits.MAX_METADATA_BYTES,
                "accepted snapshot state"
            )
            writeAndSync(staging, encoded)
            require(readAcceptedSnapshotStateFile(staging, documentId) == state) {
                "accepted snapshot state did not validate"
            }
            replaceAtomically(staging, target)
            require(readAcceptedSnapshotStateLocked(documentId) == state) {
                "accepted snapshot state read-back did not validate"
            }
        } finally {
            if (staging.exists()) {
                quarantineFile(staging, "accepted-state-staging")
            }
        }
    }

    private fun readAcceptedSnapshotStateFile(
        file: File,
        expectedDocumentId: DocumentId
    ): AcceptedSnapshotState {
        val root = FileInputStream(file).use {
            com.example.myapplication.stage5.parseBoundedJsonObject(
                it,
                com.example.myapplication.stage5.Stage5Limits.MAX_METADATA_BYTES,
                "accepted snapshot state"
            )
        }
        require(root.keySet() == setOf("schemaVersion", "documentId", "state")) {
            "accepted snapshot state contains unsupported fields"
        }
        val schemaElement = requireNotNull(root.get("schemaVersion"))
        require(schemaElement.isJsonPrimitive && schemaElement.asJsonPrimitive.isNumber) {
            "accepted snapshot state schema is not numeric"
        }
        val schema = schemaElement.asBigDecimal.intValueExact()
        if (schema != SNAPSHOT_ACCEPTED_STATE_SCHEMA_VERSION) {
            throw UnsupportedFormatSignal(
                format = "accepted snapshot state",
                actualVersion = schema,
                expectedVersion = SNAPSHOT_ACCEPTED_STATE_SCHEMA_VERSION,
                message = "unsupported accepted snapshot state schema: $schema"
            )
        }
        val documentIdElement = requireNotNull(root.get("documentId"))
        require(documentIdElement.isJsonPrimitive && documentIdElement.asJsonPrimitive.isString) {
            "accepted snapshot state document id is not a string"
        }
        require(documentIdElement.asString.length <= com.example.myapplication.stage5.Stage5Limits.MAX_ID_CHARS) {
            "accepted snapshot state document id exceeds its limit"
        }
        val actualId = DocumentId.parse(documentIdElement.asString)
        require(actualId == expectedDocumentId) {
            "accepted snapshot state document id changed"
        }
        val stateElement = requireNotNull(root.get("state"))
        require(stateElement.isJsonPrimitive && stateElement.asJsonPrimitive.isString) {
            "accepted snapshot state value is not a string"
        }
        return when (stateElement.asString) {
            SNAPSHOT_ACCEPTED_STATE_PENDING -> AcceptedSnapshotState.PENDING
            SNAPSHOT_ACCEPTED_STATE_ACCEPTED -> AcceptedSnapshotState.ACCEPTED
            else -> error("accepted snapshot state value is unsupported")
        }
    }

    private fun applyAcceptedSnapshotStateLocked(
        documentId: DocumentId,
        desired: AcceptedSnapshotState
    ) {
        when (desired) {
            AcceptedSnapshotState.ABSENT -> {
                Files.deleteIfExists(acceptedSnapshotStateFile(documentId).toPath())
            }
            AcceptedSnapshotState.PENDING,
            AcceptedSnapshotState.ACCEPTED -> writeAcceptedSnapshotStateLocked(documentId, desired)
        }
    }

    private fun clearAcceptanceJournalAfterKnownFailure(documentId: DocumentId): LocalRepositoryError? {
        try {
            Files.deleteIfExists(acceptedSnapshotStateFile(documentId).toPath())
            return null
        } catch (error: Exception) {
            return LocalRepositoryError.CommitUncertain(
                operation = "clear failed snapshot acceptance",
                path = acceptedSnapshotStateFile(documentId).path,
                detail = error.message
            )
        }
    }

    private fun retainAcceptanceJournalAfterKnownFailure(documentId: DocumentId): LocalRepositoryError? {
        return try {
            writeAcceptedSnapshotStateLocked(documentId, AcceptedSnapshotState.ACCEPTED)
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LocalRepositoryError.CommitUncertain(
                operation = "retain accepted snapshot after failed write",
                path = acceptedSnapshotStateFile(documentId).path,
                detail = error.message
            )
        }
    }

    /**
     * Replays a pending exact restore before any caller can observe the
     * current/previous pair.  The intent names the requested pair, so replay
     * is idempotent and safe after a process dies between slot replacements.
     */
    private fun recoverPendingSnapshotRestoreLocked(documentId: DocumentId): LocalRepositoryError? {
        val intentFile = snapshotRestoreIntentFile(documentId)
        if (!intentFile.exists()) return null
        return try {
            val acceptedState = readAcceptedSnapshotStateLocked(documentId)
            if (acceptedState == AcceptedSnapshotState.PENDING) {
                LocalRepositoryError.CommitUncertain(
                    operation = "recover durable snapshot restore",
                    path = acceptedSnapshotStateFile(documentId).path,
                    detail = "accepted snapshot transition remains unresolved"
                )
            } else {
                val intent = readSnapshotRestoreIntent(intentFile, documentId)
                applySnapshotRestoreIntentLocked(documentId, intent)
                clearSnapshotRestoreIntentLocked(intent)
                null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unsupported: UnsupportedFormatSignal) {
            unsupportedSnapshotError(intentFile, unsupported)
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
            writeAndSync(
                staging,
                com.example.myapplication.stage5.encodeBoundedJson(
                    gson,
                    intent,
                    com.example.myapplication.stage5.Stage5Limits.MAX_METADATA_BYTES,
                    "durable snapshot restore intent"
                )
            )
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
        require(file.isFile) { "durable snapshot restore intent is not a regular file" }
        val root = FileInputStream(file).use {
            com.example.myapplication.stage5.parseBoundedJsonObject(
                it,
                com.example.myapplication.stage5.Stage5Limits.MAX_METADATA_BYTES,
                "durable snapshot restore intent"
            )
        }
        val allowed = setOf("schemaVersion", "documentId", "currentPayload", "previousPayload", "accepted")
        require(root.keySet().all { it in allowed }) {
            "durable snapshot restore intent contains an unsupported field"
        }
        val schemaElement = requireNotNull(root.get("schemaVersion")) {
            "durable snapshot restore intent schema is missing"
        }
        require(schemaElement.isJsonPrimitive && schemaElement.asJsonPrimitive.isNumber) {
            "durable snapshot restore intent schema is not numeric"
        }
        val schemaVersion = schemaElement.asBigDecimal.intValueExact()
        if (schemaVersion != SNAPSHOT_RESTORE_INTENT_SCHEMA_VERSION) {
            throw UnsupportedFormatSignal(
                format = "durable snapshot restore intent",
                actualVersion = schemaVersion,
                expectedVersion = SNAPSHOT_RESTORE_INTENT_SCHEMA_VERSION,
                message = "unsupported durable snapshot restore intent schema: $schemaVersion"
            )
        }
        val documentIdElement = requireNotNull(root.get("documentId")) {
            "durable snapshot restore intent document id is missing"
        }
        require(documentIdElement.isJsonPrimitive && documentIdElement.asJsonPrimitive.isString) {
            "durable snapshot restore intent document id is not a string"
        }
        val documentIdValue = documentIdElement.asString
        require(documentIdValue.length <= com.example.myapplication.stage5.Stage5Limits.MAX_ID_CHARS) {
            "durable snapshot restore intent document id exceeds its limit"
        }
        val acceptedElement = requireNotNull(root.get("accepted")) {
            "durable snapshot restore intent accepted state is missing"
        }
        require(acceptedElement.isJsonPrimitive && acceptedElement.asJsonPrimitive.isBoolean) {
            "durable snapshot restore intent accepted state is not boolean"
        }
        fun optionalPayload(name: String): String? {
            val element = root.get(name) ?: return null
            if (element.isJsonNull) return null
            require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                "durable snapshot restore intent $name is not a string"
            }
            val value = element.asString
            require(value.length <= com.example.myapplication.stage5.Stage5Limits.MAX_STRING_CHARS) {
                "durable snapshot restore intent $name exceeds its limit"
            }
            return value
        }
        val intent = requireNotNull(gson.fromJson(root, SnapshotRestoreIntentJson::class.java)) {
            "durable snapshot restore intent missing"
        }
        require(intent.schemaVersion == SNAPSHOT_RESTORE_INTENT_SCHEMA_VERSION)
        require(intent.documentId == documentIdValue)
        require(intent.accepted != null)
        val documentId = DocumentId.parse(documentIdValue)
        require(documentId == expectedDocumentId) {
            "durable snapshot restore intent document id changed"
        }
        val currentPayload = optionalPayload("currentPayload")
        val previousPayload = optionalPayload("previousPayload")
        validateSnapshotRestorePayloadName(currentPayload)
        validateSnapshotRestorePayloadName(previousPayload)
        return intent.copy(
            documentId = documentId.value,
            currentPayload = currentPayload,
            previousPayload = previousPayload,
            accepted = acceptedElement.asBoolean
        )
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
        applyAcceptedSnapshotStateLocked(
            documentId,
            if (intent.accepted == true) AcceptedSnapshotState.ACCEPTED else AcceptedSnapshotState.ABSENT
        )
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
            // The intent payload is durable recovery input too. Recheck its
            // bounded envelope before copying so a modified or unexpectedly
            // grown payload cannot turn replay into an unbounded read.
            val record = readSnapshotFile(payload)
            require(record.documentId == documentId) {
                "durable snapshot restore payload document id changed"
            }
            copyAndSyncBounded(
                source = payload,
                destination = replacement,
                maxBytes = com.example.myapplication.stage5.Stage5Limits.MAX_JSON_BYTES,
                label = "durable snapshot restore payload"
            )
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
        return filesEqualExactly(file, payload)
    }

    private fun filesEqualExactly(first: File, second: File): Boolean {
        if (!first.isFile || !second.isFile || first.length() != second.length()) return false
        val firstBuffer = ByteArray(64 * 1024)
        val secondBuffer = ByteArray(64 * 1024)
        FileInputStream(first).use { firstInput ->
            FileInputStream(second).use { secondInput ->
                while (true) {
                    val firstRead = readChunk(firstInput, firstBuffer)
                    val secondRead = readChunk(secondInput, secondBuffer)
                    if (firstRead != secondRead) return false
                    if (firstRead < 0) return true
                    for (index in 0 until firstRead) {
                        if (firstBuffer[index] != secondBuffer[index]) return false
                    }
                }
            }
        }
    }

    private fun readChunk(input: FileInputStream, buffer: ByteArray): Int {
        while (true) {
            val read = input.read(buffer)
            if (read != 0) return read
        }
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

    /** One current validator shared by local, bundle, and remote state. */
    private fun validateSnapshot(snapshot: DocumentSnapshotV1) =
        com.example.myapplication.stage5.validateSnapshot(snapshot)

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

    private fun copyAndSyncBounded(
        source: File,
        destination: File,
        maxBytes: Int,
        label: String
    ) {
        require(source.isFile) { "$label is not a regular file" }
        require(source.length() <= maxBytes.toLong()) { "$label exceeds $maxBytes bytes" }
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read.toLong()
                    require(total <= maxBytes.toLong()) { "$label exceeds $maxBytes bytes" }
                    output.write(buffer, 0, read)
                }
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

    /** Runs synchronous repository reads on the injected IO dispatcher. */
    internal suspend fun <T> runOnIo(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

    private fun unsupportedManifestError(
        file: File,
        signal: UnsupportedFormatSignal
    ): LocalRepositoryError.UnsupportedFormat = LocalRepositoryError.UnsupportedFormat(
        path = file.path,
        format = signal.format,
        actualVersion = signal.actualVersion,
        expectedVersion = signal.expectedVersion,
        detail = signal.message
    )

    private fun unsupportedSnapshotError(
        file: File,
        signal: UnsupportedFormatSignal
    ): LocalRepositoryError.UnsupportedFormat = LocalRepositoryError.UnsupportedFormat(
        path = file.path,
        format = signal.format,
        actualVersion = signal.actualVersion,
        expectedVersion = signal.expectedVersion,
        detail = signal.message
    )

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
        val sourceFingerprint: SourceFingerprint?
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
        val previousPayload: String?,
        val accepted: Boolean?
    )

    private data class SnapshotAcceptedStateJson(
        val schemaVersion: Int?,
        val documentId: String?,
        val state: String?
    )
}
