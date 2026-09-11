package com.example.myapplication.stage10

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.MainActivity
import com.example.myapplication.stage2.*
import kotlinx.coroutines.runBlocking
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real selector, viewer gestures, lifecycle saves and disk readback; no injected snapshots. */
@RunWith(AndroidJUnit4::class)
class Stage10DocumentIsolationInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repository get() = LocalDocumentRepository(context)

    @Test fun sameNameDrawingsRemainIsolatedAcrossRepeatedUiSwitchesAndColdOwners() {
        val run = UUID.randomUUID().toString()
        val a = fixture("a", run)
        val b = fixture("b", run)
        val aNote = "Drawing A $run"
        val bNote = "Drawing B $run"
        assertEquals(a.lastPathSegment, b.lastPathSegment)
        assertNotEquals(runBlocking { fingerprintContentUri(context, a) },
            runBlocking { fingerprintContentUri(context, b) })
        seedViaUi(a, aNote)
        seedViaUi(b, bNote)
        val aEntry = entry(a)
        val bEntry = entry(b)
        assertEquals("plan.pdf", aEntry.displayName)
        assertEquals(aEntry.displayName, bEntry.displayName)
        assertNotEquals(aEntry.documentId, bEntry.documentId)
        val scenario = launch(a)
        try {
            assertVisibleNotes(scenario, aNote)
            // Selecting the older of the two most recent same-name rows alternates A/B.
            // No debounce sleep, direct reducer call or test-only selection hook is used.
            repeat(6) { index ->
                backToSelector()
                val rows = compose.onAllNodesWithText("plan.pdf")
                assertTrue("both same-name rows must remain available", rows.fetchSemanticsNodes().size >= 2)
                rows[1].performClick()
                enterViewer()
                assertVisibleNotes(scenario, if (index % 2 == 0) bNote else aNote)
            }
            // Another real note is immediately followed by navigation and a document switch.
            addNote("Unsaved A $run")
            backToSelector()
            compose.onAllNodesWithText("plan.pdf")[1].performClick()
            enterViewer()
            assertVisibleNotes(scenario, bNote)
        } finally { scenario.close() }
        val reopened = launch(a)
        try { assertVisibleNotes(reopened, aNote, "Unsaved A $run") }
        finally { reopened.close() }
        assertDurable(aEntry, setOf(aNote, "Unsaved A $run"))
        assertDurable(bEntry, setOf(bNote))
        assertEquals(aEntry.documentId, entry(a).documentId)
        assertEquals(bEntry.documentId, entry(b).documentId)
    }

    private fun fixture(side: String, run: String): Uri = Uri.Builder().scheme("content")
        .authority("${instrumentation.context.packageName}.stage8.fixture")
        .appendPath("stage10").appendPath("pdfs").appendPath(side).appendPath("plan.pdf")
        .appendQueryParameter("stage10", run).build()

    private fun seedViaUi(uri: Uri, note: String) {
        val scenario = launch(uri)
        try {
            assertVisibleNotes(scenario)
            addNote(note)
            assertVisibleNotes(scenario, note)
        } finally { scenario.close() }
        assertDurable(entry(uri), setOf(note))
    }

    private fun launch(uri: Uri): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .putExtra("com.sotaware.construct.stage8.INITIAL_PDF_URI", uri.toString()))
        try { enterViewer(); return scenario }
        catch (error: Throwable) { scenario.close(); throw error }
    }

    private fun enterViewer() {
        compose.waitUntil(30_000) { shown("SHEET 1") || viewerShown() }
        if (shown("SHEET 1")) compose.onNodeWithText("SHEET 1").performClick()
        compose.waitUntil(30_000) { viewerShown() }
        compose.waitForIdle()
    }

    private fun addNote(text: String) {
        compose.onNodeWithContentDescription("Note").performClick()
        val center = compose.onRoot().fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithText("Add Note").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput(text)
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
    }

    private fun backToSelector() {
        repeat(2) {
            compose.onAllNodes(hasContentDescription("Back"))[0].performClick()
            compose.waitForIdle()
        }
        compose.waitUntil(15_000) { shown("Recent Drawings") }
    }

    private fun assertVisibleNotes(scenario: ActivityScenario<MainActivity>, vararg texts: String) {
        compose.waitForIdle()
        scenario.onActivity {
            val vm = ViewModelProvider(it)[BlueprintViewModel::class.java]
            assertEquals(texts.toList().sorted(), vm.pageNotes.values.flatten().map { n -> n.text }.sorted())
        }
    }

    private fun entry(uri: Uri): DocumentManifestEntryV1 = runBlocking {
        val result = repository.readManifest()
        assertTrue("manifest must remain readable: $result", result is ManifestReadResult.Loaded)
        (result as ManifestReadResult.Loaded).entries.single { it.sourceUri == uri.toString() }
    }

    private fun assertDurable(entry: DocumentManifestEntryV1, texts: Set<String>) {
        compose.waitUntil(15_000) {
            runBlocking {
                val result = repository.load(entry.documentId, entry.sourceUri, entry.sourceFingerprint)
                result is DocumentLoadResult.Loaded &&
                    result.snapshot.pages.values.flatMap { it.notes }.map { it.text }.toSet() == texts
            }
        }
        runBlocking {
            val result = repository.load(entry.documentId, entry.sourceUri, entry.sourceFingerprint)
                as DocumentLoadResult.Loaded
            assertEquals(entry.sourceUri, result.snapshot.source.sourceUri)
            assertEquals(entry.sourceFingerprint, result.sourceFingerprint)
            assertEquals(texts.size, result.snapshot.pages.values.sumOf { it.notes.size })
        }
    }

    private fun shown(text: String): Boolean = try {
        compose.onNodeWithText(text).assertIsDisplayed(); true
    } catch (_: AssertionError) { false }

    private fun viewerShown(): Boolean = try {
        compose.onNodeWithContentDescription("Note").assertIsDisplayed(); true
    } catch (_: AssertionError) { false }
}
