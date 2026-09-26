package com.example.myapplication.stage3

import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage9b.RecentDocumentReadResult
import com.example.myapplication.stage9b.RecentDocumentRecord
import com.example.myapplication.stage9b.RecentDocumentWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SELECTED_SOURCE = "content://selection.test/document/plan"

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentSelectionWorkflowTest {
    @Test
    fun failedSwitch_releasesNewGrantWhenAllOwnershipInventoriesProveUnused() = runTest {
        val ports = SelectionPorts().apply {
            manifestInventory = DocumentSelectionSourceInventory.Known(emptyList())
            recentInventory = DocumentSelectionSourceInventory.Known(emptyList())
        }

        val outcome = workflow(ports).open(contentRequest())

        assertEquals(DocumentSelectionCompletion.SWITCH_NOT_APPLIED, outcome.completion)
        assertEquals(1, ports.grant.releaseCount)
        assertEquals(false, ports.grant.lastStillNeeded)
        assertFalse(ports.grant.accepted)
    }

    @Test
    fun failedSwitch_retainsGrantWhenManifestStillAssociatesSelectedSource() = runTest {
        val ports = SelectionPorts().apply {
            manifestInventory = DocumentSelectionSourceInventory.Known(listOf(SELECTED_SOURCE))
            recentInventory = DocumentSelectionSourceInventory.Known(emptyList())
        }

        workflow(ports).open(contentRequest())

        assertEquals(1, ports.grant.releaseCount)
        assertEquals(true, ports.grant.lastStillNeeded)
    }

    @Test
    fun supersededSwitch_retainsGrantWhenRecentInventoryUsesSelectedSource() = runTest {
        val ports = SelectionPorts().apply {
            switchResult = SwitchResult.Superseded(SELECTED_SOURCE)
            manifestInventory = DocumentSelectionSourceInventory.Known(emptyList())
            recentInventory = DocumentSelectionSourceInventory.Known(listOf(SELECTED_SOURCE))
        }

        val outcome = workflow(ports).open(contentRequest())

        assertEquals(DocumentSelectionCompletion.SWITCH_NOT_APPLIED, outcome.completion)
        assertEquals(true, ports.grant.lastStillNeeded)
    }

    @Test
    fun failedSwitch_retainsGrantWhenEitherOwnershipInventoryIsUncertain() = runTest {
        val uncertainCases = listOf(
            DocumentSelectionSourceInventory.Uncertain to
                DocumentSelectionSourceInventory.Known(emptyList()),
            DocumentSelectionSourceInventory.Known(emptyList()) to
                DocumentSelectionSourceInventory.Uncertain
        )

        uncertainCases.forEach { (manifest, recent) ->
            val ports = SelectionPorts().apply {
                manifestInventory = manifest
                recentInventory = recent
            }

            workflow(ports).open(contentRequest())

            assertEquals(1, ports.grant.releaseCount)
            assertEquals(true, ports.grant.lastStillNeeded)
        }
    }

    @Test
    fun currentSessionAlsoRetainsGrantEvenWhenInventoriesAreEmpty() = runTest {
        val session = selectionSession(SELECTED_SOURCE)
        val ports = SelectionPorts().apply {
            currentSession = session
            manifestInventory = DocumentSelectionSourceInventory.Known(emptyList())
            recentInventory = DocumentSelectionSourceInventory.Known(emptyList())
        }

        workflow(ports).open(contentRequest())

        assertEquals(true, ports.grant.lastStillNeeded)
    }

    @Test
    fun cancellationPropagatesAfterNonCancellableGrantCleanup() = runTest {
        val ports = SelectionPorts().apply {
            throwCancellationDuringSwitch = true
            manifestInventory = DocumentSelectionSourceInventory.Known(emptyList())
            recentInventory = DocumentSelectionSourceInventory.Known(emptyList())
        }
        var cancellationPropagated = false

        try {
            workflow(ports).open(contentRequest())
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue(cancellationPropagated)
        assertEquals(1, ports.grant.releaseCount)
        assertEquals(false, ports.grant.lastStillNeeded)
    }

    @Test
    fun alreadyActiveReadySessionRestoresBrowserBeforeRecentWriteCompletes() = runTest {
        val session = selectionSession(SELECTED_SOURCE)
        val gate = CompletableDeferred<Unit>()
        val ports = SelectionPorts().apply {
            currentSession = session
            currentApplied = true
            ready = true
            switchResult = SwitchResult.AlreadyActive(session)
            recentWriteGate = gate
        }
        val observer = SelectionObserver()
        val opening = async {
            workflow(ports, observer).open(contentRequest())
        }

        runCurrent()

        assertEquals(1, observer.restoredSessions.size)
        assertEquals(session, observer.restoredSessions.single())
        assertEquals(1, ports.recentWriteCount)
        assertFalse(opening.isCompleted)
        assertTrue(ports.grant.accepted)
        assertEquals(0, ports.grant.releaseCount)

        gate.complete(Unit)
        runCurrent()
        val outcome = opening.await()

        assertTrue(outcome.restoredAlreadyActiveBrowser)
        assertEquals(DocumentSelectionCompletion.OPENED, outcome.completion)
        assertNotNull(outcome.recentRecord)
        assertEquals(SELECTED_SOURCE, outcome.recentRecord?.sourceUri)
    }

    @Test
    fun acceptedSessionKeepsGrantWhenRecentWriteFails() = runTest {
        val session = selectionSession(SELECTED_SOURCE)
        val ports = SelectionPorts().apply {
            currentSession = session
            currentApplied = true
            switchResult = SwitchResult.Switched(session, loadedSnapshot = true, recoveredFromPrevious = false)
            recentWriteResult = RecentDocumentWriteResult.Failed(
                com.example.myapplication.stage9b.RecentDocumentStoreError.WriteFailed("unavailable")
            )
        }
        val observer = SelectionObserver()

        val outcome = workflow(ports, observer).open(contentRequest())

        assertEquals(DocumentSelectionCompletion.OPENED, outcome.completion)
        assertTrue(ports.grant.accepted)
        assertEquals(0, ports.grant.releaseCount)
        assertTrue(observer.recentUnavailable)
        assertEquals(listOf(DocumentSelectionNotice.RECENT_ENTRY_FAILED), observer.notices)
    }

    @Test
    fun projectSelectionUsesVerifiedAssociationAndBindsTheOpenedDocument() = runTest {
        val session = selectionSession(SELECTED_SOURCE)
        val project = DocumentSelectionProject(
            projectId = "synthetic-project",
            uri = SELECTED_SOURCE,
            folder = "drawings",
            displayName = "Project sheet.pdf"
        )
        val ports = SelectionPorts().apply {
            currentSession = session
            currentApplied = true
            switchResult = SwitchResult.Switched(session, loadedSnapshot = true, recoveredFromPrevious = false)
        }
        val observer = SelectionObserver()

        val outcome = workflow(ports, observer).open(
            DocumentSelectionRequest(SELECTED_SOURCE, isContentUri = true, project = project)
        )

        assertEquals(DocumentSelectionCompletion.OPENED, outcome.completion)
        assertEquals(0, ports.grantTakeCount)
        assertEquals(SELECTED_SOURCE, ports.verifiedProjectSource)
        assertEquals(SELECTED_SOURCE, ports.switchedSource)
        assertEquals("Project sheet.pdf", ports.writtenRecord?.displayName)
        assertEquals(project to ports.writtenRecord, ports.projectOpen)
        assertEquals(session.token.documentId.value to project.projectId, ports.projectBinding)
        assertTrue(observer.projectScopeChanged)
    }

    private fun TestScope.workflow(
        ports: SelectionPorts,
        observer: SelectionObserver = SelectionObserver()
    ): DocumentSelectionWorkflow = DocumentSelectionWorkflow(
        ports = ports,
        observer = observer,
        nowMillis = { 1234L },
        ioDispatcher = StandardTestDispatcher(testScheduler)
    )

    private fun contentRequest() = DocumentSelectionRequest(
        sourceUri = SELECTED_SOURCE,
        isContentUri = true
    )
}

private fun selectionSession(sourceUri: String): DocumentSession {
    val documentId = DocumentId.new()
    val fingerprint = SourceFingerprint.fromBytes("synthetic pdf".toByteArray())
    val association = DocumentAssociation(
        documentId = documentId,
        source = DocumentSourceIdentityV1(sourceUri, "plan.pdf"),
        sourceFingerprint = fingerprint
    )
    val target = ResolvedDocumentTarget(association)
    return DocumentSession(
        target = target,
        token = DocumentSessionToken(documentId, sourceUri, fingerprint, generation = 1L)
    )
}

private class SelectionPorts : DocumentSelectionWorkflowPorts {
    val grant = SelectionGrant(SELECTED_SOURCE)
    var grantTakeCount = 0
    var verifiedProjectSource: String? = null
    var switchedSource: String? = null
    var projectOpen: Pair<DocumentSelectionProject, RecentDocumentRecord>? = null
    var projectBinding: Pair<String, String>? = null
    var switchResult: SwitchResult = SwitchResult.Failed(
        SwitchFailure(SwitchFailureStage.TARGET_LOAD, "synthetic failed switch"),
        preservedSession = null
    )
    var currentSession: DocumentSession? = null
    var currentApplied = false
    var ready = false
    var manifestInventory: DocumentSelectionSourceInventory = DocumentSelectionSourceInventory.Known(emptyList())
    var recentInventory: DocumentSelectionSourceInventory = DocumentSelectionSourceInventory.Known(emptyList())
    var recentWriteResult: RecentDocumentWriteResult = RecentDocumentWriteResult.Committed
    var recentWriteGate: CompletableDeferred<Unit>? = null
    var recentWriteCount = 0
    var writtenRecord: RecentDocumentRecord? = null
    var throwCancellationDuringSwitch = false

    override fun takePersistableReadGrant(sourceUri: String): DocumentSelectionGrant {
        grantTakeCount++
        require(sourceUri == grant.sourceUri)
        return grant
    }

    override suspend fun resolveProjectSource(sourceUri: String): String {
        verifiedProjectSource = sourceUri
        return sourceUri
    }
    override suspend fun switchTo(sourceUri: String): SwitchResult {
        switchedSource = sourceUri
        if (throwCancellationDuringSwitch) throw CancellationException("synthetic cancellation")
        return switchResult
    }
    override fun isCurrent(token: DocumentSessionToken): Boolean = currentSession?.token == token
    override fun isCurrentApplied(token: DocumentSessionToken): Boolean =
        currentApplied && currentSession?.token == token
    override fun isReady(token: DocumentSessionToken): Boolean = ready && currentSession?.token == token
    override fun currentSessionSourceUri(): String? = currentSession?.token?.sourceUri

    override suspend fun writeRecent(record: RecentDocumentRecord): RecentDocumentWriteResult {
        recentWriteCount++
        writtenRecord = record
        recentWriteGate?.await()
        return recentWriteResult
    }

    override suspend fun readRecent(): RecentDocumentReadResult = RecentDocumentReadResult.Loaded(emptyList())
    override suspend fun recordProjectOpen(project: DocumentSelectionProject, record: RecentDocumentRecord) {
        projectOpen = project to record
    }
    override suspend fun bindDocumentToProject(documentId: String, projectId: String) {
        projectBinding = documentId to projectId
    }
    override suspend fun readManifestSourceInventory(): DocumentSelectionSourceInventory = manifestInventory
    override suspend fun readRecentSourceInventory(): DocumentSelectionSourceInventory = recentInventory

}

private class SelectionGrant(val sourceUri: String) : DocumentSelectionGrant {
    var accepted = false
    var releaseCount = 0
    var lastStillNeeded: Boolean? = null

    override fun markAccepted() {
        accepted = true
    }

    override fun releaseIfUnused(isStillNeeded: (String) -> Boolean) {
        releaseCount++
        lastStillNeeded = isStillNeeded(sourceUri)
    }
}

private class SelectionObserver : DocumentSelectionWorkflowObserver {
    val restoredSessions = mutableListOf<DocumentSession>()
    val notices = mutableListOf<DocumentSelectionNotice>()
    val recentRecords = mutableListOf<List<RecentDocumentRecord>>()
    var recentUnavailable = false
    var projectScopeChanged = false
    var unexpectedFailure: Exception? = null

    override fun restoreBrowser(session: DocumentSession) {
        restoredSessions += session
    }

    override fun recentFilesLoaded(records: List<RecentDocumentRecord>) {
        recentRecords += records
    }

    override fun recentFilesUnavailable() {
        recentUnavailable = true
    }

    override fun projectToolScopeChanged() {
        projectScopeChanged = true
    }

    override fun showNotice(notice: DocumentSelectionNotice) {
        notices += notice
    }

    override fun persistableGrantUnavailable() = Unit
    override fun unexpectedFailure(error: Exception) {
        unexpectedFailure = error
    }
}
