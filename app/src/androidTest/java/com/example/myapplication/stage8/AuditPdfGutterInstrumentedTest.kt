package com.example.myapplication.stage8

import android.content.Intent
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.MainActivity
import com.example.myapplication.PageScale
import com.example.myapplication.PdfPageRenderer
import com.example.myapplication.R
import com.example.myapplication.ToolMode
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentTransactionBarrier
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Standalone audit regression for starts in the empty gutter around a landscape
 * PDF rendered in an explicitly wide viewer. It deliberately drives the real
 * PdfPageRenderer pointer surface; it does not duplicate coordinate mapping.
 *
 * Against the audited source, gutter taps are clamped into page coordinates
 * and navigation pinches fall through as creation-tool taps on release.
 */
@RunWith(AndroidJUnit4::class)
class AuditPdfGutterInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun scaleGutterTouchesDoNotStartCalibrationOrOfferConfirmation() {
        val points = MeasurementPointSelection()
        val vm = BlueprintViewModel()
        val harness = launchRenderer(ToolMode.SCALE, points, vm)
        try {
            tapLeftGutter(.25f)
            tapLeftGutter(.75f)

            composeRule.runOnIdle {
                assertNull("gutter taps must not begin calibration", points.firstPoint)
                assertNull("gutter taps must not complete calibration", points.secondPoint)
            }
            composeRule.onNodeWithText(targetContext.getString(R.string.confirm_calibration))
                .assertDoesNotExist()
        } finally {
            harness.scenario.close()
            harness.pdf.delete()
        }
    }

    @Test
    fun gutterOriginPinchStillZoomsInCalibrationModeWithoutAddingPoints() =
        assertPinchZoomWithoutCalibrationPoint(.05f, .5f, .05f, .95f)

    @Test
    fun interiorOriginPinchDoesNotCreateCalibrationPointOnRelease() =
        assertPinchZoomWithoutCalibrationPoint(.35f, .65f, .2f, .8f)

    private fun assertPinchZoomWithoutCalibrationPoint(
        start0X: Float, start1X: Float, end0X: Float, end1X: Float
    ) {
        val points = MeasurementPointSelection()
        val vm = BlueprintViewModel()
        val harness = launchRenderer(ToolMode.SCALE, points, vm)
        try {
            val canvas = composeRule.onNodeWithTag(com.example.myapplication.PDF_READY_CANVAS_TAG)
            val size = canvas.fetchSemanticsNode().size
            canvas.performTouchInput {
                pinch(
                    start0 = Offset(size.width * start0X, size.height * .5f),
                    start1 = Offset(size.width * start1X, size.height * .5f),
                    end0 = Offset(size.width * end0X, size.height * .5f),
                    end1 = Offset(size.width * end1X, size.height * .5f)
                )
            }
            composeRule.runOnIdle {
                assertNull(points.firstPoint)
                assertNull(points.secondPoint)
            }
            canvas.performTouchInput { click(Offset(size.width * .5f, size.height * .25f)) }
            composeRule.runOnIdle {
                val first = requireNotNull(points.firstPoint)
                assertTrue("navigation pinch must preserve zoom", first.y > .3f && first.y < .5f)
                assertNull(points.secondPoint)
            }
        } finally {
            harness.scenario.close()
            harness.pdf.delete()
        }
    }

    @Test
    fun measureGutterTouchesDoNotCreateMeasurement() {
        val points = MeasurementPointSelection()
        val vm = BlueprintViewModel()
        val harness = launchRenderer(ToolMode.MEASURE, points, vm)
        try {
            tapLeftGutter(.25f)
            tapLeftGutter(.75f)

            composeRule.runOnIdle {
                assertNull("gutter taps must not leave a pending measure point", points.firstPoint)
                assertTrue("gutter taps must not add a page measurement", vm.pageMeasurements[0]!!.isEmpty())
            }
        } finally {
            harness.scenario.close()
            harness.pdf.delete()
        }
    }

    private fun launchRenderer(
        mode: ToolMode,
        points: MeasurementPointSelection,
        vm: BlueprintViewModel
    ): Harness {
        PDFBoxResourceLoader.init(targetContext.applicationContext)
        val pdf = createSyntheticLandscapePdf()
        val uri = Uri.fromFile(pdf)

        val page = 0
        val token = DocumentSessionToken(DocumentId.new(), uri.toString(), null, 1L)
        vm.pagePaths[page] = mutableStateListOf()
        vm.pageMeasurements[page] = mutableStateListOf()
        vm.pageNotes[page] = mutableStateListOf()
        vm.pagePhotoPins[page] = mutableStateListOf()
        vm.pageShapes[page] = mutableStateListOf()
        val reducer = stage8TestReducer(vm)
        val scenario = try {
            ActivityScenario.launch<MainActivity>(
                Intent(targetContext, MainActivity::class.java)
            )
        } catch (error: Throwable) {
            pdf.delete()
            throw error
        }
        try {
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        // At a 2:1 viewport ratio, the 792x612 landscape page is
                        // still narrower than the viewport and has blank gutters
                        // on both sides. Touch x=5% is outside its fitted page.
                        Box(Modifier.width(360.dp).height(180.dp)) {
                            PdfPageRenderer(
                                uri = uri,
                                sessionToken = token,
                                isSessionCurrent = { it == token },
                                isPageCurrent = { candidate, candidatePage ->
                                    candidate == token && candidatePage == page
                                },
                                documentTransactionBarrier = DocumentTransactionBarrier(),
                                pageIndex = page,
                                mode = mode,
                                pointSelection = points,
                                currentScale = PageScale(12f),
                                paths = vm.pagePaths[page]!!,
                                measurements = vm.pageMeasurements[page]!!,
                                notes = vm.pageNotes[page]!!,
                                photoPins = vm.pagePhotoPins[page]!!,
                                shapes = vm.pageShapes[page]!!,
                                annotationReducer = reducer,
                                allPagePhotoPins = vm.pagePhotoPins,
                                searchTerm = "",
                                highlightRects = emptyList(),
                                onScaleDefined = { _, _, _ -> true },
                                onDeleteItem = {},
                                onFullScreenModeChanged = {}
                            )
                        }
                    }
                }
            }
            composeRule.awaitPdfCanvas()
        } catch (error: Throwable) {
            scenario.close()
            pdf.delete()
            throw error
        }
        return Harness(scenario, pdf)
    }

    private fun createSyntheticLandscapePdf(): File {
        val pdf = File.createTempFile("audit-gutter-", ".pdf", targetContext.cacheDir)
        val document = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(792, 612, 1).create()
            val page = document.startPage(pageInfo)
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawRect(72f, 72f, 720f, 540f, android.graphics.Paint().apply {
                color = 0xffeeeeee.toInt()
            })
            document.finishPage(page)
            FileOutputStream(pdf).use { output -> document.writeTo(output) }
        } catch (error: Throwable) {
            pdf.delete()
            throw error
        } finally {
            document.close()
        }
        return pdf
    }

    private data class Harness(
        val scenario: ActivityScenario<MainActivity>,
        val pdf: File
    )

    private fun tapLeftGutter(yFraction: Float) {
        val canvas = composeRule.onNodeWithTag(com.example.myapplication.PDF_READY_CANVAS_TAG)
        val size = canvas.fetchSemanticsNode().size
        canvas.performTouchInput {
            click(Offset(size.width * .05f, size.height * yFraction))
        }
        composeRule.waitForIdle()
    }

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

}
