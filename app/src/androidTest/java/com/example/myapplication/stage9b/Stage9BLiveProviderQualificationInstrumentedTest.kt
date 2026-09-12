package com.example.myapplication.stage9b

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
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
import com.example.myapplication.stage2.DocumentManifestEntryV1
import com.example.myapplication.stage2.LocalDocumentRepository
import com.example.myapplication.stage2.ManifestReadResult
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage4.DownloadResult
import com.example.myapplication.stage4.FileSyncMetadataStore
import com.example.myapplication.stage4.RemoteLookup
import com.example.myapplication.stage4.RemoteSnapshotEnvelope
import com.example.myapplication.stage4.ScopeRemoteMutationLease
import com.example.myapplication.stage4.SyncMetadata
import com.example.myapplication.stage4.SyncMetadataStore
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage4.UploadRequest
import com.example.myapplication.stage4.UploadResult
import com.example.myapplication.stage5.DocumentPhotoAssetStore
import com.example.myapplication.stage5.PhotoDescriptor
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage8.AnnotationReducer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Properties
import java.util.UUID

/**
 * Opt-in live-provider qualification.  The default Android test suite skips
 * this test before it launches an Activity or touches the fixture provider.
 *
 * This class deliberately does not inject auth, a Drive gateway, or Compose
 * content.  Human-operated Credential Manager/Drive consent can therefore
 * establish the real session, while the test drives only the bounded SAF and
 * document UI after authorization is visible.
 *
 * Required runner argument:
 *   stage9b.live=true
 * Optional deterministic conflict smoke:
 *   stage9b.live.conflict=true
 */
@RunWith(AndroidJUnit4::class)
class Stage9BLiveProviderQualificationInstrumentedTest {

