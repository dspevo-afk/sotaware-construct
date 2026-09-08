package com.example.myapplication.stage9a

import androidx.compose.runtime.mutableStateListOf
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.HistoryAction
import com.example.myapplication.PhotoPin
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.NoteSnapshotV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage1.applySnapshotReplace
import com.example.myapplication.stage1.snapshotFromState
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentLoadResult
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.LocalDocumentRepository
import com.example.myapplication.stage3.DocumentSession
import com.example.myapplication.stage3.DocumentSessionCallbacks
import com.example.myapplication.stage3.DocumentSwitchCoordinator
import com.example.myapplication.stage3.SessionLoadResult
import com.example.myapplication.stage3.SessionSnapshotApplyResult
import com.example.myapplication.stage3.SwitchFailure
import com.example.myapplication.stage3.SwitchFailureStage
import com.example.myapplication.stage3.TargetResolution
import com.example.myapplication.stage3.ResolvedDocumentTarget
import com.example.myapplication.stage3.SwitchResult
import com.example.myapplication.stage5.DocumentPhotoAssetStore
import com.example.myapplication.stage5.ImageIoPhotoDecodeProbe
import com.example.myapplication.stage5.PhotoRetentionAuthority
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage8.AnnotationReducer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Stage 9A TESTS-FIRST contract for one coherent history/retention owner.
 *
 * A same-session accepted replacement must advance a replacement epoch and
 * invalidate entries captured against the replaced live state. An equal
 * canonical persistence must not advance that epoch or discard a valid undo.
 * Every history entry that can restore a generated photo must also contribute
 * its retained names to the post-commit cleanup set. The cleanup set must be
 * the union of current durable/live authorities, previous-good recovery state,
 * and the history owner's retained references.
 *
 * The tests intentionally call the production coordinator, mapper, reducer,
 * repository, and post-commit photo-cleanup seams so the assertions exercise
 * the actual history and retention boundaries.
 */
class HistoryRetentionRegressionTest {
    @Test
    fun acceptedSameSessionReplacement_invalidatesClearUndoBeforeItCanOverwriteIncoming() = runBlocking {
        val source = source("replacement")
        val initial = snapshotWithNote(source, "before-clear")
        val incoming = snapshotWithNote(source, "accepted-canonical")

        withActiveSession(initial) { harness, coordinator, session ->
            val reducer = AnnotationReducer(
                vm = harness.vm,
                sessionKey = session.token,
                currentSessionKey = { coordinator.currentSession()?.token },
                sessionActivePredicate = { coordinator.isCurrentApplied(session.token) }
            )

            assertTrue(reducer.clearPage(0))
            assertTrue(reducer.canUndo(0))

            val applied = coordinator.persistAndApplyCurrentSnapshot(
                token = session.token,
                snapshot = incoming
            )
            assertTrue(applied is SessionSnapshotApplyResult.Applied)

            // The old ClearPageEntry must not be allowed to restore the
            // pre-replacement page over the accepted canonical snapshot.
            assertFalse(reducer.undo(0))
            assertEquals(incoming, snapshotFromState(harness.vm, source))
        }
    }

    @Test
    fun failedReplacementRollback_restoresOutgoingHistoryAfterLiveStateCompensation() = runBlocking {
        val source = source("rollback")
        val initial = snapshotWithNote(source, "before-clear")
        val incoming = snapshotWithNote(source, "accepted-canonical")

        withActiveSession(initial, rollbackHistory = true) { harness, coordinator, session ->
            val reducer = AnnotationReducer(
                vm = harness.vm,
                sessionKey = session.token,
                currentSessionKey = { coordinator.currentSession()?.token },
                sessionActivePredicate = { coordinator.isCurrentApplied(session.token) }
            )
            assertTrue(reducer.clearPage(0))
            val outgoingLive = snapshotFromState(harness.vm, source)
            assertTrue(reducer.canUndo(0))

            assertTrue(
                coordinator.persistAndApplyCurrentSnapshot(session.token, incoming) is
                    SessionSnapshotApplyResult.Applied
            )
            assertFalse(reducer.undo(0))

            val restored = coordinator.restoreSnapshotWithinDocumentTransaction(
                token = session.token,
                durableSnapshot = initial,
                liveSnapshot = outgoingLive
            )
            assertTrue(restored is SessionSnapshotApplyResult.Applied)
            assertEquals(outgoingLive, snapshotFromState(harness.vm, source))
            assertTrue(reducer.undo(0))
            assertEquals(initial, snapshotFromState(harness.vm, source))
        }
    }

