package com.example.myapplication.stage10

import android.os.Process
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage5.CameraCaptureOperationRecord
import com.example.myapplication.stage5.CameraCaptureOperationStatus
import com.example.myapplication.stage5.CameraCaptureResult
import com.example.myapplication.stage5.CameraCaptureFilePolicy
import com.example.myapplication.stage5.CameraCaptureOperationStore
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage6.PdfExportOutcome
import com.example.myapplication.stage6.PdfExportRequestOwner
import com.example.myapplication.stage6.PdfExportTemporaryOwner
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two separately selected invocations form one real process-boundary test.
 * The host runner invokes [stageInterruptedPublications], force-stops the
 * target package, and then invokes [recoverAfterProcessRestart].
 */
@RunWith(AndroidJUnit4::class)
class AuditProcessRecoveryInstrumentedTest {
    @Test
    fun stageInterruptedPublications(): Unit = runBlocking {
        assumePhase("stage")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = fixtureDirectory(context)
        check(!fixture.exists() || fixture.deleteRecursively()) {
            "the owned process-recovery fixture could not be reset"
        }
        check(fixture.mkdirs()) { "the owned process-recovery fixture could not be created" }

        val cameraRoot = File(fixture, CAMERA_DIRECTORY_NAME).apply { check(mkdirs()) }
        val exportOwner = PdfExportTemporaryOwner(
            fixture,
            abandonedAfterMillis = EXPORT_LEASE_MILLIS
        )
        val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val exportRequestOwner = PdfExportRequestOwner(exportScope, Dispatchers.IO, exportOwner)
        val exportRequestId = requireNotNull(exportRequestOwner.begin())
        val exportSource = exportOwner.createSourceFile(exportRequestId)
        val exportResult = exportRequestOwner.createResultFile(exportRequestId)
        exportSource.writeBytes(EXPIRED_SOURCE_BYTES)
        exportResult.writeBytes(EXPIRED_RESULT_BYTES)
        setExpired(exportSource)
        setExpired(exportResult)
        val unrelatedCacheSibling = File(fixture, UNRELATED_SIBLING_NAME)
            .apply { writeBytes(UNRELATED_SIBLING_BYTES) }

        try {
            lateinit var stagedOperation: CameraCaptureOperationRecord
            lateinit var stagedJournalFile: File
            lateinit var orphanCapture: File
            CameraCaptureOperationStore(cameraRoot).use { store ->
                orphanCapture = store.newCaptureFile()
                orphanCapture.writeBytes(ORPHAN_CAPTURE_BYTES)
                setExpired(orphanCapture)

                val request = com.example.myapplication.stage5.CameraCaptureOperationRequest(
                    processInstanceId = UUID.randomUUID().toString(),
                    documentId = DocumentId.new(),
                    sourceUri = CAMERA_SOURCE_URI,
                    sourceFingerprint = SourceFingerprint.fromBytes(CAMERA_SOURCE_BYTES),
                    sessionGeneration = 1L,
                    pageIndex = 0,
                    pinId = UUID.randomUUID().toString(),
                    createdAtMillis = CAMERA_CREATED_AT
                )
                val prepared = store.prepare(request)
                val launched = store.markLaunched(prepared.operationId, CAMERA_LAUNCHED_AT)
                val activeCapture = store.captureFile(prepared.operationId)
                activeCapture.writeBytes(ACTIVE_CAPTURE_BYTES)
                setExpired(activeCapture)
                stagedOperation = launched.copy(
                    revision = 3L,
                    status = CameraCaptureOperationStatus.RESULT_AVAILABLE,
                    result = CameraCaptureResult.SUCCESS,
                    updatedAtMillis = CAMERA_RESULT_AT
                )
                val eventName = ".camera-operation-${stagedOperation.operationId}-${stagedOperation.revision}.json"
                stagedJournalFile = File(store.operationJournalDirectoryForTests, "$eventName.tmp")
                val stagedBytes = GsonBuilder()
                    .serializeNulls()
                    .create()
                    .toJson(stagedOperation)
                    .toByteArray(StandardCharsets.UTF_8)
                stagedJournalFile.writeBytes(stagedBytes)
                check(stagedJournalFile.isFile) { "staged camera journal was not written" }

                writeManifest(
                    fixture,
                    mapOf(
                        "stage.pid" to Process.myPid().toString(),
                        "camera.operationId" to stagedOperation.operationId,
                        "camera.captureFileName" to stagedOperation.captureFileName,
                        "camera.captureDigest" to sha256(activeCapture),
                        "camera.orphanFileName" to orphanCapture.name,
                        "camera.stagedEventFileName" to eventName,
                        "camera.stagedTemporaryFileName" to stagedJournalFile.name,
                        "camera.stagedDigest" to sha256(stagedJournalFile),
                        "camera.expectedRevision" to stagedOperation.revision.toString(),
                        "camera.expectedStatus" to stagedOperation.status.name,
                        "export.requestId" to exportRequestId,
                        "export.sourceFileName" to exportSource.name,
                        "export.sourceDigest" to sha256(exportSource),
                        "export.resultFileName" to exportResult.name,
                        "export.resultDigest" to sha256(exportResult),
                        "export.siblingFileName" to unrelatedCacheSibling.name,
                        "export.siblingDigest" to sha256(unrelatedCacheSibling),
                        "export.leaseMillis" to EXPORT_LEASE_MILLIS.toString()
                    )
                )
                recordEvidence(
                    "stage pid=${Process.myPid()} operationId=${stagedOperation.operationId} " +
                        "revision=${stagedOperation.revision} exportRequestId=$exportRequestId " +
                        "sourceSha256=${sha256(exportSource)} resultSha256=${sha256(exportResult)} " +
                        "captureSha256=${sha256(activeCapture)}"
                )
            }
        } finally {
            // Preserve the intentionally abandoned files, while avoiding an
            // idle worker scope surviving longer than this staging invocation.
            exportScope.cancel()
        }
    }

