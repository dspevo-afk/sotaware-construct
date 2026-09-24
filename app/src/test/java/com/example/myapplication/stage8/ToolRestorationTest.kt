package com.example.myapplication.stage8

import androidx.compose.runtime.mutableStateListOf
import com.example.myapplication.*
import com.example.myapplication.stage1.*
import com.example.myapplication.stage5.decodeValidatedSnapshotJson
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ToolRestorationTest {
    private val photo = "11111111-1111-1111-1111-111111111111.jpg"
    private fun state() = BlueprintViewModel().apply {
        pagePaths[0] = mutableStateListOf(); pageMeasurements[0] = mutableStateListOf()
        pageNotes[0] = mutableStateListOf(); pageShapes[0] = mutableStateListOf(); pagePhotoPins[0] = mutableStateListOf()
    }
    private fun reducer(vm: BlueprintViewModel) = AnnotationReducer(vm, {}, "session", { "session" }, { true })
    private fun path(id: String = "stroke") = DrawnPath(listOf(Point(.2f, .3f), Point(.8f, .3f)), 0xffff0000.toInt(), false, .01f, id)

    @Test fun polylineMeasuresEveryLegInSourceCoordinates() {
        val vertices = listOf(Point(.1f, .1f), Point(.4f, .1f), Point(.4f, .5f))
        val m = requireNotNull(buildMeasurement(vertices, 100f, 200f, PageScale(10f), DrawingToolStyle()))
        assertEquals("11' 0\"", m.text)
        assertEquals(vertices, m.vertices)
        assertNull(buildMeasurement(vertices, 100f, 200f, null, DrawingToolStyle()))
        assertNull(measurementSourceLength(listOf(Point(Float.NaN, 0f), Point(1f, 1f)), 100f, 200f))
    }

    @Test fun photoDomainsRoundTripReplaceClearUndoAndRedo() {
        val vm = state(); val reducer = reducer(vm)
        assertTrue(reducer.addPhotoPin(0, PhotoPin(.5f, .5f, "pin", listOf(photo))).changed)
        fun pin() = vm.pagePhotoPins[0]!!.single()
        assertTrue(reducer.addImagePath(0, pin(), photo, path()).changed)
        val dimension = requireNotNull(buildMeasurement(listOf(Point(.1f, .1f), Point(.4f, .1f), Point(.4f, .5f)), 1f, .75f, PageScale(.01f), DrawingToolStyle()))
        assertTrue(reducer.addImageMeasurement(0, pin(), photo, dimension).changed)
        assertTrue(reducer.setImageScale(0, pin(), photo, PageScale(.01f), .75f).changed)
        assertTrue(reducer.addImageNote(0, "pin", photo, Note(.5f, .5f, "note", colorArgb = 0xff1565c0.toInt())).changed)
        assertTrue(reducer.addImageShape(0, "pin", photo, Shape(.7f, .7f, 0f, ShapeType.CIRCLE, 7)).changed)
        val before = pin().copyPin()
        val snapshot = snapshotFromState(vm, DocumentSourceIdentityV1("content://fixture/drawing"), 1L)
        val decoded = decodeValidatedSnapshotJson(Gson(), Gson().toJson(snapshot), "tool fixture")
        val restored = state()
        applySnapshotReplace(decoded, restored)
        assertEquals(before, restored.pagePhotoPins[0]!!.single())
        assertTrue(reducer.clearImageAnnotations(0, pin(), photo).changed)
        assertTrue(pin().imagePaths.isEmpty() && pin().imageMeasurements.isEmpty() && pin().imageScales.isEmpty())
        assertEquals(listOf(photo), pin().imageFileNames)
        assertTrue(reducer.undo(0).changed); assertEquals(before, pin())
        assertTrue(reducer.redo(0).changed); assertTrue(pin().imageNotes.isEmpty())
    }

    @Test fun appearanceEditsPreserveGeometryAndAreUndoable() {
        val vm = state(); val reducer = reducer(vm); val path = path()
        assertTrue(reducer.addPdfPath(0, path).changed)
        assertTrue(reducer.changeAppearance(0, PageItem.Path(path), DrawingToolStyle(colorArgb = 5, width = .02f)).changed)
        assertEquals(path.points, vm.pagePaths[0]!!.single().points)
        assertEquals(5, vm.pagePaths[0]!!.single().colorArgb)
        assertTrue(reducer.undo(0).changed); assertEquals(path, vm.pagePaths[0]!!.single())
        val note = Note(.4f, .6f, "anchored", rotation = 32f)
        assertTrue(reducer.addPdfNote(0, note).changed)
        assertTrue(reducer.changeAppearance(0, PageItem.NoteItem(note), DrawingToolStyle(fontSize = .06f, colorArgb = 9)).changed)
        val resized = vm.pageNotes[0]!!.single()
        assertEquals(note.x, resized.x); assertEquals(note.y, resized.y); assertEquals(note.rotation, resized.rotation)
        assertEquals(AnnotationReducer.Result.Rejected, reducer.changeAppearance(0, PageItem.NoteItem(note), DrawingToolStyle()))
    }

    @Test fun duplicateAndUnattachedImageAnnotationsAreRejectedWithoutMutation() {
        val vm = state(); val reducer = reducer(vm)
        reducer.addPhotoPin(0, PhotoPin(.5f, .5f, "pin", listOf(photo)))
        val pin = vm.pagePhotoPins[0]!!.single()
        assertEquals(AnnotationReducer.Result.Rejected, reducer.addImagePath(0, pin, "unattached.jpg", path()))
        reducer.addImagePath(0, pin, photo, path("same-id"))
        val accepted = vm.pagePhotoPins[0]!!.single()
        assertEquals(AnnotationReducer.Result.Rejected, reducer.addImageNote(0, "pin", photo, Note(.5f, .5f, "bad", id = "same-id")))
        assertEquals(accepted, vm.pagePhotoPins[0]!!.single())
    }

    @Test fun toolDefaultsRemainRedAndYellowUntilChanged() {
        val defaults = DrawingToolSettings()
        assertEquals(0xffff0000.toInt(), defaults.style(ToolMode.PEN).colorArgb)
        assertEquals(0xffffff00.toInt(), defaults.style(ToolMode.HIGHLIGHTER).colorArgb)
        val custom = defaults.withStyle(ToolMode.PEN, defaults.style(ToolMode.PEN).copy(colorArgb = 0xff1565c0.toInt()))
        assertEquals(0xff1565c0.toInt(), custom.style(ToolMode.PEN).colorArgb)
        assertEquals(defaults.style(ToolMode.HIGHLIGHTER), custom.style(ToolMode.HIGHLIGHTER))
    }
}
