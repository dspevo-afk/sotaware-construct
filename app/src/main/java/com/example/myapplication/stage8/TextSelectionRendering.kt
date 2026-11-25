package com.example.myapplication.stage8

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.myapplication.OcrBox
import com.example.myapplication.PageOcr
import com.example.myapplication.PdfCoordinateMapper
import com.example.myapplication.Point
import com.example.myapplication.normalizedRectToBitmapRectOrNull
import kotlin.math.abs
import kotlin.math.hypot

/**
 * What a long-press text selection looks like on the page, and where its handles are.
 *
 * The viewer's canvas, the pointer gestures that drag the handles, and the tests all read
 * these rules, so the blue highlight, the teardrop handles, and the hit targets cannot
 * drift apart - a selected word is highlighted exactly where a finger can grab it.
 */
object TextSelectionRendering {
    /** Highlight and handle colour: the same blue the Copy affordance uses. */
    val COLOR: Color = Color(0xFF2196F3)

    /** The highlight covers the box a little taller than the glyphs, like web selection. */
    private const val FILL_HEIGHT_EXPANSION = 1.2f
    private const val HANDLE_RADIUS_MIN = 6f
    private const val HANDLE_RADIUS_MAX = 20f
    private const val STEM_HEIGHT_RATIO = 0.8f

    /**
     * A word box of [page] in bitmap pixels, or null when that box is not a usable
     * rectangle on this bitmap. Every painter and hit test starts from this one mapping.
     */
    fun boxRectInBitmap(box: OcrBox, bitmapWidthPx: Float, bitmapHeightPx: Float) =
        PdfCoordinateMapper.normalizedRectToBitmapRectOrNull(box.rectN, bitmapWidthPx, bitmapHeightPx)

    /**
     * The viewer transform measures the page in normalized coordinates (0..1), so every
     * painter and hit test hands it a page point, never a bitmap pixel: a pixel fed straight
     * in is multiplied by the page width again and lands thousands of pixels off the canvas,
     * which is what made a selected word invisible.
     */
    fun pagePoint(bitmapXpx: Float, bitmapYpx: Float, bitmapWidthPx: Float, bitmapHeightPx: Float): Point =
        Point(bitmapXpx / bitmapWidthPx, bitmapYpx / bitmapHeightPx)

    /** The screen rectangle that highlights one selected word box. */
    fun highlightRect(
        box: OcrBox,
        bitmapWidthPx: Float,
        bitmapHeightPx: Float,
        toScreen: (Point) -> Offset
    ): Pair<Offset, Size>? {
        val rect = boxRectInBitmap(box, bitmapWidthPx, bitmapHeightPx) ?: return null
        val centerY = (rect.top + rect.bottom) / 2f
        val halfH = (rect.bottom - rect.top) / 2f * FILL_HEIGHT_EXPANSION
        val topLeft = toScreen(pagePoint(rect.left, centerY - halfH, bitmapWidthPx, bitmapHeightPx))
        val bottomRight = toScreen(pagePoint(rect.right, centerY + halfH, bitmapWidthPx, bitmapHeightPx))
        return Pair(
            Offset(minOf(topLeft.x, bottomRight.x), minOf(topLeft.y, bottomRight.y)),
            Size(abs(bottomRight.x - topLeft.x), abs(bottomRight.y - topLeft.y))
        )
    }

    /** The first/last selected box indices, ordered the way the handles read them. */
    fun selectionRange(startIdx: Int, endIdx: Int, boxCount: Int): Pair<Int, Int>? {
        val start = minOf(startIdx, endIdx)
        val end = maxOf(startIdx, endIdx)
        if (start < 0 || end >= boxCount) return null
        return start to end
    }

    /** Handle anchors in screen pixels: the ends of the selected words' baselines. */
    fun handleAnchors(
        page: PageOcr,
        startIdx: Int,
        endIdx: Int,
        bitmapWidthPx: Float,
        bitmapHeightPx: Float,
        toScreen: (Point) -> Offset
    ): Pair<Offset, Offset>? {
        val range = selectionRange(startIdx, endIdx, page.boxes.size) ?: return null
        val startRect = boxRectInBitmap(page.boxes[range.first], bitmapWidthPx, bitmapHeightPx)
            ?: return null
        val endRect = boxRectInBitmap(page.boxes[range.second], bitmapWidthPx, bitmapHeightPx)
            ?: return null
        return toScreen(pagePoint(startRect.left, startRect.bottom, bitmapWidthPx, bitmapHeightPx)) to
            toScreen(pagePoint(endRect.right, endRect.bottom, bitmapWidthPx, bitmapHeightPx))
    }

