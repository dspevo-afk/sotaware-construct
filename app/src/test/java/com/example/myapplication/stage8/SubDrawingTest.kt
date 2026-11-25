package com.example.myapplication.stage8

import androidx.compose.runtime.mutableStateListOf
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.DrawnPath
import com.example.myapplication.Measurement
import com.example.myapplication.Note
import com.example.myapplication.PageScale
import com.example.myapplication.PdfNormalizedRect
import com.example.myapplication.PhotoPin
import com.example.myapplication.PhotoImageNote
import com.example.myapplication.Point
import com.example.myapplication.Shape
import com.example.myapplication.ShapeType
import com.example.myapplication.SubDrawing
import com.example.myapplication.SubDrawingBadge
import com.example.myapplication.SubDrawingGeometry
import com.example.myapplication.SubDrawingLabels
import com.example.myapplication.SubDrawingPalette
import com.example.myapplication.SubDrawingPageMarkers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sub-drawing crop geometry, inherited calibration, palette assignment, and the
 * canonical reducer transactions (including undo/redo of nested markup edits).
 */
class SubDrawingTest {
    private fun reducer(vm: BlueprintViewModel, sessionKey: Any = "sub-session") =
        AnnotationReducer(vm, {}, sessionKey, { sessionKey }, { true })

    private fun sub(
        id: String = "sub-1",
        left: Float = 0.1f,
        top: Float = 0.1f,
        right: Float = 0.5f,
        bottom: Float = 0.5f,
        colorIndex: Int = 0,
        scale: PageScale? = null
    ) = SubDrawing(id = id, left = left, top = top, right = right, bottom = bottom, colorIndex = colorIndex, scale = scale)

    private fun vmWithSubDrawings(page: Int, vararg subs: SubDrawing): BlueprintViewModel {
        val vm = BlueprintViewModel()
        vm.pageSubDrawings[page] = mutableStateListOf(*subs)
        return vm
    }

    private fun assertPoint(expected: Point, actual: Point) {
        assertEquals(expected.x, actual.x, 0.0001f)
        assertEquals(expected.y, actual.y, 0.0001f)
    }

    // Geometry

    @Test
    fun cropForDrag_normalizesEitherDragDirectionAndRejectsTinyCrops() {
        val forward = SubDrawingGeometry.cropForDrag(Point(0.2f, 0.6f), Point(0.7f, 0.9f))
        assertNotNull(forward)
        assertEquals(0.2f, requireNotNull(forward).left, 0f)
        assertEquals(0.6f, forward.top, 0f)
        assertEquals(0.7f, forward.right, 0f)
        assertEquals(0.9f, forward.bottom, 0f)

        val backward = SubDrawingGeometry.cropForDrag(Point(0.7f, 0.9f), Point(0.2f, 0.6f))
        assertEquals(forward, backward)

        assertNull(SubDrawingGeometry.cropForDrag(Point(0.5f, 0.5f), Point(0.5f + SubDrawingGeometry.MIN_CROP_RATIO / 2f, 0.9f)))
        assertNull(SubDrawingGeometry.cropForDrag(Point(0.5f, 0.5f), Point(0.5f, 0.5f)))
        assertNull(SubDrawingGeometry.cropForDrag(Point(Float.NaN, 0.1f), Point(0.8f, 0.9f)))
    }

    @Test
    fun isValidCrop_acceptsOnlyFiniteNormalizedCropsWithBothEdgesAtTheMinimum() {
        assertTrue(SubDrawingGeometry.isValidCrop(0f, 0f, 1f, 1f))
        // Both edges at the accepted minimum, and one edge just below it.
        val min = SubDrawingGeometry.MIN_CROP_RATIO
        assertTrue(SubDrawingGeometry.isValidCrop(0.4f, 0.4f, 0.4f + min, 0.4f + min))
        assertFalse(SubDrawingGeometry.isValidCrop(0.4f, 0.4f, 0.4f + min / 2f, 0.4f + min))
        assertFalse(SubDrawingGeometry.isValidCrop(0.5f, 0.5f, 0.519f, 0.6f))
        assertFalse(SubDrawingGeometry.isValidCrop(-0.1f, 0f, 0.5f, 0.5f))
        assertFalse(SubDrawingGeometry.isValidCrop(0f, 0f, 1.2f, 0.5f))
        assertFalse(SubDrawingGeometry.isValidCrop(Float.NaN, 0f, 0.5f, 0.5f))
    }
    @Test
    fun cropSourceSize_isTheCropsShareOfThePagePoints() {
        // A crop half the page width spans half the page's points, which is the
        // surface the inherited page scale is measured against.
        val crop = sub(left = 0f, top = 0f, right = 0.5f, bottom = 0.25f)
        val size = requireNotNull(SubDrawingGeometry.cropSourceSize(1000f, 800f, crop))
        assertEquals(500f, size.width, 0.0001f)
        assertEquals(200f, size.height, 0.0001f)
    }

    @Test
    fun measuringTheSameEdge_insideACropReadsTheSameLengthAsOnThePage() {
        // The reported bug: a box measured 10' on the page read 1' when a
        // sub-drawing was opened over it. A 50-points-per-foot page, a crop
        // spanning half the page width, and the same physical edge drawn from
        // one crop edge to the other must produce the same label as the page.
        val pageScale = PageScale(50f)
        val pageWidth = 1000f
        val pageHeight = 800f
        val crop = sub(left = 0.1f, top = 0.2f, right = 0.6f, bottom = 0.6f)
        val cropScale = requireNotNull(SubDrawingGeometry.inheritedScale(pageScale, crop.widthRatio))

        // On the page: the same edge spans half the crop's width in page points.
        val edgeOnPage = listOf(Point(0.1f, 0.3f), Point(0.6f, 0.3f))
        val pageLength = requireNotNull(measurementSourceLength(edgeOnPage, pageWidth, pageHeight))
        val pageLabel = requireNotNull(formatSourceDistance(pageLength, pageScale.pointsPerFoot))

        // Inside the sub-drawing: the edge runs across the whole crop surface.
        val edgeInCrop = listOf(Point(0f, 0.5f), Point(1f, 0.5f))
        val cropSurface = requireNotNull(SubDrawingGeometry.cropSourceSize(pageWidth, pageHeight, crop))
        val cropLength = requireNotNull(measurementSourceLength(edgeInCrop, cropSurface.width, cropSurface.height))
        val cropLabel = requireNotNull(formatSourceDistance(cropLength, cropScale.pointsPerFoot))

        assertEquals(500f, pageLength, 0.01f)
        assertEquals(500f, cropLength, 0.01f)
        assertEquals(pageLabel, cropLabel)
        assertTrue("a 10-foot edge is expected, was $pageLabel", pageLabel.startsWith("10'"))
    }

