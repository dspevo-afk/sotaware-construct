package com.example.myapplication.stage10

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.LocalRepositoryError
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage3.DocumentSession
import com.example.myapplication.stage3.DocumentSessionCallbacks
import com.example.myapplication.stage3.DocumentSwitchCoordinator
import com.example.myapplication.stage3.DocumentLoadFailure
import com.example.myapplication.stage3.ResolvedDocumentTarget
import com.example.myapplication.stage3.SessionLoadResult
import com.example.myapplication.stage3.SwitchFailure
import com.example.myapplication.stage3.SwitchFailureStage
import com.example.myapplication.stage3.SwitchResult
import com.example.myapplication.stage3.TargetResolution
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android coroutine setup/teardown and rebind coverage for A07/A01. */
@RunWith(AndroidJUnit4::class)
class AuditSetupInstrumentedTest {
    private companion object {
        const val CONCURRENCY_TIMEOUT_MILLIS = 10_000L
    }

    private val packageName
        get() = InstrumentationRegistry.getInstrumentation().targetContext.packageName

    @Test
    fun suspendedInitialResolution_closeAndJoin_fencesCallbacksBeforeTeardown() = runBlocking {
        val uri = "$packageName://audit-setup/suspended"
        val host = SetupHost(uri, blockResolution = true)
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = coordinator(host, parent)

        try {
            withTimeout(CONCURRENCY_TIMEOUT_MILLIS) {
                val switching = parent.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    coordinator.switchTo(uri)
                }
                host.resolveStarted.await()
                val closing = parent.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    coordinator.closeAndJoin()
                }
                assertFalse("close must account for the admitted suspended setup", closing.isCompleted)

                host.resolveGate.complete(Unit)
                val result = switching.await()
                closing.await()

                assertTrue(result is SwitchResult.Failed)
                assertEquals(0, host.clearCalls)
                assertEquals(0, host.establishCalls)
                assertEquals(0, host.applyCalls)
            }
        } finally {
            withContext(NonCancellable) {
                host.resolveGate.complete(Unit)
                coordinator.closeAndJoin()
            }
            parent.cancel()
        }
    }

    @Test
    fun suspendedSetup_closeThenRebind_allowsFreshCoordinatorAdmission() = runBlocking {
        val uri = "$packageName://audit-setup/rebind"
        val oldHost = SetupHost(uri, blockResolution = true)
        val oldParent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val oldCoordinator = coordinator(oldHost, oldParent)
        try {
            withTimeout(CONCURRENCY_TIMEOUT_MILLIS) {
                val switching = oldParent.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    oldCoordinator.switchTo(uri)
                }
                oldHost.resolveStarted.await()
                val closing = oldParent.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    oldCoordinator.closeAndJoin()
                }
                assertFalse(closing.isCompleted)
                oldHost.resolveGate.complete(Unit)
                switching.await()
                closing.await()
                assertEquals(0, oldHost.establishCalls)
            }

            val newHost = SetupHost(uri, blockResolution = false)
            val newParent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val newCoordinator = coordinator(newHost, newParent)
            try {
                val result = withTimeout(CONCURRENCY_TIMEOUT_MILLIS) { newCoordinator.switchTo(uri) }
                assertTrue("a rebound host must still admit a new session", result is SwitchResult.Switched)
                assertEquals(1, newHost.establishCalls)
                assertEquals(1, newHost.startCalls)
            } finally {
                newCoordinator.closeAndJoin()
                newParent.cancel()
            }
        } finally {
            withContext(NonCancellable) {
                oldHost.resolveGate.complete(Unit)
                oldCoordinator.closeAndJoin()
            }
            oldParent.cancel()
        }
    }

    @Test
    fun repositoryFailure_doesNotPublishSuccessfulEmptySession() = runBlocking {
        val uri = "$packageName://audit-setup/corrupt"
        val host = SetupHost(uri, blockResolution = false).apply {
            loadResult = SessionLoadResult.Failed(
                DocumentLoadFailure(
                    detail = "corrupt accepted snapshot",
                    repositoryError = LocalRepositoryError.CorruptSnapshot(
                        path = "audit",
                        recoveryAttempted = true,
                        detail = "both slots unavailable"
                    )
                )
            )
        }
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = coordinator(host, parent)
        try {
            val result = withTimeout(CONCURRENCY_TIMEOUT_MILLIS) { coordinator.switchTo(uri) }
            assertTrue(result is SwitchResult.Failed)
            assertEquals(SwitchFailureStage.TARGET_LOAD, (result as SwitchResult.Failed).failure.stage)
            assertEquals(0, host.applyCalls)
            assertEquals(0, host.startCalls)
            assertTrue(host.failures.any { it.stage == SwitchFailureStage.TARGET_LOAD })
        } finally {
            coordinator.closeAndJoin()
            parent.cancel()
        }
    }

    private fun coordinator(host: SetupHost, parent: CoroutineScope) =
        DocumentSwitchCoordinator(
            callbacks = host,
            parentScope = parent,
            debounceMillis = 0L,
            coordinatorDispatcher = Dispatchers.Default
        )

    private class SetupHost(
        private val uri: String,
        private val blockResolution: Boolean
    ) : DocumentSessionCallbacks {
        private val source = DocumentSourceIdentityV1(uri, "audit.pdf")
        private val target = ResolvedDocumentTarget(
            DocumentAssociation(
                documentId = DocumentId.new(),
                source = source,
                sourceFingerprint = SourceFingerprint.fromBytes(uri.toByteArray())
            )
        )
        private val snapshot = DocumentSnapshotV1(
            schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
            snapshotRevision = 1L,
            source = source,
            pages = emptyMap()
        )

        val resolveStarted = CompletableDeferred<Unit>()
        val resolveGate = CompletableDeferred<Unit>()
        var clearCalls = 0
        var establishCalls = 0
        var applyCalls = 0
        var startCalls = 0
        var loadResult: SessionLoadResult = SessionLoadResult.Empty()
        val failures = mutableListOf<SwitchFailure>()

        override suspend fun resolveTarget(sourceUri: String): TargetResolution {
            resolveStarted.complete(Unit)
            if (blockResolution) {
                withContext(NonCancellable) { resolveGate.await() }
            }
            return TargetResolution.Resolved(target)
        }

        override fun captureSnapshot(session: DocumentSession): DocumentSnapshotV1 = snapshot

        override suspend fun saveSnapshot(
            session: DocumentSession,
            frozenSnapshot: DocumentSnapshotV1
        ): DocumentSaveResult = DocumentSaveResult.Saved(session.token.documentId)

        override suspend fun cancelAndJoinDocumentWork(session: DocumentSession) = Unit

        override fun invalidateDocumentWork(session: DocumentSession) = Unit

        override fun clearDocumentState() {
            clearCalls += 1
        }

        override fun establishSession(session: DocumentSession) {
            establishCalls += 1
        }

        override suspend fun loadTarget(session: DocumentSession): SessionLoadResult = loadResult

        override fun applyLoadedSnapshot(session: DocumentSession, snapshot: DocumentSnapshotV1) {
            applyCalls += 1
        }

        override fun startDocumentBackgroundWork(session: DocumentSession) {
            startCalls += 1
        }

        override fun onSwitchFailure(failure: SwitchFailure) {
            failures += failure
        }
    }
}
