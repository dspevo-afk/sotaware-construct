package com.example.myapplication.stage4

import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage5.ImageInfo
import com.example.myapplication.stage5.PhotoDescriptor
import com.example.myapplication.stage5.PhotoPathOperationsFactory
import com.example.myapplication.stage5.PhotoPathResolver
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.encodeBoundedJson
import com.example.myapplication.stage5.decodeValidatedSnapshotJson
import com.example.myapplication.stage5.parseBoundedJsonObject
import com.example.myapplication.stage5.requireBoundedString
import com.example.myapplication.stage5.requiredPhotoNames
import com.example.myapplication.stage5.sha256Hex
import com.example.myapplication.stage5.validatePhotoFileName
import com.example.myapplication.stage5.validateSnapshot
import com.example.myapplication.stage9b.PhotoAsset
import com.example.myapplication.stage9b.PhotoAssetSet
import com.example.myapplication.stage9b.PhotoAssetLease
import com.example.myapplication.stage9b.PhotoAssetOwnershipRegistry
import com.example.myapplication.stage9b.copyPhotoAsset
import com.example.myapplication.stage9b.photoAssetsFromDescriptors
import com.example.myapplication.stage9b.validatePhotoAssets
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.channels.Channels
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

internal const val PENDING_UPLOAD_OUTBOX_SCHEMA_VERSION: Int = 3
internal const val PENDING_UPLOAD_OUTBOX_DIRECTORY: String = "pending_upload_outbox"
internal const val PENDING_UPLOAD_OUTBOX_MANIFEST: String = "manifest.json"
internal const val PENDING_UPLOAD_OUTBOX_MANIFEST_TEMP: String = ".manifest.json.tmp"
internal const val PENDING_UPLOAD_OUTBOX_SNAPSHOT: String = "snapshot.json"
private const val PENDING_UPLOAD_OUTBOX_SNAPSHOT_TEMP: String = ".snapshot.json.tmp"
internal const val PENDING_UPLOAD_OUTBOX_METADATA_FIELD: String = "pendingUploadPhotoSidecar"
private const val MAX_UNCERTAIN_CONTENT_DIRECTORIES: Int = 4

/**
 * The deliberately small directory capability used by outbox reclamation.
 * Implementations expose only the bounded listing, no-follow attribute read,
 * child-directory open, and descriptor-relative deletions that the cleanup
 * algorithm needs.
 */
internal interface OutboxDirectoryStream : AutoCloseable {
    fun listNames(maxEntries: Int): List<String>
    fun readAttributes(name: String): BasicFileAttributes
    fun openDirectory(name: String): OutboxDirectoryStream
    fun deleteFile(name: String)
    fun deleteDirectory(name: String)
}

/** Explicit test-only replacement for the native secure directory opener. */
internal fun interface OutboxDirectoryStreamFactory {
    fun open(path: Path, trustedRoot: Path): OutboxDirectoryStream
}

/**
 * Small metadata-only pointer to one immutable, validated pending-upload
 * content set.  Version 3 externalizes both canonical snapshot bytes and the
 * optional photo set.  The content ID is deterministic for the complete
 * logical pending upload and the manifest hash protects the publication
 * marker.
 */
