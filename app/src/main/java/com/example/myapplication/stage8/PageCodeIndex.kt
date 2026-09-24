package com.example.myapplication.stage8

import com.example.myapplication.PageOcr
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A region in the same displayed, crop/rotation-normalized space as OCR and annotations. */
data class PageCodeRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(left < right && top < bottom)
    }
}

/** Derived labels only: this never edits the document's annotation snapshot. */
object PageCodeIndex {
    const val MAX_PAGES = 10_000
    const val MAX_CODE_LENGTH = 80

    fun codeInRegion(page: PageOcr, region: PageCodeRegion): String? {
        val text = page.boxes.asSequence().filter { box ->
            val r = box.rectN
            val x = (r.left + r.right) / 2f
            val y = (r.top + r.bottom) / 2f
            r.left.isFinite() && r.top.isFinite() && r.right.isFinite() && r.bottom.isFinite() &&
                r.left < r.right && r.top < r.bottom &&
                x in region.left..region.right && y in region.top..region.bottom
        }.map { it.text.trim() }.filter(String::isNotEmpty).joinToString(" ")
            .replace(Regex("\\s+"), " ").trim()
        // Do not invent codes, truncate ambiguous selections or label blank pages.
        return text.takeIf { it.isNotEmpty() && it.length <= MAX_CODE_LENGTH }
    }

    suspend fun scan(
        pageCount: Int,
        region: PageCodeRegion,
        isCurrent: () -> Boolean,
        loadPage: suspend (Int) -> PageOcr?,
        onProgress: (Int) -> Unit = {}
    ): Map<Int, String> {
        require(pageCount in 1..MAX_PAGES)
        val codes = linkedMapOf<Int, String>()
        for (page in 0 until pageCount) {
            currentCoroutineContext().ensureActive()
            check(isCurrent()) { "Page-code request is stale" }
            val ocr = checkNotNull(loadPage(page)) { "Page text unavailable" }
            currentCoroutineContext().ensureActive()
            check(isCurrent() && ocr.pageIndex == page) { "Page-code result is stale" }
            withContext(Dispatchers.Default) { codeInRegion(ocr, region) }?.let { codes[page] = it }
            check(isCurrent()) { "Page-code request is stale" }
            onProgress(page + 1)
        }
        check(isCurrent()) { "Page-code request is stale" }
        return codes
    }
}
