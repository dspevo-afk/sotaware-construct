package com.example.myapplication.stage9

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupDriveAuthorizationTest {
    private val combined = DriveAuthorizationRequestResult.Granted("combined-token",
        setOf(DRIVE_FILE_SCOPE, "https://www.googleapis.com/auth/drive.readonly"))

    @Test fun cachedProjectGrantIsClearedBeforeRequestingABackupToken() = runBlocking {
        val events = mutableListOf<String>()
        val narrow = DriveAuthorizationRequestResult.Granted("backup-token", setOf(DRIVE_FILE_SCOPE))
        val result = refreshUnexpectedBackupGrant(combined,
            clearToken = { events += "clear:$it" }, requestFresh = { events += "request"; narrow })
        assertEquals(listOf("clear:combined-token", "request"), events)
        assertEquals(narrow, result)
        val session = DriveAuthorizationSession()
        val generation = session.beginAuthentication(GoogleIdentity("subject", "synthetic@example.test"))
        assertTrue(session.applyAuthorization(generation, narrow.accessToken, narrow.grantedScopes) is DriveAuthorizationApplyResult.Accepted)
    }

    @Test fun repeatedBroadGrantStillFailsClosedWithoutAnInfiniteRetry() = runBlocking {
        var requests = 0
        val result = refreshUnexpectedBackupGrant(combined, {}, { requests++; combined }) as DriveAuthorizationRequestResult.Granted
        val session = DriveAuthorizationSession()
        val generation = session.beginAuthentication(GoogleIdentity("subject", "synthetic@example.test"))
        assertEquals(1, requests)
        assertEquals(DriveAuthorizationApplyResult.ContainsUnexpectedDriveScope,
            session.applyAuthorization(generation, result.accessToken, result.grantedScopes))
        assertNull(session.activeSession())
    }

    @Test fun validBackupGrantDoesNotClearAnyProviderToken() = runBlocking {
        val narrow = DriveAuthorizationRequestResult.Granted("backup-token", setOf(DRIVE_FILE_SCOPE))
        assertSame(narrow, refreshUnexpectedBackupGrant(narrow, { error("Unexpected cache clear") }, { error("Unexpected retry") }))
    }

    @Test fun cancellationDuringClearDoesNotRequestAnotherToken() = runBlocking {
        try {
            refreshUnexpectedBackupGrant(combined, { throw CancellationException() }, { error("Cancelled request retried") })
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }
}
