package com.example.myapplication

import kotlin.math.abs

private const val PDF_COORDINATE_LIMIT = 10_000_000f
private const val PDF_BOX_SPAN_LIMIT = 20_000_000f
private const val BITMAP_COORDINATE_LIMIT = 100_000_000f
private const val BITMAP_DIMENSION_LIMIT = 20_000_000f

/** Immutable PDF user-space box in the PDF bottom-left coordinate frame. */
data class PdfBox(
    val left: Float,
    val bottom: Float,
    val right: Float,
    val top: Float
) {
    init {
        requirePdfCoordinate(left, "left")
        requirePdfCoordinate(bottom, "bottom")
        requirePdfCoordinate(right, "right")
        requirePdfCoordinate(top, "top")
        require(left < right) { "left must be less than right" }
        require(bottom < top) { "bottom must be less than top" }
        require(right - left <= PDF_BOX_SPAN_LIMIT) { "box width is unreasonably large" }
        require(top - bottom <= PDF_BOX_SPAN_LIMIT) { "box height is unreasonably large" }
    }

    val width: Float get() = right - left
    val height: Float get() = top - bottom
}

/** Immutable top-left rectangle used by PDFBox adjusted coordinates. */
data class PdfTopLeftRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    init {
        requirePdfCoordinate(left, "left")
        requirePdfCoordinate(top, "top")
        requirePdfCoordinate(right, "right")
        requirePdfCoordinate(bottom, "bottom")
        require(left < right) { "left must be less than right" }
        require(top < bottom) { "top must be less than bottom" }
        require(right - left <= PDF_BOX_SPAN_LIMIT) { "rectangle width is unreasonably large" }
        require(bottom - top <= PDF_BOX_SPAN_LIMIT) { "rectangle height is unreasonably large" }
    }
}

/** Immutable normalized top-left rectangle. Every coordinate is in [0, 1]. */
data class PdfNormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "normalized rectangle must be finite"
        }
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "normalized rectangle must be within [0, 1]"
        }
        require(left < right) { "normalized left must be less than right" }
        require(top < bottom) { "normalized top must be less than bottom" }
    }
}

/** Immutable bitmap-pixel rectangle in the bitmap top-left coordinate frame. */
data class PdfBitmapRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    init {
        requireBitmapCoordinate(left, "left")
        requireBitmapCoordinate(top, "top")
        requireBitmapCoordinate(right, "right")
        requireBitmapCoordinate(bottom, "bottom")
        require(left < right) { "left must be less than right" }
        require(top < bottom) { "top must be less than bottom" }
    }
}

/**
 * Immutable visible-page geometry. Media-box coordinates are retained for
 * validation/context, while the crop box defines the displayed page.
 */
data class PdfPageGeometry(
    val mediaBox: PdfBox,
    val cropBox: PdfBox,
    val rotationDegrees: Int
) {
    init {
        require(rotationDegrees == 0 || rotationDegrees == 90 ||
            rotationDegrees == 180 || rotationDegrees == 270) {
            "page rotation must be exactly 0, 90, 180, or 270 degrees"
        }
        require(cropBox.left >= mediaBox.left && cropBox.bottom >= mediaBox.bottom &&
            cropBox.right <= mediaBox.right && cropBox.top <= mediaBox.top) {
            "crop box must be contained by the media box"
        }
    }

    val visibleWidth: Float get() = cropBox.width
    val visibleHeight: Float get() = cropBox.height
    val displayedWidth: Float
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) visibleHeight else visibleWidth
    val displayedHeight: Float
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) visibleWidth else visibleHeight
}

/**
 * Single coordinate contract for PDF, PDFBox, OCR, and bitmap rectangles.
 *
 * PDF raw rectangles are strict: they must be non-empty and fully contained
 * by the visible crop box. No out-of-crop PDF rectangle is silently clipped.
 * Bitmap rectangles use the one deliberate clipping policy in this seam:
 * [normalizeBitmapRect] intersects a valid OCR rectangle with the bitmap
 * bounds before normalizing it, and rejects rectangles with no visible area.
 */
object PdfCoordinateMapper {
    /** Creates validated geometry while retaining both page boxes. */
    fun pageGeometry(
        mediaBox: PdfBox,
        cropBox: PdfBox,
        rotationDegrees: Int
    ): PdfPageGeometry = PdfPageGeometry(mediaBox, cropBox, rotationDegrees)

