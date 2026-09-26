package com.example.myapplication.stage3

import com.example.myapplication.stage9b.RecentDocumentReadResult
import com.example.myapplication.stage9b.RecentDocumentRecord
import com.example.myapplication.stage9b.RecentDocumentWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Project identity and source metadata needed when a drawing is opened from the project browser. */
internal data class DocumentSelectionProject(
    val projectId: String,
    val uri: String,
    val folder: String,
    val displayName: String
)

internal data class DocumentSelectionRequest(
    val sourceUri: String,
    val isContentUri: Boolean,
    val project: DocumentSelectionProject? = null
)

internal enum class DocumentSelectionNotice {
    PERSISTABLE_GRANT_UNAVAILABLE,
    PROJECT_ASSOCIATION_UNVERIFIED,
    DOCUMENT_OPEN_FAILED,
    RECENT_ENTRY_FAILED,
    PROJECT_RECENT_ENTRY_FAILED
}

/** A source inventory that cannot be read must never be interpreted as empty. */
internal sealed interface DocumentSelectionSourceInventory {
    data class Known(val sourceUris: List<String>) : DocumentSelectionSourceInventory
    data object Uncertain : DocumentSelectionSourceInventory
}

internal interface DocumentSelectionGrant {
    fun markAccepted()

    /** The predicate receives the exact URI held by the platform grant. */
    fun releaseIfUnused(isStillNeeded: (String) -> Boolean)
}

/** Android and durable-store operations used by the selection transaction. */
internal interface DocumentSelectionWorkflowPorts {
    fun takePersistableReadGrant(sourceUri: String): DocumentSelectionGrant
    suspend fun resolveProjectSource(sourceUri: String): String
    suspend fun switchTo(sourceUri: String): SwitchResult
    fun isCurrent(token: DocumentSessionToken): Boolean
    fun isCurrentApplied(token: DocumentSessionToken): Boolean
    fun isReady(token: DocumentSessionToken): Boolean
    fun currentSessionSourceUri(): String?
    suspend fun writeRecent(record: RecentDocumentRecord): RecentDocumentWriteResult
    suspend fun readRecent(): RecentDocumentReadResult
    suspend fun recordProjectOpen(project: DocumentSelectionProject, record: RecentDocumentRecord)
    suspend fun bindDocumentToProject(documentId: String, projectId: String)
    suspend fun readManifestSourceInventory(): DocumentSelectionSourceInventory
    suspend fun readRecentSourceInventory(): DocumentSelectionSourceInventory
}

/** Compose remains the UI adapter; this observer reports state changes and user-facing notices. */
internal interface DocumentSelectionWorkflowObserver {
    fun restoreBrowser(session: DocumentSession)
    fun recentFilesLoaded(records: List<RecentDocumentRecord>)
    fun recentFilesUnavailable()
    fun projectToolScopeChanged()
    fun showNotice(notice: DocumentSelectionNotice)
    fun persistableGrantUnavailable()
    fun unexpectedFailure(error: Exception)
}

internal enum class DocumentSelectionCompletion {
    OPENED,
    SWITCH_NOT_APPLIED,
    PROJECT_ASSOCIATION_REJECTED,
    FAILED
}

internal data class DocumentSelectionOutcome(
    val completion: DocumentSelectionCompletion,
    val switchResult: SwitchResult? = null,
    val openedSession: DocumentSession? = null,
    val recentRecord: RecentDocumentRecord? = null,
    val restoredAlreadyActiveBrowser: Boolean = false,
    val grantAccepted: Boolean = false
)

/**
 * Owns the end-to-end consequences of selecting a PDF: exact-source admission,
 * transactional switching, ready-session navigation, recent records, project
 * binding, and fail-closed cleanup of a grant that was not accepted.
 */