    @Test
    fun identicalCanonicalPersistence_preservesValidBlueprintViewModelUndo() = runBlocking {
        val source = source("identical")
        val initial = snapshotWithNote(source, "ordinary-state")

        withActiveSession(initial) { harness, coordinator, session ->
            val note = harness.vm.pageNotes.getValue(0).single()
            harness.vm.addAction(0, HistoryAction.AddNote(note))
            assertTrue(harness.vm.canUndo(0))

            // This is the current-document persistence/apply route with an
            // equal snapshot, not a changed remote replacement.
            val identical = snapshotFromState(harness.vm, source)
            val applied = coordinator.persistAndApplyCurrentSnapshot(
                token = session.token,
                snapshot = identical
            )
            assertTrue(applied is SessionSnapshotApplyResult.Applied)

            assertTrue(harness.vm.canUndo(0))
            harness.vm.undo(0)
            assertTrue(harness.vm.pageNotes.getValue(0).isEmpty())
        }
    }

    @Test
    fun deletingOneOfSeveralPhotoPins_postCommitCleanup_keepsUndoBytesExact() {
        val filesRoot = Files.createTempDirectory("stage9a-history-photo-delete").toFile()
        val documentId = DocumentId.new()
        val store = DocumentPhotoAssetStore(
            filesDirectory = filesRoot,
            documentId = documentId,
            imageProbe = ImageIoPhotoDecodeProbe,
            operationsFactory = TestPhotoPathOperationsFactory
        )
        try {
            val deletedBytes = Stage4PhotoFixture.previousJpegBytes()
            val retainedBytes = Stage4PhotoFixture.incomingJpegBytes()
            val deletedReference = store.publishNewPhoto(deletedBytes).also(store::releasePhotoPublication)
            val retainedReference = store.publishNewPhoto(retainedBytes).also(store::releasePhotoPublication)
            val source = source("photo-delete")
            val vm = BlueprintViewModel()
            vm.pagePhotoPins[0] = mutableStateListOf(
                PhotoPin(
                    x = 0.2f,
                    y = 0.3f,
                    id = "deleted-pin",
                    imageFileNames = mutableListOf(deletedReference)
                ),
                PhotoPin(
                    x = 0.7f,
                    y = 0.8f,
                    id = "retained-pin",
                    imageFileNames = mutableListOf(retainedReference)
                )
            )
            val reducer = AnnotationReducer(vm)
            val deletedPin = vm.pagePhotoPins.getValue(0).first { it.id == "deleted-pin" }

            assertTrue(reducer.deletePhotoPin(0, deletedPin))
            val afterDelete = snapshotFromState(vm, source)

            // This is the production post-canonical-commit cleanup seam used
            // by MainActivity's cleanupPhotoContentAfterCanonicalCommit and
            // the SyncSessionBridge cleanup callback.
            store.cleanupAfterCanonicalCommit(
                PhotoRetentionAuthority(
                    currentDurableSnapshot = afterDelete,
                    currentLiveSnapshot = afterDelete,
                    retainedPhotoNames = vm.retainedPhotoNamesForPhotoRetention()
                )
            )

            assertTrue(reducer.undo(0))
            assertTrue(vm.pagePhotoPins.getValue(0).any { it.id == "deleted-pin" })
            assertEquals(deletedBytes.toList(), store.read(deletedReference).toList())
            assertEquals(retainedBytes.toList(), store.read(retainedReference).toList())
        } finally {
            store.close()
            filesRoot.deleteRecursively()
        }
    }

