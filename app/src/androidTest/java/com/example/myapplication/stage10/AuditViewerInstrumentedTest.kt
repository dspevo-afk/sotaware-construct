package com.example.myapplication.stage10

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.*
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentTransactionBarrier
import com.example.myapplication.stage5.DocumentPhotoAssetStore
import com.example.myapplication.stage8.AnnotationReducer
import com.example.myapplication.stage8.awaitPdfCanvas
import com.example.myapplication.stage8.stage8TestReducer
import com.example.myapplication.ui.ViewerFloatingControl
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real renderer gestures; direct reducer calls only arrange replacement/undo boundaries. */
@RunWith(AndroidJUnit4::class)
class AuditViewerInstrumentedTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext

    @Test fun selectedSingleNoteDeleteThenPinchDoesNotThrowOrRestoreDeletedData() =
        withRenderer { vm, _, _ ->
            val note = Note(.5f, .5f, "delete-selected")
            composeRule.runOnIdle { vm.pageNotes[0]!!.add(note) }
            canvas().performTouchInput { click(center) }
            deleteSelectedNote()
            pinchCanvas()
            composeRule.runOnIdle { assertTrue(vm.pageNotes[0]!!.isEmpty()) }
        }

    @Test fun deletingSelectedEarlierNoteCannotResizeTheRemainingAnnotation() =
        withRenderer { vm, _, _ ->
            val selected = Note(.5f, .5f, "selected")
            val other = Note(.8f, .75f, "unchanged")
            composeRule.runOnIdle { vm.pageNotes[0]!!.addAll(listOf(selected, other)) }
            canvas().performTouchInput { click(center) }
            deleteSelectedNote()
            pinchCanvas()
            composeRule.runOnIdle { assertEquals(listOf(other), vm.pageNotes[0]!!.toList()) }
        }

    @Test fun removingPrecedingNoteResolvesSelectedIdAtPinchAdmission() =
        withRenderer { vm, reducer, _ ->
            val earlier = Note(.2f, .25f, "earlier")
            val selected = Note(.5f, .5f, "selected")
            composeRule.runOnIdle { vm.pageNotes[0]!!.addAll(listOf(earlier, selected)) }
            canvas().performTouchInput { click(center) }
            composeRule.runOnIdle { assertTrue(reducer.deletePdfNote(0, earlier).changed) }
            pinchCanvas()
            composeRule.runOnIdle {
                val result = vm.pageNotes[0]!!.single()
                assertEquals(selected.id, result.id)
                assertTrue("pinch must reach the selected production note", result.fontSizeRatio > selected.fontSizeRatio)
                assertTrue(reducer.undo(0).changed)
                assertEquals(selected, vm.pageNotes[0]!!.single())
            }
        }

    @Test fun overlappingSelectionUndoAndReplacementCannotTargetStaleObjects() =
        withRenderer { vm, reducer, _ ->
            val lower = Note(.5f, .5f, "lower")
            val upper = Note(.5f, .5f, "upper")
            composeRule.runOnIdle { vm.pageNotes[0]!!.addAll(listOf(lower, upper)) }
            canvas().performTouchInput { click(center) }
            composeRule.onAllNodesWithText("Note")[1].performClick()
            composeRule.runOnIdle {
                assertTrue(reducer.deletePdfNote(0, upper).changed)
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertTrue(reducer.undo(0).changed) }
            pinchCanvas()
            composeRule.runOnIdle { assertEquals(listOf(lower, upper), vm.pageNotes[0]!!.toList()) }
            composeRule.runOnIdle { vm.pageNotes[0] = mutableStateListOf(lower.copy(text = "replacement")) }
            pinchCanvas()
            composeRule.runOnIdle { assertEquals(listOf(lower.copy(text = "replacement")), vm.pageNotes[0]!!.toList()) }
        }

    @Test fun photoOverlapsDragTopShapeThenTopNoteInDrawOrder() = withRenderer { vm, reducer, documentId ->
        val photoName = DocumentPhotoAssetStore(target.filesDir, documentId).use { store ->
            val name = instrumentation.context.assets.open("stage7/photos/small_valid_photo.jpg").use { store.publishNewPhoto(it, ".jpg") }
            store.releasePhotoPublication(name)
            name
        }
        val lower = Note(.5f, .5f, "covered", fontSizeRatio = .05f)
        val upper = Note(.5f, .5f, "upper", fontSizeRatio = .05f)
        val lowerShape = Shape(.5f, .5f, 0f, ShapeType.RECTANGLE, 0xff000000.toInt(), true, widthRatio = .3f, heightRatio = .3f)
        val upperShape = lowerShape.copy(id = "upper-shape")
        val pin = PhotoPin(.5f, .5f, imageFileNames = listOf(photoName),
            imageNotes = mapOf(photoName to listOf(lower, upper)), imageShapes = mapOf(photoName to listOf(lowerShape, upperShape)))
        try {
            composeRule.runOnIdle { assertTrue(reducer.addPhotoPin(0, pin).changed) }
            canvas().performTouchInput { click(center) }
            composeRule.onNodeWithText("View").performClick()
            composeRule.waitUntil(20_000) { shownDescription("Photo 0") }
            composeRule.onNodeWithContentDescription("Photo 0").performClick()
            composeRule.waitUntil(20_000) { shownDescription("Full screen photo") }
            composeRule.onRoot().performTouchInput { down(center); moveBy(Offset(20f, 20f), delayMillis = 100); up() }
            composeRule.runOnIdle {
                val updated = vm.pagePhotoPins[0]!!.single()
                assertEquals(listOf(lower, upper), updated.imageNotes[photoName])
                assertEquals(lowerShape, updated.imageShapes[photoName]!![0])
                assertEquals(upperShape.id, updated.imageShapes[photoName]!![1].id)
                assertNotEquals(upperShape, updated.imageShapes[photoName]!![1])
            }
            composeRule.onNodeWithContentDescription("Delete Shape").performClick()
            composeRule.onRoot().performTouchInput { click(center) }
            composeRule.onNodeWithContentDescription("Delete Shape").performClick()
            composeRule.onRoot().performTouchInput { down(center); moveBy(Offset(20f, 20f), delayMillis = 100); up() }
            composeRule.runOnIdle {
                val updated = vm.pagePhotoPins[0]!!.single()
                assertTrue(updated.imageShapes[photoName].isNullOrEmpty())
                assertEquals(lower, updated.imageNotes[photoName]!![0])
                assertEquals(upper.id, updated.imageNotes[photoName]!![1].id)
                assertNotEquals(upper, updated.imageNotes[photoName]!![1])
            }
            composeRule.onNodeWithContentDescription("Close").performClick()
        } finally {
            DocumentPhotoAssetStore(target.filesDir, documentId).use { it.cleanup(photoName) }
        }
    }

    @Test fun floatingControlsAreContainedInNarrowPortraitAndLandscapeViewers() {
        val scenario = ActivityScenario.launch<MainActivity>(Intent(target, MainActivity::class.java))
        try {
            for ((width, height) in listOf(250 to 460, 420 to 190)) {
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme {
                        Box(Modifier.size(width.dp, height.dp).testTag("local-viewer")) {
                            ViewerFloatingControl(Offset(10000f, 10000f), Modifier.testTag("edge-control")) {
                                Box(Modifier.size(180.dp, 60.dp)) { Text("Measured toolbar") }
                            }
                        }
                    }
                } }
                composeRule.waitForIdle()
                val viewer = composeRule.onNodeWithTag("local-viewer").fetchSemanticsNode().boundsInRoot
                val control = composeRule.onNodeWithTag("edge-control").fetchSemanticsNode().boundsInRoot
                assertTrue(control.left >= viewer.left && control.top >= viewer.top)
                assertTrue(control.right <= viewer.right + 1 && control.bottom <= viewer.bottom + 1)
                assertTrue("actual control size must remain visible", control.width > 0 && control.height > 0)
            }
        } finally { scenario.close() }
    }

    private fun canvas() = composeRule.onNodeWithTag(PDF_READY_CANVAS_TAG)
    private fun pinchCanvas() {
        canvas().performTouchInput {
            down(0, center - Offset(35f, 0f)); down(1, center + Offset(35f, 0f))
            moveTo(1, center + Offset(70f, 0f), delayMillis = 100)
            moveTo(1, center + Offset(100f, 0f), delayMillis = 100)
            up(1); up(0)
        }
        composeRule.waitForIdle()
    }
    private fun deleteSelectedNote() {
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onAllNodesWithText("Delete").onLast().performClick()
        composeRule.waitForIdle()
    }
    private fun shownDescription(description: String): Boolean = try {
        composeRule.onNodeWithContentDescription(description).assertIsDisplayed(); true
    } catch (_: AssertionError) { false }

    private fun withRenderer(block: (BlueprintViewModel, AnnotationReducer, DocumentId) -> Unit) {
        PDFBoxResourceLoader.init(target.applicationContext)
        val uri = Uri.Builder().scheme("content").authority("${instrumentation.context.packageName}.stage8.fixture")
            .appendPath("stage7/pdfs/scanned/scanned_text_fixture.pdf").build()
        val id = DocumentId.new()
        val token = DocumentSessionToken(id, uri.toString(), null, 1L)
        val vm = BlueprintViewModel().apply {
            pagePaths[0] = mutableStateListOf(); pageMeasurements[0] = mutableStateListOf()
            pageNotes[0] = mutableStateListOf(); pageShapes[0] = mutableStateListOf(); pagePhotoPins[0] = mutableStateListOf()
        }
        val reducer = stage8TestReducer(vm)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(target, MainActivity::class.java))
        try {
            scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                PdfPageRenderer(uri = uri, sessionToken = token, isSessionCurrent = { it == token },
                    isPageCurrent = { candidate, page -> candidate == token && page == 0 },
                    documentTransactionBarrier = DocumentTransactionBarrier(), pageIndex = 0, mode = ToolMode.PAN,
                    currentScale = null, paths = vm.pagePaths[0]!!, measurements = vm.pageMeasurements[0]!!,
                    notes = vm.pageNotes[0]!!, photoPins = vm.pagePhotoPins[0]!!, shapes = vm.pageShapes[0]!!,
                    annotationReducer = reducer, allPagePhotoPins = vm.pagePhotoPins, searchTerm = "", highlightRects = emptyList(),
                    onScaleDefined = { _, _ -> false }, onDeleteItem = { item ->
                        if (item is PageItem.NoteItem) reducer.deletePdfNote(0, item.data)
                    }, onFullScreenModeChanged = {})
            } } }
            composeRule.awaitPdfCanvas()
            block(vm, reducer, id)
        } finally { scenario.close() }
    }
}
