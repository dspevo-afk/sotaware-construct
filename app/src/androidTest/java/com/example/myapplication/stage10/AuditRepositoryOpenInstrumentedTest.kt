package com.example.myapplication.stage10

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage2.DocumentLoadResult
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.LocalDocumentRepository
import com.example.myapplication.stage3.AndroidDocumentSessionCallbacks
import com.example.myapplication.stage3.DocumentSwitchCoordinator
import com.example.myapplication.stage3.SwitchFailureStage
import com.example.myapplication.stage3.SwitchResult
import com.example.myapplication.stage3.TargetResolution
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real PDF resolver, durable repository and Android callback adapter into the coordinator. */
@RunWith(AndroidJUnit4::class)
class AuditRepositoryOpenInstrumentedTest {
    @Test
    fun exhaustedRecoveryNeverPublishesAnEmptySessionThroughTheAndroidAdapter() = runBlocking {
        withTimeout(30_000) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val root = Files.createTempDirectory(context.cacheDir.toPath(), "audit-repository-").toFile()
            val uri = Uri.Builder().scheme("content")
                .authority("${instrumentation.context.packageName}.stage8.fixture")
                .appendPath("stage7/pdfs/scanned/scanned_text_fixture.pdf")
                .appendQueryParameter("audit-open", UUID.randomUUID().toString()).build()
            val starts = AtomicInteger()
            fun callbacks(repository: LocalDocumentRepository) =
                AndroidDocumentSessionCallbacks.withDefaultPageLoader(
                    context = context, viewModel = BlueprintViewModel(), repository = repository,
                    onSessionEstablished = {}, onStateCleared = {}, onPageCount = { _, _ -> },
                    onRecovered = {}, onFailure = {}, onStart = { starts.incrementAndGet() },
                    cancelAndJoinWork = {}, resumeWork = {}
                )
            try {
                val repository = LocalDocumentRepository(root)
                val resolution = callbacks(repository).resolveTarget(uri.toString())
                assertTrue(resolution is TargetResolution.Resolved)
                val association = (resolution as TargetResolution.Resolved).target.association
                val snapshot = DocumentSnapshotV1(
                    schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
                    snapshotRevision = 1L, source = association.source, pages = emptyMap()
                )
                assertTrue(repository.save(association, snapshot) is DocumentSaveResult.Saved)
                assertTrue(repository.save(association, snapshot.copy(snapshotRevision = 2L)) is DocumentSaveResult.Saved)
                withContext(Dispatchers.IO) {
                    repository.currentSnapshotFile(association.documentId).writeText("corrupt-current")
                    repository.previousSnapshotFile(association.documentId).writeText("corrupt-previous")
                }

                repeat(3) {
                    val reopenedRepository = LocalDocumentRepository(root)
                    val parent = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                    val coordinator = DocumentSwitchCoordinator(
                        callbacks(reopenedRepository), parentScope = parent,
                        coordinatorDispatcher = Dispatchers.Main.immediate
                    )
                    try {
                        val result = withContext(Dispatchers.Main.immediate) { coordinator.switchTo(uri.toString()) }
                        assertTrue("corrupt accepted state must fail on every open: $result", result is SwitchResult.Failed)
                        assertEquals(SwitchFailureStage.TARGET_LOAD, (result as SwitchResult.Failed).failure.stage)
                        assertNull(coordinator.currentSession())
                        assertEquals("no successful empty session may start background work", 0, starts.get())
                        assertTrue(reopenedRepository.load(association) is DocumentLoadResult.Failed)
                    } finally {
                        coordinator.closeAndJoin()
                        parent.cancel()
                    }
                }
                val evidence = repository.snapshotQuarantineDirectory(association.documentId)
                    .listFiles().orEmpty().filter { it.isFile }.map { it.readText() }
                assertTrue(evidence.contains("corrupt-current"))
                assertTrue(evidence.contains("corrupt-previous"))
            } finally {
                check(root.deleteRecursively())
            }
        }
    }
}
