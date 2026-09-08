package com.example.myapplication.stage8

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.myapplication.ToolMode

/** Pure admission/precedence seam for Stage 8 interactions. */
class Stage8InteractionController {
    data class ClearRequest(val session: Any, val page: Int)
    private var pending: ClearRequest? by mutableStateOf(null)
    val pendingClearRequest: ClearRequest? get() = pending
    val hasPendingClear: Boolean get() = pending != null
    val pendingClearSession: Any? get() = pending?.session
    var optionsVisible: Boolean = false
        private set

    fun requestClear(session: Any?, page: Int) {
        if (session != null) pending = ClearRequest(session, page)
    }

    fun cancelClear() { pending = null }

    fun confirmClear(currentSession: Any?, currentPage: Int, action: (Int) -> Unit): Boolean {
        val candidate = pending ?: return false
        pending = null // consumed even when stale; it can never replay later
        if (candidate.session != currentSession || candidate.page != currentPage) return false
        action(candidate.page)
        return true
    }

    fun pendingPage(): Int? = pending?.page

    fun clearPendingOnContextChange() { pending = null }

    fun selectMode(mode: ToolMode): Boolean {
        optionsVisible = mode != ToolMode.PAN
        return optionsVisible
    }

    fun dismissOptions() { optionsVisible = false }

    fun onBack(fullscreen: Boolean, closeFullscreen: () -> Unit): Boolean {
        if (!fullscreen) return false
        closeFullscreen()
        return true
    }
}
