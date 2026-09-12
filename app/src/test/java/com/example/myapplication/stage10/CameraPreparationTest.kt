package com.example.myapplication.stage10

import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage5.*
import com.example.myapplication.stage7.Stage7WorkerResourceBoundary
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CameraPreparationTest {
    @Test fun slowStorageDoesNotBlockTheCallingThread() = runBlocking {
        val workerThread = AtomicReference<Thread>()
        val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "camera-preparation-worker").also(workerThread::set) }.asCoroutineDispatcher()
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val remembered = AtomicReference<String?>()
        try {
            val request = request()
            val result = async {
                prepareCameraOperationOnWorker(request, Stage7WorkerResourceBoundary(dispatcher, Dispatchers.Unconfined), remembered::set) {
                    assertSame(workerThread.get(), Thread.currentThread())
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS)) { "calling thread could not run" }
                    record(it)
                }
            }
            withTimeout(5_000) { entered.await() }
            assertFalse(result.isCompleted)
            assertTrue(async { Thread.currentThread() !== workerThread.get() }.await())
            release.countDown()
            assertEquals(result.await().operationId, remembered.get())
        } finally { release.countDown(); dispatcher.close() }
    }

    @Test fun cancellationAtWorkerHandoffRetainsThePreparedOperationId() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val remembered = AtomicReference<String?>()
        val boundary = Stage7WorkerResourceBoundary(dispatcher, Dispatchers.Unconfined,
            beforeWorkerHandoff = { it?.cancel(CancellationException("handoff cancelled")) })
        try {
            val task = async {
                prepareCameraOperationOnWorker(request(), boundary, remembered::set, ::record)
            }
            try { task.await(); fail("cancelled preparation returned") }
            catch (_: CancellationException) { }
            assertNotNull("completed journal identity was lost at handoff", remembered.get())
        } finally { dispatcher.close() }
    }

    @Test fun failedPreparationNeverClaimsAnOperation() = runBlocking {
        var remembered: String? = null
        try {
            prepareCameraOperationOnWorker(request(), Stage7WorkerResourceBoundary(Dispatchers.Unconfined, Dispatchers.Unconfined),
                { remembered = it }) { throw IOException("storage failed") }
            fail("failed preparation returned")
        } catch (_: IOException) { }
        assertNull(remembered)
    }

    private fun request() = CameraCaptureOperationRequest(
        UUID.randomUUID().toString(), DocumentId.new(), "content://synthetic/camera.pdf",
        SourceFingerprint.fromBytes(byteArrayOf(1)), 1L, 0, UUID.randomUUID().toString()
    )
    private fun record(request: CameraCaptureOperationRequest): CameraCaptureOperationRecord {
        val id = UUID.randomUUID().toString()
        return CameraCaptureOperationRecord(
            schemaVersion = 1, revision = 1, operationId = id,
            processInstanceId = request.processInstanceId, documentId = request.documentId.value,
            sourceUri = request.sourceUri, sourceFingerprint = request.sourceFingerprint,
            sessionGeneration = request.sessionGeneration, pageIndex = request.pageIndex, pinId = request.pinId,
            captureFileName = ".camera-capture-$id.tmp", publishedPhotoFileName = null,
            status = CameraCaptureOperationStatus.PREPARED, result = null,
            createdAtMillis = request.createdAtMillis, updatedAtMillis = request.createdAtMillis
        )
    }
}
