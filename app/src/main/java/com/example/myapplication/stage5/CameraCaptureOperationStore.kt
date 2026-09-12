package com.example.myapplication.stage5

import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max

/** The version of the small, app-private camera operation journal. */
const val CAMERA_CAPTURE_OPERATION_SCHEMA_VERSION: Int = 1

/** A camera operation's durable lifecycle.  The journal is the authority. */
enum class CameraCaptureOperationStatus {
    PREPARED,
    LAUNCHED,
    RESULT_AVAILABLE,
    RESULT_CANCELLED,
    PROCESSING,
    PUBLISHED,
    COMMITTED,
    DISCARDED
}

/** The result is retained after processing so a duplicate callback is harmless. */
enum class CameraCaptureResult {
    SUCCESS,
    CANCELLED
}

/** Input captured from the current document/session before launching external work. */
data class CameraCaptureOperationRequest(
    /** Operation-owner nonce; it changes when the owner coordinator is rebound. */
    val processInstanceId: String,
    val documentId: DocumentId,
    val sourceUri: String,
    val sourceFingerprint: SourceFingerprint?,
    val sessionGeneration: Long,
    val pageIndex: Int,
    val pinId: String,
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    init {
        requireCameraUuid(processInstanceId, "camera process instance id")
        requireCameraSourceUri(sourceUri)
        require(sessionGeneration > 0L) { "camera session generation must be positive" }
        require(pageIndex in 0 until Stage5Limits.MAX_PAGES) {
            "camera page index is outside the bounded page range"
        }
        requireCameraPinId(pinId)
        requireCameraTimestamp(createdAtMillis, "camera creation time")
    }
}

/**
 * One complete durable camera operation.  Only direct scalar values and the
 * typed source fingerprint are persisted; no Activity, bitmap, or mutable pin
 * object is part of the record.
 */
data class CameraCaptureOperationRecord(
    val schemaVersion: Int,
    val revision: Long,
    val operationId: String,
    val processInstanceId: String,
    val documentId: String,
    val sourceUri: String,
    val sourceFingerprint: SourceFingerprint?,
    val sessionGeneration: Long,
    val pageIndex: Int,
    val pinId: String,
    val captureFileName: String,
    /** Reserved before launch so publication can be idempotent after a crash. */
    val publishedPhotoFileName: String?,
    val status: CameraCaptureOperationStatus,
    val result: CameraCaptureResult?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
) {
    init {
        require(schemaVersion == CAMERA_CAPTURE_OPERATION_SCHEMA_VERSION) {
            "unsupported camera operation schema: $schemaVersion"
        }
        require(revision in 1L..MAX_CAMERA_OPERATION_REVISIONS) {
            "camera operation revision is outside its bounded range"
        }
        requireCameraUuid(operationId, "camera operation id")
        requireCameraUuid(processInstanceId, "camera process instance id")
        val parsedDocumentId = try {
            DocumentId.parse(documentId)
        } catch (error: IllegalArgumentException) {
            throw Stage5ValidationException("camera document id is invalid", error)
        }
        require(parsedDocumentId.value == documentId) {
            "camera document id is not in canonical form"
        }
        requireCameraSourceUri(sourceUri)
        require(sessionGeneration > 0L) { "camera session generation must be positive" }
        require(pageIndex in 0 until Stage5Limits.MAX_PAGES) {
            "camera page index is outside the bounded page range"
        }
        requireCameraPinId(pinId)
        val expectedCapture = CameraCaptureFilePolicy.FILE_PREFIX +
            operationId + CameraCaptureFilePolicy.FILE_EXTENSION
        require(captureFileName == expectedCapture &&
            CameraCaptureFilePolicy.isOwnedCaptureFileName(captureFileName)) {
            "camera capture file is not owned by this operation"
        }
        publishedPhotoFileName?.let { name ->
            validatePhotoFileName(name)
            require(name == deterministicPublishedPhotoFileName(operationId)) {
                "camera published photo name is not owned by this operation"
            }
        }
        requireCameraTimestamp(createdAtMillis, "camera creation time")
        requireCameraTimestamp(updatedAtMillis, "camera update time")
        require(updatedAtMillis >= createdAtMillis) {
            "camera operation update time precedes creation time"
        }
        when (status) {
            CameraCaptureOperationStatus.PREPARED,
            CameraCaptureOperationStatus.LAUNCHED -> {
                require(result == null) { "camera result arrived before launch completion" }
            }
            CameraCaptureOperationStatus.RESULT_AVAILABLE -> {
                require(result == CameraCaptureResult.SUCCESS) {
                    "available camera result must be successful"
                }
            }
            CameraCaptureOperationStatus.RESULT_CANCELLED -> {
                require(result == CameraCaptureResult.CANCELLED) {
                    "cancelled camera result must be cancelled"
                }
            }
            CameraCaptureOperationStatus.PROCESSING,
            CameraCaptureOperationStatus.PUBLISHED,
            CameraCaptureOperationStatus.COMMITTED -> {
                require(result == CameraCaptureResult.SUCCESS) {
                    "processed camera operation must have a successful result"
                }
            }
            CameraCaptureOperationStatus.DISCARDED -> Unit
        }
        if (status == CameraCaptureOperationStatus.PUBLISHED ||
            status == CameraCaptureOperationStatus.COMMITTED
        ) {
            require(!publishedPhotoFileName.isNullOrBlank()) {
                "published camera operation is missing its reserved photo name"
            }
        }
    }
}

open class CameraCaptureOperationException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

class CameraCaptureOperationNotFoundException(message: String) :
    CameraCaptureOperationException(message)

class CameraCaptureOperationConflictException(message: String) :
    CameraCaptureOperationException(message)

class CameraCaptureOperationCorruptException(
    message: String,
    cause: Throwable? = null
) : CameraCaptureOperationException(message, cause)

class CameraCaptureOperationIoException(
    message: String,
    cause: Throwable? = null
) : CameraCaptureOperationException(message, cause)

/**
 * Outcome of the explicit interrupted-publication reconciliation pass.
 * Ordinary journal reads deliberately do not use this operation: a staged
 * revision remains a hard failure until this pass proves that it is complete
 * and can be published without ambiguity.
 */
enum class CameraCaptureJournalReconciliationDisposition {
    NONE,
    PUBLISHED_STAGED_REVISION,
    RETAINED_PARTIAL,
    RETAINED_AMBIGUOUS
}

