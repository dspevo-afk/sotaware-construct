package com.example.myapplication.stage4

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
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
import java.io.IOException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.PhotoPathOperations
import com.example.myapplication.stage5.PhotoPathOperationsFactory
import com.example.myapplication.stage5.encodeBoundedJson
import com.example.myapplication.stage5.requireBoundedString
import com.example.myapplication.stage5.validateSourceFingerprintProperty
import com.example.myapplication.stage5.parseBoundedJsonObject
import com.example.myapplication.stage5.readBoundedBytes
import com.example.myapplication.stage5.sha256Hex
import com.example.myapplication.stage5.Stage5ValidationException
import com.example.myapplication.stage5.validateSyncMetadataTree
import com.example.myapplication.stage9b.PhotoAssetSet
import com.example.myapplication.stage9b.PhotoAssetLease
import com.example.myapplication.stage9b.validatePhotoAssets

const val SYNC_METADATA_SCHEMA_VERSION: Int = 2

/** Durable provenance for pending upload bytes. */
enum class PendingUploadIntent {
    AUTOMATIC_RETRY,
    EXPLICIT_CONFLICT_REPLAY
}

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
    val photoFiles: PhotoAssetSet = PhotoAssetSet.EMPTY,
    val pendingUploadIntent: PendingUploadIntent = PendingUploadIntent.AUTOMATIC_RETRY,
    /** Process-local lease for a loaded outbox source; never serialized. */
    internal val outboxLease: PhotoAssetLease? = null
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
        validatePhotoAssets(snapshot, photoFiles)
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
class FileSyncMetadataStore internal constructor(
    private val rootDirectory: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val pendingUploadOperationsFactory: PhotoPathOperationsFactory?,
    private val trustedRootDirectory: File? = null,
    /**
     * Explicit JVM-only cleanup seam. Public production constructors leave it
     * null, preserving the native SecureDirectoryStream requirement.
     */
    private val pendingUploadDirectoryStreamFactory: OutboxDirectoryStreamFactory? = null
) : SyncMetadataStore {
    constructor(
        rootDirectory: File,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(rootDirectory, ioDispatcher, null, null)

    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        File(context.filesDir, "sync_metadata"),
        ioDispatcher,
        null,
        context.filesDir
    )

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val pendingUploadOutbox = FilePendingUploadOutbox(
        rootDirectory,
        pendingUploadOperationsFactory,
        pendingUploadDirectoryStreamFactory
    )

    override suspend fun read(scope: SyncScope): MetadataReadResult = withContext(ioDispatcher) {
        val target = metadataFile(scope)
        lockFor(scope).withLock {
            var operations: MetadataFileOperations? = null
            try {
                val rootPath = metadataRootPath()
                if (!isSafeMetadataRootPresent(rootPath)) {
                    return@withLock MetadataReadResult.Loaded(null)
                }
                val targetAttributes = metadataAttributes(rootPath.resolve(target.name))
                    ?: return@withLock MetadataReadResult.Loaded(null)
                if (targetAttributes.isSymbolicLink || !targetAttributes.isRegularFile) {
                    throw IOException("sync metadata target is not a regular file")
                }
                val metadataOperations = openMetadataOperations(rootPath)
                operations = metadataOperations
                val targetName = target.name
                // The no-follow preflight above preserves first-use absence;
                // the opened operation repeats it against the authoritative
                // directory descriptor before reading bytes.
                if (!metadataOperations.exists(targetName)) {
                    throw IOException("sync metadata target disappeared during read")
                }
                if (!metadataOperations.isRegularFile(targetName)) {
                    throw IOException("sync metadata target is not a regular file")
                }
                val bytes = metadataOperations.openRead(targetName).use {
                    readBoundedBytes(it, Stage5Limits.MAX_METADATA_BYTES, "sync metadata")
                }
                val raw = parseBoundedJsonObject(
                    ByteArrayInputStream(bytes),
                    Stage5Limits.MAX_METADATA_BYTES,
                    "sync metadata"
                )
                validateCurrentMetadataWire(raw)
                val json = gson.fromJson(
                    raw,
                    MetadataJson::class.java
                )
                    ?: return@withLock MetadataReadResult.Failed(
                        SyncMetadataError.Corrupt(target.path, "metadata payload missing")
                    )
                MetadataReadResult.Loaded(json.toMetadata(scope, target, gson, pendingUploadOutbox))
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
            } finally {
                closeMetadataOperations(operations)
            }
        }
    }

    override suspend fun write(metadata: SyncMetadata): MetadataWriteResult = withContext(ioDispatcher) {
        val target = metadataFile(metadata.scope)
        lockFor(metadata.scope).withLock {
            val stagingName = "${target.name}.${UUID.randomUUID()}.tmp"
            var operations: MetadataFileOperations? = null
            var stagingCreated = false
            var moveAttempted = false
            try {
                val rootPath = metadataRootPath()
                val frozen = freezeSyncMetadata(metadata)
                validateMetadataForWrite(frozen)
                ensureSafeMetadataRoot(rootPath)
                val metadataOperations = openMetadataOperations(rootPath)
                operations = metadataOperations
                // Reject an unsafe incumbent before creating any staging
                // evidence. A regular incumbent may be atomically replaced;
                // links and special files may not participate in the commit.
                if (metadataOperations.exists(target.name) &&
                    !metadataOperations.isRegularFile(target.name)
                ) {
                    throw IOException("sync metadata target is not a regular file")
                }
                if (metadataOperations.exists(target.name)) {
                    // Current-format cutover is fail-closed: an unsupported,
                    // malformed, or incomplete incumbent is preserved rather
                    // than silently replaced by a fresh empty record.
                    try {
                        val incumbentBytes = readMetadataBytes(
                            metadataOperations,
                            target.name,
                            "sync metadata incumbent"
                        )
                        val incumbentTree = parseBoundedJsonObject(
                            ByteArrayInputStream(incumbentBytes),
                            Stage5Limits.MAX_METADATA_BYTES,
                            "sync metadata incumbent"
                        )
                        validateCurrentMetadataWire(incumbentTree)
                        val incumbentJson = gson.fromJson(incumbentTree, MetadataJson::class.java)
                            ?: throw IllegalArgumentException("sync metadata incumbent is empty")
                        incumbentJson.toMetadata(
                            metadata.scope,
                            target,
                            gson,
                            pendingUploadOutbox,
                            retainOutboxLease = false
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: IOException) {
                        return@withLock MetadataWriteResult.Failed(
                            SyncMetadataError.Corrupt(target.path, error.message, error)
                        )
                    } catch (error: JsonParseException) {
                        return@withLock MetadataWriteResult.Failed(
                            SyncMetadataError.Corrupt(target.path, error.message, error)
                        )
                    } catch (error: IllegalArgumentException) {
                        return@withLock MetadataWriteResult.Failed(
                            SyncMetadataError.Corrupt(target.path, error.message, error)
                        )
                    } catch (error: IllegalStateException) {
                        return@withLock MetadataWriteResult.Failed(
                            SyncMetadataError.Corrupt(target.path, error.message, error)
                        )
                    } catch (error: SecurityException) {
                        return@withLock MetadataWriteResult.Failed(
                            SyncMetadataError.Corrupt(target.path, error.message, error)
                        )
                    }
                }
                val pendingUploadSidecar = frozen.pendingUpload
                    ?.let { pendingUploadOutbox.publish(metadata.scope, it) }
                val bytes = encodeBoundedJson(
                    gson,
                    MetadataJson.from(frozen, gson, pendingUploadSidecar),
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
                validateCurrentMetadataWire(encodedTree)
                metadataOperations.openNewOutput(stagingName).use { output ->
                    stagingCreated = true
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) {
                        if (output.write(buffer) <= 0) {
                            throw IOException("metadata staging write made no progress")
                        }
                    }
                    output.force(true)
                }
                // Once rename is attempted the staging path is ambiguous: a
                // provider may have completed the rename before reporting an
                // error. Leave that evidence in place rather than deleting an
                // unknown path.
                moveAttempted = true
                metadataOperations.replace(stagingName, target.name)
                stagingCreated = false
                metadataOperations.forceFile(target.name)
                metadataOperations.forceDirectory()
                // Read the published bytes back through the same raw and
                // typed validators before reporting durable success. This
                // keeps a successful write honest even if the filesystem
                // boundary altered or exposed a different file than staging.
                val durableBytes = readMetadataBytes(
                    metadataOperations,
                    target.name,
                    "sync metadata read-back"
                )
                if (!durableBytes.contentEquals(bytes)) {
                    throw IOException("published metadata bytes differ from the frozen staged bytes")
                }
                val durableTree = parseBoundedJsonObject(
                    ByteArrayInputStream(durableBytes),
                    Stage5Limits.MAX_METADATA_BYTES,
                    "sync metadata read-back"
                )
                validateCurrentMetadataWire(durableTree)
                val durableJson = gson.fromJson(durableTree, MetadataJson::class.java)
                    ?: throw IOException("published metadata read-back is empty")
                durableJson.toMetadata(
                    metadata.scope,
                    target,
                    gson,
                    pendingUploadOutbox,
                    retainOutboxLease = false
                )
                pendingUploadOutbox.reconcile(metadata.scope, target)
                // Re-check after outbox reconciliation as well. The sidecar
                // cleanup is best effort, but a concurrent target replacement
                // must never turn into a reported commit.
                val postReconcileBytes = readMetadataBytes(
                    metadataOperations,
                    target.name,
                    "sync metadata final read-back"
                )
                if (!postReconcileBytes.contentEquals(bytes)) {
                    throw IOException("metadata target changed after publication")
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
            } catch (error: JsonParseException) {
                MetadataWriteResult.Failed(SyncMetadataError.Io("write metadata", target.path, error.message, error))
            } catch (error: IllegalStateException) {
                MetadataWriteResult.Failed(SyncMetadataError.Io("write metadata", target.path, error.message, error))
            } catch (error: SecurityException) {
                MetadataWriteResult.Failed(SyncMetadataError.Io("write metadata", target.path, error.message, error))
            } finally {
                if (stagingCreated && !moveAttempted) {
                    try {
                        operations?.delete(stagingName)
                    } catch (_: IOException) {
                        // The staging entry is known to be ours, but cleanup
                        // failure is retained as evidence for recovery.
                    } catch (_: SecurityException) {
                        // Same conservative rule for provider security errors.
                    }
                }
                closeMetadataOperations(operations)
            }
        }
    }

    fun metadataFileFor(scope: SyncScope): File = metadataFile(scope)

    override fun recoveryIdentity(metadata: SyncMetadata): String {
        val frozen = freezeSyncMetadata(metadata)
        validateMetadataForWrite(frozen)
        val pendingUploadSidecar = frozen.pendingUpload
            ?.let { pendingUploadOutbox.referenceFor(metadata.scope, it) }
        val bytes = encodeBoundedJson(
            gson,
            MetadataJson.from(frozen, gson, pendingUploadSidecar),
            Stage5Limits.MAX_METADATA_BYTES,
            "sync metadata recovery identity"
        )
        return sha256Hex(bytes)
    }

    private fun lockFor(scope: SyncScope): Mutex =
        LOCKS.computeIfAbsent(scopeKey(scope)) { Mutex() }

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
            // immediately before encoding; immutable handles are revalidated
            // without materializing a second byte map.
            validatePhotoAssets(pending.snapshot, pending.photoFiles)
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
                // PhotoAssetSet is an immutable, reopenable handle.  Sharing
                // it across the metadata/outbox boundary is intentional: a
                // byte deep-copy here would defeat streaming and retention.
                photoFiles = pending.photoFiles,
                pendingUploadIntent = pending.pendingUploadIntent,
                outboxLease = pending.outboxLease
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

    private fun metadataRootPath(): Path =
        rootDirectory.toPath().toAbsolutePath().normalize()

    private fun trustedMetadataRootPath(): Path? =
        trustedRootDirectory?.toPath()?.toAbsolutePath()?.normalize()

    /**
     * Reads one path component without following a symbolic link. A missing
     * component is represented as null; permission/provider errors remain
     * failures instead of being mistaken for first-use absence.
     */
    private fun metadataAttributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
    } catch (_: NoSuchFileException) {
        if (Files.isSymbolicLink(path)) {
            throw IOException("metadata path is a dangling symbolic link: $path")
        }
        null
    }

    /**
     * Production Android treats filesDir as the platform-managed trusted
     * boundary, matching the photo-store contract. Every component below that
     * boundary is still checked with NOFOLLOW_LINKS. Generic file-backed
     * callers without an explicit trusted root retain strict root-to-leaf
     * validation.
     */
    private fun validateMetadataAncestors(path: Path) {
        val absolute = path.toAbsolutePath().normalize()
        val trusted = trustedMetadataRootPath()
        if (trusted != null) {
            if (!absolute.startsWith(trusted)) {
                throw IOException("metadata root is outside its trusted app-private directory")
            }
            val trustedAttributes = metadataAttributes(trusted)
                ?: throw IOException("trusted metadata root is unavailable: $trusted")
            if (trustedAttributes.isSymbolicLink || !trustedAttributes.isDirectory) {
                throw IOException("trusted metadata root is not a real directory: $trusted")
            }
            var current = absolute
            while (current != trusted) {
                val attributes = metadataAttributes(current)
                if (attributes != null && (attributes.isSymbolicLink || !attributes.isDirectory)) {
                    throw IOException("metadata path component is unsafe: $current")
                }
                current = current.parent
                    ?: throw IOException("metadata path escaped its trusted root")
                if (!current.startsWith(trusted)) {
                    throw IOException("metadata path escaped its trusted root")
                }
            }
            return
        }

        var current: Path? = absolute
        while (current != null) {
            val cursor = current ?: break
            val attributes = metadataAttributes(cursor)
            if (attributes != null) {
                if (attributes.isSymbolicLink) {
                    throw IOException("metadata path contains a symbolic link: $cursor")
                }
                if (cursor != absolute && !attributes.isDirectory) {
                    throw IOException("metadata path ancestor is not a directory: $cursor")
                }
            }
            current = cursor.parent
        }
    }

    private fun isSafeMetadataRootPresent(path: Path): Boolean {
        validateMetadataAncestors(path)
        val attributes = metadataAttributes(path) ?: return false
        if (attributes.isSymbolicLink || !attributes.isDirectory) {
            throw IOException("metadata root is not a real directory: $path")
        }
        return true
    }

    /** Creates only missing components below the already-trusted boundary. */
    private fun ensureSafeMetadataRoot(path: Path) {
        validateMetadataAncestors(path)
        val trusted = trustedMetadataRootPath()
        val missing = ArrayList<Path>()
        var current = path.toAbsolutePath().normalize()
        while (true) {
            val attributes = metadataAttributes(current)
            if (attributes != null) {
                if (attributes.isSymbolicLink || !attributes.isDirectory) {
                    throw IOException("metadata root is not a real directory: $current")
                }
                break
            }
            if (trusted != null && current == trusted) {
                throw IOException("trusted metadata root is unavailable: $trusted")
            }
            missing.add(current)
            current = current.parent
                ?: throw IOException("metadata root has no filesystem parent: $path")
            if (trusted != null && !current.startsWith(trusted)) {
                throw IOException("metadata root creation escaped its trusted boundary")
            }
        }
        missing.asReversed().forEach { component ->
            try {
                Files.createDirectory(component)
            } catch (_: FileAlreadyExistsException) {
                // Re-check below; an attacker-created symlink is not accepted.
            }
            val attributes = metadataAttributes(component)
                ?: throw IOException("metadata root component disappeared: $component")
            if (attributes.isSymbolicLink || !attributes.isDirectory) {
                throw IOException("metadata root component is unsafe: $component")
            }
        }
    }

    private fun openMetadataOperations(rootPath: Path): MetadataFileOperations {
        pendingUploadOperationsFactory?.let { factory ->
            return FactoryMetadataFileOperations(rootPath, factory.open(rootPath))
        }
        return SecureMetadataFileOperations.open(rootPath, trustedMetadataRootPath())
    }

    private fun closeMetadataOperations(operations: MetadataFileOperations?) {
        try {
            operations?.close()
        } catch (_: IOException) {
            // The authoritative bytes have already been validated; retaining
            // a closed descriptor is not useful recovery evidence.
        } catch (_: SecurityException) {
            // Same conservative rule for provider security failures.
        }
    }

    private fun readMetadataBytes(
        operations: MetadataFileOperations,
        name: String,
        label: String
    ): ByteArray {
        if (!operations.exists(name)) throw IOException("$label target is absent")
        if (!operations.isRegularFile(name)) throw IOException("$label target is not a regular file")
        return operations.openRead(name).use {
            readBoundedBytes(it, Stage5Limits.MAX_METADATA_BYTES, label)
        }
    }

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
        val pendingUploadIntent: String?,
        val pendingUploadSourceUri: String?,
        val pendingUploadSourceFingerprint: String?,
        val pendingUploadGeneration: Long?,
        val pendingUploadExpectedRevision: String?,
        val pendingUploadExpectedModifiedTimeMillis: Long?,
        val pendingUploadPhotoSidecar: PendingUploadSidecarReference?
    ) {
        fun toMetadata(
            scope: SyncScope,
            file: File,
            gson: Gson,
            pendingUploadOutbox: FilePendingUploadOutbox,
            retainOutboxLease: Boolean = true
        ): SyncMetadata {
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
            requireBoundedString(pendingUploadIntent, "pending upload intent")
            requireBoundedString(pendingUploadSourceUri, "pending upload source URI")
            requireBoundedString(pendingUploadSourceFingerprint, "pending upload fingerprint")
            validateSourceFingerprintProperty(pendingAdoptionSourceFingerprint, "pending adoption fingerprint")
            validateSourceFingerprintProperty(pendingUploadSourceFingerprint, "pending upload fingerprint")
            val sidecarReference = pendingUploadPhotoSidecar?.let { reference ->
                PendingUploadSidecarReference(
                    schemaVersion = reference.schemaVersion,
                    contentId = reference.contentId,
                    manifestSha256 = reference.manifestSha256,
                    snapshotSha256 = reference.snapshotSha256,
                    snapshotByteCount = reference.snapshotByteCount,
                    photoCount = reference.photoCount,
                    totalPhotoBytes = reference.totalPhotoBytes
                )
            }
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
            val pendingUploadSpecified = pendingUploadReason != null ||
                pendingUploadIntent != null ||
                pendingUploadSourceUri != null ||
                pendingUploadSourceFingerprint != null ||
                pendingUploadGeneration != null ||
                pendingUploadExpectedRevision != null ||
                pendingUploadExpectedModifiedTimeMillis != null ||
                sidecarReference != null
            val pendingUpload = if (!pendingUploadSpecified) {
                null
            } else {
                val sidecar = sidecarReference
                    ?: throw IllegalArgumentException(
                        "pending upload metadata must reference a schema-3 outbox sidecar"
                    )
                val reason = pendingUploadReason?.let {
                    runCatching { SyncReason.valueOf(it) }.getOrNull()
                } ?: throw IllegalArgumentException("pending upload reason missing or invalid")
                val intent = pendingUploadIntent?.let {
                    runCatching { PendingUploadIntent.valueOf(it) }.getOrNull()
                } ?: throw IllegalArgumentException("pending upload intent missing or invalid")
                val sourceUri = pendingUploadSourceUri
                    ?: throw IllegalArgumentException("pending upload source URI missing")
                val generation = pendingUploadGeneration
                    ?: throw IllegalArgumentException("pending upload generation missing")
                val pendingFingerprint = pendingUploadSourceFingerprint?.let {
                    sourceFingerprintFromDriveProperty(it)
                        ?: throw IllegalArgumentException("pending upload source fingerprint is invalid")
                }
                val expectedCursor = pendingUploadExpectedRevision?.let {
                    RemoteCursor(it, pendingUploadExpectedModifiedTimeMillis)
                }
                val pendingContent = pendingUploadOutbox.load(
                    scope = scope,
                    reference = sidecar,
                    reason = reason,
                    sourceUri = sourceUri,
                    sourceFingerprint = pendingFingerprint,
                    generation = generation,
                    expectedCursor = expectedCursor
                )
                try {
                    DurablePendingUpload(
                        reason = reason,
                        sourceUri = sourceUri,
                        sourceFingerprint = pendingFingerprint,
                        generation = generation,
                        expectedCursor = expectedCursor,
                        snapshot = pendingContent.snapshot,
                        photoFiles = pendingContent.photoFiles,
                        pendingUploadIntent = intent,
                        outboxLease = pendingContent.lease.takeIf { retainOutboxLease }
                    )
                } catch (error: Throwable) {
                    pendingContent.close()
                    throw error
                }.also {
                    // Validation/read-back callers only need to prove the
                    // sidecar.  The coordinator read path explicitly retains
                    // the lease for the returned metadata owner.
                    if (!retainOutboxLease) pendingContent.close()
                }
            }
            return try {
                SyncMetadata(
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
            } catch (error: Throwable) {
                pendingUpload?.outboxLease?.close()
                throw error
            }
        }

        companion object {
            fun from(
                metadata: SyncMetadata,
                gson: Gson,
                sidecarReference: PendingUploadSidecarReference? = null
            ): MetadataJson = MetadataJson(
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
                pendingUploadIntent = metadata.pendingUpload?.pendingUploadIntent?.name,
                pendingUploadSourceUri = metadata.pendingUpload?.sourceUri,
                pendingUploadSourceFingerprint = metadata.pendingUpload?.sourceFingerprint?.toDriveProperty(),
                pendingUploadGeneration = metadata.pendingUpload?.generation,
                pendingUploadExpectedRevision = metadata.pendingUpload?.expectedCursor?.revision,
                pendingUploadExpectedModifiedTimeMillis = metadata.pendingUpload?.expectedCursor?.modifiedTimeMillis,
                pendingUploadPhotoSidecar = sidecarReference
            )
        }
    }
}

/**
 * Narrow filesystem seam for the metadata authority. Production uses an
 * opened SecureDirectoryStream; JVM tests may inject the existing Stage 5
 * path seam because the Windows provider does not expose that primitive.
 * Pending-upload cleanup has its own explicit directory-stream seam.
 */
private interface MetadataFileOperations : AutoCloseable {
    fun exists(name: String): Boolean
    fun isRegularFile(name: String): Boolean
    fun openRead(name: String): InputStream
    fun openNewOutput(name: String): FileChannel
    fun replace(source: String, target: String)
    fun forceFile(name: String)
    fun forceDirectory()
    fun delete(name: String)
}

/** Adapter used only by explicit JVM test factories. */
private class FactoryMetadataFileOperations(
    private val root: Path,
    private val delegate: PhotoPathOperations
) : MetadataFileOperations {
    private fun relative(name: String): Path {
        if (name.isEmpty() || name == "." || name == ".." ||
            name.contains('/') || name.contains('\\') || name.indexOf('\u0000') >= 0
        ) {
            throw IOException("metadata operation requires one relative child name")
        }
        return root.resolve(name)
    }

    private fun rejectSymlink(name: String) {
        if (Files.isSymbolicLink(relative(name))) {
            throw IOException("metadata entry is a symbolic link: $name")
        }
    }

    override fun exists(name: String): Boolean {
        rejectSymlink(name)
        return delegate.exists(name)
    }

    override fun isRegularFile(name: String): Boolean {
        rejectSymlink(name)
        return delegate.isRegularFile(name)
    }

    override fun openRead(name: String): InputStream {
        rejectSymlink(name)
        return delegate.openRead(name)
    }

    override fun openNewOutput(name: String): FileChannel {
        rejectSymlink(name)
        if (Files.exists(relative(name), LinkOption.NOFOLLOW_LINKS)) {
            throw FileAlreadyExistsException(name)
        }
        // The injected operation is required to enforce CREATE_NEW and
        // NOFOLLOW_LINKS itself. The preflight above makes a hostile existing
        // entry fail closed before the delegate is asked to open it.
        return delegate.openNewOutput(name)
    }

    override fun replace(source: String, target: String) {
        rejectSymlink(source)
        rejectSymlink(target)
        if (!delegate.isRegularFile(source)) {
            throw IOException("metadata staging entry is not a regular file")
        }
        try {
            delegate.move(source, target, replaceExisting = true)
        } catch (error: Stage5ValidationException) {
            // The checked-in JVM photo seam intentionally refuses replacement
            // moves. Keep that compatibility adapter narrow while allowing
            // other deterministic factories to inject move failures/changes.
            if (error.message != "test photo moves do not allow replacement") {
                throw error
            }
            Files.move(
                relative(source),
                relative(target),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    override fun forceFile(name: String) {
        rejectSymlink(name)
        if (!delegate.isRegularFile(name)) {
            throw IOException("metadata target is not a regular file")
        }
        FileChannel.open(
            relative(name),
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS
        ).use { channel -> channel.force(true) }
    }

    override fun forceDirectory() {
        forceMetadataDirectory(root)
    }

    override fun delete(name: String) {
        rejectSymlink(name)
        delegate.delete(name)
    }

    override fun close() {
        delegate.close()
    }
}

/** Secure production implementation rooted at one opened directory handle. */
private class SecureMetadataFileOperations private constructor(
    private val fileSystem: java.nio.file.FileSystem,
    private val root: Path,
    private val directory: SecureDirectoryStream<Path>
) : MetadataFileOperations {
    companion object {
        fun open(root: Path, trustedRoot: Path? = null): MetadataFileOperations {
            val absolute = root.toAbsolutePath().normalize()
            val fileSystem = absolute.fileSystem
            val trusted = trustedRoot?.toAbsolutePath()?.normalize()
            if (trusted != null && !absolute.startsWith(trusted)) {
                throw IOException("metadata root is outside its trusted app-private directory")
            }
            val anchor = trusted ?: absolute.root
                ?: throw IOException("metadata root has no filesystem root")
            var current = openSecureDirectory(anchor)
            try {
                val relative = if (trusted != null) trusted.relativize(absolute) else absolute
                relative.iterator().forEach { component ->
                    val next = current.newDirectoryStream(
                        fileSystem.getPath(component.toString()),
                        LinkOption.NOFOLLOW_LINKS
                    )
                    current.close()
                    current = next
                }
            } catch (error: IOException) {
                try {
                    current.close()
                } catch (_: IOException) {
                } catch (_: SecurityException) {
                }
                throw IOException("secure metadata directory could not be opened", error)
            } catch (error: SecurityException) {
                try {
                    current.close()
                } catch (_: IOException) {
                } catch (_: SecurityException) {
                }
                throw IOException("secure metadata directory could not be opened", error)
            }
            return SecureMetadataFileOperations(fileSystem, absolute, current)
        }

        private fun openSecureDirectory(path: Path): SecureDirectoryStream<Path> {
            val stream = try {
                Files.newDirectoryStream(path)
            } catch (error: IOException) {
                throw IOException("secure metadata filesystem root could not be opened", error)
            } catch (error: SecurityException) {
                throw IOException("secure metadata filesystem root could not be opened", error)
            }
            if (stream !is SecureDirectoryStream<*>) {
                try {
                    stream.close()
                } catch (_: IOException) {
                } catch (_: SecurityException) {
                }
                throw IOException("metadata provider lacks SecureDirectoryStream support")
            }
            @Suppress("UNCHECKED_CAST")
            return stream as SecureDirectoryStream<Path>
        }
    }

    private fun relative(name: String): Path {
        if (name.isEmpty() || name == "." || name == ".." ||
            name.contains('/') || name.contains('\\') || name.indexOf('\u0000') >= 0
        ) {
            throw IOException("metadata operation requires one relative child name")
        }
        return fileSystem.getPath(name)
    }

    private fun attributes(name: String): BasicFileAttributes? {
        val relative = relative(name)
        return try {
            val view = directory.getFileAttributeView(
                relative,
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS
            ) ?: throw IOException("metadata attributes are unavailable")
            view.readAttributes()
        } catch (_: NoSuchFileException) {
            if (Files.isSymbolicLink(root.resolve(name))) {
                throw IOException("metadata entry is a dangling symbolic link: $name")
            }
            null
        }
    }

    private fun rejectSymlink(name: String, attributes: BasicFileAttributes?) {
        if (attributes?.isSymbolicLink == true) {
            throw IOException("metadata entry is a symbolic link: $name")
        }
    }

    override fun exists(name: String): Boolean {
        val attributes = attributes(name)
        rejectSymlink(name, attributes)
        return attributes != null
    }

    override fun isRegularFile(name: String): Boolean {
        val attributes = attributes(name)
        rejectSymlink(name, attributes)
        return attributes?.isRegularFile == true
    }

    override fun openRead(name: String): InputStream {
        val attributes = attributes(name)
        rejectSymlink(name, attributes)
        if (attributes != null && !attributes.isRegularFile) {
            throw IOException("metadata entry is not a regular file: $name")
        }
        val channel = directory.newByteChannel(
            relative(name),
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        )
        return Channels.newInputStream(channel)
    }

    override fun openNewOutput(name: String): FileChannel {
        val attributes = attributes(name)
        rejectSymlink(name, attributes)
        if (attributes != null) throw FileAlreadyExistsException(name)
        val channel = directory.newByteChannel(
            relative(name),
            setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        )
        return channel as? FileChannel ?: run {
            try {
                channel.close()
            } catch (_: IOException) {
            } catch (_: SecurityException) {
            }
            throw IOException("metadata provider did not return a forceable file channel")
        }
    }

    override fun replace(source: String, target: String) {
        val sourceAttributes = attributes(source)
        rejectSymlink(source, sourceAttributes)
        if (sourceAttributes == null || !sourceAttributes.isRegularFile) {
            throw IOException("metadata staging entry is unavailable")
        }
        val targetAttributes = attributes(target)
        rejectSymlink(target, targetAttributes)
        if (targetAttributes != null && !targetAttributes.isRegularFile) {
            throw IOException("metadata target is not a regular file")
        }
        // Same-directory SecureDirectoryStream.move is descriptor-relative and
        // maps to the provider's atomic rename primitive. On providers where
        // replacement is unsupported, the operation fails closed rather than
        // falling back to a path-based move.
        directory.move(relative(source), directory, relative(target))
    }

    override fun forceFile(name: String) {
        val attributes = attributes(name)
        rejectSymlink(name, attributes)
        if (attributes == null || !attributes.isRegularFile) {
            throw IOException("metadata target is not a regular file")
        }
        val channel = directory.newByteChannel(
            relative(name),
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        )
        try {
            (channel as? FileChannel)?.force(true)
                ?: throw IOException("metadata provider did not return a forceable file channel")
        } finally {
            try {
                channel.close()
            } catch (_: IOException) {
            } catch (_: SecurityException) {
            }
        }
    }

    override fun forceDirectory() {
        forceMetadataDirectory(root)
    }

    override fun delete(name: String) {
        val attributes = attributes(name)
        rejectSymlink(name, attributes)
        if (attributes == null) return
        if (!attributes.isRegularFile) {
            throw IOException("metadata entry is not a regular file: $name")
        }
        directory.deleteFile(relative(name))
    }

    override fun close() {
        directory.close()
    }
}

private fun forceMetadataDirectory(directory: Path) {
    // The desktop Windows provider cannot fsync directories. Validate the
    // capability boundary explicitly instead of claiming a flush occurred.
    if (directory.fileSystem.provider().javaClass.name == "sun.nio.fs.WindowsFileSystemProvider") {
        val attributes = Files.readAttributes(
            directory,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        if (attributes.isSymbolicLink || !attributes.isDirectory) {
            throw IOException("metadata directory is not safe for publication")
        }
        return
    }
    try {
        FileChannel.open(
            directory,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS
        ).use { channel -> channel.force(true) }
    } catch (_: UnsupportedOperationException) {
        // Some Android filesystems do not expose directory fsync. Individual
        // file fsync plus the atomic rename remain mandatory per repository
        // durability policy.
    }
}

/** Process-wide scope locks prevent separate store instances from racing. */
private val LOCKS = ConcurrentHashMap<String, Mutex>()

/** Small deterministic store for JVM coordinator tests and failure injection. */
class InMemorySyncMetadataStore(
    private val failWrites: () -> SyncMetadataError? = { null }
) : SyncMetadataStore {
    private val values = ConcurrentHashMap<SyncScope, SyncMetadata>()

    override suspend fun read(scope: SyncScope): MetadataReadResult =
        MetadataReadResult.Loaded(values[scope])

    override suspend fun write(metadata: SyncMetadata): MetadataWriteResult {
        failWrites()?.let { return MetadataWriteResult.Failed(it) }
        if (metadata.pendingUpload?.photoFiles?.isNotEmpty() == true) {
            // This fixture has no durable outbox.  Accepting the metadata
            // would let the coordinator release its capture claim while the
            // in-memory handle still points at reclaimable pool content.
            return MetadataWriteResult.Failed(
                SyncMetadataError.Io(
                    operation = "write pending upload",
                    path = null,
                    detail = "photo persistence capability is not configured"
                )
            )
        }
        values[metadata.scope] = metadata
        return MetadataWriteResult.Committed
    }

    fun snapshot(scope: SyncScope): SyncMetadata? = values[scope]
}

private fun defaultSyncMetadataRecoveryIdentity(metadata: SyncMetadata): String {
    val gson = GsonBuilder().disableHtmlEscaping().create()
    return sha256Hex(gson.toJson(metadata).toByteArray(Charsets.UTF_8))
}

/**
 * Stage 9B has one metadata wire.  Keep the explicit retirement check here in
 * addition to the shared tree validator so a stale validator cannot turn an
 * old inline payload into an apparently empty current record.
 */
private fun validateCurrentMetadataWire(root: JsonObject) {
    validateSyncMetadataTree(root)
    val version = root.get("schemaVersion")?.asInt
        ?: throw Stage5ValidationException("sync metadata schema version is missing")
    require(version == SYNC_METADATA_SCHEMA_VERSION) {
        "unsupported sync metadata schema: $version"
    }
    listOf("pendingUploadSnapshotJson", "pendingUploadPhotoFiles").firstOrNull(root::has)?.let {
        throw Stage5ValidationException("sync metadata contains retired inline field: $it")
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
