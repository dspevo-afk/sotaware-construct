package com.example.myapplication.stage9

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveAuthorizationResolutionTrackerTest {
    private val accountA = GoogleIdentity("subject-a", "a@example.test")
    private val accountB = GoogleIdentity("subject-b", "b@example.test")

    @Test
    fun matchingResult_consumesTheExactPendingAuthority() {
        val tracker = trackerWithIds("operation-a")
        val owner = DriveAuthorizationAuthorityOwner()
        val pending = tracker.begin(owner, 7L, accountA)

        assertTrue(tracker.hasPending())
        assertEquals(pending, tracker.consume(pending.operationId, owner, 7L, accountA))
        assertFalse(tracker.hasPending())
        assertNull(tracker.consume(pending.operationId, owner, 7L, accountA))
    }

    @Test
    fun staleResult_cannotConsumeANewerAccountAttempt() {
        val ids = ArrayDeque(listOf("account-a", "account-b"))
        val tracker = DriveAuthorizationResolutionTracker { ids.removeFirst() }
        val owner = DriveAuthorizationAuthorityOwner()
        val old = tracker.begin(owner, 1L, accountA)
        val current = tracker.begin(owner, 2L, accountB)

        assertNull(tracker.consume(old.operationId, owner, 2L, accountB))
        assertTrue(tracker.hasPending())
        assertEquals(current, tracker.consume(current.operationId, owner, 2L, accountB))
    }

    @Test
    fun processRecreation_generationCollisionDoesNotRelabelOldResult() {
        val oldProcess = trackerWithIds("old-process-operation")
        val oldOwner = DriveAuthorizationAuthorityOwner()
        val old = oldProcess.begin(oldOwner, 1L, accountB)

        val recreatedProcess = trackerWithIds("new-process-operation")
        val newOwner = DriveAuthorizationAuthorityOwner()
        val current = recreatedProcess.begin(newOwner, 1L, accountA)

        assertNull(recreatedProcess.consume(old.operationId, newOwner, 1L, accountA))
        assertTrue(recreatedProcess.hasPending())
        assertEquals(current, recreatedProcess.consume(current.operationId, newOwner, 1L, accountA))
    }

    @Test
    fun activityOwnerReplacement_rejectsMatchingOperationAndGeneration() {
        val tracker = trackerWithIds("old-operation")
        val oldOwner = DriveAuthorizationAuthorityOwner()
        val old = tracker.begin(oldOwner, 1L, accountB)

        val replacementOwner = DriveAuthorizationAuthorityOwner()
        assertNull(tracker.consume(old.operationId, replacementOwner, 1L, accountA))
        assertFalse(tracker.hasPending())
    }

    @Test
    fun identityReplacement_rejectsMatchingOperationOwnerAndGeneration() {
        val tracker = trackerWithIds("old-operation")
        val owner = DriveAuthorizationAuthorityOwner()
        val old = tracker.begin(owner, 1L, accountB)

        assertNull(tracker.consume(old.operationId, owner, 1L, accountA))
        assertFalse(tracker.hasPending())
    }

    @Test
    fun failedLaunch_clearsOnlyItsExactPendingOperation() {
        val ids = ArrayDeque(listOf("old", "new"))
        val tracker = DriveAuthorizationResolutionTracker { ids.removeFirst() }
        val owner = DriveAuthorizationAuthorityOwner()
        val old = tracker.begin(owner, 3L, accountA)
        val current = tracker.begin(owner, 4L, accountB)

        assertFalse(tracker.clearIfCurrent(old))
        assertTrue(tracker.hasPending())
        assertTrue(tracker.clearIfCurrent(current))
        assertFalse(tracker.hasPending())
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankOperationId_isRejected() {
        DriveAuthorizationResolutionTracker { " " }
            .begin(DriveAuthorizationAuthorityOwner(), 1L, accountA)
    }

    private fun trackerWithIds(vararg ids: String): DriveAuthorizationResolutionTracker {
        val remaining = ArrayDeque(ids.toList())
        return DriveAuthorizationResolutionTracker { remaining.removeFirst() }
    }
}
