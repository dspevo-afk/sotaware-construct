package com.example.myapplication.stage7

import android.app.Application
import android.graphics.RectF
import com.example.myapplication.OcrBox
import com.example.myapplication.OcrIndex
import com.example.myapplication.PageOcr
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage3.DocumentSessionToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Deferred
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher

/** Copy into app/src/test/.../stage7 to verify pre-cache yields the cache lock between pages. */
@OptIn(ExperimentalCoroutinesApi::class)
class OcrPreCacheForegroundProgressRegressionTest {
    @Test
    fun sameDocumentUncachedPageCanLoadWhilePrecacheProgressIsOnMain() = runTest {
        val sessionToken = token("same-document")
        val workerDispatcher = UnconfinedTestDispatcher(testScheduler)
        val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val progressEntered = CountDownLatch(1)
        val releaseProgress = CountDownLatch(1)
        val boundary = Stage7WorkerResourceBoundary(
            workerDispatcher = workerDispatcher,
            mainDispatcher = mainDispatcher,
            ocrSessionRegistry = OcrSessionRegistry(),
            publicationFence = Stage7PublicationFence()
        )
        val index = OcrIndex(
            Application(),
            boundary,
            OcrSessionResourceFactory { GateGraph(pageCount = 3) }
        )
        val scan = async(start = CoroutineStart.UNDISPATCHED) {
            index.preCacheDocument(
                token = sessionToken,
                cacheNamespace = "same-document-cache",
                onProgress = { done, _ ->
                    if (done == 1) {
                        progressEntered.countDown()
                        check(releaseProgress.await(2, TimeUnit.SECONDS)) {
                            "test did not release the progress callback"
                        }
                    }
                }
            )
        }
        var foreground: Deferred<PageOcr?>? = null
        try {
            assertTrue("pre-cache did not reach progress after page 0", progressEntered.await(2, TimeUnit.SECONDS))
            assertNull(index.getCachedPageOcr(sessionToken, pageIndex = 2, cacheNamespace = "same-document-cache"))

            // Page 0's session operation is complete. The main-dispatch progress
            // callback is still held, so page 2 must be independently loadable.
            val foregroundJob = async(start = CoroutineStart.UNDISPATCHED) {
                index.getPageOcr(
                    token = sessionToken,
                    pageIndex = 2,
                    cacheNamespace = "same-document-cache"
                )
            }
            foreground = foregroundJob
            assertTrue(
                "foreground OCR waited for the pre-cache progress callback",
                foregroundJob.isCompleted
            )
            assertNotNull(foregroundJob.await())
            assertFalse("pre-cache should remain inside the held callback", scan.isCompleted)
        } finally {
            releaseProgress.countDown()
            foreground?.cancel()
            scan.cancel()
            runCurrent()
            index.closeAndJoin()
            mainDispatcher.close()
        }
    }

    private fun token(name: String): DocumentSessionToken = DocumentSessionToken(
        documentId = DocumentId.new(),
        sourceUri = "content://same-document-repro/$name",
        sourceFingerprint = SourceFingerprint.fromBytes(name.toByteArray()),
        generation = 1L
    )

    private class GateGraph(private val pageCount: Int) : OcrSessionResourceGraph {
        override suspend fun pageCount(): Int = pageCount

        override suspend fun extractEmbeddedText(pageIndex: Int): List<OcrBox> = emptyList()

        override suspend fun recognizePage(pageIndex: Int): List<OcrBox> =
            listOf(OcrBox("page-$pageIndex", RectF(0f, 0f, 1f, 1f)))

        override fun close() = Unit
    }
}
