package com.example.myapplication.stage10

import com.example.myapplication.stage6.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PdfExportRequestOwnerTest {
    @Test fun retainedRequestWritesFrozenBytesOnceAndIncludesDestinationClose() = withOwner { owner, dir ->
        val id = requireNotNull(owner.begin())
        val file = artifact(dir)
        assertTrue(owner.prepared(id, file))
        val output = Sink()
        owner.complete(id) { output }.join()
        assertArrayEquals("frozen PDF".toByteArray(), output.bytes.toByteArray())
        assertTrue(output.closed)
        assertFalse(file.exists()); assertFalse(owner.busy.value)
        assertEquals(PdfExportOutcome.SUCCEEDED, owner.notice.value?.outcome)
        owner.complete(id) { fail("duplicate result opened output"); output }.join()
    }

    @Test fun missingProcessOwnerReportsExpiredWithoutOpeningDestination() = withOwner { owner, _ ->
        var opens = 0
        owner.complete(java.util.UUID.randomUUID().toString()) { opens++; Sink() }.join()
        assertEquals(0, opens)
        assertEquals(PdfExportOutcome.EXPIRED, owner.notice.value?.outcome)
        owner.complete(null) { opens++; Sink() }.join()
        assertEquals(0, opens)
    }

    @Test fun staleIdCannotConsumeNewRequestAndOnlyOneRequestCanWait() = withOwner { owner, dir ->
        val id = requireNotNull(owner.begin())
        val file = artifact(dir)
        assertNull(owner.begin())
        assertTrue(owner.prepared(id, file))
        owner.complete("stale-id") { fail("stale output opened"); Sink() }.join()
        assertTrue(owner.busy.value); assertTrue(file.exists())
        owner.complete(id) { Sink() }.join()
        assertEquals(PdfExportOutcome.SUCCEEDED, owner.notice.value?.outcome)
    }

    @Test fun pickerCancellationReleasesPreparedFile() = withOwner { owner, dir ->
        val id = requireNotNull(owner.begin()); val file = artifact(dir)
        owner.prepared(id, file)
        owner.complete(id, null).join()
        assertFalse(file.exists()); assertFalse(owner.busy.value)
        assertEquals(PdfExportOutcome.CANCELLED, owner.notice.value?.outcome)
    }

    @Test fun failedNullWriteAndCloseNeverReportSuccess() = withOwner { owner, dir ->
        for (mode in 0..2) {
            val id = requireNotNull(owner.begin()); val file = artifact(dir)
            owner.prepared(id, file)
            owner.complete(id) { if (mode == 0) null else Sink(failWrite = mode == 1, failClose = mode == 2) }.join()
            assertEquals(PdfExportOutcome.FAILED, owner.notice.value?.outcome)
            assertFalse(file.exists()); assertFalse(owner.busy.value)
        }
    }

    @Test fun closedOwnerCannotAcceptARequestOrKeepWaitingArtifact() = withOwner { owner, dir ->
        val id = requireNotNull(owner.begin()); val file = artifact(dir)
        owner.prepared(id, file)
        owner.close().join()
        assertFalse(file.exists()); assertNull(owner.begin())
        owner.complete(id) { fail("closed owner wrote"); Sink() }.join()
        assertEquals(PdfExportOutcome.EXPIRED, owner.notice.value?.outcome)
    }

    @Test fun cancelledParentStillCleansClaimedArtifact() = runTest {
        val dir = Files.createTempDirectory("construct-pdf-cancel-test-").toFile()
        val parent = SupervisorJob()
        val owner = PdfExportRequestOwner(CoroutineScope(parent + StandardTestDispatcher(testScheduler)), StandardTestDispatcher(testScheduler))
        try {
            val id = requireNotNull(owner.begin()); val file = artifact(dir)
            owner.prepared(id, file); parent.cancel()
            owner.complete(id) { fail("cancelled owner opened output"); Sink() }.join()
            assertFalse(file.exists()); assertFalse(owner.busy.value)
            assertEquals(PdfExportOutcome.CANCELLED, owner.notice.value?.outcome)
        } finally { owner.close().join(); dir.deleteRecursively() }
    }

    @Test fun dedicatedOwnerReconcilesAbandonedFilesAndProtectsActiveRequest() = runTest {
        val activeCache = Files.createTempDirectory("construct-pdf-active-cache-").toFile()
        val activeTemporary = PdfExportTemporaryOwner(activeCache, abandonedAfterMillis = 100L)
        val owner = PdfExportRequestOwner(this, StandardTestDispatcher(testScheduler), activeTemporary)
        val abandonedCache = Files.createTempDirectory("construct-pdf-restart-cache-").toFile()
        try {
            val id = requireNotNull(owner.begin())
            val active = owner.createResultFile(id).apply { writeText("active") }
            Files.setLastModifiedTime(active.toPath(), FileTime.fromMillis(1L))
            assertTrue(owner.reconcileTemporaryFiles(nowMillis = 1_000L).retainedFileNames.contains(active.name))
            assertTrue(active.exists())

            val abandonedTemporary = PdfExportTemporaryOwner(abandonedCache, abandonedAfterMillis = 100L)
            val abandonedRequestId = UUID.randomUUID().toString()
            Files.createDirectories(abandonedTemporary.directoryForTests.toPath())
            val abandonedSource = File(
                abandonedTemporary.directoryForTests,
                "construct-export-source-$abandonedRequestId-${UUID.randomUUID()}.pdf"
            ).apply { createNewFile(); writeText("source") }
            val abandonedResult = File(
                abandonedTemporary.directoryForTests,
                "construct-export-result-$abandonedRequestId-${UUID.randomUUID()}.pdf"
            ).apply { createNewFile(); writeText("result") }
            Files.setLastModifiedTime(abandonedSource.toPath(), FileTime.fromMillis(1L))
            Files.setLastModifiedTime(abandonedResult.toPath(), FileTime.fromMillis(1L))
            assertTrue(abandonedSource.exists())
            assertTrue(abandonedResult.exists())

            owner.abandon(id, PdfExportOutcome.CANCELLED)
            assertFalse(active.exists())
            val restarted = PdfExportTemporaryOwner(abandonedCache, abandonedAfterMillis = 100L)
            val reconciliation = restarted.reconcile(nowMillis = 1_000L)
            assertTrue(reconciliation.removedFileNames.contains(abandonedSource.name))
            assertTrue(reconciliation.removedFileNames.contains(abandonedResult.name))
            assertFalse(abandonedSource.exists())
            assertFalse(abandonedResult.exists())
        } finally {
            owner.close().join()
            activeCache.deleteRecursively()
            abandonedCache.deleteRecursively()
        }
    }

    @Test fun dedicatedOwnerSuccessFailureAndCancellationReleaseResultFiles() = runTest {
        val cache = Files.createTempDirectory("construct-pdf-terminal-cache-").toFile()
        val temporary = PdfExportTemporaryOwner(cache)
        val owner = PdfExportRequestOwner(this, StandardTestDispatcher(testScheduler), temporary)
        try {
            for (outcome in listOf(PdfExportOutcome.SUCCEEDED, PdfExportOutcome.FAILED, PdfExportOutcome.CANCELLED)) {
                val id = requireNotNull(owner.begin())
                val file = owner.createResultFile(id).apply { writeText("frozen PDF") }
                assertTrue(owner.prepared(id, file))
                when (outcome) {
                    PdfExportOutcome.SUCCEEDED -> owner.complete(id) { Sink() }.join()
                    PdfExportOutcome.FAILED -> owner.complete(id) { Sink(failWrite = true) }.join()
                    PdfExportOutcome.CANCELLED -> owner.complete(id, null).join()
                    else -> error("unexpected test outcome")
                }
                assertFalse(file.exists())
                assertFalse(owner.busy.value)
                assertEquals(outcome, owner.notice.value?.outcome)
            }
        } finally {
            owner.close().join()
            cache.deleteRecursively()
        }
    }

    @Test fun sameDirectoryOwnerPreservesAgedActiveSourceAndResult() = runTest {
        val cache = Files.createTempDirectory("construct-pdf-shared-owner-cache-").toFile()
        val requestId = UUID.randomUUID().toString()
        val first = PdfExportTemporaryOwner(cache, abandonedAfterMillis = 100L)
        try {
            val source = first.createSourceFile(requestId).apply { writeText("source") }
            val result = first.createResultFile(requestId).apply { writeText("result") }
            Files.setLastModifiedTime(source.toPath(), FileTime.fromMillis(1L))
            Files.setLastModifiedTime(result.toPath(), FileTime.fromMillis(1L))

            val second = PdfExportTemporaryOwner(cache, abandonedAfterMillis = 100L)
            val reconciliation = second.reconcile(nowMillis = 1_000L)
            assertTrue(reconciliation.retainedFileNames.contains(source.name))
            assertTrue(reconciliation.retainedFileNames.contains(result.name))
            assertTrue(source.exists())
            assertTrue(result.exists())

            first.release(source)
            first.release(result)
            assertFalse(source.exists())
            assertFalse(result.exists())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test fun dedicatedOwnerCloseReleasesResultBeforePreparedCanBeAccepted() = runTest {
        val cache = Files.createTempDirectory("construct-pdf-close-owner-cache-").toFile()
        val temporary = PdfExportTemporaryOwner(cache)
        val owner = PdfExportRequestOwner(this, StandardTestDispatcher(testScheduler), temporary)
        try {
            val id = requireNotNull(owner.begin())
            val file = owner.createResultFile(id).apply { writeText("frozen PDF") }
            owner.close().join()
            assertFalse(file.exists())
            assertFalse(owner.prepared(id, file))
            assertNull(owner.begin())
        } finally {
            owner.close().join()
            cache.deleteRecursively()
        }
    }

    @Test fun dedicatedOwnerCloseDuringWritingStillReleasesResult() = runTest {
        val cache = Files.createTempDirectory("construct-pdf-close-writing-cache-").toFile()
        val worker = StandardTestDispatcher(testScheduler)
        val temporary = PdfExportTemporaryOwner(cache)
        val owner = PdfExportRequestOwner(this, worker, temporary)
        try {
            val id = requireNotNull(owner.begin())
            val file = owner.createResultFile(id).apply { writeText("frozen PDF") }
            assertTrue(owner.prepared(id, file))
            owner.complete(id) { Sink() }
            owner.close().join()
            assertFalse(file.exists())
            assertFalse(owner.busy.value)
        } finally {
            owner.close().join()
            cache.deleteRecursively()
        }
    }

    private fun artifact(dir: File): File = Files.createTempFile(dir.toPath(), "prepared-", ".pdf").toFile().apply {
        writeText("frozen PDF")
    }
    private class Sink(val failWrite: Boolean = false, val failClose: Boolean = false) : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var closed = false
        override fun write(value: Int) {
            if (failWrite) throw IOException("write failed")
            bytes.write(value)
        }
        override fun close() {
            closed = true
            if (failClose) throw IOException("close failed")
        }
    }
    private fun withOwner(block: suspend (PdfExportRequestOwner, File) -> Unit) = runTest {
        val dir = Files.createTempDirectory("construct-pdf-owner-test-").toFile()
        val owner = PdfExportRequestOwner(this, StandardTestDispatcher(testScheduler))
        try { block(owner, dir) } finally { owner.close().join(); dir.deleteRecursively() }
    }
}
