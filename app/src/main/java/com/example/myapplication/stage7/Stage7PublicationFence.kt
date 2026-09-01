package com.example.myapplication.stage7

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One owner-facing linearization fence for session invalidation and cache
 * publication.
 *
 * The critical section supplied to [withPublication] and [withInvalidation]
 * is deliberately non-suspending. Acquisition is suspendable, so a caller on
 * Main waits without blocking the Main thread. The shared lock order is:
 * publication fence -> cache visibility lock, or publication fence -> the
 * coordinator closed/invalidation state lock. No code may acquire the fence
 * while holding either downstream lock.
 */
class Stage7PublicationFence {
    private val mutex = Mutex()

    suspend fun <T> withPublication(block: () -> T): T = mutex.withLock {
        block()
    }

    suspend fun <T> withInvalidation(block: () -> T): T = mutex.withLock {
        block()
    }

    /** Non-suspending compatibility path for the coordinator's legacy close. */
    fun <T> tryWithInvalidation(block: () -> T): T? {
        if (!mutex.tryLock()) return null
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    /** Non-suspending compatibility path for legacy marker callers. */
    fun <T> tryWithPublication(block: () -> T): T? {
        if (!mutex.tryLock()) return null
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        /** The process-local fence used by the shared Main/OCR cache route. */
        val global: Stage7PublicationFence = Stage7PublicationFence()
    }
}
