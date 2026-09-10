package com.example.myapplication.stage8

import com.example.myapplication.stage5.Stage5Limits
import java.math.BigDecimal

/** User-facing validation reasons; the UI may map [messageKey] to resources. */
enum class CalibrationError(val messageKey: String) {
    EMPTY_INPUT("calibration_error_empty"),
    INPUT_TOO_LONG("calibration_error_too_long"),
    INVALID_FORMAT("calibration_error_format"),
    NON_FINITE("calibration_error_non_finite"),
    SUBNORMAL("calibration_error_too_small"),
    NON_POSITIVE("calibration_error_non_positive"),
    OUT_OF_RANGE("calibration_error_out_of_range"),
    DEGENERATE_PIXEL_DISTANCE("calibration_error_pixel_distance"),
    INVALID_PAGE_SCALE("calibration_error_scale")
}

/** Strictly parsed calibration distance, or a typed reason why it was refused. */
sealed interface CalibrationInput {
    data class Accepted(val feet: Float) : CalibrationInput
    data class Rejected(val error: CalibrationError) : CalibrationInput
}

/** Result of converting a measured PDF source distance into points per foot. */
sealed interface CalibrationScaleResult {
    data class Accepted(val pointsPerFoot: Float) : CalibrationScaleResult
    data class Rejected(val error: CalibrationError) : CalibrationScaleResult
}

/** Hard cap applied before trimming, regex matching, or numeric conversion. */
const val MAX_CALIBRATION_INPUT_CHARS: Int = 256

private const val NUMBER_PATTERN =
    "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"

private val DECIMAL_FEET_PATTERN = Regex(NUMBER_PATTERN)
private val FEET_INCHES_PATTERN = Regex(
    "($NUMBER_PATTERN)\\s*'\\s*($NUMBER_PATTERN)\\s*\"?"
)
private val FEET_ONLY_PATTERN = Regex("($NUMBER_PATTERN)'")

/* Float.MIN_VALUE is the Stage 5 persisted lower bound; runtime inputs must
 * also avoid subnormal arithmetic, which is not useful for calibration. */
private val MIN_NORMAL_FLOAT: Float = java.lang.Float.MIN_NORMAL

/**
 * Parses decimal feet (`12.5`), feet-only (`10'`), or complete feet/inches
 * (`10' 6\"`).
 *
 * The inches quote is optional for ordinary feet/inches typed input, but the feet
 * apostrophe and numeric inches component are both required in that form.
 * No suffix, prefix, or trailing text is accepted.
 */
fun parseCalibrationInput(input: String): CalibrationInput {
    if (input.length > MAX_CALIBRATION_INPUT_CHARS) {
        return CalibrationInput.Rejected(CalibrationError.INPUT_TOO_LONG)
    }
    val normalized = input.trim()
    if (normalized.isEmpty()) return CalibrationInput.Rejected(CalibrationError.EMPTY_INPUT)
    if (normalized.isNonFiniteLiteral()) {
        return CalibrationInput.Rejected(CalibrationError.NON_FINITE)
    }

    FEET_INCHES_PATTERN.matchEntire(normalized)?.let { match ->
        val feet = when (val parsed = parseNumber(match.groupValues[1])) {
            is ParsedNumber.Valid -> parsed.value
            is ParsedNumber.Invalid -> return CalibrationInput.Rejected(parsed.error)
        }
        val inches = when (val parsed = parseNumber(match.groupValues[2])) {
            is ParsedNumber.Valid -> parsed.value
            is ParsedNumber.Invalid -> return CalibrationInput.Rejected(parsed.error)
        }
        validateNonNegativeComponent(feet)?.let { return CalibrationInput.Rejected(it) }
        validateNonNegativeComponent(inches)?.let { return CalibrationInput.Rejected(it) }
        return acceptedDistance(feet + inches / 12f)
    }

    FEET_ONLY_PATTERN.matchEntire(normalized)?.let { match ->
        val feet = when (val parsed = parseNumber(match.groupValues[1])) {
            is ParsedNumber.Valid -> parsed.value
            is ParsedNumber.Invalid -> return CalibrationInput.Rejected(parsed.error)
        }
        validateNonNegativeComponent(feet)?.let { return CalibrationInput.Rejected(it) }
        return acceptedDistance(feet)
    }

    DECIMAL_FEET_PATTERN.matchEntire(normalized)?.let { match ->
        val feet = when (val parsed = parseNumber(match.value)) {
            is ParsedNumber.Valid -> parsed.value
            is ParsedNumber.Invalid -> return CalibrationInput.Rejected(parsed.error)
        }
        return acceptedDistance(feet)
    }

    return CalibrationInput.Rejected(CalibrationError.INVALID_FORMAT)
}

/**
 * Converts a PDF source-point measurement and a parsed distance into a safe scale.
 * Subnormal inputs/results are rejected before they can produce unstable
 * arithmetic; the returned value is still checked against the Stage 5 scale
 * persistence limits.
 */
