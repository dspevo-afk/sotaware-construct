package com.example.myapplication.stage6

import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage2.DocumentDurableSnapshotState
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DurableSnapshotSlot
import com.example.myapplication.stage2.LocalRepositoryError
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage3.SessionSnapshotApplyResult
import com.example.myapplication.stage4.PhotoContentTransaction
import com.example.myapplication.stage5.DefaultImageProbe
import com.example.myapplication.stage5.PhotoDecodeProbe
import com.example.myapplication.stage5.PhotoDescriptor
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.Stage5ValidationException
import com.example.myapplication.stage5.decodeValidatedSnapshotJson
import com.example.myapplication.stage5.encodeBoundedJson
import com.example.myapplication.stage5.requiredPhotoNames
import com.example.myapplication.stage5.sha256Hex
import com.example.myapplication.stage5.validatePhotoFileName
import com.example.myapplication.stage5.validatePhotoSet
import com.example.myapplication.stage5.validateSnapshot
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.CheckedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Resource limits for the self-contained local import/export format. */
object Stage6BundleLimits {
    const val MAX_MANIFEST_BYTES: Int = 1 * 1024 * 1024
    const val MAX_ARCHIVE_BYTES: Int = 180 * 1024 * 1024
    const val MAX_ENTRY_COUNT: Int = Stage5Limits.MAX_TOTAL_PHOTOS + 2
    const val MAX_COMPRESSION_RATIO: Long = 100L
    const val MAX_TOTAL_UNCOMPRESSED_BYTES: Long =
        MAX_MANIFEST_BYTES.toLong() +
            Stage5Limits.MAX_JSON_BYTES.toLong() +
            Stage5Limits.MAX_TOTAL_PHOTO_BYTES
}

const val SOTAWARE_BUNDLE_FORMAT_VERSION: Int = 1
const val SOTAWARE_BUNDLE_EXTENSION: String = ".sotaware"
const val SOTAWARE_BUNDLE_MANIFEST_ENTRY: String = "manifest.json"
const val SOTAWARE_BUNDLE_SNAPSHOT_ENTRY: String = "snapshot.json"
private const val SOTAWARE_BUNDLE_PHOTO_PREFIX: String = "photos/"

/** The strict, typed manifest written inside a .sotaware archive. */
data class BundleManifestV1(
    val formatVersion: Int,
    val snapshotSchemaVersion: Int,
    val exportedDocumentId: String,
    val source: BundleSourceManifestV1,
    val snapshot: BundleSnapshotDescriptorV1,
    val photos: List<BundlePhotoDescriptorV1>
)

data class BundleSourceManifestV1(
    val sourceUri: String,
    val displayName: String?,
    val providerMetadata: Map<String, String>,
    val sourceFingerprint: BundleSourceFingerprintV1
)

data class BundleSourceFingerprintV1(
    val algorithm: String,
    val digestHex: String,
    val byteCount: Long
)

data class BundleSnapshotDescriptorV1(
    val revision: Long,
    val byteCount: Long,
    val sha256: String
)

data class BundlePhotoDescriptorV1(
    val fileName: String,
    val byteCount: Long,
    val sha256: String,
    val mimeType: String,
    val width: Int,
    val height: Int
)

/** The export input is already bound to one verified live session. */
data class BundleExportInput(
    val exportedDocumentId: DocumentId,
    val source: DocumentSourceIdentityV1,
    val sourceFingerprint: SourceFingerprint,
    val snapshot: DocumentSnapshotV1,
    val photoFiles: Map<String, ByteArray>
)

/** A verified destination source. The exported DocumentId is never reused. */
data class VerifiedBundleTarget(
    val documentId: DocumentId,
    val source: DocumentSourceIdentityV1,
    val sourceFingerprint: SourceFingerprint
)

/**
 * The exported ID is metadata, never an authority for the import target.
 *
 * A matching ID is a same-document restore.  A different ID is an explicit
 * copy/import into the caller-supplied verified target.  Both policies keep
 * [VerifiedBundleTarget.documentId] as the only identity used for storage.
 */
enum class BundleDocumentIdentityPolicy {
    SAME_DOCUMENT_RESTORE,
    VERIFIED_TARGET_COPY
}

data class ReboundDocumentBundle(
    val target: VerifiedBundleTarget,
    val snapshot: DocumentSnapshotV1,
    val photoFiles: Map<String, ByteArray>,
    val identityPolicy: BundleDocumentIdentityPolicy = BundleDocumentIdentityPolicy.VERIFIED_TARGET_COPY
)

data class DecodedDocumentBundle(
    val manifest: BundleManifestV1,
    val snapshot: DocumentSnapshotV1,
    val photoFiles: Map<String, ByteArray>
)

/** Host boundary used by the transaction service; the caller holds the shared document barrier. */
interface DocumentBundleImportHost {
    val documentId: DocumentId

    suspend fun captureCurrentLiveSnapshot(): DocumentSnapshotV1

    suspend fun captureCurrentDurableSnapshot(): DocumentSnapshotV1?

    /**
     * Captures the exact durable current/previous pair when the host owns a
     * Stage 2 repository.  The default preserves the older snapshot-only
     * host contract for tests and compatibility callers.
     */
    suspend fun captureCurrentDurableState(): DocumentDurableSnapshotState =
        captureCurrentDurableSnapshot()?.let { snapshot ->
            DocumentDurableSnapshotState(
                current = DurableSnapshotSlot(snapshot, null),
                previous = null
            )
        } ?: DocumentDurableSnapshotState(current = null, previous = null)

    suspend fun persistAndApply(snapshot: DocumentSnapshotV1): SessionSnapshotApplyResult

    suspend fun restore(
        durableSnapshot: DocumentSnapshotV1,
        liveSnapshot: DocumentSnapshotV1
    ): SessionSnapshotApplyResult

    /**
     * Exact durable rollback seam.  Legacy hosts fall back to the old
     * snapshot restore only when a current/previous state is representable.
     */
    suspend fun restore(
        durableState: DocumentDurableSnapshotState,
        liveSnapshot: DocumentSnapshotV1
    ): SessionSnapshotApplyResult {
        val snapshot = durableState.current?.snapshot ?: durableState.previous?.snapshot
            ?: return SessionSnapshotApplyResult.Failed(
                LocalRepositoryError.InvalidSnapshot(
                    "exact durable rollback has no snapshot for a legacy host"
                )
            )
        return restore(snapshot, liveSnapshot)
    }
}

sealed class BundleImportResult {
    data object Applied : BundleImportResult()
    data object Stale : BundleImportResult()
    data class Failed(val cause: Throwable) : BundleImportResult()
}

class DocumentBundleException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Verifies the source revision again at the export boundary.  The snapshot
 * carries the source identity but not its byte fingerprint, so the caller
 * must supply a freshly computed fingerprint while holding its document
 * transaction barrier.
 */
fun verifyBundleExportSourceFingerprint(
    sessionSourceUri: String,
    sessionSourceFingerprint: SourceFingerprint?,
    snapshot: DocumentSnapshotV1,
    currentSourceFingerprint: SourceFingerprint?
): SourceFingerprint {
    if (snapshot.source.sourceUri != sessionSourceUri) {
        throw DocumentBundleException("the live snapshot is not bound to the active document")
    }
    val verified = currentSourceFingerprint
        ?: throw DocumentBundleException("the active PDF source could not be fingerprinted")
    if (sessionSourceFingerprint != verified) {
        throw DocumentBundleException("the active PDF source revision changed before export")
    }
    return verified
}

/**
 * Stage 6 codec and local application service.
 *
 * The codec owns the complete archive boundary. The application method is
 * deliberately a "within document transaction" primitive: callers must hold
 * the shared [com.example.myapplication.stage3.DocumentTransactionBarrier]
 * while capturing state, staging photos, and invoking it.
 */
