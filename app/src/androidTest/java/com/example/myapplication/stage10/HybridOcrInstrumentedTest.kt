package com.example.myapplication.stage10

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.*
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage3.DocumentWorkToken
import com.example.myapplication.stage7.Stage7WorkerResourceBoundary
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HybridOcrInstrumentedTest {
    @Test fun realMixedPageIndexesDigitalTitleAndScannedDetail() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "hybrid-native-test-").toFile()
        val file = File(directory, "hybrid.pdf")
        val boundary = Stage7WorkerResourceBoundary()
        val index = OcrIndex(context, boundary)
        try {
            withContext(Dispatchers.IO) { createFixture(file) }
            val token = DocumentSessionToken(DocumentId.new(), Uri.fromFile(file).toString(),
                SourceFingerprint.fromBytes(file.readBytes()), 1L)
            withContext(Dispatchers.IO) {
                AndroidOcrSessionResourceFactory(context).open(token).use { graph ->
                    assertTrue("fixture must trigger the former embedded-only branch", graph.extractEmbeddedText(0).size >= 10)
                    assertTrue("mixed fixture raster was not detected", graph.hasRasterContent(0))
                }
            }
            val page = requireNotNull(index.getPageOcr(token, 0))
            val engine = PdfSearchEngine(context, boundary, index)
            assertEquals("Synthetic hybrid boxes: ${page.boxes}", 2, engine.search(DocumentWorkToken(token), "digital title", 1)[0]?.size)
            assertEquals(2, engine.search(DocumentWorkToken(token), "scanned detail", 1)[0]?.size)
            assertEquals("digital/OCR overlap must not duplicate a hit", 1,
                PdfSearchEngine.matchPhrase(page.boxes, "digital").size)
            assertTrue(page.boxes.all { it.rectN.left >= 0f && it.rectN.right <= 1f })
        } finally {
            withContext(NonCancellable) { index.closeAndJoin() }
            directory.deleteRecursively()
        }
    }

    @Test fun realTwoColumnMixedPagePreservesEmbeddedPhraseBoundaries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "hybrid-columns-test-").toFile()
        val file = File(directory, "two-column-mixed.pdf")
        val boundary = Stage7WorkerResourceBoundary()
        val index = OcrIndex(context, boundary)
        try {
            withContext(Dispatchers.IO) { createTwoColumnFixture(file) }
            val token = DocumentSessionToken(
                DocumentId.new(),
                Uri.fromFile(file).toString(),
                SourceFingerprint.fromBytes(file.readBytes()),
                1L
            )
            val embedded = withContext(Dispatchers.IO) {
                AndroidOcrSessionResourceFactory(context).open(token).use { graph ->
                    val extracted = graph.extractEmbeddedText(0)
                    assertTrue("two-column fixture must contain embedded words", extracted.size >= 10)
                    assertTrue("mixed fixture raster was not detected", graph.hasRasterContent(0))
                    extracted
                }
            }
            val embeddedTexts = embedded.map { it.text.lowercase() }
            assertTrue(embeddedTexts.indexOf("install") >= 0)
            assertTrue(embeddedTexts.indexOf("conduit") > embeddedTexts.indexOf("install"))
            assertTrue(embeddedTexts.indexOf("paint") > embeddedTexts.indexOf("conduit"))

            val page = requireNotNull(index.getPageOcr(token, 0))
            assertEquals(
                "mixed merge reordered embedded content",
                embeddedTexts,
                page.boxes.take(embedded.size).map { it.text.lowercase() }
            )
            val engine = PdfSearchEngine(context, boundary, index)
            assertEquals(
                2,
                engine.search(DocumentWorkToken(token), "install conduit", 1)[0]?.size
            )
            assertTrue(
                "words from unrelated columns became a phrase",
                engine.search(DocumentWorkToken(token), "install paint", 1)[0].isNullOrEmpty()
            )
            assertTrue("column boundary became a phrase",
                engine.search(DocumentWorkToken(token), "conduit paint", 1)[0].isNullOrEmpty())
            assertTrue("separate stamp became a phrase",
                engine.search(DocumentWorkToken(token), "wall stamp", 1)[0].isNullOrEmpty())
            assertFalse("recognized stamp was not retained", page.boxes.none {
                it.text.lowercase().contains("stamp")
            })
            assertTrue(
                "recognized stamp was not searchable through the production path",
                engine.search(DocumentWorkToken(token), "stamp text", 1)[0].orEmpty().isNotEmpty()
            )
        } finally {
            withContext(NonCancellable) { index.closeAndJoin() }
            directory.deleteRecursively()
        }
    }

    @Test fun realEmbeddedExtractionResetsGapStatisticsAcrossHeadingFonts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "ocr-gap-test-").toFile()
        val file = File(directory, "font-gap.pdf")
        try {
            withContext(Dispatchers.IO) { createFontGapFixture(file) }
            val token = DocumentSessionToken(
                DocumentId.new(),
                Uri.fromFile(file).toString(),
                SourceFingerprint.fromBytes(file.readBytes()),
                1L
            )
            withContext(Dispatchers.IO) {
                AndroidOcrSessionResourceFactory(context).open(token).use { graph ->
                    val separated = graph.extractEmbeddedText(0)
                    assertEquals(listOf("AAAA", "BBBB", "CCCC"), separated.map { it.text })
                    assertEquals(2, PdfSearchEngine.matchPhrase(separated, "BBBB CCCC").size)

                    val smallHeading = graph.extractEmbeddedText(1)
                    assertEquals(listOf("AAAA", "BBBB", "CCCC"), smallHeading.map { it.text })
                    assertEquals(2, PdfSearchEngine.matchPhrase(smallHeading, "BBBB CCCC").size)
                    val whitespace = graph.extractEmbeddedText(2)
                    assertEquals(listOf("AAAA", "BBBB", "CCCC"), whitespace.map { it.text })
                    assertEquals(2, PdfSearchEngine.matchPhrase(whitespace, "BBBB CCCC").size)
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createFixture(file: File) {
        val bitmap = Bitmap.createBitmap(1000, 200, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText("SCANNED DETAIL", 35f, 130f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK; textSize = 80f; isFakeBoldText = true
                })
            }
            PDDocument().use { document ->
                val page = PDPage(PDRectangle(600f, 800f))
                document.addPage(page)
                val image = LosslessFactory.createFromImage(document, bitmap)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 16f)
                    stream.newLineAtOffset(30f, 740f)
                    stream.showText("DIGITAL TITLE ELECTRICAL DRAWING REVISION ALPHA")
                    stream.newLineAtOffset(0f, -35f)
                    stream.showText("CIRCUIT PANEL FEEDER DETAIL PROJECT SHEET")
                    stream.endText()
                    stream.drawImage(image, 30f, 350f, 500f, 100f)
                }
                document.save(file)
            }
        } finally { bitmap.recycle() }
    }

    private fun createTwoColumnFixture(file: File) {
        val bitmap = Bitmap.createBitmap(1200, 240, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText("STAMP TEXT", 45f, 155f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 100f
                    isFakeBoldText = true
                })
            }
            PDDocument().use { document ->
                val page = PDPage(PDRectangle(720f, 900f))
                document.addPage(page)
                val image = LosslessFactory.createFromImage(document, bitmap)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 14f)
                    stream.newLineAtOffset(45f, 835f)
                    stream.showText("install")
                    stream.newLineAtOffset(0f, -32f)
                    stream.showText("conduit")
                    stream.newLineAtOffset(0f, -32f)
                    stream.showText("left")
                    stream.newLineAtOffset(0f, -32f)
                    stream.showText("service")
                    stream.newLineAtOffset(0f, -32f)
                    stream.showText("branch")
                    stream.endText()

                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 14f)
                    stream.newLineAtOffset(410f, 835f)
                    stream.showText("paint")
                    stream.newLineAtOffset(0f, -32f)
                    stream.showText("wall")
                    stream.newLineAtOffset(0f, -32f)
                    stream.showText("right")
                    stream.newLineAtOffset(0f, -32f)
                    stream.showText("finish")
                    stream.newLineAtOffset(0f, -32f)
                    stream.showText("schedule")
                    stream.endText()
                    stream.drawImage(image, 70f, 90f, 580f, 115f)
                }
                document.save(file)
            }
        } finally { bitmap.recycle() }
    }

    private fun createFontGapFixture(file: File) {
        PDDocument().use { document ->
            // The first two pages differ only in heading size. The third
            // independently verifies ordinary explicit whitespace handling.
            for ((fontSize, explicitSpace) in listOf(100f to false, 10f to false, 10f to true)) {
                val page = PDPage(PDRectangle.LETTER)
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, fontSize)
                    stream.newLineAtOffset(50f, 650f)
                    stream.showText("AAAA")
                    stream.endText()
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 10f)
                    stream.newLineAtOffset(300f, 600f)
                    stream.showText(if (explicitSpace) "BBBB CCCC" else "BBBB")
                    stream.endText()
                    if (!explicitSpace) {
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, 10f)
                        stream.newLineAtOffset(380f, 600f)
                        stream.showText("CCCC")
                        stream.endText()
                    }
                }
            }
            document.save(file)
        }
    }
}
