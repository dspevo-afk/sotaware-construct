package com.example.myapplication.stage9a

import androidx.compose.runtime.mutableStateListOf
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.Note
import com.example.myapplication.stage1.*
import com.example.myapplication.stage2.*
import com.example.myapplication.stage3.*
import com.example.myapplication.stage8.AnnotationReducer
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real Android history adapter through the actual switch coordinator. */
class FailedSwitchHistoryRegressionTest {
    @Test fun targetLoadFailureRestoresBothHistoryDirections() = runBlocking {
        withHarness { h -> h.failure = Failure.LOAD; assertTrue(h.coordinator.switchTo(h.b.source.sourceUri) is SwitchResult.Failed); h.assertRestoredHistory() }
    }

    @Test fun targetEstablishmentFailureRestoresBothHistoryDirections() = runBlocking {
        withHarness { h -> h.failure = Failure.ESTABLISH; assertTrue(h.coordinator.switchTo(h.b.source.sourceUri) is SwitchResult.Failed); h.assertRestoredHistory() }
    }

    @Test fun targetApplyFailureAfterMutationRestoresBothHistoryDirections() = runBlocking {
        withHarness { h -> h.failure = Failure.APPLY; assertTrue(h.coordinator.switchTo(h.b.source.sourceUri) is SwitchResult.Failed); h.assertRestoredHistory() }
    }

    @Test fun cancellationAfterProvisionalClearRestoresBothHistoryDirections() = runBlocking {
        withHarness { h ->
            h.failure = Failure.WAIT
            val pending = async { h.coordinator.switchTo(h.b.source.sourceUri) }
            h.bStarted.await()
            pending.cancelAndJoin()
            h.assertRestoredHistory()
        }
    }

    @Test fun supersededProvisionalTargetCarriesTheOriginalOutgoingHistory() = runBlocking {
        withHarness { h ->
            h.failure = Failure.WAIT
            val pending = async { h.coordinator.switchTo(h.b.source.sourceUri) }
            h.bStarted.await()
            assertTrue(h.coordinator.switchTo(h.c.source.sourceUri) is SwitchResult.Failed)
            assertTrue(pending.await() is SwitchResult.Superseded)
            h.assertRestoredHistory()
        }
    }

    @Test fun successfulSwitchDoesNotInheritTheOutgoingHistory() = runBlocking {
        withHarness { h ->
            h.failure = Failure.NONE
            assertTrue(h.coordinator.switchTo(h.b.source.sourceUri) is SwitchResult.Switched)
            assertEquals(h.b.documentId, h.coordinator.currentSession()?.token?.documentId)
            val targetToken = requireNotNull(h.coordinator.currentSession()).token
            val targetReducer = h.boundReducer(targetToken)
            assertFalse(targetReducer.canUndo(0))
            assertFalse(targetReducer.canRedo(0))
            assertFalse(h.originalReducer.undo(0).changed)
            assertEquals("target B", h.vm.pageNotes[0]!!.single().text)
        }
    }

    @Test fun failedInitialSameDocumentReentryPreservesRetainedLiveStateAndHistory() = runBlocking {
        withHarness { h -> h.assertFailedInitialReentryPreservesHistory() }
    }

    @Test fun failedInitialSameDocumentApplyRestoresRetainedLiveStateAndHistory() = runBlocking {
        withHarness { h -> h.assertFailedInitialApplyPreservesHistory() }
    }

    @Test fun emptyInitialSameDocumentReentryClearsRetainedStateBeforeReady() = runBlocking {
        withHarness { h -> h.assertEmptyInitialReentryClearsRetainedState() }
    }


    private enum class Failure { NONE, LOAD, ESTABLISH, APPLY, WAIT, REENTRY_LOAD, REENTRY_APPLY, REENTRY_EMPTY }

