package com.example.myapplication.stage8

import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import com.example.myapplication.OcrBox
import com.example.myapplication.PageOcr
import com.example.myapplication.Point
import com.example.myapplication.ui.firstMatchFrom
import com.example.myapplication.ui.orderedSearchMatches
import com.example.myapplication.ui.theme.AppTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Phase 5 of the UX plan: page-code box editing, automatic page codes, search order, themes. */
class Phase5UxTest {
    // Android's host stub constructor does not populate RectF fields.
    private fun rect(l: Float, t: Float, r: Float, b: Float) = RectF().apply { left = l; top = t; right = r; bottom = b }

    // --- C15: the page-code box can be adjusted before it is used ---------------------
    private val identity: (Point) -> Point = { Point(it.x * 1000f, it.y * 1000f) }
    private val box = PageCodeRegion(.70f, .80f, .90f, .95f)

    @Test fun cornerHandlesAreGrippedNearTheCornerAndTheInsideMovesTheBox() {
        assertEquals(PageCodeBoxEdit.Grip.Corner(0), PageCodeBoxEdit.grip(box, identity, Offset(705f, 805f), 28f))
        assertEquals(PageCodeBoxEdit.Grip.Corner(2), PageCodeBoxEdit.grip(box, identity, Offset(895f, 948f), 28f))
        assertEquals(PageCodeBoxEdit.Grip.Move, PageCodeBoxEdit.grip(box, identity, Offset(800f, 870f), 28f))
        assertNull(PageCodeBoxEdit.grip(box, identity, Offset(100f, 100f), 28f))
    }

    @Test fun resizingKeepsTheOppositeCornerAndNeverInvertsOrLeavesThePage() {
        val grown = PageCodeBoxEdit.resize(box, 0, Point(.6f, .7f))
        assertEquals(PageCodeRegion(.6f, .7f, .9f, .95f), grown)
        val pastOpposite = PageCodeBoxEdit.resize(box, 0, Point(.99f, .99f))
        assertTrue(pastOpposite.left < pastOpposite.right && pastOpposite.top < pastOpposite.bottom)
        assertEquals(.9f, pastOpposite.right, 1e-6f)
        val offPage = PageCodeBoxEdit.resize(box, 2, Point(1.4f, 1.3f))
        assertEquals(1f, offPage.right, 1e-6f)
        assertEquals(1f, offPage.bottom, 1e-6f)
    }

    @Test fun movingKeepsTheSizeAndStaysOnThePage() {
        val moved = PageCodeBoxEdit.move(box, .5f, .5f)
        assertEquals(1f, moved.right, 1e-6f)
        assertEquals(1f, moved.bottom, 1e-6f)
        assertEquals(box.right - box.left, moved.right - moved.left, 1e-5f)
        assertEquals(box.bottom - box.top, moved.bottom - moved.top, 1e-5f)
    }

    @Test fun aTinyDragIsNotABox() {
        assertNull(PageCodeBoxEdit.fromCorners(Point(.5f, .5f), Point(.501f, .6f)))
        assertEquals(PageCodeRegion(.2f, .3f, .4f, .5f), PageCodeBoxEdit.fromCorners(Point(.4f, .5f), Point(.2f, .3f)))
    }

    // --- L12: automatic page codes ----------------------------------------------------
    @Test fun theCornerGuessIsTheLargestSheetNumberAndIgnoresOtherText() {
        val page = PageOcr(0, listOf(
            OcrBox("SHEET NUMBER:", rect(.75f, .86f, .85f, .88f)),
            OcrBox("A1.0", rect(.80f, .90f, .84f, .91f)),        // small reference
            OcrBox("E-101", rect(.85f, .92f, .97f, .97f)),       // the big sheet number
            OcrBox("E9.9", rect(.10f, .10f, .20f, .30f))         // outside the corner
        ))
        assertEquals("E-101", PageCodeIndex.codeInCorner(page))
        assertNull(PageCodeIndex.codeInCorner(PageOcr(1, listOf(OcrBox("CHECKED BY", rect(.8f, .9f, .9f, .95f))))))
    }

    @Test fun theAutomaticPassReportsUnreadSheetsInsteadOfFailing() = runBlocking {
        val result = PageCodeIndex.autoScan(3, { true }) { page ->
            when (page) {
                0 -> PageOcr(0, listOf(OcrBox("E2.0", rect(.85f, .9f, .95f, .96f))))
                else -> null
            }
        }
        assertEquals(mapOf(0 to "E2.0"), result.codes)
        assertEquals(2, result.unreadPages)
        // Nothing readable anywhere is still a result, not an error.
        val none = PageCodeIndex.autoScan(2, { true }) { null }
        assertTrue(none.codes.isEmpty())
        assertEquals(2, none.unreadPages)
    }

    // --- L11: search bar order ------------------------------------------------------------
    @Test fun matchesAreInReadingOrderAndAFreshResultStartsOnTheCurrentSheet() {
        val matches = orderedSearchMatches(mapOf(
            2 to listOf(rect(.5f, .5f, .6f, .55f), rect(.1f, .1f, .2f, .15f)),
            0 to listOf(rect(.3f, .2f, .4f, .25f))
        ))
        assertEquals(listOf(0, 2, 2), matches.map { it.first })
        assertEquals(.1f, matches[1].second.top, 0f)
        assertEquals(1, firstMatchFrom(matches, 1))
        assertEquals(0, firstMatchFrom(matches, 0))
        assertEquals(0, firstMatchFrom(matches, 3)) // past the last sheet: wrap to the start
    }

    // --- Themes -------------------------------------------------------------------------
    @Test fun themePreferenceRoundTripsAndUnknownValuesFallBackToSlate() {
        AppTheme.entries.forEach { assertEquals(it, AppTheme.fromPref(it.prefValue)) }
        assertEquals(AppTheme.SLATE, AppTheme.fromPref(null))
        assertEquals(AppTheme.SLATE, AppTheme.fromPref("purple"))
    }
}
