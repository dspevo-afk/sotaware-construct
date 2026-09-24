package com.example.myapplication.audit

import com.example.myapplication.PageScale
import com.example.myapplication.Point
import com.example.myapplication.stage8.*
import org.junit.Assert.*
import org.junit.Test

class MeasurementRangeTest {
    @Test fun ordinaryDimensionsAndScaleSummaryUseTheSameCalculation() {
        assertEquals("12' 0\"", formatSourceDistance(144f, 12f))
        assertEquals("6' 0\"", formatSourceDistance(72f, 12f))
        assertEquals("0\"", formatSourceDistance(0f, 12f))
    }
    @Test fun admittedExtremeScalesReturnNoLabelInsteadOfThrowing() {
        for (scale in listOf(Float.MIN_VALUE, java.lang.Float.MIN_NORMAL, 1e-30f)) {
            assertTrue(isValidPageScale(scale))
            assertNull(formatSourceDistance(72f, scale))
            assertNull(formatSourceDistance(144f, scale))
        }
    }
    @Test fun invalidOperandsAndUnrepresentableInchesAreRejected() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertNull(formatSourceDistance(value, 12f))
            assertNull(formatSourceDistance(72f, value))
        }
        assertNull(formatSourceDistance(72f, 0f))
        assertNull(formatSourceDistance(Float.MAX_VALUE, 1f))
        assertNull(formatSourceDistance(1e18f, 1f))
    }
    @Test fun unsafePolylineIsNotCreatedButOrdinaryPolylineRetainsItsGeometry() {
        val points = listOf(Point(0f, 0f), Point(.5f, 0f), Point(1f, 0f))
        assertNull(buildMeasurement(points, 144f, 72f, PageScale(Float.MIN_VALUE), DrawingToolStyle()))
        val accepted = requireNotNull(buildMeasurement(points, 144f, 72f, PageScale(12f), DrawingToolStyle()))
        assertEquals("12' 0\"", accepted.text)
        assertEquals(points, accepted.vertices)
    }
    @Test fun legacyFormatterCannotSilentlySaturateItsIntegerResult() {
        try { formatFeet(Float.MAX_VALUE); fail("Expected range rejection") }
        catch (_: IllegalArgumentException) { }
    }
}
