package com.example.myapplication.projects

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.MainActivity
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentManifestEntryV1
import com.example.myapplication.stage8.awaitPdfCanvas
import com.example.myapplication.stage9b.RECENT_DOCUMENT_PREFERENCES_KEY
import com.example.myapplication.stage9b.RECENT_DOCUMENT_PREFERENCES_NAME
import com.example.myapplication.stage9.GoogleIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProjectBrowserInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun projectConsentRequestsReadOnlyWithoutInheritingBackupGrants() {
        val identity = GoogleIdentity("synthetic-subject", "synthetic@example.invalid")
        val request = projectDriveAuthorizationRequest(identity)
        assertEquals(identity.email, request.account?.name)
        assertEquals(listOf(DRIVE_PROJECT_READ_SCOPE), request.requestedScopes.map { it.scopeUri })
        assertTrue(request.optOutIncludingGrantedScopes)
    }

    @Test fun actualFolderPickerSubfoldersRecentReopenAndStartupPreserveDrawing() = preserveLibrary {
        var scenario = launch()
        val note = "project qualification ${UUID.randomUUID()}"
        try {
            addFolder("Project Alpha")
            compose.onNodeWithText("Recent files").assertIsDisplayed()
            compose.onNodeWithText("Electrical").performClick()
            awaitText("plan.pdf")
            compose.onNodeWithText("plan.pdf").performClick()
            awaitText("SHEET 1")
            compose.onNodeWithText("SHEET 1").performClick()
            compose.awaitPdfCanvas()
            compose.onNodeWithContentDescription("Note").performClick()
            compose.onRoot().performTouchInput { click(center) }
            compose.onNodeWithText("Add Note").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performTextInput(note)
            compose.onNodeWithText("Save").performClick()
            back() // Viewer -> sheets.
            back()
            awaitText("Recent files")
            back() // Electrical -> project root, with the recent drawing still at the top.
            compose.onNodeWithText("Project Alpha / Electrical").assertIsDisplayed()
            compose.onNodeWithText("plan.pdf").performClick()
            awaitText("SHEET 1")
            scenario.onActivity { activity -> assertTrue(ViewModelProvider(activity)[BlueprintViewModel::class.java]
                .pageNotes[0].orEmpty().any { it.text == note }) }
            back()
            compose.onNodeWithContentDescription("All projects").performClick()
            addFolder("Project Beta")
            compose.onNodeWithText("Drawings you open in this project will appear here.").assertIsDisplayed()
            compose.onNodeWithText("plan.pdf").performClick()
            awaitText("SHEET 1")
            scenario.onActivity { activity -> assertFalse(ViewModelProvider(activity)[BlueprintViewModel::class.java]
                .pageNotes[0].orEmpty().any { it.text == note }) }
            back()
            compose.onNodeWithContentDescription("All projects").performClick()
            scenario.close()
            scenario = launch()
            awaitText("Projects")
            compose.onNodeWithText("Project Alpha").performClick()
            compose.onNodeWithText("Project Alpha / Electrical").assertIsDisplayed()
            compose.onNodeWithText("plan.pdf").performClick()
            awaitText("SHEET 1")
            scenario.recreate()
            awaitText("SHEET 1")
            scenario.onActivity { activity -> assertTrue(ViewModelProvider(activity)[BlueprintViewModel::class.java]
                .pageNotes[0].orEmpty().any { it.text == note }) }
            val tree = DocumentsContract.buildTreeDocumentUri(ProjectFixtureDocumentsProvider.AUTHORITY, "project-alpha")
            assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission })
        } finally { scenario.close() }
    }

    @Test fun downloadedProjectBrowsesAndOpensPdfWithoutDriveConnection() = preserveLibrary {
        val store = ProjectLibraryStore(context)
        val downloads = ProjectDownloads(File(context.filesDir, "project-downloads"), store)
        val bytes = instrumentation.context.assets.open("stage10/pdfs/a/plan.pdf").use { it.readBytes() }
        val root = DriveProjectEntry("offline-project", "Offline Project Test", DRIVE_FOLDER_MIME)
        val nested = DriveProjectEntry("offline-electrical", "Electrical", DRIVE_FOLDER_MIME)
        val digest = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val pdf = DriveProjectEntry("offline-pdf", "offline-plan.pdf", "application/pdf", bytes.size.toLong(), 1, digest)
        val gateway = object : DriveProjectGateway {
            override val accountId = "synthetic-${UUID.randomUUID()}"
            override fun requireCurrent() = Unit
            override suspend fun metadata(fileId: String) = if (fileId == root.id) root else pdf
            override suspend fun children(folderId: String) = if (folderId == root.id) listOf(nested) else listOf(pdf)
            override suspend fun readPdf(file: DriveProjectEntry, consume: (java.io.InputStream) -> Unit) { bytes.inputStream().use(consume) }
        }
        val project = runBlocking { downloads.download(gateway, root, { file -> assertTrue(file.length() > 0) }, {}) }
        val scenario = launch()
        try {
            compose.onNodeWithText("Offline Project Test").performClick()
            compose.onNodeWithText("Electrical").performClick()
            awaitText("offline-plan.pdf")
            compose.onNodeWithText("offline-plan.pdf").performClick()
            awaitText("SHEET 1")
            back()
            awaitText("Recent files")
            val recent = store.read().projects.single { it.id == project.id }.recent
            assertEquals(1, recent.size)
            assertEquals("offline-plan.pdf", recent.single().name)
        } finally {
            scenario.close()
            val owned = downloads.directory(project.id)
            assertEquals(File(context.filesDir, "project-downloads").canonicalFile, owned.canonicalFile.parentFile)
            owned.deleteRecursively()
        }
    }

    @Test fun failedFolderAdmissionReleasesOnlyItsNewGrantAndKeepsAcceptedSharedGrant() = preserveLibrary {
        AtomicFile(File(context.filesDir, "project-library-v1.json")).delete()
        val scenario = launch()
        try {
            addFolder("Project Alpha")
            val alphaTree = DocumentsContract.buildTreeDocumentUri(ProjectFixtureDocumentsProvider.AUTHORITY, "project-alpha")
            assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == alphaTree && it.isReadPermission })

            compose.onNodeWithContentDescription("All projects").performClick()
            val store = ProjectLibraryStore(context)
            repeat(MAX_PROJECTS - 1) { index ->
                store.add(ProjectRecord(UUID.randomUUID().toString(), "Synthetic limit $index", "content://limit.test/$index"))
            }

            val failureFolderIndex = (0 until 32).firstOrNull { index ->
                val candidate = DocumentsContract.buildTreeDocumentUri(ProjectFixtureDocumentsProvider.AUTHORITY, "project-failure-$index")
                context.contentResolver.persistedUriPermissions.none { it.uri == candidate && it.isReadPermission }
            } ?: fail("A fresh synthetic project tree grant is required")
            val failureFolderName = "Failure Fixture $failureFolderIndex"
            assertTrue("New project folder permission", chooseFolder(failureFolderName))
            val addError = "Could not add this folder. Select it again to grant access."
            awaitText(addError)
            val failedTree = DocumentsContract.buildTreeDocumentUri(ProjectFixtureDocumentsProvider.AUTHORITY, "project-failure-$failureFolderIndex")
            assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == failedTree && it.isReadPermission })
            assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == alphaTree && it.isReadPermission })

            // A failed attempt for the exact accepted tree must not release its
            // pre-existing shared grant, even if the library becomes unreadable.
            val atomic = AtomicFile(File(context.filesDir, "project-library-v1.json"))
            atomic.startWrite().let { output -> output.write("{".toByteArray()); atomic.finishWrite(output) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(addError).fetchSemanticsNodes().isEmpty() }
            chooseFolder("Project Alpha")
            awaitText(addError)
            assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == alphaTree && it.isReadPermission })
        } finally { scenario.close() }
    }

    @Test fun exactProviderDocumentIdentityReusesExistingAssociationButNeverSameName() {
        val authority = ProjectFixtureDocumentsProvider.AUTHORITY
        val original = DocumentsContract.buildDocumentUri(authority, "alpha-pdf").toString()
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "project-alpha")
        val selected = DocumentsContract.buildDocumentUriUsingTree(tree, "alpha-pdf").toString()
        val other = DocumentsContract.buildDocumentUri(authority, "beta-pdf").toString()
        val entries = listOf(DocumentManifestEntryV1(DocumentId.new(), original, "plan.pdf", emptyMap(), null),
            DocumentManifestEntryV1(DocumentId.new(), other, "plan.pdf", emptyMap(), null))
        assertEquals(original, existingProjectSource(selected, entries))
        val unknown = DocumentsContract.buildDocumentUriUsingTree(tree, "new-pdf").toString()
        assertEquals(unknown, existingProjectSource(unknown, entries))
    }

    @Test fun treeGrantUseMatchesOnlyTheExactTreeAndItsTreeBackedDocuments() {
        val authority = ProjectFixtureDocumentsProvider.AUTHORITY
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "project-alpha")
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, "alpha-pdf")
        val differentTree = DocumentsContract.buildTreeDocumentUri(authority, "project-beta")
        val standalone = DocumentsContract.buildDocumentUri(authority, "alpha-pdf")

        assertTrue(sourceUsesTreeGrant(tree.toString(), tree))
        assertTrue(sourceUsesTreeGrant(document.toString(), tree))
        assertFalse(sourceUsesTreeGrant(differentTree.toString(), tree))
        assertFalse(sourceUsesTreeGrant(standalone.toString(), tree))
        assertTrue(sourceUsesTreeGrant("content://$authority/tree/", tree))
    }

    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch<MainActivity>(
        Intent(context, MainActivity::class.java)
    ).also { awaitText("Projects") }

    private fun addFolder(name: String) {
        chooseFolder(name)
        awaitText(name)
    }

    private fun chooseFolder(name: String): Boolean {
        compose.onNodeWithText("Add folder").performClick()
        // DocumentsUI may open its navigation drawer automatically on first use.
        if (!clickSystem("SOTAware Project Tests", 2_000)) {
            assertTrue("Folder picker roots", clickSystem("Show roots", 10_000))
            assertTrue("Synthetic provider root", clickSystem("SOTAware Project Tests", 10_000))
        }
        assertTrue("Project directory", clickSystem(name, 15_000))
        assertTrue("Use folder action", clickSystem("Use this folder", 15_000))
        return clickSystem("Allow", 10_000)
    }
    private fun back() { compose.onAllNodesWithContentDescription("Back")[0].performClick(); compose.waitForIdle() }
    private fun awaitText(text: String) { compose.waitUntil(30_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    } }
    private fun clickSystem(text: String, timeout: Long): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + timeout
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString()?.equals(text, true) == true || node.contentDescription?.toString()?.equals(text, true) == true) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var node = find(instrumentation.uiAutomation.rootInActiveWindow)
            while (node != null) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                node = node.parent
            }
            android.os.SystemClock.sleep(100)
        }
        return false
    }

    private fun preserveLibrary(action: () -> Unit) {
        val atomic = AtomicFile(File(context.filesDir, "project-library-v1.json"))
        val before = if (atomic.baseFile.exists()) atomic.readFully() else null
        val prefs = context.getSharedPreferences(RECENT_DOCUMENT_PREFERENCES_NAME, 0)
        val previousRecent = prefs.getString(RECENT_DOCUMENT_PREFERENCES_KEY, null)
        val previousGrants = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        try { action() }
        finally {
            if (before == null) atomic.delete() else atomic.startWrite().let { output -> output.write(before); atomic.finishWrite(output) }
            check(prefs.edit().putString(RECENT_DOCUMENT_PREFERENCES_KEY, previousRecent).commit())
            context.contentResolver.persistedUriPermissions.filter {
                it.uri.authority == ProjectFixtureDocumentsProvider.AUTHORITY && it.uri !in previousGrants
            }.forEach { context.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }
}
