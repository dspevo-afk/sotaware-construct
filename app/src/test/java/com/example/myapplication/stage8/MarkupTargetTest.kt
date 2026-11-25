package com.example.myapplication.stage8

import androidx.compose.runtime.mutableStateListOf
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.DrawnPath
import com.example.myapplication.Measurement
import com.example.myapplication.Note
import com.example.myapplication.PageItem
import com.example.myapplication.PhotoPin
import com.example.myapplication.Point
import com.example.myapplication.Shape
import com.example.myapplication.ShapeType
import com.example.myapplication.SubDrawing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The target-based markup commands. The regression this guards: photo-markup
 * edits on a pin placed inside a sub-drawing used page-only commands, which
 * looked the pin up on the page, found nothing and silently rejected the edit.
 */
class MarkupTargetTest {
    private fun reducer(vm: BlueprintViewModel) = AnnotationReducer(vm, {}, "s", { "s" }, { true })

    private val file = "photo-1.jpg"
    private fun subPin() = PhotoPin(x = 0.2f, y = 0.3f, id = "sub-pin", imageFileNames = listOf(file))
    private fun vmWithSubPin(): BlueprintViewModel = BlueprintViewModel().also {
        it.pageSubDrawings[0] = mutableStateListOf(SubDrawing(id = "sub", left = .1f, top = .1f, right = .6f, bottom = .6f, photos = listOf(subPin())))
        it.pagePhotoPins[0] = mutableStateListOf()
        it.pageNotes[0] = mutableStateListOf()
    }
    private fun storedSubPin(vm: BlueprintViewModel) = vm.pageSubDrawings.getValue(0).single().photos.single()

    @Test
    fun noteOnSubDrawingPinPhoto_movesEditsDeletesAndUndoes() {
        val vm = vmWithSubPin()
        val reducer = reducer(vm)
        val target = MarkupTarget.Photo(0, "sub-pin", file)
        val note = Note(x = .2f, y = .2f, text = "hi", id = "n1")

        assertTrue(reducer.add(target, PageItem.NoteItem(note)).changed)
        // The page-only command (what the photo viewer used to call) cannot see this pin.
        assertEquals(AnnotationReducer.Result.Rejected, reducer.updateImageNote(0, "sub-pin", file, note, note.copy(x = .5f)))

        val moved = note.copy(x = .5f, y = .6f)
        assertEquals(AnnotationReducer.Result.Accepted, reducer.update(target, PageItem.NoteItem(note), PageItem.NoteItem(moved), AnnotationReducer.Kind.MOVE))
        assertEquals(moved, storedSubPin(vm).imageNotes.getValue(file).single())

        val edited = moved.copy(text = "edited")
        assertTrue(reducer.update(target, PageItem.NoteItem(moved), PageItem.NoteItem(edited)).changed)
        // A stale "before" never commits.
        assertFalse(reducer.update(target, PageItem.NoteItem(moved), PageItem.NoteItem(moved.copy(text = "stale"))).changed)

        assertTrue(reducer.delete(target, PageItem.NoteItem(edited)).changed)
        assertTrue(storedSubPin(vm).imageNotes[file].isNullOrEmpty())
        assertTrue(vm.pagePhotoPins.getValue(0).isEmpty())

        assertTrue(reducer.undo(0).changed)
        assertEquals("edited", storedSubPin(vm).imageNotes.getValue(file).single().text)
        assertTrue(reducer.undo(0).changed)
        assertEquals(moved, storedSubPin(vm).imageNotes.getValue(file).single())
    }

    @Test
    fun shapeOnSubDrawingPinPhoto_resizesAndDeletes() {
        val vm = vmWithSubPin()
        val reducer = reducer(vm)
        val target = MarkupTarget.Photo(0, "sub-pin", file)
        val shape = Shape(.4f, .4f, 0f, ShapeType.CIRCLE, -65536, id = "s1")
        assertTrue(reducer.add(target, PageItem.ShapeItem(shape)).changed)
        val bigger = shape.copy(widthRatio = .2f, heightRatio = .2f)
        assertTrue(reducer.update(target, PageItem.ShapeItem(shape), PageItem.ShapeItem(bigger), AnnotationReducer.Kind.RESIZE).changed)
        assertEquals(bigger, storedSubPin(vm).imageShapes.getValue(file).single())
        assertTrue(reducer.delete(target, PageItem.ShapeItem(bigger)).changed)
        assertTrue(storedSubPin(vm).imageShapes[file].isNullOrEmpty())
        assertTrue(reducer.undo(0).changed)
        assertEquals(bigger, storedSubPin(vm).imageShapes.getValue(file).single())
    }

    @Test
    fun photoTarget_worksForPagePinsAndRejectsUnknownPhotosAndDuplicateIds() {
        val vm = BlueprintViewModel()
        vm.pagePhotoPins[0] = mutableStateListOf(PhotoPin(x = .5f, y = .5f, id = "page-pin", imageFileNames = listOf(file)))
        val reducer = reducer(vm)
        val target = MarkupTarget.Photo(0, "page-pin", file)
        val path = DrawnPath(listOf(Point(.1f, .1f), Point(.3f, .3f)), -65536, false, id = "p1")
        assertTrue(reducer.add(target, PageItem.Path(path)).changed)
        assertEquals("p1", vm.pagePhotoPins.getValue(0).single().imagePaths.getValue(file).single().id)
        // Same id as an existing markup on that photo, or a photo the pin does not have.
        assertFalse(reducer.add(target, PageItem.NoteItem(Note(x = .2f, y = .2f, text = "dup", id = "p1"))).changed)
        assertFalse(reducer.add(MarkupTarget.Photo(0, "page-pin", "missing.jpg"), PageItem.Path(path.copy(id = "p2"))).changed)
        assertFalse(reducer.add(MarkupTarget.Photo(0, "no-such-pin", file), PageItem.Path(path.copy(id = "p3"))).changed)
        // Pins and sub-drawings are never photo markups.
        assertFalse(reducer.add(target, PageItem.PhotoPinItem(PhotoPin(x = .1f, y = .1f, id = "x"))).changed)
    }

    @Test
    fun sheetAndSubDrawingTargets_routeToTheirOwnStorage() {
        val vm = vmWithSubPin()
        val reducer = reducer(vm)
        val note = Note(x = .3f, y = .3f, text = "page", id = "pn")
        assertTrue(reducer.add(MarkupTarget.Sheet(0), PageItem.NoteItem(note)).changed)
        assertEquals("pn", vm.pageNotes.getValue(0).single().id)
        val inside = Note(x = .3f, y = .3f, text = "inside", id = "sn")
        assertTrue(reducer.add(MarkupTarget.SubDrawing(0, "sub"), PageItem.NoteItem(inside)).changed)
        assertEquals("sn", vm.pageSubDrawings.getValue(0).single().notes.single().id)
        assertTrue(reducer.update(MarkupTarget.SubDrawing(0, "sub"), PageItem.NoteItem(inside), PageItem.NoteItem(inside.copy(x = .4f)), AnnotationReducer.Kind.MOVE).changed)
        assertTrue(reducer.delete(MarkupTarget.Sheet(0), PageItem.NoteItem(note)).changed)
        assertTrue(vm.pageNotes.getValue(0).isEmpty())
        // Mismatched item kinds never commit.
        assertFalse(reducer.update(MarkupTarget.Sheet(0), PageItem.NoteItem(note), PageItem.Measure(Measurement(Point(0f, 0f), Point(.1f, .1f), "1 ft"))).changed)
    }
}
