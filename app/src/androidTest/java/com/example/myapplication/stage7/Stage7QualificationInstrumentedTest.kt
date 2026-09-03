package com.example.myapplication.stage7

import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.os.StrictMode
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.AndroidOcrSessionResourceFactory
import com.example.myapplication.OcrBox
import com.example.myapplication.PdfBitmapRenderer
import com.example.myapplication.Stage7BitmapCache
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage5.AndroidPhotoDecodeProbe
import com.example.myapplication.stage5.AndroidPhotoImageDecoder
import com.example.myapplication.stage5.DecodedPhotoImage
import com.example.myapplication.stage5.PhotoImageBounds
import com.example.myapplication.stage5.PhotoImageDecoder
import com.example.myapplication.stage5.validatePhotoBytes
import com.google.mlkit.vision.text.Text
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.util.IdentityHashMap
import java.util.Locale

private const val QUALIFICATION_VIEWPORT_WIDTH_PX = 1080
private const val QUALIFICATION_VIEWPORT_HEIGHT_PX = 1920

@RunWith(AndroidJUnit4::class)
class Stage7QualificationInstrumentedTest {
    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val testContext
        get() = InstrumentationRegistry.getInstrumentation().context

    @Before
    fun initializePdfBoxResources() {
        PDFBoxResourceLoader.init(targetContext.applicationContext)
    }

    @Test
    fun realPdfRenderer_rendersAllStage7FixturesWithinActualBitmapBudget_andReopens() = runBlocking {
        val boundary = Stage7WorkerResourceBoundary(
            workerDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.Main.immediate
        )
        val fixtures = listOf(
            FixtureSpec(
                assetPath = "stage7/pdfs/blueprint/large_blueprint.pdf",
                pageCount = 4
            ),
            FixtureSpec(
                assetPath = "stage7/pdfs/scanned/scanned_image_only.pdf",
                pageCount = 1
            ),
            FixtureSpec(
                assetPath = "stage7/pdfs/scanned/scanned_text_fixture.pdf",
                pageCount = 1
            ),
            FixtureSpec(
                assetPath = "stage7/pdfs/cropped-rotated/embedded_text_crop_offset_rotate.pdf",
                pageCount = 1
            )
        )

        fixtures.forEach { spec ->
            withFixture(spec.assetPath) { fixture ->
                withFailFastStage7ThreadPolicy {
                    val renderer = PdfBitmapRenderer(targetContext)
                    try {
                        var renderThread: Thread? = null
                        boundary.withWorker {
                            renderThread = Thread.currentThread()
                            val session = checkNotNull(renderer.openSession(fixture.uri))
                            try {
                                assertNull(session.renderPageBitmap(-1))
                                assertNull(session.renderPageBitmap(spec.pageCount))
                                assertNull(session.renderPageBitmap(0, scaleFactor = 0))
                                assertNull(session.renderPageBitmap(0, maxDim = 0))

                                repeat(spec.pageCount) { pageIndex ->
                                    val bitmap = checkNotNull(
                                        session.renderPageBitmap(
                                            pageIndex = pageIndex,
                                            viewportWidthPx = QUALIFICATION_VIEWPORT_WIDTH_PX,
                                            viewportHeightPx = QUALIFICATION_VIEWPORT_HEIGHT_PX
                                        )
                                    )
                                    assertBitmapWithinBudget(bitmap)
                                    bitmap.recycle()
                                }
                            } finally {
                                session.close()
                            }
                        }
                        assertWorkerThread(renderThread)

                        val reopened = boundary.withWorker {
                            checkNotNull(renderer.openSession(fixture.uri))
                        }
                        try {
                            val bitmap = boundary.withWorker {
                                reopened.renderPageBitmap(
                                    pageIndex = 0,
                                    viewportWidthPx = QUALIFICATION_VIEWPORT_WIDTH_PX,
                                    viewportHeightPx = QUALIFICATION_VIEWPORT_HEIGHT_PX
                                )
                            }
                            assertNotNull(bitmap)
                            assertBitmapWithinBudget(checkNotNull(bitmap))
                            checkNotNull(bitmap).recycle()
                        } finally {
                            boundary.withWorker { reopened.close() }
                        }
                    } finally {
                        renderer.close()
                    }
                }
            }
        }
    }

