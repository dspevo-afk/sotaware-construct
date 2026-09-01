package com.example.myapplication.stage3

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.core.net.toUri
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.getFileName
import com.example.myapplication.stage1.applySnapshotReplace
import com.example.myapplication.stage1.documentSourceIdentityForSnapshot
import com.example.myapplication.stage2.AndroidLegacyPersistenceSource
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentDurableSnapshotState
import com.example.myapplication.stage2.DocumentLoadResult
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.LegacyMigrationResult
import com.example.myapplication.stage2.LocalDocumentRepository
import com.example.myapplication.stage2.LocalRepositoryError
import com.example.myapplication.stage2.ResolveDocumentResult
import com.example.myapplication.stage2.fingerprintContentUri
import com.example.myapplication.stage2.migrateLegacy
import com.example.myapplication.stage5.DocumentPhotoAssetStore
import com.example.myapplication.stage5.PhotoCanonicalRecoveryException
import com.example.myapplication.stage5.Stage5ValidationException
import com.example.myapplication.stage7.Stage7WorkerResourceBoundary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Android adapter for the Stage 3 coordinator. It deliberately contains no
 * Compose state transitions beyond the callbacks supplied by BlueprintApp.
 */
class AndroidDocumentSessionCallbacks(
    private val context: Context,
    private val viewModel: BlueprintViewModel,
    private val repository: LocalDocumentRepository,
    private val legacySource: AndroidLegacyPersistenceSource,
    private val onSessionEstablished: (DocumentSession) -> Unit,
    private val onStateCleared: () -> Unit,
    private val onPageCount: (DocumentSession, Int) -> Unit,
    private val onRecovered: (DocumentSession) -> Unit,
    private val onFailure: (SwitchFailure) -> Unit,
    private val onStart: (DocumentSession) -> Unit,
    private val cancelAndJoinWork: suspend (DocumentSession) -> Unit,
    private val closeDocumentWorkAction: suspend () -> Unit = {},
    private val resumeWork: (DocumentSession) -> Unit,
    private val loadPageCount: suspend (Uri) -> Int,
    private val workerBoundary: Stage7WorkerResourceBoundary = Stage7WorkerResourceBoundary(),
    /** JVM recovery tests may supply the same document-scoped seam explicitly. */
    internal val photoAssetStoreFactory: (Context, DocumentId) -> DocumentPhotoAssetStore =
        { ownerContext, documentId -> DocumentPhotoAssetStore(ownerContext.filesDir, documentId) },
    /** JVM recovery tests can keep page loading independent of Android Uri stubs. */
    internal val loadPageCountForSource: (suspend (String) -> Int)? = null,
    /**
     * Supplies the complete current sync-metadata identity when a durable
     * remote-acceptance rollback marker asks a reopened document to prove the
     * old tuple before cleanup. A missing provider deliberately fails closed.
    */
    internal val photoRecoveryMetadataIdentity: (suspend (DocumentAssociation) -> String?)? = null,
    /** Owner-bound background-work seam used across coordinator rebinds. */
    private val onStartWithOwner: ((DocumentSession, DocumentWorkOwner) -> Unit)? = null,
    private val cancelAndJoinWorkWithOwner:
        (suspend (DocumentSession, DocumentWorkOwner) -> Unit)? = null,
    private val resumeWorkWithOwner: ((DocumentSession, DocumentWorkOwner) -> Unit)? = null
) : DocumentSessionCallbacks {

    override suspend fun resolveTarget(sourceUri: String): TargetResolution {
        val uri = sourceUri.toUri()
        val sourceName = workerBoundary.withWorker { getFileName(context, uri) }
        val source = documentSourceIdentityForSnapshot(uri, sourceName)
        val fingerprint = fingerprintContentUri(context, uri)
        return when (val resolved = repository.resolveOrCreate(source, fingerprint)) {
            is ResolveDocumentResult.Resolved -> TargetResolution.Resolved(
                ResolvedDocumentTarget(resolved.association)
            )
            is ResolveDocumentResult.SourceChanged -> TargetResolution.Failed(
                SwitchFailure(
                    stage = SwitchFailureStage.RESOLVE_TARGET,
                    detail = "The selected URI now refers to changed PDF content",
                    repositoryError = LocalRepositoryError.SourceChanged(
                        documentId = resolved.documentId,
                        sourceUri = resolved.sourceUri,
                        storedFingerprint = resolved.previousFingerprint,
                        currentFingerprint = resolved.currentFingerprint
                    )
                )
            )
            is ResolveDocumentResult.FingerprintUnavailable -> TargetResolution.Failed(
                SwitchFailure(
                    stage = SwitchFailureStage.RESOLVE_TARGET,
                    detail = "The PDF source could not be fingerprinted"
                )
            )
            is ResolveDocumentResult.FingerprintNotBound -> TargetResolution.Failed(
                SwitchFailure(
                    stage = SwitchFailureStage.RESOLVE_TARGET,
                    detail = "The existing local snapshot is not bound to a verified source revision"
                )
            )
            is ResolveDocumentResult.Failed -> TargetResolution.Failed(
                SwitchFailure(
                    stage = SwitchFailureStage.RESOLVE_TARGET,
                    detail = "Document identity resolution failed",
                    repositoryError = resolved.error
                )
            )
        }
    }

    override fun captureSnapshot(session: DocumentSession) =
        com.example.myapplication.stage1.snapshotFromState(viewModel, session.target.association.source)

    override suspend fun captureDurableSnapshot(session: DocumentSession): com.example.myapplication.stage1.DocumentSnapshotV1? =
        when (val loaded = repository.load(session.target.association)) {
            is DocumentLoadResult.Loaded -> loaded.snapshot
            DocumentLoadResult.NotFound -> null
            is DocumentLoadResult.Failed -> throw IllegalStateException(
                "previous durable snapshot could not be read: ${loaded.error}"
            )
        }

    override suspend fun restoreDurableSnapshotState(
        session: DocumentSession,
        state: DocumentDurableSnapshotState
    ): DocumentSaveResult = repository.restoreDurableSnapshotState(
        session.target.association,
        state
    )

    /**
     * Re-resolve the source before every durable write. This prevents a URI
     * whose contents changed while it was open from accepting the old session's
     * snapshot. The supplied snapshot is never recaptured here.
     */
    override suspend fun saveSnapshot(
        session: DocumentSession,
        frozenSnapshot: com.example.myapplication.stage1.DocumentSnapshotV1
    ): DocumentSaveResult {
        val association = session.target.association
        val uri = association.source.sourceUri.toUri()
        val sourceName = workerBoundary.withWorker { getFileName(context, uri) }
        val source = documentSourceIdentityForSnapshot(uri, sourceName)
        val currentFingerprint = fingerprintContentUri(context, uri)
        return when (val resolved = repository.resolveOrCreate(source, currentFingerprint)) {
            is ResolveDocumentResult.Resolved -> {
                if (resolved.association.documentId != session.token.documentId ||
                    resolved.association.sourceFingerprint != session.token.sourceFingerprint
                ) {
                    DocumentSaveResult.Failed(
                        LocalRepositoryError.SourceChanged(
                            documentId = session.token.documentId,
                            sourceUri = session.token.sourceUri,
                            storedFingerprint = session.token.sourceFingerprint,
                            currentFingerprint = resolved.association.sourceFingerprint
                                ?: return DocumentSaveResult.Failed(
                                    LocalRepositoryError.IoFailure(
                                        operation = "save snapshot",
                                        path = null,
                                        detail = "current source fingerprint is unavailable"
                                    )
                                )
                        )
                    )
                } else {
                    repository.save(resolved.association, frozenSnapshot)
                }
            }
            is ResolveDocumentResult.SourceChanged -> DocumentSaveResult.Failed(
                LocalRepositoryError.SourceChanged(
                    documentId = resolved.documentId,
                    sourceUri = resolved.sourceUri,
                    storedFingerprint = resolved.previousFingerprint,
                    currentFingerprint = resolved.currentFingerprint
                )
            )
            is ResolveDocumentResult.FingerprintUnavailable -> DocumentSaveResult.Failed(
                LocalRepositoryError.IoFailure(
                    operation = "save snapshot",
                    path = null,
                    detail = "source fingerprint unavailable"
                )
            )
            is ResolveDocumentResult.FingerprintNotBound -> DocumentSaveResult.Failed(
                LocalRepositoryError.IoFailure(
                    operation = "save snapshot",
                    path = null,
                    detail = "source fingerprint is not bound"
                )
            )
            is ResolveDocumentResult.Failed -> DocumentSaveResult.Failed(resolved.error)
        }
    }

    override suspend fun cancelAndJoinDocumentWork(session: DocumentSession) =
        cancelAndJoinWork(session)

    override suspend fun cancelAndJoinDocumentWork(
        session: DocumentSession,
        owner: DocumentWorkOwner
    ) = cancelAndJoinWorkWithOwner?.invoke(session, owner) ?: cancelAndJoinWork(session)

    override suspend fun closeDocumentWork() = closeDocumentWorkAction()

    override fun invalidateDocumentWork(session: DocumentSession) = Unit

    override fun clearDocumentState() {
        viewModel.clearSession()
        onStateCleared()
    }

    override fun establishSession(session: DocumentSession) {
        onSessionEstablished(session)
    }

    override suspend fun loadTarget(session: DocumentSession): SessionLoadResult {
        val association = session.target.association
        val pageCount = try {
            workerBoundary.withWorker {
                if (loadPageCountForSource != null) {
                    loadPageCountForSource.invoke(association.source.sourceUri)
                } else {
                    loadPageCount(association.source.sourceUri.toUri())
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            return SessionLoadResult.Failed(
                DocumentLoadFailure(
                    detail = "PDF could not be opened",
                    cause = error
                )
            )
        } catch (error: SecurityException) {
            return SessionLoadResult.Failed(
                DocumentLoadFailure(
                    detail = "PDF could not be opened",
                    cause = error
                )
            )
        } catch (error: IllegalArgumentException) {
            return SessionLoadResult.Failed(
                DocumentLoadFailure(
                    detail = "PDF could not be opened",
                    cause = error
                )
            )
        } catch (error: IllegalStateException) {
            return SessionLoadResult.Failed(
                DocumentLoadFailure(
                    detail = "PDF could not be opened",
                    cause = error
                )
            )
        }

        val migration = try {
            repository.migrateLegacy(association, legacySource)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            return SessionLoadResult.Failed(
                DocumentLoadFailure("Legacy migration failed", cause = error)
            )
        } catch (error: SecurityException) {
            return SessionLoadResult.Failed(
                DocumentLoadFailure("Legacy migration failed", cause = error)
            )
        } catch (error: IllegalArgumentException) {
            return SessionLoadResult.Failed(
                DocumentLoadFailure("Legacy migration failed", cause = error)
            )
        } catch (error: IllegalStateException) {
            return SessionLoadResult.Failed(
                DocumentLoadFailure("Legacy migration failed", cause = error)
            )
        }

        return when (migration) {
            is LegacyMigrationResult.Migrated -> gatePhotoRecoveryBeforeReady(
                association,
                SessionLoadResult.Loaded(
                    snapshot = migration.snapshot,
                    pageCount = pageCount,
                    recoveredFromPrevious = false
                )
            )
            is LegacyMigrationResult.Failed -> SessionLoadResult.Failed(
                DocumentLoadFailure(
                    detail = "Legacy local data could not be migrated safely",
                    repositoryError = migration.error
                )
            )
            is LegacyMigrationResult.AmbiguousLegacyArtifact -> SessionLoadResult.Failed(
                DocumentLoadFailure(
                    detail = "Legacy local data is ambiguous for this source",
                    repositoryError = LocalRepositoryError.LegacyMigrationFailure(
                        "artifact ${migration.artifactName} is already claimed by ${migration.existingDocumentId}"
                    )
                )
            )
            else -> when (val loaded = repository.load(association)) {
                is DocumentLoadResult.Loaded -> gatePhotoRecoveryBeforeReady(
                    association,
                    SessionLoadResult.Loaded(
                        snapshot = loaded.snapshot,
                        pageCount = pageCount,
                        recoveredFromPrevious = loaded.recoveredFromPrevious
                    )
                )
                DocumentLoadResult.NotFound -> gatePhotoRecoveryBeforeReady(
                    association,
                    SessionLoadResult.Empty(pageCount)
                )
                is DocumentLoadResult.Failed -> SessionLoadResult.Failed(
                    DocumentLoadFailure(
                        detail = "Local annotations could not be loaded safely",
                        repositoryError = loaded.error
                    )
                )
            }
        }
    }

    /**
     * A reopened document is not ready until any cross-store photo intent has
     * been reconciled against the exact durable/live authorities that were
     * recorded before the operation. An unequal prior live authority is
     * rehydrated from the journal-bound photo recovery sidecar; substituting
     * the durable snapshot for it would incorrectly reject a valid rollback.
     * An empty offline target must also fail closed if recovery evidence exists.
     */
    private suspend fun gatePhotoRecoveryBeforeReady(
        association: com.example.myapplication.stage2.DocumentAssociation,
        result: SessionLoadResult
    ): SessionLoadResult {
        val snapshot = (result as? SessionLoadResult.Loaded)?.snapshot
        var gatedResult = result
        val failure = try {
            withContext(Dispatchers.IO) {
                photoAssetStoreFactory(context, association.documentId).use { store ->
                    if (snapshot == null) {
                        store.resolver.requireCanonicalRecoveryResolved()
                    } else {
                        val recoveredLiveSnapshot =
                            store.rehydratePreviousLiveCanonicalSnapshot(
                                snapshot
                            )
                        val liveSnapshot = recoveredLiveSnapshot ?: snapshot
                        if (liveSnapshot.source.sourceUri != association.source.sourceUri) {
                            throw PhotoCanonicalRecoveryException(
                                "rehydrated live snapshot source does not match the document session"
                            )
                        }
                        store.reconcilePhotoContent(
                            snapshot,
                            liveSnapshot,
                            photoRecoveryMetadataIdentity?.invoke(association)
                        )
                        if (recoveredLiveSnapshot != null) {
                            gatedResult = (result as SessionLoadResult.Loaded).copy(
                                snapshot = recoveredLiveSnapshot,
                                recoveredFromPrevious = true
                            )
                        }
                    }
                }
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PhotoCanonicalRecoveryException) {
            DocumentLoadFailure(
                detail = "Photo canonical recovery could not be completed before the document became ready",
                cause = error
            )
        } catch (error: Stage5ValidationException) {
            DocumentLoadFailure(
                detail = "Photo recovery evidence is invalid; the document remains unopened",
                cause = error
            )
        } catch (error: IOException) {
            DocumentLoadFailure(
                detail = "Photo canonical recovery could not be read safely",
                cause = error
            )
        } catch (error: SecurityException) {
            DocumentLoadFailure(
                detail = "Photo canonical recovery could not be read safely",
                cause = error
            )
        } catch (error: IllegalArgumentException) {
            DocumentLoadFailure(
                detail = "Photo canonical recovery evidence is invalid",
                cause = error
            )
        } catch (error: IllegalStateException) {
            DocumentLoadFailure(
                detail = "Photo canonical recovery could not be completed safely",
                cause = error
            )
        }
        return failure?.let(SessionLoadResult::Failed) ?: gatedResult
    }

    override fun applyLoadedSnapshot(
        session: DocumentSession,
        snapshot: com.example.myapplication.stage1.DocumentSnapshotV1
    ) {
        require(snapshot.source.sourceUri == session.token.sourceUri) {
            "Loaded snapshot source does not match active session"
        }
        applySnapshotReplace(snapshot, viewModel)
    }

    override fun onTargetMetadata(session: DocumentSession, pageCount: Int?) {
        if (pageCount != null) onPageCount(session, pageCount)
    }

    override fun onRecoveredSnapshot(session: DocumentSession) = onRecovered(session)

    override fun onSwitchFailure(failure: SwitchFailure) = onFailure(failure)

    override fun onAutosaveFailure(
        session: DocumentSession,
        result: DocumentSaveResult.Failed
    ) = onFailure(
        SwitchFailure(
            stage = SwitchFailureStage.OUTGOING_FLUSH,
            detail = "Automatic local save failed; the current document remains open",
            repositoryError = result.error
        )
    )

    override fun startDocumentBackgroundWork(session: DocumentSession) = onStart(session)

    override fun startDocumentBackgroundWork(
        session: DocumentSession,
        owner: DocumentWorkOwner
    ) = onStartWithOwner?.invoke(session, owner) ?: onStart(session)

    override fun resumeDocumentBackgroundWork(session: DocumentSession) = resumeWork(session)

    override fun resumeDocumentBackgroundWork(
        session: DocumentSession,
        owner: DocumentWorkOwner
    ) = resumeWorkWithOwner?.invoke(session, owner) ?: resumeWork(session)

    companion object {
        fun withDefaultPageLoader(
            context: Context,
            viewModel: BlueprintViewModel,
            repository: LocalDocumentRepository,
            legacySource: AndroidLegacyPersistenceSource,
            onSessionEstablished: (DocumentSession) -> Unit,
            onStateCleared: () -> Unit,
            onPageCount: (DocumentSession, Int) -> Unit,
            onRecovered: (DocumentSession) -> Unit,
            onFailure: (SwitchFailure) -> Unit,
            onStart: (DocumentSession) -> Unit,
            cancelAndJoinWork: suspend (DocumentSession) -> Unit,
            resumeWork: (DocumentSession) -> Unit,
            photoRecoveryMetadataIdentity: (suspend (DocumentAssociation) -> String?)? = null,
            closeDocumentWork: suspend () -> Unit = {},
            workerBoundary: Stage7WorkerResourceBoundary = Stage7WorkerResourceBoundary(),
            onStartWithOwner: ((DocumentSession, DocumentWorkOwner) -> Unit)? = null,
            cancelAndJoinWorkWithOwner:
                (suspend (DocumentSession, DocumentWorkOwner) -> Unit)? = null,
            resumeWorkWithOwner: ((DocumentSession, DocumentWorkOwner) -> Unit)? = null
        ): AndroidDocumentSessionCallbacks = AndroidDocumentSessionCallbacks(
            context = context,
            viewModel = viewModel,
            repository = repository,
            legacySource = legacySource,
            onSessionEstablished = onSessionEstablished,
            onStateCleared = onStateCleared,
            onPageCount = onPageCount,
            onRecovered = onRecovered,
            onFailure = onFailure,
            onStart = onStart,
            cancelAndJoinWork = cancelAndJoinWork,
            closeDocumentWorkAction = closeDocumentWork,
            resumeWork = resumeWork,
            loadPageCount = { uri ->
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: error("PDF file descriptor unavailable")
                pfd.use { descriptor -> PdfRenderer(descriptor).use { renderer -> renderer.pageCount } }
            },
            workerBoundary = workerBoundary,
            photoRecoveryMetadataIdentity = photoRecoveryMetadataIdentity,
            onStartWithOwner = onStartWithOwner,
            cancelAndJoinWorkWithOwner = cancelAndJoinWorkWithOwner,
            resumeWorkWithOwner = resumeWorkWithOwner
        )
    }
}
