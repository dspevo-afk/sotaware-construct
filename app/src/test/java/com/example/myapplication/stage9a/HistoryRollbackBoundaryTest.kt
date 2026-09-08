package com.example.myapplication.stage9a

import androidx.compose.runtime.mutableStateListOf
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.Note
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.NoteSnapshotV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.applySnapshotReplace
import com.example.myapplication.stage8.AnnotationReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRollbackBoundaryTest {
    @Test fun laterNoOpRollbackCannotResurrectHistoryFromAnEarlierAcceptedReplacement() {
        val source = DocumentSourceIdentityV1("content://stage9a/rollback-boundary", "fixture.pdf")
        val vm = BlueprintViewModel()
        vm.pageNotes[0] = mutableStateListOf(Note(.2f, .3f, "old local"))
        val reducer = AnnotationReducer(vm)
        assertTrue(reducer.clearPage(0))
        val incoming = DocumentSnapshotV1(1, 0, source, mapOf(0 to PageSnapshotV1(
            notes = listOf(NoteSnapshotV1(.2f, .3f, "accepted", 16f, false, 0f))
        )))
        applySnapshotReplace(incoming, vm)
        assertFalse(AnnotationReducer(vm).canUndo(0))
        // A subsequent failed operation can compensate to its unchanged prior snapshot.
        // It must not borrow a history checkpoint from a different replacement.
        applySnapshotReplace(incoming, vm, preserveHistoryOnRollback = true)
        val after = AnnotationReducer(vm)
        assertFalse(after.undo(0))
        assertEquals("accepted", vm.pageNotes[0]!!.single().text)
    }
}