    @Test
    fun bitmapCache_recyclesAdmissionEvictionClearAndRejectionExactlyOnce() {
        val first = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val third = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val rejected = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val alreadyRecycled = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        alreadyRecycled.recycle()

        val releaseCounts = IdentityHashMap<Bitmap, Int>()
        fun ownerFor(bitmap: Bitmap): Stage7OwnedResource<Bitmap> {
            val owner = Stage7ResourceOwner<Bitmap> { value ->
                releaseCounts[value] = (releaseCounts[value] ?: 0) + 1
                if (!value.isRecycled) value.recycle()
            }
            return owner.owned(bitmap)
        }

        val bytes = first.allocationByteCount.toLong()
        val cache = Stage7BitmapCache(maxTotalBytes = bytes * 2L)
        val firstKey = Stage7CacheKey("fixture-document", "page-0")
        val secondKey = Stage7CacheKey("fixture-document", "page-1")
        val thirdKey = Stage7CacheKey("fixture-document", "page-2")
        val rejectedKey = Stage7CacheKey("fixture-document", "rejected")
        val invalidKey = Stage7CacheKey("fixture-document", "invalid")

        try {
            assertEquals(ByteAwareCachePutResult.ACCEPTED, cache.putOwned(firstKey, ownerFor(first)))
            assertEquals(ByteAwareCachePutResult.ACCEPTED, cache.putOwned(secondKey, ownerFor(second)))
            assertTrue(cache.entries.values.all { !it.isRecycled })

            // Touch the first entry so the second is the deterministic LRU victim.
            assertEquals(first, cache.get(firstKey))
            assertEquals(ByteAwareCachePutResult.ACCEPTED, cache.putOwned(thirdKey, ownerFor(third)))
            assertFalse(cache.contains(secondKey))
            // Accepted ownership transfers to the cache. The producer owner
            // callback therefore remains untouched; the cache's own release
            // path is observed through the platform bitmap state below.
            assertEquals(0, releaseCounts[second] ?: 0)
            assertTrue(second.isRecycled)
            assertTrue(cache.entries.values.all { !it.isRecycled })

            val firstLease = checkNotNull(cache.acquire(firstKey))
            val thirdLease = checkNotNull(cache.acquire(thirdKey))
            val rejectedOwner = ownerFor(rejected)
            assertEquals(
                ByteAwareCachePutResult.REJECTED_BUDGET,
                cache.putOwned(rejectedKey, rejectedOwner)
            )
            assertFalse(cache.contains(rejectedKey))
            assertTrue(cache.entries.values.all { !it.isRecycled })

            // Rejected ownership remains with the producer until it closes it.
            assertEquals(0, releaseCounts[rejected] ?: 0)
            rejectedOwner.close()
            assertEquals(1, releaseCounts[rejected])
            ownerFor(alreadyRecycled).let { invalidOwner ->
                assertEquals(
                    ByteAwareCachePutResult.REJECTED_INVALID_BYTES,
                    cache.putOwned(invalidKey, invalidOwner)
                )
                invalidOwner.close()
            }
            assertEquals(1, releaseCounts[alreadyRecycled])
            assertFalse(cache.contains(invalidKey))

            // Clearing retires leased entries but does not recycle them until
            // the consumer leases have released their ownership.
            cache.clear()
            assertTrue(cache.entries.isEmpty())
            assertFalse(first.isRecycled)
            assertFalse(third.isRecycled)
            firstLease.close()
            thirdLease.close()
            assertEquals(0, releaseCounts[first] ?: 0)
            assertEquals(0, releaseCounts[third] ?: 0)
            assertTrue(first.isRecycled)
            assertTrue(third.isRecycled)
        } finally {
            cache.close()
            cache.close()
            if (!first.isRecycled) first.recycle()
            if (!second.isRecycled) second.recycle()
            if (!third.isRecycled) third.recycle()
            if (!rejected.isRecycled) rejected.recycle()
            if (!alreadyRecycled.isRecycled) alreadyRecycled.recycle()
        }

        assertEquals(0, releaseCounts[first] ?: 0)
        assertEquals(0, releaseCounts[second] ?: 0)
        assertEquals(0, releaseCounts[third] ?: 0)
        assertEquals(1, releaseCounts[rejected])
        assertEquals(1, releaseCounts[alreadyRecycled])
    }