    @Test
    fun surfaceMaps_roundTripPointsAndRectsBetweenPageAndCrop() {
        val crop = sub(left = 0.2f, top = 0.1f, right = 0.6f, bottom = 0.5f)
        assertPoint(Point(0.2f, 0.1f), SubDrawingGeometry.pagePoint(crop, Point(0f, 0f)))
        assertPoint(Point(0.6f, 0.5f), SubDrawingGeometry.pagePoint(crop, Point(1f, 1f)))
        assertPoint(Point(0.4f, 0.3f), SubDrawingGeometry.pagePoint(crop, Point(0.5f, 0.5f)))

        val inside = SubDrawingGeometry.subPoint(crop, Point(0.4f, 0.3f))
        assertEquals(0.5f, inside.x, 0.0001f)
        assertEquals(0.5f, inside.y, 0.0001f)

        // The crop is 0.4 x 0.4 of the page, so its own surface aspect is 1.
        val surfaceAspect = SubDrawingGeometry.subSurfaceAspect(crop)
        assertNotNull(surfaceAspect)
        assertEquals(1f, requireNotNull(surfaceAspect), 0.0001f)

        val pageRect = SubDrawingGeometry.pageRect(crop, PdfNormalizedRect(0.25f, 0.25f, 0.75f, 0.75f))
        assertNotNull(pageRect)
        assertEquals(0.3f, requireNotNull(pageRect).left, 0.0001f)
        assertEquals(0.2f, pageRect.top, 0.0001f)
        assertEquals(0.5f, pageRect.right, 0.0001f)
        assertEquals(0.4f, pageRect.bottom, 0.0001f)

        // A page rectangle that only partly overlaps the crop maps the intersection.
        val clipped = SubDrawingGeometry.subRectFromPageRect(crop, PdfNormalizedRect(0f, 0f, 0.3f, 0.2f))
        assertNotNull(clipped)
        assertEquals(0f, requireNotNull(clipped).left, 0.0001f)
        assertEquals(0.25f, clipped.right, 0.0001f)
        assertEquals(0.25f, clipped.bottom, 0.0001f)

        assertNull(SubDrawingGeometry.subRectFromPageRect(crop, PdfNormalizedRect(0.7f, 0.6f, 0.9f, 0.9f)))
    }

    @Test
    fun cropSourceSize_scalesPagePixelsByTheCrop() {
        val size = SubDrawingGeometry.cropSourceSize(1000f, 800f, sub(left = 0.25f, top = 0.25f, right = 0.75f, bottom = 0.75f))
        assertNotNull(size)
        assertEquals(500f, requireNotNull(size).width, 0.0001f)
        assertEquals(400f, size.height, 0.0001f)
        assertNull(SubDrawingGeometry.cropSourceSize(0f, 800f, sub()))
    }

    // Palette

    @Test
    fun assignIndex_differsFromEveryCropOnThePage() {
        val left = sub(id = "a", left = 0f, top = 0f, right = 0.5f, bottom = 0.5f, colorIndex = 0)
        val overlapping = sub(id = "b", left = 0.25f, top = 0.25f, right = 0.75f, bottom = 0.75f)
        val assigned = SubDrawingPalette.assignIndex(listOf(left))
        assertTrue(SubDrawingPalette.colorArgb(assigned) == SubDrawingPalette.colors[assigned].toInt())
        assertFalse(assigned == left.colorIndex)

        // Crops that do not touch still read as distinct: a page of room tags is
        // told apart by colour, not only where the areas overlap.
        val neighbours = (0 until minOf(SubDrawingPalette.size - 1, 3)).map { index ->
            sub(id = "n$index", left = 0.05f * index, top = 0.05f * index, right = 0.2f + 0.05f * index, bottom = 0.2f + 0.05f * index, colorIndex = index)
        }
        val farAssigned = SubDrawingPalette.assignIndex(neighbours)
        assertTrue(neighbours.none { it.colorIndex == farAssigned })

        // Filling the palette keeps the new crop distinct from every crop on the page.
        val taken = (0 until SubDrawingPalette.size - 1).map { index ->
            sub(id = "n$index", left = 0f, top = 0f, right = 0.5f, bottom = 0.5f, colorIndex = index)
        }
        val lastAssigned: Int = SubDrawingPalette.assignIndex(taken.toList())
        assertTrue(taken.none { sub -> sub.colorIndex == lastAssigned })
    }

    @Test
    fun markerHitArea_answersTheBadgeTheCanvasPaintsAndOnlyWhatIsShown() {
        // The owner the viewer paints with is the owner its gestures hit-test, so a
        // finger lands on the marker the user is looking at: the badge answers at its
        // saved size, a point inside the crop answers only when rectangles are shown.
        val crop = SubDrawing(
            id = "hit-1", left = 0.1f, top = 0.1f, right = 0.5f, bottom = 0.5f,
            badgeRatio = 0.1f
        )
        val pageWidthPx = 1000f
        val onBadge = SubDrawingPageMarkers.badgeAt(
            subDrawings = listOf(crop), pagePoint = Point(0.3f, 0.3f),
            pageWidthPx = pageWidthPx, compositeScale = 1f, showRectangles = false
        )
        assertEquals(crop.id, requireNotNull(onBadge).id)
        // Far outside the drawn badge (radius 0.1 of a 1000px page plus the hit slop).
        assertEquals(
            null,
            SubDrawingPageMarkers.badgeAt(
                subDrawings = listOf(crop), pagePoint = Point(0.05f, 0.05f),
                pageWidthPx = pageWidthPx, compositeScale = 1f, showRectangles = false
            )
        )
        // A point inside the crop area but outside its badge answers in rectangle mode only.
        val inCropArea = Point(0.12f, 0.12f)
        assertEquals(
            null,
            SubDrawingPageMarkers.badgeAt(
                subDrawings = listOf(crop), pagePoint = inCropArea,
                pageWidthPx = pageWidthPx, compositeScale = 1f, showRectangles = false
            )
        )
        assertEquals(
            crop.id,
            requireNotNull(
                SubDrawingPageMarkers.badgeAt(
                    subDrawings = listOf(crop), pagePoint = inCropArea,
                    pageWidthPx = pageWidthPx, compositeScale = 1f, showRectangles = true
                )
            ).id
        )
        // A saved badge size is what the hit-test uses, not the default: a 0.4 badge
        // reaches this point while the 0.1 badge above does not.
        val big = crop.copy(badgeRatio = 0.4f)
        assertEquals(
            big.id,
            requireNotNull(
                SubDrawingPageMarkers.badgeAt(
                    subDrawings = listOf(big), pagePoint = Point(0.05f, 0.05f),
                    pageWidthPx = pageWidthPx, compositeScale = 1f, showRectangles = false
                )
            ).id
        )
    }

    @Test
    fun markerDragRect_isTheBoxBetweenTheTwoFingerPoints() {
        // Creation previews the box between where the finger landed and where it is,
        // in either drag direction; publishing is still the crop rule's job.
        val rect = requireNotNull(SubDrawingPageMarkers.dragRect(Point(0.7f, 0.9f), Point(0.2f, 0.3f)))
        assertEquals(0.2f, rect.left, 0.0001f)
        assertEquals(0.3f, rect.top, 0.0001f)
        assertEquals(0.7f, rect.right, 0.0001f)
        assertEquals(0.9f, rect.bottom, 0.0001f)
        // Out-of-range points publish nothing, exactly as the viewer's drag has done.
        assertEquals(null, SubDrawingPageMarkers.dragRect(Point(0f, 1f), Point(1.4f, 1.2f)))
    }

