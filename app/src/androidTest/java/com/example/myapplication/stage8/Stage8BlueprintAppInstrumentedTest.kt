package com.example.myapplication.stage8

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.click
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.MainActivity
import com.example.myapplication.AndroidOcrSessionResourceFactory
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage7.OcrSessionResourceGraph
import com.example.myapplication.stage7.Stage7WorkerResourceBoundary
import com.example.myapplication.stage5.Stage5Limits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end qualification through the production BlueprintApp document path. */
@RunWith(AndroidJUnit4::class)
class Stage8BlueprintAppInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun blueprintApp_fixtureViewer_noteAndClearDialogUseProductionPath() {
        withFixture("stage7/pdfs/scanned/scanned_text_fixture.pdf") { uri ->
            val scenario = ActivityScenario.launch<MainActivity>(
                Intent(targetContext, MainActivity::class.java)
                    .putExtra("com.sotaware.construct.stage8.INITIAL_PDF_URI", uri.toString())
            )
            try {
                composeRule.waitUntil(30_000) {
                    try {
                        composeRule.onNodeWithText("SHEET 1").assertIsDisplayed()
                        true
                    } catch (_: AssertionError) {
                        false
                    }
                }
                composeRule.onNodeWithText("SHEET 1").performClick()
                composeRule.waitUntil(30_000) {
                    try {
                        composeRule.onNodeWithContentDescription("Note").assertIsDisplayed()
                        true
                    } catch (_: AssertionError) {
                        false
                    }
                }

                composeRule.awaitPdfCanvas()
                composeRule.onNodeWithContentDescription("Note").performClick()
                val canvasCenter = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.center
                composeRule.onRoot().performTouchInput {
                    click(canvasCenter)
                }
                composeRule.onNodeWithText("Add Note").assertIsDisplayed()
                composeRule.onNode(hasSetTextAction()).performTextInput("app note")
                composeRule.onNodeWithText("Save").performClick()
                composeRule.waitForIdle()
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[BlueprintViewModel::class.java]
                    assertEquals(1, vm.pageNotes[0]?.size)
                    assertEquals("app note", vm.pageNotes[0]?.firstOrNull()?.text)
                }

                composeRule.onNodeWithContentDescription("Clear page").performScrollTo().performClick()
                composeRule.waitUntil(5_000) {
                    try {
                        composeRule.onNodeWithText(
                            "Clear page annotations?",
                            useUnmergedTree = true
                        ).assertIsDisplayed()
                        true
                    } catch (_: AssertionError) {
                        false
                    }
                }
                composeRule.onNodeWithText("Cancel").performClick()
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[BlueprintViewModel::class.java]
                    assertEquals(1, vm.pageNotes[0]?.size)
                }