    @Test
    fun realAndroidPhotoDecoder_samplesWithinBudgetOnWorker_andReleasesExactlyOnce() = runBlocking {
        val fixtures = listOf(
            PhotoFixtureSpec(
                assetPath = "stage7/photos/high_resolution_phone_photo.jpg",
                sourceWidth = 4032,
                sourceHeight = 3024,
                requiresSampling = true
            ),
            PhotoFixtureSpec(
                assetPath = "stage7/photos/small_valid_photo.jpg",
                sourceWidth = 3264,
                sourceHeight = 2448,
                requiresSampling = false
            )
        )
        val bytesByFixture = fixtures.associateWith { fixture ->
            testContext.assets.open(fixture.assetPath).use { input -> input.readBytes() }
        }
        val boundary = Stage7WorkerResourceBoundary(
            workerDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.Main.immediate
        )

        withFailFastStage7ThreadPolicy {
            fixtures.forEach { fixture ->
                val recordingDecoder = RecordingPhotoImageDecoder(AndroidPhotoImageDecoder)
                var decodeThread: Thread? = null
                val validated = boundary.withWorker {
                    decodeThread = Thread.currentThread()
                    validatePhotoBytes(
                        bytesByFixture.getValue(fixture),
                        imageProbe = AndroidPhotoDecodeProbe(recordingDecoder)
                    )
                }
                val bounds = checkNotNull(recordingDecoder.sourceBounds)
                val requestedSample = checkNotNull(recordingDecoder.requestedSample)
                val decodedWidth = recordingDecoder.decodedWidth
                val decodedHeight = recordingDecoder.decodedHeight
                val plan = checkNotNull(
                    BitmapBudgetPolicy.photoDecodePlan(bounds.width, bounds.height)
                )
                val minDecodedWidth = maxOf(1, bounds.width / requestedSample)
                val maxDecodedWidth = (bounds.width + requestedSample - 1) / requestedSample
                val minDecodedHeight = maxOf(1, bounds.height / requestedSample)
                val maxDecodedHeight = (bounds.height + requestedSample - 1) / requestedSample

                assertEquals(fixture.sourceWidth, bounds.width)
                assertEquals(fixture.sourceHeight, bounds.height)
                assertEquals(fixture.requiresSampling, requestedSample > 1)
                assertEquals(plan.inSampleSize, requestedSample)
                assertTrue(decodedWidth in minDecodedWidth..maxDecodedWidth)
                assertTrue(decodedHeight in minDecodedHeight..maxDecodedHeight)
                assertTrue(decodedWidth <= plan.target.width)
                assertTrue(decodedHeight <= plan.target.height)
                assertTrue(recordingDecoder.decodedIsArgb8888)
                assertTrue(recordingDecoder.allocationBytes > 0L)
                assertTrue(
                    recordingDecoder.allocationBytes <= BitmapBudgetPolicy.MAX_BITMAP_BYTES
                )
                assertEquals(1, recordingDecoder.releaseCount)
                assertEquals(fixture.sourceWidth, validated.descriptor.width)
                assertEquals(fixture.sourceHeight, validated.descriptor.height)
                assertWorkerThread(decodeThread)
                Log.i(
                    "Stage7Qualification",
                    "photo=${fixture.assetPath} bytes=${bytesByFixture.getValue(fixture).size} " +
                        "source=${bounds.width}x${bounds.height} " +
                        "sample=$requestedSample decoded=${decodedWidth}x${decodedHeight} " +
                        "range=${minDecodedWidth}..${maxDecodedWidth}x${minDecodedHeight}..${maxDecodedHeight} " +
                        "plannedTarget=${plan.target.width}x${plan.target.height} " +
                        "config=ARGB_8888 allocation=${recordingDecoder.allocationBytes} " +
                        "maxAllocation=${BitmapBudgetPolicy.MAX_BITMAP_BYTES} " +
                        "releaseCount=${recordingDecoder.releaseCount} " +
                        "worker=${decodeThread?.name}"
                )
            }
        }
    }

