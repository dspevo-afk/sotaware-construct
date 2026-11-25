package com.example.myapplication.stage8

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.DrawnPath
import com.example.myapplication.Note
import com.example.myapplication.PageScale
import com.example.myapplication.PhotoPin
import com.example.myapplication.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions for the sub-drawing parity round (2026-11-21): clearing a photo
 * whose pin lives inside a sub-drawing, Clear inside a sub-drawing (its own
 * markups only, undo restores them), and sheet Clear keeping today's scope.
 */
class SubDrawingClearCommandsTest {

    private fun <T> list(vararg items: T): SnapshotStateList<T> = mutableStateListOf(*items)

    private fun vmWith(
        paths: SnapshotStateList<DrawnPath>,
        pins: SnapshotStateList<PhotoPin>,
        subs: SnapshotStateList<com.example.myapplication.SubDrawing>
    ): BlueprintViewModel {
        val vm = BlueprintViewModel()
        vm.pagePaths[0] = paths
        vm.pageMeasurements[0] = list()
        vm.pageNotes[0] = list()
        vm.pagePhotoPins[0] = pins
        vm.pageShapes[0] = list()
        vm.pageSubDrawings[0] = subs
        vm.pageScales[0] = PageScale(100f)
        return vm
    }

    private fun reducer(vm: BlueprintViewModel) = AnnotationReducer(
        vm,
        {},
        "test-session",
        { "test-session" },
        { true }
    )

    private fun photoPin() = PhotoPin(
        x = .3f, y = .4f,
        imageFileNames = listOf("a.jpg", "b.jpg"),
        imageNotes = mapOf("a.jpg" to listOf(Note(x = .1f, y = .1f, text = "cap"))),
        imagePaths = mapOf("a.jpg" to listOf(DrawnPath(points = listOf(Point(0f, 0f), Point(1f, 1f)), isHighlighter = false, colorArgb = -0x10000)))
    )

    private fun subWithPin() = com.example.myapplication.SubDrawing(
        left = .1f, top = .1f, right = .5f, bottom = .5f, name = "104",
        paths = listOf(DrawnPath(points = listOf(Point(0f, 0f), Point(.5f, .5f)), isHighlighter = false, colorArgb = -0xff0000)),
        notes = listOf(Note(x = .2f, y = .2f, text = "inside")),
        photos = listOf(photoPin())
    )

    @Test
    fun clearPhotoAnnotationsClearsMarkupsOnAPinInsideASubDrawing() {
        val sub = subWithPin()
        val pin = sub.photos.first()
        val vm = vmWith(list<DrawnPath>(), list<PhotoPin>(), list(sub))
        val reducer = reducer(vm)
        val result = reducer.clearPhotoAnnotations(0, pin.id, "a.jpg")
        assertEquals(AnnotationReducer.Result.Accepted, result)
        val stored = vm.pageSubDrawings[0]!!.first().photos.first()
        assertTrue(stored.imagePaths["a.jpg"].orEmpty().isEmpty())
        assertTrue(stored.imageNotes["a.jpg"].orEmpty().isEmpty())
        // The photo itself stays pinned; only its markups are cleared.
        assertEquals(listOf("a.jpg", "b.jpg"), stored.imageFileNames)
    }

    @Test
    fun clearPhotoAnnotationsUndoRestoresSubDrawingPinMarkups() {
        val sub = subWithPin()
        val pin = sub.photos.first()
        val vm = vmWith(list<DrawnPath>(), list<PhotoPin>(), list(sub))
        val reducer = reducer(vm)
        assertTrue(reducer.clearPhotoAnnotations(0, pin.id, "a.jpg").changed)
        assertTrue(reducer.undo(0).changed)
        val restored = vm.pageSubDrawings[0]!!.first().photos.first()
        assertEquals("cap", restored.imageNotes["a.jpg"]!!.first().text)
        assertEquals(1, restored.imagePaths["a.jpg"]!!.size)
    }

    @Test
    fun clearPhotoAnnotationsStillWorksForPageLevelPins() {
        val pin = photoPin()
        val vm = vmWith(list<DrawnPath>(), list(pin), list<com.example.myapplication.SubDrawing>())
        val reducer = reducer(vm)
        assertTrue(reducer.clearPhotoAnnotations(0, pin.id, "a.jpg").changed)
        val stored = vm.pagePhotoPins[0]!!.first()
        assertTrue(stored.imagePaths["a.jpg"].orEmpty().isEmpty())
        assertTrue(stored.imageNotes["a.jpg"].orEmpty().isEmpty())
    }

