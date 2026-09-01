package com.example.myapplication.stage7

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Adapter around an asynchronous OCR task. [await] is the normal cancellable
 * handoff; [awaitTerminal] must not return or throw until the underlying task
 * has reached its terminal state.
 */
interface OcrRecognitionTask<T> {
    suspend fun await(): T

    suspend fun awaitTerminal(): T
}

/**
 * Adapter for Google Play Services Tasks used by ML Kit. ML Kit does not
 * receive a CancellationToken here, so cancellation of the coroutine only
 * cancels its await continuation; [awaitTerminal] explicitly joins the same
 * task before its bitmap or recognizer owners may be released.
 */
fun <T> googleMlKitRecognitionTask(task: Task<T>): OcrRecognitionTask<T> =
    GoogleMlKitRecognitionTask(task)

private class GoogleMlKitRecognitionTask<T>(
    private val task: Task<T>
) : OcrRecognitionTask<T> {
    override suspend fun await(): T = task.await()

    override suspend fun awaitTerminal(): T = withContext(NonCancellable) {
        task.await()
    }
}

/**
 * Awaits the underlying task after caller cancellation before any bitmap or
 * recognizer owner can be released. The caller's CancellationException stays
 * primary even when the terminal wait itself fails.
 */
suspend fun <T> awaitOcrRecognitionTask(task: OcrRecognitionTask<T>): T {
    try {
        return task.await()
    } catch (cancelled: CancellationException) {
        try {
            withContext(NonCancellable) {
                task.awaitTerminal()
            }
        } catch (waitFailure: Throwable) {
            if (waitFailure !== cancelled) cancelled.addSuppressed(waitFailure)
        }
        throw cancelled
    }
}

/**
 * Runs one task and then releases its transient owners. Cleanup is synchronous
 * and therefore happens after task terminal completion even when the caller
 * was canceled. Cleanup failures never replace an existing task failure.
 */
suspend fun <T> runOcrRecognitionTask(
    task: OcrRecognitionTask<T>,
    closeTransientOwners: () -> Unit
): T {
    var primaryFailure: Throwable? = null
    try {
        return awaitOcrRecognitionTask(task)
    } catch (cancelled: CancellationException) {
        primaryFailure = cancelled
        throw cancelled
    } catch (error: Throwable) {
        primaryFailure = error
        throw error
    } finally {
        try {
            closeTransientOwners()
        } catch (closeFailure: Throwable) {
            if (primaryFailure != null) {
                if (closeFailure !== primaryFailure &&
                    primaryFailure?.suppressed?.none { it === closeFailure } == true
                ) {
                    primaryFailure?.addSuppressed(closeFailure)
                }
            } else {
                throw closeFailure
            }
        }
    }
}