    @Test
    fun recoverAfterProcessRestart(): Unit = runBlocking {
        assumePhase("restart")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = fixtureDirectory(context)
        try {
            val manifest = readManifest(fixture)
            val previousPid = manifest.required("stage.pid").toInt()
            assertNotEquals("the target process was not restarted", previousPid, Process.myPid())

            val cameraRoot = File(fixture, CAMERA_DIRECTORY_NAME)
            val cameraStore = CameraCaptureOperationStore(cameraRoot)
            cameraStore.use { store ->
                val maintenance = store.reconcileInterruptedJournalAndSweep(
                    Stage5Limits.MAX_CAPTURE_AGE_MILLIS + 10_000L
                )
                assertTrue(maintenance.journal.resolved)
                assertEquals(
                    com.example.myapplication.stage5.CameraCaptureJournalReconciliationDisposition.PUBLISHED_STAGED_REVISION,
                    maintenance.journal.disposition
                )
                assertEquals(1, maintenance.removedOrphanedCaptureFiles)

                val current = requireNotNull(store.readOperation())
                assertEquals(manifest.required("camera.operationId"), current.operationId)
                assertEquals(manifest.required("camera.expectedRevision").toLong(), current.revision)
                assertEquals(manifest.required("camera.expectedStatus"), current.status.name)
                assertEquals(CAMERA_SOURCE_URI, current.sourceUri)
                assertEquals(CameraCaptureResult.SUCCESS, current.result)
                val activeCapture = store.captureFile(current.operationId)
                assertEquals(manifest.required("camera.captureFileName"), activeCapture.name)
                assertEquals(manifest.required("camera.captureDigest"), sha256(activeCapture))
                assertTrue(activeCapture.isFile)
                assertFalse(
                    File(
                        File(cameraRoot, CameraCaptureFilePolicy.ROOT_DIRECTORY),
                        manifest.required("camera.orphanFileName")
                    ).exists()
                )

                val publishedJournal = File(
                    store.operationJournalDirectoryForTests,
                    manifest.required("camera.stagedEventFileName")
                )
                assertFalse(
                    File(
                        store.operationJournalDirectoryForTests,
                        manifest.required("camera.stagedTemporaryFileName")
                    ).exists()
                )
                assertTrue(publishedJournal.isFile)
                assertEquals(manifest.required("camera.stagedDigest"), sha256(publishedJournal))
            }

            val leaseMillis = manifest.required("export.leaseMillis").toLong()
            val exportOwner = PdfExportTemporaryOwner(fixture, abandonedAfterMillis = leaseMillis)
            val source = File(exportOwner.directoryForTests, manifest.required("export.sourceFileName"))
            val result = File(exportOwner.directoryForTests, manifest.required("export.resultFileName"))
            assertEquals(manifest.required("export.sourceDigest"), sha256(source))
            assertEquals(manifest.required("export.resultDigest"), sha256(result))
            val reconciliation = exportOwner.reconcile(nowMillis = 1_000L)
            assertTrue(reconciliation.removedFileNames.contains(manifest.required("export.sourceFileName")))
            assertTrue(reconciliation.removedFileNames.contains(manifest.required("export.resultFileName")))
            assertFalse(source.exists())
            assertFalse(result.exists())
            val sibling = File(fixture, manifest.required("export.siblingFileName"))
            assertTrue(sibling.isFile)
            assertEquals(manifest.required("export.siblingDigest"), sha256(sibling))
            val repeated = exportOwner.reconcile(nowMillis = 1_000L)
            assertTrue(repeated.removedFileNames.isEmpty())

            val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val requestOwner = PdfExportRequestOwner(ownerScope, Dispatchers.IO, exportOwner)
            try {
                requestOwner.complete(manifest.required("export.requestId")) {
                    error("an unknown picker request must not open a destination")
                }.join()
                assertEquals(PdfExportOutcome.EXPIRED, requestOwner.notice.value?.outcome)
            } finally {
                requestOwner.close().join()
                ownerScope.cancel()
            }
            recordEvidence(
                "restart pid=${Process.myPid()} previousPid=$previousPid " +
                    "operationId=${manifest.required("camera.operationId")} " +
                    "revision=${manifest.required("camera.expectedRevision")} " +
                    "exportRequestId=${manifest.required("export.requestId")} " +
                    "sourceSha256=${manifest.required("export.sourceDigest")} " +
                    "resultSha256=${manifest.required("export.resultDigest")} " +
                    "captureSha256=${manifest.required("camera.captureDigest")}"
            )
        } finally {
            check(fixture.deleteRecursively() || !fixture.exists()) {
                "the owned process-recovery fixture could not be removed"
            }
        }
    }