    private class Harness(val directory: File) {
        val vm = BlueprintViewModel()
        private val context = object : android.content.ContextWrapper(null) {
            override fun getFilesDir() = directory
        }
        val a = association("a")
        val b = association("b")
        val c = association("c")
        var failure = Failure.NONE
        var stateClearCount = 0
        val bStarted = CompletableDeferred<Unit>()
        private val parent = SupervisorJob()
        private val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        private val host = AndroidDocumentSessionCallbacks(
            context = context, viewModel = vm,
            repository = LocalDocumentRepository(File(directory, "repository")),
            onSessionEstablished = {}, onStateCleared = { stateClearCount++ }, onPageCount = { _, _ -> },
            onRecovered = {}, onFailure = {}, onStart = {}, cancelAndJoinWork = {},
            resumeWork = {}, loadPageCount = { 1 }
        )
        private val callbacks = object : DocumentSessionCallbacks by host {
            override suspend fun resolveTarget(sourceUri: String): TargetResolution =
                TargetResolution.Resolved(ResolvedDocumentTarget(listOf(a, b, c).single { it.source.sourceUri == sourceUri }))

            override suspend fun saveSnapshot(session: DocumentSession, frozenSnapshot: DocumentSnapshotV1): DocumentSaveResult {
                assertEquals(session.token.sourceUri, frozenSnapshot.source.sourceUri)
                return DocumentSaveResult.Saved(session.token.documentId)
            }

            override fun establishSession(session: DocumentSession) {
                if (session.token.documentId == b.documentId && failure == Failure.ESTABLISH) error("injected setup failure")
                host.establishSession(session)
            }

            override suspend fun loadTarget(session: DocumentSession): SessionLoadResult {
                if (session.token.documentId == c.documentId ||
                    (session.token.documentId == b.documentId && failure == Failure.LOAD) ||
                    (session.token.documentId == a.documentId && failure == Failure.REENTRY_LOAD)) {
                    return SessionLoadResult.Failed(DocumentLoadFailure("injected target load failure"))
                }
                if (session.token.documentId == b.documentId && failure == Failure.WAIT) {
                    bStarted.complete(Unit)
                    awaitCancellation()
                }
                if (session.token.documentId == a.documentId && failure == Failure.REENTRY_EMPTY) {
                    return SessionLoadResult.Empty(pageCount = 1)
                }
                val page = if (session.token.documentId == a.documentId) {
                    if (failure == Failure.REENTRY_APPLY) PageSnapshotV1(
                        notes = listOf(
                            NoteSnapshotV1(
                                x = .8f,
                                y = .8f,
                                text = "reentry replacement",
                                isBold = false,
                                rotation = 0f,
                                fontSizeRatio = .02f,
                                id = "reentry-replacement"
                            )
                        )
                    ) else PageSnapshotV1()
                } else PageSnapshotV1(
                    notes = listOf(
                        NoteSnapshotV1(
                            x = .2f,
                            y = .3f,
                            text = "target B",
                            isBold = false,
                            rotation = 0f,
                            fontSizeRatio = .02f,
                            id = "target-b-note"
                        )
                    )
                )
                return SessionLoadResult.Loaded(
                    DocumentSnapshotV1(
                        schemaVersion = com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
                        snapshotRevision = 0L,
                        source = session.target.association.source,
                        pages = mapOf(0 to page)
                    ),
                    pageCount = 1
                )
            }

            override fun applyLoadedSnapshot(session: DocumentSession, snapshot: DocumentSnapshotV1) {
                host.applyLoadedSnapshot(session, snapshot)
                if ((session.token.documentId == b.documentId && failure == Failure.APPLY) ||
                    (session.token.documentId == a.documentId && failure == Failure.REENTRY_APPLY)
                ) error("injected failure after target mutation")
            }
        }
        val coordinator = DocumentSwitchCoordinator(callbacks, scope, 60_000L, coordinatorDispatcher = Dispatchers.Unconfined)
        lateinit var originalReducer: AnnotationReducer
        lateinit var originalToken: DocumentSessionToken
        lateinit var outgoing: DocumentSnapshotV1

        suspend fun initialize() {
            assertTrue(coordinator.switchTo(a.source.sourceUri) is SwitchResult.Switched)
            originalToken = requireNotNull(coordinator.currentSession()).token
            originalReducer = boundReducer(originalToken)
            assertTrue(
                originalReducer.addPdfNote(
                    0,
                    Note(
                        x = .2f,
                        y = .3f,
                        text = "undo-kept",
                        fontSizeRatio = .02f,
                        id = "undo-kept-note"
                    )
                ).changed
            )
            assertTrue(
                originalReducer.addPdfNote(
                    0,
                    Note(
                        x = .4f,
                        y = .5f,
                        text = "redo-kept",
                        fontSizeRatio = .02f,
                        id = "redo-kept-note"
                    )
                ).changed
            )
            assertTrue(originalReducer.undo(0).changed)
            assertTrue(originalReducer.canUndo(0))
            assertTrue(originalReducer.canRedo(0))
            outgoing = snapshotFromState(vm, a.source)
        }


