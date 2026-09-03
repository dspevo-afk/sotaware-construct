package com.example.myapplication.stage7

import com.example.myapplication.OcrBox
import com.example.myapplication.PageOcr
import com.example.myapplication.OcrIndex
import com.example.myapplication.PdfSearchEngine
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentWorkOwner
import com.example.myapplication.stage3.DocumentWorkToken
import com.example.myapplication.stage4.runNonCancellableFinalizers
import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.math.max

@OptIn(ExperimentalCoroutinesApi::class)
class OcrSessionTest {
    @Test
    fun acquire_cancellationAfterOpenBeforeRegistration_closesCandidateAndPublishesNoEntry() = runTest {
        val firstGraph = FakeGraph(pageCountValue = 1)
        val secondGraph = FakeGraph(pageCountValue = 1)
        val graphs = ArrayDeque(listOf(firstGraph, secondGraph))
        val factory = FakeFactory { graphs.removeFirst() }
        val cancellation = CancellationException("canceled after OCR graph open")
        val cancelOnce = AtomicInteger(0)
        val registry = OcrSessionRegistry(afterOpenBeforeRegistration = { job ->
            if (cancelOnce.compareAndSet(0, 1)) job?.cancel(cancellation)
        })
        val sessionToken = token()

        val firstFailure = AtomicReference<Throwable?>()
        val openingJob = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                registry.getOrOpen(sessionToken, factory)
            } catch (error: Throwable) {
                firstFailure.set(error)
            }
        }
        openingJob.join()
        assertTrue(firstFailure.get() is CancellationException)
        assertEquals(1, firstGraph.closeCalls)
        assertEquals(1, factory.openCalls)

        // The canceled candidate was never registered. A later caller opens
        // a fresh graph for the same exact token rather than receiving a
        // closing/closed entry.
        registry.withSession(sessionToken, factory) { session -> session.pageOcr(0) }
        assertEquals(2, factory.openCalls)
        assertEquals(0, secondGraph.closeCalls)
        registry.closeAndJoin()
        assertEquals(1, secondGraph.closeCalls)
    }

    @Test
    fun withSessionLease_blocksEvictionUntilActiveUseReleases_thenReopensFreshGraph() = runTest {
        val firstGraph = FakeGraph(pageCountValue = 1)
        val secondGraph = FakeGraph(pageCountValue = 1)
        val graphs = ArrayDeque(listOf(firstGraph, secondGraph))
        val factory = FakeFactory { graphs.removeFirst() }
        val registry = OcrSessionRegistry()
        val sessionToken = token()

        // Establish the already-active graph before exercising the lease.
        registry.getOrOpen(sessionToken, factory)
        val useEntered = CompletableDeferred<Unit>()
        val releaseUse = CompletableDeferred<Unit>()
        val use = async(start = CoroutineStart.UNDISPATCHED) {
            registry.withSession(sessionToken, factory) {
                useEntered.complete(Unit)
                withContext(kotlinx.coroutines.NonCancellable) {
                    releaseUse.await()
                }
            }
        }
        useEntered.await()

        val eviction = async { registry.evictSessionAndJoin(sessionToken) }
        runCurrent()
        assertFalse(eviction.isCompleted)
        assertEquals(0, firstGraph.closeCalls)

        releaseUse.complete(Unit)
        runCatching { use.await() }
        eviction.await()
        assertEquals(1, firstGraph.closeCalls)

        registry.withSession(sessionToken, factory) { it.pageOcr(0) }
        assertEquals(2, factory.openCalls)
        registry.closeAndJoin()
        assertEquals(1, secondGraph.closeCalls)
    }

    @Test
    fun failedOldLeaseCleanup_cannotEvictReboundEntryForTheSameFullToken() = runTest {
        val oldGraph = FakeGraph(pageCountValue = 1)
        val reboundGraph = FakeGraph(pageCountValue = 1)
        val graphs = ArrayDeque(listOf(oldGraph, reboundGraph))
        val factory = FakeFactory { graphs.removeFirst() }
        val registry = OcrSessionRegistry()
        val sessionToken = token()
        val oldSession = registry.getOrOpen(sessionToken, factory)
        val firstReady = CompletableDeferred<Unit>()
        val firstMayFail = CompletableDeferred<Unit>()
        val firstAboutToFail = CompletableDeferred<Unit>()
        val secondReady = CompletableDeferred<Unit>()
        val secondCleanupStarted = CompletableDeferred<Unit>()
        val releaseSecondCleanup = CompletableDeferred<Unit>()
        val secondBodyGate = CompletableDeferred<Unit>()
        val firstFailure = IllegalStateException("first old lease failed")

        supervisorScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    registry.withSession(sessionToken, factory) {
                        assertSame(oldSession, it)
                        firstReady.complete(Unit)
                        firstMayFail.await()
                        firstAboutToFail.complete(Unit)
                        throw firstFailure
                    }
                }.exceptionOrNull()
            }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    registry.withSession(sessionToken, factory) {
                        assertSame(oldSession, it)
                        secondReady.complete(Unit)
                        try {
                            secondBodyGate.await()
                        } finally {
                            // The first failed lease removes old E and
                            // cancels this still-active lease. Hold its
                            // release until N has been rebound so the old
                            // conditional cleanup runs after the rebind.
                            secondCleanupStarted.complete(Unit)
                            withContext(kotlinx.coroutines.NonCancellable) {
                                releaseSecondCleanup.await()
                            }
                        }
                    }
                }.exceptionOrNull()
            }
            firstReady.await()
            secondReady.await()
            firstMayFail.complete(Unit)
            firstAboutToFail.await()

            // The first failure cleanup removes old E and waits for the
            // still-active second lease. It has already canceled that lease,
            // but the block's NonCancellable finally keeps it outstanding.
            secondCleanupStarted.await()

            registry.withSession(sessionToken, factory) { rebound ->
                assertNotSame(oldSession, rebound)
                rebound.pageOcr(0)
            }
            assertEquals(2, factory.openCalls)
            assertEquals(0, reboundGraph.closeCalls)

            releaseSecondCleanup.complete(Unit)
            assertSame(firstFailure, first.await())
            second.join()
            assertTrue(second.isCancelled)
            assertEquals(1, oldGraph.closeCalls)

            // The old second lease's conditional cleanup must not remove N.
            registry.withSession(sessionToken, factory) { rebound ->
                assertNotSame(oldSession, rebound)
                rebound.pageOcr(0)
            }
            assertEquals(2, factory.openCalls)
            assertEquals(0, reboundGraph.closeCalls)
            registry.closeAndJoin()
            assertEquals(1, reboundGraph.closeCalls)
        }
    }

    @Test
    fun ownerBoundEviction_cannotCloseReboundGraph_forOldCoordinatorOwner() = runBlocking {
        val oldEntryCloseStarted = CompletableDeferred<Unit>()
        val allowOldEntryClose = CountDownLatch(1)
        val oldGraph = FakeGraph(pageCountValue = 1)
        val reboundGraph = FakeGraph(pageCountValue = 1)
        val graphs = ArrayDeque<OcrSessionResourceGraph>(listOf(oldGraph, reboundGraph))
        val reboundOpenStarted = CompletableDeferred<Unit>()
        var openCalls = 0
        val factory = OcrSessionResourceFactory {
            openCalls++
            if (openCalls == 2) reboundOpenStarted.complete(Unit)
            graphs.removeFirst()
        }
        // Use a dedicated worker so the post-removal close gate cannot starve
        // the concurrent rebound/open operation in a shared scheduler.
        val workerDispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        var oldCleanup: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            val registry = OcrSessionRegistry {
                oldEntryCloseStarted.complete(Unit)
                check(allowOldEntryClose.await(2, TimeUnit.SECONDS)) {
                    "timed out waiting to close the old OCR entry"
                }
            }
            val boundary = Stage7WorkerResourceBoundary(
                workerDispatcher,
                workerDispatcher,
                ocrSessionRegistry = registry
            )
            val index = OcrIndex(Application(), boundary, factory)
            val sessionToken = token()
            val oldOwner = DocumentWorkOwner()
            val reboundOwner = DocumentWorkOwner()

            index.getPageOcr(
                token = sessionToken,
                pageIndex = 0,
                cacheNamespace = "old-owner-${sessionToken.documentId.value}",
                owner = oldOwner
            )
            assertEquals(1, openCalls)

            oldCleanup = async(Dispatchers.Default) {
                index.evictSessionAndJoin(sessionToken, oldOwner)
            }
            try {
                withTimeout(2_000) { oldEntryCloseStarted.await() }
            } catch (timeout: Throwable) {
                throw AssertionError(
                    "old cleanup did not reach close; completed=${oldCleanup?.isCompleted}, " +
                        "cancelled=${oldCleanup?.isCancelled}, opens=$openCalls",
                    timeout
                )
            }

            // The old cleanup has removed E from the registry but is still
            // closing its graph. A new owner must bind a fresh N and remain usable.
            val reboundPage = async(Dispatchers.Default) {
                index.getPageOcr(
                    token = sessionToken,
                    pageIndex = 0,
                    cacheNamespace = "rebound-owner-${sessionToken.documentId.value}",
                    owner = reboundOwner
                )
            }.await()
            withTimeout(2_000) { reboundOpenStarted.await() }
            assertEquals(1, reboundPage?.boxes?.size)
            assertEquals(2, openCalls)

            allowOldEntryClose.countDown()
            oldCleanup.await()
            assertEquals(0, reboundGraph.closeCalls)
            assertEquals(
                1,
                reboundGraph.recognizeCalls
            )

            index.getPageOcr(
                token = sessionToken,
                pageIndex = 0,
                cacheNamespace = "rebound-owner-${sessionToken.documentId.value}",
                owner = reboundOwner
            )
            assertEquals(
                1,
                reboundGraph.recognizeCalls
            )
            index.evictSessionAndJoin(sessionToken, reboundOwner)
            assertEquals(1, reboundGraph.closeCalls)
            index.closeAndJoin()
        } finally {
            // Do not let a failed assertion strand the intentionally gated
            // close; the child cleanup must be able to finish before the test
            // dispatcher is closed.
            allowOldEntryClose.countDown()
            oldCleanup?.let { cleanup -> runCatching { cleanup.await() } }
            workerDispatcher.close()
        }
    }

    @Test
    fun canceledWorkerHandoff_cannotEvictSameEntryReusedByNewOperation() = runBlocking {
        val handoffEntered = CompletableDeferred<Unit>()
        val releaseHandoff = CountDownLatch(1)
        val handoffState = AtomicInteger(0)
        val cancellation = CancellationException("canceled at worker handoff")
        val graph = FakeGraph(pageCountValue = 1)
        val factory = FakeFactory { graph }
        val workerDispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        var oldOperation: kotlinx.coroutines.Deferred<PageOcr?>? = null
        var newOperation: kotlinx.coroutines.Deferred<PageOcr?>? = null
        try {
            val boundary = Stage7WorkerResourceBoundary(
                workerDispatcher,
                workerDispatcher,
                beforeWorkerHandoff = { job ->
                    if (handoffState.compareAndSet(1, 2)) {
                        job?.cancel(cancellation)
                        handoffEntered.complete(Unit)
                        check(releaseHandoff.await(2, TimeUnit.SECONDS)) {
                            "timed out waiting to release canceled worker handoff"
                        }
                    }
                }
            )
            val index = OcrIndex(Application(), boundary, factory)
            val sessionToken = token()
            val oldOwner = DocumentWorkOwner()
            val oldNamespace = "handoff-old-${sessionToken.documentId.value}"
            val canceledNamespace = "handoff-canceled-${sessionToken.documentId.value}"
            val newNamespace = "handoff-new-${sessionToken.documentId.value}"

            index.getPageOcr(
                token = sessionToken,
                pageIndex = 0,
                cacheNamespace = oldNamespace,
                owner = oldOwner
            )
            handoffState.set(1)
            oldOperation = async(Dispatchers.Default) {
                index.getPageOcr(
                    token = sessionToken,
                    pageIndex = 0,
                    cacheNamespace = canceledNamespace,
                    owner = oldOwner
                )
            }
            withTimeout(2_000) { handoffEntered.await() }

            // The old operation has released its registry lease at the
            // worker handoff, but its stale cleanup is not running yet. The
            // A newer operation from the same stable owner must be able to
            // reuse E before that cleanup starts. Its binding reference is a
            // newer generation, so old cleanup cannot close E after the new
            // operation has already released its lease.
            newOperation = async(Dispatchers.Default) {
                index.getPageOcr(
                    token = sessionToken,
                    pageIndex = 0,
                    cacheNamespace = newNamespace,
                    owner = oldOwner
                )
            }
            assertEquals(1, newOperation!!.await()?.boxes?.size)
            assertEquals(1, factory.openCalls)
            assertEquals(0, graph.closeCalls)

            releaseHandoff.countDown()
            val oldObserved = runCatching { oldOperation!!.await() }.exceptionOrNull()
            assertTrue(oldObserved is CancellationException)
            assertEquals(0, graph.closeCalls)
            val recognizeCallsAfterHandoffCleanup = graph.recognizeCalls
            assertNotNull(index.getCachedPageOcr(sessionToken, 0, newNamespace))

            // The old operation cleanup observed the newer operation binding
            // while E itself remained current, so it must not clear the newer
            // page published under the shared token prefix. The stable owner
            // can still use and then close E without recognizing again.
            assertEquals(1, index.getPageOcr(
                token = sessionToken,
                pageIndex = 0,
                cacheNamespace = newNamespace,
                owner = oldOwner
            )?.boxes?.size)
            assertEquals(recognizeCallsAfterHandoffCleanup, graph.recognizeCalls)
            index.evictSessionAndJoin(sessionToken, oldOwner)
            assertEquals(1, graph.closeCalls)
            index.closeAndJoin()
        } finally {
            releaseHandoff.countDown()
            oldOperation?.let { operation -> runCatching { operation.await() } }
            newOperation?.let { operation -> runCatching { operation.await() } }
            workerDispatcher.close()
        }
    }

    @Test
    fun stalePublicationCleanup_doesNotEvictSameTokenEntryReacquiredByNewOwner() = runBlocking {
        val publicationEntered = CompletableDeferred<Unit>()
        val releasePublication = CountDownLatch(1)
        val oldEntryCloseStarted = CompletableDeferred<Unit>()
        val releaseOldEntryClose = CountDownLatch(1)
        val publicationCalls = AtomicInteger(0)
        var oldOwnerAdmitted = true
        val oldGraph = FakeGraph(pageCountValue = 1)
        val newGraph = FakeGraph(pageCountValue = 1)
        val graphs = ArrayDeque<OcrSessionResourceGraph>(listOf(oldGraph, newGraph))
        val openCalls = AtomicInteger(0)
        val workerDispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        var oldOperation: kotlinx.coroutines.Deferred<PageOcr?>? = null
        var newOperation: kotlinx.coroutines.Deferred<PageOcr?>? = null
        try {
            val fence = com.example.myapplication.stage7.Stage7PublicationFence()
            val boundary = Stage7WorkerResourceBoundary(
                workerDispatcher,
                workerDispatcher,
                ocrSessionRegistry = OcrSessionRegistry {
                    oldEntryCloseStarted.complete(Unit)
                    check(releaseOldEntryClose.await(2, TimeUnit.SECONDS)) {
                        "timed out waiting to release stale entry close"
                    }
                },
                publicationFence = fence
            )
            val index = OcrIndex(
                Application(),
                boundary,
                OcrSessionResourceFactory {
                    openCalls.incrementAndGet()
                    graphs.removeFirst()
                },
                cachePublicationHook = {
                    if (publicationCalls.compareAndSet(0, 1)) {
                        oldOwnerAdmitted = false
                        publicationEntered.complete(Unit)
                        check(releasePublication.await(2, TimeUnit.SECONDS)) {
                            "timed out waiting to release stale publication"
                        }
                    }
                }
            )
            val sessionToken = token()
            val oldNamespace = "publication-old-${sessionToken.documentId.value}"
            val newNamespace = "publication-new-${sessionToken.documentId.value}"
            val oldOwner = DocumentWorkOwner()
            val newOwner = DocumentWorkOwner()

            oldOperation = async(Dispatchers.Default) {
                index.getPageOcr(
                    token = sessionToken,
                    pageIndex = 0,
                    cacheNamespace = oldNamespace,
                    isCurrent = { oldOwnerAdmitted },
                    owner = oldOwner
                )
            }
            withTimeout(2_000) { publicationEntered.await() }
            releasePublication.countDown()

            // Namespace rollback has completed and registry failure cleanup
            // has atomically retired E, but its close is held. A new owner
            // must therefore acquire a fresh N before old cleanup resumes.
            withTimeout(2_000) { oldEntryCloseStarted.await() }
            newOperation = async(Dispatchers.Default) {
                index.getPageOcr(
                    token = sessionToken,
                    pageIndex = 0,
                    cacheNamespace = newNamespace,
                    isCurrent = { true },
                    owner = newOwner
                )
            }
            assertEquals(1, newOperation!!.await()?.boxes?.size)
            assertEquals(2, openCalls.get())
            assertEquals(0, oldGraph.closeCalls)
            assertEquals(0, newGraph.closeCalls)

            releaseOldEntryClose.countDown()
            assertNull(oldOperation!!.await())
            assertEquals(1, oldGraph.closeCalls)
            assertNull(index.getCachedPageOcr(sessionToken, 0, oldNamespace))
            assertFalse(index.isDocumentCached(sessionToken, oldNamespace))

            // Old cleanup was tied to E, so it cannot close N. The new owner
            // remains usable and is the only owner that closes N.
            assertEquals(1, index.getPageOcr(
                token = sessionToken,
                pageIndex = 0,
                cacheNamespace = newNamespace,
                owner = newOwner
            )?.boxes?.size)
            assertEquals(0, newGraph.closeCalls)

            index.evictSessionAndJoin(sessionToken, newOwner)
            assertEquals(1, newGraph.closeCalls)
            index.closeAndJoin()
        } finally {
            releasePublication.countDown()
            releaseOldEntryClose.countDown()
            oldOperation?.let { operation ->
                try {
                    withTimeout(2_000) { operation.await() }
                } catch (_: Throwable) {
                    // Preserve the assertion that caused teardown while still
                    // releasing the intentionally gated worker operations.
                }
            }
            newOperation?.let { operation ->
                try {
                    withTimeout(2_000) { operation.await() }
                } catch (_: Throwable) {
                    // See the old-operation cleanup above.
                }
            }
            workerDispatcher.close()
        }
    }

    @Test
    fun registry_reusesOneResourceGraphForFullToken_andSerializesPages() = runTest {
        val factory = FakeFactory { FakeGraph(pageCountValue = 3) }
        val registry = OcrSessionRegistry()
        val token = token()

        val first = registry.getOrOpen(token, factory)
        val second = registry.getOrOpen(token, factory)
        assertSame(first, second)

        coroutineScope {
            listOf(
                async { first.pageOcr(0) },
                async { second.pageOcr(1) }
            ).awaitAll()
        }

        val generationTwo = registry.getOrOpen(token.copy(generation = 2L), factory)
        assertNotSame(first, generationTwo)
        assertEquals(2, factory.openCalls)
        assertEquals(1, factory.graphs[0].pageCountCalls)
        assertEquals(2, factory.graphs[0].recognizeCalls)
        assertEquals(1, factory.graphs[0].maxConcurrentOperations)

        registry.closeAndJoin()
        assertEquals(1, factory.graphs[0].closeCalls)
        assertEquals(1, factory.graphs[1].closeCalls)
    }

    @Test
    fun ocrTaskLifecycle_joinsCancellationBeforeReleasingBitmapAndRecognizerOwners() = runTest {
        val successEvents = mutableListOf<String>()
        val successBitmap = FakeTransientOwner()
        val successRecognizer = FakeTransientOwner()
        val success = runOcrRecognitionTask(
            task = FakeRecognitionTask("success", events = successEvents),
            closeTransientOwners = {
                successBitmap.close()
                successEvents += "bitmap"
                successRecognizer.close()
                successEvents += "recognizer"
            }
        )
        assertEquals("success", success)
        assertEquals(listOf("await", "bitmap", "recognizer"), successEvents)
        assertEquals(1, successBitmap.closeCalls)
        assertEquals(1, successRecognizer.closeCalls)

        val failure = IllegalStateException("task failed")
        val failureEvents = mutableListOf<String>()
        val failureBitmap = FakeTransientOwner()
        val failureRecognizer = FakeTransientOwner()
        val observedFailure = runCatching {
            runOcrRecognitionTask(
                task = FakeRecognitionTask<String>(
                    value = "unused",
                    failure = failure,
                    events = failureEvents
                ),
                closeTransientOwners = {
                    failureBitmap.close()
                    failureRecognizer.close()
                }
            )
        }.exceptionOrNull()
        assertSame(failure, observedFailure)
        assertEquals(1, failureBitmap.closeCalls)
        assertEquals(1, failureRecognizer.closeCalls)

        val cancellationEvents = mutableListOf<String>()
        val awaitGate = CompletableDeferred<Unit>()
        val terminalGate = CompletableDeferred<Unit>()
        val cancellationStarted = CompletableDeferred<Unit>()
        val cancellationBitmap = FakeTransientOwner()
        val cancellationRecognizer = FakeTransientOwner()
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            runOcrRecognitionTask(
                task = FakeRecognitionTask(
                    value = "after terminal",
                    events = cancellationEvents,
                    started = cancellationStarted,
                    awaitGate = awaitGate,
                    terminalGate = terminalGate
                ),
                closeTransientOwners = {
                    cancellationBitmap.close()
                    cancellationEvents += "bitmap"
                    cancellationRecognizer.close()
                    cancellationEvents += "recognizer"
                }
            )
        }
        cancellationStarted.await()
        val cancellation = CancellationException("caller canceled recognition")
        operation.cancel(cancellation)
        runCurrent()
        assertFalse(operation.isCompleted)
        assertEquals(0, cancellationBitmap.closeCalls)
        assertEquals(0, cancellationRecognizer.closeCalls)

        // The canceled await is not the task's terminal state. Cleanup must
        // wait for this independent terminal completion gate first.
        terminalGate.complete(Unit)
        val observedCancellation = runCatching { operation.await() }.exceptionOrNull()
        assertTrue(observedCancellation is CancellationException)
        assertEquals(cancellation.message, observedCancellation?.message)
        assertEquals(
            listOf("await", "await-terminal", "bitmap", "recognizer"),
            cancellationEvents
        )
        assertEquals(1, cancellationBitmap.closeCalls)
        assertEquals(1, cancellationRecognizer.closeCalls)

        // A task adapter must preserve the exact task-originated cancellation
        // object when no coroutine boundary replaces it with a recovered Job
        // cancellation wrapper.
        val taskCancellation = CancellationException("task canceled")
        val taskObserved = runCatching {
            awaitOcrRecognitionTask(
                FakeRecognitionTask<String>(
                    value = "unused",
                    failure = taskCancellation,
                    events = mutableListOf()
                )
            )
        }.exceptionOrNull()
        assertSame(taskCancellation, taskObserved)

        val terminalFailure = IllegalStateException("task terminal wait failed")
        val transientCloseFailure = IllegalArgumentException("transient owner close failed")
        val cleanupObserved = runCatching {
            runOcrRecognitionTask(
                task = FakeRecognitionTask<String>(
                    value = "unused",
                    failure = taskCancellation,
                    terminalFailure = terminalFailure,
                    events = mutableListOf()
                ),
                closeTransientOwners = { throw transientCloseFailure }
            )
        }.exceptionOrNull()
        assertTrue(cleanupObserved is CancellationException)
        assertTrue(cleanupObserved?.suppressed?.any { it.message == terminalFailure.message } == true)
        assertTrue(cleanupObserved?.suppressed?.any { it.message == transientCloseFailure.message } == true)
    }

    @Test
    fun embeddedText_isPreferred_andPdfSearchEngine_preservesFallbackSearchBehavior() = runTest {
        val embeddedGraph = FakeGraph(
            pageCountValue = 1,
            embeddedBoxes = List(OcrSession.EMBEDDED_TEXT_MIN_BOXES) { index ->
                OcrBox("embedded-$index", android.graphics.RectF(0f, 0f, 1f, 1f))
            }
        )
        val embeddedFactory = FakeFactory { embeddedGraph }
        OcrSessionRunner(embeddedFactory).run(token()) { session ->
            val page = session.pageOcr(0)
            assertEquals(OcrSession.EMBEDDED_TEXT_MIN_BOXES, page.boxes.size)
            assertEquals(0, embeddedGraph.recognizeCalls)
        }

        val fallbackGraph = FakeGraph(
            pageCountValue = 1,
            embeddedBoxes = listOf(OcrBox("sparse", android.graphics.RectF(0f, 0f, 1f, 1f)))
        )
        val fallbackFactory = FakeFactory { fallbackGraph }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val boundary = Stage7WorkerResourceBoundary(dispatcher, dispatcher)
        val index = OcrIndex(Application(), boundary, fallbackFactory)
        val engine = PdfSearchEngine(Application(), boundary, index)
        val searchToken = token()
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = engine.search(
            workToken = DocumentWorkToken(searchToken),
            query = "page-0",
            pageCount = 1,
            onProgress = { done, total -> progress += done to total }
        )

        val hits = result[0] ?: error("expected a page-0 search hit")
        assertEquals(1, hits.size)
        assertEquals(listOf(1 to 1), progress)
        assertEquals(1, fallbackGraph.recognizeCalls)
        index.closeAndJoin()
    }

    @Test
    fun runner_closesGraphExactlyOnce_afterSuccessAndOrdinaryFailure() = runTest {
        val successGraph = FakeGraph(pageCountValue = 1)
        OcrSessionRunner(FakeFactory { successGraph }).run(token()) {
            it.pageOcr(0)
        }
        assertEquals(1, successGraph.closeCalls)

        val failure = IllegalStateException("recognition failed")
        val failureGraph = FakeGraph(pageCountValue = 1, operationFailure = failure)
        val observed = runCatching {
            OcrSessionRunner(FakeFactory { failureGraph }).run(token()) {
                it.pageOcr(0)
            }
        }.exceptionOrNull()

        assertSame(failure, observed)
        assertEquals(1, failureGraph.closeCalls)
    }

    @Test
    fun cancellationDuringRecognition_rethrowsCancellation_andClosesGraphOnce() = runTest {
        val started = CompletableDeferred<Unit>()
        val recognitionGate = CompletableDeferred<Unit>()
        val graph = FakeGraph(
            pageCountValue = 1,
            recognitionStarted = started,
            recognitionGate = recognitionGate
        )
        val operation = async(start = CoroutineStart.DEFAULT) {
            OcrSessionRunner(FakeFactory { graph }).run(token()) {
                it.pageOcr(0)
            }
        }

        started.await()
        val cancellation = CancellationException("caller canceled OCR")
        operation.cancel(cancellation)
        val observed = runCatching { operation.await() }.exceptionOrNull()

        assertTrue(observed is CancellationException)
        assertEquals(1, graph.closeCalls)
    }

    @Test
    fun concurrentFailure_andQueuedPage_doNotDeadlock_beforeExplicitOwnerClose() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val failure = IllegalStateException("recognition failed")
        val graph = FakeGraph(
            pageCountValue = 2,
            recognitionFailure = failure,
            recognitionStarted = started,
            recognitionGate = gate
        )
        val session = OcrSession(token(), graph)

        supervisorScope {
            val first = async { runCatching { session.pageOcr(0) }.exceptionOrNull() }
            started.await()
            val second = async { runCatching { session.pageOcr(1) }.exceptionOrNull() }
            yield()
            gate.complete(Unit)

            val outcomes = withTimeout(2_000) {
                listOf(
                    runCatching { first.await() }.getOrElse { it },
                    runCatching { second.await() }.getOrElse { it }
                )
            }
            assertTrue(outcomes.all { it === failure })
        }

        // A bare OcrSession does not own the registry lease cleanup path.
        // Its direct owner still performs the required cancel/join/close.
        assertEquals(0, graph.closeCalls)
        session.closeAndJoin()
        assertEquals(1, graph.closeCalls)
    }

    @Test
    fun registryLeases_failedOperationDoNotJoinSiblingLease_beforeExactEntryCleanup() = runTest {
        val q1Started = CompletableDeferred<Unit>()
        val allowQ1Failure = CompletableDeferred<Unit>()
        val failure = IllegalStateException("q1 recognition failed")
        val oldGraph = FakeGraph(
            pageCountValue = 2,
            recognitionFailure = failure,
            recognitionStarted = q1Started,
            recognitionGate = allowQ1Failure
        )
        val reboundGraph = FakeGraph(pageCountValue = 1)
        val graphs = ArrayDeque(listOf(oldGraph, reboundGraph))
        val factory = FakeFactory { graphs.removeFirst() }
        val registry = OcrSessionRegistry()
        val sessionToken = token()
        val q1Leased = CompletableDeferred<Unit>()
        val q2Leased = CompletableDeferred<Unit>()

        supervisorScope {
            val q1 = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    registry.withSession(sessionToken, factory) { session ->
                        q1Leased.complete(Unit)
                        session.pageOcr(0)
                    }
                }.exceptionOrNull()
            }
            q1Leased.await()
            q1Started.await()

            val q2 = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    registry.withSession(sessionToken, factory) { session ->
                        q2Leased.complete(Unit)
                        session.pageOcr(1)
                    }
                }.exceptionOrNull()
            }
            q2Leased.await()

            // q1 owns the serialized page operation; q2 owns a second lease
            // and is waiting on the same session mutex. Its cancellation must
            // unwind its lease, not recursively join q1's cleanup.
            allowQ1Failure.complete(Unit)
            val outcomes = withTimeout(2_000) {
                listOf(
                    runCatching { q1.await() }.getOrElse { it },
                    runCatching { q2.await() }.getOrElse { it }
                )
            }

            assertSame(failure, outcomes[0])
            assertTrue(outcomes[1] is CancellationException)
            assertEquals(1, factory.openCalls)
            assertEquals(1, oldGraph.closeCalls)

            // Failure cleanup retired only the old exact entry. A later
            // caller receives a fresh graph, which is closed once by the
            // terminal registry owner.
            registry.withSession(sessionToken, factory) { session ->
                assertNotSame(oldGraph, reboundGraph)
                session.pageOcr(0)
            }
            assertEquals(2, factory.openCalls)
            assertEquals(0, reboundGraph.closeCalls)
            registry.closeAndJoin()
            assertEquals(1, reboundGraph.closeCalls)
        }
    }

    @Test
    fun closeFailure_isSuppressedBehindPrimary_andReportedAfterSuccess() = runTest {
        val closeFailure = IllegalStateException("close failed")
        val successfulGraph = FakeGraph(pageCountValue = 1, closeFailure = closeFailure)
        val successfulObserved = runCatching {
            OcrSessionRunner(FakeFactory { successfulGraph }).run(token()) { Unit }
        }.exceptionOrNull()
        assertTrue(successfulObserved is IllegalStateException)
        assertEquals(closeFailure.message, successfulObserved?.message)
        assertEquals(1, successfulGraph.closeCalls)

        val primaryFailure = IllegalArgumentException("page failed")
        val failedGraph = FakeGraph(pageCountValue = 1, closeFailure = closeFailure)
        val failedObserved = runCatching {
            OcrSessionRunner(FakeFactory { failedGraph }).run(token()) {
                throw primaryFailure
            }
        }.exceptionOrNull()
        assertSame(primaryFailure, failedObserved)
        assertTrue(primaryFailure.suppressed.any { it.message == closeFailure.message })
        assertEquals(1, failedGraph.closeCalls)
    }

    @Test
    fun registryCloseAndJoin_cancelsActiveOwnerWork_andClosesExactlyOnce() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val graph = FakeGraph(
            pageCountValue = 1,
            recognitionStarted = started,
            recognitionGate = gate
        )
        val factory = FakeFactory { graph }
        val registry = OcrSessionRegistry()
        val token = token()
        val session = registry.getOrOpen(token, factory)
        val operation = async {
            session.pageOcr(0)
        }
        started.await()

        registry.closeAndJoin()
        val observed = runCatching { operation.await() }.exceptionOrNull()
        assertTrue(observed is CancellationException)
        assertEquals(1, graph.closeCalls)
        assertEquals(1, factory.openCalls)
    }

    @Test
    fun failedOrCanceledSession_neverPublishesPageOrFullDocumentCache() = runTest {
        val failedPageStore = linkedMapOf<String, PageOcr>()
        val failedMarkerStore = linkedMapOf<String, Any>()
        val failedAuthority = Stage7NamespaceCacheAuthority(failedPageStore, failedMarkerStore)
        val failure = IllegalStateException("failed page")
        val failedSession = OcrSession(
            token(),
            FakeGraph(pageCountValue = 1, operationFailure = failure)
        )
        val failedObserved = runCatching {
            failedAuthority.withNamespaceTransaction("failed") {
                val transactionContext = currentCoroutineContext()
                val page = failedSession.pageOcr(0)
                stagePageIfActive("failed|0", page) { transactionContext.ensureActive() }
                stageMarkerIfActive { transactionContext.ensureActive() }
            }
        }.exceptionOrNull()
        assertSame(failure, failedObserved)
        assertFalse(failedAuthority.hasPage("failed|0"))
        assertFalse(failedAuthority.isDocumentCached("failed"))

        val canceledPageStore = linkedMapOf<String, PageOcr>()
        val canceledMarkerStore = linkedMapOf<String, Any>()
        val canceledAuthority = Stage7NamespaceCacheAuthority(canceledPageStore, canceledMarkerStore)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val canceledSession = OcrSession(
            token(),
            FakeGraph(
                pageCountValue = 1,
                recognitionStarted = started,
                recognitionGate = gate
            )
        )
        val operation = async {
            canceledAuthority.withNamespaceTransaction("canceled") {
                val transactionContext = currentCoroutineContext()
                val page = canceledSession.pageOcr(0)
                stagePageIfActive("canceled|0", page) { transactionContext.ensureActive() }
                stageMarkerIfActive { transactionContext.ensureActive() }
            }
        }
        started.await()
        operation.cancel(CancellationException("canceled before cache commit"))
        val canceledObserved = runCatching { operation.await() }.exceptionOrNull()
        assertTrue(canceledObserved is CancellationException)
        assertFalse(canceledAuthority.hasPage("canceled|0"))
        assertFalse(canceledAuthority.isDocumentCached("canceled"))
    }

    @Test
    fun admissionRejectedAfterWorkerComputation_doesNotReturnAStalePage() = runTest {
        var current = true
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val session = OcrSession(
            token(),
            FakeGraph(
                pageCountValue = 1,
                recognitionStarted = started,
                recognitionGate = gate
            )
        )
        supervisorScope {
            val operation = async {
                session.pageOcr(0) { current }
            }
            started.await()
            current = false
            gate.complete(Unit)
            val observed = runCatching { operation.await() }.exceptionOrNull()

            assertTrue(observed is OcrSessionStaleException)
        }
    }

    @Test
    fun staleOperation_evictsItsGraph_withoutPoisoningTheCurrentToken() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val firstGraph = FakeGraph(
            pageCountValue = 1,
            recognitionStarted = firstStarted,
            recognitionGate = firstGate
        )
        val secondGraph = FakeGraph(pageCountValue = 1)
        val graphs = ArrayDeque(listOf(firstGraph, secondGraph))
        val factory = FakeFactory { graphs.removeFirst() }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val boundary = Stage7WorkerResourceBoundary(dispatcher, dispatcher)
        val index = OcrIndex(Application(), boundary, factory)
        val sessionToken = token()
        var current = true
        val namespace = "stale-reopen-${sessionToken.documentId.value}"

        val staleOperation = async {
            index.getPageOcr(
                token = sessionToken,
                pageIndex = 0,
                cacheNamespace = namespace,
                isCurrent = { current }
            )
        }
        firstStarted.await()
        current = false
        firstGate.complete(Unit)
        assertNull(staleOperation.await())
        assertEquals(1, firstGraph.closeCalls)

        current = true
        val reopened = index.getPageOcr(
            token = sessionToken,
            pageIndex = 0,
            cacheNamespace = namespace,
            isCurrent = { current }
        )
        assertEquals(1, reopened?.boxes?.size)
        assertEquals(2, factory.openCalls)
        index.closeAndJoin()
        assertEquals(1, secondGraph.closeCalls)
    }

    @Test
    fun ocrCloseAndJoin_dispatchesResourceClosureToWorker_andAwaitsBeforeReturn() = runTest {
        val delegate = StandardTestDispatcher(testScheduler)
        val worker = RecordingDispatcher(delegate)
        val main = StandardTestDispatcher(testScheduler)
        val boundary = Stage7WorkerResourceBoundary(worker, main)
        val graph = FakeGraph(pageCountValue = 1)
        val factory = FakeFactory { graph }
        val registry = boundary.ocrSessionRegistry
        val sessionToken = token()
        registry.getOrOpen(sessionToken, factory)
        val index = OcrIndex(Application(), boundary, factory)

        index.closeAndJoin()

        assertTrue(worker.dispatchCount > 0)
        assertEquals(1, graph.closeCalls)
    }

    @Test
    fun ocrEviction_dispatchesResourceClosureToWorker_andAwaitsBeforeReturn() = runTest {
        val delegate = StandardTestDispatcher(testScheduler)
        val worker = RecordingDispatcher(delegate)
        val boundary = Stage7WorkerResourceBoundary(worker, delegate)
        val graph = FakeGraph(pageCountValue = 1)
        val factory = FakeFactory { graph }
        val token = token()
        boundary.ocrSessionRegistry.getOrOpen(token, factory)
        val index = OcrIndex(Application(), boundary, factory)

        val eviction = async { index.evictSessionAndJoin(token) }
        runCurrent()
        eviction.await()

        assertTrue(worker.dispatchCount > 0)
        assertEquals(1, graph.closeCalls)
        index.closeAndJoin()
    }

    @Test
    fun lifecycleFinalizers_attemptEveryOwner_afterEarlierFailure_andRemainIdempotent() = runTest {
        val graph = FakeGraph(pageCountValue = 1)
        val registry = OcrSessionRegistry()
        registry.getOrOpen(token(), FakeFactory { graph })
        val syncFailure = IllegalStateException("sync close failed")
        val sessionFailure = IllegalArgumentException("session close failed")

        val observed = runCatching {
            runNonCancellableFinalizers(
                { throw syncFailure },
                { throw sessionFailure },
                { registry.closeAndJoin() }
            )
        }.exceptionOrNull()

        assertTrue(observed is IllegalStateException)
        assertEquals(syncFailure.message, observed?.message)
        assertTrue(observed?.suppressed?.any { it.message == sessionFailure.message } == true)
        assertEquals(1, graph.closeCalls)

        registry.closeAndJoin()
        assertEquals(1, graph.closeCalls)
    }

    private class RecordingDispatcher(
        private val delegate: CoroutineDispatcher
    ) : CoroutineDispatcher() {
        var dispatchCount: Int = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchCount++
            delegate.dispatch(context, block)
        }
    }

    private class FakeRecognitionTask<T>(
        private val value: T,
        private val failure: Throwable? = null,
        private val terminalFailure: Throwable? = null,
        private val events: MutableList<String>,
        private val started: CompletableDeferred<Unit>? = null,
        private val awaitGate: CompletableDeferred<Unit>? = null,
        private val terminalGate: CompletableDeferred<Unit>? = null
    ) : OcrRecognitionTask<T> {
        override suspend fun await(): T {
            events += "await"
            started?.complete(Unit)
            awaitGate?.await()
            return finish()
        }

        override suspend fun awaitTerminal(): T {
            events += "await-terminal"
            terminalGate?.await()
            terminalFailure?.let { throw it }
            return finish()
        }

        private fun finish(): T {
            failure?.let { throw it }
            return value
        }
    }

    private class FakeTransientOwner {
        var closeCalls: Int = 0

        fun close() {
            closeCalls++
        }
    }

    private fun token(): DocumentSessionToken = DocumentSessionToken(
        documentId = DocumentId.new(),
        sourceUri = "content://documents/${DocumentId.new()}",
        sourceFingerprint = SourceFingerprint.fromBytes("pdf".toByteArray()),
        generation = 1L
    )

    private class FakeFactory(private val create: () -> FakeGraph) : OcrSessionResourceFactory {
        var openCalls: Int = 0
        val graphs = mutableListOf<FakeGraph>()

        override suspend fun open(token: DocumentSessionToken): OcrSessionResourceGraph =
            create().also {
                openCalls++
                graphs += it
            }
    }

    private class FakeGraph(
        private val pageCountValue: Int,
        private val operationFailure: Throwable? = null,
        private val recognitionFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
        private val embeddedBoxes: List<OcrBox> = emptyList(),
        private val recognitionStarted: CompletableDeferred<Unit>? = null,
        private val recognitionGate: CompletableDeferred<Unit>? = null
    ) : OcrSessionResourceGraph {
        var pageCountCalls: Int = 0
        var recognizeCalls: Int = 0
        var closeCalls: Int = 0
        var maxConcurrentOperations: Int = 0
        private val activeOperations = AtomicInteger(0)
        private val closed = AtomicInteger(0)
        private val observedCloseFailure = AtomicReference<Throwable?>(null)

        override suspend fun pageCount(): Int = operation("page-count") {
            pageCountCalls++
            pageCountValue
        }

        override suspend fun extractEmbeddedText(pageIndex: Int): List<OcrBox> =
            operation("embedded-$pageIndex") {
                embeddedBoxes
            }

        override suspend fun recognizePage(pageIndex: Int): List<OcrBox> =
            operation("recognize-$pageIndex") {
                recognizeCalls++
                recognitionStarted?.complete(Unit)
                recognitionGate?.await()
                (recognitionFailure ?: operationFailure)?.let { throw it }
                listOf(OcrBox("page-$pageIndex", android.graphics.RectF(0f, 0f, 1f, 1f)))
            }

        private suspend fun <T> operation(name: String, block: suspend () -> T): T {
            val active = activeOperations.incrementAndGet()
            maxConcurrentOperations = max(maxConcurrentOperations, active)
            try {
                yield()
                return block()
            } finally {
                activeOperations.decrementAndGet()
            }
        }

        override fun close() {
            closeCalls++
            check(closed.incrementAndGet() == 1) { "graph closed more than once" }
            closeFailure?.let {
                observedCloseFailure.set(it)
                throw it
            }
        }
    }
}
