package com.example.myapplication.stage8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCodeCacheBudgetTest {
    @Test fun unavailableInventoryCannotAdmitACacheWrite() {
        val plan = PageCodeCacheBudget.plan(
            entries = null,
            incomingKey = "next",
            incomingBytes = 1
        )

        assertNull(plan)
    }

    @Test fun bytePressureEvictsOldestInactiveEntriesAndKeepsActiveOnes() {
        val plan = PageCodeCacheBudget.plan(
            entries = listOf(
                PageCodeCacheBudget.Entry("old", bytes = 4, files = 1, lastUsedMillis = 1, active = false),
                PageCodeCacheBudget.Entry("active", bytes = 4, files = 1, lastUsedMillis = 2, active = true),
                PageCodeCacheBudget.Entry("newer", bytes = 1, files = 1, lastUsedMillis = 3, active = false)
            ),
            incomingKey = "next",
            incomingBytes = 3,
            maxBytes = 8,
            maxFiles = 20
        )

        assertEquals(listOf("old"), plan?.evictKeys)
        assertTrue(plan?.canFit == true)
    }

    @Test fun filePressureEvictsEnoughInactiveCacheFilesEvenWhenBytesFit() {
        val plan = PageCodeCacheBudget.plan(
            entries = listOf(
                PageCodeCacheBudget.Entry("old", bytes = 1, files = 2, lastUsedMillis = 1, active = false),
                PageCodeCacheBudget.Entry("active", bytes = 1, files = 1, lastUsedMillis = 2, active = true),
                PageCodeCacheBudget.Entry("newer", bytes = 1, files = 1, lastUsedMillis = 3, active = false)
            ),
            incomingKey = "next",
            incomingBytes = 1,
            maxBytes = 100,
            maxFiles = 3
        )

        assertEquals(listOf("old"), plan?.evictKeys)
        assertTrue(plan?.canFit == true)
    }

    @Test fun replacementNeverEvictsItsOwnEntryAndReportsActiveCapacityFailure() {
        val plan = PageCodeCacheBudget.plan(
            entries = listOf(
                PageCodeCacheBudget.Entry("inactive", bytes = 1, files = 1, lastUsedMillis = 1, active = false),
                PageCodeCacheBudget.Entry("active", bytes = 4, files = 1, lastUsedMillis = 2, active = true),
                PageCodeCacheBudget.Entry("current", bytes = 2, files = 1, lastUsedMillis = 3, active = false)
            ),
            incomingKey = "current",
            incomingBytes = 1,
            maxBytes = 6,
            maxFiles = 10
        )

        assertEquals(listOf("inactive"), plan?.evictKeys)
        assertFalse(plan?.canFit ?: true)
    }

    @Test fun anActiveEntryAloneIsNeverSelectedForEviction() {
        val plan = PageCodeCacheBudget.plan(
            entries = listOf(
                PageCodeCacheBudget.Entry("active", bytes = 7, files = 1, lastUsedMillis = 1, active = true)
            ),
            incomingKey = "next",
            incomingBytes = 2,
            maxBytes = 8,
            maxFiles = 1
        )

        assertEquals(emptyList<String>(), plan?.evictKeys)
        assertFalse(plan?.canFit ?: true)
    }
}