    /**
     * Maps a PDF user-space rectangle (left, bottom, right, top) to the
     * normalized top-left displayed frame by transforming all four corners.
     */
    fun fromRawPdfRect(
        rawPdfRect: PdfBox,
        geometry: PdfPageGeometry
    ): PdfNormalizedRect {
        require(rawPdfRect.left >= geometry.cropBox.left &&
            rawPdfRect.bottom >= geometry.cropBox.bottom &&
            rawPdfRect.right <= geometry.cropBox.right &&
            rawPdfRect.top <= geometry.cropBox.top) {
            "raw PDF rectangle must be contained by the visible crop box"
        }

        val points = arrayOf(
            transformPoint(rawPdfRect.left, rawPdfRect.bottom, geometry),
            transformPoint(rawPdfRect.left, rawPdfRect.top, geometry),
            transformPoint(rawPdfRect.right, rawPdfRect.bottom, geometry),
            transformPoint(rawPdfRect.right, rawPdfRect.top, geometry)
        )
        val left = points.minOf { it.first }
        val top = points.minOf { it.second }
        val right = points.maxOf { it.first }
        val bottom = points.maxOf { it.second }
        return PdfNormalizedRect(
            left = left / geometry.displayedWidth,
            top = top / geometry.displayedHeight,
            right = right / geometry.displayedWidth,
            bottom = bottom / geometry.displayedHeight
        )
    }

    /** Alias that makes the PDF user-space input contract explicit at call sites. */
    fun fromRawPdfUserSpaceRect(
        rawPdfRect: PdfBox,
        geometry: PdfPageGeometry
    ): PdfNormalizedRect = fromRawPdfRect(rawPdfRect, geometry)

    /**
     * Normalizes PDFBox TextPosition coordinates after LegacyPDFStreamEngine
     * has already applied page rotation and crop-relative display positioning.
     * This adapter intentionally does not rotate a second time.
     */
    fun fromPdfBoxAlreadyDisplayedTopLeftRect(
        displayedTopLeftRect: PdfTopLeftRect,
        geometry: PdfPageGeometry
    ): PdfNormalizedRect {
        require(displayedTopLeftRect.left >= 0f && displayedTopLeftRect.top >= 0f &&
            displayedTopLeftRect.right <= geometry.displayedWidth &&
            displayedTopLeftRect.bottom <= geometry.displayedHeight) {
            "already-displayed PDFBox rectangle must be inside displayed crop bounds"
        }
        return PdfNormalizedRect(
            left = displayedTopLeftRect.left / geometry.displayedWidth,
            top = displayedTopLeftRect.top / geometry.displayedHeight,
            right = displayedTopLeftRect.right / geometry.displayedWidth,
            bottom = displayedTopLeftRect.bottom / geometry.displayedHeight
        )
    }

    /**
     * Normalizes PDFBox getXDirAdj/getYDirAdj coordinates. Those values are
     * unrotated, crop-relative, top-left coordinates, so this adapter first
     * converts them to one raw PDF user-space rectangle and then invokes the
     * canonical raw adapter exactly once.
     */
    fun fromPdfBoxUnrotatedTopLeftRect(
        unrotatedTopLeftRect: PdfTopLeftRect,
        geometry: PdfPageGeometry
    ): PdfNormalizedRect {
        require(unrotatedTopLeftRect.left >= 0f && unrotatedTopLeftRect.top >= 0f &&
            unrotatedTopLeftRect.right <= geometry.visibleWidth &&
            unrotatedTopLeftRect.bottom <= geometry.visibleHeight) {
            "unrotated PDFBox rectangle must be inside crop bounds"
        }
        val rawPdfRect = PdfBox(
            left = geometry.cropBox.left + unrotatedTopLeftRect.left,
            bottom = geometry.cropBox.bottom + geometry.visibleHeight - unrotatedTopLeftRect.bottom,
            right = geometry.cropBox.left + unrotatedTopLeftRect.right,
            top = geometry.cropBox.bottom + geometry.visibleHeight - unrotatedTopLeftRect.top
        )
        return fromRawPdfRect(rawPdfRect, geometry)
    }

    /** Normalizes a bitmap/OCR rectangle, clipping only to bitmap bounds. */
    fun normalizeBitmapRect(
        bitmapRect: PdfBitmapRect,
        bitmapWidthPx: Int,
        bitmapHeightPx: Int
    ): PdfNormalizedRect = normalizeBitmapRect(
        bitmapRect = bitmapRect,
        bitmapWidthPx = bitmapWidthPx.toFloat(),
        bitmapHeightPx = bitmapHeightPx.toFloat()
    )

