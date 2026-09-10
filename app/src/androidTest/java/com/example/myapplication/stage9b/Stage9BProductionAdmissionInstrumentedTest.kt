package com.example.myapplication.stage9b

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.BlueprintApp
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.MainActivity
import com.example.myapplication.PhotoPin
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.snapshotFromState
import com.example.myapplication.stage2.DocumentManifestEntryV1
import com.example.myapplication.stage2.LocalDocumentRepository
import com.example.myapplication.stage2.ManifestReadResult
import com.example.myapplication.stage5.DocumentPhotoAssetStore
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage8.AnnotationReducer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

/**
 * Production Compose admission qualification.  The reducer is used only to
 * seed a photo pin; every dialog save, gesture, overlay, and Back assertion is
 * driven through BlueprintApp's real UI.
 */
@RunWith(AndroidJUnit4::class)
class Stage9BProductionAdmissionInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun pdfNoteInvalidSavesRetainInputAndValidAndUnchangedControls() {
        val uri = fixturePdfUri()
        val vm = BlueprintViewModel()
        val effects = AtomicInteger(0)
        val scenario = launchProductionViewer(uri, vm, effects)
        try {
            val source = sourceFor(uri)
            val before = snapshot(vm, source)
            val beforeHistory = historyCheckpoint(vm)
            val beforeEffects = effects.get()

            openPdfNoteDialog()
            composeRule.onNodeWithText("Save").performClick()
            composeRule.onNodeWithText("Add Note").assertIsDisplayed()
            assertEquals(before, snapshot(vm, source))
            assertEquals(beforeHistory, historyCheckpoint(vm))
            assertEquals(beforeEffects, effects.get())

            val oversized = "x".repeat(Stage5Limits.MAX_TEXT_CHARS + 1)
            composeRule.onNode(hasSetTextAction()).performTextInput(oversized)
            composeRule.onNodeWithText("Save").performClick()
            composeRule.onNodeWithText("Add Note").assertIsDisplayed()
            composeRule.onNode(hasSetTextAction()).assertTextContains("x".repeat(128), substring = true)
            assertEquals(before, snapshot(vm, source))
            assertEquals(beforeHistory, historyCheckpoint(vm))
            assertEquals(beforeEffects, effects.get())

            composeRule.onNode(hasSetTextAction()).performTextClearance()
            composeRule.onNode(hasSetTextAction()).performTextInput("production valid note")
            composeRule.onNodeWithText("Save").performClick()
            composeRule.waitUntil(5_000L) {
                vm.pageNotes[0]?.singleOrNull()?.text == "production valid note"
            }
            assertEquals("production valid note", vm.pageNotes[0]?.singleOrNull()?.text)
            composeRule.waitUntil(5_000L) { effects.get() > beforeEffects }
            val afterValid = snapshot(vm, source)
            assertNotEquals(before, afterValid)
            assertNotEquals(beforeHistory, historyCheckpoint(vm))

            // Tap the real rendered note, open the real edit dialog, and save
            // the unchanged valid value.  This must close with no effect or
            // history record, unlike the accepted save above.
            tapRootCenter()
            composeRule.waitUntil(5_000L) {
                composeRule.onAllNodesWithText("Edit", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Edit", useUnmergedTree = true).performClick()
            composeRule.onNodeWithText("Edit Note").assertIsDisplayed()
            val unchangedHistory = historyCheckpoint(vm)
            val unchangedEffects = effects.get()
            composeRule.onNodeWithText("Save").performClick()
            composeRule.onNodeWithText("Edit Note").assertDoesNotExist()
            assertEquals(afterValid, snapshot(vm, source))
            assertEquals(unchangedHistory, historyCheckpoint(vm))
            assertEquals(unchangedEffects, effects.get())
        } finally {
            scenario.close()
        }
    }

    @Test
    fun photoNoteInvalidSavesRetainInputAndOverlayBackReturnsToGallery() {
        val uri = fixturePdfUri()
        val vm = BlueprintViewModel()
        val effects = AtomicInteger(0)
        val scenario = launchProductionViewer(uri, vm, effects)
        var photoName: String? = null
        try {
            photoName = seedPhotoPin(scenario, vm, uri)
            val source = sourceFor(uri)
            val before = snapshot(vm, source)
            val beforeHistory = historyCheckpoint(vm)
            val beforeEffects = effects.get()

            openPhotoGalleryAndFullscreen()
            // System Back is handled by the production full-screen overlay and
            // returns to the production gallery dialog, not the PDF viewer.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.onNodeWithText("Photos (1)").assertIsDisplayed()
            awaitPhotoThumbnailLoaded()
            composeRule.onNodeWithContentDescription("Photo 0").performClick()
            awaitFullScreenPhotoLoaded()
            composeRule.onNodeWithContentDescription("Full screen photo").assertIsDisplayed()

            openImageNoteDialog()
            composeRule.onNodeWithText("Save").performClick()
            composeRule.onNodeWithText("Add Image Note").assertIsDisplayed()
            assertEquals(before, snapshot(vm, source))
            assertEquals(beforeHistory, historyCheckpoint(vm))
            assertEquals(beforeEffects, effects.get())

            val oversized = "y".repeat(Stage5Limits.MAX_TEXT_CHARS + 1)
            composeRule.onNode(hasSetTextAction()).performTextInput(oversized)
            composeRule.onNodeWithText("Save").performClick()
            composeRule.onNodeWithText("Add Image Note").assertIsDisplayed()
            composeRule.onNode(hasSetTextAction()).assertTextContains("y".repeat(128), substring = true)
            assertEquals(before, snapshot(vm, source))
            assertEquals(beforeHistory, historyCheckpoint(vm))
            assertEquals(beforeEffects, effects.get())

            composeRule.onNode(hasSetTextAction()).performTextClearance()
            composeRule.onNode(hasSetTextAction()).performTextInput("production image note")
            composeRule.onNodeWithText("Save").performClick()
            composeRule.waitUntil(5_000L) {
                val pin = vm.pagePhotoPins[0]?.singleOrNull()
                val reference = photoName ?: return@waitUntil false
                reference in pin?.imageNotes.orEmpty() &&
                    pin?.imageNotes?.get(reference)?.singleOrNull()?.text == "production image note"
            }
            composeRule.waitUntil(5_000L) { effects.get() > beforeEffects }
            val afterValid = snapshot(vm, source)
            assertNotEquals(before, afterValid)
            assertNotEquals(beforeHistory, historyCheckpoint(vm))

            // Select the real rendered image note, open its edit dialog, and
            // save without changing its value.  The no-op is typed by the
            // reducer and therefore must not emit a second production effect.
            tapImageCenter()
            composeRule.waitUntil(5_000L) {
                composeRule.onAllNodesWithContentDescription("Edit Note").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription("Edit Note").performClick()
            composeRule.onNodeWithText("Edit Image Note").assertIsDisplayed()
            val unchangedHistory = historyCheckpoint(vm)
            val unchangedEffects = effects.get()
            composeRule.onNodeWithText("Save").performClick()
            composeRule.onNodeWithText("Edit Image Note").assertDoesNotExist()
            assertEquals(afterValid, snapshot(vm, source))
            assertEquals(unchangedHistory, historyCheckpoint(vm))
            assertEquals(unchangedEffects, effects.get())

            // Exercise the production pointer coroutine. Cancellation must not
            // commit a draft, a following real UP must still commit normally,
            // and a later blank drag must not reuse either transient target.
            composeRule.onNodeWithContentDescription("Full screen photo").performTouchInput {
                down(center)
                moveTo(center + androidx.compose.ui.geometry.Offset(width * .12f, 0f), 32L)
                cancel()
            }
            composeRule.waitForIdle()
            assertEquals(afterValid, snapshot(vm, source))
            assertEquals(unchangedHistory, historyCheckpoint(vm))
            assertEquals(unchangedEffects, effects.get())

            // A real UP immediately after cancellation must still commit the
            // selected image note through the production reducer/effect path.
            val beforeSuccessfulUp = snapshot(vm, source)
            val beforeSuccessfulUpHistory = historyCheckpoint(vm)
            val beforeSuccessfulUpEffects = effects.get()
            composeRule.onNodeWithContentDescription("Full screen photo").performTouchInput {
                down(center)
                moveTo(center + androidx.compose.ui.geometry.Offset(width * .12f, 0f), 32L)
                up()
            }
            composeRule.waitForIdle()
            composeRule.waitUntil(5_000L) { snapshot(vm, source) != beforeSuccessfulUp }
            assertNotEquals(beforeSuccessfulUp, snapshot(vm, source))
            assertNotEquals(beforeSuccessfulUpHistory, historyCheckpoint(vm))
            composeRule.waitUntil(5_000L) { effects.get() > beforeSuccessfulUpEffects }
            val afterSuccessfulUp = snapshot(vm, source)
            val afterSuccessfulUpHistory = historyCheckpoint(vm)
            val afterSuccessfulUpEffects = effects.get()

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.onNodeWithText("Photos (1)").assertIsDisplayed()
            awaitPhotoThumbnailLoaded()
            composeRule.onNodeWithContentDescription("Photo 0").performClick()
            awaitFullScreenPhotoLoaded()
            composeRule.onNodeWithContentDescription("Full screen photo").performTouchInput {
                val blank = androidx.compose.ui.geometry.Offset(width * .93f, height * .82f)
                down(blank)
                moveTo(blank + androidx.compose.ui.geometry.Offset(-width * .05f, 0f), 32L)
                up()
            }
            composeRule.waitForIdle()
            assertEquals("blank drag reused a stale image annotation target", afterSuccessfulUp, snapshot(vm, source))
            assertEquals(afterSuccessfulUpHistory, historyCheckpoint(vm))
            assertEquals(afterSuccessfulUpEffects, effects.get())
        } finally {
            scenario.close()
        }
    }

    private fun launchProductionViewer(
        uri: Uri,
        vm: BlueprintViewModel,
        effects: AtomicInteger
    ): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch<MainActivity>(Intent(targetContext, MainActivity::class.java))
        scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    BlueprintApp(
                        vm = vm,
                        initialPdfUri = uri,
                        onStage8EffectConsumed = { effects.incrementAndGet() }
                    )
                }
            }
        }
        composeRule.waitUntil(30_000L) {
            try {
                composeRule.onNodeWithText("SHEET 1").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        composeRule.onNodeWithText("SHEET 1").performClick()
        composeRule.waitUntil(30_000L) {
            try {
                composeRule.onNodeWithContentDescription("Note").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        return scenario
    }

    private fun openPdfNoteDialog() {
        composeRule.onNodeWithContentDescription("Note").performClick()
        tapRootCenter()
        composeRule.onNodeWithText("Add Note").assertIsDisplayed()
    }

    private fun openPhotoGalleryAndFullscreen() {
        tapRootCenter()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("View", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("View", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Photos (1)").assertIsDisplayed()
        awaitPhotoThumbnailLoaded()
        composeRule.onNodeWithContentDescription("Photo 0").performClick()
        awaitFullScreenPhotoLoaded()
    }

    private fun awaitFullScreenPhotoLoaded() {
        // Thumbnail and full-screen decodes have separate asynchronous owners.
        // A click is not proof the decoded full-screen image is displayed.
        composeRule.waitUntil(15_000L) {
            try {
                composeRule.onNodeWithContentDescription("Full screen photo").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    private fun awaitPhotoThumbnailLoaded() {
        // Re-opening the gallery creates a fresh bounded bitmap cache.  The
        // dialog title is available before the async thumbnail owner is
        // published, so wait for the real rendered thumbnail semantics rather
        // than attempting a click against the loading placeholder.
        composeRule.waitUntil(15_000L) {
            composeRule.onAllNodesWithContentDescription("Photo 0").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openImageNoteDialog() {
        val candidates = composeRule.onAllNodesWithText("Note", useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("full-screen photo note control is missing", candidates.isNotEmpty())
        composeRule.onAllNodesWithText("Note", useUnmergedTree = true)[candidates.lastIndex].performClick()
        tapImageCenter()
        composeRule.onNodeWithText("Add Image Note").assertIsDisplayed()
    }

    private fun seedPhotoPin(
        scenario: ActivityScenario<MainActivity>,
        vm: BlueprintViewModel,
        uri: Uri
    ): String {
        val entry = manifestEntry(uri)
        val photoName = DocumentPhotoAssetStore(targetContext.filesDir, entry.documentId).use { store ->
            testContext.assets.open("stage7/photos/small_valid_photo.jpg").use { input ->
                store.publishNewPhoto(input, ".jpg").also(store::releasePhotoPublication)
            }
        }
        scenario.onActivity {
            ensurePage(vm, 0)
            val key = Any()
            val reducer = AnnotationReducer(
                vm = vm,
                sessionKey = key,
                currentSessionKey = { key },
                sessionActivePredicate = { true }
            )
            val pin = PhotoPin(
                x = .5f,
                y = .5f,
                id = "stage9b-production-photo-pin",
                imageFileNames = mutableListOf(photoName)
            )
            assertTrue("synthetic photo fixture was rejected", reducer.addPhotoPin(0, pin).changed)
            assertEquals(
                "identical synthetic fixture update must be a typed no-op",
                AnnotationReducer.Result.Unchanged,
                reducer.updatePhotoPin(0, pin, pin)
            )
        }
        composeRule.waitUntil(5_000L) { vm.pagePhotoPins[0]?.singleOrNull()?.id == "stage9b-production-photo-pin" }
        return photoName
    }

    private fun ensurePage(vm: BlueprintViewModel, page: Int) {
        if (vm.pagePaths[page] == null) vm.pagePaths[page] = androidx.compose.runtime.mutableStateListOf()
        if (vm.pageMeasurements[page] == null) vm.pageMeasurements[page] = androidx.compose.runtime.mutableStateListOf()
        if (vm.pageNotes[page] == null) vm.pageNotes[page] = androidx.compose.runtime.mutableStateListOf()
        if (vm.pagePhotoPins[page] == null) vm.pagePhotoPins[page] = androidx.compose.runtime.mutableStateListOf()
        if (vm.pageShapes[page] == null) vm.pageShapes[page] = androidx.compose.runtime.mutableStateListOf()
    }

    private fun historyCheckpoint(vm: BlueprintViewModel): AnnotationReducer.HistoryOwner.Checkpoint =
        vm.annotationHistory.captureCheckpoint()

    private fun snapshot(vm: BlueprintViewModel, source: DocumentSourceIdentityV1) =
        snapshotFromState(vm, source)

    private fun sourceFor(uri: Uri) = DocumentSourceIdentityV1(
        sourceUri = uri.toString(),
        displayName = "scanned_text_fixture.pdf",
        providerMetadata = mapOf("authority" to "com.sotaware.construct.test.stage8.fixture")
    )

    private fun manifestEntry(uri: Uri): DocumentManifestEntryV1 {
        val loaded = runBlocking { LocalDocumentRepository(targetContext).readManifest() }
        if (loaded !is ManifestReadResult.Loaded) {
            throw AssertionError("production qualification manifest is unavailable: $loaded")
        }
        return loaded.entries.firstOrNull { it.sourceUri == uri.toString() }
            ?: throw AssertionError("production qualification fixture was not associated")
    }

    private fun tapRootCenter() {
        composeRule.onRoot().performTouchInput { click(center) }
    }

    private fun tapImageCenter() {
        val image = composeRule.onNodeWithContentDescription("Full screen photo")
        image.performTouchInput { click(center) }
    }

    private fun fixturePdfUri(): Uri =
        NativeFixtureAssets.fixtureUri(
            "com.sotaware.construct.test.stage8.fixture",
            "stage7/pdfs/scanned/scanned_text_fixture.pdf"
        ).buildUpon()
            // Keep each production qualification document association
            // independent across repeated instrumentation invocations.
            .appendQueryParameter("stage9b", UUID.randomUUID().toString())
            .build()
        .also {
            requireNotNull(targetContext.contentResolver.openFileDescriptor(it, "r")).use { }
        }

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val testContext
        get() = InstrumentationRegistry.getInstrumentation().context
}
