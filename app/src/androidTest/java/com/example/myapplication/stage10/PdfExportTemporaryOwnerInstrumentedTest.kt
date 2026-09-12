package com.example.myapplication.stage10

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.stage6.PdfExportTemporaryOwner
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable device proof for restart cleanup in the dedicated export root. */
@RunWith(AndroidJUnit4::class)
class PdfExportTemporaryOwnerInstrumentedTest {
    @Test
    fun abandonedSourceAndResultAreReclaimedWithoutTouchingCacheSiblings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = Files.createTempDirectory(context.cacheDir.toPath(), "export-owner-").toFile()
        val sibling = File(cache, "unrelated-cache-entry.bin").apply { writeText("keep") }
        try {
            val owner = PdfExportTemporaryOwner(cache, abandonedAfterMillis = 100L)
            val abandonedRequestId = UUID.randomUUID().toString()
            Files.createDirectories(owner.directoryForTests.toPath())
            val source = File(
                owner.directoryForTests,
                "construct-export-source-$abandonedRequestId-${UUID.randomUUID()}.pdf"
            ).apply { createNewFile(); writeText("source") }
            val result = File(
                owner.directoryForTests,
                "construct-export-result-$abandonedRequestId-${UUID.randomUUID()}.pdf"
            ).apply { createNewFile(); writeText("result") }
            Files.setLastModifiedTime(source.toPath(), FileTime.fromMillis(1L))
            Files.setLastModifiedTime(result.toPath(), FileTime.fromMillis(1L))

            val restarted = PdfExportTemporaryOwner(cache, abandonedAfterMillis = 100L)
            restarted.reconcile(nowMillis = 1_000L)
            assertFalse(source.exists())
            assertFalse(result.exists())
            assertTrue(sibling.exists())
        } finally {
            cache.deleteRecursively()
        }
    }
}