    @Test
    fun liveProvider_roundTripAndUnchangedPhotoQualification() {
        requireLiveOptIn()

        val runId = UUID.randomUUID().toString()
        var cleanup = CleanupRecord(runId = runId)
        var scenario: ActivityScenario<MainActivity>? = null
        var photoCapture: PhotoAssetCapture? = null

        try {
            val pdfUri = prepareProviderFixture()
            val liveScenario = ActivityScenario.launch<MainActivity>(
                Intent(targetContext, MainActivity::class.java)
            )
            scenario = liveScenario
            val viewModel = awaitViewModel(liveScenario)
            val driveManager = viewModel.getOrCreateDriveSyncManager(targetContext)

            awaitLiveAuthorization(driveManager)

            // This intentionally creates a unique folder directly under the
            // Drive account root.  It never calls createRootBackupFolder() and
            // never creates or reuses "SOTAware Construct Backups".
            val disposable = createDisposableRootFolder(driveManager, runId) { created ->
                cleanup = cleanup.copy(
                    folderId = created.id,
                    folderName = created.name
                )
                writeCleanupRecord(cleanup)
            }

            val generation = driveManager.authorizationStatus.value.generation
            check(generation > 0L) { "live authorization generation is not positive" }
            check(
                driveManager.setBackupFolder(generation, disposable.id, disposable.name)
            ) { "could not install the disposable backup folder" }
            awaitConfiguredFolder(driveManager, disposable.id)
            logState(
                "disposable root configured: runId=$runId folderId=${disposable.id} " +
                    "folderName=${disposable.name}"
            )

            openSourceWithProductionPicker(pdfUri)
            val entry = awaitManifestEntry(pdfUri)
            val source = DocumentSourceIdentityV1(
                sourceUri = entry.sourceUri,
                displayName = entry.displayName,
                providerMetadata = entry.providerMetadata
            )
            val sourceFingerprint = requireNotNull(entry.sourceFingerprint) {
                "live provider fixture has no source fingerprint"
            }

            assertSelectedIdentity(liveScenario, entry)
            seedCompleteCurrentDomains(liveScenario, entry.documentId)
            addRealUiNote(liveScenario, "Stage 9B live UI edit")
            val firstLocalSnapshot = captureSnapshot(liveScenario, source)
            assertCompleteCurrentDomains(firstLocalSnapshot)

            photoCapture = DocumentPhotoAssetStore(targetContext.filesDir, entry.documentId)
                .use { it.capturePhotoAssets(firstLocalSnapshot) }
            assertTrue("the complete fixture must contain photo bytes", photoCapture!!.assets.isNotEmpty())

            val accountRoot = requireNotNull(driveManager.currentSyncAccountRoot()) {
                "the production Drive account/root authority is unavailable"
            }
            assertEquals(disposable.id, accountRoot.second)
            val scope = SyncScope(
                accountId = accountRoot.first,
                backupRootId = disposable.id,
                documentId = entry.documentId
            )
            cleanup = cleanup.copy(documentId = entry.documentId.value)
            writeCleanupRecord(cleanup)

            clickSyncNow()
            val firstMetadata = awaitAcceptedMetadata(scope)
            val firstRemote = awaitRemoteEvidence(
                driveManager = driveManager,
                scope = scope,
                sourceFingerprint = sourceFingerprint,
                expectedSnapshot = firstLocalSnapshot,
                expectedAssets = photoCapture!!.assets
            )
            assertEquals(firstMetadata.remoteReference, firstRemote.reference)
            assertEquals(firstMetadata.acceptedCursor, firstRemote.cursor)
            cleanup = cleanup.withRemote(firstRemote)
            writeCleanupRecord(cleanup)
            logState(
                "first accepted upload: runId=$runId documentFolderId=" +
                    "${firstRemote.reference.folderId} manifestId=${firstRemote.reference.snapshotFileId} " +
                    "assetCount=${firstRemote.photoDescriptors.size}"
            )

            // An annotation-only production UI mutation must not replace the
            // immutable photo bytes or their content-addressed remote IDs.
            returnToViewer()
            assertSelectedIdentity(liveScenario, entry)
            addRealUiNote(liveScenario, "Stage 9B live second annotation")
            val secondLocalSnapshot = captureSnapshot(liveScenario, source)
            assertEquals(
                "the second live edit must remain an annotation-only change",
                firstLocalSnapshot.source,
                secondLocalSnapshot.source
            )
            assertTrue(
                "the second live edit did not change the canonical snapshot",
                secondLocalSnapshot != firstLocalSnapshot
            )

            clickSyncNow()
            val secondMetadata = awaitAcceptedMetadata(
                scope,
                priorCursor = firstMetadata.acceptedCursor
            )
            val secondRemote = awaitRemoteEvidence(
                driveManager = driveManager,
                scope = scope,
                sourceFingerprint = sourceFingerprint,
                expectedSnapshot = secondLocalSnapshot,
                expectedAssets = photoCapture!!.assets
            )
            assertEquals(secondMetadata.remoteReference, secondRemote.reference)
            assertEquals(secondMetadata.acceptedCursor, secondRemote.cursor)
            assertRemotePhotoIdentityStable(firstRemote, secondRemote)
            cleanup = cleanup.withRemote(secondRemote)
            writeCleanupRecord(cleanup)
            logState(
                "unchanged-photo proof: remote asset IDs/descriptors/streamed bytes " +
                    "remain identical; HTTP PUT byte count is not measured"
            )

            if (liveConflictEnabled()) {
                runLiveConflictSmoke(
                    driveManager = driveManager,
                    scope = scope,
                    sourceFingerprint = sourceFingerprint,
                    displayName = entry.displayName ?: "stage9b-source.pdf",
                    baselineSnapshot = secondLocalSnapshot,
                    assets = photoCapture!!.assets,
                    accepted = secondRemote
                )
            } else {
                logState(
                    "live remote-conflict smoke: NOT RUN " +
                        "(set stage9b.live.conflict=true to enable)"
                )
            }
        } finally {
            photoCapture?.close()
            scenario?.close()
            writeCleanupRecord(cleanup)
            logState(
                "cleanup record retained for root: runId=${cleanup.runId} " +
                    "folderId=${cleanup.folderId ?: "<none>"} " +
                    "documentId=${cleanup.documentId ?: "<none>"}"
            )
        }
    }

    private fun requireLiveOptIn() {
        // This is deliberately the first operation in the test body.  A
        // normal suite invocation must not launch an Activity, prepare SAF,
        // read auth state, or create any local/remote fixture.
        val enabled = "true".equals(
            InstrumentationRegistry.getArguments().getString(ARG_LIVE), ignoreCase = true)
        assumeTrue(
            "live provider qualification is opt-in; pass -e $ARG_LIVE true",
            enabled
        )
    }

    private fun prepareProviderFixture(): Uri {
        Stage9BProviderFixtureAccess.clearArtifacts()
        return try {
            Stage9BProviderFixtureAccess.prepare()
        } catch (failure: Throwable) {
            throw AssertionError(
                "Stage9BQualificationDocumentsProvider is unavailable in the instrumentation APK",
                failure
            )
        }
    }

    private fun awaitViewModel(
        scenario: ActivityScenario<MainActivity>
    ): BlueprintViewModel {
        lateinit var result: BlueprintViewModel
        scenario.onActivity { activity ->
            result = ViewModelProvider(activity)[BlueprintViewModel::class.java]
        }
        return result
    }