    @Test
    fun markerDragRect_givesTheCanvasABoxFromTheFirstMovementOfEveryDrag() {
        // Dave's tablet (beta-4): on a page with no sub-drawings, dragging out the first crop
        // showed no box mid-drag - the viewer painted the preview only inside its "this page
        // has sub-drawings" block. The preview rule itself was never the problem: any movement
        // that leaves a real width and height yields the box the canvas paints, in every drag
        // direction, and only a finger that never moved (or left the page) gives nothing.
        val start = Point(0.4f, 0.4f)
        listOf(Point(0.401f, 0.401f), Point(0.39f, 0.401f), Point(0.399f, 0.62f), Point(0.65f, 0.401f))
            .forEach { finger ->
                val rect = requireNotNull(
                    SubDrawingPageMarkers.dragRect(start, finger)
                ) { "no creation preview for the drag to $finger" }
                assertTrue(rect.right > rect.left && rect.bottom > rect.top)
            }
        // A finger that has not moved yet, and one dragged off the sheet, paint nothing - and a
        // perfectly straight vertical or horizontal drag publishes nothing either, because a
        // crop needs a width as well as a height (SubDrawingGeometry.MIN_CROP_RATIO), so the
        // preview shows exactly the box the release can keep.
        assertEquals(null, SubDrawingPageMarkers.dragRect(start, start))
        assertEquals(null, SubDrawingPageMarkers.dragRect(start, Point(1.4f, 0.9f)))
        assertEquals(null, SubDrawingPageMarkers.dragRect(start, Point(0.4f, 0.62f)))
        assertEquals(null, SubDrawingPageMarkers.dragRect(start, Point(0.65f, 0.4f)))
    }

    @Test
    fun inheritedScale_carriesThePageCalibrationIntoTheCropUnchanged() {
        // Measuring inside a crop reads the same as on the page: the page's
        // points-per-foot carries over as-is, only a crop too narrow to measure
        // (or an uncalibrated page) declines it.
        val crop = sub(id = "scale-1", left = 0.2f, top = 0.2f, right = 0.6f, bottom = 0.6f)
        val inherited = requireNotNull(SubDrawingGeometry.inheritedScale(PageScale(120f), crop.widthRatio))
        assertEquals(120f, inherited.pointsPerFoot, 0.0001f)
        assertEquals(null, SubDrawingGeometry.inheritedScale(PageScale(120f), 0.001f))
        assertEquals(null, SubDrawingGeometry.inheritedScale(null, crop.widthRatio))
    }

    @Test
    fun assignIndex_usesEveryColourInDragOrderAndCenterIsTheCropMiddle() {
        // The bug Dave saw: two crops dragged one after the other came out the
        // same pink. Through the reducer, each new crop takes the next colour.
        val page = 0
        val vm = vmWithSubDrawings(page)
        val reduce = reducer(vm)
        val crops = (1..3).map { index -> sub(id = "drag$index", left = 0.05f * index, top = 0.05f * index, right = 0.2f + 0.05f * index, bottom = 0.2f + 0.05f * index) }
        crops.forEach { crop -> assertEquals(AnnotationReducer.Result.Accepted, reduce.addSubDrawing(page, crop)) }
        val stored = vm.pageSubDrawings.getValue(page).toList()
        assertEquals(crops.size, stored.size)
        assertEquals(crops.size, stored.map { it.colorIndex }.toSet().size)

        // A non-overlapping crop on an empty page keeps the first colour.
        assertEquals(0, SubDrawingPalette.assignIndex(emptyList()))

        val center = SubDrawingGeometry.center(sub(left = 0.2f, top = 0.1f, right = 0.6f, bottom = 0.5f))
        assertEquals(0.4f, center.x, 0.0001f)
        assertEquals(0.3f, center.y, 0.0001f)
    }

    @Test
    fun badgeText_shrinksToTheHalfCircleAndCutsLongNames() {
        // Long names are cut to what the badge can show; short ones pass through.
        assertEquals("104", SubDrawingBadge.nameForBadge("104"))
        assertEquals("", SubDrawingBadge.nameForBadge(null))
        assertEquals("", SubDrawingBadge.nameForBadge("   "))
        val long = SubDrawingBadge.nameForBadge("Mechanical room with a very long name")
        assertTrue(long.length <= SubDrawingBadge.MAX_BADGE_NAME_CHARS)
        assertTrue(long.endsWith("…"))

        // Text size scales with the badge radius, and the inscribed-square bound is
        // what keeps glyphs inside the semicircle.
        assertTrue(SubDrawingBadge.MAX_TEXT_TO_RADIUS < SubDrawingBadge.INSCRIBED_SQUARE_TO_RADIUS)
        assertTrue(SubDrawingBadge.MIN_TEXT_TO_RADIUS < SubDrawingBadge.MAX_TEXT_TO_RADIUS)
        val wide = 34f * SubDrawingBadge.INSCRIBED_SQUARE_TO_RADIUS
        assertTrue(wide > 20f)
    }

    @Test
    fun colorArgb_wrapsEveryIndexIntoThePalette() {
        val size = SubDrawingPalette.size
        val distinct = (0 until size).map { SubDrawingPalette.colorArgb(it) }.toSet()
        assertEquals(size, distinct.size)
        assertEquals(SubDrawingPalette.colorArgb(0), SubDrawingPalette.colorArgb(size))
        assertEquals(SubDrawingPalette.colorArgb(1), SubDrawingPalette.colorArgb(-size + 1))
    }

    // Reducer transactions

    @Test
    fun addSubDrawing_assignsItsPaletteColourAndIsUndoable() {
        val vm = vmWithSubDrawings(0)
        val reducer = reducer(vm)
        val crop = sub(id = "sub-add", left = 0.1f, top = 0.1f, right = 0.6f, bottom = 0.6f)
        val result = reducer.addSubDrawing(0, crop)
        assertTrue(result.changed)
        val stored = vm.pageSubDrawings.getValue(0).single()
        assertEquals("sub-add", stored.id)
        assertEquals(SubDrawingPalette.assignIndex(emptyList()), stored.colorIndex)

        assertTrue(reducer.canUndo(0))
        assertTrue(reducer.undo(0).changed)
        assertTrue(vm.pageSubDrawings.getValue(0).isEmpty())
        assertTrue(reducer.canRedo(0))
        assertTrue(reducer.redo(0).changed)
        assertEquals("sub-add", vm.pageSubDrawings.getValue(0).single().id)
    }

    @Test
    fun addSubDrawing_rejectsTinyCropsAndDuplicateIds() {
        val vm = vmWithSubDrawings(0, sub(id = "existing"))
        val reducer = reducer(vm)
        assertFalse(reducer.addSubDrawing(0, sub(id = "tiny", left = 0.1f, top = 0.1f, right = 0.11f, bottom = 0.5f)).changed)
        assertFalse(reducer.addSubDrawing(0, sub(id = "existing", left = 0.6f, top = 0.1f, right = 0.9f, bottom = 0.4f)).changed)
        assertFalse(reducer.addSubDrawing(0, sub(id = "bad-colour", left = 0.6f, top = 0.1f, right = 0.9f, bottom = 0.4f, colorIndex = SubDrawingPalette.size)).changed)
        assertEquals(listOf("existing"), vm.pageSubDrawings.getValue(0).map { it.id })
        assertFalse(reducer.canUndo(0))
    }

