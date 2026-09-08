package com.example.myapplication.stage8

import com.example.myapplication.acceptsBrowserPageSelection
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage3.DocumentSessionToken
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPageSelectionTest {
    private val token = DocumentSessionToken(DocumentId.new(), "content://stage8/page.pdf", null, 1L)

    @Test
    fun provisionalTargetCannotNavigateFromPageCard() {
        assertFalse(
            acceptsBrowserPageSelection(
                activeSessionToken = token,
                readySessionToken = token,
                pageIndex = 0,
                pageCount = 1,
                isCurrent = { true },
                isCurrentApplied = { false }
            )
        )
    }

    @Test
    fun currentAppliedTargetCanNavigateFromPageCard() {
        assertTrue(
            acceptsBrowserPageSelection(
                activeSessionToken = token,
                readySessionToken = token,
                pageIndex = 0,
                pageCount = 1,
                isCurrent = { true },
                isCurrentApplied = { true }
            )
        )
    }

    @Test
    fun staleTargetCannotNavigateEvenIfAnotherTargetIsApplied() {
        val stale = token.copy(generation = 2L)
        assertFalse(
            acceptsBrowserPageSelection(
                activeSessionToken = stale,
                readySessionToken = token,
                pageIndex = 0,
                pageCount = 1,
                isCurrent = { it == token },
                isCurrentApplied = { it == token }
            )
        )
    }
}
