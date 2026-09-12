package com.example.myapplication.stage8

import android.net.Uri
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.MainActivity
import com.example.myapplication.PageItem
import com.example.myapplication.stage3.DocumentTransactionBarrier
import com.example.myapplication.PageScale
import com.example.myapplication.PdfPageRenderer
import com.example.myapplication.Point
import com.example.myapplication.ShapeType
import com.example.myapplication.ToolMode
import com.example.myapplication.PhotoPin
import com.example.myapplication.PhotoImageNote
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage5.DocumentPhotoAssetStore
import com.example.myapplication.AndroidOcrSessionResourceFactory
import com.example.myapplication.OcrBox
import com.example.myapplication.stage7.OcrSessionResourceGraph
import com.example.myapplication.stage7.Stage7WorkerResourceBoundary
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device qualification through the production PDF renderer and gesture host.
 * The canvas is intentionally driven through Compose touch input rather than
 * invoking reducer methods as a substitute for UI interaction.
 */
@RunWith(AndroidJUnit4::class)
class Stage8PdfPageRendererInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun productionPdfPageRenderer_croppedRotatedFixtureRendersSearchHighlight() {
        PDFBoxResourceLoader.init(targetContext.applicationContext)
        withFixture("stage7/pdfs/cropped-rotated/embedded_text_crop_offset_rotate.pdf") { uri ->
            val vm = BlueprintViewModel()
            val page = 0
            val token = DocumentSessionToken(DocumentId.new(), uri.toString(), null, 1L)
            val reducer = stage8TestReducer(vm)
            vm.pagePaths[page] = mutableStateListOf()
            vm.pageMeasurements[page] = mutableStateListOf()
            vm.pageNotes[page] = mutableStateListOf()
            vm.pagePhotoPins[page] = mutableStateListOf()
            vm.pageShapes[page] = mutableStateListOf()
            var rendered = false
            val scenario = ActivityScenario.launch<MainActivity>(
                Intent(targetContext, MainActivity::class.java)
            )
            try {
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme {
                        PdfPageRenderer(
                            uri = uri,
                            sessionToken = token,
                            isSessionCurrent = { candidate -> candidate == token },
                            isPageCurrent = { candidate, candidatePage ->
                                candidate == token && candidatePage == page
                            },
                            documentTransactionBarrier = DocumentTransactionBarrier(),
                            pageIndex = page,
                            mode = ToolMode.PAN,
                            currentScale = null,
                            paths = vm.pagePaths[page]!!,
                            measurements = vm.pageMeasurements[page]!!,
                            notes = vm.pageNotes[page]!!,
                            photoPins = vm.pagePhotoPins[page]!!,
                            shapes = vm.pageShapes[page]!!,
                            annotationReducer = reducer,
                            allPagePhotoPins = vm.pagePhotoPins,
                            searchTerm = "embedded text",
                            highlightRects = listOf(android.graphics.RectF(.1f, .1f, .35f, .2f)),
                            onScaleDefined = pageScaleCallback(vm, reducer, page),
                            onDeleteItem = deletePageItemCallback(reducer, page),
                            onFullScreenModeChanged = {},
                            onPageRendered = { rendered = true }
                        )
                    }
                } }
                composeRule.waitUntil(20_000) {
                    rendered && try {
                        composeRule.onRoot().assertIsDisplayed()
                        true
                    } catch (_: IllegalStateException) {
                        // Rendering can complete before the Compose root is registered.
                        false
                    } catch (_: AssertionError) {
                        false
                    }
                }
                composeRule.onRoot().assertIsDisplayed()
            } finally {
                scenario.close()
            }
        }
    }

    @Test
    fun productionPdfPageRenderer_cacheMissLongPressCompletesWithCopyAffordance() = runBlocking {
        PDFBoxResourceLoader.init(targetContext.applicationContext)
        withFixture("stage7/pdfs/scanned/scanned_text_fixture.pdf") { uri ->
            val documentId = DocumentId.new()
            val token = DocumentSessionToken(documentId, uri.toString(), null, 1L)
            val ocrBox = firstRecognizedBox(token)
            val vm = BlueprintViewModel()
            val page = 0
            vm.pagePaths[page] = mutableStateListOf()
            vm.pageMeasurements[page] = mutableStateListOf()
            vm.pageNotes[page] = mutableStateListOf()
            vm.pagePhotoPins[page] = mutableStateListOf()
            vm.pageShapes[page] = mutableStateListOf()
            val reducer = stage8TestReducer(vm)
            var rendered = false
            val scenario = ActivityScenario.launch<MainActivity>(Intent(targetContext, MainActivity::class.java))
            try {
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme {
                        // A fresh renderer/OcrIndex guarantees the long press
                        // enters the real cache-miss completion path. The OCR
                        // factory above is used only to locate known fixture
                        // text; it does not populate this renderer's cache.
                        PdfPageRenderer(
                            uri = uri,
                            sessionToken = token,
                            documentTransactionBarrier = DocumentTransactionBarrier(),
                            pageIndex = page,
                            mode = ToolMode.PAN,
                            currentScale = null,
                            paths = vm.pagePaths[page]!!,
                            measurements = vm.pageMeasurements[page]!!,
                            notes = vm.pageNotes[page]!!,
                            photoPins = vm.pagePhotoPins[page]!!,
                            shapes = vm.pageShapes[page]!!,
                            annotationReducer = reducer,
                            allPagePhotoPins = vm.pagePhotoPins,
                            searchTerm = "",
                            highlightRects = emptyList(),
                            onScaleDefined = pageScaleCallback(vm, reducer, page),
                            onDeleteItem = deletePageItemCallback(reducer, page),
                            onFullScreenModeChanged = {},
                            isSessionCurrent = { it == token },
                            isPageCurrent = { candidate, candidatePage -> candidate == token && candidatePage == page },
                            onPageRendered = { rendered = true }
                        )
                    }
                } }
                composeRule.waitUntil(20_000) { rendered }
                val viewport = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
                // The scanned fixture is rendered at its checked-in 2160x2795
                // page dimensions. Convert the recognized normalized box to
                // the renderer's letterboxed screen coordinates.
                val bitmapWidth = 2160f
                val bitmapHeight = 2795f
                val bitmapAspect = bitmapWidth / bitmapHeight
                val viewportAspect = viewport.width / viewport.height
                val renderedWidth = if (bitmapAspect > viewportAspect) viewport.width else viewport.height * bitmapAspect
                val renderedHeight = if (bitmapAspect > viewportAspect) viewport.width / bitmapAspect else viewport.height
                val imageLeft = viewport.left + (viewport.width - renderedWidth) / 2f
                val imageTop = viewport.top + (viewport.height - renderedHeight) / 2f
                val point = Offset(
                    imageLeft + ocrBox.rectN.centerX() * renderedWidth,
                    imageTop + ocrBox.rectN.centerY() * renderedHeight
                )
                composeRule.onRoot().performTouchInput {
                    down(point)
                    // The production recognizer uses the pointer event clock;
                    // advance that clock while the pointer remains down.
                    advanceEventTime(900)
                    moveBy(Offset(2f, 2f))
                    up()
                }
                composeRule.waitUntil(30_000) {
                    try {
                        composeRule.onNodeWithText("Copy").assertIsDisplayed()
                        true
                    } catch (_: AssertionError) {
                        false
                    }
                }
                val viewerBounds = composeRule.onNodeWithTag(com.example.myapplication.PDF_READY_CANVAS_TAG)
                    .fetchSemanticsNode().boundsInRoot
                val copyBounds = composeRule.onNodeWithTag("sotaware.pdf.copy-control")
                    .fetchSemanticsNode().boundsInRoot
                assertTrue(copyBounds.left >= viewerBounds.left && copyBounds.top >= viewerBounds.top)
                assertTrue(copyBounds.right <= viewerBounds.right + 1 && copyBounds.bottom <= viewerBounds.bottom + 1)
            } finally {
                scenario.close()
            }
        }
    }

    private fun firstRecognizedBox(token: DocumentSessionToken): OcrBox = runBlocking {
        val boundary = Stage7WorkerResourceBoundary(
            workerDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.Main.immediate
        )
        val factory = AndroidOcrSessionResourceFactory(targetContext)
        var graph: OcrSessionResourceGraph? = null
        try {
            graph = boundary.withWorker { factory.open(token) }
            boundary.withWorker {
                graph!!.recognizePage(0).firstOrNull { it.rectN.width() > 0f && it.rectN.height() > 0f }
                    ?: error("scanned fixture produced no usable OCR box")
            }
        } finally {
            graph?.let { resourceGraph -> boundary.withWorker { resourceGraph.close() } }
        }
    }

    @Test
    fun productionPdfPageRenderer_addsAndMovesNoteShapePathAndMeasurement() {
        PDFBoxResourceLoader.init(targetContext.applicationContext)
        withFixture("stage7/pdfs/scanned/scanned_text_fixture.pdf") { uri ->
            val vm = BlueprintViewModel()
            val page = 0
            val token = DocumentSessionToken(DocumentId.new(), uri.toString(), null, 1L)
            vm.pagePaths[page] = mutableStateListOf()
            vm.pageMeasurements[page] = mutableStateListOf()
            vm.pageNotes[page] = mutableStateListOf()
            vm.pagePhotoPins[page] = mutableStateListOf()
            vm.pageShapes[page] = mutableStateListOf()
            vm.pageScales[page] = PageScale(10f)
            val effects = mutableListOf<AnnotationReducer.EffectIntent>()
            val reducer = stage8TestReducer(vm) { effects += it }
            val mode = mutableStateOf(ToolMode.PAN)
            var rendered = false
            val scenario = ActivityScenario.launch<MainActivity>(
                Intent(targetContext, MainActivity::class.java)
            )

            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    PdfPageRenderer(
                        uri = uri,
                        sessionToken = token,
                        isSessionCurrent = { candidate -> candidate == token },
                        isPageCurrent = { candidate, candidatePage ->
                            candidate == token && candidatePage == page
                        },
                        documentTransactionBarrier = DocumentTransactionBarrier(),
                        pageIndex = page,
                        mode = mode.value,
                        currentScale = vm.pageScales[page],
                        paths = vm.pagePaths[page]!!,
                        measurements = vm.pageMeasurements[page]!!,
                        notes = vm.pageNotes[page]!!,
                        photoPins = vm.pagePhotoPins[page]!!,
                        shapes = vm.pageShapes[page]!!,
                        annotationReducer = reducer,
                        allPagePhotoPins = vm.pagePhotoPins,
                        searchTerm = "",
                        highlightRects = emptyList(),
                        onScaleDefined = pageScaleCallback(vm, reducer, page),
                        onDeleteItem = deletePageItemCallback(reducer, page),
                        onFullScreenModeChanged = {},
                        onPageRendered = { rendered = true }
                    )
                }
            } }
            composeRule.waitUntil(20_000) { rendered }
            composeRule.onRoot().assertIsDisplayed()

            // Add a note through the production Canvas pointer path and dialog.
            mode.value = ToolMode.NOTE
            composeRule.waitForIdle()
            val notePoint = Offset(420f, 600f)
            composeRule.onRoot().performTouchInput { click(notePoint) }
            composeRule.onNodeWithText("Add Note").assertIsDisplayed()
            composeRule.onNode(hasSetTextAction()).performTextInput("renderer note")
            composeRule.onNodeWithText("Save").performClick()
            composeRule.runOnIdle {
                assertEquals(1, vm.pageNotes[page]!!.size)
                assertEquals("renderer note", vm.pageNotes[page]!![0].text)
            }

            // Add a shape through the production shape picker.
            mode.value = ToolMode.SHAPE
            composeRule.waitForIdle()
            val shapePoint = Offset(700f, 1000f)
            composeRule.onRoot().performTouchInput { click(shapePoint) }
            composeRule.onNodeWithText("Rectangle").performClick()
            composeRule.runOnIdle { assertEquals(1, vm.pageShapes[page]!!.size) }

            // Draw a path through the production pointer input surface.
            mode.value = ToolMode.PEN
            composeRule.waitForIdle()
            composeRule.onRoot().performTouchInput {
                down(Offset(180f, 500f))
                moveTo(Offset(260f, 540f))
                up()
            }
            composeRule.runOnIdle { assertEquals(1, vm.pagePaths[page]!!.size) }

            // Add a measured segment through two production Canvas taps.
            mode.value = ToolMode.MEASURE
            composeRule.waitForIdle()
            // Use the middle of the actual viewport. In a wide tablet layout
            // the old x=180/320 taps both landed in the left letterbox, which
            // correctly clamps them to one source point (a zero-length line).
            composeRule.onRoot().performTouchInput { click(center - Offset(70f, 0f)) }
            composeRule.onRoot().performTouchInput { click(center + Offset(70f, 0f)) }
            composeRule.runOnIdle {
                assertEquals(1, vm.pageMeasurements[page]!!.size)
                assertTrue(vm.pageMeasurements[page]!!.single().p2.x > vm.pageMeasurements[page]!!.single().p1.x)
            }

            // Select and move the shape, then verify reducer history/effect.
            mode.value = ToolMode.PAN
            composeRule.waitForIdle()
            composeRule.onRoot().performTouchInput { click(shapePoint) }
            composeRule.waitForIdle()
            composeRule.onRoot().performTouchInput {
                down(shapePoint)
                moveTo(shapePoint + Offset(80f, 40f))
                up()
            }
            composeRule.runOnIdle {
                assertTrue(effects.any { it.kind == AnnotationReducer.Kind.ADD })
                assertTrue(effects.any { it.kind == AnnotationReducer.Kind.MOVE })
                assertTrue(reducer.canUndo(page))
            }

            // Rotate the selected shape through the production pinch path,
            // then deselect and reselect it at an offset inside its rotated
            // hit box. This exercises the same geometry used by rendering and
            // hit testing rather than mutating the model behind the UI.
            val movedShapePoint = shapePoint + Offset(80f, 40f)
            composeRule.onRoot().performTouchInput {
                down(0, movedShapePoint - Offset(48f, 0f))
                down(1, movedShapePoint + Offset(48f, 0f))
                // Move one contact around the other in several admitted
                // frames so calculateRotation receives a non-zero delta even
                // on slower emulator input dispatch.
                moveTo(1, movedShapePoint + Offset(48f, 48f), delayMillis = 100)
                moveTo(1, movedShapePoint + Offset(0f, 96f), delayMillis = 100)
                moveTo(1, movedShapePoint + Offset(0f, -96f), delayMillis = 100)
                up(1)
                up(0)
            }
            composeRule.runOnIdle {
                assertTrue(vm.pageShapes[page]!!.first().rotation != 0f)
                assertTrue(effects.any { it.kind == AnnotationReducer.Kind.ROTATE })
            }
            // A production PAN tap on empty page content clears the current
            // selection before the subsequent rotated-shape hit test.
            composeRule.onRoot().performTouchInput { click(Offset(80f, 100f)) }
            val rotatedHit = movedShapePoint + Offset(24f, 20f)
            composeRule.onRoot().performTouchInput { click(rotatedHit) }
            composeRule.onRoot().performTouchInput {
                down(rotatedHit)
                moveTo(rotatedHit + Offset(24f, 12f))
                up()
            }
            composeRule.runOnIdle {
                assertTrue(effects.count { it.kind == AnnotationReducer.Kind.MOVE } >= 2)
            }
            scenario.close()
        }
    }

    @Test
    fun productionPhotoOverlay_backAndImageNoteGesturesUseStoredPhotoAndReducer() {
        PDFBoxResourceLoader.init(targetContext.applicationContext)
        withFixture("stage7/pdfs/scanned/scanned_text_fixture.pdf") { uri ->
            val documentId = DocumentId.new()
            val token = DocumentSessionToken(documentId, uri.toString(), null, 1L)
            // The renderer host is the test-only ComponentActivity, so its
            // process-owned files directory is the durable root for this
            // document-scoped fixture. Production uses the equivalent app
            // files root through the same DocumentPhotoAssetStore contract.
            val photoName = DocumentPhotoAssetStore(targetContext.filesDir, documentId).use { store ->
                val name = testContext.assets.open("stage7/photos/small_valid_photo.jpg").use { input ->
                    store.publishNewPhoto(input, ".jpg")
                }
                store.releasePhotoPublication(name)
                name
            }
            val vm = BlueprintViewModel()
            val page = 0
            vm.pagePaths[page] = mutableStateListOf()
            vm.pageMeasurements[page] = mutableStateListOf()
            vm.pageNotes[page] = mutableStateListOf()
            vm.pagePhotoPins[page] = mutableStateListOf()
            vm.pageShapes[page] = mutableStateListOf()
            // Centering the pin/note makes the test independent of the PDF's
            // aspect ratio while still driving the real renderer hit targets.
            val seededNote = PhotoImageNote(
                .5f,
                .5f,
                "seeded",
                fontSizeRatio = .02f,
                id = "image-note-1"
            )
            val pin = PhotoPin(
                x = .5f, y = .5f, id = "photo-pin-1",
                imageFileNames = listOf(photoName)
            )
            val effects = mutableListOf<AnnotationReducer.EffectIntent>()
            val reducer = stage8TestReducer(vm) { effects += it }
            assertTrue(reducer.addPhotoPin(page, pin).changed)
            assertTrue(reducer.addImageNote(page, pin.id, photoName, seededNote).changed)
            var rendered = false
            val scenario = ActivityScenario.launch<MainActivity>(
                Intent(targetContext, MainActivity::class.java)
            )

            try {
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme {
                        PdfPageRenderer(
                            uri = uri,
                            sessionToken = token,
                            documentTransactionBarrier = DocumentTransactionBarrier(),
                            pageIndex = page,
                            mode = ToolMode.PAN,
                            currentScale = null,
                            paths = vm.pagePaths[page]!!,
                            measurements = vm.pageMeasurements[page]!!,
                            notes = vm.pageNotes[page]!!,
                            photoPins = vm.pagePhotoPins[page]!!,
                            shapes = vm.pageShapes[page]!!,
                            annotationReducer = reducer,
                            allPagePhotoPins = vm.pagePhotoPins,
                            searchTerm = "",
                            highlightRects = emptyList(),
                            onScaleDefined = pageScaleCallback(vm, reducer, page),
                            onDeleteItem = deletePageItemCallback(reducer, page),
                            onFullScreenModeChanged = {},
                            isSessionCurrent = { it == token },
                            isPageCurrent = { candidate, candidatePage -> candidate == token && candidatePage == page },
                            onPageRendered = { rendered = true }
                        )
                    }
                } }
                composeRule.waitUntil(20_000) { rendered }
                val viewport = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
                val bitmapWidth = 2160f
                val bitmapHeight = 2795f
                val bitmapAspect = bitmapWidth / bitmapHeight
                val viewportAspect = viewport.width / viewport.height
                val renderedWidth = if (bitmapAspect > viewportAspect) viewport.width else viewport.height * bitmapAspect
                val renderedHeight = if (bitmapAspect > viewportAspect) viewport.width / bitmapAspect else viewport.height
                val imageLeft = viewport.left + (viewport.width - renderedWidth) / 2f
                val imageTop = viewport.top + (viewport.height - renderedHeight) / 2f
                val pageToScreen: (Float, Float) -> Offset = { x, y ->
                    Offset(imageLeft + x * renderedWidth, imageTop + y * renderedHeight)
                }
                composeRule.onRoot().performTouchInput { click(pageToScreen(.5f, .5f)) }
                composeRule.onNodeWithText("View").assertIsDisplayed().performClick()
                composeRule.waitUntil(20_000) {
                    try { composeRule.onNodeWithContentDescription("Photo 0").assertIsDisplayed(); true }
                    catch (_: AssertionError) { false }
                }
                composeRule.onNodeWithContentDescription("Photo 0").performClick()
                composeRule.waitUntil(20_000) {
                    try { composeRule.onNodeWithContentDescription("Full screen photo").assertIsDisplayed(); true }
                    catch (_: AssertionError) { false }
                }

                // Android system Back is handled by the production fullscreen
                // BackHandler and returns to the gallery before any navigation.
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .uiAutomation.executeShellCommand("input keyevent 4").close()
                composeRule.waitForIdle()
                composeRule.waitUntil(5_000) {
                    composeRule.onAllNodes(hasContentDescription("Full screen photo"))
                        .fetchSemanticsNodes().isEmpty()
                }
                composeRule.waitUntil(20_000) {
                    try { composeRule.onNodeWithContentDescription("Photo 0").assertIsDisplayed(); true }
                    catch (_: AssertionError) { false }
                }
                composeRule.onNodeWithContentDescription("Photo 0").performClick()
                composeRule.waitUntil(20_000) {
                    try { composeRule.onNodeWithContentDescription("Full screen photo").assertIsDisplayed(); true }
                    catch (_: AssertionError) { false }
                }

                // Select the seeded note and pinch it through the real
                // pointer-input surface. The reducer receives one committed
                // replacement on pointer-up and materializes a usable ratio.
                val imageViewport = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
                val imageCenter = imageViewport.center
                composeRule.onRoot().performTouchInput {
                    click(imageCenter)
                    down(0, imageCenter - Offset(40f, 0f))
                    down(1, imageCenter + Offset(40f, 0f))
                    moveTo(1, imageCenter + Offset(80f, 0f))
                    up(1)
                    up(0)
                }
                composeRule.runOnIdle {
                    val updated = vm.pagePhotoPins[page]!!.first().imageNotes[photoName]!!.first()
                    assertTrue(updated.fontSizeRatio > 0f)
                    assertTrue(effects.any { it.kind == AnnotationReducer.Kind.RESIZE || it.kind == AnnotationReducer.Kind.ROTATE })
                    assertTrue(reducer.canUndo(page))
                }
                assertTrue(reducer.undo(page).changed)
                assertEquals(.02f, vm.pagePhotoPins[page]!!.first().imageNotes[photoName]!!.first().fontSizeRatio, .001f)
                assertTrue(reducer.redo(page).changed)
                assertTrue(vm.pagePhotoPins[page]!!.first().imageNotes[photoName]!!.first().fontSizeRatio > 0f)

                // The production fullscreen viewer keeps the edited note
                // selected until its fullscreen surface is dismissed. Use
                // that real close/reopen transition to clear selection before
                // entering the shape tool; do not mutate test state behind the
                // UI.
                composeRule.onNodeWithContentDescription("Close").performClick()
                composeRule.waitUntil(5_000) {
                    composeRule.onAllNodes(hasContentDescription("Full screen photo"))
                        .fetchSemanticsNodes().isEmpty()
                }
                composeRule.waitUntil(20_000) {
                    try { composeRule.onNodeWithContentDescription("Photo 0").assertIsDisplayed(); true }
                    catch (_: AssertionError) { false }
                }
                composeRule.onNodeWithContentDescription("Photo 0").performClick()
                composeRule.waitUntil(20_000) {
                    try { composeRule.onNodeWithContentDescription("Full screen photo").assertIsDisplayed(); true }
                    catch (_: AssertionError) { false }
                }

                // Add an image shape through the fullscreen production
                // toolbar, then resize/rotate and delete it through gesture
                // selection. These are separate reducer history entries.
                composeRule.onNodeWithContentDescription("Add Shape").performClick()
                val reopenedViewport = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
                val shapePoint = Offset(reopenedViewport.width * .75f, reopenedViewport.height * .6f)
                composeRule.onRoot().performTouchInput { click(shapePoint) }
                composeRule.runOnIdle {
                    assertEquals(1, vm.pagePhotoPins[page]!!.first().imageShapes[photoName]?.size)
                }
                composeRule.onRoot().performTouchInput {
                    down(0, shapePoint - Offset(40f, 0f))
                    down(1, shapePoint + Offset(40f, 0f))
                    moveTo(1, shapePoint + Offset(80f, 30f))
                    up(1)
                    up(0)
                }
                composeRule.runOnIdle {
                    assertTrue(effects.any { it.kind == AnnotationReducer.Kind.RESIZE || it.kind == AnnotationReducer.Kind.ROTATE })
                    assertTrue(reducer.canUndo(page))
                }
                // Dismiss and reopen through production fullscreen semantics,
                // then reselect the rotated shape through its rotated hit box.
                composeRule.onNodeWithContentDescription("Close").performClick()
                composeRule.waitUntil(5_000) {
                    composeRule.onAllNodes(hasContentDescription("Full screen photo"))
                        .fetchSemanticsNodes().isEmpty()
                }
                composeRule.waitUntil(20_000) {
                    try { composeRule.onNodeWithContentDescription("Photo 0").assertIsDisplayed(); true }
                    catch (_: AssertionError) { false }
                }
                composeRule.onNodeWithContentDescription("Photo 0").performClick()
                composeRule.waitUntil(20_000) {
                    try { composeRule.onNodeWithContentDescription("Full screen photo").assertIsDisplayed(); true }
                    catch (_: AssertionError) { false }
                }
                val rotatedShapeViewport = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
                val rotatedShapePoint = Offset(rotatedShapeViewport.width * .75f, rotatedShapeViewport.height * .6f)
                composeRule.onRoot().performTouchInput { click(rotatedShapePoint) }
                composeRule.onNodeWithContentDescription("Delete Shape").assertIsDisplayed()
                composeRule.onNodeWithContentDescription("Delete Shape").performClick()
                composeRule.runOnIdle {
                    assertTrue(vm.pagePhotoPins[page]!!.first().imageShapes[photoName].isNullOrEmpty())
                }
            } finally {
                composeRule.runOnIdle { }
                DocumentPhotoAssetStore(targetContext.filesDir, documentId).use { it.cleanup(photoName) }
            }
        }
    }

    private fun pageScaleCallback(
        vm: BlueprintViewModel,
        reducer: AnnotationReducer,
        page: Int
    ): (Float, Float) -> Boolean = { pixelDistance, feet ->
        when (val result = calculatePageScale(pixelDistance, feet)) {
            is CalibrationScaleResult.Accepted -> {
                val scale = PageScale(result.pointsPerFoot)
                reducer.acceptsCurrentSession() &&
                    (vm.pageScales[page] == scale || reducer.setScale(page, scale).changed)
            }
            is CalibrationScaleResult.Rejected -> false
        }
    }

    private fun deletePageItemCallback(
        reducer: AnnotationReducer,
        page: Int
    ): (PageItem) -> Unit = { item ->
        when (item) {
            is PageItem.NoteItem -> if (item.ordinal >= 0) {
                reducer.deletePdfNoteAt(page, item.ordinal, item.data).changed
            } else {
                reducer.deletePdfNote(page, item.data).changed
            }
            is PageItem.ShapeItem -> reducer.deletePdfShape(page, item.data).changed
            is PageItem.Path -> reducer.deletePdfPath(page, item.data).changed
            is PageItem.Measure -> reducer.deleteMeasurement(page, item.data).changed
            is PageItem.PhotoPinItem -> reducer.deletePhotoPin(page, item.data).changed
        }
        Unit
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
}
