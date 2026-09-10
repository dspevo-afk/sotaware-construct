package com.example.myapplication.stage8

import androidx.compose.runtime.mutableStateListOf
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.DrawnPath
import com.example.myapplication.Measurement
import com.example.myapplication.Note
import com.example.myapplication.PageScale
import com.example.myapplication.PhotoImageNote
import com.example.myapplication.PhotoPin
import com.example.myapplication.Point
import com.example.myapplication.Shape
import com.example.myapplication.ShapeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reducer tests use the current normalized, ID-addressed model directly.
 * They intentionally do not construct compatibility history records or mutate
 * annotation fields in place: one reducer owns every editable transition.
 */
class AnnotationReducerTest {
    private fun reducer(
        vm: BlueprintViewModel,
        effectSink: (AnnotationReducer.EffectIntent) -> Unit = {},
        sessionKey: Any = "test-session",
        currentSessionKey: () -> Any? = { sessionKey },
        sessionActivePredicate: () -> Boolean = { true }
    ) = AnnotationReducer(
        vm,
        effectSink,
        sessionKey,
        currentSessionKey,
        sessionActivePredicate
    )

    private fun path(id: String = "path") = DrawnPath(
        points = listOf(Point(.1f, .2f), Point(.2f, .3f)),
        colorArgb = 1,
        isHighlighter = false,
        strokeWidthRatio = .01f,
        id = id
    )

    private fun measurement(id: String = "measurement", start: Float = .1f) = Measurement(
        p1 = Point(start, .1f),
        p2 = Point((start + .3f).coerceAtMost(1f), .1f),
        text = "3",
        id = id
    )

    private fun note(
        id: String = "note",
        text: String = "note",
        x: Float = .1f,
        y: Float = .2f,
        ratio: Float = .02f,
        rotation: Float = 0f
    ) = Note(
        x = x,
        y = y,
        text = text,
        isBold = false,
        rotation = rotation,
        fontSizeRatio = ratio,
        id = id
    )

    private fun imageNote(
        id: String = "image-note",
        text: String = "caption",
        x: Float = .2f,
        y: Float = .3f,
        ratio: Float = .02f,
        rotation: Float = 0f
    ) = PhotoImageNote(
        x = x,
        y = y,
        text = text,
        isBold = false,
        rotation = rotation,
        fontSizeRatio = ratio,
        id = id
    )

    private fun shape(id: String = "shape", x: Float = .5f, rotation: Float = 0f) = Shape(
        x = x,
        y = .5f,
        rotation = rotation,
        type = ShapeType.RECTANGLE,
        colorArgb = 1,
        isFilled = false,
        strokeWidthRatio = .01f,
        widthRatio = .2f,
        heightRatio = .1f,
        id = id
    )

    private fun pin(
        id: String = "pin",
        fileNames: List<String> = listOf("photo.jpg"),
        imageNotes: Map<String, List<PhotoImageNote>> = emptyMap(),
        imageShapes: Map<String, List<Shape>> = emptyMap()
    ) = PhotoPin(
        x = .1f,
        y = .2f,
        id = id,
        imageFileNames = fileNames,
        imageNotes = imageNotes,
        imageShapes = imageShapes
    )

