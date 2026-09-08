package com.example.myapplication.stage8

import kotlin.math.cos
import kotlin.math.sin

/** Dimensions used by rendering and hit testing. */
data class AnnotationSize(val width: Float, val height: Float) {
    init {
        require(width.isFinite() && height.isFinite() && width >= 0f && height >= 0f)
    }
}

data class AnnotationPivot(val x: Float, val y: Float)
data class AnnotationPoint(val x: Float, val y: Float)

object AnnotationGeometry {
    /**
     * Legacy image notes stored an absolute text size based on the original
     * 800px image viewport.  Keep that reference explicit so rendering,
     * pinch migration, and export resolve the same persisted value.
     */
    const val LEGACY_IMAGE_NOTE_REFERENCE_HEIGHT_PX = 800f

    fun accumulateNormalizedDelta(current: AnnotationPoint, deltaX: Float, deltaY: Float, displayedWidth: Float, displayedHeight: Float): AnnotationPoint {
        if (!listOf(current.x, current.y, deltaX, deltaY, displayedWidth, displayedHeight).all { it.isFinite() } || displayedWidth <= 0f || displayedHeight <= 0f) return current
        return AnnotationPoint(
            (current.x + deltaX / displayedWidth).coerceIn(0f, 1f),
            (current.y + deltaY / displayedHeight).coerceIn(0f, 1f)
        )
    }

    fun accumulateRatio(current: Float, zoom: Float, minimum: Float = .01f, maximum: Float = 1f): Float =
        if (current.isFinite() && zoom.isFinite() && zoom > 0f) (current * zoom).coerceIn(minimum, maximum) else current

    /** Resolves the persisted image-note size to the canonical image-height ratio. */
    fun resolveImageNoteFontSizeRatio(
        currentRatio: Float,
        legacyFontSize: Float,
        legacyReferenceHeight: Float = LEGACY_IMAGE_NOTE_REFERENCE_HEIGHT_PX
    ): Float = when {
        currentRatio.isFinite() && currentRatio > 0f -> currentRatio
        legacyFontSize.isFinite() && legacyFontSize > 0f &&
            legacyReferenceHeight.isFinite() && legacyReferenceHeight > 0f ->
            legacyFontSize / legacyReferenceHeight
        else -> 0f
    }

    /** Resolves the canonical ratio to pixels in the current displayed image. */
    fun resolveImageNoteFontSizePx(
        currentRatio: Float,
        legacyFontSize: Float,
        displayedImageHeight: Float,
        legacyReferenceHeight: Float = LEGACY_IMAGE_NOTE_REFERENCE_HEIGHT_PX
    ): Float {
        if (!displayedImageHeight.isFinite() || displayedImageHeight <= 0f) return 0f
        return resolveImageNoteFontSizeRatio(
            currentRatio, legacyFontSize, legacyReferenceHeight
        ) * displayedImageHeight
    }

    /** Converts legacy absolute image-note sizing into the bounded ratio used by pinch edits. */
    fun usableImageNoteFontSizeRatio(
        currentRatio: Float,
        legacyFontSize: Float,
        density: Float,
        displayedImageHeight: Float,
        minimum: Float = .01f,
        maximum: Float = .2f
    ): Float {
        // density/display height remain part of this compatibility signature;
        // the canonical migration deliberately does not vary with the device.
        @Suppress("UNUSED_VARIABLE")
        val ignoredLegacyDensity = density
        @Suppress("UNUSED_VARIABLE")
        val ignoredDisplayedHeight = displayedImageHeight
        return resolveImageNoteFontSizeRatio(currentRatio, legacyFontSize)
            .takeIf { it > 0f }
            ?.coerceIn(minimum, maximum)
            ?: minimum
    }

    fun notePivot(left: Float, top: Float, measuredWidth: Float, measuredHeight: Float): AnnotationPivot =
        AnnotationPivot(left + measuredWidth / 2f, top + measuredHeight / 2f)

    /** Converts measured screen-space text bounds into the page coordinate space. */
    fun pageSizeFromScreen(width: Float, height: Float, compositeScale: Float): AnnotationSize =
        if (width.isFinite() && height.isFinite() && compositeScale.isFinite() &&
            width >= 0f && height >= 0f && compositeScale > 0f
        ) AnnotationSize(width / compositeScale, height / compositeScale)
        else AnnotationSize(0f, 0f)
    /** Ratio fields win when valid; old absolute page dimensions remain usable. */
    fun resolvePageSize(
        pageWidth: Float,
        pageHeight: Float,
        widthRatio: Float,
        heightRatio: Float,
        legacyWidth: Float = pageWidth,
        legacyHeight: Float = pageHeight
    ): AnnotationSize =
        AnnotationSize(
            safeAxis(if (widthRatio > 0f && widthRatio.isFinite() && pageWidth.isFinite() && pageWidth > 0f) widthRatio * pageWidth else legacyWidth),
            safeAxis(if (heightRatio > 0f && heightRatio.isFinite() && pageHeight.isFinite() && pageHeight > 0f) heightRatio * pageHeight else legacyHeight)
        )

    /** Image dimensions use positive ratios first, then normalized legacy dimensions. */
    fun resolveImageSize(
        imageWidth: Float,
        imageHeight: Float,
        widthRatio: Float,
        heightRatio: Float,
        legacyWidth: Float = imageWidth,
        legacyHeight: Float = imageHeight
    ): AnnotationSize = AnnotationSize(
        resolveImageAxis(imageWidth, widthRatio, legacyWidth),
        resolveImageAxis(imageHeight, heightRatio, legacyHeight)
    )

    private fun resolveImageAxis(base: Float, ratio: Float, legacy: Float): Float = safeAxis(when {
        ratio > 0f && ratio.isFinite() && base.isFinite() && base >= 0f -> ratio * base
        legacy in 0f..1f && base.isFinite() && base >= 0f -> legacy * base
        legacy.isFinite() && legacy >= 0f -> legacy
        else -> 0f
    })

    private fun safeAxis(value: Float) = if (value.isFinite() && value >= 0f) value else 0f

    /**
     * Tests a point against a rectangle rotated clockwise in screen space.
     * The inverse rotation maps the point into the unrotated rectangle.
     */
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
        if (width < 0f || height < 0f) return false
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

    /** A top-left anchored note rotated around its measured center. */
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
}
