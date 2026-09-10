package com.example.myapplication.stage9a

import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.PageScale
import com.example.myapplication.stage8.parseCalibrationInput
import com.example.myapplication.stage8.CalibrationInput
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
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
    fun typedParserRejectsIncompleteNonFiniteAndSubnormalCalibrationInput() {
        listOf(
            "10' garbage",
            "garbage' 6\"",
            "Infinity",
            "1e-45"
        ).forEach { input ->
            assertTrue(
                "invalid calibration input must preserve a typed rejection: $input",
                parseCalibrationInput(input) is CalibrationInput.Rejected
            )
        }
    }

    @Test
    fun typedParserStillAcceptsDecimalFeetAndCompleteFeetAndInches() {
        assertEquals(CalibrationInput.Accepted(12.5f), parseCalibrationInput("12.5"))
        assertEquals(CalibrationInput.Accepted(10.5f), parseCalibrationInput("10' 6\""))
    }

    @Test
    fun snapshotValidationRejectsNonFiniteZeroAndOutOfRangePageScales() {
        listOf(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            0f,
            -1f,
            Math.nextUp(Stage5Limits.MAX_NUMERIC_ABS),
            Float.MAX_VALUE,
            Stage5Limits.MAX_NUMERIC_ABS * 2f
        ).forEach { pointsPerFoot ->
            assertSnapshotRejected(pointsPerFoot)
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
            Math.nextUp(Stage5Limits.MAX_NUMERIC_ABS),
            Float.MAX_VALUE,
            Stage5Limits.MAX_NUMERIC_ABS * 2f
        ).forEach { pointsPerFoot ->
            val vm = BlueprintViewModel()
            vm.pageScales[0] = PageScale(12f)
            val effects = mutableListOf<AnnotationReducer.EffectIntent>()
            val reducer = AnnotationReducer(
                vm,
                effectSink = { effects += it },
                sessionKey = "calibration-test",
                currentSessionKey = { "calibration-test" },
                sessionActivePredicate = { true }
            )
            val beforeState = vm.pageScales.toMap()

            assertFalse(
                "invalid page scale must be rejected: $pointsPerFoot",
                reducer.setScale(0, PageScale(pointsPerFoot)).changed
            )
            assertEquals(beforeState, vm.pageScales.toMap())
            assertTrue(effects.isEmpty())
            assertFalse(reducer.canUndo(0))
            assertFalse(reducer.canRedo(0))
        }
    }

    private fun snapshotWithScale(pointsPerFoot: Float): DocumentSnapshotV1 =
        DocumentSnapshotV1(
            schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
            snapshotRevision = 0L,
            source = DocumentSourceIdentityV1("content://stage9a/calibration"),
            pages = mapOf(
                0 to PageSnapshotV1(scale = PageScaleSnapshotV1(pointsPerFoot))
            )
        )

    private fun assertSnapshotRejected(pointsPerFoot: Float) {
        var rejected = false
        try {
            validateSnapshot(snapshotWithScale(pointsPerFoot))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("snapshot must reject page scale: $pointsPerFoot", rejected)
    }
}
