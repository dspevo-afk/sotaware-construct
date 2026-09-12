package com.example.myapplication.stage5

import com.example.myapplication.stage7.Stage7WorkerResourceBoundary

/**
 * Immutable request enters on Main; blocking storage work stays on the worker.
 * Record ownership before the cancellable worker-to-Main handoff so a completed
 * PREPARED journal can still be retired safely if launch admission is cancelled.
 */
internal suspend fun prepareCameraOperationOnWorker(
    request: CameraCaptureOperationRequest,
    worker: Stage7WorkerResourceBoundary,
    rememberPrepared: (String) -> Unit,
    prepare: (CameraCaptureOperationRequest) -> CameraCaptureOperationRecord
): CameraCaptureOperationRecord = worker.withWorker {
    prepare(request).also { rememberPrepared(it.operationId) }
}

/**
 * Runs restart reconciliation and the guarded orphan sweep on the same worker
 * boundary as journal preparation. The callback owns the durable store and
 * must not publish a session until this operation has returned.
 */
internal suspend fun reconcileCameraOperationOnWorker(
    worker: Stage7WorkerResourceBoundary,
    reconcile: () -> CameraCaptureMaintenanceResult
): CameraCaptureMaintenanceResult = worker.withWorker {
    reconcile()
}
