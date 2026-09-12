package com.example.myapplication.stage10

import com.example.myapplication.Note
import com.example.myapplication.PageItem
import com.example.myapplication.Point
import com.example.myapplication.Shape
import com.example.myapplication.ShapeType
import com.example.myapplication.stage8.MeasurementPointSelection
import com.example.myapplication.stage8.PdfSelectionKey
import com.example.myapplication.stage8.PhotoAnnotationStack
import com.example.myapplication.stage8.ViewerTransform
import com.example.myapplication.stage8.formatFeet
import org.junit.Assert.*
import org.junit.Test

class ViewerInteractionTest {
    @Test fun calculatedLengthsRoundTotalInchesBeforeSplitting() {
        val cases = mapOf(14f to "1' 2\"", 17f to "1' 5\"", 25f to "2' 1\"", 121f to "10' 1\"",
            0f to "0\"", 7f to "7\"", 11.49f to "11\"", 11.5f to "1' 0\"", 11.51f to "1' 0\"",
            0.49f to "0\"", 0.5f to "1\"", 12f to "1' 0\"", 23.5f to "2' 0\"")
        cases.forEach { (inches, expected) -> assertEquals("$inches inches", expected, formatFeet(inches / 12f)) }
    }

    @Test fun missingSelectionCannotBecomeAnotherNoteAfterDeleteOrReplacement() {
        val first = Note(.4f, .4f, "first")
        val selected = Note(.4f, .4f, "selected")
        val key = PdfSelectionKey.from(PageItem.NoteItem(selected, 1))
        fun resolve(notes: List<Note>) = key.resolve(emptyList(), notes, emptyList(), emptyList(), emptyList()) as? PageItem.NoteItem
        assertEquals(1, resolve(listOf(first, selected))!!.ordinal)
        assertEquals(0, resolve(listOf(selected))!!.ordinal)
        assertNull(resolve(listOf(first)))
        assertNull(resolve(emptyList()))
        assertEquals(selected.id, resolve(listOf(first, selected))!!.data.id)
        val replacement = selected.copy(text = "updated by replacement")
        assertEquals(replacement, resolve(listOf(replacement))!!.data)
    }

    @Test fun nonsquareFittedViewportsUseScreenPixelHitRadii() {
        for (fitted in listOf(.5f, 2f)) {
            val transform = ViewerTransform(400f, 800f, 400f * fitted, 800f * fitted, 1.6f, 17f, -23f)
            assertEquals(fitted, transform.fittedScale, .0001f)
            val point = Point(.3f, .7f)
            val screen = transform.toScreen(point)
            assertTrue(transform.hits(point, screen.x + 79f, screen.y, 80f))
            assertTrue(transform.hits(point, screen.x, screen.y + 79f, 80f))
            assertFalse(transform.hits(point, screen.x + 81f, screen.y, 80f))
            assertFalse(transform.hits(point, screen.x, screen.y + 81f, 80f))
            val restored = transform.toNormalized(screen.x, screen.y)
            assertEquals(point.x, restored.x, .0001f)
            assertEquals(point.y, restored.y, .0001f)
        }
    }

    @Test fun controlsClampToMeasuredLocalBoundsAndTheirOwnSize() {
        for ((w, h) in listOf(270f to 640f, 420f to 220f)) {
            val clamped = ViewerTransform.clampControl(Point(w + 200f, h + 300f), 190f, 65f, w, h)
            assertEquals(w - 190f, clamped.x, 0f)
            assertEquals(h - 65f, clamped.y, 0f)
            assertEquals(Point(0f, 0f), ViewerTransform.clampControl(Point(-10f, -15f), 80f, 40f, w, h))
        }
    }

    @Test fun photoHitOrderMatchesNotesThenShapesDrawOrder() {
        val lowNote = Note(.5f, .5f, "lower")
        val highNote = lowNote.copy(id = "upper-note", text = "upper")
        val lowShape = Shape(.5f, .5f, 0f, ShapeType.RECTANGLE, 0xff000000.toInt())
        val highShape = lowShape.copy(id = "upper-shape")
        val notes = listOf(lowNote, highNote)
        assertEquals(PhotoAnnotationStack.Hit.NoteHit(highNote), PhotoAnnotationStack.hit(notes, emptyList(), { true }, { true }))
        assertEquals(PhotoAnnotationStack.Hit.ShapeHit(highShape), PhotoAnnotationStack.hit(notes, listOf(lowShape, highShape), { true }, { true }))
        assertEquals(PhotoAnnotationStack.Hit.ShapeHit(lowShape), PhotoAnnotationStack.hit(notes, listOf(lowShape), { true }, { true }))
        assertNull(PhotoAnnotationStack.hit(notes, listOf(lowShape), { false }, { false }))
    }

    @Test fun measurementPhaseFollowsThePendingPointsAndReset() {
        val phase = MeasurementPointSelection()
        assertFalse(phase.hasFirstPoint)
        phase.firstPoint = Point(.1f, .2f)
        assertTrue(phase.hasFirstPoint)
        phase.secondPoint = Point(.3f, .4f)
        assertTrue(phase.hasFirstPoint)
        phase.clear()
        assertFalse(phase.hasFirstPoint)
        assertNull(phase.secondPoint)
    }
}
