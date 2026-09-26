package com.example.myapplication

import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage3.DocumentSession
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentSwitchCoordinator
import com.example.myapplication.stage3.DocumentTransactionBarrier
import com.example.myapplication.stage5.CameraCaptureOperationRecord
import com.example.myapplication.stage5.CameraCaptureOperationRequest
import com.example.myapplication.stage5.CameraCaptureOperationStatus
import com.example.myapplication.stage5.CameraCaptureStableIdentity
import com.example.myapplication.stage5.CameraCaptureStore
import com.example.myapplication.stage5.DocumentPhotoAssetStore
import com.example.myapplication.stage5.prepareCameraOperationOnWorker
import com.example.myapplication.stage7.Stage7WorkerResourceBoundary
import com.example.myapplication.stage9.DiagnosticEvent
import com.example.myapplication.stage9.SafeDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Narrow UI adapter for the camera-session owner. Implementations should read
 * Compose state in each method instead of capturing a one-time state snapshot.
 * This lets the owner keep its mutex and durable workflow outside composition
 * while every fence observes the current page, session and pins.
 */
interface CameraCaptureSessionHost {
    fun activeSessionToken(): DocumentSessionToken?
    fun readySessionToken(): DocumentSessionToken?
    fun selectedPageIndex(): Int
    fun photoPin(pageIndex: Int, pinId: String): PhotoPin?
    fun allPhotoReferences(): Set<String>
    fun canAttachPhoto(pageIndex: Int, pinId: String, fileName: String? = null): Boolean

    /** Performs the canonical annotation-reducer operation and returns changed. */
    fun attachPhoto(pageIndex: Int, pin: PhotoPin, fileName: String): Boolean

    fun recoveryOperationId(): String?
    fun recoveryOperation(): CameraCaptureOperationRecord?
    fun showRecovery(operation: CameraCaptureOperationRecord?, message: String)
    fun clearRecovery()
    fun notifyRecoveryChanged()
    fun showPhotoLimitReached()
    fun showMessage(message: String)
    fun launchCamera(operationId: String)
    fun triggerPhotoSync()
}

/**
 * Serializes camera preparation, result recovery, commit and explicit discard
 * for one document-session coordinator. The operation journal remains the
 * authority; Activity results only wake [drain].
 */
