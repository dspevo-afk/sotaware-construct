package com.example.myapplication.stage7

import com.example.myapplication.OcrBox
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

/** Marks clear layout discontinuities without sorting the authoritative token stream. */
internal object OcrReadingOrder {
    suspend fun markBlocks(boxes: List<OcrBox>): List<OcrBox> {
        val context = currentCoroutineContext()
        var blockLeft = boxes.firstOrNull()?.rectN?.left ?: return boxes
        return boxes.mapIndexed { index, box ->
            if (index % 64 == 0) context.ensureActive()
            val previous = boxes.getOrNull(index - 1)
            val boundary = box.startsNewBlock || (previous != null && !continues(previous, box, blockLeft))
            blockLeft = if (boundary) box.rectN.left else minOf(blockLeft, box.rectN.left)
            if (box.startsNewBlock == boundary) box else box.copy(startsNewBlock = boundary)
        }.also { context.ensureActive() }
    }

    private fun continues(previous: OcrBox, next: OcrBox, blockLeft: Float): Boolean {
        val a = previous.rectN
        val b = next.rectN
        val height = maxOf(a.bottom - a.top, b.bottom - b.top)
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        val sameLine = overlap >= minOf(a.bottom - a.top, b.bottom - b.top) * .35f
        if (sameLine) {
            val characterWidth = maxOf(
                (a.right - a.left) / previous.text.length.coerceAtLeast(1),
                (b.right - b.left) / next.text.length.coerceAtLeast(1)
            )
            // Permit ordinary spaces (and either inline reading direction),
            // while a distant parallel column starts another phrase group.
            val gap = maxOf(b.left - a.right, a.left - b.right, 0f)
            return gap <= characterWidth * 12f
        }
        // A wrapped line continues down the same column. Moving back up into
        // another column, or down to an unrelated stamp, breaks adjacency.
        return b.top >= a.bottom && b.top - a.bottom <= height * 4f &&
            abs(b.left - blockLeft) <= maxOf(a.right - a.left, b.right - b.left) * 1.5f
    }
}
