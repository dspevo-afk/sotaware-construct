package com.example.myapplication.stage10

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.BlueprintApp
import com.example.myapplication.DriveSyncManager
import com.example.myapplication.MainActivity
import com.example.myapplication.R
import com.example.myapplication.stage9.DRIVE_FILE_SCOPE
import com.example.myapplication.stage9.DriveAuthorizationApplyResult
import com.example.myapplication.stage9.GoogleCredentialDriveAuth
import com.example.myapplication.stage9.GoogleIdentity
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android UI/Toast scheduling with synthetic identity and generated Drive HTTP. */
@RunWith(AndroidJUnit4::class)
class AuditDriveRootUiInstrumentedTest {
    @Test
    fun ambiguousRoots_createButtonShowsFailureWithoutAssociatingOrCreating() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val methods = CopyOnWriteArrayList<String>()
        val rootId = "audit-account-root"
        fun folder(id: String) = """{"id":"$id","name":"SOTAware Construct Backups","mimeType":"application/vnd.google-apps.folder","parents":["$rootId"],"appProperties":{"sotaware_backup_root":"1"},"trashed":false}"""
        val transport = object : MockHttpTransport() {
            override fun buildRequest(method: String, url: String) = object : MockLowLevelHttpRequest(url) {
                override fun execute(): LowLevelHttpResponse {
                    methods.add(method)
                    assertEquals("ambiguous discovery cannot mutate Drive", "GET", method)
                    val body = if (url.substringBefore('?').endsWith("/files/root")) {
                        """{"id":"$rootId"}"""
                    } else {
                        """{"files":[${folder("root-a")},${folder("root-b")}]}"""
                    }
                    return MockLowLevelHttpResponse().setStatusCode(200)
                        .setContentType("application/json").setContent(body)
                }
            }
        }
        val preferenceName = "audit-drive-root-${UUID.randomUUID()}"
        val manager = DriveSyncManager(
            context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE),
            { context.filesDir }, transport
        )
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val generation = manager.beginAuthenticationAttempt()
            assertTrue(manager.authenticateIfCurrent(generation, GoogleIdentity("audit-subject", "audit@example.test")))
            assertTrue(manager.installAuthorizedDriveSession(generation, "synthetic-token", listOf(DRIVE_FILE_SCOPE))
                is DriveAuthorizationApplyResult.Accepted)
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            scenario.onActivity { activity ->
                val auth = GoogleCredentialDriveAuth(activity, "")
                activity.setContent {
                    BlueprintApp(driveSyncManagerOverride = manager, googleDriveAuthOverride = auth)
                }
            }
            click("Settings")
            click("Google Drive Backup")
            val createLabel = context.getString(R.string.create_sotaware_backup_folder)
            awaitCondition { clickableNode(createLabel)?.isEnabled == true }
            val expectedMessage = context.getString(R.string.backup_root_ambiguous)
            val notification = instrumentation.uiAutomation.executeAndWaitForEvent(
                { click(createLabel) },
                { event -> event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                    event.text.any { it.toString().contains(expectedMessage) } },
                10_000L
            )
            notification.recycle()
            awaitCondition { clickableNode(createLabel)?.isEnabled == true }
            assertNotNull(clickableNode(createLabel))
            assertEquals(listOf("GET", "GET"), methods.toList())
            assertEquals(generation, manager.authorizationStatus.value.generation)
            assertNull(manager.authorizationStatus.value.backupFolder)
            assertNull(manager.currentSyncAccountRoot())
        } finally {
            scenario?.close()
            manager.clearSession()
            context.deleteSharedPreferences(preferenceName)
        }
    }

    private fun clickableNode(label: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { find(it)?.let { match -> return match } }
            }
            return null
        }
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
        var current = find(root)
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun click(label: String) {
        awaitCondition {
            clickableNode(label)?.let { it.isEnabled && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        throw AssertionError("UI condition did not become true within 10000ms")
    }
}