    private fun awaitLiveAuthorization(
        driveManager: com.example.myapplication.DriveSyncManager
    ) {
        // Do not install a Compose test dispatcher in this live-provider test.
        // Real Credential Manager and AuthorizationClient callbacks must resume
        // under the production Activity's main-thread coroutine context.
        awaitUiText("Recent Drawings", 30_000L)
        if (!driveManager.authorizationStatus.value.isAuthorized) {
            openDriveSettings()
            // Normal session restoration may finish during navigation. Do not
            // wait for a sign-in button that correctly disappears on success.
            awaitCondition(10_000L) {
                driveManager.authorizationStatus.value.isAuthorized ||
                    tryClickAccessibilityText("Sign in with Google")
            }
        }
        var lastState: String? = null
        awaitCondition(AUTH_TIMEOUT_MILLIS) {
            val status = driveManager.authorizationStatus.value
            val state = "authorized=${status.isAuthorized},generation=${status.generation}," +
                "backupConfigured=${status.backupFolder != null}"
            if (state != lastState) {
                lastState = state
                logState("live auth state: $state")
            }
            status.isAuthorized && status.generation > 0L
        }
        backToSelector()
    }

    private fun createDisposableRootFolder(
        driveManager: com.example.myapplication.DriveSyncManager,
        runId: String,
        onCreated: (com.example.myapplication.DriveSyncManager.DriveFolder) -> Unit
    ): com.example.myapplication.DriveSyncManager.DriveFolder {
        val name = "Stage9B LIVE disposable $runId"
        val created = runBlocking {
            driveManager.createFolder(name = name, parentId = "root")
        } ?: throw AssertionError("production Drive folder creation returned no folder")
        onCreated(created) // Retain the returned identity even if subsequent validation fails.
        assertTrue("Drive returned a blank folder ID", created.id.isNotBlank())
        assertEquals(name, created.name)
        // Emit the exact ID before the verification read so a fail-closed
        // verification failure still leaves an unambiguous cleanup record in
        // instrumentation diagnostics.
        logState("created disposable folder pending verification: id=${created.id} name=${created.name}")

        // This exact ID comes from the create response for our explicit root
        // parent. Do not enumerate unrelated account-root folders to verify it.
        // Subsequent gateway manifest/asset reads are scoped to this ID only.
        return created
    }

    private fun awaitConfiguredFolder(
        driveManager: com.example.myapplication.DriveSyncManager,
        expectedFolderId: String
    ) {
        awaitCondition(15_000L) {
            driveManager.authorizationStatus.value.backupFolder?.id == expectedFolderId
        }
    }

    private fun openSourceWithProductionPicker(pdfUri: Uri) {
        awaitUiText("Recent Drawings", 30_000L)
        clickAccessibilityText(targetContext.getString(com.example.myapplication.R.string.open_pdf), 10_000L)
        awaitCondition(15_000L) {
            runCatching {
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .rootInActiveWindow?.packageName?.toString()
            }.getOrNull()?.contains("documents", ignoreCase = true) == true
        }
        clickAccessibilityTextIfPresent("SOTAware Stage 9B", 5_000L)
        clickAccessibilityText(Stage9BQualificationDocumentsProvider.SOURCE_NAME, 20_000L)
        clickAccessibilityTextIfPresent("Open", 2_000L)
        awaitCondition(15_000L) {
            targetContext.contentResolver.persistedUriPermissions.any {
                it.uri == pdfUri && it.isReadPermission
            }
        }
        awaitUiText("SHEET 1", 30_000L)
        clickAccessibilityText("SHEET 1", 10_000L)
        awaitUiText("Note", 30_000L)
    }

    private fun awaitManifestEntry(uri: Uri): DocumentManifestEntryV1 {
        lateinit var result: DocumentManifestEntryV1
        awaitCondition(30_000L) {
            val loaded = runBlocking { LocalDocumentRepository(targetContext).readManifest() }
            if (loaded is ManifestReadResult.Loaded) {
                loaded.entries.firstOrNull { it.sourceUri == uri.toString() }?.let {
                    result = it
                    true
                } ?: false
            } else {
                false
            }
        }
        return result
    }

