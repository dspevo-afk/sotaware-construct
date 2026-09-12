package com.example.myapplication.stage6

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PdfExportOutcome { SUCCEEDED, FAILED, EXPIRED, CANCELLED }
data class PdfExportNotice(val operationId: String, val outcome: PdfExportOutcome)

/** One bounded frozen PDF, retained with the ViewModel rather than saved in a Bundle. */
internal class PdfExportRequestOwner(
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    @Volatile private var temporaryOwner: PdfExportTemporaryOwner? = null
) {
    private enum class Phase { PREPARING, PICKER, WRITING }
    private data class Slot(val id: String, var phase: Phase, var file: File? = null)
    private val lock = Any()
    private var slot: Slot? = null
    private var closed = false
    private var running: Job? = null
    private var lastCompletedId: String? = null
    private val mutableBusy = MutableStateFlow(false)
    private val mutableNotice = MutableStateFlow<PdfExportNotice?>(null)
    val busy = mutableBusy.asStateFlow()
    val notice = mutableNotice.asStateFlow()

    fun begin(): String? = synchronized(lock) {
        if (closed || slot != null) return@synchronized null
        UUID.randomUUID().toString().also {
            slot = Slot(it, Phase.PREPARING)
            mutableBusy.value = true
        }
    }

    /**
     * Binds the owner to the application cache after the ViewModel is created.
     * This keeps the existing no-argument ViewModel construction while making
     * production result staging use the dedicated directory.
     */
    fun configureTemporaryDirectory(cacheDirectory: File) = synchronized(lock) {
        check(!closed) { "cannot configure PDF export storage after close" }
        val current = temporaryOwner
        if (current == null) {
            temporaryOwner = PdfExportTemporaryOwner(cacheDirectory)
        } else {
            check(current.directoryForTests.canonicalFile ==
                File(cacheDirectory, PDF_EXPORT_TEMPORARY_DIRECTORY).canonicalFile) {
                "PDF export temporary owner is already bound to another directory"
            }
        }
    }

    /** Returns the shared source/result owner while this request is preparing. */
    fun temporaryOwnerFor(id: String): PdfExportTemporaryOwner = synchronized(lock) {
        val current = slot
        check(!closed && current?.id == id && current.phase == Phase.PREPARING) {
            "PDF export request is not preparing"
        }
        requireNotNull(temporaryOwner) {
            "a dedicated PDF export temporary owner is required"
        }
    }

    /**
     * Allocates the result staging file before the caller starts rendering.
     * Production callers must use this seam so the result shares ownership
     * with verified source copies and survives process-death reconciliation.
     */
    fun createResultFile(id: String): File = synchronized(lock) {
        val current = slot ?: error("PDF export request is not preparing a result")
        check(!closed && current.id == id && current.phase == Phase.PREPARING) {
            "PDF export request is not preparing a result"
        }
        check(current.file == null) {
            "PDF export result staging file is already allocated"
        }
        val owner = requireNotNull(temporaryOwner) {
            "a dedicated PDF export temporary owner is required"
        }
        owner.createResultFile(id).also { current.file = it }
    }

    /** Startup seam for the host to reconcile abandoned source/result files. */
    fun reconcileTemporaryFiles(
        nowMillis: Long = System.currentTimeMillis()
    ): PdfExportTemporaryReconciliation {
        // Snapshot under the request lock, then release it before entering
        // the directory owner. Completion cleanup takes the inverse path
        // (directory owner, then request lock), so holding both would permit a
        // close/reconcile deadlock.
        val state = synchronized(lock) {
            temporaryOwner to slot?.id?.let(::setOf).orEmpty()
        }
        return state.first?.reconcile(
            nowMillis = nowMillis,
            activeRequestIds = state.second
        ) ?: PdfExportTemporaryReconciliation(emptySet(), emptySet())
    }

    /** Transfers the completed private file only after the worker has closed it. */
    fun prepared(id: String, file: File): Boolean = synchronized(lock) {
        val current = slot
        val owner = temporaryOwner
        if (closed || current?.id != id || current.phase != Phase.PREPARING ||
            (owner != null &&
                (current.file?.canonicalFile != file.canonicalFile ||
                    !owner.isOwnedResultFile(id, file)))
        ) false
        else { current.file = file; current.phase = Phase.PICKER; true }
    }

    /** The preparing caller still owns and deletes its local file on rejection. */
    fun abandon(id: String, outcome: PdfExportOutcome) {
        val file = synchronized(lock) {
            if (slot?.id == id && slot?.phase != Phase.WRITING) {
                val file = slot?.file
                finishLocked(id, outcome)
                file
            } else {
                null
            }
        }
        file?.let(::releaseFile)
    }

    fun acknowledge(id: String) = synchronized(lock) {
        if (mutableNotice.value?.operationId == id) mutableNotice.value = null
    }

    fun complete(id: String?, openOutput: (() -> OutputStream?)?): Job {
        return synchronized(lock) {
            if (id != null && (id == lastCompletedId || slot?.let { it.id == id && it.phase == Phase.WRITING } == true)) {
                return completedJob()
            }
            val current = slot
            if (id == null || closed || current?.id != id || current.phase != Phase.PICKER) {
                mutableNotice.value = PdfExportNotice(id ?: UUID.randomUUID().toString(),
                    if (openOutput == null) PdfExportOutcome.CANCELLED else PdfExportOutcome.EXPIRED)
                return completedJob()
            }
            current.phase = Phase.WRITING
            val file = requireNotNull(current.file)
            val requestId = id
            // Create and start the job while holding the owner lock. This
            // makes the WRITING slot and its cleanup job visible atomically to
            // close(), including the narrow handoff before the first
            // suspension.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var outcome = PdfExportOutcome.FAILED
                try {
                    if (openOutput == null) outcome = PdfExportOutcome.CANCELLED
                    else withContext(workerDispatcher) {
                        copyPreparedPdf(file, openOutput)
                        outcome = PdfExportOutcome.SUCCEEDED
                    }
                } catch (cancelled: CancellationException) {
                    outcome = PdfExportOutcome.CANCELLED
                    throw cancelled
                } catch (_: Exception) {
                    outcome = PdfExportOutcome.FAILED
                } finally {
                    withContext(NonCancellable + workerDispatcher) {
                        try { releaseFile(file) }
                        catch (_: Exception) { outcome = PdfExportOutcome.FAILED }
                        synchronized(lock) { finishLocked(requestId, outcome) }
                    }
                }
            }.also { job -> if (slot?.id == requestId) running = job }
        }
    }

    private fun finishLocked(id: String, outcome: PdfExportOutcome) {
        if (slot?.id != id) return
        slot = null
        running = null
        lastCompletedId = id
        mutableBusy.value = false
        mutableNotice.value = PdfExportNotice(id, outcome)
    }

    /** ViewModel terminal cleanup; Activity recreation does not call this. */
    fun close(): Job {
        val owned = synchronized(lock) {
            closed = true
            val pending = slot?.takeIf { it.phase != Phase.WRITING }?.file
            if (slot?.phase != Phase.WRITING) { slot = null; mutableBusy.value = false }
            pending to running
        }
        return scope.launch(NonCancellable + workerDispatcher) {
            owned.second?.cancelAndJoin()
            owned.first?.let(::releaseFile)
        }
    }

    private fun releaseFile(file: File) {
        val owner = temporaryOwner
        if (owner == null) Files.deleteIfExists(file.toPath())
        else owner.release(file)
    }

    private fun completedJob(): Job = Job().apply { complete() }
}

/** Success includes destination close. A partial external destination is never called successful. */
internal suspend fun copyPreparedPdf(file: File, openOutput: () -> OutputStream?) {
    currentCoroutineContext().ensureActive()
    val expected = file.length()
    if (expected <= 0L) throw IOException("Prepared PDF is unavailable")
    file.inputStream().use { input ->
        (openOutput() ?: throw IOException("PDF destination is unavailable")).use { output ->
            val buffer = ByteArray(64 * 1024)
            var count = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) throw IOException("Prepared PDF made no progress")
                if (read.toLong() > expected - count) throw IOException("Prepared PDF changed")
                output.write(buffer, 0, read)
                count += read
            }
            if (count != expected) throw IOException("Prepared PDF is incomplete")
        }
    }
}
