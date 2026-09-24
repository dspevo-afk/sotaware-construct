package com.example.myapplication.projects

import android.os.Process
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** The host also runs stage, force-stop, recover to prove a fresh-process restart. */
@RunWith(AndroidJUnit4::class)
class ProjectDownloadRecoveryInstrumentedTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val root get() = File(instrument.targetContext.filesDir, "audit-download-restart")
    @Test fun completeRestartMatrix() = runBlocking {
        val mode = InstrumentationRegistry.getArguments().getString("project.recovery")
        require(mode == null || mode == "stage" || mode == "recover")
        if (mode != "recover") stage()
        if (mode != "stage") {
            val originalPid = File(root, "pid").readText().toInt()
            if (mode == "recover") assertNotEquals("A new process is required", originalPid, Process.myPid())
            recover()
            assertTrue(root.deleteRecursively())
        }
    }
    private suspend fun stage() {
        check(!root.exists() && root.mkdir())
        File(root, "pid").writeText(Process.myPid().toString())
        for (phase in ProjectDownloadPhase.entries) {
            val folder = File(root, phase.name).apply { mkdir() }
            val store = ProjectLibraryStore(Storage(File(folder, "library.json")))
            val remote = Gateway()
            var identity = ""
            val downloader = ProjectDownloads(File(folder, "downloads"), store) { step, id ->
                identity = id
                if (step == phase) throw Interrupted()
            }
            try {
                downloader.download(remote, remote.root, { file ->
                    android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        android.graphics.pdf.PdfRenderer(pfd).use { assertTrue(it.pageCount > 0) }
                    }
                }, {})
                fail("Expected interruption at $phase")
            } catch (_: Interrupted) { }
            assertTrue(identity.isNotBlank())
            File(folder, "identity").writeText(identity)
        }
        println("PROJECT_RECOVERY_STAGED_PID=${Process.myPid()}")
    }

    private suspend fun recover() {
        for (phase in ProjectDownloadPhase.entries) {
            val folder = File(root, phase.name)
            val store = ProjectLibraryStore(Storage(File(folder, "library.json")))
            val base = File(folder, "downloads")
            val downloader = ProjectDownloads(base, store)
            downloader.recover()
            val projects = store.read().projects
            assertEquals(phase.name, if (phase >= ProjectDownloadPhase.VERIFIED) 1 else 0, projects.size)
            if (projects.isNotEmpty()) {
                val project = projects.single()
                assertEquals(File(folder, "identity").readText(), project.id)
                val files = downloader.readCatalog(project).filterNot { it.folder }
                assertEquals(1, files.size)
                assertArrayEquals(Gateway().bytes, downloader.pdf(project.id, files.single().id).readBytes())
            }
            assertTrue(base.list().orEmpty().none { it.startsWith(".") })
            downloader.recover()
            assertEquals(projects, store.read().projects)
        }
        println("PROJECT_RECOVERY_VERIFIED_PID=${Process.myPid()}")
    }
    private class Storage(private val file: File) : ProjectLibraryStorage {
        private val atomic = AtomicFile(file)
        override fun read(): String? = if (!file.exists() && !File(file.path + ".bak").exists()) null
            else atomic.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        override fun commit(value: String): Boolean {
            val stream = atomic.startWrite()
            try { stream.write(value.toByteArray(Charsets.UTF_8)); stream.fd.sync(); atomic.finishWrite(stream) }
            catch (failure: Exception) { atomic.failWrite(stream); throw failure }
            forceProjectDirectory(requireNotNull(file.parentFile))
            return true
        }
    }
    private inner class Gateway : DriveProjectGateway {
        override val accountId = "synthetic-recovery-account"
        val bytes = instrument.context.assets.open("stage7/pdfs/scanned/scanned_text_fixture.pdf").use { it.readBytes() }
        val root = DriveProjectEntry("synthetic-root", "Recovery fixture", DRIVE_FOLDER_MIME)
        private val file = DriveProjectEntry("synthetic-pdf", "fixture.pdf", "application/pdf", bytes.size.toLong(), 1L,
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
        override fun requireCurrent() = Unit
        override suspend fun children(folderId: String) = listOf(file)
        override suspend fun metadata(fileId: String) = if (fileId == root.id) root else file
        override suspend fun readPdf(file: DriveProjectEntry, consume: (InputStream) -> Unit) {
            bytes.inputStream().use(consume)
        }
    }
    private class Interrupted : Error() {}

}
