package com.example.myapplication.stage9a

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

/** Real repository and Android history adapter; barriers control the lifecycle race. */
class DocumentRecreationHandoffTest {
    @Test fun freshDocumentRecreationWaitsForTheOldFlushBeforeReadingEmptyDisk() = runBlocking {
        withHarness { h -> h.verifyDelayedFlush(seedDurable = false) }
    }

    @Test fun existingDocumentRecreationCannotApplyAnOlderDurableSnapshot() = runBlocking {
        withHarness { h -> h.verifyDelayedFlush(seedDurable = true) }
    }

    @Test fun failedFinalFlushPreservesRetainedNotesAndHistoryUntilRetry() = runBlocking {
        withHarness { h ->
            val old = h.host(); old.open(h.a); h.addNote(old, "not-yet-saved")
            val before = snapshotFromState(h.vm, h.a.source)
            h.failSave = true; old.parent.cancel()
            val next = h.host()
            assertTrue(next.coordinator.switchTo(h.a.source.sourceUri) is SwitchResult.Failed)
            assertEquals(0, next.loads); assertNull(next.coordinator.currentSession())
            assertEquals(before.pages, snapshotFromState(h.vm, h.a.source).pages)
            assertTrue(h.reducer(old).canUndo(0))
            h.failSave = false; next.open(h.a)
            assertEquals(before.pages, snapshotFromState(h.vm, h.a.source).pages)
            assertTrue(h.reducer(next).canUndo(0)); h.assertDurable(h.a, "not-yet-saved")
        }
    }

    @Test fun lateOldOwnerCloseAndSelectionCannotEraseTheReplacementDocument() = runBlocking {
        withHarness { h ->
            val old = h.host(); old.open(h.a); h.addNote(old, "saved A")
            val next = h.host(); next.open(h.b); h.addNote(next, "live B")
            val before = snapshotFromState(h.vm, h.b.source)
            old.owner.closeAndJoin()
            assertTrue(old.coordinator.switchTo(h.a.source.sourceUri) is SwitchResult.Failed)
            assertEquals(before.pages, snapshotFromState(h.vm, h.b.source).pages)
            assertTrue(h.reducer(next).canUndo(0)); h.assertDurable(h.a, "saved A")
        }
    }

    @Test fun cancelledRecreationDoesNotAllowAnotherHostToBypassTheFlush() = runBlocking {
        withHarness { h ->
            val old = h.host(); old.open(h.a); h.addNote(old, "retained A")
            h.delaySave = true
            val next = h.host()
            val pending = launch(start = CoroutineStart.UNDISPATCHED) { next.open(h.a) }
            h.saveEntered.await(); pending.cancel()
            val latest = h.host()
            val opening = async(start = CoroutineStart.UNDISPATCHED) { latest.open(h.a) }
            try { assertFalse(opening.isCompleted); assertEquals(0, latest.loads) }
            finally { h.saveRelease.complete(Unit) }
            pending.join(); opening.await()
            assertTrue(h.reducer(latest).canUndo(0)); h.assertDurable(h.a, "retained A")
        }
    }

    private class Harness(val directory: File) {
        val vm = BlueprintViewModel()
        val repository = LocalDocumentRepository(File(directory, "repository"))
        val a = association("a"); val b = association("b")
        val saveEntered = CompletableDeferred<Unit>()
        val saveRelease = CompletableDeferred<Unit>()
        var delaySave = false; var failSave = false
        private val hosts = mutableListOf<Host>()
        private val context = object : android.content.ContextWrapper(null) {
            override fun getFilesDir() = directory
        }

