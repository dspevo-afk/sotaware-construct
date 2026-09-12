package com.example.myapplication.stage10

import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage5.*
import com.example.myapplication.stage7.Stage7WorkerResourceBoundary
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraPreparationInstrumentedTest {
    @Test fun realJournalPreparationRunsOffMainAndMainRemainsResponsive() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "camera-dispatch-test-").toFile()
        val violations = CopyOnWriteArrayList<Throwable>()
        var operationId: String? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                val previous = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites()
                    .penaltyListener({ runnable -> runnable.run() }) { violations += it }.build())
                try {
                    val request = CameraCaptureOperationRequest(UUID.randomUUID().toString(), DocumentId.new(),
                        "content://synthetic/dispatch.pdf", SourceFingerprint.fromBytes(byteArrayOf(1)),
                        1L, 0, UUID.randomUUID().toString())
                    val record = prepareCameraOperationOnWorker(request, Stage7WorkerResourceBoundary(), { operationId = it }) {
                        assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                        val heartbeat = CountDownLatch(1)
                        Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
                        assertTrue("Main was blocked by preparation", heartbeat.await(5, TimeUnit.SECONDS))
                        CameraCaptureStore(directory).use { store -> store.prepareOperation(it) }
                    }
                    assertEquals(record.operationId, operationId)
                } finally { StrictMode.setThreadPolicy(previous) }
            }
            assertTrue("Main performed disk work: $violations", violations.isEmpty())
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                operationId?.let { id -> CameraCaptureStore(directory).use { it.discardPrepared(id); it.cleanup(id) } }
                directory.deleteRecursively()
            }
        }
    }
}
