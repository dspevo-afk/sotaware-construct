package com.example.myapplication.stage9a

import android.content.ContextWrapper
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
            assertFalse(AnnotationReducer(h.vm).canUndo(0))
            assertFalse(AnnotationReducer(h.vm).canRedo(0))
            assertFalse(h.originalReducer.undo(0))
            assertEquals("target B", h.vm.pageNotes[0]!!.single().text)
        }
    }

    private enum class Failure { NONE, LOAD, ESTABLISH, APPLY, WAIT }

    private class Harness(val directory: File) {
        val vm = BlueprintViewModel()
        private val context = object : ContextWrapper(null) { override fun getFilesDir() = directory }
        val a = association("a")
        val b = association("b")
        val c = association("c")
        var failure = Failure.NONE
        val bStarted = CompletableDeferred<Unit>()
        private val parent = SupervisorJob()
        private val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        private val host = AndroidDocumentSessionCallbacks(
            context = context, viewModel = vm,
            repository = LocalDocumentRepository(File(directory, "repository")),
            legacySource = AndroidLegacyPersistenceSource(context),
            onSessionEstablished = {}, onStateCleared = {}, onPageCount = { _, _ -> },
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
                    (session.token.documentId == b.documentId && failure == Failure.LOAD)) {
                    return SessionLoadResult.Failed(DocumentLoadFailure("injected target load failure"))
                }
                if (session.token.documentId == b.documentId && failure == Failure.WAIT) {
                    bStarted.complete(Unit)
                    awaitCancellation()
                }
                val page = if (session.token.documentId == a.documentId) PageSnapshotV1() else PageSnapshotV1(
                    notes = listOf(NoteSnapshotV1(.2f, .3f, "target B", 16f, false, 0f))
                )
                return SessionLoadResult.Loaded(DocumentSnapshotV1(1, 0L, session.target.association.source, mapOf(0 to page)), pageCount = 1)
            }

            override fun applyLoadedSnapshot(session: DocumentSession, snapshot: DocumentSnapshotV1) {
                host.applyLoadedSnapshot(session, snapshot)
                if (session.token.documentId == b.documentId && failure == Failure.APPLY) error("injected failure after target mutation")
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
            assertTrue(originalReducer.addPdfNote(0, Note(.2f, .3f, "undo-kept")))
            assertTrue(originalReducer.addPdfNote(0, Note(.4f, .5f, "redo-kept")))
            assertTrue(originalReducer.undo(0))
            assertTrue(originalReducer.canUndo(0))
            assertTrue(originalReducer.canRedo(0))
            outgoing = snapshotFromState(vm, a.source)
        }

        fun assertRestoredHistory() {
            val restored = requireNotNull(coordinator.currentSession()).token
            assertEquals(a.documentId, restored.documentId)
            assertTrue(coordinator.isCurrentApplied(restored))
            assertNotEquals(originalToken, restored)
            assertEquals(outgoing, snapshotFromState(vm, a.source))
            assertFalse("old session closures stay stale even after rollback", originalReducer.undo(0))
            val reducer = boundReducer(restored)
            assertTrue("failed switch must preserve outgoing Undo", reducer.canUndo(0))
            assertTrue("failed switch must preserve outgoing Redo", reducer.canRedo(0))
            assertTrue(reducer.redo(0))
            assertEquals(listOf("undo-kept", "redo-kept"), vm.pageNotes[0]!!.map { it.text })
            assertTrue(reducer.undo(0))
            assertTrue(reducer.undo(0))
            assertTrue(vm.pageNotes[0]!!.isEmpty())
            assertTrue(reducer.redo(0))
            assertEquals(listOf("undo-kept"), vm.pageNotes[0]!!.map { it.text })
        }

        private fun boundReducer(token: DocumentSessionToken) = AnnotationReducer(vm,
            sessionKey = token, currentSessionKey = { coordinator.currentSession()?.token },
            sessionActivePredicate = { coordinator.isCurrentApplied(token) })

        suspend fun close() { coordinator.closeAndJoin(); parent.cancel() }

        private fun association(label: String) = DocumentAssociation(
            DocumentId.new(), DocumentSourceIdentityV1("content://stage9a/failed-switch/$label", "$label.pdf"),
            SourceFingerprint.fromBytes(label.toByteArray()), "markups-$label.bin"
        )
    }

    private suspend fun withHarness(block: suspend (Harness) -> Unit) {
        val directory = Files.createTempDirectory("stage9a-switch-history").toFile()
        val harness = Harness(directory)
        try { harness.initialize(); block(harness) }
        finally { harness.close(); directory.deleteRecursively() }
    }
}
