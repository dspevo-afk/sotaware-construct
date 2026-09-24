package com.example.myapplication.stage6

import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentDurableSnapshotState
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentLoadResult
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage3.DocumentSession
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentTransactionBarrier
import com.example.myapplication.stage3.SessionSnapshotApplyResult
import com.example.myapplication.stage4.PhotoContentTransaction
import com.example.myapplication.stage9b.PhotoAssetSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/** One exported photo capture whose files remain leased until the bundle write completes. */
class BundlePhotoCapture internal constructor(
    val assets: PhotoAssetSet,
    private val release: () -> Unit
) : AutoCloseable {
    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        release()
    }
}

/** Document-scoped photo storage operations used by the bundle transaction. */
interface DocumentBundlePhotoStore : AutoCloseable {
    fun capturePhotoAssetsForAdmission(
        durableSnapshot: DocumentSnapshotV1,
        liveSnapshot: DocumentSnapshotV1
    ): BundlePhotoCapture

    fun reconcilePhotoContent(
        durableSnapshot: DocumentSnapshotV1,
        liveSnapshot: DocumentSnapshotV1
    )

    fun stageImport(photoFiles: PhotoAssetSet): PhotoContentTransaction?
}

/**
 * The narrow application adapter for Stage 6. Session and coordinator access
 * stays with the Android host; this workflow owns ordering, barriers, source
 * verification, bundle IO, transaction application, and resource release.
 * Methods that touch Compose/coordinator state must perform their Main.immediate
 * hop in the host implementation. Repository and provider operations must use IO.
 */
interface DocumentBundleWorkflowHost {
    fun hasOpenPdf(): Boolean
    fun currentSession(): DocumentSession?
    fun activeSessionToken(): DocumentSessionToken?
    fun readySessionToken(): DocumentSessionToken?
    fun isCurrentApplied(token: DocumentSessionToken): Boolean
    suspend fun awaitReadySession(): DocumentSession

    suspend fun sourceIdentity(sourceUri: String): DocumentSourceIdentityV1
    suspend fun currentSourceFingerprint(sourceUri: String): SourceFingerprint?
    suspend fun captureCurrentSnapshot(token: DocumentSessionToken): DocumentSnapshotV1?
    suspend fun loadDurableSnapshot(association: DocumentAssociation): DocumentLoadResult
    suspend fun captureDurableSnapshotState(association: DocumentAssociation): DocumentDurableSnapshotState
    suspend fun persistAndApplyCurrentSnapshot(
        token: DocumentSessionToken,
        snapshot: DocumentSnapshotV1
    ): SessionSnapshotApplyResult
    suspend fun restoreCurrentSnapshot(
        token: DocumentSessionToken,
        durableSnapshot: DocumentSnapshotV1,
        liveSnapshot: DocumentSnapshotV1
    ): SessionSnapshotApplyResult
    suspend fun restoreCurrentSnapshot(
        token: DocumentSessionToken,
        durableState: DocumentDurableSnapshotState,
        liveSnapshot: DocumentSnapshotV1
    ): SessionSnapshotApplyResult

    fun openPhotoStore(documentId: DocumentId): DocumentBundlePhotoStore
    suspend fun cleanupAfterCanonicalCommit(
        session: DocumentSession,
        acceptedSnapshot: DocumentSnapshotV1
    )

    fun openBundleInput(uri: String): InputStream?
    fun openBundleOutput(uri: String): OutputStream?
}

sealed interface DocumentBundleImportWorkflowOutcome {
    data object OpenPdfRequired : DocumentBundleImportWorkflowOutcome
    data object Imported : DocumentBundleImportWorkflowOutcome
}

