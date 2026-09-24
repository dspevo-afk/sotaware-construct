package com.example.myapplication.stage8

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.*
import com.example.myapplication.stage2.*
import com.example.myapplication.stage5.DocumentPhotoAssetStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ToolRestorationInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context
    private fun canvas() = compose.onNodeWithTag(PDF_READY_CANVAS_TAG)
    private fun photoCanvas() = compose.onNodeWithTag("sotaware.photo.annotations")
    private fun tool(name: String) = compose.onNodeWithContentDescription(name).performScrollTo().performClick()
    private fun point(node: SemanticsNodeInteraction, x: Float, y: Float, width: Float = 600f, height: Float = 800f): Offset {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val result = ViewerTransform(width, height, bounds.width, bounds.height).toScreen(Point(x, y))
        return Offset(result.x, result.y)
    }
    private fun tap(x: Float, y: Float) { val p = point(canvas(), x, y); canvas().performTouchInput { click(p) } }
    private fun draw(x1: Float, y1: Float, x2: Float, y2: Float) {
        val a = point(canvas(), x1, y1); val b = point(canvas(), x2, y2)
        canvas().performTouchInput { swipe(a, b, 300) }
    }
    private fun vm(scenario: ActivityScenario<MainActivity>): BlueprintViewModel {
        lateinit var value: BlueprintViewModel
        scenario.onActivity { value = ViewModelProvider(it)[BlueprintViewModel::class.java] }
        return value
    }

    @Test fun projectToolSettingsAppearancePolylineAndAnchoredNoteResize() = withDrawing { scenario, uri ->
        val model = vm(scenario)
        val record = (runBlocking { LocalDocumentRepository(context).readManifest() } as ManifestReadResult.Loaded).entries.single { it.sourceUri == uri.toString() }
        val projectId = "tool-project-${UUID.randomUUID()}"
        DrawingToolSettingsStore(context).bindDocument(record.documentId.value, projectId)
        scenario.recreate(); reopenDrawing()
        tool("Pen")
        compose.onNodeWithContentDescription("Close tool options").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pen").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Blue").performClick()
        compose.onNodeWithContentDescription("Stroke width").performSemanticsAction(SemanticsActions.SetProgress) { it(.02f) }
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Pen settings").fetchSemanticsNodes().isEmpty() }
        draw(.2f, .35f, .7f, .35f)
        compose.runOnIdle {
            val stroke = model.pagePaths[0]!!.single()
            assertEquals(0xff1565c0.toInt(), stroke.colorArgb); assertEquals(.02f, stroke.strokeWidthRatio, .0001f)
        }
        assertTrue(compose.onAllNodesWithContentDescription("Undo").fetchSemanticsNodes().size <= 1)
        tool("Pan")
        tap(.45f, .35f)
        compose.onNodeWithContentDescription("Appearance").performClick()
        compose.onNodeWithContentDescription("Red").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(0xffff0000.toInt(), model.pagePaths[0]!!.single().colorArgb) }
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.runOnIdle { assertEquals(0xff1565c0.toInt(), model.pagePaths[0]!!.single().colorArgb) }

        tool("Note"); tap(.5f, .65f)
        compose.onNode(hasSetTextAction()).performTextInput("Anchored note")
        compose.onNodeWithText("Save").performClick()
        tap(.5f, .65f)
        val center = point(canvas(), .5f, .65f)
        val before = model.pageNotes[0]!!.single()
        canvas().performTouchInput {
            down(0, center - Offset(12f, 0f)); down(1, center + Offset(12f, 0f))
            moveTo(0, center - Offset(48f, 0f), 100); moveTo(1, center + Offset(48f, 0f), 100)
            up(1); up(0)
        }
        compose.runOnIdle {
            val after = model.pageNotes[0]!!.single()
            assertEquals(before.x, after.x, .002f); assertEquals(before.y, after.y, .002f)
            assertTrue(after.fontSizeRatio > before.fontSizeRatio)
            assertTrue(stage8TestReducer(model).setScale(0, PageScale(10f)).changed)
        }
        tool("Polyline"); tap(.2f, .2f); tap(.5f, .2f); tap(.5f, .4f)
        compose.onNodeWithText("Finish").performClick()
        compose.runOnIdle {
            val m = model.pageMeasurements[0]!!.single()
            assertEquals(1, m.intermediatePoints.size); assertEquals("34' 0\"", m.text)
        }
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.runOnIdle { assertTrue(model.pageMeasurements[0]!!.isEmpty()) }
        compose.onNodeWithContentDescription("Redo").performClick()
        scenario.recreate(); reopenDrawing()
        val reopened = DrawingToolSettingsStore(context)
        assertEquals(0xff1565c0.toInt(), reopened.read("project:$projectId").style(ToolMode.PEN).colorArgb)
        assertEquals(0xffff0000.toInt(), reopened.read("project:other-${UUID.randomUUID()}").style(ToolMode.PEN).colorArgb)
        tool("Pen"); draw(.2f, .48f, .7f, .48f)
        compose.runOnIdle { assertEquals(0xff1565c0.toInt(), model.pagePaths[0]!!.last().colorArgb) }
    }

    @Test fun mainToolbarAnnotatesPhotosAndHidesCamera() = withDrawing { scenario, uri ->
        val model = vm(scenario)
        tool("Photo"); tap(.5f, .5f)
        val record = (runBlocking { LocalDocumentRepository(context).readManifest() } as ManifestReadResult.Loaded).entries.single { it.sourceUri == uri.toString() }
        val fileName = DocumentPhotoAssetStore(context.filesDir, record.documentId).use { store ->
            testContext.assets.open("stage7/photos/small_valid_photo.jpg").use { store.publishNewPhoto(it, ".jpg").also(store::releasePhotoPublication) }
        }
        compose.runOnIdle {
            val pin = model.pagePhotoPins[0]!!.single()
            assertTrue(stage8TestReducer(model).attachPhoto(0, pin, fileName).changed)
        }
        tap(.5f, .5f); compose.onNodeWithText("View").performClick()
        compose.onNodeWithContentDescription("Photo 0").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("sotaware.photo.annotations").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Photo").assertDoesNotExist()
        val dimensions = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        testContext.assets.open("stage7/photos/small_valid_photo.jpg").use { BitmapFactory.decodeStream(it, null, dimensions) }
        fun photoPoint(x: Float, y: Float) = point(photoCanvas(), x, y, dimensions.outWidth.toFloat(), dimensions.outHeight.toFloat())
        fun photoTap(x: Float, y: Float) { val p = photoPoint(x, y); photoCanvas().performTouchInput { click(p) } }
        fun photoDraw(y: Float) { val a = photoPoint(.2f, y); val b = photoPoint(.8f, y); photoCanvas().performTouchInput { swipe(a, b, 250) } }
        fun pin() = model.pagePhotoPins[0]!!.single()
        tool("Pen"); photoDraw(.2f)
        compose.onNodeWithContentDescription("Pen").assertIsSelected()
        tool("Highlighter"); photoDraw(.32f)
        compose.onNodeWithContentDescription("Highlighter").assertIsSelected()
        compose.runOnIdle {
            assertEquals(2, pin().imagePaths[fileName]!!.size)
            assertEquals(0xffff0000.toInt(), pin().imagePaths[fileName]!![0].colorArgb)
            assertEquals(0xffffff00.toInt(), pin().imagePaths[fileName]!![1].colorArgb)
        }
        tool("Pan"); photoTap(.5f, .2f); compose.onNodeWithContentDescription("Appearance").performClick()
        compose.onNodeWithContentDescription("Blue").performClick(); compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(0xff1565c0.toInt(), pin().imagePaths[fileName]!!.first().colorArgb) }
        tool("Note"); photoTap(.35f, .55f)
        compose.onNode(hasSetTextAction()).performTextInput("Photo note"); compose.onNodeWithText("Save").performClick()
        tool("Shape"); photoTap(.7f, .6f)
        tool("Calibrate"); photoTap(.15f, .85f); photoTap(.35f, .85f)
        compose.onNode(hasSetTextAction()).performTextInput("10")
        compose.onNodeWithText("Set Scale").performClick()
        tool("Measure"); photoTap(.15f, .75f); photoTap(.55f, .75f)
        tool("Polyline"); photoTap(.1f, .45f); photoTap(.2f, .45f); photoTap(.2f, .6f)
        compose.onNodeWithText("Finish").performClick()
        compose.runOnIdle {
            assertEquals(1, pin().imageNotes[fileName]!!.size)
            assertEquals(1, pin().imageShapes[fileName]!!.size)
            assertEquals(2, pin().imageMeasurements[fileName]!!.size)
            assertEquals("20' 0\"", pin().imageMeasurements[fileName]!!.first().text)
            assertEquals(1, pin().imageMeasurements[fileName]!!.last().intermediatePoints.size)
            assertNotNull(pin().imageScales[fileName])
        }
        scenario.recreate(); reopenDrawing()
        tap(.5f, .5f); compose.onNodeWithText("View").performClick(); compose.onNodeWithContentDescription("Photo 0").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("sotaware.photo.annotations").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(2, pin().imagePaths[fileName]!!.size); assertEquals(2, pin().imageMeasurements[fileName]!!.size) }
    }

    private fun reopenDrawing() {
        compose.waitUntil(30_000) { compose.onAllNodesWithText("SHEET 1").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag(PDF_READY_CANVAS_TAG).fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithText("SHEET 1").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("SHEET 1").performClick()
        compose.awaitPdfCanvas()
    }

    private fun withDrawing(body: (ActivityScenario<MainActivity>, Uri) -> Unit) {
        PDFBoxResourceLoader.init(context)
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "tool-restoration-").toFile()
        val pdf = File(directory, "drawing.pdf")
        PDDocument().use { it.addPage(PDPage(PDRectangle(600f, 800f))); it.save(pdf) }
        val uri = Uri.fromFile(pdf)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(STAGE8_INITIAL_PDF_URI_EXTRA, uri.toString()))
        try {
            compose.waitUntil(30_000) { compose.onAllNodesWithText("SHEET 1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("SHEET 1").performClick(); compose.awaitPdfCanvas()
            body(scenario, uri)
        } finally {
            scenario.close(); check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile); directory.deleteRecursively()
        }
    }
}
