package com.example.myapplication.stage8

/** Admission fence for a selection resolved after asynchronous OCR work. */
object OcrSelection {
    data class LoadedSelection(val startIndex: Int, val endIndex: Int = startIndex)

    /**
     * Admission fence used by both cache-hit and cache-miss long-press paths.
     * The returned range is intentionally only a single box: the gesture may
     * extend it later, but a loaded result must first prove that its captured
     * document/page still owns the touch.
     */
    fun admitLoadedSelection(
        capturedSession: Any?,
        currentSession: Any?,
        capturedPage: Int,
        currentPage: Int,
        boxIndex: Int,
        boxCount: Int
    ): LoadedSelection? = boxIndexOrNull(
        capturedSession,
        currentSession,
        capturedPage,
        currentPage,
        boxIndex,
        boxCount
    )?.let { LoadedSelection(it) }

    fun boxIndexOrNull(
        capturedSession: Any?,
        currentSession: Any?,
        capturedPage: Int,
        currentPage: Int,
        boxIndex: Int,
        boxCount: Int
    ): Int? {
        if (capturedSession != currentSession || capturedPage != currentPage) return null
        if (boxIndex !in 0 until boxCount) return null
        return boxIndex
    }
}
