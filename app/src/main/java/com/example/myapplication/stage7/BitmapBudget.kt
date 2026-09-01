package com.example.myapplication.stage7

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val ARGB_8888_BYTES_PER_PIXEL: Long = 4L

/**
 * A checked ARGB_8888 allocation size.  Instances are only created by the
 * policy below, so callers can use [pixels] and [bytes] without repeating
 * integer arithmetic at an Android allocation site.
 */
data class BitmapSizePlan(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "bitmap dimensions must be positive" }
        val pixelCount = width.toLong() * height.toLong()
        require(pixelCount <= Long.MAX_VALUE / ARGB_8888_BYTES_PER_PIXEL) {
            "bitmap byte count overflows Long"
        }
    }

    val pixels: Long
        get() = width.toLong() * height.toLong()

    val bytes: Long
        get() = pixels * ARGB_8888_BYTES_PER_PIXEL
}

/** The two live surfaces which can coexist while an EXIF transform runs. */
data class BitmapTransformPlan(
    val source: BitmapSizePlan,
    val transformed: BitmapSizePlan,
    val peakBytes: Long
)

/** A planned bitmap whose platform-reported allocation bytes were checked. */
data class ActualBitmapAllocationPlan(
    val dimensions: BitmapSizePlan,
    val allocationBytes: Long
)

/** Decode target and the bounded integer passed to BitmapFactory.Options. */
data class PhotoDecodePlan(
    val target: BitmapSizePlan,
    val inSampleSize: Int
)

/** A finite, positive Compose display viewport measured in physical pixels. */
data class DisplayViewport(val width: Int, val height: Int)

/** Pure EXIF orientation geometry used before any transform allocation. */
data class ExifOrientationPlan(
    val transformedWidth: Int,
    val transformedHeight: Int,
    val swapsDimensions: Boolean,
    val requiresBitmapTransform: Boolean
)

/**
 * Single authority for Stage 7 bitmap dimensions and memory checks.
 *
 * The limits are intentionally conservative for phone-sized viewers:
 * 8,000,000 pixels and 32 MiB for one ARGB_8888 bitmap, with an 8,192-pixel
 * edge cap for unusually wide construction drawings.  EXIF transforms may
 * temporarily retain the source and transformed surfaces, so their combined
 * peak is limited to 64 MiB.  The viewport quality multiplier renders at up
 * to 2x the measured viewport for readable line work without following
 * display-only pinch zoom into new allocations.
 */
object BitmapBudgetPolicy {
    const val BYTES_PER_ARGB_8888_PIXEL: Long = 4L
    const val MAX_BITMAP_DIMENSION_PX: Int = 8_192
    const val MAX_BITMAP_PIXELS: Long = 8_000_000L
    const val MAX_BITMAP_BYTES: Long = 32L * 1024L * 1024L
    const val MAX_TRANSFORM_PEAK_BYTES: Long = 64L * 1024L * 1024L
    const val MAX_TRANSFORM_PEAK_PIXELS: Long =
        MAX_TRANSFORM_PEAK_BYTES / BYTES_PER_ARGB_8888_PIXEL
    const val VIEWPORT_QUALITY_MULTIPLIER: Double = 2.0
    const val PHOTO_QUALITY_MULTIPLIER: Double = 2.0
    const val THUMBNAIL_TARGET_WIDTH_PX: Int = 600
    const val FALLBACK_PDF_SCALE_FACTOR: Int = 4

    /** Rejects Compose's unbounded sentinel as well as absent/invalid sizes. */
    fun displayViewport(widthPx: Int?, heightPx: Int?): DisplayViewport? {
        if (widthPx == null || heightPx == null ||
            widthPx <= 0 || heightPx <= 0 ||
            widthPx == Int.MAX_VALUE || heightPx == Int.MAX_VALUE
        ) {
            return null
        }
        return DisplayViewport(widthPx, heightPx)
    }