    @Test
    fun clearPageIsAtomicDeepUndoableAndRedoableAcrossAllDomains() {
        val vm = BlueprintViewModel()
        vm.pagePaths[2] = mutableStateListOf(path("clear-path"))
        vm.pageMeasurements[2] = mutableStateListOf(measurement("clear-measurement"))
        vm.pageNotes[2] = mutableStateListOf(note("clear-note", "n"))
        val initialPin = pin(
            "clear-pin",
            imageNotes = mapOf("photo.jpg" to listOf(imageNote("clear-image-note"))),
            imageShapes = mapOf("photo.jpg" to listOf(shape("clear-image-shape")))
        )
        vm.pagePhotoPins[2] = mutableStateListOf(initialPin)
        vm.pageScales[2] = PageScale(4f)
        vm.pageShapes[2] = mutableStateListOf(shape("clear-page-shape"))
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = reducer(vm, effectSink = { effects += it })

        assertTrue(reducer.clearPage(2).changed)
        assertTrue(vm.pagePaths[2]!!.isEmpty() && vm.pagePhotoPins[2]!!.isEmpty())
        assertFalse(vm.pageScales.containsKey(2))
        assertEquals(AnnotationReducer.Kind.CLEAR, effects.single().kind)

        assertTrue(reducer.undo(2).changed)
        assertEquals("caption", vm.pagePhotoPins[2]!![0].imageNotes["photo.jpg"]!![0].text)
        // The restored graph is detached from the clear entry's snapshot.
        val restoredPin = vm.pagePhotoPins[2]!![0]
        val detachedMutation = restoredPin.copy(
            imageNotes = restoredPin.imageNotes +
                ("photo.jpg" to listOf(restoredPin.imageNotes["photo.jpg"]!![0].copy(text = "live")))
        )
        assertEquals("live", detachedMutation.imageNotes["photo.jpg"]!![0].text)
        assertEquals("caption", restoredPin.imageNotes["photo.jpg"]!![0].text)
        assertTrue(reducer.redo(2).changed)
        assertTrue(vm.pagePhotoPins[2]!!.isEmpty())
        assertTrue(reducer.undo(2).changed)
        assertEquals("caption", vm.pagePhotoPins[2]!![0].imageNotes["photo.jpg"]!![0].text)
        assertEquals(4f, vm.pageScales[2]!!.pointsPerFoot)
    }

    @Test
    fun clearRejectsStaleSessionAndLeavesOtherPageUntouched() {
        val vm = BlueprintViewModel()
        vm.pageNotes[0] = mutableStateListOf(note("page0", "page0"))
        vm.pageNotes[1] = mutableStateListOf(note("page1", "page1"))
        var current = "A"
        val reducer = AnnotationReducer(
            vm,
            sessionKey = "A",
            currentSessionKey = { current },
            sessionActivePredicate = { true }
        )
        current = "B"
        assertFalse(reducer.clearPage(0).changed)
        assertEquals("page0", vm.pageNotes[0]!![0].text)
        assertEquals("page1", vm.pageNotes[1]!![0].text)
    }

    @Test
    fun clearIsOneReducerTransactionAndDoesNotDestroyEarlierHistory() {
        val vm = BlueprintViewModel()
        vm.pageNotes[0] = mutableStateListOf()
        val reducer = reducer(vm)
        val added = note("before-clear", "before")
        assertTrue(reducer.addPdfNote(0, added).changed)
        assertTrue(reducer.clearPage(0).changed)
        assertTrue(reducer.undo(0).changed)
        assertEquals(listOf("before"), vm.pageNotes[0]!!.map { it.text })
        assertTrue(reducer.undo(0).changed)
        assertTrue(vm.pageNotes[0]!!.isEmpty())
        assertTrue(reducer.redo(0).changed)
        assertEquals(listOf("before"), vm.pageNotes[0]!!.map { it.text })
        assertTrue(reducer.redo(0).changed)
        assertTrue(vm.pageNotes[0]!!.isEmpty())
    }

    @Test
    fun inactiveSessionRejectsMutationAndHistoryWithoutEffects() {
        val vm = BlueprintViewModel()
        vm.pageNotes[0] = mutableStateListOf()
        var active = true
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = reducer(
            vm,
            effectSink = { effects += it },
            sessionKey = "active",
            currentSessionKey = { "active" },
            sessionActivePredicate = { active }
        )
        assertTrue(reducer.addPdfNote(0, note("live", "live")).changed)
        active = false
        assertFalse(reducer.addPdfNote(0, note("stale", "stale")).changed)
        assertFalse(reducer.undo(0).changed)
        assertEquals(1, vm.pageNotes[0]!!.size)
        assertEquals(1, effects.size)
    }

