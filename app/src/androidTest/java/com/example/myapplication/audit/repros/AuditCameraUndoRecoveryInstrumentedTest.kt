package com.example.myapplication.audit.repros

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.CameraCaptureSessionHost
import com.example.myapplication.CameraCaptureSessionOwner
import com.example.myapplication.PhotoPin
import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.LocalRepositoryError
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage3.DocumentSession
import com.example.myapplication.stage3.DocumentSessionCallbacks
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentSwitchCoordinator
import com.example.myapplication.stage3.DocumentTransactionBarrier
import com.example.myapplication.stage3.ResolvedDocumentTarget
import com.example.myapplication.stage3.SessionLoadResult
import com.example.myapplication.stage3.SwitchResult
import com.example.myapplication.stage3.TargetResolution
import com.example.myapplication.stage5.CameraCaptureOperationStatus
import com.example.myapplication.stage5.CameraCaptureStore
import com.example.myapplication.stage5.DocumentPhotoAssetStore
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage7.Stage7WorkerResourceBoundary
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Audit regression probe for the real production camera operation owner.
 * Uses the packaged synthetic JPEG without launching a physical camera or
 * touching user files. A pending publication must protect history even after
 * dialog dismissal; explicit discard or successful commit releases history.
 */
@RunWith(AndroidJUnit4::class)
class AuditCameraUndoRecoveryInstrumentedTest {
    @Test
    fun publishedCaptureBlocksUndoUntilExplicitDiscardEvenAfterDialogDismissal() = runBlocking {
        exerciseCapture(failPostAttachSave = true)
    }

    @Test
    fun committedCaptureAllowsUndoWithoutLaterReattachment() = runBlocking {
        exerciseCapture(failPostAttachSave = false)
    }

    private suspend fun exerciseCapture(failPostAttachSave: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val jpeg = instrumentation.context.assets
            .open("stage7/photos/small_valid_photo.jpg")
            .use { it.readBytes() }
        val filesDirectory = File(
            targetContext.cacheDir,
            "audit-camera-undo-recovery-${UUID.randomUUID()}"
        )
        check(filesDirectory.mkdirs()) { "could not create owned camera fixture directory" }

        val sourceUri = "content://synthetic/audit-camera-undo.pdf"
        val source = DocumentSourceIdentityV1(sourceUri, "audit-camera-undo.pdf")
        val association = DocumentAssociation(
            documentId = DocumentId.new(),
            source = source,
            sourceFingerprint = SourceFingerprint.fromBytes("synthetic audit PDF".toByteArray())
        )
        val target = ResolvedDocumentTarget(association)
        val state = UndoRecoveryState()
        val barrier = DocumentTransactionBarrier()
        val callbacks = UndoRecoveryCallbacks(target, state, failPostAttachSave)
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = DocumentSwitchCoordinator(
            callbacks = callbacks,
            parentScope = coordinatorScope,
            debounceMillis = 60_000L,
            coordinatorDispatcher = Dispatchers.Default,
            transactionBarrier = barrier
        )
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = UndoRecoveryHost(filesDirectory, coordinator, state, jpeg)
        val owner = CameraCaptureSessionOwner(
            filesDirectory = filesDirectory,
            worker = Stage7WorkerResourceBoundary(),
            sessionCoordinator = coordinator,
            documentTransactionBarrier = barrier,
            scope = ownerScope,
            hostProvider = { host }
        )

        try {
            val switch = withTimeout(TIMEOUT_MILLIS) { coordinator.switchTo(sourceUri) }
            assertTrue("synthetic source did not establish a session", switch is SwitchResult.Switched)

            val pin = PhotoPin(
                x = 0.5f,
                y = 0.5f,
                id = UUID.randomUUID().toString()
            )
            state.pin = pin.copyPin()

            val captureJob = assertNotNullJob(
                owner.requestCapture(requestPageIndex = 0, requestPinId = pin.id)
            )
            withTimeout(TIMEOUT_MILLIS) { captureJob.join() }

            val operationId = requireNotNull(host.returnedOperationId) {
                "production owner did not dispatch the synthetic camera result"
            }
            val prepared = CameraCaptureStore(filesDirectory).use { it.readOperation() }
            val photoFileName = requireNotNull(prepared?.publishedPhotoFileName)

            // The first drain attaches through the actual owner. The fake save
            // boundary fails only once, and only for the snapshot containing
            // the new photo reference, leaving its durable record PUBLISHED.
            withTimeout(TIMEOUT_MILLIS) { owner.drain(operationId) }
            assertEquals(if (failPostAttachSave) 1 else 0, callbacks.failedPostAttachSaves)
            assertEquals(1, host.attachCount)
            if (failPostAttachSave) {
                assertEquals(
                    CameraCaptureOperationStatus.PUBLISHED,
                    CameraCaptureStore(filesDirectory).use { it.readOperation()?.status }
                )
            } else {
                assertEquals(null, CameraCaptureStore(filesDirectory).use { it.readOperation() })
            }
            assertTrue(photoFileName in requireNotNull(state.pin).imageFileNames)
            DocumentPhotoAssetStore(filesDirectory, association.documentId).use { photos ->
                assertArrayEquals(jpeg, photos.read(photoFileName))
            }

            val token = requireNotNull(host.readySessionToken())
            if (failPostAttachSave) {
                host.clearRecovery() // "Later" dismisses UI, not journal authority.
                assertTrue(owner.isAnnotationHistoryBlocked(pageIndex = 0))
                val blocked = owner.runAnnotationHistoryActionIfSafe(0, token) {
                    host.undoPhotoAttachment(photoFileName)
                }
                assertFalse("pending publication must reject Undo", blocked)
                assertTrue(photoFileName in requireNotNull(state.pin).imageFileNames)
                withTimeout(TIMEOUT_MILLIS) { owner.requestRecoveryDiscard(operationId).join() }
            }
            assertFalse(owner.isAnnotationHistoryBlocked(pageIndex = 0))
            assertTrue(owner.runAnnotationHistoryActionIfSafe(0, token) {
                host.undoPhotoAttachment(photoFileName)
            })
            assertEquals(pin.id, requireNotNull(state.pin).id)
            assertFalse(photoFileName in requireNotNull(state.pin).imageFileNames)

            // Models the later ON_RESUME journal wake-up in the same session.
            withTimeout(TIMEOUT_MILLIS) { owner.drain() }

            assertFalse(
                "a retained PUBLISHED camera operation must not resurrect an undone photo",
                photoFileName in requireNotNull(state.pin).imageFileNames
            )
            assertEquals("Undo was overwritten by retry", 1, host.attachCount)
        } finally {
            ownerScope.cancel()
            withTimeout(TIMEOUT_MILLIS) { coordinator.closeAndJoin() }
            coordinatorScope.cancel()
            check(filesDirectory.deleteRecursively() || !filesDirectory.exists()) {
                "owned camera fixture directory could not be removed"
            }
        }
    }

