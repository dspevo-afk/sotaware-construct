package com.example.myapplication.projects

import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage9b.RecentDocumentRecord
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

internal class MemoryProjectStorage : ProjectLibraryStorage {
    var value: String? = null
    var fail = false
    var throwAfterCommit = false
    override fun read() = value
    override fun commit(value: String): Boolean {
        if (fail) return false
        this.value = value
        if (throwAfterCommit) throw IOException("Synthetic uncertain commit")
        return true
    }
}

class ProjectLibraryTest {
    @Test fun sameNameProjectsKeepIndependentRecentFilesAcrossReload() {
        val storage = MemoryProjectStorage()
        val store = ProjectLibraryStore(storage)
        val first = store.add(project("tree:A"))
        val second = store.add(project("tree:B"))
        val drawingA = recent("content://provider/A/plan.pdf", 100)
        val drawingB = recent("content://provider/B/plan.pdf", 200)
        store.recordOpen(first.id, drawingA, "A / Electrical")
        store.recordOpen(second.id, drawingB, "B / Electrical")
        val restored = ProjectLibraryStore(storage).read()
        assertEquals(listOf(drawingA.documentId.value), restored.projects[0].recent.map { it.documentId })
        assertEquals(listOf(drawingB.documentId.value), restored.projects[1].recent.map { it.documentId })
        assertEquals("A / Electrical", restored.projects[0].recent.single().folder)
    }

    @Test fun reopeningMovesDrawingToTopWithoutDuplicateAndRetainsFivePerProject() {
        val store = ProjectLibraryStore(MemoryProjectStorage())
        val project = store.add(project("tree:A"))
        val records = (1L..8L).map { recent("content://provider/$it", it) }
        records.forEach { store.recordOpen(project.id, it, "Root") }
        store.recordOpen(project.id, records[4].copy(lastSuccessfullyOpenedAtEpochMillis = 99), "Root / Moved")
        val entries = store.read().projects.single().recent
        assertEquals(5, entries.size)
        assertEquals(records[4].documentId.value, entries[0].documentId)
        assertEquals(listOf(99L, 8L, 7L, 6L, 4L), entries.map { it.openedAt })
    }

    @Test fun duplicateFolderSelectionRetainsProjectAndRecentIdentity() {
        val store = ProjectLibraryStore(MemoryProjectStorage())
        val first = store.add(project("tree:A"))
        store.recordOpen(first.id, recent("content://provider/A/plan.pdf", 1), "Root")
        assertEquals(first.id, store.add(project("tree:A")).id)
        assertEquals(1, store.read().projects.single().recent.size)
    }

    @Test fun corruptAndUnsupportedIndexesCannotBeReplacedByAdd() {
        for (bad in listOf("{", "{\"schema\":2,\"projects\":[],\"driveRoots\":[]}",
            "{\"schema\":1,\"schema\":1,\"projects\":[],\"driveRoots\":[]}")) {
            val storage = MemoryProjectStorage().apply { value = bad }
            assertThrows(Exception::class.java) { ProjectLibraryStore(storage).add(project("tree:A")) }
            assertEquals(bad, storage.value)
        }
    }

    @Test fun failedRecentCommitPreservesLastSuccessfulEntry() {
        val storage = MemoryProjectStorage()
        val store = ProjectLibraryStore(storage)
        val first = store.add(project("tree:A"))
        val before = storage.value
        storage.fail = true
        assertThrows(IOException::class.java) { store.recordOpen(first.id, recent("content://provider/A", 1), "Root") }
        assertEquals(before, storage.value)
        assertTrue(store.read().projects.single().recent.isEmpty())
    }

    @Test fun selectedDriveSourceIsScopedByAccountAndStoredById() {
        val store = ProjectLibraryStore(MemoryProjectStorage())
        store.selectDriveRoot(DriveProjectRoot("account-A", "folder-1", "New Bid Documents"))
        store.selectDriveRoot(DriveProjectRoot("account-B", "folder-2", "New Bid Documents"))
        store.selectDriveRoot(DriveProjectRoot("account-A", "folder-3", "New Bid Documents"))
        assertEquals(setOf("folder-2", "folder-3"), store.read().driveRoots.map { it.folderId }.toSet())
    }

    @Test fun catalogRejectsTraversalCyclesAndAbsentParents() {
        val id = UUID.randomUUID().toString()
        assertThrows(Exception::class.java) { encodeCatalog(listOf(DownloadedEntry("../outside", "", "Plan", false))) }
        assertThrows(Exception::class.java) { encodeCatalog(listOf(DownloadedEntry(id, "missing", "Plan", false))) }
        assertThrows(Exception::class.java) { encodeCatalog(listOf(DownloadedEntry(id, id, "Folder", true),
            DownloadedEntry(UUID.randomUUID().toString(), id, "Plan", false))) }
    }

    private fun project(source: String) = ProjectRecord(UUID.randomUUID().toString(), "Same project name", source)
    private fun recent(source: String, time: Long) = RecentDocumentRecord(DocumentId.new(), source, "plan.pdf", time)
}
