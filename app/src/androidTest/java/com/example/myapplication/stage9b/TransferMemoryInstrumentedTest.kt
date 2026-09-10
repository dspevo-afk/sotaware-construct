package com.example.myapplication.stage9b

import android.content.ContextWrapper
import android.content.res.AssetManager
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.stage1.*
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.*
import com.example.myapplication.stage5.Stage5Limits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Measured Android supported-envelope gate, including real durable outbox loading. */
@RunWith(AndroidJUnit4::class)
class TransferMemoryInstrumentedTest {
    @Test fun fullPhotoEnvelopeUsesBoundedReadsAndReopensWithoutAggregateArrays() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixtureContext = object : ContextWrapper(context) {
            override fun getAssets(): AssetManager = instrumentation.context.assets
        }
        val directory = File(context.filesDir, "stage9b-memory-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val names = (0 until 4).associate { "large-$it.jpg" to "stage7/photos/small_valid_photo.jpg" }
        val owned = NativeFixtureAssets.fileBackedLargePhotoAssets(fixtureContext, names, Stage5Limits.MAX_PHOTO_BYTES)
        val maxRead = AtomicLong(0L)
        val handles = PhotoAssetSet.of(owned.assets.mapValues { (_, original) ->
            object : PhotoAsset {
                override val descriptor = original.descriptor
                override fun open(): InputStream = object : FilterInputStream(original.open()) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        maxRead.accumulateAndGet(length.toLong(), ::maxOf)
                        return `in`.read(buffer, offset, length)
                    }
                }
            }
        })
        val runtime = Runtime.getRuntime()
        fun usedHeap() = runtime.totalMemory() - runtime.freeMemory()
        System.gc()
        val initialHeap = usedHeap()
        val initialNative = Debug.getNativeHeapAllocatedSize()
        val peakHeap = AtomicLong(initialHeap)
        val peakNative = AtomicLong(initialNative)
        val running = AtomicBoolean(true)
        val sampler = Thread({
            while (running.get()) {
                peakHeap.accumulateAndGet(usedHeap(), ::maxOf)
                peakNative.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
                try { Thread.sleep(10L) } catch (_: InterruptedException) { break }
            }
        }, "stage9b-memory-sampler").apply { start() }
        var loadedPending: DurablePendingUpload? = null
        try {
            val source = DocumentSourceIdentityV1("content://synthetic/memory-envelope", "fixture.pdf")
            val snapshot = DocumentSnapshotV1(DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION, 1L, source,
                mapOf(0 to PageSnapshotV1(photoPins = listOf(PhotoPinSnapshotV1(.5f, .5f, "pin-memory", names.keys.toList(), emptyMap(), emptyMap())))))
            val scope = SyncScope("synthetic-account", "synthetic-root", DocumentId.new())
            val pending = DurablePendingUpload(SyncReason.PHOTO, source.sourceUri, null, 1L, null, snapshot, handles)
            val store = FileSyncMetadataStore(directory, Dispatchers.IO, null, context.filesDir)
            assertEquals(MetadataWriteResult.Committed, store.write(SyncMetadata(scope = scope, pendingUpload = pending)))
            assertTrue("metadata must not embed snapshots/photos", store.metadataFileFor(scope).length() < 64 * 1024)
            val reopened = FileSyncMetadataStore(directory, Dispatchers.IO, null, context.filesDir).read(scope)
            assertTrue(reopened.toString(), reopened is MetadataReadResult.Loaded)
            loadedPending = (reopened as MetadataReadResult.Loaded).metadata!!.pendingUpload!!
            val recovered = loadedPending!!
            assertEquals(snapshot, recovered.snapshot)
            assertEquals(handles.descriptors, recovered.photoFiles.descriptors)
            assertTrue(recovered.photoFiles.totalBytes in (Stage5Limits.MAX_TOTAL_PHOTO_BYTES - 12L)..Stage5Limits.MAX_TOTAL_PHOTO_BYTES)
            val sink = object : OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
            }
            recovered.photoFiles.values.forEach { asset -> assertEquals(asset.descriptor.byteCount, copyPhotoAsset(asset, sink)) }
            assertTrue("producer reads exceed the 64 KiB bound: ${maxRead.get()}", maxRead.get() <= 64L * 1024L)
        } finally {
            running.set(false)
            sampler.interrupt()
            sampler.join(5_000L)
            assertFalse("owned memory sampler must terminate", sampler.isAlive)
            loadedPending?.outboxLease?.close()
            owned.close()
            directory.deleteRecursively()
        }
        val heapDelta = (peakHeap.get() - initialHeap).coerceAtLeast(0L)
        val nativeDelta = (peakNative.get() - initialNative).coerceAtLeast(0L)
        println("STAGE9B_MEMORY photos=4 logicalBytes=${owned.assets.totalBytes} peakJavaDelta=$heapDelta peakNativeDelta=$nativeDelta maxRead=${maxRead.get()}")
        assertTrue("Java transfer/outbox heap delta exceeded 128 MiB: $heapDelta", heapDelta <= 128L * 1024L * 1024L)
        assertTrue("native decoder heap delta exceeded 64 MiB: $nativeDelta", nativeDelta <= 64L * 1024L * 1024L)
    }
}
