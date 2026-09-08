package com.example.myapplication.stage9a

import android.app.Instrumentation
import android.app.Activity
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.atomic.AtomicReference
import android.graphics.Rect
import java.io.FileInputStream
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicInteger
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.MainActivity
import com.example.myapplication.PhotoPin
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentLoadResult
import com.example.myapplication.stage2.LocalDocumentRepository
import com.example.myapplication.stage2.ManifestReadResult
import com.example.myapplication.stage5.DocumentPhotoAssetStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Real production Add Photo -> TakePicture -> FileProvider route coverage.
 *
 * The camera implementation is synthetic: this validates the Android
 * ActivityResult/SAF/FileProvider handoff and byte/persistence behavior, not a
 * physical camera sensor, driver, permission prompt, or image hardware path.
 */
@RunWith(AndroidJUnit4::class)
class CameraRecoveryInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext: Context
        get() = instrumentation.targetContext
    private val testContext: Context
        get() = instrumentation.context

    @Test
    fun addPhoto_normalCameraResult_isProductionSuccessControl() {
        exerciseCameraRoute(recreateHostWhilePending = false)
    }

    /**
     * This is expected to fail on the baseline: the current pending URI/file,
     * session, page, and selected pin all live in remember-only Compose state.
     */
    @Test
    fun addPhoto_resultAfterHostRecreation_persistsOnTheIntendedPin() {
        exerciseCameraRoute(recreateHostWhilePending = true)
    }

    private fun exerciseCameraRoute(recreateHostWhilePending: Boolean) {
        val control = CameraTestControl(instrumentation, testContext.packageName)
        val redirectMonitor = cameraRedirectMonitor(control)
        instrumentation.addMonitor(redirectMonitor)

        var scenario: ActivityScenario<MainActivity>? = null
        var lifecycleRegistration: ActivityLifecycleCallback? = null
        try {
            val sourceUri = uniqueFixtureUri()
            val expectedBytes = testContext.assets.open(CameraTestActivity.CAMERA_ASSET).use {
                it.readBytes()
            }
            scenario = ActivityScenario.launch(
                Intent(targetContext, MainActivity::class.java)
                    .putExtra(
                        "com.sotaware.construct.stage8.INITIAL_PDF_URI",
                        sourceUri.toString()
                    )
            )

            waitForViewer()
            val host = AtomicReference<MainActivity>()
            scenario.onActivity { host.set(it) }
            val recreated = AtomicReference<Activity?>()
            val lifecycle = ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && activity !== host.get() && stage == Stage.CREATED) {
                    recreated.set(activity)
                }
            }
            lifecycleRegistration = lifecycle
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle)
            val pinId = createAndSelectPhotoPin(scenario)

            assertTrue("production TakePicture did not launch the synthetic camera", control.awaitReady(10_000L))
            assertEquals("the real capture intent should be routed exactly once", 1, control.launchCount.get())

            if (recreateHostWhilePending) {
                // The external camera remains pending while only the host
                // Activity is recreated. Completion is delivered afterward.
                // ActivityScenario.recreate waits for RESUMED and cannot represent
                // a host intentionally stopped behind an external camera Activity.
                instrumentation.runOnMainSync { host.get().recreate() }
                val deadline = SystemClock.uptimeMillis() + 10_000L
                while (recreated.get() == null && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(25L)
                }
                assertNotNull("host was not recreated while the camera remained pending", recreated.get())
            }
            control.complete(success = true)
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)

            val persisted = awaitPersistedPhoto(scenario, sourceUri.toString(), pinId)
            assertEquals(1, currentPin(scenario, pinId)?.imageFileNames?.size)
            assertEquals(1, persisted.referenceCount)
            assertArrayEquals(expectedBytes, persisted.bytes)
        } finally {
            lifecycleRegistration?.let {
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(it)
            }
            // If an assertion interrupts the route, ask the owned test camera
            // to finish before removing its exact control files.
            runCatching { control.complete(success = false) }
            scenario?.close()
            instrumentation.removeMonitor(redirectMonitor)
        }
    }

    private fun waitForViewer() {
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
                composeRule.onNodeWithContentDescription("Photo").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    private fun createAndSelectPhotoPin(scenario: ActivityScenario<MainActivity>): String {
        composeRule.onNodeWithContentDescription("Photo").performClick()
        val viewport = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val point = Offset(viewport.width * 0.5f, viewport.height * 0.5f)
        composeRule.onRoot().performTouchInput { click(point) }

        composeRule.waitUntil(5_000L) {
            currentPhotoPinCount(scenario) == 1
        }
        val pinId = requireNotNull(currentPin(scenario, expectedId = null)).id

        // The photo tool returns to PAN after placing the pin. A second real
        // canvas tap selects that pin and reveals the contextual Add action.
        composeRule.onRoot().performTouchInput { click(point) }
        composeRule.waitUntil(5_000L) {
            try {
                composeRule.onNodeWithText("Add").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        composeRule.onNodeWithText("Add").performClick()
        return pinId
    }

    private fun currentPhotoPinCount(scenario: ActivityScenario<MainActivity>): Int {
        var count = 0
        runCatching {
            scenario.onActivity { activity ->
                count = ViewModelProvider(activity)[BlueprintViewModel::class.java]
                    .pagePhotoPins[0]
                    ?.size
                    ?: 0
            }
        }
        return count
    }

    private fun currentPin(
        scenario: ActivityScenario<MainActivity>,
        expectedId: String?
    ): PhotoPin? {
        var pin: PhotoPin? = null
        runCatching {
            scenario.onActivity { activity ->
                pin = ViewModelProvider(activity)[BlueprintViewModel::class.java]
                    .pagePhotoPins[0]
                    ?.firstOrNull { expectedId == null || it.id == expectedId }
            }
        }
        return pin
    }

    private fun awaitPersistedPhoto(
        scenario: ActivityScenario<MainActivity>,
        sourceUri: String,
        pinId: String
    ): PersistedPhoto {
        var observed: PersistedPhoto? = null
        composeRule.waitUntil(30_000L) {
            val pin = currentPin(scenario, pinId)
            if (pin?.imageFileNames?.size == 1) {
                observed = readCanonicalPhoto(sourceUri, pinId)
            }
            observed != null
        }
        return requireNotNull(observed)
    }

    private fun readCanonicalPhoto(sourceUri: String, pinId: String): PersistedPhoto? =
        runBlocking {
            val repository = LocalDocumentRepository(targetContext)
            val manifest = repository.readManifest() as? ManifestReadResult.Loaded
                ?: return@runBlocking null
            val entry = manifest.entries.singleOrNull { it.sourceUri == sourceUri }
                ?: return@runBlocking null
            val association = DocumentAssociation(
                documentId = entry.documentId,
                source = DocumentSourceIdentityV1(
                    sourceUri = entry.sourceUri,
                    displayName = entry.displayName,
                    providerMetadata = entry.providerMetadata
                ),
                sourceFingerprint = entry.sourceFingerprint,
                legacyArtifactName = entry.legacyArtifactName
            )
            val loaded = repository.load(association) as? DocumentLoadResult.Loaded
                ?: return@runBlocking null
            val pin = loaded.snapshot.pages.values
                .flatMap { it.photoPins }
                .singleOrNull { it.id == pinId }
                ?: return@runBlocking null
            if (pin.imageFileNames.size != 1) return@runBlocking null
            val reference = pin.imageFileNames.single()
            val bytes = DocumentPhotoAssetStore(targetContext.filesDir, entry.documentId).use {
                it.read(reference)
            }
            PersistedPhoto(referenceCount = pin.imageFileNames.size, bytes = bytes)
        }

    private fun uniqueFixtureUri(): Uri = Uri.Builder()
        .scheme("content")
        .authority("${testContext.packageName}.stage8.fixture")
        .appendPath("stage7/pdfs/scanned/scanned_text_fixture.pdf")
        .appendQueryParameter("stage9a", UUID.randomUUID().toString())
        .build()

    private fun cameraRedirectMonitor(control: CameraTestControl): Instrumentation.ActivityMonitor =
        object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == MediaStore.ACTION_IMAGE_CAPTURE) {
                    control.launchCount.incrementAndGet()
                    intent.component = ComponentName(testContext, CameraTestActivity::class.java)
                }
                return null
            }
        }

    private data class PersistedPhoto(
        val referenceCount: Int,
        val bytes: ByteArray
    )

    private class CameraTestControl(
        private val instrumentation: Instrumentation,
        private val packageName: String
    ) {
        val launchCount = AtomicInteger()

        fun awaitReady(timeoutMillis: Long): Boolean = waitUntil(timeoutMillis) {
            button(CameraTestActivity.SAVE) != null
        }

        fun complete(success: Boolean) {
            val text = if (success) CameraTestActivity.SAVE else CameraTestActivity.CANCEL
            val target = button(text) ?: return
            val bounds = Rect().also(target::getBoundsInScreen)
            check(!bounds.isEmpty) { "synthetic camera button has no clickable bounds" }
            instrumentation.uiAutomation.executeShellCommand(
                "input tap ${bounds.centerX()} ${bounds.centerY()}"
            ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
            check(waitUntil(10_000L) { button(text) == null }) {
                "synthetic camera did not finish after completion"
            }
        }

        private fun button(text: String): AccessibilityNodeInfo? {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
            if (root.packageName?.toString() != packageName) return null
            return root.findAccessibilityNodeInfosByText(text)
                .firstOrNull { it.text?.toString() == text && it.isClickable }
        }

        private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
            val deadline = SystemClock.uptimeMillis() + timeoutMillis
            do {
                if (condition()) return true
                // Bounded polling of a real cross-process UI state, not an assertion delay.
                SystemClock.sleep(25L)
            } while (SystemClock.uptimeMillis() < deadline)
            return condition()
        }
    }
}