        inner class Host {
            val parent = SupervisorJob()
            val scope = CoroutineScope(parent + Dispatchers.Unconfined)
            val owner = vm.documentHostHandoff.newOwner()
            var loads = 0
            private val adapter = AndroidDocumentSessionCallbacks(
                context = context, viewModel = vm, repository = repository,
                onSessionEstablished = {}, onStateCleared = {}, onPageCount = { _, _ -> },
                onRecovered = {}, onFailure = {}, onStart = {}, cancelAndJoinWork = {},
                resumeWork = {}, loadPageCount = { 1 }
            )
            private val callbacks = object : DocumentSessionCallbacks by adapter {
                override suspend fun resolveTarget(sourceUri: String) = TargetResolution.Resolved(
                    ResolvedDocumentTarget(listOf(a, b).single { it.source.sourceUri == sourceUri }))

                override suspend fun saveSnapshot(session: DocumentSession, frozenSnapshot: DocumentSnapshotV1): DocumentSaveResult {
                    if (delaySave) { saveEntered.complete(Unit); saveRelease.await() }
                    if (failSave) return DocumentSaveResult.Failed(LocalRepositoryError.InvalidSnapshot("injected save failure"))
                    return repository.save(session.target.association, frozenSnapshot)
                }

                override suspend fun loadTarget(session: DocumentSession): SessionLoadResult {
                    loads++
                    return when (val loaded = repository.load(session.target.association)) {
                        is DocumentLoadResult.Loaded -> SessionLoadResult.Loaded(loaded.snapshot, pageCount = 1)
                        DocumentLoadResult.NotFound -> SessionLoadResult.Empty(pageCount = 1)
                        is DocumentLoadResult.Failed -> error("fixture load failed: $loaded")
                    }
                }
            }
            val coordinator = DocumentSwitchCoordinator(callbacks, scope, 60_000L,
                coordinatorDispatcher = Dispatchers.Unconfined, beforeSwitch = owner::activate)
            init {
                owner.bind {
                    check(coordinator.flushCurrent() !is DocumentSaveResult.Failed) { "save failed" }
                    coordinator.closeAndJoin()
                }
            }
            suspend fun open(association: DocumentAssociation) {
                assertTrue(coordinator.switchTo(association.source.sourceUri) is SwitchResult.Switched)
            }
        }

        fun host() = Host().also(hosts::add)
        fun reducer(host: Host): AnnotationReducer {
            val token = requireNotNull(host.coordinator.currentSession()).token
            return AnnotationReducer(vm = vm, sessionKey = token,
                currentSessionKey = { host.coordinator.currentSession()?.token },
                sessionActivePredicate = { host.coordinator.isCurrentApplied(token) })
        }
        fun addNote(host: Host, text: String) {
            vm.pageNotes.getOrPut(0) { androidx.compose.runtime.mutableStateListOf() }
            val result = reducer(host).addPdfNote(0, Note(.3f, .3f, text, id = java.util.UUID.randomUUID().toString()))
            assertTrue(result.toString(), result.changed)
            host.coordinator.markDocumentDirty()
        }
        suspend fun assertDurable(association: DocumentAssociation, text: String) {
            val loaded = repository.load(association)
            assertTrue(loaded.toString(), loaded is DocumentLoadResult.Loaded)
            assertEquals(listOf(text), (loaded as DocumentLoadResult.Loaded).snapshot.pages.values.flatMap { it.notes }.map { it.text })
        }

        suspend fun verifyDelayedFlush(seedDurable: Boolean) = coroutineScope {
            val old = host(); old.open(a)
            if (seedDurable) assertTrue(old.coordinator.flushCurrent() is DocumentSaveResult.Saved)
            addNote(old, "surviving note")
            val before = snapshotFromState(vm, a.source)
            val epoch = vm.annotationHistoryEpoch()
            delaySave = true; old.parent.cancel()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { old.owner.closeAndJoin() }
            saveEntered.await()
            val next = host()
            val opening = async(start = CoroutineStart.UNDISPATCHED) { next.open(a) }
            try {
                assertFalse("replacement became ready before the previous save", opening.isCompleted)
                assertEquals("replacement read stale disk", 0, next.loads)
                assertEquals(before.pages, snapshotFromState(vm, a.source).pages)
            } finally { saveRelease.complete(Unit) }
            closing.await(); opening.await()
            assertEquals(before.pages, snapshotFromState(vm, a.source).pages)
            assertEquals(epoch, vm.annotationHistoryEpoch())
            assertTrue(reducer(next).canUndo(0)); assertDurable(a, "surviving note")
            assertTrue(reducer(next).undo(0).changed)
            assertTrue(vm.pageNotes[0].orEmpty().isEmpty())
            assertTrue(reducer(next).redo(0).changed)
        }

        suspend fun close() {
            failSave = false; saveRelease.complete(Unit)
            for (host in hosts) {
                host.owner.closeAndJoin(); host.parent.cancel()
            }
        }

        private fun association(name: String) = DocumentAssociation(
            DocumentId.new(), DocumentSourceIdentityV1("content://synthetic/$name", "plan.pdf"),
            SourceFingerprint("SHA-256", (if (name == "a") "a" else "b").repeat(64), 100L)
        )
    }

    private suspend fun withHarness(block: suspend (Harness) -> Unit) {
        val directory = Files.createTempDirectory("construct-host-handoff-test-").toFile()
        val harness = Harness(directory)
        try { withTimeout(20_000L) { block(harness) } }
        finally { harness.close(); directory.deleteRecursively() }
    }
}