        suspend fun assertFailedInitialReentryPreservesHistory() {
            val retainedSnapshot = snapshotFromState(vm, a.source)
            assertTrue(historyProbe(originalToken).canUndo(0))
            assertTrue(historyProbe(originalToken).canRedo(0))
            coordinator.closeAndJoin()
            failure = Failure.REENTRY_LOAD

            val rebound = DocumentSwitchCoordinator(
                callbacks,
                scope,
                60_000L,
                coordinatorDispatcher = Dispatchers.Unconfined
            )
            try {
                assertTrue(rebound.switchTo(a.source.sourceUri) is SwitchResult.Failed)
                assertNull("failed re-entry must not publish a provisional session", rebound.currentSession())
                assertEquals(retainedSnapshot, snapshotFromState(vm, a.source))
                val retainedReducer = historyProbe(originalToken)
                assertTrue("failed re-entry must retain Undo", retainedReducer.canUndo(0))
                assertTrue("failed re-entry must retain Redo", retainedReducer.canRedo(0))
            } finally {
                rebound.closeAndJoin()
            }
        }

        suspend fun assertFailedInitialApplyPreservesHistory() {
            val retainedSnapshot = snapshotFromState(vm, a.source)
            coordinator.closeAndJoin()
            failure = Failure.REENTRY_APPLY
            val rebound = DocumentSwitchCoordinator(callbacks, scope, 60_000L, coordinatorDispatcher = Dispatchers.Unconfined)
            try {
                assertTrue(rebound.switchTo(a.source.sourceUri) is SwitchResult.Failed)
                assertNull(rebound.currentSession())
                assertEquals(retainedSnapshot, snapshotFromState(vm, a.source))
                val retainedReducer = historyProbe(originalToken)
                assertTrue(retainedReducer.canUndo(0))
                assertTrue(retainedReducer.canRedo(0))
            } finally {
                rebound.closeAndJoin()
            }
        }

        suspend fun assertEmptyInitialReentryClearsRetainedState() {
            coordinator.closeAndJoin()
            val clearsBeforeReentry = stateClearCount
            failure = Failure.REENTRY_EMPTY
            val rebound = DocumentSwitchCoordinator(callbacks, scope, 60_000L, coordinatorDispatcher = Dispatchers.Unconfined)
            try {
                assertTrue(rebound.switchTo(a.source.sourceUri) is SwitchResult.Switched)
                val active = requireNotNull(rebound.currentSession())
                assertTrue(rebound.isCurrentApplied(active.token))
                assertEquals("canonical-empty success must not tear down the established host session", clearsBeforeReentry + 1, stateClearCount)
                assertTrue(vm.pageNotes.values.all { it.isEmpty() })
                val reducer = boundReducer(active.token)
                assertFalse(reducer.canUndo(0))
                assertFalse(reducer.canRedo(0))
            } finally {
                rebound.closeAndJoin()
            }
        }

        fun assertRestoredHistory() {
            val restored = requireNotNull(coordinator.currentSession()).token
            assertEquals(a.documentId, restored.documentId)
            assertTrue(coordinator.isCurrentApplied(restored))
            assertNotEquals(originalToken, restored)
            assertEquals(outgoing, snapshotFromState(vm, a.source))
            assertFalse("old session closures stay stale even after rollback", originalReducer.undo(0).changed)
            val reducer = boundReducer(restored)
            assertTrue("failed switch must preserve outgoing Undo", reducer.canUndo(0))
            assertTrue("failed switch must preserve outgoing Redo", reducer.canRedo(0))
            assertTrue(reducer.redo(0).changed)
            assertEquals(listOf("undo-kept", "redo-kept"), vm.pageNotes[0]!!.map { it.text })
            assertTrue(reducer.undo(0).changed)
            assertTrue(reducer.undo(0).changed)
            assertTrue(vm.pageNotes[0]!!.isEmpty())
            assertTrue(reducer.redo(0).changed)
            assertEquals(listOf("undo-kept"), vm.pageNotes[0]!!.map { it.text })
        }

        internal fun boundReducer(token: DocumentSessionToken) = AnnotationReducer(vm,
            sessionKey = token, currentSessionKey = { coordinator.currentSession()?.token },
            sessionActivePredicate = { coordinator.isCurrentApplied(token) })

        /** Probe retained history by the exact token after the coordinator has
         * deliberately relinquished its current session during failed setup. */
        private fun historyProbe(token: DocumentSessionToken) = AnnotationReducer(
            vm,
            sessionKey = token,
            currentSessionKey = { token },
            sessionActivePredicate = { true }
        )

        suspend fun close() { coordinator.closeAndJoin(); parent.cancel() }

        private fun association(label: String) = DocumentAssociation(
            DocumentId.new(), DocumentSourceIdentityV1("content://stage9a/failed-switch/$label", "$label.pdf"),
            SourceFingerprint.fromBytes(label.toByteArray())
        )
    }

    private suspend fun withHarness(block: suspend (Harness) -> Unit) {
        val directory = Files.createTempDirectory("stage9a-switch-history").toFile()
        val harness = Harness(directory)
        try { harness.initialize(); block(harness) }
        finally { harness.close(); directory.deleteRecursively() }
    }
}
