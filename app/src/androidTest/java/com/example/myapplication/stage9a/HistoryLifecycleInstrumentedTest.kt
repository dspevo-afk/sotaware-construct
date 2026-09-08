package com.example.myapplication.stage9a

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.MainActivity
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real owner/re-entry boundary through normal app gestures. */
@RunWith(AndroidJUnit4::class)
class HistoryLifecycleInstrumentedTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun sameDocumentViewerReentryPreservesTheMostRecentUndo() = verifyUndoAfter(false)
    @Test fun activityRecreationPreservesTheSameDocumentUndoOwner() = verifyUndoAfter(true)

    private fun verifyUndoAfter(recreate: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uri = Uri.Builder().scheme("content")
            .authority("${instrumentation.context.packageName}.stage8.fixture")
            .appendPath("stage7/pdfs/scanned/scanned_text_fixture.pdf").build()
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .putExtra("com.sotaware.construct.stage8.INITIAL_PDF_URI", uri.toString())
        )
        val uniqueNote = "history fixture ${UUID.randomUUID()}"
        var originalOwner: BlueprintViewModel? = null
        var originalEpoch = -1L
        try {
            enterViewer()
            composeRule.onNodeWithContentDescription("Note").performClick()
            val center = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.center
            composeRule.onRoot().performTouchInput { click(center) }
            composeRule.onNodeWithText("Add Note").assertIsDisplayed()
            composeRule.onNode(hasSetTextAction()).performTextInput(uniqueNote)
            composeRule.onNodeWithText("Save").performClick()
            composeRule.waitForIdle()
            scenario.onActivity { activity ->
                assertTrue(ViewModelProvider(activity)[BlueprintViewModel::class.java]
                    .pageNotes[0].orEmpty().any { it.text == uniqueNote })
            }
            scenario.onActivity { activity ->
                originalOwner = ViewModelProvider(activity)[BlueprintViewModel::class.java]
                originalEpoch = originalOwner!!.annotationHistoryEpoch()
                assertTrue("the newly added note must have an undo entry before re-entry",
                    com.example.myapplication.stage8.AnnotationReducer(originalOwner!!).canUndo(0))
            }
            if (recreate) scenario.recreate()
            else composeRule.onAllNodes(hasContentDescription("Back"))[0].performClick()
            enterViewer()
            composeRule.waitForIdle()
            scenario.onActivity { activity ->
                val current = ViewModelProvider(activity)[BlueprintViewModel::class.java]
                assertTrue("ViewModel identity was lost across UI recreation", current === originalOwner)
                assertTrue("history owner lost its entry: epoch before=$originalEpoch after=${current.annotationHistoryEpoch()} notePresent=${current.pageNotes[0].orEmpty().any { it.text == uniqueNote }}",
                    com.example.myapplication.stage8.AnnotationReducer(current).canUndo(0))
            }
            composeRule.onAllNodes(hasContentDescription("Undo"))[0].assertIsEnabled().performClick()
            composeRule.waitForIdle()
            scenario.onActivity { activity ->
                assertTrue("the latest note should be undoable after the owner lifecycle transition",
                    ViewModelProvider(activity)[BlueprintViewModel::class.java]
                        .pageNotes[0].orEmpty().none { it.text == uniqueNote })
            }
        } finally {
            scenario.close()
        }
    }

    private fun enterViewer() {
        composeRule.waitUntil(30_000) { shown("SHEET 1") || viewerShown() }
        if (shown("SHEET 1")) composeRule.onNodeWithText("SHEET 1").performClick()
        composeRule.waitUntil(30_000) { viewerShown() }
    }

    private fun shown(text: String): Boolean = try {
        composeRule.onNodeWithText(text).assertIsDisplayed(); true
    } catch (_: AssertionError) { false }

    private fun viewerShown(): Boolean = try {
        composeRule.onNodeWithContentDescription("Note").assertIsDisplayed(); true
    } catch (_: AssertionError) { false }
}
