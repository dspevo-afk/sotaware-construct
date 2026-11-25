package com.example.myapplication.stage7

import android.app.Application
import android.graphics.RectF
import com.example.myapplication.OcrBox
import com.example.myapplication.OcrIndex
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Copy into app/src/test/.../stage7 to reproduce the OCR-cache lock contention. */
@OptIn(ExperimentalCoroutinesApi::class)
class OcrPreCacheContentionRegressionTest {
    @Test
    fun differentDocumentCacheMissCompletesWhileAnotherDocumentPrecacheIsGated() = runTest {
        val scanToken = token("scan")
        val foregroundToken = token("foreground")
        val scanEntered = CompletableDeferred<Unit>()
        val releaseScan = CompletableDeferred<Unit>()
        val foregroundOpened = CompletableDeferred<Unit>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val boundary = Stage7WorkerResourceBoundary(
            workerDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            ocrSessionRegistry = OcrSessionRegistry(),
            publicationFence = Stage7PublicationFence()
        )
        val factory = OcrSessionResourceFactory { token ->
            if (token == scanToken) {
                GateGraph(pageCount = 2, entered = scanEntered, release = releaseScan)
            } else {
                foregroundOpened.complete(Unit)
                GateGraph(pageCount = 1)
            }
        }
        val index = OcrIndex(Application(), boundary, factory)
        val scan = async(start = CoroutineStart.UNDISPATCHED) {
            index.preCacheDocument(scanToken, cacheNamespace = "scan-namespace")
        }
        var foreground: kotlinx.coroutines.Deferred<com.example.myapplication.PageOcr?>? = null
        try {
            scanEntered.await() // Pre-cache is inside page 0 recognition and owns the cache transaction.
            val foregroundJob = async(start = CoroutineStart.UNDISPATCHED) {
                index.getPageOcr(foregroundToken, pageIndex = 0, cacheNamespace = "foreground-namespace")
            }
            foreground = foregroundJob
            foregroundOpened.await()
            runCurrent()

            // An unrelated document's cache miss should not wait for all of this
            // document's page recognition. Current code fails here because the
            // pre-cache transaction holds storeMutationMutex during recognition.
            assertTrue(
                "foreground cache miss remained blocked while unrelated pre-cache was gated",
                foregroundJob.isCompleted
            )
            assertNotNull(foregroundJob.await())
            assertTrue("the scan should still be gated", !scan.isCompleted)
        } finally {
            releaseScan.complete(Unit)
            foreground?.cancel()
            scan.cancel()
            runCurrent()
            index.closeAndJoin()
        }
    }

    private fun token(name: String): DocumentSessionToken = DocumentSessionToken(
        documentId = DocumentId.new(),
        sourceUri = "content://concurrency-repro/$name",
        sourceFingerprint = SourceFingerprint.fromBytes(name.toByteArray()),
        generation = 1L
    )

    private class GateGraph(
        private val pageCount: Int,
        private val entered: CompletableDeferred<Unit>? = null,
        private val release: CompletableDeferred<Unit>? = null
    ) : OcrSessionResourceGraph {
        override suspend fun pageCount(): Int = pageCount

        override suspend fun extractEmbeddedText(pageIndex: Int): List<OcrBox> = emptyList()

        override suspend fun recognizePage(pageIndex: Int): List<OcrBox> {
            if (pageIndex == 0 && entered != null && release != null) {
                entered.complete(Unit)
                release.await()
            }
            return listOf(OcrBox("page-$pageIndex", RectF(0f, 0f, 1f, 1f)))
        }

        override fun close() = Unit
    }
}
