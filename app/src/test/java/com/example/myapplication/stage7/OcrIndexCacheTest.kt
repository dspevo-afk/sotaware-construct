package com.example.myapplication.stage7

import android.app.Application
import android.graphics.RectF
import com.example.myapplication.OcrBox
import com.example.myapplication.OcrIndex
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentWorkOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class OcrIndexCacheTest {
    @Test
    fun fullPassMarkerIsNotPageResidency_andMissingPagesRebuildAfterLruEviction() = runTest {
        val firstGraph = CountingGraph(pageCountValue = 201)
        val secondGraph = CountingGraph(pageCountValue = 201)
        val factory = QueueFactory(firstGraph, secondGraph)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val index = OcrIndex(
            Application(),
            Stage7WorkerResourceBoundary(dispatcher, dispatcher),
            factory
        )
        val firstToken = token("first")
        val secondToken = token("second")

        try {
            assertTrue(index.preCacheDocument(firstToken, cacheNamespace = "namespace-a"))

            // Page 0 is outside the 200-entry page cache, but the full-pass
            // marker remains a separate successful-indexing fact.
            assertNull(index.getCachedPageOcr(firstToken, 0, "namespace-a"))
            assertNotNull(index.getCachedPageOcr(firstToken, 200, "namespace-a"))
            assertTrue(index.isDocumentCached(firstToken, "namespace-a"))
            assertFalse(index.isDocumentCached(firstToken, "namespace-b"))

            // A different token cannot read the first document's namespace,
            // even before it opens its own OCR graph.
            assertNull(index.getCachedPageOcr(secondToken, 200, "namespace-a"))
            assertTrue(index.preCacheDocument(secondToken, cacheNamespace = "namespace-b"))
            assertTrue(index.isDocumentCached(secondToken, "namespace-b"))
            assertFalse(index.isDocumentCached(secondToken, "namespace-a"))

            // The first marker still skips a redundant full pass, while a
            // missing page is rebuilt on demand in the live session.
            assertFalse(index.preCacheDocument(firstToken, cacheNamespace = "namespace-a"))
            assertNotNull(index.getPageOcr(firstToken, 0, "namespace-a"))
            assertEquals(202, firstGraph.recognizeCalls)
            assertEquals(201, secondGraph.recognizeCalls)
        } finally {
            index.closeAndJoin()
        }
    }

    @Test
    fun evictionAndClose_areScopedToExactToken_andRePrecacheReopensCleanGraph() = runTest {
        val firstGraph = CountingGraph(pageCountValue = 1)
        val secondGraph = CountingGraph(pageCountValue = 1)
        val nextGenerationGraph = CountingGraph(pageCountValue = 1)
        val reopenedGraph = CountingGraph(pageCountValue = 1)
        val factory = QueueFactory(firstGraph, secondGraph, nextGenerationGraph, reopenedGraph)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val index = OcrIndex(
            Application(),
            Stage7WorkerResourceBoundary(dispatcher, dispatcher),
            factory
        )
        val firstToken = token("same-source", generation = 1L)
        val nextGenerationToken = token(
            "same-source",
            documentId = firstToken.documentId,
            generation = 2L
        )
        val otherToken = token("other-source")

        try {
            assertTrue(index.preCacheDocument(firstToken, "shared"))
            assertTrue(index.preCacheDocument(otherToken, "shared"))
            assertTrue(index.preCacheDocument(nextGenerationToken, "shared"))

            index.evictSessionAndJoin(firstToken)
            assertFalse(index.isDocumentCached(firstToken, "shared"))
            assertNull(index.getCachedPageOcr(firstToken, 0, "shared"))
            assertTrue(index.isDocumentCached(otherToken, "shared"))
            assertTrue(index.isDocumentCached(nextGenerationToken, "shared"))
            assertEquals(1, firstGraph.closeCalls)

            // The marker and page were removed with the retired session, so a
            // later pre-cache must use a fresh graph rather than trust stale
            // full-document state.
            assertTrue(index.preCacheDocument(firstToken, "shared"))
            assertEquals(4, factory.openCalls)
            assertEquals(1, reopenedGraph.recognizeCalls)
            assertTrue(index.isDocumentCached(firstToken, "shared"))

            index.closeSessionAndJoin(firstToken)
            assertFalse(index.isDocumentCached(firstToken, "shared"))
            assertTrue(index.isDocumentCached(otherToken, "shared"))
            assertTrue(index.isDocumentCached(nextGenerationToken, "shared"))
            assertEquals(1, reopenedGraph.closeCalls)

            index.closeAndJoin()
            assertFalse(index.isDocumentCached(otherToken, "shared"))
            assertFalse(index.isDocumentCached(nextGenerationToken, "shared"))
        } finally {
            index.closeAndJoin()
        }
        assertEquals(1, secondGraph.closeCalls)
        assertEquals(1, nextGenerationGraph.closeCalls)
    }

    @Test
    fun cancellationDuringPageRecognition_doesNotPublishFullPassMarker() = runTest {
        val recognitionStarted = CompletableDeferred<Unit>()
        val recognitionGate = CompletableDeferred<Unit>()
        val graph = CountingGraph(
            pageCountValue = 1,
            recognitionStarted = recognitionStarted,
            recognitionGate = recognitionGate
        )
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val index = newIndex(dispatcher, QueueFactory(graph))
        val sessionToken = token("canceled")
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            index.preCacheDocument(sessionToken, "canceled-namespace")
        }

        try {
            recognitionStarted.await()
            val cancellation = CancellationException("cancel OCR pre-cache")
            operation.cancel(cancellation)
            val observed = runCatching { operation.await() }.exceptionOrNull()

            assertTrue(observed is CancellationException)
            assertNull(index.getCachedPageOcr(sessionToken, 0, "canceled-namespace"))
            assertFalse(index.isDocumentCached(sessionToken, "canceled-namespace"))
            assertEquals(1, graph.closeCalls)
        } finally {
            recognitionGate.cancel()
            index.closeAndJoin()
        }
    }

    @Test
    fun recognitionFailure_doesNotPublishFullPassMarker_andCanRetryWithFreshGraph() = runTest {
        val failure = IllegalStateException("recognition failed")
        val failedGraph = CountingGraph(pageCountValue = 1, recognitionFailure = failure)
        val recoveredGraph = CountingGraph(pageCountValue = 1)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val index = newIndex(dispatcher, QueueFactory(failedGraph, recoveredGraph))
        val sessionToken = token("failed")

        try {
            val observed = supervisorScope {
                val operation = async {
                    index.preCacheDocument(sessionToken, "failed-namespace")
                }
                runCatching { operation.await() }.exceptionOrNull()
            }
            assertTrue(observed is IllegalStateException)
            assertEquals(failure.message, observed?.message)
            assertNull(index.getCachedPageOcr(sessionToken, 0, "failed-namespace"))
            assertFalse(index.isDocumentCached(sessionToken, "failed-namespace"))
            assertEquals(1, failedGraph.closeCalls)

            assertTrue(index.preCacheDocument(sessionToken, "failed-namespace"))
            assertTrue(index.isDocumentCached(sessionToken, "failed-namespace"))
            assertEquals(1, recoveredGraph.recognizeCalls)
        } finally {
            index.closeAndJoin()
        }
    }

    @Test
    fun publicationCancellationOrFailure_rollsBackPagesAndFullPassMarker() = runTest {
        val cancellation = CancellationException("canceled at cache publication")
        val failedPublication = IllegalStateException("cache publication failed")
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        runPublicationAbortCase(cancellation, dispatcher)
        runPublicationAbortCase(failedPublication, dispatcher)
    }

    @Test
    fun fullPassMarkerRetention_isBoundedToConfiguredHistory() = runTest {
        val factory = OcrSessionResourceFactory { CountingGraph(pageCountValue = 0) }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val index = newIndex(dispatcher, factory)
        val tokens = (0..OcrIndex.MAX_FULL_DOCUMENT_MARKERS).map { index ->
            token("marker-$index")
        }

        try {
            tokens.forEach { sessionToken ->
                assertTrue(index.preCacheDocument(sessionToken, "marker-namespace"))
            }
            assertFalse(index.isDocumentCached(tokens.first(), "marker-namespace"))
            assertTrue(index.isDocumentCached(tokens.last(), "marker-namespace"))
        } finally {
            index.closeAndJoin()
        }
    }

    @Test
    fun preAdmissionReadOnlyAndFailedGenerations_doNotRetainNamespaceOwnership() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val failure = IllegalStateException("session open failed")
        val failingIndex = OcrIndex(
            Application(),
            Stage7WorkerResourceBoundary(dispatcher, dispatcher),
            OcrSessionResourceFactory { throw failure }
        )

        try {
            repeat(512) { generation ->
                val sessionToken = token("stale-$generation", generation = generation.toLong() + 1L)

                // Rejected/read-only calls never acquire a session and must
                // therefore never become terminal-cleanup bookkeeping.
                assertFalse(
                    failingIndex.isDocumentCached(
                        sessionToken,
                        "read-only",
                        isCurrent = { false }
                    )
                )
                assertNull(
                    failingIndex.getCachedPageOcr(
                        sessionToken,
                        0,
                        "read-only",
                        isCurrent = { false }
                    )
                )
                assertFalse(
                    failingIndex.preCacheDocument(
                        sessionToken,
                        "stale",
                        isCurrent = { false }
                    )
                )
                assertEquals(0, failingIndex.retainedCacheNamespaceCount)

                val observed = runCatching {
                    failingIndex.getPageOcr(sessionToken, 0, "failed")
                }.exceptionOrNull()
                assertSame(failure, observed)
                assertEquals(0, failingIndex.retainedCacheNamespaceCount)
            }
        } finally {
            failingIndex.closeAndJoin()
        }

        val activeGraph = CountingGraph(pageCountValue = 0)
        val activeToken = token("active")
        val activeIndex = newIndex(dispatcher, QueueFactory(activeGraph))
        try {
            assertTrue(activeIndex.preCacheDocument(activeToken, "active"))
            assertEquals(1, activeIndex.retainedCacheNamespaceCount)
            activeIndex.closeSessionAndJoin(activeToken)
            assertEquals(0, activeIndex.retainedCacheNamespaceCount)
        } finally {
            activeIndex.closeAndJoin()
        }
    }

    @Test
    fun admittedFailureAfterSameOwnerRebind_releasesOldIdentityAndPreservesNewCache() = runBlocking {
        val failure = IllegalStateException("rebound recognition failed")
        val oldGraph = CountingGraph(pageCountValue = 1, recognitionFailure = failure)
        val reboundGraph = CountingGraph(pageCountValue = 1)
        val factory = QueueFactory(oldGraph, reboundGraph)
        val oldCloseStarted = CompletableDeferred<Unit>()
        val allowOldClose = CountDownLatch(1)
        val workerDispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val registry = OcrSessionRegistry {
            oldCloseStarted.complete(Unit)
            check(allowOldClose.await(2, TimeUnit.SECONDS)) {
                "timed out waiting to close the old OCR entry"
            }
        }
        val boundary = Stage7WorkerResourceBoundary(
            workerDispatcher,
            workerDispatcher,
            ocrSessionRegistry = registry
        )
        val index = OcrIndex(Application(), boundary, factory)
        val sessionToken = token("rebound-failure")
        val owner = DocumentWorkOwner()
        var failedOperation: kotlinx.coroutines.Deferred<Throwable?>? = null

        try {
            failedOperation = async(Dispatchers.Default) {
                runCatching {
                    index.getPageOcr(
                        token = sessionToken,
                        pageIndex = 0,
                        cacheNamespace = "shared",
                        owner = owner
                    )
                }.exceptionOrNull()
            }
            withTimeout(2_000) { oldCloseStarted.await() }

            // The failed withSession has retired the old registry entry, but
            // its OcrIndex cleanup is still gated in close. Rebinding the
            // same owner replaces the old binding before that cleanup can
            // reserve it, then publishes a page under the shared prefix.
            assertNotNull(
                async(Dispatchers.Default) {
                    index.getPageOcr(
                        token = sessionToken,
                        pageIndex = 0,
                        cacheNamespace = "shared",
                        owner = owner
                    )
                }.await()
            )
            assertEquals(1, reboundGraph.recognizeCalls)
            assertEquals(2, index.retainedCacheNamespaceCount)

            allowOldClose.countDown()
            val observedFailure = checkNotNull(checkNotNull(failedOperation).await())
            assertTrue(observedFailure is IllegalStateException)
            assertEquals(failure.message, observedFailure.message)

            // The old exact identity is released even though its cleanup
            // reservation was rejected by the same-owner rebind. The new
            // page remains cached and is not recognized a second time.
            assertEquals(1, index.retainedCacheNamespaceCount)
            assertNotNull(index.getCachedPageOcr(sessionToken, 0, "shared"))
            index.getPageOcr(
                token = sessionToken,
                pageIndex = 0,
                cacheNamespace = "shared",
                owner = owner
            )
            assertEquals(1, reboundGraph.recognizeCalls)
        } finally {
            allowOldClose.countDown()
            failedOperation?.let { operation -> runCatching { operation.await() } }
            runCatching { index.closeAndJoin() }
            workerDispatcher.close()
        }
    }

    private suspend fun runPublicationAbortCase(
        expected: Throwable,
        dispatcher: TestDispatcher
    ) = supervisorScope {
        val graph = CountingGraph(pageCountValue = 1)
        val fence = Stage7PublicationFence()
        val index = OcrIndex(
            Application(),
            Stage7WorkerResourceBoundary(
                dispatcher,
                dispatcher,
                publicationFence = fence
            ),
            QueueFactory(graph),
            cachePublicationHook = { throw expected }
        )
        val sessionToken = token("publication-${expected::class.simpleName}")

        try {
            val operation = async {
                index.preCacheDocument(sessionToken, "publication-namespace")
            }
            val observed = runCatching { operation.await() }.exceptionOrNull()
            if (expected is CancellationException) {
                assertTrue(observed is CancellationException)
            } else {
                assertTrue(observed is IllegalStateException)
                assertEquals(expected.message, observed?.message)
            }
            assertNull(index.getCachedPageOcr(sessionToken, 0, "publication-namespace"))
            assertFalse(index.isDocumentCached(sessionToken, "publication-namespace"))
            assertEquals(1, graph.closeCalls)
        } finally {
            index.closeAndJoin()
        }
    }

    private fun newIndex(
        dispatcher: TestDispatcher,
        factory: OcrSessionResourceFactory
    ): OcrIndex {
        return OcrIndex(
            Application(),
            Stage7WorkerResourceBoundary(dispatcher, dispatcher),
            factory
        )
    }

    private fun token(
        name: String,
        documentId: DocumentId = DocumentId.new(),
        generation: Long = 1L
    ): DocumentSessionToken = DocumentSessionToken(
        documentId = documentId,
        sourceUri = "content://stage7/$name",
        sourceFingerprint = SourceFingerprint.fromBytes(name.toByteArray()),
        generation = generation
    )

    private class QueueFactory(vararg graphs: CountingGraph) : OcrSessionResourceFactory {
        private val pending = ArrayDeque(graphs.toList())
        var openCalls: Int = 0

        override suspend fun open(token: DocumentSessionToken): OcrSessionResourceGraph {
            openCalls++
            return pending.removeFirst()
        }
    }

    private class CountingGraph(
        private val pageCountValue: Int,
        private val recognitionFailure: Throwable? = null,
        private val recognitionStarted: CompletableDeferred<Unit>? = null,
        private val recognitionGate: CompletableDeferred<Unit>? = null
    ) : OcrSessionResourceGraph {
        var recognizeCalls: Int = 0
        var closeCalls: Int = 0

        override suspend fun pageCount(): Int = pageCountValue

        override suspend fun extractEmbeddedText(pageIndex: Int): List<OcrBox> = emptyList()

        override suspend fun recognizePage(pageIndex: Int): List<OcrBox> {
            recognizeCalls++
            recognitionStarted?.complete(Unit)
            recognitionGate?.await()
            recognitionFailure?.let { throw it }
            return listOf(
                OcrBox(
                    text = "page-$pageIndex",
                    rectN = RectF(0f, 0f, 1f, 1f)
                )
            )
        }

        override fun close() {
            closeCalls++
            check(closeCalls == 1) { "OCR graph closed more than once" }
        }
    }
}
