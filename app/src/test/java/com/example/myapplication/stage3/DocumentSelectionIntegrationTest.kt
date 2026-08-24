package com.example.myapplication.stage3

import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.NoteSnapshotV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.SourceFingerprint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun integrationSnapshot(target: ResolvedDocumentTarget, marker: String) = DocumentSnapshotV1(
    schemaVersion = 1,
    snapshotRevision = 0,
    source = target.association.source,
    pages = mapOf(0 to PageSnapshotV1(notes = listOf(NoteSnapshotV1(1f, 2f, marker, 12f, false, 0f))))
)

private fun integrationEmptySnapshot(source: DocumentSourceIdentityV1? = null) = DocumentSnapshotV1(
    schemaVersion = 1,
    snapshotRevision = 0,
    source = source ?: DocumentSourceIdentityV1("empty", "empty"),
    pages = emptyMap()
)

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentSelectionIntegrationTest {
    @Test
    fun selectingReadyActiveDocumentFromSelector_restoresBrowserWithoutReloadingOrRecreatingSession() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = IntegrationHost()
        val target = host.addTarget("A")
        val coordinator = host.coordinator(dispatcher)

        try {
            assertTrue(switch(coordinator, "A", scheduler) is SwitchResult.Switched)
            val existingSession = coordinator.currentSession()!!
            val existingSnapshot = host.liveSnapshot
            val ui = IntegrationUi(
                activeToken = existingSession.token,
                readyToken = existingSession.token,
                screen = IntegrationScreen.SELECTOR
            )

            val result = switch(coordinator, "A", scheduler)
            assertTrue(result is SwitchResult.AlreadyActive)
            val restored = restoreAlreadyActiveSession(
                result = result,
                isCurrent = coordinator::isCurrent,
                isReady = { token -> ui.activeToken == token && ui.readyToken == token },
                restoreBrowser = { session ->
                    ui.activeToken = session.token
                    ui.pdfUri = session.token.sourceUri
                    ui.screen = IntegrationScreen.BROWSER
                }
            )

            assertTrue(restored)
            assertEquals(IntegrationScreen.BROWSER, ui.screen)
            assertEquals("A", ui.pdfUri)
            assertSame(existingSession, coordinator.currentSession())
            assertEquals(existingSession.token.generation, coordinator.currentSession()!!.token.generation)
            assertEquals(1, host.loadCount("A"))
            assertEquals(1, host.applyCount("A"))
            assertEquals(1, host.startBackgroundWorkCount("A"))
            assertEquals(existingSnapshot, host.liveSnapshot)
            assertEquals(existingSnapshot, host.snapshots[target.association.documentId])
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun alreadyActiveProvisionalSession_doesNotRestoreBrowserOrMarkItReady() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val host = IntegrationHost()
        host.addTarget("A")
        host.loadGate = CompletableDeferred()
        val ui = IntegrationUi(null, null, IntegrationScreen.SELECTOR)
        host.onSessionEstablished = { session -> ui.activeToken = session.token }
        host.onBackgroundWorkStarted = { session -> ui.readyToken = session.token }
        val coordinator = host.coordinator(dispatcher)

        try {
            val initialLoad = async { coordinator.switchTo("A") }
            scheduler.runCurrent()
            val existingSession = coordinator.currentSession()!!
            assertEquals(existingSession.token, ui.activeToken)
            assertEquals(null, ui.readyToken)
            assertEquals(1, host.loadCount("A"))

            val result = switch(coordinator, "A", scheduler)
            assertTrue(result is SwitchResult.AlreadyActive)
            val restored = restoreAlreadyActiveSession(
                result = result,
                isCurrent = coordinator::isCurrent,
                isReady = { token -> ui.activeToken == token && ui.readyToken == token },
                restoreBrowser = {
                    ui.pdfUri = it.token.sourceUri
                    ui.screen = IntegrationScreen.BROWSER
                    ui.readyToken = it.token
                }
            )

            assertFalse(restored)
            assertEquals(IntegrationScreen.SELECTOR, ui.screen)
            assertEquals(null, ui.pdfUri)
            assertEquals(null, ui.readyToken)
            assertEquals(1, host.loadCount("A"))
            assertEquals(0, host.applyCount("A"))
            assertEquals(0, host.startBackgroundWorkCount("A"))
            assertSame(existingSession, coordinator.currentSession())

            host.loadGate!!.complete(Unit)
            scheduler.runCurrent()
            assertTrue(initialLoad.await() is SwitchResult.Switched)
            assertEquals(existingSession.token, ui.activeToken)
            assertEquals(existingSession.token, ui.readyToken)
            assertEquals(1, host.applyCount("A"))
            assertEquals(1, host.startBackgroundWorkCount("A"))
        } finally {
            coordinator.close()
        }
    }

    private suspend fun switch(
        coordinator: DocumentSwitchCoordinator,
        uri: String,
        scheduler: TestCoroutineScheduler
    ): SwitchResult {
        val request = CoroutineScope(currentCoroutineContext()).async { coordinator.switchTo(uri) }
        scheduler.runCurrent()
        return request.await()
    }

    private enum class IntegrationScreen { SELECTOR, BROWSER }

    private data class IntegrationUi(
        var activeToken: DocumentSessionToken?,
        var readyToken: DocumentSessionToken?,
        var screen: IntegrationScreen,
        var pdfUri: String? = null
    )

    private class IntegrationHost : DocumentSessionCallbacks {
        private val targets = mutableMapOf<String, ResolvedDocumentTarget>()
        val snapshots = mutableMapOf<DocumentId, DocumentSnapshotV1>()
        var liveSnapshot: DocumentSnapshotV1 = integrationEmptySnapshot()
        var loadGate: CompletableDeferred<Unit>? = null
        var onSessionEstablished: ((DocumentSession) -> Unit)? = null
        var onBackgroundWorkStarted: ((DocumentSession) -> Unit)? = null
        private val loads = mutableMapOf<String, Int>()
        private val applied = mutableMapOf<String, Int>()
        private val backgroundWorkStarts = mutableMapOf<String, Int>()

        fun addTarget(uri: String): ResolvedDocumentTarget {
            val target = ResolvedDocumentTarget(
                DocumentAssociation(
                    documentId = DocumentId.new(),
                    source = DocumentSourceIdentityV1(uri, uri),
                    sourceFingerprint = SourceFingerprint.fromBytes(uri.toByteArray()),
                    legacyArtifactName = "legacy-$uri"
                )
            )
            targets[uri] = target
            snapshots[target.association.documentId] = integrationSnapshot(target, uri)
            return target
        }

        fun loadCount(uri: String): Int = loads[uri] ?: 0

        fun applyCount(uri: String): Int = applied[uri] ?: 0

        fun startBackgroundWorkCount(uri: String): Int = backgroundWorkStarts[uri] ?: 0

        fun coordinator(dispatcher: TestDispatcher): DocumentSwitchCoordinator =
            DocumentSwitchCoordinator(
                callbacks = this,
                parentScope = CoroutineScope(SupervisorJob() + dispatcher),
                coordinatorDispatcher = dispatcher
            )

        override suspend fun resolveTarget(sourceUri: String): TargetResolution =
            targets[sourceUri]?.let(TargetResolution::Resolved)
                ?: TargetResolution.Failed(SwitchFailure(SwitchFailureStage.RESOLVE_TARGET, "unknown target"))

        override fun captureSnapshot(session: DocumentSession): DocumentSnapshotV1 = liveSnapshot

        override suspend fun saveSnapshot(
            session: DocumentSession,
            frozenSnapshot: DocumentSnapshotV1
        ): DocumentSaveResult {
            snapshots[session.token.documentId] = frozenSnapshot
            return DocumentSaveResult.Saved(session.token.documentId)
        }

        override suspend fun cancelAndJoinDocumentWork(session: DocumentSession) = Unit

        override fun invalidateDocumentWork(session: DocumentSession) = Unit

        override fun clearDocumentState() {
            liveSnapshot = integrationEmptySnapshot()
        }

        override fun establishSession(session: DocumentSession) {
            liveSnapshot = integrationEmptySnapshot(session.target.association.source)
            onSessionEstablished?.invoke(session)
        }

        override suspend fun loadTarget(session: DocumentSession): SessionLoadResult {
            val uri = session.token.sourceUri
            loads[uri] = loadCount(uri) + 1
            loadGate?.await()
            return SessionLoadResult.Loaded(snapshots.getValue(session.token.documentId))
        }

        override fun applyLoadedSnapshot(session: DocumentSession, snapshot: DocumentSnapshotV1) {
            liveSnapshot = snapshot
            val uri = session.token.sourceUri
            applied[uri] = applyCount(uri) + 1
        }

        override fun startDocumentBackgroundWork(session: DocumentSession) {
            val uri = session.token.sourceUri
            backgroundWorkStarts[uri] = startBackgroundWorkCount(uri) + 1
            onBackgroundWorkStarted?.invoke(session)
        }
    }

}
