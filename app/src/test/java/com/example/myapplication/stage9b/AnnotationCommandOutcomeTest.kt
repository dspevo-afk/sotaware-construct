package com.example.myapplication.stage9b

import androidx.compose.runtime.mutableStateListOf
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.DrawnPath
import com.example.myapplication.Note
import com.example.myapplication.PageScale
import com.example.myapplication.Point
import com.example.myapplication.stage8.AnnotationReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focused result-contract checks for the reducer's three-way command outcome. */
class AnnotationCommandOutcomeTest {
    private fun reducer(
        vm: BlueprintViewModel,
        effects: MutableList<AnnotationReducer.EffectIntent> = mutableListOf(),
        session: Any = Any(),
        current: () -> Any? = { session },
        active: () -> Boolean = { true }
    ) = AnnotationReducer(vm, { effects += it }, session, current, active)

    private fun note(id: String = "note-1", text: String = "caption") =
        Note(.25f, .5f, text, id = id)

    @Test
    fun acceptedUnchangedAndRejectedHaveDistinctSideEffects() {
        val vm = BlueprintViewModel()
        vm.pageNotes[0] = mutableStateListOf()
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = reducer(vm, effects)
        val original = note()

        val accepted = reducer.addPdfNote(0, original)
        assertEquals(AnnotationReducer.Result.Accepted, accepted)
        assertTrue(accepted.changed)
        assertEquals(1, effects.size)
        assertTrue(reducer.canUndo(0))

        val beforeUnchanged = vm.pageNotes[0]!!.toList()
        val unchanged = reducer.updatePdfNoteAt(0, 0, original.copyNote(), before = original)
        assertEquals(AnnotationReducer.Result.Unchanged, unchanged)
        assertFalse(unchanged.changed)
        assertEquals(beforeUnchanged, vm.pageNotes[0]!!.toList())
        assertEquals(1, effects.size)
        assertTrue(reducer.canUndo(0))

        val rejected = reducer.updatePdfNoteAt(0, 0, original.copy(text = ""), before = original)
        assertEquals(AnnotationReducer.Result.Rejected, rejected)
        assertFalse(rejected.changed)
        assertEquals(beforeUnchanged, vm.pageNotes[0]!!.toList())
        assertEquals(1, effects.size)

        assertEquals(AnnotationReducer.Result.Accepted, reducer.undo(0))
        assertEquals(2, effects.size)
        assertFalse(reducer.canUndo(0))
        assertEquals(AnnotationReducer.Result.Accepted, reducer.redo(0))
        assertEquals(3, effects.size)
        assertTrue(reducer.canUndo(0))
    }

    @Test
    fun unsupportedPagesAndInvalidScaleAreRejectedWithoutHistoryOrEffects() {
        val vm = BlueprintViewModel()
        vm.pageNotes[0] = mutableStateListOf()
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = reducer(vm, effects)

        assertEquals(AnnotationReducer.Result.Rejected, reducer.addPdfNote(-1, note("negative-page")))
        assertEquals(AnnotationReducer.Result.Rejected, reducer.addPdfNote(1, note("missing-page")))
        assertEquals(AnnotationReducer.Result.Rejected, reducer.setScale(1, PageScale(72f)))
        assertEquals(AnnotationReducer.Result.Rejected, reducer.setScale(0, PageScale(-1f)))
        assertEquals(AnnotationReducer.Result.Rejected, reducer.clearPage(1))

        assertTrue(effects.isEmpty())
        assertFalse(reducer.canUndo(0))
        assertFalse(reducer.canUndo(1))
    }

    @Test
    fun staleExpectedBeforeAndSessionOrEpochObsolescenceAreRejected() {
        val vm = BlueprintViewModel()
        vm.pageNotes[0] = mutableStateListOf()
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val sessionA = Any()
        var currentSession: Any? = sessionA
        var active = true
        val reducer = reducer(vm, effects, sessionA, { currentSession }, { active })
        val original = note("stale-note", "before")
        val newer = original.copy(text = "after")

        assertEquals(AnnotationReducer.Result.Accepted, reducer.addPdfNote(0, original))
        assertEquals(AnnotationReducer.Result.Accepted, reducer.updatePdfNoteAt(0, 0, newer, before = original))
        val effectsAfterAccepted = effects.size
        assertEquals(
            AnnotationReducer.Result.Rejected,
            reducer.updatePdfNoteAt(0, 0, original, before = original)
        )
        assertEquals(newer, vm.pageNotes[0]!!.single())
        assertEquals(effectsAfterAccepted, effects.size)

        currentSession = Any()
        assertEquals(AnnotationReducer.Result.Rejected, reducer.updatePdfNoteAt(0, 0, newer, before = newer))
        assertEquals(effectsAfterAccepted, effects.size)

        currentSession = sessionA
        active = true
        vm.annotationHistory.invalidateForReplacement()
        assertEquals(AnnotationReducer.Result.Rejected, reducer.undo(0))
        assertEquals(newer, vm.pageNotes[0]!!.single())
        assertEquals(effectsAfterAccepted, effects.size)
    }

    @Test
    fun acceptedPathDetachesSourceAndFreezesNestedPointsAcrossHistory() {
        val vm = BlueprintViewModel()
        vm.pagePaths[0] = mutableStateListOf()
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = reducer(vm, effects)
        val sourcePoints = mutableListOf(Point(.1f, .2f), Point(.2f, .3f))
        val source = DrawnPath(sourcePoints, colorArgb = 1, isHighlighter = false, id = "path-1")

        assertEquals(AnnotationReducer.Result.Accepted, reducer.addPdfPath(0, source))
        sourcePoints += Point(.9f, .9f)
        val committed = vm.pagePaths[0]!!.single()
        assertEquals(2, committed.points.size)
        assertTrue(committed.points !== sourcePoints)

        var immutable = false
        try {
            @Suppress("UNCHECKED_CAST")
            val mutablePoints = committed.points as MutableList<Point>
            mutablePoints.add(Point(.4f, .4f))
        } catch (_: UnsupportedOperationException) {
            immutable = true
        }
        assertTrue("committed path points must be immutable", immutable)
        assertEquals(AnnotationReducer.Result.Accepted, reducer.undo(0))
        assertEquals(AnnotationReducer.Result.Accepted, reducer.redo(0))
        assertEquals(2, vm.pagePaths[0]!!.single().points.size)
        assertEquals(3, effects.size)
        assertEquals(AnnotationReducer.Kind.ADD, effects[0].kind)
    }
}
