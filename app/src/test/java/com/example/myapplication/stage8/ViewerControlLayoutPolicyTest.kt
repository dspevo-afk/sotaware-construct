package com.example.myapplication.stage8

import com.example.myapplication.ui.ViewerAction
import com.example.myapplication.ui.ViewerControlLayoutPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerControlLayoutPolicyTest {
    @Test fun narrowPortraitKeepsTouchTargetsAndAllActions() {
        val layout = ViewerControlLayoutPolicy.forWidth(320, landscape = false)
        assertEquals(48, layout.minimumTouchTargetDp)
        assertTrue(layout.overflowActionCount > 0)
        assertEquals(ViewerAction.entries.toSet(), layout.actions.toSet())
        assertEquals(listOf(ViewerAction.BACK, ViewerAction.SEARCH), layout.visibleActions)
    }

    @Test fun landscapeUsesCompactOverflowAndPreservesActions() {
        val layout = ViewerControlLayoutPolicy.forWidth(480, landscape = true)
        assertEquals(48, layout.minimumTouchTargetDp)
        assertTrue(layout.visibleActionCount < ViewerAction.entries.size)
        assertEquals(ViewerAction.entries.toSet(), layout.actions.toSet())
        assertEquals(listOf(ViewerAction.BACK, ViewerAction.PREVIOUS_PAGE, ViewerAction.NEXT_PAGE), layout.visibleActions)
    }

    @Test fun roomyWidthUsesExpandedDirectActions() {
        val layout = ViewerControlLayoutPolicy.forWidth(600, landscape = false)
        assertEquals(ViewerAction.entries.toList(), layout.visibleActions)
        assertTrue(layout.overflowActions.isEmpty())
    }
}