data class PendingUploadSidecarReference(
    val schemaVersion: Int,
    val contentId: String,
    val manifestSha256: String,
    val snapshotSha256: String,
    val snapshotByteCount: Long,
    val photoCount: Int,
    val totalPhotoBytes: Long
) {
    init {
        require(schemaVersion == PENDING_UPLOAD_OUTBOX_SCHEMA_VERSION) {
            "unsupported pending upload outbox schema"
        }
        require(HEX_SHA256.matches(contentId)) { "pending upload content identity is invalid" }
        require(HEX_SHA256.matches(manifestSha256)) { "pending upload manifest hash is invalid" }
        require(HEX_SHA256.matches(snapshotSha256)) { "pending upload snapshot hash is invalid" }
        require(snapshotByteCount in 1L..Stage5Limits.MAX_JSON_BYTES.toLong()) {
            "pending upload snapshot byte count is out of range"
        }
        require(photoCount in 0..Stage5Limits.MAX_TOTAL_PHOTOS) {
            "pending upload sidecar photo count is out of range"
        }
        if (photoCount == 0) {
            require(totalPhotoBytes == 0L) { "pending upload sidecar byte count is out of range" }
        } else {
            require(totalPhotoBytes in photoCount.toLong()..Stage5Limits.MAX_TOTAL_PHOTO_BYTES) {
                "pending upload sidecar byte count is out of range"
            }
        }
    }

    companion object {
        private val HEX_SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal data class LoadedPendingUpload(
    val snapshot: DocumentSnapshotV1,
    val photoFiles: PhotoAssetSet,
    internal val lease: PhotoAssetLease? = null
) : AutoCloseable {
    override fun close() {
        lease?.close()
    }
}

private object DiscardOutputStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

/**
 * File-backed immutable outbox for complete pending-upload work.  A directory
 * becomes authoritative only when its complete manifest is atomically moved
 * into place.  Snapshot and photo files are independently fsynced before that
 * marker; photo files are generated from hashes and never from caller names.
 */
internal class FilePendingUploadOutbox(
    private val rootDirectory: File,
    /**
     * JVM-only seam for providers without SecureDirectoryStream. Production
     * callers leave this null and therefore use PhotoPathResolver's secure
     * default; tests must opt into any replacement explicitly.
     */
    private val payloadOperationsFactory: PhotoPathOperationsFactory? = null,
    /**
     * JVM-only seam for the cleanup directory capability. Production callers
     * leave this null so reclamation still requires SecureDirectoryStream.
     */
    private val directoryStreamFactory: OutboxDirectoryStreamFactory? = null
) {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    /**
     * Computes the exact reference that [publish] will use without changing
     * filesystem state.  FileSyncMetadataStore uses this for recoveryIdentity.
     */
    fun referenceFor(scope: SyncScope, pending: DurablePendingUpload): PendingUploadSidecarReference {
        return withSourceLease(pending) { prepare(scope, pending).reference }
    }

    /**
     * Validates, durably writes, and atomically publishes one immutable
     * content set.  Repeated attempts for equivalent logical input reuse the
     * deterministic directory rather than creating unbounded generations.
     */
    fun publish(scope: SyncScope, pending: DurablePendingUpload): PendingUploadSidecarReference {
        synchronized(lockFor(scope)) {
            return withSourceLease(pending) {
                val prepared = prepare(scope, pending)
                val scopeDirectory = ensureScopeDirectory(scope)
                val contentDirectory = contentDirectory(scopeDirectory, prepared.reference.contentId)
                if (Files.exists(contentDirectory, LinkOption.NOFOLLOW_LINKS)) {
                    rejectSymlink(contentDirectory, "pending upload content directory")
                    if (!Files.isDirectory(contentDirectory, LinkOption.NOFOLLOW_LINKS)) {
                        throw IOException("pending upload content path is not a directory")
                    }
                    if (hasPublishedManifest(contentDirectory)) {
                        validatePublished(scope, pending, prepared.reference, contentDirectory)
                        return@withSourceLease prepared.reference
                    }
                } else {
                    enforceUncertainRetentionBound(scopeDirectory, prepared.totalBytes)
                    Files.createDirectory(contentDirectory)
                }
                rejectSymlink(contentDirectory, "pending upload content directory")
                writeSnapshotFile(contentDirectory, prepared)
                writePhotoFiles(contentDirectory, prepared)
                val manifestBytes = prepared.manifestBytes
                val manifestTemp = contentDirectory.resolve(PENDING_UPLOAD_OUTBOX_MANIFEST_TEMP)
                deleteContainedFileIfPresent(manifestTemp, contentDirectory)
                writeDurableNew(manifestTemp, manifestBytes, "pending upload manifest staging")
                val manifestPath = contentDirectory.resolve(PENDING_UPLOAD_OUTBOX_MANIFEST)
                if (Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
                    rejectSymlink(manifestPath, "pending upload manifest")
                    validatePublished(scope, pending, prepared.reference, contentDirectory)
                    deleteContainedFile(manifestTemp, contentDirectory)
                    return@withSourceLease prepared.reference
                }
                try {
                    atomicMove(contentDirectory, manifestTemp, manifestPath)
                } catch (error: AtomicMoveNotSupportedException) {
                    // The staged bytes remain recoverable, but no metadata may
                    // point at them without an atomic publication marker.
                    throw IOException("atomic pending upload manifest publication is unavailable", error)
                }
                forceDirectory(contentDirectory)
                validatePublished(scope, pending, prepared.reference, contentDirectory)
                forceDirectory(scopeDirectory)
                forceDirectory(outboxDirectory())
                prepared.reference
            }
        }
    }

    /**
     * Reconstructs the complete logical pending upload from a referenced
     * sidecar and checks snapshot canonical bytes plus every photo identity,
     * descriptor, byte count, hash, and image boundary again.
     */
    fun load(
        scope: SyncScope,
        reference: PendingUploadSidecarReference,
        reason: SyncReason,
        sourceUri: String,
        sourceFingerprint: com.example.myapplication.stage2.SourceFingerprint?,
        generation: Long,
        expectedCursor: RemoteCursor?
    ): LoadedPendingUpload {
        synchronized(lockFor(scope)) {
            val scopeDirectory = existingScopeDirectory(scope)
            val contentDirectory = contentDirectory(scopeDirectory, reference.contentId)
            validatePublishedDirectoryPath(contentDirectory)
            val manifestBytes = readManifestBytes(contentDirectory)
            if (sha256Hex(manifestBytes) != reference.manifestSha256) {
                throw IllegalArgumentException("pending upload manifest hash mismatch")
            }
            val manifest = readManifest(contentDirectory)
            validatePendingReference(scope, reference, manifest)
            validatePublishedFileSet(contentDirectory, manifest)
            val snapshotBytes = readExact(
                contentDirectory.resolve(PENDING_UPLOAD_OUTBOX_SNAPSHOT),
                reference.snapshotByteCount,
                Stage5Limits.MAX_JSON_BYTES.toLong(),
                "pending upload snapshot"
            )
            if (sha256Hex(snapshotBytes) != reference.snapshotSha256 ||
                manifest.snapshotSha256 != reference.snapshotSha256 ||
                manifest.snapshotByteCount != reference.snapshotByteCount
            ) {
                throw IllegalArgumentException("pending upload snapshot hash or size mismatch")
            }
            val snapshotJson = String(snapshotBytes, StandardCharsets.UTF_8)
            val snapshot = decodeValidatedSnapshotJson(
                gson,
                snapshotJson,
                "pending upload snapshot"
            )
            val canonicalSnapshotBytes = encodeBoundedJson(
                gson,
                snapshot,
                Stage5Limits.MAX_JSON_BYTES,
                "pending upload snapshot"
            )
            if (!canonicalSnapshotBytes.contentEquals(snapshotBytes)) {
                throw IllegalArgumentException("pending upload snapshot is not canonical")
            }
            validatePendingIdentity(scope, reference, reason, sourceUri, sourceFingerprint, generation, expectedCursor, snapshot, contentDirectory)
            val records = manifest.photoFiles.orEmpty()
            val descriptors = LinkedHashMap<String, PhotoDescriptor>()
            records.toSortedMap().forEach { (name, record) ->
                descriptors[name] = descriptorFor(record, name)
            }
            val reopened = reopenPhotoAssets(contentDirectory, records)
            val validatedSet = validatePhotoAssets(snapshot, reopened, descriptors)
            if (validatedSet.keys != records.keys || validatedSet.descriptors != descriptors) {
                throw IllegalArgumentException("pending upload sidecar photo descriptors changed during reconstruction")
            }
            val snapshotHash = snapshotHash(snapshot)
            val expectedContentId = contentIdentity(
                scope,
                reason,
                sourceUri,
                sourceFingerprint,
                generation,
                expectedCursor,
                snapshotHash,
                descriptors
            )
            if (expectedContentId != reference.contentId || expectedContentId != manifest.contentId) {
                throw IllegalArgumentException("pending upload sidecar content identity mismatch")
            }
            val lease = PhotoAssetOwnershipRegistry.claim(
                outboxOwnerKey(scope, reference.contentId),
                validatedSet
            )
            return LoadedPendingUpload(snapshot, validatedSet, lease)
        }
    }

    /**
     * Reclaims only content that is provably not referenced by the current
     * valid metadata record.  If metadata or the current referenced sidecar
     * cannot be proved valid, nothing is removed.  This is called only after
     * a successful metadata read-back, so failed/uncertain commits retain
     * their recoverable evidence until authority is known.
     */
    fun reconcile(scope: SyncScope, metadataFile: File) {
        synchronized(lockFor(scope)) {
            try {
                val authority = readAuthoritativeMetadata(scope, metadataFile) ?: return
                val scopeDirectory = existingScopeDirectoryOrNull(scope) ?: return
                val current = authority.reference
                if (current != null) {
                    if (!isReferenceComplete(scope, authority)) return
                }
                Files.list(scopeDirectory).use { stream ->
                    stream.forEach { child ->
                        if (Files.isSymbolicLink(child)) return@forEach
                        val name = child.fileName.toString()
                        if (name == current?.contentId || isRetainedContent(scope, name)) return@forEach
                        if (!name.matches(HEX_SHA256)) return@forEach
                        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) return@forEach
                        if (!isProvablyOwnedContentDirectory(scope, child)) return@forEach
                        // Reclamation is deliberately best effort.  The
                        // metadata commit already succeeded, so an inability
                        // to remove one orphan must not turn that commit into
                        // a reported failure or risk another candidate.
                        try {
                            deleteContainedTree(child, scopeDirectory)
                        } catch (_: Exception) {
                            // Retain any candidate whose cleanup is uncertain.
                        }
                    }
                }
            } catch (_: Exception) {
                // Cleanup is not part of the authoritative commit boundary.
            }
        }
    }

    /**
     * A stale directory is deletable only after its manifest binds it to this
     * exact scope and its complete published file set is proven.  Malformed,
     * unpublished, or extra-file candidates remain as recovery evidence.
     */
    private fun isProvablyOwnedContentDirectory(scope: SyncScope, directory: Path): Boolean = try {
        val manifest = readManifest(directory)
        if (manifest.accountId != scope.accountId ||
            manifest.backupRootId != scope.backupRootId ||
            manifest.documentId != scope.documentId.value ||
            manifest.contentId != directory.fileName?.toString()
        ) {
            false
        } else {
            validatePublishedFileSet(directory, manifest)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun prepare(scope: SyncScope, pending: DurablePendingUpload): PreparedOutbox {
        validateSnapshot(pending.snapshot)
        val validated = validatePhotoAssets(pending.snapshot, pending.photoFiles)
        val snapshotBytes = encodeBoundedJson(
            gson,
            pending.snapshot,
            Stage5Limits.MAX_JSON_BYTES,
            "pending upload snapshot"
        )
        val snapshotSha256 = sha256Hex(snapshotBytes)
        val descriptors = validated.descriptors
        val contentId = contentIdentity(
            scope,
            pending.reason,
            pending.sourceUri,
            pending.sourceFingerprint,
            pending.generation,
            pending.expectedCursor,
            snapshotSha256,
            descriptors
        )
        val records = LinkedHashMap<String, SidecarPhotoRecord>()
        validated.toSortedMap().forEach { (name, photo) ->
            records[name] = SidecarPhotoRecord(
                fileName = generatedPhotoFileName(name, photo.descriptor.sha256),
                byteCount = photo.descriptor.byteCount,
                sha256 = photo.descriptor.sha256,
                mimeType = photo.descriptor.mimeType,
                width = photo.descriptor.width,
                height = photo.descriptor.height
            )
        }
        val totalPhotoBytes = descriptors.values.sumOf { it.byteCount }
        val manifest = SidecarManifest(
            schemaVersion = PENDING_UPLOAD_OUTBOX_SCHEMA_VERSION,
            accountId = scope.accountId,
            backupRootId = scope.backupRootId,
            documentId = scope.documentId.value,
            contentId = contentId,
            reason = pending.reason.name,
            sourceUri = pending.sourceUri,
            sourceFingerprint = pending.sourceFingerprint?.toDriveProperty(),
            generation = pending.generation,
            expectedRevision = pending.expectedCursor?.revision,
            expectedModifiedTimeMillis = pending.expectedCursor?.modifiedTimeMillis,
            snapshotSha256 = snapshotSha256,
            snapshotByteCount = snapshotBytes.size.toLong(),
            photoFiles = records,
            totalPhotoBytes = totalPhotoBytes
        )
        val manifestBytes = encodeBoundedJson(
            gson,
            manifest,
            Stage5Limits.MAX_METADATA_BYTES,
            "pending upload outbox manifest"
        )
        val reference = PendingUploadSidecarReference(
            schemaVersion = PENDING_UPLOAD_OUTBOX_SCHEMA_VERSION,
            contentId = contentId,
            manifestSha256 = sha256Hex(manifestBytes),
            snapshotSha256 = snapshotSha256,
            snapshotByteCount = snapshotBytes.size.toLong(),
            photoCount = descriptors.size,
            totalPhotoBytes = totalPhotoBytes
        )
        return PreparedOutbox(reference, snapshotBytes, manifestBytes, validated)
    }

    private fun writeSnapshotFile(directory: Path, prepared: PreparedOutbox) {
        val target = directory.resolve(PENDING_UPLOAD_OUTBOX_SNAPSHOT)
        validateContainedGeneratedFile(target, directory, "pending upload snapshot")
        val temporary = directory.resolve(PENDING_UPLOAD_OUTBOX_SNAPSHOT_TEMP)
        validateContainedGeneratedFile(temporary, directory, "pending upload snapshot staging")
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            rejectSymlink(target, "pending upload snapshot")
            rejectHardLink(target, "pending upload snapshot")
            val existing = readExact(
                target,
                prepared.snapshotBytes.size.toLong(),
                Stage5Limits.MAX_JSON_BYTES.toLong(),
                "pending upload snapshot"
            )
            if (!existing.contentEquals(prepared.snapshotBytes)) {
                throw IOException("published pending upload snapshot differs; evidence retained")
            }
            return
        }
        deleteContainedFileIfPresent(temporary, directory)
        writeDurableNew(temporary, prepared.snapshotBytes, "pending upload snapshot staging")
        try {
            atomicMove(directory, temporary, target)
        } catch (error: AtomicMoveNotSupportedException) {
            throw IOException("atomic pending upload snapshot publication is unavailable", error)
        }
        forceDirectory(directory)
    }

    private fun writePhotoFiles(directory: Path, prepared: PreparedOutbox) {
        prepared.photos.toSortedMap().forEach { (name, photo) ->
            val record = prepared.recordFor(name)
            val target = directory.resolve(record.fileName!!)
            validateContainedGeneratedFile(target, directory, "pending upload photo")
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                rejectSymlink(target, "pending upload photo")
                rejectHardLink(target, "pending upload photo")
                validateFileBackedPhoto(target, directory, photo.descriptor, "pending upload photo: $name")
                return@forEach
            }
            val temporary = directory.resolve(".${record.fileName}.tmp")
            validateContainedGeneratedFile(temporary, directory, "pending upload photo staging")
            deleteContainedFileIfPresent(temporary, directory)
            try {
                writeDurableNewStreaming(
                    path = temporary,
                    label = "pending upload photo staging: $name",
                    writer = { output ->
                        copyPhotoAsset(photo, output).also { written ->
                            if (written != photo.descriptor.byteCount) {
                                throw IOException("pending upload photo byte count changed: $name")
                            }
                        }
                    },
                    afterWrite = { resolver ->
                        // Keep creation, fsync, and publication on one opened
                        // descriptor-relative root. Some Android providers do
                        // not make a newly-created child visible to a second
                        // SecureDirectoryStream before rename, which otherwise
                        // reports a false NoSuchFileException after all bytes
                        // were durably written.
                        atomicMove(resolver, temporary, target)
                    }
                )
            } catch (error: AtomicMoveNotSupportedException) {
                throw IOException("atomic pending upload photo publication is unavailable", error)
            } catch (_: FileAlreadyExistsException) {
                // A concurrent publisher may have won the immutable target.
                // Revalidate its bytes; never replace or truncate it.
                validateFileBackedPhoto(target, directory, photo.descriptor, "pending upload photo: $name")
            }
            forceDirectory(directory)
        }
    }

    private fun validatePublished(
        scope: SyncScope,
        pending: DurablePendingUpload,
        reference: PendingUploadSidecarReference,
        directory: Path
    ) {
        validatePublishedDirectoryPath(directory)
        val manifest = readManifest(directory)
        val manifestBytes = readManifestBytes(directory)
        if (sha256Hex(manifestBytes) != reference.manifestSha256) {
            throw IllegalArgumentException("pending upload manifest hash mismatch")
        }
        validatePendingReference(scope, reference, manifest)
        validatePublishedFileSet(directory, manifest)
        val snapshotBytes = readExact(
            directory.resolve(PENDING_UPLOAD_OUTBOX_SNAPSHOT),
            reference.snapshotByteCount,
            Stage5Limits.MAX_JSON_BYTES.toLong(),
            "pending upload snapshot"
        )
        val expectedSnapshotBytes = encodeBoundedJson(
            gson,
            pending.snapshot,
            Stage5Limits.MAX_JSON_BYTES,
            "pending upload snapshot"
        )
        if (!snapshotBytes.contentEquals(expectedSnapshotBytes) ||
            sha256Hex(snapshotBytes) != reference.snapshotSha256
        ) {
            throw IllegalArgumentException("pending upload snapshot changed")
        }
        validatePendingIdentity(
            scope,
            reference,
            pending.reason,
            pending.sourceUri,
            pending.sourceFingerprint,
            pending.generation,
            pending.expectedCursor,
            pending.snapshot,
            directory
        )
        val records = manifest.photoFiles.orEmpty()
        val expected = pending.photoFiles.keys
        if (records.keys != expected) throw IllegalArgumentException("pending upload sidecar photo keys changed")
        val descriptors = records.mapValues { (name, record) -> descriptorFor(record, name) }
        // Validate both the caller-owned immutable source and the published
        // destination. Neither validation materializes the complete set.
        validatePhotoAssets(pending.snapshot, pending.photoFiles, descriptors)
        validatePhotoAssets(
            pending.snapshot,
            reopenPhotoAssets(directory, records),
            descriptors
        )
        val contentId = contentIdentity(
            scope,
            pending.reason,
            pending.sourceUri,
            pending.sourceFingerprint,
            pending.generation,
            pending.expectedCursor,
            snapshotHash(pending.snapshot),
            descriptors
        )
        if (contentId != reference.contentId || contentId != manifest.contentId) {
            throw IllegalArgumentException("pending upload sidecar content identity mismatch")
        }
    }

    private fun validatePendingReference(
        scope: SyncScope,
        reference: PendingUploadSidecarReference,
        manifest: SidecarManifest
    ) {
        if (manifest.schemaVersion != PENDING_UPLOAD_OUTBOX_SCHEMA_VERSION ||
            manifest.accountId != scope.accountId ||
            manifest.backupRootId != scope.backupRootId ||
            manifest.documentId != scope.documentId.value ||
            manifest.contentId != reference.contentId ||
            manifest.snapshotSha256 != reference.snapshotSha256 ||
            manifest.snapshotByteCount != reference.snapshotByteCount ||
            manifest.photoFiles?.size != reference.photoCount ||
            manifest.totalPhotoBytes != reference.totalPhotoBytes
        ) {
            throw IllegalArgumentException("pending upload sidecar reference mismatch")
        }
    }

    private fun validatePublishedFileSet(directory: Path, manifest: SidecarManifest) {
        val expected = LinkedHashSet<String>()
        expected += PENDING_UPLOAD_OUTBOX_MANIFEST
        expected += PENDING_UPLOAD_OUTBOX_SNAPSHOT
        val photoFileNames = HashSet<String>()
        manifest.photoFiles.orEmpty().values.forEach { record ->
            val fileName = record.fileName
                ?: throw IllegalArgumentException("pending upload manifest photo filename is missing")
            validateGeneratedPhotoFileName(fileName)
            if (!photoFileNames.add(fileName)) {
                throw IllegalArgumentException("pending upload manifest reuses a photo filename")
            }
            expected += fileName
        }
        val actual = ArrayList<String>(expected.size)
        Files.list(directory).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                if (actual.size >= expected.size + 4) {
                    throw IOException("pending upload content directory has too many entries")
                }
                actual += iterator.next().fileName.toString()
            }
        }
        if (actual.toSet() != expected) {
            throw IOException("pending upload published content has unexpected or missing files")
        }
        withPayloadResolver(directory) { resolver ->
            expected.forEach { name ->
                if (!resolver.isRegularFile(directory.resolve(name))) {
                    throw IOException("pending upload published entry is not a regular file: $name")
                }
                rejectHardLink(directory.resolve(name), "pending upload published entry $name")
            }
        }
    }

    private fun validatePendingIdentity(
        scope: SyncScope,
        reference: PendingUploadSidecarReference,
        reason: SyncReason,
        sourceUri: String,
        sourceFingerprint: com.example.myapplication.stage2.SourceFingerprint?,
        generation: Long,
        expectedCursor: RemoteCursor?,
        snapshot: DocumentSnapshotV1,
        directory: Path
    ) {
        val manifest = readManifest(directory)
        if (manifest.schemaVersion != PENDING_UPLOAD_OUTBOX_SCHEMA_VERSION ||
            manifest.contentId != reference.contentId ||
            manifest.reason != reason.name ||
            manifest.sourceUri != sourceUri ||
            manifest.sourceFingerprint != sourceFingerprint?.toDriveProperty() ||
            manifest.generation != generation ||
            manifest.expectedRevision != expectedCursor?.revision ||
            manifest.expectedModifiedTimeMillis != expectedCursor?.modifiedTimeMillis ||
            manifest.snapshotSha256 != snapshotHash(snapshot)
        ) {
            throw IllegalArgumentException("pending upload sidecar identity mismatch")
        }
        val records = manifest.photoFiles.orEmpty()
        if (records.size != reference.photoCount || manifest.totalPhotoBytes != reference.totalPhotoBytes) {
            throw IllegalArgumentException("pending upload sidecar aggregate metadata mismatch")
        }
        if (records.keys != requiredPhotoNames(snapshot)) {
            throw IllegalArgumentException("pending upload sidecar references changed")
        }
    }

    private fun readManifest(directory: Path): SidecarManifest {
        val bytes = readManifestBytes(directory)
        val tree = parseBoundedJsonObject(
            ByteArrayInputStream(bytes),
            Stage5Limits.MAX_METADATA_BYTES,
            "pending upload outbox manifest"
        )
        validateManifestTree(tree)
        return gson.fromJson(tree, SidecarManifest::class.java)
            ?: throw IllegalArgumentException("pending upload outbox manifest is empty")
    }

    private fun readManifestBytes(directory: Path): ByteArray {
        val manifest = directory.resolve(PENDING_UPLOAD_OUTBOX_MANIFEST)
        validateContainedGeneratedFile(manifest, directory, "pending upload manifest")
        rejectHardLink(manifest, "pending upload manifest")
        return readBoundedExact(manifest, Stage5Limits.MAX_METADATA_BYTES, "pending upload outbox manifest")
    }

    private fun validateManifestTree(root: JsonObject) {
        val allowed = setOf(
            "schemaVersion", "accountId", "backupRootId", "documentId", "contentId",
            "reason", "sourceUri", "sourceFingerprint", "generation", "expectedRevision",
            "expectedModifiedTimeMillis", "snapshotSha256", "snapshotByteCount",
            "photoFiles", "totalPhotoBytes"
        )
        rejectUnknownFields(root, allowed, "pending upload outbox manifest")
        root.keySet().forEach { key ->
            if (root.get(key).isJsonNull) throw IllegalArgumentException("pending upload manifest $key is null")
        }
        requireNumber(root, "schemaVersion", exact = PENDING_UPLOAD_OUTBOX_SCHEMA_VERSION)
        requireString(root, "accountId", required = true, maxChars = Stage5Limits.MAX_STRING_CHARS)
        requireString(root, "backupRootId", required = true, maxChars = Stage5Limits.MAX_STRING_CHARS)
        requireString(root, "documentId", required = true, maxChars = Stage5Limits.MAX_ID_CHARS)
        requireHash(root, "contentId")
        requireString(root, "reason", required = true, maxChars = Stage5Limits.MAX_STRING_CHARS)
        requireString(root, "sourceUri", required = true, maxChars = Stage5Limits.MAX_STRING_CHARS)
        optionalString(root, "sourceFingerprint", Stage5Limits.MAX_STRING_CHARS)?.let {
            if (com.example.myapplication.stage5.validateSourceFingerprintProperty(it, "pending upload manifest fingerprint") == null) {
                throw IllegalArgumentException("pending upload manifest fingerprint is missing")
            }
        }
        requireNumber(root, "generation", min = 1L)
        optionalString(root, "expectedRevision", Stage5Limits.MAX_STRING_CHARS)
        optionalLong(root, "expectedModifiedTimeMillis")?.let {
            if (it < 0L) throw IllegalArgumentException("pending upload manifest expected time is invalid")
            if (!root.has("expectedRevision")) throw IllegalArgumentException("pending upload manifest expected time has no revision")
        }
        requireHash(root, "snapshotSha256")
        requireNumber(root, "snapshotByteCount", min = 1L, max = Stage5Limits.MAX_JSON_BYTES.toLong())
        val photoFiles = requireObject(root, "photoFiles")
        if (photoFiles.size() !in 0..Stage5Limits.MAX_TOTAL_PHOTOS) {
            throw IllegalArgumentException("pending upload manifest photo count is out of range")
        }
        var total = 0L
        photoFiles.entrySet().forEach { (name, element) ->
            validatePhotoFileName(name)
            val record = requireObjectElement(element, "pending upload manifest photo $name")
            validatePhotoRecordTree(record, name)
            total += requireNumber(record, "byteCount", min = 1L, max = Stage5Limits.MAX_PHOTO_BYTES.toLong())
            if (total > Stage5Limits.MAX_TOTAL_PHOTO_BYTES) {
                throw IllegalArgumentException("pending upload manifest photo bytes exceed aggregate limit")
            }
        }
        val declaredTotal = requireNumber(root, "totalPhotoBytes", min = 0L, max = Stage5Limits.MAX_TOTAL_PHOTO_BYTES)
        if (declaredTotal != total) throw IllegalArgumentException("pending upload manifest total bytes mismatch")
    }

    private fun validatePhotoRecordTree(record: JsonObject, name: String) {
        val allowed = setOf("fileName", "byteCount", "sha256", "mimeType", "width", "height")
        rejectUnknownFields(record, allowed, "pending upload manifest photo $name")
        requireString(record, "fileName", required = true, maxChars = 128).also { validateGeneratedPhotoFileName(it) }
        requireNumber(record, "byteCount", min = 1L, max = Stage5Limits.MAX_PHOTO_BYTES.toLong())
        requireHash(record, "sha256")
        val mime = requireString(record, "mimeType", required = true, maxChars = Stage5Limits.MAX_STRING_CHARS)
        val width = requireNumber(record, "width", min = 1L, max = Stage5Limits.MAX_IMAGE_WIDTH.toLong()).toInt()
        val height = requireNumber(record, "height", min = 1L, max = Stage5Limits.MAX_IMAGE_HEIGHT.toLong()).toInt()
        ImageInfo(mime, width, height)
        if (width.toLong() * height.toLong() > Stage5Limits.MAX_IMAGE_PIXELS) {
            throw IllegalArgumentException("pending upload manifest image pixels exceed limit")
        }
    }

    private fun descriptorFor(record: SidecarPhotoRecord?, name: String): PhotoDescriptor {
        record ?: throw IllegalArgumentException("pending upload manifest photo record is missing: $name")
        val fileName = record.fileName ?: throw IllegalArgumentException("pending upload manifest photo filename is missing: $name")
        validateGeneratedPhotoFileName(fileName)
        val byteCount = record.byteCount ?: throw IllegalArgumentException("pending upload manifest byte count is missing: $name")
        val hash = record.sha256 ?: throw IllegalArgumentException("pending upload manifest hash is missing: $name")
        val mime = record.mimeType ?: throw IllegalArgumentException("pending upload manifest MIME is missing: $name")
        val width = record.width ?: throw IllegalArgumentException("pending upload manifest width is missing: $name")
        val height = record.height ?: throw IllegalArgumentException("pending upload manifest height is missing: $name")
        if (fileName != generatedPhotoFileName(name, hash)) {
            throw IllegalArgumentException("pending upload manifest filename does not match its photo identity: $name")
        }
        return PhotoDescriptor(byteCount, hash, mime, width, height)
    }

    private fun isReferenceComplete(
        scope: SyncScope,
        authority: AuthoritativeMetadata
    ): Boolean {
        return try {
            val reference = authority.reference ?: return false
            val reason = authority.reason ?: return false
            val sourceUri = authority.sourceUri ?: return false
            val generation = authority.generation ?: return false
            val loaded = load(
                scope = scope,
                reference = reference,
                reason = reason,
                sourceUri = sourceUri,
                sourceFingerprint = authority.sourceFingerprint,
                generation = generation,
                expectedCursor = authority.expectedCursor
            )
            loaded.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun readAuthoritativeMetadata(scope: SyncScope, metadataFile: File): AuthoritativeMetadata? {
        return try {
            if (!metadataFile.isFile || Files.isSymbolicLink(metadataFile.toPath())) return null
            val tree = metadataFile.inputStream().use {
                parseBoundedJsonObject(it, Stage5Limits.MAX_METADATA_BYTES, "sync metadata reconciliation")
            }
            validateCurrentMetadataWire(tree)
            if (tree.get("accountId").asString != scope.accountId ||
                tree.get("backupRootId").asString != scope.backupRootId ||
                tree.get("documentId").asString != scope.documentId.value
            ) return null
            val sidecar = tree.getAsJsonObject(PENDING_UPLOAD_OUTBOX_METADATA_FIELD)
            val reference = sidecar?.let {
                    PendingUploadSidecarReference(
                        schemaVersion = requireNumber(it, "schemaVersion", exact = PENDING_UPLOAD_OUTBOX_SCHEMA_VERSION).toInt(),
                        contentId = requireHash(it, "contentId"),
                        manifestSha256 = requireHash(it, "manifestSha256"),
                        snapshotSha256 = requireHash(it, "snapshotSha256"),
                        snapshotByteCount = requireNumber(it, "snapshotByteCount", min = 1L, max = Stage5Limits.MAX_JSON_BYTES.toLong()),
                        photoCount = requireNumber(it, "photoCount", min = 0L, max = Stage5Limits.MAX_TOTAL_PHOTOS.toLong()).toInt(),
                        totalPhotoBytes = requireNumber(it, "totalPhotoBytes", min = 0L, max = Stage5Limits.MAX_TOTAL_PHOTO_BYTES)
                    )
                }
            if (reference == null) {
                return AuthoritativeMetadata(reference = null)
            }
            val sourceUri = tree.get("pendingUploadSourceUri")?.asString
                ?: return null
            val reason = tree.get("pendingUploadReason")?.asString
                ?.let { runCatching { SyncReason.valueOf(it) }.getOrNull() }
                ?: return null
            val generation = tree.get("pendingUploadGeneration")?.asLong
                ?: return null
            val sourceFingerprint = tree.get("pendingUploadSourceFingerprint")?.asString?.let {
                sourceFingerprintFromDriveProperty(it)
                    ?: return null
            }
            val expectedCursor = tree.get("pendingUploadExpectedRevision")?.asString?.let {
                RemoteCursor(it, tree.get("pendingUploadExpectedModifiedTimeMillis")?.asLong)
            }
            if (reason == SyncReason.REMOTE_CHECK || reason == SyncReason.REMOTE_ACCEPTANCE ||
                generation <= 0L
            ) {
                return null
            }
            AuthoritativeMetadata(
                reference = reference,
                reason = reason,
                sourceUri = sourceUri,
                sourceFingerprint = sourceFingerprint,
                generation = generation,
                expectedCursor = expectedCursor
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun hasPublishedManifest(directory: Path): Boolean =
        withPayloadResolver(directory) { resolver ->
            resolver.isRegularFile(directory.resolve(PENDING_UPLOAD_OUTBOX_MANIFEST))
        }

    private fun existingScopeDirectory(scope: SyncScope): Path =
        existingScopeDirectoryOrNull(scope)
            ?: throw IOException("pending upload outbox scope directory is missing")

    private fun existingScopeDirectoryOrNull(scope: SyncScope): Path? {
        val directory = scopeDirectory(scope)
        validatePathContainment(directory)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return null
        rejectSymlink(directory, "pending upload outbox scope directory")
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("pending upload outbox scope path is not a directory")
        }
        return directory
    }

    private fun ensureScopeDirectory(scope: SyncScope): Path {
        ensureDirectory(rootDirectory.toPath().toAbsolutePath().normalize())
        val outbox = outboxDirectory()
        ensureDirectory(outbox)
        val scopeDirectory = scopeDirectory(scope)
        ensureDirectory(scopeDirectory)
        return scopeDirectory
    }

    /**
     * When metadata authority is absent or unreadable, preserve every existing
     * candidate but refuse to create an unbounded stream of new candidates.
     * A retry for an existing deterministic content ID still remains allowed.
     */
    private fun enforceUncertainRetentionBound(scopeDirectory: Path, incomingBytes: Long) {
        var contentDirectories = 0
        var retainedBytes = 0L
        Files.list(scopeDirectory).use { stream ->
            stream.forEach { child ->
                if (child.fileName.toString().matches(HEX_SHA256)) {
                    if (Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        throw IOException("pending upload retention candidate is not a safe directory")
                    }
                    contentDirectories++
                    val files = Files.newDirectoryStream(child).use { entries -> boundedNames(entries) }
                    withPayloadResolver(child) { resolver ->
                        files.forEach { name ->
                            val file = child.resolve(name)
                            if (!resolver.isRegularFile(file)) {
                                throw IOException("pending upload retention candidate contains an unsafe entry")
                            }
                            retainedBytes = Math.addExact(retainedBytes, resolver.size(file, "pending upload retention $name"))
                        }
                    }
                }
            }
        }
        if (contentDirectories >= MAX_UNCERTAIN_CONTENT_DIRECTORIES) {
            throw IOException("pending upload outbox retention bound reached while metadata authority is uncertain")
        }
        val maximumRetainedBytes = MAX_UNCERTAIN_CONTENT_DIRECTORIES.toLong() *
            (Stage5Limits.MAX_JSON_BYTES.toLong() + Stage5Limits.MAX_TOTAL_PHOTO_BYTES)
        if (incomingBytes < 0L || Math.addExact(retainedBytes, incomingBytes) > maximumRetainedBytes) {
            throw IOException("pending upload outbox byte retention bound reached while metadata authority is uncertain")
        }
    }

    private fun outboxDirectory(): Path = rootDirectory.toPath().toAbsolutePath().normalize()
        .resolve(PENDING_UPLOAD_OUTBOX_DIRECTORY)

    private fun scopeDirectory(scope: SyncScope): Path =
        outboxDirectory().resolve(scopeHash(scope))

    private fun contentDirectory(scopeDirectory: Path, contentId: String): Path {
        if (!contentId.matches(HEX_SHA256)) throw IllegalArgumentException("pending upload content identity is invalid")
        return scopeDirectory.resolve(contentId)
    }

    private fun scopeHash(scope: SyncScope): String =
        sha256Hex(scopeKey(scope).toByteArray(StandardCharsets.UTF_8))

    private fun scopeKey(scope: SyncScope): String =
        "${scope.accountId}\u0000${scope.backupRootId}\u0000${scope.documentId.value}"

    private fun contentIdentity(
        scope: SyncScope,
        reason: SyncReason,
        sourceUri: String,
        sourceFingerprint: com.example.myapplication.stage2.SourceFingerprint?,
        generation: Long,
        expectedCursor: RemoteCursor?,
        snapshotSha256: String,
        descriptors: Map<String, PhotoDescriptor>
    ): String {
        val identity = StringBuilder("pending-upload-outbox-v1\n")
        appendIdentity(identity, scope.accountId)
        appendIdentity(identity, scope.backupRootId)
        appendIdentity(identity, scope.documentId.value)
        appendIdentity(identity, reason.name)
        appendIdentity(identity, sourceUri)
        appendIdentity(identity, sourceFingerprint?.toDriveProperty().orEmpty())
        appendIdentity(identity, generation.toString())
        appendIdentity(identity, expectedCursor?.revision.orEmpty())
        appendIdentity(identity, expectedCursor?.modifiedTimeMillis?.toString().orEmpty())
        appendIdentity(identity, snapshotSha256)
        descriptors.toSortedMap().forEach { (name, descriptor) ->
            appendIdentity(identity, name)
            appendIdentity(identity, descriptor.byteCount.toString())
            appendIdentity(identity, descriptor.sha256)
            appendIdentity(identity, descriptor.mimeType)
            appendIdentity(identity, descriptor.width.toString())
            appendIdentity(identity, descriptor.height.toString())
        }
        return sha256Hex(identity.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun appendIdentity(builder: StringBuilder, value: String) {
        builder.append(value.length).append(':').append(value).append('|')
    }

    private fun validateGeneratedPhotoFileName(name: String) {
        if (!name.matches(Regex("p-[0-9a-f]{64}\\.bin"))) {
            throw IllegalArgumentException("unsafe pending upload generated filename")
        }
    }

    private fun snapshotHash(snapshot: DocumentSnapshotV1): String =
        sha256Hex(encodeBoundedJson(gson, snapshot, Stage5Limits.MAX_JSON_BYTES, "pending upload snapshot"))

    private inline fun <T> withPayloadResolver(directory: Path, block: (PhotoPathResolver) -> T): T {
        validatePublishedDirectoryPath(directory)
        val resolver = if (payloadOperationsFactory == null) {
            PhotoPathResolver(
                directory.toFile(),
                createRoot = false,
                trustedRootDirectory = rootDirectory.absoluteFile.parentFile ?: rootDirectory
            )
        } else {
            PhotoPathResolver(
                directory.toFile(),
                false,
                payloadOperationsFactory,
                rootDirectory.absoluteFile.parentFile ?: rootDirectory
            )
        }
        return try {
            block(resolver)
        } finally {
            resolver.close()
        }
    }

    private fun atomicMove(directory: Path, source: Path, target: Path) {
        validateContainedGeneratedFile(source, directory, "pending upload move source")
        validateContainedGeneratedFile(target, directory, "pending upload move target")
        withPayloadResolver(directory) { resolver -> atomicMove(resolver, source, target) }
    }

    private fun atomicMove(resolver: PhotoPathResolver, source: Path, target: Path) {
        try {
            resolver.atomicMove(source, target, replaceExisting = false)
        } catch (error: IOException) {
            when (val cause = error.cause) {
                is AtomicMoveNotSupportedException -> throw cause
                is FileAlreadyExistsException -> throw cause
                else -> throw error
            }
        }
    }

    private fun writeDurableNew(path: Path, bytes: ByteArray, label: String) {
        val directory = path.parent ?: throw IOException("$label has no parent directory")
        validateContainedGeneratedFile(path, directory, label)
        withPayloadResolver(directory) { resolver ->
            if (resolver.exists(path)) throw FileAlreadyExistsException(path.toString())
            resolver.openNewOutput(path, label).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        }
    }

    /** Streams one immutable asset through the same CREATE_NEW/fsync seam. */
    private fun writeDurableNewStreaming(
        path: Path,
        label: String,
        writer: (OutputStream) -> Unit,
        afterWrite: ((PhotoPathResolver) -> Unit)? = null
    ) {
        val directory = path.parent ?: throw IOException("$label has no parent directory")
        validateContainedGeneratedFile(path, directory, label)
        withPayloadResolver(directory) { resolver ->
            // CREATE_NEW rejects every incumbent, including hard links. Do not
            // stat an absent staging name for nlink before creating it: native
            // providers correctly report NoSuchFileException for that name.
            if (resolver.exists(path)) throw FileAlreadyExistsException(path.toString())
            resolver.openNewOutput(path, label).use { channel ->
                val output = Channels.newOutputStream(channel)
                writer(output)
                output.flush()
                channel.force(true)
            }
            afterWrite?.invoke(resolver)
        }
    }

    /** Reopens files only through a fresh descriptor-relative secure resolver. */
    private fun reopenPhotoAssets(
        directory: Path,
        records: Map<String, SidecarPhotoRecord>
    ): PhotoAssetSet {
        val descriptors = records.mapValues { (name, record) -> descriptorFor(record, name) }
        return photoAssetsFromDescriptors(descriptors) { occurrenceName ->
            val descriptor = descriptors[occurrenceName]
                ?: throw IOException("pending upload photo occurrence is missing: $occurrenceName")
            openOutboxAssetStream(
                directory,
                generatedPhotoFileName(occurrenceName, descriptor.sha256)
            )
        }
    }

    private fun openOutboxAssetStream(directory: Path, name: String): InputStream {
        validateGeneratedPhotoFileName(name)
        val resolver = if (payloadOperationsFactory == null) {
            PhotoPathResolver(
                directory.toFile(),
                createRoot = false,
                trustedRootDirectory = rootDirectory.absoluteFile.parentFile ?: rootDirectory
            )
        } else {
            PhotoPathResolver(
                directory.toFile(),
                createRoot = false,
                operationsFactory = payloadOperationsFactory,
                trustedRootDirectory = rootDirectory.absoluteFile.parentFile ?: rootDirectory
            )
        }
        return try {
            val path = directory.resolve(name).toAbsolutePath().normalize()
            resolver.ensureContained(path, "pending upload photo $name")
            if (!resolver.isRegularFile(path)) {
                resolver.close()
                throw IOException("pending upload photo is not a regular file: $name")
            }
            val input = resolver.openRead(path, "pending upload photo $name")
            object : java.io.FilterInputStream(input) {
                override fun close() {
                    var failure: Throwable? = null
                    try {
                        super.close()
                    } catch (error: Throwable) {
                        failure = error
                    }
                    try {
                        resolver.close()
                    } catch (error: Throwable) {
                        if (failure == null) failure = error else failure?.addSuppressed(error)
                    }
                    failure?.let { throw it }
                }
            }
        } catch (error: Throwable) {
            try {
                resolver.close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
            }
            throw error
        }
    }

    private fun validateFileBackedPhoto(
        path: Path,
        directory: Path,
        descriptor: PhotoDescriptor,
        label: String
    ) {
        val fileName = path.fileName?.toString()
            ?: throw IOException("$label has no filename")
        validateContainedGeneratedFile(path, directory, label)
        rejectHardLink(path, label)
        val asset = object : PhotoAsset {
            override val descriptor: PhotoDescriptor = descriptor
            override fun open(): InputStream = openOutboxAssetStream(directory, fileName)
        }
        copyPhotoAsset(asset, DiscardOutputStream)
    }

    private fun readExact(path: Path, expectedBytes: Long, label: String): ByteArray =
        readExact(path, expectedBytes, Stage5Limits.MAX_PHOTO_BYTES.toLong(), label)

    private fun readExact(
        path: Path,
        expectedBytes: Long,
        maximumBytes: Long,
        label: String
    ): ByteArray {
        if (expectedBytes !in 1L..maximumBytes || maximumBytes > Int.MAX_VALUE) {
            throw IllegalArgumentException("$label has an invalid byte count")
        }
        val bytes = readBoundedExact(path, expectedBytes.toInt(), label)
        if (bytes.size.toLong() != expectedBytes) throw IllegalArgumentException("$label byte count changed")
        return bytes
    }

    private fun readBoundedExact(path: Path, maxBytes: Int, label: String): ByteArray {
        val directory = path.parent ?: throw IOException("$label has no parent directory")
        validateContainedGeneratedFile(path, directory, label)
        withPayloadResolver(directory) { resolver ->
            if (!resolver.isRegularFile(path)) throw IOException("$label is missing")
            return resolver.openRead(path, label).use {
                com.example.myapplication.stage5.readBoundedBytes(it, maxBytes, label)
            }
        }
    }

    private fun ensureDirectory(path: Path) {
        validatePathContainment(path, allowRoot = true)
        ensureNoSymlinkAncestors(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(path)
        rejectSymlink(path, "pending upload directory")
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("pending upload path is not a directory")
        }
    }

    private fun validatePublishedDirectoryPath(path: Path) {
        validatePathContainment(path)
        ensureNoSymlinkAncestors(path)
        rejectSymlink(path, "pending upload content directory")
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw IOException("pending upload content directory is missing")
    }

    private fun validateContainedGeneratedFile(path: Path, directory: Path, label: String) {
        if (path.parent != directory.toAbsolutePath().normalize()) {
            throw IllegalArgumentException("$label escaped its content directory")
        }
        validatePathContainment(path)
        ensureNoSymlinkAncestors(path)
        rejectSymlink(path, label)
    }

    private fun validatePathContainment(path: Path, allowRoot: Boolean = false) {
        val root = rootDirectory.toPath().toAbsolutePath().normalize()
        val candidate = path.toAbsolutePath().normalize()
        if (!candidate.startsWith(root) || (!allowRoot && candidate == root)) {
            throw IllegalArgumentException("pending upload path escaped its root")
        }
    }

    private fun ensureNoSymlinkAncestors(path: Path) {
        val root = rootDirectory.toPath().toAbsolutePath().normalize()
        var current: Path? = path.toAbsolutePath().normalize()
        while (current != null && current!!.startsWith(root)) {
            if (Files.isSymbolicLink(current)) throw IOException("pending upload path contains a symbolic link")
            if (current == root) return
            current = current.parent
        }
        throw IllegalArgumentException("pending upload path escaped its root")
    }

    private fun rejectSymlink(path: Path, label: String) {
        if (Files.isSymbolicLink(path)) throw IOException("$label is a symbolic link")
    }

    /** A pre-existing hard link would let an anchored name alias outside data. */
    private fun rejectHardLink(path: Path, label: String) {
        try {
            val links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)
            if ((links as? Number)?.toLong()?.let { it > 1L } == true) {
                throw IOException("$label is a hard link")
            }
        } catch (_: UnsupportedOperationException) {
            // Providers without unix link-count attributes still get the
            // descriptor-relative/no-follow checks; they cannot report a
            // positive hard-link count here.
        } catch (_: IllegalArgumentException) {
            // Same capability boundary on providers without unix attributes.
        }
    }

    private fun deleteContainedFileIfPresent(path: Path, directory: Path) {
        validateContainedGeneratedFile(path, directory, "pending upload temporary file")
        withPayloadResolver(directory) { resolver ->
            if (resolver.exists(path)) resolver.deletePath(path, "pending upload temporary file")
        }
    }

    private fun deleteContainedFile(path: Path, directory: Path) {
        validateContainedGeneratedFile(path, directory, "pending upload temporary file")
        withPayloadResolver(directory) { resolver ->
            resolver.deletePath(path, "pending upload temporary file")
        }
    }

    private fun deleteContainedTree(path: Path, parent: Path) {
        validatePathContainment(path)
        if (path.parent != parent.toAbsolutePath().normalize()) return
        ensureNoSymlinkAncestors(path)
        val parentDirectory = openAnchoredDirectory(parent)
        try {
            val childName = path.fileName?.toString()
                ?: throw IOException("pending upload content directory has no name")
            val child = parentDirectory.openDirectory(childName)
            try {
                val names = validateSecureCleanupTree(child)
                names.forEach { name ->
                    child.deleteFile(name)
                }
            } finally {
                child.close()
            }
            parentDirectory.deleteDirectory(childName)
        } finally {
            parentDirectory.close()
        }
    }

    /**
     * The cleanup walk is two-phase: unknown entries are rejected before any
     * deletion, and the second phase uses only the already-open descriptors.
     * This keeps an orphan candidate's cleanup from becoming a recursive path
     * delete or an unbounded purge of ambiguous evidence.
     */
    private fun validateSecureCleanupTree(directory: OutboxDirectoryStream): List<String> {
        val names = directory.listNames(Stage5Limits.MAX_TOTAL_PHOTOS + 8)
        names.forEach { name ->
            val attributes = directory.readAttributes(name)
            if (attributes.isSymbolicLink || (!attributes.isRegularFile && !attributes.isDirectory)) {
                throw IOException("pending upload cleanup candidate contains an unsafe entry")
            }
            if (attributes.isDirectory) {
                throw IOException("pending upload cleanup candidate contains an unexpected directory")
            }
            if (!isKnownCleanupFileName(name)) {
                throw IOException("pending upload cleanup candidate contains an unexpected file")
            }
        }
        return names
    }

    private fun boundedNames(directory: Iterable<Path>): List<String> {
        val names = ArrayList<String>()
        for (entry in directory) {
            if (names.size >= Stage5Limits.MAX_TOTAL_PHOTOS + 8) {
                throw IOException("pending upload directory has too many entries")
            }
            names.add(entry.fileName.toString())
        }
        return names
    }

    private fun isKnownCleanupFileName(name: String): Boolean =
        name == PENDING_UPLOAD_OUTBOX_MANIFEST ||
            name == PENDING_UPLOAD_OUTBOX_MANIFEST_TEMP ||
            name == PENDING_UPLOAD_OUTBOX_SNAPSHOT ||
            name == PENDING_UPLOAD_OUTBOX_SNAPSHOT_TEMP ||
            name.matches(Regex("p-[0-9a-f]{64}\\.bin")) ||
            name.matches(Regex("\\.p-[0-9a-f]{64}\\.bin\\.tmp"))

    private fun openAnchoredDirectory(path: Path): OutboxDirectoryStream {
        val boundary = rootDirectory.toPath().toAbsolutePath().normalize().parent
            ?: throw IOException("pending upload metadata root has no trusted parent")
        val absolute = path.toAbsolutePath().normalize()
        if (!absolute.startsWith(boundary)) {
            throw IOException("pending upload directory escaped its trusted root")
        }
        if (Files.isSymbolicLink(boundary) || !Files.isDirectory(boundary, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("pending upload trusted root is not a directory")
        }
        directoryStreamFactory?.let { factory ->
            return factory.open(absolute, boundary)
        }
        val fileSystem = absolute.fileSystem
        var current: SecureDirectoryStream<Path> = openSecureDirectory(boundary)
        try {
            boundary.relativize(absolute).forEach { component ->
                val next = current.newDirectoryStream(
                    fileSystem.getPath(component.toString()),
                    LinkOption.NOFOLLOW_LINKS
                )
                current.close()
                current = next
            }
            return SecureOutboxDirectoryStream(fileSystem, current)
        } catch (error: IOException) {
            try {
                current.close()
            } catch (_: Exception) {
            }
            throw IOException("pending upload secure directory could not be opened", error)
        } catch (error: SecurityException) {
            try {
                current.close()
            } catch (_: Exception) {
            }
            throw IOException("pending upload secure directory could not be opened", error)
        }
    }

    /** Native production adapter; its constructor is reached only after a
     * SecureDirectoryStream capability check and descriptor-relative walk. */
    private class SecureOutboxDirectoryStream(
        private val fileSystem: java.nio.file.FileSystem,
        private val directory: SecureDirectoryStream<Path>
    ) : OutboxDirectoryStream {
        private fun relative(name: String): Path {
            if (name.isEmpty() || name == "." || name == ".." ||
                name.contains('/') || name.contains('\\') || name.indexOf('\u0000') >= 0
            ) {
                throw IOException("pending upload cleanup requires one relative child name")
            }
            return fileSystem.getPath(name)
        }

        override fun listNames(maxEntries: Int): List<String> {
            require(maxEntries > 0) { "pending upload cleanup entry bound must be positive" }
            val names = ArrayList<String>()
            val iterator = directory.iterator()
            while (iterator.hasNext()) {
                if (names.size >= maxEntries) {
                    throw IOException("pending upload directory has too many entries")
                }
                names += iterator.next().fileName.toString()
            }
            return names
        }

        override fun readAttributes(name: String): BasicFileAttributes {
            val view = directory.getFileAttributeView(
                relative(name),
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS
            ) ?: throw IOException("pending upload cleanup attributes are unavailable")
            return view.readAttributes()
        }

        override fun openDirectory(name: String): OutboxDirectoryStream {
            val attributes = readAttributes(name)
            if (attributes.isSymbolicLink || !attributes.isDirectory) {
                throw IOException("pending upload cleanup entry is not a directory: $name")
            }
            val child = directory.newDirectoryStream(relative(name), LinkOption.NOFOLLOW_LINKS)
            return SecureOutboxDirectoryStream(fileSystem, child)
        }

        override fun deleteFile(name: String) {
            val attributes = readAttributes(name)
            if (attributes.isSymbolicLink || !attributes.isRegularFile) {
                throw IOException("pending upload cleanup entry is not a regular file: $name")
            }
            directory.deleteFile(relative(name))
        }

        override fun deleteDirectory(name: String) {
            val attributes = readAttributes(name)
            if (attributes.isSymbolicLink || !attributes.isDirectory) {
                throw IOException("pending upload cleanup entry is not a directory: $name")
            }
            directory.deleteDirectory(relative(name))
        }

        override fun close() {
            directory.close()
        }
    }

    private fun openSecureDirectory(path: Path): SecureDirectoryStream<Path> {
        val stream = Files.newDirectoryStream(path)
        if (stream !is SecureDirectoryStream<*>) {
            stream.close()
            throw IOException("pending upload provider lacks SecureDirectoryStream support")
        }
        @Suppress("UNCHECKED_CAST")
        return stream as SecureDirectoryStream<Path>
    }

    private fun forceDirectory(directory: Path) {
        // The desktop JVM provider cannot open directories as FileChannels.
        // This explicit platform capability does not suppress Android/Unix I/O failures.
        if (directory.fileSystem.provider().javaClass.name == "sun.nio.fs.WindowsFileSystemProvider") {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
                throw IOException("pending upload directory is not safe for publication")
            }
            return
        }
        try {
            java.nio.channels.FileChannel.open(directory, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        } catch (_: UnsupportedOperationException) {
            // Some Android filesystems do not expose directory fsync. The
            // individual file fsync and atomic marker remain mandatory.
        }
    }

    private fun lockFor(scope: SyncScope): Any =
        LOCKS.computeIfAbsent(scopeHash(scope)) { Any() }

    private fun isRetainedContent(scope: SyncScope, contentId: String): Boolean =
        PhotoAssetOwnershipRegistry.isOwnerClaimed(outboxOwnerKey(scope, contentId))

    private fun outboxOwnerKey(scope: SyncScope, contentId: String): String =
        outboxOwnerKeyForContent(scopeHash(scope) + ":" + contentId)

    private fun outboxOwnerKeyForContent(contentId: String): String =
        "pending-upload-outbox:${rootDirectory.toPath().toAbsolutePath().normalize()}:$contentId"

    private inline fun <T> withSourceLease(
        pending: DurablePendingUpload,
        block: () -> T
    ): T {
        val lease = PhotoAssetOwnershipRegistry.claim(
            "pending-upload-source:${rootDirectory.toPath().toAbsolutePath().normalize()}",
            pending.photoFiles
        )
        return try {
            block()
        } finally {
            lease.close()
        }
    }

    private data class PreparedOutbox(
        val reference: PendingUploadSidecarReference,
        val snapshotBytes: ByteArray,
        val manifestBytes: ByteArray,
        val photos: PhotoAssetSet
    ) {
        val totalBytes: Long
            get() = snapshotBytes.size.toLong() + photos.totalBytes

        fun recordFor(name: String): SidecarPhotoRecord = SidecarPhotoRecord(
            fileName = generatedPhotoFileName(name, photos.getValue(name).descriptor.sha256),
            byteCount = photos.getValue(name).descriptor.byteCount,
            sha256 = photos.getValue(name).descriptor.sha256,
            mimeType = photos.getValue(name).descriptor.mimeType,
            width = photos.getValue(name).descriptor.width,
            height = photos.getValue(name).descriptor.height
        )
    }

    private data class SidecarManifest(
        val schemaVersion: Int?,
        val accountId: String?,
        val backupRootId: String?,
        val documentId: String?,
        val contentId: String?,
        val reason: String?,
        val sourceUri: String?,
        val sourceFingerprint: String?,
        val generation: Long?,
        val expectedRevision: String?,
        val expectedModifiedTimeMillis: Long?,
        val snapshotSha256: String?,
        val snapshotByteCount: Long?,
        val photoFiles: Map<String, SidecarPhotoRecord>?,
        val totalPhotoBytes: Long?
    )

    private data class SidecarPhotoRecord(
        val fileName: String?,
        val byteCount: Long?,
        val sha256: String?,
        val mimeType: String?,
        val width: Int?,
        val height: Int?
    )

    private data class AuthoritativeMetadata(
        val reference: PendingUploadSidecarReference?,
        val reason: SyncReason? = null,
        val sourceUri: String? = null,
        val sourceFingerprint: com.example.myapplication.stage2.SourceFingerprint? = null,
        val generation: Long? = null,
        val expectedCursor: RemoteCursor? = null
    )

    private fun rejectUnknownFields(root: JsonObject, allowed: Set<String>, label: String) {
        root.keySet().firstOrNull { it !in allowed }?.let {
            throw IllegalArgumentException("$label contains unsupported field: $it")
        }
    }

    private fun validateCurrentMetadataWire(root: JsonObject) {
        com.example.myapplication.stage5.validateSyncMetadataTree(root)
        val version = root.get("schemaVersion")?.asInt
            ?: throw IllegalArgumentException("sync metadata schema version is missing")
        require(version == SYNC_METADATA_SCHEMA_VERSION) {
            "unsupported sync metadata schema: $version"
        }
        listOf("pendingUploadSnapshotJson", "pendingUploadPhotoFiles")
            .firstOrNull(root::has)
            ?.let { throw IllegalArgumentException("sync metadata contains retired inline field: $it") }
    }

    private fun requireObject(root: JsonObject, name: String): JsonObject {
        val element = root.get(name) ?: throw IllegalArgumentException("pending upload manifest $name is missing")
        return requireObjectElement(element, "pending upload manifest.$name")
    }

    private fun requireObjectElement(element: JsonElement, label: String): JsonObject =
        if (element.isJsonObject) element.asJsonObject else throw IllegalArgumentException("$label must be an object")

    private fun requireString(root: JsonObject, name: String, required: Boolean, maxChars: Int): String {
        val element = root.get(name)
        if (element == null) {
            if (required) throw IllegalArgumentException("pending upload manifest $name is missing")
            return ""
        }
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw IllegalArgumentException("pending upload manifest $name must be a string")
        }
        val value = element.asString
        requireBoundedString(value, "pending upload manifest $name", required = required, maxChars = maxChars)
        return value
    }

    private fun optionalString(root: JsonObject, name: String, maxChars: Int): String? {
        if (!root.has(name)) return null
        return requireString(root, name, required = true, maxChars = maxChars)
    }

    private fun optionalLong(root: JsonObject, name: String): Long? {
        if (!root.has(name)) return null
        return requireNumber(root, name)
    }

    private fun requireNumber(root: JsonObject, name: String, min: Long = Long.MIN_VALUE, max: Long = Long.MAX_VALUE, exact: Int? = null): Long {
        val element = root.get(name) ?: throw IllegalArgumentException("pending upload manifest $name is missing")
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw IllegalArgumentException("pending upload manifest $name must be an integer")
        }
        val value = element.asBigDecimal.longValueExact()
        if (value < min || value > max || (exact != null && value != exact.toLong())) {
            throw IllegalArgumentException("pending upload manifest $name is out of range")
        }
        return value
    }

    private fun requireHash(root: JsonObject, name: String): String {
        val value = requireString(root, name, required = true, maxChars = 64)
        if (!HEX_SHA256.matches(value)) throw IllegalArgumentException("pending upload manifest $name is invalid")
        return value
    }

    companion object {
        private val HEX_SHA256 = Regex("[0-9a-f]{64}")
        private val LOCKS = ConcurrentHashMap<String, Any>()
    }
}

private fun generatedPhotoFileName(name: String, hash: String): String =
        "p-${sha256Hex((name + "\u0000" + hash).toByteArray(StandardCharsets.UTF_8))}.bin"
