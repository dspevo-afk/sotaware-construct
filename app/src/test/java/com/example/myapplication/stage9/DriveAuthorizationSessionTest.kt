package com.example.myapplication.stage9

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveAuthorizationSessionTest {
    @Test
    fun driveFileGrant_installsOnlyForCurrentAuthenticatedAccount() {
        val state = DriveAuthorizationSession()
        val generation = state.beginAuthentication(GoogleIdentity("subject-a", "a@example.test"))

        val result = state.applyAuthorization(
            expectedGeneration = generation,
            accessToken = "short-lived-token",
            grantedScopes = listOf(DRIVE_FILE_SCOPE)
        )

        val accepted = result as DriveAuthorizationApplyResult.Accepted
        assertEquals("a@example.test", accepted.session.identity.email)
        assertEquals(generation, accepted.session.generation)
        assertEquals(accepted.session, state.activeSession())
    }

    @Test
    fun accountChange_fencesLateAuthorizationResolution() {
        val state = DriveAuthorizationSession()
        val staleGeneration = state.beginAuthentication(GoogleIdentity("subject-a", "a@example.test"))
        val currentGeneration = state.beginAuthentication(GoogleIdentity("subject-b", "b@example.test"))

        assertEquals(
            DriveAuthorizationApplyResult.Stale,
            state.applyAuthorization(staleGeneration, "late-token", listOf(DRIVE_FILE_SCOPE))
        )
        assertNull(state.activeSession())
        assertEquals(
            currentGeneration,
            state.applyAuthorization(currentGeneration, "current-token", listOf(DRIVE_FILE_SCOPE))
                .let { (it as DriveAuthorizationApplyResult.Accepted).session.generation }
        )
    }

    @Test
    fun missingOrBroaderDriveScopes_neverInstallAToken() {
        val state = DriveAuthorizationSession()
        val generation = state.beginAuthentication(GoogleIdentity("subject-a", "a@example.test"))

        assertEquals(
            DriveAuthorizationApplyResult.MissingDriveFileScope,
            state.applyAuthorization(generation, "token", emptyList())
        )
        assertEquals(
            DriveAuthorizationApplyResult.ContainsUnexpectedDriveScope,
            state.applyAuthorization(
                generation,
                "token",
                listOf(DRIVE_FILE_SCOPE, "https://www.googleapis.com/auth/drive")
            )
        )
        assertNull(state.activeSession())
    }

    @Test
    fun signOutAndUnauthorizedResponse_clearOnlyTheCurrentToken() {
        val state = DriveAuthorizationSession()
        val generation = state.beginAuthentication(GoogleIdentity("subject-a", "a@example.test"))
        assertTrue(
            state.applyAuthorization(generation, "token", listOf(DRIVE_FILE_SCOPE))
                is DriveAuthorizationApplyResult.Accepted
        )

        assertTrue(state.clearAuthorizationIfCurrent(generation))
        assertNull(state.activeSession())
        state.clear()
        assertEquals(
            DriveAuthorizationApplyResult.Stale,
            state.applyAuthorization(generation, "late-token", listOf(DRIVE_FILE_SCOPE))
        )
    }

    @Test
    fun authenticationAttempt_reservesGenerationBeforeCredentialResponse() {
        val state = DriveAuthorizationSession()
        val firstAttempt = state.beginAuthenticationAttempt()

        assertEquals(firstAttempt, state.currentGeneration())
        assertNull(state.authenticatedIdentity())
        assertNull(state.activeSession())

        val secondAttempt = state.beginAuthenticationAttempt()
        assertTrue(secondAttempt > firstAttempt)
        assertFalse(
            state.authenticateIfCurrent(
                firstAttempt,
                GoogleIdentity("subject-a", "a@example.test")
            )
        )
        assertNull(state.authenticatedIdentity())
        assertTrue(
            state.authenticateIfCurrent(
                secondAttempt,
                GoogleIdentity("subject-b", "b@example.test")
            )
        )
    }

    @Test
    fun staleCredentialFailure_cannotClearNewerAuthenticatedAccount() {
        val state = DriveAuthorizationSession()
        val staleAttempt = state.beginAuthenticationAttempt()
        val currentAttempt = state.beginAuthentication(
            GoogleIdentity("subject-b", "b@example.test")
        )

        assertFalse(state.clearIfCurrent(staleAttempt))
        assertEquals(currentAttempt, state.currentGeneration())
        assertEquals(
            GoogleIdentity("subject-b", "b@example.test"),
            state.authenticatedIdentity()
        )
    }

    @Test
    fun staleAuthorizationGrant_cannotChangeNewerAccount() {
        val state = DriveAuthorizationSession()
        val staleGeneration = state.beginAuthentication(
            GoogleIdentity("subject-a", "a@example.test")
        )
        val currentGeneration = state.beginAuthentication(
            GoogleIdentity("subject-b", "b@example.test")
        )

        assertEquals(
            DriveAuthorizationApplyResult.Stale,
            state.applyAuthorization(
                expectedGeneration = staleGeneration,
                accessToken = "late-a-token",
                grantedScopes = listOf(DRIVE_FILE_SCOPE)
            )
        )
        assertNull(state.activeSession())
        assertTrue(
            state.applyAuthorization(
                expectedGeneration = currentGeneration,
                accessToken = "b-token",
                grantedScopes = listOf(DRIVE_FILE_SCOPE)
            ) is DriveAuthorizationApplyResult.Accepted
        )
        assertEquals("b@example.test", state.activeSession()?.identity?.email)
    }

    @Test
    fun accountEpochs_remainDistinctAcrossAtoBtoA() {
        val state = DriveAuthorizationSession()
        val firstA = GoogleIdentity("subject-a", "a@example.test")
        val b = GoogleIdentity("subject-b", "b@example.test")

        val firstAGeneration = state.beginAuthentication(firstA)
        assertTrue(
            state.applyAuthorization(firstAGeneration, "first-a-token", listOf(DRIVE_FILE_SCOPE))
                is DriveAuthorizationApplyResult.Accepted
        )
        val bGeneration = state.beginAuthentication(b)
        val secondAGeneration = state.beginAuthentication(firstA)

        assertNotEquals(firstAGeneration, bGeneration)
        assertNotEquals(bGeneration, secondAGeneration)
        assertNotEquals(firstAGeneration, secondAGeneration)
        assertEquals(
            DriveAuthorizationApplyResult.Stale,
            state.applyAuthorization(firstAGeneration, "late-first-a-token", listOf(DRIVE_FILE_SCOPE))
        )
        assertEquals(
            DriveAuthorizationApplyResult.Stale,
            state.applyAuthorization(bGeneration, "late-b-token", listOf(DRIVE_FILE_SCOPE))
        )
        val accepted = state.applyAuthorization(
            secondAGeneration,
            "second-a-token",
            listOf(DRIVE_FILE_SCOPE)
        ) as DriveAuthorizationApplyResult.Accepted
        assertEquals(firstA, accepted.session.identity)
        assertEquals(secondAGeneration, accepted.session.generation)
    }

    @Test
    fun signOut_fencesLateIdentityAndAuthorizationGrant() {
        val state = DriveAuthorizationSession()
        val attempt = state.beginAuthenticationAttempt()

        assertTrue(state.clearIfCurrent(attempt))
        assertTrue(state.currentGeneration() > attempt)
        assertFalse(
            state.authenticateIfCurrent(
                attempt,
                GoogleIdentity("subject-late", "late@example.test")
            )
        )
        assertEquals(
            DriveAuthorizationApplyResult.Stale,
            state.applyAuthorization(attempt, "late-token", listOf(DRIVE_FILE_SCOPE))
        )
        assertNull(state.authenticatedIdentity())
        assertNull(state.activeSession())
    }

    @Test
    fun exactDriveFileScope_isRequired() {
        val state = DriveAuthorizationSession()
        val generation = state.beginAuthentication(
            GoogleIdentity("subject-a", "a@example.test")
        )

        assertEquals(
            DriveAuthorizationApplyResult.MissingDriveFileScope,
            state.applyAuthorization(
                generation,
                "token",
                listOf("openid", "email", "profile")
            )
        )
        assertNull(state.activeSession())
    }

    @Test
    fun fullDriveAppDataAndReadOnlyDriveScopes_areRejected() {
        val unexpectedScopes = listOf(
            "https://www.googleapis.com/auth/drive",
            "https://www.googleapis.com/auth/drive.appdata",
            "https://www.googleapis.com/auth/drive.readonly"
        )

        unexpectedScopes.forEach { unexpectedScope ->
            val state = DriveAuthorizationSession()
            val generation = state.beginAuthentication(
                GoogleIdentity("subject-a", "a@example.test")
            )

            assertEquals(
                "unexpected scope $unexpectedScope",
                DriveAuthorizationApplyResult.ContainsUnexpectedDriveScope,
                state.applyAuthorization(
                    generation,
                    "token",
                    listOf(DRIVE_FILE_SCOPE, unexpectedScope)
                )
            )
            assertNull(state.activeSession())
        }
    }

    @Test
    fun blankOrMissingAccessToken_isRejected() {
        listOf(null, "", "  ").forEach { token ->
            val state = DriveAuthorizationSession()
            val generation = state.beginAuthentication(
                GoogleIdentity("subject-a", "a@example.test")
            )

            assertEquals(
                "token=$token",
                DriveAuthorizationApplyResult.MissingAccessToken,
                state.applyAuthorization(
                    generation,
                    token,
                    listOf(DRIVE_FILE_SCOPE)
                )
            )
            assertNull(state.activeSession())
        }
    }

    @Test
    fun harmlessGoogleIdentityScopes_areAllowedWithDriveFile() {
        val state = DriveAuthorizationSession()
        val generation = state.beginAuthentication(
            GoogleIdentity("subject-a", "a@example.test")
        )
        val harmlessIdentityScopes = listOf(
            "openid",
            "email",
            "profile",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile"
        )

        val accepted = state.applyAuthorization(
            generation,
            "token",
            harmlessIdentityScopes + DRIVE_FILE_SCOPE
        ) as DriveAuthorizationApplyResult.Accepted

        assertEquals(generation, accepted.session.generation)
        assertEquals("a@example.test", accepted.session.identity.email)
        assertEquals("token", accepted.session.accessToken)
    }

    @Test
    fun duplicateGrant_cannotReplaceActiveToken() {
        val state = DriveAuthorizationSession()
        val generation = state.beginAuthentication(
            GoogleIdentity("subject-a", "a@example.test")
        )
        assertTrue(
            state.applyAuthorization(generation, "first-token", listOf(DRIVE_FILE_SCOPE))
                is DriveAuthorizationApplyResult.Accepted
        )

        assertEquals(
            DriveAuthorizationApplyResult.Stale,
            state.applyAuthorization(generation, "replacement-token", listOf(DRIVE_FILE_SCOPE))
        )
        assertEquals("first-token", state.activeSession()?.accessToken)
        assertTrue(state.isAuthorizedGeneration(generation))
    }

    @Test
    fun currentUnauthorizedResponse_revokesTokenAndInvalidatesLateGrant() {
        val state = DriveAuthorizationSession()
        val generation = state.beginAuthentication(
            GoogleIdentity("subject-a", "a@example.test")
        )
        assertTrue(
            state.applyAuthorization(generation, "current-token", listOf(DRIVE_FILE_SCOPE))
                is DriveAuthorizationApplyResult.Accepted
        )
        assertTrue(state.isAuthorizedGeneration(generation))

        assertTrue(state.clearAuthorizationIfCurrent(generation))
        assertEquals(generation + 1, state.currentGeneration())
        assertFalse(state.isAuthorizedGeneration(generation))
        assertNull(state.activeSession())
        assertEquals(
            DriveAuthorizationApplyResult.Stale,
            state.applyAuthorization(generation, "late-token", listOf(DRIVE_FILE_SCOPE))
        )
    }

    @Test
    fun oldGenerationUnauthorizedResponse_cannotRevokeCurrentToken() {
        val state = DriveAuthorizationSession()
        val oldGeneration = state.beginAuthentication(
            GoogleIdentity("subject-a", "a@example.test")
        )
        val currentGeneration = state.beginAuthentication(
            GoogleIdentity("subject-b", "b@example.test")
        )
        assertTrue(
            state.applyAuthorization(currentGeneration, "current-token", listOf(DRIVE_FILE_SCOPE))
                is DriveAuthorizationApplyResult.Accepted
        )

        assertFalse(state.clearAuthorizationIfCurrent(oldGeneration))
        assertEquals("current-token", state.activeSession()?.accessToken)
        assertEquals("b@example.test", state.activeSession()?.identity?.email)
        assertTrue(state.isAuthorizedGeneration(currentGeneration))
    }
}
