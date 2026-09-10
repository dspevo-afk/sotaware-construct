package com.example.myapplication

import android.content.Context
import android.graphics.RectF
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentWorkOwner
import com.example.myapplication.stage3.DocumentWorkToken
import com.example.myapplication.stage7.Stage7WorkerResourceBoundary

/**
 * PDF search engine that uses OCR-first strategy and returns normalized highlight rects.
 */
class PdfSearchEngine(
    private val context: Context,
    private val workerBoundary: Stage7WorkerResourceBoundary = Stage7WorkerResourceBoundary(),
    private val ocrIndex: OcrIndex = OcrIndex(context, workerBoundary)
) {
    /**
     * Finds a normalized phrase in OCR order and returns one rect per box that
     * contributes to a match.  OCR boxes are deliberately treated as a
     * bounded token stream: only adjacent boxes can participate, so a word
     * between two phrase words cannot be skipped.
     */
    companion object {
        private const val MAX_PHRASE_TOKENS = 128

        fun matchPhrase(boxes: List<OcrBox>, query: String): List<RectF> {
            val tokens = normalizeSearchText(query).split(' ').filter { it.isNotEmpty() }
            if (tokens.isEmpty() || tokens.size > MAX_PHRASE_TOKENS) return emptyList()
            val boxTokens = boxes.map { normalizeSearchText(it.text).split(' ').filter(String::isNotEmpty) }
            val matchingBoxIndices = LinkedHashSet<Int>()
            for (boxIndex in boxTokens.indices) {
                for (offset in boxTokens[boxIndex].indices) {
                    var matched = 0
                    var nextBox = boxIndex
                    var nextOffset = offset
                    val contributing = ArrayList<Int>()
                    while (matched < tokens.size && nextBox < boxTokens.size) {
                        val words = boxTokens[nextBox]
                        if (nextOffset >= words.size || words[nextOffset] != tokens[matched]) break
                        if (contributing.lastOrNull() != nextBox) contributing += nextBox
                        matched++
                        nextOffset++
                        if (nextOffset == words.size) {
                            nextBox++
                            nextOffset = 0
                        }
                    }
                    if (matched == tokens.size) {
                        matchingBoxIndices += contributing
                    }
                }
            }
            return matchingBoxIndices.mapNotNull { index ->
                PdfCoordinateMapper.copyNormalizedRectOrNull(boxes[index].rectN)
            }
        }
    }

    /**
     * Session-aware search. Every page request, progress callback, and final
     * result is admitted against the captured document/page/query token.
     */
    suspend fun search(
        workToken: DocumentWorkToken,
        query: String,
        pageCount: Int,
        startPage: Int = 0,
        cacheNamespace: String = workToken.session.sourceCacheKey,
        isAccepted: (DocumentWorkToken) -> Boolean = { true },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        owner: DocumentWorkOwner? = null
    ): Map<Int, List<RectF>> = workerBoundary.withWorker {
        val normalizedQuery = normalizeSearchText(query)
        if (normalizedQuery.isBlank() || !isAccepted(workToken)) return@withWorker emptyMap()
        val out = HashMap<Int, List<RectF>>()
        var done = 0
        for (pageIndex in startPage until (startPage + pageCount)) {
            val pageWork = if (workToken.pageIndex == null) {
                workToken.copy(pageIndex = null)
            } else {
                workToken.copy(pageIndex = pageIndex)
            }
            if (!isAccepted(pageWork)) return@withWorker emptyMap()
            val page = ocrIndex.getPageOcr(
                token = workToken.session,
                pageIndex = pageIndex,
                cacheNamespace = cacheNamespace,
                isCurrent = { isAccepted(pageWork) },
                owner = owner
            ) ?: return@withWorker emptyMap()
            if (!isAccepted(pageWork)) return@withWorker emptyMap()
            val hits = matchPhrase(page.boxes, normalizedQuery)
            if (hits.isNotEmpty()) out[pageIndex] = hits
            done++
            workerBoundary.withMain {
                if (isAccepted(pageWork)) onProgress(done, pageCount)
            }
        }
        if (!isAccepted(workToken)) emptyMap() else out
    }

    // Helper to get cached OCR boxes for debug overlay
    fun getCachedPageOcr(
        token: DocumentSessionToken,
        pageIndex: Int,
        cacheNamespace: String = token.sourceCacheKey,
        isAccepted: (DocumentWorkToken) -> Boolean = { true }
    ): PageOcr? = ocrIndex.getCachedPageOcr(
        token = token,
        pageIndex = pageIndex,
        cacheNamespace = cacheNamespace,
        isCurrent = { isAccepted(DocumentWorkToken(token, pageIndex = pageIndex)) }
    )

    suspend fun loadPageOcr(
        token: DocumentSessionToken,
        pageIndex: Int,
        cacheNamespace: String = token.sourceCacheKey,
        isAccepted: (DocumentWorkToken) -> Boolean = { true },
        owner: DocumentWorkOwner? = null
    ): PageOcr? = ocrIndex.getPageOcr(
        token = token,
        pageIndex = pageIndex,
        cacheNamespace = cacheNamespace,
        isCurrent = { isAccepted(DocumentWorkToken(token, pageIndex = pageIndex)) },
        owner = owner
    )

    /**
     * Gets a page's OCR from the session cache, building it when the cache
     * misses. Keeping this continuation named makes cache-miss selection
     * explicit while retaining the session admission fence.
     */
    suspend fun getOrBuildPageOcr(
        token: DocumentSessionToken,
        pageIndex: Int,
        cacheNamespace: String = token.sourceCacheKey,
        isAccepted: (DocumentWorkToken) -> Boolean = { true },
        owner: DocumentWorkOwner? = null
    ): PageOcr? = loadPageOcr(
        token = token,
        pageIndex = pageIndex,
        cacheNamespace = cacheNamespace,
        isAccepted = isAccepted,
        owner = owner
    )

}

private fun normalizeSearchText(value: String): String =
    value.trim().replace(Regex("\\s+"), " ").lowercase()
