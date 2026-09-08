package com.example.myapplication.stage4

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteMutationHandoffTest {
    @Test fun cancellationBeforeResult_remainsPromptAndClosesOwnedLease() = runTest {
        val handoff = RemoteMutationHandoff(StandardTestDispatcher(testScheduler))
        val owned = CountingSession()
        val release = CompletableDeferred<Unit>()
        var delivered = false
        val job = launch {
            handoff.deliver {
                mutationSession = owned
                release.await()
                "completed"
            }
            delivered = true
        }
        runCurrent()
        job.cancel()
        runCurrent()
        try {
            assertTrue("preparation must not become noncancellable", job.isCompleted)
            assertFalse(delivered)
            assertEquals(1, owned.closes.get())
        } finally {
            release.complete(Unit)
            job.join()
        }
    }

    @Test fun cancellationWhileWaiting_doesNotReleaseAnotherRequestsLease() = runTest {
        val lease = ScopeRemoteMutationLease()
        lease.advance(1L)
        val first = requireNotNull(lease.begin(1L) { true })
        val handoff = RemoteMutationHandoff(StandardTestDispatcher(testScheduler))
        val waiting = launch {
            handoff.deliver {
                mutationSession = lease.begin(1L) { true }
                "unexpected"
            }
        }
        try {
            runCurrent()
            waiting.cancelAndJoin()
            assertNull(handoff.mutationSession)
            assertNull(withTimeoutOrNull(100) { lease.advance(2L); true })
        } finally {
            first.close()
            waiting.cancelAndJoin()
        }
        withTimeout(100) { lease.advance(2L) }
    }

    @Test fun unexpectedFailureBeforeResult_closesOwnedLeaseExactlyOnce() = runTest {
        val owned = CountingSession()
        val expected = AssertionError("synthetic failure before result")
        val handoff = RemoteMutationHandoff(StandardTestDispatcher(testScheduler))
        var actual: Throwable? = null
        try {
            handoff.deliver<String> { mutationSession = owned; throw expected }
        } catch (error: Throwable) {
            actual = error
        }
        // Coroutine stacktrace recovery may copy the failure with the original
        // as its cause. Preserve the original failure through that chain.
        assertTrue(actual is AssertionError)
        assertEquals(expected.message, actual?.message)
        assertTrue(generateSequence(actual) { it.cause }.any { it === expected })
        assertEquals(1, owned.closes.get())
    }

    @Test fun completedResult_transfersLeaseToCallerWithoutClosingItEarly() = runTest {
        val owned = CountingSession()
        val handoff = RemoteMutationHandoff(StandardTestDispatcher(testScheduler))
        val result = handoff.deliver { mutationSession = owned; owned }
        assertSame(owned, result)
        assertEquals(0, owned.closes.get())
        result.close()
        assertEquals(1, owned.closes.get())
    }

    private class CountingSession : RemoteMutationSession {
        val closes = AtomicInteger(0)
        override suspend fun <T> mutate(block: suspend () -> T): T = block()
        override fun close() { closes.incrementAndGet() }
    }
}