class DocumentBundleWorkflow(
    private val service: DocumentBundleService,
    private val transactionBarrier: DocumentTransactionBarrier,
    private val host: DocumentBundleWorkflowHost
) {
    suspend fun export(token: DocumentSessionToken, destinationUri: String) {
        var photoCapture: BundlePhotoCapture? = null
        try {
            val exportInput = transactionBarrier.withDocument(token.documentId) {
                val session = requireCurrentExportSession(token)
                val fingerprintBeforeCapture = withContext(Dispatchers.IO) {
                    host.currentSourceFingerprint(token.sourceUri)
                }
                require(token.sourceFingerprint == fingerprintBeforeCapture) {
                    "the active PDF source revision changed before export"
                }

                val snapshot = host.captureCurrentSnapshot(token)
                    ?: error("current canonical snapshot became unavailable during export")
                val verifiedFingerprint = withContext(Dispatchers.IO) {
                    host.currentSourceFingerprint(token.sourceUri)
                }
                val sourceFingerprint = verifyBundleExportSourceFingerprint(
                    sessionSourceUri = token.sourceUri,
                    sessionSourceFingerprint = token.sourceFingerprint,
                    snapshot = snapshot,
                    currentSourceFingerprint = verifiedFingerprint
                )
                val photoFiles = withContext(Dispatchers.IO) {
                    val durable = when (val loaded = host.loadDurableSnapshot(session.target.association)) {
                        is DocumentLoadResult.Loaded -> loaded.snapshot
                        DocumentLoadResult.NotFound -> snapshot
                        is DocumentLoadResult.Failed -> throw DocumentBundleException(
                            "durable state unavailable during export"
                        )
                    }
                    host.openPhotoStore(token.documentId).use { store ->
                        store.capturePhotoAssetsForAdmission(durable, snapshot)
                            .also { photoCapture = it }
                            .assets
                    }
                }
                BundleExportInput(
                    exportedDocumentId = token.documentId,
                    source = snapshot.source,
                    sourceFingerprint = sourceFingerprint,
                    snapshot = snapshot,
                    photoFiles = photoFiles
                )
            }

            withContext(Dispatchers.IO) {
                service.writeBundleAndCloseCancellable(
                    openOutput = { host.openBundleOutput(destinationUri) },
                    input = exportInput
                )
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                photoCapture?.close()
            }
        }
    }

    suspend fun import(
        targetPdfSourceUri: String,
        bundleUri: String
    ): DocumentBundleImportWorkflowOutcome {
        if (host.currentSession() == null && !host.hasOpenPdf()) {
            return DocumentBundleImportWorkflowOutcome.OpenPdfRequired
        }

        val session = host.awaitReadySession()
        requireCurrentImportSession(session)
        val selectedSource = withContext(Dispatchers.IO) {
            host.sourceIdentity(targetPdfSourceUri)
        }
        val fingerprint = withContext(Dispatchers.IO) {
            requireNotNull(host.currentSourceFingerprint(targetPdfSourceUri)) {
                "the current PDF source could not be fingerprinted"
            }
        }
        require(session.token.sourceUri == selectedSource.sourceUri) {
            "the save file targets a different PDF than the active session"
        }
        require(session.token.sourceFingerprint == fingerprint) {
            "the active PDF source revision no longer matches this import"
        }
        val association = session.target.association
        require(association.documentId == session.token.documentId) {
            "the save file resolved to a different document identity"
        }
        require(association.source.sourceUri == selectedSource.sourceUri) {
            "the save file targets a different source identity"
        }
        require(association.sourceFingerprint == session.token.sourceFingerprint) {
            "the document association source revision changed during import"
        }

        var decodedBundle: DecodedDocumentBundle? = null
        try {
            val decoded = withContext(Dispatchers.IO) {
                service.readBundleFromCancellable { host.openBundleInput(bundleUri) }
                    .also { decodedBundle = it }
            }
            val rebound = withContext(Dispatchers.IO) {
                service.rebindToVerifiedTarget(
                    decoded,
                    VerifiedBundleTarget(
                        documentId = session.token.documentId,
                        source = association.source,
                        sourceFingerprint = fingerprint
                    )
                )
            }
            val applied = withVerifiedStage6ImportDocument(
                transactionBarrier = transactionBarrier,
                documentId = session.token.documentId,
                sessionSourceUri = session.token.sourceUri,
                associationDocumentId = association.documentId,
                associationSourceUri = association.source.sourceUri,
                targetSourceUri = rebound.snapshot.source.sourceUri,
                sessionSourceFingerprint = session.token.sourceFingerprint,
                associationSourceFingerprint = association.sourceFingerprint,
                targetSourceFingerprint = rebound.target.sourceFingerprint,
                currentSourceFingerprint = {
                    host.currentSourceFingerprint(targetPdfSourceUri)
                }
            ) {
                val importHost = createImportHost(session)
                host.openPhotoStore(session.token.documentId).use { store ->
                    val currentLive = importHost.captureCurrentLiveSnapshot()
                    val currentDurable = importHost.captureCurrentDurableSnapshot() ?: currentLive
                    store.reconcilePhotoContent(currentDurable, currentLive)
                    val photoTransaction = store.stageImport(rebound.photoFiles)
                    val result = service.applyReboundBundleWithinDocumentTransaction(
                        bundle = rebound,
                        host = importHost,
                        photoTransaction = photoTransaction
                    )
                    if (result is BundleImportResult.Applied) {
                        host.cleanupAfterCanonicalCommit(session, rebound.snapshot)
                    }
                    result
                }
            }
            return when (applied) {
                BundleImportResult.Applied -> DocumentBundleImportWorkflowOutcome.Imported
                BundleImportResult.Stale -> error("the active document changed during bundle import")
                is BundleImportResult.Failed -> throw applied.cause
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                decodedBundle?.close()
            }
        }
    }

    private fun requireCurrentExportSession(token: DocumentSessionToken): DocumentSession {
        val session = host.currentSession()
        require(
            session?.token == token &&
                host.activeSessionToken() == token &&
                host.readySessionToken() == token &&
                host.isCurrentApplied(token)
        ) {
            "the active document session changed before export"
        }
        return requireNotNull(session)
    }

    private fun requireCurrentImportSession(session: DocumentSession) {
        require(
            host.activeSessionToken() == session.token &&
                host.readySessionToken() == session.token &&
                host.isCurrentApplied(session.token)
        ) {
            "the active document session is not ready for import"
        }
    }

    private fun createImportHost(session: DocumentSession): DocumentBundleImportHost =
        object : DocumentBundleImportHost {
            override val documentId: DocumentId = session.token.documentId

            override suspend fun captureCurrentLiveSnapshot(): DocumentSnapshotV1 =
                host.captureCurrentSnapshot(session.token)
                    ?: error("current canonical snapshot became unavailable during bundle import")

            override suspend fun captureCurrentDurableSnapshot(): DocumentSnapshotV1? =
                when (val loaded = host.loadDurableSnapshot(session.target.association)) {
                    is DocumentLoadResult.Loaded -> loaded.snapshot
                    DocumentLoadResult.NotFound -> null
                    is DocumentLoadResult.Failed -> throw DocumentBundleException(
                        "current durable snapshot could not be read during bundle import",
                        IllegalStateException(loaded.error.toString())
                    )
                }

            override suspend fun captureCurrentDurableState(): DocumentDurableSnapshotState =
                host.captureDurableSnapshotState(session.target.association)

            override suspend fun persistAndApply(snapshot: DocumentSnapshotV1): SessionSnapshotApplyResult =
                host.persistAndApplyCurrentSnapshot(session.token, snapshot)

            override suspend fun restore(
                durableSnapshot: DocumentSnapshotV1,
                liveSnapshot: DocumentSnapshotV1
            ): SessionSnapshotApplyResult = host.restoreCurrentSnapshot(
                session.token,
                durableSnapshot,
                liveSnapshot
            )

            override suspend fun restore(
                durableState: DocumentDurableSnapshotState,
                liveSnapshot: DocumentSnapshotV1
            ): SessionSnapshotApplyResult = host.restoreCurrentSnapshot(
                session.token,
                durableState,
                liveSnapshot
            )
        }
}