    /** Adds every current persisted annotation/photo domain through one reducer. */
    private fun seedCompleteCurrentDomains(
        scenario: ActivityScenario<MainActivity>,
        documentId: DocumentId
    ) {
        val photoName = DocumentPhotoAssetStore(targetContext.filesDir, documentId).use { store ->
            testContext.assets.open("stage7/photos/small_valid_photo.jpg").use { input ->
                store.publishNewPhoto(input, ".jpg").also(store::releasePhotoPublication)
            }
        }
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
                id = "stage9b-live-path-$suffix"
            )
            val measurement = Measurement(
                p1 = Point(.18f, .22f), p2 = Point(.52f, .68f), text = "12.5 ft",
                id = "stage9b-live-measurement-$suffix"
            )
            val note = Note(
                x = .42f, y = .33f, text = "Stage 9B live note",
                isBold = true, rotation = 23f, fontSizeRatio = .025f,
                id = "stage9b-live-note-$suffix"
            )
            val pdfShape = Shape(
                x = .65f, y = .30f, rotation = 17f, type = ShapeType.RECTANGLE,
                colorArgb = 0xff33b5e5.toInt(), isFilled = false,
                strokeWidthRatio = .009f, widthRatio = .22f, heightRatio = .14f,
                id = "stage9b-live-pdf-shape-$suffix"
            )
            val pin = PhotoPin(
                x = .58f, y = .64f, id = "stage9b-live-pin-$suffix",
                imageFileNames = listOf(photoName)
            )
            val imageNote = PhotoImageNote(
                x = .36f, y = .44f, text = "Live image note",
                isBold = false, rotation = -11f, fontSizeRatio = .021f,
                id = "stage9b-live-image-note-$suffix"
            )
            val imageShape = Shape(
                x = .58f, y = .54f, rotation = -9f, type = ShapeType.CIRCLE,
                colorArgb = 0xfff0ad4e.toInt(), isFilled = false,
                strokeWidthRatio = .007f, widthRatio = .18f, heightRatio = .16f,
                id = "stage9b-live-image-shape-$suffix"
            )
            assertTrue(reducer.addPdfPath(0, path).changed)
            assertTrue(reducer.addMeasurement(0, measurement).changed)
            assertTrue(reducer.addPdfNote(0, note).changed)
            assertTrue(reducer.addPdfShape(0, pdfShape).changed)
            assertTrue(reducer.addPhotoPin(0, pin).changed)
            assertTrue(reducer.addImageNote(0, pin.id, photoName, imageNote).changed)
            assertTrue(reducer.addImageShape(0, pin.id, photoName, imageShape).changed)
            assertTrue(reducer.setScale(0, PageScale(pointsPerFoot = 144f)).changed)
        }
    }

    private fun addRealUiNote(
        scenario: ActivityScenario<MainActivity>,
        text: String
    ) {
        var beforeCount = 0
        val bounds = Rect()
        scenario.onActivity {
            beforeCount = ViewModelProvider(it)[BlueprintViewModel::class.java].pageNotes[0]?.size ?: 0
            assertTrue(it.findViewById<android.view.View>(android.R.id.content).getGlobalVisibleRect(bounds))
        }
        awaitCondition(10_000L) {
            dismissSystemFullscreenHintIfVisible()
            tryClickAccessibilityText("Note")
        }
        clickAccessibilityText(targetContext.getString(com.example.myapplication.R.string.tool_options_close), 10_000L)
        tapScreen(
            bounds.left + bounds.width() * .28f,
            bounds.top + bounds.height() * (if (beforeCount < 2) .55f else .72f)
        )
        awaitUiText("Add Note", 10_000L)
        val editable = ArrayList<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo) {
            if (node.isEditable && node.isVisibleToUser && node.isEnabled) editable.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
        }
        collect(requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow))
        assertEquals("the live note dialog must expose exactly one editable field", 1, editable.size)
        assertTrue(editable.single().performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        ))
        clickAccessibilityText("Save", 10_000L)
        awaitCondition(10_000L) {
            var count = 0
            scenario.onActivity {
                count = ViewModelProvider(it)[BlueprintViewModel::class.java].pageNotes[0]?.size ?: 0
            }
            count == beforeCount + 1
        }
        scenario.onActivity {
            assertEquals(text, ViewModelProvider(it)[BlueprintViewModel::class.java].pageNotes[0]?.last()?.text)
        }
    }

    private fun tapScreen(x: Float, y: Float) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue("native touch injection failed", automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun captureSnapshot(
        scenario: ActivityScenario<MainActivity>,
        source: DocumentSourceIdentityV1
    ): DocumentSnapshotV1 {
        lateinit var snapshot: DocumentSnapshotV1
        scenario.onActivity { activity ->
            snapshot = snapshotFromState(
                ViewModelProvider(activity)[BlueprintViewModel::class.java],
                source
            )
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

    private fun assertCompleteCurrentDomains(snapshot: DocumentSnapshotV1) {
        assertEquals(setOf(0), snapshot.pages.keys)
        val page = requireNotNull(snapshot.pages[0])
        assertTrue(page.paths.isNotEmpty())
        assertTrue(page.measurements.isNotEmpty())
        assertTrue(page.notes.size >= 2) // seeded note plus one real UI edit
        assertTrue(page.shapes.isNotEmpty())
        assertNotNull(page.scale)
        assertTrue(page.photoPins.isNotEmpty())
        val pin = page.photoPins.single()
        assertEquals(1, pin.imageFileNames.size)
        val photoName = pin.imageFileNames.single()
        assertTrue(pin.imageNotes[photoName].orEmpty().isNotEmpty())
        assertTrue(pin.imageShapes[photoName].orEmpty().isNotEmpty())
    }

    private fun backToSelector() {
        repeat(3) {
            awaitCondition(10_000L) {
                isUiVisible("Recent Drawings") || isUiVisible("Note") ||
                    isUiVisible("Select Sheet") || isUiVisible("Sync Now") ||
                    isUiVisible("Google Drive Backup") || isUiVisible("Settings")
            }
            if (isUiVisible("Recent Drawings")) return
            val destination = when {
                isUiVisible("Note") -> "Select Sheet"
                isUiVisible("Google Drive Backup") && !isUiVisible("Settings") -> "Settings"
                else -> "Recent Drawings"
            }
            // A successful accessibility click only queues the Compose state
            // change. Observe the next screen before admitting another Back;
            // otherwise every click can hit the same departing viewer node.
            logState("navigation Back: awaiting $destination")
            clickAccessibilityText("Back", 10_000L)
            awaitUiText(destination, 10_000L)
        }
        awaitUiText("Recent Drawings", 10_000L)
    }

    private fun openDriveSettings() {
        backToSelector()
        clickAccessibilityText("Settings", 10_000L)
        clickAccessibilityText("Google Drive Backup", 10_000L)
    }

    /** Verify the actual applied owner, never a source supplied to the snapshot mapper. */
    private fun assertSelectedIdentity(
        scenario: ActivityScenario<MainActivity>, expected: DocumentManifestEntryV1
    ) {
        val association = com.example.myapplication.stage2.DocumentAssociation(
            expected.documentId,
            DocumentSourceIdentityV1(expected.sourceUri, expected.displayName, expected.providerMetadata),
            requireNotNull(expected.sourceFingerprint)
        )
        // Empty documents have a verified owner before any saved snapshot exists.
        awaitCondition(30_000L) {
            var owned = false
            scenario.onActivity {
                owned = ViewModelProvider(it)[BlueprintViewModel::class.java]
                    .canRetainHistoryForTarget(association)
            }
            owned
        }
        scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[BlueprintViewModel::class.java]
            assertTrue("refusing to mutate a different document owner",
                vm.canRetainHistoryForTarget(association))
            vm.canonicalSourceOrNull()?.let { applied ->
                assertEquals("applied snapshot belongs to a different document",
                    expected.sourceUri, applied.sourceUri)
            }
        }
        val observed = awaitManifestEntry(Uri.parse(expected.sourceUri))
        assertEquals(expected.documentId, observed.documentId)
        assertEquals(expected.sourceFingerprint, observed.sourceFingerprint)
    }

    private fun returnToViewer() {
        backToSelector()
        clickAccessibilityText(Stage9BQualificationDocumentsProvider.SOURCE_NAME, 10_000L)
        awaitUiText("SHEET 1", 30_000L)
        clickAccessibilityText("SHEET 1", 10_000L)
        awaitUiText("Note", 30_000L)
    }

    private fun clickSyncNow(expectSuccess: Boolean = true) {
        if (!isUiVisible("Sync Now")) openDriveSettings()
        awaitUiText("Sync Now", 10_000L)
        if (!expectSuccess) {
            clickAccessibilityText("Sync Now", 10_000L)
            return
        }
        // Require production success after accepted metadata and transfer cleanup;
        // seeing a partially published cursor is not sufficient.
        val success = targetContext.getString(com.example.myapplication.R.string.sync_complete)
        val failure = targetContext.getString(com.example.myapplication.R.string.sync_failed)
        val event = InstrumentationRegistry.getInstrumentation().uiAutomation.executeAndWaitForEvent(
            { clickAccessibilityText("Sync Now", 10_000L) },
            { observed -> observed.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                observed.packageName?.toString() == targetContext.packageName &&
                observed.text.any { it.toString().contains(success) || it.toString().contains(failure) } },
            90_000L
        )
        try {
            assertTrue("production Sync Now reported failure",
                event.text.any { it.toString().contains(success) })
        } finally {
            event.recycle()
        }
    }

    private fun awaitAcceptedMetadata(
        scope: SyncScope,
        priorCursor: com.example.myapplication.stage4.RemoteCursor? = null
    ): SyncMetadata {
        lateinit var result: SyncMetadata
        val store: SyncMetadataStore = FileSyncMetadataStore(targetContext)
        var stableCursor: com.example.myapplication.stage4.RemoteCursor? = null
        var stableSince = 0L
        // A real edit also schedules the production 3-second debounce route.
        // Manual success completes that request, not a later queued request.
        // Observe durable acceptance remaining settled beyond that debounce;
        // never race our external-writer fixture against the app's own upload.
        awaitCondition(90_000L) {
            val loaded = runBlocking { store.read(scope) }
            val metadata = (loaded as? com.example.myapplication.stage4.MetadataReadResult.Loaded)?.metadata
                ?: return@awaitCondition false
            val accepted = metadata.acceptedCursor
            val ready = metadata.remoteReference != null &&
                accepted != null &&
                metadata.pendingUpload == null &&
                metadata.conflictCursor == null &&
                (priorCursor == null || accepted != priorCursor)
            if (!ready) {
                stableCursor = null
                stableSince = 0L
                metadata.pendingUpload?.outboxLease?.close()
                false
            } else {
                val now = SystemClock.uptimeMillis()
                if (stableCursor != accepted) {
                    stableCursor = accepted
                    stableSince = now
                }
                val settled = now - stableSince >= 5_000L
                if (settled) result = metadata
                settled
            }
        }
        return result
    }

    private fun awaitRemoteEvidence(
        driveManager: com.example.myapplication.DriveSyncManager,
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint,
        expectedSnapshot: DocumentSnapshotV1,
        expectedAssets: PhotoAssetSet
    ): RemoteEvidence {
        val gateway = requireNotNull(driveManager.stage4Gateway()) {
            "production Stage 4 gateway is unavailable"
        }
        val deadline = SystemClock.uptimeMillis() + 90_000L
        var lastNotFound = false
        while (SystemClock.uptimeMillis() < deadline) {
            when (val lookup = runBlocking { gateway.find(scope, sourceFingerprint) }) {
                is RemoteLookup.Found -> {
                    val downloaded = runBlocking {
                        gateway.download(scope, lookup.metadata.reference, lookup.metadata.cursor)
                    }
                    if (downloaded is DownloadResult.Downloaded) {
                        return verifyDownloadedEvidence(
                            downloaded.remote,
                            downloaded.ownership,
                            scope,
                            sourceFingerprint,
                            expectedSnapshot,
                            expectedAssets
                        )
                    }
                    throw AssertionError("live Drive download failed: $downloaded")
                }
                RemoteLookup.NotFound -> lastNotFound = true
                is RemoteLookup.PendingAdoption -> throw AssertionError(
                    "live Drive returned an unexpected pending-adoption candidate"
                )
                is RemoteLookup.Failed -> throw AssertionError(
                    "live Drive lookup failed: ${lookup.failure}"
                )
            }
            SystemClock.sleep(250L)
        }
        throw AssertionError(
            "live Drive manifest was not readable within 90 seconds; lastNotFound=$lastNotFound"
        )
    }

    private fun verifyDownloadedEvidence(
        remote: RemoteSnapshotEnvelope,
        ownership: RemoteDownloadOwnership?,
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint,
        expectedSnapshot: DocumentSnapshotV1,
        expectedAssets: PhotoAssetSet
    ): RemoteEvidence {
        try {
            assertEquals(scope, remote.scope)
            assertEquals(sourceFingerprint, remote.sourceFingerprint)
            assertEquals(expectedSnapshot.source, remote.snapshot.source)
            assertEquals(
                RemoteManifestCodec.snapshotDigest(expectedSnapshot),
                RemoteManifestCodec.snapshotDigest(remote.snapshot)
            )
            assertEquals(expectedSnapshot.pages, remote.snapshot.pages)
            assertEquals(expectedAssets.descriptors.keys, remote.photoDescriptors.keys)
            expectedAssets.descriptors.forEach { (name, descriptor) ->
                assertEquals(
                    descriptor,
                    requireNotNull(remote.photoDescriptors[name]).asPhotoDescriptor()
                )
                val downloadedAsset = requireNotNull(remote.photoFiles[name])
                assertStreamsEqual(descriptor, descriptorSource = expectedAssets[name]!!.open(), downloaded = downloadedAsset.open())
            }
            return RemoteEvidence(
                reference = remote.reference,
                cursor = remote.cursor,
                snapshot = remote.snapshot,
                photoDescriptors = remote.photoDescriptors.toMap()
            )
        } finally {
            ownership?.release()
        }
    }

    private fun assertStreamsEqual(
        descriptor: PhotoDescriptor,
        descriptorSource: InputStream,
        downloaded: InputStream
    ) {
        descriptorSource.use { expected ->
            java.io.DataInputStream(downloaded).use { actual ->
                val expectedBuffer = ByteArray(64 * 1024)
                val actualBuffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val expectedCount = expected.read(expectedBuffer)
                    if (expectedCount < 0) {
                        assertEquals(-1, actual.read())
                        break
                    }
                    actual.readFully(actualBuffer, 0, expectedCount)
                    assertTrue(expectedCount <= expectedBuffer.size)
                    for (index in 0 until expectedCount) {
                        assertEquals(expectedBuffer[index], actualBuffer[index])
                    }
                    total += expectedCount
                    assertTrue(total <= Stage5Limits.MAX_PHOTO_BYTES.toLong())
                }
                assertEquals(descriptor.byteCount, total)
            }
        }
    }

    private fun assertRemotePhotoIdentityStable(first: RemoteEvidence, second: RemoteEvidence) {
        assertEquals(first.photoDescriptors.keys, second.photoDescriptors.keys)
        first.photoDescriptors.forEach { (name, descriptor) ->
            val later = requireNotNull(second.photoDescriptors[name])
            assertEquals("remote asset ID changed for $name", descriptor.remoteAssetId, later.remoteAssetId)
            assertEquals(descriptor.byteCount, later.byteCount)
            assertEquals(descriptor.sha256, later.sha256)
            assertEquals(descriptor.mimeType, later.mimeType)
            assertEquals(descriptor.width, later.width)
            assertEquals(descriptor.height, later.height)
        }
    }

    private fun runLiveConflictSmoke(
        driveManager: com.example.myapplication.DriveSyncManager,
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint,
        displayName: String,
        baselineSnapshot: DocumentSnapshotV1,
        assets: PhotoAssetSet,
        accepted: RemoteEvidence
    ) {
        val remoteNote = baselineSnapshot.pages.getValue(0).notes.first().copy(
            id = "stage9b-live-remote-conflict-${UUID.randomUUID()}",
            text = "Stage 9B remote conflict fixture"
        )
        val remoteSnapshot = baselineSnapshot.copy(
            snapshotRevision = baselineSnapshot.snapshotRevision + 1L,
            pages = baselineSnapshot.pages + (
                0 to baselineSnapshot.pages.getValue(0).copy(
                    notes = baselineSnapshot.pages.getValue(0).notes + remoteNote
                )
            )
        )
        val lease = ScopeRemoteMutationLease()
        val generation = 1L
        runBlocking { lease.advance(generation) }
        val gateway = requireNotNull(driveManager.stage4Gateway()) {
            "production Stage 4 gateway disappeared before conflict setup"
        }
        val result = runBlocking {
            gateway.upload(
                UploadRequest(
                    scope = scope,
                    displayName = displayName,
                    snapshot = remoteSnapshot,
                    expectedCursor = accepted.cursor,
                    generation = generation,
                    mutationLease = lease,
                    isGenerationCurrent = { lease.isGenerationCurrent(generation) },
                    sourceFingerprint = sourceFingerprint,
                    photoFiles = assets
                )
            )
        }
        val remote = try { when (result) {
            is UploadResult.Uploaded -> result.remote
            is UploadResult.Conflict -> throw AssertionError(
                "conflict setup raced with another remote writer: ${result.remote.cursor}"
            )
            is UploadResult.PendingAdoption -> throw AssertionError("conflict setup produced adoption")
            is UploadResult.Rejected -> throw AssertionError(
                "production gateway rejected conflict setup: ${result.failure}"
            )
        }

        } finally {
            // This is an external-change fixture, NOT acceptance by the app's
            // coordinator. Do not forge its accepted cursor or acknowledge the
            // transfer; retain recovery evidence until disposable app cleanup.
            result.mutationSession?.close()
        }

        logState("conflict setup published only inside disposable folder; cursor=${remote.cursor.revision}")
        if (!isUiVisible("Remote Changes Detected")) {
            clickSyncNow(expectSuccess = false)
        }
        awaitUiText("Remote Changes Detected", 30_000L)
        clickAccessibilityText("Keep Local", 10_000L)

        val metadata = awaitConflictMetadata(scope, accepted.cursor, remote.cursor)
        try {
            assertEquals(accepted.cursor, metadata.acceptedCursor)
            assertEquals(remote.cursor, metadata.conflictCursor)
        } finally {
            metadata.pendingUpload?.outboxLease?.close()
        }
        val afterKeepLocal = awaitRemoteEvidence(
            driveManager,
            scope,
            sourceFingerprint,
            remoteSnapshot,
            assets
        )
        assertEquals(remote.cursor, afterKeepLocal.cursor)
        assertEquals(remote.snapshot, afterKeepLocal.snapshot)
        awaitCondition(5_000L) { syncDisabledVisible() }
        assertTrue("Keep Local did not leave sync disabled", syncDisabledVisible())
        logState("live remote-conflict smoke: PASS; Keep Local preserved cursor and remote state")
    }

    private fun awaitConflictMetadata(
        scope: SyncScope,
        acceptedCursor: com.example.myapplication.stage4.RemoteCursor,
        conflictCursor: com.example.myapplication.stage4.RemoteCursor
    ): SyncMetadata {
        lateinit var result: SyncMetadata
        val store: SyncMetadataStore = FileSyncMetadataStore(targetContext)
        awaitCondition(30_000L) {
            val loaded = runBlocking { store.read(scope) }
            val metadata = (loaded as? com.example.myapplication.stage4.MetadataReadResult.Loaded)?.metadata
                ?: return@awaitCondition false
            val ready = metadata.acceptedCursor == acceptedCursor &&
                metadata.conflictCursor == conflictCursor
            if (ready) result = metadata
            else metadata.pendingUpload?.outboxLease?.close()
            ready
        }
        return result
    }

    private fun syncDisabledVisible(): Boolean = isUiVisible("Sync disabled", exact = false)

    private fun liveConflictEnabled(): Boolean =
        "true".equals(InstrumentationRegistry.getArguments().getString(ARG_LIVE_CONFLICT), ignoreCase = true)

    private fun isUiVisible(label: String, exact: Boolean = true): Boolean =
        findAccessibilityNode(label, exact) != null

    private fun awaitUiText(text: String, timeoutMillis: Long) {
        awaitCondition(timeoutMillis) {
            if (text == "Note") dismissSystemFullscreenHintIfVisible()
            isUiVisible(text)
        }
    }

    private fun clickAccessibilityText(label: String, timeoutMillis: Long) {
        awaitCondition(timeoutMillis) { tryClickAccessibilityText(label) }
    }

    private fun tryClickAccessibilityText(label: String): Boolean {
        var current = findAccessibilityNode(label, exact = true)
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
        }
        return false
    }

    /** Android's first-use immersive tutorial can appear after the viewer is ready. */
    private fun dismissSystemFullscreenHintIfVisible() {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return
        if (root.packageName?.toString() != "com.android.systemui" ||
            !isUiVisible("Viewing full screen")) return
        if (tryClickAccessibilityText("Got it")) {
            logState("dismissed Android fullscreen tutorial through its visible button")
        }
    }

    private fun clickAccessibilityTextIfPresent(label: String, timeoutMillis: Long) {
        try {
            clickAccessibilityText(label, timeoutMillis)
        } catch (_: AssertionError) {
            // DocumentsUI's Open action is optional across API-level variants.
        }
    }

    private fun findAccessibilityNode(label: String, exact: Boolean = false): AccessibilityNodeInfo? {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
        fun matches(value: CharSequence?): Boolean = if (exact) {
            value?.toString()?.equals(label, ignoreCase = true) == true
        } else {
            value?.toString()?.contains(label, ignoreCase = true) == true
        }
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (matches(node.text) || matches(node.contentDescription))) return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                find(child)?.let { return it }
            }
            return null
        }
        return find(root)
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
        if (lastError != null) {
            throw AssertionError("condition did not become true", lastError)
        }
        throw AssertionError("condition did not become true within ${timeoutMillis}ms")
    }

    private fun writeCleanupRecord(record: CleanupRecord) {
        val properties = Properties().apply {
            setProperty("runId", record.runId)
            record.folderId?.let { setProperty("disposableFolderId", it) }
            record.folderName?.let { setProperty("disposableFolderName", it) }
            setProperty("disposableFolderParentId", "root")
            record.documentId?.let { setProperty("documentId", it) }
            record.documentFolderId?.let { setProperty("documentFolderId", it) }
            record.manifestId?.let { setProperty("manifestId", it) }
            record.assetIds.toList().sorted().forEachIndexed { index, id ->
                setProperty("assetId.$index", id)
            }
            setProperty("accountId", "redacted; inspect only through the authorized cleanup account")
            setProperty("fixedRootTouched", "false")
        }
        val target = File(targetContext.filesDir, "${record.runId}-$CLEANUP_RECORD_NAME")
        val staging = File(targetContext.filesDir, "${record.runId}-$CLEANUP_RECORD_NAME.tmp")
        FileOutputStream(staging).use { output ->
            properties.store(output, "Stage9B live provider cleanup record")
            output.fd.sync()
        }
        if (target.exists()) {
            check(target.delete()) { "could not replace prior test-owned cleanup record" }
        }
        check(staging.renameTo(target)) { "could not publish cleanup record" }
    }

    private fun logState(message: String) {
        // Never include an access token, email address, raw document payload,
        // photo bytes, or an authorization response in diagnostics.
        Log.i(TAG, message)
    }

    private data class RemoteEvidence(
        val reference: com.example.myapplication.stage4.RemoteReference,
        val cursor: com.example.myapplication.stage4.RemoteCursor,
        val snapshot: DocumentSnapshotV1,
        val photoDescriptors: Map<String, RemoteAssetDescriptor>
    )

    private data class CleanupRecord(
        val runId: String,
        val folderId: String? = null,
        val folderName: String? = null,
        val documentId: String? = null,
        val documentFolderId: String? = null,
        val manifestId: String? = null,
        val assetIds: Set<String> = emptySet()
    ) {
        fun withRemote(remote: RemoteEvidence): CleanupRecord = copy(
            documentFolderId = remote.reference.folderId,
            manifestId = remote.reference.snapshotFileId,
            assetIds = remote.photoDescriptors.values.mapTo(linkedSetOf()) { it.remoteAssetId }
        )
    }

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val testContext
        get() = InstrumentationRegistry.getInstrumentation().context

    companion object {
        private const val TAG = "STAGE9B_LIVE_PROVIDER"
        private const val ARG_LIVE = "stage9b.live"
        private const val ARG_LIVE_CONFLICT = "stage9b.live.conflict"
        // This manual gate includes human account selection and consent.
        private const val AUTH_TIMEOUT_MILLIS = 300_000L
        private const val CLEANUP_RECORD_NAME = "stage9b-live-provider-cleanup.properties"
    }
}
