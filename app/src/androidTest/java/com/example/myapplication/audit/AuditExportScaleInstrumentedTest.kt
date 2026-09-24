package com.example.myapplication.audit
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.PhotoPin
import com.example.myapplication.exportPageAsPdf
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage9b.NativeFixtureAssets
import com.example.myapplication.stage9b.PhotoAssetSet
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical paper size must remain independent of the bounded raster size. */
@RunWith(AndroidJUnit4::class)
class AuditExportScaleInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test fun largeBlueprintMustRetainItsPhysicalPageDimensions() = runBlocking {
        for ((width, height) in listOf(3456 to 2592, 2592 to 3456, 792 to 612)) {
        runCase(width, height) { source, output, token ->
            assertTrue(exportPageAsPdf(context, Uri.fromFile(output), Uri.fromFile(source), 0,
                emptyList(), emptyList(), emptyList(), emptyList(), photoSessionToken = token))
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> renderer.openPage(0).use { page ->
                    assertEquals("export changed the physical paper width", width, page.width)
                    assertEquals("export changed the physical paper height", height, page.height)
                } }
            }
        }
        }
    }
    @Test fun densePhotoAppendicesPreserveEveryPhotoAndOriginalPaperSize() = runBlocking {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val pssBefore = android.os.Debug.getPss()
        var largestPss = pssBefore
        for ((width, height) in listOf(792 to 612, 612 to 792)) {
            for (count in listOf(1, 2, 7, 128)) runCase(width, height) { source, output, token ->
                val names = (1..count).map { "audit_$it.jpg" }
                val labels = names.mapIndexed { index, name -> name to "AUDITPHOTO${index.toString().padStart(3, '0')}END" }.toMap()
                val assets = NativeFixtureAssets.photoAssets(instrumentation.context,
                    names.associateWith { "stage7/photos/small_valid_photo.jpg" })
                val pin = PhotoPin(.5f, .5f, imageFileNames = names, imageNotes = labels.mapValues { (_, label) ->
                    listOf(com.example.myapplication.Note(.5f, .5f, label, fontSizeRatio = .035f))
                })
                assertTrue("$count photos on ${width}x$height", exportPageAsPdf(context,
                    Uri.fromFile(output), Uri.fromFile(source), 0, emptyList(), emptyList(), emptyList(), listOf(pin),
                    photoSessionToken = token, photoAssets = assets))
                largestPss = maxOf(largestPss, android.os.Debug.getPss())
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(output).use { exported ->
                    val capacity = com.example.myapplication.stage6.PhotoAppendixLayout.create(width, height).capacity
                    assertEquals(1 + (count + capacity - 1) / capacity, exported.numberOfPages)
                    for (page in exported.pages) {
                        assertEquals(width.toFloat(), page.mediaBox.width, .01f)
                        assertEquals(height.toFloat(), page.mediaBox.height, .01f)
                    }
                    val text = com.tom_roush.pdfbox.text.PDFTextStripper().getText(exported)
                    for (label in labels.values) {
                        assertEquals("Photo label must occur exactly once: $label", 1,
                            Regex(Regex.escape(label)).findAll(text).count())
                    }
                }
            }
        }
        println("PHOTO_APPENDIX_PSS_KIB baseline=$pssBefore observedPeak=$largestPss")
        assertTrue("Appendix retained excessive process memory", largestPss - pssBefore < 192 * 1024)
    }

    @Test fun missingPhotoCannotPublishAnIncompleteAppendix() = runBlocking {
        runCase(792, 612) { source, output, token ->
            val assets = NativeFixtureAssets.photoAssets(instrumentation.context,
                mapOf("present.jpg" to "stage7/photos/small_valid_photo.jpg"))
            val pin = PhotoPin(.5f, .5f, imageFileNames = listOf("present.jpg", "missing.jpg"))
            assertFalse(exportPageAsPdf(context, Uri.fromFile(output), Uri.fromFile(source), 0,
                emptyList(), emptyList(), emptyList(), listOf(pin), photoSessionToken = token, photoAssets = assets))
            assertFalse("An incomplete PDF was published", output.exists())
        }
    }

    @Test fun photoNotesRemainAbovePathsInTheActualExport() = runBlocking {
        runCase(792, 612) { source, output, token ->
            val white = android.graphics.Bitmap.createBitmap(320, 240, android.graphics.Bitmap.Config.ARGB_8888)
            val bytes = try {
                white.eraseColor(android.graphics.Color.WHITE)
                java.io.ByteArrayOutputStream().use { stream ->
                    assertTrue(white.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, stream))
                    stream.toByteArray()
                }
            } finally { white.recycle() }
            val descriptor = com.example.myapplication.stage5.validatePhotoBytes(bytes).descriptor
            val assets = com.example.myapplication.stage9b.photoAssetsFromDescriptors(mapOf("scene.jpg" to descriptor)) { bytes.inputStream() }
            val path = com.example.myapplication.DrawnPath(listOf(com.example.myapplication.Point(0f,.5f),
                com.example.myapplication.Point(1f,.5f)), 0xff00aa00.toInt(), false, .3f)
            val note = com.example.myapplication.Note(.5f,.5f,"MMMM", fontSizeRatio = .06f, colorArgb = 0xffee1111.toInt())
            val pin = PhotoPin(.5f,.5f, imageFileNames = listOf("scene.jpg"),
                imagePaths = mapOf("scene.jpg" to listOf(path)), imageNotes = mapOf("scene.jpg" to listOf(note)))
            assertTrue(exportPageAsPdf(context, Uri.fromFile(output), Uri.fromFile(source), 0,
                emptyList(), emptyList(), emptyList(), listOf(pin), photoSessionToken = token, photoAssets = assets))
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer -> renderer.openPage(1).use { page ->
                    val rendered = android.graphics.Bitmap.createBitmap(page.width, page.height, android.graphics.Bitmap.Config.ARGB_8888)
                    try {
                        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val pixels = IntArray(page.width * page.height)
                        rendered.getPixels(pixels, 0, page.width, 0, 0, page.width, page.height)
                        val redPixels = pixels.count { pixel -> android.graphics.Color.red(pixel) > 180 &&
                            android.graphics.Color.green(pixel) < 80 && android.graphics.Color.blue(pixel) < 80 }
                        assertTrue("The later path hid the note instead of being drawn beneath it", redPixels > 50)
                    } finally { rendered.recycle() }
                } }
            }
        }
    }

    private suspend fun runCase(width: Int, height: Int,
        block: suspend (File, File, DocumentSessionToken) -> Unit) {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "audit-export-").toFile()
        try {
            val source = File(directory, "source.pdf")
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, 1).create())
                page.canvas.drawColor(android.graphics.Color.WHITE)
                document.finishPage(page)
                source.outputStream().use(document::writeTo)
            } finally { document.close() }
            val uri = Uri.fromFile(source)
            val token = DocumentSessionToken(DocumentId.new(), uri.toString(),
                SourceFingerprint.fromBytes(source.readBytes()), 1L)
            block(source, File(directory, "result.pdf"), token)
        } finally { directory.deleteRecursively() }
    }
}