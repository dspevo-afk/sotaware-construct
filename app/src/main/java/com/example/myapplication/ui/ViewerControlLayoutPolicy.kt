package com.example.myapplication.ui

/** Stable, platform-independent policy for compact viewer controls. */
data class ViewerControlLayout(
    val minimumTouchTargetDp: Int,
    val visibleActions: List<ViewerAction>,
    val overflowActions: List<ViewerAction>
) {
    val visibleActionCount: Int get() = visibleActions.size
    val overflowActionCount: Int get() = overflowActions.size
    val actions: List<ViewerAction> get() = visibleActions + overflowActions
}

enum class ViewerAction { BACK, PREVIOUS_PAGE, NEXT_PAGE, SEARCH, SCREENSHOT, MENU, UNDO, REDO }

object ViewerControlLayoutPolicy {
    private const val MinimumTouchTarget = 48

    fun forWidth(widthDp: Int, landscape: Boolean): ViewerControlLayout {
        val all = ViewerAction.entries
        val visible = if (widthDp >= 600) all else if (landscape) {
            listOf(ViewerAction.BACK, ViewerAction.PREVIOUS_PAGE, ViewerAction.NEXT_PAGE)
        } else listOf(ViewerAction.BACK, ViewerAction.SEARCH)
        return ViewerControlLayout(
            minimumTouchTargetDp = MinimumTouchTarget,
            visibleActions = visible,
            overflowActions = all.filterNot { it in visible }
        )
    }
}