    private class UndoRecoveryState {
        @Volatile var pin: PhotoPin? = null

        fun snapshot(source: DocumentSourceIdentityV1): DocumentSnapshotV1 {
            val photoPins = pin?.let { value ->
                listOf(
                    PhotoPinSnapshotV1(
                        x = value.x,
                        y = value.y,
                        id = value.id,
                        imageFileNames = value.imageFileNames.toList(),
                        imageNotes = emptyMap(),
                        imageShapes = emptyMap(),
                        imagePaths = emptyMap(),
                        imageMeasurements = emptyMap(),
                        imageScales = emptyMap()
                    )
                )
            }.orEmpty()
            return DocumentSnapshotV1(
                schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
                snapshotRevision = 0L,
                source = source,
                pages = mapOf(0 to PageSnapshotV1(photoPins = photoPins))
            )
        }
    }

    private class UndoRecoveryCallbacks(
        private val target: ResolvedDocumentTarget,
        private val state: UndoRecoveryState,
        private var failOnePostAttachSave: Boolean
    ) : DocumentSessionCallbacks {
        @Volatile var failedPostAttachSaves: Int = 0
            private set

        override suspend fun resolveTarget(sourceUri: String): TargetResolution =
            if (sourceUri == target.association.source.sourceUri) {
                TargetResolution.Resolved(target)
            } else {
                TargetResolution.Failed(
                    com.example.myapplication.stage3.SwitchFailure(
                        com.example.myapplication.stage3.SwitchFailureStage.RESOLVE_TARGET,
                        "unexpected synthetic source"
                    )
                )
            }

        override fun captureSnapshot(session: DocumentSession): DocumentSnapshotV1 =
            state.snapshot(session.target.association.source)

        override suspend fun saveSnapshot(
            session: DocumentSession,
            frozenSnapshot: DocumentSnapshotV1
        ): DocumentSaveResult {
            val containsPhoto = frozenSnapshot.pages.values
                .flatMap { it.photoPins }
                .any { it.imageFileNames.isNotEmpty() }
            if (containsPhoto && failOnePostAttachSave) {
                failOnePostAttachSave = false
                failedPostAttachSaves++
                return DocumentSaveResult.Failed(
                    LocalRepositoryError.IoFailure(
                        operation = "synthetic post-attach save",
                        path = null,
                        detail = "one-shot audit fault"
                    )
                )
            }
            return DocumentSaveResult.Saved(session.token.documentId)
        }

        override suspend fun cancelAndJoinDocumentWork(session: DocumentSession) = Unit
        override fun invalidateDocumentWork(session: DocumentSession) = Unit
        override fun clearDocumentState() { state.pin = null }
        override fun establishSession(session: DocumentSession) = Unit
        override suspend fun loadTarget(session: DocumentSession): SessionLoadResult =
            SessionLoadResult.Empty(pageCount = 1)
        override fun applyLoadedSnapshot(session: DocumentSession, snapshot: DocumentSnapshotV1) = Unit
    }

