package com.example.myapplication.stage4

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns a gateway lease until its completed result reaches the coordinator.
 *
 * withContext may discard a completed result when dispatching back to a canceled
 * caller. Keep the result outside that dispatch boundary: an irreversible remote
 * mutation still needs the coordinator's noncancellable local finalization. The
 * caller must release the delivered result's lease in finally, then propagate
 * cancellation. Preparation, lease acquisition and transport remain cancellable.
 */
internal class RemoteMutationHandoff(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    var mutationSession: RemoteMutationSession? = null

    suspend fun <T : Any> deliver(block: suspend RemoteMutationHandoff.() -> T): T {
        var completed: T? = null
        var delivered = false
        try {
            try {
                withContext(dispatcher) {
                    completed = block(this@RemoteMutationHandoff)
                }
            } catch (cancelled: CancellationException) {
                // withContext waits for its block to finish before returning or
                // throwing, so a completed value is no longer being modified.
                // No value means preparation/transport really was interrupted.
                if (completed == null) throw cancelled
            }
            return checkNotNull(completed).also { delivered = true }
        } finally {
            // Includes cancellation before a result exists and unexpected errors.
            // Once delivered, only the caller owns finalization and release.
            if (!delivered) mutationSession?.close()
        }
    }
}