    @Test
    fun addSubDrawing_keepsTheIdTheViewerSuppliedSoIdenticalCropsStayDistinguishable() {
        // Dave's beta-6 report: releasing the Sub-drawing drag created nothing. The viewer arms
        // its naming popup for the crop *it* published, so the host must publish it under the id
        // the viewer generated - and a page that already holds an unnamed crop over exactly the
        // same rectangle must keep both crops, each answerable by its own id.
        val vm = vmWithSubDrawings(0, sub(id = "saved-same-area"))
        val reducer = reducer(vm)
        val dragId = java.util.UUID.randomUUID().toString()
        val crop = sub(id = dragId)
        assertTrue(reducer.addSubDrawing(0, crop).changed)
        val crops = vm.pageSubDrawings.getValue(0)
        assertEquals(listOf("saved-same-area", dragId), crops.map { it.id })
        assertEquals(crops.first().left, crops.last().left, 0f)

        // Naming the newly dragged crop touches only that crop: the pre-existing crop is untouched.
        assertTrue(reducer.renameSubDrawing(0, dragId, "104").changed)
        assertEquals("104", crops.first { it.id == dragId }.name)
        assertEquals(null, crops.first { it.id == "saved-same-area" }.name)

        // Cancel on the naming popup discards only the crop this drag made. Undo steps the typed
        // name off first, so the crop is back to an untouched creation, which is what the
        // discard command accepts; the pre-existing crop stays.
        assertTrue(reducer.undo(0).changed)
        assertTrue(reducer.abandonSubDrawing(0, crops.first { it.id == dragId }).changed)
        assertEquals(listOf("saved-same-area"), vm.pageSubDrawings.getValue(0).map { it.id })
    }

    @Test
    fun deleteSubDrawing_isUndoableAndRestoresItsNestedMarkups() {
        val nestedPath = DrawnPath(listOf(Point(0.1f, 0.2f), Point(0.3f, 0.4f)), -65536, false, 0.005f, id = "nested-path")
        val original = sub(id = "sub-del").copy(paths = listOf(nestedPath))
        val vm = vmWithSubDrawings(0, original)
        val reducer = reducer(vm)

        assertTrue(reducer.deleteSubDrawing(0, original).changed)
        assertTrue(vm.pageSubDrawings.getValue(0).isEmpty())
        assertTrue(reducer.undo(0).changed)
        val restored = vm.pageSubDrawings.getValue(0).single()
        assertEquals("sub-del", restored.id)
        assertEquals(listOf("nested-path"), restored.paths.map { it.id })
    }

    @Test
    fun nestedMarkupEdits_commitOneUndoableEntryAndRejectForeignIds() {
        val vm = vmWithSubDrawings(0, sub(id = "sub-nested"))
        val reducer = reducer(vm)
        val path = DrawnPath(listOf(Point(0.1f, 0.1f), Point(0.4f, 0.6f)), -16711935, false, 0.005f, id = "nested-add")

        assertTrue(reducer.addSubDrawingPath(0, "sub-nested", path).changed)
        assertEquals(listOf("nested-add"), vm.pageSubDrawings.getValue(0).single().paths.map { it.id })
        // One entry covers the nested add.
        assertTrue(reducer.undo(0).changed)
        assertTrue(vm.pageSubDrawings.getValue(0).single().paths.isEmpty())
        assertFalse(reducer.canUndo(0))

        assertTrue(reducer.addSubDrawingPath(0, "sub-nested", path).changed)
        val moved = path.copy(points = listOf(Point(0.2f, 0.2f), Point(0.5f, 0.7f)))
        assertTrue(reducer.updateSubDrawingPath(0, "sub-nested", path, moved).changed)
        assertTrue(reducer.undo(0).changed)
        assertEquals(path, vm.pageSubDrawings.getValue(0).single().paths.single())

        assertTrue(reducer.deleteSubDrawingPath(0, "sub-nested", path).changed)
        assertTrue(vm.pageSubDrawings.getValue(0).single().paths.isEmpty())

        // A markup that belongs to no sub-drawing on this page is rejected.
        assertFalse(reducer.addSubDrawingPath(0, "missing-sub", path).changed)
        assertFalse(reducer.addSubDrawingNote(0, "sub-nested", Note(1.5f, 0.2f, "off-surface", id = "off-note")).changed)
        assertFalse(reducer.deleteSubDrawingShape(0, "missing-sub", Shape(0.3f, 0.3f, 0f, ShapeType.RECTANGLE, -1, id = "shape-a")).changed)
    }

    @Test
    fun setSubDrawingScale_relabelsNestedMeasurementsAndIsUndoable() {
        val nested = Measurement(Point(0f, 0f), Point(0.5f, 0f), "stale", id = "nested-measure")
        val vm = vmWithSubDrawings(0, sub(id = "sub-scale", left = 0f, top = 0f, right = 0.5f, bottom = 0.5f).copy(measurements = listOf(nested)))
        val reducer = reducer(vm)

        assertTrue(reducer.setSubDrawingScale(0, vm.pageSubDrawings.getValue(0).single(), PageScale(10f)).changed)
        val calibrated = vm.pageSubDrawings.getValue(0).single()
        assertEquals(10f, requireNotNull(calibrated.scale).pointsPerFoot, 0f)
        val label = requireNotNull(calibrated.measurements.single()).text
        assertTrue("expected a relabelled measurement, was '$label'", label != nested.text)

        assertTrue(reducer.undo(0).changed)
        val restored = vm.pageSubDrawings.getValue(0).single()
        assertNull(restored.scale)
        assertEquals(nested, restored.measurements.single())
    }

    @Test
    fun setSubDrawingScale_rejectsUnknownSubDrawingsAndInvalidScales() {
        val vm = vmWithSubDrawings(0, sub(id = "sub-reject"))
        val reducer = reducer(vm)
        val current = vm.pageSubDrawings.getValue(0).single()
        assertFalse(reducer.setSubDrawingScale(0, sub(id = "absent"), PageScale(10f)).changed)
        assertFalse(reducer.setSubDrawingScale(0, current, PageScale(Float.NaN)).changed)
        assertFalse(reducer.setSubDrawingScale(0, current, PageScale(0f)).changed)
        assertTrue(reducer.setSubDrawingScale(0, current, PageScale(10f)).changed)
        assertEquals(com.example.myapplication.stage8.AnnotationReducer.Result.Unchanged, reducer.setSubDrawingScale(0, vm.pageSubDrawings.getValue(0).single(), PageScale(10f)))
    }

    @Test
    fun subDrawingCommands_requireAnInitializedPage() {
        val vm = BlueprintViewModel()
        val reducer = reducer(vm)
        assertFalse(reducer.addSubDrawing(3, sub()).changed)
        vm.pageSubDrawings[3] = mutableStateListOf()
        assertTrue(reducer.addSubDrawing(3, sub()).changed)
    }

    @Test
    fun moveSubDrawingBadge_movesOnlyTheBadgeAndIsUndoable() {
        val vm = vmWithSubDrawings(0, sub(id = "badge-move"))
        val reducer = reducer(vm)
        assertEquals(AnnotationReducer.Result.Accepted, reducer.moveSubDrawingBadge(0, "badge-move", Point(0.8f, 0.2f)))
        val moved = vm.pageSubDrawings.getValue(0).single()
        assertEquals(Point(0.8f, 0.2f), SubDrawingGeometry.badgeCenter(moved))
        // The crop rectangle is locked: only the badge moved.
        assertEquals(0.1f, moved.left, 0f); assertEquals(0.5f, moved.right, 0f)
        assertFalse(reducer.moveSubDrawingBadge(0, "badge-move", Point(1.2f, 0.2f)).changed)
        assertFalse(reducer.moveSubDrawingBadge(0, "missing", Point(0.2f, 0.2f)).changed)
        assertTrue(reducer.undo(0).changed)
        assertEquals(SubDrawingGeometry.center(sub()), SubDrawingGeometry.badgeCenter(vm.pageSubDrawings.getValue(0).single()))
    }

