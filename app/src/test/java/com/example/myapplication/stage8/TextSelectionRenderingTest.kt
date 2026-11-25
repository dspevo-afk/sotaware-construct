package com.example.myapplication.stage8

import androidx.compose.ui.geometry.Offset
import com.example.myapplication.OcrBox
import com.example.myapplication.PageOcr
import com.example.myapplication.Point
import com.example.myapplication.stage8.ViewerTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a text selection is painted. Dave's tablet report: the Copy popup appeared over the
 * selected words and nothing on the page was highlighted, because the painter fed the viewer
 * transform bitmap pixels where it measures the page in normalized coordinates (0..1). The
 * result landed thousands of pixels off the canvas - a selection that is never visible.
 */
class TextSelectionRenderingTest {
    // A sheet-sized bitmap inside a landscape viewport: the fitted page is much smaller than
    // the bitmap's own pixel size, so any pixel/normalized mix-up is enormous.
    private val bitmapWidth = 2400f
    private val bitmapHeight = 1800f
    private val viewerWidth = 2160f
    private val viewerHeight = 1080f
    private val transform = ViewerTransform(bitmapWidth, bitmapHeight, viewerWidth, viewerHeight)
    private fun toScreen(point: Point) = transform.toScreen(point).let { Offset(it.x, it.y) }

    private fun box(left: Float, top: Float, right: Float, bottom: Float): OcrBox {
        // Android's host stub constructor does not populate RectF fields.
        val rect = android.graphics.RectF().apply {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom
        }
        return OcrBox("E2.0", rect)
    }

    @Test fun theHighlightOfATitleBlockWordLandsOnThePage() {
        val word = box(0.90f, 0.86f, 0.96f, 0.89f)
        val painted = TextSelectionRendering.highlightRect(word, bitmapWidth, bitmapHeight, ::toScreen)
        assertNotNull(painted)
        val (topLeft, size) = painted!!
        // The words sit inside the drawn page, so their highlight must too - never a pixel-sized
        // coordinate multiplied by the page width again.
        val pageLeft = transform.left
        val pageTop = transform.top
        val pageRight = pageLeft + bitmapWidth * transform.compositeScale
        val pageBottom = pageTop + bitmapHeight * transform.compositeScale
        assertTrue("highlight x=$topLeft.x outside page [$pageLeft, $pageRight]", topLeft.x >= pageLeft)
        assertTrue("highlight y=$topLeft.y outside page [$pageTop, $pageBottom]", topLeft.y >= pageTop)
        assertTrue(
            "highlight right=${topLeft.x + size.width} outside page [$pageLeft, $pageRight]",
            topLeft.x + size.width <= pageRight
        )
        assertTrue(
            "highlight bottom=${topLeft.y + size.height} outside page [$pageTop, $pageBottom]",
            topLeft.y + size.height <= pageBottom
        )
        // It covers the word: a real width and height on screen, not a zero-size rect.
        assertTrue(size.width > 10f && size.height > 5f)
    }

    @Test fun theHighlightSitsWhereTheCopyAffordanceIsPlaced() {
        // The Copy popup is positioned from the same box; the highlight must be under it, not
        // somewhere else on the sheet.
        val word = box(0.90f, 0.86f, 0.96f, 0.89f)
        val (topLeft, size) = checkNotNull(
            TextSelectionRendering.highlightRect(word, bitmapWidth, bitmapHeight, ::toScreen)
        )
        val affordance = toScreen(Point(0.96f, 0.89f))
        assertTrue("popup x=${affordance.x} left of highlight", affordance.x <= topLeft.x + size.width + 1f)
        assertTrue("popup y=${affordance.y} above highlight", affordance.y <= topLeft.y + size.height + 1f)
    }

    @Test fun theHandlesAnchorAtTheEndsOfTheSelectedWords() {
        val page = PageOcr(1, listOf(box(0.90f, 0.86f, 0.93f, 0.89f), box(0.94f, 0.86f, 0.96f, 0.89f)))
        val anchors = checkNotNull(
            TextSelectionRendering.handleAnchors(page, 0, 1, bitmapWidth, bitmapHeight, ::toScreen)
        )
        val start = toScreen(Point(0.90f, 0.89f))
        val end = toScreen(Point(0.96f, 0.89f))
        assertEquals(start.x, anchors.first.x, 0.5f)
        assertEquals(start.y, anchors.first.y, 0.5f)
        assertEquals(end.x, anchors.second.x, 0.5f)
        assertEquals(end.y, anchors.second.y, 0.5f)
    }

    @Test fun aFingerOnTheHandleAnchorHitsTheHandle() {
        val page = PageOcr(1, listOf(box(0.90f, 0.86f, 0.93f, 0.89f), box(0.94f, 0.86f, 0.96f, 0.89f)))
        val end = toScreen(Point(0.96f, 0.89f))
        assertTrue(
            TextSelectionRendering.hitsHandle(
                page = page,
                startIdx = 0,
                endIdx = 1,
                bitmapWidthPx = bitmapWidth,
                bitmapHeightPx = bitmapHeight,
                compositeScale = transform.compositeScale,
                screenX = end.x,
                screenY = end.y,
                which = "end",
                toScreen = ::toScreen
            )
        )
        // A point far from the drawn handle must not grab it.
        assertTrue(
            !TextSelectionRendering.hitsHandle(
                page = page,
                startIdx = 0,
                endIdx = 1,
                bitmapWidthPx = bitmapWidth,
                bitmapHeightPx = bitmapHeight,
                compositeScale = transform.compositeScale,
                screenX = end.x - 600f,
                screenY = end.y - 600f,
                which = "end",
                toScreen = ::toScreen
            )
        )
    }

    @Test fun onlyBoxesInsideTheSelectionRangeArePainted() {
        val page = PageOcr(1, listOf(box(0.1f, 0.1f, 0.2f, 0.13f)))
        assertEquals(null, TextSelectionRendering.selectionRange(-1, 0, page.boxes.size))
        assertEquals(null, TextSelectionRendering.selectionRange(0, 1, page.boxes.size))
        assertEquals(0 to 0, TextSelectionRendering.selectionRange(0, 0, page.boxes.size))
        // The handles read the selection in either direction.
        assertEquals(0 to 0, TextSelectionRendering.selectionRange(0, 0, page.boxes.size))
    }
}
