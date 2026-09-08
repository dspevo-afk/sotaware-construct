package com.example.myapplication.stage9a

import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage8.CalibrationError
import com.example.myapplication.stage8.CalibrationInput
import com.example.myapplication.stage8.CalibrationScaleResult
import com.example.myapplication.stage8.MAX_CALIBRATION_INPUT_CHARS
import com.example.myapplication.stage8.calculatePageScale
import com.example.myapplication.stage8.isValidPageScale
import com.example.myapplication.stage8.parseCalibrationInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationInputTest {
    @Test
    fun parserReturnsTypedAcceptedValuesForSupportedCompleteFormats() {
        assertEquals(
            CalibrationInput.Accepted(12.5f),
            parseCalibrationInput(" 12.5 ")
        )
        assertEquals(
            CalibrationInput.Accepted(10.5f),
            parseCalibrationInput("10' 6\"")
        )
        assertEquals(
            CalibrationInput.Accepted(10f),
            parseCalibrationInput("10'")
        )
        assertEquals(
            CalibrationInput.Accepted(.5f),
            parseCalibrationInput("0' 6\"")
        )
    }

    @Test
    fun parserReturnsTypedReasonsForMalformedAndUnsafeValues() {
        assertEquals(
            CalibrationInput.Rejected(CalibrationError.INVALID_FORMAT),
            parseCalibrationInput("10' garbage")
        )
        assertEquals(
            CalibrationInput.Rejected(CalibrationError.INVALID_FORMAT),
            parseCalibrationInput("garbage' 6\"")
        )
        assertEquals(
            CalibrationInput.Rejected(CalibrationError.INVALID_FORMAT),
            parseCalibrationInput("' 6\"")
        )
        assertEquals(
            CalibrationInput.Rejected(CalibrationError.INVALID_FORMAT),
            parseCalibrationInput("12.5 feet")
        )
        assertEquals(
            CalibrationInput.Rejected(CalibrationError.NON_FINITE),
            parseCalibrationInput("NaN")
        )
        assertEquals(
            CalibrationInput.Rejected(CalibrationError.NON_FINITE),
            parseCalibrationInput("Infinity")
        )
        assertEquals(
            CalibrationInput.Rejected(CalibrationError.SUBNORMAL),
            parseCalibrationInput("1e-45")
        )
        assertEquals(
            CalibrationInput.Rejected(CalibrationError.SUBNORMAL),
            parseCalibrationInput("1e-999' 6\"")
        )
        assertEquals(
            CalibrationInput.Rejected(CalibrationError.NON_POSITIVE),
            parseCalibrationInput("-1")
        )
        assertEquals(
            CalibrationInput.Rejected(CalibrationError.OUT_OF_RANGE),
            parseCalibrationInput("200000000")
        )
        assertTrue(parseCalibrationInput("1e39") is CalibrationInput.Rejected)
    }

    @Test
    fun parserRejectsOverlongInputBeforeNumericParsing() {
        val overlong = "1".repeat(MAX_CALIBRATION_INPUT_CHARS + 1)
        assertEquals(
            CalibrationInput.Rejected(CalibrationError.INPUT_TOO_LONG),
            parseCalibrationInput(overlong)
        )
    }

    @Test
    fun pageScaleValidationMatchesStage5FinitePositiveBounds() {
        assertTrue(isValidPageScale(Float.MIN_VALUE))
        assertTrue(isValidPageScale(Stage5Limits.MAX_NUMERIC_ABS))
        assertFalse(isValidPageScale(Float.NaN))
        assertFalse(isValidPageScale(Float.POSITIVE_INFINITY))
        assertFalse(isValidPageScale(Float.NEGATIVE_INFINITY))
        assertFalse(isValidPageScale(0f))
        assertFalse(isValidPageScale(-1f))
        assertFalse(isValidPageScale(java.lang.Math.nextUp(Stage5Limits.MAX_NUMERIC_ABS)))
    }

    @Test
    fun scaleCalculationAdmitsBoundaryAndRejectsDegenerateArithmetic() {
        assertEquals(
            CalibrationScaleResult.Accepted(12f),
            calculatePageScale(120f, 10f)
        )
        assertEquals(
            CalibrationScaleResult.Accepted(Stage5Limits.MAX_NUMERIC_ABS),
            calculatePageScale(Stage5Limits.MAX_NUMERIC_ABS, 1f)
        )
        assertEquals(
            CalibrationScaleResult.Rejected(CalibrationError.DEGENERATE_PIXEL_DISTANCE),
            calculatePageScale(0f, 10f)
        )
        assertEquals(
            CalibrationScaleResult.Rejected(CalibrationError.DEGENERATE_PIXEL_DISTANCE),
            calculatePageScale(-1f, 10f)
        )
        assertEquals(
            CalibrationScaleResult.Rejected(CalibrationError.SUBNORMAL),
            calculatePageScale(Float.MIN_VALUE, 10f)
        )
        assertEquals(
            CalibrationScaleResult.Rejected(CalibrationError.NON_FINITE),
            calculatePageScale(Float.POSITIVE_INFINITY, 10f)
        )
        assertEquals(
            CalibrationScaleResult.Rejected(CalibrationError.NON_FINITE),
            calculatePageScale(Float.NaN, 10f)
        )
        assertEquals(
            CalibrationScaleResult.Rejected(CalibrationError.NON_POSITIVE),
            calculatePageScale(120f, 0f)
        )
        assertEquals(
            CalibrationScaleResult.Rejected(CalibrationError.NON_POSITIVE),
            calculatePageScale(120f, -1f)
        )
        assertEquals(
            CalibrationScaleResult.Rejected(CalibrationError.INVALID_PAGE_SCALE),
            calculatePageScale(Stage5Limits.MAX_NUMERIC_ABS, .5f)
        )
    }

    @Test
    fun typedAcceptedInputOverloadCalculatesTheSameScale() {
        val input = parseCalibrationInput("10' 6\"")
        assertTrue(input is CalibrationInput.Accepted)
        assertEquals(
            CalibrationScaleResult.Accepted(12f),
            calculatePageScale(126f, input as CalibrationInput.Accepted)
        )
    }

    @Test
    fun rejectedInputRemainsRejectedWhenComposedWithScaleCalculation() {
        val input: CalibrationInput = parseCalibrationInput("10' garbage")
        assertEquals(
            CalibrationScaleResult.Rejected(CalibrationError.INVALID_FORMAT),
            calculatePageScale(126f, input)
        )
    }
}