class CameraCaptureSessionOwner(
    private val filesDirectory: File,
    private val worker: Stage7WorkerResourceBoundary,
    private val sessionCoordinator: DocumentSwitchCoordinator,
    private val documentTransactionBarrier: DocumentTransactionBarrier,
    private val scope: CoroutineScope,
    private val hostProvider: () -> CameraCaptureSessionHost,
    private val operationOwnerId: String = UUID.randomUUID().toString(),
    private val operationMutex: Mutex = Mutex()
) {
    private fun host(): CameraCaptureSessionHost = hostProvider()

    private fun promptRecoveryFor(
        operation: CameraCaptureOperationRecord,
        detail: String? = null
    ) {
        val message = when (operation.status) {
            CameraCaptureOperationStatus.PREPARED ->
                "A camera capture was prepared but was not launched. Discard it before starting another capture."
            CameraCaptureOperationStatus.LAUNCHED ->
                "A camera capture is still awaiting its external result. Keep waiting, or abandon it only after confirming that no camera is open."
            CameraCaptureOperationStatus.RESULT_AVAILABLE,
            CameraCaptureOperationStatus.PROCESSING,
            CameraCaptureOperationStatus.PUBLISHED ->
                detail ?: "A captured photo is ready for recovery. Open the original document to finish attaching it."
            CameraCaptureOperationStatus.RESULT_CANCELLED,
            CameraCaptureOperationStatus.COMMITTED,
            CameraCaptureOperationStatus.DISCARDED ->
                detail ?: "The camera capture remains available for safe recovery."
        }
        host().showRecovery(operation, message)
    }

    private suspend fun readOperation(): CameraCaptureOperationRecord? =
        worker.withWorker {
            CameraCaptureStore(filesDirectory).use { store ->
                val maintenance = store.reconcileInterruptedJournalAndSweep()
                if (!maintenance.journal.resolved) {
                    throw com.example.myapplication.stage5.CameraCaptureOperationCorruptException(
                        "Interrupted camera publication remains unresolved"
                    )
                }
                store.readOperation()
            }
        }

    private suspend fun operationOrFallback(
        fallback: CameraCaptureOperationRecord
    ): CameraCaptureOperationRecord = try {
        readOperation() ?: fallback
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // Keep the operation that triggered recovery visible if a second read fails.
        fallback
    }

    /** Cleanup is used only for terminal states; LAUNCHED is never aged out. */
    private suspend fun discardOperationDurably(operationId: String): Boolean =
        worker.withWorker {
            CameraCaptureStore(filesDirectory).use { store ->
                val current = store.readOperation()
                if (current == null || current.operationId != operationId) {
                    false
                } else {
                    when (current.status) {
                        CameraCaptureOperationStatus.PREPARED -> store.discardPrepared(operationId)
                        CameraCaptureOperationStatus.LAUNCHED -> store.abandonLaunched(operationId)
                        CameraCaptureOperationStatus.RESULT_CANCELLED,
                        CameraCaptureOperationStatus.RESULT_AVAILABLE,
                        CameraCaptureOperationStatus.PROCESSING,
                        CameraCaptureOperationStatus.PUBLISHED -> store.markDiscarded(operationId)
                        CameraCaptureOperationStatus.DISCARDED,
                        CameraCaptureOperationStatus.COMMITTED -> current
                    }
                    store.cleanup(operationId)
                    true
                }
            }
        }

    /** Cancellation before dispatch can retire PREPARED, and no later state. */
    private suspend fun discardPreparedIfSafe(operationId: String) {
        withContext(NonCancellable) {
            try {
                worker.withWorker {
                    CameraCaptureStore(filesDirectory).use { store ->
                        val current = store.readOperation()
                        if (current?.operationId == operationId &&
                            current.status == CameraCaptureOperationStatus.PREPARED
                        ) {
                            store.discardPrepared(operationId)
                            store.cleanup(operationId)
                        }
                    }
                }
            } catch (error: Throwable) {
                // Preserve the journal for the explicit recovery prompt on failure.
                SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
            }
        }
    }

    private suspend fun cleanupTerminalOperation(operationId: String) {
        worker.withWorker {
            CameraCaptureStore(filesDirectory).use { store ->
                val current = store.readOperation()
                if (current?.operationId == operationId &&
                    (current.status == CameraCaptureOperationStatus.RESULT_CANCELLED ||
                        current.status == CameraCaptureOperationStatus.DISCARDED ||
                        current.status == CameraCaptureOperationStatus.COMMITTED)
                ) {
                    store.cleanup(operationId)
                }
            }
        }
    }

    private fun currentReadySession(): DocumentSession? {
        val current = sessionCoordinator.currentSession() ?: return null
        val ui = host()
        return current.takeIf {
            ui.activeSessionToken() == it.token &&
                ui.readySessionToken() == it.token &&
                sessionCoordinator.isCurrentApplied(it.token)
        }
    }

    /**
     * Prepare and dispatch a capture for the requested pin. The caller owns
     * ActivityResult registration and provides it through the live host port.
     */
    fun requestCapture(requestPageIndex: Int, requestPinId: String): Job? {
        val initialHost = host()
        val requestedToken = initialHost.activeSessionToken() ?: return null
        if (requestedToken != initialHost.readySessionToken() ||
            !sessionCoordinator.isCurrentApplied(requestedToken) ||
            requestPageIndex != initialHost.selectedPageIndex() ||
            initialHost.photoPin(requestPageIndex, requestPinId) == null
        ) return null
        if (!initialHost.canAttachPhoto(requestPageIndex, requestPinId)) {
            SafeDiagnostics.warn(DiagnosticEvent.LIMIT_REACHED)
            initialHost.showPhotoLimitReached()
            return null
        }

        return scope.launch {
            operationMutex.withLock {
                var operationId: String? = null
                var dispatchConfirmed = false
                try {
                    val existing = readOperation()
                    if (existing != null) {
                        promptRecoveryFor(
                            existing,
                            "Resolve the existing camera capture before starting another one."
                        )
                        return@withLock
                    }
                    when (sessionCoordinator.flushCurrent()) {
                        is DocumentSaveResult.Saved -> Unit
                        is DocumentSaveResult.Failed -> {
                            host().showMessage(
                                "The photo pin could not be saved before opening the camera."
                            )
                            return@withLock
                        }
                        null -> return@withLock
                    }
                    currentCoroutineContext().ensureActive()
                    val prepared = documentTransactionBarrier.withDocument(requestedToken.documentId) {
                        val ui = host()
                        val current = currentReadySession()
                        val pinStillPresent = ui.photoPin(requestPageIndex, requestPinId) != null
                        val pinHasCapacity = ui.canAttachPhoto(requestPageIndex, requestPinId)
                        if (current?.token != requestedToken ||
                            ui.selectedPageIndex() != requestPageIndex ||
                            !pinStillPresent ||
                            !pinHasCapacity
                        ) {
                            null
                        } else {
                            val request = CameraCaptureOperationRequest(
                                processInstanceId = operationOwnerId,
                                documentId = requestedToken.documentId,
                                sourceUri = requestedToken.sourceUri,
                                sourceFingerprint = requestedToken.sourceFingerprint,
                                sessionGeneration = requestedToken.generation,
                                pageIndex = requestPageIndex,
                                pinId = requestPinId
                            )
                            prepareCameraOperationOnWorker(
                                request,
                                worker,
                                rememberPrepared = { operationId = it }
                            ) { frozenRequest ->
                                CameraCaptureStore(filesDirectory).use { store ->
                                    store.prepareOperation(frozenRequest)
                                }
                            }
                        }
                    }
                    if (prepared == null) return@withLock
                    operationId = prepared.operationId
                    currentCoroutineContext().ensureActive()
                    val stillLaunchable = documentTransactionBarrier.withDocument(requestedToken.documentId) {
                        val ui = host()
                        val current = currentReadySession()
                        current?.token == requestedToken &&
                            ui.selectedPageIndex() == requestPageIndex &&
                            ui.photoPin(requestPageIndex, requestPinId) != null &&
                            ui.canAttachPhoto(requestPageIndex, requestPinId)
                    }
                    if (!stillLaunchable) {
                        discardPreparedIfSafe(operationId!!)
                        return@withLock
                    }
                    currentCoroutineContext().ensureActive()
                    host().launchCamera(operationId!!)
                    dispatchConfirmed = true
                } catch (cancelled: CancellationException) {
                    if (!dispatchConfirmed) operationId?.let { discardPreparedIfSafe(it) }
                    throw cancelled
                } catch (error: Throwable) {
                    if (!dispatchConfirmed) operationId?.let { discardPreparedIfSafe(it) }
                    SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
                    host().showMessage(
                        "The camera could not be opened. The capture remains available for recovery if it was dispatched."
                    )
                }
            }
        }
    }

    /** Reads and drains only the journal operation matching the ready source. */
    suspend fun drain(returnedOperationId: String? = null) {
        operationMutex.withLock {
            val operation = try {
                readOperation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
                host().showRecovery(
                    null,
                    "Camera recovery evidence could not be verified. No new capture will be started until it is repaired or removed safely."
                )
                return@withLock
            }
            if (operation == null) {
                if (host().recoveryOperationId() != null) host().clearRecovery()
                return@withLock
            }
            if (returnedOperationId != null && returnedOperationId != operation.operationId) {
                promptRecoveryFor(
                    operation,
                    "A stale camera result was returned. The durable operation remains protected for recovery."
                )
                return@withLock
            }
            when (operation.status) {
                CameraCaptureOperationStatus.COMMITTED,
                CameraCaptureOperationStatus.DISCARDED -> {
                    try {
                        cleanupTerminalOperation(operation.operationId)
                        host().clearRecovery()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
                        promptRecoveryFor(
                            operation,
                            "A completed camera operation is retained until its cleanup can be retried safely."
                        )
                    }
                    return@withLock
                }
                CameraCaptureOperationStatus.RESULT_CANCELLED -> {
                    try {
                        cleanupTerminalOperation(operation.operationId)
                        host().clearRecovery()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
                        promptRecoveryFor(
                            operation,
                            "The cancelled camera operation is retained until its cleanup can be retried safely."
                        )
                    }
                    return@withLock
                }
                CameraCaptureOperationStatus.PREPARED,
                CameraCaptureOperationStatus.LAUNCHED -> {
                    promptRecoveryFor(operation)
                    return@withLock
                }
                CameraCaptureOperationStatus.RESULT_AVAILABLE,
                CameraCaptureOperationStatus.PROCESSING,
                CameraCaptureOperationStatus.PUBLISHED -> Unit
            }

            val session = currentReadySession()
            if (session == null) {
                promptRecoveryFor(
                    operation,
                    "A camera result is retained. Open the original document and wait for it to finish loading before recovery."
                )
                return@withLock
            }
            val identity = CameraCaptureStableIdentity(
                documentId = session.token.documentId,
                sourceUri = session.token.sourceUri,
                sourceFingerprint = session.token.sourceFingerprint
            )
            val binding = try {
                worker.withWorker {
                    CameraCaptureStore(filesDirectory).use { store ->
                        val recovery = store.recovery()
                        recovery.canRebind(operation, identity) to recovery.canApplyToSession(
                            operation = operation,
                            identity = identity,
                            ownerInstanceId = operationOwnerId,
                            sessionGeneration = session.token.generation
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
                false to false
            }
            if (!binding.first) {
                promptRecoveryFor(
                    operation,
                    "A camera result belongs to a different verified document source. It will not be attached here; open the original document to recover it or explicitly discard it."
                )
                return@withLock
            }
            if (!binding.second) {
                promptRecoveryFor(
                    operation,
                    "This camera result belongs to an earlier session generation. It was not attached; reopen the original document after a process restart to recover it, or explicitly discard it."
                )
                return@withLock
            }
            processOperation(operation, session)
        }
    }

    /**
     * Publishes, attaches and commits a captured photo. The publication and
     * reducer action share the document barrier; flush is outside it because
     * the canonical save path reacquires that same barrier.
     */
    private suspend fun processOperation(
        operation: CameraCaptureOperationRecord,
        session: DocumentSession
    ) {
        val operationId = operation.operationId
        val documentId = session.token.documentId

        val sourceValidated = try {
            sessionCoordinator.flushCurrent() is DocumentSaveResult.Saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
            false
        }
        if (!sourceValidated) {
            promptRecoveryFor(
                operation,
                "The document source could not be verified for this camera result. The capture remains retained for recovery."
            )
            return
        }
        currentCoroutineContext().ensureActive()

        val attachedOrPresent = try {
            documentTransactionBarrier.withDocument(documentId) {
                val ui = host()
                val current = currentReadySession()
                if (current?.token != session.token) {
                    false
                } else if (ui.selectedPageIndex() != operation.pageIndex) {
                    promptRecoveryFor(
                        operation,
                        "A captured photo is ready for page ${operation.pageIndex + 1}. Select that page to finish recovery."
                    )
                    false
                } else {
                    val initialPin = ui.photoPin(operation.pageIndex, operation.pinId)
                    if (initialPin == null) {
                        promptRecoveryFor(
                            operation,
                            "The original photo pin is no longer present. Open its document to restore it, or explicitly discard this capture."
                        )
                        false
                    } else {
                        val loadedOperation = worker.withWorker {
                            CameraCaptureStore(filesDirectory).use { store -> store.readOperation() }
                        }
                        if (loadedOperation == null || loadedOperation.operationId != operationId) {
                            false
                        } else {
                            val capacityReference = requireNotNull(loadedOperation.publishedPhotoFileName) {
                                "camera operation has no deterministic publication name"
                            }
                            if (!initialPin.imageFileNames.contains(capacityReference) &&
                                !host().canAttachPhoto(
                                    operation.pageIndex,
                                    operation.pinId,
                                    capacityReference
                                )
                            ) {
                                SafeDiagnostics.warn(DiagnosticEvent.LIMIT_REACHED)
                                throw com.example.myapplication.stage5.Stage5ValidationException(
                                    "camera photo capacity limit reached before publication"
                                )
                            }
                            var currentOperation = loadedOperation
                            if (currentOperation.status == CameraCaptureOperationStatus.RESULT_AVAILABLE) {
                                currentOperation = worker.withWorker {
                                    CameraCaptureStore(filesDirectory).use { store ->
                                        store.markProcessing(operationId)
                                    }
                                }
                            }
                            if (currentOperation.status != CameraCaptureOperationStatus.PROCESSING &&
                                currentOperation.status != CameraCaptureOperationStatus.PUBLISHED
                            ) {
                                throw IllegalStateException(
                                    "camera operation cannot be processed from ${currentOperation.status}"
                                )
                            }
                            val reservedReference = requireNotNull(currentOperation.publishedPhotoFileName) {
                                "camera operation has no deterministic publication name"
                            }
                            val existingPhotoReferences = host().allPhotoReferences()

                            // Re-read through the operation store to verify owned capture bytes.
                            worker.withWorker {
                                CameraCaptureStore(filesDirectory).use { captureStore ->
                                    captureStore.withCaptureInput(operationId) { input ->
                                        DocumentPhotoAssetStore(filesDirectory, documentId).use { store ->
                                            store.publishReservedPhoto(
                                                input = input,
                                                reservedPhotoFileName = reservedReference,
                                                extension = ".jpg",
                                                existingPhotoReferences = existingPhotoReferences
                                            )
                                        }
                                    }
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            if (currentOperation.status == CameraCaptureOperationStatus.PROCESSING) {
                                // Persist publication before reducer mutation.
                                currentOperation = worker.withWorker {
                                    CameraCaptureStore(filesDirectory).use { store ->
                                        store.markPublished(operationId, reservedReference)
                                    }
                                }
                            }
                            val livePin = host().photoPin(operation.pageIndex, operation.pinId)
                            if (livePin == null) {
                                promptRecoveryFor(
                                    currentOperation,
                                    "The original photo pin was deleted while the camera result was being recovered. The capture is retained for explicit recovery or discard."
                                )
                                false
                            } else if (livePin.imageFileNames.contains(reservedReference)) {
                                // Exact-reference retry after attach-before-commit is idempotent.
                                true
                            } else if (host().attachPhoto(
                                    operation.pageIndex,
                                    livePin,
                                    reservedReference
                                )
                            ) {
                                true
                            } else {
                                val afterFailure = host().photoPin(operation.pageIndex, operation.pinId)
                                if (afterFailure?.imageFileNames?.contains(reservedReference) == true) {
                                    true
                                } else {
                                    throw IllegalStateException(
                                        "camera photo pin could not be updated through the annotation reducer"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
            val latest = try {
                readOperation()
            } catch (_: Throwable) {
                operation
            }
            promptRecoveryFor(
                latest ?: operation,
                "The captured photo could not be attached yet. It remains retained for recovery."
            )
            return
        }
        if (!attachedOrPresent) return

        val flushed = try {
            // flushCurrent reacquires the document barrier internally.
            sessionCoordinator.flushCurrent()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
            null
        }
        if (flushed !is DocumentSaveResult.Saved) {
            promptRecoveryFor(
                operationOrFallback(operation),
                "The photo was attached in memory, but its canonical document save did not finish. The capture is retained; retry recovery when the document is ready."
            )
            return
        }

        val committed = try {
            withContext(NonCancellable) {
                documentTransactionBarrier.withDocument(documentId) {
                    val current = currentReadySession()
                    val ui = host()
                    if (current?.token != session.token ||
                        ui.selectedPageIndex() != operation.pageIndex
                    ) {
                        false
                    } else {
                        val livePin = ui.photoPin(operation.pageIndex, operation.pinId)
                        val currentOperation = worker.withWorker {
                            CameraCaptureStore(filesDirectory).use { store -> store.readOperation() }
                        }
                        val reservedReference = currentOperation?.publishedPhotoFileName
                        if (currentOperation == null ||
                            livePin == null || reservedReference.isNullOrBlank() ||
                            !livePin.imageFileNames.contains(reservedReference) ||
                            currentOperation.operationId != operationId
                        ) {
                            false
                        } else {
                            if (currentOperation.status == CameraCaptureOperationStatus.PUBLISHED) {
                                worker.withWorker {
                                    CameraCaptureStore(filesDirectory).use { store ->
                                        store.markCommitted(operationId)
                                        store.cleanup(operationId)
                                    }
                                }
                            } else if (currentOperation.status != CameraCaptureOperationStatus.COMMITTED) {
                                throw IllegalStateException(
                                    "camera operation is not publication-committed after canonical save"
                                )
                            } else {
                                cleanupTerminalOperation(operationId)
                            }
                            // The canonical reference is durable; release its temporary reservation.
                            worker.withWorker {
                                DocumentPhotoAssetStore(filesDirectory, documentId).use { store ->
                                    store.releasePhotoPublication(reservedReference)
                                }
                            }
                            true
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
            false
        }
        if (committed) {
            host().clearRecovery()
            // Photo sync retains the existing admission rule for the same live session.
            if (currentReadySession()?.token == session.token) host().triggerPhotoSync()
        } else {
            promptRecoveryFor(
                operationOrFallback(operation),
                "The document changed before the camera commit completed. Reopen the original document to finish recovery."
            )
        }
    }

    /** Explicit recovery discard; the UI must gate LAUNCHED behind confirmation. */
    fun requestRecoveryDiscard(operationId: String): Job = scope.launch {
        operationMutex.withLock {
            try {
                if (discardOperationDurably(operationId) &&
                    host().recoveryOperationId() == operationId
                ) {
                    host().clearRecovery()
                }
                host().notifyRecoveryChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                SafeDiagnostics.error(DiagnosticEvent.ANNOTATION_ACTIVITY, error = error)
                host().showRecovery(
                    host().recoveryOperation(),
                    "The camera capture could not be discarded safely. It remains protected for recovery."
                )
            }
        }
    }

}