class DocumentBundleService(
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
    private val imageProbe: PhotoDecodeProbe = DefaultImageProbe,
    /** App-private cache directory used for bounded disk-backed import staging. */
    private val stagingDirectory: File? = null,
    /** Test-only failure injection; production uses the default cleanup. */
    private val cleanupStagingDirectoryOverride: ((Path) -> Unit)? = null,
    /** Test-only failure injection for cleanup of one failed ZIP entry. */
    private val deleteStagedEntryOverride: ((Path) -> Unit)? = null,
    /** Test-only ZIP seam used to assert that rejected entries are not drained. */
    private val zipInputStreamFactory: ((InputStream) -> ZipInputStream)? = null
) {
    fun writeBundle(output: OutputStream, input: BundleExportInput) {
        writeBundleInternal(output, input, cancellationCheck = null)
    }

    /** Cancellable export path used by the Android SAF integration. */
    suspend fun writeBundleCancellable(output: OutputStream, input: BundleExportInput) {
        val coroutineContext = currentCoroutineContext()
        writeBundleInternal(output, input) { coroutineContext.ensureActive() }
    }

    private fun writeBundleInternal(
        output: OutputStream,
        input: BundleExportInput,
        cancellationCheck: (() -> Unit)?
    ) {
        cancellationCheck?.invoke()
        val prepared = prepareExport(input, cancellationCheck)
        val manifestBytes = encodeBoundedJson(
            gson,
            prepared.manifest,
            Stage6BundleLimits.MAX_MANIFEST_BYTES,
            "bundle manifest"
        )

        val boundedOutput = ArchiveLimitOutputStream(
            output,
            Stage6BundleLimits.MAX_ARCHIVE_BYTES.toLong()
        )
        ZipOutputStream(boundedOutput).let { zip ->
            putEntry(zip, SOTAWARE_BUNDLE_MANIFEST_ENTRY, manifestBytes, cancellationCheck)
            putEntry(zip, SOTAWARE_BUNDLE_SNAPSHOT_ENTRY, prepared.snapshotBytes, cancellationCheck)
            prepared.photoFiles.keys.sorted().forEach { name ->
                cancellationCheck?.invoke()
                putEntry(
                    zip,
                    "$SOTAWARE_BUNDLE_PHOTO_PREFIX$name",
                    prepared.photoFiles.getValue(name),
                    cancellationCheck
                )
            }
            // finish() writes the central directory without taking ownership of
            // the caller's stream. The caller's close is the success boundary.
            cancellationCheck?.invoke()
            zip.finish()
            zip.flush()
        }
        cancellationCheck?.invoke()
        output.flush()
    }

    /** Returns only after the output stream has been flushed and closed successfully. */
    fun writeBundleAndClose(
        openOutput: () -> OutputStream?,
        input: BundleExportInput
    ) {
        val staged = createExportStagingFile()
        var primaryFailure: Throwable? = null
        try {
            Files.newOutputStream(
                staged,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            ).use { stream ->
                writeBundle(stream, input)
                stream.flush()
            }
            Files.newInputStream(staged, StandardOpenOption.READ).use { stagedInput ->
                readBundle(stagedInput)
            }
            publishStagedBundle(openOutput, staged)
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            cleanupExportStagingFile(staged, primaryFailure)
        }
    }

    /** Returns only after archive finish, flush, and destination close succeed. */
    suspend fun writeBundleAndCloseCancellable(
        openOutput: () -> OutputStream?,
        input: BundleExportInput
    ) {
        val staged = createExportStagingFile()
        var primaryFailure: Throwable? = null
        try {
            Files.newOutputStream(
                staged,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            ).use { stream ->
                writeBundleCancellable(stream, input)
                stream.flush()
            }
            currentCoroutineContext().ensureActive()
            Files.newInputStream(staged, StandardOpenOption.READ).use { stagedInput ->
                readBundleCancellable(stagedInput)
            }
            currentCoroutineContext().ensureActive()
            // Once SAF has opened the selected destination, finish the
            // already validated archive under a non-cancellable close
            // boundary so cancellation cannot report a false success.
            withContext(NonCancellable) {
                publishStagedBundle(openOutput, staged)
            }
            currentCoroutineContext().ensureActive()
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            cleanupExportStagingFileCancellable(staged, primaryFailure)
        }
    }

    fun encodeToByteArray(input: BundleExportInput): ByteArray =
        ByteArrayOutputStream().also { writeBundle(it, input) }.toByteArray()

    fun readBundle(input: InputStream): DecodedDocumentBundle =
        readBundleInternal(input, cancellationCheck = null)

    /** Cancellable, disk-staged import path used by the Android SAF integration. */
    suspend fun readBundleCancellable(input: InputStream): DecodedDocumentBundle {
        val coroutineContext = currentCoroutineContext()
        return readBundleInternal(input) { coroutineContext.ensureActive() }
    }

    private fun readBundleInternal(
        input: InputStream,
        cancellationCheck: (() -> Unit)?
    ): DecodedDocumentBundle {
        val staging = createStagingDirectory()
        var primaryFailure: Throwable? = null
        try {
            val archive = staging.resolve("archive.zip")
            stageArchive(input, archive, cancellationCheck)
            val centralEntries = scanCentralDirectory(archive, cancellationCheck)
            val extracted = extractEntries(archive, centralEntries, staging, cancellationCheck)

            val manifest = extracted[SOTAWARE_BUNDLE_MANIFEST_ENTRY]?.let { path ->
                readStagedBytes(
                    path,
                    Stage6BundleLimits.MAX_MANIFEST_BYTES.toLong(),
                    "bundle manifest",
                    cancellationCheck
                )
                    .let(::parseManifest)
            } ?: throw Stage5ValidationException("bundle manifest is missing")
            val snapshot = extracted[SOTAWARE_BUNDLE_SNAPSHOT_ENTRY]?.let { path ->
                readStagedBytes(
                    path,
                    Stage5Limits.MAX_JSON_BYTES.toLong(),
                    "bundle snapshot",
                    cancellationCheck
                ).let { snapshotBytes ->
                    if (manifest.snapshotSchemaVersion != 1) {
                        throw Stage5ValidationException("unsupported bundle snapshot schema")
                    }
                    if (manifest.snapshot.byteCount != snapshotBytes.size.toLong()) {
                        throw Stage5ValidationException("bundle snapshot byte count does not match")
                    }
                    if (!manifest.snapshot.sha256.equals(sha256Hex(snapshotBytes), ignoreCase = true)) {
                        throw Stage5ValidationException("bundle snapshot hash does not match")
                    }
                    decodeValidatedSnapshotJson(
                        gson,
                        decodeUtf8(snapshotBytes, "bundle snapshot"),
                        "bundle snapshot"
                    )
                }
            } ?: throw Stage5ValidationException("bundle snapshot is missing")

            if (snapshot.schemaVersion != manifest.snapshotSchemaVersion ||
                snapshot.snapshotRevision != manifest.snapshot.revision
            ) {
                throw Stage5ValidationException("bundle snapshot metadata does not match its manifest")
            }
            val manifestSource = manifest.source.toSourceIdentity()
            if (snapshot.source != manifestSource) {
                throw Stage5ValidationException("bundle snapshot source metadata does not match its manifest")
            }

            val expectedPhotoNames = manifest.photos.map { it.fileName }.toSet()
            val actualPhotoEntries = extracted.keys
                .filter { it.startsWith(SOTAWARE_BUNDLE_PHOTO_PREFIX) }
                .map { it.removePrefix(SOTAWARE_BUNDLE_PHOTO_PREFIX) }
                .toSet()
            if (actualPhotoEntries != expectedPhotoNames) {
                throw Stage5ValidationException("bundle photo entries do not match its manifest")
            }
            val expectedDescriptors = manifest.photos.associate { descriptor ->
                descriptor.fileName to descriptor.toPhotoDescriptor()
            }
            val validatedPhotos = LinkedHashMap<String, ByteArray>(actualPhotoEntries.size)
            var totalPhotoBytes = 0L
            actualPhotoEntries.sorted().forEach { name ->
                cancellationCheck?.invoke()
                val validated = extracted.getValue("$SOTAWARE_BUNDLE_PHOTO_PREFIX$name")
                    .let { path ->
                        readStagedBytes(
                            path,
                            Stage5Limits.MAX_PHOTO_BYTES.toLong(),
                            "bundle photo $name",
                            cancellationCheck
                        )
                    }
                    .let { bytes ->
                        totalPhotoBytes = addBounded(
                            totalPhotoBytes,
                            bytes.size.toLong(),
                            Stage5Limits.MAX_TOTAL_PHOTO_BYTES,
                            "bundle photo content"
                        )
                        com.example.myapplication.stage5.validatePhotoBytes(
                            bytes,
                            expected = expectedDescriptors[name],
                            imageProbe = imageProbe
                        )
                    }
                validatedPhotos[name] = validated.bytes
            }
            if (validatedPhotos.keys != requiredPhotoNames(snapshot)) {
                throw Stage5ValidationException("bundle photo entries do not match snapshot references")
            }

            return DecodedDocumentBundle(
                manifest = manifest,
                snapshot = snapshot,
                photoFiles = immutableOwnedBytesMap(validatedPhotos)
            )
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                cleanupStagingDirectory(staging)
            } catch (cleanupFailure: Throwable) {
                if (primaryFailure != null) {
                    if (cleanupFailure !== primaryFailure) {
                        primaryFailure?.addSuppressed(cleanupFailure)
                    }
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    /** Returns only after the input reaches EOF and is closed successfully. */
    fun readBundleFrom(openInput: () -> InputStream?): DecodedDocumentBundle {
        val input = openInput() ?: throw IOException("sotaware bundle source is unavailable")
        return input.use { readBundle(it) }
    }

    /** Returns only after the input reaches EOF and closes successfully. */
    suspend fun readBundleFromCancellable(openInput: () -> InputStream?): DecodedDocumentBundle {
        val input = openInput() ?: throw IOException("sotaware bundle source is unavailable")
        return input.use { readBundleCancellable(it) }
    }

    fun rebindToVerifiedTarget(
        bundle: DecodedDocumentBundle,
        target: VerifiedBundleTarget
    ): ReboundDocumentBundle {
        val exportedDocumentId = DocumentId.parse(bundle.manifest.exportedDocumentId)
        val exportedFingerprint = bundle.manifest.source.toSourceFingerprint()
        val targetFingerprint = normalizeFingerprint(target.sourceFingerprint)
        if (exportedFingerprint != targetFingerprint) {
            throw Stage5ValidationException("bundle source revision does not match the selected PDF")
        }
        if (bundle.snapshot.source != bundle.manifest.source.toSourceIdentity()) {
            throw Stage5ValidationException("bundle source metadata is inconsistent")
        }
        val reboundSnapshot = bundle.snapshot.copy(source = target.source)
        validateSnapshot(reboundSnapshot)
        val photos = validatePhotoSet(
            reboundSnapshot,
            bundle.photoFiles,
            imageProbe = imageProbe
        ).mapValues { (_, photo) -> photo.bytes }
        val identityPolicy = if (exportedDocumentId == target.documentId) {
            // Same-document restore is legitimate: the caller still supplies
            // the target and it happens to equal the exported metadata ID.
            BundleDocumentIdentityPolicy.SAME_DOCUMENT_RESTORE
        } else {
            // Copy/import is also legitimate, but the verified target wins;
            // the exported ID is never used to rebind local state.
            BundleDocumentIdentityPolicy.VERIFIED_TARGET_COPY
        }
        return ReboundDocumentBundle(
            target = target.copy(sourceFingerprint = targetFingerprint),
            snapshot = reboundSnapshot,
            photoFiles = immutableOwnedBytesMap(photos),
            identityPolicy = identityPolicy
        )
    }

    /**
     * Applies a rebound bundle with staged photos. This method is intentionally
     * independent of a particular Android host so JVM tests can inject every
     * repository, session, cancellation, and photo failure boundary.
     */
    suspend fun applyReboundBundleWithinDocumentTransaction(
        bundle: ReboundDocumentBundle,
        host: DocumentBundleImportHost,
        photoTransaction: PhotoContentTransaction?
    ): BundleImportResult {
        var previousLive: DocumentSnapshotV1? = null
        var previousDurableState: DocumentDurableSnapshotState? = null
        var previousDurable: DocumentSnapshotV1? = null
        var canonicalMayHaveChanged = false
        var recoveryPrepared = false
        var photoCommitAuthorityRetained = false
        var rollbackAttempted = false
        try {
            if (bundle.target.documentId != host.documentId) {
                throw DocumentBundleException("bundle target document identity is not current")
            }
            if (bundle.snapshot.source != bundle.target.source) {
                throw DocumentBundleException("bundle snapshot was not rebound to the verified target")
            }
            val requiredPhotos = requiredPhotoNames(bundle.snapshot)
            if (bundle.photoFiles.keys != requiredPhotos) {
                throw DocumentBundleException("bundle photo set is incomplete")
            }
            if (requiredPhotos.isNotEmpty() && photoTransaction == null) {
                throw DocumentBundleException("required bundle photos were not staged")
            }
            validateSnapshot(bundle.snapshot)
            validatePhotoSet(bundle.snapshot, bundle.photoFiles, imageProbe = imageProbe)
            previousLive = host.captureCurrentLiveSnapshot().also(::validateSnapshot)
            previousDurableState = host.captureCurrentDurableState()
            previousDurable = (
                previousDurableState?.current?.snapshot
                    ?: previousDurableState?.previous?.snapshot
                    ?: previousLive!!
                ).also(::validateSnapshot)

            photoTransaction?.prepareCanonicalRecovery(
                previousDurable = com.example.myapplication.stage5.photoCanonicalIdentity(
                    host.documentId,
                    previousDurable!!
                ),
                previousLive = com.example.myapplication.stage5.photoCanonicalIdentity(
                    host.documentId,
                    previousLive!!
                ),
                previousLiveSnapshot = previousLive!!,
                intended = com.example.myapplication.stage5.photoCanonicalIdentity(
                    host.documentId,
                    bundle.snapshot
                )
            )
            recoveryPrepared = photoTransaction != null

            canonicalMayHaveChanged = true
            val replacement = host.persistAndApply(bundle.snapshot)
            // The host may have durably replaced canonical state before it
            // reports Applied. Observe cancellation at that post-apply race
            // before any photo publication is allowed to begin.
            currentCoroutineContext().ensureActive()
            when (replacement) {
                SessionSnapshotApplyResult.Applied -> Unit
                SessionSnapshotApplyResult.Stale -> {
                    val rollbackFailure = rollbackAll(
                        host = host,
                        photoTransaction = photoTransaction,
                        previousDurableState = previousDurableState,
                        previousLive = previousLive,
                        restoreCanonical = true,
                        original = DocumentBundleException(
                            "canonical bundle replacement became stale"
                        )
                    )
                    return if (rollbackFailure == null) {
                        BundleImportResult.Stale
                    } else {
                        BundleImportResult.Failed(rollbackFailure)
                    }
                }
                is SessionSnapshotApplyResult.Failed -> {
                    val rollbackFailure = rollbackAll(
                        host = host,
                        photoTransaction = photoTransaction,
                        previousDurableState = previousDurableState!!,
                        previousLive = previousLive!!,
                        restoreCanonical = true,
                        original = DocumentBundleException(
                            "canonical bundle replacement failed: ${replacement.error}"
                        )
                    )
                    return BundleImportResult.Failed(
                        rollbackFailure ?: DocumentBundleException(
                            "canonical bundle replacement failed: ${replacement.error}"
                        )
                    )
                }
            }

            try {
                // A cancellation that arrives between canonical apply and
                // this point must take the compensating rollback path rather
                // than publishing a photo sidecar and returning Applied.
                currentCoroutineContext().ensureActive()
                photoTransaction?.publish()
                // Publication is still reversible. Check again before the
                // NonCancellable commit/finalization boundary.
                currentCoroutineContext().ensureActive()
                var photoCommitCompleted = false
                withContext(NonCancellable) {
                    photoTransaction?.commit()
                    // A normal return from commit is itself the completion
                    // proof for source-compatible transactions whose legacy
                    // hasAuthoritativeCommit() default remains false.
                    photoCommitCompleted = photoTransaction != null
                }
                photoCommitAuthorityRetained =
                    photoCommitCompleted || photoTransaction?.hasAuthoritativeCommit() == true
                // NonCancellable is required to finish the durable photo
                // commit, but it must not hide cancellation requested while
                // that finalization was running. If observed, the catch
                // below preserves authoritative ownership and rethrows.
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) {
                photoCommitAuthorityRetained =
                    photoCommitAuthorityRetained || photoTransaction?.hasAuthoritativeCommit() == true
                if (!photoCommitAuthorityRetained) {
                    rollbackAttempted = true
                    val rollbackFailure = rollbackAll(
                        host,
                        photoTransaction,
                        previousDurableState!!,
                        previousLive!!,
                        restoreCanonical = true,
                        original = cancelled
                    )
                    rollbackFailure?.let { cancelled.addSuppressed(it) }
                } else {
                    photoTransaction?.releaseAfterFailure()
                }
                throw cancelled
            } catch (error: Throwable) {
                photoCommitAuthorityRetained =
                    photoCommitAuthorityRetained || photoTransaction?.hasAuthoritativeCommit() == true
                if (photoCommitAuthorityRetained) {
                    photoTransaction?.releaseAfterFailure()
                    return BundleImportResult.Failed(
                        DocumentBundleException(
                            "bundle import committed canonical state but photo cleanup is uncertain",
                            error
                        )
                    )
                }
                val rollbackFailure = rollbackAll(
                    host,
                    photoTransaction,
                    previousDurableState!!,
                    previousLive!!,
                    restoreCanonical = true,
                    original = error
                )
                return BundleImportResult.Failed(
                    rollbackFailure ?: DocumentBundleException("bundle photo commit failed", error)
                )
            }
            return BundleImportResult.Applied
        } catch (cancelled: CancellationException) {
            if (photoCommitAuthorityRetained || photoTransaction?.hasAuthoritativeCommit() == true) {
                photoTransaction?.releaseAfterFailure()
                throw cancelled
            }
            val rollbackFailure = if (rollbackAttempted) {
                null
            } else if (recoveryPrepared || canonicalMayHaveChanged) {
                rollbackAll(
                    host = host,
                    photoTransaction = photoTransaction,
                    previousDurableState = previousDurableState,
                    previousLive = previousLive,
                    restoreCanonical = canonicalMayHaveChanged,
                    original = cancelled
                )
            } else {
                rollbackPhotoOnly(photoTransaction)
            }
            rollbackFailure?.let { cancelled.addSuppressed(it) }
            throw cancelled
        } catch (error: Throwable) {
            if (photoCommitAuthorityRetained || photoTransaction?.hasAuthoritativeCommit() == true) {
                photoTransaction?.releaseAfterFailure()
                return BundleImportResult.Failed(
                    DocumentBundleException(
                        "bundle import committed canonical state but photo cleanup is uncertain",
                        error
                    )
                )
            }
            val rollbackFailure = if (rollbackAttempted) {
                null
            } else if (recoveryPrepared || canonicalMayHaveChanged) {
                rollbackAll(
                    host = host,
                    photoTransaction = photoTransaction,
                    previousDurableState = previousDurableState,
                    previousLive = previousLive,
                    restoreCanonical = canonicalMayHaveChanged,
                    original = error
                )
            } else {
                rollbackPhotoOnly(photoTransaction)
            }
            return BundleImportResult.Failed(
                rollbackFailure ?: DocumentBundleException("bundle import failed", error)
            )
        }
    }

    companion object {
        /** Recognizes ZIP signatures before a legacy JSON fallback is attempted. */
        fun looksLikeZip(prefix: ByteArray): Boolean {
            if (prefix.size < 4) return false
            if (prefix[0] != 'P'.code.toByte() || prefix[1] != 'K'.code.toByte()) return false
            return when {
                prefix[2] == 0x03.toByte() && prefix[3] == 0x04.toByte() -> true
                prefix[2] == 0x05.toByte() && prefix[3] == 0x06.toByte() -> true
                prefix[2] == 0x06.toByte() && prefix[3] == 0x06.toByte() -> true
                prefix[2] == 0x06.toByte() && prefix[3] == 0x07.toByte() -> true
                prefix[2] == 0x07.toByte() && prefix[3] == 0x08.toByte() -> true
                else -> false
            }
        }
    }

    private data class PreparedExport(
        val manifest: BundleManifestV1,
        val snapshotBytes: ByteArray,
        val photoFiles: Map<String, ByteArray>
    )

    private data class CentralEntry(
        val name: String,
        val nameBytes: ByteArray,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val externalAttributes: Long,
        val madeByPlatform: Int
    )

    private fun prepareExport(
        input: BundleExportInput,
        cancellationCheck: (() -> Unit)? = null
    ): PreparedExport {
        cancellationCheck?.invoke()
        require(input.snapshot.source == input.source) {
            "bundle export source metadata does not match the snapshot"
        }
        val fingerprint = normalizeFingerprint(input.sourceFingerprint)
        validateSnapshot(input.snapshot)
        cancellationCheck?.invoke()
        val validatedPhotos = validatePhotoSet(
            input.snapshot,
            input.photoFiles,
            imageProbe = imageProbe
        )
        cancellationCheck?.invoke()
        val snapshotBytes = encodeBoundedJson(
            gson,
            input.snapshot,
            Stage5Limits.MAX_JSON_BYTES,
            "bundle snapshot"
        )
        val descriptors = validatedPhotos.keys.sorted().map { name ->
            cancellationCheck?.invoke()
            validatedPhotos.getValue(name).descriptor.toManifestDescriptor(name)
        }
        val manifest = BundleManifestV1(
            formatVersion = SOTAWARE_BUNDLE_FORMAT_VERSION,
            snapshotSchemaVersion = input.snapshot.schemaVersion,
            exportedDocumentId = input.exportedDocumentId.value,
            source = BundleSourceManifestV1(
                sourceUri = input.source.sourceUri,
                displayName = input.source.displayName,
                providerMetadata = input.source.providerMetadata.toSortedMap(),
                sourceFingerprint = fingerprint.toManifestFingerprint()
            ),
            snapshot = BundleSnapshotDescriptorV1(
                revision = input.snapshot.snapshotRevision,
                byteCount = snapshotBytes.size.toLong(),
                sha256 = sha256Hex(snapshotBytes)
            ),
            photos = descriptors
        )
        return PreparedExport(
            manifest,
            snapshotBytes,
            immutableOwnedBytesMap(validatedPhotos.mapValues { (_, photo) -> photo.bytes })
        )
    }

    private fun putEntry(
        zip: ZipOutputStream,
        name: String,
        bytes: ByteArray,
        cancellationCheck: (() -> Unit)? = null
    ) {
        cancellationCheck?.invoke()
        // Store already-bounded payloads without deflation.  This keeps every
        // archive produced by the app within the reader's compression-ratio
        // policy, including highly repetitive PNGs, while the reader still
        // rejects compressed bomb-like input from untrusted sources.
        val checksum = CRC32().also { it.update(bytes) }
        val entry = ZipEntry(name).apply {
            time = 0L
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = checksum.value
        }
        zip.putNextEntry(entry)
        var offset = 0
        while (offset < bytes.size) {
            cancellationCheck?.invoke()
            val count = minOf(8 * 1024, bytes.size - offset)
            zip.write(bytes, offset, count)
            offset += count
        }
        zip.closeEntry()
    }

    private class ArchiveLimitOutputStream(
        delegate: OutputStream,
        private val maximumBytes: Long
    ) : FilterOutputStream(delegate) {
        private var writtenBytes = 0L

        override fun write(oneByte: Int) {
            ensureCapacity(1L)
            out.write(oneByte)
            writtenBytes += 1L
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > bytes.size - length) {
                throw IndexOutOfBoundsException("invalid archive output range")
            }
            ensureCapacity(length.toLong())
            out.write(bytes, offset, length)
            writtenBytes += length.toLong()
        }

        private fun ensureCapacity(additionalBytes: Long) {
            if (additionalBytes < 0L || writtenBytes > maximumBytes - additionalBytes) {
                throw IOException("sotaware bundle archive exceeds its byte limit")
            }
        }
    }

    private fun createStagingDirectory(): Path {
        val base = stagingDirectory?.toPath()
        return if (base == null) {
            Files.createTempDirectory("sotaware-bundle-")
        } else {
            Files.createDirectories(base)
            Files.createTempDirectory(base, ".sotaware-bundle-")
        }
    }

    private fun createExportStagingFile(): Path {
        val base = stagingDirectory?.toPath()
        return if (base == null) {
            Files.createTempFile("sotaware-bundle-export-", ".tmp")
        } else {
            Files.createDirectories(base)
            Files.createTempFile(base, ".sotaware-bundle-export-", ".tmp")
        }
    }

    /** Copies only a complete, already validated archive to the SAF stream. */
    private fun publishStagedBundle(
        openOutput: () -> OutputStream?,
        staged: Path
    ) {
        val expectedBytes = Files.size(staged)
        val output = openOutput() ?: throw IOException("bundle export destination is unavailable")
        output.use { destination ->
            Files.newInputStream(staged, StandardOpenOption.READ).use { input ->
                val copiedBytes = copyBounded(
                    input,
                    destination,
                    Stage6BundleLimits.MAX_ARCHIVE_BYTES.toLong(),
                    "sotaware bundle export",
                    cancellationCheck = null
                )
                if (copiedBytes != expectedBytes) {
                    throw IOException("staged sotaware bundle changed while it was being exported")
                }
            }
            // Destination close is the existing success boundary.  A
            // DocumentsProvider-specific atomic replacement is not assumed.
            destination.flush()
        }
    }

    private fun cleanupExportStagingFile(staged: Path, primaryFailure: Throwable?) {
        try {
            Files.deleteIfExists(staged)
        } catch (cleanupFailure: Throwable) {
            if (primaryFailure != null) {
                if (cleanupFailure !== primaryFailure &&
                    primaryFailure.suppressed.none { it === cleanupFailure }
                ) {
                    primaryFailure.addSuppressed(cleanupFailure)
                }
            } else {
                throw cleanupFailure
            }
        }
    }

    private suspend fun cleanupExportStagingFileCancellable(
        staged: Path,
        primaryFailure: Throwable?
    ) {
        try {
            withContext(NonCancellable) {
                Files.deleteIfExists(staged)
            }
        } catch (cleanupFailure: Throwable) {
            if (primaryFailure != null) {
                if (cleanupFailure !== primaryFailure &&
                    primaryFailure.suppressed.none { it === cleanupFailure }
                ) {
                    primaryFailure.addSuppressed(cleanupFailure)
                }
            } else {
                throw cleanupFailure
            }
        }
    }

    private fun cleanupStagingDirectory(directory: Path) {
        cleanupStagingDirectoryOverride?.let { override ->
            override(directory)
            return
        }
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder<Path>()).forEach { path ->
                Files.deleteIfExists(path)
            }
        }
    }

    private fun deleteStagedEntry(path: Path) {
        deleteStagedEntryOverride?.let { override ->
            override(path)
            return
        }
        Files.deleteIfExists(path)
    }

    private fun copyBounded(
        input: InputStream,
        output: OutputStream,
        maximumBytes: Long,
        label: String,
        cancellationCheck: (() -> Unit)?
    ): Long {
        if (maximumBytes < 0L) throw IllegalArgumentException("negative copy limit")
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            cancellationCheck?.invoke()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) {
                val oneByte = input.read()
                if (oneByte < 0) break
                total = addBounded(total, 1L, maximumBytes, "$label bytes")
                output.write(oneByte)
                continue
            }
            total = addBounded(total, read.toLong(), maximumBytes, "$label bytes")
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun readStagedBytes(
        path: Path,
        maximumBytes: Long,
        label: String,
        cancellationCheck: (() -> Unit)?
    ): ByteArray {
        val fileSize = Files.size(path)
        if (fileSize < 0L || fileSize > maximumBytes || fileSize > Int.MAX_VALUE.toLong()) {
            throw Stage5ValidationException("$label exceeds its byte limit")
        }
        // Allocate exactly once for the bounded payload. ByteArrayOutputStream
        // would retain a second growable buffer and then copy it on toByteArray.
        val bytes = ByteArray(fileSize.toInt())
        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            var offset = 0
            while (offset < bytes.size) {
                cancellationCheck?.invoke()
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read < 0) {
                    throw Stage5ValidationException("$label changed while it was being read")
                }
                if (read == 0) {
                    val oneByte = input.read()
                    if (oneByte < 0) {
                        throw Stage5ValidationException("$label changed while it was being read")
                    }
                    bytes[offset++] = oneByte.toByte()
                } else {
                    offset += read
                }
            }
            cancellationCheck?.invoke()
            if (input.read() >= 0) {
                throw Stage5ValidationException("$label grew while it was being read")
            }
        }
        return bytes
    }

    private fun immutableOwnedBytesMap(values: Map<String, ByteArray>): Map<String, ByteArray> =
        Collections.unmodifiableMap(LinkedHashMap(values))

    private fun stageArchive(
        input: InputStream,
        archive: Path,
        cancellationCheck: (() -> Unit)?
    ) {
        Files.newOutputStream(
            archive,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        ).use { output ->
            copyBounded(
                input,
                output,
                Stage6BundleLimits.MAX_ARCHIVE_BYTES.toLong(),
                "sotaware bundle",
                cancellationCheck
            )
            output.flush()
        }
    }

    private fun extractEntries(
        archive: Path,
        centralEntries: List<CentralEntry>,
        stagingDirectory: Path,
        cancellationCheck: (() -> Unit)?
    ): Map<String, Path> {
        val expected = centralEntries.associateBy { it.name }
        val extracted = LinkedHashMap<String, Path>(centralEntries.size)
        Files.newInputStream(archive, StandardOpenOption.READ).use { archiveInput ->
            val zip = zipInputStreamFactory?.invoke(archiveInput)
                ?: ZipInputStream(archiveInput, StandardCharsets.UTF_8)
            var archiveAborted = false
            var archiveFailure: Throwable? = null
            try {
                while (true) {
                    cancellationCheck?.invoke()
                    val entry = zip.nextEntry ?: break
                    val central = expected[entry.name]
                        ?: throw Stage5ValidationException(
                            "bundle entry is not in its central directory: ${entry.name}"
                        )
                    if (entry.isDirectory) throw Stage5ValidationException("bundle directories are not allowed")
                    if (extracted.containsKey(entry.name)) {
                        throw Stage5ValidationException("bundle contains a duplicate entry: ${entry.name}")
                    }
                    if (entry.method != central.method) {
                        throw Stage5ValidationException("bundle entry compression method changed")
                    }
                    val maxBytes = when {
                        entry.name == SOTAWARE_BUNDLE_MANIFEST_ENTRY -> Stage6BundleLimits.MAX_MANIFEST_BYTES.toLong()
                        entry.name == SOTAWARE_BUNDLE_SNAPSHOT_ENTRY -> Stage5Limits.MAX_JSON_BYTES.toLong()
                        else -> Stage5Limits.MAX_PHOTO_BYTES.toLong()
                    }
                    val staged = Files.createTempFile(stagingDirectory, ".entry-", ".tmp")
                    var retained = false
                    var primaryFailure: Throwable? = null
                    try {
                        val checksum = CRC32()
                        Files.newOutputStream(
                            staged,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING
                        ).use { fileOutput ->
                            val output = CheckedOutputStream(fileOutput, checksum)
                            val actualSize = copyBounded(
                                zip,
                                output,
                                minOf(maxBytes, central.uncompressedSize),
                                "bundle entry ${entry.name}",
                                cancellationCheck
                            )
                            if (actualSize != central.uncompressedSize) {
                                throw Stage5ValidationException(
                                    "bundle entry size does not match its central directory"
                                )
                            }
                            output.flush()
                        }
                        if (checksum.value != central.crc) {
                            throw Stage5ValidationException("bundle entry CRC does not match its central directory")
                        }
                        if (entry.size >= 0L && entry.size != central.uncompressedSize) {
                            throw Stage5ValidationException("bundle entry size is inconsistent")
                        }
                        if (entry.compressedSize >= 0L && entry.compressedSize != central.compressedSize) {
                            throw Stage5ValidationException("bundle compressed size is inconsistent")
                        }
                        zip.closeEntry()
                        extracted[entry.name] = staged
                        retained = true
                    } catch (error: Throwable) {
                        // Do not call closeEntry() here.  After a bounded
                        // read rejects an entry, closeEntry() may inflate and
                        // drain the unbounded remainder.  Closing the archive
                        // input directly aborts the stream while preserving
                        // the validation failure as the primary error.  A
                        // ZipInputStream.close() is deliberately avoided
                        // here because JDK implementations may call
                        // closeEntry() as part of close().
                        primaryFailure = error
                        throw error
                    } finally {
                        if (!retained) {
                            try {
                                deleteStagedEntry(staged)
                            } catch (cleanupFailure: Throwable) {
                                val failure = primaryFailure
                                if (failure == null) {
                                    throw cleanupFailure
                                }
                                if (cleanupFailure !== failure &&
                                    failure.suppressed.none { it === cleanupFailure }
                                ) {
                                    failure.addSuppressed(cleanupFailure)
                                }
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                archiveAborted = true
                archiveFailure = error
                throw error
            } finally {
                if (archiveAborted) {
                    try {
                        // Abort the inflater by closing the underlying
                        // archive stream, without asking ZipInputStream to
                        // finish the rejected entry.
                        archiveInput.close()
                    } catch (closeError: Throwable) {
                        if (archiveFailure == null) {
                            throw closeError
                        }
                        if (closeError !== archiveFailure &&
                            archiveFailure?.suppressed?.none { it === closeError } == true
                        ) {
                            archiveFailure?.addSuppressed(closeError)
                        }
                    }
                } else {
                    zip.close()
                }
            }
        }
        if (extracted.keys != expected.keys) {
            throw Stage5ValidationException("bundle local and central entry sets differ")
        }
        if (extracted.size > Stage6BundleLimits.MAX_ENTRY_COUNT) {
            throw Stage5ValidationException("bundle contains too many entries")
        }
        if (!extracted.containsKey(SOTAWARE_BUNDLE_MANIFEST_ENTRY) ||
            !extracted.containsKey(SOTAWARE_BUNDLE_SNAPSHOT_ENTRY)
        ) {
            throw Stage5ValidationException("bundle is missing a required fixed entry")
        }
        return extracted
    }

    private fun scanCentralDirectory(
        archive: Path,
        cancellationCheck: (() -> Unit)? = null
    ): List<CentralEntry> {
        val archiveLength = Files.size(archive)
        if (archiveLength < 22L || archiveLength > Stage6BundleLimits.MAX_ARCHIVE_BYTES.toLong()) {
            throw Stage5ValidationException("bundle archive is truncated or oversized")
        }
        RandomAccessFile(archive.toFile(), "r").use { file ->
            val eocd = findEndOfCentralDirectory(file)
            val disk = readU16(file, eocd + 4L)
            val centralDisk = readU16(file, eocd + 6L)
            val entriesOnDisk = readU16(file, eocd + 8L)
            val entryCount = readU16(file, eocd + 10L)
            if (disk != 0 || centralDisk != 0 || entriesOnDisk != entryCount) {
                throw Stage5ValidationException("multi-disk ZIP bundles are not supported")
            }
            if (entryCount == 0xFFFF) throw Stage5ValidationException("ZIP64 bundles are not supported")
            if (entryCount > Stage6BundleLimits.MAX_ENTRY_COUNT) {
                throw Stage5ValidationException("bundle entry count exceeds its limit")
            }
            val centralSize = readU32(file, eocd + 12L)
            val centralOffset = readU32(file, eocd + 16L)
            if (centralOffset > archiveLength || centralSize > archiveLength ||
                centralOffset > archiveLength - centralSize ||
                centralOffset + centralSize != eocd
            ) {
                throw Stage5ValidationException("bundle central directory bounds are invalid")
            }

            val result = ArrayList<CentralEntry>(entryCount)
            val names = LinkedHashSet<String>(entryCount)
            var position = centralOffset
            val centralEnd = centralOffset + centralSize
            var uncompressedTotal = 0L
            var compressedTotal = 0L
            repeat(entryCount) {
                cancellationCheck?.invoke()
                if (position > centralEnd - 46L || readU32(file, position) != 0x02014b50L) {
                    throw Stage5ValidationException("bundle central directory is malformed")
                }
                val madeBy = readU16(file, position + 4L)
                val versionNeeded = readU16(file, position + 6L)
                val flags = readU16(file, position + 8L)
                val method = readU16(file, position + 10L)
                val crc = readU32(file, position + 16L)
                val compressedSize = readU32(file, position + 20L)
                val uncompressedSize = readU32(file, position + 24L)
                val nameLength = readU16(file, position + 28L)
                val extraLength = readU16(file, position + 30L)
                val commentLength = readU16(file, position + 32L)
                val externalAttributes = readU32(file, position + 38L)
                val localHeaderOffset = readU32(file, position + 42L)
                if (compressedSize == UINT32_MAX || uncompressedSize == UINT32_MAX || localHeaderOffset == UINT32_MAX) {
                    throw Stage5ValidationException("ZIP64 entry sizes are not supported")
                }
                val headerLength = 46L + nameLength + extraLength + commentLength
                if (position > centralEnd - headerLength) {
                    throw Stage5ValidationException("bundle central directory entry is truncated")
                }
                val nameBytes = readBytes(file, position + 46L, nameLength)
                val extraBytes = readBytes(file, position + 46L + nameLength, extraLength)
                validateExtraFields(extraBytes, "bundle central directory entry")
                val name = decodeUtf8(nameBytes, "bundle entry name")
                validateZipEntryName(name)
                if (!names.add(name)) throw Stage5ValidationException("bundle contains a duplicate entry: $name")
                val madeByPlatform = madeBy ushr 8
                validateExternalAttributes(name, madeByPlatform, externalAttributes)
                validateZipFlags(flags, method, "bundle central directory entry")
                if (versionNeeded > 20) {
                    throw Stage5ValidationException("bundle entry requires an unsupported ZIP version")
                }
                if (method != 0 && method != 8) throw Stage5ValidationException("unsupported bundle compression method")
                if (method == 0 && compressedSize != uncompressedSize) {
                    throw Stage5ValidationException("stored bundle entry sizes do not match")
                }
                val maxBytes = when {
                    name == SOTAWARE_BUNDLE_MANIFEST_ENTRY -> Stage6BundleLimits.MAX_MANIFEST_BYTES.toLong()
                    name == SOTAWARE_BUNDLE_SNAPSHOT_ENTRY -> Stage5Limits.MAX_JSON_BYTES.toLong()
                    else -> Stage5Limits.MAX_PHOTO_BYTES.toLong()
                }
                if (uncompressedSize <= 0L || uncompressedSize > maxBytes) {
                    throw Stage5ValidationException("bundle entry exceeds its uncompressed limit: $name")
                }
                if (compressedSize <= 0L || compressedSize > archiveLength) {
                    throw Stage5ValidationException("bundle entry has an invalid compressed size: $name")
                }
                if (uncompressedSize > compressedSize * Stage6BundleLimits.MAX_COMPRESSION_RATIO) {
                    throw Stage5ValidationException("bundle entry compression ratio exceeds its limit: $name")
                }
                uncompressedTotal = addBounded(
                    uncompressedTotal,
                    uncompressedSize,
                    Stage6BundleLimits.MAX_TOTAL_UNCOMPRESSED_BYTES,
                    "bundle uncompressed size"
                )
                compressedTotal = addBounded(
                    compressedTotal,
                    compressedSize,
                    archiveLength,
                    "bundle compressed size"
                )
                validateLocalHeader(
                    file,
                    localHeaderOffset,
                    nameBytes,
                    flags,
                    method,
                    crc,
                    compressedSize,
                    uncompressedSize,
                    centralOffset
                )
                result += CentralEntry(
                    name = name,
                    nameBytes = nameBytes,
                    flags = flags,
                    method = method,
                    crc = crc,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    localHeaderOffset = localHeaderOffset,
                    externalAttributes = externalAttributes,
                    madeByPlatform = madeByPlatform
                )
                position += headerLength
            }
            if (position != centralEnd) {
                throw Stage5ValidationException("bundle central directory has trailing bytes")
            }
            return result
        }
    }

    private fun validateLocalHeader(
        file: RandomAccessFile,
        offset: Long,
        centralNameBytes: ByteArray,
        centralFlags: Int,
        centralMethod: Int,
        centralCrc: Long,
        compressedSize: Long,
        uncompressedSize: Long,
        centralOffset: Long
    ) {
        val archiveLength = file.length()
        if (offset < 0L || offset > archiveLength - 30L) {
            throw Stage5ValidationException("bundle local header offset is invalid")
        }
        if (readU32(file, offset) != 0x04034b50L) {
            throw Stage5ValidationException("bundle local header is malformed")
        }
        val versionNeeded = readU16(file, offset + 4L)
        val flags = readU16(file, offset + 6L)
        val method = readU16(file, offset + 8L)
        val localCrc = readU32(file, offset + 14L)
        val localCompressedSize = readU32(file, offset + 18L)
        val localUncompressedSize = readU32(file, offset + 22L)
        val nameLength = readU16(file, offset + 26L)
        val extraLength = readU16(file, offset + 28L)
        if (flags != centralFlags || method != centralMethod) {
            throw Stage5ValidationException("bundle local and central headers differ")
        }
        if (versionNeeded > 20) {
            throw Stage5ValidationException("bundle local header requires an unsupported ZIP version")
        }
        validateZipFlags(flags, method, "bundle local header")
        if (centralFlags and 0x0008 == 0 &&
            (localCompressedSize != compressedSize ||
                localUncompressedSize != uncompressedSize ||
                localCrc != centralCrc)
        ) {
            throw Stage5ValidationException("bundle local and central sizes differ")
        }
        val nameStart = offset + 30L
        val dataStart = nameStart + nameLength + extraLength
        if (nameLength != centralNameBytes.size ||
            dataStart > centralOffset ||
            compressedSize > centralOffset - dataStart ||
            compressedSize > archiveLength - dataStart
        ) {
            throw Stage5ValidationException("bundle local entry bounds are invalid")
        }
        val localName = readBytes(file, nameStart, nameLength)
        if (!localName.contentEquals(centralNameBytes)) {
            throw Stage5ValidationException("bundle local and central entry names differ")
        }
        val localExtra = readBytes(file, nameStart + nameLength, extraLength)
        validateExtraFields(localExtra, "bundle local entry")
    }

    private fun parseManifest(bytes: ByteArray): BundleManifestV1 {
        val root = com.example.myapplication.stage5.parseBoundedJsonObject(
            ByteArrayInputStream(bytes),
            Stage6BundleLimits.MAX_MANIFEST_BYTES,
            "bundle manifest"
        )
        rejectUnknownFields(
            root,
            setOf("formatVersion", "snapshotSchemaVersion", "exportedDocumentId", "source", "snapshot", "photos"),
            "bundle manifest"
        )
        val source = requireObject(root, "source", "bundle manifest")
        rejectUnknownFields(
            source,
            setOf("sourceUri", "displayName", "providerMetadata", "sourceFingerprint"),
            "bundle manifest.source"
        )
        val fingerprint = requireObject(source, "sourceFingerprint", "bundle manifest.source")
        rejectUnknownFields(fingerprint, setOf("algorithm", "digestHex", "byteCount"), "bundle manifest.source.sourceFingerprint")
        val fingerprintAlgorithm = requireString(
            fingerprint,
            "algorithm",
            "bundle manifest.source.sourceFingerprint",
            32
        )
        if (!fingerprintAlgorithm.equals(SourceFingerprint.SHA256_ALGORITHM, ignoreCase = true)) {
            throw Stage5ValidationException("bundle source fingerprint algorithm is unsupported")
        }
        val sourceFingerprint = BundleSourceFingerprintV1(
            algorithm = SourceFingerprint.SHA256_ALGORITHM,
            digestHex = requireDigest(
                requireString(fingerprint, "digestHex", "bundle manifest.source.sourceFingerprint", 64),
                "bundle source fingerprint SHA-256"
            ),
            byteCount = requireLong(fingerprint, "byteCount", "bundle manifest.source.sourceFingerprint", 0L)
        )
        val providerMetadataObject = requireObject(source, "providerMetadata", "bundle manifest.source")
        if (providerMetadataObject.size() > Stage5Limits.MAX_PROVIDER_PROPERTIES) {
            throw Stage5ValidationException("bundle provider metadata exceeds its limit")
        }
        val providerMetadata = LinkedHashMap<String, String>(providerMetadataObject.size())
        providerMetadataObject.entrySet().forEach { (key, value) ->
            if (key.isBlank() || key.length > Stage5Limits.MAX_STRING_CHARS) {
                throw Stage5ValidationException("bundle provider metadata key is unsafe")
            }
            providerMetadata[key] = requireStringElement(value, "bundle provider metadata[$key]", Stage5Limits.MAX_STRING_CHARS)
        }
        val sourceManifest = BundleSourceManifestV1(
            sourceUri = requireString(source, "sourceUri", "bundle manifest.source", Stage5Limits.MAX_STRING_CHARS),
            displayName = optionalString(source, "displayName", "bundle manifest.source", Stage5Limits.MAX_STRING_CHARS),
            providerMetadata = Collections.unmodifiableMap(providerMetadata),
            sourceFingerprint = sourceFingerprint
        )

        val snapshot = requireObject(root, "snapshot", "bundle manifest")
        rejectUnknownFields(snapshot, setOf("revision", "byteCount", "sha256"), "bundle manifest.snapshot")
        val snapshotDescriptor = BundleSnapshotDescriptorV1(
            revision = requireLong(snapshot, "revision", "bundle manifest.snapshot", 0L),
            byteCount = requireLong(snapshot, "byteCount", "bundle manifest.snapshot", 1L, Stage5Limits.MAX_JSON_BYTES.toLong()),
            sha256 = requireDigest(requireString(snapshot, "sha256", "bundle manifest.snapshot", 64), "bundle snapshot SHA-256")
        )
        val photosArray = requireArray(root, "photos", "bundle manifest")
        if (photosArray.size() > Stage5Limits.MAX_TOTAL_PHOTOS) {
            throw Stage5ValidationException("bundle photo descriptor count exceeds its limit")
        }
        val photoNames = LinkedHashSet<String>(photosArray.size())
        val photos = photosArray.mapIndexed { index, value ->
            val photo = requireObjectElement(value, "bundle manifest.photos[$index]")
            rejectUnknownFields(photo, setOf("fileName", "byteCount", "sha256", "mimeType", "width", "height"), "bundle manifest.photos[$index]")
            val fileName = requireString(photo, "fileName", "bundle manifest.photos[$index]", Stage5Limits.MAX_STRING_CHARS)
            validatePhotoFileName(fileName)
            if (!photoNames.add(fileName)) throw Stage5ValidationException("bundle contains a duplicate photo descriptor")
            val descriptor = BundlePhotoDescriptorV1(
                fileName = fileName,
                byteCount = requireLong(photo, "byteCount", "bundle manifest.photos[$index]", 1L, Stage5Limits.MAX_PHOTO_BYTES.toLong()),
                sha256 = requireDigest(requireString(photo, "sha256", "bundle manifest.photos[$index]", 64), "bundle photo SHA-256"),
                mimeType = requireString(photo, "mimeType", "bundle manifest.photos[$index]", 32),
                width = requireInt(photo, "width", "bundle manifest.photos[$index]", 1, Stage5Limits.MAX_IMAGE_WIDTH),
                height = requireInt(photo, "height", "bundle manifest.photos[$index]", 1, Stage5Limits.MAX_IMAGE_HEIGHT)
            )
            if (descriptor.mimeType !in com.example.myapplication.stage5.ImageInfo.APPROVED_IMAGE_MIME_TYPES) {
                throw Stage5ValidationException("bundle photo MIME type is unsupported")
            }
            if (descriptor.width.toLong() * descriptor.height.toLong() > Stage5Limits.MAX_IMAGE_PIXELS) {
                throw Stage5ValidationException("bundle photo dimensions exceed their limit")
            }
            descriptor
        }

        val formatVersion = requireInt(root, "formatVersion", "bundle manifest", SOTAWARE_BUNDLE_FORMAT_VERSION, SOTAWARE_BUNDLE_FORMAT_VERSION)
        val snapshotSchemaVersion = requireInt(root, "snapshotSchemaVersion", "bundle manifest", 1, 1)
        val exportedDocumentId = requireString(root, "exportedDocumentId", "bundle manifest", Stage5Limits.MAX_ID_CHARS)
        try {
            DocumentId.parse(exportedDocumentId)
        } catch (error: IllegalArgumentException) {
            throw Stage5ValidationException("bundle exported document identity is invalid", error)
        }
        return BundleManifestV1(
            formatVersion = formatVersion,
            snapshotSchemaVersion = snapshotSchemaVersion,
            exportedDocumentId = exportedDocumentId,
            source = sourceManifest,
            snapshot = snapshotDescriptor,
            photos = Collections.unmodifiableList(photos)
        )
    }

    private fun BundleSourceManifestV1.toSourceIdentity(): DocumentSourceIdentityV1 =
        DocumentSourceIdentityV1(sourceUri, displayName, providerMetadata)

    private fun BundleSourceManifestV1.toSourceFingerprint(): SourceFingerprint =
        SourceFingerprint(
            SourceFingerprint.SHA256_ALGORITHM,
            sourceFingerprint.digestHex.lowercase(Locale.ROOT),
            sourceFingerprint.byteCount
        )

    private fun BundlePhotoDescriptorV1.toPhotoDescriptor(): PhotoDescriptor =
        PhotoDescriptor(byteCount, sha256.lowercase(Locale.ROOT), mimeType, width, height)

    private fun PhotoDescriptor.toManifestDescriptor(name: String): BundlePhotoDescriptorV1 =
        BundlePhotoDescriptorV1(name, byteCount, sha256, mimeType, width, height)

    private fun SourceFingerprint.toManifestFingerprint(): BundleSourceFingerprintV1 =
        BundleSourceFingerprintV1(
            SourceFingerprint.SHA256_ALGORITHM,
            digestHex.lowercase(Locale.ROOT),
            byteCount
        )

    private fun normalizeFingerprint(value: SourceFingerprint): SourceFingerprint =
        SourceFingerprint(
            SourceFingerprint.SHA256_ALGORITHM,
            value.digestHex.lowercase(Locale.ROOT),
            value.byteCount
        )

    private suspend fun rollbackPhotoOnly(transaction: PhotoContentTransaction?): Throwable? =
        withContext(NonCancellable) {
            if (transaction == null) return@withContext null
            if (transaction.hasAuthoritativeCommit()) {
                transaction.releaseAfterFailure()
                return@withContext DocumentBundleException("photo commit authority is uncertain")
            }
            try {
                transaction.rollback()
                null
            } catch (error: Throwable) {
                transaction.releaseAfterFailure()
                error
            }
        }

    private suspend fun rollbackAll(
        host: DocumentBundleImportHost,
        photoTransaction: PhotoContentTransaction?,
        previousDurableState: DocumentDurableSnapshotState?,
        previousLive: DocumentSnapshotV1?,
        restoreCanonical: Boolean,
        original: Throwable
    ): Throwable? = withContext(NonCancellable) {
        val failures = mutableListOf<Throwable>()
        val photoAuthorityRetained = photoTransaction?.hasAuthoritativeCommit() == true
        var photoRecoveryEvidenceRetained = false
        if (photoTransaction != null) {
            if (photoAuthorityRetained) {
                // Never restore canonical metadata over a photo transaction
                // whose commit marker is authoritative or ambiguous: that
                // would knowingly create a mixed old/new document.
                failures += DocumentBundleException("photo commit authority is uncertain")
            } else if (restoreCanonical) {
                try {
                    photoTransaction.rollbackForCrossStoreCompensation()
                    photoRecoveryEvidenceRetained = true
                } catch (error: Throwable) {
                    failures += error
                }
            } else {
                // This branch is only used for pre-mutation validation
                // failures.  Canonical state has not been exposed yet, so
                // ordinary transaction rollback is safe here.
                try {
                    photoTransaction.rollback()
                } catch (error: Throwable) {
                    failures += error
                }
            }
        }
        var canonicalRestored = !restoreCanonical
        if (restoreCanonical && !photoAuthorityRetained) {
            if (previousDurableState == null || previousLive == null) {
                failures += DocumentBundleException("previous canonical state was unavailable for rollback")
            } else {
                try {
                    when (val restored = host.restore(previousDurableState, previousLive)) {
                        SessionSnapshotApplyResult.Applied -> {
                            val verificationFailure = verifyCanonicalRestore(
                                host = host,
                                expectedDurableState = previousDurableState,
                                expectedLive = previousLive,
                            )
                            if (verificationFailure == null) {
                                canonicalRestored = true
                            } else {
                                failures += verificationFailure
                            }
                        }
                        SessionSnapshotApplyResult.Stale ->
                            failures += DocumentBundleException("canonical rollback became stale")
                        is SessionSnapshotApplyResult.Failed ->
                            failures += DocumentBundleException("canonical rollback failed: ${restored.error}")
                    }
                } catch (error: Throwable) {
                    failures += error
                }
            }
        }
        if (restoreCanonical && photoTransaction != null && !photoAuthorityRetained &&
            photoRecoveryEvidenceRetained && canonicalRestored && failures.isEmpty()
        ) {
            try {
                // This is the recovery journal's finalization point.  It is
                // deliberately after verified durable/live restoration.
                photoTransaction.completeCrossStoreRollback()
            } catch (error: Throwable) {
                failures += error
            }
        }
        if (failures.isEmpty()) {
            null
        } else {
            val failure = DocumentBundleException("bundle rollback was not proven complete", failures.first())
            failures.drop(1).forEach(failure::addSuppressed)
            if (original !== failure && failure.suppressed.none { it === original }) failure.addSuppressed(original)
            photoTransaction?.releaseAfterFailure()
            failure
        }
    }

    private suspend fun verifyCanonicalRestore(
        host: DocumentBundleImportHost,
        expectedDurableState: DocumentDurableSnapshotState,
        expectedLive: DocumentSnapshotV1
    ): Throwable? {
        val restoredLive = host.captureCurrentLiveSnapshot()
        if (restoredLive != expectedLive) {
            return DocumentBundleException("canonical live state was not restored exactly")
        }
        val restoredDurableState = host.captureCurrentDurableState()
        if (!durableStateMatches(expectedDurableState, restoredDurableState)) {
            return DocumentBundleException("canonical durable state was not restored exactly")
        }
        return null
    }

    private fun durableStateMatches(
        expected: DocumentDurableSnapshotState,
        actual: DocumentDurableSnapshotState
    ): Boolean {
        fun slotMatches(
            expectedSlot: DurableSnapshotSlot?,
            actualSlot: DurableSnapshotSlot?
        ): Boolean {
            if (expectedSlot == null || actualSlot == null) return expectedSlot == actualSlot
            val expectedBytes = expectedSlot.serializedBytes
            val actualBytes = actualSlot.serializedBytes
            return expectedSlot.snapshot == actualSlot.snapshot &&
                expectedSlot.sourceFingerprint == actualSlot.sourceFingerprint &&
                (expectedBytes == null || actualBytes == null || expectedBytes.contentEquals(actualBytes))
        }
        return slotMatches(expected.current, actual.current) &&
            slotMatches(expected.previous, actual.previous)
    }

    private fun validateZipEntryName(name: String) {
        if (name.isBlank() || name.length > Stage5Limits.MAX_STRING_CHARS ||
            name.any { it.code < 0x20 || it.code == 0x7F || it == '\\' || it == '\u0000' } ||
            name.startsWith('/') || name.contains("//") || name.contains(':') ||
            name.split('/').any { it == "." || it == ".." || it.isEmpty() }
        ) {
            throw Stage5ValidationException("unsafe bundle ZIP entry name")
        }
        when {
            name == SOTAWARE_BUNDLE_MANIFEST_ENTRY || name == SOTAWARE_BUNDLE_SNAPSHOT_ENTRY -> Unit
            name.startsWith(SOTAWARE_BUNDLE_PHOTO_PREFIX) -> {
                val photoName = name.removePrefix(SOTAWARE_BUNDLE_PHOTO_PREFIX)
                if (photoName.contains('/')) throw Stage5ValidationException("nested bundle photo entry")
                validatePhotoFileName(photoName)
            }
            else -> throw Stage5ValidationException("unsupported bundle ZIP entry: $name")
        }
    }

    private fun validateExternalAttributes(name: String, madeByPlatform: Int, externalAttributes: Long) {
        val unixMode = ((externalAttributes ushr 16) and 0xFFFF).toInt()
        val unixType = unixMode and 0xF000
        if (madeByPlatform == 3) {
            // The high word is meaningful for UNIX-made archives.  Reject
            // links, directories, and unknown file types before any entry is
            // opened; a zero mode is also malformed for a UNIX entry.
            if (unixMode == 0) {
                throw Stage5ValidationException("bundle UNIX entry attributes are invalid: $name")
            }
            when (unixType) {
                0x8000 -> Unit
                0x4000 -> throw Stage5ValidationException("bundle ZIP directories are not allowed")
                0xA000 -> throw Stage5ValidationException("bundle ZIP entry is a symlink: $name")
                else -> throw Stage5ValidationException("bundle ZIP entry has an unsupported UNIX type: $name")
            }
            if ((externalAttributes and 0x10L) != 0L) {
                throw Stage5ValidationException("bundle ZIP entry has DOS directory attributes: $name")
            }
        } else {
            // For DOS/unknown producers only the low DOS attribute word is
            // authoritative.  A non-zero UNIX type in that case is a
            // malformed platform/type combination, not a harmless mode bit.
            if (unixType != 0) {
                throw Stage5ValidationException("bundle ZIP entry has malformed platform attributes: $name")
            }
            if ((externalAttributes and 0x10L) != 0L) {
                throw Stage5ValidationException("bundle ZIP directories are not allowed")
            }
        }
    }

    private fun validateZipFlags(flags: Int, method: Int, label: String) {
        // This reader requires the central/local headers to carry the final
        // size and CRC.  Reject data-descriptor entries before opening a
        // ZipInputStream; closeEntry() is not a bounded way to discover a
        // descriptor's actual end.  Only deflate's compression-option bits
        // and the UTF-8 name bit are otherwise meaningful here.  Reject all
        // encryption, patched-data, masking, and reserved feature bits.
        if ((flags and 0x0008) != 0 ||
            (flags and 0xF7F1) != 0 ||
            (method == ZipEntry.STORED && flags and 0x0006 != 0)
        ) {
            throw Stage5ValidationException(
                "$label has unsupported, encrypted, or data-descriptor ZIP flags"
            )
        }
    }

    private fun findEndOfCentralDirectory(file: RandomAccessFile): Long {
        val archiveLength = file.length()
        val start = maxOf(0L, archiveLength - 65_557L)
        var position = archiveLength - 22L
        while (position >= start) {
            if (readU32(file, position) == 0x06054b50L) {
                val commentLength = readU16(file, position + 20L)
                if (position + 22L + commentLength == archiveLength) return position
            }
            position -= 1L
        }
        throw Stage5ValidationException("bundle end of central directory is missing")
    }

    private fun readU16(file: RandomAccessFile, offset: Long): Int {
        return try {
            if (offset < 0L || offset > file.length() - 2L) {
                throw Stage5ValidationException("bundle archive is truncated")
            }
            file.seek(offset)
            file.readUnsignedByte() or (file.readUnsignedByte() shl 8)
        } catch (error: IOException) {
            throw Stage5ValidationException("bundle archive is truncated", error)
        }
    }

    private fun readU32(file: RandomAccessFile, offset: Long): Long {
        return try {
            if (offset < 0L || offset > file.length() - 4L) {
                throw Stage5ValidationException("bundle archive is truncated")
            }
            file.seek(offset)
            file.readUnsignedByte().toLong() or
                (file.readUnsignedByte().toLong() shl 8) or
                (file.readUnsignedByte().toLong() shl 16) or
                (file.readUnsignedByte().toLong() shl 24)
        } catch (error: IOException) {
            throw Stage5ValidationException("bundle archive is truncated", error)
        }
    }

    private fun readBytes(file: RandomAccessFile, offset: Long, length: Int): ByteArray {
        if (length < 0 || offset < 0L || offset > file.length() - length.toLong()) {
            throw Stage5ValidationException("bundle archive is truncated")
        }
        return try {
            file.seek(offset)
            ByteArray(length).also(file::readFully)
        } catch (error: IOException) {
            throw Stage5ValidationException("bundle archive is truncated", error)
        }
    }

    private fun validateExtraFields(bytes: ByteArray, label: String) {
        var offset = 0
        while (offset < bytes.size) {
            if (bytes.size - offset < 4) {
                throw Stage5ValidationException("$label has a malformed extra field")
            }
            val fieldId = (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            val fieldLength = (bytes[offset + 2].toInt() and 0xFF) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 8)
            if (fieldLength > bytes.size - offset - 4) {
                throw Stage5ValidationException("$label has a truncated extra field")
            }
            if (fieldId == 0x0001) {
                throw Stage5ValidationException("ZIP64 bundle entries are not supported")
            }
            offset += 4 + fieldLength
        }
    }

    private fun decodeUtf8(bytes: ByteArray, label: String): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: CharacterCodingException) {
        throw Stage5ValidationException("$label is not valid UTF-8", error)
    }

    private fun addBounded(total: Long, amount: Long, maximum: Long, label: String): Long {
        if (amount < 0L || total > maximum - amount) throw Stage5ValidationException("$label exceeds its limit")
        return total + amount
    }

    private fun rejectUnknownFields(objectValue: JsonObject, allowed: Set<String>, label: String) {
        objectValue.keySet().firstOrNull { it !in allowed }?.let {
            throw Stage5ValidationException("$label contains unsupported field: $it")
        }
    }

    private fun requireObject(objectValue: JsonObject, name: String, label: String): JsonObject =
        requireObjectElement(requiredElement(objectValue, name, label), "$label.$name")

    private fun requireArray(objectValue: JsonObject, name: String, label: String): JsonArray =
        requireArrayElement(requiredElement(objectValue, name, label), "$label.$name")

    private fun requiredElement(objectValue: JsonObject, name: String, label: String): JsonElement {
        if (!objectValue.has(name) || objectValue.get(name).isJsonNull) {
            throw Stage5ValidationException("$label.$name is missing or null")
        }
        return objectValue.get(name)
    }

    private fun requireObjectElement(element: JsonElement, label: String): JsonObject {
        if (!element.isJsonObject) throw Stage5ValidationException("$label must be an object")
        return element.asJsonObject
    }

    private fun requireArrayElement(element: JsonElement, label: String): JsonArray {
        if (!element.isJsonArray) throw Stage5ValidationException("$label must be an array")
        return element.asJsonArray
    }

    private fun requirePrimitive(element: JsonElement, label: String): JsonPrimitive {
        if (!element.isJsonPrimitive) throw Stage5ValidationException("$label must be a primitive")
        return element.asJsonPrimitive
    }

    private fun requireStringElement(element: JsonElement, label: String, maxChars: Int): String {
        val primitive = requirePrimitive(element, label)
        if (!primitive.isString) throw Stage5ValidationException("$label must be a string")
        val value = primitive.asString
        if (value.length > maxChars || value.isBlank()) throw Stage5ValidationException("$label is blank or oversized")
        return value
    }

    private fun requireString(objectValue: JsonObject, name: String, label: String, maxChars: Int): String =
        requireStringElement(requiredElement(objectValue, name, label), "$label.$name", maxChars)

    private fun optionalString(objectValue: JsonObject, name: String, label: String, maxChars: Int): String? =
        if (!objectValue.has(name) || objectValue.get(name).isJsonNull) null
        else requireStringElement(objectValue.get(name), "$label.$name", maxChars)

    private fun requireNumber(objectValue: JsonObject, name: String, label: String): BigDecimal {
        val primitive = requirePrimitive(requiredElement(objectValue, name, label), "$label.$name")
        if (!primitive.isNumber) throw Stage5ValidationException("$label.$name must be a number")
        return try {
            BigDecimal(primitive.asString)
        } catch (error: NumberFormatException) {
            throw Stage5ValidationException("$label.$name is not a valid number", error)
        }
    }

    private fun requireInt(objectValue: JsonObject, name: String, label: String, min: Int, max: Int): Int =
        try {
            requireNumber(objectValue, name, label).intValueExact().also {
                if (it < min || it > max) throw Stage5ValidationException("$label.$name is out of range")
            }
        } catch (error: ArithmeticException) {
            throw Stage5ValidationException("$label.$name must be an integer", error)
        }

    private fun requireLong(objectValue: JsonObject, name: String, label: String, min: Long, max: Long = Long.MAX_VALUE): Long =
        try {
            requireNumber(objectValue, name, label).longValueExact().also {
                if (it < min || it > max) throw Stage5ValidationException("$label.$name is out of range")
            }
        } catch (error: ArithmeticException) {
            throw Stage5ValidationException("$label.$name must be an integer", error)
        }

    private fun requireDigest(value: String, label: String): String {
        val normalized = value.lowercase(Locale.ROOT)
        if (!normalized.matches(Regex("[0-9a-f]{64}"))) throw Stage5ValidationException("$label is invalid")
        return normalized
    }

    private val UINT32_MAX = 0x1_0000_0000L
}
