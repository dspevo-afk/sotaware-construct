package com.example.myapplication.stage8

import android.graphics.RectF
import com.example.myapplication.OcrBox
import com.example.myapplication.PageOcr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PageCodeIndexTest {
    private val region = PageCodeRegion(.75f, .85f, 1f, 1f)
    // Android's host stub constructor does not populate RectF fields.
    private fun rect(l: Float, t: Float, r: Float, b: Float) = RectF().apply {
        left = l; top = t; right = r; bottom = b
    }
    private fun code(value: String) = OcrBox(value, rect(.8f, .9f, .95f, .95f))
    private fun unrelated() = OcrBox("PROJECT NUMBER 999", rect(.1f, .1f, .5f, .2f))

    @Test fun selectedAreaIsReadOnEveryPageAndBlankPagesKeepTheirNumericLabel() = runBlocking {
        val visited = mutableListOf<Int>()
        val pages = listOf(
            PageOcr(0, listOf(unrelated(), code("E-101"))),
            PageOcr(1, listOf(unrelated())),
            PageOcr(2, listOf(code("A2.03"), unrelated()))
        )
        val result = PageCodeIndex.scan(3, region, { true }, { visited += it; pages[it] })
        assertEquals(listOf(0, 1, 2), visited)
        assertEquals(mapOf(0 to "E-101", 2 to "A2.03"), result)
    }

    @Test fun aFailedPageNeverReturnsASuccessfulPartialIndex() = runBlocking {
        var published: Map<Int, String>? = null
        try {
            published = PageCodeIndex.scan(3, region, { true }, {
                if (it == 1) null else PageOcr(it, listOf(code("E-101")))
            })
            fail("A missing OCR result must fail the scan")
        } catch (_: IllegalStateException) { }
        assertNull(published)
    }

    @Test fun switchingDocumentsOrReplacingTheRegionRejectsLateOcr() = runBlocking {
        var current = true
        try {
            PageCodeIndex.scan(2, region, { current }, {
                current = false
                PageOcr(it, listOf(code("OLD-101")))
            })
            fail("A stale result must not publish labels")
        } catch (_: IllegalStateException) { }
    }

    @Test fun cancellationPropagatesAndDoesNotReadTheRemainingPages() = runBlocking {
        val visited = mutableListOf<Int>()
        try {
            PageCodeIndex.scan(3, region, { true }, {
                visited += it
                if (it == 1) throw CancellationException()
                PageOcr(it, listOf(code("E-101")))
            })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(listOf(0, 1), visited)
    }

    @Test fun anotherPageCannotBeRelabeledAsTheRequestedPage() = runBlocking {
        try {
            PageCodeIndex.scan(1, region, { true }, { PageOcr(7, listOf(code("E-701"))) })
            fail("Page identity must match")
        } catch (_: IllegalStateException) { }
    }

    @Test fun emptyOversizedAndMalformedTextCannotBecomeCodes() {
        assertNull(PageCodeIndex.codeInRegion(PageOcr(0, listOf(code(" "))), region))
        assertNull(PageCodeIndex.codeInRegion(PageOcr(0, listOf(code("x".repeat(81)))), region))
        assertNull(PageCodeIndex.codeInRegion(PageOcr(0, listOf(OcrBox("BAD", rect(Float.NaN, .9f, .95f, .95f)))), region))
        assertEquals("A 101", PageCodeIndex.codeInRegion(PageOcr(0, listOf(code(" A\n101 "))), region))
    }

    @Test fun invalidRegionsAreRejectedBeforeOcr() {
        for (bounds in listOf(listOf(Float.NaN, 0f, 1f, 1f), listOf(-1f, 0f, 1f, 1f), listOf(.9f, 0f, .5f, 1f))) {
            try { PageCodeRegion(bounds[0], bounds[1], bounds[2], bounds[3]); fail("Invalid region accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