    @Test
    fun addSubDrawingPhoto_storesThePinOnTheSubDrawingAndIsUndoable() {
        val vm = vmWithSubDrawings(0, sub(id = "sub-photos"))
        vm.pagePhotoPins[0] = mutableStateListOf()
        val reducer = reducer(vm)
        val pin = PhotoPin(x = 0.25f, y = 0.4f, id = "sub-pin")

        assertTrue(reducer.addSubDrawingPhoto(0, "sub-photos", pin).changed)
        // The pin belongs to the sub-drawing, never to the page.
        assertEquals(listOf("sub-pin"), vm.pageSubDrawings.getValue(0).single().photos.map { it.id })
        assertTrue(vm.pagePhotoPins.getValue(0).isEmpty())

        assertTrue(reducer.undo(0).changed)
        assertTrue(vm.pageSubDrawings.getValue(0).single().photos.isEmpty())
        assertTrue(reducer.redo(0).changed)
        assertEquals(listOf("sub-pin"), vm.pageSubDrawings.getValue(0).single().photos.map { it.id })
    }

    @Test
    fun subDrawingPhotoMarkups_stayOnTheOwningPinAndRejectForeignNames() {
        val pin = PhotoPin(x = 0.2f, y = 0.3f, id = "sub-pin", imageFileNames = listOf("photo-1.jpg"))
        val vm = vmWithSubDrawings(0, sub(id = "sub-markups").copy(photos = listOf(pin)))
        val reducer = reducer(vm)

        assertTrue(reducer.attachSubDrawingPhoto(0, "sub-markups", "sub-pin", "photo-2.jpg").changed)
        assertTrue(reducer.addSubDrawingImagePath(0, "sub-markups", "sub-pin", "photo-1.jpg", DrawnPath(listOf(Point(0.1f, 0.1f), Point(0.4f, 0.2f)), -65536, false, id = "pin-path")).changed)
        assertTrue(reducer.addSubDrawingImageNote(0, "sub-markups", "sub-pin", "photo-1.jpg", PhotoImageNote(x = 0.2f, y = 0.2f, text = "hi", id = "pin-note")).changed)
        val stored = vm.pageSubDrawings.getValue(0).single().photos.single()
        assertEquals(listOf("photo-1.jpg", "photo-2.jpg"), stored.imageFileNames)
        assertEquals("pin-path", stored.imagePaths.getValue("photo-1.jpg").single().id)
        assertEquals("pin-note", stored.imageNotes.getValue("photo-1.jpg").single().id)

        // A markup for an unattached photo never commits.
        assertFalse(reducer.addSubDrawingImagePath(0, "sub-markups", "sub-pin", "missing.jpg", DrawnPath(listOf(Point(0f, 0f), Point(0.2f, 0.2f)), -65536, false, id = "ghost-path")).changed)
        assertTrue(vm.pageSubDrawings.getValue(0).single().photos.single().imagePaths.keys == setOf("photo-1.jpg"))

        assertTrue(reducer.deleteSubDrawingImageMarkup(0, "sub-markups", "sub-pin", "photo-1.jpg", "pin-path").changed)
        assertTrue(vm.pageSubDrawings.getValue(0).single().photos.single().imagePaths.getValue("photo-1.jpg").isEmpty())
        // Undo restores the whole sub-drawing, its pin markups included.
        assertTrue(reducer.undo(0).changed)
        assertEquals("pin-path", vm.pageSubDrawings.getValue(0).single().photos.single().imagePaths.getValue("photo-1.jpg").single().id)

        assertTrue(reducer.detachSubDrawingPhoto(0, "sub-markups", "sub-pin", "photo-1.jpg").changed)
        val detached = vm.pageSubDrawings.getValue(0).single().photos.single()
        assertEquals(listOf("photo-2.jpg"), detached.imageFileNames)
        assertTrue(detached.imageNotes.keys == setOf("photo-2.jpg") || detached.imageNotes.keys.isEmpty())
    }

    @Test
    fun subDrawingPhotoCommands_rejectForeignSubDrawingsAndPins() {
        val pin = PhotoPin(x = 0.2f, y = 0.3f, id = "owned-pin", imageFileNames = listOf("photo-1.jpg"))
        val vm = vmWithSubDrawings(0, sub(id = "sub-a").copy(photos = listOf(pin)))
        vm.pagePhotoPins[0] = mutableStateListOf(PhotoPin(0.1f, 0.1f, id = "page-pin", imageFileNames = listOf("photo-1.jpg")))
        val reducer = reducer(vm)

        assertFalse(reducer.addSubDrawingPhoto(0, "absent-sub", PhotoPin(0.3f, 0.3f, id = "new-pin")).changed)
        assertFalse(reducer.deleteSubDrawingPhoto(0, "sub-a", PhotoPin(0.9f, 0.9f, id = "other-pin")).changed)
        assertFalse(reducer.attachSubDrawingPhoto(0, "sub-a", "absent-pin", "photo-3.jpg").changed)
        assertFalse(reducer.addSubDrawingImageShape(0, "sub-a", "owned-pin", "missing.jpg", Shape(0.1f, 0.1f, 0f, ShapeType.CIRCLE, -1, false, 0.005f, 0.1f, 0.1f, id = "ghost-shape")).changed)
        // The page pin is untouched by every rejected sub-drawing command.
        assertEquals(listOf("photo-1.jpg"), vm.pagePhotoPins.getValue(0).single().imageFileNames)
        assertEquals(listOf("owned-pin"), vm.pageSubDrawings.getValue(0).single().photos.map { it.id })
    }

    @Test
    fun photoPinLookup_findsPinsOwnedByASubDrawingForTheCameraHost() {
        // The camera host resolves a pin by identity across the page and its
        // sub-drawings, so capture/attach inside a sub-drawing works like on
        // the page. Lookup must not confuse same-id pins on different surfaces.
        val pagePin = PhotoPin(0.1f, 0.1f, id = "page-pin", imageFileNames = listOf("page-photo.jpg"))
        val nestedPin = PhotoPin(0.4f, 0.4f, id = "nested-pin", imageFileNames = emptyList())
        val vm = vmWithSubDrawings(0, sub(id = "cam-sub").copy(photos = listOf(nestedPin)))
        vm.pagePhotoPins[0] = mutableStateListOf(pagePin)
        val reducer = reducer(vm)

        assertEquals(listOf("page-pin", "nested-pin"), reducer.allPhotoPins(0).map { it.id })
        assertNull(reducer.photoPinOwnerSubDrawingId(0, "page-pin"))
        assertEquals("cam-sub", reducer.photoPinOwnerSubDrawingId(0, "nested-pin"))
        assertTrue(reducer.canAttachSubDrawingPhoto(0, "cam-sub", "nested-pin", "shot.jpg"))
        assertTrue(reducer.attachSubDrawingPhoto(0, "cam-sub", "nested-pin", "shot.jpg").changed)
        assertEquals(
            listOf("shot.jpg"),
            vm.pageSubDrawings.getValue(0).single().photos.single().imageFileNames
        )
        // The page pin is unchanged: a sub-drawing capture never lands on it.
        assertEquals(listOf("page-photo.jpg"), vm.pagePhotoPins.getValue(0).single().imageFileNames)
    }