    @Test
    fun incompleteCleanupAuthority_retainsUnknownGeneratedPhotoUntilExplicitUnionIsSupplied() {
        val root = Files.createTempDirectory("stage9a-history-unknown-authority").toFile()
        val store = DocumentPhotoAssetStore(
            filesDirectory = root,
            documentId = DocumentId.new(),
            imageProbe = ImageIoPhotoDecodeProbe,
            operationsFactory = TestPhotoPathOperationsFactory
        )
        try {
            val reference = store.publishNewPhoto(Stage4PhotoFixture.previousJpegBytes())
                .also(store::releasePhotoPublication)
            val snapshot = snapshotFromState(BlueprintViewModel(), source("unknown-authority"))

            // The compatibility pair does not include previous-good, history,
            // or capture reachability, so it must not collect an unknown file.
            store.cleanupAfterCanonicalCommit(snapshot, snapshot)
            assertTrue(store.resolver.resolve(reference).isFile)

            // Once the caller proves the full authority set, the same orphan
            // is eligible for the production GC boundary.
            store.cleanupAfterCanonicalCommit(
                PhotoRetentionAuthority(
                    currentDurableSnapshot = snapshot,
                    currentLiveSnapshot = snapshot
                )
            )
            assertFalse(store.resolver.resolve(reference).exists())
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun previousGoodSnapshot_recoveryAndPostCommitCleanup_keepItsPhotoBytes() = runBlocking {
        val root = Files.createTempDirectory("stage9a-history-previous-good").toFile()
        val filesRoot = File(root, "files").apply { check(mkdirs()) }
        val repositoryRoot = File(root, "repository")
        val documentId = DocumentId.new()
        val store = DocumentPhotoAssetStore(
            filesDirectory = filesRoot,
            documentId = documentId,
            imageProbe = ImageIoPhotoDecodeProbe,
            operationsFactory = TestPhotoPathOperationsFactory
        )
        try {
            val previousBytes = Stage4PhotoFixture.previousJpegBytes()
            val currentBytes = Stage4PhotoFixture.incomingJpegBytes()
            val previousReference = store.publishNewPhoto(previousBytes).also(store::releasePhotoPublication)
            val currentReference = store.publishNewPhoto(currentBytes).also(store::releasePhotoPublication)
            val source = source("previous-good")
            val association = DocumentAssociation(
                documentId = documentId,
                source = source,
                sourceFingerprint = null,
                legacyArtifactName = "stage9a-previous-good.json"
            )
            val previousSnapshot = snapshotWithPhoto(source, previousReference, "previous-pin")
            val currentSnapshot = snapshotWithPhoto(source, currentReference, "current-pin")
            val repository = LocalDocumentRepository(repositoryRoot)

            assertTrue(repository.save(association, previousSnapshot) is DocumentSaveResult.Saved)
            assertTrue(repository.save(association, currentSnapshot) is DocumentSaveResult.Saved)
            val durableBeforeFailure = repository.captureDurableSnapshotState(association)
            assertEquals(currentSnapshot, durableBeforeFailure.current?.snapshot)
            assertEquals(previousSnapshot, durableBeforeFailure.previous?.snapshot)

            // The current/live pair no longer names the previous-good photo,
            // but the exact previous-good durable slot is still an authority.
            // Cleanup must not collect that sidecar before recovery below.
            store.cleanupAfterCanonicalCommit(
                PhotoRetentionAuthority(
                    currentDurableSnapshot = currentSnapshot,
                    currentLiveSnapshot = currentSnapshot,
                    previousDurableSnapshot = previousSnapshot
                )
            )
            assertEquals(previousBytes.toList(), store.read(previousReference).toList())

            repository.currentSnapshotFile(documentId).writeText("corrupt current snapshot")
            val recovered = repository.load(association)
            assertTrue(recovered is DocumentLoadResult.Loaded)
            val loaded = recovered as DocumentLoadResult.Loaded
            assertEquals(previousSnapshot, loaded.snapshot)
            assertTrue(loaded.recoveredFromPrevious)
            assertEquals(previousBytes.toList(), store.read(previousReference).toList())

            val durableAfterRecovery = repository.captureDurableSnapshotState(association)
            assertEquals(previousSnapshot, durableAfterRecovery.current?.snapshot)
            assertEquals(previousSnapshot, durableAfterRecovery.previous?.snapshot)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    private class CanonicalSessionHarness(
        private val initialSnapshot: DocumentSnapshotV1,
        val association: DocumentAssociation,
        private val rollbackHistory: Boolean = false
    ) {
        val vm = BlueprintViewModel()
        var durableSnapshot: DocumentSnapshotV1 = initialSnapshot

        val callbacks: DocumentSessionCallbacks = object : DocumentSessionCallbacks {
            override suspend fun resolveTarget(sourceUri: String): TargetResolution {
                return if (sourceUri == association.source.sourceUri) {
                    TargetResolution.Resolved(ResolvedDocumentTarget(association))
                } else {
                    TargetResolution.Failed(
                        SwitchFailure(
                            stage = SwitchFailureStage.RESOLVE_TARGET,
                            detail = "unexpected test source: $sourceUri"
                        )
                    )
                }
            }

            override fun captureSnapshot(session: DocumentSession): DocumentSnapshotV1 =
                snapshotFromState(vm, session.target.association.source)

            override suspend fun captureDurableSnapshot(session: DocumentSession): DocumentSnapshotV1 =
                durableSnapshot

            override suspend fun saveSnapshot(
                session: DocumentSession,
                frozenSnapshot: DocumentSnapshotV1
            ): DocumentSaveResult {
                require(frozenSnapshot.source.sourceUri == association.source.sourceUri)
                durableSnapshot = frozenSnapshot
                return DocumentSaveResult.Saved(association.documentId)
            }

            override suspend fun cancelAndJoinDocumentWork(session: DocumentSession) = Unit

            override fun invalidateDocumentWork(session: DocumentSession) = Unit

            override fun clearDocumentState() {
                vm.clearSession()
            }

            override fun establishSession(session: DocumentSession) = Unit

            override suspend fun loadTarget(session: DocumentSession): SessionLoadResult =
                SessionLoadResult.Loaded(initialSnapshot, pageCount = 1)

            override fun applyLoadedSnapshot(session: DocumentSession, snapshot: DocumentSnapshotV1) {
                applySnapshotReplace(snapshot, vm)
            }

            override fun applyRollbackSnapshot(session: DocumentSession, snapshot: DocumentSnapshotV1) {
                applySnapshotReplace(
                    snapshot = snapshot,
                    vm = vm,
                    preserveHistoryOnRollback = rollbackHistory
                )
            }
        }
    }

    private suspend fun <T> withActiveSession(
        initialSnapshot: DocumentSnapshotV1,
        rollbackHistory: Boolean = false,
        block: suspend (
            harness: CanonicalSessionHarness,
            coordinator: DocumentSwitchCoordinator,
            session: DocumentSession
        ) -> T
    ): T {
        val documentId = DocumentId.new()
        val association = DocumentAssociation(
            documentId = documentId,
            source = initialSnapshot.source,
            sourceFingerprint = null,
            legacyArtifactName = "stage9a-${documentId.value}.json"
        )
        val harness = CanonicalSessionHarness(initialSnapshot, association, rollbackHistory)
        val parentJob = SupervisorJob()
        val parentScope = CoroutineScope(parentJob + Dispatchers.Unconfined)
        val coordinator = DocumentSwitchCoordinator(
            callbacks = harness.callbacks,
            parentScope = parentScope,
            debounceMillis = 60_000L,
            coordinatorDispatcher = Dispatchers.Unconfined
        )
        try {
            assertTrue(coordinator.switchTo(initialSnapshot.source.sourceUri) is SwitchResult.Switched)
            val session = requireNotNull(coordinator.currentSession())
            return block(harness, coordinator, session)
        } finally {
            coordinator.closeAndJoin()
            parentJob.cancel()
        }
    }

    private fun source(label: String): DocumentSourceIdentityV1 =
        DocumentSourceIdentityV1(
            sourceUri = "content://stage9a/$label",
            displayName = "stage9a-$label.pdf"
        )

    private fun snapshotWithNote(
        source: DocumentSourceIdentityV1,
        text: String
    ): DocumentSnapshotV1 = DocumentSnapshotV1(
        schemaVersion = 1,
        snapshotRevision = 0L,
        source = source,
        pages = mapOf(
            0 to PageSnapshotV1(
                notes = listOf(
                    NoteSnapshotV1(
                        x = 0.25f,
                        y = 0.75f,
                        text = text,
                        fontSize = 16f,
                        isBold = false,
                        rotation = 0f
                    )
                )
            )
        )
    )

    private fun snapshotWithPhoto(
        source: DocumentSourceIdentityV1,
        reference: String,
        pinId: String
    ): DocumentSnapshotV1 = DocumentSnapshotV1(
        schemaVersion = 1,
        snapshotRevision = 0L,
        source = source,
        pages = mapOf(
            0 to PageSnapshotV1(
                photoPins = listOf(
                    PhotoPinSnapshotV1(
                        x = 0.5f,
                        y = 0.5f,
                        id = pinId,
                        imageFileNames = listOf(reference),
                        imageNotes = emptyMap(),
                        imageShapes = emptyMap()
                    )
                )
            )
        )
    )
}
