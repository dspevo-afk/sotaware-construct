package com.example.myapplication.stage9a

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.MainActivity
import com.example.myapplication.PageScale
import com.example.myapplication.stage8.stage8TestReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual calibration gesture/dialog path, not a direct parser/reducer substitute. */
@RunWith(AndroidJUnit4::class)
class CalibrationDialogInstrumentedTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test
    fun invalidCalibrationKeepsDialogAndStateUntilACompleteValidDistanceIsAdmitted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val uri = Uri.Builder().scheme("content")
            .authority("${instrumentation.context.packageName}.stage8.fixture")
            .appendPath("stage7/pdfs/scanned/scanned_text_fixture.pdf").build()
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(target, MainActivity::class.java)
                .putExtra("com.sotaware.construct.stage8.INITIAL_PDF_URI", uri.toString())
        )
        try {
            composeRule.waitUntil(30_000) {
                try { composeRule.onNodeWithText("SHEET 1").assertIsDisplayed(); true }
                catch (_: AssertionError) { false }
            }
            composeRule.onNodeWithText("SHEET 1").performClick()
            composeRule.waitUntil(30_000) {
                try { composeRule.onNodeWithContentDescription("Calibrate").assertIsDisplayed(); true }
                catch (_: AssertionError) { false }
            }
            var previousScales: Map<Int, PageScale> = emptyMap()
            var previousHistoryEpoch = 0L
            var previousCanUndo = false
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[BlueprintViewModel::class.java]
                previousScales = vm.pageScales.toMap()
                previousHistoryEpoch = vm.annotationHistoryEpoch()
                previousCanUndo = stage8TestReducer(vm).canUndo(0)
            }
            composeRule.onNodeWithContentDescription("Calibrate").performScrollTo().performClick()
            // Selecting a non-PAN tool opens the real options sheet.  Dismiss
            // that overlay through its production close action before sending
            // the two calibration taps to the renderer canvas.
            composeRule.onNodeWithContentDescription("Close tool options").performClick()
            composeRule.waitForIdle()
            val bounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
            composeRule.onRoot().performTouchInput {
                click(Offset(bounds.width * .35f, bounds.height * .50f))
            }
            composeRule.waitForIdle()
            composeRule.onRoot().performTouchInput {
                click(Offset(bounds.width * .65f, bounds.height * .50f))
            }
            composeRule.waitUntil(10_000) {
                try { composeRule.onNodeWithText("Confirm Calibration").assertIsDisplayed(); true }
                catch (_: AssertionError) { false }
            }
            composeRule.onNodeWithText("Confirm Calibration").performClick()
            for (input in listOf("10' garbage", "garbage' 6\"", "Infinity", "1e-45")) {
                composeRule.onNode(hasSetTextAction()).performTextClearance()
                composeRule.onNode(hasSetTextAction()).performTextInput(input)
                composeRule.onNodeWithText("Set Scale").performClick()
                composeRule.waitForIdle()
                // Baseline closes the dialog and may mutate live scale here.
                composeRule.onNodeWithText("Calibrate Scale").assertIsDisplayed()
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[BlueprintViewModel::class.java]
                    assertEquals(previousScales, vm.pageScales.toMap())
                    assertEquals(previousHistoryEpoch, vm.annotationHistoryEpoch())
                    assertEquals(previousCanUndo, stage8TestReducer(vm).canUndo(0))
                }
            }
            composeRule.onNode(hasSetTextAction()).performTextClearance()
            composeRule.onNode(hasSetTextAction()).performTextInput("10' 6\"")
            composeRule.onNodeWithText("Set Scale").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Calibrate Scale").assertDoesNotExist()
            scenario.onActivity { activity ->
                val scale = ViewModelProvider(activity)[BlueprintViewModel::class.java].pageScales[0]
                assertTrue(scale != null && scale.pointsPerFoot.isFinite() && scale.pointsPerFoot > 0f)
            }
        } finally {
            scenario.close()
        }
    }
}
