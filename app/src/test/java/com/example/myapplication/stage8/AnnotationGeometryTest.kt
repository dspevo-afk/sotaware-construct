package com.example.myapplication.stage8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationGeometryTest {
    @Test fun positiveRatiosTakePrecedenceAndLegacyAxesFallBackIndependently() {
        assertEquals(200f, AnnotationGeometry.resolvePageSize(1000f, 800f, .2f, .0f).width, .001f)
        assertEquals(800f, AnnotationGeometry.resolvePageSize(1000f, 800f, .2f, .0f).height, .001f)
        assertEquals(120f, AnnotationGeometry.resolvePageSize(1000f, 800f, .0f, .0f, 120f, 90f).width, .001f)
        assertEquals(90f, AnnotationGeometry.resolvePageSize(1000f, 800f, .0f, .0f, 120f, 90f).height, .001f)
        assertEquals(200f, AnnotationGeometry.resolveImageSize(1000f, 800f, .2f, .0f, .9f, .25f).width, .001f)
        assertEquals(200f, AnnotationGeometry.resolveImageSize(1000f, 800f, .2f, .0f, .9f, .25f).height, .001f)
        assertEquals(900f, AnnotationGeometry.resolveImageSize(1000f, 800f, .0f, .0f, .9f, .25f).width, .001f)
        assertEquals(200f, AnnotationGeometry.resolveImageSize(1000f, 800f, .0f, .0f, .9f, .25f).height, .001f)
    }

    @Test fun inverseClockwiseRotationRejectsPointsOutsideRotatedRectangle() {
        // A 20x10 rectangle centered at (50,50), rotated clockwise 90 degrees.
        assertTrue(AnnotationGeometry.rotatedRectContains(50f, 59f, 50f, 50f, 20f, 10f, 90f))
        assertFalse(AnnotationGeometry.rotatedRectContains(50f, 61f, 50f, 50f, 20f, 10f, 90f))
        assertTrue(AnnotationGeometry.rotatedRectContains(50f, 62f, 50f, 50f, 20f, 10f, 90f, 3f))
    }

    @Test fun topLeftNoteUsesMeasuredCenterAsRotationPivot() {
        assertTrue(AnnotationGeometry.rotatedNoteContains(10f, 20f, 0f, 10f, 20f, 20f, 45f))
        assertFalse(AnnotationGeometry.rotatedNoteContains(0f, 0f, 0f, 10f, 20f, 20f, 45f))
    }

    @Test fun rotatedPdfNoteSelectionConvertsCenterToTopLeftBeforeHitTest() {
        val centerX = 100f
        val centerY = 80f
        val width = 40f
        val height = 20f
        assertTrue(
            AnnotationGeometry.rotatedNoteContains(
                centerX, centerY,
                centerX - width / 2f, centerY - height / 2f,
                width, height, 37f
            )
        )
        assertFalse(
            AnnotationGeometry.rotatedNoteContains(
                centerX + width, centerY,
                centerX - width / 2f, centerY - height / 2f,
                width, height, 37f
            )
        )
    }

    @Test fun imageExportNotePivotMatchesOnScreenTopLeftLayout() {
        assertEquals(110f, AnnotationGeometry.notePivot(100f, 200f, 20f, 40f).x, .001f)
        assertEquals(220f, AnnotationGeometry.notePivot(100f, 200f, 20f, 40f).y, .001f)
    }

    @Test fun gestureDraftAccumulatesMultiplePanAndZoomIncrements() {
        val first = AnnotationGeometry.accumulateNormalizedDelta(AnnotationPoint(.2f, .3f), 10f, 0f, 100f, 100f)
        val second = AnnotationGeometry.accumulateNormalizedDelta(first, 10f, 0f, 100f, 100f)
        assertEquals(.4f, second.x, .001f)
        val zoom1 = AnnotationGeometry.accumulateRatio(.2f, 1.5f)
        val zoom2 = AnnotationGeometry.accumulateRatio(zoom1, 1.5f)
        assertEquals(.45f, zoom2, .001f)
    }

    @Test fun legacyImageNoteFontSizeMigratesToUsableResizeRatio() {
        val ratio = AnnotationGeometry.usableImageNoteFontSizeRatio(
            currentRatio = 0f,
            legacyFontSize = 16f,
            density = 2f,
            displayedImageHeight = 800f
        )
        assertEquals(.02f, ratio, .001f)
        assertEquals(.04f, AnnotationGeometry.accumulateRatio(ratio, 2f, .01f, .2f), .001f)
        assertEquals(.01f, AnnotationGeometry.usableImageNoteFontSizeRatio(0f, 0f, 1f, 0f), .001f)
    }

    @Test fun legacyImageNoteResolutionIsSharedByViewerAndExport() {
        val viewerPx = AnnotationGeometry.resolveImageNoteFontSizePx(
            currentRatio = 0f,
            legacyFontSize = 16f,
            displayedImageHeight = 1600f
        )
        val exportPx = AnnotationGeometry.resolveImageNoteFontSizePx(
            currentRatio = 0f,
            legacyFontSize = 16f,
            displayedImageHeight = 1600f
        )
        assertEquals(32f, viewerPx, .001f)
        assertEquals(viewerPx, exportPx, .001f)
        assertEquals(.125f, AnnotationGeometry.resolveImageNoteFontSizeRatio(.125f, 16f), .001f)
    }

    @Test fun pageNoteHitUsesMeasuredScreenBoundsAtAnyCompositeScale() {
        val measuredScreen = AnnotationSize(40f, 20f)
        for (compositeScale in listOf(.5f, 2f)) {
            val page = AnnotationGeometry.pageSizeFromScreen(
                measuredScreen.width, measuredScreen.height, compositeScale
            )
            assertTrue(
                AnnotationGeometry.rotatedNoteContains(
                    100f, 50f,
                    100f - page.width / 2f, 50f - page.height / 2f,
                    page.width, page.height, 37f
                )
            )
            assertFalse(
                AnnotationGeometry.rotatedNoteContains(
                    100f + page.width, 50f,
                    100f - page.width / 2f, 50f - page.height / 2f,
                    page.width, page.height, 37f
                )
            )
        }
    }
}