    /**
     * The screen radius a handle answers to. Long-press admission and the handle drag both
     * use it, so a finger that grabbed a word at selection time keeps grabbing it afterwards.
     */
    fun handleHitRadiusPx(boxHeightPx: Float, compositeScale: Float): Float =
        (boxHeightPx * compositeScale * 2.5f).coerceIn(50f, 120f)

    /** Whether a pointer lands on the start/end handle of the current selection. */
    fun hitsHandle(
        page: PageOcr,
        startIdx: Int,
        endIdx: Int,
        bitmapWidthPx: Float,
        bitmapHeightPx: Float,
        compositeScale: Float,
        screenX: Float,
        screenY: Float,
        which: String,
        toScreen: (Point) -> Offset
    ): Boolean {
        val range = selectionRange(startIdx, endIdx, page.boxes.size) ?: return false
        val index = if (which == "start") range.first else range.second
        val rect = boxRectInBitmap(page.boxes[index], bitmapWidthPx, bitmapHeightPx) ?: return false
        val anchor = if (which == "start") toScreen(pagePoint(rect.left, rect.bottom, bitmapWidthPx, bitmapHeightPx))
        else toScreen(pagePoint(rect.right, rect.bottom, bitmapWidthPx, bitmapHeightPx))
        val radius = handleHitRadiusPx(rect.bottom - rect.top, compositeScale)
        return hypot(anchor.x - screenX, anchor.y - screenY) < radius
    }

    /**
     * Paints the blue highlight behind every selected word box: a fill plus its outline, so
     * the selection stays visible on top of dense title-block line work.
     */
    fun DrawScope.drawSelectionHighlights(
        boxes: List<OcrBox>,
        bitmapWidthPx: Float,
        bitmapHeightPx: Float,
        toScreen: (Point) -> Offset
    ) {
        for (box in boxes) {
            val (topLeft, size) = highlightRect(box, bitmapWidthPx, bitmapHeightPx, toScreen)
                ?: continue
            drawRect(color = COLOR.copy(alpha = 0.45f), topLeft = topLeft, size = size)
            drawRect(color = COLOR, topLeft = topLeft, size = size, style = Stroke(width = 2f))
        }
    }

    /** Paints the teardrop handles at both ends of the selection. */
    fun DrawScope.drawSelectionHandles(
        page: PageOcr,
        startIdx: Int,
        endIdx: Int,
        bitmapWidthPx: Float,
        bitmapHeightPx: Float,
        compositeScale: Float,
        toScreen: (Point) -> Offset
    ) {
        val range = selectionRange(startIdx, endIdx, page.boxes.size) ?: return
        val startRect = boxRectInBitmap(page.boxes[range.first], bitmapWidthPx, bitmapHeightPx)
            ?: return
        val endRect = boxRectInBitmap(page.boxes[range.second], bitmapWidthPx, bitmapHeightPx)
            ?: return
        drawHandle(
            anchor = toScreen(pagePoint(startRect.left, startRect.bottom, bitmapWidthPx, bitmapHeightPx)),
            boxHeightPx = (startRect.bottom - startRect.top) * compositeScale,
            stemUp = true
        )
        drawHandle(
            anchor = toScreen(pagePoint(endRect.right, endRect.bottom, bitmapWidthPx, bitmapHeightPx)),
            boxHeightPx = (endRect.bottom - endRect.top) * compositeScale,
            stemUp = false
        )
    }

    private fun DrawScope.drawHandle(anchor: Offset, boxHeightPx: Float, stemUp: Boolean) {
        val radius = (boxHeightPx * 0.5f).coerceIn(HANDLE_RADIUS_MIN, HANDLE_RADIUS_MAX)
        val stemHeight = boxHeightPx * STEM_HEIGHT_RATIO
        val center = anchor + Offset(0f, radius * 0.5f)
        drawLine(
            color = COLOR,
            start = anchor,
            end = if (stemUp) anchor - Offset(0f, stemHeight) else anchor + Offset(0f, stemHeight),
            strokeWidth = (radius * 0.25f).coerceIn(2f, 4f)
        )
        drawCircle(color = COLOR, radius = radius, center = center)
        drawCircle(color = Color.White, radius = radius * 0.35f, center = center)
    }
}
