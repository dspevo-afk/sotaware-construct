package com.example.myapplication.stage9

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.example.myapplication.BlueprintApp
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.DriveSyncManager
import com.example.myapplication.MainActivity
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.RemoteLookup
import com.example.myapplication.stage4.SyncScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Android request/Compose boundaries with synthetic identity and HTTP data only. */
@RunWith(AndroidJUnit4::class)
class Stage9AuthInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testContext: Context get() = instrumentation.targetContext
    private val identity = GoogleIdentity("stage9-subject", "stage9@example.test")

    @Test
    fun installedIdentityVersionAndBackupFlag_matchTheStage9Policy() {
        val context = instrumentation.targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("com.sotaware.construct", context.packageName)
        assertEquals(1L, info.longVersionCode)
        assertEquals("1.0.0", info.versionName)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun fileProvider_exposesCameraCapturePathsButRejectsDocumentsAndCache() {
        val context = instrumentation.targetContext
        val authority = "${context.packageName}.fileprovider"
        val capture = File(context.filesDir, "camera_captures/stage9-not-created.jpg")
        val uri = FileProvider.getUriForFile(context, authority, capture)
        assertEquals(authority, uri.authority)
        assertTrue(uri.path.orEmpty().startsWith("/camera_captures/"))
        for (privateFile in listOf(
            File(context.filesDir, "documents/stage9-not-created.json"),
            File(context.cacheDir, "stage9-not-created.zip")
        )) {
            var rejected = false
            try {
                FileProvider.getUriForFile(context, authority, privateFile)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("Private application path must not be shareable", rejected)
        }
    }

    @Test
    fun explicitSignIn_usesTheGoogleButtonRequest() {
        val request = explicitGoogleSignInRequest("synthetic.apps.googleusercontent.com")
        val option = request.credentialOptions.single() as GetSignInWithGoogleOption
        assertEquals("synthetic.apps.googleusercontent.com", option.serverClientId)
        // The SDK's generic auto-select flag is provider metadata, not proof
        // that the Google button displayed or suppressed its account chooser.
    }

    @Test
    fun restoration_usesOnlyReturningAccountsAndPrefersImmediateCredentials() {
        val request = returningGoogleSignInRequest("synthetic.apps.googleusercontent.com")
        val option = request.credentialOptions.single() as GetGoogleIdOption
        assertEquals("synthetic.apps.googleusercontent.com", option.serverClientId)
        assertTrue(option.filterByAuthorizedAccounts)
        assertTrue(option.autoSelectEnabled)
        assertTrue(request.preferImmediatelyAvailableCredentials)
    }

    @Test
    fun driveConsent_isPinnedToAuthenticatedAccountAndOnlyDriveFile() {
        val request = driveAuthorizationRequest(identity)
        assertEquals(identity.email, request.account?.name)
        assertEquals("com.google", request.account?.type)
        assertEquals(listOf(DRIVE_FILE_SCOPE), request.requestedScopes.map { it.scopeUri })
    }

    @Test
    fun consentResolution_returnsTheImmutableLaunchingOperationId() {
        val operationId = UUID.randomUUID().toString()
        val providerMarker = UUID.randomUUID().toString()
        val providerIntent = Intent(
            instrumentation.targetContext,
            QualificationConsentActivity::class.java
        )
            .putExtra(QualificationConsentActivity.EXTRA_RESULT_CODE, Activity.RESULT_OK)
            .putExtra(QualificationConsentActivity.EXTRA_PROVIDER_MARKER, providerMarker)
        val pendingIntent = PendingIntent.getActivity(
            instrumentation.targetContext,
            operationId.hashCode(),
            providerIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contract = DriveAuthorizationResolutionContract()
        val launchIntent = contract.createIntent(
            instrumentation.targetContext,
            DriveAuthorizationResolutionRequest(operationId, pendingIntent.intentSender)
        )
        val scenario = ActivityScenario.launchActivityForResult<DriveAuthorizationResolutionActivity>(
            launchIntent
        )
        try {
            val rawResult = scenario.result
            val result = contract.parseResult(rawResult.resultCode, rawResult.resultData)
            assertEquals(Activity.RESULT_OK, result.resultCode)
            assertEquals(operationId, result.operationId)
            assertEquals(
                providerMarker,
                result.providerData?.getStringExtra(QualificationConsentActivity.EXTRA_PROVIDER_MARKER)
            )
        } finally {
            // Reading ActivityScenario.result waits until this self-finishing
            // trampoline is already destroyed. Calling close() afterward makes
            // ActivityScenario try to move a terminal activity through another
            // lifecycle transition and crashes inside the test harness.
            pendingIntent.cancel()
        }
    }

    @Test
    fun driveAuthorityOwner_isRetainedWithItsViewModelAcrossActivityRecreation() {
        val scenario = ActivityScenario.launch<QualificationViewModelActivity>(
            Intent(instrumentation.targetContext, QualificationViewModelActivity::class.java)
        )
        lateinit var originalViewModel: BlueprintViewModel
        lateinit var originalManager: DriveSyncManager
        try {
            scenario.onActivity { activity ->
                originalViewModel = ViewModelProvider(activity)[BlueprintViewModel::class.java]
                originalManager = originalViewModel.getOrCreateDriveSyncManager(activity.applicationContext)
            }

            scenario.recreate()
            scenario.onActivity { activity ->
                val recreatedViewModel = ViewModelProvider(activity)[BlueprintViewModel::class.java]
                val recreatedManager = recreatedViewModel.getOrCreateDriveSyncManager(activity.applicationContext)
                assertSame(originalViewModel, recreatedViewModel)
                assertSame(originalManager, recreatedManager)
                assertSame(originalManager.authorizationOwner, recreatedManager.authorizationOwner)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun rejectedToken_removesAuthorizedAccountAndRootFromProductionSettings() = withManager { manager ->
        val generation = authorize(manager)
        assertTrue(manager.setBackupFolder(generation, "synthetic-root", "Stage 9 test backups"))
        withApp(manager) {
            openDriveSettings()
            compose.onNodeWithText(identity.email).assertIsDisplayed()
            compose.onNodeWithText("Stage 9 test backups").assertIsDisplayed()

            val result = runBlocking {
                requireNotNull(manager.stage4Gateway()).find(
                    SyncScope(identity.email, "synthetic-root", DocumentId.new())
                )
            }
            assertTrue(result is RemoteLookup.Failed)
            assertFalse(manager.isSignedIn())
            assertNull(manager.currentSyncAccountRoot())
            compose.waitForIdle()
            compose.onNodeWithText("Sign in with Google").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText(identity.email).assertDoesNotExist()
            compose.onNodeWithText("Stage 9 test backups").assertDoesNotExist()
        }
    }

    @Test
    fun missingClientConfiguration_failsClosedAndAllowsTheUserToRetry() = withManager { manager ->
        withApp(manager) {
            openDriveSettings()
            repeat(2) {
                val generation = manager.authorizationStatus.value.generation
                compose.onNodeWithText("Sign in with Google").performClick()
                compose.waitUntil(5_000) { manager.authorizationStatus.value.generation > generation }
                compose.waitForIdle()
                compose.onNodeWithText("Sign in with Google").assertIsEnabled()
                assertFalse(manager.isSignedIn())
                assertNull(manager.stage4Gateway())
                assertFalse(manager.shouldRestoreSession())
            }
        }
    }

    @Test
    fun explicitAccountSwitch_fencesTheOldAccountAndInstallsTheChosenAccount() =
        withManager { manager ->
            val oldIdentity = GoogleIdentity("stage9-old-subject", "old@example.test")
            val newIdentity = GoogleIdentity("stage9-new-subject", "new@example.test")
            val oldGeneration = manager.beginAuthenticationAttempt()
            assertTrue(manager.authenticateIfCurrent(oldGeneration, oldIdentity))
            assertTrue(
                manager.installAuthorizedDriveSession(
                    oldGeneration,
                    "old-synthetic-token",
                    listOf(DRIVE_FILE_SCOPE)
                ) is DriveAuthorizationApplyResult.Accepted
            )
            assertTrue(manager.setBackupFolder(oldGeneration, "old-root", "Old backups"))

            withApp(manager, authFactory = { ImmediateGoogleDriveAuth(newIdentity) }) {
                openDriveSettings()
                compose.onNodeWithText(oldIdentity.email).assertIsDisplayed()
                compose.onNodeWithText("Switch Google account").assertIsDisplayed().performClick()
                compose.waitUntil(5_000) {
                    manager.authorizationStatus.value.identity == newIdentity && manager.isSignedIn()
                }
                compose.waitForIdle()
                compose.onNodeWithText(newIdentity.email).assertIsDisplayed()
                compose.onNodeWithText(oldIdentity.email).assertDoesNotExist()
                assertNull(manager.authorizationStatus.value.backupFolder)
                assertTrue(manager.authorizationStatus.value.generation > oldGeneration)
            }
        }

    private fun authorize(manager: DriveSyncManager): Long {
        val generation = manager.beginAuthenticationAttempt()
        assertTrue(manager.authenticateIfCurrent(generation, identity))
        assertTrue(
            manager.installAuthorizedDriveSession(generation, "synthetic-token", listOf(DRIVE_FILE_SCOPE))
                is DriveAuthorizationApplyResult.Accepted
        )
        return generation
    }

    private fun openDriveSettings() {
        val settings = compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        val bounds = settings.fetchSemanticsNode().boundsInRoot
        assertTrue("Settings must have visible touch bounds: $bounds", bounds.width > 0f && bounds.height > 0f)
        settings.performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Google Drive Backup").assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    private fun withApp(
        manager: DriveSyncManager,
        authFactory: (MainActivity) -> GoogleDriveAuthClient = { activity ->
            GoogleCredentialDriveAuth(activity, "")
        },
        block: () -> Unit
    ) {
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(instrumentation.targetContext, MainActivity::class.java)
        )
        try {
            scenario.onActivity { activity ->
                val auth = authFactory(activity)
                activity.setContent {
                    BlueprintApp(
                        driveSyncManagerOverride = manager,
                        googleDriveAuthOverride = auth
                    )
                }
            }
            compose.waitForIdle()
            block()
        } finally {
            scenario.close()
        }
    }

    private class ImmediateGoogleDriveAuth(
        private val selectedIdentity: GoogleIdentity
    ) : GoogleDriveAuthClient {
        override val isConfigured: Boolean = true

        override suspend fun signIn(activity: Activity): GoogleIdentity = selectedIdentity

        override suspend fun restoreAuthorizedIdentity(activity: Activity): GoogleIdentity? = null

        override suspend fun requestDriveAuthorization(
            activity: Activity,
            identity: GoogleIdentity
        ): DriveAuthorizationRequestResult = DriveAuthorizationRequestResult.Granted(
            accessToken = "new-synthetic-token",
            grantedScopes = setOf(DRIVE_FILE_SCOPE)
        )

        override fun completeDriveAuthorization(
            activity: Activity,
            data: Intent?
        ): DriveAuthorizationRequestResult.Granted = error("No consent resolution is expected")

        override suspend fun clearCredentialState() = Unit

        override suspend fun clearAccessToken(activity: Activity, accessToken: String) = Unit
    }

    private fun withManager(block: (DriveSyncManager) -> Unit) {
        val preferenceName = "stage9-auth-test-${UUID.randomUUID()}"
        val preferences = testContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val transport = MockHttpTransport.Builder().setLowLevelHttpResponse(
            MockLowLevelHttpResponse().setStatusCode(401).setContentType("application/json")
                .setContent("""{"error":{"code":401,"message":"synthetic rejection"}}""")
        ).build()
        val manager = DriveSyncManager(preferences, { testContext.filesDir }, transport)
        try {
            block(manager)
        } finally {
            manager.clearSession()
            testContext.deleteSharedPreferences(preferenceName)
        }
    }
}
