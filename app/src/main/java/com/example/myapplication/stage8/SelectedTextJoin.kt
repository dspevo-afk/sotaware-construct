package com.example.myapplication.stage8

import com.example.myapplication.OcrBox

/**
 * Turns selected OCR word boxes (already in reading order) into the text that
 * Copy puts on the clipboard: words on one line are joined with spaces and a
 * new line of the drawing starts a new line of text.
 */
object SelectedTextJoin {
    /** One word as the join sees it: its text and its vertical extent on the page. */
    data class Word(val text: String, val top: Float, val bottom: Float, val startsNewBlock: Boolean = false)

    fun join(words: List<Word>): String {
        val out = StringBuilder()
        var previous: Word? = null
        for (word in words) {
            val text = word.text.trim()
            if (text.isEmpty()) continue
            val last = previous
            if (last != null) {
                val center = (word.top + word.bottom) / 2f
                val sameLine = !word.startsNewBlock && center >= last.top && center <= last.bottom
                out.append(if (sameLine) ' ' else '\n')
            }
            out.append(text)
            previous = word
        }
        return out.toString()
    }

    fun wordCount(words: List<Word>): Int = words.count { it.text.isNotBlank() }

    fun fromBoxes(boxes: List<OcrBox>): List<Word> =
        boxes.map { Word(it.text, minOf(it.rectN.top, it.rectN.bottom), maxOf(it.rectN.top, it.rectN.bottom), it.startsNewBlock) }
}