    /**
     * Plans a source image after either a requested scale or a viewport fit.
     *
     * Null viewport dimensions mean "use [scaleFactor]".  A partially
     * specified viewport is rejected.  All floating-point inputs are checked
     * before conversion, and final pixels/bytes are accounted for with Longs.
     * [maxPixels] and [maxBytes] are injectable only to make the byte/pixel
     * boundaries independently testable; production callers use the defaults.
     */
    fun plan(
        sourceWidthPx: Double,
        sourceHeightPx: Double,
        scaleFactor: Double = 1.0,
        viewportWidthPx: Double? = null,
        viewportHeightPx: Double? = null,
        qualityMultiplier: Double = 1.0,
        maxDimensionPx: Int = MAX_BITMAP_DIMENSION_PX,
        maxPixels: Long = MAX_BITMAP_PIXELS,
        maxBytes: Long = MAX_BITMAP_BYTES
    ): BitmapSizePlan? {
        if (!isFinitePositive(sourceWidthPx) || !isFinitePositive(sourceHeightPx)) return null
        if (!isFinitePositive(scaleFactor) || !isFinitePositive(qualityMultiplier)) return null

        val hasViewport = viewportWidthPx != null || viewportHeightPx != null
        if (hasViewport &&
            (viewportWidthPx == null || viewportHeightPx == null ||
                !isFinitePositive(viewportWidthPx) || !isFinitePositive(viewportHeightPx))
        ) {
            return null
        }

        val requested = if (hasViewport) {
            val targetWidth = safeMultiply(viewportWidthPx!!, qualityMultiplier) ?: return null
            val targetHeight = safeMultiply(viewportHeightPx!!, qualityMultiplier) ?: return null
            val fitScale = min(targetWidth / sourceWidthPx, targetHeight / sourceHeightPx)
            if (!isFinitePositive(fitScale)) return null
            val fittedWidth = safeMultiply(sourceWidthPx, fitScale) ?: return null
            val fittedHeight = safeMultiply(sourceHeightPx, fitScale) ?: return null
            fittedWidth to fittedHeight
        } else {
            val scaledWidth = safeMultiply(sourceWidthPx, scaleFactor) ?: return null
            val scaledHeight = safeMultiply(sourceHeightPx, scaleFactor) ?: return null
            scaledWidth to scaledHeight
        }

        return reduceToBudget(
            requestedWidthPx = requested.first,
            requestedHeightPx = requested.second,
            maxDimensionPx = maxDimensionPx,
            maxPixels = maxPixels,
            maxBytes = maxBytes
        )
    }

    /** PDF display/OCR plan; viewport rendering takes precedence over scale. */
    fun pdfRenderPlan(
        pageWidthPx: Int,
        pageHeightPx: Int,
        scaleFactor: Int = FALLBACK_PDF_SCALE_FACTOR,
        viewportWidthPx: Int? = null,
        viewportHeightPx: Int? = null,
        viewportQualityMultiplier: Double = VIEWPORT_QUALITY_MULTIPLIER,
        maxDimensionPx: Int = MAX_BITMAP_DIMENSION_PX
    ): BitmapSizePlan? {
        if (scaleFactor <= 0) return null
        return plan(
            sourceWidthPx = pageWidthPx.toDouble(),
            sourceHeightPx = pageHeightPx.toDouble(),
            scaleFactor = scaleFactor.toDouble(),
            viewportWidthPx = viewportWidthPx?.toDouble(),
            viewportHeightPx = viewportHeightPx?.toDouble(),
            qualityMultiplier = viewportQualityMultiplier,
            maxDimensionPx = maxDimensionPx
        )
    }

    /** A thumbnail plan retaining the existing 600-pixel target width. */
    fun pdfThumbnailPlan(
        pageWidthPx: Int,
        pageHeightPx: Int,
        targetWidthPx: Int = THUMBNAIL_TARGET_WIDTH_PX
    ): BitmapSizePlan? {
        if (pageWidthPx <= 0 || pageHeightPx <= 0 || targetWidthPx <= 0) return null
        val targetHeightPx = targetWidthPx.toDouble() * pageHeightPx.toDouble() / pageWidthPx.toDouble()
        if (!isFinitePositive(targetHeightPx)) return null
        return plan(
            sourceWidthPx = pageWidthPx.toDouble(),
            sourceHeightPx = pageHeightPx.toDouble(),
            viewportWidthPx = targetWidthPx.toDouble(),
            viewportHeightPx = targetHeightPx,
            qualityMultiplier = 1.0
        )
    }

