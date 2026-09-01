package com.example.myapplication

import android.graphics.Rect
import android.graphics.RectF

/** Android boundary adapters; the coordinate contract itself remains JVM-pure. */
fun PdfNormalizedRect.toAndroidRectF(): RectF = freshRectF(left, top, right, bottom)

fun PdfCoordinateMapper.normalizeBitmapRect(
    bitmapRect: Rect,
    bitmapWidthPx: Int,
    bitmapHeightPx: Int
): PdfNormalizedRect? = try {
    normalizeBitmapRect(
        PdfBitmapRect(
            left = bitmapRect.left.toFloat(),
            top = bitmapRect.top.toFloat(),
            right = bitmapRect.right.toFloat(),
            bottom = bitmapRect.bottom.toFloat()
        ),
        bitmapWidthPx,
        bitmapHeightPx
    )
} catch (_: IllegalArgumentException) {
    null
}

fun PdfCoordinateMapper.normalizeBitmapRect(
    bitmapRect: RectF,
    bitmapWidthPx: Float,
    bitmapHeightPx: Float
): PdfNormalizedRect? = try {
    normalizeBitmapRect(
        PdfBitmapRect(bitmapRect.left, bitmapRect.top, bitmapRect.right, bitmapRect.bottom),
        bitmapWidthPx,
        bitmapHeightPx
    )
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Returns a fresh RectF for an OcrBox/search rectangle whose producer already
 * owns the normalized-coordinate contract.  This is a copy boundary rather
 * than a second mapping authority; validation happens when the rectangle is
 * converted to bitmap coordinates through the core mapper.
 */
fun PdfCoordinateMapper.copyNormalizedRectOrNull(rect: RectF): RectF {
    return freshRectF(rect.left, rect.top, rect.right, rect.bottom)
}

/** Returns fresh bitmap coordinates for a normalized OCR/search rectangle. */
fun PdfCoordinateMapper.normalizedRectToBitmapRectOrNull(
    normalizedRect: RectF,
    bitmapWidthPx: Float,
    bitmapHeightPx: Float
): RectF? = try {
    val normalized = PdfNormalizedRect(
        normalizedRect.left,
        normalizedRect.top,
        normalizedRect.right,
        normalizedRect.bottom
    )
    normalizedRectToBitmapRect(normalized, bitmapWidthPx, bitmapHeightPx).let { pixelRect ->
        freshRectF(pixelRect.left, pixelRect.top, pixelRect.right, pixelRect.bottom)
    }
} catch (_: IllegalArgumentException) {
    null
}

private fun freshRectF(left: Float, top: Float, right: Float, bottom: Float): RectF =
    RectF(left, top, right, bottom).also { rect ->
        // Android's local-test stubs may not initialize constructor fields.
        // Explicit assignment is harmless on device and keeps every returned
        // boundary independent from mutable source-rectangle state.
        rect.left = left
        rect.top = top
        rect.right = right
        rect.bottom = bottom
    }