    @Test
    fun clearPage_removesSubDrawingsWithOneUndo() {
        val vm = vmWithSubDrawings(0, sub(id = "clear-a"), sub(id = "clear-b", left = 0.6f, top = 0.1f, right = 0.9f, bottom = 0.4f))
        val reducer = reducer(vm)
        assertTrue(reducer.clearPage(0).changed)
        assertTrue(vm.pageSubDrawings.getValue(0).isEmpty())
        assertTrue(reducer.undo(0).changed)
        assertEquals(listOf("clear-a", "clear-b"), vm.pageSubDrawings.getValue(0).map { it.id })
    }

    @Test
    fun renameSubDrawing_isOneUndoableTransaction() {
        val vm = vmWithSubDrawings(0, sub(id = "name-sub"))
        val reducer = reducer(vm)

        assertEquals(AnnotationReducer.Result.Accepted, reducer.renameSubDrawing(0, "name-sub", "104"))
        assertEquals("104", vm.pageSubDrawings.getValue(0).single().name)

        // Undo restores the previous name; redo re-applies the new one.
        assertTrue(reducer.undo(0).changed)
        assertNull(vm.pageSubDrawings.getValue(0).single().name)
        assertTrue(reducer.redo(0).changed)
        assertEquals("104", vm.pageSubDrawings.getValue(0).single().name)

        // Renaming to a blank name clears it, and undo brings the name back.
        assertTrue(reducer.renameSubDrawing(0, "name-sub", "   ").changed)
        assertNull(vm.pageSubDrawings.getValue(0).single().name)
        assertTrue(reducer.undo(0).changed)
        assertEquals("104", vm.pageSubDrawings.getValue(0).single().name)
    }

    @Test
    fun renameSubDrawing_rejectsUnknownCropsAndOversizedNames() {
        val vm = vmWithSubDrawings(0, sub(id = "named"))
        val reducer = reducer(vm)

        assertFalse(reducer.renameSubDrawing(0, "absent-sub", "104").changed)
        assertFalse(reducer.renameSubDrawing(3, "named", "104").changed)
        assertFalse(
            reducer.renameSubDrawing(
                0,
                "named",
                "n".repeat(com.example.myapplication.stage5.Stage5Limits.MAX_ID_CHARS + 1)
            ).changed
        )
        assertEquals("named", vm.pageSubDrawings.getValue(0).single().id)
        assertNull(vm.pageSubDrawings.getValue(0).single().name)
    }

    @Test
    fun creatingNamingAndRemovingANewSubDrawing_stepsThroughHistory() {
        // The popup's Save path: the crop is added by the drag, then named, so
        // history steps back through the rename and then the crop itself, and
        // forward again with the name intact.
        val vm = BlueprintViewModel()
        vm.pageSubDrawings[0] = mutableStateListOf()
        val reducer = reducer(vm)
        val crop = sub(id = "room-1", left = 0.2f, top = 0.2f, right = 0.7f, bottom = 0.6f)

        assertTrue(reducer.addSubDrawing(0, crop).changed)
        val created = vm.pageSubDrawings.getValue(0).single()
        assertNull(created.name)
        assertTrue(reducer.renameSubDrawing(0, created.id, "Kitchen").changed)

        assertTrue(reducer.undo(0).changed)
        assertNull(vm.pageSubDrawings.getValue(0).single().name)
        assertTrue(reducer.undo(0).changed)
        assertTrue(vm.pageSubDrawings.getValue(0).isEmpty())
        assertTrue(reducer.redo(0).changed)
        assertTrue(vm.pageSubDrawings.getValue(0).single().name == null)
        assertTrue(reducer.redo(0).changed)
        assertEquals("Kitchen", vm.pageSubDrawings.getValue(0).single().name)
    }

    @Test
    fun subDrawingNames_areCarriedThroughClearPageUndo() {
        val vm = vmWithSubDrawings(0, sub(id = "keep-a").copy(name = "104"), sub(id = "keep-b", left = 0.6f, top = 0.1f, right = 0.9f, bottom = 0.4f))
        val reducer = reducer(vm)
        assertTrue(reducer.clearPage(0).changed)
        assertTrue(reducer.undo(0).changed)
        assertEquals(
            listOf("104", null),
            vm.pageSubDrawings.getValue(0).map { it.name }
        )
    }

    @Test
    fun subDrawingNames_areFrozenCopiesAtTheStateBoundary() {
        val vm = vmWithSubDrawings(0, sub(id = "frozen"))
        val reducer = reducer(vm)
        assertTrue(reducer.renameSubDrawing(0, "frozen", "Kitchen").changed)
        // History keeps its own value: a later rename is undone to the name it
        // recorded, not to whatever the live crop holds.
        assertTrue(reducer.renameSubDrawing(0, "frozen", "Pantry").changed)
        assertTrue(reducer.undo(0).changed)
        assertEquals("Kitchen", vm.pageSubDrawings.getValue(0).single().name)
    }

    @Test
    fun abandoningANewSubDrawing_leavesNoHistoryForUndoToResurrect() {
        // Dave's bug: drag, press Cancel in the naming popup, then Undo brought
        // the discarded crop back. A cancelled creation must be gone for good.
        val vm = BlueprintViewModel()
        vm.pageSubDrawings[0] = mutableStateListOf()
        // Pen strokes live in their own page list, like the viewer host creates them.
        vm.pagePaths[0] = mutableStateListOf()
        val reducer = reducer(vm)
        val stroke = DrawnPath(listOf(Point(0.1f, 0.1f), Point(0.4f, 0.4f)), 0xFF000000.toInt(), false)
        assertTrue(reducer.addPdfPath(0, stroke).changed)

        assertTrue(reducer.addSubDrawing(0, sub(id = "cancelled")).changed)
        val created = vm.pageSubDrawings.getValue(0).single()
        assertTrue(reducer.abandonSubDrawing(0, created).changed)
        assertTrue(vm.pageSubDrawings.getValue(0).isEmpty())

        // Undo reaches the pen stroke made before the crop, not the crop itself.
        assertTrue(reducer.canUndo(0))
        assertTrue(reducer.undo(0).changed)
        assertTrue(vm.pageSubDrawings.getValue(0).isEmpty())
        assertTrue(vm.pagePaths.getValue(0).isEmpty())

        // Redo replays only the pen stroke: the abandoned crop left no record, so
        // it cannot be restored forward.
        assertTrue(reducer.canRedo(0))
        assertTrue(reducer.redo(0).changed)
        assertEquals(1, vm.pagePaths.getValue(0).size)
        assertTrue(vm.pageSubDrawings.getValue(0).isEmpty())
    }

    @Test
    fun abandoningRejectsNamedMarkedUpAndForeignCrops() {
        val vm = BlueprintViewModel()
        vm.pageSubDrawings[0] = mutableStateListOf()
        val reducer = reducer(vm)

        assertTrue(reducer.addSubDrawing(0, sub(id = "named-crop")).changed)
        val named = vm.pageSubDrawings.getValue(0).single()
        assertTrue(reducer.renameSubDrawing(0, named.id, "104").changed)
        // A named crop is durable data: only the undoable delete removes it.
        assertFalse(reducer.abandonSubDrawing(0, named).changed)
        assertEquals("104", vm.pageSubDrawings.getValue(0).single().name)

        assertTrue(reducer.addSubDrawingPath(0, named.id, DrawnPath(listOf(Point(0.2f, 0.2f), Point(0.3f, 0.3f)), 0xFF000000.toInt(), false)).changed)
        val markedUp = vm.pageSubDrawings.getValue(0).single()
        assertFalse(reducer.abandonSubDrawing(0, markedUp).changed)

        // An unknown crop on an initialized page rejects without touching state.
        assertFalse(reducer.abandonSubDrawing(0, sub(id = "absent")).changed)
        assertEquals(1, vm.pageSubDrawings.getValue(0).size)
    }

