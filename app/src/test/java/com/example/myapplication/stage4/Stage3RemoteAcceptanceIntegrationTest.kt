package com.example.myapplication.stage4

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
import com.example.myapplication.stage1.SnapshotShapeTypeV1
import com.example.myapplication.stage1.ShapeSnapshotV1
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.LocalRepositoryError
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage3.DocumentSession
import com.example.myapplication.stage3.DocumentSessionCallbacks
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentTransactionBarrier
import com.example.myapplication.stage3.DocumentSwitchCoordinator
import com.example.myapplication.stage3.ResolvedDocumentTarget
import com.example.myapplication.stage3.SessionLoadResult
import com.example.myapplication.stage3.TargetResolution
import com.example.myapplication.stage3.SwitchFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Stage3RemoteAcceptanceIntegrationTest {
    @Test
    fun remoteApply_usesStage3DurableSaveBeforeMemory_andRejectsStaleToken() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val host = Stage3Host()
        val coordinator = DocumentSwitchCoordinator(
            callbacks = host,
            parentScope = CoroutineScope(dispatcher + SupervisorJob()),
            coordinatorDispatcher = dispatcher
        )

        assertTrue(coordinator.switchTo("content://stage3-one") is com.example.myapplication.stage3.SwitchResult.Switched)
        val first = requireNotNull(coordinator.currentSession())
        val remote = snapshot(first, "remote")

        host.saveFailure = LocalRepositoryError.IoFailure("test", null, "injected")
        val failed = coordinator.persistAndApplyCurrentSnapshot(first.token, remote)
        assertTrue(failed is com.example.myapplication.stage3.SessionSnapshotApplyResult.Failed)
        assertEquals(0, host.applyCount)

        host.saveFailure = null
        val applied = coordinator.persistAndApplyCurrentSnapshot(first.token, remote)
        assertTrue(applied is com.example.myapplication.stage3.SessionSnapshotApplyResult.Applied)
        assertEquals(1, host.applyCount)
        assertEquals("remote", host.live.pages.getValue(0).notes.single().text)

        assertTrue(coordinator.switchTo("content://stage3-two") is com.example.myapplication.stage3.SwitchResult.Switched)
        val stale = coordinator.persistAndApplyCurrentSnapshot(first.token, remote)
        assertTrue(stale is com.example.myapplication.stage3.SessionSnapshotApplyResult.Stale)
        assertEquals(1, host.applyCount)

        coordinator.close()
    }

    @Test
    fun remoteAcceptance_barrierPreventsSwitchFromPersistingOldMemoryDuringApply() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val barrier = DocumentTransactionBarrier()
        val host = CrossStageHost()
        val stage3 = DocumentSwitchCoordinator(
            callbacks = host,
            parentScope = CoroutineScope(dispatcher + SupervisorJob()),
            coordinatorDispatcher = dispatcher,
            transactionBarrier = barrier
        )
        host.stage3 = stage3

        assertTrue(stage3.switchTo("content://stage3-barrier-a") is com.example.myapplication.stage3.SwitchResult.Switched)
        val first = requireNotNull(stage3.currentSession())
        host.setLive(snapshot(first, "old-live-memory"))
        host.durableSnapshots[first.token.documentId] = snapshot(first, "old-live-memory")
        host.events.clear()
        val scope = SyncScope("account@example.com", "root-barrier", first.token.documentId)
        val drive = FakeDriveGateway(idFactory = { "barrier-${host.nextId++}" })
        val metadata = InMemorySyncMetadataStore()
        val bridge = CrossStageBridge(host)
        val sync = SyncCoordinator(
            gateway = drive,
            metadataStore = metadata,
            bridge = bridge,
            parentScope = CoroutineScope(dispatcher + SupervisorJob()),
            dispatcher = dispatcher,
            documentTransactionBarrier = barrier
        )
        host.sync = sync
        bridge.bindingCurrent = sync::isBindingCurrent
        val binding = requireNotNull(sync.bind(scope, first.token))

        val remoteSnapshot = snapshot(first, "remote-after-durable-save")
        val remote = drive.seed(
            scope,
            "plan.pdf",
            remoteSnapshot,
            sourceFingerprint = first.token.sourceFingerprint
        )
        host.persistStarted = CompletableDeferred()
        host.releasePersist = CompletableDeferred()

        assertTrue(stage3.isCurrentApplied(first.token))
        assertTrue(sync.isBindingCurrent(binding))
        val acceptance = async { sync.enqueueRemoteAcceptance(binding).await() }
        testScheduler.runCurrent()
        withTimeout(5_000L) { requireNotNull(host.persistStarted).await() }

        assertEquals("old-live-memory", host.liveMarker())
        assertEquals("remote-after-durable-save", host.durableSnapshots[first.token.documentId]?.let(::marker))
        val acceptanceCaptureCount = host.events.count { it == "capture:old-live-memory" }

        val switching = async { stage3.switchTo("content://stage3-barrier-b") }
        testScheduler.runCurrent()
        assertFalse("switch must wait for the remote transaction barrier", switching.isCompleted)
        assertTrue(host.events.contains("durable:remote-after-durable-save"))
        assertEquals(
            acceptanceCaptureCount,
            host.events.count { it == "capture:old-live-memory" }
        )

        requireNotNull(host.releasePersist).complete(Unit)
        testScheduler.advanceUntilIdle()
        val accepted = acceptance.await()
        val switched = switching.await()

        assertTrue(accepted is SyncOutcome.AppliedRemote)
        assertTrue(switched is com.example.myapplication.stage3.SwitchResult.Switched)
        assertEquals(
            remoteSnapshot,
            host.durableSnapshots[first.token.documentId]
        )
        assertEquals(remote.cursor, metadata.snapshot(scope)?.acceptedCursor)
        assertNull(metadata.snapshot(scope)?.conflictCursor)
        assertTrue(host.events.indexOf("apply:remote-after-durable-save") < host.events.indexOf("capture:remote-after-durable-save"))
        assertEquals(acceptanceCaptureCount, host.events.count { it == "capture:old-live-memory" })

        sync.closeAndJoin()
        stage3.close()
    }

    @Test
    fun import_usesSharedBarrier_thenQueuesExactlyOneCompleteCanonicalUpload() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val barrier = DocumentTransactionBarrier()
        val host = CrossStageHost()
        val stage3 = DocumentSwitchCoordinator(
            callbacks = host,
            parentScope = CoroutineScope(dispatcher + SupervisorJob()),
            coordinatorDispatcher = dispatcher,
            transactionBarrier = barrier
        )
        host.stage3 = stage3
        assertTrue(stage3.switchTo("content://stage3-barrier-a") is com.example.myapplication.stage3.SwitchResult.Switched)
        val first = requireNotNull(stage3.currentSession())
        val oldSnapshot = snapshot(first, "old-live-memory")
        host.setLive(oldSnapshot)
        host.events.clear()

        val scope = SyncScope("account@example.com", "root-import", first.token.documentId)
        val drive = FakeDriveGateway(idFactory = { "import-${host.nextId++}" })
        val metadata = InMemorySyncMetadataStore()
        val bridge = CrossStageBridge(host)
        val sync = SyncCoordinator(
            gateway = drive,
            metadataStore = metadata,
            bridge = bridge,
            parentScope = CoroutineScope(dispatcher + SupervisorJob()),
            dispatcher = dispatcher,
            documentTransactionBarrier = barrier
        )
        host.sync = sync
        bridge.bindingCurrent = sync::isBindingCurrent
        val binding = requireNotNull(sync.bind(scope, first.token))
        val imported = completeSnapshot(first, "imported-complete")

        val unboundImport = runCatching {
            sync.requireCurrentImportBinding(null, first.token)
        }
        assertTrue(unboundImport.isFailure)
        assertEquals("old-live-memory", host.liveMarker())
        assertNull(host.durableSnapshots[first.token.documentId])
        assertNull(metadata.snapshot(scope))
        assertTrue(drive.calls.none { it.operation == "upload" })

        val staleBindingImport = stage3.importCurrentSnapshot(
            token = first.token,
            snapshot = imported,
            currentSourceFingerprint = first.token.sourceFingerprint,
            isBindingCurrent = { false }
        )
        assertEquals(com.example.myapplication.stage3.SessionSnapshotApplyResult.Stale, staleBindingImport)
        assertTrue(host.events.isEmpty())

        val applied = stage3.importCurrentSnapshot(
            token = first.token,
            snapshot = imported,
            currentSourceFingerprint = first.token.sourceFingerprint,
            isBindingCurrent = { sync.isBindingCurrent(binding) }
        )
        val upload = sync.enqueueUpload(binding, SyncReason.IMPORT).await()

        assertEquals(com.example.myapplication.stage3.SessionSnapshotApplyResult.Applied, applied)
        assertTrue(upload is SyncOutcome.Uploaded)
        assertTrue(host.events.indexOf("durable:imported-complete") < host.events.indexOf("apply:imported-complete"))
        assertEquals(1, drive.calls.count { it.operation == "upload" })
        assertEquals(listOf(imported), bridge.capturedSnapshots)
        assertEquals(imported, drive.record(scope)?.snapshot)
        assertEquals(imported, host.durableSnapshots[first.token.documentId])
        assertEquals(
            (upload as SyncOutcome.Uploaded).remote.cursor,
            metadata.snapshot(scope)?.acceptedCursor
        )

        sync.closeAndJoin()
        stage3.close()
    }

    @Test
    fun importWithinDocumentTransaction_doesNotReacquireSharedBarrier() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val barrier = DocumentTransactionBarrier()
        val host = CrossStageHost()
        val stage3 = DocumentSwitchCoordinator(
            callbacks = host,
            parentScope = CoroutineScope(dispatcher + SupervisorJob()),
            coordinatorDispatcher = dispatcher,
            transactionBarrier = barrier
        )
        host.stage3 = stage3

        assertTrue(stage3.switchTo("content://stage3-barrier-a") is com.example.myapplication.stage3.SwitchResult.Switched)
        val session = requireNotNull(stage3.currentSession())
        val imported = completeSnapshot(session, "import-within-barrier")

        val result = barrier.withDocument(session.token.documentId) {
            stage3.importCurrentSnapshotWithinDocumentTransaction(
                token = session.token,
                snapshot = imported,
                currentSourceFingerprint = session.token.sourceFingerprint
            )
        }

        assertEquals(com.example.myapplication.stage3.SessionSnapshotApplyResult.Applied, result)
        assertEquals("import-within-barrier", host.liveMarker())
        assertEquals(imported, host.durableSnapshots[session.token.documentId])

        stage3.close()
    }

    @Test
    fun lifecycleFlushFailure_blocksRemoteMutation_andSuccessRequiresDurableFlush() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val barrier = DocumentTransactionBarrier()
        val host = CrossStageHost()
        val stage3 = DocumentSwitchCoordinator(
            callbacks = host,
            parentScope = CoroutineScope(dispatcher + SupervisorJob()),
            coordinatorDispatcher = dispatcher,
            transactionBarrier = barrier
        )
        host.stage3 = stage3
        assertTrue(stage3.switchTo("content://stage3-barrier-a") is com.example.myapplication.stage3.SwitchResult.Switched)
        val first = requireNotNull(stage3.currentSession())
        host.setLive(snapshot(first, "lifecycle-live"))
        host.events.clear()

        val scope = SyncScope("account@example.com", "root-lifecycle", first.token.documentId)
        val drive = FakeDriveGateway(idFactory = { "lifecycle-${host.nextId++}" })
        val metadata = InMemorySyncMetadataStore()
        val bridge = CrossStageBridge(host)
        val sync = SyncCoordinator(
            gateway = drive,
            metadataStore = metadata,
            bridge = bridge,
            parentScope = CoroutineScope(dispatcher + SupervisorJob()),
            dispatcher = dispatcher,
            documentTransactionBarrier = barrier
        )
        host.sync = sync
        bridge.bindingCurrent = sync::isBindingCurrent
        val binding = requireNotNull(sync.bind(scope, first.token))

        host.saveFailure = LocalRepositoryError.IoFailure("lifecycle", null, "injected")
        val failed = sync.enqueueLifecycleUploadAfterDurableFlush(
            binding = binding,
            isSessionCurrent = { stage3.isCurrent(first.token) },
            flushCurrent = { stage3.flushCurrent() }
        )
        assertTrue(failed is SyncOutcome.Failed)
        assertTrue(sync.status(scope)?.state is SyncState.Error)
        assertEquals(0, drive.calls.count { it.operation == "upload" })
        assertNull(metadata.snapshot(scope)?.acceptedCursor)
        assertNull(drive.record(scope))
        assertEquals("lifecycle-live", host.liveMarker())

        host.saveFailure = null
        val uploaded = sync.enqueueLifecycleUploadAfterDurableFlush(
            binding = binding,
            isSessionCurrent = { stage3.isCurrent(first.token) },
            flushCurrent = { stage3.flushCurrent() }
        )
        assertTrue(uploaded is SyncOutcome.Uploaded)
        assertEquals(1, drive.calls.count { it.operation == "upload" })
        assertTrue(metadata.snapshot(scope)?.acceptedCursor != null)
        assertEquals("lifecycle-live", drive.record(scope)?.snapshot?.pages?.getValue(0)?.notes?.single()?.text)

        sync.closeAndJoin()
        stage3.close()
    }

    @Test
    fun localImportAndLifecycleFlushRemainDurableWithoutDriveBinding() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val barrier = DocumentTransactionBarrier()
        val host = CrossStageHost()
        val stage3 = DocumentSwitchCoordinator(
            callbacks = host,
            parentScope = CoroutineScope(dispatcher + SupervisorJob()),
            coordinatorDispatcher = dispatcher,
            transactionBarrier = barrier
        )
        assertTrue(stage3.switchTo("content://stage3-barrier-a") is com.example.myapplication.stage3.SwitchResult.Switched)
        val session = requireNotNull(stage3.currentSession())
        val imported = completeSnapshot(session, "offline-import")

        val importedResult = stage3.importCurrentSnapshot(
            token = session.token,
            snapshot = imported,
            currentSourceFingerprint = session.token.sourceFingerprint,
            isBindingCurrent = { true }
        )

        assertEquals(com.example.myapplication.stage3.SessionSnapshotApplyResult.Applied, importedResult)
        assertEquals(imported, host.durableSnapshots[session.token.documentId])
        assertEquals("offline-import", host.liveMarker())

        host.setLive(completeSnapshot(session, "offline-lifecycle"))
        val flushed = stage3.flushCurrent()
        assertTrue(flushed is DocumentSaveResult.Saved)
        assertEquals(
            "offline-lifecycle",
            host.durableSnapshots[session.token.documentId]?.pages?.getValue(0)?.notes?.single()?.text
        )
        stage3.close()
    }

    private fun snapshot(session: DocumentSession, marker: String) = DocumentSnapshotV1(
        schemaVersion = 1,
        snapshotRevision = 0,
        source = session.target.association.source,
        pages = mapOf(0 to PageSnapshotV1(notes = listOf(NoteSnapshotV1(1f, 2f, marker, 12f, false, 0f))))
    )

    private fun completeSnapshot(session: DocumentSession, marker: String): DocumentSnapshotV1 {
        val shape = ShapeSnapshotV1(
            x = 10f,
            y = 20f,
            width = 30f,
            height = 40f,
            rotation = 5f,
            type = SnapshotShapeTypeV1.RECTANGLE,
            colorArgb = 0x0000FF,
            strokeWidth = 2f,
            isFilled = true,
            strokeWidthRatio = 0.01f,
            widthRatio = 0.2f,
            heightRatio = 0.3f,
            id = "shape-$marker"
        )
        val photoShape = shape.copy(id = "photo-shape-$marker")
        return DocumentSnapshotV1(
            schemaVersion = 1,
            snapshotRevision = 0,
            source = session.target.association.source,
            pages = mapOf(
                0 to PageSnapshotV1(
                    paths = listOf(
                        DrawnPathSnapshotV1(
                            points = listOf(PointSnapshotV1(1f, 2f), PointSnapshotV1(3f, 4f)),
                            colorArgb = 0xFF00FF,
                            strokeWidth = 3f,
                            isHighlighter = false
                        )
                    ),
                    measurements = listOf(
                        MeasurementSnapshotV1(PointSnapshotV1(5f, 6f), PointSnapshotV1(7f, 8f), "measure-$marker")
                    ),
                    notes = listOf(NoteSnapshotV1(9f, 10f, marker, 12f, true, 4f)),
                    photoPins = listOf(
                        PhotoPinSnapshotV1(
                            x = 11f,
                            y = 12f,
                            id = "photo-$marker",
                            imageFileNames = listOf("photo-$marker.jpg"),
                            imageNotes = mapOf(
                                "photo-$marker.jpg" to listOf(
                                    PhotoImageNoteSnapshotV1(1f, 2f, "image-note-$marker", 10f, false, 1f, 0.5f, "image-note-id-$marker")
                                )
                            ),
                            imageShapes = mapOf("photo-$marker.jpg" to listOf(photoShape))
                        )
                    ),
                    scale = PageScaleSnapshotV1(42f),
                    shapes = listOf(shape)
                ),
                2 to PageSnapshotV1()
            )
        )
    }

    private class Stage3Host : DocumentSessionCallbacks {
        private val targets = linkedMapOf<String, ResolvedDocumentTarget>()
        var active: DocumentSession? = null
        var live: DocumentSnapshotV1 = DocumentSnapshotV1(
            1,
            0,
            DocumentSourceIdentityV1("content://empty", "empty"),
            emptyMap()
        )
        var saveFailure: LocalRepositoryError? = null
        var applyCount: Int = 0

        init {
            add("content://stage3-one")
            add("content://stage3-two")
        }

        private fun add(uri: String) {
            val source = DocumentSourceIdentityV1(uri, "plan.pdf")
            targets[uri] = ResolvedDocumentTarget(
                DocumentAssociation(DocumentId.new(), source, null, "legacy-${uri.substringAfterLast('/')}.bin")
            )
        }

        override suspend fun resolveTarget(sourceUri: String): TargetResolution =
            targets[sourceUri]?.let(TargetResolution::Resolved)
                ?: TargetResolution.Failed(SwitchFailure(com.example.myapplication.stage3.SwitchFailureStage.RESOLVE_TARGET, "missing"))

        override fun captureSnapshot(session: DocumentSession): DocumentSnapshotV1 = live

        override suspend fun saveSnapshot(
            session: DocumentSession,
            frozenSnapshot: DocumentSnapshotV1
        ): DocumentSaveResult = saveFailure?.let(DocumentSaveResult::Failed)
            ?: DocumentSaveResult.Saved(session.token.documentId).also { live = frozenSnapshot }

        override suspend fun cancelAndJoinDocumentWork(session: DocumentSession) = Unit
        override fun invalidateDocumentWork(session: DocumentSession) = Unit
        override fun clearDocumentState() = Unit

        override fun establishSession(session: DocumentSession) {
            active = session
            live = DocumentSnapshotV1(1, 0, session.target.association.source, emptyMap())
        }

        override suspend fun loadTarget(session: DocumentSession): SessionLoadResult = SessionLoadResult.Empty()

        override fun applyLoadedSnapshot(session: DocumentSession, snapshot: DocumentSnapshotV1) {
            live = snapshot
            applyCount++
        }

        override fun onSwitchFailure(failure: SwitchFailure) = Unit
        override fun startDocumentBackgroundWork(session: DocumentSession) = Unit
        override fun resumeDocumentBackgroundWork(session: DocumentSession) = Unit
        override fun onAutosaveFailure(session: DocumentSession, result: DocumentSaveResult.Failed) = Unit
    }

    /** Real Stage 3 coordinator plus the Stage 4 bridge used by the regression. */
    private class CrossStageHost : DocumentSessionCallbacks {
        private val targets = linkedMapOf<String, ResolvedDocumentTarget>()
        val durableSnapshots = linkedMapOf<DocumentId, DocumentSnapshotV1>()
        val events = mutableListOf<String>()
        var stage3: DocumentSwitchCoordinator? = null
        var sync: SyncCoordinator? = null
        var persistStarted: CompletableDeferred<Unit>? = null
        var releasePersist: CompletableDeferred<Unit>? = null
        var saveFailure: LocalRepositoryError? = null
        var nextId: Int = 0
        private var active: DocumentSession? = null
        private var live: DocumentSnapshotV1 = emptySnapshot()

        init {
            add("content://stage3-barrier-a", "plan.pdf")
            add("content://stage3-barrier-b", "other.pdf")
        }

        private fun add(uri: String, displayName: String) {
            val source = DocumentSourceIdentityV1(uri, displayName)
            targets[uri] = ResolvedDocumentTarget(
                DocumentAssociation(
                    DocumentId.new(),
                    source,
                    SourceFingerprint.fromBytes(uri.toByteArray()),
                    "legacy-${nextId++}.bin"
                )
            )
        }

        override suspend fun resolveTarget(sourceUri: String): TargetResolution =
            targets[sourceUri]?.let(TargetResolution::Resolved)
                ?: TargetResolution.Failed(SwitchFailure(com.example.myapplication.stage3.SwitchFailureStage.RESOLVE_TARGET, "missing"))

        override fun captureSnapshot(session: DocumentSession): DocumentSnapshotV1 {
            events += "capture:${marker(live)}"
            return live
        }

        override fun captureSnapshotWithinDocumentTransaction(session: DocumentSession): DocumentSnapshotV1 =
            live

        override suspend fun saveSnapshot(
            session: DocumentSession,
            frozenSnapshot: DocumentSnapshotV1
        ): DocumentSaveResult {
            saveFailure?.let { return DocumentSaveResult.Failed(it) }
            durableSnapshots[session.token.documentId] = frozenSnapshot
            events += "durable:${marker(frozenSnapshot)}"
            persistStarted?.complete(Unit)
            releasePersist?.await()
            return DocumentSaveResult.Saved(session.token.documentId)
        }

        override suspend fun cancelAndJoinDocumentWork(session: DocumentSession) {
            sync?.cancelForSessionAndJoin(session.token)
        }

        override fun invalidateDocumentWork(session: DocumentSession) = Unit

        override fun clearDocumentState() {
            events += "clear"
            live = emptySnapshot(active?.target?.association?.source)
        }

        override fun establishSession(session: DocumentSession) {
            active = session
            events += "establish:${session.token.sourceUri.substringAfterLast('/')}"
            live = emptySnapshot(session.target.association.source)
        }

        override suspend fun loadTarget(session: DocumentSession): SessionLoadResult =
            durableSnapshots[session.token.documentId]?.let(SessionLoadResult::Loaded)
                ?: SessionLoadResult.Empty()

        override fun applyLoadedSnapshot(session: DocumentSession, snapshot: DocumentSnapshotV1) {
            live = snapshot
            events += "apply:${marker(snapshot)}"
        }

        override fun onSwitchFailure(failure: SwitchFailure) = Unit
        override fun startDocumentBackgroundWork(session: DocumentSession) = Unit
        override fun resumeDocumentBackgroundWork(session: DocumentSession) = Unit
        override fun onAutosaveFailure(session: DocumentSession, result: DocumentSaveResult.Failed) = Unit

        fun liveMarker(): String = marker(live)

        fun setLive(snapshot: DocumentSnapshotV1) {
            live = snapshot
        }

        private fun emptySnapshot(source: DocumentSourceIdentityV1? = null) = DocumentSnapshotV1(
            1,
            0,
            source ?: DocumentSourceIdentityV1("content://empty", "empty"),
            emptyMap()
        )

        private fun marker(snapshot: DocumentSnapshotV1): String =
            snapshot.pages[0]?.notes?.singleOrNull()?.text ?: "empty"
    }

    private class CrossStageBridge(
        private val host: CrossStageHost
    ) : SyncSessionBridge {
        var bindingCurrent: (SyncBinding) -> Boolean = { true }
        val capturedSnapshots = mutableListOf<DocumentSnapshotV1>()

        override fun currentSession(scope: SyncScope): DocumentSession? =
            host.stage3?.currentSession()?.takeIf { it.token.documentId == scope.documentId }

        override suspend fun captureSnapshot(session: DocumentSession): DocumentSnapshotV1? =
            host.stage3?.captureCurrentSnapshotWithinDocumentTransaction(session.token)
                ?.also(capturedSnapshots::add)

        override suspend fun captureSnapshotWithinDocumentTransaction(session: DocumentSession): DocumentSnapshotV1? =
            host.stage3?.captureCurrentSnapshotWithinDocumentTransaction(session.token)

        override suspend fun captureDurableSnapshot(session: DocumentSession): DocumentSnapshotV1? =
            host.durableSnapshots[session.token.documentId]

        override suspend fun persistSnapshot(
            session: DocumentSession,
            snapshot: DocumentSnapshotV1
        ): DocumentSaveResult = host.stage3?.persistCurrentSnapshot(session.token, snapshot)
            ?: DocumentSaveResult.Failed(LocalRepositoryError.InvalidSnapshot("stage 3 is closed"))

        override fun isCurrent(token: DocumentSessionToken): Boolean = host.stage3?.isCurrent(token) == true

        override fun hasRequiredPhotoContent(snapshot: DocumentSnapshotV1): Boolean = true

        override suspend fun capturePhotoContent(snapshot: DocumentSnapshotV1): Map<String, ByteArray> =
            requiredPhotoFileNames(snapshot).associateWith { Stage4PhotoFixture.jpegBytes() }

        override suspend fun persistPhotoContent(
            session: DocumentSession,
            remote: RemoteSnapshotEnvelope
        ): DocumentSaveResult {
            validatedPhotoFiles(remote.snapshot, remote.photoFiles)
            return DocumentSaveResult.Saved(session.token.documentId)
        }

        override fun applySnapshotReplace(session: DocumentSession, snapshot: DocumentSnapshotV1) {
            check(isCurrent(session.token))
            host.applyLoadedSnapshot(session, snapshot)
        }

        override suspend fun persistAndApplySnapshotWithinDocumentTransaction(
            binding: SyncBinding,
            session: DocumentSession,
            snapshot: DocumentSnapshotV1
        ): SnapshotApplyResult = when (
            val result = host.stage3?.persistAndApplyCurrentSnapshotWithinDocumentTransaction(
                binding.token,
                snapshot
            ) { bindingCurrent(binding) } ?: com.example.myapplication.stage3.SessionSnapshotApplyResult.Stale
        ) {
            com.example.myapplication.stage3.SessionSnapshotApplyResult.Applied -> SnapshotApplyResult.Applied
            com.example.myapplication.stage3.SessionSnapshotApplyResult.Stale -> SnapshotApplyResult.Stale
            is com.example.myapplication.stage3.SessionSnapshotApplyResult.Failed -> SnapshotApplyResult.Failed(result.error)
        }

        override suspend fun restoreSnapshotWithinDocumentTransaction(
            binding: SyncBinding,
            session: DocumentSession,
            durableSnapshot: DocumentSnapshotV1,
            liveSnapshot: DocumentSnapshotV1
        ): SnapshotApplyResult = when (
            val result = host.stage3?.restoreSnapshotWithinDocumentTransaction(
                binding.token,
                durableSnapshot,
                liveSnapshot
            ) { bindingCurrent(binding) } ?: com.example.myapplication.stage3.SessionSnapshotApplyResult.Stale
        ) {
            com.example.myapplication.stage3.SessionSnapshotApplyResult.Applied -> SnapshotApplyResult.Applied
            com.example.myapplication.stage3.SessionSnapshotApplyResult.Stale -> SnapshotApplyResult.Stale
            is com.example.myapplication.stage3.SessionSnapshotApplyResult.Failed -> SnapshotApplyResult.Failed(result.error)
        }
    }

    private fun marker(snapshot: DocumentSnapshotV1): String =
        snapshot.pages[0]?.notes?.singleOrNull()?.text ?: "empty"
}