    @Test
    fun pdfNoteAndShapeTransitionsUndoRedoAndClearRedo() {
        val vm = BlueprintViewModel()
        vm.pageNotes[0] = mutableStateListOf()
        vm.pageShapes[0] = mutableStateListOf()
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = reducer(vm, effectSink = { effects += it })
        val originalNote = note("pdf-note", "old")
        assertTrue(reducer.addPdfNote(0, originalNote).changed)
        val editedNote = originalNote.copy(text = "new")
        assertTrue(reducer.updatePdfNoteAt(0, 0, editedNote, before = originalNote).changed)
        assertEquals("new", vm.pageNotes[0]!![0].text)
        assertTrue(reducer.undo(0).changed)
        assertEquals("old", vm.pageNotes[0]!![0].text)
        assertTrue(reducer.redo(0).changed)
        assertEquals("new", vm.pageNotes[0]!![0].text)
        assertTrue(reducer.deletePdfNote(0, vm.pageNotes[0]!![0]).changed)
        assertFalse(reducer.canRedo(0))
        assertTrue(reducer.undo(0).changed)
        assertEquals(1, vm.pageNotes[0]!!.size)

        val originalShape = shape("page-shape")
        assertTrue(reducer.addPdfShape(0, originalShape).changed)
        val movedShape = originalShape.copy(x = .7f)
        assertTrue(reducer.movePdfShape(0, originalShape, movedShape).changed)
        assertEquals(.7f, vm.pageShapes[0]!![0].x)
        assertTrue(reducer.undo(0).changed)
        assertEquals(.5f, vm.pageShapes[0]!![0].x)
        assertTrue(reducer.redo(0).changed)
        assertEquals(.7f, vm.pageShapes[0]!![0].x)
        assertTrue(effects.any { it.kind == AnnotationReducer.Kind.UNDO })
        assertTrue(effects.any { it.kind == AnnotationReducer.Kind.REDO })
    }

    @Test
    fun allPdfDomainsUseReducerHistoryIncludingPhotoAttachmentAndScale() {
        val vm = BlueprintViewModel()
        vm.pagePaths[0] = mutableStateListOf()
        vm.pageMeasurements[0] = mutableStateListOf()
        vm.pagePhotoPins[0] = mutableStateListOf()
        val reducer = reducer(vm)
        val pdfPath = path("all-path")
        val measured = measurement("all-measurement")
        val photoPin = pin("all-pin", emptyList())

        assertTrue(reducer.addPdfPath(0, pdfPath).changed)
        assertTrue(reducer.addMeasurement(0, measured).changed)
        assertTrue(reducer.addPhotoPin(0, photoPin).changed)
        assertTrue(reducer.attachPhoto(0, photoPin, "photo.jpg").changed)
        assertEquals(listOf("photo.jpg"), vm.pagePhotoPins[0]!![0].imageFileNames)
        assertTrue(reducer.setScale(0, PageScale(12f)).changed)

        val moved = measured.copy(p1 = Point(.2f, .2f), p2 = Point(.5f, .2f))
        assertTrue(reducer.moveMeasurement(0, measured, moved).changed)
        assertEquals(.2f, vm.pageMeasurements[0]!![0].p1.x)
        assertTrue(reducer.undo(0).changed)
        assertEquals(.1f, vm.pageMeasurements[0]!![0].p1.x)
        assertTrue(reducer.redo(0).changed)
        assertTrue(reducer.deletePdfPath(0, vm.pagePaths[0]!![0]).changed)
        assertTrue(reducer.deletePhotoPin(0, vm.pagePhotoPins[0]!![0]).changed)
        assertTrue(reducer.undo(0).changed)
        assertEquals(1, vm.pagePhotoPins[0]!!.size)
    }

    @Test
    fun staleShapeUpdateCannotOverwriteNewerGeometry() {
        val vm = BlueprintViewModel()
        vm.pageShapes[0] = mutableStateListOf()
        val reducer = reducer(vm)
        val original = shape("stale-shape")
        assertTrue(reducer.addPdfShape(0, original).changed)
        val committed = original.copy(x = .7f)
        assertTrue(reducer.movePdfShape(0, original, committed).changed)
        val staleReplacement = original.copy(x = .2f)

        assertFalse(reducer.movePdfShape(0, original, staleReplacement).changed)
        assertEquals(.7f, vm.pageShapes[0]!![0].x)
        assertTrue(reducer.canUndo(0))
    }

    @Test
    fun measurementMovesRemainAddressableAcrossMultipleReducerUpdates() {
        val vm = BlueprintViewModel()
        vm.pageMeasurements[0] = mutableStateListOf()
        val reducer = reducer(vm)
        val original = measurement("moving-measurement", .1f)
        assertTrue(reducer.addMeasurement(0, original).changed)
        val first = original.copy(p1 = Point(.2f, .1f), p2 = Point(.5f, .1f))
        val second = first.copy(p1 = Point(.3f, .1f), p2 = Point(.6f, .1f))
        assertTrue(reducer.moveMeasurement(0, original, first).changed)
        assertTrue(reducer.moveMeasurement(0, first, second).changed)
        assertEquals(.3f, vm.pageMeasurements[0]!![0].p1.x)
        assertTrue(reducer.undo(0).changed)
        assertEquals(.2f, vm.pageMeasurements[0]!![0].p1.x)
        assertTrue(reducer.undo(0).changed)
        assertEquals(.1f, vm.pageMeasurements[0]!![0].p1.x)
    }