    private class UndoRecoveryHost(
        private val filesDirectory: File,
        private val coordinator: DocumentSwitchCoordinator,
        private val state: UndoRecoveryState,
        private val jpeg: ByteArray
    ) : CameraCaptureSessionHost {
        @Volatile var returnedOperationId: String? = null
            private set
        @Volatile var attachCount: Int = 0
            private set
        private var recoveryOperationId: String? = null
        private var recoveryOperation: com.example.myapplication.stage5.CameraCaptureOperationRecord? = null

        override fun activeSessionToken(): DocumentSessionToken? = coordinator.currentSession()?.token
        override fun readySessionToken(): DocumentSessionToken? = activeSessionToken()
            ?.takeIf(coordinator::isCurrentApplied)
        override fun selectedPageIndex(): Int = 0
        override fun photoPin(pageIndex: Int, pinId: String): PhotoPin? =
            state.pin?.takeIf { pageIndex == 0 && it.id == pinId }
        override fun allPhotoReferences(): Set<String> =
            state.pin?.imageFileNames?.toSet().orEmpty()

        override fun canAttachPhoto(pageIndex: Int, pinId: String, fileName: String?): Boolean {
            val pin = photoPin(pageIndex, pinId) ?: return false
            if (fileName != null && fileName in pin.imageFileNames) return false
            val total = state.pin?.imageFileNames?.size ?: 0
            return pin.imageFileNames.size < Stage5Limits.MAX_PHOTOS_PER_PIN &&
                total < Stage5Limits.MAX_TOTAL_PHOTOS
        }

        override fun attachPhoto(pageIndex: Int, pin: PhotoPin, fileName: String): Boolean {
            if (pageIndex != 0 || state.pin != pin || !canAttachPhoto(pageIndex, pin.id, fileName)) {
                return false
            }
            state.pin = pin.copy(imageFileNames = pin.imageFileNames + fileName).copyPin()
            attachCount++
            coordinator.markDocumentDirty()
            return true
        }

        /** Represents an undoable replacement back to the exact pre-capture pin. */
        fun undoPhotoAttachment(photoFileName: String) {
            val current = requireNotNull(state.pin)
            check(photoFileName in current.imageFileNames)
            state.pin = current.copy(
                imageFileNames = current.imageFileNames.filterNot { it == photoFileName }
            ).copyPin()
            coordinator.markDocumentDirty()
        }

        override fun recoveryOperationId(): String? = recoveryOperationId
        override fun recoveryOperation(): com.example.myapplication.stage5.CameraCaptureOperationRecord? =
            recoveryOperation
        override fun showRecovery(
            operation: com.example.myapplication.stage5.CameraCaptureOperationRecord?,
            message: String
        ) {
            recoveryOperation = operation
            recoveryOperationId = operation?.operationId
        }
        override fun clearRecovery() {
            recoveryOperation = null
            recoveryOperationId = null
        }
        override fun notifyRecoveryChanged() = Unit
        override fun showPhotoLimitReached() = error("synthetic photo pin unexpectedly reached capacity")
        override fun showMessage(message: String) = Unit
        override fun launchCamera(operationId: String) {
            CameraCaptureStore(filesDirectory).use { store ->
                store.markLaunched(operationId)
                store.captureFile(operationId).writeBytes(jpeg)
                store.recordResult(operationId, success = true)
            }
            returnedOperationId = operationId
        }
        override fun triggerPhotoSync() = Unit
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
    }
}

private fun assertNotNullJob(job: kotlinx.coroutines.Job?): kotlinx.coroutines.Job {
    assertNotNull("production owner rejected the ready synthetic photo pin", job)
    return requireNotNull(job)
}
