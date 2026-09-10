package com.example.myapplication.stage9b

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.DrawnPath
import com.example.myapplication.MainActivity
import com.example.myapplication.Measurement
import com.example.myapplication.Note
import com.example.myapplication.PageScale
import com.example.myapplication.PhotoImageNote
import com.example.myapplication.PhotoPin
import com.example.myapplication.Point
import com.example.myapplication.Shape
import com.example.myapplication.ShapeType
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.snapshotFromState
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.LocalDocumentRepository
import com.example.myapplication.stage2.ManifestReadResult
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage5.DocumentPhotoAssetStore
import com.example.myapplication.stage5.PhotoDescriptor
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage6.SOTAWARE_BUNDLE_FORMAT_VERSION
import com.example.myapplication.stage6.SOTAWARE_BUNDLE_SNAPSHOT_SCHEMA_VERSION
import com.example.myapplication.stage8.AnnotationReducer
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real app/SAF qualification for the current Stage 9B bundle format.
 *
 * The test is deliberately phase-driven because a fresh-install round trip is
 * a multi-invocation workflow.  The provider's files live in the test APK's
 * private directory, so uninstalling the target application cannot erase the
 * exported archive or its expected digest.  Run one phase at a time with:
 *
 *   -Pandroid.testInstrumentationRunnerArguments.stage9b.phase=pdf-export
 *   -Pandroid.testInstrumentationRunnerArguments.stage9b.phase=export
 *   -Pandroid.testInstrumentationRunnerArguments.stage9b.phase=import
 *   -Pandroid.testInstrumentationRunnerArguments.stage9b.phase=relaunch
 *   -Pandroid.testInstrumentationRunnerArguments.stage9b.phase=retired-format
 *
 * Host sequencing for the fresh-install gate is intentionally pinned to the
 * authorized emulator and direct instrumentation runner.  Keep the
 * instrumentation APK/provider installed so its private archive and oracle
 * survive the target-only reinstall:
 *
 *   1. adb -s emulator-5580 uninstall com.sotaware.construct
 *      adb -s emulator-5580 install app\build\outputs\apk\debug\app-debug.apk
 *      adb -s emulator-5580 shell am instrument -w -e class com.example.myapplication.stage9b.Stage9BWorkflowInstrumentedTest -e stage9b.phase pdf-export com.sotaware.construct.test/androidx.test.runner.AndroidJUnitRunner
 *   2. adb -s emulator-5580 uninstall com.sotaware.construct
 *      adb -s emulator-5580 install app\build\outputs\apk\debug\app-debug.apk
 *      adb -s emulator-5580 shell am instrument -w -e class com.example.myapplication.stage9b.Stage9BWorkflowInstrumentedTest -e stage9b.phase export com.sotaware.construct.test/androidx.test.runner.AndroidJUnitRunner
 *   3. adb -s emulator-5580 uninstall com.sotaware.construct
 *      adb -s emulator-5580 install app\build\outputs\apk\debug\app-debug.apk
 *      adb -s emulator-5580 shell am instrument -w -e class com.example.myapplication.stage9b.Stage9BWorkflowInstrumentedTest -e stage9b.phase import com.sotaware.construct.test/androidx.test.runner.AndroidJUnitRunner
 *   4. adb -s emulator-5580 shell am force-stop com.sotaware.construct
 *      adb -s emulator-5580 shell am instrument -w -e class com.example.myapplication.stage9b.Stage9BWorkflowInstrumentedTest -e stage9b.phase relaunch com.sotaware.construct.test/androidx.test.runner.AndroidJUnitRunner
 *   5. adb -s emulator-5580 uninstall com.sotaware.construct
 *      adb -s emulator-5580 install app\build\outputs\apk\debug\app-debug.apk
 *      adb -s emulator-5580 shell am instrument -w -e class com.example.myapplication.stage9b.Stage9BWorkflowInstrumentedTest -e stage9b.phase retired-format com.sotaware.construct.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The uninstall/reinstall is target-only; do not uninstall or clear the test
 * APK.  Every measured source/import/export selection is made in DocumentsUI.
 * Shell MANAGE_DOCUMENTS is adopted only by the fixture-access seam while
 * preparing, copying, hashing, or cleaning synthetic provider files.
 *
 * No bundle codec is used to perform the transfer: the production buttons and
 * ActivityResult SAF contracts are the only export/import path exercised here.
 */