data class CameraCaptureJournalReconciliation(
    val disposition: CameraCaptureJournalReconciliationDisposition,
    val operation: CameraCaptureOperationRecord? = null,
    val retainedStagedFileNames: Set<String> = emptySet()
) {
    val resolved: Boolean
        get() = disposition == CameraCaptureJournalReconciliationDisposition.NONE ||
            disposition == CameraCaptureJournalReconciliationDisposition.PUBLISHED_STAGED_REVISION
}

/** Result of the production maintenance entry point. */
data class CameraCaptureMaintenanceResult(
    val journal: CameraCaptureJournalReconciliation,
    val removedOrphanedCaptureFiles: Int
)

/**
 * Durable operation journal and contained capture-file owner.  The operation
 * journal is in [CameraCaptureFilePolicy.OPERATION_DIRECTORY], deliberately
 * outside the FileProvider-exposed `camera_captures` root.
 *
 * Journal revisions are immutable CREATE_NEW files.  This avoids a delete-then-
 * replace window on providers whose secure primitive intentionally does not
 * permit replacement.  A crash can leave several valid revisions; the highest
 * revision is authoritative only after every retained revision agrees on the
 * operation's stable identity.
 */
class CameraCaptureOperationStore internal constructor(
    filesDirectory: File,
    operationsFactory: PhotoPathOperationsFactory?
) : AutoCloseable {
    constructor(filesDirectory: File) : this(filesDirectory, null)

    /** JVM tests inject the same bounded path seam used by Stage 5 tests. */
    private val captureResolver = newResolver(
        filesDirectory,
        File(filesDirectory, CameraCaptureFilePolicy.ROOT_DIRECTORY),
        operationsFactory
    )
    private val operationResolver = newResolver(
        filesDirectory,
        File(filesDirectory, CameraCaptureFilePolicy.OPERATION_DIRECTORY),
        operationsFactory
    )
    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .serializeNulls()
        .create()

    /** Creates an unjournaled legacy capture only when no operation is active. */
    fun newCaptureFile(): File = CameraCaptureOperationCriticalSection.withLock {
        requireNoOperationLocked()
        newCaptureFileLocked()
    }

    /** Deletes only an unjournaled compatibility capture. */
    internal fun discardUnjournaledCaptureFile(file: File) =
        CameraCaptureOperationCriticalSection.withLock {
            val active = readOperationLocked()
            if (active?.captureFileName == file.name) {
                throw CameraCaptureOperationConflictException(
                    "active camera capture must be resolved through its operation"
                )
            }
            if (!CameraCaptureFilePolicy.isOwnedCaptureFileName(file.name)) {
                throw Stage5ValidationException("unsafe camera capture file")
            }
            val path = file.toPath()
            captureResolver.ensureContained(path, "camera capture")
            if (captureResolver.exists(path)) {
                if (!captureResolver.isRegularFile(path)) {
                    throw CameraCaptureOperationCorruptException(
                        "camera capture is not a regular file"
                    )
                }
                captureResolver.deletePath(path, "camera capture")
            }
        }

    /**
     * Creates the capture file and PREPARED journal revision under one process
     * lock.  The caller must not launch external work until this returns.
     */
    fun prepare(request: CameraCaptureOperationRequest): CameraCaptureOperationRecord =
        CameraCaptureOperationCriticalSection.withLock {
            requireNoOperationLocked()
            val operationId = UUID.randomUUID().toString()
            val captureName = CameraCaptureFilePolicy.FILE_PREFIX + operationId +
                CameraCaptureFilePolicy.FILE_EXTENSION
            val capture = captureResolver.root.resolve(captureName)
            captureResolver.ensureContained(capture.toPath(), "camera capture")
            try {
                captureResolver.openNewOutput(capture.toPath(), "camera capture").use {
                    it.force(true)
                }
                val now = request.createdAtMillis
                val record = CameraCaptureOperationRecord(
                    schemaVersion = CAMERA_CAPTURE_OPERATION_SCHEMA_VERSION,
                    revision = 1L,
                    operationId = operationId,
                    processInstanceId = request.processInstanceId,
                    documentId = request.documentId.value,
                    sourceUri = request.sourceUri,
                    sourceFingerprint = request.sourceFingerprint,
                    sessionGeneration = request.sessionGeneration,
                    pageIndex = request.pageIndex,
                    pinId = request.pinId,
                    captureFileName = captureName,
                    publishedPhotoFileName = deterministicPublishedPhotoFileName(operationId),
                    status = CameraCaptureOperationStatus.PREPARED,
                    result = null,
                    createdAtMillis = now,
                    updatedAtMillis = now
                )
                writeRecordLocked(record)
                record
            } catch (error: Throwable) {
                // If a complete journal revision was published, it is now the
                // authority and the capture must remain recoverable.  Only
                // remove the file when no authoritative revision exists.
                val journalState = runCatching { readOperationLocked() }
                if (journalState.isSuccess && journalState.getOrNull() == null) {
                    runCatching { captureResolver.deletePath(capture.toPath(), "camera capture") }
                }
                throw error
            }
        }

    fun readOperation(): CameraCaptureOperationRecord? =
        CameraCaptureOperationCriticalSection.withLock { readOperationLocked() }

    /**
     * Reconciles a complete interrupted journal revision, if and only if its
     * identity and immediately preceding revision are unambiguous.  Partial,
     * conflicting, and otherwise ambiguous bytes are left in place as
     * recovery evidence.  This method is intentionally separate from
     * [readOperation], whose fail-closed behavior is part of the ordinary
     * journal contract.
     */
    fun reconcileInterruptedJournal(): CameraCaptureJournalReconciliation =
        CameraCaptureOperationCriticalSection.withLock {
            reconcileInterruptedJournalLocked()
        }

    /** Alias named after the on-disk representation for recovery callers. */
    fun reconcileStagedJournal(): CameraCaptureJournalReconciliation =
        reconcileInterruptedJournal()

    /**
     * Production startup/foreground maintenance seam.  The orphan sweep is
     * reachable only after journal reconciliation has resolved the directory;
     * retained staged bytes therefore protect their captures by preventing the
     * sweep entirely.
     */
    fun reconcileInterruptedJournalAndSweep(
        nowMillis: Long = System.currentTimeMillis()
    ): CameraCaptureMaintenanceResult = CameraCaptureOperationCriticalSection.withLock {
        val journal = reconcileInterruptedJournalLocked()
        val removed = if (journal.resolved) {
            cleanupOrphanedCaptureFilesLocked(nowMillis)
        } else {
            0
        }
        CameraCaptureMaintenanceResult(journal, removed)
    }

    /** Returns the safely resolved capture path for the current exact operation. */
    fun captureFile(operationId: String): File =
        CameraCaptureOperationCriticalSection.withLock {
            val record = requireCurrentLocked(operationId)
            capturePath(record)
        }

    /** Read through the pinned directory descriptor, not the advisory FileProvider path. */
    fun <T> withCaptureInput(operationId: String, action: (java.io.InputStream) -> T): T =
        CameraCaptureOperationCriticalSection.withLock {
            val current = requireCurrentLocked(operationId)
            check(current.status == CameraCaptureOperationStatus.PROCESSING ||
                current.status == CameraCaptureOperationStatus.PUBLISHED) {
                "camera capture is not ready for publication"
            }
            captureResolver.openRead(capturePath(current).toPath(), "camera publication input").use(action)
        }

    fun markLaunched(
        operationId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): CameraCaptureOperationRecord = transition(operationId, nowMillis) { current, now ->
        when (current.status) {
            CameraCaptureOperationStatus.PREPARED -> current.copy(
                revision = current.revision + 1L,
                status = CameraCaptureOperationStatus.LAUNCHED,
                updatedAtMillis = now
            )
            CameraCaptureOperationStatus.LAUNCHED,
            CameraCaptureOperationStatus.RESULT_AVAILABLE,
            CameraCaptureOperationStatus.RESULT_CANCELLED,
            CameraCaptureOperationStatus.PROCESSING,
            CameraCaptureOperationStatus.PUBLISHED,
            CameraCaptureOperationStatus.COMMITTED,
            CameraCaptureOperationStatus.DISCARDED -> current
        }
    }

    /**
     * Records the external camera result before the trampoline returns.  A
     * repeated callback is idempotent when its boolean agrees with the durable
     * result; a conflicting callback is rejected without changing the record.
     */
    fun recordResult(
        operationId: String,
        success: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): CameraCaptureOperationRecord =
        CameraCaptureOperationCriticalSection.withLock {
            val current = requireCurrentLocked(operationId)
            val incoming = if (success) CameraCaptureResult.SUCCESS else CameraCaptureResult.CANCELLED
            if (current.result != null) {
                if (current.result != incoming) {
                    throw CameraCaptureOperationConflictException(
                        "camera operation received a conflicting duplicate result"
                    )
                }
                return@withLock current
            }
            val nextStatus = if (success) {
                CameraCaptureOperationStatus.RESULT_AVAILABLE
            } else {
                CameraCaptureOperationStatus.RESULT_CANCELLED
            }
            if (current.status != CameraCaptureOperationStatus.LAUNCHED) {
                throw CameraCaptureOperationConflictException(
                    "camera result arrived in status ${current.status}"
                )
            }
            val next = current.copy(
                revision = current.revision + 1L,
                status = nextStatus,
                result = incoming,
                updatedAtMillis = monotonicTimestamp(current, nowMillis)
            )
            writeRecordLocked(next)
            next
        }

    fun markProcessing(
        operationId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): CameraCaptureOperationRecord = transition(operationId, nowMillis) { current, now ->
        when (current.status) {
            CameraCaptureOperationStatus.RESULT_AVAILABLE -> current.copy(
                revision = current.revision + 1L,
                status = CameraCaptureOperationStatus.PROCESSING,
                updatedAtMillis = now
            )
            CameraCaptureOperationStatus.PROCESSING,
            CameraCaptureOperationStatus.PUBLISHED,
            CameraCaptureOperationStatus.COMMITTED -> current
            CameraCaptureOperationStatus.RESULT_CANCELLED -> throw CameraCaptureOperationConflictException(
                "cancelled camera operation cannot be processed"
            )
            else -> throw CameraCaptureOperationConflictException(
                "camera operation cannot enter processing from ${current.status}"
            )
        }
    }

    /**
     * A deterministic photo name is reserved in PREPARED.  This transition
     * records that the bytes have been published (or were found already) but
     * does not claim canonical annotation commit.
     */
    fun markPublished(
        operationId: String,
        publishedPhotoFileName: String? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): CameraCaptureOperationRecord = transition(operationId, nowMillis) { current, now ->
        val requested = publishedPhotoFileName ?: current.publishedPhotoFileName
            ?: deterministicPublishedPhotoFileName(current.operationId)
        require(requested == deterministicPublishedPhotoFileName(current.operationId)) {
            "camera publication name does not belong to the operation"
        }
        if (current.status == CameraCaptureOperationStatus.PUBLISHED ||
            current.status == CameraCaptureOperationStatus.COMMITTED
        ) {
            require(current.publishedPhotoFileName == requested) {
                "camera publication name conflicts with durable record"
            }
            return@transition current
        }
        require(current.status == CameraCaptureOperationStatus.PROCESSING) {
            "camera operation cannot publish from ${current.status}"
        }
        current.copy(
            revision = current.revision + 1L,
            status = CameraCaptureOperationStatus.PUBLISHED,
            publishedPhotoFileName = requested,
            updatedAtMillis = now
        )
    }

    fun markCommitted(
        operationId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): CameraCaptureOperationRecord = transition(operationId, nowMillis) { current, now ->
        when (current.status) {
            CameraCaptureOperationStatus.PUBLISHED -> current.copy(
                revision = current.revision + 1L,
                status = CameraCaptureOperationStatus.COMMITTED,
                updatedAtMillis = now
            )
            CameraCaptureOperationStatus.COMMITTED -> current
            else -> throw CameraCaptureOperationConflictException(
                "camera operation cannot commit from ${current.status}"
            )
        }
    }

    /** Explicit disposition for a PREPARED operation that was never launched. */
    fun discardPrepared(
        operationId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): CameraCaptureOperationRecord = transition(operationId, nowMillis) { current, now ->
        when (current.status) {
            CameraCaptureOperationStatus.PREPARED -> current.copy(
                revision = current.revision + 1L,
                status = CameraCaptureOperationStatus.DISCARDED,
                updatedAtMillis = now
            )
            CameraCaptureOperationStatus.DISCARDED -> current
            else -> throw CameraCaptureOperationConflictException(
                "only an unlaunched camera operation can be discarded directly"
            )
        }
    }

    /**
     * Explicit recovery for a launch whose external dispatch outcome is known
     * to be abandoned by the user.  This is intentionally never age-based:
     * while the camera may still own the output URI the LAUNCHED record stays
     * recoverable and blocks a replacement operation.
     */
    fun abandonLaunched(
        operationId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): CameraCaptureOperationRecord = transition(operationId, nowMillis) { current, now ->
        when (current.status) {
            CameraCaptureOperationStatus.LAUNCHED -> current.copy(
                revision = current.revision + 1L,
                status = CameraCaptureOperationStatus.DISCARDED,
                updatedAtMillis = now
            )
            CameraCaptureOperationStatus.DISCARDED -> current
            else -> throw CameraCaptureOperationConflictException(
                "only an externally abandoned camera launch can be abandoned directly"
            )
        }
    }

    /**
     * Explicitly discards a result or processing operation.  LAUNCHED is
     * intentionally excluded: the external camera may still own the URI.
     */
    fun markDiscarded(
        operationId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): CameraCaptureOperationRecord = transition(operationId, nowMillis) { current, now ->
        when (current.status) {
            CameraCaptureOperationStatus.RESULT_CANCELLED,
            CameraCaptureOperationStatus.RESULT_AVAILABLE,
            CameraCaptureOperationStatus.PROCESSING,
            CameraCaptureOperationStatus.PUBLISHED -> current.copy(
                revision = current.revision + 1L,
                status = CameraCaptureOperationStatus.DISCARDED,
                updatedAtMillis = now
            )
            CameraCaptureOperationStatus.DISCARDED -> current
            CameraCaptureOperationStatus.COMMITTED -> current
            CameraCaptureOperationStatus.PREPARED,
            CameraCaptureOperationStatus.LAUNCHED -> throw CameraCaptureOperationConflictException(
                "camera operation is still owned by the external launch"
            )
        }
    }

    /**
     * Deletes the capture before deleting all journal revisions.  If either
     * deletion is interrupted, the remaining record makes cleanup retryable.
     * Published document photos are deliberately not deleted here.
     */
    fun cleanup(operationId: String): Boolean =
        CameraCaptureOperationCriticalSection.withLock {
            val current = requireCurrentLocked(operationId)
            require(
                current.status == CameraCaptureOperationStatus.RESULT_CANCELLED ||
                    current.status == CameraCaptureOperationStatus.DISCARDED ||
                    current.status == CameraCaptureOperationStatus.COMMITTED
            ) {
                "camera operation is not in a cleanup-safe terminal state"
            }
            val capture = captureResolver.root.resolve(current.captureFileName)
            captureResolver.ensureContained(capture.toPath(), "camera capture")
            if (captureResolver.exists(capture.toPath())) {
                if (!captureResolver.isRegularFile(capture.toPath())) {
                    throw CameraCaptureOperationCorruptException(
                        "camera capture is not a regular file"
                    )
                }
                captureResolver.deletePath(capture.toPath(), "camera capture")
            }
            // Keep the highest revision until every older revision is gone.
            // If deletion is interrupted, the durable record still describes
            // the same cleanup-safe terminal state and the next attempt can
            // resume instead of exposing an earlier nonterminal status.
            val latestName = operationEventFileName(current.operationId, current.revision)
            val journalNames = journalFilesLocked()
            (journalNames.filter { it != latestName } +
                journalNames.filter { it == latestName }).forEach { name ->
                operationResolver.deletePath(
                    operationResolver.root.resolve(name).toPath(),
                    "camera operation journal"
                )
            }
            true
        }

    /**
     * Removes only old, unowned temporary captures.  Any journal corruption or
     * ambiguous in-flight record fails closed before the directory is touched.
     */
    fun cleanupOrphanedCaptureFiles(
        nowMillis: Long = System.currentTimeMillis()
    ): Int = CameraCaptureOperationCriticalSection.withLock {
        cleanupOrphanedCaptureFilesLocked(nowMillis)
    }

    private fun cleanupOrphanedCaptureFilesLocked(nowMillis: Long): Int {
        requireCameraTimestamp(nowMillis, "camera cleanup time")
        val active = readOperationLocked()?.captureFileName
        val files = captureResolver.root.listFiles()
            ?: throw CameraCaptureOperationIoException("camera capture directory could not be listed")
        if (files.size > Stage5Limits.MAX_PHOTO_DIRECTORY_ENTRIES) {
            throw CameraCaptureOperationCorruptException(
                "camera capture directory exceeds its bounded entry limit"
            )
        }
        var removed = 0
        files.forEach { file ->
            if (!CameraCaptureFilePolicy.isOwnedCaptureFileName(file.name) ||
                file.name == active
            ) {
                return@forEach
            }
            val modified = file.lastModified()
            if (modified <= 0L || modified > nowMillis ||
                nowMillis - modified < Stage5Limits.MAX_CAPTURE_AGE_MILLIS
            ) {
                return@forEach
            }
            captureResolver.ensureContained(file.toPath(), "camera orphan cleanup")
            if (!captureResolver.isRegularFile(file.toPath())) {
                throw CameraCaptureOperationCorruptException(
                    "camera orphan is not a regular file: ${file.name}"
                )
            }
            captureResolver.deletePath(file.toPath(), "camera orphan cleanup")
            removed++
        }
        return removed
    }

    /** Stable identity match used by the recovery coordinator after a restart. */
    fun matchesStableIdentity(
        operation: CameraCaptureOperationRecord,
        documentId: DocumentId,
        sourceUri: String,
        sourceFingerprint: SourceFingerprint?
    ): Boolean = operation.documentId == documentId.value &&
        operation.sourceUri == sourceUri &&
        operation.sourceFingerprint == sourceFingerprint

    /** The journal directory is intentionally not part of FileProvider paths. */
    internal val operationJournalDirectoryForTests: File
        get() = operationResolver.root

    internal fun operationJournalFilesForTests(): List<File> =
        CameraCaptureOperationCriticalSection.withLock {
            journalFilesLocked().map { operationResolver.root.resolve(it) }
        }

    override fun close() {
        CameraCaptureOperationCriticalSection.withLock {
            try {
                operationResolver.close()
            } finally {
                captureResolver.close()
            }
        }
    }

    private fun transition(
        operationId: String,
        nowMillis: Long,
        transform: (CameraCaptureOperationRecord, Long) -> CameraCaptureOperationRecord
    ): CameraCaptureOperationRecord = CameraCaptureOperationCriticalSection.withLock {
        requireCameraUuid(operationId, "camera operation id")
        requireCameraTimestamp(nowMillis, "camera update time")
        val current = requireCurrentLocked(operationId)
        val next = transform(current, monotonicTimestamp(current, nowMillis))
        if (next === current) return@withLock current
        require(next.revision == current.revision + 1L) {
            "camera operation transition did not advance its revision"
        }
        writeRecordLocked(next)
        next
    }

    private fun requireNoOperationLocked() {
        val current = readOperationLocked()
        if (current != null) {
            throw CameraCaptureOperationConflictException(
                "a camera operation is already active: ${current.operationId}"
            )
        }
    }

    private fun requireCurrentLocked(operationId: String): CameraCaptureOperationRecord {
        val current = readOperationLocked()
            ?: throw CameraCaptureOperationNotFoundException(
                "camera operation does not exist: $operationId"
            )
        if (current.operationId != operationId) {
            throw CameraCaptureOperationConflictException(
                "camera operation id does not match the durable record"
            )
        }
        return current
    }

    private fun newCaptureFileLocked(): File {
        val file = captureResolver.root.resolve(
            CameraCaptureFilePolicy.FILE_PREFIX + UUID.randomUUID() +
                CameraCaptureFilePolicy.FILE_EXTENSION
        )
        captureResolver.ensureContained(file.toPath(), "camera capture")
        captureResolver.openNewOutput(file.toPath(), "camera capture").use { it.force(true) }
        return file
    }

    private fun capturePath(record: CameraCaptureOperationRecord): File {
        val path = captureResolver.root.resolve(record.captureFileName)
        captureResolver.ensureContained(path.toPath(), "camera capture")
        if (!captureResolver.exists(path.toPath()) ||
            !captureResolver.isRegularFile(path.toPath())
        ) {
            throw CameraCaptureOperationCorruptException(
                "camera capture is unavailable for the durable operation"
            )
        }
        return path
    }

    private fun writeRecordLocked(record: CameraCaptureOperationRecord) {
        val bytes = gson.toJson(record).toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_CAMERA_OPERATION_RECORD_BYTES) {
            throw CameraCaptureOperationCorruptException(
                "camera operation record exceeds its bounded size"
            )
        }
        val eventName = operationEventFileName(record.operationId, record.revision)
        val temporaryName = operationTemporaryFileName(record.operationId, record.revision)
        val existing = journalFilesLocked()
        if (eventName in existing || temporaryName in existing) {
            throw CameraCaptureOperationCorruptException(
                "camera operation journal revision already exists"
            )
        }
        val temporary = operationResolver.root.resolve(temporaryName)
        val event = operationResolver.root.resolve(eventName)
        try {
            operationResolver.openNewOutput(temporary.toPath(), "camera operation staging").use {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) it.write(buffer)
                it.force(true)
            }
            operationResolver.atomicMove(temporary.toPath(), event.toPath())
        } catch (error: CameraCaptureOperationException) {
            throw error
        } catch (error: IOException) {
            throw CameraCaptureOperationIoException(
                "camera operation journal publication failed",
                error
            )
        } catch (error: SecurityException) {
            throw CameraCaptureOperationIoException(
                "camera operation journal publication failed",
                error
            )
        }
    }

    private fun readOperationLocked(): CameraCaptureOperationRecord? {
        val names = journalFilesLocked()
        if (names.isEmpty()) return null
        if (names.any(::isOperationTemporaryFileName)) {
            throw CameraCaptureOperationCorruptException(
                "camera operation journal contains an unfinished staged revision"
            )
        }
        val records = readEventRecordsLocked(names.filter(::isOperationEventFileName))
        return records.maxByOrNull { it.revision }
    }

    /** Reads complete event files while allowing the recovery pass to inspect
     * them alongside a separately staged revision. */
    private fun readEventRecordsLocked(eventNames: List<String>): List<CameraCaptureOperationRecord> {
        if (eventNames.isEmpty()) return emptyList()
        val records = eventNames.map { name ->
            val path = operationResolver.root.resolve(name)
            val size = try {
                operationResolver.size(path.toPath(), "camera operation journal")
            } catch (error: IOException) {
                throw CameraCaptureOperationIoException(
                    "camera operation journal size could not be read",
                    error
                )
            }
            if (size <= 0L || size > MAX_CAMERA_OPERATION_RECORD_BYTES) {
                throw CameraCaptureOperationCorruptException(
                    "camera operation journal has an invalid size"
                )
            }
            val bytes = try {
                operationResolver.openRead(path.toPath(), "camera operation journal").use {
                    readBoundedBytes(it, MAX_CAMERA_OPERATION_RECORD_BYTES, "camera operation journal")
                }
            } catch (error: Stage5ValidationException) {
                throw CameraCaptureOperationCorruptException(
                    "camera operation journal bytes are invalid",
                    error
                )
            } catch (error: IOException) {
                throw CameraCaptureOperationIoException(
                    "camera operation journal could not be read",
                    error
                )
            }
            parseRecord(bytes, name)
        }
        val operationIds = records.map { it.operationId }.toSet()
        if (operationIds.size != 1) {
            throw CameraCaptureOperationCorruptException(
                "camera journal contains multiple operation identities"
            )
        }
        val first = records.first()
        records.forEach { record ->
            requireSameStableIdentity(first, record)
        }
        val revisions = records.map { it.revision }
        if (revisions.distinct().size != revisions.size) {
            throw CameraCaptureOperationCorruptException(
                "camera journal contains duplicate revisions"
            )
        }
        return records
    }

    private fun reconcileInterruptedJournalLocked(): CameraCaptureJournalReconciliation {
        val names = journalFilesLocked()
        val stagedNames = names.filter(::isOperationTemporaryFileName)
        val eventNames = names.filter(::isOperationEventFileName)
        if (stagedNames.isEmpty()) {
            val events = readEventRecordsLocked(eventNames)
            val latest = events.maxByOrNull { it.revision }
            if (!hasCompleteEventHistory(events) ||
                (latest != null && !captureAvailableForRecoveryLocked(latest))
            ) {
                return CameraCaptureJournalReconciliation(
                    disposition = CameraCaptureJournalReconciliationDisposition.RETAINED_AMBIGUOUS,
                    operation = latest
                )
            }
            return CameraCaptureJournalReconciliation(
                disposition = CameraCaptureJournalReconciliationDisposition.NONE,
                operation = latest
            )
        }

        val previous = readEventRecordsLocked(eventNames)
        val previousLatest = previous.maxByOrNull { it.revision }
        if (stagedNames.size != 1) {
            return CameraCaptureJournalReconciliation(
                disposition = CameraCaptureJournalReconciliationDisposition.RETAINED_AMBIGUOUS,
                operation = previousLatest,
                retainedStagedFileNames = stagedNames.toSet()
            )
        }

        val stagedName = stagedNames.single()
        val staged = try {
            readStagedRecordLocked(stagedName)
        } catch (error: CameraCaptureOperationCorruptException) {
            return CameraCaptureJournalReconciliation(
                disposition = CameraCaptureJournalReconciliationDisposition.RETAINED_PARTIAL,
                operation = previousLatest,
                retainedStagedFileNames = setOf(stagedName)
            )
        }

        val unambiguous = if (previousLatest == null) {
            staged.revision == 1L &&
                staged.status == CameraCaptureOperationStatus.PREPARED &&
                captureAvailableForRecoveryLocked(staged)
        } else {
                hasCompleteEventHistory(previous) &&
                previous.size.toLong() == previousLatest.revision &&
                previous.all { it.operationId == previousLatest.operationId } &&
                sameStableIdentity(previousLatest, staged) &&
                captureAvailableForRecoveryLocked(staged) &&
                staged.revision == previousLatest.revision + 1L &&
                isValidNextRevision(previousLatest, staged)
        }
        if (!unambiguous) {
            return CameraCaptureJournalReconciliation(
                disposition = CameraCaptureJournalReconciliationDisposition.RETAINED_AMBIGUOUS,
                operation = previousLatest,
                retainedStagedFileNames = setOf(stagedName)
            )
        }

        val eventName = operationEventFileName(staged.operationId, staged.revision)
        val eventPath = operationResolver.root.resolve(eventName)
        try {
            operationResolver.atomicMove(
                operationResolver.root.resolve(stagedName).toPath(),
                eventPath.toPath()
            )
        } catch (error: CameraCaptureOperationException) {
            throw error
        } catch (error: IOException) {
            throw CameraCaptureOperationIoException(
                "camera staged journal reconciliation could not publish the complete revision",
                error
            )
        } catch (error: SecurityException) {
            throw CameraCaptureOperationIoException(
                "camera staged journal reconciliation could not publish the complete revision",
                error
            )
        }
        val resolved = readOperationLocked()
            ?: throw CameraCaptureOperationCorruptException(
                "camera staged journal publication disappeared during reconciliation"
            )
        if (resolved.operationId != staged.operationId || resolved.revision != staged.revision) {
            throw CameraCaptureOperationCorruptException(
                "camera staged journal publication resolved to a different revision"
            )
        }
        return CameraCaptureJournalReconciliation(
            disposition = CameraCaptureJournalReconciliationDisposition.PUBLISHED_STAGED_REVISION,
            operation = resolved
        )
    }

    private fun hasCompleteEventHistory(records: List<CameraCaptureOperationRecord>): Boolean {
        if (records.isEmpty()) return true
        val ordered = records.sortedBy { it.revision }
        if (ordered.first().revision != 1L) return false
        return ordered.zipWithNext().all { (previous, next) ->
            isValidNextRevision(previous, next)
        }
    }

    private fun captureAvailableForRecoveryLocked(
        record: CameraCaptureOperationRecord
    ): Boolean {
        val path = captureResolver.root.resolve(record.captureFileName)
        captureResolver.ensureContained(path.toPath(), "camera recovery capture")
        return captureResolver.exists(path.toPath()) &&
            captureResolver.isRegularFile(path.toPath())
    }

    private fun readStagedRecordLocked(stagedName: String): CameraCaptureOperationRecord {
        val eventName = stagedName.removeSuffix(".tmp")
        val path = operationResolver.root.resolve(stagedName)
        val size = try {
            operationResolver.size(path.toPath(), "camera staged operation journal")
        } catch (error: IOException) {
            throw CameraCaptureOperationIoException(
                "camera staged operation journal size could not be read",
                error
            )
        }
        if (size <= 0L || size > MAX_CAMERA_OPERATION_RECORD_BYTES) {
            throw CameraCaptureOperationCorruptException(
                "camera staged operation journal has an invalid size"
            )
        }
        val bytes = try {
            operationResolver.openRead(path.toPath(), "camera staged operation journal").use {
                readBoundedBytes(it, MAX_CAMERA_OPERATION_RECORD_BYTES, "camera staged operation journal")
            }
        } catch (error: Stage5ValidationException) {
            throw CameraCaptureOperationCorruptException(
                "camera staged operation journal bytes are invalid",
                error
            )
        } catch (error: IOException) {
            throw CameraCaptureOperationIoException(
                "camera staged operation journal could not be read",
                error
            )
        }
        return parseRecord(bytes, eventName)
    }

    private fun isValidNextRevision(
        previous: CameraCaptureOperationRecord,
        next: CameraCaptureOperationRecord
    ): Boolean {
        if (next.revision != previous.revision + 1L ||
            next.updatedAtMillis < previous.updatedAtMillis
        ) return false
        return when (previous.status) {
            CameraCaptureOperationStatus.PREPARED ->
                next.status == CameraCaptureOperationStatus.LAUNCHED ||
                    next.status == CameraCaptureOperationStatus.DISCARDED
            CameraCaptureOperationStatus.LAUNCHED ->
                next.status == CameraCaptureOperationStatus.RESULT_AVAILABLE ||
                    next.status == CameraCaptureOperationStatus.RESULT_CANCELLED ||
                    next.status == CameraCaptureOperationStatus.DISCARDED
            CameraCaptureOperationStatus.RESULT_AVAILABLE ->
                next.status == CameraCaptureOperationStatus.PROCESSING ||
                    next.status == CameraCaptureOperationStatus.DISCARDED
            CameraCaptureOperationStatus.RESULT_CANCELLED ->
                next.status == CameraCaptureOperationStatus.DISCARDED
            CameraCaptureOperationStatus.PROCESSING ->
                next.status == CameraCaptureOperationStatus.PUBLISHED ||
                    next.status == CameraCaptureOperationStatus.DISCARDED
            CameraCaptureOperationStatus.PUBLISHED ->
                next.status == CameraCaptureOperationStatus.COMMITTED ||
                    next.status == CameraCaptureOperationStatus.DISCARDED
            CameraCaptureOperationStatus.COMMITTED,
            CameraCaptureOperationStatus.DISCARDED -> false
        }
    }

    private fun sameStableIdentity(
        expected: CameraCaptureOperationRecord,
        actual: CameraCaptureOperationRecord
    ): Boolean = expected.operationId == actual.operationId &&
        expected.processInstanceId == actual.processInstanceId &&
        expected.documentId == actual.documentId &&
        expected.sourceUri == actual.sourceUri &&
        expected.sourceFingerprint == actual.sourceFingerprint &&
        expected.sessionGeneration == actual.sessionGeneration &&
        expected.pageIndex == actual.pageIndex &&
        expected.pinId == actual.pinId &&
        expected.captureFileName == actual.captureFileName &&
        expected.publishedPhotoFileName == actual.publishedPhotoFileName &&
        expected.createdAtMillis == actual.createdAtMillis

    /** Lists only direct children and rejects any camera-prefixed ambiguity. */
    private fun journalFilesLocked(): List<String> {
        val files = operationResolver.root.listFiles()
            ?: throw CameraCaptureOperationIoException("camera operation directory could not be listed")
        if (files.size > MAX_CAMERA_OPERATION_FILES) {
            throw CameraCaptureOperationCorruptException(
                "camera operation directory exceeds its bounded entry limit"
            )
        }
        val names = mutableListOf<String>()
        files.forEach { file ->
            if (!file.name.startsWith(CameraCaptureFilePolicy.OPERATION_FILE_PREFIX)) {
                throw CameraCaptureOperationCorruptException(
                    "camera operation directory contains an unexpected file"
                )
            }
            val name = file.name
            if (!isOperationEventFileName(name) && !isOperationTemporaryFileName(name)) {
                throw CameraCaptureOperationCorruptException(
                    "camera operation directory contains an ambiguous file"
                )
            }
            val path = operationResolver.root.resolve(name)
            operationResolver.ensureContained(path.toPath(), "camera operation journal")
            if (!operationResolver.isRegularFile(path.toPath())) {
                throw CameraCaptureOperationCorruptException(
                    "camera operation journal entry is not a regular file"
                )
            }
            names += name
        }
        return names.sorted()
    }

    private fun requireSameStableIdentity(
        expected: CameraCaptureOperationRecord,
        actual: CameraCaptureOperationRecord
    ) {
        if (expected.operationId != actual.operationId ||
            expected.processInstanceId != actual.processInstanceId ||
            expected.documentId != actual.documentId ||
            expected.sourceUri != actual.sourceUri ||
            expected.sourceFingerprint != actual.sourceFingerprint ||
            expected.sessionGeneration != actual.sessionGeneration ||
            expected.pageIndex != actual.pageIndex ||
            expected.pinId != actual.pinId ||
            expected.captureFileName != actual.captureFileName ||
            expected.publishedPhotoFileName != actual.publishedPhotoFileName ||
            expected.createdAtMillis != actual.createdAtMillis
        ) {
            throw CameraCaptureOperationCorruptException(
                "camera journal revisions disagree on stable operation identity"
            )
        }
    }

    private fun parseRecord(bytes: ByteArray, fileName: String): CameraCaptureOperationRecord {
        val root = try {
            parseBoundedJsonObject(
                ByteArrayInputStream(bytes),
                MAX_CAMERA_OPERATION_RECORD_BYTES,
                "camera operation journal $fileName"
            )
        } catch (error: Throwable) {
            if (error is CameraCaptureOperationException) throw error
            throw CameraCaptureOperationCorruptException(
                "camera operation journal JSON is invalid",
                error
            )
        }
        val allowed = setOf(
            "schemaVersion",
            "revision",
            "operationId",
            "processInstanceId",
            "documentId",
            "sourceUri",
            "sourceFingerprint",
            "sessionGeneration",
            "pageIndex",
            "pinId",
            "captureFileName",
            "publishedPhotoFileName",
            "status",
            "result",
            "createdAtMillis",
            "updatedAtMillis"
        )
        root.keySet().firstOrNull { it !in allowed }?.let {
            throw CameraCaptureOperationCorruptException(
                "camera operation journal contains unsupported field: $it"
            )
        }
        allowed.forEach { key ->
            if (!root.has(key)) {
                throw CameraCaptureOperationCorruptException(
                    "camera operation journal is missing field: $key"
                )
            }
        }
        try {
            val record = CameraCaptureOperationRecord(
                schemaVersion = requiredInt(root, "schemaVersion"),
                revision = requiredLong(root, "revision"),
                operationId = requiredString(root, "operationId"),
                processInstanceId = requiredString(root, "processInstanceId"),
                documentId = requiredString(root, "documentId"),
                sourceUri = requiredString(root, "sourceUri"),
                sourceFingerprint = optionalFingerprint(root.get("sourceFingerprint")),
                sessionGeneration = requiredLong(root, "sessionGeneration"),
                pageIndex = requiredInt(root, "pageIndex"),
                pinId = requiredString(root, "pinId"),
                captureFileName = requiredString(root, "captureFileName"),
                publishedPhotoFileName = optionalString(root.get("publishedPhotoFileName"), "publishedPhotoFileName"),
                status = requiredEnum(root, "status", CameraCaptureOperationStatus.values()),
                result = optionalEnum(root.get("result"), "result", CameraCaptureResult.values()),
                createdAtMillis = requiredLong(root, "createdAtMillis"),
                updatedAtMillis = requiredLong(root, "updatedAtMillis")
            )
            val expectedName = operationEventFileName(record.operationId, record.revision)
            if (fileName != expectedName) {
                throw CameraCaptureOperationCorruptException(
                    "camera operation journal filename does not match its record"
                )
            }
            return record
        } catch (error: CameraCaptureOperationException) {
            throw error
        } catch (error: Throwable) {
            throw CameraCaptureOperationCorruptException(
                "camera operation journal fields are invalid",
                error
            )
        }
    }
}

