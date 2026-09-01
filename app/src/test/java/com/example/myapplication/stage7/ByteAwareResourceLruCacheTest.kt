package com.example.myapplication.stage7

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteAwareResourceLruCacheTest {
    private class FakeResource(
        val name: String,
        val theoreticalBytes: Long,
        val actualBytes: Long
    )

    private fun key(namespace: String, value: String): Stage7CacheKey<String> =
        Stage7CacheKey(namespace, value)

    @Test
    fun admissionAccountsProvidedActualBytesIncludingPadding() {
        val released = mutableListOf<FakeResource>()
        val cache = ByteAwareResourceLruCache<String, FakeResource>(64L, released::add)
        val resource = FakeResource("padded", theoreticalBytes = 16L, actualBytes = 24L)
        var transferred = 0

        assertEquals(
            ByteAwareCachePutResult.ACCEPTED,
            cache.putOwned(key("document-a", "page-1"), resource, resource.actualBytes) {
                transferred++
            }
        )
        assertEquals(1, transferred)
        assertEquals(24L, cache.stats().totalBytes)
        assertEquals(24L, cache.activeEntries().single().bytes)
        assertTrue(resource.actualBytes > resource.theoreticalBytes)

        cache.clear()
        assertEquals(listOf(resource), released)
    }

    @Test
    fun nonPositiveAndLongOverflowInputsRejectWithoutTransferOrAccountingOverflow() {
        val released = mutableListOf<FakeResource>()
        val cache = ByteAwareResourceLruCache<String, FakeResource>(Long.MAX_VALUE, released::add)
        val invalid = FakeResource("invalid", 4L, 4L)
        val maximum = FakeResource("maximum", Long.MAX_VALUE, Long.MAX_VALUE)
        val overflow = FakeResource("overflow", 1L, 1L)
        var transfers = 0

        assertEquals(
            ByteAwareCachePutResult.REJECTED_INVALID_BYTES,
            cache.putOwned(key("document", "invalid"), invalid, 0L) { transfers++ }
        )
        assertEquals(
            ByteAwareCachePutResult.ACCEPTED,
            cache.putOwned(key("document", "maximum"), maximum, Long.MAX_VALUE) { transfers++ }
        )
        val maximumLease = checkNotNull(cache.acquire(key("document", "maximum")))
        assertEquals(
            ByteAwareCachePutResult.REJECTED_BUDGET,
            cache.putOwned(key("document", "overflow"), overflow, 1L) { transfers++ }
        )

        assertEquals(1, transfers)
        assertEquals(Long.MAX_VALUE, cache.stats().totalBytes)
        assertEquals(0, released.size)

        // The producer remains responsible for rejected values; the admitted
        // value is released by the cache exactly once.
        cache.clear()
        maximumLease.close()
        assertEquals(listOf(maximum), released)
    }

    @Test
    fun configurableBudgetRejectsOversizedEntriesAndEmitsObservableChanges() {
        val released = mutableListOf<FakeResource>()
        var changes = 0
        val cache = ByteAwareResourceLruCache<String, FakeResource>(10L, released::add) {
            changes++
        }
        val oversized = FakeResource("oversized", 11L, 11L)
        val first = FakeResource("first", 6L, 6L)
        val second = FakeResource("second", 6L, 6L)
        var transfers = 0

        assertEquals(
            ByteAwareCachePutResult.REJECTED_OVERSIZED,
            cache.putOwned(key("document", "oversized"), oversized, oversized.actualBytes) {
                transfers++
            }
        )
        assertEquals(
            ByteAwareCachePutResult.ACCEPTED,
            cache.putOwned(key("document", "first"), first, first.actualBytes) { transfers++ }
        )
        assertEquals(
            ByteAwareCachePutResult.ACCEPTED,
            cache.putOwned(key("document", "second"), second, second.actualBytes) { transfers++ }
        )

        assertEquals(2, transfers)
        assertEquals(6L, cache.stats().totalBytes)
        assertEquals(listOf(first), released)
        assertTrue(changes >= 2)
    }

    @Test
    fun failedOwnershipTransferLeavesExistingEntryAndBudgetUntouched() {
        val released = mutableListOf<FakeResource>()
        val cache = ByteAwareResourceLruCache<String, FakeResource>(8L, released::add)
        val existing = FakeResource("existing", 4L, 4L)
        val candidate = FakeResource("candidate", 4L, 4L)
        val existingKey = key("document", "page")

        cache.putOwned(existingKey, existing, 4L)
        val failure = runCatching {
            cache.putOwned(existingKey, candidate, 4L) {
                error("producer could not transfer ownership")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertSame(existing, cache.get(existingKey))
        assertEquals(4L, cache.stats().totalBytes)
        assertTrue(released.isEmpty())

        cache.clear()
        assertEquals(listOf(existing), released)
    }

    @Test
    fun lruEvictionIsDeterministicAndTouchesOnRead() {
        val released = mutableListOf<FakeResource>()
        val cache = ByteAwareResourceLruCache<String, FakeResource>(10L, released::add)
        val first = FakeResource("first", 4L, 4L)
        val second = FakeResource("second", 4L, 4L)
        val third = FakeResource("third", 4L, 4L)

        cache.putOwned(key("document", "first"), first, 4L)
        cache.putOwned(key("document", "second"), second, 4L)
        assertSame(first, cache.get(key("document", "first")))
        cache.putOwned(key("document", "third"), third, 4L)

        assertEquals(
            listOf(key("document", "first"), key("document", "third")),
            cache.activeEntries().map { it.key }
        )
        assertEquals(listOf(second), released)
        assertEquals(8L, cache.stats().totalBytes)
    }

    @Test
    fun replacementAndClearRetireLeasedEntriesUntilLeaseCloses() {
        val released = mutableListOf<FakeResource>()
        val cache = ByteAwareResourceLruCache<String, FakeResource>(8L, released::add)
        val original = FakeResource("original", 4L, 4L)
        val replacement = FakeResource("replacement", 4L, 4L)
        val originalKey = key("document", "page")

        cache.putOwned(originalKey, original, 4L)
        val lease = checkNotNull(cache.acquire(originalKey))
        assertEquals(
            ByteAwareCachePutResult.ACCEPTED,
            cache.putOwned(originalKey, replacement, 4L)
        )
        assertEquals(8L, cache.stats().totalBytes)
        assertEquals(1, cache.stats().retiredLeasedEntryCount)
        assertTrue(released.isEmpty())
        assertSame(original, lease.value)

        cache.clear()
        assertEquals(1, cache.stats().retiredLeasedEntryCount)
        assertEquals(4L, cache.stats().totalBytes)
        assertEquals(listOf(replacement), released)

        lease.close()
        lease.close()
        assertEquals(listOf(replacement, original), released)
        assertEquals(0L, cache.stats().totalBytes)
    }

    @Test
    fun leasedEntryCannotForceBudgetOverageWhenNoUnleasedEntryCanMakeRoom() {
        val released = mutableListOf<FakeResource>()
        val cache = ByteAwareResourceLruCache<String, FakeResource>(8L, released::add)
        val first = FakeResource("first", 4L, 4L)
        val second = FakeResource("second", 4L, 4L)
        val rejected = FakeResource("rejected", 4L, 4L)

        cache.putOwned(key("document", "first"), first, 4L)
        cache.putOwned(key("document", "second"), second, 4L)
        val firstLease = checkNotNull(cache.acquire(key("document", "first")))
        val secondLease = checkNotNull(cache.acquire(key("document", "second")))

        var transferred = false
        assertEquals(
            ByteAwareCachePutResult.REJECTED_BUDGET,
            cache.putOwned(key("document", "rejected"), rejected, 4L) { transferred = true }
        )
        assertFalse(transferred)
        assertEquals(8L, cache.stats().totalBytes)
        assertEquals(2, cache.activeEntries().size)
        assertTrue(released.isEmpty())

        firstLease.close()
        secondLease.close()
        cache.clear()
        assertEquals(listOf(first, second), released)
    }

    @Test
    fun aliasedValuesReleaseExactlyOnceAfterAllKeysDisappear() {
        val released = mutableListOf<FakeResource>()
        val cache = ByteAwareResourceLruCache<String, FakeResource>(12L, released::add)
        val shared = FakeResource("shared", 4L, 4L)
        var transfers = 0

        cache.putOwned(key("document", "one"), shared, 4L) { transfers++ }
        cache.putOwned(key("document", "two"), shared, 4L) { transfers++ }
        cache.clearNamespace("other-document")
        assertTrue(released.isEmpty())
        cache.clearNamespace("document")

        assertEquals(2, transfers)
        assertEquals(listOf(shared), released)
    }

    @Test
    fun namespacesAreIsolatedAndNamespaceClearDoesNotTouchOtherDocuments() {
        val released = mutableListOf<FakeResource>()
        val cache = ByteAwareResourceLruCache<String, FakeResource>(16L, released::add)
        val documentA = FakeResource("A", 4L, 4L)
        val documentB = FakeResource("B", 4L, 4L)
        val pageKeyA = key("document-a|fingerprint-a", "page-1")
        val pageKeyB = key("document-b|fingerprint-b", "page-1")

        cache.putOwned(pageKeyA, documentA, 4L)
        cache.putOwned(pageKeyB, documentB, 4L)
        assertSame(documentA, cache.get(pageKeyA))
        assertSame(documentB, cache.get(pageKeyB))

        cache.clearNamespace(pageKeyA.namespace)
        assertFalse(cache.contains(pageKeyA))
        assertSame(documentB, cache.get(pageKeyB))
        assertEquals(listOf(documentA), released)

        cache.close()
        assertEquals(listOf(documentA, documentB), released)
    }

    @Test
    fun cacheCloseIsIdempotentAndLeaseReleasesAfterClose() {
        val released = mutableListOf<FakeResource>()
        val cache = ByteAwareResourceLruCache<String, FakeResource>(8L, released::add)
        val resource = FakeResource("resource", 4L, 4L)
        val resourceKey = key("document", "page")

        cache.putOwned(resourceKey, resource, 4L)
        val lease = checkNotNull(cache.acquire(resourceKey))
        cache.close()
        cache.close()
        assertTrue(cache.stats().closed)
        assertEquals(4L, cache.stats().totalBytes)
        assertTrue(released.isEmpty())

        lease.close()
        assertEquals(listOf(resource), released)
        assertEquals(0L, cache.stats().totalBytes)
    }

    @Test
    fun rejectedOrStaleWorkerPublicationDoesNotTransferOrLeak() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val boundary = Stage7WorkerResourceBoundary(dispatcher, dispatcher)
        val released = mutableListOf<FakeResource>()
        val cache = ByteAwareResourceLruCache<String, FakeResource>(4L, released::add)
        val resource = FakeResource("worker", 4L, 4L)
        val producerOwner = Stage7ResourceOwner<FakeResource> { released += it }
        val loaded = producerOwner.owned(resource)
        var transferred = false

        val failure = runCatching {
            boundary.computeAndPublish(
                compute = { loaded },
                acceptsBeforeMain = { true },
                acceptsOnMain = { true },
                publish = {
                    val admission = cache.putOwned(
                        key("document", "page"),
                        resource,
                        bytes = 5L,
                        transferOwnership = {
                            transferred = true
                            loaded.transferOwnership()
                        }
                    )
                    assertEquals(ByteAwareCachePutResult.REJECTED_OVERSIZED, admission)
                    error("cache admission was rejected")
                },
                reject = { it.close() }
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(transferred)
        assertEquals(listOf(resource), released)
        assertEquals(0, cache.activeEntries().size)

        val staleResource = FakeResource("stale", 4L, 4L)
        val staleOwner = Stage7ResourceOwner<FakeResource> { released += it }
        val staleLoaded = staleOwner.owned(staleResource)
        var stalePublished = false
        val staleAccepted = boundary.computeAndPublish(
            compute = { staleLoaded },
            acceptsBeforeMain = { false },
            publish = { stalePublished = true },
            reject = { it.close() }
        )

        assertFalse(staleAccepted)
        assertFalse(stalePublished)
        assertEquals(listOf(resource, staleResource), released)
    }
}
