package com.example.myapplication.stage9b

import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.DrawnPath
import com.example.myapplication.MainActivity
import com.example.myapplication.Measurement
import com.example.myapplication.Note
import com.example.myapplication.PageScale
import com.example.myapplication.PdfPageRenderer
import com.example.myapplication.PhotoPin
import com.example.myapplication.Point
import com.example.myapplication.Shape
import com.example.myapplication.ToolMode
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentTransactionBarrier
import com.example.myapplication.stage8.AnnotationReducer
import com.example.myapplication.stage8.stage8TestReducer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Qualification of the production PDF pointer loop at the stroke admission boundary.
 *
 * These tests deliberately keep the reducer behind PdfPageRenderer and inject actual
 * Compose touch events.  The only direct reducer call is the post-commit undo control;
 * the measured path is admitted by the renderer's pointer coroutine.
 */
@RunWith(AndroidJUnit4::class)
class Stage9BStrokeCancellationInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun cancelledPenStrokeLeavesCanonicalStateThenCompletedPenUsesOnlyItsOwnPoints() {
        val harness = launchRenderer(ToolMode.PEN)
        try {
            val aspect = pdfPageAspect(harness.uri, pageIndex = 0)
            val beforeState = annotationState(harness.vm)
            val beforeHistory = harness.vm.annotationHistory.captureCheckpoint()
            val beforeEffects = harness.effects.toList()

            cancelIncompleteStroke(
                harness = harness,
                pageAspect = aspect,
                down = Point(.08f, .12f),
                moves = listOf(Point(.76f, .78f), Point(.9f, .88f))
            )

            assertEquals(beforeState, annotationState(harness.vm))
            assertEquals(beforeHistory, harness.vm.annotationHistory.captureCheckpoint())
            assertEquals(beforeEffects, harness.effects.toList())
            assertFalse(harness.reducer.canUndo(0))

             val completed = listOf(
                 Point(.2f, .23f),
                 Point(.43f, .37f),
                 Point(.7f, .58f)
             )
             // This is a fresh real UP after the consumed all-up cancellation;
             // it must remain a normal commit and must not inherit the canceled draft.
             completeStroke(harness, aspect, pageIndex = 0, expected = completed)
            assertCommittedPathAndUndo(
                harness = harness,
                pageIndex = 0,
                expected = completed,
                highlighter = false,
                beforeState = beforeState,
                beforeHistory = beforeHistory
            )
        } finally {
            harness.scenario.close()
        }
    }

    @Test
    fun cancelledHighlighterStrokeLeavesCanonicalStateThenCompletedHighlighterUsesOnlyItsOwnPoints() {
        val harness = launchRenderer(ToolMode.HIGHLIGHTER)
        try {
            val aspect = pdfPageAspect(harness.uri, pageIndex = 0)
            val beforeState = annotationState(harness.vm)
            val beforeHistory = harness.vm.annotationHistory.captureCheckpoint()
            val beforeEffects = harness.effects.toList()

            cancelIncompleteStroke(
                harness = harness,
                pageAspect = aspect,
                down = Point(.1f, .14f),
                moves = listOf(Point(.8f, .8f), Point(.92f, .9f))
            )

            assertEquals(beforeState, annotationState(harness.vm))
            assertEquals(beforeHistory, harness.vm.annotationHistory.captureCheckpoint())
            assertEquals(beforeEffects, harness.effects.toList())
            assertFalse(harness.reducer.canUndo(0))

             val completed = listOf(
                 Point(.19f, .27f),
                 Point(.45f, .4f),
                 Point(.73f, .62f)
             )
             // This is a fresh real UP after the consumed all-up cancellation;
             // it must remain a normal commit and must not inherit the canceled draft.
             completeStroke(harness, aspect, pageIndex = 0, expected = completed)
            assertCommittedPathAndUndo(
                harness = harness,
                pageIndex = 0,
                expected = completed,
                highlighter = true,
                beforeState = beforeState,
                beforeHistory = beforeHistory
            )
        } finally {
            harness.scenario.close()
        }
    }

    @Test
    fun pageSessionToolAndViewportChangesWhilePointerDownDiscardTheOldStroke() {
        val harness = launchRenderer(ToolMode.PEN)
        try {
            val pageZeroAspect = pdfPageAspect(harness.uri, pageIndex = 0)
            val baselineState = annotationState(harness.vm)
            val baselineHistory = harness.vm.annotationHistory.captureCheckpoint()

            var renderedRevision = harness.rendered.get()
            cancelIncompleteStroke(
                harness = harness,
                pageAspect = pageZeroAspect,
                down = Point(.12f, .18f),
                moves = listOf(Point(.38f, .3f), Point(.54f, .42f)),
                whileDown = { harness.page.value = 1 }
            )
            awaitRendered(harness, renderedRevision)
            renderedRevision = harness.rendered.get()
            assertEquals(1, harness.page.value)
            assertEquals(baselineState, annotationState(harness.vm))
            assertEquals(baselineHistory, harness.vm.annotationHistory.captureCheckpoint())
            assertTrue(harness.effects.isEmpty())

            val replacementToken = DocumentSessionToken(
                documentId = DocumentId.new(),
                sourceUri = harness.uri.toString(),
                sourceFingerprint = null,
                generation = 2L
            )
            cancelIncompleteStroke(
                harness = harness,
                pageAspect = pdfPageAspect(harness.uri, pageIndex = 1),
                down = Point(.15f, .2f),
                moves = listOf(Point(.4f, .33f), Point(.57f, .46f)),
                whileDown = { harness.token.value = replacementToken }
            )
            awaitRendered(harness, renderedRevision)
            renderedRevision = harness.rendered.get()
            assertEquals(replacementToken, harness.token.value)
            assertEquals(baselineState, annotationState(harness.vm))
            assertEquals(baselineHistory, harness.vm.annotationHistory.captureCheckpoint())
            assertTrue(harness.effects.isEmpty())

            cancelIncompleteStroke(
                harness = harness,
                pageAspect = pdfPageAspect(harness.uri, pageIndex = 1),
                down = Point(.16f, .22f),
                moves = listOf(Point(.42f, .35f), Point(.59f, .48f)),
                whileDown = { harness.mode.value = ToolMode.HIGHLIGHTER }
            )
            composeRule.waitForIdle()
            assertEquals(ToolMode.HIGHLIGHTER, harness.mode.value)
            assertEquals(baselineState, annotationState(harness.vm))
            assertEquals(baselineHistory, harness.vm.annotationHistory.captureCheckpoint())
            assertTrue(harness.effects.isEmpty())

            val oldViewport = composeRule.onNodeWithTag(VIEWPORT_TAG)
                .fetchSemanticsNode()
                .boundsInRoot
            cancelIncompleteStroke(
                harness = harness,
                pageAspect = pdfPageAspect(harness.uri, pageIndex = 1),
                down = Point(.17f, .24f),
                moves = listOf(Point(.44f, .36f), Point(.61f, .5f)),
                whileDown = { harness.compact.value = true }
            )
            composeRule.waitUntil(30_000L) {
                val next = composeRule.onNodeWithTag(VIEWPORT_TAG)
                    .fetchSemanticsNode()
                    .boundsInRoot
                next.width < oldViewport.width && next.height < oldViewport.height
            }
            composeRule.waitForIdle()
            assertTrue(harness.compact.value)
            assertEquals(baselineState, annotationState(harness.vm))
            assertEquals(baselineHistory, harness.vm.annotationHistory.captureCheckpoint())
            assertTrue(harness.effects.isEmpty())

            val completed = listOf(
                Point(.21f, .26f),
                Point(.46f, .39f),
                Point(.75f, .64f)
            )
            completeStroke(
                harness = harness,
                pageAspect = pdfPageAspect(harness.uri, pageIndex = 1),
                pageIndex = 1,
                expected = completed
            )
            assertCommittedPathAndUndo(
                harness = harness,
                pageIndex = 1,
                expected = completed,
                highlighter = true,
                beforeState = baselineState,
                beforeHistory = baselineHistory
            )
        } finally {
            harness.scenario.close()
        }
    }

    private fun launchRenderer(initialMode: ToolMode): RendererHarness {
        PDFBoxResourceLoader.init(targetContext.applicationContext)
        val uri = Uri.Builder()
            .scheme("content")
            .authority("${testContext.packageName}.stage8.fixture")
            .appendPath(BLUEPRINT_FIXTURE)
            .build()
        targetContext.contentResolver.openFileDescriptor(uri, "r")?.use { }
            ?: error("content resolver could not open $BLUEPRINT_FIXTURE")

        val vm = BlueprintViewModel()
        val allPagePhotoPins = mutableStateMapOf<Int, SnapshotStateList<PhotoPin>>()
        for (page in 0 until BLUEPRINT_PAGE_COUNT) {
            vm.pagePaths[page] = mutableStateListOf()
            vm.pageMeasurements[page] = mutableStateListOf()
            vm.pageNotes[page] = mutableStateListOf()
            vm.pagePhotoPins[page] = mutableStateListOf()
            vm.pageShapes[page] = mutableStateListOf()
            allPagePhotoPins[page] = vm.pagePhotoPins[page]!!
        }

        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = stage8TestReducer(vm) { effects += it }
        val mode = mutableStateOf(initialMode)
        val page = mutableStateOf(0)
        val token = mutableStateOf(
            DocumentSessionToken(
                documentId = DocumentId.new(),
                sourceUri = uri.toString(),
                sourceFingerprint = null,
                generation = 1L
            )
        )
        val compact = mutableStateOf(false)
        val rendered = AtomicInteger(0)
        val barrier = DocumentTransactionBarrier()
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(targetContext, MainActivity::class.java)
        )
        scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        val viewportSize = if (compact.value) 320.dp else 640.dp
                        Box(
                            Modifier
                                .size(viewportSize)
                                .testTag(VIEWPORT_TAG)
                        ) {
                            val currentPage = page.value
                            val currentToken = token.value
                            PdfPageRenderer(
                                uri = uri,
                                sessionToken = currentToken,
                                isSessionCurrent = { candidate -> candidate == token.value },
                                isPageCurrent = { candidate, candidatePage ->
                                    candidate == token.value && candidatePage == page.value
                                },
                                documentTransactionBarrier = barrier,
                                pageIndex = currentPage,
                                mode = mode.value,
                                currentScale = null,
                                paths = vm.pagePaths[currentPage]!!,
                                measurements = vm.pageMeasurements[currentPage]!!,
                                notes = vm.pageNotes[currentPage]!!,
                                photoPins = vm.pagePhotoPins[currentPage]!!,
                                shapes = vm.pageShapes[currentPage]!!,
                                annotationReducer = reducer,
                                allPagePhotoPins = allPagePhotoPins,
                                searchTerm = "",
                                highlightRects = emptyList(),
                                onScaleDefined = { _, _ -> false },
                                onDeleteItem = {},
                                onFullScreenModeChanged = {},
                                onPageRendered = { rendered.incrementAndGet() }
                            )
                        }
                    }
                }
            }
        }
        awaitRendered(
            RendererHarness(
                uri = uri,
                vm = vm,
                reducer = reducer,
                effects = effects,
                scenario = scenario,
                mode = mode,
                page = page,
                token = token,
                compact = compact,
                rendered = rendered
            ),
            renderedRevision = 0
        )
        return RendererHarness(
            uri = uri,
            vm = vm,
            reducer = reducer,
            effects = effects,
            scenario = scenario,
            mode = mode,
            page = page,
            token = token,
            compact = compact,
            rendered = rendered
        )
    }

    private fun cancelIncompleteStroke(
        harness: RendererHarness,
        pageAspect: Float,
        down: Point,
        moves: List<Point>,
        whileDown: (() -> Unit)? = null
    ) {
        val node = composeRule.onNodeWithTag(VIEWPORT_TAG)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        node.performTouchInput {
            down(toScreenOffset(bounds, pageAspect, down))
            moves.forEach { moveTo(toScreenOffset(bounds, pageAspect, it)) }
            whileDown?.invoke()
            // Advance the test clock so a key/constraint change is recomposed
            // before the held pointer is canceled.
            advanceEventTime(32)
            cancel()
        }
        composeRule.waitForIdle()
    }

    private fun completeStroke(
        harness: RendererHarness,
        pageAspect: Float,
        pageIndex: Int,
        expected: List<Point>
    ) {
        val node = composeRule.onNodeWithTag(VIEWPORT_TAG)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val downPoint = Point(.1f, .14f)
        node.performTouchInput {
            down(toScreenOffset(bounds, pageAspect, downPoint))
            expected.forEach { moveTo(toScreenOffset(bounds, pageAspect, it)) }
            up()
        }
        composeRule.waitUntil(10_000L) {
            harness.vm.pagePaths[pageIndex]?.size == 1 && harness.effects.size == 1
        }
    }

    private fun assertCommittedPathAndUndo(
        harness: RendererHarness,
        pageIndex: Int,
        expected: List<Point>,
        highlighter: Boolean,
        beforeState: AnnotationState,
        beforeHistory: AnnotationReducer.HistoryOwner.Checkpoint
    ) {
        val paths = harness.vm.pagePaths[pageIndex].orEmpty()
        assertEquals(1, paths.size)
        val path = paths.single()
        assertEquals(highlighter, path.isHighlighter)
        assertTrue(path.points.size >= expected.size)

        // The release event can repeat the final point, but every committed
        // point must be one of this gesture's normalized input points.  This
        // catches a stale point from a canceled gesture rather than only
        // checking that some path was created.
        val allowed = expected + Point(.1f, .14f)
        assertTrue(
            "committed points were not the completed gesture: ${path.points}",
            path.points.all { actual -> allowed.any { expectedPoint -> near(actual, expectedPoint) } }
        )
        expected.forEach { expectedPoint ->
            assertTrue(
                "missing normalized point $expectedPoint in ${path.points}",
                path.points.any { actual -> near(actual, expectedPoint) }
            )
        }

        assertEquals(1, harness.effects.size)
        assertEquals(AnnotationReducer.Kind.ADD, harness.effects.single().kind)
        assertEquals(pageIndex, harness.effects.single().page)
        assertNotEquals(beforeState, annotationState(harness.vm))
        assertNotEquals(beforeHistory, harness.vm.annotationHistory.captureCheckpoint())
        assertTrue(harness.reducer.canUndo(pageIndex))

        val undo = harness.reducer.undo(pageIndex)
        assertTrue(undo.changed)
        assertTrue(harness.vm.pagePaths[pageIndex].orEmpty().isEmpty())
        assertEquals(beforeState, annotationState(harness.vm))
        assertFalse(harness.reducer.canUndo(pageIndex))
        assertTrue(harness.reducer.canRedo(pageIndex))
        assertEquals(2, harness.effects.size)
        assertEquals(AnnotationReducer.Kind.UNDO, harness.effects.last().kind)
    }

    private fun awaitRendered(harness: RendererHarness, renderedRevision: Int) {
        composeRule.waitUntil(30_000L) {
            harness.rendered.get() > renderedRevision
        }
        composeRule.onNodeWithTag(VIEWPORT_TAG).fetchSemanticsNode()
        composeRule.waitForIdle()
    }

    private fun toScreenOffset(bounds: Rect, pageAspect: Float, point: Point): Offset {
        val viewportWidth = bounds.width
        val viewportHeight = bounds.height
        val renderedWidth: Float
        val renderedHeight: Float
        if (pageAspect > viewportWidth / viewportHeight) {
            renderedWidth = viewportWidth
            renderedHeight = viewportWidth / pageAspect
        } else {
            renderedWidth = viewportHeight * pageAspect
            renderedHeight = viewportHeight
        }
        val imageLeft = (viewportWidth - renderedWidth) / 2f
        val imageTop = (viewportHeight - renderedHeight) / 2f
        return Offset(
            imageLeft + point.x * renderedWidth,
            imageTop + point.y * renderedHeight
        )
    }

    private fun pdfPageAspect(uri: Uri, pageIndex: Int): Float {
        val descriptor = targetContext.contentResolver.openFileDescriptor(uri, "r")
            ?: error("could not open PDF descriptor for page aspect")
        return descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                renderer.openPage(pageIndex).use { page ->
                    page.width.toFloat() / page.height.toFloat()
                }
            }
        }
    }

    private fun annotationState(vm: BlueprintViewModel) = AnnotationState(
        paths = vm.pagePaths.entries.associate { (page, values) -> page to values.toList() },
        measurements = vm.pageMeasurements.entries.associate { (page, values) -> page to values.toList() },
        notes = vm.pageNotes.entries.associate { (page, values) -> page to values.toList() },
        photoPins = vm.pagePhotoPins.entries.associate { (page, values) -> page to values.toList() },
        shapes = vm.pageShapes.entries.associate { (page, values) -> page to values.toList() },
        scales = vm.pageScales.toMap()
    )

    private fun near(actual: Point, expected: Point): Boolean =
        abs(actual.x - expected.x) <= NORMALIZED_TOLERANCE &&
            abs(actual.y - expected.y) <= NORMALIZED_TOLERANCE

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val testContext
        get() = InstrumentationRegistry.getInstrumentation().context

    private data class RendererHarness(
        val uri: Uri,
        val vm: BlueprintViewModel,
        val reducer: AnnotationReducer,
        val effects: MutableList<AnnotationReducer.EffectIntent>,
        val scenario: ActivityScenario<MainActivity>,
        val mode: MutableState<ToolMode>,
        val page: MutableState<Int>,
        val token: MutableState<DocumentSessionToken>,
        val compact: MutableState<Boolean>,
        val rendered: AtomicInteger
    )

    private data class AnnotationState(
        val paths: Map<Int, List<DrawnPath>>,
        val measurements: Map<Int, List<Measurement>>,
        val notes: Map<Int, List<Note>>,
        val photoPins: Map<Int, List<PhotoPin>>,
        val shapes: Map<Int, List<Shape>>,
        val scales: Map<Int, PageScale>
    )

    private companion object {
        const val VIEWPORT_TAG = "stage9bStrokeViewport"
        const val BLUEPRINT_FIXTURE = "stage7/pdfs/blueprint/large_blueprint.pdf"
        const val BLUEPRINT_PAGE_COUNT = 4
        const val NORMALIZED_TOLERANCE = .015f
    }
}