fun calculatePageScale(pixelDistance: Float, feet: Float): CalibrationScaleResult {
    validatePixelDistance(pixelDistance)?.let {
        return CalibrationScaleResult.Rejected(it)
    }
    validateFeet(feet)?.let {
        return CalibrationScaleResult.Rejected(it)
    }

    val pointsPerFoot = pixelDistance / feet
    if (!pointsPerFoot.isFinite()) {
        return CalibrationScaleResult.Rejected(CalibrationError.NON_FINITE)
    }
    if (pointsPerFoot <= 0f) {
        return CalibrationScaleResult.Rejected(CalibrationError.INVALID_PAGE_SCALE)
    }
    if (pointsPerFoot < MIN_NORMAL_FLOAT) {
        return CalibrationScaleResult.Rejected(CalibrationError.SUBNORMAL)
    }
    if (!isValidPageScale(pointsPerFoot)) {
        return CalibrationScaleResult.Rejected(CalibrationError.INVALID_PAGE_SCALE)
    }
    return CalibrationScaleResult.Accepted(pointsPerFoot)
}

/** Typed-result overload for callers that have already admitted the input. */
fun calculatePageScale(
    pixelDistance: Float,
    input: CalibrationInput.Accepted
): CalibrationScaleResult = calculatePageScale(pixelDistance, input.feet)

/** Preserves a parser rejection when callers compose parsing and calculation. */
fun calculatePageScale(
    pixelDistance: Float,
    input: CalibrationInput
): CalibrationScaleResult = when (input) {
    is CalibrationInput.Accepted -> calculatePageScale(pixelDistance, input.feet)
    is CalibrationInput.Rejected -> CalibrationScaleResult.Rejected(input.error)
}

/**
 * Matches the Stage 5 persisted page-scale boundary: finite, positive, and
 * no greater than [Stage5Limits.MAX_NUMERIC_ABS].  The positive check is
 * equivalent to Stage 5's inclusive `Float.MIN_VALUE` lower bound for Float.
 */
fun isValidPageScale(pointsPerFoot: Float): Boolean =
    pointsPerFoot.isFinite() &&
        pointsPerFoot >= Float.MIN_VALUE &&
        pointsPerFoot <= Stage5Limits.MAX_NUMERIC_ABS

private sealed interface ParsedNumber {
    data class Valid(val value: Float) : ParsedNumber
    data class Invalid(val error: CalibrationError) : ParsedNumber
}

private fun parseNumber(token: String): ParsedNumber {
    val exact = try {
        BigDecimal(token)
    } catch (_: NumberFormatException) {
        return ParsedNumber.Invalid(CalibrationError.INVALID_FORMAT)
    }
    val value = token.toFloatOrNull()
        ?: return ParsedNumber.Invalid(CalibrationError.INVALID_FORMAT)
    if (!value.isFinite()) {
        return ParsedNumber.Invalid(CalibrationError.NON_FINITE)
    }
    // Float conversion can silently turn a nonzero decimal such as 1e-999
    // into zero.  Preserve the lexical value long enough to reject it.
    if (exact.signum() != 0 && value == 0f) {
        return ParsedNumber.Invalid(CalibrationError.SUBNORMAL)
    }
    return ParsedNumber.Valid(value)
}

private fun acceptedDistance(feet: Float): CalibrationInput {
    val error = validateFeet(feet)
    return if (error == null) {
        CalibrationInput.Accepted(feet)
    } else {
        CalibrationInput.Rejected(error)
    }
}

private fun validateFeet(feet: Float): CalibrationError? = when {
    !feet.isFinite() -> CalibrationError.NON_FINITE
    feet <= 0f -> CalibrationError.NON_POSITIVE
    feet < MIN_NORMAL_FLOAT -> CalibrationError.SUBNORMAL
    feet > Stage5Limits.MAX_NUMERIC_ABS -> CalibrationError.OUT_OF_RANGE
    else -> null
}

private fun validateNonNegativeComponent(value: Float): CalibrationError? = when {
    !value.isFinite() -> CalibrationError.NON_FINITE
    value < 0f -> CalibrationError.NON_POSITIVE
    value != 0f && value < MIN_NORMAL_FLOAT -> CalibrationError.SUBNORMAL
    value > Stage5Limits.MAX_NUMERIC_ABS -> CalibrationError.OUT_OF_RANGE
    else -> null
}

private fun validatePixelDistance(pixelDistance: Float): CalibrationError? = when {
    !pixelDistance.isFinite() -> CalibrationError.NON_FINITE
    pixelDistance <= 0f -> CalibrationError.DEGENERATE_PIXEL_DISTANCE
    pixelDistance < MIN_NORMAL_FLOAT -> CalibrationError.SUBNORMAL
    pixelDistance > Stage5Limits.MAX_NUMERIC_ABS -> CalibrationError.OUT_OF_RANGE
    else -> null
}

private fun String.isNonFiniteLiteral(): Boolean =
    equals("NaN", ignoreCase = true) ||
        equals("+NaN", ignoreCase = true) ||
        equals("-NaN", ignoreCase = true) ||
        equals("Infinity", ignoreCase = true) ||
        equals("+Infinity", ignoreCase = true) ||
        equals("-Infinity", ignoreCase = true)
