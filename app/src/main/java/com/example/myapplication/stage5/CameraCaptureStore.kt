package com.example.myapplication.stage5

import java.io.File

/**
 * Compatibility faÃ§ade for the existing capture-file callers. New camera
 * launches should use [prepareOperation] so the operation is journaled before
 * external work begins.
 */
class CameraCaptureStore internal constructor(
    filesDirectory: File,
    operationsFactory: PhotoPathOperationsFactory?
) : AutoCloseable {
    constructor(filesDirectory: File) : this(filesDirectory, null)

    private val delegate = if (operationsFactory == null) {
        CameraCaptureOperationStore(filesDirectory)
    } else {
        CameraCaptureOperationStore(filesDirectory, operationsFactory)
    }

    fun newCaptureFile(): File = delegate.newCaptureFile()

    fun discardCaptureFile(file: File) {
        require(CameraCaptureFilePolicy.isOwnedCaptureFileName(file.name)) {
            "unsafe camera capture file"
        }
        delegate.discardUnjournaledCaptureFile(file)
    }

    fun prepareOperation(request: CameraCaptureOperationRequest): CameraCaptureOperationRecord =
        delegate.prepare(request)

    fun readOperation(): CameraCaptureOperationRecord? = delegate.readOperation()

    /**
     * Explicit restart recovery. Call this before the ordinary recovery read;
     * the latter intentionally still fails closed while a staged revision is
     * present.
     */
    fun reconcileInterruptedJournal(): CameraCaptureJournalReconciliation =
        delegate.reconcileInterruptedJournal()

    /** Representation-oriented alias for callers naming the staged file. */
    fun reconcileStagedJournal(): CameraCaptureJournalReconciliation =
        delegate.reconcileStagedJournal()

    /**
     * Startup/foreground maintenance entry point. Orphan cleanup is performed
     * only when journal reconciliation resolved all staged evidence.
     */
    fun reconcileInterruptedJournalAndSweep(
        nowMillis: Long = System.currentTimeMillis()
    ): CameraCaptureMaintenanceResult =
        delegate.reconcileInterruptedJournalAndSweep(nowMillis)

    fun recovery(): CameraCaptureRecovery = CameraCaptureRecovery(delegate)

    fun <T> withCaptureInput(operationId: String, action: (java.io.InputStream) -> T): T =
        delegate.withCaptureInput(operationId, action)

    fun captureFile(operationId: String): File = delegate.captureFile(operationId)

    fun markLaunched(
        operationId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): CameraCaptureOperationRecord = delegate.markLaunched(operationId, nowMillis)

    fun recordResult(
        operationId: String,
        success: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): CameraCaptureOperationRecord = delegate.recordResult(operationId, success, nowMillis)

    fun markProcessing(
        operationId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): CameraCaptureOperationRecord = delegate.markProcessing(operationId, nowMillis)

    fun markPublished(
        operationId: String,
        publishedPhotoFileName: String? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): CameraCaptureOperationRecord =
        delegate.markPublished(operationId, publishedPhotoFileName, nowMillis)

    fun markCommitted(
        operationId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): CameraCaptureOperationRecord = delegate.markCommitted(operationId, nowMillis)

    fun discardPrepared(
        operationId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): CameraCaptureOperationRecord = delegate.discardPrepared(operationId, nowMillis)

    fun abandonLaunched(
        operationId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): CameraCaptureOperationRecord = delegate.abandonLaunched(operationId, nowMillis)

    fun markDiscarded(
        operationId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): CameraCaptureOperationRecord = delegate.markDiscarded(operationId, nowMillis)

    fun cleanup(operationId: String): Boolean = delegate.cleanup(operationId)

    fun cleanupOrphanedCaptureFiles(nowMillis: Long = System.currentTimeMillis()): Int =
        delegate.cleanupOrphanedCaptureFiles(nowMillis)

    override fun close() {
        delegate.close()
    }
}

/** Small pure policy seam shared by capture storage and configuration tests. */
object CameraCaptureFilePolicy {
    const val ROOT_DIRECTORY = "camera_captures"
    /** The journal is not exposed through FileProvider. */
    const val OPERATION_DIRECTORY = "camera_operations"
    const val OPERATION_FILE_PREFIX = ".camera-operation-"
    const val FILE_PREFIX = ".camera-capture-"
    const val FILE_EXTENSION = ".tmp"

    fun isOwnedCaptureFileName(name: String): Boolean =
        name.matches(
            Regex("\\.camera-capture-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.tmp")
        )
}
