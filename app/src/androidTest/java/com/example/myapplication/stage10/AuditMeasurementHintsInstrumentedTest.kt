package com.example.myapplication.stage10

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.MainActivity
import com.example.myapplication.Measurement
import com.example.myapplication.PDF_READY_CANVAS_TAG
import com.example.myapplication.PageScale
import com.example.myapplication.Point
import com.example.myapplication.stage8.awaitPdfCanvas
import com.example.myapplication.stage8.stage8TestReducer
import java.util.UUID
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises both actual BlueprintApp layout branches and renderer-owned pending points. */
@RunWith(AndroidJUnit4::class)
class AuditMeasurementHintsInstrumentedTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test
    fun portraitHintsAdvanceCancelAndComplete() = exercise(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

    @Test
    fun landscapeHintsAdvanceCancelAndComplete() = exercise(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)

    private fun exercise(orientation: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val settings = target.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val hadHintsPreference = settings.contains(HINTS_DISABLED)
        val previousHintsDisabled = settings.getBoolean(HINTS_DISABLED, true)
        check(settings.edit().putBoolean(HINTS_DISABLED, false).commit())

        val uri = Uri.Builder().scheme("content")
            .authority("${instrumentation.context.packageName}.stage8.fixture")
            .appendPath("stage7/pdfs/scanned/scanned_text_fixture.pdf")
            .appendQueryParameter("audit-hints", UUID.randomUUID().toString())
            .build()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch<MainActivity>(
                Intent(target, MainActivity::class.java)
                    .putExtra("com.sotaware.construct.stage8.INITIAL_PDF_URI", uri.toString())
            )
            val launchedScenario = requireNotNull(scenario)
            launchedScenario.onActivity { it.requestedOrientation = orientation }
            composeRule.waitUntil(30_000L) { shown("SHEET 1") || hasCanvas() }
            if (shown("SHEET 1")) composeRule.onNodeWithText("SHEET 1").performClick()
            composeRule.awaitPdfCanvas()

            val geometry = pdfPageGeometry(target, uri)
            val vm = seedScaleAfterViewerReady(launchedScenario)
            composeRule.waitUntil(5_000L) {
                vm.pageScales[PAGE]?.pointsPerFoot == POINTS_PER_FOOT
            }

            selectTool("Measure")
            composeRule.onNodeWithText(FIRST_POINT_HINT).assertIsDisplayed()

            val first = pageToCanvas(geometry, FIRST_POINT)
            val second = pageToCanvas(geometry, SECOND_POINT)
            canvas().performTouchInput { click(first) }
            composeRule.onNodeWithText(SECOND_POINT_HINT).assertIsDisplayed()

            // The renderer's BackHandler owns an incomplete measurement gesture.
            launchedScenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()
            composeRule.onNodeWithText(FIRST_POINT_HINT).assertIsDisplayed()

            // The actual two-point Cancel target must remain clickable, including
            // in portrait where the HUD would otherwise overlap its center.
            selectTool("Calibrate")
            composeRule.onNodeWithContentDescription("Close tool options").performClick()
            composeRule.onNodeWithText(SCALE_FIRST_POINT_HINT).assertIsDisplayed()
            canvas().performTouchInput { click(first) }
            composeRule.onNodeWithText(SCALE_SECOND_POINT_HINT).assertIsDisplayed()
            canvas().performTouchInput { click(second) }
            composeRule.onNodeWithText("Confirm Calibration").assertIsDisplayed()
            composeRule.onNodeWithText("Page 1 / 1").assertDoesNotExist()
            composeRule.onNodeWithText("Cancel").performClick()
            composeRule.waitUntil(5_000L) { shown(SCALE_FIRST_POINT_HINT) }
            composeRule.onNodeWithText(SCALE_FIRST_POINT_HINT).assertIsDisplayed()
            composeRule.onNodeWithText("Page 1 / 1").assertIsDisplayed()
            assertEquals(POINTS_PER_FOOT, vm.pageScales[PAGE]?.pointsPerFoot ?: Float.NaN, 0f)

            // Also open the real calibration dialog and cancel it. Both paths
            // return to the same first-point phase without changing the scale.
            canvas().performTouchInput { click(first) }
            canvas().performTouchInput { click(second) }
            composeRule.onNodeWithText("Confirm Calibration").performClick()
            composeRule.onNodeWithText(target.getString(com.example.myapplication.R.string.scale_dialog_title))
                .assertIsDisplayed()
            composeRule.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
            composeRule.waitUntil(5_000L) { shown(SCALE_FIRST_POINT_HINT) }
            composeRule.onNodeWithText(SCALE_FIRST_POINT_HINT).assertIsDisplayed()
            composeRule.onNodeWithText("Page 1 / 1").assertIsDisplayed()
            assertEquals(POINTS_PER_FOOT, vm.pageScales[PAGE]?.pointsPerFoot ?: Float.NaN, 0f)

            // A calibrated two-tap measurement must commit its whole-inch
            // label, clear the pending points, and return to PAN.
            selectTool("Measure")
            composeRule.onNodeWithText(FIRST_POINT_HINT).assertIsDisplayed()
            canvas().performTouchInput { click(first) }
            composeRule.onNodeWithText(SECOND_POINT_HINT).assertIsDisplayed()
            canvas().performTouchInput { click(second) }
            composeRule.waitUntil(10_000L) { currentMeasurement(launchedScenario) != null }
            val created = requireNotNull(currentMeasurement(launchedScenario))
            assertEquals(
                expectedLabel(created, geometry, POINTS_PER_FOOT),
                created.text
            )
            composeRule.waitUntil(5_000L) { !shown(FIRST_POINT_HINT) }
            composeRule.onNodeWithText(FIRST_POINT_HINT).assertDoesNotExist()

            // Re-entering Measure after production onAnnotationAdded switched
            // the viewer to PAN must start a fresh first-point phase.
            selectTool("Measure")
            composeRule.onNodeWithText(FIRST_POINT_HINT).assertIsDisplayed()
            selectTool("Pan")

            // Select the stored segment through the real PAN hit test, then
            // drag its endpoint. The label must follow the corrected source
            // geometry and the calibrated points-per-foot value.
            val createdStart = pageToCanvas(geometry, created.p1)
            val createdEnd = pageToCanvas(geometry, created.p2)
            val draggedTarget = pageToCanvas(geometry, Point(.98f, .97f))
            canvas().performTouchInput {
                click(createdStart)
            }
            composeRule.waitUntil(5_000L) {
                try {
                    composeRule.onNodeWithTag("sotaware.pdf.annotation-toolbar").assertIsDisplayed()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
            canvas().performTouchInput {
                down(createdEnd)
                moveTo(draggedTarget, delayMillis = 100)
                up()
            }
            composeRule.waitUntil(10_000L) {
                val current = currentMeasurement(launchedScenario)
                current != null && current.p2 != created.p2 &&
                    current.text == expectedLabel(current, geometry, POINTS_PER_FOOT)
            }
            val dragged = requireNotNull(currentMeasurement(launchedScenario))
            assertNotEquals(created.p2, dragged.p2)
            assertEquals(expectedLabel(dragged, geometry, POINTS_PER_FOOT), dragged.text)
            val viewerBounds = canvas().fetchSemanticsNode().boundsInRoot
            val toolbarBounds = composeRule.onNodeWithTag("sotaware.pdf.annotation-toolbar")
                .fetchSemanticsNode().boundsInRoot
            assertTrue(toolbarBounds.left >= viewerBounds.left && toolbarBounds.top >= viewerBounds.top)
            assertTrue(toolbarBounds.right <= viewerBounds.right + 1 && toolbarBounds.bottom <= viewerBounds.bottom + 1)
        } catch (failure: Throwable) {
            runCatching { println(composeRule.onRoot(useUnmergedTree = true).printToString()) }
            throw failure
        } finally {
            scenario?.close()
            val editor = settings.edit()
            if (hadHintsPreference) editor.putBoolean(HINTS_DISABLED, previousHintsDisabled)
            else editor.remove(HINTS_DISABLED)
            check(editor.commit())
        }
    }

    private fun seedScaleAfterViewerReady(scenario: ActivityScenario<MainActivity>): BlueprintViewModel {
        var vm: BlueprintViewModel? = null
        scenario.onActivity { activity ->
            val current = ViewModelProvider(activity)[BlueprintViewModel::class.java]
            ensurePage(current, PAGE)
            val result = stage8TestReducer(current).setScale(PAGE, PageScale(POINTS_PER_FOOT))
            assertTrue(result.changed || current.pageScales[PAGE]?.pointsPerFoot == POINTS_PER_FOOT)
            vm = current
        }
        return requireNotNull(vm)
    }

    private fun currentMeasurement(scenario: ActivityScenario<MainActivity>): Measurement? {
        var measurement: Measurement? = null
        scenario.onActivity { activity ->
            measurement = ViewModelProvider(activity)[BlueprintViewModel::class.java]
                .pageMeasurements[PAGE]?.singleOrNull()
        }
        return measurement
    }

    private fun selectTool(contentDescription: String) {
        composeRule.onNodeWithContentDescription(contentDescription).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun canvas() = composeRule.onNodeWithTag(PDF_READY_CANVAS_TAG)

    private fun hasCanvas(): Boolean = try {
        canvas().assertIsDisplayed()
        true
    } catch (_: AssertionError) {
        false
    }

    private fun shown(text: String): Boolean = try {
        composeRule.onNodeWithText(text).assertIsDisplayed()
        true
    } catch (_: AssertionError) {
        false
    }

    private fun pageToCanvas(geometry: PageGeometry, point: Point): Offset {
        val bounds = canvas().fetchSemanticsNode().boundsInRoot
        val viewportWidth = bounds.width
        val viewportHeight = bounds.height
        val pageAspect = geometry.width / geometry.height
        val renderedWidth: Float
        val renderedHeight: Float
        if (pageAspect > viewportWidth / viewportHeight) {
            renderedWidth = viewportWidth
            renderedHeight = viewportWidth / pageAspect
        } else {
            renderedWidth = viewportHeight * pageAspect
            renderedHeight = viewportHeight
        }
        return Offset(
            (viewportWidth - renderedWidth) / 2f + point.x * renderedWidth,
            (viewportHeight - renderedHeight) / 2f + point.y * renderedHeight
        )
    }

    private fun expectedLabel(measurement: Measurement, geometry: PageGeometry, pointsPerFoot: Float): String {
        val dx = (measurement.p1.x - measurement.p2.x).toDouble() * geometry.width
        val dy = (measurement.p1.y - measurement.p2.y).toDouble() * geometry.height
        val sourceDistance = kotlin.math.sqrt(dx * dx + dy * dy)
        val totalInches = (sourceDistance / pointsPerFoot.toDouble() * 12.0).roundToLong()
        val wholeFeet = totalInches / 12
        val inches = totalInches % 12
        return if (wholeFeet > 0) "$wholeFeet' $inches\"" else "$inches\""
    }

    private fun pdfPageGeometry(context: Context, uri: Uri): PageGeometry {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("could not open PDF descriptor for measurement geometry")
        return descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                renderer.openPage(PAGE).use { page ->
                    PageGeometry(page.width.toFloat(), page.height.toFloat())
                }
            }
        }
    }

    private fun ensurePage(vm: BlueprintViewModel, page: Int) {
        if (vm.pagePaths[page] == null) vm.pagePaths[page] = androidx.compose.runtime.mutableStateListOf()
        if (vm.pageMeasurements[page] == null) vm.pageMeasurements[page] = androidx.compose.runtime.mutableStateListOf()
        if (vm.pageNotes[page] == null) vm.pageNotes[page] = androidx.compose.runtime.mutableStateListOf()
        if (vm.pagePhotoPins[page] == null) vm.pagePhotoPins[page] = androidx.compose.runtime.mutableStateListOf()
        if (vm.pageShapes[page] == null) vm.pageShapes[page] = androidx.compose.runtime.mutableStateListOf()
    }

    private data class PageGeometry(val width: Float, val height: Float)

    private companion object {
        const val PAGE = 0
        const val POINTS_PER_FOOT = 10f
        const val HINTS_DISABLED = "hints_disabled"
        const val FIRST_POINT_HINT = "Tap first point to start measuring"
        const val SECOND_POINT_HINT = "Tap second point to complete measurement"
        const val SCALE_FIRST_POINT_HINT = "Tap two points of a known distance to calibrate"
        const val SCALE_SECOND_POINT_HINT = "Tap second point, then enter known distance"
        val FIRST_POINT = Point(.27f, .39f)
        val SECOND_POINT = Point(.61f, .57f)
    }
}