internal class DocumentSelectionWorkflow(
    private val ports: DocumentSelectionWorkflowPorts,
    private val observer: DocumentSelectionWorkflowObserver,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun open(request: DocumentSelectionRequest): DocumentSelectionOutcome {
        var grant: DocumentSelectionGrant? = null
        var grantAccepted = false
        var completion = DocumentSelectionCompletion.FAILED
        var switchResult: SwitchResult? = null
        var openedSession: DocumentSession? = null
        var recentRecord: RecentDocumentRecord? = null
        var restoredBrowser = false

        try {
            if (request.project == null && request.isContentUri) {
                grant = try {
                    ports.takePersistableReadGrant(request.sourceUri)
                } catch (_: SecurityException) {
                    // A transient read grant can still be enough for this session.
                    observer.persistableGrantUnavailable()
                    null
                }
            }

            val sourceUri = if (request.project != null) {
                try {
                    ports.resolveProjectSource(request.sourceUri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    observer.showNotice(DocumentSelectionNotice.PROJECT_ASSOCIATION_UNVERIFIED)
                    completion = DocumentSelectionCompletion.PROJECT_ASSOCIATION_REJECTED
                    return DocumentSelectionOutcome(completion)
                }
            } else {
                request.sourceUri
            }

            val result = ports.switchTo(sourceUri)
            switchResult = result
            openedSession = when (result) {
                is SwitchResult.Switched -> result.session
                is SwitchResult.AlreadyActive -> result.session
                else -> null
            }

            // Keep the already-ready path ahead of recents and project writes so
            // returning to a document's browser does not wait on storage.
            restoredBrowser = restoreAlreadyActiveSession(
                result = result,
                isCurrent = ports::isCurrent,
                isReady = ports::isReady,
                restoreBrowser = observer::restoreBrowser
            )

            if (openedSession != null && ports.isCurrentApplied(openedSession!!.token)) {
                grant?.markAccepted()
                grantAccepted = true

                val session = openedSession!!
                recentRecord = RecentDocumentRecord.fromAssociation(
                    association = session.target.association,
                    lastSuccessfullyOpenedAtEpochMillis = nowMillis()
                ).let { record ->
                    request.project?.let { record.copy(displayName = it.displayName) } ?: record
                }

                when (val written = ports.writeRecent(recentRecord!!)) {
                    is RecentDocumentWriteResult.Committed -> when (val loaded = ports.readRecent()) {
                        is RecentDocumentReadResult.Loaded -> observer.recentFilesLoaded(loaded.records)
                        is RecentDocumentReadResult.Failed -> observer.recentFilesUnavailable()
                    }
                    is RecentDocumentWriteResult.Failed -> {
                        observer.recentFilesUnavailable()
                        observer.showNotice(DocumentSelectionNotice.RECENT_ENTRY_FAILED)
                    }
                }

                request.project?.let { project ->
                    try {
                        ports.recordProjectOpen(project, recentRecord!!)
                        ports.bindDocumentToProject(recentRecord!!.documentId.value, project.projectId)
                        observer.projectToolScopeChanged()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        observer.showNotice(DocumentSelectionNotice.PROJECT_RECENT_ENTRY_FAILED)
                    }
                }
                completion = DocumentSelectionCompletion.OPENED
            } else {
                completion = DocumentSelectionCompletion.SWITCH_NOT_APPLIED
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            observer.unexpectedFailure(error)
            observer.showNotice(DocumentSelectionNotice.DOCUMENT_OPEN_FAILED)
            completion = DocumentSelectionCompletion.FAILED
        } finally {
            if (grant != null && !grantAccepted) {
                withContext(NonCancellable) {
                    // If either durable inventory is unavailable, retain access.
                    val durableUse = try {
                        withContext(ioDispatcher) {
                            val manifest = ports.readManifestSourceInventory()
                            val recent = ports.readRecentSourceInventory()
                            manifest.uses(request.sourceUri) || recent.uses(request.sourceUri)
                        }
                    } catch (_: Exception) {
                        true
                    }
                    grant!!.releaseIfUnused { selectedUri ->
                        durableUse || ports.currentSessionSourceUri() == selectedUri
                    }
                }
            }
        }

        return DocumentSelectionOutcome(
            completion = completion,
            switchResult = switchResult,
            openedSession = openedSession,
            recentRecord = recentRecord,
            restoredAlreadyActiveBrowser = restoredBrowser,
            grantAccepted = grantAccepted
        )
    }

    private fun DocumentSelectionSourceInventory.uses(sourceUri: String): Boolean = when (this) {
        is DocumentSelectionSourceInventory.Known -> sourceUris.any { it == sourceUri }
        DocumentSelectionSourceInventory.Uncertain -> true
    }
}
