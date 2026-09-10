package com.example.myapplication.stage9a

import androidx.compose.runtime.mutableStateListOf
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.Note
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage1.NoteSnapshotV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.applySnapshotReplace
import com.example.myapplication.stage8.AnnotationReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRollbackBoundaryTest {
    private fun reducer(vm: BlueprintViewModel) = AnnotationReducer(
        vm,
        sessionKey = "rollback-boundary-test",
        currentSessionKey = { "rollback-boundary-test" },
        sessionActivePredicate = { true }
    )

    @Test fun laterNoOpRollbackCannotResurrectHistoryFromAnEarlierAcceptedReplacement() {
        val source = DocumentSourceIdentityV1("content://stage9a/rollback-boundary", "fixture.pdf")
        val vm = BlueprintViewModel()
        vm.pageNotes[0] = mutableStateListOf(
            Note(
                x = .2f,
                y = .3f,
                text = "old local",
                fontSizeRatio = .02f,
                id = "old-local-note"
            )
        )
        val reducer = reducer(vm)
        assertTrue(reducer.clearPage(0).changed)
        val incoming = DocumentSnapshotV1(DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION, 0, source, mapOf(0 to PageSnapshotV1(
            notes = listOf(NoteSnapshotV1(.2f, .3f, "accepted", false, 0f, .02f, "accepted-note"))
        )))
        applySnapshotReplace(incoming, vm)
        assertFalse(reducer(vm).canUndo(0))
        // A subsequent failed operation can compensate to its unchanged prior snapshot.
        // It must not borrow a history checkpoint from a different replacement.
        applySnapshotReplace(incoming, vm, preserveHistoryOnRollback = true)
        val after = reducer(vm)
        assertFalse(after.undo(0).changed)
        assertEquals("accepted", vm.pageNotes[0]!!.single().text)
    }
}
