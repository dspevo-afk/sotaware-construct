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
import com.example.myapplication.stage2.DocumentLoadResult
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.LegacyMigrationResult
import com.example.myapplication.stage2.LocalDocumentRepository
import com.example.myapplication.stage2.LocalRepositoryError
import com.example.myapplication.stage2.ResolveDocumentResult
import com.example.myapplication.stage2.fingerprintContentUri
import com.example.myapplication.stage2.migrateLegacy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private val resumeWork: (DocumentSession) -> Unit,
    private val loadPageCount: suspend (Uri) -> Int
) : DocumentSessionCallbacks {

    override suspend fun resolveTarget(sourceUri: String): TargetResolution {
        val uri = sourceUri.toUri()
        val source = documentSourceIdentityForSnapshot(uri, getFileName(context, uri))
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
        val source = documentSourceIdentityForSnapshot(uri, getFileName(context, uri))
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
            loadPageCount(association.source.sourceUri.toUri())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
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
        } catch (error: Throwable) {
            return SessionLoadResult.Failed(
                DocumentLoadFailure("Legacy migration failed", cause = error)
            )
        }

        return when (migration) {
            is LegacyMigrationResult.Migrated -> SessionLoadResult.Loaded(
                snapshot = migration.snapshot,
                pageCount = pageCount,
                recoveredFromPrevious = false
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
                is DocumentLoadResult.Loaded -> SessionLoadResult.Loaded(
                    snapshot = loaded.snapshot,
                    pageCount = pageCount,
                    recoveredFromPrevious = loaded.recoveredFromPrevious
                )
                DocumentLoadResult.NotFound -> SessionLoadResult.Empty(pageCount)
                is DocumentLoadResult.Failed -> SessionLoadResult.Failed(
                    DocumentLoadFailure(
                        detail = "Local annotations could not be loaded safely",
                        repositoryError = loaded.error
                    )
                )
            }
        }
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

    override fun resumeDocumentBackgroundWork(session: DocumentSession) = resumeWork(session)

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
            resumeWork: (DocumentSession) -> Unit
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
            resumeWork = resumeWork,
            loadPageCount = { uri ->
                withContext(Dispatchers.IO) {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: error("PDF file descriptor unavailable")
                    pfd.use { descriptor -> PdfRenderer(descriptor).use { renderer -> renderer.pageCount } }
                }
            }
        )
    }
}
