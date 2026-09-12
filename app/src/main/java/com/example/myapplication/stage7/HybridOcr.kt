package com.example.myapplication.stage7

import com.example.myapplication.OcrBox
import com.example.myapplication.PdfCoordinateMapper
import com.example.myapplication.toAndroidRectF
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Bounded merge of embedded and recognized text; embedded text wins over the
 * same OCR word at that location.
 *
 * Embedded PDF text already has an authoritative content-stream order. It is
 * deliberately kept as one contiguous prefix of the result. Reordering those
 * boxes by geometry makes two unrelated columns adjacent and changes phrase
 * meaning (for example, it can turn "install conduit" into "install paint").
 * Recognized boxes have no trusted content-stream order, so only that source
 * is grouped into bounded visual columns and lines before it is appended.
 */
internal object HybridOcr {
    /** The merge budget applies when both sources must be retained together. */
    const val MAX_BOXES = 20_000
    const val MAX_TEXT_CHARS = 1_000_000
    private const val GRID = 32
    private const val MAX_CELL_ENTRIES = 1_000_000
    private const val MAX_COMPARISONS = 2_000_000

    suspend fun merge(embedded: List<OcrBox>, recognized: List<OcrBox>): List<OcrBox> {
        // A pure embedded page bypasses this merge entirely in OcrSession and
        // remains admitted under the existing 1 MiB/page cache policy. The
        // tighter budget is for the extra spatial index and duplicate checks
        // required when two sources are combined.
        if (embedded.isNotEmpty() && recognized.isNotEmpty() &&
            embedded.size.toLong() + recognized.size > MAX_BOXES
        ) {
            throw IOException("OCR box budget exceeded")
        }
        val context = currentCoroutineContext()
        val accepted = ArrayList<OcrBox>()
        val recognizedGroups = ArrayList<MutableList<OcrBox>>()
        var recognizedGroup = ArrayList<OcrBox>().also { recognizedGroups += it }
        var groupStartsNewBlock = false
        val words = ArrayList<String>()
        val grid = HashMap<Int, MutableList<Int>>()
        var cellEntries = 0
        var comparisons = 0
        var textChars = 0L
        var embeddedCount = 0
        val matchedEmbedded = HashSet<Int>()
        fun key(text: String) = text.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        fun cells(box: OcrBox, paddingY: Float = 0f): List<Int> {
            val r = box.rectN
            val left = (r.left * GRID).toInt().coerceIn(0, GRID - 1)
            val right = (r.right * GRID).toInt().coerceIn(0, GRID - 1)
            val top = ((r.top - paddingY) * GRID).toInt().coerceIn(0, GRID - 1)
            val bottom = ((r.bottom + paddingY) * GRID).toInt().coerceIn(0, GRID - 1)
            return (top..bottom).flatMap { y -> (left..right).map { x -> y * GRID + x } }
        }
        fun sameLocation(a: OcrBox, b: OcrBox, allowMetricOffset: Boolean): Boolean {
            val x = a.rectN; val y = b.rectN
            val intersection = (minOf(x.right, y.right) - maxOf(x.left, y.left)).coerceAtLeast(0f) *
                (minOf(x.bottom, y.bottom) - maxOf(x.top, y.top)).coerceAtLeast(0f)
            val smaller = minOf((x.right - x.left) * (x.bottom - x.top),
                (y.right - y.left) * (y.bottom - y.top))
            if (smaller > 0f && intersection / smaller >= 0.6f) return true
            if (!allowMetricOffset) return false
            // PDF font-metric bounds and OCR ink bounds may straddle a baseline
            // rather than overlap. Match identical words on the same narrow
            // line band; do not suppress repeated labels elsewhere on the page.
            val overlapX = (minOf(x.right, y.right) - maxOf(x.left, y.left)).coerceAtLeast(0f)
            val minWidth = minOf(x.right - x.left, y.right - y.left)
            val height = maxOf(x.bottom - x.top, y.bottom - y.top)
            val width = maxOf(x.right - x.left, y.right - y.left)
            return minWidth > 0f && overlapX / minWidth >= 0.8f &&
                kotlin.math.abs((x.top + x.bottom - y.top - y.bottom) / 2f) <= height * 1.1f &&
                kotlin.math.abs((x.left + x.right - y.left - y.right) / 2f) <= width * 0.25f
        }
        fun add(raw: OcrBox, deduplicate: Boolean) {
            context.ensureActive()
            textChars += raw.text.length
            if (textChars > MAX_TEXT_CHARS) throw IOException("OCR text budget exceeded")
            if (raw.text.isBlank()) return
            val r = raw.rectN
            val rectangle = PdfCoordinateMapper.normalizedRectOrNull(r.left, r.top, r.right, r.bottom)?.toAndroidRectF()
                ?: throw IOException("OCR geometry is invalid")
            val box = OcrBox(raw.text, rectangle, raw.startsNewBlock)
            val normalized = key(box.text)
            val cellKeys = cells(box)
            if (deduplicate) {
                val seen = HashSet<Int>()
                val nearbyCells = cells(box, (rectangle.bottom - rectangle.top) * 2f)
                for (cell in nearbyCells) for (index in grid[cell].orEmpty()) {
                    if (!seen.add(index)) continue
                    if (++comparisons > MAX_COMPARISONS) throw IOException("OCR merge work budget exceeded")
                    if (comparisons % 128 == 0) context.ensureActive()
                    if (words[index] == normalized && sameLocation(accepted[index], box, index < embeddedCount) &&
                        (index >= embeddedCount || matchedEmbedded.add(index))) return
                }
            }
            if (cellEntries > MAX_CELL_ENTRIES - cellKeys.size) throw IOException("OCR spatial budget exceeded")
            val index = accepted.size
            accepted += box
            words += normalized
            for (cell in cellKeys) grid.getOrPut(cell) { ArrayList() }.add(index)
            cellEntries += cellKeys.size
        }
        embedded.forEach { add(it, false) }
        embeddedCount = accepted.size
        recognized.forEach {
            if (it.startsNewBlock) {
                recognizedGroup = ArrayList<OcrBox>().also { group -> recognizedGroups += group }
                groupStartsNewBlock = true
            }
            val before = accepted.size
            add(it, true)
            if (accepted.size > before) {
                val box = accepted.last()
                recognizedGroup += if (recognizedGroup.isEmpty() && groupStartsNewBlock)
                    box.copy(startsNewBlock = true) else box
            }
        }
        context.ensureActive()
        // No recognized additions means that the embedded sequence is already
        // the complete authoritative result. In particular, do not even run
        // the visual ordering pass for a pure embedded page.
        if (recognizedGroups.all { it.isEmpty() }) return OcrReadingOrder.markBlocks(accepted)

        val embeddedSequence = accepted.subList(0, embeddedCount)
        val output = ArrayList<OcrBox>(accepted.size)
        output.addAll(embeddedSequence)
        recognizedGroups.filter { it.isNotEmpty() }.forEach { group ->
            val ordered = orderRecognizedAdditions(group, context)
            output += if (group.first().startsNewBlock)
                ordered.mapIndexed { index, box -> box.copy(startsNewBlock = index == 0) }
            else ordered
        }
        return OcrReadingOrder.markBlocks(output)
    }