    /** Normalizes a bitmap/OCR rectangle, clipping only to bitmap bounds. */
    fun normalizeBitmapRect(
        bitmapRect: PdfBitmapRect,
        bitmapWidthPx: Float,
        bitmapHeightPx: Float
    ): PdfNormalizedRect {
        requireBitmapDimension(bitmapWidthPx, "bitmap width")
        requireBitmapDimension(bitmapHeightPx, "bitmap height")
        val left = maxOf(0f, bitmapRect.left)
        val top = maxOf(0f, bitmapRect.top)
        val right = minOf(bitmapWidthPx, bitmapRect.right)
        val bottom = minOf(bitmapHeightPx, bitmapRect.bottom)
        require(left < right && top < bottom) {
            "bitmap rectangle has no visible intersection with bitmap bounds"
        }
        return PdfNormalizedRect(
            left = left / bitmapWidthPx,
            top = top / bitmapHeightPx,
            right = right / bitmapWidthPx,
            bottom = bottom / bitmapHeightPx
        )
    }

    /** Inverse of bitmap normalization, returning a fresh pixel rectangle. */
    fun normalizedRectToBitmapRect(
        normalizedRect: PdfNormalizedRect,
        bitmapWidthPx: Int,
        bitmapHeightPx: Int
    ): PdfBitmapRect = normalizedRectToBitmapRect(
        normalizedRect = normalizedRect,
        bitmapWidthPx = bitmapWidthPx.toFloat(),
        bitmapHeightPx = bitmapHeightPx.toFloat()
    )

    /** Inverse of bitmap normalization, returning a fresh pixel rectangle. */
    fun normalizedRectToBitmapRect(
        normalizedRect: PdfNormalizedRect,
        bitmapWidthPx: Float,
        bitmapHeightPx: Float
    ): PdfBitmapRect {
        requireBitmapDimension(bitmapWidthPx, "bitmap width")
        requireBitmapDimension(bitmapHeightPx, "bitmap height")
        return PdfBitmapRect(
            left = normalizedRect.left * bitmapWidthPx,
            top = normalizedRect.top * bitmapHeightPx,
            right = normalizedRect.right * bitmapWidthPx,
            bottom = normalizedRect.bottom * bitmapHeightPx
        )
    }

    fun normalizedRectOrNull(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): PdfNormalizedRect? = try {
        PdfNormalizedRect(left, top, right, bottom)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun fromPdfBoxAlreadyDisplayedTopLeftRectOrNull(
        displayedTopLeftRect: PdfTopLeftRect,
        geometry: PdfPageGeometry
    ): PdfNormalizedRect? = try {
        fromPdfBoxAlreadyDisplayedTopLeftRect(displayedTopLeftRect, geometry)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun fromPdfBoxUnrotatedTopLeftRectOrNull(
        unrotatedTopLeftRect: PdfTopLeftRect,
        geometry: PdfPageGeometry
    ): PdfNormalizedRect? = try {
        fromPdfBoxUnrotatedTopLeftRect(unrotatedTopLeftRect, geometry)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun transformPoint(
        x: Float,
        y: Float,
        geometry: PdfPageGeometry
    ): Pair<Float, Float> = when (geometry.rotationDegrees) {
        0 -> Pair(x - geometry.cropBox.left, geometry.cropBox.top - y)
        90 -> Pair(y - geometry.cropBox.bottom, x - geometry.cropBox.left)
        180 -> Pair(geometry.cropBox.right - x, y - geometry.cropBox.bottom)
        270 -> Pair(geometry.cropBox.top - y, geometry.cropBox.right - x)
        else -> error("validated geometry has an unsupported rotation")
    }
}

private fun requirePdfCoordinate(value: Float, name: String) {
    require(value.isFinite()) { "$name must be finite" }
    require(abs(value) <= PDF_COORDINATE_LIMIT) { "$name is unreasonably large" }
}

private fun requireBitmapCoordinate(value: Float, name: String) {
    require(value.isFinite()) { "$name must be finite" }
    require(abs(value) <= BITMAP_COORDINATE_LIMIT) { "$name is unreasonably large" }
}

private fun requireBitmapDimension(value: Float, name: String) {
    require(value.isFinite() && value > 0f) { "$name must be finite and positive" }
    require(value <= BITMAP_DIMENSION_LIMIT) { "$name is unreasonably large" }
}
