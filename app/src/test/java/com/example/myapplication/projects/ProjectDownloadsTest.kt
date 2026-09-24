package com.example.myapplication.projects

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class ProjectDownloadsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val storage = MemoryProjectStorage()
    private val library = ProjectLibraryStore(storage)
    private val base by lazy { temporary.newFolder("downloads") }
    private val downloads by lazy { ProjectDownloads(base, library) }
    private val gateway = FixtureGateway()

    @Test fun nestedProjectAndSameNameDrawingsPublishWithDistinctSafePaths() = runBlocking {
        val project = download()
        val catalog = downloads.readCatalog(project)
        val pdfs = catalog.filterNot { it.folder }
        assertEquals(2, pdfs.size)
        assertEquals(2, pdfs.map { it.id }.toSet().size)
        assertTrue(catalog.any { it.folder && it.name == "../Electrical" })
        assertTrue(pdfs.any { it.parent.isNotEmpty() })
        pdfs.forEach { assertArrayEquals(gateway.bytes, downloads.pdf(project.id, it.id).readBytes()) }
        assertEquals(project, library.read().projects.single())
        assertFalse(File(temporary.root, "Electrical").exists())
        assertTrue(base.listFiles()!!.none { it.name.startsWith(".incoming") })
    }

    @Test fun truncatedDownloadDoesNotPublishAnyProject() = runBlocking {
        gateway.truncated = true
        expectFailure { download() }
        assertTrue(library.read().projects.isEmpty())
        assertTrue(base.listFiles().orEmpty().isEmpty())
    }

    @Test fun checksumMismatchAndInvalidPdfLeaveNoVisibleProject() = runBlocking {
        gateway.wrongBytes = true
        expectFailure { download() }
        gateway.wrongBytes = false
        expectFailure { downloads.download(gateway, gateway.root, { throw IOException("Invalid PDF") }, {}) }
        assertTrue(library.read().projects.isEmpty())
        assertTrue(base.listFiles().orEmpty().isEmpty())
    }

    @Test fun changedRemoteDocumentCannotBecomeAnAcceptedDownload() = runBlocking {
        gateway.changed = true
        expectFailure { download() }
        assertTrue(library.read().projects.isEmpty())
    }

    @Test fun cancellationClosesStreamAndRemovesOnlyIncomingOutput() = runBlocking {
        val unrelated = File(base, "unrelated.txt").apply { writeText("preserve") }
        gateway.cancelCopy = true
        try { download(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertTrue(gateway.closed)
        assertEquals("preserve", unrelated.readText())
        assertEquals(listOf("unrelated.txt"), base.list()!!.toList())
        assertTrue(library.read().projects.isEmpty())
    }

    @Test fun failedLibraryPublicationRollsBackCompleteButUnregisteredDownload() = runBlocking {
        storage.fail = true
        expectFailure { download() }
        assertTrue(library.read().projects.isEmpty())
        assertTrue(base.listFiles().orEmpty().isEmpty())
    }

    @Test fun uncertainLibraryCommitCannotDeleteAlreadyAcceptedProjectFiles() = runBlocking {
        storage.throwAfterCommit = true
        expectFailure { download() }
        val accepted = library.read().projects.single()
        val catalog = downloads.readCatalog(accepted)
        catalog.filterNot { it.folder }.forEach {
            assertArrayEquals(gateway.bytes, downloads.pdf(accepted.id, it.id).readBytes())
        }
        assertTrue(base.listFiles()!!.none { it.name.startsWith(".incoming") })
    }

    @Test fun reselectingDownloadedProjectDoesNotReplaceItsFiles() = runBlocking {
        val project = download()
        val pdf = downloads.pdf(project.id, downloads.readCatalog(project).first { !it.folder }.id)
        val before = pdf.readBytes()
        gateway.offline = true
        assertEquals(project, download())
        assertArrayEquals(before, pdf.readBytes())
        assertEquals(1, library.read().projects.size)
    }

    @Test fun sourceAccountIsPartOfDownloadedProjectIdentity() = runBlocking {
        val first = download()
        gateway.account = "second-account"
        val second = download()
        assertNotEquals(first.id, second.id)
        assertEquals(2, library.read().projects.size)
    }

    @Test fun declaredByteLimitAndDuplicateFolderIdsAreRejectedBeforePublication() = runBlocking {
        gateway.oversized = true
        expectFailure { download() }
        gateway.oversized = false; gateway.cycle = true
        expectFailure { download() }
        assertTrue(library.read().projects.isEmpty())
        assertTrue(base.listFiles().orEmpty().isEmpty())
    }

    @Test fun nonProgressingDownloadFailsBoundedlyAndClosesItsStream() = runBlocking {
        gateway.stallReads = true
        expectFailure { download() }
        assertEquals(com.example.myapplication.stage5.Stage5Limits.MAX_ZERO_READS + 1, gateway.zeroReadCount)
        assertTrue(gateway.closed)
        assertTrue(library.read().projects.isEmpty())
        assertTrue(base.listFiles().orEmpty().isEmpty())
    }

    @Test fun briefZeroReadsDoNotRejectACompleteDownload() = runBlocking {
        gateway.initialZeroReads = 2
        val project = download()
        assertEquals(project, library.read().projects.single())
        assertEquals(2, gateway.zeroReadCount)
        assertTrue(gateway.closed)
    }

    @Test fun restartReconcilesEveryPublicationPhaseWithoutRetryCopies() = runBlocking {
        for (phase in ProjectDownloadPhase.entries) {
            val state = crashAt(phase)
            val recovered = ProjectDownloads(state.base, ProjectLibraryStore(state.storage))
            recovered.recover()
            val accepted = ProjectLibraryStore(state.storage).read().projects
            val shouldRecover = phase >= ProjectDownloadPhase.VERIFIED
            assertEquals(phase.name, if (shouldRecover) 1 else 0, accepted.size)
            if (shouldRecover) {
                val catalog = recovered.readCatalog(accepted.single())
                catalog.filterNot { it.folder }.forEach {
                    assertArrayEquals(FixtureGateway().bytes, recovered.pdf(state.id, it.id).readBytes())
                }
            }
            assertTrue(phase.name, state.base.list().orEmpty().none { it.startsWith(".") })
            val before = state.base.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(state.base).path to it.readBytes().toList() }
            recovered.recover()
            assertEquals(before, state.base.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(state.base).path to it.readBytes().toList() })
        }
    }

    private class SimulatedProcessDeath : Error()
    private data class InterruptedProject(val base: File, val storage: MemoryProjectStorage, val id: String)
    private suspend fun crashAt(phase: ProjectDownloadPhase): InterruptedProject {
        val directory = temporary.newFolder("crash-${phase.name}-${java.util.UUID.randomUUID()}")
        val storage = MemoryProjectStorage()
        val library = ProjectLibraryStore(storage)
        var id = ""
        val downloader = ProjectDownloads(directory, library) { step, identity ->
            id = identity
            if (step == phase) throw SimulatedProcessDeath()
        }
        val remote = FixtureGateway()
        try {
            downloader.download(remote, remote.root, { require(it.readText().startsWith("%PDF")) }, {})
            fail("Expected process interruption at $phase")
        } catch (_: SimulatedProcessDeath) { }
        assertTrue(id.isNotBlank())
        return InterruptedProject(directory, storage, id)
    }

    @Test fun recoveryNeverDeletesUnownedDirectories() = runBlocking {
        val unknown = File(base, ".incoming-${java.util.UUID.randomUUID()}").apply { mkdir() }
        File(unknown, "keep.txt").writeText("not owned by a receipt")
        downloads.recover()
        assertEquals("not owned by a receipt", File(unknown, "keep.txt").readText())
    }

    @Test fun corruptReceiptOrChangedAssetBlocksRecoveryWithoutDiscardingBytes() = runBlocking {
        val state = crashAt(ProjectDownloadPhase.VERIFIED)
        val receipt = File(state.base, ".download-${state.id}.json")
        val original = receipt.readBytes()
        receipt.writeText("{")
        val recover = ProjectDownloads(state.base, ProjectLibraryStore(state.storage))
        try { recover.recover(); fail("Expected corrupt receipt rejection") } catch (_: Exception) { }
        assertTrue(File(state.base, ".incoming-${state.id}").exists())
        assertEquals("{", receipt.readText())
        receipt.writeBytes(original)
        val pdf = File(state.base, ".incoming-${state.id}").listFiles()!!.first { it.extension == "pdf" }
        pdf.writeText("changed")
        expectFailure { recover.recover() }
        assertEquals("changed", pdf.readText())
        assertTrue(ProjectLibraryStore(state.storage).read().projects.isEmpty())
    }

    @Test fun ordinaryFailureAfterVerificationDiscardsOnlyItsUnacceptedProject() = runBlocking {
        val other = File(base, "unrelated.txt").apply { writeText("preserve") }
        val failing = ProjectDownloads(base, library) { phase, _ ->
            if (phase == ProjectDownloadPhase.RENAMED) throw IOException("abort after rename")
        }
        expectFailure { failing.download(gateway, gateway.root, {}, {}) }
        downloads.recover()
        assertEquals(listOf(other.name), base.list()!!.toList())
        assertTrue(library.read().projects.isEmpty())
    }

    @Test fun tornFirstReceiptIsRemovedWithoutTouchingUnownedContent() = runBlocking {
        val id = java.util.UUID.randomUUID().toString()
        val marker = File(base, ".download-$id.json.tmp").apply { writeText("{") }
        val keep = File(base, "unrelated.txt").apply { writeText("preserve") }
        downloads.recover()
        assertFalse(marker.exists())
        assertEquals("preserve", keep.readText())
        assertTrue(library.read().projects.isEmpty())
    }

    @Test fun firstReceiptWithoutAuthorityCannotDeleteAProjectDirectory() = runBlocking {
        val id = java.util.UUID.randomUUID().toString()
        val marker = File(base, ".download-$id.json.tmp").apply { writeText("{") }
        val directory = File(base, ".incoming-$id").apply { mkdir() }
        val keep = File(directory, "keep.txt").apply { writeText("preserve") }
        expectFailure { downloads.recover() }
        assertEquals("{", marker.readText())
        assertEquals("preserve", keep.readText())
    }

    @Test fun staleReceiptScratchDoesNotTruncateAnUnrelatedHardLinkedFile() = runBlocking {
        val state = crashAt(ProjectDownloadPhase.VERIFIED)
        val other = File(temporary.root, "sentinel-${java.util.UUID.randomUUID()}").apply { writeText("preserve") }
        val scratch = File(state.base, ".download-${state.id}.json.tmp")
        java.nio.file.Files.createLink(scratch.toPath(), other.toPath())
        ProjectDownloadJournal(state.base, ProjectLibraryStore(state.storage)).abort(state.id)
        assertEquals("preserve", other.readText())
        assertTrue(state.base.list().orEmpty().isEmpty())
    }

    private suspend fun download() = downloads.download(gateway, gateway.root, { file ->
        require(file.readText().startsWith("%PDF"))
    }, {})
    private suspend fun expectFailure(action: suspend () -> Any) {
        try { action(); fail("Expected failed download") } catch (_: IllegalArgumentException) { } catch (_: IOException) { }
    }

    private class FixtureGateway : DriveProjectGateway {
        var account = "first-account"
        override val accountId get() = account
        val bytes = "%PDF-1.4 synthetic fixture".toByteArray()
        private val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val root = DriveProjectEntry("project", "New Project", DRIVE_FOLDER_MIME)
        private val nested = DriveProjectEntry("electrical", "../Electrical", DRIVE_FOLDER_MIME)
        var truncated = false; var wrongBytes = false; var changed = false; var cancelCopy = false
        var oversized = false; var cycle = false; var offline = false; var closed = false
        var stallReads = false; var initialZeroReads = 0; var zeroReadCount = 0
        private fun pdf(id: String) = DriveProjectEntry(id, "plan.pdf", "application/pdf",
            if (oversized) PROJECT_PDF_BYTE_LIMIT + 1 else bytes.size.toLong(), 1, md5)
        override fun requireCurrent() = Unit
        override suspend fun children(folderId: String): List<DriveProjectEntry> {
            if (offline) throw IOException("Offline")
            return when (folderId) {
                "project" -> listOf(nested, pdf("a"))
                "electrical" -> if (cycle) listOf(root) else listOf(pdf("b"))
                else -> error("Unexpected folder")
            }
        }
        override suspend fun metadata(fileId: String): DriveProjectEntry {
            if (offline) throw IOException("Offline")
            return if (fileId == root.id) root else pdf(fileId).let { if (changed) it.copy(version = 2) else it }
        }
        override suspend fun readPdf(file: DriveProjectEntry, consume: (InputStream) -> Unit) { object : ByteArrayInputStream(
            if (truncated) bytes.dropLast(1).toByteArray() else if (wrongBytes) ByteArray(bytes.size) else bytes
        ) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (cancelCopy) throw CancellationException("cancel fixture")
                if (stallReads || initialZeroReads > 0) {
                    if (++zeroReadCount > com.example.myapplication.stage5.Stage5Limits.MAX_ZERO_READS + 1) {
                        throw AssertionError("Unbounded non-progressing download loop")
                    }
                    if (initialZeroReads > 0) initialZeroReads--
                    return 0
                }
                return super.read(buffer, offset, length)
            }
            override fun close() { closed = true; super.close() }
        }.use(consume) }
    }
}