    @Test
    fun abandoningClearsRedoSoForwardCannotRestoreTheDiscardedCrop() {
        val vm = BlueprintViewModel()
        vm.pageSubDrawings[0] = mutableStateListOf()
        val reducer = reducer(vm)
        assertTrue(reducer.addSubDrawing(0, sub(id = "redo-crop")).changed)
        val created = vm.pageSubDrawings.getValue(0).single()
        assertTrue(reducer.abandonSubDrawing(0, created).changed)
        // Abandon is not an undoable step, so Redo has nothing to replay.
        assertFalse(reducer.canRedo(0))
        assertFalse(reducer.redo(0).changed)
        assertTrue(vm.pageSubDrawings.getValue(0).isEmpty())
    }

    // Badge size (round 5: the badge belongs to the drawing)

    @Test
    fun badgeResize_isOneUndoableStepCoveringOnlyThatBadge() {
        val a = sub(id = "size-a").copy(badgeRatio = 0.03f)
        val b = sub(id = "size-b", left = 0.55f, top = 0.55f, right = 0.75f, bottom = 0.75f).copy(badgeRatio = 0.03f)
        val vm = vmWithSubDrawings(0, a, b)
        val reducer = reducer(vm)

        assertEquals(AnnotationReducer.Result.Accepted, reducer.resizeSubDrawingBadge(0, "size-a", 0.08f))
        val grown = vm.pageSubDrawings.getValue(0).first { it.id == "size-a" }
        assertEquals(0.08f, grown.badgeRatio!!, 0.0001f)
        // The other crop keeps its own size: a resize touches one badge only.
        assertEquals(0.03f, vm.pageSubDrawings.getValue(0).first { it.id == "size-b" }.badgeRatio!!, 0.0001f)

        assertTrue(reducer.undo(0).changed)
        assertEquals(0.03f, vm.pageSubDrawings.getValue(0).first { it.id == "size-a" }.badgeRatio!!, 0.0001f)
        assertTrue(reducer.redo(0).changed)
        assertEquals(0.08f, vm.pageSubDrawings.getValue(0).first { it.id == "size-a" }.badgeRatio!!, 0.0001f)
    }

    @Test
    fun badgeResize_rejectsUnknownCropsAndUnusableSizes() {
        val vm = vmWithSubDrawings(0, sub(id = "size-c").copy(badgeRatio = SubDrawingGeometry.DEFAULT_BADGE_RATIO))
        val reducer = reducer(vm)
        val before = vm.pageSubDrawings.getValue(0).single().badgeRatio

        assertFalse(reducer.resizeSubDrawingBadge(0, "absent-sub", 0.09f).changed)
        assertFalse(reducer.resizeSubDrawingBadge(3, "size-c", 0.09f).changed)
        assertFalse(reducer.resizeSubDrawingBadge(0, "size-c", Float.NaN).changed)
        assertFalse(reducer.resizeSubDrawingBadge(0, "size-c", Float.POSITIVE_INFINITY).changed)
        assertFalse(reducer.resizeSubDrawingBadge(0, "size-c", 0f).changed)
        assertFalse(reducer.resizeSubDrawingBadge(0, "size-c", 4f).changed)
        assertEquals(before, vm.pageSubDrawings.getValue(0).single().badgeRatio)
        // Nothing was recorded, so Undo cannot replay a rejected resize.
        assertFalse(reducer.canUndo(0))

        // Repeating the stored size changes nothing and records nothing.
        assertEquals(
            AnnotationReducer.Result.Unchanged,
            reducer.resizeSubDrawingBadge(0, "size-c", SubDrawingGeometry.DEFAULT_BADGE_RATIO)
        )
    }

    @Test
    fun badgeResize_clampsAPinchBeyondTheLimitsToTheRange() {
        val vm = vmWithSubDrawings(0, sub(id = "size-d").copy(badgeRatio = 0.03f))
        val reducer = reducer(vm)
        // The viewer clamps every pinch step before publishing it, so the size it
        // sends is always inside the range - and the reducer accepts that size.
        val overshot = SubDrawingGeometry.badgeRatioOrDefault(10f)
        assertEquals(SubDrawingGeometry.MAX_BADGE_RATIO, overshot, 0.0001f)
        assertTrue(reducer.resizeSubDrawingBadge(0, "size-d", overshot, AnnotationReducer.Kind.RESIZE).changed)
        assertEquals(
            SubDrawingGeometry.MAX_BADGE_RATIO,
            vm.pageSubDrawings.getValue(0).single().badgeRatio!!,
            0.0001f
        )
        val undershot = SubDrawingGeometry.badgeRatioOrDefault(0.0001f)
        assertEquals(SubDrawingGeometry.MIN_BADGE_RATIO, undershot, 0.0001f)
        assertTrue(reducer.resizeSubDrawingBadge(0, "size-d", undershot, AnnotationReducer.Kind.RESIZE).changed)
        assertEquals(
            SubDrawingGeometry.MIN_BADGE_RATIO,
            vm.pageSubDrawings.getValue(0).single().badgeRatio!!,
            0.0001f
        )
        // A raw out-of-range value never reaches the model: it is rejected outright.
        assertFalse(reducer.resizeSubDrawingBadge(0, "size-d", 10f, AnnotationReducer.Kind.RESIZE).changed)
        assertEquals(SubDrawingGeometry.MIN_BADGE_RATIO, vm.pageSubDrawings.getValue(0).single().badgeRatio!!, 0.0001f)
    }

    @Test
    fun badgeGeometry_sizesSpawnedByZoomAndSizedByCrop() {
        // Zoomed out (the whole sheet): the badge is the default page fraction.
        assertEquals(SubDrawingGeometry.DEFAULT_BADGE_RATIO, SubDrawingGeometry.badgeRatioForZoom(1f), 0.0001f)
        // Deep inside a small room (zoom 4): a quarter of that page space, so the
        // badge the user released is a small room tag on the sheet, not a huge circle.
        assertEquals(SubDrawingGeometry.DEFAULT_BADGE_RATIO / 4f, SubDrawingGeometry.badgeRatioForZoom(4f), 0.0001f)
        // The pinch range is the hard limit, and junk zoom values cannot escape it.
        assertEquals(SubDrawingGeometry.MIN_BADGE_RATIO, SubDrawingGeometry.badgeRatioForZoom(400f), 0.0001f)
        assertEquals(SubDrawingGeometry.DEFAULT_BADGE_RATIO, SubDrawingGeometry.badgeRatioForZoom(Float.NaN), 0.0001f)

        // A crop with no saved size (drawings saved before badge sizes existed):
        // the marker scales with its own room and never covers the sheet.
        val smallRoom = SubDrawingGeometry.defaultBadgeRatio(sub(right = 0.24f, bottom = 0.24f))
        val wholeSheet = SubDrawingGeometry.defaultBadgeRatio(sub(left = 0f, top = 0f, right = 1f, bottom = 1f))
        assertTrue(smallRoom < wholeSheet)
        assertTrue(wholeSheet <= SubDrawingGeometry.DEFAULT_BADGE_RATIO)
        assertTrue(wholeSheet < 0.5f)
        assertTrue(smallRoom >= SubDrawingGeometry.MIN_BADGE_RATIO)

        // A missing size resolves to the default, a stored size is clamped in range.
        assertEquals(SubDrawingGeometry.DEFAULT_BADGE_RATIO, SubDrawingGeometry.badgeRatioOrDefault(null), 0.0001f)
        assertEquals(SubDrawingGeometry.DEFAULT_BADGE_RATIO, SubDrawingGeometry.badgeRatioOrDefault(Float.NaN), 0.0001f)
        assertEquals(SubDrawingGeometry.MAX_BADGE_RATIO, SubDrawingGeometry.badgeRatioOrDefault(9f), 0.0001f)
        assertEquals(SubDrawingGeometry.MIN_BADGE_RATIO, SubDrawingGeometry.badgeRatioOrDefault(0f), 0.0001f)
        assertEquals(0.042f, SubDrawingGeometry.badgeRatioOrDefault(0.042f), 0.0001f)
    }

