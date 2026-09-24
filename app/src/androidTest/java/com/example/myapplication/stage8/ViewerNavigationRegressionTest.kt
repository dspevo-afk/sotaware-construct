package com.example.myapplication.stage8

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.*
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentTransactionBarrier
import com.example.myapplication.stage7.Stage7WorkerResourceBoundary
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class ViewerNavigationRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun fullAppPublishesPageCodesInTheBrowserAndKeepsThemAfterRecreation() {
        PDFBoxResourceLoader.init(context)
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "page-code-app-").toFile()
        val file = File(directory, "synthetic.pdf")
        createFixture(file)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .putExtra(STAGE8_INITIAL_PDF_URI_EXTRA, Uri.fromFile(file).toString()))
        try {
            compose.waitUntil(30_000) { compose.onAllNodesWithText("SHEET 1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("SHEET 1").performClick()
            compose.awaitPdfCanvas()
            compose.onNodeWithContentDescription("Menu").performClick()
            compose.onNodeWithText("Identify page codes").performClick()
            val bounds = compose.onNodeWithTag("page-code-region").fetchSemanticsNode().boundsInRoot
            val mapping = ViewerTransform(600f, 800f, bounds.width, bounds.height)
            val start = mapping.toScreen(Point(.73f, .87f))
            val end = mapping.toScreen(Point(.98f, .99f))
            compose.onNodeWithTag("page-code-region").performTouchInput {
                down(Offset(start.x, start.y)); moveTo(Offset(end.x, end.y), 250); up()
            }
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Identified codes on 2 of 3 pages. See them in View Pages.")
                .fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("OK").performClick()
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithText("E-101 · 1").assertIsDisplayed()
            compose.onNodeWithText("A2.03 · 2").assertIsDisplayed()
            compose.onNodeWithText("SHEET 3").assertIsDisplayed()
            scenario.recreate()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("E-101 · 1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("A2.03 · 2").assertIsDisplayed()
            compose.onNodeWithText("SHEET 3").assertIsDisplayed()
        } finally {
            scenario.close()
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }

    @Test fun selectedTitleBlockReadsEveryActualPdfPageAndRestoresOnlyForTheSameSource() = withViewer(selectRegion = true) { token, _, selected ->
        // This renderer-only fixture starts selection through the same requested-selection input as the app menu.
        val bounds = compose.onNodeWithTag("page-code-region").fetchSemanticsNode().boundsInRoot
        val mapping = ViewerTransform(600f, 800f, bounds.width, bounds.height)
        val start = mapping.toScreen(Point(.73f, .87f))
        val end = mapping.toScreen(Point(.98f, .99f))
        compose.onNodeWithTag("page-code-region").performTouchInput {
            down(Offset(start.x, start.y))
            moveTo(Offset(end.x, end.y), 250)
            up()
        }
        val region = requireNotNull(selected())
        val boundary = Stage7WorkerResourceBoundary()
        val index = OcrIndex(context, boundary)
        try {
            val engine = PdfSearchEngine(context, boundary, index)
            val codes = runBlocking {
                PageCodeIndex.scan(3, region, { true }, { engine.getOrBuildPageOcr(token, it) })
            }
            assertEquals(mapOf(0 to "E-101", 1 to "A2.03"), codes)
            val cache = PageCodeCache(context, token)
            cache.write(3, codes)
            assertEquals(codes, PageCodeCache(context, token.copy(generation = 2L)).read(3))
            assertTrue(PageCodeCache(context, token.copy(documentId = DocumentId.new())).read(3).isEmpty())
        } finally { runBlocking { index.closeAndJoin() } }
    }

    @Test fun flickContinuesAfterReleaseAndANewTouchStopsIt() = withViewer { _, _, _ ->
        compose.mainClock.autoAdvance = false
        flick()
        compose.mainClock.advanceTimeByFrame()
        val released = redStripeX()
        compose.mainClock.advanceTimeBy(160)
        assertTrue("A released flick must continue to pan", redStripeX() > released + 10)
        canvas().performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        val stopped = redStripeX()
        compose.mainClock.advanceTimeBy(160)
        assertEquals("A new touch must stop momentum", stopped, redStripeX(), 1f)
        canvas().performTouchInput { up() }
    }

    @Test fun cancelledDragAndToolChangeDoNotLeaveMomentumRunning() = withViewer { _, mode, _ ->
        compose.mainClock.autoAdvance = false
        canvas().performTouchInput {
            down(center)
            repeat(3) { moveBy(Offset(20f, 0f), 16) }
            cancel()
        }
        compose.mainClock.advanceTimeByFrame()
        val cancelled = redStripeX()
        compose.mainClock.advanceTimeBy(160)
        assertEquals(cancelled, redStripeX(), 1f)
        flick()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread { mode.value = ToolMode.NOTE }
        compose.mainClock.advanceTimeByFrame()
        val stopped = redStripeX()
        compose.mainClock.advanceTimeBy(160)
        assertEquals("Changing tools must cancel the old pan", stopped, redStripeX(), 1f)
    }

    private fun canvas() = compose.onNodeWithTag(PDF_READY_CANVAS_TAG)
    private fun flick() = canvas().performTouchInput {
        down(center)
        repeat(4) { moveBy(Offset(20f, 0f), 16) }
        up()
    }

    /** Observe the rendered PDF, not the gesture handler's internal offset. */
    private fun redStripeX(): Float {
        val pixels = canvas().captureToImage().toPixelMap()
        val positions = (0 until pixels.width).filter { x ->
            val color = pixels[x, pixels.height / 2]
            color.red > .75f && color.green < .3f && color.blue < .3f
        }
        assertTrue("Synthetic PDF's red stripe must be visible", positions.isNotEmpty())
        return positions.average().toFloat()
    }

    private fun withViewer(selectRegion: Boolean = false, body: (DocumentSessionToken, androidx.compose.runtime.MutableState<ToolMode>, () -> PageCodeRegion?) -> Unit) {
        PDFBoxResourceLoader.init(context)
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "viewer-navigation-").toFile()
        val file = File(directory, "synthetic.pdf")
        createFixture(file)
        val uri = Uri.fromFile(file)
        val token = DocumentSessionToken(DocumentId.new(), uri.toString(), SourceFingerprint.fromBytes(file.readBytes()), 1L)
        val vm = BlueprintViewModel()
        val reducer = stage8TestReducer(vm)
        val mode = mutableStateOf(ToolMode.PAN)
        var selected: PageCodeRegion? = null
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    PdfPageRenderer(
                        uri = uri, sessionToken = token,
                        isSessionCurrent = { it == token }, isPageCurrent = { candidate, page -> candidate == token && page == 0 },
                        documentTransactionBarrier = DocumentTransactionBarrier(), pageIndex = 0, mode = mode.value,
                        currentScale = null, paths = mutableStateListOf(), measurements = mutableStateListOf(),
                        notes = mutableStateListOf(), photoPins = mutableStateListOf(), shapes = mutableStateListOf(),
                        annotationReducer = reducer, allPagePhotoPins = vm.pagePhotoPins,
                        searchTerm = "", highlightRects = emptyList(), onScaleDefined = { _, _, _ -> false },
                        onDeleteItem = {}, onFullScreenModeChanged = {}, onPageCodeRegionSelected = { selected = it }, selectingPageCode = selectRegion
                    )
                }
            } }
            compose.awaitPdfCanvas()
            body(token, mode) { selected }
        } finally {
            compose.mainClock.autoAdvance = true
            scenario.close()
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }

    private fun createFixture(file: File) {
        PDDocument().use { document ->
            listOf("E-101", "A2.03", "").forEach { code ->
                val page = PDPage(PDRectangle(600f, 800f))
                document.addPage(page)
                PDPageContentStream(document, page).use { out ->
                    out.setNonStrokingColor(255, 0, 0)
                    out.addRect(250f, 0f, 8f, 800f)
                    out.fill()
                    out.setNonStrokingColor(0, 0, 0)
                    out.beginText(); out.setFont(PDType1Font.HELVETICA, 24f)
                    out.newLineAtOffset(50f, 650f); out.showText("SYNTHETIC DRAWING"); out.endText()
                    if (code.isNotEmpty()) {
                        out.beginText(); out.setFont(PDType1Font.HELVETICA, 24f)
                        out.newLineAtOffset(460f, 40f); out.showText(code); out.endText()
                    }
                }
            }
            document.save(file)
        }
    }
}