/** One process-wide lock protects both exposed capture files and the journal. */
internal object CameraCaptureOperationCriticalSection {
    private val lock = ReentrantLock()

    fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

private fun newResolver(
    filesDirectory: File,
    rootDirectory: File,
    operationsFactory: PhotoPathOperationsFactory?
): PhotoPathResolver = if (operationsFactory == null) {
    PhotoPathResolver(
        rootDirectory = rootDirectory,
        createRoot = true,
        trustedRootDirectory = filesDirectory
    )
} else {
    PhotoPathResolver(
        rootDirectory = rootDirectory,
        createRoot = true,
        operationsFactory = operationsFactory,
        trustedRootDirectory = filesDirectory
    )
}

private const val MAX_CAMERA_OPERATION_RECORD_BYTES: Int = 64 * 1024
private const val MAX_CAMERA_OPERATION_FILES: Int = 64
private const val MAX_CAMERA_OPERATION_REVISIONS: Long = 64L
private const val MAX_CAMERA_TIMESTAMP_MILLIS: Long = 4_102_444_800_000L
private val CAMERA_UUID_PATTERN = Regex(
    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
)
private val CAMERA_OPERATION_EVENT_PATTERN = Regex(
    "\\.camera-operation-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[1-9][0-9]{0,2}\\.json"
)
private val CAMERA_OPERATION_TEMPORARY_PATTERN = Regex(
    "\\.camera-operation-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[1-9][0-9]{0,2}\\.json\\.tmp"
)

internal fun deterministicPublishedPhotoFileName(operationId: String): String =
    "photo-$operationId.jpg"

internal fun requireCameraUuid(value: String, label: String) {
    if (!CAMERA_UUID_PATTERN.matches(value)) {
        throw Stage5ValidationException("$label is not a canonical UUID")
    }
    try {
        require(UUID.fromString(value).toString() == value) {
            "$label is not a canonical UUID"
        }
    } catch (error: IllegalArgumentException) {
        throw Stage5ValidationException("$label is not a canonical UUID", error)
    }
}

internal fun requireCameraPinId(value: String) {
    // Stable pin IDs are opaque canonical metadata, never directory or file names.
    if (value.isBlank() || value.length > Stage5Limits.MAX_ID_CHARS) {
        throw Stage5ValidationException("camera photo pin id is blank or oversized")
    }
}

internal fun requireCameraSourceUri(value: String) {
    require(value.isNotBlank() && value.length <= Stage5Limits.MAX_STRING_CHARS) {
        "camera source URI is blank or oversized"
    }
    require(value.none { it.code < 0x20 || it.code == 0x7F }) {
        "camera source URI contains a control character"
    }
}

internal fun requireCameraTimestamp(value: Long, label: String) {
    require(value in 0L..MAX_CAMERA_TIMESTAMP_MILLIS) {
        "$label is outside its bounded range"
    }
}

private fun monotonicTimestamp(
    current: CameraCaptureOperationRecord,
    requested: Long
): Long = max(current.updatedAtMillis, requested)

private fun operationEventFileName(operationId: String, revision: Long): String {
    requireCameraUuid(operationId, "camera operation id")
    require(revision in 1L..MAX_CAMERA_OPERATION_REVISIONS) {
        "camera operation revision is outside its bounded range"
    }
    return CameraCaptureFilePolicy.OPERATION_FILE_PREFIX + operationId + "-" + revision + ".json"
}

private fun operationTemporaryFileName(operationId: String, revision: Long): String =
    operationEventFileName(operationId, revision) + ".tmp"

private fun isOperationEventFileName(name: String): Boolean =
    CAMERA_OPERATION_EVENT_PATTERN.matches(name)

private fun isOperationTemporaryFileName(name: String): Boolean =
    CAMERA_OPERATION_TEMPORARY_PATTERN.matches(name)

private fun requiredString(root: JsonObject, name: String): String {
    val element = root.get(name)
    if (element == null || element.isJsonNull || !element.isJsonPrimitive ||
        !element.asJsonPrimitive.isString
    ) {
        throw Stage5ValidationException("camera operation field $name must be a string")
    }
    return element.asString
}

private fun optionalString(element: JsonElement?, name: String): String? {
    if (element == null || element.isJsonNull) return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
        throw Stage5ValidationException("camera operation field $name must be a string or null")
    }
    return element.asString
}