    /**
     * Computes a bounded photo decode and a decoder-safe power-of-two sample.
     * The selected sample is rounded up from the required integer sample so a
     * decoder which only honors power-of-two reductions cannot round down and
     * produce a bitmap larger than the checked target.
     */
    fun photoDecodePlan(
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        viewportWidthPx: Int? = null,
        viewportHeightPx: Int? = null,
        qualityMultiplier: Double = PHOTO_QUALITY_MULTIPLIER
    ): PhotoDecodePlan? {
        if (sourceWidthPx <= 0 || sourceHeightPx <= 0) return null
        val target = plan(
            sourceWidthPx = sourceWidthPx.toDouble(),
            sourceHeightPx = sourceHeightPx.toDouble(),
            scaleFactor = 1.0,
            viewportWidthPx = viewportWidthPx?.toDouble(),
            viewportHeightPx = viewportHeightPx?.toDouble(),
            qualityMultiplier = qualityMultiplier
        ) ?: return null

        val widthSample = ceilDivide(sourceWidthPx, target.width)
        val heightSample = ceilDivide(sourceHeightPx, target.height)
        val requiredSample = max(1L, max(widthSample, heightSample))
        val sample = powerOfTwoAtLeast(requiredSample) ?: return null
        return PhotoDecodePlan(target = target, inSampleSize = sample)
    }

    /**
     * Maps EXIF orientations 1 through 8 to their output geometry. EXIF 0
     * (undefined) is treated as no transform; unknown values are rejected.
     */
    fun exifOrientationPlan(
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        orientation: Int
    ): ExifOrientationPlan? {
        if (sourceWidthPx <= 0 || sourceHeightPx <= 0) return null
        return when (orientation) {
            0, 1 -> ExifOrientationPlan(
                transformedWidth = sourceWidthPx,
                transformedHeight = sourceHeightPx,
                swapsDimensions = false,
                requiresBitmapTransform = false
            )
            2, 3, 4 -> ExifOrientationPlan(
                transformedWidth = sourceWidthPx,
                transformedHeight = sourceHeightPx,
                swapsDimensions = false,
                requiresBitmapTransform = true
            )
            5, 6, 7, 8 -> ExifOrientationPlan(
                transformedWidth = sourceHeightPx,
                transformedHeight = sourceWidthPx,
                swapsDimensions = true,
                requiresBitmapTransform = true
            )
            else -> null
        }
    }

    /** Applies the bitmap and combined-transform caps to one EXIF orientation. */
    fun exifTransformPlan(
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        orientation: Int,
        maxTransformPeakBytes: Long = MAX_TRANSFORM_PEAK_BYTES,
        maxDimensionPx: Int = MAX_BITMAP_DIMENSION_PX,
        maxPixels: Long = MAX_BITMAP_PIXELS,
        maxBytes: Long = MAX_BITMAP_BYTES
    ): BitmapTransformPlan? {
        val orientationPlan = exifOrientationPlan(sourceWidthPx, sourceHeightPx, orientation)
            ?: return null
        if (!orientationPlan.requiresBitmapTransform) {
            val source = bitmapPlan(sourceWidthPx, sourceHeightPx, maxDimensionPx, maxPixels, maxBytes)
                ?: return null
            return BitmapTransformPlan(source, source, source.bytes)
        }
        return transformPlan(
            sourceWidthPx = sourceWidthPx,
            sourceHeightPx = sourceHeightPx,
            transformedWidthPx = orientationPlan.transformedWidth,
            transformedHeightPx = orientationPlan.transformedHeight,
            maxTransformPeakBytes = maxTransformPeakBytes,
            maxDimensionPx = maxDimensionPx,
            maxPixels = maxPixels,
            maxBytes = maxBytes
        )
    }

