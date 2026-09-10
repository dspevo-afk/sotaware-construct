package com.example.myapplication.stage8

import kotlin.math.cos
import kotlin.math.sin

/** Dimensions of a visible target surface in source/view units. */
data class AnnotationSize(val width: Float, val height: Float) {
    init {
        require(width.isFinite() && height.isFinite() && width >= 0f && height >= 0f)
    }
}
data class AnnotationPivot(val x: Float, val y: Float)
data class AnnotationPoint(val x: Float, val y: Float)

/** A visible crop/rotation-resolved rectangle. */
data class AnnotationSurface(val left: Float, val top: Float, val width: Float, val height: Float) {
    init {
        require(listOf(left, top, width, height).all { it.isFinite() })
        require(width > 0f && height > 0f)
    }
}

object AnnotationGeometry {
    /** Convert a normalized point to target coordinates. */
    fun normalizedToSurface(point: NormalizedPoint, surface: AnnotationSurface): AnnotationPoint =
        AnnotationPoint(surface.left + point.x * surface.width, surface.top + point.y * surface.height)

    /** Convert target coordinates to a bounded normalized point. */
    fun surfaceToNormalized(point: AnnotationPoint, surface: AnnotationSurface): NormalizedPoint =
        NormalizedPoint(
            ((point.x - surface.left) / surface.width).coerceIn(0f, 1f),
            ((point.y - surface.top) / surface.height).coerceIn(0f, 1f)
        )

    fun accumulateNormalizedDelta(
        current: AnnotationPoint,
        deltaX: Float,
        deltaY: Float,
        displayedWidth: Float,
        displayedHeight: Float
    ): AnnotationPoint {
        if (!listOf(current.x, current.y, deltaX, deltaY, displayedWidth, displayedHeight).all { it.isFinite() } ||
            displayedWidth <= 0f || displayedHeight <= 0f
        ) return current
        return AnnotationPoint(
            (current.x + deltaX / displayedWidth).coerceIn(0f, 1f),
            (current.y + deltaY / displayedHeight).coerceIn(0f, 1f)
        )
    }

    fun accumulateRatio(current: Float, zoom: Float, minimum: Float = .01f, maximum: Float = 1f): Float =
        if (current.isFinite() && zoom.isFinite() && zoom > 0f) (current * zoom).coerceIn(minimum, maximum) else current

    /** Current-format text sizing is ratio-only (font height / visible target height). */
    fun resolveImageNoteFontSizeRatio(currentRatio: Float): Float =
        currentRatio.takeIf { it.isFinite() && it > 0f } ?: 0f

    fun resolveImageNoteFontSizePx(
        currentRatio: Float,
        displayedImageHeight: Float
    ): Float {
        if (!displayedImageHeight.isFinite() || displayedImageHeight <= 0f) return 0f
        return resolveImageNoteFontSizeRatio(currentRatio) * displayedImageHeight
    }

    fun usableImageNoteFontSizeRatio(
        currentRatio: Float,
        minimum: Float = .01f,
        maximum: Float = .2f
    ): Float = resolveImageNoteFontSizeRatio(currentRatio)
        .takeIf { it > 0f }
        ?.coerceIn(minimum, maximum)
        ?: minimum

    fun notePivot(left: Float, top: Float, measuredWidth: Float, measuredHeight: Float): AnnotationPivot =
        AnnotationPivot(left + measuredWidth / 2f, top + measuredHeight / 2f)

    /** Converts measured screen-space text bounds into page coordinates. */
    fun pageSizeFromScreen(width: Float, height: Float, compositeScale: Float): AnnotationSize =
        if (width.isFinite() && height.isFinite() && compositeScale.isFinite() &&
            width >= 0f && height >= 0f && compositeScale > 0f
        ) AnnotationSize(width / compositeScale, height / compositeScale) else AnnotationSize(0f, 0f)

    /** Resolve normalized shape dimensions against a visible page surface. */
    fun resolvePageSize(
        pageWidth: Float,
        pageHeight: Float,
        widthRatio: Float,
        heightRatio: Float
    ): AnnotationSize = AnnotationSize(
        safeRatio(widthRatio, pageWidth),
        safeRatio(heightRatio, pageHeight)
    )

    /** Resolve normalized shape dimensions against a visible image surface. */
    fun resolveImageSize(
        imageWidth: Float,
        imageHeight: Float,
        widthRatio: Float,
        heightRatio: Float
    ): AnnotationSize = AnnotationSize(
        safeRatio(widthRatio, imageWidth),
        safeRatio(heightRatio, imageHeight)
    )

    private fun safeRatio(ratio: Float, extent: Float): Float =
        if (ratio.isFinite() && ratio >= 0f && extent.isFinite() && extent >= 0f) ratio * extent else 0f

    /** Tests a point against a rectangle rotated clockwise in target space. */
    fun rotatedRectContains(
        pointX: Float,
        pointY: Float,
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float,
        rotationDegrees: Float,
        padding: Float = 0f
    ): Boolean {
        if (!listOf(pointX, pointY, centerX, centerY, width, height, rotationDegrees, padding).all { it.isFinite() }) return false
        if (width < 0f || height < 0f || padding < 0f) return false
        val radians = Math.toRadians((-rotationDegrees).toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val dx = pointX - centerX
        val dy = pointY - centerY
        val localX = dx * c - dy * s
        val localY = dx * s + dy * c
        val halfWidth = width / 2f + padding
        val halfHeight = height / 2f + padding
        return localX in -halfWidth..halfWidth && localY in -halfHeight..halfHeight
    }

    fun rotatedNoteContains(
        pointX: Float,
        pointY: Float,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        rotationDegrees: Float,
        padding: Float = 0f
    ): Boolean = rotatedRectContains(
        pointX, pointY, left + width / 2f, top + height / 2f,
        width, height, rotationDegrees, padding
    )

    fun shapeStrokeWidth(surface: AnnotationSize, ratio: Float): Float =
        if (ratio.isFinite() && ratio >= 0f) {
            (ratio * maxOf(surface.width, surface.height)).takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        } else 0f

    fun resolveTextSize(surface: AnnotationSize, fontSizeRatio: Float): Float =
        if (fontSizeRatio.isFinite() && fontSizeRatio >= 0f) {
            (fontSizeRatio * surface.height).takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        } else 0f
}
