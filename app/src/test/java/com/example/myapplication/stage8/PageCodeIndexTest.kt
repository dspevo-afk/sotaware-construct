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
        assertEquals(mapOf(0 to "E-101", 2 to "A2.03"), result.codes)
    }

    @Test fun aFailedPageIsSkippedAndTheCodesItFoundStayPublished() = runBlocking {
        // Dave's report: "Identify Page Codes" registered nothing. The pass used to abort on the
        // first sheet whose text could not be read, discarding every code it had already found -
        // and sheet numbers drawn as vector graphics read as no text at all. Unreadable sheets are
        // skipped now, so the readable ones register.
        val result = PageCodeIndex.scan(3, region, { true }, {
            if (it == 1) null else PageOcr(it, listOf(code("E-10${it}")))
        })
        assertEquals(mapOf(0 to "E-100", 2 to "E-102"), result.codes)
    }

    @Test fun aPageWithoutReadableTextIsSkippedAndTheRestStillRegisters() = runBlocking {
        // Dave's report: highlighting the code registered nothing. Sheet numbers are vector
        // graphics on many drawings, so OCR reads nothing on some sheets; the pass used to
        // abort on the first unreadable sheet and throw every code it had already found away.
        val visited = mutableListOf<Int>()
        val result = PageCodeIndex.scan(4, region, { true }, { page ->
            visited += page
            when (page) {
                1 -> null // text extraction/OCR unavailable for this sheet
                2 -> PageOcr(2, listOf(unrelated())) // readable page, nothing inside the box
                else -> PageOcr(page, listOf(code("E-10${page}")))
            }
        })
        // One sheet, one read: a sheet is never asked for twice.
        assertEquals(listOf(0, 1, 2, 3), visited)
        assertEquals(mapOf(0 to "E-100", 3 to "E-103"), result.codes)
        assertEquals(1, result.unreadPages)
    }

    @Test fun everySheetReadsOnceFromAnyOpenSheet() = runBlocking {
        // Dave's beta-7 report: his sheet number was read on the sheet he highlighted (25 of 28)
        // and on no other sheet. The probe on that device showed every page returning its text,
        // so nothing here may depend on a second pass: each sheet is read exactly once, in order,
        // whichever sheet the scan was started from.
        val visited = mutableListOf<Int>()
        val result = PageCodeIndex.scan(4, region, { true }, { page ->
            visited += page
            PageOcr(page, listOf(code("E-10${page}")))
        })
        assertEquals(setOf(0, 1, 2, 3), result.codes.keys)
        assertEquals("E-100", result.codes[0])
        assertEquals("E-103", result.codes[3])
        assertEquals(listOf(0, 1, 2, 3), visited)
        assertEquals(0, result.unreadPages)
    }

    @Test fun progressCountsTheSheetsOfTheDocumentOnceEach() = runBlocking {
        // The dialog says "Reading page n of m": m is the sheet count, never twice it.
        val steps = mutableListOf<Pair<Int, Int>>()
        PageCodeIndex.scan(3, region, { true }, { if (it == 1) null else PageOcr(it, listOf(code("E-10$it"))) },
            onProgress = { done, total -> steps += done to total })
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), steps)
        assertTrue(steps.zipWithNext().all { (a, b) -> b.first >= a.first })
    }

    @Test fun aPassThatReadsNothingAtAllIsNotReportedAsSuccess() = runBlocking {
        // Skipping unreadable sheets must not turn into a fake success: a scan that read no text
        // on any page fails, and the caller keeps the existing labels and says so.
        try {
            PageCodeIndex.scan(2, region, { true }, { null })
            fail("A scan that read no text on any page must fail")
        } catch (_: IllegalStateException) { }
    }

    @Test fun aPageWithTextButNoCodeInTheBoxIsNotACountedFailure() = runBlocking {
        // A readable sheet whose box holds nothing is the normal "no code here" case: it is not
        // an unreadable page, so the pass still succeeds with whatever other sheets produced.
        val result = PageCodeIndex.scan(2, region, { true }, { page ->
            if (page == 0) null else PageOcr(page, listOf(unrelated()))
        })
        assertEquals(emptyMap<Int, String>(), result.codes)
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

    @Test fun cancellationDuringTheRetryPassStopsBeforePublishingLabels() = runBlocking {
        val visited = mutableListOf<Int>()
        try {
            PageCodeIndex.scan(3, region, { true }, { page ->
                visited += page
                if (page == 1) throw CancellationException()
                null
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

    @Test fun aBoxThatCatchesTheTitleBlockKeepsTheSheetNumber() {
        // Dave's codes-28.pdf: a box around the sheet number also covers the words beside it
        // ("sheet number:", "checked by:", "PAW Typical Partition"). Joining all of that is a
        // long sentence, and the old length limit threw the whole sheet away - only 1 of his
        // 28 sheets registered. The sheet number inside the box is the answer.
        val titleBlock = PageOcr(3, listOf(
            OcrBox("sheet", rect(.90f, .86f, .93f, .88f)),
            OcrBox("number:", rect(.93f, .86f, .96f, .88f)),
            code("A0.10"),
            OcrBox("PAW Typical Partition Detail", rect(.88f, .93f, 1f, .95f))
        ))
        assertEquals("A0.10", PageCodeIndex.codeInRegion(titleBlock, region))
        // The sheet's own number alone is untouched, and so is ordinary text inside the box.
        assertEquals("E2.0", PageCodeIndex.codeInRegion(PageOcr(24, listOf(code("E2.0"))), region))
        assertEquals("checked by: J. Pyrciott", PageCodeIndex.codeInRegion(
            PageOcr(4, listOf(OcrBox("checked by:", rect(.8f, .9f, .9f, .95f)), code("J. Pyrciott"))), region))
        // A box over a sheet's whole title block can hold two sheet numbers (a detail sheet);
        // both are kept while they stay within the label limit.
        assertEquals("A0.10 LS1.00", PageCodeIndex.codeInRegion(
            PageOcr(5, listOf(code("A0.10"), code("LS1.00"))), region))
    }

    @Test fun invalidRegionsAreRejectedBeforeOcr() {
        for (bounds in listOf(listOf(Float.NaN, 0f, 1f, 1f), listOf(-1f, 0f, 1f, 1f), listOf(.9f, 0f, .5f, 1f))) {
            try { PageCodeRegion(bounds[0], bounds[1], bounds[2], bounds[3]); fail("Invalid region accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
