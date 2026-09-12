package com.example.myapplication.stage3

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ViewModel-owned handoff between UI coordinators sharing the same live document.
 * A replacement cannot resolve/load/clear state until its predecessor has flushed
 * and joined. Failed retirement is retained and retried, never treated as success.
 */
internal class DocumentHostHandoff {
    private val transition = Mutex()
    private var nextId = 0L
    private var latestAdmittedId = 0L
    private var active: Owner? = null

    @Synchronized
    fun newOwner(): Owner = Owner(++nextId)

    internal inner class Owner internal constructor(private val id: Long) {
        private val retirement = CompletableDeferred<suspend () -> Unit>()
        private var retired = false

        /** Bind only after composition commits; abandoned compositions own nothing. */
        fun bind(action: suspend () -> Unit) {
            retirement.complete(action)
        }

        suspend fun activate() {
            // A launched switch may start before SideEffect binds its teardown.
            retirement.await()
            transition.withLock {
                check(!retired && id >= latestAdmittedId) { "Document host is retired" }
                if (active === this) return@withLock
                active?.retireLocked()
                active = this
                latestAdmittedId = id
            }
        }

        suspend fun closeAndJoin() = withContext(NonCancellable) {
            transition.withLock {
                retireLocked()
                if (active === this@Owner) active = null
            }
        }

        private suspend fun retireLocked() {
            if (retired) return
            withContext(NonCancellable) { retirement.await().invoke() }
            // On failure, retain both the action and active owner for retry.
            retired = true
        }
    }
}