    /**
     * Orders only OCR additions. Horizontal components act as visual columns;
     * each component is then split into lines and read top-to-bottom. This
     * keeps words from separate columns apart without touching embedded order.
     */
    private suspend fun orderRecognizedAdditions(
        boxes: List<OcrBox>,
        context: kotlin.coroutines.CoroutineContext
    ): List<OcrBox> {
        if (boxes.size < 2) return boxes
        // Build horizontal components with a sweep rather than comparing all
        // pairs. The merge budget admits up to 20,000 boxes; a quadratic
        // column pass would otherwise defeat cancellation and memory bounds.
        val byLeft = boxes.indices.sortedWith(
            compareBy<Int> { boxes[it].rectN.left }.thenBy { it }
        )
        val orderedComponents = ArrayList<MutableList<Int>>()
        var component: MutableList<Int>? = null
        var componentRight = Float.NaN
        byLeft.forEachIndexed { position, index ->
            if (position % 64 == 0) context.ensureActive()
            val box = boxes[index].rectN
            val gap = if (component == null) Float.POSITIVE_INFINITY else box.left - componentRight
            val threshold = maxOf(0.02f, (box.right - box.left) * 1.5f)
            if (component == null || gap > threshold) {
                component = ArrayList<Int>().also { orderedComponents += it }
                componentRight = box.right
            } else {
                componentRight = maxOf(componentRight, box.right)
            }
            component!!.add(index)
        }

        val result = ArrayList<OcrBox>(boxes.size)
        orderedComponents.forEach { component ->
            context.ensureActive()
            val lines = ArrayList<MutableList<Int>>()
            // Keep the recognizer's order within a line. It is the only
            // source-order signal available for words on that line; geometry
            // is used to order lines and separate columns around it.
            var currentLine: MutableList<Int>? = null
            var lineTop = Float.NaN
            var lineBottom = Float.NaN
            var lineHeight = 0f
            component.sortedWith(compareBy<Int> { boxes[it].rectN.top }.thenBy { it })
                .forEachIndexed { position, index ->
                if (position % 64 == 0) context.ensureActive()
                val box = boxes[index].rectN
                val minimumHeight = if (currentLine == null) 0f else minOf(
                    lineBottom - lineTop,
                    box.bottom - box.top
                )
                val overlap = if (currentLine == null) -Float.MAX_VALUE else
                    minOf(lineBottom, box.bottom) - maxOf(lineTop, box.top)
                val sameLine = currentLine != null && (
                    overlap >= minimumHeight * 0.35f ||
                        kotlin.math.abs(
                            (lineTop + lineBottom - box.top - box.bottom) / 2f
                        ) <= maxOf(lineHeight, box.bottom - box.top) * 0.75f
                    )
                if (!sameLine) {
                    currentLine = ArrayList<Int>().also { lines += it }
                    lineTop = box.top
                    lineBottom = box.bottom
                    lineHeight = box.bottom - box.top
                } else {
                    lineTop = minOf(lineTop, box.top)
                    lineBottom = maxOf(lineBottom, box.bottom)
                    lineHeight = maxOf(lineHeight, box.bottom - box.top)
                }
                currentLine!!.add(index)
            }
            lines.forEach { line ->
                line.sort()
                line.forEachIndexed { position, index ->
                    if (position % 64 == 0) context.ensureActive()
                    result += boxes[index]
                }
            }
        }
        return result
    }
}