    @Test
    fun clearSubDrawingClearsOnlyItsOwnMarkupsAndUndoes() {
        val other = com.example.myapplication.SubDrawing(
            left = .6f, top = .6f, right = .9f, bottom = .9f, name = "105",
            paths = listOf(DrawnPath(points = listOf(Point(0f, 0f), Point(.2f, .2f)), isHighlighter = false, colorArgb = -0xffff00))
        )
        val sub = subWithPin()
        val vm = vmWith(
            list(DrawnPath(points = listOf(Point(0f, 0f), Point(.9f, .9f)), isHighlighter = false, colorArgb = -0x1)),
            list(photoPin()),
            list(sub, other)
        )
        val reducer = reducer(vm)
        assertEquals(AnnotationReducer.Result.Accepted, reducer.clearSubDrawing(0, sub.id))
        val cleared = vm.pageSubDrawings[0]!!.first { it.id == sub.id }
        assertTrue(cleared.paths.isEmpty() && cleared.notes.isEmpty())
        // Its photo stays pinned with its markups cleared.
        val clearedPin = cleared.photos.single()
        assertEquals(listOf("a.jpg", "b.jpg"), clearedPin.imageFileNames)
        assertTrue(clearedPin.imagePaths.values.all { it.isEmpty() })
        assertTrue(clearedPin.imageNotes.values.all { it.isEmpty() })
        // The crop's identity survives: name, badge and scale untouched.
        assertEquals("104", cleared.name)
        // The sheet's own markups, the sheet pins, and the other crop are untouched.
        assertEquals(1, vm.pagePaths[0]!!.size)
        assertEquals(1, vm.pagePhotoPins[0]!!.size)
        assertEquals(1, vm.pageSubDrawings[0]!!.first { it.id == other.id }.paths.size)
        // Undo brings all of the cleared sub-drawing's work back.
        assertTrue(reducer.undo(0).changed)
        val restored = vm.pageSubDrawings[0]!!.first { it.id == sub.id }
        assertEquals(1, restored.paths.size)
        assertEquals("inside", restored.notes.first().text)
        assertEquals("cap", restored.photos.single().imageNotes["a.jpg"]!!.first().text)
    }

    @Test
    fun clearSubDrawingOnEmptyCropIsUnchanged() {
        val empty = com.example.myapplication.SubDrawing(left = .1f, top = .1f, right = .5f, bottom = .5f)
        val vm = vmWith(list<DrawnPath>(), list<PhotoPin>(), list(empty))
        val reducer = reducer(vm)
        assertEquals(AnnotationReducer.Result.Unchanged, reducer.clearSubDrawing(0, empty.id))
    }

    @Test
    fun clearPageKeepsClearingSheetAndSubDrawingsTogether() {
        val sub = subWithPin()
        val vm = vmWith(
            list(DrawnPath(points = listOf(Point(0f, 0f), Point(.9f, .9f)), isHighlighter = false, colorArgb = -0x1)),
            list(photoPin()),
            list(sub)
        )
        val reducer = reducer(vm)
        assertTrue(reducer.clearPage(0).changed)
        assertTrue(vm.pagePaths[0]!!.isEmpty())
        // Today's sheet Clear takes the page's sub-drawings away as part of the
        // page snapshot (Undo brings them back with everything inside them).
        assertTrue(vm.pageSubDrawings[0]!!.isEmpty())
        assertTrue(vm.pagePhotoPins[0]!!.isEmpty())
        assertTrue(reducer.undo(0).changed)
        assertEquals(1, vm.pagePaths[0]!!.size)
        assertEquals("inside", vm.pageSubDrawings[0]!!.first().notes.first().text)
        assertEquals("cap", vm.pageSubDrawings[0]!!.first().photos.first().imageNotes["a.jpg"]!!.first().text)
    }

    @Test
    fun clearSubDrawingRejectsUnknownId() {
        val vm = vmWith(list<DrawnPath>(), list<PhotoPin>(), list(subWithPin()))
        val reducer = reducer(vm)
        assertEquals(AnnotationReducer.Result.Rejected, reducer.clearSubDrawing(0, "missing"))
    }

    @Test
    fun setSubDrawingImageScaleRoundTripsThroughStoredPin() {
        val sub = subWithPin()
        val pin = sub.photos.first()
        val vm = vmWith(list<DrawnPath>(), list<PhotoPin>(), list(sub))
        val reducer = reducer(vm)
        // setImageScale reads the stored pin from its owning surface.
        val result = reducer.setImageScale(0, pin.copyPin(), "a.jpg", PageScale(50f), 1f)
        assertEquals(AnnotationReducer.Result.Accepted, result)
        val stored = vm.pageSubDrawings[0]!!.first().photos.first()
        assertNotNull(stored.imageScales["a.jpg"])
        assertEquals(50f, stored.imageScales["a.jpg"]!!.pointsPerFoot)
    }
}
