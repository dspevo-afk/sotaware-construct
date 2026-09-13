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

/** Synthetic, account-free negative audit probes; no real drawings or app lifecycle calls. */
@RunWith(AndroidJUnit4::class)
class RepositoryAuditExportProbe {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test fun largeBlueprintMustRetainItsPhysicalPageDimensions() = runBlocking {
        runCase(3456, 2592) { source, output, token ->
            assertTrue(exportPageAsPdf(context, Uri.fromFile(output), Uri.fromFile(source), 0,
                emptyList(), emptyList(), emptyList(), emptyList(), photoSessionToken = token))
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> renderer.openPage(0).use { page ->
                    assertEquals("export changed the physical paper width", 3456, page.width)
                    assertEquals("export changed the physical paper height", 2592, page.height)
                } }
            }
        }
    }
    @Test fun acceptedPhotoCountMustExportOnLandscapeLetter() = runBlocking {
        runCase(792, 612) { source, output, token ->
            val names = (1..128).map { "audit_$it.jpg" }
            val assets = NativeFixtureAssets.photoAssets(instrumentation.context,
                names.associateWith { "stage7/photos/small_valid_photo.jpg" })
            val pin = PhotoPin(.5f, .5f, imageFileNames = names)
            assertTrue("accepted 128-photo pin cannot be exported on landscape letter",
                exportPageAsPdf(context, Uri.fromFile(output), Uri.fromFile(source), 0,
                    emptyList(), emptyList(), emptyList(), listOf(pin),
                    photoSessionToken = token, photoAssets = assets))
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
