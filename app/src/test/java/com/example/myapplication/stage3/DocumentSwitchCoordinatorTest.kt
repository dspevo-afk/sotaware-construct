package com.example.myapplication.stage3

import com.example.myapplication.acceptsCurrentPageSearchWork
import com.example.myapplication.clearSearchProgressIfOwned
import com.example.myapplication.runDocumentWorkCleanupFinalizer
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.DrawnPathSnapshotV1
import com.example.myapplication.stage1.MeasurementSnapshotV1
import com.example.myapplication.stage1.NoteSnapshotV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PageScaleSnapshotV1
import com.example.myapplication.stage1.PhotoImageNoteSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage1.PointSnapshotV1
import com.example.myapplication.stage1.ShapeSnapshotV1
import com.example.myapplication.stage1.SnapshotShapeTypeV1
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.LocalRepositoryError
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage7.OcrSessionRegistry
import com.example.myapplication.stage7.OcrSessionResourceFactory
import com.example.myapplication.stage7.OcrSessionResourceGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun DocumentSnapshotV1.marker(): String = pages.getValue(0).notes.single().text

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentSwitchCoordinatorTest {
    private val coordinators = mutableListOf<DocumentSwitchCoordinator>()

    @After
    fun closeCoordinators() {
        coordinators.forEach(DocumentSwitchCoordinator::close)
    }

    @Test
    fun switch_A_to_B_savesFrozenA_andLoadsBOnce() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        val b = host.addTarget("B", "B")
        host.snapshots[a.association.documentId] = snapshot(a, "A-loaded")
        host.snapshots[b.association.documentId] = snapshot(b, "B-loaded")
        val coordinator = coordinator(host, dispatcher)

        assertTrue(switch(coordinator, "A", scheduler) is SwitchResult.Switched)
        host.liveSnapshot = snapshot(a, "A-edit")
        val result = switch(coordinator, "B", scheduler)

        assertTrue(result is SwitchResult.Switched)
        assertEquals(snapshot(a, "A-edit"), host.savedSnapshots.single())
        assertEquals(1, host.loadCount("B"))
        assertEquals("B-loaded", host.liveSnapshot.marker())
        assertEquals(b.association.documentId, coordinator.currentSession()?.token?.documentId)
    }

    @Test
    fun canonicalSnapshot_roundTripsScaleShapesPhotosImageNotesPathsMeasurementsAndNotes() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        val b = host.addTarget("B", "B")
        val full = DocumentSnapshotV1(
            schemaVersion = 1,
            snapshotRevision = 7,
            source = a.association.source,
            pages = mapOf(
                0 to PageSnapshotV1(
                    paths = listOf(DrawnPathSnapshotV1(listOf(PointSnapshotV1(1f, 2f), PointSnapshotV1(3f, 4f)), 0xFF0000, 2f, false)),
                    measurements = listOf(MeasurementSnapshotV1(PointSnapshotV1(5f, 6f), PointSnapshotV1(7f, 8f), "12'")),
                    notes = listOf(NoteSnapshotV1(9f, 10f, "A-full", 14f, true, 15f)),
                    photoPins = listOf(
                        PhotoPinSnapshotV1(
                            x = 0.2f,
                            y = 0.3f,
                            id = "pin-a",
                            imageFileNames = listOf("photo-a.jpg"),
                            imageNotes = mapOf("photo-a.jpg" to listOf(PhotoImageNoteSnapshotV1(0.4f, 0.5f, "image note", 16f, false, 2f, 0.02f, "image-note-a"))),
                            imageShapes = mapOf("photo-a.jpg" to listOf(ShapeSnapshotV1(0.6f, 0.7f, 0.1f, 0.2f, 3f, SnapshotShapeTypeV1.CIRCLE, 0x00FF00, 4f, false, 0.01f, 0.1f, 0.2f, "image-shape-a")))
                        )
                    ),
                    scale = PageScaleSnapshotV1(42f),
                    shapes = listOf(ShapeSnapshotV1(11f, 12f, 13f, 14f, 5f, SnapshotShapeTypeV1.RECTANGLE, 0x0000FF, 3f, false, 0.01f, 0.2f, 0.3f, "shape-a"))
                )
            )
        )
        host.snapshots[a.association.documentId] = full
        host.snapshots[b.association.documentId] = snapshot(b, "B")
        val coordinator = coordinator(host, dispatcher)

        switch(coordinator, "A", scheduler)
        switch(coordinator, "B", scheduler)
        switch(coordinator, "A", scheduler)

        assertEquals(full, host.liveSnapshot)
    }

    @Test
    fun rapid_A_to_B_to_A_preservesBothDocuments_andGenerationsDiffer() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        val b = host.addTarget("B", "B")
        host.snapshots[a.association.documentId] = snapshot(a, "A-original")
        host.snapshots[b.association.documentId] = snapshot(b, "B-original")
        val coordinator = coordinator(host, dispatcher)

        switch(coordinator, "A", scheduler)
        val firstA = coordinator.currentSession()!!
        host.liveSnapshot = snapshot(a, "A-edit")
        switch(coordinator, "B", scheduler)
        val bSession = coordinator.currentSession()!!
        host.liveSnapshot = snapshot(b, "B-edit")
        switch(coordinator, "A", scheduler)
        val secondA = coordinator.currentSession()!!

        assertEquals(firstA.token.documentId, secondA.token.documentId)
        assertNotEquals(firstA.token.generation, secondA.token.generation)
        assertEquals("A-edit", host.liveSnapshot.marker())
        assertEquals(snapshot(a, "A-edit"), host.savedSnapshots.first { it.marker() == "A-edit" })
        assertEquals(snapshot(b, "B-edit"), host.savedSnapshots.first { it.marker() == "B-edit" })
        assertFalse(coordinator.isCurrent(firstA.token))
        assertFalse(coordinator.isCurrent(bSession.token))
    }

    @Test
    fun delayed_targetB_isCancelledAndCannotApplyAfterC_becomesCurrent() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        val b = host.addTarget("B", "B")
        val c = host.addTarget("C", "C")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        host.snapshots[b.association.documentId] = snapshot(b, "B")
        host.snapshots[c.association.documentId] = snapshot(c, "C")
        host.loadGates[b.association.documentId] = CompletableDeferred()
        val coordinator = coordinator(host, dispatcher)

        switch(coordinator, "A", scheduler)
        val bRequest = async { coordinator.switchTo("B") }
        scheduler.runCurrent()
        val cRequest = async { coordinator.switchTo("C") }
        scheduler.runCurrent()
        advanceUntilIdle()

        assertTrue(cRequest.await() is SwitchResult.Switched)
        assertTrue(bRequest.await() is SwitchResult.Superseded)
        assertEquals("C", host.liveSnapshot.marker())
        assertEquals(0, host.appliedMarkers.count { it == "B" })
        assertEquals(0, host.savedSnapshots.count { it.source.sourceUri == "B" })
        assertEquals("B", host.snapshots.getValue(b.association.documentId).marker())
    }

    @Test
    fun outgoingSaveReceivesFrozenA_evenIfLiveStateChangesWhileRepositoryIsDelayed() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        val b = host.addTarget("B", "B")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        host.snapshots[b.association.documentId] = snapshot(b, "B")
        host.saveGate = CompletableDeferred()
        val coordinator = coordinator(host, dispatcher)

        switch(coordinator, "A", scheduler)
        host.liveSnapshot = snapshot(a, "A-before-save")
        val switching = async { coordinator.switchTo("B") }
        scheduler.runCurrent()
        assertEquals(snapshot(a, "A-before-save"), host.saveStartedSnapshot)
        host.liveSnapshot = snapshot(a, "B-wrong-live-state")
        host.saveGate!!.complete(Unit)
        scheduler.runCurrent()
        assertTrue(switching.await() is SwitchResult.Switched)
        assertEquals(snapshot(a, "A-before-save"), host.savedSnapshots.single())
        assertFalse(host.savedSnapshots.any { it.marker() == "B-wrong-live-state" })
    }

    @Test
    fun outgoingSaveFailure_preservesActiveA_andDoesNotLoadB() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        host.addTarget("B", "B")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        val coordinator = coordinator(host, dispatcher)
        switch(coordinator, "A", scheduler)
        host.liveSnapshot = snapshot(a, "A-recoverable-edit")
        host.saveFailure = LocalRepositoryError.CommitUncertain("save", null, "test uncertainty")

        val result = switch(coordinator, "B", scheduler)

        assertTrue(result is SwitchResult.Failed)
        assertEquals(SwitchFailureStage.OUTGOING_FLUSH, (result as SwitchResult.Failed).failure.stage)
        assertEquals(a.association.documentId, coordinator.currentSession()?.token?.documentId)
        assertEquals("A-recoverable-edit", host.liveSnapshot.marker())
        assertEquals(0, host.loadCount("B"))
        assertTrue(host.failures.any { it.repositoryError is LocalRepositoryError.CommitUncertain })
    }

    @Test
    fun targetLoadFailure_rollsBackToSavedOutgoingSession() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        val b = host.addTarget("B", "B")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        host.loadFailures[b.association.documentId] = DocumentLoadFailure("corrupt target", LocalRepositoryError.CorruptSnapshot("B", false, "bad"))
        val coordinator = coordinator(host, dispatcher)
        switch(coordinator, "A", scheduler)
        host.liveSnapshot = snapshot(a, "A-edit")

        val result = switch(coordinator, "B", scheduler)

        assertTrue(result is SwitchResult.Failed)
        assertEquals(a.association.documentId, coordinator.currentSession()?.token?.documentId)
        assertEquals("A-edit", host.liveSnapshot.marker())
        assertEquals(2, host.establishedSessions.count { it.token.documentId == a.association.documentId })
        assertTrue(host.failures.any { it.stage == SwitchFailureStage.TARGET_LOAD })
    }

    @Test
    fun selectingSameDocumentAgain_isNoOp_andDoesNotLoadTwice() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        val coordinator = coordinator(host, dispatcher)
        switch(coordinator, "A", scheduler)
        val first = coordinator.currentSession()!!

        val result = switch(coordinator, "A", scheduler)

        assertTrue(result is SwitchResult.AlreadyActive)
        assertEquals(1, host.loadCount("A"))
        assertSame(first, (result as SwitchResult.AlreadyActive).session)
    }

    @Test
    fun staleWorkRequiresDocumentGeneration_page_andQueryRevision() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        val b = host.addTarget("B", "B")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        host.snapshots[b.association.documentId] = snapshot(b, "B")
        val coordinator = coordinator(host, dispatcher)
        switch(coordinator, "A", scheduler)
        val firstA = coordinator.currentSession()!!
        val oldWork = DocumentWorkToken(firstA.token, pageIndex = 2, queryRevision = 10)
        switch(coordinator, "B", scheduler)
        switch(coordinator, "A", scheduler)
        val secondA = coordinator.currentSession()!!

        assertFalse(coordinator.accepts(oldWork, currentPageIndex = 2, currentQueryRevision = 10))
        assertTrue(coordinator.accepts(DocumentWorkToken(secondA.token, 2, 10), 2, 10))
        assertFalse(coordinator.accepts(DocumentWorkToken(secondA.token, 2, 10), 1, 10))
        assertFalse(coordinator.accepts(DocumentWorkToken(secondA.token, 2, 10), 2, 11))
    }

    @Test
    fun currentPageSearchAdmission_readsLivePage_beforeWorkerOrMainPublication() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        val coordinator = coordinator(host, dispatcher)
        assertTrue(switch(coordinator, "A", testScheduler) is SwitchResult.Switched)

        var selectedPageIndex = 2
        val queryRevision = 41L
        val workToken = DocumentWorkToken(
            session = coordinator.currentSession()!!.token,
            pageIndex = selectedPageIndex,
            queryRevision = queryRevision
        )
        val accepts = {
            acceptsCurrentPageSearchWork(
                coordinator = coordinator,
                candidate = workToken,
                currentPageIndex = { selectedPageIndex },
                queryRevision = queryRevision
            )
        }
        assertTrue(accepts())

        val searchStarted = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()
        var progressPublished = false
        val publishedHighlights = mutableMapOf<Int, List<String>>()
        var completionShown = false
        val inFlight = async(start = CoroutineStart.UNDISPATCHED) {
            searchStarted.complete(Unit)
            allowCompletion.await()
            if (accepts()) progressPublished = true
            if (accepts()) {
                publishedHighlights[workToken.pageIndex!!] = listOf("stale-result")
                completionShown = true
            }
        }
        searchStarted.await()

        // This is the live selected page changing while the captured page-N
        // request is still in flight. Its range/token remains page N, but
        // both worker progress and final publication must now be rejected.
        selectedPageIndex = 3
        allowCompletion.complete(Unit)
        inFlight.await()

        assertFalse(accepts())
        assertFalse(progressPublished)
        assertTrue(publishedHighlights.isEmpty())
        assertFalse(completionShown)
    }

    @Test
    fun currentPageSearchAdmission_rejectsOlderQueryAfterSamePageReplacement() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        val coordinator = coordinator(host, dispatcher)
        assertTrue(switch(coordinator, "A", testScheduler) is SwitchResult.Switched)

        val selectedPageIndex = 2
        var liveQueryRevision = 41L
        val sessionToken = coordinator.currentSession()!!.token
        val q1 = DocumentWorkToken(sessionToken, pageIndex = selectedPageIndex, queryRevision = 41L)
        val accepts = { candidate: DocumentWorkToken ->
            acceptsCurrentPageSearchWork(
                coordinator = coordinator,
                candidate = candidate,
                currentPageIndex = { selectedPageIndex },
                queryRevision = { liveQueryRevision }
            )
        }
        assertTrue(accepts(q1))

        var searching = true
        var activeRequestRevision = q1.queryRevision!!
        var q1ProgressPublished = false
        var q1HighlightsPublished = false
        var q1CompletionDialogShown = false
        val q1Started = CompletableDeferred<Unit>()
        val allowQ1Publication = CompletableDeferred<Unit>()
        val inFlightQ1 = async(start = CoroutineStart.UNDISPATCHED) {
            q1Started.complete(Unit)
            allowQ1Publication.await()
            if (accepts(q1)) q1ProgressPublished = true
            if (accepts(q1)) {
                q1HighlightsPublished = true
                q1CompletionDialogShown = true
            }
        }
        q1Started.await()

        // q2 replaces q1 without changing the document or selected page.
        liveQueryRevision = 42L
        val q2 = DocumentWorkToken(sessionToken, pageIndex = selectedPageIndex, queryRevision = 42L)
        activeRequestRevision = q2.queryRevision!!
        assertFalse(accepts(q1))
        assertTrue(accepts(q2))

        allowQ1Publication.complete(Unit)
        inFlightQ1.await()
        assertFalse(q1ProgressPublished)
        assertFalse(q1HighlightsPublished)
        assertFalse(q1CompletionDialogShown)
        assertTrue(searching)

        // Cleanup from canceled q1 must not clear q2's progress ownership.
        assertFalse(
            clearSearchProgressIfOwned(activeRequestRevision, q1.queryRevision!!) {
                searching = false
            }
        )
        assertTrue(searching)
        assertTrue(
            clearSearchProgressIfOwned(activeRequestRevision, q2.queryRevision!!) {
                searching = false
                activeRequestRevision = 0L
            }
        )
        assertFalse(searching)
        assertEquals(0L, activeRequestRevision)
    }

    @Test
    fun currentPageSearch_pageChange_clearsOnlyTheCanceledRequestProgress() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        val coordinator = coordinator(host, dispatcher)
        assertTrue(switch(coordinator, "A", testScheduler) is SwitchResult.Switched)

        var selectedPageIndex = 2
        val queryRevision = 41L
        val workToken = DocumentWorkToken(
            session = coordinator.currentSession()!!.token,
            pageIndex = selectedPageIndex,
            queryRevision = queryRevision
        )
        val accepts = {
            acceptsCurrentPageSearchWork(
                coordinator = coordinator,
                candidate = workToken,
                currentPageIndex = { selectedPageIndex },
                queryRevision = queryRevision
            )
        }
        var searching = true
        var activeRequestRevision = queryRevision
        var progressPublished = false
        var publishedHighlights = emptyMap<Int, List<String>>()
        var completionDialogShown = false
        val searchStarted = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()
        val inFlight = async(start = CoroutineStart.UNDISPATCHED) {
            searchStarted.complete(Unit)
            allowCompletion.await()
            if (accepts()) progressPublished = true
            if (accepts()) {
                publishedHighlights = mapOf(workToken.pageIndex!! to listOf("stale"))
                completionDialogShown = true
            }
        }
        searchStarted.await()

        selectedPageIndex = 3
        allowCompletion.complete(Unit)
        inFlight.await()
        assertFalse(accepts())
        assertFalse(progressPublished)
        assertTrue(publishedHighlights.isEmpty())
        assertFalse(completionDialogShown)

        assertTrue(
            clearSearchProgressIfOwned(activeRequestRevision, queryRevision) {
                searching = false
                activeRequestRevision = 0L
            }
        )
        assertFalse(searching)

        // A newer request owns progress now; cleanup from the canceled old
        // effect must not clear the newer request's indicator.
        searching = true
        activeRequestRevision = queryRevision + 1
        assertFalse(
            clearSearchProgressIfOwned(activeRequestRevision, queryRevision) {
                searching = false
            }
        )
        assertTrue(searching)
        assertTrue(
            clearSearchProgressIfOwned(activeRequestRevision, queryRevision + 1) {
                searching = false
                activeRequestRevision = 0L
            }
        )
        assertFalse(searching)
    }

    @Test
    fun oldCoordinatorCleanup_usesCapturedOwner_afterSameTokenRebind() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        val oldCoordinator = coordinator(host, dispatcher)
        assertTrue(switch(oldCoordinator, "A", testScheduler) is SwitchResult.Switched)

        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val ownerCalls = mutableListOf<DocumentWorkOwner>()
        host.cancelAndJoinWorkWithOwner = { _, owner ->
            ownerCalls += owner
            if (owner === oldCoordinator.documentWorkOwner) {
                cleanupStarted.complete(Unit)
                releaseCleanup.await()
            }
        }

        val oldCleanup = async(start = CoroutineStart.UNDISPATCHED) {
            oldCoordinator.closeAndJoin()
        }
        cleanupStarted.await()

        val newCoordinator = coordinator(host, dispatcher)
        assertTrue(switch(newCoordinator, "A", testScheduler) is SwitchResult.Switched)
        assertEquals(
            oldCoordinator.currentSession()!!.token,
            newCoordinator.currentSession()!!.token
        )
        assertEquals(listOf(oldCoordinator.documentWorkOwner), ownerCalls)

        releaseCleanup.complete(Unit)
        oldCleanup.await()
        newCoordinator.closeAndJoin()
        assertEquals(
            listOf(oldCoordinator.documentWorkOwner, newCoordinator.documentWorkOwner),
            ownerCalls
        )
    }

    @Test
    fun autosaveCoalescesRapidMutations_andSwitchFlushUsesNewestFrozenSnapshot() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        val b = host.addTarget("B", "B")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        host.snapshots[b.association.documentId] = snapshot(b, "B")
        val coordinator = coordinator(host, dispatcher, debounceMillis = 100)
        switch(coordinator, "A", scheduler)

        host.liveSnapshot = snapshot(a, "A-first")
        coordinator.markDocumentDirty()
        host.liveSnapshot = snapshot(a, "A-newest")
        coordinator.markDocumentDirty()
        scheduler.advanceUntilIdle()

        assertEquals(1, host.savedSnapshots.size)
        assertEquals("A-newest", host.savedSnapshots.single().marker())
        host.liveSnapshot = snapshot(a, "A-before-switch")
        val result = switch(coordinator, "B", scheduler)
        assertTrue(result is SwitchResult.Switched)
        assertEquals("A-before-switch", host.savedSnapshots.last().marker())
    }

    @Test
    fun provisionalTarget_dirtyCallback_cannotAutosaveClearedPlaceholder() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        val b = host.addTarget("B", "B")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        host.snapshots[b.association.documentId] = snapshot(b, "B")
        host.loadGates[b.association.documentId] = CompletableDeferred()
        val coordinator = coordinator(host, dispatcher)

        assertTrue(switch(coordinator, "A", scheduler) is SwitchResult.Switched)
        val switching = async { coordinator.switchTo("B") }
        scheduler.runCurrent()

        // B owns the token but its load has not applied; its live state is
        // still the cleared placeholder and is not an autosave source.
        coordinator.markDocumentDirty()
        scheduler.runCurrent()

        assertTrue(host.savedSnapshots.none { it.source.sourceUri == "B" })
        assertTrue(host.savedSnapshots.none { it.pages.isEmpty() && it.source.sourceUri == "B" })

        switching.cancel()
        switching.join()
    }

    @Test
    fun sourceRevisionChange_createsNewGeneration_andRejectsOldAResults() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a1 = host.addTarget("A", "A-revision-1")
        val b = host.addTarget("B", "B")
        val a2 = host.addTarget("A-revision-2", "A-revision-2", documentId = a1.association.documentId)
        host.targetsByUri["A"] = a1
        host.snapshots[a1.association.documentId] = snapshot(a1, "A-r1")
        host.snapshots[b.association.documentId] = snapshot(b, "B")
        host.snapshots[a2.association.documentId] = snapshot(a2, "A-r2")
        val coordinator = coordinator(host, dispatcher)
        switch(coordinator, "A", scheduler)
        val old = coordinator.currentSession()!!
        switch(coordinator, "B", scheduler)
        host.targetsByUri["A"] = a2
        switch(coordinator, "A", scheduler)
        val current = coordinator.currentSession()!!

        assertEquals(old.token.documentId, current.token.documentId)
        assertNotEquals(old.token.sourceFingerprint, current.token.sourceFingerprint)
        assertNotEquals(old.token.generation, current.token.generation)
        assertFalse(coordinator.isCurrent(old.token))
    }

    @Test
    fun concurrentSwitchRequests_areSerialized_andFinalTargetWins() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        val b = host.addTarget("B", "B")
        val c = host.addTarget("C", "C")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        host.snapshots[b.association.documentId] = snapshot(b, "B")
        host.snapshots[c.association.documentId] = snapshot(c, "C")
        host.loadGates[b.association.documentId] = CompletableDeferred()
        val coordinator = coordinator(host, dispatcher)
        switch(coordinator, "A", scheduler)

        val bRequest = async { coordinator.switchTo("B") }
        scheduler.runCurrent()
        val cRequest = async { coordinator.switchTo("C") }
        scheduler.runCurrent()
        advanceUntilIdle()

        assertTrue(cRequest.await() is SwitchResult.Switched)
        assertTrue(bRequest.await() is SwitchResult.Superseded)
        assertEquals(c.association.documentId, coordinator.currentSession()?.token?.documentId)
        assertEquals("C", host.liveSnapshot.marker())
        assertEquals(3, host.clearCount)
    }

    @Test
    fun abandoningProvisionalTarget_thenFailingNextTarget_restoresLastCommittedSnapshot() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        val b = host.addTarget("B", "B")
        val c = host.addTarget("C", "C")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        host.snapshots[b.association.documentId] = snapshot(b, "B")
        host.loadGates[b.association.documentId] = CompletableDeferred()
        host.loadFailures[c.association.documentId] = DocumentLoadFailure("C is unavailable")
        val coordinator = coordinator(host, dispatcher)
        switch(coordinator, "A", scheduler)
        host.liveSnapshot = snapshot(a, "A-edit")

        val bRequest = async { coordinator.switchTo("B") }
        scheduler.runCurrent()
        val cRequest = async { coordinator.switchTo("C") }
        scheduler.runCurrent()
        advanceUntilIdle()

        assertTrue(bRequest.await() is SwitchResult.Superseded)
        assertTrue(cRequest.await() is SwitchResult.Failed)
        assertEquals(a.association.documentId, coordinator.currentSession()?.token?.documentId)
        assertEquals("A-edit", host.liveSnapshot.marker())
        assertEquals(0, host.savedSnapshots.count { it.source.sourceUri == "B" })
        assertEquals("B", host.snapshots.getValue(b.association.documentId).marker())
    }

    @Test
    fun coordinatorOwnedDocumentJobs_areCancelledAndJoinedBeforeSwitchCompletes() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        val b = host.addTarget("B", "B")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        host.snapshots[b.association.documentId] = snapshot(b, "B")
        val coordinator = coordinator(host, dispatcher)
        switch(coordinator, "A", scheduler)

        val cancellationObserved = CompletableDeferred<Unit>()
        val job = coordinator.launchDocumentJob(coordinator.currentSession()!!.token) {
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                cancellationObserved.complete(Unit)
            }
        }
        scheduler.runCurrent()

        assertTrue(switch(coordinator, "B", scheduler) is SwitchResult.Switched)
        assertTrue(cancellationObserved.isCompleted)
        assertFalse(job.isActive)
    }

    @Test
    fun lifecycleFlush_freezesBeforeDelayedSave_andCannotCaptureLaterLiveState() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        host.saveGate = CompletableDeferred()
        val coordinator = coordinator(host, dispatcher)
        switch(coordinator, "A", scheduler)
        host.liveSnapshot = snapshot(a, "A-lifecycle-before-save")

        val flush = async { coordinator.flushCurrent() }
        scheduler.runCurrent()
        assertEquals("A-lifecycle-before-save", host.saveStartedSnapshot?.marker())

        host.liveSnapshot = snapshot(a, "wrong-later-live-state")
        host.saveGate!!.complete(Unit)
        scheduler.runCurrent()

        assertTrue(flush.await() is DocumentSaveResult.Saved)
        assertEquals("A-lifecycle-before-save", host.savedSnapshots.single().marker())
    }

    @Test
    fun lifecycleFlush_skipsProvisionalTarget_andCannotPersistClearedLoadingState() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        val b = host.addTarget("B", "B")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        host.snapshots[b.association.documentId] = snapshot(b, "B")
        host.loadGates[b.association.documentId] = CompletableDeferred()
        val coordinator = coordinator(host, dispatcher)
        switch(coordinator, "A", scheduler)
        host.liveSnapshot = snapshot(a, "A-before-switch")

        val switching = async { coordinator.switchTo("B") }
        scheduler.runCurrent()
        val flush = async { coordinator.flushCurrent() }
        scheduler.runCurrent()

        assertNull(flush.await())
        assertFalse(host.savedSnapshots.any { it.source.sourceUri == "B" })
        host.loadGates.getValue(b.association.documentId).complete(Unit)
        advanceUntilIdle()
        assertTrue(switching.await() is SwitchResult.Switched)
        assertEquals("B", host.liveSnapshot.marker())
    }

    @Test
    fun setupFailure_afterOutgoingCommit_restoresOutgoingSession() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        val b = host.addTarget("B", "B")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        host.snapshots[b.association.documentId] = snapshot(b, "B")
        val coordinator = coordinator(host, dispatcher)
        switch(coordinator, "A", scheduler)
        host.liveSnapshot = snapshot(a, "A-edit")
        host.failNextClear = true

        val result = switch(coordinator, "B", scheduler)

        assertTrue(result is SwitchResult.Failed)
        assertEquals(SwitchFailureStage.TARGET_APPLY, (result as SwitchResult.Failed).failure.stage)
        assertEquals(a.association.documentId, coordinator.currentSession()?.token?.documentId)
        assertEquals("A-edit", host.liveSnapshot.marker())
        assertEquals(0, host.loadCount("B"))
    }

    @Test
    fun targetResolutionFailure_preservesCurrentDocument_withoutClearingOrLoadingTarget() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        val coordinator = coordinator(host, dispatcher)
        switch(coordinator, "A", scheduler)
        host.liveSnapshot = snapshot(a, "A-still-live")
        val clearsBefore = host.clearCount

        val result = switch(coordinator, "unreadable-target", scheduler)

        assertTrue(result is SwitchResult.Failed)
        assertEquals(SwitchFailureStage.RESOLVE_TARGET, (result as SwitchResult.Failed).failure.stage)
        assertEquals(a.association.documentId, coordinator.currentSession()?.token?.documentId)
        assertEquals("A-still-live", host.liveSnapshot.marker())
        assertEquals(clearsBefore, host.clearCount)
        assertEquals(0, host.loadCount("unreadable-target"))
    }

    @Test
    fun targetWithoutSnapshot_switchesToEmptyState_andLoadsExactlyOnce() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val target = host.addTarget("new", "new")
        val coordinator = coordinator(host, dispatcher)

        val result = switch(coordinator, "new", scheduler)

        assertTrue(result is SwitchResult.Switched)
        assertFalse((result as SwitchResult.Switched).loadedSnapshot)
        assertEquals(1, host.loadCount("new"))
        assertTrue(host.liveSnapshot.pages.isEmpty())
        assertEquals(target.association.documentId, coordinator.currentSession()?.token?.documentId)
    }

    @Test
    fun cancelledSwitch_restoresOutgoingSnapshot_andDoesNotLeaveTargetActive() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        val b = host.addTarget("B", "B")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        host.snapshots[b.association.documentId] = snapshot(b, "B")
        host.loadGates[b.association.documentId] = CompletableDeferred()
        val coordinator = coordinator(host, dispatcher)
        switch(coordinator, "A", scheduler)
        host.liveSnapshot = snapshot(a, "A-before-cancel")

        val switching = async { coordinator.switchTo("B") }
        scheduler.runCurrent()
        switching.cancel()
        scheduler.runCurrent()
        switching.join()
        advanceUntilIdle()

        assertEquals(a.association.documentId, coordinator.currentSession()?.token?.documentId)
        assertEquals("A-before-cancel", host.liveSnapshot.marker())
        assertEquals(0, host.appliedMarkers.count { it == "B" })
        assertTrue(host.failures.any { it.stage == SwitchFailureStage.CANCELLED })
    }

    @Test
    fun recoveredTargetSnapshot_appliesOnlyToTheCurrentTargetSession() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = FakeHost()
        val a = host.addTarget("A", "A")
        val b = host.addTarget("B", "B")
        host.snapshots[a.association.documentId] = snapshot(a, "A")
        host.snapshots[b.association.documentId] = snapshot(b, "B-recovered")
        host.recoveredTargets += b.association.documentId
        val coordinator = coordinator(host, dispatcher)

        switch(coordinator, "A", scheduler)
        val result = switch(coordinator, "B", scheduler)

        assertTrue(result is SwitchResult.Switched)
        assertTrue((result as SwitchResult.Switched).recoveredFromPrevious)
        assertEquals("B-recovered", host.liveSnapshot.marker())
        assertEquals(listOf(b.association.documentId), host.recoveredSessions.map { it.token.documentId })
    }

    @Test
    fun closeAndJoin_invalidatesToken_andWaitsForDocumentJobCleanup() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val host = FakeHost()
        val target = host.addTarget("A", "A")
        host.snapshots[target.association.documentId] = snapshot(target, "A")
        val coordinator = coordinator(host, dispatcher)

        switch(coordinator, "A", testScheduler)
        val token = coordinator.currentSession()!!.token
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val cleanupFinished = CompletableDeferred<Unit>()
        coordinator.launchDocumentJob(token) {
            try {
                awaitCancellation()
            } finally {
                cleanupStarted.complete(Unit)
                withContext(NonCancellable) {
                    allowCleanup.await()
                }
                cleanupFinished.complete(Unit)
            }
        }
        runCurrent()

        val closing = async { coordinator.closeAndJoin() }
        runCurrent()
        cleanupStarted.await()

        // closeAndJoin fences admission before it waits for the canceled job.
        assertFalse(coordinator.isCurrent(token))
        assertFalse(coordinator.accepts(DocumentWorkToken(token, pageIndex = 0), 0, 1L))
        assertFalse(closing.isCompleted)

        allowCleanup.complete(Unit)
        closing.await()
        assertTrue(cleanupFinished.isCompleted)
        assertFalse(coordinator.isCurrentApplied(token))
    }

    @Test
    fun switchCleanupFinalizer_attemptsSyncAfterOcrFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val host = FakeHost()
        val target = host.addTarget("A", "A")
        host.snapshots[target.association.documentId] = snapshot(target, "A")
        val coordinator = coordinator(host, dispatcher)
        assertTrue(switch(coordinator, "A", testScheduler) is SwitchResult.Switched)

        val events = mutableListOf<String>()
        val ocrFailure = IllegalStateException("OCR eviction failed")
        val syncFailure = IllegalArgumentException("sync cancellation failed")
        host.cancelAndJoinWork = {
            runDocumentWorkCleanupFinalizer(
                evictOcr = {
                    events += "ocr"
                    throw ocrFailure
                },
                cancelSync = {
                    events += "sync"
                    throw syncFailure
                }
            )
        }

        val observed = runCatching { coordinator.closeAndJoin() }.exceptionOrNull()
        assertTrue(observed is IllegalStateException)
        assertEquals(ocrFailure.message, observed?.message)
        assertEquals(listOf("ocr", "sync"), events)
        assertTrue(observed?.suppressed?.any { it.message == syncFailure.message } == true)
    }

    @Test
    fun coordinatorRebind_closesOldOcrSession_withoutPermanentlyClosingSharedRegistry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val host = FakeHost()
        val target = host.addTarget("A", "A")
        host.snapshots[target.association.documentId] = snapshot(target, "A")
        val registry = OcrSessionRegistry()
        val graphs = mutableListOf<CountingOcrGraph>()
        var opens = 0
        val factory = OcrSessionResourceFactory {
            opens++
            CountingOcrGraph().also(graphs::add)
        }
        host.cancelAndJoinWork = { session ->
            // Coordinator rebinding is non-terminal for the stable
            // composition owner, so only the old token is evicted.
            registry.evictSessionAndJoin(session.token)
        }

        val oldCoordinator = coordinator(host, dispatcher)
        assertTrue(switch(oldCoordinator, "A", testScheduler) is SwitchResult.Switched)
        val oldToken = oldCoordinator.currentSession()!!.token
        registry.getOrOpen(oldToken, factory)

        oldCoordinator.closeAndJoin()
        assertEquals(1, graphs.single().closeCalls)
        assertFalse(oldCoordinator.isCurrent(oldToken))

        // A newly rebound coordinator starts its generation counter again and
        // therefore exercises the exact same full token. The stable registry
        // must still permit the new owner to open it.
        val newCoordinator = coordinator(host, dispatcher)
        assertTrue(switch(newCoordinator, "A", testScheduler) is SwitchResult.Switched)
        val newToken = newCoordinator.currentSession()!!.token
        assertEquals(oldToken, newToken)
        registry.getOrOpen(newToken, factory)
        assertEquals(2, opens)

        newCoordinator.closeAndJoin()
        assertEquals(1, graphs[1].closeCalls)
        registry.closeAndJoin()
    }

    private fun coordinator(
        host: FakeHost,
        dispatcher: TestDispatcher,
        debounceMillis: Long = 0L
    ): DocumentSwitchCoordinator {
        val coordinator = DocumentSwitchCoordinator(
            callbacks = host,
            parentScope = CoroutineScope(SupervisorJob() + dispatcher),
            debounceMillis = debounceMillis,
            coordinatorDispatcher = dispatcher
        )
        coordinators += coordinator
        host.coordinator = coordinator
        return coordinator
    }

    private suspend fun switch(
        coordinator: DocumentSwitchCoordinator,
        uri: String,
        scheduler: TestCoroutineScheduler
    ): SwitchResult {
        val request = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async {
            coordinator.switchTo(uri)
        }
        scheduler.runCurrent()
        return request.await()
    }

    private fun snapshot(target: ResolvedDocumentTarget, marker: String): DocumentSnapshotV1 {
        return DocumentSnapshotV1(
            schemaVersion = 1,
            snapshotRevision = 0,
            source = target.association.source,
            pages = mapOf(
                0 to PageSnapshotV1(
                    notes = listOf(NoteSnapshotV1(1f, 2f, marker, 12f, false, 0f))
                )
            )
        )
    }

    private class FakeHost : DocumentSessionCallbacks {
        val targetsByUri = linkedMapOf<String, ResolvedDocumentTarget>()
        val snapshots = linkedMapOf<DocumentId, DocumentSnapshotV1>()
        val savedSnapshots = mutableListOf<DocumentSnapshotV1>()
        val appliedMarkers = mutableListOf<String>()
        val establishedSessions = mutableListOf<DocumentSession>()
        val failures = mutableListOf<SwitchFailure>()
        val loadCounts = mutableMapOf<String, Int>()
        val loadGates = mutableMapOf<DocumentId, CompletableDeferred<Unit>>()
        val loadFailures = mutableMapOf<DocumentId, DocumentLoadFailure>()
        val recoveredTargets = mutableSetOf<DocumentId>()
        val recoveredSessions = mutableListOf<DocumentSession>()
        var saveGate: CompletableDeferred<Unit>? = null
        var saveStartedSnapshot: DocumentSnapshotV1? = null
        var saveFailure: LocalRepositoryError? = null
        var failNextClear = false
        var liveSnapshot: DocumentSnapshotV1 = emptySnapshot()
        var activeSession: DocumentSession? = null
        var clearCount = 0
        var coordinator: DocumentSwitchCoordinator? = null
        var cancelAndJoinWork: suspend (DocumentSession) -> Unit = {}
        var cancelAndJoinWorkWithOwner:
            suspend (DocumentSession, DocumentWorkOwner) -> Unit = { session, _ ->
                cancelAndJoinWork(session)
            }

        fun addTarget(uri: String, marker: String, documentId: DocumentId = DocumentId.new()): ResolvedDocumentTarget {
            val source = DocumentSourceIdentityV1(uri, uri)
            val target = ResolvedDocumentTarget(
                DocumentAssociation(
                    documentId = documentId,
                    source = source,
                    sourceFingerprint = SourceFingerprint.fromBytes(marker.toByteArray()),
                    legacyArtifactName = "legacy-$uri"
                )
            )
            targetsByUri[uri] = target
            return target
        }

        fun loadCount(uri: String) = loadCounts[uri] ?: 0

        override suspend fun resolveTarget(sourceUri: String): TargetResolution =
            targetsByUri[sourceUri]?.let { TargetResolution.Resolved(it) }
                ?: TargetResolution.Failed(
                    SwitchFailure(SwitchFailureStage.RESOLVE_TARGET, "unknown target $sourceUri")
                )

        override fun captureSnapshot(session: DocumentSession): DocumentSnapshotV1 = liveSnapshot

        override suspend fun saveSnapshot(
            session: DocumentSession,
            frozenSnapshot: DocumentSnapshotV1
        ): DocumentSaveResult {
            saveStartedSnapshot = frozenSnapshot
            saveGate?.await()
            val failure = saveFailure
            if (failure != null) return DocumentSaveResult.Failed(failure)
            savedSnapshots += frozenSnapshot
            snapshots[session.token.documentId] = frozenSnapshot
            return DocumentSaveResult.Saved(session.token.documentId)
        }

        override suspend fun cancelAndJoinDocumentWork(session: DocumentSession) = cancelAndJoinWork(session)

        override suspend fun cancelAndJoinDocumentWork(
            session: DocumentSession,
            owner: DocumentWorkOwner
        ) = cancelAndJoinWorkWithOwner(session, owner)

        override fun invalidateDocumentWork(session: DocumentSession) = Unit

        override fun clearDocumentState() {
            if (failNextClear) {
                failNextClear = false
                error("test clear failure")
            }
            clearCount++
            liveSnapshot = emptySnapshot(activeSession?.target?.association?.source)
        }

        override fun establishSession(session: DocumentSession) {
            activeSession = session
            establishedSessions += session
            liveSnapshot = emptySnapshot(session.target.association.source)
        }

        override suspend fun loadTarget(session: DocumentSession): SessionLoadResult {
            val uri = session.token.sourceUri
            loadCounts[uri] = loadCount(uri) + 1
            loadGates[session.token.documentId]?.await()
            loadFailures[session.token.documentId]?.let { return SessionLoadResult.Failed(it) }
            return snapshots[session.token.documentId]?.let {
                SessionLoadResult.Loaded(
                    snapshot = it,
                    recoveredFromPrevious = session.token.documentId in recoveredTargets
                )
            }
                ?: SessionLoadResult.Empty()
        }

        override fun applyLoadedSnapshot(session: DocumentSession, snapshot: DocumentSnapshotV1) {
            liveSnapshot = snapshot
            appliedMarkers += snapshot.pages.getValue(0).notes.single().text
        }

        override fun onRecoveredSnapshot(session: DocumentSession) {
            recoveredSessions += session
        }

        override fun onSwitchFailure(failure: SwitchFailure) {
            failures += failure
        }

        override fun startDocumentBackgroundWork(session: DocumentSession) = Unit

        override fun resumeDocumentBackgroundWork(session: DocumentSession) = Unit

        override fun onAutosaveFailure(session: DocumentSession, result: DocumentSaveResult.Failed) {
            failures += SwitchFailure(SwitchFailureStage.OUTGOING_FLUSH, "autosave failed", result.error)
        }

        private fun emptySnapshot(source: DocumentSourceIdentityV1? = null) = DocumentSnapshotV1(
            schemaVersion = 1,
            snapshotRevision = 0,
            source = source ?: DocumentSourceIdentityV1("empty", "empty"),
            pages = emptyMap()
        )
    }

    private class CountingOcrGraph : OcrSessionResourceGraph {
        var closeCalls = 0

        override suspend fun pageCount(): Int = 0

        override suspend fun extractEmbeddedText(pageIndex: Int) = emptyList<com.example.myapplication.OcrBox>()

        override suspend fun recognizePage(pageIndex: Int) = emptyList<com.example.myapplication.OcrBox>()

        override fun close() {
            closeCalls++
        }
    }
}
