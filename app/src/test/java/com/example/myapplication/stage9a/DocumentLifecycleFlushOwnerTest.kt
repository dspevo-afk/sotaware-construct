package com.example.myapplication.stage9a

import com.example.myapplication.stage3.DocumentLifecycleFlushOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DocumentLifecycleFlushOwnerTest {
    @Test fun teardownWaitsForLocalFlushAndCoalescesRepeatedRequests() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val owner = DocumentLifecycleFlushOwner(this, {
            calls++
            if (calls == 1) release.await()
        }, { throw AssertionError(it) })
        owner.request()
        runCurrent()
        assertEquals(1, calls)
        owner.request(); owner.request(); owner.request()
        val close = async { owner.closeAndJoin() }
        runCurrent()
        assertFalse(close.isCompleted)
        release.complete(Unit)
        close.await()
        assertEquals(2, calls)
        owner.request(); runCurrent()
        assertEquals(2, calls)
    }

    @Test fun failedFlushIsReportedAndQueuedRetryIsNotDropped() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val failures = mutableListOf<Throwable>()
        val owner = DocumentLifecycleFlushOwner(this, {
            calls++
            if (calls == 1) { release.await(); throw java.io.IOException("fixture failure") }
        }, { failures += it })
        owner.request(); runCurrent()
        owner.request()
        release.complete(Unit)
        owner.closeAndJoin()
        assertEquals(2, calls)
        assertEquals(1, failures.size)
    }
}
