package com.example.myapplication.stage4

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
        require(sourceUri.isNotBlank()) { "pending upload source URI must not be blank" }
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
                val json = gson.fromJson(target.readText(Charsets.UTF_8), MetadataJson::class.java)
                    ?: return@withLock MetadataReadResult.Failed(
                        SyncMetadataError.Corrupt(target.path, "metadata payload missing")
                    )
                MetadataReadResult.Loaded(json.toMetadata(scope, target, gson))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                MetadataReadResult.Failed(SyncMetadataError.Corrupt(target.path, error.message, error))
            } catch (error: Throwable) {
                MetadataReadResult.Failed(SyncMetadataError.Io("read metadata", target.path, error.message, error))
            }
        }
    }

    override suspend fun write(metadata: SyncMetadata): MetadataWriteResult = withContext(ioDispatcher) {
        val target = metadataFile(metadata.scope)
        lockFor(metadata.scope).withLock {
            val staging = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
            try {
                require(metadata.schemaVersion == SYNC_METADATA_SCHEMA_VERSION) { "unsupported metadata schema" }
                if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
                    throw IOException("unable to create ${rootDirectory.path}")
                }
                val bytes = gson.toJson(MetadataJson.from(metadata)).toByteArray(Charsets.UTF_8)
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
                MetadataWriteResult.Committed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AtomicMoveNotSupportedException) {
                MetadataWriteResult.Failed(SyncMetadataError.CommitUncertain(target.path, error.message, error))
            } catch (error: IOException) {
                MetadataWriteResult.Failed(SyncMetadataError.CommitUncertain(target.path, error.message, error))
            } catch (error: IllegalArgumentException) {
                MetadataWriteResult.Failed(SyncMetadataError.Io("write metadata", target.path, error.message, error))
            } catch (error: Throwable) {
                MetadataWriteResult.Failed(SyncMetadataError.Io("write metadata", target.path, error.message, error))
            } finally {
                if (staging.exists()) staging.delete()
            }
        }
    }

    fun metadataFileFor(scope: SyncScope): File = metadataFile(scope)

    private fun lockFor(scope: SyncScope): Mutex =
        locks.computeIfAbsent(scopeKey(scope)) { Mutex() }

    private fun metadataFile(scope: SyncScope): File {
        val directory = rootDirectory
        val name = "${sha256(scopeKey(scope))}.json"
        return File(directory, name)
    }

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
                val snapshot = gson.fromJson(snapshotJson, DocumentSnapshotV1::class.java)
                    ?: throw IllegalArgumentException("pending upload snapshot is invalid")
                val photoFiles = pendingUploadPhotoFiles.orEmpty().mapValues { (name, encoded) ->
                    try {
                        Base64.getDecoder().decode(encoded)
                    } catch (error: IllegalArgumentException) {
                        throw IllegalArgumentException("pending upload photo is not valid base64: $name", error)
                    }
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
            fun from(metadata: SyncMetadata): MetadataJson = MetadataJson(
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
                pendingUploadSnapshotJson = metadata.pendingUpload?.let { Gson().toJson(it.snapshot) },
                pendingUploadPhotoFiles = metadata.pendingUpload?.photoFiles?.mapValues { (_, bytes) ->
                    Base64.getEncoder().encodeToString(bytes)
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

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
