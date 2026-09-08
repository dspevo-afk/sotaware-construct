package com.example.myapplication.stage8

import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.Note
import com.example.myapplication.PhotoImageNote
import com.example.myapplication.PhotoPin
import com.example.myapplication.Shape
import com.example.myapplication.ShapeType
import com.example.myapplication.DrawnPath
import com.example.myapplication.Measurement
import com.example.myapplication.PageScale
import com.example.myapplication.HistoryAction
import com.example.myapplication.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationReducerTest {
    private fun shape(id: String = "shape") = Shape(
        x = .5f, y = .5f, width = 100f, height = 80f, rotation = 0f,
        type = ShapeType.RECTANGLE, colorArgb = 1, strokeWidth = 2f, id = id,
        widthRatio = .2f, heightRatio = .1f
    )

    @Test fun clearPageIsAtomicDeepUndoableAndRedoableAcrossAllDomains() {
        val vm = BlueprintViewModel()
        vm.pagePaths[2] = androidx.compose.runtime.mutableStateListOf(DrawnPath(listOf(com.example.myapplication.Point(1f, 2f)), 1, 2f, false))
        vm.pageMeasurements[2] = androidx.compose.runtime.mutableStateListOf(Measurement(com.example.myapplication.Point(1f, 1f), com.example.myapplication.Point(2f, 2f), "m"))
        vm.pageNotes[2] = androidx.compose.runtime.mutableStateListOf(Note(1f, 2f, "n"))
        val pin = PhotoPin(.1f, .2f).also {
            it.imageFileNames += "photo.jpg"
            it.imageNotes["photo.jpg"] = mutableListOf(PhotoImageNote(.2f, .3f, "caption"))
            it.imageShapes["photo.jpg"] = mutableListOf(shape("nested"))
        }
        vm.pagePhotoPins[2] = androidx.compose.runtime.mutableStateListOf(pin)
        vm.pageScales[2] = PageScale(4f)
        vm.pageShapes[2] = androidx.compose.runtime.mutableStateListOf(shape("page"))
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = AnnotationReducer(vm, effectSink = { effects += it })
        assertTrue(reducer.clearPage(2))
        assertTrue(vm.pagePaths[2]!!.isEmpty() && vm.pagePhotoPins[2]!!.isEmpty())
        assertFalse(vm.pageScales.containsKey(2))
        assertEquals(AnnotationReducer.Kind.CLEAR, effects.single().kind)
        assertTrue(reducer.undo(2))
        assertEquals("caption", vm.pagePhotoPins[2]!![0].imageNotes["photo.jpg"]!![0].text)
        vm.pagePhotoPins[2]!![0].imageNotes["photo.jpg"]!![0].text = "live"
        assertTrue(reducer.redo(2))
        assertTrue(vm.pagePhotoPins[2]!!.isEmpty())
        assertTrue(reducer.undo(2))
        assertEquals("caption", vm.pagePhotoPins[2]!![0].imageNotes["photo.jpg"]!![0].text)
        assertEquals(4f, vm.pageScales[2]!!.pixelsPerFoot)
    }

    @Test fun clearRejectsStaleSessionAndLeavesOtherPageUntouched() {
        val vm = BlueprintViewModel()
        vm.pageNotes[0] = androidx.compose.runtime.mutableStateListOf(Note(0f, 0f, "page0"))
        vm.pageNotes[1] = androidx.compose.runtime.mutableStateListOf(Note(0f, 0f, "page1"))
        var current = "A"
        val reducer = AnnotationReducer(vm, sessionKey = "A", currentSessionKey = { current })
        current = "B"
        assertFalse(reducer.clearPage(0))
        assertEquals("page0", vm.pageNotes[0]!![0].text)
        assertEquals("page1", vm.pageNotes[1]!![0].text)
    }

    @Test fun legacyMutationAfterClearUsesLegacyUndoBoundary() {
        val vm = BlueprintViewModel()
        vm.pageNotes[0] = androidx.compose.runtime.mutableStateListOf(Note(0f, 0f, "before"))
        vm.pagePaths[0] = androidx.compose.runtime.mutableStateListOf()
        val reducer = AnnotationReducer(vm)
        assertTrue(reducer.clearPage(0))
        val path = DrawnPath(listOf(Point(1f, 1f)), 1, 1f, false)
        vm.pagePaths[0]!!.add(path); vm.addAction(0, HistoryAction.AddPath(path)); reducer.notifyLegacyMutation(0)
        assertFalse(reducer.undo(0))
        vm.undo(0)
        assertTrue(vm.pagePaths[0]!!.isEmpty())
    }

    @Test fun inactiveSessionRejectsMutationAndHistoryWithoutEffects() {
        val vm = BlueprintViewModel()
        vm.pageNotes[0] = androidx.compose.runtime.mutableStateListOf()
        var active = true
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = AnnotationReducer(vm, { effects += it }) { active }
        assertTrue(reducer.addPdfNote(0, Note(1f, 1f, "live")))
        active = false
        assertFalse(reducer.addPdfNote(0, Note(2f, 2f, "stale")))
        assertFalse(reducer.undo(0))
        assertEquals(1, vm.pageNotes[0]!!.size)
        assertEquals(1, effects.size)
    }

    @Test fun pdfNoteAndShapeTransitionsUndoRedoAndClearRedo() {
        val vm = BlueprintViewModel()
        vm.pageNotes[0] = androidx.compose.runtime.mutableStateListOf()
        vm.pageShapes[0] = androidx.compose.runtime.mutableStateListOf()
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = AnnotationReducer(vm, effectSink = { effects += it })
        val note = Note(10f, 20f, "old")
        assertTrue(reducer.addPdfNote(0, note))
        val edited = note.copyNote().also { it.text = "new" }
        assertTrue(reducer.updatePdfNoteAt(0, 0, edited, before = note))
        assertEquals("new", vm.pageNotes[0]!![0].text)
        assertTrue(reducer.undo(0))
        assertEquals("old", vm.pageNotes[0]!![0].text)
        assertTrue(reducer.redo(0))
        assertEquals("new", vm.pageNotes[0]!![0].text)
        assertTrue(reducer.deletePdfNote(0, vm.pageNotes[0]!![0]))
        assertFalse(reducer.canRedo(0))
        assertTrue(reducer.undo(0))
        assertEquals(1, vm.pageNotes[0]!!.size)

        val originalShape = shape()
        assertTrue(reducer.addPdfShape(0, originalShape))
        val moved = originalShape.copyShape().also { it.x = .7f }
        assertTrue(reducer.movePdfShape(0, originalShape, moved))
        assertEquals(.7f, vm.pageShapes[0]!![0].x)
        assertTrue(reducer.undo(0))
        assertEquals(.5f, vm.pageShapes[0]!![0].x)
        assertTrue(reducer.redo(0))
        assertEquals(.7f, vm.pageShapes[0]!![0].x)
        assertTrue(effects.any { it.kind == AnnotationReducer.Kind.UNDO })
        assertTrue(effects.any { it.kind == AnnotationReducer.Kind.REDO })
    }

    @Test fun allPdfDomainsUseReducerHistoryIncludingPhotoAttachmentAndScale() {
        val vm = BlueprintViewModel()
        vm.pagePaths[0] = androidx.compose.runtime.mutableStateListOf()
        vm.pageMeasurements[0] = androidx.compose.runtime.mutableStateListOf()
        vm.pagePhotoPins[0] = androidx.compose.runtime.mutableStateListOf()
        val reducer = AnnotationReducer(vm)
        val path = DrawnPath(listOf(Point(1f, 1f), Point(2f, 2f)), 1, 2f, false)
        val measurement = Measurement(Point(1f, 1f), Point(4f, 1f), "3")
        val pin = PhotoPin(.2f, .3f)

        assertTrue(reducer.addPdfPath(0, path))
        assertTrue(reducer.addMeasurement(0, measurement))
        assertTrue(reducer.addPhotoPin(0, pin))
        assertTrue(reducer.attachPhoto(0, pin, "photo.jpg"))
        assertEquals(listOf("photo.jpg"), vm.pagePhotoPins[0]!![0].imageFileNames)
        assertTrue(reducer.setScale(0, PageScale(12f)))

        val moved = measurement.copyMeasurement(Point(2f, 2f), Point(5f, 2f), "3")
        assertTrue(reducer.moveMeasurement(0, measurement, moved))
        assertEquals(2f, vm.pageMeasurements[0]!![0].p1.x)
        assertTrue(reducer.undo(0))
        assertEquals(1f, vm.pageMeasurements[0]!![0].p1.x)
        assertTrue(reducer.redo(0))
        assertTrue(reducer.deletePdfPath(0, vm.pagePaths[0]!![0]))
        assertTrue(reducer.deletePhotoPin(0, vm.pagePhotoPins[0]!![0]))
        assertTrue(reducer.undo(0))
        assertEquals(1, vm.pagePhotoPins[0]!!.size)
    }

    @Test fun staleShapeUpdateCannotOverwriteNewerGeometry() {
        val vm = BlueprintViewModel()
        vm.pageShapes[0] = androidx.compose.runtime.mutableStateListOf()
        val reducer = AnnotationReducer(vm)
        val original = shape("stale-shape")
        assertTrue(reducer.addPdfShape(0, original))

        val committed = original.copyShape().also { it.x = .7f }
        assertTrue(reducer.movePdfShape(0, original, committed))
        val staleReplacement = original.copyShape().also { it.x = .2f }

        assertFalse(reducer.movePdfShape(0, original, staleReplacement))
        assertEquals(.7f, vm.pageShapes[0]!![0].x)
        assertTrue(reducer.canUndo(0))
    }

    @Test fun measurementMovesRemainAddressableAcrossMultipleReducerUpdates() {
        val vm = BlueprintViewModel()
        vm.pageMeasurements[0] = androidx.compose.runtime.mutableStateListOf()
        val reducer = AnnotationReducer(vm)
        val original = Measurement(Point(1f, 1f), Point(4f, 1f), "3")
        assertTrue(reducer.addMeasurement(0, original))
        val first = original.copyMeasurement(Point(2f, 1f), Point(5f, 1f), "3")
        val second = first.copyMeasurement(Point(3f, 1f), Point(6f, 1f), "3")
        assertTrue(reducer.moveMeasurement(0, original, first))
        assertTrue(reducer.moveMeasurement(0, first, second))
        assertEquals(3f, vm.pageMeasurements[0]!![0].p1.x)
        assertTrue(reducer.undo(0))
        assertEquals(2f, vm.pageMeasurements[0]!![0].p1.x)
        assertTrue(reducer.undo(0))
        assertEquals(1f, vm.pageMeasurements[0]!![0].p1.x)
    }

    @Test fun equalValuedMeasurementsUseStableOrdinalDuringGestureCommit() {
        val vm = BlueprintViewModel()
        val first = Measurement(Point(1f, 1f), Point(4f, 1f), "3")
        val second = Measurement(Point(1f, 1f), Point(4f, 1f), "3")
        vm.pageMeasurements[0] = androidx.compose.runtime.mutableStateListOf(first, second)
        val reducer = AnnotationReducer(vm)
        val replacement = second.copyMeasurement(Point(2f, 1f), Point(5f, 1f), "3")

        assertTrue(reducer.updateMeasurementAt(0, 1, replacement, AnnotationReducer.Kind.MOVE, before = second))
        assertEquals(1f, vm.pageMeasurements[0]!![0].p1.x)
        assertEquals(2f, vm.pageMeasurements[0]!![1].p1.x)
        assertTrue(reducer.undo(0))
        assertEquals(1f, vm.pageMeasurements[0]!![1].p1.x)
    }

    @Test fun equalValuedPdfNotesUseOrdinalRatherThanEquality() {
        val vm = BlueprintViewModel()
        vm.pageNotes[0] = androidx.compose.runtime.mutableStateListOf()
        val first = Note(10f, 20f, "same")
        val second = Note(10f, 20f, "same")
        vm.pageNotes[0]!!.add(first)
        vm.pageNotes[0]!!.add(second)
        val reducer = AnnotationReducer(vm)
        val replacement = second.copyNote().also { it.text = "second" }
        assertTrue(reducer.updatePdfNoteAt(0, 1, replacement))
        assertEquals("same", vm.pageNotes[0]!![0].text)
        assertEquals("second", vm.pageNotes[0]!![1].text)
        // The updated ordinal remains independently addressable even though
        // the two original notes had identical values.
        assertTrue(reducer.deletePdfNoteAt(0, 1, replacement))
        assertEquals("same", vm.pageNotes[0]!![0].text)
        assertTrue(reducer.undo(0))
        assertEquals("second", vm.pageNotes[0]!![1].text)
        assertTrue(reducer.redo(0))
        assertEquals(1, vm.pageNotes[0]!!.size)
        assertEquals("same", vm.pageNotes[0]!![0].text)
    }

    @Test fun pinchDraftNoteCommitsOneReducerTransitionWithUndoRedoEffects() {
        val vm = BlueprintViewModel()
        val original = Note(10f, 20f, "pinch", fontSize = 16f, rotation = 0f)
        vm.pageNotes[0] = androidx.compose.runtime.mutableStateListOf(original)
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = AnnotationReducer(vm, effectSink = { effects += it })
        val draft = original.copyNote().also {
            it.fontSize = 24f
            it.rotation = 18f
        }

        assertTrue(reducer.updatePdfNoteAt(0, 0, draft, before = original))
        assertEquals(1, effects.size)
        assertEquals(24f, vm.pageNotes[0]!![0].fontSize)
        assertTrue(reducer.undo(0))
        assertEquals(16f, vm.pageNotes[0]!![0].fontSize)
        assertTrue(reducer.redo(0))
        assertEquals(18f, vm.pageNotes[0]!![0].rotation)
        assertEquals(3, effects.size)
    }

    @Test fun sessionPredicateRejectsUndoRedoAfterSwitch() {
        val vm = BlueprintViewModel()
        vm.pageShapes[0] = androidx.compose.runtime.mutableStateListOf()
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
        assertTrue(reducer.addPdfShape(0, original))
        currentSession = "B"
        val beforeEffects = effects
        assertFalse(reducer.undo(0))
        assertFalse(reducer.redo(0))
        assertEquals(1, vm.pageShapes[0]!!.size)
        assertEquals(beforeEffects, effects)
        assertTrue(reducer.canUndo(0))
        active = false
        assertFalse(reducer.deletePdfShape(0, original))
    }

    @Test fun deleteUndoRedoRestoresPdfAndImageOrdering() {
        val vm = BlueprintViewModel()
        vm.pageShapes[0] = androidx.compose.runtime.mutableStateListOf()
        vm.pagePhotoPins[0] = androidx.compose.runtime.mutableStateListOf()
        val pin = PhotoPin(0f, 0f)
        vm.pagePhotoPins[0]!!.add(pin)
        val reducer = AnnotationReducer(vm)
        val a = shape("a"); val b = shape("b")
        vm.pageShapes[0]!!.add(a); vm.pageShapes[0]!!.add(b)
        assertTrue(reducer.deletePdfShape(0, a)); assertTrue(reducer.undo(0))
        assertEquals(listOf("a", "b"), vm.pageShapes[0]!!.map { it.id })
        assertTrue(reducer.redo(0)); assertEquals(listOf("b"), vm.pageShapes[0]!!.map { it.id })
        val n1 = PhotoImageNote(.1f, .1f, "1"); val n2 = PhotoImageNote(.2f, .2f, "2")
        pin.imageNotes["f"] = mutableListOf(n1, n2)
        assertTrue(reducer.deleteImageNote(0, pin.id, "f", n1)); assertTrue(reducer.undo(0)); assertTrue(reducer.redo(0)); assertTrue(reducer.undo(0))
        assertEquals(listOf(n1.id, n2.id), pin.imageNotes["f"]!!.map { it.id })
        val s1 = shape("s1"); val s2 = shape("s2")
        pin.imageShapes["f"] = mutableListOf(s1, s2)
        assertTrue(reducer.deleteImageShape(0, pin.id, "f", s1)); assertTrue(reducer.undo(0)); assertTrue(reducer.redo(0)); assertTrue(reducer.undo(0))
        assertEquals(listOf("s1", "s2"), pin.imageShapes["f"]!!.map { it.id })
    }

    @Test fun imageNestedStateIsDeepCopiedAndMissingTargetsAreNoOp() {
        val vm = BlueprintViewModel()
        vm.pagePhotoPins[0] = androidx.compose.runtime.mutableStateListOf()
        val pin = PhotoPin(.2f, .3f)
        val imageNote = PhotoImageNote(.1f, .2f, "caption")
        val imageShape = shape("image-shape")
        pin.imageNotes["photo.jpg"] = mutableListOf(imageNote)
        pin.imageShapes["photo.jpg"] = mutableListOf(imageShape)
        vm.pagePhotoPins[0]!!.add(pin)
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = AnnotationReducer(vm, effectSink = { effects += it })
        val addedShape = shape("added-shape")
        assertTrue(reducer.addImageShape(0, pin.id, "new.jpg", addedShape))
        assertTrue(reducer.undo(0))
        assertTrue(reducer.redo(0))
        val edited = imageNote.copyImageNote().also { it.text = "changed" }
        assertTrue(reducer.updateImageNote(0, pin.id, "photo.jpg", imageNote, edited))
        assertTrue(reducer.undo(0))
        // Mutating the detached object used to start the gesture must not
        // mutate the reducer's deep-copied history entry.
        imageNote.text = "detached mutation"
        assertTrue(reducer.redo(0))
        assertEquals("changed", pin.imageNotes["photo.jpg"]!![0].text)
        assertTrue(reducer.deleteImageShape(0, pin.id, "photo.jpg", imageShape))
        assertTrue(reducer.undo(0))
        assertEquals(1, pin.imageShapes["photo.jpg"]!!.size)
        assertFalse(reducer.deleteImageNote(0, "missing", "photo.jpg", imageNote))
        assertEquals(8, effects.size)

        val copy = pin.copyPin()
        copy.imageNotes["photo.jpg"]!![0].text = "copy only"
        copy.imageShapes["photo.jpg"]!![0].x = .9f
        assertEquals("changed", pin.imageNotes["photo.jpg"]!![0].text)
        assertEquals(.5f, pin.imageShapes["photo.jpg"]!![0].x)
    }

    @Test fun staleImageNoteUpdateAndDeleteCannotReplaceNewerValue() {
        val vm = BlueprintViewModel()
        vm.pagePhotoPins[0] = androidx.compose.runtime.mutableStateListOf()
        val pin = PhotoPin(.2f, .3f)
        val original = PhotoImageNote(.1f, .2f, "before", id = "note-1")
        pin.imageNotes["photo.jpg"] = mutableListOf(original)
        vm.pagePhotoPins[0]!!.add(pin)
        val reducer = AnnotationReducer(vm)
        val committed = original.copyImageNote().also { it.x = .8f; it.text = "newer" }
        assertTrue(reducer.updateImageNote(0, pin.id, "photo.jpg", original, committed))

        val staleReplacement = original.copyImageNote().also { it.text = "stale" }
        assertFalse(reducer.updateImageNote(0, pin.id, "photo.jpg", original, staleReplacement))
        assertFalse(reducer.deleteImageNote(0, pin.id, "photo.jpg", original))
        assertEquals(committed, pin.imageNotes["photo.jpg"]!!.single())
    }

    @Test fun staleImageShapeUpdateAndDeleteCannotReplaceNewerGeometry() {
        val vm = BlueprintViewModel()
        vm.pagePhotoPins[0] = androidx.compose.runtime.mutableStateListOf()
        val pin = PhotoPin(.2f, .3f)
        val original = shape("image-shape-stale")
        pin.imageShapes["photo.jpg"] = mutableListOf(original)
        vm.pagePhotoPins[0]!!.add(pin)
        val reducer = AnnotationReducer(vm)
        val committed = original.copyShape().also { it.x = .8f; it.rotation = 24f }
        assertTrue(reducer.updateImageShape(0, pin.id, "photo.jpg", original, committed))

        val staleReplacement = original.copyShape().also { it.x = .1f }
        assertFalse(reducer.updateImageShape(0, pin.id, "photo.jpg", original, staleReplacement))
        assertFalse(reducer.deleteImageShape(0, pin.id, "photo.jpg", original))
        assertEquals(committed, pin.imageShapes["photo.jpg"]!!.single())
    }

    @Test fun stalePdfShapeDeleteCannotRemoveNewerGeometry() {
        val vm = BlueprintViewModel()
        vm.pageShapes[0] = androidx.compose.runtime.mutableStateListOf()
        val reducer = AnnotationReducer(vm)
        val original = shape("pdf-shape-stale")
        assertTrue(reducer.addPdfShape(0, original))
        val committed = original.copyShape().also { it.y = .8f }
        assertTrue(reducer.updatePdfShape(0, original, committed))

        assertFalse(reducer.deletePdfShape(0, original))
        assertEquals(committed, vm.pageShapes[0]!!.single())
    }

    @Test fun stalePhotoPinUpdateAndDeleteCannotReplaceNewerPosition() {
        val vm = BlueprintViewModel()
        vm.pagePhotoPins[0] = androidx.compose.runtime.mutableStateListOf()
        val reducer = AnnotationReducer(vm)
        val original = PhotoPin(.2f, .3f, id = "pin-stale")
        assertTrue(reducer.addPhotoPin(0, original))
        val committed = original.copyPin().also { it.x = .8f }
        assertTrue(reducer.updatePhotoPin(0, original, committed))

        val staleReplacement = original.copyPin().also { it.y = .9f }
        assertFalse(reducer.updatePhotoPin(0, original, staleReplacement))
        assertFalse(reducer.deletePhotoPin(0, original))
        assertEquals(committed, vm.pagePhotoPins[0]!!.single())
    }
}
