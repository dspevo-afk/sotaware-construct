package com.example.myapplication

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PDF search engine that uses OCR-first strategy and returns normalized highlight rects.
 */
class PdfSearchEngine(private val context: Context) {
    private val ocrIndex = OcrIndex(context)

    suspend fun search(uri: Uri, query: String, pageCount: Int, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Map<Int, List<RectF>> =
        withContext(Dispatchers.Default) {
            val normalizedQuery = query.trim().replace(Regex("\\s+"), " ")
            if (normalizedQuery.isBlank()) return@withContext emptyMap()
            val out = HashMap<Int, List<RectF>>()
            // HYBRID: try a fast embedded-text prefilter to limit pages we OCR
            val candidatePages = ArrayList<Int>()
            try {
                val input = context.contentResolver.openInputStream(uri)
                if (input != null) {
                    com.tom_roush.pdfbox.pdmodel.PDDocument.load(input).use { doc ->
                        val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                        for (i in 0 until pageCount) {
                            try {
                                stripper.startPage = i + 1
                                stripper.endPage = i + 1
                                val txt = stripper.getText(doc)
                                if (txt.contains(normalizedQuery, ignoreCase = true)) candidatePages.add(i)
                            } catch (_: Exception) { }
                        }
                    }
                    input.close()
                }
            } catch (_: Exception) { /* ignore embedded-text prefilter errors */ }

            val pagesToSearch = if (candidatePages.isNotEmpty()) candidatePages else (0 until pageCount)
            var done = 0
            val total = pagesToSearch.count()
            for (i in pagesToSearch) {
                val page = ocrIndex.getPageOcr(uri, i)
                val hits = ArrayList<RectF>()
                for (box in page.boxes) {
                    if (box.text.contains(normalizedQuery, ignoreCase = true)) {
                        hits.add(box.rectN)
                    }
                }
                if (hits.isNotEmpty()) out[i] = hits
                done++
                onProgress(done, total)
            }
            return@withContext out
        }

    // Helper to get cached OCR boxes for debug overlay
    fun getCachedPageOcr(uri: Uri, pageIndex: Int): PageOcr? = ocrIndex.getCachedPageOcr(uri, pageIndex)
}
