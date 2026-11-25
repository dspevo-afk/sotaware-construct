package com.example.myapplication

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.text.TextPaint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas

/**
 * The round marker a sub-drawing leaves on its page: a white circle with a black
 * outline, cut in half by a black horizontal line through its middle, the sheet's
 * number in the top half and the room name typed for the crop in the bottom half -
 * all black text on white. The badge carries no sub-drawing colour; only the crop's
 * rectangle keeps that. A selected badge is ringed in cyan.
 *
 * One implementation serves both surfaces: [draw] paints on an Android canvas
 * (the PDF page export) and [drawOn] paints on the viewer's Compose canvas, which
 * reaches the same code through its native canvas. Both take a radius in their own
 * pixel space, so the badge belongs to the drawing: it grows and shrinks with the
 * zoom exactly like a photo pin does. Each half's text is laid out inside the
 * square inscribed in its semicircle and shrunk to fit, so at every size - down to
 * a small badge on a distant sheet - the sheet number and name stay inside the
 * circle, with a long name cut by an ellipsis.
 */
object SubDrawingBadge {
    /** Longest name drawn on the badge; longer input is cut off with an ellipsis. */
    const val MAX_BADGE_NAME_CHARS: Int = 18

    /** Largest text size, as a fraction of the badge radius. */
    const val MAX_TEXT_TO_RADIUS: Float = 0.55f

    /** Smallest legible text size, as a fraction of the badge radius. */
    const val MIN_TEXT_TO_RADIUS: Float = 0.19f

    /** Side of the square inscribed in a semicircle, as a fraction of its radius. */
    const val INSCRIBED_SQUARE_TO_RADIUS: Float = 0.894f

    /** Cyan highlight ring drawn around a selected badge. */
    val SELECTED_COLOR: Int = 0xFF00BCD4.toInt()

    /** The sheet number over the room name, cropped to what the badge can show. */
    fun nameForBadge(name: String?): String {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
        if (trimmed.length <= MAX_BADGE_NAME_CHARS) return trimmed
        return trimmed.take(MAX_BADGE_NAME_CHARS - 1) + "…"
    }

    /** Draws one badge on the viewer's Compose canvas at [center]. */
    fun DrawScope.drawOn(
        center: Offset,
        radiusPx: Float,
        topText: String,
        bottomText: String,
        selected: Boolean = false
    ) {
        if (!center.x.isFinite() || !center.y.isFinite() || !radiusPx.isFinite() || radiusPx <= 0f) return
        draw(drawContext.canvas.nativeCanvas, PointF(center.x, center.y), radiusPx, topText, bottomText, selected)
    }

    /** Draws one badge on an Android [canvas] (export); radius is canvas pixels. */
    fun draw(
        canvas: Canvas,
        center: PointF,
        radiusPx: Float,
        topText: String,
        bottomText: String,
        selected: Boolean = false
    ) {
        if (!center.x.isFinite() || !center.y.isFinite() || !radiusPx.isFinite() || radiusPx <= 0f) return
        val edge = Color.BLACK
        val stroke = (radiusPx * 0.07f).coerceAtLeast(1f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center.x, center.y, radiusPx, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = edge
        canvas.drawCircle(center.x, center.y, radiusPx, paint)
        // The horizontal cut through the middle, ending at the circle's edge.
        val halfSpan = radiusPx - stroke / 2f
        canvas.drawLine(center.x - halfSpan, center.y, center.x + halfSpan, center.y, paint)

        drawHalf(canvas, center, radiusPx, topText, above = true)
        drawHalf(canvas, center, radiusPx, nameForBadge(bottomText), above = false)
        if (selected) {
            // The same cyan highlight every other selected annotation gets.
            paint.color = SELECTED_COLOR
            paint.strokeWidth = (stroke * 1.8f).coerceAtLeast(2f)
            canvas.drawCircle(center.x, center.y, radiusPx + paint.strokeWidth, paint)
        }
    }

    private fun drawHalf(
        canvas: android.graphics.Canvas,
        center: PointF,
        radiusPx: Float,
        text: String,
        above: Boolean
    ) {
        if (text.isEmpty()) return
        val squareSide = radiusPx * INSCRIBED_SQUARE_TO_RADIUS
        val boxWidth = squareSide * 0.9f
        val boxHeight = squareSide * 0.5f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        // The largest size that fits this half of the badge, capped so a short label
        // ("E1") stays a label: measure once, scale to the box, then make sure.
        val minTextPx = (radiusPx * MIN_TEXT_TO_RADIUS).coerceAtLeast(4f)
        paint.textSize = 100f
        var bounds = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
        val fit = if (bounds.width() > 0 && bounds.height() > 0)
            100f * minOf(boxWidth / bounds.width(), boxHeight / bounds.height()) else minTextPx
        var textSizePx = fit.coerceIn(minTextPx, radiusPx * MAX_TEXT_TO_RADIUS)
        paint.textSize = textSizePx
        bounds = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
        while (textSizePx > minTextPx && (bounds.width() > boxWidth || bounds.height() > boxHeight)) {
            textSizePx *= 0.85f
            paint.textSize = textSizePx
            bounds = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
        }
        val halfCenterY = center.y + (if (above) -1f else 1f) * radiusPx / 2f
        // A CENTER-aligned drawText call anchors the baseline, so the baseline is
        // placed to centre the measured glyph box in this half of the badge.
        canvas.drawText(text, center.x, halfCenterY - (bounds.top + bounds.bottom) / 2f, paint)
    }
}