    @Test
    fun badgeSizeSurvivesAStoredRoundThroughTheModel() {
        // The size is part of the stored crop, so closing and reopening the page
        // (and every capture/apply of the canonical snapshot) keeps it.
        val vm = vmWithSubDrawings(0, sub(id = "keep-size"))
        val reducer = reducer(vm)
        assertTrue(reducer.resizeSubDrawingBadge(0, "keep-size", 0.11f).changed)
        val stored = vm.pageSubDrawings.getValue(0).single()
        assertEquals(0.11f, stored.copySubDrawing().badgeRatio!!, 0.0001f)

        val snapshot = com.example.myapplication.stage1.snapshotFromState(
            vm,
            com.example.myapplication.stage0.CurrentStateFixture.source(),
            snapshotRevision = 1L
        )
        assertEquals(0.11f, snapshot.pages.getValue(0).subDrawings.single().badgeRatio!!, 0.0001f)

        val restored = BlueprintViewModel()
        com.example.myapplication.stage1.applySnapshotReplace(snapshot, restored)
        assertEquals(0.11f, restored.pageSubDrawings.getValue(0).single().badgeRatio!!, 0.0001f)
    }
    @Test
    fun legacyBadgeSizes_openNoBiggerThanABadgeDrawnAtTheWholeSheetZoom() {
        // Drawings saved before badge sizes existed must open with markers about the
        // size a new badge has at the whole-sheet zoom - not the old huge circles.
        val smallRoom = SubDrawingGeometry.defaultBadgeRatio(sub(right = 0.24f, bottom = 0.24f))
        val midRoom = SubDrawingGeometry.defaultBadgeRatio(sub(right = 0.5f, bottom = 0.5f))
        val wholeSheet = SubDrawingGeometry.defaultBadgeRatio(sub(left = 0f, top = 0f, right = 1f, bottom = 1f))
        val newAtWholeSheet = SubDrawingGeometry.badgeRatioForZoom(1f)

        assertTrue(smallRoom <= midRoom)
        assertTrue(midRoom <= wholeSheet)
        // Nothing derived is ever bigger than a badge drawn today at zoom 1.
        assertTrue(wholeSheet <= newAtWholeSheet)
        assertTrue(midRoom <= newAtWholeSheet)
        assertTrue(smallRoom >= SubDrawingGeometry.MIN_BADGE_RATIO)

        // A stored size is honoured exactly, so a badge the user pinched keeps its own
        // size even when it is larger than the derived default.
        val vm = vmWithSubDrawings(0, sub(id = "legacy-a"), sub(id = "pinched", right = 0.3f, bottom = 0.3f).copy(badgeRatio = 0.2f))
        assertNull(vm.pageSubDrawings.getValue(0).first { it.id == "legacy-a" }.badgeRatio)
        assertEquals(0.2f, vm.pageSubDrawings.getValue(0).first { it.id == "pinched" }.badgeRatio!!, 0.0001f)
        assertTrue(
            SubDrawingGeometry.defaultBadgeRatio(vm.pageSubDrawings.getValue(0).first { it.id == "legacy-a" }) <= newAtWholeSheet
        )
    }
    @Test
    fun badgeHitArea_tracksTheStoredBadgeSize() {
        // The page hit-test uses the radius the canvas paints, so a small badge is
        // held by a small touch target and a big badge keeps its larger one. That
        // geometry lives in SubDrawingGeometry: the drawn radius is the stored ratio
        // times the displayed page width.
        val small = sub(id = "hit-small").copy(badgeRatio = 0.01f)
        val big = sub(id = "hit-big").copy(badgeRatio = 0.2f)
        val pageWidthPx = 1200f
        val compositeScale = 1f
        fun drawnRadius(s: SubDrawing): Float =
            (s.badgeRatio ?: SubDrawingGeometry.defaultBadgeRatio(s)) * pageWidthPx * compositeScale

        assertEquals(12f, drawnRadius(small), 0.0001f)
        assertEquals(240f, drawnRadius(big), 0.0001f)
        assertTrue(drawnRadius(small) < drawnRadius(big))

        // Zoom doubles both hit areas: the badge belongs to the drawing, so its touch
        // target scales with the sheet exactly like the painted marker.
        assertEquals(24f, drawnRadius(small.copy()) * 2f, 0.0001f)

        // A legacy crop with no stored size answers within the capped default radius.
        val legacy = sub(id = "hit-legacy")
        assertTrue(drawnRadius(legacy) <= SubDrawingGeometry.DEFAULT_BADGE_RATIO * pageWidthPx + 0.001f)
    }
    @Test
    fun labels_showTheTypedNameAndSheetNumberWithoutRewording() {
        val numbered: (Int) -> String = { index -> "Sub-drawing $index" }
        // The name is shown exactly as typed - no "Room" prefix.
        assertEquals(
            "Mechanical Room North Wing (E1)",
            SubDrawingLabels.open(sub(id = "a").copy(name = "Mechanical Room North Wing"), "E1", 2, numbered)
        )
        assertEquals("104 (E1)", SubDrawingLabels.open(sub(id = "b").copy(name = " 104 "), "E1", 1, numbered))
        assertEquals("Kitchen (SHEET 1)", SubDrawingLabels.open(sub(id = "c").copy(name = "Kitchen"), "SHEET 1", 3, numbered))
        // An unnamed crop reads as its number on the page.
        assertEquals("Sub-drawing 3", SubDrawingLabels.open(sub(id = "d"), "E1", 3, numbered))
        // A sheet with no code shows the name alone rather than empty brackets.
        assertEquals("104", SubDrawingLabels.open(sub(id = "e").copy(name = "104"), "", 1, numbered))

        // The overlap picker uses the same rule: name first, number otherwise.
        assertEquals("Kitchen", SubDrawingLabels.picker(sub(id = "f").copy(name = "Kitchen"), 4, numbered))
        assertEquals("Sub-drawing 4", SubDrawingLabels.picker(sub(id = "g").copy(name = "   "), 4, numbered))
        assertNull(SubDrawingLabels.typedName(sub(id = "h").copy(name = "")))
    }
}
