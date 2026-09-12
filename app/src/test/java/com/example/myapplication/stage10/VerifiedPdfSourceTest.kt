package com.example.myapplication.stage10

import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage6.PdfExportTemporaryOwner
import com.example.myapplication.stage6.withVerifiedPdfSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VerifiedPdfSourceTest {
    @Test fun rendererReadsOneVerifiedCopyEvenIfProviderChangesAgain() = runCase { dir ->
        val original = "verified drawing A".toByteArray()
        var provider = original
        var opens = 0
        withVerifiedPdfSource(dir, SourceFingerprint.fromBytes(original), {
            opens++; ByteArrayInputStream(provider)
        }) { file ->
            provider = "different drawing B".toByteArray()
            assertArrayEquals(original, file.readBytes())
        }
        assertEquals(1, opens)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun changedTruncatedAndOversizedSourcesNeverReachRenderer() = runCase { dir ->
        val original = "original revision".toByteArray()
        for (candidate in listOf("different version".toByteArray(), original.drop(1).toByteArray(), original + 1)) {
            var rendered = false
            try {
                withVerifiedPdfSource(dir, SourceFingerprint.fromBytes(original), { candidate.inputStream() }) {
                    rendered = true
                }
                fail("changed source accepted")
            } catch (_: IOException) { }
            assertFalse(rendered)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun unavailableAndNonProgressingSourcesFailClosed() = runCase { dir ->
        val expected = SourceFingerprint.fromBytes(byteArrayOf(1))
        val zero = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        for (source in listOf<InputStream?>(null, zero)) {
            try { withVerifiedPdfSource(dir, expected, { source }) { fail("rendered") }; fail("accepted") }
            catch (_: IOException) { }
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun cancellationPropagatesAndClosesTheSourceAndCopy() = runCase { dir ->
        var closed = false
        val source = object : InputStream() {
            override fun read(): Int = throw CancellationException("cancelled source")
            override fun close() { closed = true }
        }
        try { withVerifiedPdfSource(dir, SourceFingerprint.fromBytes(byteArrayOf(1)), { source }) { fail("rendered") }; fail("accepted") }
        catch (expected: CancellationException) { assertEquals("cancelled source", expected.message) }
        assertTrue(closed)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun rendererFailureStillRemovesTheVerifiedCopy() = runCase { dir ->
        val bytes = "source".toByteArray()
        try { withVerifiedPdfSource(dir, SourceFingerprint.fromBytes(bytes), { bytes.inputStream() }) { throw IOException("render failed") } }
        catch (expected: IOException) { assertEquals("render failed", expected.message) }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun sourceHelperSharesRequestLeaseWithResultAcrossOwnerInstances() = runCase { dir ->
        val bytes = "shared source".toByteArray()
        val requestId = UUID.randomUUID().toString()
        val owner = PdfExportTemporaryOwner(dir, abandonedAfterMillis = 100L)
        val result = owner.createResultFile(requestId).apply { writeText("result") }
        Files.setLastModifiedTime(result.toPath(), FileTime.fromMillis(1L))
        try {
            withVerifiedPdfSource(
                temporaryOwner = owner,
                requestId = requestId,
                expected = SourceFingerprint.fromBytes(bytes),
                openSource = { bytes.inputStream() }
            ) { staged ->
                Files.setLastModifiedTime(staged.toPath(), FileTime.fromMillis(1L))
                val restartedOwner = PdfExportTemporaryOwner(dir, abandonedAfterMillis = 100L)
                val reconciliation = restartedOwner.reconcile(
                    nowMillis = System.currentTimeMillis() + 1_000L
                )
                assertTrue(reconciliation.retainedFileNames.contains(staged.name))
                assertTrue(reconciliation.retainedFileNames.contains(result.name))
                assertTrue(staged.exists())
                assertTrue(result.exists())
            }
        } finally {
            owner.release(result)
        }
    }

    private fun runCase(block: suspend (File) -> Unit): Unit = runBlocking {
        val dir = Files.createTempDirectory("construct-verified-export-test-").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
}
