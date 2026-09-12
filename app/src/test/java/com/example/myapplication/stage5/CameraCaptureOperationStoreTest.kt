package com.example.myapplication.stage5

import com.example.myapplication.stage0.HighResolutionPhonePhotoFixture
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage4.Stage4PhotoFixture
import com.google.gson.GsonBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCaptureOperationStoreTest {
    @Test
    fun prepare_isDurableAndReopensWithExactIdentity() {
        val root = Files.createTempDirectory("stage9a-camera-record").toFile()
        val request = request(createdAtMillis = 1_000L)
        try {
            lateinit var prepared: CameraCaptureOperationRecord
            lateinit var capturePath: File
            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                prepared = store.prepare(request)
                capturePath = store.captureFile(prepared.operationId)
                assertEquals(CameraCaptureOperationStatus.PREPARED, prepared.status)
                assertTrue(capturePath.isFile)
                assertEquals("camera_captures", capturePath.parentFile?.name)
                assertEquals("camera_operations", store.operationJournalDirectoryForTests.name)
                assertEquals(1, store.operationJournalFilesForTests().size)
            }

            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { reopened ->
                assertEquals(prepared, reopened.readOperation())
                assertTrue(reopened.matchesStableIdentity(
                    prepared,
                    request.documentId,
                    request.sourceUri,
                    request.sourceFingerprint
                ))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resultAndPublicationTransitions_areIdempotentAndDuplicateConflictFailsClosed() {
        val root = Files.createTempDirectory("stage9a-camera-transition").toFile()
        try {
            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                val prepared = store.prepare(request(2_000L))
                val launched = store.markLaunched(prepared.operationId, 2_001L)
                val available = store.recordResult(prepared.operationId, true, 2_002L)
                assertEquals(CameraCaptureOperationStatus.LAUNCHED, launched.status)
                assertEquals(CameraCaptureResult.SUCCESS, available.result)
                assertEquals(available, store.recordResult(prepared.operationId, true, 2_003L))
                assertRejected<CameraCaptureOperationConflictException> {
                    store.recordResult(prepared.operationId, false, 2_004L)
                }

                val processing = store.markProcessing(prepared.operationId, 2_005L)
                val published = store.markPublished(prepared.operationId, nowMillis = 2_006L)
                assertEquals(CameraCaptureOperationStatus.PROCESSING, processing.status)
                assertEquals(
                    deterministicPublishedPhotoFileName(prepared.operationId),
                    published.publishedPhotoFileName
                )
                assertEquals(published, store.markPublished(
                    prepared.operationId,
                    published.publishedPhotoFileName,
                    2_007L
                ))
                val committed = store.markCommitted(prepared.operationId, 2_008L)
                assertEquals(CameraCaptureOperationStatus.COMMITTED, committed.status)
                assertEquals(committed, store.markCommitted(prepared.operationId, 2_009L))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resultBeforeDurableLaunch_isRejected() {
        val root = Files.createTempDirectory("stage9a-camera-order").toFile()
        try {
            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                val prepared = store.prepare(request(2_100L))
                assertRejected<CameraCaptureOperationConflictException> {
                    store.recordResult(prepared.operationId, true, 2_101L)
                }
                assertEquals(
                    CameraCaptureOperationStatus.PREPARED,
                    store.readOperation()?.status
                )
                store.markLaunched(prepared.operationId, 2_102L)
                assertEquals(
                    CameraCaptureOperationStatus.RESULT_AVAILABLE,
                    store.recordResult(prepared.operationId, true, 2_103L).status
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun launchedOperation_canBeExplicitlyAbandonedAndThenCleaned() {
        val root = Files.createTempDirectory("stage9a-camera-abandon").toFile()
        try {
            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                val prepared = store.prepare(request(2_200L))
                val capture = store.captureFile(prepared.operationId)
                store.markLaunched(prepared.operationId, 2_201L)

                val abandoned = store.abandonLaunched(prepared.operationId, 2_202L)
                assertEquals(CameraCaptureOperationStatus.DISCARDED, abandoned.status)
                assertTrue(capture.exists())
                assertTrue(store.cleanup(prepared.operationId))
                assertFalse(capture.exists())
                assertEquals(null, store.readOperation())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reservedPublication_isDeterministicIdempotentAndRejectsByteMismatch() {
        val root = Files.createTempDirectory("stage9a-camera-publication").toFile()
        val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
        val differentBytes = Stage4PhotoFixture.incomingJpegBytes()
        val operationId = UUID.randomUUID().toString()
        val reference = deterministicPublishedPhotoFileName(operationId)
        try {
            DocumentPhotoAssetStore(
                root,
                DocumentId.new(),
                DefaultImageProbe,
                TestPhotoPathOperationsFactory
            ).use { store ->
                assertEquals(reference, store.publishReservedPhoto(bytes, reference))
                val target = store.resolver.resolve(reference)
                assertEquals(bytes.toList(), target.readBytes().toList())

                assertEquals(
                    reference,
                    store.publishReservedPhoto(
                        bytes,
                        reference,
                        existingPhotoReferences = setOf(reference)
                    )
                )
                assertEquals(bytes.toList(), target.readBytes().toList())

                assertRejected<Stage5ValidationException> {
                    store.publishReservedPhoto(differentBytes, reference)
                }
                assertEquals(bytes.toList(), target.readBytes().toList())
                store.cleanup(reference)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellationCleanup_removesCaptureOnlyAfterDurableCancellation() {
        val root = Files.createTempDirectory("stage9a-camera-cancel").toFile()
        try {
            lateinit var capture: File
            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                val prepared = store.prepare(request(3_000L))
                capture = store.captureFile(prepared.operationId)
                store.markLaunched(prepared.operationId, 3_001L)
                val cancelled = store.recordResult(prepared.operationId, false, 3_002L)
                assertEquals(CameraCaptureOperationStatus.RESULT_CANCELLED, cancelled.status)
                assertTrue(capture.isFile)
                assertTrue(store.cleanup(prepared.operationId))
                assertFalse(capture.exists())
                assertEquals(null, store.readOperation())
            }

            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                assertEquals(null, store.readOperation())
                val next = store.prepare(request(3_100L))
                assertNotNull(next)
                store.discardPrepared(next.operationId, 3_101L)
                store.cleanup(next.operationId)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanup_isRetryableWhenCaptureWasRemovedBeforeJournalCleanup() {
        val root = Files.createTempDirectory("stage9a-camera-cleanup-retry").toFile()
        try {
            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                val prepared = store.prepare(request(3_200L))
                store.markLaunched(prepared.operationId, 3_201L)
                store.recordResult(prepared.operationId, false, 3_202L)
                val capture = store.captureFile(prepared.operationId)
                assertTrue(capture.delete())
                assertTrue(store.cleanup(prepared.operationId))
                assertEquals(null, store.readOperation())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recovery_exposesPublishedNameAndRejectsDifferentStableSource() {
        val root = Files.createTempDirectory("stage9a-camera-recovery").toFile()
        val original = request(4_000L)
        try {
            lateinit var operation: CameraCaptureOperationRecord
            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                operation = store.prepare(original)
                store.markLaunched(operation.operationId, 4_001L)
                store.recordResult(operation.operationId, true, 4_002L)
                store.markProcessing(operation.operationId, 4_003L)
                operation = store.markPublished(operation.operationId, nowMillis = 4_004L)

                val recovery = CameraCaptureRecovery(store)
                val matching = recovery.inspect(CameraCaptureStableIdentity(
                    original.documentId,
                    original.sourceUri,
                    original.sourceFingerprint
                ))
                assertEquals(CameraCaptureRecoveryDisposition.PUBLISHED_UNCOMMITTED, matching.disposition)
                assertEquals(
                    setOf(deterministicPublishedPhotoFileName(operation.operationId)),
                    recovery.retainedPublishedPhotoNames()
                )
                assertTrue(recovery.canRebind(
                    operation,
                    CameraCaptureStableIdentity(
                        original.documentId,
                        original.sourceUri,
                        original.sourceFingerprint
                    )
                ))
                assertFalse(recovery.canRebind(
                    operation,
                    CameraCaptureStableIdentity(
                        original.documentId,
                        original.sourceUri + "/different",
                        original.sourceFingerprint
                    )
                ))
            }

            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { reopened ->
                val state = CameraCaptureRecovery(reopened).inspect(
                    CameraCaptureStableIdentity(
                        original.documentId,
                        original.sourceUri + "/different",
                        original.sourceFingerprint
                    )
                )
                assertEquals(CameraCaptureRecoveryDisposition.IDENTITY_MISMATCH, state.disposition)
                assertEquals(operation, state.operation?.copy(
                    revision = operation.revision,
                    updatedAtMillis = operation.updatedAtMillis
                ))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recovery_rejectsOldGenerationInSameProcess_butAllowsStableProcessDeathRebind() {
        val root = Files.createTempDirectory("stage9a-camera-generation").toFile()
        val original = request(4_500L)
        try {
            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                val operation = store.prepare(original)
                val recovery = CameraCaptureRecovery(store)
                val identity = CameraCaptureStableIdentity(
                    original.documentId,
                    original.sourceUri,
                    original.sourceFingerprint
                )

                assertFalse(recovery.canApplyToSession(
                    operation = operation,
                    identity = identity,
                    ownerInstanceId = original.processInstanceId,
                    sessionGeneration = original.sessionGeneration + 1L
                ))
                assertTrue(recovery.canApplyToSession(
                    operation = operation,
                    identity = identity,
                    ownerInstanceId = UUID.randomUUID().toString(),
                    sessionGeneration = original.sessionGeneration + 1L
                ))

                store.discardPrepared(operation.operationId, 4_501L)
                store.cleanup(operation.operationId)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedOrStagedJournal_failsClosedInsteadOfBecomingNoOperation() {
        val root = Files.createTempDirectory("stage9a-camera-corrupt").toFile()
        val operationId = UUID.randomUUID().toString()
        val eventName = ".camera-operation-$operationId-1.json"
        val tempName = "$eventName.tmp"
        try {
            lateinit var journalDirectory: File
            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                journalDirectory = store.operationJournalDirectoryForTests
            }
            File(journalDirectory, eventName).writeText("{\"schemaVersion\":1}")
            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                assertRejected<CameraCaptureOperationCorruptException> { store.readOperation() }
            }
            File(journalDirectory, eventName).delete()
            File(journalDirectory, tempName).writeText("partial")
            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                assertRejected<CameraCaptureOperationCorruptException> { store.readOperation() }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completeStagedRevision_isPublishedOnlyByExplicitRestartReconciliation() {
        val root = Files.createTempDirectory("stage9a-camera-staged-complete").toFile()
        try {
            lateinit var staged: CameraCaptureOperationRecord
            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                val prepared = store.prepare(request(3_500L))
                store.markLaunched(prepared.operationId, 3_501L)
                staged = prepared.copy(
                    revision = 3L,
                    status = CameraCaptureOperationStatus.RESULT_AVAILABLE,
                    result = CameraCaptureResult.SUCCESS,
                    updatedAtMillis = 3_502L
                )
                writeStaged(store, staged)
                assertRejected<CameraCaptureOperationCorruptException> { store.readOperation() }

                val reconciled = store.reconcileInterruptedJournal()
                assertEquals(
                    CameraCaptureJournalReconciliationDisposition.PUBLISHED_STAGED_REVISION,
                    reconciled.disposition
                )
                assertEquals(staged, reconciled.operation)
                assertEquals(staged, store.readOperation())
                assertTrue(store.operationJournalFilesForTests().none { it.name.endsWith(".tmp") })
            }

            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { reopened ->
                assertEquals(staged, reopened.readOperation())
                assertEquals(
                    CameraCaptureJournalReconciliationDisposition.NONE,
                    reopened.reconcileInterruptedJournal().disposition
                )
                val discarded = reopened.markDiscarded(staged.operationId, 3_503L)
                assertEquals(CameraCaptureOperationStatus.DISCARDED, discarded.status)
                assertTrue(reopened.cleanup(staged.operationId))
                assertEquals(null, reopened.readOperation())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun partialStagedRevision_isRetainedAndProductionSweepWaitsForResolution() {
        val root = Files.createTempDirectory("stage9a-camera-staged-partial").toFile()
        try {
            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                val orphan = store.newCaptureFile()
                Files.setLastModifiedTime(orphan.toPath(), FileTime.fromMillis(1L))
                val prepared = store.prepare(request(3_600L))
                store.markLaunched(prepared.operationId, 3_601L)
                val temp = File(
                    store.operationJournalDirectoryForTests,
                    ".camera-operation-${prepared.operationId}-3.json.tmp"
                )
                temp.writeText("{\"revision\":3")

                val maintenance = store.reconcileInterruptedJournalAndSweep(
                    Stage5Limits.MAX_CAPTURE_AGE_MILLIS + 10_000L
                )
                assertEquals(
                    CameraCaptureJournalReconciliationDisposition.RETAINED_PARTIAL,
                    maintenance.journal.disposition
                )
                assertEquals(0, maintenance.removedOrphanedCaptureFiles)
                assertTrue(temp.isFile)
                assertTrue(orphan.isFile)
                assertRejected<CameraCaptureOperationCorruptException> { store.readOperation() }
            }

            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { reopened ->
                val recovered = reopened.reconcileInterruptedJournal()
                assertEquals(
                    CameraCaptureJournalReconciliationDisposition.RETAINED_PARTIAL,
                    recovered.disposition
                )
                assertTrue(recovered.retainedStagedFileNames.single().endsWith(".tmp"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun conflictingOrOlderStagedRevision_preservesTheNewestGoodEvent() {
        val root = Files.createTempDirectory("stage9a-camera-staged-conflict").toFile()
        try {
            lateinit var latest: CameraCaptureOperationRecord
            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                val prepared = store.prepare(request(3_700L))
                store.markLaunched(prepared.operationId, 3_701L)
                latest = store.recordResult(prepared.operationId, true, 3_702L)
                val stale = prepared.copy(
                    revision = 2L,
                    status = CameraCaptureOperationStatus.LAUNCHED,
                    updatedAtMillis = 3_701L
                )
                writeStaged(store, stale)

                val reconciled = store.reconcileInterruptedJournal()
                assertEquals(
                    CameraCaptureJournalReconciliationDisposition.RETAINED_AMBIGUOUS,
                    reconciled.disposition
                )
                assertEquals(latest, reconciled.operation)
                assertTrue(store.operationJournalFilesForTests().any { it.name.endsWith(".tmp") })
                assertRejected<CameraCaptureOperationCorruptException> { store.readOperation() }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun gappedEventHistory_isUnresolvedAndDoesNotSweepCaptures() {
        val root = Files.createTempDirectory("stage9a-camera-event-gap").toFile()
        try {
            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                val orphan = store.newCaptureFile()
                Files.setLastModifiedTime(orphan.toPath(), FileTime.fromMillis(1L))
                val prepared = store.prepare(request(3_800L))
                store.markLaunched(prepared.operationId, 3_801L)
                val firstRevision = store.operationJournalFilesForTests()
                    .single { it.name.endsWith("-1.json") }
                assertTrue(firstRevision.delete())

                val maintenance = store.reconcileInterruptedJournalAndSweep(
                    Stage5Limits.MAX_CAPTURE_AGE_MILLIS + 10_000L
                )
                assertEquals(
                    CameraCaptureJournalReconciliationDisposition.RETAINED_AMBIGUOUS,
                    maintenance.journal.disposition
                )
                assertFalse(maintenance.journal.resolved)
                assertEquals(0, maintenance.removedOrphanedCaptureFiles)
                assertTrue(orphan.exists())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oversizedIntegerFields_doNotWrapIntoValidValues() {
        val root = Files.createTempDirectory("stage9a-camera-integer").toFile()
        val operationId = UUID.randomUUID().toString()
        val eventName = ".camera-operation-$operationId-1.json"
        val documentId = DocumentId.new().value
        val processId = UUID.randomUUID().toString()
        val pinId = UUID.randomUUID().toString()
        val captureName = ".camera-capture-$operationId.tmp"
        val json = """
            {
              "schemaVersion": 4294967297,
              "revision": 1,
              "operationId": "$operationId",
              "processInstanceId": "$processId",
              "documentId": "$documentId",
              "sourceUri": "content://com.example.fixture/document.pdf",
              "sourceFingerprint": null,
              "sessionGeneration": 1,
              "pageIndex": 0,
              "pinId": "$pinId",
              "captureFileName": "$captureName",
              "publishedPhotoFileName": "photo-$operationId.jpg",
              "status": "PREPARED",
              "result": null,
              "createdAtMillis": 1,
              "updatedAtMillis": 1
            }
        """.trimIndent()
        try {
            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                File(store.operationJournalDirectoryForTests, eventName).writeText(json)
                assertRejected<CameraCaptureOperationCorruptException> { store.readOperation() }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun orphanCleanup_removesOnlyOldUnownedCaptureAndProtectsActiveRecord() {
        val root = Files.createTempDirectory("stage9a-camera-orphans").toFile()
        val cleanupNow = Stage5Limits.MAX_CAPTURE_AGE_MILLIS + 10_000L
        try {
            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                val orphan = store.newCaptureFile()
                Files.setLastModifiedTime(orphan.toPath(), FileTime.fromMillis(1L))
                assertEquals(1, store.cleanupOrphanedCaptureFiles(cleanupNow))
                assertFalse(orphan.exists())

                val prepared = store.prepare(request(5_000L))
                val active = store.captureFile(prepared.operationId)
                Files.setLastModifiedTime(active.toPath(), FileTime.fromMillis(1L))
                assertEquals(0, store.cleanupOrphanedCaptureFiles(cleanupNow))
                assertTrue(active.exists())
                store.discardPrepared(prepared.operationId, 5_001L)
                store.cleanup(prepared.operationId)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun untrustedRequestFields_areRejectedBeforeCaptureCreation() {
        val root = Files.createTempDirectory("stage9a-camera-input").toFile()
        try {
            CameraCaptureOperationStore(root, TestPhotoPathOperationsFactory).use { store ->
                assertRejected<IllegalArgumentException> {
                    store.prepare(request(6_000L).copy(sourceUri = "content://camera/\u0000bad"))
                }
                assertRejected<Stage5ValidationException> {
                    store.prepare(request(6_001L).copy(pinId = " "))
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun request(createdAtMillis: Long): CameraCaptureOperationRequest =
        CameraCaptureOperationRequest(
            processInstanceId = UUID.randomUUID().toString(),
            documentId = DocumentId.new(),
            sourceUri = "content://com.example.fixture/document.pdf",
            sourceFingerprint = SourceFingerprint.fromBytes(byteArrayOf(1, 2, 3, 4)),
            sessionGeneration = 7L,
            pageIndex = 0,
            pinId = UUID.randomUUID().toString(),
            createdAtMillis = createdAtMillis
        )

    private fun writeStaged(
        store: CameraCaptureOperationStore,
        record: CameraCaptureOperationRecord
    ) {
        val eventName = ".camera-operation-${record.operationId}-${record.revision}.json"
        File(store.operationJournalDirectoryForTests, "$eventName.tmp").writeText(
            GsonBuilder().serializeNulls().create().toJson(record)
        )
    }

    private inline fun <reified T : Throwable> assertRejected(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue("expected ${T::class.java.simpleName}, got ${error::class.java.name}", error is T)
            return
        }
        throw AssertionError("expected ${T::class.java.simpleName}")
    }
}
