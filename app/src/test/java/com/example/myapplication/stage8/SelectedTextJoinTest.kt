package com.example.myapplication.stage8

import com.example.myapplication.stage8.SelectedTextJoin.Word
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectedTextJoinTest {
    @Test
    fun wordsOnOneLineJoinWithSpacesAndNewLinesBreak() {
        val words = listOf(
            Word("PANEL", .10f, .12f), Word("LP-1", .101f, .121f),
            Word("208V", .15f, .17f), Word("3PH", .151f, .171f)
        )
        assertEquals("PANEL LP-1\n208V 3PH", SelectedTextJoin.join(words))
        assertEquals(4, SelectedTextJoin.wordCount(words))
    }

    @Test
    fun newBlockAlwaysBreaksAndBlankWordsAreSkipped() {
        val words = listOf(Word("A", .1f, .2f), Word(" ", .1f, .2f), Word("B", .1f, .2f, startsNewBlock = true))
        assertEquals("A\nB", SelectedTextJoin.join(words))
        assertEquals(2, SelectedTextJoin.wordCount(words))
    }
}