    @Test
    fun equalValuedMeasurementsUseStableDistinctIdsDuringGestureCommit() {
        val vm = BlueprintViewModel()
        val first = measurement("measurement-first")
        val second = measurement("measurement-second")
        vm.pageMeasurements[0] = mutableStateListOf(first, second)
        val reducer = reducer(vm)
        val replacement = second.copy(p1 = Point(.2f, .1f), p2 = Point(.5f, .1f))

        assertTrue(reducer.updateMeasurementAt(0, 1, replacement, AnnotationReducer.Kind.MOVE, before = second).changed)
        assertEquals(.1f, vm.pageMeasurements[0]!![0].p1.x)
        assertEquals(.2f, vm.pageMeasurements[0]!![1].p1.x)
        assertTrue(reducer.undo(0).changed)
        assertEquals(.1f, vm.pageMeasurements[0]!![1].p1.x)
    }

    @Test
    fun equalValuedPdfNotesUseStableDistinctIdsRatherThanEquality() {
        val vm = BlueprintViewModel()
        vm.pageNotes[0] = mutableStateListOf()
        val first = note("note-first", "same")
        val second = note("note-second", "same")
        vm.pageNotes[0]!!.add(first)
        vm.pageNotes[0]!!.add(second)
        val reducer = reducer(vm)
        val replacement = second.copy(text = "second")
        assertTrue(reducer.updatePdfNoteAt(0, 1, replacement, before = second).changed)
        assertEquals("same", vm.pageNotes[0]!![0].text)
        assertEquals("second", vm.pageNotes[0]!![1].text)
        assertTrue(reducer.deletePdfNoteAt(0, 1, replacement).changed)
        assertEquals("same", vm.pageNotes[0]!![0].text)
        assertTrue(reducer.undo(0).changed)
        assertEquals("second", vm.pageNotes[0]!![1].text)
        assertTrue(reducer.redo(0).changed)
        assertEquals(listOf("same"), vm.pageNotes[0]!!.map { it.text })
    }

    @Test
    fun ratioDraftCommitsOneReducerTransitionWithUndoRedoEffects() {
        val vm = BlueprintViewModel()
        val original = note("pinch-note", "pinch", ratio = .02f)
        vm.pageNotes[0] = mutableStateListOf(original)
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = reducer(vm, effectSink = { effects += it })
        val draft = original.copy(fontSizeRatio = .03f, rotation = 18f)

        assertTrue(reducer.updatePdfNoteAt(0, 0, draft, before = original).changed)
        assertEquals(1, effects.size)
        assertEquals(.03f, vm.pageNotes[0]!![0].fontSizeRatio)
        assertTrue(reducer.undo(0).changed)
        assertEquals(.02f, vm.pageNotes[0]!![0].fontSizeRatio)
        assertTrue(reducer.redo(0).changed)
        assertEquals(18f, vm.pageNotes[0]!![0].rotation)
        assertEquals(3, effects.size)
    }

    @Test
    fun sessionPredicateRejectsUndoRedoAfterSwitch() {
        val vm = BlueprintViewModel()
        vm.pageShapes[0] = mutableStateListOf()
        var active = true
        var currentSession = "A"
        var effects = 0
        val reducer = AnnotationReducer(
            vm,
            effectSink = { effects++ },
            sessionActivePredicate = { active },
            sessionKey = "A",
            currentSessionKey = { currentSession }
        )
        val original = shape("session-shape")
        assertTrue(reducer.addPdfShape(0, original).changed)
        currentSession = "B"
        val beforeEffects = effects
        assertFalse(reducer.undo(0).changed)
        assertFalse(reducer.redo(0).changed)
        assertEquals(1, vm.pageShapes[0]!!.size)
        assertEquals(beforeEffects, effects)
        assertFalse(reducer.canUndo(0))
        active = false
        assertFalse(reducer.deletePdfShape(0, original).changed)
    }

