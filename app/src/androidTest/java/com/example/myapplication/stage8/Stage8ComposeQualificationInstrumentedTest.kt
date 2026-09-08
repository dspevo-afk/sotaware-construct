package com.example.myapplication.stage8

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredWidth
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.myapplication.ToolMode
import com.example.myapplication.MainActivity
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.DrawnPath
import com.example.myapplication.Measurement
import com.example.myapplication.Note
import com.example.myapplication.PageScale
import com.example.myapplication.PhotoImageNote
import com.example.myapplication.PhotoPin
import com.example.myapplication.Point
import com.example.myapplication.Shape
import com.example.myapplication.ShapeType
import androidx.compose.runtime.mutableStateListOf
import android.graphics.RectF
import com.example.myapplication.OcrBox
import com.example.myapplication.PdfSearchEngine
import com.example.myapplication.ui.ToolOptionsSheet
import com.example.myapplication.ui.ToolRail
import com.example.myapplication.ui.ViewerTopBar
import com.example.myapplication.ui.FloatingViewerControls
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Functional Compose qualification of the production Stage 8 controls. */
@RunWith(AndroidJUnit4::class)
class Stage8ComposeQualificationInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun toolRail_exposesSelectionAndClearSemantics_inTallPortraitLayout() {
        var clearCount = 0
        var selectedMode = ToolMode.PAN
        composeRule.activity.setContent {
            MaterialTheme {
                var selected by remember { mutableStateOf(ToolMode.PAN) }
                ToolRail(
                    currentMode = selected,
                    onModeSelected = { selected = it; selectedMode = it },
                    canUndo = true,
                    canRedo = false,
                    onUndo = {},
                    onRedo = {},
                    onClearPage = { clearCount++ },
                    isVertical = true,
                    modifier = Modifier.height(700.dp)
                )
            }
        }
        composeRule.onNodeWithContentDescription("Note").performClick()
        composeRule.runOnIdle { assertEquals(ToolMode.NOTE, selectedMode) }
        composeRule.onNodeWithContentDescription("Clear page").performClick()
        composeRule.runOnIdle { assertEquals(1, clearCount) }
    }

    @Test
    fun horizontalToolRail_reachesAnnotationAction_inWideLandscapeLayout() {
        var selectedMode = ToolMode.PAN
        composeRule.activity.setContent {
            MaterialTheme {
                ToolRail(
                    currentMode = ToolMode.PAN,
                    onModeSelected = { selectedMode = it },
                    canUndo = false,
                    canRedo = false,
                    onUndo = {},
                    onRedo = {},
                    onClearPage = {},
                    isVertical = false,
                    modifier = Modifier.width(1000.dp).height(80.dp)
                )
            }
        }
        composeRule.onNodeWithContentDescription("Note").performClick()
        composeRule.runOnIdle { assertEquals(ToolMode.NOTE, selectedMode) }
    }

    @Test
    fun toolOptions_closeButtonDismissesVisibleProductionSheet() {
        var dismissed = false
        composeRule.activity.setContent {
            MaterialTheme {
                var visible by remember { mutableStateOf(true) }
                ToolOptionsSheet(currentMode = ToolMode.NOTE, isVisible = visible,
                    isTablet = false, currentScale = null,
                    onDismiss = { visible = false; dismissed = true })
            }
        }
        composeRule.onNodeWithContentDescription("Close tool options").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(true, dismissed) }
    }

    @Test
    fun narrowViewerTopBar_placesSecondaryActionsInReachableOverflow() {
        var menuCount = 0
        composeRule.activity.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Box(Modifier.width(240.dp)) {
                    ViewerTopBar(
                        currentPage = 0, totalPages = 2, pdfName = "fixture.pdf",
                        onBack = {}, onPreviousPage = {}, onNextPage = {},
                        onSearch = {}, onScreenshot = {}, onMenu = { menuCount++ }
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Screenshot").assertIsDisplayed()
        composeRule.onNodeWithText("Menu").performClick()
        composeRule.runOnIdle { assertEquals(1, menuCount) }
    }

    @Test
    fun wideLandscapeFloatingControls_haveDistinctClickableBounds() {
        val clicks = mutableListOf<String>()
        composeRule.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        composeRule.activity.setContent {
            MaterialTheme {
                FloatingViewerControls(
                    currentPage = 0,
                    totalPages = 2,
                    onBack = { clicks += "back" },
                    onPreviousPage = {},
                    onNextPage = {},
                    onSearch = { clicks += "search" },
                    onScreenshot = { clicks += "screenshot" },
                    onMenu = { clicks += "menu" },
                    canUndo = true,
                    canRedo = true,
                    onUndo = { clicks += "undo" },
                    onRedo = { clicks += "redo" },
                    // Match the production caller's natural height. A fixed
                    // total height can be consumed by tablet status/taskbar insets.
                    modifier = androidx.compose.ui.Modifier.requiredWidth(1000.dp)
                )
            }
        }
        val labels = listOf("Undo", "Redo", "Search", "Screenshot", "Menu")
        val bounds = labels.map { label ->
            composeRule.onNodeWithContentDescription(label).assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
        }.sortedBy { it.left }
        assertEquals(labels.size, bounds.size)
        assertTrue("floating controls must have visible area", bounds.all { it.width > 0f && it.height > 0f })
        bounds.zipWithNext().forEach { (left, right) ->
            assertTrue("floating controls overlap", left.right <= right.left)
        }
        labels.forEach { label -> composeRule.onNodeWithContentDescription(label).performClick() }
        composeRule.runOnIdle {
            val decor = composeRule.activity.window.decorView
            val location = IntArray(2).also { decor.getLocationOnScreen(it) }
            assertEquals(
                "Control bounds=$bounds; window=${decor.width}x${decor.height} at ${location.toList()}; focused=${decor.hasWindowFocus()}",
                setOf("undo", "redo", "search", "screenshot", "menu"),
                clicks.toSet()
            )
        }
    }

    @Test
    fun productionReducer_onDevice_clearsRestoresAndRedoesPdfAndImageDomains() {
        val vm = BlueprintViewModel()
        val page = 0
        vm.pagePaths[page] = mutableStateListOf(DrawnPath(listOf(Point(1f, 1f)), 1, 2f, false))
        vm.pageMeasurements[page] = mutableStateListOf(Measurement(Point(1f, 1f), Point(4f, 1f), "3"))
        vm.pageNotes[page] = mutableStateListOf(Note(2f, 3f, "note"))
        val pin = PhotoPin(.2f, .3f).also {
            it.imageFileNames += "photo.jpg"
            it.imageNotes["photo.jpg"] = mutableListOf(PhotoImageNote(.1f, .2f, "caption"))
        }
        vm.pagePhotoPins[page] = mutableStateListOf(pin)
        vm.pageShapes[page] = mutableStateListOf(Shape(.5f, .5f, 10f, 10f, 45f, ShapeType.RECTANGLE, 1, 1f))
        vm.pageScales[page] = PageScale(12f)
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = AnnotationReducer(vm, effectSink = { effects += it })

        assertTrue(reducer.clearPage(page))
        assertTrue(vm.pagePaths[page]!!.isEmpty() && vm.pagePhotoPins[page]!!.isEmpty())
        assertFalse(vm.pageScales.containsKey(page))
        assertTrue(reducer.undo(page))
        assertEquals("caption", vm.pagePhotoPins[page]!![0].imageNotes["photo.jpg"]!![0].text)
        assertEquals(12f, vm.pageScales[page]!!.pixelsPerFoot)
        assertTrue(reducer.redo(page))
        assertTrue(vm.pageNotes[page]!!.isEmpty())
        assertTrue(effects.any { it.kind == AnnotationReducer.Kind.CLEAR })
        assertTrue(effects.any { it.kind == AnnotationReducer.Kind.UNDO })
        assertTrue(effects.any { it.kind == AnnotationReducer.Kind.REDO })
    }

    @Test
    fun productionStage8Seams_onDevice_preservePhraseAndStaleSelectionRules() {
        val boxes = listOf(
            OcrBox("North", RectF(0f, 0f, .2f, .1f)),
            OcrBox("Wall", RectF(.2f, 0f, .4f, .1f)),
            OcrBox("Door", RectF(.4f, 0f, .6f, .1f))
        )
        assertEquals(2, PdfSearchEngine.matchPhrase(boxes, "north wall").size)
        assertTrue(PdfSearchEngine.matchPhrase(boxes, "north window").isEmpty())
        val session = Any()
        assertEquals(1, OcrSelection.boxIndexOrNull(session, session, 2, 2, 1, boxes.size))
        assertEquals(null, OcrSelection.boxIndexOrNull(session, Any(), 2, 2, 1, boxes.size))
        assertEquals(null, OcrSelection.boxIndexOrNull(session, session, 3, 2, 1, boxes.size))
        assertTrue(AnnotationGeometry.rotatedNoteContains(50f, 50f, 20f, 40f, 60f, 20f, 90f))

        val interactions = Stage8InteractionController()
        var clearCount = 0
        interactions.requestClear(session, 2)
        interactions.cancelClear()
        assertFalse(interactions.hasPendingClear)
        interactions.requestClear(session, 2)
        assertFalse(interactions.confirmClear(Any(), 2) { clearCount++ })
        interactions.requestClear(session, 2)
        assertTrue(interactions.confirmClear(session, 2) { clearCount++ })
        assertEquals(1, clearCount)
        var fullscreenClosed = false
        assertTrue(interactions.onBack(true) { fullscreenClosed = true })
        assertTrue(fullscreenClosed)
    }
}
