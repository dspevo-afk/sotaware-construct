package com.example.myapplication.stage8

import android.graphics.RectF
import com.example.myapplication.OcrBox
import com.example.myapplication.PdfSearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSearchEngineTest {
    private fun box(text: String, left: Float): OcrBox =
        OcrBox(text, RectF(left, 0.1f, left + 0.1f, 0.2f))

    @Test
    fun phraseMatchesWithinAndAcrossAdjacentBoxes_inSuppliedOrder() {
        val boxes = listOf(box("FIRE", 0f), box("  Exit\n only", 0.2f), box("FIRE EXIT", 0.4f))

        val rects = PdfSearchEngine.matchPhrase(boxes, " fire   exit ")

        assertEquals(3, rects.size)
        boxes.map { it.rectN }.zip(rects).forEach { (expected, actual) ->
            assertEquals(expected.left, actual.left, 0f)
            assertEquals(expected.top, actual.top, 0f)
            assertEquals(expected.right, actual.right, 0f)
            assertEquals(expected.bottom, actual.bottom, 0f)
        }
    }

    @Test
    fun phraseDoesNotSkipInterveningOrReversedWords() {
        val boxes = listOf(box("fire north", 0f), box("exit", 0.2f), box("exit fire", 0.4f))

        assertTrue(PdfSearchEngine.matchPhrase(boxes, "fire exit").isEmpty())
    }

    @Test
    fun matchingIsCaseInsensitiveWhitespaceNormalizedAndBounded() {
        val boxes = listOf(box("fire", 0f), box("exit", 0.2f))
        assertEquals(2, PdfSearchEngine.matchPhrase(boxes, " FIRE\t EXIT ").size)
        assertTrue(PdfSearchEngine.matchPhrase(boxes, List(129) { "word" }.joinToString(" ")).isEmpty())
    }
}
