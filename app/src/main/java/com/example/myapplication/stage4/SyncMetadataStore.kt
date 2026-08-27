package com.example.myapplication.stage4

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.ByteArrayInputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.decodeBoundedBase64
import com.example.myapplication.stage5.decodeValidatedSnapshotJson
import com.example.myapplication.stage5.encodeBoundedBase64
import com.example.myapplication.stage5.encodeBoundedJson
import com.example.myapplication.stage5.requireBoundedString
import com.example.myapplication.stage5.validatePhotoFileName
import com.example.myapplication.stage5.validateSourceFingerprintProperty
import com.example.myapplication.stage5.parseBoundedJsonObject
import com.example.myapplication.stage5.sha256Hex
import com.example.myapplication.stage5.validateSyncMetadataTree

const val SYNC_METADATA_SCHEMA_VERSION: Int = 1

/**
 * Complete local work that must survive conflict acceptance and coordinator
 * recreation.  This is a sidecar of the scoped metadata record, not a change
 * to the strict Stage 2 document manifest.
 */
data class DurablePendingUpload(
    val reason: SyncReason,
    val sourceUri: String,
    val sourceFingerprint: SourceFingerprint?,
    val generation: Long,
    val expectedCursor: RemoteCursor?,
    val snapshot: DocumentSnapshotV1,
    val photoFiles: Map<String, ByteArray>
) {
    init {
        require(reason != SyncReason.REMOTE_CHECK && reason != SyncReason.REMOTE_ACCEPTANCE) {
            "remote-only requests cannot become pending local uploads"
        }
        requireBoundedString(sourceUri, "pending upload source URI", required = true)
        require(generation > 0L) { "pending upload generation must be positive" }
        require(snapshot.source.sourceUri == sourceUri) {
            "pending upload source does not match its canonical snapshot"
        }
        requireValidSnapshot(snapshot)
        validatedPhotoFiles(snapshot, photoFiles)
    }
}

/** Durable synchronization state scoped by account, root, and DocumentId. */
data class SyncMetadata(
    val schemaVersion: Int = SYNC_METADATA_SCHEMA_VERSION,
    val scope: SyncScope,
    val remoteReference: RemoteReference? = null,
    val acceptedCursor: RemoteCursor? = null,
    val conflictCursor: RemoteCursor? = null,
    val conflictDetail: String? = null,
    /** Remote device-local identity retained for auditability after linking. */
    val adoptedRemoteDocumentId: DocumentId? = null,
    /** Candidate exposed by a read/check and awaiting explicit user action. */
    val pendingAdoption: RemoteAdoptionCandidate? = null,
    /** Frozen complete local work retained across conflict/recreation. */
    val pendingUpload: DurablePendingUpload? = null
) {
    init {
        require(schemaVersion == SYNC_METADATA_SCHEMA_VERSION) { "unsupported sync metadata schema" }
        if (conflictCursor == null) require(conflictDetail == null) {
            "conflict detail cannot exist without a conflict cursor"
        }
        remoteReference?.let {
            require(it.appProperties[SYNC_DOCUMENT_ID_APP_PROPERTY] == scope.documentId.value) {
                "remote reference DocumentId does not match metadata scope"
            }
        }
        pendingAdoption?.let { candidate ->
            require(candidate.accountId == scope.accountId) { "pending adoption account mismatch" }
            require(candidate.backupRootId == scope.backupRootId) { "pending adoption root mismatch" }
        }
        pendingUpload?.let { pending ->
            require(pending.snapshot.source.sourceUri == pending.sourceUri) {
                "pending upload source identity mismatch"
            }
        }
    }
}

sealed class MetadataReadResult {
    data class Loaded(val metadata: SyncMetadata?) : MetadataReadResult()
    data class Failed(val error: SyncMetadataError) : MetadataReadResult()
}

sealed class MetadataWriteResult {
    data object Committed : MetadataWriteResult()
    data class Failed(val error: SyncMetadataError) : MetadataWriteResult()
}

sealed class SyncMetadataError {
    data class Io(val operation: String, val path: String?, val detail: String?, val cause: Throwable? = null) : SyncMetadataError()
    data class Corrupt(val path: String, val detail: String?, val cause: Throwable? = null) : SyncMetadataError()
    data class CommitUncertain(val path: String, val detail: String?, val cause: Throwable? = null) : SyncMetadataError()
    data class Injected(val operation: String, val detail: String) : SyncMetadataError()
}

