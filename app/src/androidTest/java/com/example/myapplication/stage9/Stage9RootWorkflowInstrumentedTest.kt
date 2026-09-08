package com.example.myapplication.stage9

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.BlueprintApp
import com.example.myapplication.DriveSyncManager
import com.example.myapplication.MainActivity
import com.example.myapplication.R
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exercises real Android Main dispatch and real touch input. Compose 1.7's
 * test-rule effect dispatcher must not replace the runtime for this IO-to-UI case.
 */
@RunWith(AndroidJUnit4::class)
class Stage9RootWorkflowInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun rootCreation_disablesSignOutUntilTheOwnedRequestCompletes() {
        val testContext = instrumentation.context
        val preferenceName = "stage9-root-test-${UUID.randomUUID()}"
        val preferences = testContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val postStarted = CountDownLatch(1)
        val releasePost = CountDownLatch(1)
        val transport = object : MockHttpTransport() {
            override fun buildRequest(method: String, url: String) = object : MockLowLevelHttpRequest(url) {
                override fun execute(): MockLowLevelHttpResponse {
                    val content = if (method == "GET") """{"files":[]}""" else {
                        postStarted.countDown()
                        check(releasePost.await(30, TimeUnit.SECONDS))
                        """{"id":"synthetic-root","name":"Stage 9 test backups","mimeType":"application/vnd.google-apps.folder","parents":["root"],"appProperties":{"sotaware_backup_root":"1"},"trashed":false}"""
                    }
                    return MockLowLevelHttpResponse().setStatusCode(200)
                        .setContentType("application/json").setContent(content)
                }
            }
        }
        val manager = DriveSyncManager(preferences, { testContext.filesDir }, transport)
        val signOutLabel = instrumentation.targetContext.getString(R.string.sign_out)
        val generation = manager.beginAuthenticationAttempt()
        assertTrue(manager.authenticateIfCurrent(generation, GoogleIdentity("stage9-subject", "stage9@example.test")))
        assertTrue(manager.installAuthorizedDriveSession(generation, "synthetic-token", listOf(DRIVE_FILE_SCOPE))
            is DriveAuthorizationApplyResult.Accepted)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(Intent(instrumentation.targetContext, MainActivity::class.java))
            scenario.onActivity { activity ->
                val auth = GoogleCredentialDriveAuth(activity, "")
                activity.setContent {
                    BlueprintApp(driveSyncManagerOverride = manager, googleDriveAuthOverride = auth)
                }
            }
            tap(awaitControl("Settings", description = true, enabled = true))
            tap(awaitControl("Google Drive Backup", enabled = true))
            tap(awaitControl("Create SOTAware backup folder", enabled = true))
            assertTrue("Synthetic root POST must start", postStarted.await(5, TimeUnit.SECONDS))
            awaitControl(signOutLabel, enabled = false)
            awaitControl("Create SOTAware backup folder", enabled = false)
            assertTrue(manager.isSignedIn())

            releasePost.countDown()
            awaitControl("Stage 9 test backups", enabled = true)
            awaitControl(signOutLabel, enabled = true)
            assertEquals("synthetic-root", manager.authorizationStatus.value.backupFolder?.id)
        } finally {
            releasePost.countDown()
            scenario?.close()
            manager.clearSession()
            runBlocking { manager.cancelRootOperationsAndJoin() }
            testContext.deleteSharedPreferences(preferenceName)
        }
    }

    private data class Control(val bounds: Rect, val enabled: Boolean)

    private fun awaitControl(label: String, description: Boolean = false, enabled: Boolean): Control {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var observed: Control? = null
        do {
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) {
                try {
                    if (root.packageName?.toString() == instrumentation.targetContext.packageName) {
                        observed = findControl(root, label, description, parentEnabled = true, depth = 0)
                        if (observed?.enabled == enabled) return requireNotNull(observed)
                    }
                } finally {
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            }
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Visible control '$label' enabled=$enabled not found; observed=$observed")
    }

    private fun findControl(
        node: AccessibilityNodeInfo,
        label: String,
        description: Boolean,
        parentEnabled: Boolean,
        depth: Int
    ): Control? {
        if (depth > 32) return null
        val enabled = parentEnabled && node.isEnabled
        val value = if (description) node.contentDescription else node.text
        if (value?.toString() == label && node.isVisibleToUser) {
            val bounds = Rect().also(node::getBoundsInScreen)
            if (!bounds.isEmpty) return Control(bounds, enabled)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                findControl(child, label, description, enabled, depth + 1)?.let { return it }
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return null
    }

    private fun tap(control: Control) {
        assertTrue("Cannot tap a disabled control", control.enabled)
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action,
                control.bounds.exactCenterX(), control.bounds.exactCenterY(), 0
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                assertTrue("Android must accept the touch event", instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
    }
}
