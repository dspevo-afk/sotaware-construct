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

    @Test fun chosenDriveRoot_reopensTheFolderThisAccountPickedLastTime() {
        // Dave's report: the picker forgot his folder and dropped him back at My Drive. The
        // choice is stored per account, so a new picker session opens where he left it.
        val store = ProjectLibraryStore(MemoryProjectStorage())
        store.selectDriveRoot(DriveProjectRoot("account-A", "bid-docs", "New Bid Documents", "dave@example.test"))
        val library = ProjectLibraryStore(MemoryProjectStorage().apply { value = store.read().let {
            ProjectLibraryCodec.encode(ProjectLibrary(emptyList(), store.read().driveRoots))
        } }).read()
        val chosen = requireNotNull(library.chosenDriveRoot(listOf("account-A")))
        assertEquals("bid-docs", chosen.folderId)
        assertEquals("New Bid Documents", chosen.name)
        assertEquals("dave@example.test", chosen.driveName)
        // A different account has no folder of its own to open, so it starts at My Drive.
        assertEquals(null, library.chosenDriveRoot(listOf("account-B")))
        // With no account signed in yet the last saved choice is shown, and it opens as soon as
        // that account connects. An empty list of identities is not an account.
        assertEquals("bid-docs", requireNotNull(library.chosenDriveRoot(emptyList())).folderId)
        assertEquals(null, ProjectLibrary().chosenDriveRoot(emptyList()))
    }

    @Test fun chosenDriveRoot_survivesAReopenedLibraryAndIsReplacedByTheNextChoice() {
        // Closing the app and reopening it (a fresh store over the same file) keeps the choice;
        // picking a different folder replaces it, and never leaves the old one behind.
        val storage = MemoryProjectStorage()
        ProjectLibraryStore(storage).selectDriveRoot(DriveProjectRoot("account-A", "first", "First Folder"))
        val reopened = ProjectLibraryStore(storage)
        assertEquals("first", requireNotNull(reopened.read().chosenDriveRoot(listOf("account-A"))).folderId)
        val saved = reopened.selectDriveRoot(DriveProjectRoot("account-A", "second", "Second Folder"))
        assertEquals("second", saved.folderId)
        val afterRestart = ProjectLibraryStore(storage).read()
        assertEquals(1, afterRestart.driveRoots.size)
        assertEquals("second", requireNotNull(afterRestart.chosenDriveRoot(listOf("account-A"))).folderId)
    }

    @Test fun chosenDriveRoot_prefersTheCurrentSignInAndKeepsEveryAccountApart() {
        val store = ProjectLibraryStore(MemoryProjectStorage())
        store.selectDriveRoot(DriveProjectRoot("account-A", "folder-a", "A Documents"))
        store.selectDriveRoot(DriveProjectRoot("account-B", "folder-b", "B Documents"))
        val library = store.read()
        assertEquals("folder-a", requireNotNull(library.chosenDriveRoot(listOf("account-A", "account-B"))).folderId)
        assertEquals("folder-b", requireNotNull(library.chosenDriveRoot(listOf("account-B", "account-A"))).folderId)
        // No account signed in (blank identities count as none) still names the last saved choice.
        assertEquals("folder-b", requireNotNull(library.chosenDriveRoot(emptyList())).folderId)
        assertEquals("folder-b", requireNotNull(library.chosenDriveRoot(listOf("", "  "))).folderId)
        assertEquals(null, ProjectLibrary().chosenDriveRoot(emptyList()))
    }

    @Test fun driveSourceKeepsItsLabelAcrossSaveAndRejectsThePickersOwnRootFolder() {
        val store = ProjectLibraryStore(MemoryProjectStorage())
        store.selectDriveRoot(DriveProjectRoot("account-A", "bid-docs", "New Bid Documents", "dave@example.test"))
        val restored = ProjectLibraryStore(MemoryProjectStorage().apply { value = storageText(store) })
        assertEquals("dave@example.test", requireNotNull(restored.read().chosenDriveRoot(listOf("account-A"))).driveName)
        // "My Drive" is the picker's own starting point, never a saved source folder.
        assertThrows(Exception::class.java) {
            store.selectDriveRoot(DriveProjectRoot("account-A", DRIVE_ROOT_FOLDER_ID, "My Drive"))
        }
        assertThrows(Exception::class.java) {
            ProjectLibraryCodec.decode(
                """{"schema":1,"projects":[],"driveRoots":[{"account":"a","folderId":"root","name":"x"}]}"""
            )
        }
        // A library saved before the sign-in address existed restores with no label.
        val legacy = ProjectLibraryCodec.decode(
            """{"schema":1,"projects":[],"driveRoots":[{"account":"a","folderId":"f","name":"Rooms"}]}"""
        )
        assertEquals("", legacy.driveRoots.single().driveName)
    }

    @Test fun driveSourceStaysWithinTheStoredAccountBudget() {
        val store = ProjectLibraryStore(MemoryProjectStorage())
        (1..12).forEach { index ->
            store.selectDriveRoot(DriveProjectRoot("account-$index", "folder-$index", "Folder $index"))
        }
        val roots = store.read().driveRoots
        assertEquals(8, roots.size)
        assertEquals(listOf("folder-5", "folder-12"), listOf(roots.first().folderId, roots.last().folderId))
    }

    private fun storageText(store: ProjectLibraryStore): String {
        val library = store.read()
        return ProjectLibraryCodec.encode(ProjectLibrary(library.projects, library.driveRoots))
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
