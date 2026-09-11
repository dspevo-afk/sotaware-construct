package com.example.myapplication.stage9b

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PhotoReleaseHandleTest {
    @Test fun captureRetriesFailedAction() = retries(retained = false)
    @Test fun leaseRetriesFailedAction() = retries(retained = true)
    @Test fun captureSerializesConcurrentRetry() = concurrentRetry(retained = false)
    @Test fun leaseSerializesConcurrentRetry() = concurrentRetry(retained = true)

    private fun retries(retained: Boolean) {
        var calls = 0
        val handle = handle(retained) { if (++calls == 1) throw IOException("release failed") }
        try { handle.close(); fail("first release must fail") } catch (_: IOException) { }
        assertFalse("failure is not completed release", released(handle))
        handle.close()
        assertTrue(released(handle))
        repeat(3) { handle.close() }
        assertEquals("one failure and exactly one successful action", 2, calls)
    }

    private fun concurrentRetry(retained: Boolean) {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val calls = AtomicInteger()
        val handle = handle(retained) {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(unblock.await(5, TimeUnit.SECONDS))
                throw IOException("first owner failed")
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { handle.close() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = executor.submit {
                secondStarted.countDown()
                try { handle.close() } finally { secondFinished.countDown() }
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse("in-flight release is not complete", released(handle))
            assertFalse("another close must await the outcome", secondFinished.await(100, TimeUnit.MILLISECONDS))
            unblock.countDown()
            try { first.get(5, TimeUnit.SECONDS); fail("first attempt must report failure") }
            catch (error: ExecutionException) { assertTrue(error.cause is IOException) }
            second.get(5, TimeUnit.SECONDS)
            assertTrue(released(handle))
            assertEquals(2, calls.get())
            handle.close()
            assertEquals(2, calls.get())
        } finally {
            unblock.countDown()
            executor.shutdown()
            assertTrue("all test threads must finish", executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun handle(retained: Boolean, action: () -> Unit): AutoCloseable =
        if (retained) PhotoAssetLease("synthetic-release", emptySet(), action)
        else PhotoAssetCapture.of(PhotoAssetSet.EMPTY, action)
    private fun released(handle: AutoCloseable): Boolean = when (handle) {
        is PhotoAssetCapture -> handle.isReleased
        is PhotoAssetLease -> handle.isReleased
        else -> error("unknown test handle")
    }
}