    @Test
    fun deleteUndoRedoRestoresPdfAndImageOrdering() {
        val vm = BlueprintViewModel()
        vm.pageShapes[0] = mutableStateListOf()
        vm.pagePhotoPins[0] = mutableStateListOf()
        val n1 = imageNote("n1", "1")
        val n2 = imageNote("n2", "2", x = .3f, y = .4f)
        val s1 = shape("s1")
        val s2 = shape("s2", x = .6f)
        val pin = pin(
            "ordering-pin",
            imageNotes = mapOf("photo.jpg" to listOf(n1, n2)),
            imageShapes = mapOf("photo.jpg" to listOf(s1, s2))
        )
        vm.pagePhotoPins[0]!!.add(pin)
        val reducer = reducer(vm)
        val a = shape("a")
        val b = shape("b")
        vm.pageShapes[0]!!.add(a)
        vm.pageShapes[0]!!.add(b)
        assertTrue(reducer.deletePdfShape(0, a).changed)
        assertTrue(reducer.undo(0).changed)
        assertEquals(listOf("a", "b"), vm.pageShapes[0]!!.map { it.id })
        assertTrue(reducer.redo(0).changed)
        assertEquals(listOf("b"), vm.pageShapes[0]!!.map { it.id })

        assertTrue(reducer.deleteImageNote(0, pin.id, "photo.jpg", n1).changed)
        assertTrue(reducer.undo(0).changed)
        assertTrue(reducer.redo(0).changed)
        assertTrue(reducer.undo(0).changed)
        assertEquals(
            listOf(n1.id, n2.id),
            vm.pagePhotoPins[0]!![0].imageNotes["photo.jpg"]!!.map { it.id }
        )

        assertTrue(reducer.deleteImageShape(0, pin.id, "photo.jpg", s1).changed)
        assertTrue(reducer.undo(0).changed)
        assertTrue(reducer.redo(0).changed)
        assertTrue(reducer.undo(0).changed)
        assertEquals(
            listOf("s1", "s2"),
            vm.pagePhotoPins[0]!![0].imageShapes["photo.jpg"]!!.map { it.id }
        )
    }

    @Test
    fun imageNestedStateIsDeepCopiedAndMissingTargetsAreNoOp() {
        val vm = BlueprintViewModel()
        vm.pagePhotoPins[0] = mutableStateListOf()
        val imageNote = imageNote("nested-note")
        val imageShape = shape("nested-shape")
        val pin = pin(
            "nested-pin",
            imageNotes = mapOf("photo.jpg" to listOf(imageNote)),
            imageShapes = mapOf("photo.jpg" to listOf(imageShape))
        )
        vm.pagePhotoPins[0]!!.add(pin)
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = reducer(vm, effectSink = { effects += it })
        val addedShape = shape("added-shape")
        assertFalse(reducer.addImageShape(0, pin.id, "missing.jpg", addedShape).changed)
        assertTrue(reducer.addImageShape(0, pin.id, "photo.jpg", addedShape).changed)
        assertTrue(reducer.undo(0).changed)
        assertTrue(reducer.redo(0).changed)
        val edited = imageNote.copy(text = "changed")
        assertTrue(reducer.updateImageNote(0, pin.id, "photo.jpg", imageNote, edited).changed)
        assertTrue(reducer.undo(0).changed)
        // Mutating the detached object used to start the gesture must not
        // mutate the reducer's deep-copied history entry.
        val detachedMutation = imageNote.copy(text = "detached mutation")
        assertEquals("detached mutation", detachedMutation.text)
        assertTrue(reducer.redo(0).changed)
        assertEquals("changed", vm.pagePhotoPins[0]!![0].imageNotes["photo.jpg"]!![0].text)
        assertTrue(reducer.deleteImageShape(0, pin.id, "photo.jpg", imageShape).changed)
        assertTrue(reducer.undo(0).changed)
        assertEquals(2, vm.pagePhotoPins[0]!![0].imageShapes["photo.jpg"]!!.size)
        assertFalse(reducer.deleteImageNote(0, "missing", "photo.jpg", imageNote).changed)
        assertEquals(8, effects.size)

        val copy = vm.pagePhotoPins[0]!![0].copyPin()
        val mutatedCopy = copy.copy(
            imageNotes = copy.imageNotes +
                ("photo.jpg" to listOf(copy.imageNotes["photo.jpg"]!![0].copy(text = "copy only"))),
            imageShapes = copy.imageShapes +
                ("photo.jpg" to listOf(copy.imageShapes["photo.jpg"]!![0].copy(x = .9f)))
        )
        assertEquals("copy only", mutatedCopy.imageNotes["photo.jpg"]!![0].text)
        assertEquals(.9f, mutatedCopy.imageShapes["photo.jpg"]!![0].x)
        assertEquals("changed", vm.pagePhotoPins[0]!![0].imageNotes["photo.jpg"]!![0].text)
        assertEquals(.5f, vm.pagePhotoPins[0]!![0].imageShapes["photo.jpg"]!![0].x)
    }

