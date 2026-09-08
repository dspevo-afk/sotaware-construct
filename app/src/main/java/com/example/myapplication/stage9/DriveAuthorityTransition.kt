package com.example.myapplication.stage9

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Fence existing bindings, remove their live authority before any suspension,
 * and drain old work before the caller can install a newly authorized service.
 */
internal suspend fun <T> changeDriveAuthority(
    invalidateSync: () -> Unit,
    changeAuthority: () -> T,
    joinPreviousWork: suspend () -> Unit
): T {
    invalidateSync()
    val result = changeAuthority()
    withContext(NonCancellable) { joinPreviousWork() }
    currentCoroutineContext().ensureActive()
    return result
}
