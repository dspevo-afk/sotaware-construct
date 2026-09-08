package com.example.myapplication.stage9

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveAuthorityTransitionTest {
    @Test
    fun authenticationAttempt_removesLiveAuthorityWhileOldWorkIsStillJoining() = runTest {
        val state = authorizedSession()
        val oldGeneration = state.currentGeneration()
        val joinEntered = CompletableDeferred<Unit>()
        val releaseJoin = CompletableDeferred<Unit>()
        var invalidated = false
        var requestedNewCredential = false
        val transition = async {
            val attempt = changeDriveAuthority(
                invalidateSync = { invalidated = true },
                changeAuthority = {
                    assertTrue(invalidated)
                    state.beginAuthenticationAttempt()
                },
                joinPreviousWork = {
                    joinEntered.complete(Unit)
                    releaseJoin.await()
                }
            )
            requestedNewCredential = true
            attempt
        }
        joinEntered.await()

        // A concurrent document callback now sees no account to bind, even if
        // Compose has not consumed an auth-state update during this window.
        assertNull(state.activeSession())
        assertNull(state.authenticatedIdentity())
        assertFalse(state.isAuthorizedGeneration(oldGeneration))
        assertEquals(
            DriveAuthorizationApplyResult.Stale,
            state.applyAuthorization(oldGeneration, "late-token", listOf(DRIVE_FILE_SCOPE))
        )
        assertFalse(requestedNewCredential)
        releaseJoin.complete(Unit)
        assertEquals(state.currentGeneration(), transition.await())
        assertTrue(requestedNewCredential)
    }

    @Test
    fun canceledSignOut_stillDrainsOldWorkAndCannotRestoreTheOldToken() = runTest {
        val state = authorizedSession()
        val oldGeneration = state.currentGeneration()
        val joinEntered = CompletableDeferred<Unit>()
        val releaseJoin = CompletableDeferred<Unit>()
        var joinFinished = false
        var continuedAfterTransition = false
        val transition = async {
            changeDriveAuthority(
                invalidateSync = {},
                changeAuthority = { state.clear() },
                joinPreviousWork = {
                    joinEntered.complete(Unit)
                    releaseJoin.await()
                    joinFinished = true
                }
            )
            continuedAfterTransition = true
        }
        joinEntered.await()
        transition.cancel()
        assertNull(state.activeSession())
        assertFalse(transition.isCompleted)
        assertFalse(joinFinished)
        releaseJoin.complete(Unit)
        transition.join()

        assertTrue(joinFinished)
        assertTrue(transition.isCancelled)
        assertFalse(continuedAfterTransition)
        assertNull(state.authenticatedIdentity())
        assertEquals(
            DriveAuthorizationApplyResult.Stale,
            state.applyAuthorization(oldGeneration, "late-token", listOf(DRIVE_FILE_SCOPE))
        )
    }

    private fun authorizedSession(): DriveAuthorizationSession = DriveAuthorizationSession().apply {
        val generation = beginAuthentication(GoogleIdentity("subject-a", "a@example.test"))
        check(applyAuthorization(generation, "token", listOf(DRIVE_FILE_SCOPE)) is DriveAuthorizationApplyResult.Accepted)
    }
}