    @Test
    fun staleImageNoteUpdateAndDeleteCannotReplaceNewerValue() {
        val vm = BlueprintViewModel()
        vm.pagePhotoPins[0] = mutableStateListOf()
        val original = imageNote("note-1", "before")
        val pin = pin("stale-note-pin", imageNotes = mapOf("photo.jpg" to listOf(original)))
        vm.pagePhotoPins[0]!!.add(pin)
        val reducer = reducer(vm)
        val committed = original.copy(x = .8f, text = "newer")
        assertTrue(reducer.updateImageNote(0, pin.id, "photo.jpg", original, committed).changed)

        val staleReplacement = original.copy(text = "stale")
        assertFalse(reducer.updateImageNote(0, pin.id, "photo.jpg", original, staleReplacement).changed)
        assertFalse(reducer.deleteImageNote(0, pin.id, "photo.jpg", original).changed)
        assertEquals(committed, vm.pagePhotoPins[0]!![0].imageNotes["photo.jpg"]!!.single())
    }

    @Test
    fun staleImageShapeUpdateAndDeleteCannotReplaceNewerGeometry() {
        val vm = BlueprintViewModel()
        vm.pagePhotoPins[0] = mutableStateListOf()
        val original = shape("image-shape-stale")
        val pin = pin("stale-shape-pin", imageShapes = mapOf("photo.jpg" to listOf(original)))
        vm.pagePhotoPins[0]!!.add(pin)
        val reducer = reducer(vm)
        val committed = original.copy(x = .8f, rotation = 24f)
        assertTrue(reducer.updateImageShape(0, pin.id, "photo.jpg", original, committed).changed)

        val staleReplacement = original.copy(x = .1f)
        assertFalse(reducer.updateImageShape(0, pin.id, "photo.jpg", original, staleReplacement).changed)
        assertFalse(reducer.deleteImageShape(0, pin.id, "photo.jpg", original).changed)
        assertEquals(committed, vm.pagePhotoPins[0]!![0].imageShapes["photo.jpg"]!!.single())
    }

    @Test
    fun stalePdfShapeDeleteCannotRemoveNewerGeometry() {
        val vm = BlueprintViewModel()
        vm.pageShapes[0] = mutableStateListOf()
        val reducer = reducer(vm)
        val original = shape("pdf-shape-stale")
        assertTrue(reducer.addPdfShape(0, original).changed)
        val committed = original.copy(y = .8f)
        assertTrue(reducer.updatePdfShape(0, original, committed).changed)

        assertFalse(reducer.deletePdfShape(0, original).changed)
        assertEquals(committed, vm.pageShapes[0]!!.single())
    }

    @Test
    fun stalePhotoPinUpdateAndDeleteCannotReplaceNewerPosition() {
        val vm = BlueprintViewModel()
        vm.pagePhotoPins[0] = mutableStateListOf()
        val reducer = reducer(vm)
        val original = pin("pin-stale", emptyList())
        assertTrue(reducer.addPhotoPin(0, original).changed)
        val committed = original.copy(x = .8f)
        assertTrue(reducer.updatePhotoPin(0, original, committed).changed)

        val staleReplacement = original.copy(y = .9f)
        assertFalse(reducer.updatePhotoPin(0, original, staleReplacement).changed)
        assertFalse(reducer.deletePhotoPin(0, original).changed)
        assertEquals(committed, vm.pagePhotoPins[0]!!.single())
    }
}