interface SyncMetadataStore {
    suspend fun read(scope: SyncScope): MetadataReadResult

    suspend fun write(metadata: SyncMetadata): MetadataWriteResult

    /**
     * Stable identity of the complete metadata content used by cross-store
     * rollback evidence. File-backed stores override this with the exact
     * bounded bytes they publish; compatibility stores retain a deterministic
     * structural fallback.
     */
    fun recoveryIdentity(metadata: SyncMetadata): String =
        defaultSyncMetadataRecoveryIdentity(metadata)
}

/**
 * File-backed metadata authority. Each scope has a hashed filename, so account
 * and Drive IDs cannot become paths. Writes are staged, fsynced, and atomically
 * replaced; there is no SharedPreferences/apply path for accepted cursors.
 */
class FileSyncMetadataStore(
    private val rootDirectory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SyncMetadataStore {
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(File(context.filesDir, "sync_metadata"), ioDispatcher)

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val locks = ConcurrentHashMap<String, Mutex>()

    override suspend fun read(scope: SyncScope): MetadataReadResult = withContext(ioDispatcher) {
        val target = metadataFile(scope)
        lockFor(scope).withLock {
            try {
                if (!target.exists()) return@withLock MetadataReadResult.Loaded(null)
                val raw = target.inputStream().use {
                    parseBoundedJsonObject(it, Stage5Limits.MAX_METADATA_BYTES, "sync metadata")
                }
                validateSyncMetadataTree(raw)
                val json = gson.fromJson(
                    raw,
                    MetadataJson::class.java
                )
                    ?: return@withLock MetadataReadResult.Failed(
                        SyncMetadataError.Corrupt(target.path, "metadata payload missing")
                    )
                MetadataReadResult.Loaded(json.toMetadata(scope, target, gson))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                MetadataReadResult.Failed(SyncMetadataError.Corrupt(target.path, error.message, error))
            } catch (error: JsonParseException) {
                MetadataReadResult.Failed(SyncMetadataError.Corrupt(target.path, error.message, error))
            } catch (error: IllegalStateException) {
                MetadataReadResult.Failed(SyncMetadataError.Corrupt(target.path, error.message, error))
            } catch (error: IOException) {
                MetadataReadResult.Failed(SyncMetadataError.Io("read metadata", target.path, error.message, error))
            } catch (error: SecurityException) {
                MetadataReadResult.Failed(SyncMetadataError.Io("read metadata", target.path, error.message, error))
            }
        }
    }

    override suspend fun write(metadata: SyncMetadata): MetadataWriteResult = withContext(ioDispatcher) {
        val target = metadataFile(metadata.scope)
        lockFor(metadata.scope).withLock {
            val staging = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
            try {
                val frozen = freezeSyncMetadata(metadata)
                validateMetadataForWrite(frozen)
                if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
                    throw IOException("unable to create ${rootDirectory.path}")
                }
                val bytes = encodeBoundedJson(
                    gson,
                    MetadataJson.from(frozen, gson),
                    Stage5Limits.MAX_METADATA_BYTES,
                    "sync metadata"
                )
                // Validate the exact bytes that are about to become durable so
                // the writer and reader share one strict boundary.
                val encodedTree = parseBoundedJsonObject(
                    ByteArrayInputStream(bytes),
                    Stage5Limits.MAX_METADATA_BYTES,
                    "sync metadata"
                )
                validateSyncMetadataTree(encodedTree)
                FileOutputStream(staging).use { output ->
                    output.write(bytes)
                    output.flush()
                    output.fd.sync()
                }
                try {
                    Files.move(
                        staging.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (unsupported: AtomicMoveNotSupportedException) {
                    throw IOException("atomic metadata replacement is unavailable", unsupported)
                }
                // Read the published bytes back through the same raw and
                // typed validators before reporting durable success. This
                // keeps a successful write honest even if the filesystem
                // boundary altered or exposed a different file than staging.
                val durableTree = target.inputStream().use {
                    parseBoundedJsonObject(it, Stage5Limits.MAX_METADATA_BYTES, "sync metadata read-back")
                }
                validateSyncMetadataTree(durableTree)
                val durableJson = gson.fromJson(durableTree, MetadataJson::class.java)
                    ?: throw IOException("published metadata read-back is empty")
                durableJson.toMetadata(metadata.scope, target, gson)
                MetadataWriteResult.Committed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AtomicMoveNotSupportedException) {
                MetadataWriteResult.Failed(SyncMetadataError.CommitUncertain(target.path, error.message, error))
            } catch (error: IOException) {
                MetadataWriteResult.Failed(SyncMetadataError.CommitUncertain(target.path, error.message, error))
            } catch (error: IllegalArgumentException) {
                MetadataWriteResult.Failed(SyncMetadataError.Io("write metadata", target.path, error.message, error))
            } catch (error: JsonParseException) {
                MetadataWriteResult.Failed(SyncMetadataError.Io("write metadata", target.path, error.message, error))
            } catch (error: IllegalStateException) {
                MetadataWriteResult.Failed(SyncMetadataError.Io("write metadata", target.path, error.message, error))
            } catch (error: SecurityException) {
                MetadataWriteResult.Failed(SyncMetadataError.Io("write metadata", target.path, error.message, error))
            } finally {
                if (staging.exists()) staging.delete()
            }
        }
    }

    fun metadataFileFor(scope: SyncScope): File = metadataFile(scope)

    override fun recoveryIdentity(metadata: SyncMetadata): String {
        val frozen = freezeSyncMetadata(metadata)
        validateMetadataForWrite(frozen)
        val bytes = encodeBoundedJson(
            gson,
            MetadataJson.from(frozen, gson),
            Stage5Limits.MAX_METADATA_BYTES,
            "sync metadata recovery identity"
        )
        return sha256Hex(bytes)
    }

    private fun lockFor(scope: SyncScope): Mutex =
        locks.computeIfAbsent(scopeKey(scope)) { Mutex() }

    private fun metadataFile(scope: SyncScope): File {
        val directory = rootDirectory
        val name = "${sha256(scopeKey(scope))}.json"
        return File(directory, name)
    }

    private fun validateMetadataForWrite(metadata: SyncMetadata) {
        require(metadata.schemaVersion == SYNC_METADATA_SCHEMA_VERSION) { "unsupported metadata schema" }
        requireBoundedString(metadata.scope.accountId, "metadata account", required = true)
        requireBoundedString(metadata.scope.backupRootId, "metadata root", required = true)
        requireBoundedString(metadata.scope.documentId.value, "metadata document", required = true, maxChars = Stage5Limits.MAX_ID_CHARS)
        metadata.remoteReference?.let { reference ->
            requireBoundedString(reference.folderId, "metadata remote folder", required = true)
            requireBoundedString(reference.snapshotFileId, "metadata remote snapshot", required = true)
            validateMetadataProperties(reference.appProperties, "metadata remote properties", metadata.scope.documentId.value)
        }
        validateRemoteCursor(metadata.acceptedCursor, "metadata accepted cursor")
        validateRemoteCursor(metadata.conflictCursor, "metadata conflict cursor")
        requireBoundedString(metadata.conflictDetail, "metadata conflict detail", maxChars = Stage5Limits.MAX_TEXT_CHARS)
        if (metadata.conflictCursor == null) require(metadata.conflictDetail == null) {
            "metadata conflict detail cannot exist without a conflict cursor"
        }
        metadata.adoptedRemoteDocumentId?.let {
            requireBoundedString(it.value, "metadata adopted document", required = true, maxChars = Stage5Limits.MAX_ID_CHARS)
        }
        metadata.pendingAdoption?.let { candidate ->
            requireBoundedString(candidate.accountId, "pending adoption account", required = true)
            requireBoundedString(candidate.backupRootId, "pending adoption root", required = true)
            requireBoundedString(candidate.remoteDocumentId.value, "pending adoption document", required = true, maxChars = Stage5Limits.MAX_ID_CHARS)
            validateSourceFingerprintProperty(candidate.sourceFingerprint.toDriveProperty(), "pending adoption fingerprint")
            requireBoundedString(candidate.displayName, "pending adoption display name", required = true)
            requireBoundedString(candidate.reference.folderId, "pending adoption folder", required = true)
            requireBoundedString(candidate.reference.snapshotFileId, "pending adoption snapshot", required = true)
            validateMetadataProperties(candidate.reference.appProperties, "pending adoption properties", candidate.remoteDocumentId.value)
            validateRemoteCursor(candidate.cursor, "pending adoption cursor")
        }
        metadata.pendingUpload?.let { pending ->
            requireBoundedString(pending.sourceUri, "pending upload source URI", required = true)
            pending.sourceFingerprint?.let {
                validateSourceFingerprintProperty(it.toDriveProperty(), "pending upload fingerprint")
            }
            validateRemoteCursor(pending.expectedCursor, "pending upload expected cursor")
            requireValidSnapshot(pending.snapshot)
            // The constructor validation is repeated against the frozen graph
            // immediately before encoding; this rejects mutable byte/map edits.
            validatedPhotoFiles(pending.snapshot, pending.photoFiles)
        }
    }

    private fun validateRemoteCursor(cursor: RemoteCursor?, label: String) {
        cursor ?: return
        requireBoundedString(cursor.revision, "$label revision", required = true)
        require(cursor.modifiedTimeMillis == null || cursor.modifiedTimeMillis >= 0L) {
            "$label modified time is invalid"
        }
    }

    private fun validateMetadataProperties(properties: Map<String, String>, label: String, requiredDocumentId: String) {
        require(properties.size <= Stage5Limits.MAX_REMOTE_PROPERTIES) { "$label exceeds its entry limit" }
        properties.forEach { (key, value) ->
            requireBoundedString(key, "$label key", required = true)
            requireBoundedString(value, "$label value", required = true)
        }
        require(properties[SYNC_DOCUMENT_ID_APP_PROPERTY] == requiredDocumentId) {
            "$label is missing its document identity property"
        }
    }

    private fun freezeSyncMetadata(metadata: SyncMetadata): SyncMetadata {
        val frozenPendingAdoption = metadata.pendingAdoption?.let { candidate ->
            candidate.copy(
                sourceFingerprint = candidate.sourceFingerprint.copy(),
                reference = candidate.reference.copy(appProperties = LinkedHashMap(candidate.reference.appProperties)),
                cursor = candidate.cursor.copy()
            )
        }
        val frozenPendingUpload = metadata.pendingUpload?.let { pending ->
            DurablePendingUpload(
                reason = pending.reason,
                sourceUri = pending.sourceUri,
                sourceFingerprint = pending.sourceFingerprint?.copy(),
                generation = pending.generation,
                expectedCursor = pending.expectedCursor?.copy(),
                snapshot = freezeSnapshot(pending.snapshot),
                photoFiles = pending.photoFiles.mapValues { (_, bytes) -> bytes.copyOf() }.let(::LinkedHashMap)
            )
        }
        return SyncMetadata(
            schemaVersion = metadata.schemaVersion,
            scope = metadata.scope.copy(),
            remoteReference = metadata.remoteReference?.copy(
                appProperties = LinkedHashMap(metadata.remoteReference.appProperties)
            ),
            acceptedCursor = metadata.acceptedCursor?.copy(),
            conflictCursor = metadata.conflictCursor?.copy(),
            conflictDetail = metadata.conflictDetail,
            adoptedRemoteDocumentId = metadata.adoptedRemoteDocumentId,
            pendingAdoption = frozenPendingAdoption,
            pendingUpload = frozenPendingUpload
        )
    }

    private fun freezeSnapshot(snapshot: DocumentSnapshotV1): DocumentSnapshotV1 = snapshot.copy(
        source = snapshot.source.copy(providerMetadata = LinkedHashMap(snapshot.source.providerMetadata)),
        pages = snapshot.pages.mapValues { (_, page) ->
            page.copy(
                paths = page.paths.map { path -> path.copy(points = path.points.map { it.copy() }) },
                measurements = page.measurements.map { measurement ->
                    measurement.copy(p1 = measurement.p1.copy(), p2 = measurement.p2.copy())
                },
                notes = page.notes.map { it.copy() },
                photoPins = page.photoPins.map { pin ->
                    pin.copy(
                        imageFileNames = pin.imageFileNames.toList(),
                        imageNotes = pin.imageNotes.mapValues { (_, notes) -> notes.map { it.copy() } },
                        imageShapes = pin.imageShapes.mapValues { (_, shapes) -> shapes.map { shape ->
                            shape.copy()
                        } }
                    )
                },
                scale = page.scale?.copy(),
                shapes = page.shapes.map { it.copy() }
            )
        }
    )

    private fun scopeKey(scope: SyncScope): String =
        "${scope.accountId}\u0000${scope.backupRootId}\u0000${scope.documentId.value}"

    private data class MetadataJson(
        val schemaVersion: Int?,
        val accountId: String?,
        val backupRootId: String?,
        val documentId: String?,
        val remoteFolderId: String?,
        val remoteSnapshotFileId: String?,
        val remoteAppProperties: Map<String, String>?,
        val acceptedRevision: String?,
        val acceptedModifiedTimeMillis: Long?,
        val conflictRevision: String?,
        val conflictModifiedTimeMillis: Long?,
        val conflictDetail: String?,
        val adoptedRemoteDocumentId: String?,
        val pendingAdoptionRemoteDocumentId: String?,
        val pendingAdoptionSourceFingerprint: String?,
        val pendingAdoptionDisplayName: String?,
        val pendingAdoptionFolderId: String?,
        val pendingAdoptionSnapshotFileId: String?,
        val pendingAdoptionAppProperties: Map<String, String>?,
        val pendingAdoptionRevision: String?,
        val pendingAdoptionModifiedTimeMillis: Long?,
        val pendingUploadReason: String?,
        val pendingUploadSourceUri: String?,
        val pendingUploadSourceFingerprint: String?,
        val pendingUploadGeneration: Long?,
        val pendingUploadExpectedRevision: String?,
        val pendingUploadExpectedModifiedTimeMillis: Long?,
        val pendingUploadSnapshotJson: String?,
        val pendingUploadPhotoFiles: Map<String, String>?
    ) {
        fun toMetadata(scope: SyncScope, file: File, gson: Gson): SyncMetadata {
            require(schemaVersion == SYNC_METADATA_SCHEMA_VERSION) { "unsupported metadata schema" }
            require(accountId == scope.accountId && backupRootId == scope.backupRootId && documentId == scope.documentId.value) {
                "metadata scope mismatch"
            }
            requireBoundedString(accountId, "metadata account", required = true)
            requireBoundedString(backupRootId, "metadata root", required = true)
            requireBoundedString(documentId, "metadata document", required = true)
            requireBoundedString(remoteFolderId, "metadata remote folder")
            requireBoundedString(remoteSnapshotFileId, "metadata remote snapshot")
            requireBoundedString(acceptedRevision, "metadata accepted revision")
            requireBoundedString(conflictRevision, "metadata conflict revision")
            requireBoundedString(conflictDetail, "metadata conflict detail", maxChars = Stage5Limits.MAX_TEXT_CHARS)
            requireBoundedString(adoptedRemoteDocumentId, "metadata adopted document")
            requireBoundedString(pendingAdoptionRemoteDocumentId, "pending adoption document")
            requireBoundedString(pendingAdoptionSourceFingerprint, "pending adoption fingerprint")
            requireBoundedString(pendingAdoptionDisplayName, "pending adoption display name")
            requireBoundedString(pendingAdoptionFolderId, "pending adoption folder")
            requireBoundedString(pendingAdoptionSnapshotFileId, "pending adoption snapshot")
            requireBoundedString(pendingAdoptionRevision, "pending adoption revision")
            requireBoundedString(pendingUploadReason, "pending upload reason")
            requireBoundedString(pendingUploadSourceUri, "pending upload source URI")
            requireBoundedString(pendingUploadSourceFingerprint, "pending upload fingerprint")
            validateSourceFingerprintProperty(pendingAdoptionSourceFingerprint, "pending adoption fingerprint")
            validateSourceFingerprintProperty(pendingUploadSourceFingerprint, "pending upload fingerprint")
            require(remoteAppProperties.orEmpty().size <= Stage5Limits.MAX_REMOTE_PROPERTIES)
            remoteAppProperties.orEmpty().forEach { (key, value) ->
                require(key.length <= Stage5Limits.MAX_STRING_CHARS && value.length <= Stage5Limits.MAX_STRING_CHARS)
            }
            val remoteReference = if (remoteFolderId == null && remoteSnapshotFileId == null) {
                null
            } else {
                require(!remoteFolderId.isNullOrBlank() && !remoteSnapshotFileId.isNullOrBlank()) {
                    "incomplete remote reference"
                }
                val properties = LinkedHashMap(remoteAppProperties.orEmpty())
                require(properties[SYNC_DOCUMENT_ID_APP_PROPERTY] == scope.documentId.value) {
                    "remote reference DocumentId property mismatch"
                }
                RemoteReference(remoteFolderId, remoteSnapshotFileId, properties)
            }
            val accepted = acceptedRevision?.let { RemoteCursor(it, acceptedModifiedTimeMillis) }
            val conflict = conflictRevision?.let { RemoteCursor(it, conflictModifiedTimeMillis) }
            val pendingAdoption = if (pendingAdoptionRemoteDocumentId == null) {
                null
            } else {
                val sourceFingerprint = sourceFingerprintFromDriveProperty(pendingAdoptionSourceFingerprint)
                    ?: throw IllegalArgumentException("pending adoption source fingerprint missing")
                val folderId = pendingAdoptionFolderId
                    ?: throw IllegalArgumentException("pending adoption folder id missing")
                val fileId = pendingAdoptionSnapshotFileId
                    ?: throw IllegalArgumentException("pending adoption file id missing")
                val revision = pendingAdoptionRevision
                    ?: throw IllegalArgumentException("pending adoption revision missing")
                val remoteDocumentId = DocumentId.parse(pendingAdoptionRemoteDocumentId)
                val properties = LinkedHashMap(pendingAdoptionAppProperties.orEmpty())
                RemoteAdoptionCandidate(
                    accountId = scope.accountId,
                    backupRootId = scope.backupRootId,
                    remoteDocumentId = remoteDocumentId,
                    sourceFingerprint = sourceFingerprint,
                    displayName = pendingAdoptionDisplayName.orEmpty(),
                    reference = RemoteReference(folderId, fileId, properties),
                    cursor = RemoteCursor(revision, pendingAdoptionModifiedTimeMillis)
                )
            }
            val pendingUpload = if (pendingUploadReason == null &&
                pendingUploadSourceUri == null &&
                pendingUploadGeneration == null &&
                pendingUploadSnapshotJson == null &&
                pendingUploadPhotoFiles == null
            ) {
                null
            } else {
                val reason = pendingUploadReason?.let {
                    runCatching { SyncReason.valueOf(it) }.getOrNull()
                } ?: throw IllegalArgumentException("pending upload reason missing or invalid")
                val sourceUri = pendingUploadSourceUri
                    ?: throw IllegalArgumentException("pending upload source URI missing")
                val generation = pendingUploadGeneration
                    ?: throw IllegalArgumentException("pending upload generation missing")
                val snapshotJson = pendingUploadSnapshotJson
                    ?: throw IllegalArgumentException("pending upload snapshot missing")
                require(snapshotJson.toByteArray(Charsets.UTF_8).size <= Stage5Limits.MAX_JSON_BYTES) {
                    "pending upload snapshot exceeds JSON limit"
                }
                val snapshot = decodeValidatedSnapshotJson(
                    gson,
                    snapshotJson,
                    "pending upload snapshot"
                )
                val encodedPhotos = pendingUploadPhotoFiles.orEmpty()
                require(encodedPhotos.size <= Stage5Limits.MAX_TOTAL_PHOTOS) {
                    "pending upload photo count exceeds limit"
                }
                val photoFiles = encodedPhotos.mapValues { (name, encoded) ->
                    validatePhotoFileName(name)
                    decodeBoundedBase64(encoded, "pending upload photo: $name")
                }
                val pendingFingerprint = pendingUploadSourceFingerprint?.let {
                    sourceFingerprintFromDriveProperty(it)
                        ?: throw IllegalArgumentException("pending upload source fingerprint is invalid")
                }
                DurablePendingUpload(
                    reason = reason,
                    sourceUri = sourceUri,
                    sourceFingerprint = pendingFingerprint,
                    generation = generation,
                    expectedCursor = pendingUploadExpectedRevision?.let {
                        RemoteCursor(it, pendingUploadExpectedModifiedTimeMillis)
                    },
                    snapshot = snapshot,
                    photoFiles = photoFiles
                )
            }
            return SyncMetadata(
                schemaVersion = schemaVersion,
                scope = scope,
                remoteReference = remoteReference,
                acceptedCursor = accepted,
                conflictCursor = conflict,
                conflictDetail = conflictDetail,
                adoptedRemoteDocumentId = adoptedRemoteDocumentId?.let(DocumentId::parse),
                pendingAdoption = pendingAdoption,
                pendingUpload = pendingUpload
            )
        }

        companion object {
            fun from(metadata: SyncMetadata, gson: Gson): MetadataJson = MetadataJson(
                schemaVersion = metadata.schemaVersion,
                accountId = metadata.scope.accountId,
                backupRootId = metadata.scope.backupRootId,
                documentId = metadata.scope.documentId.value,
                remoteFolderId = metadata.remoteReference?.folderId,
                remoteSnapshotFileId = metadata.remoteReference?.snapshotFileId,
                remoteAppProperties = metadata.remoteReference?.appProperties,
                acceptedRevision = metadata.acceptedCursor?.revision,
                acceptedModifiedTimeMillis = metadata.acceptedCursor?.modifiedTimeMillis,
                conflictRevision = metadata.conflictCursor?.revision,
                conflictModifiedTimeMillis = metadata.conflictCursor?.modifiedTimeMillis,
                conflictDetail = metadata.conflictDetail,
                adoptedRemoteDocumentId = metadata.adoptedRemoteDocumentId?.value,
                pendingAdoptionRemoteDocumentId = metadata.pendingAdoption?.remoteDocumentId?.value,
                pendingAdoptionSourceFingerprint = metadata.pendingAdoption?.sourceFingerprint?.toDriveProperty(),
                pendingAdoptionDisplayName = metadata.pendingAdoption?.displayName,
                pendingAdoptionFolderId = metadata.pendingAdoption?.reference?.folderId,
                pendingAdoptionSnapshotFileId = metadata.pendingAdoption?.reference?.snapshotFileId,
                pendingAdoptionAppProperties = metadata.pendingAdoption?.reference?.appProperties,
                pendingAdoptionRevision = metadata.pendingAdoption?.cursor?.revision,
                pendingAdoptionModifiedTimeMillis = metadata.pendingAdoption?.cursor?.modifiedTimeMillis,
                pendingUploadReason = metadata.pendingUpload?.reason?.name,
                pendingUploadSourceUri = metadata.pendingUpload?.sourceUri,
                pendingUploadSourceFingerprint = metadata.pendingUpload?.sourceFingerprint?.toDriveProperty(),
                pendingUploadGeneration = metadata.pendingUpload?.generation,
                pendingUploadExpectedRevision = metadata.pendingUpload?.expectedCursor?.revision,
                pendingUploadExpectedModifiedTimeMillis = metadata.pendingUpload?.expectedCursor?.modifiedTimeMillis,
                pendingUploadSnapshotJson = metadata.pendingUpload?.let {
                    String(
                        encodeBoundedJson(
                            gson,
                            it.snapshot,
                            Stage5Limits.MAX_JSON_BYTES,
                            "pending upload snapshot"
                        ),
                        Charsets.UTF_8
                    )
                },
                pendingUploadPhotoFiles = metadata.pendingUpload?.photoFiles?.mapValues { (name, bytes) ->
                    encodeBoundedBase64(bytes, "pending upload photo content: $name")
                }
            )
        }
    }
}

/** Small deterministic store for JVM coordinator tests and failure injection. */
class InMemorySyncMetadataStore(
    private val failWrites: () -> SyncMetadataError? = { null }
) : SyncMetadataStore {
    private val values = ConcurrentHashMap<SyncScope, SyncMetadata>()

    override suspend fun read(scope: SyncScope): MetadataReadResult =
        MetadataReadResult.Loaded(values[scope])

    override suspend fun write(metadata: SyncMetadata): MetadataWriteResult {
        failWrites()?.let { return MetadataWriteResult.Failed(it) }
        values[metadata.scope] = metadata
        return MetadataWriteResult.Committed
    }

    fun snapshot(scope: SyncScope): SyncMetadata? = values[scope]
}

private fun defaultSyncMetadataRecoveryIdentity(metadata: SyncMetadata): String {
    val gson = GsonBuilder().disableHtmlEscaping().create()
    return sha256Hex(gson.toJson(metadata).toByteArray(Charsets.UTF_8))
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