    @Test
    fun androidOcrFactory_pdfBoxExtractsFiniteBoxesFromCroppedRotatedFixture() = runBlocking {
        withFixture("stage7/pdfs/cropped-rotated/embedded_text_crop_offset_rotate.pdf") { fixture ->
            withFailFastStage7ThreadPolicy {
                val boundary = Stage7WorkerResourceBoundary(
                    workerDispatcher = Dispatchers.IO,
                    mainDispatcher = Dispatchers.Main.immediate
                )
                val factory = AndroidOcrSessionResourceFactory(targetContext)
                var graph: OcrSessionResourceGraph? = null
                try {
                    graph = boundary.withWorker { factory.open(tokenFor(fixture.uri)) }
                    assertEquals(1, boundary.withWorker { checkNotNull(graph).pageCount() })
                    val boxes = boundary.withWorker { checkNotNull(graph).extractEmbeddedText(0) }
                    assertTrue("cropped/rotated fixture should expose embedded text", boxes.isNotEmpty())
                    assertValidNormalizedBoxes(boxes)
                } finally {
                    graph?.let { resourceGraph ->
                        boundary.withWorker { resourceGraph.close() }
                    }
                }
            }
        }
    }

    @Test
    fun androidOcrFactory_realMlKitRecognizesKnownTextInScannedFixtureOnWorker() = runBlocking {
        withFixture("stage7/pdfs/scanned/scanned_text_fixture.pdf") { fixture ->
            withFailFastStage7ThreadPolicy {
                val boundary = Stage7WorkerResourceBoundary(
                    workerDispatcher = Dispatchers.IO,
                    mainDispatcher = Dispatchers.Main.immediate
                )
                val factory = AndroidOcrSessionResourceFactory(targetContext)
                var graph: OcrSessionResourceGraph? = null
                var openThread: Thread? = null
                var embeddedThread: Thread? = null
                var recognitionThread: Thread? = null
                var closeThread: Thread? = null
                try {
                    graph = boundary.withWorker {
                        openThread = Thread.currentThread()
                        factory.open(tokenFor(fixture.uri))
                    }
                    val embedded = boundary.withWorker {
                        embeddedThread = Thread.currentThread()
                        checkNotNull(graph).extractEmbeddedText(0)
                    }
                    assertTrue("image-only fixture must not have embedded text", embedded.isEmpty())

                    val boxes = boundary.withWorker {
                        recognitionThread = Thread.currentThread()
                        checkNotNull(graph).recognizePage(0)
                    }
                    assertTrue(
                        "known text-bearing scanned fixture should produce OCR boxes",
                        boxes.isNotEmpty()
                    )
                    assertValidNormalizedBoxes(boxes)
                    val recognizedText = boxes.joinToString(" ") { it.text }.uppercase(Locale.ROOT)
                    assertTrue(
                        "OCR output should contain a known fixture marker",
                        recognizedText.contains("STAGE") || recognizedText.contains("OCR")
                    )
                } finally {
                    graph?.let { resourceGraph ->
                        boundary.withWorker {
                            closeThread = Thread.currentThread()
                            resourceGraph.close()
                        }
                    }
                }
                assertWorkerThread(openThread)
                assertWorkerThread(embeddedThread)
                assertWorkerThread(recognitionThread)
                assertWorkerThread(closeThread)
            }
        }
    }

    @Test
    fun cancellation_waitsForRealRecognitionTerminalBeforeBitmapRelease_andSessionCloseJoinFinishes() = runBlocking {
        withFixture("stage7/pdfs/scanned/scanned_image_only.pdf") { fixture ->
            withFailFastStage7ThreadPolicy {
                val boundary = Stage7WorkerResourceBoundary(
                    workerDispatcher = Dispatchers.IO,
                    mainDispatcher = Dispatchers.Main.immediate
                )
                val recognitionStarted = CompletableDeferred<Unit>()
                val terminalWaiting = CompletableDeferred<Unit>()
                val terminalGate = CompletableDeferred<Unit>()
                val terminalCompleted = CompletableDeferred<Unit>()
                val recognitionAwaitGate = CompletableDeferred<Unit>()
                val capturedBitmap = CompletableDeferred<Bitmap>()
                val factory = AndroidOcrSessionResourceFactory(targetContext) { _, image ->
                    val bitmap = checkNotNull(image.bitmapInternal) {
                        "bitmap-backed InputImage was expected"
                    }
                    capturedBitmap.complete(bitmap)
                    GatedCancellationRecognitionTask(
                        bitmap = bitmap,
                        recognitionStarted = recognitionStarted,
                        recognitionAwaitGate = recognitionAwaitGate,
                        terminalWaiting = terminalWaiting,
                        terminalGate = terminalGate,
                        terminalCompleted = terminalCompleted
                    )
                }

                var graph: OcrSessionResourceGraph? = null
                var sessionClosed = false
                try {
                    val sessionToken = tokenFor(fixture.uri)
                    graph = boundary.withWorker { factory.open(sessionToken) }
                    val session = OcrSession(sessionToken, checkNotNull(graph))
                    val operation = async {
                        boundary.withWorker { session.pageOcr(0) }
                    }

                    recognitionStarted.await()
                    val bitmap = capturedBitmap.await()
                    val close = async {
                        boundary.withWorker { session.closeAndJoin() }
                    }
                    terminalWaiting.await()

                    assertFalse("close/join must wait for task terminal state", close.isCompleted)
                    assertFalse("bitmap must remain owned while task is non-terminal", bitmap.isRecycled)

                    terminalGate.complete(Unit)
                    terminalCompleted.await()
                    val observed = runCatching { operation.await() }.exceptionOrNull()
                    close.await()
                    sessionClosed = true

                    assertTrue("caller cancellation must remain CancellationException", observed is CancellationException)
                    assertTrue("bitmap should be released after terminal completion", bitmap.isRecycled)
                } finally {
                    // If setup fails before OcrSession owns the graph, close the
                    // graph here; normal execution closes it through closeAndJoin.
                    if (!sessionClosed) {
                        graph?.let { resourceGraph ->
                            if (!terminalGate.isCompleted) terminalGate.complete(Unit)
                            boundary.withWorker { resourceGraph.close() }
                        }
                    }
                }
            }
        }
    }

