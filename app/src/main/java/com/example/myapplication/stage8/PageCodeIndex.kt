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

    /**
     * The shape a sheet number has on these drawings: a discipline letter(s), a number, and an
     * optional revision - "E2.0", "LS1.00", "A0.10", "ME1.0". A box dragged around the sheet
     * number on a real title block also lands on the words beside it ("sheet number:",
     * "checked by:", "PAW Typical Partition"), and joining all of those is a sentence, not a
     * code: it passed the length limit on a bare title block and was thrown away on every
     * sheet with one, which is why only one sheet of twenty-eight registered.
     */
    private val SHEET_CODE = Regex("^[A-Za-z]{1,4}[0-9]{1,3}(?:\\.[0-9]{1,3})?$")

    /** Sheet numbers the automatic pass accepts: "E2.0" and also "E-101", "A-2.03". */
    private val AUTO_CODE = Regex("^[A-Za-z]{1,4}-?[0-9]{1,4}(?:[.-][0-9]{1,3})?$")

    /** The corner read when a drawing opens: title blocks put the sheet number bottom right. */
    val AUTO_REGION = PageCodeRegion(0.6f, 0.75f, 1f, 1f)

    /**
     * The automatic guess for one sheet: of the sheet-number-shaped words in [region], the
     * one drawn largest (sheet numbers are the biggest text in a title block). Never guesses
     * from other text, so a sheet without a recognisable number is left unlabelled.
     */
    fun codeInCorner(page: PageOcr, region: PageCodeRegion = AUTO_REGION): String? =
        page.boxes.asSequence().filter { box ->
            val r = box.rectN
            r.left.isFinite() && r.top.isFinite() && r.right.isFinite() && r.bottom.isFinite() &&
                r.left < r.right && r.top < r.bottom &&
                (r.left + r.right) / 2f in region.left..region.right &&
                (r.top + r.bottom) / 2f in region.top..region.bottom
        }.map { it.text.trim() to (it.rectN.bottom - it.rectN.top) }
            .filter { (text, _) -> text.length <= MAX_CODE_LENGTH && AUTO_CODE.matches(text) }
            .maxByOrNull { it.second }?.first

    fun codeInRegion(page: PageOcr, region: PageCodeRegion): String? {
        val words = page.boxes.asSequence().filter { box ->
            val r = box.rectN
            val x = (r.left + r.right) / 2f
            val y = (r.top + r.bottom) / 2f
            r.left.isFinite() && r.top.isFinite() && r.right.isFinite() && r.bottom.isFinite() &&
                r.left < r.right && r.top < r.bottom &&
                x in region.left..region.right && y in region.top..region.bottom
        }.map { it.text.trim() }.filter(String::isNotEmpty).toList()
        val text = words.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        // A box that holds only the code (the common case, and the whole point of the tool)
        // answers with that text unchanged, so short codes are never reshaped.
        if (text.isEmpty()) return null
        if (SHEET_CODE.matches(text)) return text
        // The box caught the code together with the title-block words around it: keep the
        // sheet number(s) and leave the sentences out. Nothing matching a sheet number is
        // left as the joined text, so a box around ordinary text still says what is there.
        val codes = words.filter(SHEET_CODE::matches).distinct()
        val answer = when {
            codes.isNotEmpty() -> codes.joinToString(" ")
            text.length <= MAX_CODE_LENGTH -> text
            else -> ""
        }
        // Do not invent codes, truncate ambiguous selections or label blank pages.
        return answer.takeIf { it.isNotEmpty() && it.length <= MAX_CODE_LENGTH }
    }

    /** What one pass over the whole document found, and how many sheets it could not read. */
    data class ScanResult(
        val codes: Map<Int, String>,
        val unreadPages: Int
    )

    suspend fun scan(
        pageCount: Int,
        region: PageCodeRegion,
        isCurrent: () -> Boolean,
        loadPage: suspend (Int) -> PageOcr?,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ScanResult {
        val result = read(pageCount, isCurrent, loadPage, onProgress) { codeInRegion(it, region) }
        // Nothing read on any sheet: fail the pass so the user is told, instead of a
        // "complete" report with no codes registered.
        if (result.codes.isEmpty() && result.unreadPages == pageCount) error("Page text unavailable")
        return result
    }

    /**
     * The quiet pass when a drawing opens: reads [AUTO_REGION] of every sheet with
     * [codeInCorner]. Unlike [scan] it never fails for sheets without text; it reports them.
     */
    suspend fun autoScan(
        pageCount: Int,
        isCurrent: () -> Boolean,
        loadPage: suspend (Int) -> PageOcr?
    ): ScanResult = read(pageCount, isCurrent, loadPage, { _, _ -> }) { codeInCorner(it) }

    private suspend fun read(
        pageCount: Int,
        isCurrent: () -> Boolean,
        loadPage: suspend (Int) -> PageOcr?,
        onProgress: (Int, Int) -> Unit,
        codeOf: (PageOcr) -> String?
    ): ScanResult {
        require(pageCount in 1..MAX_PAGES)
        // One sheet, one step: the progress bar counts the sheets of the document, never twice
        // them, so "Reading page 12 of 28" always names a sheet the user can count.
        var stepsDone = 0
        val codes = linkedMapOf<Int, String>()
        var unreadPages = 0
        for (page in 0 until pageCount) {
            currentCoroutineContext().ensureActive()
            check(isCurrent()) { "Page-code request is stale" }
            val ocr = loadPage(page)
            currentCoroutineContext().ensureActive()
            stepsDone++
            onProgress(stepsDone, pageCount)
            // A sheet whose text cannot be read is skipped, not fatal. Sheet numbers are drawn as
            // vector graphics on many drawings, so OCR returns nothing for them: the old
            // checkNotNull here threw on the first such sheet, the whole pass was discarded, and
            // nothing was registered - not even the sheets that did read. Keep going, label what
            // reads, and count the rest so the caller can report them honestly.
            if (ocr == null || ocr.pageIndex != page) {
                unreadPages++
                check(isCurrent()) { "Page-code request is stale" }
                continue
            }
            withContext(Dispatchers.Default) { codeOf(ocr) }?.let { codes[page] = it }
            check(isCurrent()) { "Page-code request is stale" }
        }
        check(isCurrent()) { "Page-code request is stale" }
        return ScanResult(codes, unreadPages)
    }
}
