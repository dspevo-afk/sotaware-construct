package com.example.myapplication.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun matches(
        pending: PendingProjectDriveConsent,
        generation: Long,
        operationId: String?,
        owner: String = "owner",
        backupSubject: String? = "backup-a"
    ) = projectDriveConsentResultMatches(pending, generation, operationId, owner, backupSubject)
}
