package com.example.myapplication.stage5

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.stage0.HighResolutionPhonePhotoFixture
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage2.AndroidLegacyPersistenceSource
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentLoadResult
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.LocalDocumentRepository
import com.example.myapplication.stage2.ResolveDocumentResult
import com.example.myapplication.stage3.AndroidDocumentSessionCallbacks
import com.example.myapplication.stage3.DocumentSession
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.ResolvedDocumentTarget
import com.example.myapplication.stage3.SessionLoadResult
import com.example.myapplication.stage4.DurablePendingUpload
import com.example.myapplication.stage4.FileSyncMetadataStore
import com.example.myapplication.stage4.MetadataReadResult
import com.example.myapplication.stage4.MetadataWriteResult
import com.example.myapplication.stage4.PhotoRollbackException
import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage4.StagedPhotoContentTransaction
import com.example.myapplication.stage4.RemoteCursor
import com.example.myapplication.stage4.SyncMetadata
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage5.PhotoCanonicalRecoveryException
import com.example.myapplication.stage5.PhotoPathResolver
import com.example.myapplication.stage5.sha256Hex
import com.example.myapplication.stage5.validatePhotoBytes
import java.io.File
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage5MetadataBoundaryTest {
    @Test
    fun metadataWrite_freezesMutablePhotoGraphAndSuccessfulBytesReadBackThroughStrictValidator() = runTest {
        val root = Files.createTempDirectory("stage5-metadata-freeze").toFile()
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val source = DocumentSourceIdentityV1("content://stage5/photo-source", "plan.pdf")
            val snapshot = DocumentSnapshotV1(
                schemaVersion = 1,
                snapshotRevision = 0L,
                source = source,
                pages = mapOf(
                    0 to PageSnapshotV1(
                        photoPins = listOf(
                            PhotoPinSnapshotV1(
                                x = 0.5f,
                                y = 0.5f,
                                id = "metadata-photo-pin",
                                imageFileNames = listOf("photo.jpg"),
                                imageNotes = emptyMap(),
                                imageShapes = emptyMap()
                            )
                        )
                    )
                )
            )
            val photoBytes = HighResolutionPhonePhotoFixture.jpegBytes()
            val mutablePhotoFiles = linkedMapOf("photo.jpg" to photoBytes.copyOf())
            val pending = DurablePendingUpload(
                reason = com.example.myapplication.stage4.SyncReason.MANUAL,
                sourceUri = source.sourceUri,
                sourceFingerprint = null,
                generation = 1L,
                expectedCursor = null,
                snapshot = snapshot,
                photoFiles = mutablePhotoFiles
            )
            val metadata = SyncMetadata(scope = scope, pendingUpload = pending)
            val store = FileSyncMetadataStore(root)

            // The constructor saw valid bytes, but the caller later mutates the
            // map. The write boundary must validate the current graph rather
            // than trusting the earlier constructor result.
            mutablePhotoFiles["photo.jpg"] = byteArrayOf(1, 2, 3)
            assertTrue(store.write(metadata) is MetadataWriteResult.Failed)

            mutablePhotoFiles["photo.jpg"] = photoBytes.copyOf()
            assertEquals(MetadataWriteResult.Committed, store.write(metadata))
            val loaded = (store.read(scope) as MetadataReadResult.Loaded).metadata
            assertTrue(
                loaded?.pendingUpload?.photoFiles?.get("photo.jpg")?.contentEquals(photoBytes) == true
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun metadataRead_rejectsPartialRemoteAdoptionAndPendingUploadGroups() = runTest {
        val root = Files.createTempDirectory("stage5-metadata-groups").toFile()
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val store = FileSyncMetadataStore(root)
            val target = store.metadataFileFor(scope)
            requireNotNull(target.parentFile).mkdirs()
            val prefix = """
                {"schemaVersion":1,"accountId":"account","backupRootId":"root",
                 "documentId":"${scope.documentId.value}",
            """.trimIndent()
            listOf(
                "\"remoteFolderId\":\"folder\"}",
                "\"pendingAdoptionRemoteDocumentId\":\"remote\"}",
                "\"pendingUploadExpectedRevision\":\"revision\"}"
            ).forEachIndexed { index, suffix ->
                target.writeText(prefix + suffix)
                assertTrue(store.read(scope) is MetadataReadResult.Failed)
                assertTrue("malformed metadata case $index remains evidence", target.isFile)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun metadataRead_rejectsOversizedRawJsonBeforeGsonMaterialization() = runTest {
        val root = Files.createTempDirectory("stage5-metadata-read").toFile()
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val store = FileSyncMetadataStore(root)
            val target = store.metadataFileFor(scope)
            requireNotNull(target.parentFile).mkdirs()
            target.writeText("{" + "x".repeat(Stage5Limits.MAX_METADATA_BYTES) + "}")

            val result = store.read(scope)
            assertTrue(result is MetadataReadResult.Failed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun metadataWrite_rejectsOversizedSerializedPendingState() = runTest {
        val root = Files.createTempDirectory("stage5-metadata-write").toFile()
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val result = FileSyncMetadataStore(root).write(
                SyncMetadata(
                    scope = scope,
                    conflictCursor = RemoteCursor("remote-1"),
                    conflictDetail = "x".repeat(Stage5Limits.MAX_METADATA_BYTES + 1)
                )
            )
            assertTrue(result is MetadataWriteResult.Failed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun metadataRead_rejectsInvalidPendingPhotoBase64() = runTest {
        val root = Files.createTempDirectory("stage5-metadata-base64").toFile()
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val store = FileSyncMetadataStore(root)
            val target = store.metadataFileFor(scope)
            requireNotNull(target.parentFile).mkdirs()
            val snapshotJson = """
                {"schemaVersion":1,"snapshotRevision":0,
                 "source":{"sourceUri":"content://stage5/source","displayName":"plan.pdf","providerMetadata":{}},
                 "pages":{}}
            """.trimIndent()
            target.writeText(
                """
                {
                  "schemaVersion":1,
                  "accountId":"account",
                  "backupRootId":"root",
                  "documentId":"${scope.documentId.value}",
                  "pendingUploadReason":"MANUAL",
                  "pendingUploadSourceUri":"content://stage5/source",
                  "pendingUploadGeneration":1,
                  "pendingUploadSnapshotJson":${com.google.gson.Gson().toJson(snapshotJson)},
                  "pendingUploadPhotoFiles":{"photo.jpg":"not base64?"}
                }
                """.trimIndent()
            )

            assertTrue(store.read(scope) is MetadataReadResult.Failed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun metadataRead_rejectsPendingSnapshotMissingNestedPrimitiveBeforeAcceptance() = runTest {
        val root = Files.createTempDirectory("stage5-metadata-missing-field").toFile()
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val store = FileSyncMetadataStore(root)
            val target = store.metadataFileFor(scope)
            requireNotNull(target.parentFile).mkdirs()
            val snapshot = JsonParser.parseString(
                """
                {
                  "schemaVersion":1,"snapshotRevision":0,
                  "source":{"sourceUri":"content://stage5/source","displayName":"plan.pdf","providerMetadata":{}},
                  "pages":{"0":{"paths":[],"measurements":[],"notes":[{"x":0,"y":0,"text":"note","isBold":false,"rotation":0}],"photoPins":[],"scale":null,"shapes":[]}}
                }
                """.trimIndent()
            ).asJsonObject
            val pendingSnapshotJson = Gson().toJson(snapshot)
            target.writeText(
                """
                {
                  "schemaVersion":1,
                  "accountId":"account",
                  "backupRootId":"root",
                  "documentId":"${scope.documentId.value}",
                  "pendingUploadReason":"MANUAL",
                  "pendingUploadSourceUri":"content://stage5/source",
                  "pendingUploadGeneration":1,
                  "pendingUploadSnapshotJson":${Gson().toJson(pendingSnapshotJson)},
                  "pendingUploadPhotoFiles":{}
                }
                """.trimIndent()
            )

            val result = store.read(scope)
            assertTrue(result is MetadataReadResult.Failed)
            assertTrue(target.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun metadataRead_rejectsNestedDuplicateMembersAndPendingSnapshotDuplicates() = runTest {
        val root = Files.createTempDirectory("stage5-metadata-duplicates").toFile()
        try {
            val scope = SyncScope("account", "root", DocumentId.new())
            val store = FileSyncMetadataStore(root)
            val target = store.metadataFileFor(scope)
            requireNotNull(target.parentFile).mkdirs()

            target.writeText(
                """
                {
                  "schemaVersion":1,
                  "accountId":"account",
                  "backupRootId":"root",
                  "documentId":"${scope.documentId.value}",
                  "remoteAppProperties":{"one":"1","one":"2"}
                }
                """.trimIndent()
            )
            assertTrue(store.read(scope) is MetadataReadResult.Failed)

            val duplicatePendingSnapshot = """
                {"schemaVersion":1,"snapshotRevision":0,
                 "source":{"sourceUri":"content://stage5/source",
                            "sourceUri":"content://stage5/other",
                            "displayName":"plan.pdf","providerMetadata":{}},
                 "pages":{}}
            """.trimIndent()
            target.writeText(
                """
                {
                  "schemaVersion":1,
                  "accountId":"account",
                  "backupRootId":"root",
                  "documentId":"${scope.documentId.value}",
                  "pendingUploadReason":"MANUAL",
                  "pendingUploadSourceUri":"content://stage5/source",
                  "pendingUploadGeneration":1,
                  "pendingUploadSnapshotJson":${Gson().toJson(duplicatePendingSnapshot)},
                  "pendingUploadPhotoFiles":{}
                }
                """.trimIndent()
            )
            assertTrue(store.read(scope) is MetadataReadResult.Failed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun durableThreeAuthorityRecovery_blocksMixedReadinessAcrossRestartBoundaries() = runTest {
        // The first case walks the three forward phases with new repository,
        // metadata-store, context, callback, and photo-resolver instances at
        // each readiness check. The callback's Failed result is the exact
        // Stage 3 boundary that prevents onStart/editable state.
        val forward = newDurableRecoveryCase(savePrevious = true)
        try {
            val transaction = stageRemoteAcceptance(forward, TestPhotoPathOperationsFactory)
            try {
                val prePhaseStarted = mutableListOf<DocumentSession>()
                assertNotReady(
                    newCallbacks(forward, prePhaseStarted).loadTarget(forward.session),
                    "canonical apply before metadata phase"
                )
                assertTrue("pre-phase failure must not expose ready work", prePhaseStarted.isEmpty())
                assertEquals(
                    forward.previous,
                    durableSnapshot(LocalDocumentRepository(File(forward.root, "local_documents")), forward.association)
                )
                assertEquals(
                    "previous",
                    acceptedRevision(FileSyncMetadataStore(File(forward.root, "sync-metadata")), forward.scope)
                )
                assertPhotoBytes(forward, forward.incomingPhotoBytes, "pre-phase")

                assertEquals(
                    DocumentSaveResult.Saved(forward.association.documentId),
                    forward.repository.save(forward.association, forward.intended)
                )
                assertEquals(
                    MetadataWriteResult.Committed,
                    forward.metadataStore.write(acceptedMetadata(forward, "intended"))
                )
                val postMetadataStarted = mutableListOf<DocumentSession>()
                assertNotReady(
                    newCallbacks(forward, postMetadataStarted).loadTarget(forward.session),
                    "metadata durable before photo phase marker"
                )
                assertTrue("post-metadata failure must not expose ready work", postMetadataStarted.isEmpty())
                assertEquals(
                    forward.intended,
                    durableSnapshot(LocalDocumentRepository(File(forward.root, "local_documents")), forward.association)
                )
                assertEquals(
                    "intended",
                    acceptedRevision(FileSyncMetadataStore(File(forward.root, "sync-metadata")), forward.scope)
                )
                assertPhotoBytes(forward, forward.incomingPhotoBytes, "post-metadata/pre-phase")

                transaction.markMetadataCommitted()
                assertPhotoBytes(forward, forward.incomingPhotoBytes, "post-phase/pre-photo-commit")
            } finally {
                transaction.releaseAfterFailure()
            }

            val postPhaseStarted = mutableListOf<DocumentSession>()
            val recovered = newCallbacks(forward, postPhaseStarted).loadTarget(forward.session)
            val loaded = recovered as? SessionLoadResult.Loaded
            assertTrue("metadata phase must permit recovery before readiness", loaded != null)
            assertEquals(forward.intended, loaded?.snapshot)
            assertTrue("recovered state may expose ready work", postPhaseStarted.isEmpty())
            assertEquals(
                forward.intended,
                durableSnapshot(LocalDocumentRepository(File(forward.root, "local_documents")), forward.association)
            )
            assertEquals(
                "intended",
                acceptedRevision(FileSyncMetadataStore(File(forward.root, "sync-metadata")), forward.scope)
            )
            assertPhotoBytes(forward, forward.incomingPhotoBytes, "post-recovery")
            assertNoStage5Markers(forward.photoRoot)
        } finally {
            forward.root.deleteRecursively()
        }

        // An offline-empty target is also blocked by unresolved evidence and
        // becomes Empty only after the owning transaction is explicitly
        // rolled back and all five markers have been removed.
        val empty = newDurableRecoveryCase(savePrevious = false)
        try {
            val transaction = stageRemoteAcceptance(empty, TestPhotoPathOperationsFactory)
            var rolledBack = false
            try {
                val blockedStarted = mutableListOf<DocumentSession>()
                val blocked = newCallbacks(empty, blockedStarted).loadTarget(empty.session)
                assertNotReady(blocked, "offline-empty target with unresolved photo evidence")
                assertTrue("offline-empty failure must not expose ready work", blockedStarted.isEmpty())
                assertPhotoBytes(empty, empty.incomingPhotoBytes, "offline-empty pre-rollback")

                transaction.rollback()
                rolledBack = true
            } finally {
                if (!rolledBack) transaction.releaseAfterFailure()
            }
            assertNoStage5Markers(empty.photoRoot)
            val emptyStarted = mutableListOf<DocumentSession>()
            val reopenedEmpty = newCallbacks(empty, emptyStarted).loadTarget(empty.session)
            assertTrue("recovered empty target should be an explicit Empty result", reopenedEmpty is SessionLoadResult.Empty)
            assertTrue(emptyStarted.isEmpty())
            assertFalse(File(empty.photoRoot, "photo.jpg").isFile)
            assertEquals(
                "previous",
                acceptedRevision(FileSyncMetadataStore(File(empty.root, "sync-metadata")), empty.scope)
            )
        } finally {
            empty.root.deleteRecursively()
        }

        // A deletion failure after the metadata phase and authoritative photo
        // commit is not an ordinary success. A new resolver retries cleanup,
        // then the new callback can expose the complete intended tuple.
        val partial = newDurableRecoveryCase(savePrevious = true)
        try {
            val failingFactory = FailOnPhotoMarkerDeleteFactory(".stage5-photo-metadata.commit")
            val transaction = stageRemoteAcceptance(partial, failingFactory)
            var commitFailed = false
            try {
                assertEquals(
                    DocumentSaveResult.Saved(partial.association.documentId),
                    partial.repository.save(partial.association, partial.intended)
                )
                assertEquals(
                    MetadataWriteResult.Committed,
                    partial.metadataStore.write(acceptedMetadata(partial, "intended"))
                )
                transaction.markMetadataCommitted()
                assertPhotoBytes(partial, partial.incomingPhotoBytes, "partial post-phase/pre-photo-commit")
                try {
                    transaction.commit()
                } catch (_: PhotoCanonicalRecoveryException) {
                    commitFailed = true
                }
                assertTrue("partial metadata-marker cleanup must not report success", commitFailed)
                assertTrue(File(partial.photoRoot, ".stage5-photo-transaction.marker").isFile)
                assertTrue(File(partial.photoRoot, ".stage5-photo-transaction.cleanup").isFile)
                assertTrue(File(partial.photoRoot, ".stage5-photo-metadata.commit").isFile)
                assertPhotoBytes(partial, partial.incomingPhotoBytes, "partial cleanup failure")
            } finally {
                transaction.releaseAfterFailure()
            }
            assertEquals(failingFactory.opened, failingFactory.closed)
            assertEquals(0, failingFactory.usedAfterClose)

            val restartedStarted = mutableListOf<DocumentSession>()
            val restarted = newCallbacks(partial, restartedStarted).loadTarget(partial.session)
            val restartedLoaded = restarted as? SessionLoadResult.Loaded
            assertTrue("restart cleanup must resolve before readiness", restartedLoaded != null)
            assertEquals(partial.intended, restartedLoaded?.snapshot)
            assertTrue(restartedStarted.isEmpty())
            assertEquals(
                "intended",
                acceptedRevision(FileSyncMetadataStore(File(partial.root, "sync-metadata")), partial.scope)
            )
            assertPhotoBytes(partial, partial.incomingPhotoBytes, "partial restart")
            assertNoStage5Markers(partial.photoRoot)
        } finally {
            partial.root.deleteRecursively()
        }

        // A rollback cleanup failure must retain the old photo bytes and
        // remain unavailable to a restarted session until a resolver can
        // finish the retained V3 cleanup evidence.
        val rollbackFailure = newDurableRecoveryCase(savePrevious = true)
        try {
            val failingFactory = FailOnPhotoMarkerDeleteFactory(".stage5-photo-canonical.intent")
            val transaction = stageRemoteAcceptance(rollbackFailure, failingFactory)
            var rollbackFailed = false
            try {
                assertPhotoBytes(
                    rollbackFailure,
                    rollbackFailure.incomingPhotoBytes,
                    "rollback failure pre-rollback"
                )
                try {
                    transaction.rollback()
                } catch (_: PhotoRollbackException) {
                    rollbackFailed = true
                }
                assertTrue("rollback cleanup failure must not report success", rollbackFailed)
                assertPhotoBytes(
                    rollbackFailure,
                    rollbackFailure.previousPhotoBytes,
                    "rollback cleanup failure"
                )
                assertTrue(File(rollbackFailure.photoRoot, ".stage5-photo-transaction.marker").isFile)
                assertTrue(File(rollbackFailure.photoRoot, ".stage5-photo-canonical.intent").isFile)
                assertTrue(File(rollbackFailure.photoRoot, ".stage5-photo-transaction.cleanup").isFile)
            } finally {
                transaction.releaseAfterFailure()
            }
            assertEquals(failingFactory.opened, failingFactory.closed)
            assertEquals(0, failingFactory.usedAfterClose)

            val blockedStarted = mutableListOf<DocumentSession>()
            val blocked = newCallbacks(
                rollbackFailure,
                blockedStarted,
                FailOnPhotoMarkerDeleteFactory(".stage5-photo-canonical.intent")
            ).loadTarget(rollbackFailure.session)
            assertNotReady(blocked, "rollback cleanup evidence before restart reconciliation")
            assertTrue(blockedStarted.isEmpty())
            assertPhotoBytes(
                rollbackFailure,
                rollbackFailure.previousPhotoBytes,
                "rollback unresolved restart"
            )

            val restartedStarted = mutableListOf<DocumentSession>()
            val restarted = newCallbacks(rollbackFailure, restartedStarted)
                .loadTarget(rollbackFailure.session)
            val restartedLoaded = restarted as? SessionLoadResult.Loaded
            assertTrue("rollback restart must load only after cleanup", restartedLoaded != null)
            assertEquals(rollbackFailure.previous, restartedLoaded?.snapshot)
            assertTrue(restartedStarted.isEmpty())
            assertEquals(
                "previous",
                acceptedRevision(
                    FileSyncMetadataStore(File(rollbackFailure.root, "sync-metadata")),
                    rollbackFailure.scope
                )
            )
            assertPhotoBytes(
                rollbackFailure,
                rollbackFailure.previousPhotoBytes,
                "rollback restart"
            )
            assertNoStage5Markers(rollbackFailure.photoRoot)
        } finally {
            rollbackFailure.root.deleteRecursively()
        }
    }

    @Test
    fun crossStoreRollback_retainsEvidenceAcrossPhotoFirstProcessBoundary() = runTest {
        val testCase = newDurableRecoveryCase(savePrevious = true)
        val oldMetadata = acceptedMetadata(testCase, "previous")
        val oldMetadataIdentity = testCase.metadataStore.recoveryIdentity(oldMetadata)
        try {
            val transaction = stageRemoteAcceptance(testCase, TestPhotoPathOperationsFactory)
            try {
                // Model the incoming canonical and metadata authorities before
                // compensation begins. Photo rollback is intentionally the
                // first operation, so the retained journal is the only safe
                // admission/readiness boundary in this process-death window.
                assertEquals(
                    DocumentSaveResult.Saved(testCase.association.documentId),
                    testCase.repository.save(testCase.association, testCase.intended)
                )
                assertEquals(
                    MetadataWriteResult.Committed,
                    testCase.metadataStore.write(acceptedMetadata(testCase, "intended"))
                )
                transaction.markMetadataCommitted()
                transaction.prepareCrossStoreRollback(oldMetadataIdentity)
                transaction.rollbackForCrossStoreCompensation()
                assertPhotoBytes(testCase, testCase.previousPhotoBytes, "photo-first rollback")

                val blockedStarted = mutableListOf<DocumentSession>()
                val blocked = newCallbacks(testCase, blockedStarted).loadTarget(testCase.session)
                assertNotReady(blocked, "photo rollback before canonical/metadata restoration")
                assertTrue(blockedStarted.isEmpty())
                assertTrue(
                    "rollback evidence must survive the process-boundary window",
                    File(testCase.photoRoot, ".stage5-photo-transaction.marker").isFile
                )

                // Complete the other two authority restores, then publish the
                // rollback-complete proof through the owning transaction.
                assertEquals(
                    DocumentSaveResult.Saved(testCase.association.documentId),
                    testCase.repository.save(testCase.association, testCase.previous)
                )
                assertEquals(
                    MetadataWriteResult.Committed,
                    testCase.metadataStore.write(oldMetadata)
                )
                transaction.completeCrossStoreRollback(oldMetadataIdentity)
            } finally {
                transaction.releaseAfterFailure()
            }

            val recoveredStarted = mutableListOf<DocumentSession>()
            val recovered = newCallbacks(testCase, recoveredStarted).loadTarget(testCase.session)
            assertEquals(testCase.previous, (recovered as SessionLoadResult.Loaded).snapshot)
            assertTrue(recoveredStarted.isEmpty())
            assertPhotoBytes(testCase, testCase.previousPhotoBytes, "completed rollback")
            assertEquals(
                "previous",
                acceptedRevision(FileSyncMetadataStore(File(testCase.root, "sync-metadata")), testCase.scope)
            )
            assertNoStage5Markers(testCase.photoRoot)
        } finally {
            testCase.root.deleteRecursively()
        }
    }

    @Test
    fun crossStoreRollback_freshInstanceCompletesOnlyAfterExactOldTupleProof() = runTest {
        val testCase = newDurableRecoveryCase(savePrevious = true)
        val oldMetadata = acceptedMetadata(testCase, "previous")
        try {
            val transaction = stageRemoteAcceptance(testCase, TestPhotoPathOperationsFactory)
            try {
                assertEquals(
                    DocumentSaveResult.Saved(testCase.association.documentId),
                    testCase.repository.save(testCase.association, testCase.intended)
                )
                assertEquals(
                    MetadataWriteResult.Committed,
                    testCase.metadataStore.write(acceptedMetadata(testCase, "intended"))
                )
                transaction.markMetadataCommitted()
                transaction.prepareCrossStoreRollback(testCase.metadataStore.recoveryIdentity(oldMetadata))
                transaction.rollbackForCrossStoreCompensation()
                assertPhotoBytes(testCase, testCase.previousPhotoBytes, "fresh rollback photo proof")
                val pendingEvidence = listOf(
                    ".stage5-photo-canonical.intent",
                    ".stage5-photo-metadata.commit",
                    ".stage5-photo-transaction.marker",
                    ".stage5-photo-transaction.cleanup"
                ).associateWith { name ->
                    File(testCase.photoRoot, name).readBytes().toList()
                }

                // The first new process still sees the incoming durable and
                // metadata authorities. The pending rollback record must not
                // delete evidence or expose that mixed tuple as ready.
                val blockedStarted = mutableListOf<DocumentSession>()
                val blocked = newCallbacks(testCase, blockedStarted).loadTarget(testCase.session)
                assertNotReady(blocked, "fresh rollback before old authority restore")
                assertTrue(blockedStarted.isEmpty())
                assertTrue(File(testCase.photoRoot, ".stage5-photo-transaction.cleanup").isFile)

                // Simulate process death before the abandoned transaction can
                // call completeCrossStoreRollback. Restore both durable
                // authorities through fresh instances, then let a new photo
                // resolver/callback prove and finish the exact old tuple.
                val restartedRepository = LocalDocumentRepository(File(testCase.root, "local_documents"))
                assertEquals(
                    DocumentSaveResult.Saved(testCase.association.documentId),
                    restartedRepository.save(testCase.association, testCase.previous)
                )
                val restartedMetadataStore = FileSyncMetadataStore(File(testCase.root, "sync-metadata"))
                assertEquals(MetadataWriteResult.Committed, restartedMetadataStore.write(oldMetadata))
                pendingEvidence.forEach { (name, bytes) ->
                    assertEquals(
                        "rollback evidence must remain until the fresh old-tuple proof: $name",
                        bytes,
                        File(testCase.photoRoot, name).readBytes().toList()
                    )
                }
            } finally {
                transaction.releaseAfterFailure()
            }

            assertPhotoBytes(testCase, testCase.previousPhotoBytes, "fresh rollback restored tuple")
            val recoveredStarted = mutableListOf<DocumentSession>()
            val recovered = newCallbacks(testCase, recoveredStarted).loadTarget(testCase.session)
            assertEquals(testCase.previous, (recovered as SessionLoadResult.Loaded).snapshot)
            assertTrue(recoveredStarted.isEmpty())
            assertEquals("previous", acceptedRevision(FileSyncMetadataStore(File(testCase.root, "sync-metadata")), testCase.scope))
            assertPhotoBytes(testCase, testCase.previousPhotoBytes, "fresh rollback completion")
            assertNoStage5Markers(testCase.photoRoot)
        } finally {
            testCase.root.deleteRecursively()
        }
    }

    @Test
    fun callbackColdRestart_rehydratesUnequalPriorLiveAuthorityBeforeReadiness() = runTest {
        val testCase = newDurableRecoveryCase(savePrevious = true)
        val previousLive = testCase.previous.copy(snapshotRevision = 7L)
        val oldMetadata = acceptedMetadata(testCase, "previous")
        val oldMetadataIdentity = testCase.metadataStore.recoveryIdentity(oldMetadata)
        try {
            val transaction = stageRemoteAcceptance(
                testCase,
                TestPhotoPathOperationsFactory,
                previousLive
            )
            try {
                assertEquals(
                    DocumentSaveResult.Saved(testCase.association.documentId),
                    testCase.repository.save(testCase.association, testCase.intended)
                )
                assertEquals(
                    MetadataWriteResult.Committed,
                    testCase.metadataStore.write(acceptedMetadata(testCase, "intended"))
                )
                transaction.markMetadataCommitted()
                transaction.prepareCrossStoreRollback(oldMetadataIdentity)
                transaction.rollbackForCrossStoreCompensation()
                assertPhotoBytes(testCase, testCase.previousPhotoBytes, "unequal-pair photo rollback")

                val journalBytes = File(testCase.photoRoot, ".stage5-photo-transaction.marker").readBytes()
                val intentBytes = File(testCase.photoRoot, ".stage5-photo-canonical.intent").readBytes()
                val liveArtifactBytes = File(testCase.photoRoot, ".stage5-photo-canonical.live").readBytes()

                // The first cold-start attempt still sees incoming durable and
                // metadata authorities. The callback must not substitute B
                // for the recorded live A or clear any evidence.
                val blockedStarted = mutableListOf<DocumentSession>()
                val blocked = newCallbacks(testCase, blockedStarted).loadTarget(testCase.session)
                assertNotReady(blocked, "unequal prior-live pair before old authority restore")
                assertTrue(blockedStarted.isEmpty())
                assertEquals(journalBytes.toList(), File(testCase.photoRoot, ".stage5-photo-transaction.marker").readBytes().toList())
                assertEquals(intentBytes.toList(), File(testCase.photoRoot, ".stage5-photo-canonical.intent").readBytes().toList())
                assertEquals(liveArtifactBytes.toList(), File(testCase.photoRoot, ".stage5-photo-canonical.live").readBytes().toList())

                // Simulate process death before the abandoned transaction can
                // complete rollback. Fresh durable authorities are restored;
                // the fresh callback must rehydrate A and prove (B,A) before
                // it exposes a loaded session.
                val restartedRepository = LocalDocumentRepository(File(testCase.root, "local_documents"))
                assertEquals(
                    DocumentSaveResult.Saved(testCase.association.documentId),
                    restartedRepository.save(testCase.association, testCase.previous)
                )
                assertEquals(
                    MetadataWriteResult.Committed,
                    FileSyncMetadataStore(File(testCase.root, "sync-metadata")).write(oldMetadata)
                )
            } finally {
                transaction.releaseAfterFailure()
            }

            val recoveredStarted = mutableListOf<DocumentSession>()
            val recovered = newCallbacks(testCase, recoveredStarted).loadTarget(testCase.session)
            val loaded = recovered as? SessionLoadResult.Loaded
            assertTrue("cold restart should load only after exact unequal pair proof", loaded != null)
            assertEquals(previousLive, loaded?.snapshot)
            assertTrue(recoveredStarted.isEmpty())
            assertEquals(testCase.previousPhotoBytes.toList(), File(testCase.photoRoot, "photo.jpg").readBytes().toList())
            assertEquals("previous", acceptedRevision(FileSyncMetadataStore(File(testCase.root, "sync-metadata")), testCase.scope))
            assertNoStage5Markers(testCase.photoRoot)
        } finally {
            testCase.root.deleteRecursively()
        }
    }

    @Test
    fun crossStoreRollback_cleanupFailureForEveryMarkerRetainsExactProofForFreshRecovery() = runTest {
        val deletionOrder = listOf(
            ".stage5-photo-canonical.intent",
            ".stage5-photo-metadata.commit",
            ".stage5-photo-transaction.commit",
            ".stage5-photo-transaction.marker",
            ".stage5-photo-transaction.cleanup",
            ".stage5-photo-rollback.complete"
        )
        deletionOrder.forEach { failedMarker ->
            val testCase = newDurableRecoveryCase(savePrevious = true)
            val oldMetadata = acceptedMetadata(testCase, "previous")
            val oldMetadataIdentity = testCase.metadataStore.recoveryIdentity(oldMetadata)
            try {
                val failingFactory = FailOnPhotoMarkerDeleteFactory(failedMarker)
                val transaction = stageRemoteAcceptance(testCase, failingFactory)
                try {
                    assertEquals(
                        DocumentSaveResult.Saved(testCase.association.documentId),
                        testCase.repository.save(testCase.association, testCase.intended)
                    )
                    assertEquals(
                        MetadataWriteResult.Committed,
                        testCase.metadataStore.write(acceptedMetadata(testCase, "intended"))
                    )
                    transaction.markMetadataCommitted()
                    transaction.prepareCrossStoreRollback(oldMetadataIdentity)
                    transaction.rollbackForCrossStoreCompensation()
                    assertPhotoBytes(testCase, testCase.previousPhotoBytes, "cross-store old photo before cleanup $failedMarker")

                    // Restore the other two authorities before the transaction
                    // writes its complete rollback proof. This is the exact
                    // post-prepareCrossStoreRollback/completeCrossStoreRollback
                    // boundary, rather than the ordinary V2 rollback path.
                    assertEquals(
                        DocumentSaveResult.Saved(testCase.association.documentId),
                        testCase.repository.save(testCase.association, testCase.previous)
                    )
                    assertEquals(
                        MetadataWriteResult.Committed,
                        testCase.metadataStore.write(oldMetadata)
                    )

                    val journalBytes = File(
                        testCase.photoRoot,
                        ".stage5-photo-transaction.marker"
                    ).readBytes()
                    val intentBytes = File(
                        testCase.photoRoot,
                        ".stage5-photo-canonical.intent"
                    ).readBytes()
                    val metadataBytes = File(
                        testCase.photoRoot,
                        ".stage5-photo-metadata.commit"
                    ).readBytes()
                    val pendingCleanupBytes = File(
                        testCase.photoRoot,
                        ".stage5-photo-transaction.cleanup"
                    ).readBytes()

                    var failure: Throwable? = null
                    try {
                        transaction.completeCrossStoreRollback(
                            oldMetadataIdentity
                        )
                    } catch (error: PhotoRollbackException) {
                        failure = error
                    }
                    assertTrue(
                        "cross-store cleanup must fail at $failedMarker without ordinary success",
                        failure is PhotoRollbackException
                    )

                    // The new V2 proof is the durable owner after the first
                    // deletion attempt. Its bytes must be complete and stable,
                    // not merely present, even if the canonical intent/journal
                    // has already been removed.
                    val rollbackProof = File(
                        testCase.photoRoot,
                        ".stage5-photo-rollback.complete"
                    )
                    assertTrue("rollback proof must be retained at $failedMarker", rollbackProof.isFile)
                    val rollbackProofBytes = rollbackProof.readBytes()
                    assertRollbackProofContents(
                        testCase = testCase,
                        proofBytes = rollbackProofBytes,
                        journalBytes = journalBytes,
                        previousMetadataIdentity = oldMetadataIdentity
                    )
                    val pendingCleanup = File(
                        testCase.photoRoot,
                        ".stage5-photo-transaction.cleanup"
                    )
                    if (deletionOrder.indexOf(".stage5-photo-transaction.cleanup") >=
                        deletionOrder.indexOf(failedMarker)
                    ) {
                        assertEquals(
                            "pending rollback cleanup evidence must remain exact at $failedMarker",
                            pendingCleanupBytes.toList(),
                            pendingCleanup.readBytes().toList()
                        )
                    } else {
                        assertFalse(
                            "pending rollback cleanup evidence should be deleted at $failedMarker",
                            pendingCleanup.exists()
                        )
                    }
                    assertMarkerBytesAfterCrossStoreCleanupFailure(
                        testCase = testCase,
                        failedMarker = failedMarker,
                        journalBytes = journalBytes,
                        intentBytes = intentBytes,
                        metadataBytes = metadataBytes,
                        rollbackProofBytes = rollbackProofBytes
                    )
                    assertEquals(
                        "old canonical state must survive cleanup failure at $failedMarker",
                        testCase.previous,
                        durableSnapshot(
                            LocalDocumentRepository(File(testCase.root, "local_documents")),
                            testCase.association
                        )
                    )
                    assertEquals(
                        "old metadata state must survive cleanup failure at $failedMarker",
                        "previous",
                        acceptedRevision(
                            FileSyncMetadataStore(File(testCase.root, "sync-metadata")),
                            testCase.scope
                        )
                    )
                    assertPhotoBytes(
                        testCase,
                        testCase.previousPhotoBytes,
                        "old photo after cleanup failure $failedMarker"
                    )

                    transaction.releaseAfterFailure()
                    assertEquals(failingFactory.opened, failingFactory.closed)
                    assertEquals(0, failingFactory.usedAfterClose)

                    // A fresh resolver must remain recovery-bound until the
                    // callback supplies the exact old tuple; merely opening a
                    // new process must not consume the proof.
                    val reopened = PhotoPathResolver(
                        testCase.photoRoot,
                        createRoot = true,
                        operationsFactory = TestPhotoPathOperationsFactory
                    )
                    try {
                        var blocked = false
                        try {
                            reopened.requireCanonicalRecoveryResolved()
                        } catch (_: PhotoCanonicalRecoveryException) {
                            blocked = true
                        }
                        assertTrue("fresh resolver must retain rollback proof at $failedMarker", blocked)
                        assertEquals(
                            "rollback proof bytes must remain exact before reconciliation at $failedMarker",
                            rollbackProofBytes.toList(),
                            rollbackProof.readBytes().toList()
                        )
                    } finally {
                        reopened.close()
                    }
                } finally {
                    // The transaction owns the first resolver even after a
                    // marker deletion failure; release it only after evidence
                    // assertions, proving no use-after-close cleanup occurred.
                    transaction.releaseAfterFailure()
                }

                val recoveredStarted = mutableListOf<DocumentSession>()
                val recovered = newCallbacks(testCase, recoveredStarted).loadTarget(testCase.session)
                val loaded = recovered as? SessionLoadResult.Loaded
                assertTrue("fresh callback must reconcile $failedMarker", loaded != null)
                assertEquals(testCase.previous, loaded?.snapshot)
                assertTrue(recoveredStarted.isEmpty())
                assertPhotoBytes(testCase, testCase.previousPhotoBytes, "fresh old photo after $failedMarker")
                assertNoStage5Markers(testCase.photoRoot)
            } finally {
                testCase.root.deleteRecursively()
            }
        }
    }

    @Test
    fun crossStoreRollback_unequalPriorLiveSidecarFailureRetainsOrCompletesBoundEvidence() = runTest {
        listOf(
            ".stage5-photo-canonical.live" to true,
            ".stage5-photo-canonical.intent" to false
        ).forEach { (failedMarker, sidecarMustRemain) ->
            val testCase = newDurableRecoveryCase(savePrevious = true)
            val previousLive = durableAuthoritySnapshot(testCase.previous.source, "previous-live")
            val oldMetadata = acceptedMetadata(testCase, "previous")
            val oldMetadataIdentity = testCase.metadataStore.recoveryIdentity(oldMetadata)
            try {
                val transaction = stageRemoteAcceptance(
                    testCase,
                    FailOnPhotoMarkerDeleteFactory(failedMarker),
                    previousLive
                )
                try {
                    assertEquals(
                        DocumentSaveResult.Saved(testCase.association.documentId),
                        testCase.repository.save(testCase.association, testCase.intended)
                    )
                    assertEquals(
                        MetadataWriteResult.Committed,
                        testCase.metadataStore.write(acceptedMetadata(testCase, "intended"))
                    )
                    transaction.markMetadataCommitted()
                    transaction.prepareCrossStoreRollback(oldMetadataIdentity)
                    transaction.rollbackForCrossStoreCompensation()
                    assertPhotoBytes(testCase, testCase.previousPhotoBytes, "unequal sidecar rollback")

                    assertEquals(
                        DocumentSaveResult.Saved(testCase.association.documentId),
                        testCase.repository.save(testCase.association, testCase.previous)
                    )
                    assertEquals(MetadataWriteResult.Committed, testCase.metadataStore.write(oldMetadata))

                    val journalBytes = File(testCase.photoRoot, ".stage5-photo-transaction.marker").readBytes()
                    val intentBytes = File(testCase.photoRoot, ".stage5-photo-canonical.intent").readBytes()
                    val metadataBytes = File(testCase.photoRoot, ".stage5-photo-metadata.commit").readBytes()
                    val liveBytes = File(testCase.photoRoot, ".stage5-photo-canonical.live").readBytes()

                    var failure: Throwable? = null
                    try {
                        transaction.completeCrossStoreRollback(oldMetadataIdentity)
                    } catch (error: PhotoRollbackException) {
                        failure = error
                    }
                    assertTrue("sidecar cleanup must fail at $failedMarker", failure is PhotoRollbackException)
                    val phase = File(testCase.photoRoot, ".stage5-photo-canonical.live.cleanup")
                    assertEquals(
                        "SOTAWARE_STAGE5_PHOTO_CANONICAL_LIVE_CLEANUP_V1\n${sha256Hex(journalBytes)}\n",
                        phase.readText()
                    )
                    assertEquals(journalBytes.toList(), File(testCase.photoRoot, ".stage5-photo-transaction.marker").readBytes().toList())
                    assertEquals(intentBytes.toList(), File(testCase.photoRoot, ".stage5-photo-canonical.intent").readBytes().toList())
                    assertEquals(metadataBytes.toList(), File(testCase.photoRoot, ".stage5-photo-metadata.commit").readBytes().toList())
                    val proof = File(testCase.photoRoot, ".stage5-photo-rollback.complete")
                    assertTrue(proof.isFile)
                    val proofBytes = proof.readBytes()
                    assertEquals(proofBytes.toList(), proof.readBytes().toList())
                    if (sidecarMustRemain) {
                        assertEquals(liveBytes.toList(), File(testCase.photoRoot, ".stage5-photo-canonical.live").readBytes().toList())
                    } else {
                        assertFalse(File(testCase.photoRoot, ".stage5-photo-canonical.live").exists())
                    }
                    assertPhotoBytes(testCase, testCase.previousPhotoBytes, "old photo after sidecar cleanup failure")

                    transaction.releaseAfterFailure()
                    val restartedStarted = mutableListOf<DocumentSession>()
                    val restarted = newCallbacks(testCase, restartedStarted).loadTarget(testCase.session)
                    val loaded = restarted as? SessionLoadResult.Loaded
                    assertTrue("fresh sidecar recovery must load after exact proof", loaded != null)
                    assertEquals(
                        if (sidecarMustRemain) previousLive else testCase.previous,
                        loaded?.snapshot
                    )
                    assertTrue(restartedStarted.isEmpty())
                    assertFalse("fresh recovery must remove the proof after complete cleanup", proof.exists())
                    assertNoStage5Markers(testCase.photoRoot)
                } finally {
                    transaction.releaseAfterFailure()
                }
            } finally {
                testCase.root.deleteRecursively()
            }
        }
    }

    @Test
    fun crossStoreRollback_unequalPriorLiveProofDeletionAfterSidecar_isRestartSafe() = runTest {
        val testCase = newDurableRecoveryCase(savePrevious = true)
        val previousLive = durableAuthoritySnapshot(testCase.previous.source, "previous-live-proof")
        val oldMetadata = acceptedMetadata(testCase, "previous")
        val oldMetadataIdentity = testCase.metadataStore.recoveryIdentity(oldMetadata)
        try {
            val failingFactory = FailOnPhotoMarkerDeleteFactory(
                ".stage5-photo-rollback.complete"
            )
            val transaction = stageRemoteAcceptance(testCase, failingFactory, previousLive)
            try {
                assertEquals(
                    DocumentSaveResult.Saved(testCase.association.documentId),
                    testCase.repository.save(testCase.association, testCase.intended)
                )
                assertEquals(
                    MetadataWriteResult.Committed,
                    testCase.metadataStore.write(acceptedMetadata(testCase, "intended"))
                )
                transaction.markMetadataCommitted()
                transaction.prepareCrossStoreRollback(oldMetadataIdentity)
                transaction.rollbackForCrossStoreCompensation()
                assertEquals(
                    DocumentSaveResult.Saved(testCase.association.documentId),
                    testCase.repository.save(testCase.association, testCase.previous)
                )
                assertEquals(MetadataWriteResult.Committed, testCase.metadataStore.write(oldMetadata))

                val journalBytes = File(testCase.photoRoot, ".stage5-photo-transaction.marker").readBytes()
                val phaseBytes = (
                    "SOTAWARE_STAGE5_PHOTO_CANONICAL_LIVE_CLEANUP_V1\n" +
                        "${sha256Hex(journalBytes)}\n"
                    ).toByteArray(StandardCharsets.US_ASCII)
                var failure: Throwable? = null
                try {
                    transaction.completeCrossStoreRollback(oldMetadataIdentity)
                } catch (error: PhotoRollbackException) {
                    failure = error
                }
                assertTrue("proof deletion failure must be typed", failure is PhotoRollbackException)
                assertFalse("sidecar must already be deleted before proof cleanup", File(testCase.photoRoot, ".stage5-photo-canonical.live").exists())
                assertFalse(File(testCase.photoRoot, ".stage5-photo-canonical.intent").exists())
                assertFalse(File(testCase.photoRoot, ".stage5-photo-metadata.commit").exists())
                assertFalse(File(testCase.photoRoot, ".stage5-photo-transaction.marker").exists())
                assertFalse(File(testCase.photoRoot, ".stage5-photo-transaction.cleanup").exists())
                assertEquals(phaseBytes.toList(), File(testCase.photoRoot, ".stage5-photo-canonical.live.cleanup").readBytes().toList())
                val proof = File(testCase.photoRoot, ".stage5-photo-rollback.complete")
                assertTrue(proof.isFile)
                val proofBytes = proof.readBytes()
                assertRollbackProofContents(
                    testCase = testCase,
                    proofBytes = proofBytes,
                    journalBytes = journalBytes,
                    previousMetadataIdentity = oldMetadataIdentity,
                    previousLive = previousLive
                )
                assertEquals(testCase.previous, durableSnapshot(
                    LocalDocumentRepository(File(testCase.root, "local_documents")),
                    testCase.association
                ))
                assertEquals("previous", acceptedRevision(
                    FileSyncMetadataStore(File(testCase.root, "sync-metadata")),
                    testCase.scope
                ))
                assertPhotoBytes(testCase, testCase.previousPhotoBytes, "old tuple after proof deletion failure")

                transaction.releaseAfterFailure()
                assertEquals(failingFactory.opened, failingFactory.closed)
                assertEquals(0, failingFactory.usedAfterClose)

                val reopened = PhotoPathResolver(
                    testCase.photoRoot,
                    createRoot = true,
                    operationsFactory = TestPhotoPathOperationsFactory
                )
                try {
                    var blocked = false
                    try {
                        reopened.requireCanonicalRecoveryResolved()
                    } catch (_: PhotoCanonicalRecoveryException) {
                        blocked = true
                    }
                    assertTrue("proof/phase evidence must block before callback reconciliation", blocked)
                    assertEquals(proofBytes.toList(), proof.readBytes().toList())
                    assertEquals(phaseBytes.toList(), File(testCase.photoRoot, ".stage5-photo-canonical.live.cleanup").readBytes().toList())
                } finally {
                    reopened.close()
                }
            } finally {
                transaction.releaseAfterFailure()
            }

            val started = mutableListOf<DocumentSession>()
            val recovered = newCallbacks(testCase, started).loadTarget(testCase.session)
            val loaded = recovered as? SessionLoadResult.Loaded
            assertTrue("fresh callback must finish already-proven cleanup", loaded != null)
            assertEquals(testCase.previous, loaded?.snapshot)
            assertTrue(started.isEmpty())
            assertPhotoBytes(testCase, testCase.previousPhotoBytes, "old tuple after proof recovery")
            assertNoStage5Markers(testCase.photoRoot)
        } finally {
            testCase.root.deleteRecursively()
        }
    }

    @Test
    fun crossStoreRollback_alteredOrMalformedV2ProofFailsClosedAcrossFreshInstances() = runTest {
        val mutations = listOf(
            "altered journal identity" to { bytes: ByteArray ->
                val lines = bytes.toString(StandardCharsets.US_ASCII)
                    .removeSuffix("\n")
                    .split('\n')
                    .toMutableList()
                val original = lines[1]
                lines[1] = (if (original[0] == '0') '1' else '0') + original.drop(1)
                lines.joinToString("\n", postfix = "\n")
                    .toByteArray(StandardCharsets.US_ASCII)
            },
            "malformed field count" to { bytes: ByteArray ->
                val lines = bytes.toString(StandardCharsets.US_ASCII)
                    .removeSuffix("\n")
                    .split('\n')
                    .dropLast(1)
                lines.joinToString("\n", postfix = "\n")
                    .toByteArray(StandardCharsets.US_ASCII)
            }
        )
        mutations.forEach { (label, mutateProof) ->
            val testCase = newDurableRecoveryCase(savePrevious = true)
            val oldMetadata = acceptedMetadata(testCase, "previous")
            val oldMetadataIdentity = testCase.metadataStore.recoveryIdentity(oldMetadata)
            try {
                val transaction = stageRemoteAcceptance(
                    testCase,
                    FailOnPhotoMarkerDeleteFactory(".stage5-photo-canonical.intent")
                )
                try {
                    assertEquals(
                        DocumentSaveResult.Saved(testCase.association.documentId),
                        testCase.repository.save(testCase.association, testCase.intended)
                    )
                    assertEquals(
                        MetadataWriteResult.Committed,
                        testCase.metadataStore.write(acceptedMetadata(testCase, "intended"))
                    )
                    transaction.markMetadataCommitted()
                    transaction.prepareCrossStoreRollback(oldMetadataIdentity)
                    transaction.rollbackForCrossStoreCompensation()
                    assertEquals(
                        DocumentSaveResult.Saved(testCase.association.documentId),
                        testCase.repository.save(testCase.association, testCase.previous)
                    )
                    assertEquals(MetadataWriteResult.Committed, testCase.metadataStore.write(oldMetadata))

                    var rollbackFailed = false
                    try {
                        transaction.completeCrossStoreRollback(oldMetadataIdentity)
                    } catch (_: PhotoRollbackException) {
                        rollbackFailed = true
                    }
                    assertTrue("valid V2 proof setup must fail at the injected boundary", rollbackFailed)
                } finally {
                    transaction.releaseAfterFailure()
                }

                val proofFile = File(testCase.photoRoot, ".stage5-photo-rollback.complete")
                val validProofBytes = proofFile.readBytes()
                assertRollbackProofContents(
                    testCase = testCase,
                    proofBytes = validProofBytes,
                    journalBytes = File(
                        testCase.photoRoot,
                        ".stage5-photo-transaction.marker"
                    ).readBytes(),
                    previousMetadataIdentity = oldMetadataIdentity
                )
                val markerNames = listOf(
                    ".stage5-photo-transaction.marker",
                    ".stage5-photo-canonical.intent",
                    ".stage5-photo-metadata.commit",
                    ".stage5-photo-transaction.cleanup",
                    ".stage5-photo-rollback.complete"
                )
                val markerBytes = markerNames.associateWith { name ->
                    File(testCase.photoRoot, name).readBytes()
                }
                val mutatedProofBytes = mutateProof(validProofBytes)
                assertFalse("$label must change the proof bytes", validProofBytes.contentEquals(mutatedProofBytes))
                proofFile.writeBytes(mutatedProofBytes)

                var constructorFailure: PhotoCanonicalRecoveryException? = null
                var reopened: PhotoPathResolver? = null
                try {
                    reopened = PhotoPathResolver(
                        testCase.photoRoot,
                        createRoot = true,
                        operationsFactory = TestPhotoPathOperationsFactory
                    )
                } catch (error: PhotoCanonicalRecoveryException) {
                    constructorFailure = error
                } finally {
                    reopened?.close()
                }
                assertTrue("$label proof readback must fail closed", constructorFailure != null)

                val started = mutableListOf<DocumentSession>()
                assertNotReady(
                    newCallbacks(testCase, started).loadTarget(testCase.session),
                    "$label proof through fresh session callbacks"
                )
                assertTrue("$label proof must not start background work", started.isEmpty())

                markerBytes.forEach { (name, expected) ->
                    val actual = File(testCase.photoRoot, name)
                    assertTrue("$label must retain marker $name", actual.isFile)
                    val expectedBytes = if (name == ".stage5-photo-rollback.complete") {
                        mutatedProofBytes
                    } else {
                        expected
                    }
                    assertEquals(
                        "$label must retain exact bytes for $name",
                        expectedBytes.toList(),
                        actual.readBytes().toList()
                    )
                }
                assertEquals(
                    "$label must retain the old canonical authority",
                    testCase.previous,
                    durableSnapshot(
                        LocalDocumentRepository(File(testCase.root, "local_documents")),
                        testCase.association
                    )
                )
                assertEquals(
                    "$label must retain the old metadata authority",
                    "previous",
                    acceptedRevision(
                        FileSyncMetadataStore(File(testCase.root, "sync-metadata")),
                        testCase.scope
                    )
                )
                assertPhotoBytes(testCase, testCase.previousPhotoBytes, "$label old photo")
            } finally {
                testCase.root.deleteRecursively()
            }
        }
    }

    @Test
    fun legacyV1RollbackCompletion_cannotAuthorizeMixedV3EvidenceAcrossFreshInstances() = runTest {
        val forgedCleanupRecords = listOf(
            "downgraded V1 completion" to { journalIdentity: String ->
                "SOTAWARE_STAGE5_PHOTO_ROLLBACK_COMPLETE_V1\n$journalIdentity\n"
                    .toByteArray(StandardCharsets.US_ASCII)
            },
            "V1 completion with the wrong owner" to { _: String ->
                "SOTAWARE_STAGE5_PHOTO_ROLLBACK_COMPLETE_V1\n${"0".repeat(64)}\n"
                    .toByteArray(StandardCharsets.US_ASCII)
            },
            "malformed V1 completion" to { journalIdentity: String ->
                "SOTAWARE_STAGE5_PHOTO_ROLLBACK_COMPLETE_V1\n$journalIdentity\nextra\n"
                    .toByteArray(StandardCharsets.US_ASCII)
            }
        )

        forgedCleanupRecords.forEach { (label, forgeCleanup) ->
            val testCase = newDurableRecoveryCase(savePrevious = true)
            val oldMetadata = acceptedMetadata(testCase, "previous")
            val oldMetadataIdentity = testCase.metadataStore.recoveryIdentity(oldMetadata)
            try {
                val transaction = stageRemoteAcceptance(testCase, TestPhotoPathOperationsFactory)
                val pendingCleanupBytes: ByteArray
                val journalBytes: ByteArray
                val intentBytes: ByteArray
                val metadataBytes: ByteArray
                try {
                    assertEquals(
                        DocumentSaveResult.Saved(testCase.association.documentId),
                        testCase.repository.save(testCase.association, testCase.intended)
                    )
                    assertEquals(
                        MetadataWriteResult.Committed,
                        testCase.metadataStore.write(acceptedMetadata(testCase, "intended"))
                    )
                    transaction.markMetadataCommitted()
                    transaction.prepareCrossStoreRollback(oldMetadataIdentity)
                    pendingCleanupBytes = File(
                        testCase.photoRoot,
                        ".stage5-photo-transaction.cleanup"
                    ).readBytes()
                    transaction.rollbackForCrossStoreCompensation()
                    assertPhotoBytes(testCase, testCase.previousPhotoBytes, "$label old photo")

                    journalBytes = File(
                        testCase.photoRoot,
                        ".stage5-photo-transaction.marker"
                    ).readBytes()
                    intentBytes = File(
                        testCase.photoRoot,
                        ".stage5-photo-canonical.intent"
                    ).readBytes()
                    metadataBytes = File(
                        testCase.photoRoot,
                        ".stage5-photo-metadata.commit"
                    ).readBytes()
                    assertTrue(
                        "$label must be paired with the V3 remote-acceptance intent",
                        intentBytes.toString(StandardCharsets.US_ASCII)
                            .startsWith("SOTAWARE_STAGE5_PHOTO_CANONICAL_V3\n")
                    )

                    File(testCase.photoRoot, ".stage5-photo-transaction.cleanup")
                        .writeBytes(forgeCleanup(sha256Hex(journalBytes)))
                } finally {
                    transaction.releaseAfterFailure()
                }

                val forgedCleanupBytes = File(
                    testCase.photoRoot,
                    ".stage5-photo-transaction.cleanup"
                ).readBytes()
                val evidence = mapOf(
                    ".stage5-photo-transaction.marker" to journalBytes,
                    ".stage5-photo-canonical.intent" to intentBytes,
                    ".stage5-photo-metadata.commit" to metadataBytes,
                    ".stage5-photo-transaction.cleanup" to forgedCleanupBytes
                )

                var constructorFailure: PhotoCanonicalRecoveryException? = null
                var reopened: PhotoPathResolver? = null
                try {
                    reopened = PhotoPathResolver(
                        testCase.photoRoot,
                        createRoot = true,
                        operationsFactory = TestPhotoPathOperationsFactory
                    )
                } catch (error: PhotoCanonicalRecoveryException) {
                    constructorFailure = error
                } finally {
                    reopened?.close()
                }
                assertTrue(
                    "$label must fail closed in a fresh photo resolver",
                    constructorFailure != null
                )

                val started = mutableListOf<DocumentSession>()
                assertNotReady(
                    newCallbacks(testCase, started).loadTarget(testCase.session),
                    "$label must block fresh session readiness"
                )
                assertTrue("$label must not start background work", started.isEmpty())
                evidence.forEach { (name, expected) ->
                    val actual = File(testCase.photoRoot, name)
                    assertTrue("$label must retain $name", actual.isFile)
                    assertEquals(
                        "$label must retain exact bytes for $name",
                        expected.toList(),
                        actual.readBytes().toList()
                    )
                }
                assertEquals(
                    "$label must leave the incoming canonical authority untouched",
                    testCase.intended,
                    durableSnapshot(
                        LocalDocumentRepository(File(testCase.root, "local_documents")),
                        testCase.association
                    )
                )
                assertEquals(
                    "$label must leave the incoming metadata authority untouched",
                    "intended",
                    acceptedRevision(
                        FileSyncMetadataStore(File(testCase.root, "sync-metadata")),
                        testCase.scope
                    )
                )
                assertPhotoBytes(testCase, testCase.previousPhotoBytes, "$label retained old photo")
                assertFalse(
                    "$label must not manufacture a V2 rollback proof",
                    File(testCase.photoRoot, ".stage5-photo-rollback.complete").exists()
                )

                // A V1 record is never upgraded in place. Restore the exact
                // V3 pending record and the old canonical/metadata tuple;
                // only that proven protocol can authorize fresh-instance
                // cleanup.
                assertEquals(
                    DocumentSaveResult.Saved(testCase.association.documentId),
                    LocalDocumentRepository(File(testCase.root, "local_documents"))
                        .save(testCase.association, testCase.previous)
                )
                assertEquals(
                    MetadataWriteResult.Committed,
                    FileSyncMetadataStore(File(testCase.root, "sync-metadata")).write(oldMetadata)
                )
                File(testCase.photoRoot, ".stage5-photo-transaction.cleanup")
                    .writeBytes(pendingCleanupBytes)

                val recoveredStarted = mutableListOf<DocumentSession>()
                val recovered = newCallbacks(testCase, recoveredStarted).loadTarget(testCase.session)
                val loaded = recovered as? SessionLoadResult.Loaded
                assertTrue("$label must recover only after exact old-tuple proof", loaded != null)
                assertEquals(testCase.previous, loaded?.snapshot)
                assertTrue(recoveredStarted.isEmpty())
                assertEquals(
                    "previous",
                    acceptedRevision(
                        FileSyncMetadataStore(File(testCase.root, "sync-metadata")),
                        testCase.scope
                    )
                )
                assertPhotoBytes(testCase, testCase.previousPhotoBytes, "$label recovered old photo")
                assertNoStage5Markers(testCase.photoRoot)
            } finally {
                testCase.root.deleteRecursively()
            }
        }
    }

    @Test
    fun rollbackPendingEvidence_blocksOfflineEmptyUntilFreshExactOldTupleExists() = runTest {
        val testCase = newDurableRecoveryCase(savePrevious = false)
        val oldMetadata = acceptedMetadata(testCase, "previous")
        try {
            val transaction = stageRemoteAcceptance(testCase, TestPhotoPathOperationsFactory)
            try {
                assertEquals(
                    DocumentSaveResult.Saved(testCase.association.documentId),
                    testCase.repository.save(testCase.association, testCase.intended)
                )
                assertEquals(
                    MetadataWriteResult.Committed,
                    testCase.metadataStore.write(acceptedMetadata(testCase, "intended"))
                )
                transaction.markMetadataCommitted()
                transaction.prepareCrossStoreRollback(testCase.metadataStore.recoveryIdentity(oldMetadata))
                transaction.rollbackForCrossStoreCompensation()
            } finally {
                transaction.releaseAfterFailure()
            }

            // Remove only the authoritative local snapshot to model an
            // offline/empty open. Rollback evidence remains in the photo root.
            assertTrue(testCase.repository.currentSnapshotFile(testCase.association.documentId).delete())
            assertFalse(testCase.repository.previousSnapshotFile(testCase.association.documentId).exists())
            val blockedStarted = mutableListOf<DocumentSession>()
            val blocked = newCallbacks(testCase, blockedStarted).loadTarget(testCase.session)
            assertNotReady(blocked, "rollback-pending evidence with no authoritative snapshot")
            assertTrue("blocked empty recovery must not start background work", blockedStarted.isEmpty())
            assertTrue(
                File(testCase.photoRoot, ".stage5-photo-transaction.cleanup").isFile
            )

            // Only a fresh proof of all three old authorities can unblock the
            // session. The old photo is absent here, matching its recorded
            // digest, and the fresh repository/store instances model restart.
            val restartedRepository = LocalDocumentRepository(File(testCase.root, "local_documents"))
            assertEquals(
                DocumentSaveResult.Saved(testCase.association.documentId),
                restartedRepository.save(testCase.association, testCase.previous)
            )
            val restartedMetadataStore = FileSyncMetadataStore(File(testCase.root, "sync-metadata"))
            assertEquals(MetadataWriteResult.Committed, restartedMetadataStore.write(oldMetadata))
            val recoveredStarted = mutableListOf<DocumentSession>()
            val recovered = newCallbacks(testCase, recoveredStarted).loadTarget(testCase.session)
            val loaded = recovered as? SessionLoadResult.Loaded
            assertTrue("exact old tuple should resolve rollback-pending evidence: $recovered", loaded != null)
            assertEquals(testCase.previous, loaded?.snapshot)
            assertTrue(recoveredStarted.isEmpty())
            assertFalse(File(testCase.photoRoot, "photo.jpg").isFile)
            assertNoStage5Markers(testCase.photoRoot)
        } finally {
            testCase.root.deleteRecursively()
        }
    }

    private data class DurableRecoveryCase(
        val root: File,
        val repository: LocalDocumentRepository,
        val metadataStore: FileSyncMetadataStore,
        val association: DocumentAssociation,
        val scope: SyncScope,
        val session: DocumentSession,
        val previous: DocumentSnapshotV1,
        val intended: DocumentSnapshotV1,
        val previousPhotoBytes: ByteArray,
        val incomingPhotoBytes: ByteArray
    ) {
        val photoRoot: File
            get() = File(root, "documents/${association.documentId.value}/photos")
    }

    private suspend fun newDurableRecoveryCase(savePrevious: Boolean): DurableRecoveryCase {
        val root = Files.createTempDirectory("stage5-three-authority").toFile()
        val repository = LocalDocumentRepository(File(root, "local_documents"))
        val source = DocumentSourceIdentityV1(
            sourceUri = "content://stage5/durable-authority",
            displayName = "plan.pdf"
        )
        val association = when (val resolved = repository.resolveOrCreate(source, null)) {
            is ResolveDocumentResult.Resolved -> resolved.association
            else -> error("durable recovery test could not allocate document association: $resolved")
        }
        val previous = durableAuthoritySnapshot(source, "previous")
        val intended = durableAuthoritySnapshot(source, "intended")
        if (savePrevious) {
            assertEquals(
                DocumentSaveResult.Saved(association.documentId),
                repository.save(association, previous)
            )
        }
        val scope = SyncScope("stage5-account", "stage5-root", association.documentId)
        val metadataStore = FileSyncMetadataStore(File(root, "sync-metadata"))
        assertEquals(
            MetadataWriteResult.Committed,
            metadataStore.write(SyncMetadata(scope = scope, acceptedCursor = RemoteCursor("previous")))
        )
        val session = DocumentSession(
            target = ResolvedDocumentTarget(association),
            token = DocumentSessionToken(
                documentId = association.documentId,
                sourceUri = source.sourceUri,
                sourceFingerprint = association.sourceFingerprint,
                generation = 1L
            )
        )
        val previousPhotoBytes = Stage4PhotoFixture.previousJpegBytes()
        val incomingPhotoBytes = Stage4PhotoFixture.incomingJpegBytes()
        val previousDescriptor = validatePhotoBytes(previousPhotoBytes).descriptor
        val incomingDescriptor = validatePhotoBytes(incomingPhotoBytes).descriptor
        assertFalse(previousPhotoBytes.contentEquals(incomingPhotoBytes))
        assertNotEquals(previousDescriptor.sha256, incomingDescriptor.sha256)
        if (savePrevious) {
            val photoRoot = File(root, "documents/${association.documentId.value}/photos")
            require(photoRoot.mkdirs() || photoRoot.isDirectory)
            File(photoRoot, "photo.jpg").writeBytes(previousPhotoBytes)
            assertEquals(previousPhotoBytes.toList(), File(photoRoot, "photo.jpg").readBytes().toList())
            assertEquals(
                sha256Hex(previousPhotoBytes),
                sha256Hex(File(photoRoot, "photo.jpg").readBytes())
            )
        }
        return DurableRecoveryCase(
            root = root,
            repository = repository,
            metadataStore = metadataStore,
            association = association,
            scope = scope,
            session = session,
            previous = previous,
            intended = intended,
            previousPhotoBytes = previousPhotoBytes,
            incomingPhotoBytes = incomingPhotoBytes
        )
    }

    private suspend fun stageRemoteAcceptance(
        testCase: DurableRecoveryCase,
        operationsFactory: PhotoPathOperationsFactory,
        previousLive: DocumentSnapshotV1 = testCase.previous
    ): StagedPhotoContentTransaction {
        val transaction = StagedPhotoContentTransaction.stageForTesting(
            testCase.photoRoot,
            mapOf("photo.jpg" to testCase.incomingPhotoBytes),
            operationsFactory
        )
        transaction.prepareCanonicalRecovery(
            photoCanonicalIdentity(testCase.association.documentId, testCase.previous),
            photoCanonicalIdentity(testCase.association.documentId, previousLive),
            previousLive,
            photoCanonicalIdentity(testCase.association.documentId, testCase.intended),
            PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE
        )
        transaction.publish()
        return transaction
    }

    private fun newCallbacks(
        testCase: DurableRecoveryCase,
        started: MutableList<DocumentSession>,
        operationsFactory: PhotoPathOperationsFactory = TestPhotoPathOperationsFactory
    ): AndroidDocumentSessionCallbacks {
        val context = DurableRecoveryContext(testCase.root)
        return AndroidDocumentSessionCallbacks(
            context = context,
            viewModel = BlueprintViewModel(),
            repository = LocalDocumentRepository(File(testCase.root, "local_documents")),
            legacySource = AndroidLegacyPersistenceSource(context),
            onSessionEstablished = {},
            onStateCleared = {},
            onPageCount = { _, _ -> },
            onRecovered = {},
            onFailure = {},
            onStart = { started += it },
            cancelAndJoinWork = {},
            resumeWork = {},
            loadPageCount = { 1 },
            photoAssetStoreFactory = { ownerContext, documentId ->
                DocumentPhotoAssetStore(
                    ownerContext.filesDir,
                    documentId,
                    DefaultImageProbe,
                    operationsFactory
                )
            },
            loadPageCountForSource = { 1 },
            photoRecoveryMetadataIdentity = { association ->
                val scope = testCase.scope.copy(documentId = association.documentId)
                val store = FileSyncMetadataStore(File(testCase.root, "sync-metadata"))
                when (val metadata = store.read(scope)) {
                    is MetadataReadResult.Loaded -> store.recoveryIdentity(
                        metadata.metadata ?: SyncMetadata(scope = scope)
                    )
                    is MetadataReadResult.Failed -> throw IllegalStateException(
                        "test metadata recovery read failed: ${metadata.error}"
                    )
                }
            }
        )
    }

    private fun assertNotReady(result: SessionLoadResult, phase: String) {
        assertTrue("$phase must remain unavailable", result is SessionLoadResult.Failed)
    }

    private fun assertPhotoBytes(
        testCase: DurableRecoveryCase,
        expected: ByteArray,
        phase: String
    ) {
        val file = File(testCase.photoRoot, "photo.jpg")
        assertTrue("$phase photo must exist", file.isFile)
        val actual = file.readBytes()
        assertEquals("$phase photo bytes", expected.toList(), actual.toList())
        assertEquals("$phase photo SHA-256", sha256Hex(expected), sha256Hex(actual))
        validatePhotoBytes(actual)
    }

    private suspend fun durableSnapshot(
        repository: LocalDocumentRepository,
        association: DocumentAssociation
    ): DocumentSnapshotV1 {
        return when (val loaded = repository.load(association)) {
            is DocumentLoadResult.Loaded -> loaded.snapshot
            else -> error("durable snapshot was not available: $loaded")
        }
    }

    private suspend fun acceptedRevision(
        store: FileSyncMetadataStore,
        scope: SyncScope
    ): String? {
        return (store.read(scope) as MetadataReadResult.Loaded).metadata?.acceptedCursor?.revision
    }

    private fun acceptedMetadata(testCase: DurableRecoveryCase, revision: String): SyncMetadata =
        SyncMetadata(scope = testCase.scope, acceptedCursor = RemoteCursor(revision))

    private fun durableAuthoritySnapshot(
        source: DocumentSourceIdentityV1,
        marker: String
    ): DocumentSnapshotV1 = DocumentSnapshotV1(
        schemaVersion = 1,
        snapshotRevision = if (marker == "previous") 0L else 1L,
        source = source,
        pages = mapOf(
            0 to PageSnapshotV1(
                photoPins = listOf(
                    PhotoPinSnapshotV1(
                        x = 0.5f,
                        y = 0.5f,
                        id = "durable-$marker",
                        imageFileNames = listOf("photo.jpg"),
                        imageNotes = emptyMap(),
                        imageShapes = emptyMap()
                    )
                )
            )
        )
    )

    private fun assertNoStage5Markers(photoRoot: File) {
        listOf(
            ".stage5-photo-transaction.marker",
            ".stage5-photo-transaction.commit",
            ".stage5-photo-canonical.intent",
            ".stage5-photo-metadata.commit",
            ".stage5-photo-transaction.cleanup",
            ".stage5-photo-rollback.complete",
            ".stage5-photo-canonical.live",
            ".stage5-photo-canonical.live.cleanup"
        ).forEach { marker ->
            assertFalse("marker $marker must be gone after recovery", File(photoRoot, marker).exists())
        }
    }

    private fun assertRollbackProofContents(
        testCase: DurableRecoveryCase,
        proofBytes: ByteArray,
        journalBytes: ByteArray,
        previousMetadataIdentity: String,
        previousLive: DocumentSnapshotV1 = testCase.previous
    ) {
        val text = proofBytes.toString(StandardCharsets.US_ASCII)
        assertTrue("rollback proof must have a newline-terminated record", text.endsWith('\n'))
        val lines = text.dropLast(1).split('\n')
        assertEquals("rollback proof field count", 12, lines.size)
        assertEquals("SOTAWARE_STAGE5_PHOTO_ROLLBACK_COMPLETE_V2", lines[0])
        assertEquals(sha256Hex(journalBytes), lines[1])
        assertEquals(previousMetadataIdentity, lines[2])

        val previous = photoCanonicalIdentity(testCase.association.documentId, testCase.previous)
        val intended = photoCanonicalIdentity(testCase.association.documentId, testCase.intended)
        fun decode(index: Int): String =
            Base64.getUrlDecoder().decode(lines[index]).toString(StandardCharsets.UTF_8)
        assertEquals(previous.documentId, decode(3))
        assertEquals(previous.snapshotDigest, decode(4))
        val previousSourceField = decode(5)
        if (previousLive == testCase.previous) {
            assertEquals(previous.sourceUri, previousSourceField)
        } else {
            val pair = previousSourceField.split('\n')
            assertEquals("SOTAWARE_PHOTO_PREVIOUS_PAIR_V1", pair[0])
            fun decodePair(index: Int): String =
                Base64.getUrlDecoder().decode(pair[index]).toString(StandardCharsets.UTF_8)
            val live = photoCanonicalIdentity(testCase.association.documentId, previousLive)
            assertEquals(previous.documentId, decodePair(1))
            assertEquals(previous.snapshotDigest, decodePair(2))
            assertEquals(previous.sourceUri, decodePair(3))
            assertEquals(live.documentId, decodePair(4))
            assertEquals(live.snapshotDigest, decodePair(5))
            assertEquals(live.sourceUri, decodePair(6))
        }
        assertEquals(intended.documentId, decode(6))
        assertEquals(intended.snapshotDigest, decode(7))
        assertEquals(intended.sourceUri, decode(8))

        val journalEntry = PhotoTransactionJournalEntry(
            stagedName = "staged",
            targetName = "photo.jpg",
            backupName = "backup",
            targetExisted = true
        )
        val previousDigest = photoTransactionContentDigest(listOf(journalEntry)) { name ->
            if (name == "photo.jpg") testCase.previousPhotoBytes else null
        }
        val intendedDigest = photoTransactionContentDigest(listOf(journalEntry)) { name ->
            if (name == "photo.jpg") testCase.incomingPhotoBytes else null
        }
        assertEquals(previousDigest, lines[9])
        assertEquals(intendedDigest, lines[10])
        assertEquals(journalBytes.toList(), Base64.getUrlDecoder().decode(lines[11]).toList())
    }

    private fun assertMarkerBytesAfterCrossStoreCleanupFailure(
        testCase: DurableRecoveryCase,
        failedMarker: String,
        journalBytes: ByteArray,
        intentBytes: ByteArray,
        metadataBytes: ByteArray,
        rollbackProofBytes: ByteArray
    ) {
        val deletionOrder = listOf(
            ".stage5-photo-canonical.intent",
            ".stage5-photo-metadata.commit",
            ".stage5-photo-transaction.commit",
            ".stage5-photo-transaction.marker",
            ".stage5-photo-transaction.cleanup",
            ".stage5-photo-rollback.complete",
            ".stage5-photo-canonical.live",
            ".stage5-photo-canonical.live.cleanup"
        )
        val failedIndex = deletionOrder.indexOf(failedMarker)
        require(failedIndex >= 0)
        val expected = mapOf(
            ".stage5-photo-canonical.intent" to intentBytes,
            ".stage5-photo-metadata.commit" to metadataBytes,
            ".stage5-photo-transaction.marker" to journalBytes,
            ".stage5-photo-rollback.complete" to rollbackProofBytes
        )
        expected.forEach { (name, bytes) ->
            val file = File(testCase.photoRoot, name)
            val markerIndex = deletionOrder.indexOf(name)
            val shouldRemain = markerIndex >= failedIndex ||
                failedMarker == ".stage5-photo-transaction.cleanup" &&
                name == ".stage5-photo-transaction.marker"
            if (shouldRemain) {
                assertTrue("retained marker missing at $failedMarker: $name", file.isFile)
                assertEquals(
                    "retained marker bytes changed at $failedMarker: $name",
                    bytes.toList(),
                    file.readBytes().toList()
                )
            } else {
                assertFalse("marker should already be deleted at $failedMarker: $name", file.exists())
            }
        }
    }

    private class DurableRecoveryContext(private val root: File) : ContextWrapper(null) {
        private val preferences = EmptyPreferences()

        override fun getFilesDir(): File = root

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = preferences
    }

    private class EmptyPreferences : SharedPreferences {
        override fun getAll(): Map<String, *> = emptyMap<String, Any?>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = EmptyEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class EmptyEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
        override fun putStringSet(key: String?, value: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String?): SharedPreferences.Editor = this
        override fun clear(): SharedPreferences.Editor = this
        override fun commit(): Boolean = true
        override fun apply() = Unit
    }
}
