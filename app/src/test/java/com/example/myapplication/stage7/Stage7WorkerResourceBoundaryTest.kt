package com.example.myapplication.stage7

import android.app.Application
import com.example.myapplication.OcrBox
import com.example.myapplication.OcrIndex
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage3.DocumentSession
import com.example.myapplication.stage3.DocumentSessionCallbacks
import com.example.myapplication.stage3.DocumentSwitchCoordinator
import com.example.myapplication.stage3.ResolvedDocumentTarget
import com.example.myapplication.stage3.SessionLoadResult
import com.example.myapplication.stage3.SwitchFailure
import com.example.myapplication.stage3.SwitchFailureStage
import com.example.myapplication.stage3.SwitchResult
import com.example.myapplication.stage3.TargetResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Stage7WorkerResourceBoundaryTest {
    private val coordinators = mutableListOf<DocumentSwitchCoordinator>()

    @After
    fun closeCoordinators() {
        coordinators.forEach(DocumentSwitchCoordinator::close)
    }

    @Test
    fun injectedBoundary_runsBlockingLoadOnWorker_andPublicationOnMain() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val main = StandardTestDispatcher(testScheduler)
        val boundary = Stage7WorkerResourceBoundary(worker, main)
        var workerContext: ContinuationInterceptor? = null
        var mainContext: ContinuationInterceptor? = null
        var published = false

        val mainProbe = async {
            boundary.withMain {
                mainContext = currentCoroutineContext()[ContinuationInterceptor]
            }
        }

        val operation = async {
            boundary.computeAndPublish(
                compute = {
                    workerContext = currentCoroutineContext()[ContinuationInterceptor]
                    42
                },
                acceptsBeforeMain = { true },
                acceptsOnMain = { true },
                publish = { value ->
                    assertEquals(42, value)
                    published = true
                },
                reject = { error("a successful publication must not be rejected") }
            )
        }

        runCurrent()

        mainProbe.await()
        assertTrue(operation.await())
        assertSame(worker, workerContext)
        assertSame(main, mainContext)
        assertTrue(published)
    }

    @Test
    fun resourceOwner_releasesAliasesExactlyOnce_onClose() {
        data class Resource(val name: String)
        val released = mutableListOf<Resource>()
        val owner = Stage7ResourceOwner<Resource> { released += it }
        val original = Resource("original")
        val transformed = Resource("transformed")

        owner.owned(original)
        owner.owned(original)
        owner.owned(transformed)
        owner.close()
        owner.close()

        assertEquals(2, released.size)
        assertEquals(1, released.count { it === original })
        assertEquals(1, released.count { it === transformed })
    }

    @Test
    fun rejectedResource_isReleasedAfterSessionOrPageCheckFails() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val boundary = Stage7WorkerResourceBoundary(dispatcher, dispatcher)
        val released = mutableListOf<Any>()
        val owner = Stage7ResourceOwner<Any> { released += it }
        val resource = Any()
        val loaded = owner.owned(resource)
        var rejectCalls = 0
        var publishCalls = 0

        val accepted = boundary.computeAndPublish(
            compute = { loaded },
            acceptsBeforeMain = { false },
            publish = { publishCalls++ },
            reject = {
                rejectCalls++
                it.close()
            }
        )

        assertFalse(accepted)
        assertEquals(0, publishCalls)
        assertEquals(1, rejectCalls)
        assertEquals(listOf(resource), released)
    }

    @Test
    fun failedOrCanceledPublication_releasesResource_andPreservesFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val boundary = Stage7WorkerResourceBoundary(dispatcher, dispatcher)

        val failureReleased = mutableListOf<Any>()
        val failureOwner = Stage7ResourceOwner<Any> { failureReleased += it }
        val failureResource = Any()
        val failureLoaded = failureOwner.owned(failureResource)
        val failure = IllegalStateException("publication failed")
        var observedFailure: Throwable? = null
        try {
            boundary.computeAndPublish(
                compute = { failureLoaded },
                acceptsBeforeMain = { true },
                acceptsOnMain = { throw failure },
                publish = {},
                reject = { it.close() }
            )
        } catch (error: Throwable) {
            observedFailure = error
        }
        assertTrue(observedFailure is IllegalStateException)
        assertEquals(failure.message, observedFailure?.message)
        assertEquals(listOf(failureResource), failureReleased)

        val cancellationReleased = mutableListOf<Any>()
        val cancellationOwner = Stage7ResourceOwner<Any> { cancellationReleased += it }
        val cancellationResource = Any()
        val cancellationLoaded = cancellationOwner.owned(cancellationResource)
        val cancellation = CancellationException("publication canceled")
        var observedCancellation: Throwable? = null
        try {
            boundary.computeAndPublish(
                compute = { cancellationLoaded },
                acceptsBeforeMain = { throw cancellation },
                publish = {},
                reject = { it.close() }
            )
        } catch (error: Throwable) {
            observedCancellation = error
        }

        assertSame(cancellation, observedCancellation)
        assertEquals(listOf(cancellationResource), cancellationReleased)
    }

    @Test
    fun cancellationBetweenWorkerReturnAndMainPublication_rejectsRecordedResourceOnce() = runTest {
        val worker = QueueDispatcher()
        val main = QueueDispatcher()
        val boundary = Stage7WorkerResourceBoundary(worker, main)
        val released = mutableListOf<Any>()
        val owner = Stage7ResourceOwner<Any> { released += it }
        val resource = Any()
        val loaded = owner.owned(resource)
        val cancellation = CancellationException("canceled before Main publication")
        var publishCalls = 0
        var rejectCalls = 0

        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            boundary.computeAndPublish(
                compute = { loaded },
                acceptsBeforeMain = { true },
                acceptsOnMain = { true },
                publish = { publishCalls++ },
                reject = {
                    rejectCalls++
                    it.close()
                }
            )
        }

        // Drive the production seam through worker completion and into the
        // queued Main publication, then cancel before Main is allowed to run.
        worker.runAll()
        runCurrent()
        assertEquals(1, main.pendingCount())
        operation.cancel(cancellation)
        main.runAll()
        worker.runAll()
        runCurrent()
        worker.runAll()
        runCurrent()

        val observed = runCatching { operation.await() }.exceptionOrNull()
        assertTrue(observed is CancellationException)
        assertEquals(0, publishCalls)
        assertEquals(1, rejectCalls)
        assertEquals(listOf(resource), released)
    }

    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = java.util.ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(queue) { queue.addLast(block) }
        }

        fun pendingCount(): Int = synchronized(queue) { queue.size }

        fun runAll() {
            while (true) {
                val next = synchronized(queue) {
                    if (queue.isEmpty()) null else queue.removeFirst()
                } ?: return
                next.run()
            }
        }
    }

    @Test
    fun cancellationDuringPublication_commitsOwnershipWithoutSecondRelease() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val boundary = Stage7WorkerResourceBoundary(dispatcher, dispatcher)
        val released = mutableListOf<Any>()
        val owner = Stage7ResourceOwner<Any> { released += it }
        val resource = Any()
        val loaded = owner.owned(resource)
        val cancellation = CancellationException("canceled after UI publication")
        var published = false
        var rejectCalls = 0
        lateinit var operation: Deferred<Boolean>

        operation = async(start = CoroutineStart.UNDISPATCHED) {
            boundary.computeAndPublish(
                compute = { loaded },
                acceptsBeforeMain = { true },
                acceptsOnMain = { true },
                publish = {
                    published = true
                    // Cancellation is requested from the synchronous publish
                    // callback. The boundary must commit before the canceled
                    // Main context can unwind into cleanup.
                    operation.cancel(cancellation)
                },
                reject = {
                    rejectCalls++
                    it.close()
                }
            )
        }

        advanceUntilIdle()

        val observed = runCatching { operation.await() }.exceptionOrNull()
        assertTrue(observed is CancellationException)
        assertTrue(published)
        assertEquals(0, rejectCalls)
        assertTrue(released.isEmpty())
    }

    @Test
    fun ocrPageCacheCommit_cancellationBetweenChecksLeavesNoNewEntry() = runTest {
        val store = linkedMapOf<String, Any>()
        val existing = Any()
        store["existing"] = existing
        val cancellation = CancellationException("canceled before page cache commit")
        lateinit var operation: Deferred<Unit>
        val transaction = Stage7CacheCommitTransaction()
        val committer = Stage7CacheCommitter(store) {
            operation.cancel(cancellation)
        }

        operation = async(start = CoroutineStart.LAZY) {
            val cacheContext = currentCoroutineContext()
            try {
                committer.commitIfActive("new-page", Any()) {
                    cacheContext.ensureActive()
                }?.let(transaction::record)
                transaction.commit()
            } catch (cancelled: CancellationException) {
                transaction.rollback()
                throw cancelled
            }
        }
        operation.start()
        advanceUntilIdle()

        val observed = runCatching { operation.await() }.exceptionOrNull()
        assertTrue(observed is CancellationException)
        assertFalse(store.containsKey("new-page"))
        assertSame(existing, store["existing"])
    }

    @Test
    fun ocrFullDocumentCommit_cancellationAtMarkerRollsBackOnlyThisTransaction() = runTest {
        val pageStore = linkedMapOf<String, Any>()
        val markerStore = linkedMapOf<String, Any>()
        val existingPage = Any()
        val existingMarker = Any()
        pageStore["existing-page"] = existingPage
        markerStore["existing-document"] = existingMarker
        val cancellation = CancellationException("canceled at full-document cache commit")
        lateinit var operation: Deferred<Unit>
        val transaction = Stage7CacheCommitTransaction()
        val pageCommitter = Stage7CacheCommitter(pageStore)
        val markerCommitter = Stage7CacheCommitter(markerStore) {
            operation.cancel(cancellation)
        }

        operation = async(start = CoroutineStart.LAZY) {
            val cacheContext = currentCoroutineContext()
            try {
                pageCommitter.commitIfActive("new-page", Any()) {
                    cacheContext.ensureActive()
                }?.let(transaction::record)
                markerCommitter.commitIfActive("new-document", Any()) {
                    cacheContext.ensureActive()
                }?.let(transaction::record)
                transaction.commit()
            } catch (cancelled: CancellationException) {
                transaction.rollback()
                throw cancelled
            }
        }
        operation.start()
        advanceUntilIdle()

        val observed = runCatching { operation.await() }.exceptionOrNull()
        assertTrue(observed is CancellationException)
        assertFalse(pageStore.containsKey("new-page"))
        assertFalse(markerStore.containsKey("new-document"))
        assertSame(existingPage, pageStore["existing-page"])
        assertSame(existingMarker, markerStore["existing-document"])
    }

    @Test
    fun namespaceCache_canceledTransactionIsInvisibleAndDoesNotRemoveUnrelatedEntries() = runTest {
        val pageStore = linkedMapOf<String, String>()
        val markerStore = linkedMapOf<String, Any>()
        pageStore["document|existing"] = "existing"
        markerStore["other-document"] = Any()
        val authority = Stage7NamespaceCacheAuthority(pageStore, markerStore)
        val staged = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancellation = CancellationException("canceled namespace transaction")

        val first = async {
            authority.withNamespaceTransaction("document") {
                val transactionContext = currentCoroutineContext()
                stagePageIfActive("document|new", "first") {
                    transactionContext.ensureActive()
                }
                stageMarkerIfActive { transactionContext.ensureActive() }
                staged.complete(Unit)
                release.await()
            }
        }
        staged.await()

        // Staged values stay private until the transaction's one publication
        // section; synchronous readers must not see either value.
        assertNull(authority.page("document|new"))
        assertFalse(authority.isDocumentCached("document"))

        val second = async {
            authority.withNamespaceTransaction("document") {
                val pageBefore = page("document|new")
                val markerBefore = isDocumentCached()
                val transactionContext = currentCoroutineContext()
                stagePageIfActive("document|new", "second") {
                    transactionContext.ensureActive()
                }
                stageMarkerIfActive { transactionContext.ensureActive() }
                pageBefore to markerBefore
            }
        }
        runCurrent()
        assertFalse("the namespace transaction must exclude a concurrent operation", second.isCompleted)

        first.cancel(cancellation)
        val firstFailure = runCatching { first.await() }.exceptionOrNull()
        assertTrue(firstFailure is CancellationException)

        val secondObservation = second.await()
        assertNull(secondObservation.first)
        assertFalse(secondObservation.second)
        assertEquals("second", authority.page("document|new"))
        assertTrue(authority.isDocumentCached("document"))
        assertTrue(markerStore["document"] is Stage7FullDocumentIndexMarker)
        assertEquals("existing", pageStore["document|existing"])
        assertTrue(markerStore.containsKey("other-document"))
    }

    @Test
    fun namespaceCache_failedTransactionRollsBackPagesAndFullMarker() = runTest {
        val pageStore = linkedMapOf<String, String>()
        val markerStore = linkedMapOf<String, Any>()
        val authority = Stage7NamespaceCacheAuthority(pageStore, markerStore)
        val failure = IllegalStateException("page OCR failed")

        val observed = runCatching {
            authority.withNamespaceTransaction("failed-document") {
                val transactionContext = currentCoroutineContext()
                stagePageIfActive("failed-document|0", "partial") {
                    transactionContext.ensureActive()
                }
                stageMarkerIfActive { transactionContext.ensureActive() }
                throw failure
            }
        }.exceptionOrNull()

        assertSame(failure, observed)
        assertNull(authority.page("failed-document|0"))
        assertFalse(authority.isDocumentCached("failed-document"))
    }

    @Test
    fun namespaceCache_reclaimsLocksAfterHundredsOfFailedAndReadOnlyNamespaces() = runTest {
        val authority = Stage7NamespaceCacheAuthority(
            pageStore = linkedMapOf<String, String>(),
            markerStore = linkedMapOf<String, Any>()
        )
        var maximumRetainedLocks = 0

        repeat(512) { generation ->
            val observed = runCatching {
                authority.withNamespaceTransaction("failed-$generation") {
                    throw IllegalStateException("failed namespace $generation")
                }
            }.exceptionOrNull()
            assertTrue(observed is IllegalStateException)
            maximumRetainedLocks = maxOf(
                maximumRetainedLocks,
                authority.retainedNamespaceLockCount()
            )
            assertEquals(0, authority.activeNamespaceReservationCount())
        }

        repeat(512) { generation ->
            assertNull(authority.page("read-only-$generation|0"))
            assertFalse(authority.isDocumentCached("read-only-$generation"))
        }

        assertEquals(0, maximumRetainedLocks)
        assertEquals(0, authority.retainedNamespaceLockCount())
        assertEquals(0, authority.activeNamespaceReservationCount())

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            authority.withNamespaceTransaction("held") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            authority.withNamespaceTransaction("held") { Unit }
        }
        runCurrent()
        assertEquals(1, authority.retainedNamespaceLockCount())
        assertEquals(2, authority.activeNamespaceReservationCount())

        release.complete(Unit)
        owner.await()
        waiter.await()
        assertEquals(0, authority.retainedNamespaceLockCount())
        assertEquals(0, authority.activeNamespaceReservationCount())
    }

    @Test
    fun namespaceCache_publicationAdmissionRejectsStagedPageAndMarkerAfterInvalidation() = runTest {
        val pageStore = linkedMapOf<String, String>()
        val markerStore = linkedMapOf<String, Any>()
        var sessionAdmitted = true

        val authority = Stage7NamespaceCacheAuthority(pageStore, markerStore)
        val observed = runCatching {
            authority.withNamespaceTransaction(
                namespace = "document",
                publicationAdmission = {
                    if (!sessionAdmitted) {
                        throw OcrSessionStaleException("document session was invalidated before publication")
                    }
                }
            ) {
                stagePageIfActive("document|page", "staged-page") { check(sessionAdmitted) }
                stageMarkerIfActive { check(sessionAdmitted) }

                // Model DocumentSwitchCoordinator invalidating the token after
                // the final staging step but before the locked publication.
                sessionAdmitted = false
            }
        }.exceptionOrNull()

        assertTrue(observed is OcrSessionStaleException)
        assertFalse(pageStore.containsKey("document|page"))
        assertFalse(markerStore.containsKey("document"))
    }

    @Test
    fun namespaceCache_publicationAndInvalidation_shareOneLinearizationFence() = runTest {
        val pageStore = linkedMapOf<String, String>()
        val markerStore = linkedMapOf<String, Any>()
        val fence = Stage7PublicationFence()
        val publicationEntered = CompletableDeferred<Unit>()
        val releasePublication = CountDownLatch(1)
        val order = mutableListOf<String>()
        val orderLock = Any()
        val authority = Stage7NamespaceCacheAuthority(
            pageStore = pageStore,
            markerStore = markerStore,
            beforeCommit = {
                if (!publicationEntered.isCompleted) {
                    publicationEntered.complete(Unit)
                    // This gate is held by the worker-side publication only;
                    // the invalidation caller must suspend on the shared
                    // fence rather than block Main.
                    releasePublication.await()
                }
            },
            publicationFence = fence
        )
        var sessionAdmitted = true
        val publication = async(Dispatchers.Default) {
            authority.withNamespaceTransaction(
                namespace = "document",
                publicationAdmission = { check(sessionAdmitted) }
            ) {
                stagePageIfActive("document|page", "page") { check(sessionAdmitted) }
                stageMarkerIfActive { check(sessionAdmitted) }
            }
            synchronized(orderLock) { order += "publication" }
        }

        publicationEntered.await()
        val invalidationStarted = CompletableDeferred<Unit>()
        val invalidation = async(Dispatchers.Default) {
            invalidationStarted.complete(Unit)
            fence.withInvalidation {
                sessionAdmitted = false
                synchronized(orderLock) { order += "invalidation" }
            }
        }
        invalidationStarted.await()
        // The worker holds the publication permit, so the invalidation cannot
        // fence the session halfway through the final map mutations.
        assertFalse(invalidation.isCompleted)

        releasePublication.countDown()
        publication.await()
        invalidation.await()

        assertEquals(listOf("publication", "invalidation"), order)
        assertTrue(pageStore.containsKey("document|page"))
        assertTrue(markerStore.containsKey("document"))
    }

    @Test
    fun ocrIndexAndCoordinator_shareInjectedFence_throughRealCachePublicationRoute() = runTest {
        val fence = Stage7PublicationFence()
        val publicationEntered = CompletableDeferred<Unit>()
        val releasePublication = CountDownLatch(1)
        val boundary = Stage7WorkerResourceBoundary(
            workerDispatcher = Dispatchers.Default,
            mainDispatcher = Dispatchers.Default,
            publicationFence = fence
        )
        val graph = EndToEndGraph()
        val index = OcrIndex(
            Application(),
            boundary,
            OcrSessionResourceFactory { graph },
            cachePublicationHook = {
                if (publicationEntered.complete(Unit)) releasePublication.await()
            }
        )
        val host = TokenHost()
        val coordinatorDispatcher = UnconfinedTestDispatcher(testScheduler)
        val coordinator = DocumentSwitchCoordinator(
            callbacks = host,
            parentScope = CoroutineScope(SupervisorJob() + coordinatorDispatcher),
            debounceMillis = 0L,
            coordinatorDispatcher = coordinatorDispatcher,
            publicationFence = fence
        )
        coordinators += coordinator

        val switched = async { coordinator.switchTo("A") }
        advanceUntilIdle()
        assertTrue(switched.await() is SwitchResult.Switched)
        val token = coordinator.currentSession()!!.token
        val namespace = "injected-fence-${token.documentId.value}"

        val publication = async(Dispatchers.Default) {
            index.preCacheDocument(
                token = token,
                cacheNamespace = namespace,
                isCurrent = { coordinator.isCurrent(token) }
            )
        }
        publicationEntered.await()

        // The close starts at the exact final-publication boundary. It must
        // suspend on the same injected fence rather than invalidate the token
        // through an unrelated global cache lock.
        val close = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.closeAndJoin()
        }
        assertFalse(close.isCompleted)
        assertFalse(index.isDocumentCached(token, namespace))

        releasePublication.countDown()
        assertTrue(publication.await())
        close.await()

        // Publication linearized before invalidation; the marker is valid,
        // while any later active route is rejected by the closed coordinator.
        assertTrue(index.isDocumentCached(token, namespace))
        index.closeAndJoin()
        assertEquals(1, graph.closeCalls)
    }

    @Test
    fun namespaceCache_publishCancellationRestoresAccessOrderLruState() = runTest {
        val pageStore = object : LinkedHashMap<String, String>(4, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, String>?
            ): Boolean = size > 2
        }
        val markerStore = linkedMapOf<String, Any>()
        pageStore["document|old-a"] = "A"
        pageStore["document|old-b"] = "B"
        pageStore["document|old-a"]
        markerStore["other-document"] = Any()
        val originalEntries = pageStore.entries.map { it.key to it.value }
        val originalMarkers = markerStore.entries.toList()
        val cancellation = CancellationException("canceled during LRU publication")
        lateinit var operation: Deferred<Unit>
        var commitCalls = 0
        val authority = Stage7NamespaceCacheAuthority(
            pageStore = pageStore,
            markerStore = markerStore,
            beforeCommit = {
                commitCalls++
                if (commitCalls == 2) operation.cancel(cancellation)
            }
        )

        operation = async(start = CoroutineStart.LAZY) {
            authority.withNamespaceTransaction("document") {
                val transactionContext = currentCoroutineContext()
                stagePageIfActive("document|new-a", "new-a") {
                    transactionContext.ensureActive()
                }
                stagePageIfActive("document|new-b", "new-b") {
                    transactionContext.ensureActive()
                }
                stageMarkerIfActive { transactionContext.ensureActive() }
            }
        }
        operation.start()
        advanceUntilIdle()

        val observed = runCatching { operation.await() }.exceptionOrNull()
        assertTrue(observed is CancellationException)
        assertEquals(originalEntries, pageStore.entries.map { it.key to it.value })
        assertEquals(originalMarkers, markerStore.entries.toList())
        assertFalse(pageStore.containsKey("document|new-a"))
        assertFalse(pageStore.containsKey("document|new-b"))
        assertFalse(markerStore.containsKey("document"))
    }

    @Test
    fun namespaceCache_publishFailureRestoresEvictedLruEntryAndOrder() = runTest {
        val pageStore = object : LinkedHashMap<String, String>(4, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, String>?
            ): Boolean = size > 2
        }
        val markerStore = linkedMapOf<String, Any>()
        pageStore["document|old-a"] = "A"
        pageStore["document|old-b"] = "B"
        pageStore["document|old-a"]
        val originalEntries = pageStore.entries.map { it.key to it.value }
        val failure = IllegalStateException("publication failed")
        var commitCalls = 0
        val authority = Stage7NamespaceCacheAuthority(
            pageStore = pageStore,
            markerStore = markerStore,
            beforeCommit = {
                commitCalls++
                if (commitCalls == 2) throw failure
            }
        )

        val operation = async {
            runCatching {
                authority.withNamespaceTransaction("document") {
                    val transactionContext = currentCoroutineContext()
                    stagePageIfActive("document|new-a", "new-a") {
                        transactionContext.ensureActive()
                    }
                    stagePageIfActive("document|new-b", "new-b") {
                        transactionContext.ensureActive()
                    }
                    stageMarkerIfActive { transactionContext.ensureActive() }
                }
            }.exceptionOrNull()
        }

        val observed = operation.await()
        assertSame(failure, observed)
        assertEquals(originalEntries, pageStore.entries.map { it.key to it.value })
        assertFalse(pageStore.containsKey("document|new-a"))
        assertFalse(pageStore.containsKey("document|new-b"))
        assertFalse(markerStore.containsKey("document"))
    }

    @Test
    fun namespaceCache_compatibilityMarkerIsFencedAcrossReservationLifecycle() = runTest {
        val pageStore = linkedMapOf<String, String>()
        val markerStore = linkedMapOf<String, Any>()
        val staged = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val retryEntered = CompletableDeferred<Unit>()
        val retryRelease = CompletableDeferred<Unit>()
        val finalizationBoundary = CompletableDeferred<Unit>()
        val cancellation = CancellationException("canceled before retry")
        lateinit var authority: Stage7NamespaceCacheAuthority<String>
        authority = Stage7NamespaceCacheAuthority(
            pageStore = pageStore,
            markerStore = markerStore,
            beforeReservationRelease = {
                // This callback runs after Mutex.unlock but before the
                // canceled operation releases its reservation.
                authority.markDocumentCached("document")
                finalizationBoundary.complete(Unit)
            }
        )

        val first = async {
            authority.withNamespaceTransaction("document") {
                val transactionContext = currentCoroutineContext()
                stagePageIfActive("document|first", "first") {
                    transactionContext.ensureActive()
                }
                stageMarkerIfActive { transactionContext.ensureActive() }
                staged.complete(Unit)
                hold.await()
            }
        }
        staged.await()
        authority.markDocumentCached("document")
        assertFalse(markerStore.containsKey("document"))

        val retry = async {
            authority.withNamespaceTransaction("document") {
                retryEntered.complete(Unit)
                retryRelease.await()
                val transactionContext = currentCoroutineContext()
                stagePageIfActive("document|retry", "retry") {
                    transactionContext.ensureActive()
                }
                stageMarkerIfActive { transactionContext.ensureActive() }
            }
        }
        runCurrent()
        assertFalse(retryEntered.isCompleted)
        authority.markDocumentCached("document")
        assertFalse(markerStore.containsKey("document"))

        first.cancel(cancellation)
        finalizationBoundary.await()
        assertFalse(markerStore.containsKey("document"))
        val firstFailure = runCatching { first.await() }.exceptionOrNull()
        assertTrue(firstFailure is CancellationException)

        retryRelease.complete(Unit)
        retry.await()
        assertEquals("retry", authority.page("document|retry"))
        assertTrue(authority.isDocumentCached("document"))
    }

    @Test
    fun stage3Token_checksRejectStalePage_andStaleSessionBeforePublication() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val host = TokenHost()
        val coordinator = DocumentSwitchCoordinator(
            callbacks = host,
            parentScope = CoroutineScope(SupervisorJob() + dispatcher),
            debounceMillis = 0L,
            coordinatorDispatcher = dispatcher
        )
        coordinators += coordinator

        val firstSwitch = async { coordinator.switchTo("A") }
        advanceUntilIdle()
        assertTrue(firstSwitch.await() is SwitchResult.Switched)
        val tokenA = coordinator.currentSession()!!.token
        val pageWork = coordinator.workToken(pageIndex = 0)

        val pageDispatcher = StandardTestDispatcher(testScheduler)
        val pageBoundary = Stage7WorkerResourceBoundary(pageDispatcher, pageDispatcher)
        val pageOwner = Stage7ResourceOwner<Any> { }
        val pageLoaded = pageOwner.owned(Any())
        var pageRejected = false
        val pageAccepted = pageBoundary.computeAndPublish(
            compute = { pageLoaded },
            acceptsBeforeMain = { coordinator.accepts(pageWork, currentPageIndex = 0) },
            acceptsOnMain = { coordinator.accepts(pageWork, currentPageIndex = 1) },
            publish = { error("stale page work must not publish") },
            reject = {
                pageRejected = true
                it.close()
            }
        )
        assertFalse(pageAccepted)
        assertTrue(pageRejected)

        val queryWork = coordinator.workToken(queryRevision = 1L)
        val queryOwner = Stage7ResourceOwner<Any> { }
        val queryLoaded = queryOwner.owned(Any())
        var queryPublished = false
        var queryRejected = false
        val queryAccepted = pageBoundary.computeAndPublish(
            compute = { queryLoaded },
            acceptsBeforeMain = {
                coordinator.accepts(queryWork, currentQueryRevision = 1L)
            },
            acceptsOnMain = {
                coordinator.accepts(queryWork, currentQueryRevision = 2L)
            },
            publish = { queryPublished = true },
            reject = {
                queryRejected = true
                it.close()
            }
        )
        assertFalse(queryAccepted)
        assertFalse(queryPublished)
        assertTrue(queryRejected)

        val gate = CompletableDeferred<Unit>()
        val sessionOwner = Stage7ResourceOwner<Any> { }
        val sessionLoaded = sessionOwner.owned(Any())
        var sessionPublished = false
        var sessionRejected = false
        val inFlight = async {
            pageBoundary.computeAndPublish(
                compute = {
                    gate.await()
                    sessionLoaded
                },
                acceptsBeforeMain = { coordinator.isCurrent(tokenA) },
                acceptsOnMain = { coordinator.accepts(pageWork, currentPageIndex = 0) },
                publish = { sessionPublished = true },
                reject = {
                    sessionRejected = true
                    it.close()
                }
            )
        }
        runCurrent()

        val secondSwitch = async { coordinator.switchTo("B") }
        advanceUntilIdle()
        assertTrue(secondSwitch.await() is SwitchResult.Switched)
        assertFalse(coordinator.isCurrent(tokenA))

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(inFlight.await())
        assertFalse(sessionPublished)
        assertTrue(sessionRejected)

        val closeOwner = Stage7ResourceOwner<Any> { }
        val closeLoaded = closeOwner.owned(Any())
        var closePublished = false
        var closeRejected = false
        val closeAccepted = pageBoundary.computeAndPublish(
            compute = { closeLoaded },
            acceptsBeforeMain = {
                // Exercise the exact worker-result/Main-publication gap: the
                // owner is closed after computation but before Main admission.
                coordinator.close()
                true
            },
            acceptsOnMain = { coordinator.accepts(pageWork, currentPageIndex = 0) },
            publish = { closePublished = true },
            reject = {
                closeRejected = true
                it.close()
            }
        )
        assertFalse(closeAccepted)
        assertFalse(closePublished)
        assertTrue(closeRejected)
    }

    private class TokenHost : DocumentSessionCallbacks {
        private val targets = listOf("A", "B").associateWith { uri ->
            val source = DocumentSourceIdentityV1(uri, uri)
            ResolvedDocumentTarget(
                DocumentAssociation(
                    documentId = DocumentId.new(),
                    source = source,
                    sourceFingerprint = SourceFingerprint.fromBytes(uri.toByteArray()),
                    legacyArtifactName = "legacy-$uri"
                )
            )
        }

        override suspend fun resolveTarget(sourceUri: String): TargetResolution =
            targets[sourceUri]?.let(TargetResolution::Resolved)
                ?: TargetResolution.Failed(
                    SwitchFailure(SwitchFailureStage.RESOLVE_TARGET, "unknown target")
                )

        override fun captureSnapshot(session: DocumentSession): DocumentSnapshotV1 =
            DocumentSnapshotV1(
                schemaVersion = 1,
                snapshotRevision = 0,
                source = session.target.association.source,
                pages = emptyMap()
            )

        override suspend fun saveSnapshot(
            session: DocumentSession,
            frozenSnapshot: DocumentSnapshotV1
        ): DocumentSaveResult = DocumentSaveResult.Saved(session.token.documentId)

        override suspend fun cancelAndJoinDocumentWork(session: DocumentSession) = Unit

        override fun invalidateDocumentWork(session: DocumentSession) = Unit

        override fun clearDocumentState() = Unit

        override fun establishSession(session: DocumentSession) = Unit

        override suspend fun loadTarget(session: DocumentSession): SessionLoadResult =
            SessionLoadResult.Empty(pageCount = 1)

        override fun applyLoadedSnapshot(session: DocumentSession, snapshot: DocumentSnapshotV1) = Unit
    }

    private class EndToEndGraph : OcrSessionResourceGraph {
        var closeCalls: Int = 0

        override suspend fun pageCount(): Int = 1

        override suspend fun extractEmbeddedText(pageIndex: Int): List<OcrBox> = emptyList()

        override suspend fun recognizePage(pageIndex: Int): List<OcrBox> = listOf(
            OcrBox("ocr-page-$pageIndex", android.graphics.RectF(0f, 0f, 1f, 1f))
        )

        override fun close() {
            closeCalls++
        }
    }
}
