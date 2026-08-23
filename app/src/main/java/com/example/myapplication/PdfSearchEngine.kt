package com.example.myapplication

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * PDF search engine that uses OCR-first strategy and returns normalized highlight rects.
 */
class PdfSearchEngine(private val context: Context) {
    private val ocrIndex = OcrIndex(context)

    suspend fun search(
        uri: Uri,
        query: String,
        pageCount: Int,
        startPage: Int = 0,
        cacheNamespace: String = uri.toString(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Map<Int, List<RectF>> =
        withContext(Dispatchers.Default) {
            val normalizedQuery = query.trim().replace(Regex("\\s+"), " ")
            if (normalizedQuery.isBlank()) return@withContext emptyMap()
            val out = HashMap<Int, List<RectF>>()

            var done = 0
            for (i in startPage until (startPage + pageCount)) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val page = ocrIndex.getPageOcr(uri, i, cacheNamespace)
                val hits = ArrayList<RectF>()
                for (box in page.boxes) {
                    if (box.text.contains(normalizedQuery, ignoreCase = true)) {
                        // Log what's matching for debugging
                        android.util.Log.d("SOTA_OCR", "MATCH: '${box.text}' contains '$normalizedQuery'")
                        hits.add(box.rectN)
                    }
                }
                if (hits.isNotEmpty()) out[i] = hits
                done++
                onProgress(done, pageCount)
            }
            return@withContext out
        }

    // Helper to get cached OCR boxes for debug overlay
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