private fun requiredLong(root: JsonObject, name: String): Long {
    val element = root.get(name)
    if (element == null || element.isJsonNull || !element.isJsonPrimitive ||
        !element.asJsonPrimitive.isNumber
    ) {
        throw Stage5ValidationException("camera operation field $name must be an integer")
    }
    val text = element.asString
    if (!text.matches(Regex("-?(0|[1-9][0-9]*)"))) {
        throw Stage5ValidationException("camera operation field $name must be an integer")
    }
    return text.toLongOrNull()
        ?: throw Stage5ValidationException("camera operation field $name is outside Long range")
}

private fun requiredInt(root: JsonObject, name: String): Int {
    val value = requiredLong(root, name)
    if (value < Int.MIN_VALUE.toLong() || value > Int.MAX_VALUE.toLong()) {
        throw Stage5ValidationException("camera operation field $name is outside Int range")
    }
    return value.toInt()
}

private fun <T : Enum<T>> requiredEnum(
    root: JsonObject,
    name: String,
    values: Array<T>
): T = parseEnum(requiredString(root, name), name, values)

private fun <T : Enum<T>> optionalEnum(
    element: JsonElement?,
    name: String,
    values: Array<T>
): T? = optionalString(element, name)?.let { parseEnum(it, name, values) }

private fun <T : Enum<T>> parseEnum(value: String, name: String, values: Array<T>): T =
    values.firstOrNull { it.name == value }
        ?: throw Stage5ValidationException("camera operation field $name has an unsupported value")

private fun optionalFingerprint(element: JsonElement?): SourceFingerprint? {
    if (element == null || element.isJsonNull) return null
    if (!element.isJsonObject) {
        throw Stage5ValidationException("camera source fingerprint must be an object or null")
    }
    val objectValue = element.asJsonObject
    val allowed = setOf("algorithm", "digestHex", "byteCount")
    objectValue.keySet().firstOrNull { it !in allowed }?.let {
        throw Stage5ValidationException("camera source fingerprint has an unsupported field")
    }
    allowed.forEach { key ->
        if (!objectValue.has(key)) {
            throw Stage5ValidationException("camera source fingerprint is missing a field")
        }
    }
    return SourceFingerprint(
        algorithm = requiredString(objectValue, "algorithm"),
        digestHex = requiredString(objectValue, "digestHex"),
        byteCount = requiredLong(objectValue, "byteCount")
    )
}
