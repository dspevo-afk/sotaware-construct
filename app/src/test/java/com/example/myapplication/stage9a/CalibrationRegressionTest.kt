package com.example.myapplication.stage9a

import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.PageScale
import com.example.myapplication.parseDistance
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.PageScaleSnapshotV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.validateSnapshot
import com.example.myapplication.stage8.AnnotationReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 9A calibration admission contract.
 *
 * These tests intentionally use the existing parser/reducer seams.  The
 * production fix can add a typed parsing helper behind those seams without
 * making this baseline test depend on a new API.
 */
class CalibrationRegressionTest {
    @Test
    fun parseDistanceRejectsIncompleteNonFiniteAndSubnormalCalibrationInput() {
        listOf(
            "10' garbage",
            "garbage' 6\"",
            "Infinity",
            "1e-45"
        ).forEach { input ->
            assertFalse(
                "invalid calibration input must not produce an admissible distance: $input",
                parseDistance(input) > 0f
            )
        }
    }

    @Test
    fun parseDistanceStillAcceptsDecimalFeetAndCompleteFeetAndInches() {
        assertEquals(12.5f, parseDistance("12.5"), 0f)
        assertEquals(10.5f, parseDistance("10' 6\""), 0f)
    }

    @Test
    fun snapshotValidationRejectsNonFiniteZeroAndOutOfRangePageScales() {
        listOf(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            0f,
            -1f,
            Stage5Limits.MAX_NUMERIC_ABS * 2f
        ).forEach { pixelsPerFoot ->
            assertSnapshotRejected(pixelsPerFoot)
        }

        // Keep the existing valid snapshot boundary explicit while tightening
        // reducer admission below.
        validateSnapshot(snapshotWithScale(12.5f))
    }

    @Test
    fun reducerRejectsInvalidPageScalesWithoutEffectsHistoryOrStateMutation() {
        listOf(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            0f,
            -1f,
            Stage5Limits.MAX_NUMERIC_ABS * 2f
        ).forEach { pixelsPerFoot ->
            val vm = BlueprintViewModel()
            vm.pageScales[0] = PageScale(12f)
            val effects = mutableListOf<AnnotationReducer.EffectIntent>()
            val reducer = AnnotationReducer(vm, effectSink = { effects += it })
            val beforeState = vm.pageScales.toMap()

            assertFalse(
                "invalid page scale must be rejected: $pixelsPerFoot",
                reducer.setScale(0, PageScale(pixelsPerFoot))
            )
            assertEquals(beforeState, vm.pageScales.toMap())
            assertTrue(effects.isEmpty())
            assertFalse(reducer.canUndo(0))
            assertFalse(reducer.canRedo(0))
        }
    }

    private fun snapshotWithScale(pixelsPerFoot: Float): DocumentSnapshotV1 =
        DocumentSnapshotV1(
            schemaVersion = 1,
            snapshotRevision = 0L,
            source = DocumentSourceIdentityV1("content://stage9a/calibration"),
            pages = mapOf(
                0 to PageSnapshotV1(scale = PageScaleSnapshotV1(pixelsPerFoot))
            )
        )

    private fun assertSnapshotRejected(pixelsPerFoot: Float) {
        var rejected = false
        try {
            validateSnapshot(snapshotWithScale(pixelsPerFoot))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("snapshot must reject page scale: $pixelsPerFoot", rejected)
    }
}
