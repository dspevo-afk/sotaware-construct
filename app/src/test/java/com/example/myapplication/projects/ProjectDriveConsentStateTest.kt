package com.example.myapplication.projects

import android.app.Activity
import com.example.myapplication.stage9.DriveAuthorizationRequestResult
import com.example.myapplication.stage9.DriveAuthorizationResolutionResult
import com.example.myapplication.stage9.GoogleIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class ProjectDriveConsentStateTest {
    @Test fun savedPendingConsentRestoresOnlyOperationGenerationAndAccountSubject() {
        val pending = PendingProjectDriveConsent(
            "operation-123", 7L, "project-account", "process-owner", "backup-account"
        )

        val saved = encodePendingProjectDriveConsent(pending)
        assertTrue(saved is ArrayList<*>)
        assertEquals(
            arrayListOf("operation-123", "7", "project-account", "process-owner", "backup-account"),
            saved
        )
        assertEquals(pending, decodePendingProjectDriveConsent(saved))
    }

    @Test fun nullAndMalformedSavedConsentRestoreToExplicitlyRetryableEmptyState() {
        assertEquals(arrayListOf<String>(), encodePendingProjectDriveConsent(null))
        assertNull(decodePendingProjectDriveConsent(arrayListOf<String>()))
        assertNull(decodePendingProjectDriveConsent(arrayListOf("operation", "0", "account", "owner", "")))
        assertNull(decodePendingProjectDriveConsent(arrayListOf("operation", "bad", "account", "owner", "")))
        assertNull(decodePendingProjectDriveConsent(listOf("operation", "1", "account", "owner", "")))
    }

    @Test fun delayedResultMustMatchOperationAndCurrentAccountGeneration() {
        val older = PendingProjectDriveConsent("old-operation", 4L, "project-a", "owner", "backup-a")
        val current = PendingProjectDriveConsent("new-operation", 5L, "project-b", "owner", "backup-a")

        assertFalse(matches(current, 5L, older.operationId))
        assertFalse(matches(current, 6L, current.operationId))
        assertFalse(matches(current, 5L, null))
        assertTrue(matches(current, 5L, current.operationId))
    }

    @Test fun aChangedBackupIdentityInvalidatesTheResultButDistinctProjectAccountRemainsValid() {
        val pending = PendingProjectDriveConsent(
            "operation", 9L, "intentionally-selected-project-account", "owner", "backup-account-a"
        )

        assertTrue(matches(pending, 9L, "operation", backupSubject = "backup-account-a"))
        assertFalse(matches(pending, 9L, "operation", backupSubject = "backup-account-b"))
        // A temporarily unavailable backup identity is not evidence of a change;
        // the independent process owner nonce still rejects process-death restore.
        assertTrue(matches(pending, 9L, "operation", backupSubject = null))
    }

    @Test fun processDeathOwnerReplacementRejectsRestoredConsent() {
        val pending = PendingProjectDriveConsent("operation", 1L, "project", "old-process", null)

        assertTrue(matches(pending, 1L, "operation", owner = "old-process", backupSubject = null))
        assertFalse(matches(pending, 1L, "operation", owner = "new-process", backupSubject = null))
    }

    @Test fun ownerStartsScopedAttemptAndOwnsPendingCorrelationState() = runTest {
        val backupIdentity = GoogleIdentity("backup-subject", "backup@example.test")
        val projectIdentity = GoogleIdentity("project-subject", "project@example.test")
        val auth = FakeProjectAuthorizationPort(projectIdentity).apply {
            requestResult = ProjectDriveAuthorizationRequest.ResolutionRequired("synthetic-launch-handle")
        }
        val owner = ProjectDriveAuthorizationOwner(auth, "activity-owner", newOperationId = { "operation-one" })

        val result = owner.connect(backupIdentity, changeAccount = true)

        assertEquals(
            ProjectDriveConnectOutcome.ResolutionRequired(
                PendingProjectDriveConsent(
                    "operation-one", 1L, "project-subject", "activity-owner", "backup-subject"
                ),
                "synthetic-launch-handle"
            ),
            result
        )
        assertEquals(1L, owner.state.value.accountGeneration)
        assertEquals("operation-one", owner.state.value.pendingConsent?.operationId)
        assertTrue(owner.state.value.connecting)
        assertEquals(1, auth.signInCalls)
        assertEquals(projectIdentity, auth.requestedIdentity)
    }

    @Test fun existingBackupIdentityIsReusedForDirectProjectGrant() = runTest {
        val backupIdentity = GoogleIdentity("backup-subject", "backup@example.test")
        val authorization = DriveAuthorizationRequestResult.Granted(
            "synthetic-project-token",
            setOf(DRIVE_PROJECT_READ_SCOPE)
        )
        val auth = FakeProjectAuthorizationPort(GoogleIdentity("unused", "unused@example.test")).apply {
            requestResult = ProjectDriveAuthorizationRequest.Granted(authorization)
        }
        val owner = ProjectDriveAuthorizationOwner(auth, "owner")

        assertEquals(
            ProjectDriveConnectOutcome.Granted("backup-subject", authorization),
            owner.connect(backupIdentity)
        )
        assertEquals(0, auth.signInCalls)
        assertEquals(backupIdentity, auth.requestedIdentity)
        assertFalse(owner.state.value.connecting)
        assertNull(owner.state.value.pendingConsent)
    }

    @Test fun rotationRestoresOnlySafeConsentStateAndConsumesTheMatchingResult() = runTest {
        val backupIdentity = GoogleIdentity("backup-subject", "backup@example.test")
        val projectIdentity = GoogleIdentity("project-subject", "project@example.test")
        val auth = FakeProjectAuthorizationPort(projectIdentity).apply {
            requestResult = ProjectDriveAuthorizationRequest.ResolutionRequired("launch-handle")
        }
        val beforeRotation = ProjectDriveAuthorizationOwner(auth, "same-activity-owner", newOperationId = { "operation" })
        val connect = beforeRotation.connect(backupIdentity, changeAccount = true)
        val pending = (connect as ProjectDriveConnectOutcome.ResolutionRequired).pending

        val saved = encodeProjectDriveAuthorizationState(beforeRotation.saveableState())
        assertFalse(saved.contains("synthetic-project-token"))
        val afterRotation = ProjectDriveAuthorizationOwner(
            auth,
            "same-activity-owner",
            decodeProjectDriveAuthorizationState(saved)
        )
        assertEquals(pending, afterRotation.state.value.pendingConsent)
        assertFalse(afterRotation.state.value.connecting)

        val outcome = afterRotation.consumeResolutionResult(
            DriveAuthorizationResolutionResult("operation", Activity.RESULT_OK, null),
            currentBackupIdentitySubject = "backup-subject"
        )

        assertEquals(
            ProjectDriveResolutionOutcome.Granted(
                "project-subject",
                DriveAuthorizationRequestResult.Granted(
                    "synthetic-project-token",
                    setOf(DRIVE_PROJECT_READ_SCOPE)
                )
            ),
            outcome
        )
        assertNull(afterRotation.state.value.pendingConsent)
        assertFalse(afterRotation.state.value.connecting)
        assertEquals(1, auth.completeCalls)
    }

    @Test fun processDeathNonceRejectsRestoredResultAndRequiresReconnect() = runTest {
        val auth = FakeProjectAuthorizationPort(GoogleIdentity("project", "project@example.test")).apply {
            requestResult = ProjectDriveAuthorizationRequest.ResolutionRequired("launch-handle")
        }
        val beforeDeath = ProjectDriveAuthorizationOwner(auth, "old-process", newOperationId = { "operation" })
        beforeDeath.connect(null, changeAccount = true)
        val saved = encodeProjectDriveAuthorizationState(beforeDeath.saveableState())
        val afterDeath = ProjectDriveAuthorizationOwner(
            auth,
            "new-process",
            decodeProjectDriveAuthorizationState(saved)
        )

        val outcome = afterDeath.consumeResolutionResult(
            DriveAuthorizationResolutionResult("operation", Activity.RESULT_OK, null),
            currentBackupIdentitySubject = null
        )

        assertEquals(ProjectDriveResolutionOutcome.Expired, outcome)
        assertNull(afterDeath.state.value.pendingConsent)
        assertFalse(afterDeath.state.value.connecting)
        assertEquals(0, auth.completeCalls)
    }

    @Test fun changedBackupIdentityExpiresButUnavailableIdentityDoesNot() = runTest {
        val backupIdentity = GoogleIdentity("backup-a", "backup@example.test")
        val auth = FakeProjectAuthorizationPort(GoogleIdentity("project", "project@example.test")).apply {
            requestResult = ProjectDriveAuthorizationRequest.ResolutionRequired("launch-handle")
        }
        val changedOwner = ProjectDriveAuthorizationOwner(auth, "owner-a", newOperationId = { "changed" })
        changedOwner.connect(backupIdentity, changeAccount = true)
        assertEquals(
            ProjectDriveResolutionOutcome.Expired,
            changedOwner.consumeResolutionResult(
                DriveAuthorizationResolutionResult("changed", Activity.RESULT_OK, null),
                currentBackupIdentitySubject = "backup-b"
            )
        )
        assertEquals(0, auth.completeCalls)

        val unavailableOwner = ProjectDriveAuthorizationOwner(auth, "owner-a", newOperationId = { "unavailable" })
        unavailableOwner.connect(backupIdentity, changeAccount = true)
        assertTrue(unavailableOwner.invalidatePendingConsentIfStale(null).not())
        assertTrue(
            unavailableOwner.consumeResolutionResult(
                DriveAuthorizationResolutionResult("unavailable", Activity.RESULT_OK, null),
                currentBackupIdentitySubject = null
            ) is ProjectDriveResolutionOutcome.Granted
        )
        assertEquals(1, auth.completeCalls)
    }

    @Test fun olderResultCannotConsumeOrReplaceTheCurrentAttempt() = runTest {
        val auth = FakeProjectAuthorizationPort(GoogleIdentity("project", "project@example.test")).apply {
            requestResult = ProjectDriveAuthorizationRequest.ResolutionRequired("launch-handle")
        }
        val ids = ArrayDeque(listOf("old-operation", "new-operation"))
        val owner = ProjectDriveAuthorizationOwner(auth, "owner", newOperationId = { ids.removeFirst() })
        owner.connect(null, changeAccount = true)
        owner.invalidate()
        owner.connect(null, changeAccount = true)
        val currentPending = owner.state.value.pendingConsent

        val oldResult = owner.consumeResolutionResult(
            DriveAuthorizationResolutionResult("old-operation", Activity.RESULT_OK, null),
            currentBackupIdentitySubject = null
        )

        assertEquals(ProjectDriveResolutionOutcome.Ignored, oldResult)
        assertEquals(currentPending, owner.state.value.pendingConsent)
        assertEquals("new-operation", owner.state.value.pendingConsent?.operationId)
        assertEquals(0, auth.completeCalls)
    }

    @Test fun explicitRetryReplacesPendingOperationAndRejectsItsLateResult() = runTest {
        val auth = FakeProjectAuthorizationPort(GoogleIdentity("project", "project@example.test")).apply {
            requestResult = ProjectDriveAuthorizationRequest.ResolutionRequired("launch-handle")
        }
        val ids = ArrayDeque(listOf("first-operation", "retry-operation"))
        val owner = ProjectDriveAuthorizationOwner(auth, "owner", newOperationId = { ids.removeFirst() })
        owner.connect(null, changeAccount = true)

        owner.prepareRetry()
        val retry = owner.connect(null, changeAccount = true)
        val retryPending = (retry as ProjectDriveConnectOutcome.ResolutionRequired).pending

        assertEquals(2L, retryPending.generation)
        assertEquals("retry-operation", retryPending.operationId)
        assertEquals(
            ProjectDriveResolutionOutcome.Ignored,
            owner.consumeResolutionResult(
                DriveAuthorizationResolutionResult("first-operation", Activity.RESULT_OK, null),
                currentBackupIdentitySubject = null
            )
        )
        assertEquals(retryPending, owner.state.value.pendingConsent)
    }

    @Test fun aGrantFromAnInvalidatedSuspendedRequestCannotBecomeCurrent() = runTest {
        val auth = FakeProjectAuthorizationPort(GoogleIdentity("project", "project@example.test"))
        val requestEntered = CompletableDeferred<Unit>()
        val delayedRequest = CompletableDeferred<ProjectDriveAuthorizationRequest<String>>()
        auth.requestEntered = requestEntered
        auth.delayedRequest = delayedRequest
        val owner = ProjectDriveAuthorizationOwner(auth, "owner")
        val attempt = async { owner.connect(null, changeAccount = true) }

        requestEntered.await()
        owner.invalidate()
        delayedRequest.complete(
            ProjectDriveAuthorizationRequest.Granted(
                DriveAuthorizationRequestResult.Granted(
                    "synthetic-project-token",
                    setOf(DRIVE_PROJECT_READ_SCOPE)
                )
            )
        )

        assertEquals(ProjectDriveConnectOutcome.Ignored, attempt.await())
        assertEquals(2L, owner.state.value.accountGeneration)
        assertFalse(owner.state.value.connecting)
        assertNull(owner.state.value.pendingConsent)
    }

    @Test fun canceledIdentityWorkPropagatesCancellationAndFinishesItsAttempt() = runTest {
        val auth = FakeProjectAuthorizationPort(GoogleIdentity("project", "project@example.test")).apply {
            signInFailure = CancellationException("cancelled")
        }
        val owner = ProjectDriveAuthorizationOwner(auth, "owner")

        try {
            owner.connect(null, changeAccount = true)
            throw AssertionError("Expected cancellation to propagate")
        } catch (_: CancellationException) {
            // Expected: the caller owns cancellation of its coroutine.
        }

        assertEquals(1L, owner.state.value.accountGeneration)
        assertFalse(owner.state.value.connecting)
        assertNull(owner.state.value.pendingConsent)
    }

    private fun matches(
        pending: PendingProjectDriveConsent,
        generation: Long,
        operationId: String?,
        owner: String = "owner",
        backupSubject: String? = "backup-a"
    ) = projectDriveConsentResultMatches(pending, generation, operationId, owner, backupSubject)

    private class FakeProjectAuthorizationPort(
        private val signedInIdentity: GoogleIdentity
    ) : ProjectDriveAuthorizationPort<String> {
        override val isConfigured: Boolean = true
        var requestResult: ProjectDriveAuthorizationRequest<String> =
            ProjectDriveAuthorizationRequest.Granted(
                DriveAuthorizationRequestResult.Granted(
                    "synthetic-project-token",
                    setOf(DRIVE_PROJECT_READ_SCOPE)
                )
            )
        var signInFailure: Exception? = null
        var signInCalls = 0
        var completeCalls = 0
        var requestedIdentity: GoogleIdentity? = null
        var requestEntered: CompletableDeferred<Unit>? = null
        var delayedRequest: CompletableDeferred<ProjectDriveAuthorizationRequest<String>>? = null

        override suspend fun signIn(): GoogleIdentity {
            signInCalls++
            signInFailure?.let { throw it }
            return signedInIdentity
        }

        override suspend fun requestReadAccess(
            identity: GoogleIdentity
        ): ProjectDriveAuthorizationRequest<String> {
            requestedIdentity = identity
            requestEntered?.complete(Unit)
            delayedRequest?.let { return it.await() }
            return requestResult
        }

        override fun completeReadAccess(data: android.content.Intent?): DriveAuthorizationRequestResult.Granted {
            completeCalls++
            return DriveAuthorizationRequestResult.Granted(
                "synthetic-project-token",
                setOf(DRIVE_PROJECT_READ_SCOPE)
            )
        }
    }
}