    private fun assertBitmapWithinBudget(bitmap: Bitmap) {
        assertFalse(bitmap.isRecycled)
        assertTrue(bitmap.width > 0)
        assertTrue(bitmap.height > 0)
        assertTrue(bitmap.width <= BitmapBudgetPolicy.MAX_BITMAP_DIMENSION_PX)
        assertTrue(bitmap.height <= BitmapBudgetPolicy.MAX_BITMAP_DIMENSION_PX)
        assertTrue(bitmap.width.toLong() * bitmap.height.toLong() <= BitmapBudgetPolicy.MAX_BITMAP_PIXELS)
        assertTrue(bitmap.config == Bitmap.Config.ARGB_8888)
        assertNotNull(
            BitmapBudgetPolicy.actualAllocationPlan(
                widthPx = bitmap.width,
                heightPx = bitmap.height,
                actualAllocationBytes = bitmap.allocationByteCount.toLong()
            )
        )
        assertTrue(bitmap.allocationByteCount.toLong() <= BitmapBudgetPolicy.MAX_BITMAP_BYTES)
    }

    private fun assertValidNormalizedBoxes(boxes: List<OcrBox>) {
        assertTrue("OCR output must contain at least one box", boxes.isNotEmpty())
        boxes.forEach { box ->
            assertTrue(box.text.isNotBlank())
            val rect = box.rectN
            listOf(rect.left, rect.top, rect.right, rect.bottom).forEach { value ->
                assertTrue(value.isFinite())
            }
            assertTrue(rect.left >= 0f)
            assertTrue(rect.top >= 0f)
            assertTrue(rect.right <= 1f)
            assertTrue(rect.bottom <= 1f)
            assertTrue(rect.right > rect.left)
            assertTrue(rect.bottom > rect.top)
        }
    }

    private fun assertWorkerThread(thread: Thread?) {
        assertNotNull(thread)
        assertNotSame(Looper.getMainLooper().thread, thread)
    }

    private suspend fun <T> withFailFastStage7ThreadPolicy(block: suspend () -> T): T {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalTestThreadPolicy = StrictMode.getThreadPolicy()
        var originalMainThreadPolicy: StrictMode.ThreadPolicy? = null
        var mainPolicyCaptured = false
        var testPolicyInstallAttempted = false
        var mainPolicyInstallAttempted = false
        var primaryFailure: Throwable? = null

        try {
            instrumentation.runOnMainSync {
                originalMainThreadPolicy = StrictMode.getThreadPolicy()
                mainPolicyCaptured = true
            }

            val failFastPolicy = StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyDeath()
                .build()

            testPolicyInstallAttempted = true
            StrictMode.setThreadPolicy(failFastPolicy)
            instrumentation.runOnMainSync {
                mainPolicyInstallAttempted = true
                StrictMode.setThreadPolicy(failFastPolicy)
            }
            return block()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val restorationFailures = ArrayList<Throwable>(2)

            if (testPolicyInstallAttempted) {
                try {
                    StrictMode.setThreadPolicy(originalTestThreadPolicy)
                } catch (failure: Throwable) {
                    restorationFailures += failure
                }
            }

            if (mainPolicyCaptured && mainPolicyInstallAttempted) {
                try {
                    val savedMainThreadPolicy = checkNotNull(originalMainThreadPolicy)
                    instrumentation.runOnMainSync {
                        StrictMode.setThreadPolicy(savedMainThreadPolicy)
                    }
                } catch (failure: Throwable) {
                    restorationFailures += failure
                }
            }

            if (restorationFailures.isNotEmpty()) {
                val restorationFailure = restorationFailures.first()
                restorationFailures.drop(1).forEach(restorationFailure::addSuppressed)
                if (primaryFailure == null) {
                    throw restorationFailure
                }
                primaryFailure?.addSuppressed(restorationFailure)
            }
        }
    }