    private fun recordEvidence(message: String) {
        Log.i(LOG_TAG, message)
        // Retain actual process/request identities and digests in the host
        // instrumentation log before the scoped fixture is removed.
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stage10.recovery.evidence", message)
        })
    }

    private fun assumePhase(expected: String) {
        val actual = InstrumentationRegistry.getArguments().getString(RECOVERY_ARGUMENT)
        assumeTrue("run with -e $RECOVERY_ARGUMENT $expected", actual == expected)
    }

    private fun fixtureDirectory(context: android.content.Context): File {
        val targetFiles = context.filesDir.canonicalFile
        val fixture = File(targetFiles, FIXTURE_DIRECTORY_NAME).canonicalFile
        check(fixture.parentFile == targetFiles) { "process-recovery fixture escaped target files" }
        return fixture
    }

    private fun readManifest(fixture: File): Properties {
        check(fixture.isDirectory) { "the staged process-recovery fixture is missing" }
        val manifest = File(fixture, MANIFEST_NAME)
        check(manifest.isFile && manifest.length() <= MAX_MANIFEST_BYTES) {
            "the staged process-recovery manifest is missing or oversized"
        }
        return Properties().also { properties ->
            FileInputStream(manifest).use { input -> properties.load(input) }
        }
    }

    private fun writeManifest(fixture: File, values: Map<String, String>) {
        val manifest = File(fixture, MANIFEST_NAME)
        val temporary = File(fixture, "$MANIFEST_NAME.tmp")
        val properties = Properties()
        values.forEach { (key, value) -> properties.setProperty(key, value) }
        FileOutputStream(temporary).use { stream ->
            properties.store(stream, "stage10 process recovery fixture")
            stream.fd.sync()
        }
        check(temporary.renameTo(manifest)) { "the process-recovery manifest could not be published" }
    }

    private fun setExpired(file: File) {
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(1L))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(count > 0) { "fixture file made no progress" }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun Properties.required(key: String): String =
        getProperty(key)?.takeIf { it.isNotBlank() }
            ?: error("process-recovery fixture is missing $key")

    private companion object {
        const val LOG_TAG = "AuditProcessRecovery"
        const val RECOVERY_ARGUMENT = "stage10.recovery"
        const val FIXTURE_DIRECTORY_NAME = "stage10-process-recovery"
        const val MANIFEST_NAME = "fixture.properties"
        const val CAMERA_DIRECTORY_NAME = "camera"
        const val UNRELATED_SIBLING_NAME = "unrelated-cache-sibling.bin"
        const val EXPORT_LEASE_MILLIS = 100L
        const val MAX_MANIFEST_BYTES = 16 * 1024L
        const val CAMERA_SOURCE_URI = "content://synthetic/process-recovery-camera.pdf"
        const val CAMERA_CREATED_AT = 1_000L
        const val CAMERA_LAUNCHED_AT = 1_001L
        const val CAMERA_RESULT_AT = 1_002L
        val CAMERA_SOURCE_BYTES = byteArrayOf(1, 2, 3, 4)
        val ACTIVE_CAPTURE_BYTES = "active-camera-capture".toByteArray(StandardCharsets.UTF_8)
        val ORPHAN_CAPTURE_BYTES = "orphan-camera-capture".toByteArray(StandardCharsets.UTF_8)
        val EXPIRED_SOURCE_BYTES = "abandoned-export-source".toByteArray(StandardCharsets.UTF_8)
        val EXPIRED_RESULT_BYTES = "abandoned-export-result".toByteArray(StandardCharsets.UTF_8)
        val UNRELATED_SIBLING_BYTES = "unrelated-cache-content".toByteArray(StandardCharsets.UTF_8)
    }
}
