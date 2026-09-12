package com.example.myapplication.stage10

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.exportPageAsPdf
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage3.DocumentSessionToken
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfExportSourceInstrumentedTest {
    @Test fun changedBytesAtSameProviderUriAreRejectedBeforeDestinationPublication() = runCase("b") { uri, token, file ->
        assertFalse(exportPageAsPdf(context, Uri.fromFile(file), uri, 0,
            emptyList(), emptyList(), emptyList(), emptyList(), photoSessionToken = token))
        assertFalse("changed drawing was published", file.exists())
        assertEquals(1, context.contentResolver.call(uri, "state", null, null)!!.getInt("opens"))
    }

    @Test fun exportUsesVerifiedCopyWithoutReopeningTheChangingProvider() = runCase("a-then-b") { uri, token, file ->
        assertTrue(exportPageAsPdf(context, Uri.fromFile(file), uri, 0,
            emptyList(), emptyList(), emptyList(), emptyList(), photoSessionToken = token))
        assertTrue(file.length() > 5)
        file.inputStream().use { assertEquals("%PDF-", String(it.readNBytes(5), Charsets.US_ASCII)) }
        val state = context.contentResolver.call(uri, "state", null, null)!!
        assertEquals(1, state.getInt("opens"))
        assertEquals("b", state.getString("revision"))
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun runCase(mode: String, block: suspend (Uri, DocumentSessionToken, File) -> Unit): Unit = runBlocking {
        val original = InstrumentationRegistry.getInstrumentation().context.assets
            .open("stage10/pdfs/a/plan.pdf").use { it.readBytes() }
        val uri = Uri.parse(CLOSEOUT_SOURCE_URI)
        context.contentResolver.call(uri, mode, null, null)
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "closeout-source-test-").toFile()
        try {
            val token = DocumentSessionToken(DocumentId.new(), uri.toString(), SourceFingerprint.fromBytes(original), 1L)
            block(uri, token, File(directory, "result.pdf"))
        } finally { directory.deleteRecursively() }
    }
}
