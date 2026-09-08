package com.example.myapplication.stage9a

import androidx.compose.runtime.mutableStateListOf
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.Note
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.snapshotFromState
import com.example.myapplication.stage5.validateSnapshot
import com.example.myapplication.stage8.AnnotationReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A finite history budget must not silently make destructive Clear non-undoable. */
class HistoryBudgetRegressionTest {
    @Test fun clearLargerThanHistoryBudgetEitherRemainsUndoableOrRejectsWithoutMutation() {
        val vm = BlueprintViewModel()
        val body = "x".repeat(16_384)
        vm.pageNotes[0] = mutableStateListOf<Note>().apply {
            repeat(300) { add(Note(.25f, .5f, body)) }
        }
        val source = DocumentSourceIdentityV1("content://stage9a/history-budget", "fixture.pdf")
        val before = snapshotFromState(vm, source)
        validateSnapshot(before)
        var effects = 0
        val reducer = AnnotationReducer(vm, effectSink = { effects++ })
        if (reducer.clearPage(0)) {
            assertTrue("a successful clear must retain its reverse action", reducer.canUndo(0))
            assertTrue(reducer.undo(0))
            assertEquals(before, snapshotFromState(vm, source))
        } else {
            assertEquals(before, snapshotFromState(vm, source))
            assertEquals(0, effects)
        }
    }
}