    /** Checks an already-decoded bitmap without silently resizing it. */
    fun bitmapPlan(
        widthPx: Int,
        heightPx: Int,
        maxDimensionPx: Int = MAX_BITMAP_DIMENSION_PX,
        maxPixels: Long = MAX_BITMAP_PIXELS,
        maxBytes: Long = MAX_BITMAP_BYTES
    ): BitmapSizePlan? {
        if (widthPx <= 0 || heightPx <= 0) return null
        return boundedPlan(
            widthPx.toLong(),
            heightPx.toLong(),
            maxDimensionPx,
            maxPixels,
            maxBytes
        )
    }

    /**
     * Validates a created ARGB_8888 bitmap against both its dimensions and
     * the byte count reported by the Android allocation.  The reported count
     * may include row padding or allocator overhead, so it is checked against
     * the cap independently of the theoretical 4-bytes-per-pixel minimum.
     */
    fun actualAllocationPlan(
        widthPx: Int,
        heightPx: Int,
        actualAllocationBytes: Long,
        maxDimensionPx: Int = MAX_BITMAP_DIMENSION_PX,
        maxPixels: Long = MAX_BITMAP_PIXELS,
        maxBytes: Long = MAX_BITMAP_BYTES
    ): ActualBitmapAllocationPlan? {
        val dimensions = bitmapPlan(widthPx, heightPx, maxDimensionPx, maxPixels, maxBytes)
            ?: return null
        if (actualAllocationBytes < dimensions.bytes || actualAllocationBytes > maxBytes) {
            return null
        }
        return ActualBitmapAllocationPlan(dimensions, actualAllocationBytes)
    }

    /**
     * Checks both surfaces of a transform.  The source must already be a
     * bounded decoded surface; an oversized encoded input is expected to be
     * sampled first and is rejected here if it reaches this boundary whole.
     */
    fun transformPlan(
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        transformedWidthPx: Int,
        transformedHeightPx: Int,
        maxTransformPeakBytes: Long = MAX_TRANSFORM_PEAK_BYTES,
        maxDimensionPx: Int = MAX_BITMAP_DIMENSION_PX,
        maxPixels: Long = MAX_BITMAP_PIXELS,
        maxBytes: Long = MAX_BITMAP_BYTES
    ): BitmapTransformPlan? {
        if (maxTransformPeakBytes <= 0) return null
        val source = bitmapPlan(sourceWidthPx, sourceHeightPx, maxDimensionPx, maxPixels, maxBytes)
            ?: return null
        val transformed = bitmapPlan(
            transformedWidthPx,
            transformedHeightPx,
            maxDimensionPx,
            maxPixels,
            maxBytes
        ) ?: return null
        val peakBytes = safeAdd(source.bytes, transformed.bytes) ?: return null
        if (peakBytes > maxTransformPeakBytes) return null
        return BitmapTransformPlan(source, transformed, peakBytes)
    }

    /**
     * Validates the two platform-reported allocations which coexist during a
     * transform.  The peak uses overflow-safe addition of actual bytes rather
     * than the theoretical dimensions alone.
     */
    fun actualTransformPlan(
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        sourceAllocationBytes: Long,
        transformedWidthPx: Int,
        transformedHeightPx: Int,
        transformedAllocationBytes: Long,
        maxTransformPeakBytes: Long = MAX_TRANSFORM_PEAK_BYTES,
        maxDimensionPx: Int = MAX_BITMAP_DIMENSION_PX,
        maxPixels: Long = MAX_BITMAP_PIXELS,
        maxBytes: Long = MAX_BITMAP_BYTES
    ): BitmapTransformPlan? {
        if (maxTransformPeakBytes <= 0L) return null
        val source = actualAllocationPlan(
            sourceWidthPx,
            sourceHeightPx,
            sourceAllocationBytes,
            maxDimensionPx,
            maxPixels,
            maxBytes
        ) ?: return null
        val transformed = actualAllocationPlan(
            transformedWidthPx,
            transformedHeightPx,
            transformedAllocationBytes,
            maxDimensionPx,
            maxPixels,
            maxBytes
        ) ?: return null
        val peakBytes = safeAdd(source.allocationBytes, transformed.allocationBytes) ?: return null
        if (peakBytes > maxTransformPeakBytes) return null
        return BitmapTransformPlan(source.dimensions, transformed.dimensions, peakBytes)
    }