                composeRule.onNodeWithContentDescription("Clear page").performScrollTo().performClick()
                composeRule.onNodeWithText("Clear").performClick()
                composeRule.waitForIdle()
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[BlueprintViewModel::class.java]
                    assertTrue(vm.pageNotes[0]?.isEmpty() == true)
                }
                composeRule.onAllNodes(hasContentDescription("Undo"))[0].performClick()
                composeRule.waitForIdle()
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[BlueprintViewModel::class.java]
                    assertEquals("app note", vm.pageNotes[0]?.firstOrNull()?.text)
                }
                composeRule.onAllNodes(hasContentDescription("Redo"))[0].performClick()
                composeRule.waitForIdle()
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[BlueprintViewModel::class.java]
                    assertTrue(vm.pageNotes[0]?.isEmpty() == true)
                }
            } finally {
                scenario.close()
            }
        }
    }

    @Test
    fun blueprintApp_landscapeUsesVerticalRailAndFloatingControls() {
        withFixture("stage7/pdfs/scanned/scanned_text_fixture.pdf") { uri ->
            val scenario = ActivityScenario.launch<MainActivity>(
                Intent(targetContext, MainActivity::class.java)
                    .putExtra("com.sotaware.construct.stage8.INITIAL_PDF_URI", uri.toString())
            )
            try {
                // Enter the viewer through the normal production route before
                // forcing recreation. This keeps the assertion about the
                // landscape viewer layout, rather than a selector transition
                // racing document/session restoration.
                composeRule.waitUntil(30_000) {
                    try { composeRule.onNodeWithText("SHEET 1").assertIsDisplayed(); true }
                    catch (_: AssertionError) { false }
                }
                composeRule.onNodeWithText("SHEET 1").performClick()
                composeRule.waitUntil(30_000) {
                    try { composeRule.onNodeWithContentDescription("Note").assertIsDisplayed(); true }
                    catch (_: AssertionError) { false }
                }
                scenario.onActivity { activity ->
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                composeRule.waitUntil(30_000) {
                    try { composeRule.onNodeWithContentDescription("Note").assertIsDisplayed(); true }
                    catch (_: AssertionError) { false }
                }
                composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
                // Wide landscape uses direct secondary actions; compact
                // landscape uses the overflow-labelled equivalent.
                val directMenu = composeRule.onAllNodes(hasContentDescription("Menu"))
                val overflowMenu = composeRule.onAllNodes(hasContentDescription("More actions"))
                assertTrue(
                    directMenu.fetchSemanticsNodes().isNotEmpty() ||
                        overflowMenu.fetchSemanticsNodes().isNotEmpty()
                )
                // The vertical rail is intentionally scrollable on short
                // landscape windows; bring the terminal action into view.
                composeRule.onNodeWithContentDescription("Clear page").performScrollTo().assertIsDisplayed()
            } finally {
                scenario.onActivity { activity ->
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                scenario.close()
            }
        }
    }

    @Test
    fun blueprintApp_searchDialogPublishesPhraseHighlightsThenClearsStaleResults() {
        PDFBoxResourceLoader.init(targetContext.applicationContext)
        withFixture("stage7/pdfs/cropped-rotated/embedded_text_crop_offset_rotate.pdf") { uri ->
            val phrase = embeddedPhrase(uri)
            val scenario = ActivityScenario.launch<MainActivity>(
                Intent(targetContext, MainActivity::class.java)
                    .putExtra("com.sotaware.construct.stage8.INITIAL_PDF_URI", uri.toString())
            )
            try {
                composeRule.waitUntil(30_000) {
                    try { composeRule.onNodeWithText("SHEET 1").assertIsDisplayed(); true }
                    catch (_: AssertionError) { false }
                }
                composeRule.onNodeWithText("SHEET 1").performClick()
                composeRule.waitUntil(30_000) {
                    try { composeRule.onNodeWithContentDescription("Search").assertIsDisplayed(); true }
                    catch (_: AssertionError) { false }
                }

                // Open the actual viewer search dialog and submit a phrase
                // against the fixture's embedded text through BlueprintApp.
                composeRule.onNodeWithContentDescription("Search").performClick()
                composeRule.onNode(hasSetTextAction()).performTextInput(phrase)
                composeRule.onNodeWithText("Search").performClick()
                composeRule.waitUntil(30_000) {
                    var published = false
                    scenario.onActivity { activity ->
                        val vm = ViewModelProvider(activity)[BlueprintViewModel::class.java]
                        published = vm.pageHighlights[0]?.isNotEmpty() == true &&
                            vm.pageSearchTerms[0] == phrase
                    }
                    published
                }

                // A subsequent explicit blank search retires both the old
                // geometry and its term before the empty result is admitted.
                composeRule.waitUntil(5_000) {
                    try { composeRule.onNodeWithContentDescription("Search").assertIsDisplayed(); true }
                    catch (_: AssertionError) { false }
                }
                composeRule.onNodeWithContentDescription("Search").performClick()
                composeRule.onNode(hasSetTextAction()).performTextClearance()
                composeRule.onNodeWithText("Search").performClick()
                composeRule.waitUntil(5_000) {
                    var cleared = false
                    scenario.onActivity { activity ->
                        val vm = ViewModelProvider(activity)[BlueprintViewModel::class.java]
                        cleared = vm.pageHighlights.isEmpty() && vm.pageSearchTerms.isEmpty()
                    }
                    cleared
                }
            } finally {
                scenario.close()
            }
        }
    }

    @Test
    fun blueprintApp_reducerEffectEntersProductionLocalSaveAndSyncPath() {
        withFixture("stage7/pdfs/scanned/scanned_text_fixture.pdf") { uri ->
            val vm = BlueprintViewModel()
            var consumedEffects = 0
            val scenario = ActivityScenario.launch<MainActivity>(Intent(targetContext, MainActivity::class.java))
            try {
                // Keep the normal BlueprintApp composition and viewer/session
                // flow, while observing the narrow post-markDocumentDirty seam.
                scenario.onActivity { activity ->
                    activity.setContent {
                        androidx.compose.material3.MaterialTheme {
                            com.example.myapplication.BlueprintApp(
                                vm = vm,
                                initialPdfUri = uri,
                                onStage8EffectConsumed = { consumedEffects++ }
                            )
                        }
                    }
                }
                composeRule.waitUntil(30_000) {
                    try { composeRule.onNodeWithText("SHEET 1").assertIsDisplayed(); true }
                    catch (_: AssertionError) { false }
                }
                composeRule.onNodeWithText("SHEET 1").performClick()
                composeRule.waitUntil(30_000) {
                    try { composeRule.onNodeWithContentDescription("Note").assertIsDisplayed(); true }
                    catch (_: AssertionError) { false }
                }
                composeRule.awaitPdfCanvas()
                composeRule.onNodeWithContentDescription("Note").performClick()
                val center = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.center
                composeRule.onRoot().performTouchInput { click(center) }
                composeRule.onNodeWithText("Add Note").assertIsDisplayed()
                composeRule.onNode(hasSetTextAction()).performTextInput("effect note")
                composeRule.onNodeWithText("Save").performClick()
                composeRule.waitUntil(5_000) { consumedEffects > 0 }
                assertTrue("reducer effect was not consumed by production dirty/save path", consumedEffects > 0)
            } finally {
                scenario.close()
            }
        }
    }

    @Test
    fun blueprintApp_invalidNoteSavesRetainDialogAndDoNotMutateHistory() {
        withFixture("stage7/pdfs/scanned/scanned_text_fixture.pdf") { uri ->
            val vm = BlueprintViewModel()
            var consumedEffects = 0
            val scenario = ActivityScenario.launch<MainActivity>(Intent(targetContext, MainActivity::class.java))
            try {
                scenario.onActivity { activity ->
                    activity.setContent {
                        androidx.compose.material3.MaterialTheme {
                            com.example.myapplication.BlueprintApp(
                                vm = vm,
                                initialPdfUri = uri,
                                onStage8EffectConsumed = { consumedEffects++ }
                            )
                        }
                    }
                }
                composeRule.waitUntil(30_000) {
                    try { composeRule.onNodeWithText("SHEET 1").assertIsDisplayed(); true }
                    catch (_: AssertionError) { false }
                }
                composeRule.onNodeWithText("SHEET 1").performClick()
                composeRule.waitUntil(30_000) {
                    try { composeRule.onNodeWithContentDescription("Note").assertIsDisplayed(); true }
                    catch (_: AssertionError) { false }
                }
                val historyEpoch = vm.annotationHistoryEpoch()
                val hadUndo = stage8TestReducer(vm).canUndo(0)
                val effectsBeforeInvalid = consumedEffects
                composeRule.awaitPdfCanvas()
                composeRule.onNodeWithContentDescription("Note").performClick()
                tapRootUntilNoteDialog()

                // Blank text is rejected by the production reducer. The
                // dialog and draft stay visible so the user can correct it.
                composeRule.onNodeWithText("Save").performClick()
                composeRule.onNodeWithText("Add Note").assertIsDisplayed()
                assertEquals(historyEpoch, vm.annotationHistoryEpoch())
                assertEquals(hadUndo, stage8TestReducer(vm).canUndo(0))
                assertEquals(effectsBeforeInvalid, consumedEffects)

                // Oversized text follows the same fail-closed path. Keep the
                // input bounded to the documented current-format limit.
                val oversized = "x".repeat(Stage5Limits.MAX_TEXT_CHARS + 1)
                composeRule.onNode(hasSetTextAction()).performTextInput(oversized)
                composeRule.onNodeWithText("Save").performClick()
                composeRule.onNodeWithText("Add Note").assertIsDisplayed()
                // The rejected draft remains editable; assert a bounded
                // prefix instead of materializing the whole oversized string
                // in a failure message.
                composeRule.onNode(hasSetTextAction())
                    .assertTextContains("x".repeat(128), substring = true)
                assertEquals(historyEpoch, vm.annotationHistoryEpoch())
                assertEquals(hadUndo, stage8TestReducer(vm).canUndo(0))
                assertEquals(effectsBeforeInvalid, consumedEffects)

                composeRule.onNode(hasSetTextAction()).performTextClearance()
                composeRule.onNode(hasSetTextAction()).performTextInput("validated note")
                composeRule.onNodeWithText("Save").performClick()
                composeRule.waitUntil(5_000) { vm.pageNotes[0]?.singleOrNull()?.text == "validated note" }
                assertEquals("validated note", vm.pageNotes[0]?.singleOrNull()?.text)
                val reducer = stage8TestReducer(vm)
                assertTrue(reducer.undo(0).changed)
                assertTrue(vm.pageNotes[0].orEmpty().isEmpty())
                assertTrue(reducer.redo(0).changed)
                assertEquals("validated note", vm.pageNotes[0]?.singleOrNull()?.text)
            } finally {
                scenario.close()
            }
        }
    }

    /** Pointer input exists only after the real PDF bitmap has been rendered. */
    private fun tapRootUntilNoteDialog() {
        composeRule.waitUntil(30_000) {
            try {
                val center = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.center
                composeRule.onRoot().performTouchInput { click(center) }
                composeRule.onNodeWithText("Add Note").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val testContext
        get() = InstrumentationRegistry.getInstrumentation().context

    private fun withFixture(assetPath: String, block: (Uri) -> Unit) {
        val uri = Uri.Builder()
            .scheme("content")
            .authority("${testContext.packageName}.stage8.fixture")
            .appendPath(assetPath)
            .build()
        targetContext.contentResolver.openFileDescriptor(uri, "r")?.use { }
            ?: error("content resolver could not open $assetPath")
        block(uri)
    }

    private fun embeddedPhrase(uri: Uri): String = runBlocking {
        val token = DocumentSessionToken(DocumentId.new(), uri.toString(), null, 1L)
        val boundary = Stage7WorkerResourceBoundary(
            workerDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.Main.immediate
        )
        val factory = AndroidOcrSessionResourceFactory(targetContext)
        var graph: OcrSessionResourceGraph? = null
        try {
            graph = boundary.withWorker { factory.open(token) }
            boundary.withWorker {
                val boxes = graph!!.extractEmbeddedText(0)
                boxes.take(2).joinToString(" ") { it.text.trim() }.trim().also {
                    check(it.isNotBlank()) { "embedded fixture produced no searchable phrase" }
                }
            }
        } finally {
            graph?.let { resourceGraph -> boundary.withWorker { resourceGraph.close() } }
        }
    }
}