    private fun tokenFor(uri: Uri): DocumentSessionToken = DocumentSessionToken(
        documentId = DocumentId.new(),
        sourceUri = uri.toString(),
        sourceFingerprint = null,
        generation = 1L
    )

    private suspend fun withFixture(assetPath: String, block: suspend (FixtureFile) -> Unit) {
        val root = File(targetContext.cacheDir, "stage7-qualification-${System.nanoTime()}")
        check(root.mkdirs()) { "could not create test fixture directory" }
        val file = File(root, assetPath.substringAfterLast('/'))
        testContext.assets.open(assetPath).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val uri = FileProvider.getUriForFile(
            targetContext,
            "${targetContext.packageName}.fileprovider",
            file
        )
        val fixture = FixtureFile(uri, file, root)
        try {
            targetContext.contentResolver.openFileDescriptor(uri, "r")?.use { }
                ?: error("content resolver could not open $assetPath")
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private data class FixtureSpec(val assetPath: String, val pageCount: Int)

    private data class PhotoFixtureSpec(
        val assetPath: String,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val requiresSampling: Boolean
    )

    private class FixtureFile(
        val uri: Uri,
        private val file: File,
        private val root: File
    ) : Closeable {
        override fun close() {
            file.delete()
            root.delete()
        }
    }

    private class GatedCancellationRecognitionTask(
        private val bitmap: Bitmap,
        private val recognitionStarted: CompletableDeferred<Unit>,
        private val recognitionAwaitGate: CompletableDeferred<Unit>,
        private val terminalWaiting: CompletableDeferred<Unit>,
        private val terminalGate: CompletableDeferred<Unit>,
        private val terminalCompleted: CompletableDeferred<Unit>
    ) : OcrRecognitionTask<Text> {
        override suspend fun await(): Text {
            recognitionStarted.complete(Unit)
            recognitionAwaitGate.await()
            error("recognition should be canceled before returning a result")
        }

        override suspend fun awaitTerminal(): Text {
            terminalWaiting.complete(Unit)
            terminalGate.await()
            check(!bitmap.isRecycled) { "bitmap was released before recognition became terminal" }
            terminalCompleted.complete(Unit)
            error("synthetic terminal failure after the task reached terminal state")
        }
    }

    private class RecordingPhotoImageDecoder(
        private val delegate: PhotoImageDecoder
    ) : PhotoImageDecoder {
        var sourceBounds: PhotoImageBounds? = null
        var requestedSample: Int? = null
        var decodedWidth: Int = 0
        var decodedHeight: Int = 0
        var allocationBytes: Long = 0L
        var decodedIsArgb8888: Boolean = false
        var releaseCount: Int = 0

        override fun decodeBounds(bytes: ByteArray): PhotoImageBounds {
            return delegate.decodeBounds(bytes).also { sourceBounds = it }
        }

        override fun decode(bytes: ByteArray, inSampleSize: Int): DecodedPhotoImage? {
            requestedSample = inSampleSize
            val decoded = delegate.decode(bytes, inSampleSize) ?: return null
            decodedWidth = decoded.width
            decodedHeight = decoded.height
            allocationBytes = decoded.allocationBytes
            decodedIsArgb8888 = decoded.isArgb8888
            return DecodedPhotoImage(
                width = decoded.width,
                height = decoded.height,
                allocationBytes = decoded.allocationBytes,
                isArgb8888 = decoded.isArgb8888
            ) {
                releaseCount++
                decoded.close()
            }
        }
    }
}
