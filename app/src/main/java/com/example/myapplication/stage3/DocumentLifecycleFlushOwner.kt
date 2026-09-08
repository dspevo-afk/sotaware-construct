package com.example.myapplication.stage3

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/** Owns local lifecycle durability until teardown has joined it; never owns network work. */
internal class DocumentLifecycleFlushOwner(
    private val scope: CoroutineScope,
    private val flush: suspend () -> Unit,
    private val onFailure: (Throwable) -> Unit
) {
    private val lock = Any()
    private var requested = false
    private var closed = false
    private var worker: Job? = null

    fun request() {
        val start = synchronized(lock) {
            if (closed) return
            requested = true
            if (worker != null) return
            scope.launch(NonCancellable, start = CoroutineStart.LAZY) {
                try {
                    while (true) {
                        val next = synchronized(lock) {
                            if (requested) {
                                requested = false
                                true
                            } else {
                                worker = null
                                false
                            }
                        }
                        if (!next) break
                        try {
                            flush()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            onFailure(error)
                        }
                    }
                } finally {
                    synchronized(lock) {
                        // A request can replace a completed worker only after the
                        // worker was cleared while holding this same lock.
                        if (worker === coroutineContext[Job]) worker = null
                    }
                }
            }.also { worker = it }
        }
        start.start()
    }

    /** Stops new admission before joining, so close cannot miss a replacement worker. */
    suspend fun closeAndJoin() {
        val pending = synchronized(lock) {
            closed = true
            worker
        }
        pending?.join()
    }
}
