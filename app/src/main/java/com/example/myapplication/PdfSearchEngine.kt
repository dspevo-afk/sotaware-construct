package com.example.myapplication

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentWorkOwner
import com.example.myapplication.stage3.DocumentWorkToken
import com.example.myapplication.stage7.Stage7WorkerResourceBoundary
import kotlinx.coroutines.ensureActive

/**
 * PDF search engine that uses OCR-first strategy and returns normalized highlight rects.
 */
class PdfSearchEngine(
    private val context: Context,
    private val workerBoundary: Stage7WorkerResourceBoundary = Stage7WorkerResourceBoundary(),
    private val ocrIndex: OcrIndex = OcrIndex(context, workerBoundary)
) {
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
        val normalizedQuery = query.trim().replace(Regex("\\s+"), " ")
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
            val hits = ArrayList<RectF>()
            for (box in page.boxes) {
                if (box.text.contains(normalizedQuery, ignoreCase = true)) {
                    PdfCoordinateMapper.copyNormalizedRectOrNull(box.rectN)?.let { hits += it }
                }
            }
            if (hits.isNotEmpty()) out[pageIndex] = hits
            done++
            workerBoundary.withMain {
                if (isAccepted(pageWork)) onProgress(done, pageCount)
            }
        }
        if (!isAccepted(workToken)) emptyMap() else out
    }

    /** Compatibility URI search for pre-session callers. */
    suspend fun search(
        uri: Uri,
        query: String,
        pageCount: Int,
        startPage: Int = 0,
        cacheNamespace: String = uri.toString(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Map<Int, List<RectF>> =
        workerBoundary.withWorker {
            val normalizedQuery = query.trim().replace(Regex("\\s+"), " ")
            if (normalizedQuery.isBlank()) return@withWorker emptyMap()
            val out = HashMap<Int, List<RectF>>()

            var done = 0
            for (i in startPage until (startPage + pageCount)) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val page = ocrIndex.getPageOcr(uri, i, cacheNamespace)
                val hits = ArrayList<RectF>()
                for (box in page.boxes) {
                    if (box.text.contains(normalizedQuery, ignoreCase = true)) {
                        PdfCoordinateMapper.copyNormalizedRectOrNull(box.rectN)?.let { hits.add(it) }
                    }
                }
                if (hits.isNotEmpty()) out[i] = hits
                done++
                workerBoundary.withMain { onProgress(done, pageCount) }
            }
            return@withWorker out
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

    // Compatibility helper to get cached OCR boxes for debug overlay.
    fun getCachedPageOcr(
        uri: Uri,
        pageIndex: Int,
        cacheNamespace: String = uri.toString()
    ): PageOcr? = ocrIndex.getCachedPageOcr(uri, pageIndex, cacheNamespace)

    suspend fun loadPageOcr(
        uri: Uri,
        pageIndex: Int,
        cacheNamespace: String = uri.toString()
    ): PageOcr = ocrIndex.getPageOcr(uri, pageIndex, cacheNamespace)
}