    private fun reduceToBudget(
        requestedWidthPx: Double,
        requestedHeightPx: Double,
        maxDimensionPx: Int,
        maxPixels: Long,
        maxBytes: Long
    ): BitmapSizePlan? {
        if (!isFinitePositive(requestedWidthPx) || !isFinitePositive(requestedHeightPx)) return null
        if (maxPixels <= 0L || maxBytes <= 0L) return null
        val effectiveDimension = minOf(maxDimensionPx, MAX_BITMAP_DIMENSION_PX)
        if (effectiveDimension <= 0) return null

        val bytePixelCap = maxBytes / BYTES_PER_ARGB_8888_PIXEL
        if (bytePixelCap <= 0L) return null
        val pixelCap = minOf(maxPixels, bytePixelCap)
        if (pixelCap <= 0L) return null

        val requestedPixels = safeMultiply(requestedWidthPx, requestedHeightPx) ?: return null
        var reduction = 1.0
        val longest = max(requestedWidthPx, requestedHeightPx)
        if (longest > effectiveDimension.toDouble()) {
            reduction = min(reduction, effectiveDimension.toDouble() / longest)
        }
        if (requestedPixels > pixelCap.toDouble()) {
            reduction = min(reduction, sqrt(pixelCap.toDouble() / requestedPixels))
        }
        if (!isFinitePositive(reduction)) return null

        val finalWidth = safeMultiply(requestedWidthPx, reduction) ?: return null
        val finalHeight = safeMultiply(requestedHeightPx, reduction) ?: return null
        val width = floor(finalWidth).toLong().coerceAtLeast(1L)
        val height = floor(finalHeight).toLong().coerceAtLeast(1L)
        return boundedPlan(width, height, effectiveDimension, pixelCap, maxBytes)
    }

    private fun boundedPlan(
        widthPx: Long,
        heightPx: Long,
        maxDimensionPx: Int,
        maxPixels: Long,
        maxBytes: Long
    ): BitmapSizePlan? {
        if (widthPx <= 0L || heightPx <= 0L || maxPixels <= 0L || maxBytes <= 0L) return null
        val effectiveDimension = minOf(maxDimensionPx, MAX_BITMAP_DIMENSION_PX)
        if (effectiveDimension <= 0 ||
            widthPx > effectiveDimension.toLong() ||
            heightPx > effectiveDimension.toLong() ||
            widthPx > Int.MAX_VALUE.toLong() ||
            heightPx > Int.MAX_VALUE.toLong()
        ) {
            return null
        }
        val pixels = safeMultiply(widthPx, heightPx) ?: return null
        val bytes = safeMultiply(pixels, BYTES_PER_ARGB_8888_PIXEL) ?: return null
        if (pixels > maxPixels || bytes > maxBytes) return null
        return BitmapSizePlan(widthPx.toInt(), heightPx.toInt())
    }

    private fun ceilDivide(value: Int, divisor: Int): Long {
        val whole = value.toLong() / divisor.toLong()
        return whole + if (value.toLong() % divisor.toLong() == 0L) 0L else 1L
    }

    private fun powerOfTwoAtLeast(required: Long): Int? {
        if (required <= 1L) return 1
        var sample = 1L
        while (sample < required) {
            if (sample > Int.MAX_VALUE.toLong() / 2L) return null
            sample *= 2L
        }
        return sample.toInt()
    }

    private fun safeMultiply(left: Double, right: Double): Double? {
        val result = left * right
        return result.takeIf(::isFinitePositive)
    }

    private fun safeMultiply(left: Long, right: Long): Long? {
        if (left < 0L || right < 0L) return null
        if (left != 0L && right > Long.MAX_VALUE / left) return null
        return left * right
    }

    private fun safeAdd(left: Long, right: Long): Long? {
        if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) return null
        return left + right
    }

    private fun isFinitePositive(value: Double): Boolean = value.isFinite() && value > 0.0
}
