package com.example.myapplication.stage10

import android.graphics.RectF
import com.example.myapplication.OcrBox
import com.example.myapplication.PdfSearchEngine
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage7.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HybridOcrTest {
    @Test fun digitalTitleDoesNotSuppressScannedPhraseAndDuplicatesAreRemoved() = runBlocking {
        val digital = List(10) { box("title$it", 0.02f + it * 0.09f) }
        val graph = Graph(digital, listOf(box("TITLE0", 0.02f), box("scanned", 0.1f, 0.5f), box("detail", 0.2f, 0.5f)), true)
        val session = OcrSession(token(), graph)
        try {
            val result = session.pageOcr(0)
            assertEquals(1, graph.recognitions)
            assertEquals(12, result.boxes.size)
            assertEquals(2, PdfSearchEngine.matchPhrase(result.boxes, "scanned detail").size)
            assertEquals(1, PdfSearchEngine.matchPhrase(result.boxes, "title0").size)
        } finally { session.closeAndJoin() }
    }

    @Test fun pureDigitalPageKeepsEmbeddedOrderingWithoutRecognition() = runBlocking {
        val digital = List(10) { box("word$it", 0.02f + it * 0.09f) }
        val graph = Graph(digital, emptyList(), false)
        val session = OcrSession(token(), graph)
        try {
            assertEquals(digital, session.pageOcr(0).boxes)
            assertEquals(0, graph.recognitions)
        } finally { session.closeAndJoin() }
    }

    @Test fun pureEmbeddedAdmissionIsIndependentOfMixedMergeBudget() = runBlocking {
        val digital = List(HybridOcr.MAX_BOXES + 1) { box("word", 0.1f, 0.1f) }
        val graph = Graph(digital, emptyList(), false)
        val session = OcrSession(token(), graph)
        try {
            assertEquals(digital.size, session.pageOcr(0).boxes.size)
            assertEquals(0, graph.recognitions)
        } finally { session.closeAndJoin() }
    }

    @Test fun sparseEmbeddedTextIsRetainedAlongsideRasterText() = runBlocking {
        val graph = Graph(listOf(box("digital", 0.1f)), listOf(box("scan", 0.2f, 0.5f)), false)
        val session = OcrSession(token(), graph)
        try { assertEquals(setOf("digital", "scan"), session.pageOcr(0).boxes.map { it.text }.toSet()) }
        finally { session.closeAndJoin() }
    }

    @Test fun equalWordsAtDifferentLocationsRemainAndSameLinePhraseIsOrdered() = runBlocking {
        val result = HybridOcr.merge(listOf(box("fire", 0.1f)), listOf(box("exit", 0.2f, 0.105f), box("fire", 0.1f, 0.6f)))
        assertEquals(listOf("fire", "exit", "fire"), result.map { it.text })
        assertEquals(2, PdfSearchEngine.matchPhrase(result, "fire exit").size)
        assertEquals(2, PdfSearchEngine.matchPhrase(result, "fire").size)
    }

    @Test fun embeddedOrderAndColumnBoundariesSurviveMixedMerge() = runBlocking {
        val embedded = listOf(
            box("install", 0.1f, 0.1f),
            box("conduit", 0.1f, 0.2f),
            box("paint", 0.7f, 0.1f),
            box("wall", 0.7f, 0.2f)
        )
        val merged = HybridOcr.merge(embedded, listOf(box("stamp", 0.8f, 0.8f)))

        assertEquals(
            listOf("install", "conduit", "paint", "wall", "stamp"),
            merged.map { it.text }
        )
        assertEquals(2, PdfSearchEngine.matchPhrase(merged, "install conduit").size)
        assertTrue(PdfSearchEngine.matchPhrase(merged, "install paint").isEmpty())
        assertTrue(PdfSearchEngine.matchPhrase(merged, "conduit paint").isEmpty())
        assertTrue(PdfSearchEngine.matchPhrase(merged, "wall stamp").isEmpty())
        assertEquals(1, PdfSearchEngine.matchPhrase(merged, "stamp").size)
    }

    @Test fun recognizedColumnsAreOrderedWithoutReorderingWordsWithinALine() = runBlocking {
        val recognized = listOf(
            box("paint", 0.7f, 0.1f),
            box("wall", 0.7f, 0.2f),
            box("install", 0.1f, 0.1f),
            box("conduit", 0.1f, 0.2f)
        )
        val merged = HybridOcr.merge(emptyList(), recognized)

        assertEquals(listOf("install", "conduit", "paint", "wall"), merged.map { it.text })
    }

    @Test fun recognizedBlockBoundarySurvivesDeduplicationOfItsFirstWord() = runBlocking {
        val duplicate = box("heading", 0.7f, 0.7f)
        val merged = HybridOcr.merge(listOf(duplicate), listOf(
            box("stamp", 0.1f),
            duplicate.copy(startsNewBlock = true),
            box("warning", 0.2f),
            box("detail", 0.3f)
        ))
        assertTrue(PdfSearchEngine.matchPhrase(merged, "stamp warning").isEmpty())
        assertEquals(2, PdfSearchEngine.matchPhrase(merged, "warning detail").size)
        assertEquals(1, merged.count { it.text == "heading" })
    }

    @Test fun excessiveHybridPayloadFailsInsteadOfReturningPartialIndex() = runBlocking {
        try {
            HybridOcr.merge(List(HybridOcr.MAX_BOXES) { box("digital", 0.1f) }, listOf(box("scan", 0.2f)))
            fail("excessive payload accepted")
        } catch (_: IOException) { }
    }

    @Test fun failedRasterRecognitionDoesNotPretendEmbeddedOnlyWasComplete() = runBlocking {
        val graph = Graph(List(10) { box("digital$it", 0.02f + it * 0.09f) }, emptyList(), true,
            IOException("raster failed"))
        val session = OcrSession(token(), graph)
        try { session.pageOcr(0); fail("partial result was returned") }
        catch (expected: IOException) { assertEquals("raster failed", expected.message) }
        finally { session.closeAndJoin() }
    }

    @Test fun rasterCancellationIsPropagated() = runBlocking {
        val session = OcrSession(token(), Graph(emptyList(), emptyList(), true, CancellationException("cancelled")))
        try { session.pageOcr(0); fail("cancellation swallowed") }
        catch (_: CancellationException) { }
        finally { session.closeAndJoin() }
    }

    @Test fun baselineMetricAndInkRectanglesForTheSameWordAreDeduplicated() = runBlocking {
        val embedded = preciseBox("DIGITAL", 0.05f, 0.075f, 0.15373331f, 0.08656f)
        val ink = preciseBox("DIGITAL", 0.052083332f, 0.0603125f, 0.15333334f, 0.0753125f)
        val otherLabel = preciseBox("DIGITAL", 0.05f, 0.4f, 0.154f, 0.416f)
        val merged = HybridOcr.merge(listOf(embedded), listOf(ink, otherLabel))
        assertEquals(2, merged.size)
        assertEquals(0.075f, merged.first().rectN.top, 0f)
        assertEquals(0.4f, merged.last().rectN.top, 0f)
    }

    @Test fun oneEmbeddedOccurrenceCannotEraseTwoRecognizedOccurrences() = runBlocking {
        val embedded = preciseBox("LABEL", 0.05f, 0.075f, 0.15f, 0.08656f)
        val first = preciseBox("LABEL", 0.05f, 0.0603f, 0.15f, 0.0753f)
        val second = preciseBox("LABEL", 0.05f, 0.078f, 0.15f, 0.093f)
        assertEquals(2, HybridOcr.merge(listOf(embedded), listOf(first, second)).size)
    }

    private fun preciseBox(text: String, l: Float, t: Float, r: Float, b: Float) =
        OcrBox(text, RectF().apply { left = l; top = t; right = r; bottom = b })

    private class Graph(
        val embedded: List<OcrBox>, val raster: List<OcrBox>, val hasRaster: Boolean,
        val failure: Throwable? = null
    ) : OcrSessionResourceGraph {
        var recognitions = 0
        override suspend fun pageCount() = 1
        override suspend fun extractEmbeddedText(pageIndex: Int) = embedded
        override suspend fun hasRasterContent(pageIndex: Int) = hasRaster
        override suspend fun recognizePage(pageIndex: Int): List<OcrBox> {
            recognitions++
            failure?.let { throw it }
            return raster
        }
        override fun close() = Unit
    }
    private fun box(text: String, left: Float, top: Float = 0.1f) =
        OcrBox(text, RectF(left, top, left + 0.07f, top + 0.04f).apply {
            this.left = left; this.top = top; right = left + 0.07f; bottom = top + 0.04f
        })
    private fun token() = DocumentSessionToken(DocumentId.new(), "content://synthetic/hybrid.pdf",
        SourceFingerprint.fromBytes(byteArrayOf(1)), 1L)
}