/** Revalidates the selected PDF revision while the document barrier is held. */
fun verifyBundleImportSourceFingerprint(
    sessionSourceFingerprint: SourceFingerprint?,
    associationSourceFingerprint: SourceFingerprint?,
    targetSourceFingerprint: SourceFingerprint,
    currentSourceFingerprint: SourceFingerprint?
): SourceFingerprint {
    val verified = currentSourceFingerprint
        ?: throw DocumentBundleException(
            "the active PDF source could not be fingerprinted during bundle import"
        )
    if (sessionSourceFingerprint != verified) {
        throw DocumentBundleException(
            "the active PDF source revision changed during bundle import"
        )
    }
    if (associationSourceFingerprint != verified) {
        throw DocumentBundleException(
            "the document association source revision changed during import"
        )
    }
    if (targetSourceFingerprint != verified) {
        throw DocumentBundleException(
            "the verified import target source revision changed during import"
        )
    }
    return verified
}

/**
 * Import admission is performed under the shared document barrier. No photo
 * staging or canonical publication may begin until identity and the fresh
 * source fingerprint have all been reverified.
 */
internal suspend fun <T> withVerifiedStage6ImportDocument(
    transactionBarrier: DocumentTransactionBarrier,
    documentId: DocumentId,
    sessionSourceUri: String,
    associationDocumentId: DocumentId,
    associationSourceUri: String,
    targetSourceUri: String,
    sessionSourceFingerprint: SourceFingerprint?,
    associationSourceFingerprint: SourceFingerprint?,
    targetSourceFingerprint: SourceFingerprint,
    currentSourceFingerprint: suspend () -> SourceFingerprint?,
    block: suspend () -> T
): T {
    require(associationDocumentId == documentId) {
        "the save file resolved to a different document identity"
    }
    require(sessionSourceUri == associationSourceUri) {
        "the document association source identity changed during import"
    }
    require(associationSourceUri == targetSourceUri) {
        "the save file targets a different source identity"
    }
    return withContext(Dispatchers.IO) {
        transactionBarrier.withDocument(documentId) {
            val barrierSourceFingerprint = withContext(Dispatchers.IO) {
                currentSourceFingerprint()
            }
            verifyBundleImportSourceFingerprint(
                sessionSourceFingerprint = sessionSourceFingerprint,
                associationSourceFingerprint = associationSourceFingerprint,
                targetSourceFingerprint = targetSourceFingerprint,
                currentSourceFingerprint = barrierSourceFingerprint
            )
            block()
        }
    }
}
