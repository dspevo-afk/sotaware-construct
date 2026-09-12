package com.example.myapplication.stage10

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage5.CameraCaptureOperationRecord
import com.example.myapplication.stage5.CameraCaptureOperationStatus
import com.example.myapplication.stage5.CameraCaptureJournalReconciliationDisposition
import com.example.myapplication.stage5.CameraCaptureOperationStore
import com.google.gson.GsonBuilder
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable on-device proof for the interrupted camera publication seam. */
@RunWith(AndroidJUnit4::class)
class CameraStagedRecoveryInstrumentedTest {
    @Test
    fun completeStagedRevision_reconcilesAfterStoreRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "camera-staged-recovery-").toFile()
        try {
            lateinit var staged: CameraCaptureOperationRecord
            CameraCaptureOperationStore(root).use { store ->
                val request = com.example.myapplication.stage5.CameraCaptureOperationRequest(
                    processInstanceId = UUID.randomUUID().toString(),
                    documentId = DocumentId.new(),
                    sourceUri = "content://synthetic/staged-camera.pdf",
                    sourceFingerprint = SourceFingerprint.fromBytes(byteArrayOf(1, 2, 3)),
                    sessionGeneration = 1L,
                    pageIndex = 0,
                    pinId = UUID.randomUUID().toString(),
                    createdAtMillis = 1_000L
                )
                val prepared = store.prepare(request)
                store.markLaunched(prepared.operationId, 1_001L)
                staged = prepared.copy(
                    revision = 3L,
                    status = CameraCaptureOperationStatus.RESULT_AVAILABLE,
                    result = com.example.myapplication.stage5.CameraCaptureResult.SUCCESS,
                    updatedAtMillis = 1_002L
                )
                val eventName = ".camera-operation-${staged.operationId}-${staged.revision}.json"
                File(store.operationJournalDirectoryForTests, "$eventName.tmp").writeText(
                    GsonBuilder().serializeNulls().create().toJson(staged)
                )
            }

            CameraCaptureOperationStore(root).use { reopened ->
                val result = reopened.reconcileInterruptedJournal()
                assertEquals(
                    CameraCaptureJournalReconciliationDisposition.PUBLISHED_STAGED_REVISION,
                    result.disposition
                )
                assertEquals(staged, reopened.readOperation())
                reopened.markDiscarded(staged.operationId, 1_003L)
                reopened.cleanup(staged.operationId)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun partialStagedRevision_remainsVisibleForExplicitRecovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "camera-staged-partial-").toFile()
        try {
            CameraCaptureOperationStore(root).use { store ->
                val request = com.example.myapplication.stage5.CameraCaptureOperationRequest(
                    processInstanceId = UUID.randomUUID().toString(),
                    documentId = DocumentId.new(),
                    sourceUri = "content://synthetic/staged-camera.pdf",
                    sourceFingerprint = null,
                    sessionGeneration = 1L,
                    pageIndex = 0,
                    pinId = UUID.randomUUID().toString(),
                    createdAtMillis = 2_000L
                )
                val prepared = store.prepare(request)
                File(
                    store.operationJournalDirectoryForTests,
                    ".camera-operation-${prepared.operationId}-2.json.tmp"
                ).writeText("partial")
                val result = store.reconcileInterruptedJournal()
                assertEquals(
                    CameraCaptureJournalReconciliationDisposition.RETAINED_PARTIAL,
                    result.disposition
                )
                assertTrue(result.retainedStagedFileNames.isNotEmpty())
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
