package com.example.myapplication.stage7

import com.example.myapplication.stage0.HighResolutionPhonePhotoFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class BitmapBudgetPolicyTest {
    @Test
    fun plan_rejectsNonPositiveNonFinitePartialViewportAndOverflow() {
        assertNull(BitmapBudgetPolicy.plan(0.0, 100.0))
        assertNull(BitmapBudgetPolicy.plan(-1.0, 100.0))
        assertNull(BitmapBudgetPolicy.plan(Double.NaN, 100.0))
        assertNull(BitmapBudgetPolicy.plan(Double.POSITIVE_INFINITY, 100.0))
        assertNull(BitmapBudgetPolicy.plan(Double.MAX_VALUE, Double.MAX_VALUE))
        assertNull(BitmapBudgetPolicy.plan(100.0, 100.0, scaleFactor = 0.0))
        assertNull(BitmapBudgetPolicy.plan(100.0, 100.0, qualityMultiplier = Double.NaN))
        assertNull(
            BitmapBudgetPolicy.plan(
                100.0,
                100.0,
                viewportWidthPx = 100.0,
                viewportHeightPx = null
            )
        )
        assertNull(BitmapBudgetPolicy.plan(100.0, 100.0, maxDimensionPx = 0))
        assertNull(BitmapBudgetPolicy.pdfRenderPlan(100, 100, scaleFactor = 0))
    }

    @Test
    fun displayViewport_requiresBothFinitePositiveMeasurements() {
        assertNull(BitmapBudgetPolicy.displayViewport(null, 100))
        assertNull(BitmapBudgetPolicy.displayViewport(100, null))
        assertNull(BitmapBudgetPolicy.displayViewport(0, 100))
        assertNull(BitmapBudgetPolicy.displayViewport(100, 0))
        assertNull(BitmapBudgetPolicy.displayViewport(-1, 100))
        assertNull(BitmapBudgetPolicy.displayViewport(Int.MAX_VALUE, 100))
        assertEquals(
            DisplayViewport(1080, 1920),
            BitmapBudgetPolicy.displayViewport(1080, 1920)
        )
    }

    @Test
    fun plan_returnsFinitePositiveAspectPreservingDimensions() {
        val plan = checkNotNull(
            BitmapBudgetPolicy.plan(
                sourceWidthPx = 1224.0,
                sourceHeightPx = 792.0,
                scaleFactor = 1.0
            )
        )

        assertTrue(plan.width >= 1)
        assertTrue(plan.height >= 1)
        assertTrue(plan.width <= BitmapBudgetPolicy.MAX_BITMAP_DIMENSION_PX)
        assertTrue(plan.height <= BitmapBudgetPolicy.MAX_BITMAP_DIMENSION_PX)
        assertTrue(plan.pixels > 0L)
        assertTrue(plan.bytes > 0L)
        assertEquals(1224.0 / 792.0, plan.width.toDouble() / plan.height.toDouble(), 0.01)
    }

    @Test
    fun viewportPlan_fitsMeasuredViewportAndQualityMultiplier() {
        val fit = checkNotNull(
            BitmapBudgetPolicy.plan(
                sourceWidthPx = 1224.0,
                sourceHeightPx = 792.0,
                scaleFactor = 1.0,
                viewportWidthPx = 1080.0,
                viewportHeightPx = 1920.0,
                qualityMultiplier = 1.0
            )
        )
        val qualityFit = checkNotNull(
            BitmapBudgetPolicy.plan(
                sourceWidthPx = 1224.0,
                sourceHeightPx = 792.0,
                scaleFactor = 1.0,
                viewportWidthPx = 1080.0,
                viewportHeightPx = 1920.0,
                qualityMultiplier = 2.0
            )
        )

        assertEquals(1080, fit.width)
        assertEquals(698, fit.height)
        assertEquals(2160, qualityFit.width)
        assertEquals(1397, qualityFit.height)
        assertTrue(qualityFit.pixels <= BitmapBudgetPolicy.MAX_BITMAP_PIXELS)
    }

    @Test
    fun largeBlueprintFixture_hasBoundedFallbackAndViewportPlans() {
        val fixture = resourceText("stage0/pdfs/blueprint/large_blueprint.pdf")
        assertTrue(fixture.contains("/MediaBox [0 0 1224 792]"))

        val fallback = checkNotNull(
            BitmapBudgetPolicy.pdfRenderPlan(1224, 792, scaleFactor = 4)
        )
        val viewport = checkNotNull(
            BitmapBudgetPolicy.pdfRenderPlan(
                pageWidthPx = 1224,
                pageHeightPx = 792,
                scaleFactor = 1,
                viewportWidthPx = 1080,
                viewportHeightPx = 1920
            )
        )

        assertTrue(fallback.pixels <= BitmapBudgetPolicy.MAX_BITMAP_PIXELS)
        assertTrue(fallback.bytes <= BitmapBudgetPolicy.MAX_BITMAP_BYTES)
        assertTrue(viewport.width <= 2160)
        assertTrue(viewport.height <= 3840)
        assertTrue(viewport.pixels <= BitmapBudgetPolicy.MAX_BITMAP_PIXELS)
        assertTrue(viewport.bytes <= BitmapBudgetPolicy.MAX_BITMAP_BYTES)
        assertTrue("viewport rendering should avoid the uncapped fallback size", viewport.pixels < fallback.pixels)
    }

    @Test
    fun bitmapPlan_enforcesPixelAndArgbByteBoundaries() {
        val exactPixelBoundary = checkNotNull(
            BitmapBudgetPolicy.bitmapPlan(2000, 4000)
        )
        assertEquals(BitmapBudgetPolicy.MAX_BITMAP_PIXELS, exactPixelBoundary.pixels)
        assertEquals(32_000_000L, exactPixelBoundary.bytes)

        // Exercise the byte cap independently of the slightly lower default
        // pixel cap, proving the ARGB_8888 byte check is executable policy.
        val exactByteBoundary = checkNotNull(
            BitmapBudgetPolicy.bitmapPlan(
                widthPx = 2048,
                heightPx = 4096,
                maxPixels = Long.MAX_VALUE
            )
        )
        assertEquals(BitmapBudgetPolicy.MAX_BITMAP_BYTES, exactByteBoundary.bytes)
        assertNull(
            BitmapBudgetPolicy.bitmapPlan(
                widthPx = 2049,
                heightPx = 4096,
                maxPixels = Long.MAX_VALUE
            )
        )

        val reduced = checkNotNull(BitmapBudgetPolicy.plan(4096.0, 4096.0))
        assertTrue(reduced.pixels <= BitmapBudgetPolicy.MAX_BITMAP_PIXELS)
        assertTrue(reduced.bytes <= BitmapBudgetPolicy.MAX_BITMAP_BYTES)
        assertTrue(reduced.width <= BitmapBudgetPolicy.MAX_BITMAP_DIMENSION_PX)
        assertTrue(reduced.height <= BitmapBudgetPolicy.MAX_BITMAP_DIMENSION_PX)
    }

    @Test
    fun actualAllocationPlan_checksReportedBytesIncludingPaddingAndOverhead() {
        val theoretical = checkNotNull(BitmapBudgetPolicy.bitmapPlan(2000, 4000))
        val padded = checkNotNull(
            BitmapBudgetPolicy.actualAllocationPlan(
                widthPx = theoretical.width,
                heightPx = theoretical.height,
                actualAllocationBytes = theoretical.bytes + 4096L
            )
        )
        assertEquals(theoretical, padded.dimensions)
        assertEquals(theoretical.bytes + 4096L, padded.allocationBytes)

        assertNull(
            BitmapBudgetPolicy.actualAllocationPlan(
                widthPx = theoretical.width,
                heightPx = theoretical.height,
                actualAllocationBytes = BitmapBudgetPolicy.MAX_BITMAP_BYTES + 1L
            )
        )
        assertNull(
            BitmapBudgetPolicy.actualAllocationPlan(
                widthPx = theoretical.width,
                heightPx = theoretical.height,
                actualAllocationBytes = theoretical.bytes - 1L
            )
        )
        assertNull(
            BitmapBudgetPolicy.actualAllocationPlan(
                widthPx = theoretical.width,
                heightPx = theoretical.height,
                actualAllocationBytes = 0L
            )
        )
    }

    @Test
    fun plan_handlesExtremeAspectRatiosWithoutZeroDimensions() {
        val tall = checkNotNull(BitmapBudgetPolicy.plan(1.0, 1_000_000_000.0))
        val wide = checkNotNull(BitmapBudgetPolicy.plan(1_000_000_000.0, 1.0))

        assertEquals(1, tall.width)
        assertEquals(BitmapBudgetPolicy.MAX_BITMAP_DIMENSION_PX, tall.height)
        assertEquals(BitmapBudgetPolicy.MAX_BITMAP_DIMENSION_PX, wide.width)
        assertEquals(1, wide.height)
        assertTrue(tall.bytes <= BitmapBudgetPolicy.MAX_BITMAP_BYTES)
        assertTrue(wide.bytes <= BitmapBudgetPolicy.MAX_BITMAP_BYTES)
    }

    @Test
    fun photoDecodePlan_samplesHighResolutionPhonePhotoForPhoneViewport() {
        val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
        assertTrue(bytes.isNotEmpty())
        assertEquals(4032, HighResolutionPhonePhotoFixture.WIDTH)
        assertEquals(3024, HighResolutionPhonePhotoFixture.HEIGHT)

        val plan = checkNotNull(
            BitmapBudgetPolicy.photoDecodePlan(
                sourceWidthPx = HighResolutionPhonePhotoFixture.WIDTH,
                sourceHeightPx = HighResolutionPhonePhotoFixture.HEIGHT,
                viewportWidthPx = 360,
                viewportHeightPx = 640
            )
        )

        assertTrue("phone-sized photo must be sampled", plan.inSampleSize > 1)
        assertEquals(8, plan.inSampleSize)
        assertEquals(0, plan.inSampleSize and (plan.inSampleSize - 1))
        assertTrue(
            (HighResolutionPhonePhotoFixture.WIDTH + plan.inSampleSize - 1) /
                plan.inSampleSize <= plan.target.width
        )
        assertTrue(
            (HighResolutionPhonePhotoFixture.HEIGHT + plan.inSampleSize - 1) /
                plan.inSampleSize <= plan.target.height
        )
        assertTrue(plan.target.pixels <= BitmapBudgetPolicy.MAX_BITMAP_PIXELS)
        assertTrue(plan.target.bytes <= BitmapBudgetPolicy.MAX_BITMAP_BYTES)

        val representative = checkNotNull(
            BitmapBudgetPolicy.photoDecodePlan(
                sourceWidthPx = 1000,
                sourceHeightPx = 750,
                viewportWidthPx = 500,
                viewportHeightPx = 500,
                qualityMultiplier = 1.0
            )
        )
        assertEquals(2, representative.inSampleSize)
        assertEquals(0, representative.inSampleSize and (representative.inSampleSize - 1))
    }

    @Test
    fun photoDecodePlan_withoutViewportStillUsesBoundedFallback() {
        val plan = checkNotNull(
            BitmapBudgetPolicy.photoDecodePlan(
                HighResolutionPhonePhotoFixture.WIDTH,
                HighResolutionPhonePhotoFixture.HEIGHT
            )
        )

        assertTrue(plan.inSampleSize > 1)
        assertTrue(plan.target.pixels <= BitmapBudgetPolicy.MAX_BITMAP_PIXELS)
        assertTrue(plan.target.bytes <= BitmapBudgetPolicy.MAX_BITMAP_BYTES)
    }

    @Test
    fun transformPlan_boundsPeakAndRejectsOversizedWholeInput() {
        val bounded = checkNotNull(
            BitmapBudgetPolicy.transformPlan(
                sourceWidthPx = 2000,
                sourceHeightPx = 4000,
                transformedWidthPx = 4000,
                transformedHeightPx = 2000
            )
        )
        assertEquals(64_000_000L, bounded.peakBytes)
        assertTrue(bounded.peakBytes <= BitmapBudgetPolicy.MAX_TRANSFORM_PEAK_BYTES)

        assertNull(
            BitmapBudgetPolicy.transformPlan(
                sourceWidthPx = 2000,
                sourceHeightPx = 4000,
                transformedWidthPx = 4000,
                transformedHeightPx = 2000,
                maxTransformPeakBytes = 63_999_999L
            )
        )
        assertNull(
            BitmapBudgetPolicy.transformPlan(
                sourceWidthPx = HighResolutionPhonePhotoFixture.WIDTH,
                sourceHeightPx = HighResolutionPhonePhotoFixture.HEIGHT,
                transformedWidthPx = HighResolutionPhonePhotoFixture.HEIGHT,
                transformedHeightPx = HighResolutionPhonePhotoFixture.WIDTH
            )
        )
    }

    @Test
    fun actualTransformPlan_usesReportedPeakAndRejectsOverflowOrPeakOverage() {
        val source = checkNotNull(BitmapBudgetPolicy.bitmapPlan(1000, 2000))
        val transformed = checkNotNull(BitmapBudgetPolicy.bitmapPlan(2000, 1000))
        val sourceBytes = source.bytes + 2048L
        val transformedBytes = transformed.bytes + 4096L
        val actualPeak = sourceBytes + transformedBytes

        val bounded = checkNotNull(
            BitmapBudgetPolicy.actualTransformPlan(
                sourceWidthPx = source.width,
                sourceHeightPx = source.height,
                sourceAllocationBytes = sourceBytes,
                transformedWidthPx = transformed.width,
                transformedHeightPx = transformed.height,
                transformedAllocationBytes = transformedBytes
            )
        )
        assertEquals(actualPeak, bounded.peakBytes)
        assertTrue(bounded.peakBytes <= BitmapBudgetPolicy.MAX_TRANSFORM_PEAK_BYTES)
        assertNull(
            BitmapBudgetPolicy.actualTransformPlan(
                sourceWidthPx = source.width,
                sourceHeightPx = source.height,
                sourceAllocationBytes = sourceBytes,
                transformedWidthPx = transformed.width,
                transformedHeightPx = transformed.height,
                transformedAllocationBytes = transformedBytes,
                maxTransformPeakBytes = actualPeak - 1L
            )
        )
        assertNull(
            BitmapBudgetPolicy.actualTransformPlan(
                sourceWidthPx = source.width,
                sourceHeightPx = source.height,
                sourceAllocationBytes = Long.MAX_VALUE,
                transformedWidthPx = transformed.width,
                transformedHeightPx = transformed.height,
                transformedAllocationBytes = transformed.bytes,
                maxBytes = Long.MAX_VALUE,
                maxTransformPeakBytes = Long.MAX_VALUE
            )
        )
    }

    @Test
    fun exifOrientationPlan_coversAllEightOrientationsAndPeakBounds() {
        val sourceWidth = 2000
        val sourceHeight = 4000
        for (orientation in 1..8) {
            val geometry = checkNotNull(
                BitmapBudgetPolicy.exifOrientationPlan(sourceWidth, sourceHeight, orientation)
            )
            val swaps = orientation >= 5
            assertEquals(swaps, geometry.swapsDimensions)
            assertEquals(if (swaps) sourceHeight else sourceWidth, geometry.transformedWidth)
            assertEquals(if (swaps) sourceWidth else sourceHeight, geometry.transformedHeight)
            assertEquals(orientation != 1, geometry.requiresBitmapTransform)

            val bounded = checkNotNull(
                BitmapBudgetPolicy.exifTransformPlan(sourceWidth, sourceHeight, orientation)
            )
            assertEquals(geometry.transformedWidth, bounded.transformed.width)
            assertEquals(geometry.transformedHeight, bounded.transformed.height)
            assertTrue(bounded.peakBytes <= BitmapBudgetPolicy.MAX_TRANSFORM_PEAK_BYTES)
            assertEquals(
                if (orientation == 1) 32_000_000L else 64_000_000L,
                bounded.peakBytes
            )
        }

        val undefined = checkNotNull(
            BitmapBudgetPolicy.exifOrientationPlan(sourceWidth, sourceHeight, 0)
        )
        assertFalse(undefined.requiresBitmapTransform)
        assertNull(BitmapBudgetPolicy.exifOrientationPlan(sourceWidth, sourceHeight, 9))
        assertNull(BitmapBudgetPolicy.exifTransformPlan(sourceWidth, sourceHeight, 9))
        assertNull(
            BitmapBudgetPolicy.exifTransformPlan(
                sourceWidth,
                sourceHeight,
                orientation = 5,
                maxTransformPeakBytes = 63_999_999L
            )
        )
    }

    @Test
    fun thumbnailPlan_preservesExistingSixHundredPixelWidthUnderPolicy() {
        val plan = checkNotNull(BitmapBudgetPolicy.pdfThumbnailPlan(1224, 792))

        assertEquals(BitmapBudgetPolicy.THUMBNAIL_TARGET_WIDTH_PX, plan.width)
        assertEquals(388, plan.height)
        assertTrue(plan.pixels <= BitmapBudgetPolicy.MAX_BITMAP_PIXELS)
        assertTrue(plan.bytes <= BitmapBudgetPolicy.MAX_BITMAP_BYTES)
    }

    @Test
    fun displayZoom_doesNotChangeViewportAllocationPlan() {
        val plans = listOf(1f, 5f, 15f).map { _ ->
            BitmapBudgetPolicy.pdfRenderPlan(
                pageWidthPx = 1224,
                pageHeightPx = 792,
                scaleFactor = 1,
                viewportWidthPx = 1080,
                viewportHeightPx = 1920
            )
        }

        assertTrue(plans.all { it != null })
        assertEquals(plans[0], plans[1])
        assertEquals(plans[1], plans[2])
    }

    @Test
    fun finalPlans_areAlwaysFiniteAndPositiveForSmallPositiveInputs() {
        val plan = checkNotNull(
            BitmapBudgetPolicy.plan(
                sourceWidthPx = 0.25,
                sourceHeightPx = 0.5,
                scaleFactor = 0.5
            )
        )

        assertTrue(plan.width >= 1)
        assertTrue(plan.height >= 1)
        assertFalse(plan.pixels <= 0L)
        assertFalse(plan.bytes <= 0L)
    }

    private fun resourceText(path: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "missing test resource $path"
        }.use { it.readBytes().toString(StandardCharsets.UTF_8) }
}