@RunWith(AndroidJUnit4::class)
class Stage9BWorkflowInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun croppedRotatedPageThroughProductionSaf_writesRenderedPdf() {
        assumePhase(PHASE_PDF_EXPORT)
        val pdfUri = requireProviderReady()
        clearOwnedProviderArtifacts()
        val sourceSize = pdfDimensions(
            Stage9BProviderFixtureAccess.copyToCache(
                targetContext,
                Stage9BQualificationDocumentsProvider.SOURCE_NAME
            )
        )
        val scenario = launchViewer(pdfUri)
        try {
            val entry = awaitManifestEntry(pdfUri)
            val seeded = seedCompleteState(scenario, entry.documentId, sourceFor(entry))
            openScreenshotAction()
            clickAccessibilityTextIfPresent("SOTAware Stage 9B", 5_000L)
            clickAccessibilityText("Save", 15_000L)
            val artifact = awaitPdfExportArtifact()
            assertTrue("SAF PDF export was empty", artifact.length() > 5L)
            FileInputStream(artifact).use { input ->
                val header = ByteArray(5)
                assertEquals(5, input.read(header))
                assertEquals("%PDF-", header.toString(Charsets.US_ASCII))
            }
            val exportedSize = pdfDimensions(artifact)
            assertEquals(
                "production export must preserve the cropped/rotated visible page width",
                sourceSize.width,
                exportedSize.width
            )
            assertEquals(
                "production export must preserve the cropped/rotated visible page height",
                sourceSize.height,
                exportedSize.height
            )
            assertTrue("seeded photo appendix was not emitted", exportedSize.pages >= 2)
            assertRenderedPdfContainsIndependentMarkupAndPhotoEvidence(artifact, seeded.photoName)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun exportThroughProductionSaf_writesCurrentCompleteBundle() {
        assumePhase(PHASE_EXPORT)
        val pdfUri = requireProviderReady()
        clearOwnedProviderArtifacts()
        val scenario = launchViewer(pdfUri)
        var photoName: String? = null
        try {
            val entry = awaitManifestEntry(pdfUri)
            val source = sourceFor(entry)
            val seeded = seedCompleteState(scenario, entry.documentId, source)
            photoName = seeded.photoName
            val capture = DocumentPhotoAssetStore(targetContext.filesDir, entry.documentId).use { store ->
                store.capturePhotoAssets(seeded.snapshot)
            }
            try {
                writeExpectedRoundTrip(seeded.snapshot, entry, capture.assets.descriptors)

                backToSelector()
                openRecentOptionsAndChoose("Export Save File")
                performAndAwaitToast("Save bundle exported successfully", 15_000L) {
                    completeCreateDocument()
                }

                val archive = awaitExportArtifact()
                assertTrue("SAF export was empty", archive.length() > 0L)
                inspectCurrentBundle(archive, capture.assets.descriptors)
            } finally {
                capture.close()
            }
        } finally {
            scenario.close()
            // Keep the source photo until the target app is reinstalled and the
            // import phase has consumed the archive. It is document-scoped and
            // is cleaned by the test installation lifecycle.
            if (photoName != null) {
                // The reference remains in the expected snapshot; no cleanup
                // is safe until the canonical import/restart phase completes.
            }
        }
    }

    @Test
    fun importThroughProductionSaf_replacesEveryCurrentDomainAndRetainsIds() {
        assumePhase(PHASE_IMPORT)
        val pdfUri = requireProviderReady()
        val expected = readExpectedRoundTrip()
        val archive = awaitExportArtifact()
        val archiveHashBefore = Stage9BProviderFixtureAccess.fileInfo(archive.name).sha256
        val scenario = launchViewer(pdfUri)
        try {
            val entryBefore = awaitManifestEntry(pdfUri)
            assertNotEquals("fresh installation must allocate a new verified target association",
                expected.documentId, entryBefore.documentId.value)
            val baseline = captureSnapshot(scenario, sourceFor(entryBefore))
            assertNotEquals(
                "import qualification requires a non-empty complete fixture",
                expected.snapshotDigest,
                RemoteManifestCodec.snapshotDigest(baseline)
            )

            backToSelector()
            openRecentOptionsAndChoose("Load Save File")
            completeOpenDocument(archive.name)

            awaitImportedSnapshot(scenario, expected.snapshotDigest, sourceFor(entryBefore))
            val entryAfter = awaitManifestEntry(pdfUri)
            val imported = captureSnapshot(scenario, sourceFor(entryAfter))
            assertExpectedRestoredState(expected, entryAfter, imported, entryBefore.documentId.value)

            val capture = DocumentPhotoAssetStore(targetContext.filesDir, entryAfter.documentId).use { store ->
                store.capturePhotoAssets(imported)
            }
            try {
                assertEquals(expected.descriptors, capture.assets.descriptors.mapValues { descriptorWire(it.value) })
            } finally {
                capture.close()
            }
            var importedPid = 0
            scenario.onActivity { importedPid = android.os.Process.myPid() }
            recordImportedTarget(targetContext, importedPid, entryAfter.documentId)
            assertEquals(
                "production import modified the canonical provider archive",
                archiveHashBefore,
                Stage9BProviderFixtureAccess.fileInfo(archive.name).sha256
            )
        } finally {
            scenario.close()
        }
    }

    /**
     * Host sequencing must force-stop the target package after the import
     * invocation.  This phase launches a new ActivityScenario and rejects a
     * reused target process before inspecting the repository-backed snapshot.
     */
    @Test
    fun processRelaunchAfterImport_restoresDurableStateAndPhotoDescriptors() {
        assumePhase(PHASE_RELAUNCH)
        val pdfUri = requireProviderReady()
        val expected = readExpectedRoundTrip()
        val importedPid = expected.targetPidAfterImport
            ?: throw AssertionError("import phase did not record the target process id")
        val importedDocumentId = expected.targetDocumentIdAfterImport
            ?: throw AssertionError("import phase did not record the verified target association")
        assertNotEquals(expected.documentId, importedDocumentId)
        val entryBeforeLaunch = awaitManifestEntry(pdfUri)
        val associationBeforeLaunch = DocumentAssociation(
            documentId = entryBeforeLaunch.documentId,
            source = sourceFor(entryBeforeLaunch),
            sourceFingerprint = entryBeforeLaunch.sourceFingerprint
        )
        val durableBeforeLaunch = runBlocking {
            LocalDocumentRepository(targetContext).load(associationBeforeLaunch)
        }
        val durableSnapshot = when (durableBeforeLaunch) {
            is com.example.myapplication.stage2.DocumentLoadResult.Loaded -> durableBeforeLaunch.snapshot
            com.example.myapplication.stage2.DocumentLoadResult.NotFound ->
                throw AssertionError("restart phase found no durable snapshot")
            is com.example.myapplication.stage2.DocumentLoadResult.Failed ->
                throw AssertionError("restart phase could not load durable snapshot: ${durableBeforeLaunch.error}")
        }
        assertExpectedRestoredState(expected, entryBeforeLaunch, durableSnapshot, importedDocumentId)
        val scenario = launchViewer(pdfUri)
        try {
            var currentPid = 0
            scenario.onActivity { currentPid = android.os.Process.myPid() }
            assertNotEquals(
                "restart phase reused the imported target process; force-stop the target package between phases",
                importedPid,
                currentPid
            )
            val entry = awaitManifestEntry(pdfUri)
            assertExpectedRestoredState(expected, entry, durableSnapshot, importedDocumentId)

            val liveAfterRelaunch = captureSnapshot(scenario, sourceFor(entry))
            assertEquals(
                "fresh process ViewModel did not materialize the durable snapshot",
                durableSnapshot,
                liveAfterRelaunch
            )
            val capture = DocumentPhotoAssetStore(targetContext.filesDir, entry.documentId).use { store ->
                store.capturePhotoAssets(durableSnapshot)
            }
            try {
                assertEquals(expected.descriptors, capture.assets.descriptors.mapValues { descriptorWire(it.value) })
                capture.assets.forEach { (name, asset) ->
                    val bytes = asset.open().use { it.readBounded(Stage5Limits.MAX_PHOTO_BYTES.toLong()) }
                    assertEquals(expected.descriptors.getValue(name), descriptorWire(asset.descriptor))
                    assertEquals(asset.descriptor.byteCount, bytes.size.toLong())
                    assertEquals(asset.descriptor.sha256, sha256(bytes))
                }
            } finally {
                capture.close()
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun retiredBundleThroughProductionSaf_isRejectedWithoutChangingStateOrInput() {
        assumePhase(PHASE_RETIRED)
        val pdfUri = requireProviderReady()
        clearOwnedProviderArtifacts()
        val retired = writeRetiredBundle(pdfUri)
        val retiredHashBefore = Stage9BProviderFixtureAccess.fileInfo(retired).sha256
        val scenario = launchViewer(pdfUri)
        try {
            val entry = awaitManifestEntry(pdfUri)
            val source = sourceFor(entry)
            seedCompleteState(scenario, entry.documentId, source)
            val baseline = captureSnapshot(scenario, source)
            val baselineDigest = RemoteManifestCodec.snapshotDigest(baseline)
            val baselineHistoryEpoch = scenarioHistoryEpoch(scenario)
            val baselineHistory = scenarioHistoryCheckpoint(scenario)
            assertTrue("retired-format oracle must start with populated history", scenarioCanUndo(scenario))

            backToSelector()
            openRecentOptionsAndChoose("Load Save File")
            performAndAwaitToast("Import failed:", 15_000L) {
                completeOpenDocument(retired)
            }

            val after = captureSnapshot(scenario, sourceFor(awaitManifestEntry(pdfUri)))
            assertEquals(baselineDigest, RemoteManifestCodec.snapshotDigest(after))
            assertEquals(baseline, after)
            assertEquals(baselineHistoryEpoch, scenarioHistoryEpoch(scenario))
            assertEquals(baselineHistory, scenarioHistoryCheckpoint(scenario))
            assertTrue("retired-format rejection destroyed populated history", scenarioCanUndo(scenario))
            assertEquals(retiredHashBefore, Stage9BProviderFixtureAccess.fileInfo(retired).sha256)
        } finally {
            scenario.close()
        }
    }

    private data class SeededState(
        val snapshot: DocumentSnapshotV1,
        val photoName: String
    )

    private data class ExpectedRoundTrip(
        val documentId: String,
        val sourceUri: String,
        val displayName: String?,
        val providerMetadata: Map<String, String>,
        val snapshotDigest: String,
        val sourceFingerprint: String,
        val descriptors: Map<String, String>,
        val targetPidAfterImport: Int?,
        val targetDocumentIdAfterImport: String?
    )

    private fun launchViewer(pdfUri: Uri): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(targetContext, MainActivity::class.java)
        )
        // Start from the selector and make the application launch its real
        // OpenDocument contract.  The expected URI is only an oracle for the
        // manifest; no production intent extra is injected.
        composeRule.waitUntil(30_000L) {
            try {
                composeRule.onNodeWithText("Recent Drawings").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        openSourceWithProductionPicker()
        awaitPersistedReadGrant(scenario, pdfUri)
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

    private fun openSourceWithProductionPicker() {
        composeRule.onNodeWithContentDescription(
            targetContext.getString(com.example.myapplication.R.string.open_pdf)
        ).performClick()
        awaitCondition(15_000L) {
            runCatching {
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .rootInActiveWindow?.packageName?.toString()
            }.getOrNull()?.contains("documents", ignoreCase = true) == true
        }
        clickAccessibilityTextIfPresent("SOTAware Stage 9B", 5_000L)
        clickAccessibilityText(
            Stage9BQualificationDocumentsProvider.SOURCE_NAME,
            20_000L
        )
        clickAccessibilityTextIfPresent("Open", 2_000L)
    }

    private fun awaitPersistedReadGrant(
        scenario: ActivityScenario<MainActivity>,
        uri: Uri
    ) {
        awaitCondition(15_000L) {
            var granted = false
            scenario.onActivity { activity ->
                granted = activity.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission
                }
            }
            granted
        }
    }

    /** Adds every persisted annotation domain through the single reducer. */
    private fun seedCompleteState(
        scenario: ActivityScenario<MainActivity>,
        documentId: DocumentId,
        source: DocumentSourceIdentityV1
    ): SeededState {
        val photoName = DocumentPhotoAssetStore(targetContext.filesDir, documentId).use { store ->
            testContext.assets.open("stage7/photos/small_valid_photo.jpg").use { input ->
                store.publishNewPhoto(input, ".jpg").also(store::releasePhotoPublication)
            }
        }
        lateinit var snapshot: DocumentSnapshotV1
        scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[BlueprintViewModel::class.java]
            ensurePage(vm, 0)
            val sessionKey = Any()
            val reducer = AnnotationReducer(
                vm = vm,
                sessionKey = sessionKey,
                currentSessionKey = { sessionKey },
                sessionActivePredicate = { true }
            )
            val suffix = UUID.randomUUID().toString()
            val path = DrawnPath(
                points = listOf(Point(.12f, .18f), Point(.34f, .42f), Point(.62f, .55f)),
                colorArgb = 0xffe0e0e0.toInt(),
                isHighlighter = false,
                strokeWidthRatio = .008f,
                id = "stage9b-path-$suffix"
            )
            val measurement = Measurement(
                p1 = Point(.18f, .22f), p2 = Point(.52f, .68f), text = "12.5 ft",
                id = "stage9b-measurement-$suffix"
            )
            val note = Note(
                x = .42f, y = .33f, text = "Stage 9B note",
                isBold = true, rotation = 23f, fontSizeRatio = .025f,
                id = "stage9b-note-$suffix"
            )
            val pdfShape = Shape(
                x = .65f, y = .30f, rotation = 17f, type = ShapeType.RECTANGLE,
                colorArgb = 0xff33b5e5.toInt(), isFilled = false,
                strokeWidthRatio = .009f, widthRatio = .22f, heightRatio = .14f,
                id = "stage9b-pdf-shape-$suffix"
            )
            val pin = PhotoPin(
                x = .58f, y = .64f, id = "stage9b-pin-$suffix",
                imageFileNames = listOf(photoName)
            )
            val imageNote = PhotoImageNote(
                x = .36f, y = .44f, text = "Image note",
                isBold = false, rotation = -11f, fontSizeRatio = .021f,
                id = "stage9b-image-note-$suffix"
            )
            val imageShape = Shape(
                x = .58f, y = .54f, rotation = -9f, type = ShapeType.CIRCLE,
                colorArgb = 0xfff0ad4e.toInt(), isFilled = false,
                strokeWidthRatio = .007f, widthRatio = .18f, heightRatio = .16f,
                id = "stage9b-image-shape-$suffix"
            )
            assertTrue(reducer.addPdfPath(0, path).changed)
            assertTrue(reducer.addMeasurement(0, measurement).changed)
            assertTrue(reducer.addPdfNote(0, note).changed)
            assertTrue(reducer.addPdfShape(0, pdfShape).changed)
            assertTrue(reducer.addPhotoPin(0, pin).changed)
            assertTrue(reducer.addImageNote(0, pin.id, photoName, imageNote).changed)
            assertTrue(reducer.addImageShape(0, pin.id, photoName, imageShape).changed)
            assertTrue(reducer.setScale(0, PageScale(pointsPerFoot = 144f)).changed)
            snapshot = snapshotFromState(vm, source)
        }
        return SeededState(snapshot, photoName)
    }

    private fun captureSnapshot(
        scenario: ActivityScenario<MainActivity>,
        source: DocumentSourceIdentityV1
    ): DocumentSnapshotV1 {
        lateinit var snapshot: DocumentSnapshotV1
        scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[BlueprintViewModel::class.java]
            snapshot = snapshotFromState(vm, source)
        }
        return snapshot
    }

    private fun ensurePage(vm: BlueprintViewModel, page: Int) {
        if (vm.pagePaths[page] == null) vm.pagePaths[page] = androidx.compose.runtime.mutableStateListOf()
        if (vm.pageMeasurements[page] == null) vm.pageMeasurements[page] = androidx.compose.runtime.mutableStateListOf()
        if (vm.pageNotes[page] == null) vm.pageNotes[page] = androidx.compose.runtime.mutableStateListOf()
        if (vm.pagePhotoPins[page] == null) vm.pagePhotoPins[page] = androidx.compose.runtime.mutableStateListOf()
        if (vm.pageShapes[page] == null) vm.pageShapes[page] = androidx.compose.runtime.mutableStateListOf()
    }

    private fun backToSelector() {
        composeRule.waitUntil(10_000L) {
            composeRule.onAllNodes(hasContentDescription("Back")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(hasContentDescription("Back"))[0].performClick()
        composeRule.waitUntil(10_000L) {
            composeRule.onAllNodes(hasContentDescription("Back")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(hasContentDescription("Back"))[0].performClick()
        composeRule.waitUntil(10_000L) {
            try {
                composeRule.onNodeWithText("Recent Drawings").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    private fun openRecentOptionsAndChoose(label: String) {
        composeRule.waitUntil(10_000L) {
            composeRule.onAllNodes(hasContentDescription("Options")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(hasContentDescription("Options"))[0].performClick()
        composeRule.onNodeWithText(label, useUnmergedTree = true).performClick()
    }

    private fun completeCreateDocument() {
        // DocumentsUI may start in its last directory. Selecting this provider
        // is an accessibility action on the real picker, not a direct URI.
        clickAccessibilityTextIfPresent("SOTAware Stage 9B", 5_000L)
        clickAccessibilityText("Save", 15_000L)
        awaitCondition(30_000L) {
            findExportedArtifact()?.let { it.size > 0L } == true
        }
    }

    private fun openScreenshotAction() {
        if (clickOnlyDisplayed(composeRule.onAllNodes(
                hasContentDescription("Screenshot"), useUnmergedTree = true
            ))) return
        composeRule.onNodeWithContentDescription("More actions", useUnmergedTree = true).performClick()
        composeRule.waitUntil(10_000L) {
            clickOnlyDisplayed(composeRule.onAllNodes(
                androidx.compose.ui.test.hasText("Screenshot"), useUnmergedTree = true
            ))
        }
    }

    private fun clickOnlyDisplayed(
        nodes: androidx.compose.ui.test.SemanticsNodeInteractionCollection
    ): Boolean {
        val displayed = (0 until nodes.fetchSemanticsNodes().size).filter { index ->
            try {
                nodes[index].assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        if (displayed.isEmpty()) return false
        assertEquals("expected one visible action", 1, displayed.size)
        nodes[displayed.single()].performClick()
        return true
    }

    private fun completeOpenDocument(fileName: String) {
        clickAccessibilityTextIfPresent("SOTAware Stage 9B", 5_000L)
        clickAccessibilityText(fileName, 20_000L)
        // Some DocumentsUI builds show an explicit Open button, while others
        // return immediately after a single file row is selected.
        clickAccessibilityTextIfPresent("Open", 2_000L)
    }

    private fun inspectCurrentBundle(file: File, expected: Map<String, PhotoDescriptor>) {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(FileInputStream(file)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertTrue("unsafe ZIP entry", entry.name == entry.name.trim('/') && !entry.name.contains(".."))
                val bytes = zip.readBounded(12L * 1024L * 1024L)
                entries[entry.name] = bytes
                zip.closeEntry()
            }
        }
        assertTrue(entries.containsKey("manifest.json"))
        assertTrue(entries.containsKey("snapshot.json"))
        val manifestText = entries.getValue("manifest.json").toString(Charsets.UTF_8)
        val manifest = JsonParser.parseString(manifestText).asJsonObject
        assertEquals(SOTAWARE_BUNDLE_FORMAT_VERSION, manifest.get("formatVersion").asInt)
        assertEquals(SOTAWARE_BUNDLE_SNAPSHOT_SCHEMA_VERSION, manifest.get("snapshotSchemaVersion").asInt)
        assertTrue(manifest.getAsJsonArray("photos").size() == expected.size)
        assertFalse(manifestText.contains("base64", ignoreCase = true))
        assertFalse(manifestText.contains("\"bytes\"", ignoreCase = true))
        assertFalse(entries.getValue("snapshot.json").toString(Charsets.UTF_8).contains("base64", ignoreCase = true))
        expected.forEach { (name, descriptor) ->
            val bytes = entries["photos/$name"]
            assertNotNull("missing photo ZIP entry $name", bytes)
            assertEquals(descriptor.byteCount, bytes!!.size.toLong())
            assertEquals(descriptor.sha256, sha256(bytes))
        }
    }

    private fun writeExpectedRoundTrip(
        snapshot: DocumentSnapshotV1,
        entry: com.example.myapplication.stage2.DocumentManifestEntryV1,
        descriptors: Map<String, PhotoDescriptor>
    ) {
        val properties = Properties()
        properties["documentId"] = entry.documentId.value
        properties["sourceUri"] = entry.sourceUri
        properties["displayName"] = entry.displayName.orEmpty()
        properties["providerMetadataCount"] = entry.providerMetadata.size.toString()
        entry.providerMetadata.toSortedMap().entries.forEachIndexed { index, (key, value) ->
            properties["providerMetadata.$index.key"] = key
            properties["providerMetadata.$index.value"] = value
        }
        properties["snapshotDigest"] = RemoteManifestCodec.snapshotDigest(snapshot)
        properties["sourceFingerprint"] = fingerprintWire(entry.sourceFingerprint)
        properties["photoCount"] = descriptors.size.toString()
        descriptors.toSortedMap().entries.forEachIndexed { index, (name, descriptor) ->
            properties["photo.$index.name"] = name
            properties["photo.$index.descriptor"] = descriptorWire(descriptor)
        }
        val bytes = ByteArrayOutputStream().use { output ->
            properties.store(output, "Stage 9B native SAF expected state")
            output.toByteArray()
        }
        Stage9BProviderFixtureAccess.writeExpected(bytes)
    }

    private fun readExpectedRoundTrip(): ExpectedRoundTrip {
        val properties = Properties()
        ByteArrayInputStream(Stage9BProviderFixtureAccess.readExpected()).use { properties.load(it) }
        val count = properties.getProperty("photoCount").toInt()
        val descriptors = LinkedHashMap<String, String>(count)
        repeat(count) { index ->
            descriptors[properties.getProperty("photo.$index.name")] =
                properties.getProperty("photo.$index.descriptor")
        }
        return ExpectedRoundTrip(
            documentId = requireNotNull(properties.getProperty("documentId")),
            sourceUri = requireNotNull(properties.getProperty("sourceUri")),
            displayName = properties.getProperty("displayName").orEmpty().ifEmpty { null },
            providerMetadata = buildMap {
                val metadataCount = properties.getProperty("providerMetadataCount", "0").toInt()
                repeat(metadataCount) {
                    put(
                        requireNotNull(properties.getProperty("providerMetadata.$it.key")),
                        requireNotNull(properties.getProperty("providerMetadata.$it.value"))
                    )
                }
            },
            snapshotDigest = properties.getProperty("snapshotDigest"),
            sourceFingerprint = properties.getProperty("sourceFingerprint"),
            descriptors = descriptors,
            targetPidAfterImport = properties.getProperty("targetPidAfterImport")?.toIntOrNull(),
            targetDocumentIdAfterImport = properties.getProperty("targetDocumentIdAfterImport")
        )
    }

    private fun assertExpectedRestoredState(
        expected: ExpectedRoundTrip,
        entry: com.example.myapplication.stage2.DocumentManifestEntryV1,
        snapshot: DocumentSnapshotV1,
        expectedTargetDocumentId: String
    ) {
        // Imported content preserves every annotation ID, but exported metadata
        // cannot take over the fresh installation's verified local association.
        assertEquals(expectedTargetDocumentId, entry.documentId.value)
        assertEquals(expected.sourceUri, entry.sourceUri)
        assertEquals(expected.displayName, entry.displayName)
        assertEquals(expected.providerMetadata, entry.providerMetadata)
        assertEquals(expected.sourceFingerprint, fingerprintWire(entry.sourceFingerprint))
        assertEquals(expected.sourceUri, snapshot.source.sourceUri)
        assertEquals(expected.displayName, snapshot.source.displayName)
        assertEquals(expected.providerMetadata, snapshot.source.providerMetadata)
        assertEquals(expected.snapshotDigest, RemoteManifestCodec.snapshotDigest(snapshot))
        assertSnapshotHasEveryPersistedDomain(snapshot)
    }

    /** The digest is necessary but this explicit oracle makes missing domains visible. */
    private fun assertSnapshotHasEveryPersistedDomain(snapshot: DocumentSnapshotV1) {
        assertEquals("round-trip fixture must contain one page", setOf(0), snapshot.pages.keys)
        val page = requireNotNull(snapshot.pages[0])
        assertEquals(1, page.paths.size)
        assertEquals(1, page.measurements.size)
        assertEquals(1, page.notes.size)
        assertEquals(1, page.shapes.size)
        assertEquals(1, page.photoPins.size)
        assertEquals(144f, requireNotNull(page.scale).pointsPerFoot, 0.0001f)
        assertTrue(page.paths.single().id.isNotBlank())
        assertTrue(page.measurements.single().id.isNotBlank())
        assertTrue(page.notes.single().id.isNotBlank())
        assertTrue(page.shapes.single().id.isNotBlank())
        val pin = page.photoPins.single()
        assertTrue(pin.id.isNotBlank())
        assertEquals(1, pin.imageFileNames.size)
        val photoName = pin.imageFileNames.single()
        assertEquals(setOf(photoName), pin.imageNotes.keys)
        assertEquals(setOf(photoName), pin.imageShapes.keys)
        assertEquals(1, pin.imageNotes.getValue(photoName).size)
        assertEquals(1, pin.imageShapes.getValue(photoName).size)
        assertTrue(pin.imageNotes.getValue(photoName).single().id.isNotBlank())
        assertTrue(pin.imageShapes.getValue(photoName).single().id.isNotBlank())
    }

    private fun recordImportedTarget(context: android.content.Context, pid: Int, documentId: DocumentId) {
        // The context argument intentionally documents that this is the target
        // process observation; the oracle itself remains in the test APK.
        check(context.packageName == targetContext.packageName)
        val properties = Properties()
        ByteArrayInputStream(Stage9BProviderFixtureAccess.readExpected()).use { properties.load(it) }
        properties["targetPidAfterImport"] = pid.toString()
        properties["targetDocumentIdAfterImport"] = documentId.value
        val bytes = ByteArrayOutputStream().use { output ->
            properties.store(output, "Stage 9B native SAF expected state")
            output.toByteArray()
        }
        Stage9BProviderFixtureAccess.writeExpected(bytes)
    }

    private fun scenarioHistoryEpoch(scenario: ActivityScenario<MainActivity>): Long {
        var epoch: Long? = null
        scenario.onActivity { epoch = ViewModelProvider(it)[BlueprintViewModel::class.java].annotationHistoryEpoch() }
        return requireNotNull(epoch) { "Activity callback did not expose a history epoch" }
    }

    private fun scenarioHistoryCheckpoint(
        scenario: ActivityScenario<MainActivity>
    ): AnnotationReducer.HistoryOwner.Checkpoint {
        lateinit var checkpoint: AnnotationReducer.HistoryOwner.Checkpoint
        scenario.onActivity {
            checkpoint = ViewModelProvider(it)[BlueprintViewModel::class.java]
                .annotationHistory.captureCheckpoint()
        }
        return checkpoint
    }

    private fun scenarioCanUndo(scenario: ActivityScenario<MainActivity>): Boolean {
        var result = false
        scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[BlueprintViewModel::class.java]
            val key = Any()
            result = AnnotationReducer(
                vm = vm,
                sessionKey = key,
                currentSessionKey = { key },
                sessionActivePredicate = { true }
            ).canUndo(0)
        }
        return result
    }

    private fun writeRetiredBundle(pdfUri: Uri): String {
        val snapshotBytes = """
            {"schemaVersion":1,"snapshotRevision":0,"source":{"sourceUri":"${pdfUri}","displayName":"stage9b-source.pdf","providerMetadata":{"authority":"${STAGE9B_DOCUMENTS_AUTHORITY}"}},"pages":{}}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val sourceInfo = Stage9BProviderFixtureAccess.fileInfo(
            Stage9BQualificationDocumentsProvider.SOURCE_NAME
        )
        val sourceFingerprint = SourceFingerprint(
            algorithm = SourceFingerprint.SHA256_ALGORITHM,
            digestHex = sourceInfo.sha256,
            byteCount = sourceInfo.size
        )
        val manifest = JsonObject().apply {
            addProperty("formatVersion", 1)
            addProperty("snapshotSchemaVersion", 1)
            addProperty("exportedDocumentId", UUID.randomUUID().toString())
            add("source", JsonObject().apply {
                addProperty("sourceUri", pdfUri.toString())
                addProperty("displayName", "stage9b-source.pdf")
                add("providerMetadata", JsonObject().apply {
                    addProperty("authority", STAGE9B_DOCUMENTS_AUTHORITY)
                })
                add("sourceFingerprint", JsonObject().apply {
                    addProperty("algorithm", sourceFingerprint.algorithm)
                    addProperty("digestHex", sourceFingerprint.digestHex)
                    addProperty("byteCount", sourceFingerprint.byteCount)
                })
            })
            add("snapshot", JsonObject().apply {
                addProperty("revision", 0)
                addProperty("byteCount", snapshotBytes.size)
                addProperty("sha256", sha256(snapshotBytes))
            })
            add("photos", com.google.gson.JsonArray())
        }
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("snapshot.json"))
                zip.write(snapshotBytes)
                zip.closeEntry()
            }
            output.toByteArray()
        }
        Stage9BProviderFixtureAccess.writeRetired(bytes)
        assertTrue(
            "provider retired-input read did not reproduce the bounded fixture",
            Stage9BProviderFixtureAccess.readRetired().contentEquals(bytes)
        )
        return Stage9BQualificationDocumentsProvider.RETIRED_NAME
    }

    private fun requireProviderReady(): Uri {
        return try {
            Stage9BProviderFixtureAccess.prepare()
        } catch (error: Throwable) {
            throw AssertionError(
                "Stage9BQualificationDocumentsProvider is not configured for the instrumentation APK",
                error
            )
        }
    }

    private fun awaitManifestEntry(uri: Uri): com.example.myapplication.stage2.DocumentManifestEntryV1 {
        lateinit var result: com.example.myapplication.stage2.DocumentManifestEntryV1
        awaitCondition(30_000L) {
            val loaded = runBlocking { LocalDocumentRepository(targetContext).readManifest() }
            if (loaded is ManifestReadResult.Loaded) {
                loaded.entries.firstOrNull { it.sourceUri == uri.toString() }?.let {
                    result = it
                    true
                } ?: false
            } else false
        }
        return result
    }

    private fun sourceFor(entry: com.example.myapplication.stage2.DocumentManifestEntryV1) =
        DocumentSourceIdentityV1(entry.sourceUri, entry.displayName, entry.providerMetadata)

    private fun awaitImportedSnapshot(
        scenario: ActivityScenario<MainActivity>,
        expectedDigest: String,
        source: DocumentSourceIdentityV1
    ) {
        awaitCondition(30_000L) {
            var matches = false
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[BlueprintViewModel::class.java]
                matches = try {
                    RemoteManifestCodec.snapshotDigest(snapshotFromState(vm, source)) == expectedDigest
                } catch (_: Throwable) {
                    false
                }
            }
            matches
        }
    }

    private fun clearOwnedProviderArtifacts() {
        Stage9BProviderFixtureAccess.clearArtifacts()
    }

    private fun awaitExportArtifact(): File {
        lateinit var artifact: File
        awaitCondition(30_000L) {
            findExportedArtifact()?.let {
                if (it.size > 0L) {
                    artifact = Stage9BProviderFixtureAccess.copyToCache(targetContext, it.name)
                    true
                } else false
            } ?: false
        }
        return artifact
    }

    private fun awaitPdfExportArtifact(): File {
        lateinit var artifact: File
        awaitCondition(30_000L) {
            findPdfExportArtifact()?.let {
                if (it.size > 0L) {
                    artifact = Stage9BProviderFixtureAccess.copyToCache(targetContext, it.name)
                    true
                } else false
            } ?: false
        }
        return artifact
    }

    private fun findExportedArtifact(): Stage9BProviderFixtureAccess.FileInfo? =
        Stage9BProviderFixtureAccess.listNames()
            .filter {
                it.extensionEquals("sotaware") &&
                    it != Stage9BQualificationDocumentsProvider.RETIRED_NAME
            }
            .map { Stage9BProviderFixtureAccess.fileInfo(it) }
            .maxByOrNull { it.name }

    private fun findPdfExportArtifact(): Stage9BProviderFixtureAccess.FileInfo? =
        Stage9BProviderFixtureAccess.listNames()
            .filter {
                it.extensionEquals("pdf") &&
                    it != Stage9BQualificationDocumentsProvider.SOURCE_NAME
            }
            .map { Stage9BProviderFixtureAccess.fileInfo(it) }
            .maxByOrNull { it.name }

    private fun String.extensionEquals(extension: String): Boolean =
        substringAfterLast('.', missingDelimiterValue = "")
            .equals(extension, ignoreCase = true)

    private data class PdfSize(val width: Int, val height: Int, val pages: Int)

    private fun pdfDimensions(file: File): PdfSize {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            PdfRenderer(descriptor).use { renderer ->
                assertTrue("exported PDF has no pages", renderer.pageCount > 0)
                renderer.openPage(0).use { page -> PdfSize(page.width, page.height, renderer.pageCount) }
            }
        } finally {
            runCatching { descriptor.close() }
        }
    }

    /** Render a bounded page for an image oracle; never retain a full-size page. */
    private fun renderPdfPage(file: File, pageIndex: Int): Bitmap {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            PdfRenderer(descriptor).use { renderer ->
                assertTrue("exported PDF has no page $pageIndex", pageIndex in 0 until renderer.pageCount)
                renderer.openPage(pageIndex).use { page ->
                    val maxPixels = 1_600_000L
                    val sourcePixels = page.width.toLong() * page.height.toLong()
                    val scale = if (sourcePixels > maxPixels) {
                        sqrt(maxPixels.toDouble() / sourcePixels.toDouble()).coerceAtMost(1.0)
                    } else 1.0
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        } finally {
            runCatching { descriptor.close() }
        }
    }

    private data class ColorStats(
        var count: Int = 0,
        var minX: Int = Int.MAX_VALUE,
        var minY: Int = Int.MAX_VALUE,
        var maxX: Int = Int.MIN_VALUE,
        var maxY: Int = Int.MIN_VALUE,
        var sumX: Long = 0L,
        var sumY: Long = 0L
    ) {
        val centerX: Float get() = if (count == 0) 0f else sumX.toFloat() / count
        val centerY: Float get() = if (count == 0) 0f else sumY.toFloat() / count
        val width: Int get() = if (count == 0) 0 else maxX - minX + 1
        val height: Int get() = if (count == 0) 0 else maxY - minY + 1
    }

    private fun colorStats(
        bitmap: Bitmap,
        targetColor: Int,
        tolerance: Int,
        left: Int = 0,
        top: Int = 0,
        right: Int = bitmap.width,
        bottom: Int = bitmap.height
    ): ColorStats {
        val stats = ColorStats()
        val startX = left.coerceIn(0, bitmap.width)
        val startY = top.coerceIn(0, bitmap.height)
        val endX = right.coerceIn(startX, bitmap.width)
        val endY = bottom.coerceIn(startY, bitmap.height)
        val targetRed = targetColor shr 16 and 0xff
        val targetGreen = targetColor shr 8 and 0xff
        val targetBlue = targetColor and 0xff
        for (y in startY until endY) {
            for (x in startX until endX) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel ushr 24 < 128) continue
                val distance = kotlin.math.abs((pixel shr 16 and 0xff) - targetRed) +
                    kotlin.math.abs((pixel shr 8 and 0xff) - targetGreen) +
                    kotlin.math.abs((pixel and 0xff) - targetBlue)
                if (distance <= tolerance) {
                    stats.count++
                    stats.minX = minOf(stats.minX, x)
                    stats.minY = minOf(stats.minY, y)
                    stats.maxX = maxOf(stats.maxX, x)
                    stats.maxY = maxOf(stats.maxY, y)
                    stats.sumX += x
                    stats.sumY += y
                }
            }
        }
        return stats
    }

    private fun assertColorNear(
        bitmap: Bitmap,
        targetColor: Int,
        x: Float,
        y: Float,
        radius: Int,
        tolerance: Int
    ) {
        val centerX = x.toInt()
        val centerY = y.toInt()
        val stats = colorStats(
            bitmap,
            targetColor,
            tolerance,
            centerX - radius,
            centerY - radius,
            centerX + radius + 1,
            centerY + radius + 1
        )
        assertTrue(
            "expected color %#08x near (%d,%d), found %d pixels".format(
                Locale.ROOT, targetColor, centerX, centerY, stats.count
            ),
            stats.count > 0
        )
    }

    private fun assertRenderedPdfContainsIndependentMarkupAndPhotoEvidence(
        file: File,
        photoName: String
    ) {
        assertTrue("seeded photo reference is empty", photoName.isNotBlank())
        val blueprint = renderPdfPage(file, 0)
        try {
            val width = blueprint.width.toFloat()
            val height = blueprint.height.toFloat()
            val centerX = .65f * width
            val centerY = .30f * height
            val halfWidth = .22f * width / 2f
            val halfHeight = .14f * height / 2f
            val radians = Math.toRadians(17.0)
            val cosRotation = cos(radians).toFloat()
            val sinRotation = sin(radians).toFloat()
            val corners = listOf(
                -halfWidth to -halfHeight,
                halfWidth to -halfHeight,
                halfWidth to halfHeight,
                -halfWidth to halfHeight
            ).map { (localX, localY) ->
                (centerX + localX * cosRotation - localY * sinRotation) to
                    (centerY + localX * sinRotation + localY * cosRotation)
            }
            val cyan = 0xff33b5e5.toInt()
            corners.forEach { (x, y) ->
                assertColorNear(
                    blueprint,
                    cyan,
                    x,
                    y,
                    radius = maxOf(8, (minOf(width, height) * .025f).toInt()),
                    tolerance = 90
                )
            }
            val shapeStats = colorStats(
                blueprint,
                cyan,
                tolerance = 90,
                left = (width * .42f).toInt(),
                top = (height * .10f).toInt(),
                right = (width * .88f).toInt(),
                bottom = (height * .52f).toInt()
            )
            assertTrue("exported rectangle color was not materially rendered", shapeStats.count >= 24)
            assertTrue("exported rectangle width lost ratio geometry", shapeStats.width >= width * .12f)
            assertTrue("exported rectangle height lost rotated geometry", shapeStats.height >= height * .07f)
        } finally {
            blueprint.recycle()
        }

        val photoPage = renderPdfPage(file, 1)
        try {
            val width = photoPage.width.toFloat()
            val height = photoPage.height.toFloat()
            val margin = 20f
            val headerSize = (height / 30f).coerceIn(18f, 36f)
            val imageTop = margin + headerSize + margin
            val imageBottom = (height - margin).coerceAtLeast(imageTop + 1f)
            val orange = colorStats(
                photoPage,
                0xfff0ad4e.toInt(),
                tolerance = 105,
                left = (width * .20f).toInt(),
                top = imageTop.toInt(),
                right = (width * .90f).toInt(),
                bottom = imageBottom.toInt()
            )
            assertTrue("photo appendix shape was not rendered", orange.count >= 10)
            assertTrue("photo appendix shape is outside its expected horizontal placement", orange.centerX / width in .25f..0.85f)
            assertTrue("photo appendix shape is outside its expected image placement", orange.centerY >= imageTop && orange.centerY <= imageBottom)

            val yellow = colorStats(
                photoPage,
                android.graphics.Color.YELLOW,
                tolerance = 120,
                left = (width * .12f).toInt(),
                top = imageTop.toInt(),
                right = (width * .75f).toInt(),
                bottom = imageBottom.toInt()
            )
            assertTrue("photo appendix note text was not rendered", yellow.count >= 4)
            assertTrue("photo appendix note text has no measurable bounds", yellow.width >= 3 && yellow.height >= 3)
            assertTrue("photo appendix note text is outside its expected placement", yellow.centerY >= imageTop && yellow.centerY <= imageBottom)
        } finally {
            photoPage.recycle()
        }
    }

    private fun assumePhase(required: String) {
        val phase = InstrumentationRegistry.getArguments().getString("stage9b.phase").orEmpty()
        assumeTrue("set stage9b.phase=$required for this workflow invocation", phase == required)
    }

    private fun awaitCondition(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var lastError: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                if (condition()) return
            } catch (error: Throwable) {
                lastError = error
            }
            SystemClock.sleep(100L)
        }
        if (lastError != null) throw AssertionError("condition did not become true", lastError)
        throw AssertionError("condition did not become true within ${timeoutMillis}ms")
    }

    private fun clickAccessibilityText(label: String, timeoutMillis: Long) {
        awaitCondition(timeoutMillis) {
            findAccessibilityNode(label, exact = true)?.let { node ->
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@awaitCondition true
                    current = current.parent
                }
            }
            false
        }
    }

    private fun clickAccessibilityTextIfPresent(label: String, timeoutMillis: Long) {
        try {
            clickAccessibilityText(label, timeoutMillis)
        } catch (_: AssertionError) {
            // DocumentsUI's open action is optional across API-level variants.
        }
    }

    private fun performAndAwaitToast(label: String, timeoutMillis: Long, action: () -> Unit) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // A Toast is a notification event, not a stable node in the active
        // DocumentsUI/app tree. Start listening before the actual SAF action.
        val event = automation.executeAndWaitForEvent(
            Runnable { action() },
            android.app.UiAutomation.AccessibilityEventFilter { candidate ->
                candidate.eventType == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                    candidate.packageName?.toString() == targetContext.packageName &&
                    candidate.text.any { it.toString().contains(label) }
            },
            timeoutMillis
        )
        try {
            assertTrue("production Toast did not contain the expected result",
                event.text.any { it.toString().contains(label) })
        } finally {
            @Suppress("DEPRECATION")
            event.recycle()
        }
    }

    private fun findAccessibilityNode(label: String, exact: Boolean = false): AccessibilityNodeInfo? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // The real foreground picker is accessible without enabling the
        // optional all-interactive-windows enumeration service flag.
        val roots = listOfNotNull(runCatching { automation.rootInActiveWindow }.getOrNull())
        return roots.asSequence().flatMap { root ->
            root.findAccessibilityNodeInfosByText(label).asSequence() + sequenceOf(root).filter {
                it.text?.toString()?.contains(label, ignoreCase = true) == true ||
                    it.contentDescription?.toString()?.contains(label, ignoreCase = true) == true
            }
        }.filter { node ->
            // A filename preview action can contain the same text as the
            // selectable row. Never choose that substring match for a click.
            !exact || node.text?.toString()?.equals(label, ignoreCase = true) == true ||
                node.contentDescription?.toString()?.equals(label, ignoreCase = true) == true
        }.sortedBy { node ->
            if (node.text?.toString()?.equals(label, ignoreCase = true) == true) 0 else 1
        }.firstOrNull()
    }

    private fun java.io.InputStream.readBounded(limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            assertTrue("ZIP entry exceeds test inspection limit", total <= limit)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun descriptorWire(descriptor: PhotoDescriptor): String = listOf(
        descriptor.byteCount,
        descriptor.sha256,
        descriptor.mimeType,
        descriptor.width,
        descriptor.height
    ).joinToString("|")

    private fun fingerprintWire(fingerprint: SourceFingerprint?): String = fingerprint?.let {
        listOf(it.algorithm, it.digestHex, it.byteCount).joinToString("|")
    }.orEmpty()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val testContext
        get() = InstrumentationRegistry.getInstrumentation().context

    companion object {
        private const val PHASE_EXPORT = "export"
        private const val PHASE_IMPORT = "import"
        private const val PHASE_RELAUNCH = "relaunch"
        private const val PHASE_RETIRED = "retired-format"
        private const val PHASE_PDF_EXPORT = "pdf-export"
        private const val STAGE9B_DOCUMENTS_AUTHORITY = NativeFixtureAssets.STAGE9B_DOCUMENTS_AUTHORITY
    }
}
