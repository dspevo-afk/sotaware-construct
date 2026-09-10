package com.example.myapplication.stage9

import android.content.SharedPreferences
import com.example.myapplication.DriveBackupFolder
import com.example.myapplication.DriveSyncManager
import com.example.myapplication.stage4.DriveFailure
import com.example.myapplication.stage4.DynamicDriveGateway
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import com.example.myapplication.stage9b.DriveImmutableAssetTransfer
import com.example.myapplication.stage4.RemoteLookup
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage2.DocumentId
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DriveSyncManagerAuthorizationTest {
    private val testContexts = mutableListOf<TestContext>()

    @After
    fun cleanupTestContexts() {
        // Sign-out cancels owned root jobs before their temporary app-private
        // roots are reclaimed. The bounded join keeps a failed test from
        // hanging the suite if a synthetic request intentionally remains
        // blocked; every in-test latch still releases its own request.
        testContexts.asReversed().forEach { context ->
            context.manager.clearSession()
            runBlocking {
                withTimeoutOrNull(TEST_CLEANUP_TIMEOUT_MILLIS) {
                    context.manager.cancelRootOperationsAndJoin()
                }
            }
            if (!context.root.deleteRecursively()) {
                context.root.deleteOnExit()
            }
        }
        testContexts.clear()
    }

    @Test
    fun acceptedToken_isSentAsBearerByCurrentGateway() = runTest {
        val transport = RecordingTransport { emptyFileListResponse() }
        val manager = newManager(transport = transport)
        val identity = GoogleIdentity("subject-a", "a@example.test")
        authorize(manager, identity, "access-token")
        val scope = SyncScope(identity.email, "root-a", DocumentId.new())

        assertEquals(RemoteLookup.NotFound, requireNotNull(manager.stage4Gateway()).find(scope))
        assertEquals(1, transport.requests.size)
        assertEquals("Bearer access-token", transport.requests.single().header("Authorization"))
        assertTrue(manager.authorizationStatus.value.isAuthorized)
    }

    @Test
    fun unauthorized401_revokesLiveAuthorizationRootAndDynamicGatewayStopsNetwork() = runTest {
        val transport = RecordingTransport { unauthorizedResponse() }
        val manager = newManager(transport = transport)
        val identity = GoogleIdentity("subject-a", "a@example.test")
        val generation = authorize(manager, identity, "rejected-token")
        assertTrue(manager.setBackupFolder(generation, "root-a", "A Backups"))
        val scope = SyncScope(identity.email, "root-a", DocumentId.new())
        val retainedGateway = requireNotNull(manager.stage4Gateway())
        val dynamicGateway = DynamicDriveGateway { manager.stage4Gateway() }

        assertTrue(retainedGateway.find(scope) is RemoteLookup.Failed)
        assertFalse(manager.authorizationStatus.value.isAuthorized)
        assertFalse(manager.shouldRestoreSession())
        assertNull(manager.authorizationStatus.value.backupFolder)
        assertNull(manager.currentSyncAccountRoot())
        assertNull(manager.stage4Gateway())

        val requestsAfterUnauthorized = transport.requests.size
        assertTrue(retainedGateway.find(scope) is RemoteLookup.Failed)
        val dynamicResult = dynamicGateway.find(scope)
        assertTrue(dynamicResult is RemoteLookup.Failed)
        assertTrue((dynamicResult as RemoteLookup.Failed).failure is DriveFailure.NotAuthenticated)
        assertEquals(requestsAfterUnauthorized, transport.requests.size)
    }

    @Test
    fun forbiddenOrNetworkFailure_doesNotRevokeValidAuthorization() = runTest {
        assertNonUnauthorizedFailureDoesNotRevoke { forbiddenResponse() }
        assertNonUnauthorizedFailureDoesNotRevoke { throw IOException("offline") }
    }

    @Test
    fun stale401_queuesOnlyRejectedToken_withoutTouchingNewAuthorization() = runTest {
        val identityA = GoogleIdentity("subject-a", "a@example.test")
        val identityB = GoogleIdentity("subject-b", "b@example.test")
        lateinit var manager: DriveSyncManager
        var switched = false
        val transport = RecordingTransport {
            if (!switched) {
                switched = true
                val newerGeneration = manager.beginAuthenticationAttempt()
                check(manager.authenticateIfCurrent(newerGeneration, identityB))
                check(
                    manager.installAuthorizedDriveSession(
                        newerGeneration,
                        "current-token",
                        listOf(DRIVE_FILE_SCOPE)
                    ) is DriveAuthorizationApplyResult.Accepted
                )
            }
            unauthorizedResponse()
        }
        manager = newManager(transport = transport)
        val oldGeneration = authorize(manager, identityA, "old-token")
        val oldScope = SyncScope(identityA.email, "root-a", DocumentId.new())

        assertTrue(requireNotNull(manager.stage4Gateway()).find(oldScope) is RemoteLookup.Failed)
        assertTrue(manager.authorizationStatus.value.isAuthorized)
        assertTrue(manager.shouldRestoreSession())
        assertEquals(identityB, manager.authorizationStatus.value.identity)

        val invalidated = mutableListOf<String>()
        manager.invalidateRejectedAccessTokens { token -> invalidated += token }
        assertEquals(listOf("old-token"), invalidated)
        assertTrue(manager.authorizationStatus.value.isAuthorized)
        assertEquals(identityB, manager.authorizationStatus.value.identity)
        assertTrue(oldGeneration < manager.authorizationStatus.value.generation)
    }

    @Test
    fun failedOrCancelledTokenInvalidation_staysPendingUntilSuccessfulAndThenIsNotRepeated() = runTest {
        val transport = RecordingTransport { unauthorizedResponse() }
        val manager = newManager(transport = transport)
        val identity = GoogleIdentity("subject-a", "a@example.test")
        authorize(manager, identity, "rejected-token")
        val scope = SyncScope(identity.email, "root-a", DocumentId.new())
        assertTrue(requireNotNull(manager.stage4Gateway()).find(scope) is RemoteLookup.Failed)

        val attempts = mutableListOf<String>()
        var failed = false
        try {
            manager.invalidateRejectedAccessTokens { token ->
                attempts += token
                throw IllegalStateException("provider failed")
            }
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)

        var cancelled = false
        try {
            manager.invalidateRejectedAccessTokens { token ->
                attempts += token
                throw CancellationException("caller cancelled")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)

        manager.invalidateRejectedAccessTokens { token -> attempts += token }
        manager.invalidateRejectedAccessTokens { token -> attempts += "unexpected:$token" }
        assertEquals(
            listOf("rejected-token", "rejected-token", "rejected-token"),
            attempts
        )
    }

    @Test
    fun retainedGateway_cannotSendRequestsAfterSignOutOrIdentityChange() = runTest {
        val transport = RecordingTransport { emptyFileListResponse() }
        val manager = newManager(transport = transport)
        val identityA = GoogleIdentity("subject-a", "a@example.test")
        val oldGeneration = authorize(manager, identityA, "old-token")
        val oldScope = SyncScope(identityA.email, "root-a", DocumentId.new())
        val retainedGateway = requireNotNull(manager.stage4Gateway())

        assertEquals(RemoteLookup.NotFound, retainedGateway.find(oldScope))
        assertEquals(1, transport.requests.size)

        manager.clearSession()
        assertTrue(retainedGateway.find(oldScope) is RemoteLookup.Failed)
        assertEquals(1, transport.requests.size)

        val identityB = GoogleIdentity("subject-b", "b@example.test")
        val currentGeneration = authorize(manager, identityB, "current-token")
        assertTrue(currentGeneration > oldGeneration)
        assertTrue(retainedGateway.find(oldScope) is RemoteLookup.Failed)
        assertEquals(1, transport.requests.size)

        val currentScope = SyncScope(identityB.email, "root-b", DocumentId.new())
        assertEquals(RemoteLookup.NotFound, requireNotNull(manager.stage4Gateway()).find(currentScope))
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun staleGeneration_cannotSetOrClearAnotherAccountBackupFolder() {
        val manager = newManager()
        val identityA = GoogleIdentity("subject-a", "a@example.test")
        val generationA = authorize(manager, identityA, "token-a")
        assertTrue(manager.setBackupFolder(generationA, "root-a", "A Backups"))

        val identityB = GoogleIdentity("subject-b", "b@example.test")
        val generationB = authorize(manager, identityB, "token-b")
        assertFalse(manager.setBackupFolder(generationA, "stale-root", "Stale"))
        manager.clearBackupFolder(generationA)
        assertNull(manager.authorizationStatus.value.backupFolder)

        assertTrue(manager.setBackupFolder(generationB, "root-b", "B Backups"))
        manager.clearBackupFolder(generationA)
        assertEquals(
            DriveBackupFolder("root-b", "B Backups"),
            manager.authorizationStatus.value.backupFolder
        )
        assertEquals("b@example.test" to "root-b", manager.currentSyncAccountRoot())
    }

    @Test
    fun sameEmailDifferentGoogleSubject_doesNotInheritBackupFolder() {
        val manager = newManager()
        val identityA = GoogleIdentity("subject-a", "same@example.test")
        val generationA = authorize(manager, identityA, "token-a")
        assertTrue(manager.setBackupFolder(generationA, "root-a", "A Backups"))

        val identityB = GoogleIdentity("subject-b", "same@example.test")
        val generationB = authorize(manager, identityB, "token-b")
        assertNull(manager.authorizationStatus.value.backupFolder)
        assertNull(manager.currentSyncAccountRoot())

        assertTrue(manager.setBackupFolder(generationB, "root-b", "B Backups"))
        assertEquals("same@example.test" to "root-b", manager.currentSyncAccountRoot())
    }

    @Test
    fun unscopedOrEmailOnlyRoot_isNeverInherited() {
        val identity = GoogleIdentity("subject-a", "a@example.test")
        val unscopedStates = listOf(
            mapOf(
                PREF_BACKUP_FOLDER_ID to "unscoped-id",
                PREF_BACKUP_FOLDER_NAME to "Unscoped Backups"
            ),
            mapOf(
                PREF_BACKUP_FOLDER_ID to "email-only-id",
                PREF_BACKUP_FOLDER_NAME to "Email Only Backups",
                PREF_BACKUP_FOLDER_ACCOUNT to identity.email
            )
        )

        unscopedStates.forEach { values ->
            val prefs = InMemorySharedPreferences()
            values.forEach { (key, value) -> prefs.edit().putString(key, value).apply() }
            val manager = newManager(prefs = prefs)
            authorize(manager, identity, "token")

            assertNull(manager.authorizationStatus.value.backupFolder)
            assertNull(manager.currentSyncAccountRoot())
        }
    }

    @Test
    fun persistedRoot_appearsOnlyAfterMatchingFreshGrant_andSignOutDisablesRestore() {
        val prefs = InMemorySharedPreferences()
        val identity = GoogleIdentity("subject-a", "a@example.test")
        prefs.edit()
            .putString(PREF_BACKUP_FOLDER_ID, "persisted-root")
            .putString(PREF_BACKUP_FOLDER_NAME, "Persisted Backups")
            .putString(PREF_BACKUP_FOLDER_ACCOUNT, identity.email)
            .putString(PREF_BACKUP_FOLDER_SUBJECT, identity.subject)
            .putBoolean(PREF_RESTORE_GOOGLE_SESSION, true)
            .apply()
        val manager = newManager(prefs = prefs)

        assertTrue(manager.shouldRestoreSession())
        assertNull(manager.authorizationStatus.value.identity)
        assertNull(manager.authorizationStatus.value.backupFolder)

        val generation = authorize(manager, identity, "fresh-token")
        assertTrue(manager.authorizationStatus.value.isAuthorized)
        assertEquals(identity, manager.authorizationStatus.value.identity)
        assertEquals(
            DriveBackupFolder("persisted-root", "Persisted Backups"),
            manager.authorizationStatus.value.backupFolder
        )
        assertEquals("a@example.test" to "persisted-root", manager.currentSyncAccountRoot())
        assertFalse(prefs.getAll().values.any { it == "fresh-token" })
        assertTrue(manager.isSignedIn())
        assertTrue(generation > 0L)

        manager.clearSession()
        assertFalse(manager.shouldRestoreSession())
        assertFalse(manager.isSignedIn())
        assertNull(manager.currentSyncAccountRoot())
    }

    @Test
    fun clearRestoreSessionMarker_removesStaleStartupAttempt() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putBoolean(PREF_RESTORE_GOOGLE_SESSION, true).apply()
        val manager = newManager(prefs = prefs)

        assertTrue(manager.shouldRestoreSession())
        manager.clearRestoreSessionMarker()
        assertFalse(manager.shouldRestoreSession())
    }

    @Test
    fun failedAccountSwitch_doesNotRestoreThePreviouslyAuthorizedAccount() {
        val prefs = InMemorySharedPreferences()
        val manager = newManager(prefs = prefs)
        val accountA = GoogleIdentity("subject-a", "a@example.test")
        val accountB = GoogleIdentity("subject-b", "b@example.test")
        authorize(manager, accountA, "account-a-token")
        assertTrue(manager.shouldRestoreSession())

        val switchGeneration = manager.beginAuthenticationAttempt()
        assertFalse(manager.shouldRestoreSession())
        assertTrue(manager.authenticateIfCurrent(switchGeneration, accountB))
        assertTrue(manager.clearSessionIfCurrent(switchGeneration))

        assertFalse(manager.shouldRestoreSession())
        assertFalse(manager.isSignedIn())
        assertNull(manager.authorizationStatus.value.identity)
    }

    @Test
    fun rootCreation_lookupRequiresAppTagAndCreateCarriesAppTag() = runTest {
        val rootId = "0Aroot-create"
        val transport = RecordingTransport { request ->
            when {
                isRootIdentityRequest(request) ->
                    jsonResponse(200, """{"id":"$rootId"}""")
                request.method == "GET" -> emptyFileListResponse()
                else -> jsonResponse(
                    200,
                    """{"id":"root-created","name":"SOTAware Construct Backups","webViewLink":"https://drive.test/root-created","mimeType":"application/vnd.google-apps.folder","parents":["$rootId"],"appProperties":{"sotaware_backup_root":"1"},"trashed":false}"""
                )
            }
        }
        val manager = newManager(transport = transport)
        val identity = GoogleIdentity("subject-a", "a@example.test")
        val generation = authorize(manager, identity, "token")

        assertEquals(
            "root-created" to "SOTAware Construct Backups",
            manager.createRootBackupFolder(generation)
        )
        assertEquals(3, transport.requests.size)
        assertTrue(transport.requests[0].url.contains("fields=id"))
        val lookup = transport.requests[1]
        assertTrue(lookup.url.contains("appProperties"))
        assertTrue(lookup.url.contains("sotaware_backup_root"))
        val create = transport.requests[2]
        assertEquals("POST", create.method)
        val body = requireNotNull(create.body)
        assertTrue(body.contains("\"sotaware_backup_root\":\"1\""))
        assertTrue(body.contains("\"parents\":[\"$rootId\"]"))
    }

    @Test
    fun rootLookup_acceptsMarkedFolderWithOpaqueReturnedRootId() = runTest {
        val rootId = "0Aopaque-drive-root"
        val transport = RecordingTransport { request ->
            if (request.method == "GET" && request.url.contains("/files/root")) {
                jsonResponse(200, """{"id":"$rootId"}""")
            } else {
                check(request.method == "GET")
                jsonResponse(
                    200,
                    """{"files":[{"id":"root-existing-realistic","name":"SOTAware Construct Backups","mimeType":"application/vnd.google-apps.folder","parents":["$rootId"],"appProperties":{"sotaware_backup_root":"1"},"trashed":false}]}"""
                )
            }
        }
        val manager = newManager(transport = transport)
        val generation = authorize(
            manager,
            GoogleIdentity("subject-a", "a@example.test"),
            "token"
        )

        assertEquals(
            "root-existing-realistic" to "SOTAware Construct Backups",
            manager.createRootBackupFolder(generation)
        )
        assertEquals(listOf("GET", "GET"), transport.requests.map { it.method })
        assertTrue(transport.requests.first().url.contains("/files/root"))
        assertTrue(transport.requests.first().url.contains("fields=id"))
        assertTrue(transport.requests[1].url.contains(rootId))
    }

    @Test
    fun rootCreation_acceptsCreatedFolderWithOpaqueReturnedRootId() = runTest {
        val rootId = "0Aopaque-drive-root"
        val transport = RecordingTransport { request ->
            when {
                request.method == "GET" && request.url.contains("/files/root") ->
                    jsonResponse(200, """{"id":"$rootId"}""")
                request.method == "GET" -> emptyFileListResponse()
                request.method == "POST" -> jsonResponse(
                    200,
                    """{"id":"root-created-realistic","name":"SOTAware Construct Backups","mimeType":"application/vnd.google-apps.folder","parents":["$rootId"],"appProperties":{"sotaware_backup_root":"1"},"trashed":false}"""
                )
                else -> error("unexpected root request: $request")
            }
        }
        val manager = newManager(transport = transport)
        val generation = authorize(
            manager,
            GoogleIdentity("subject-a", "a@example.test"),
            "token"
        )

        assertEquals(
            "root-created-realistic" to "SOTAware Construct Backups",
            manager.createRootBackupFolder(generation)
        )
        assertEquals(listOf("GET", "GET", "POST"), transport.requests.map { it.method })
        assertTrue(transport.requests.first().url.contains("fields=id"))
        assertTrue(requireNotNull(transport.requests.last().body).contains("\"parents\":[\"$rootId\"]"))
    }

    @Test
    fun rootCreation_rejectsCreatedFolderUnderUnrelatedParentId() = runTest {
        val rootId = "0Aopaque-drive-root"
        val transport = RecordingTransport { request ->
            when {
                isRootIdentityRequest(request) ->
                    jsonResponse(200, """{"id":"$rootId"}""")
                request.method == "GET" -> emptyFileListResponse()
                request.method == "POST" -> jsonResponse(
                    200,
                    """{"id":"unrelated-created-root","name":"SOTAware Construct Backups","mimeType":"application/vnd.google-apps.folder","parents":["0Aunrelated-parent"],"appProperties":{"sotaware_backup_root":"1"},"trashed":false}"""
                )
                else -> error("unexpected root request: $request")
            }
        }
        val manager = newManager(transport = transport)
        val generation = authorize(
            manager,
            GoogleIdentity("subject-a", "a@example.test"),
            "token"
        )

        assertNull(manager.createRootBackupFolder(generation))
        assertEquals(listOf("GET", "GET", "POST"), transport.requests.map { it.method })
        assertNull(manager.authorizationStatus.value.backupFolder)
    }

    @Test
    fun rootLookup_rejectsMarkedFolderUnderUnrelatedParentId() = runTest {
        val rootId = "0Aopaque-drive-root"
        val transport = RecordingTransport { request ->
            if (request.method == "GET" && request.url.contains("/files/root")) {
                jsonResponse(200, """{"id":"$rootId"}""")
            } else {
                check(request.method == "GET")
                jsonResponse(
                    200,
                    """{"files":[{"id":"unrelated-root","name":"SOTAware Construct Backups","mimeType":"application/vnd.google-apps.folder","parents":["0Aunrelated-parent"],"appProperties":{"sotaware_backup_root":"1"},"trashed":false}]}"""
                )
            }
        }
        val manager = newManager(transport = transport)
        val generation = authorize(
            manager,
            GoogleIdentity("subject-a", "a@example.test"),
            "token"
        )

        assertNull(manager.createRootBackupFolder(generation))
        assertEquals(listOf("GET", "GET"), transport.requests.map { it.method })
        assertTrue(transport.requests.none { it.method == "POST" })
        assertNull(manager.authorizationStatus.value.backupFolder)
    }

    @Test
    fun rootResolution_rejectsEmptyRootIdWithoutListingOrCreating() = runTest {
        val transport = RecordingTransport { request ->
            check(isRootIdentityRequest(request))
            jsonResponse(200, """{"id":""}""")
        }
        val manager = newManager(transport = transport)
        val generation = authorize(
            manager,
            GoogleIdentity("subject-a", "a@example.test"),
            "token"
        )

        assertNull(manager.createRootBackupFolder(generation))
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.none { it.method == "POST" })
    }

    @Test
    fun rootResolution_rejectsMalformedRootResponseWithoutListingOrCreating() = runTest {
        val transport = RecordingTransport { request ->
            check(isRootIdentityRequest(request))
            jsonResponse(200, """{"files":[]}""")
        }
        val manager = newManager(transport = transport)
        val generation = authorize(
            manager,
            GoogleIdentity("subject-a", "a@example.test"),
            "token"
        )

        assertNull(manager.createRootBackupFolder(generation))
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.none { it.method == "POST" })
    }

    @Test
    fun rootResolution_rejectsWhitespaceRootIdWithoutListingOrCreating() = runTest {
        val transport = RecordingTransport { request ->
            check(isRootIdentityRequest(request))
            jsonResponse(200, """{"id":"   "}""")
        }
        val manager = newManager(transport = transport)
        val generation = authorize(
            manager,
            GoogleIdentity("subject-a", "a@example.test"),
            "token"
        )

        assertNull(manager.createRootBackupFolder(generation))
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.none { it.method == "POST" })
    }

    @Test
    fun rootResolution_failureDoesNotListOrCreate() = runTest {
        val transport = RecordingTransport { request ->
            check(isRootIdentityRequest(request))
            forbiddenResponse()
        }
        val manager = newManager(transport = transport)
        val generation = authorize(
            manager,
            GoogleIdentity("subject-a", "a@example.test"),
            "token"
        )

        assertNull(manager.createRootBackupFolder(generation))
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.none { it.method == "POST" })
        assertTrue(manager.authorizationStatus.value.isAuthorized)
    }

    @Test
    fun rootLookup_rejectsMarkedFolderWithInvalidMetadata() = runTest {
        val rootId = "0Aroot-invalid-metadata"
        val transport = RecordingTransport { request ->
            when {
                isRootIdentityRequest(request) ->
                    jsonResponse(200, """{"id":"$rootId"}""")
                request.method == "GET" -> jsonResponse(
                    200,
                    """{"files":[{"id":"wrong-root","name":"SOTAware Construct Backups","mimeType":"text/plain","parents":["$rootId"],"appProperties":{"sotaware_backup_root":"1"},"trashed":false}]}"""
                )
                else -> error("unexpected root request: $request")
            }
        }
        val manager = newManager(transport = transport)
        val generation = authorize(
            manager,
            GoogleIdentity("subject-a", "a@example.test"),
            "token"
        )

        assertNull(manager.createRootBackupFolder(generation))
        assertEquals(2, transport.requests.size)
        assertNull(manager.authorizationStatus.value.backupFolder)
    }

    @Test
    fun rootCreation_rejectsIncompleteCreatedMetadata() = runTest {
        val rootId = "0Aroot-incomplete"
        val transport = RecordingTransport { request ->
            when {
                isRootIdentityRequest(request) ->
                    jsonResponse(200, """{"id":"$rootId"}""")
                request.method == "GET" -> emptyFileListResponse()
                else -> jsonResponse(
                    200,
                    """{"id":"incomplete-root","name":"SOTAware Construct Backups"}"""
                )
            }
        }
        val manager = newManager(transport = transport)
        val generation = authorize(
            manager,
            GoogleIdentity("subject-a", "a@example.test"),
            "token"
        )

        assertNull(manager.createRootBackupFolder(generation))
        assertEquals(listOf("GET", "GET", "POST"), transport.requests.map { it.method })
        assertNull(manager.authorizationStatus.value.backupFolder)
    }

    @Test
    fun rootCreation_followsContinuationPagesAndReusesTaggedRoot() = runTest {
        val rootId = "0Aroot-pagination"
        var listCalls = 0
        val transport = RecordingTransport { request ->
            when {
                isRootIdentityRequest(request) ->
                    jsonResponse(200, """{"id":"$rootId"}""")
                request.method != "GET" -> error("unexpected root creation request")
                else -> {
                    listCalls += 1
                    if (request.url.contains("pageToken=page-2")) {
                        jsonResponse(
                            200,
                            """{"files":[{"id":"root-existing","name":"SOTAware Construct Backups","mimeType":"application/vnd.google-apps.folder","parents":["$rootId"],"appProperties":{"sotaware_backup_root":"1"},"trashed":false}]}"""
                        )
                    } else {
                        jsonResponse(200, """{"files":[],"nextPageToken":"page-2"}""")
                    }
                }
            }
        }
        val manager = newManager(transport = transport)
        val identity = GoogleIdentity("subject-a", "a@example.test")
        val generation = authorize(manager, identity, "token")

        assertEquals(
            "root-existing" to "SOTAware Construct Backups",
            manager.createRootBackupFolder(generation)
        )
        assertEquals(2, listCalls)
        assertEquals(3, transport.requests.size)
        assertTrue(transport.requests[2].url.contains("pageToken=page-2"))
    }

    @Test
    fun staleRootResolution_isRejectedAfterAccountSwitch() = runTest {
        val identityA = GoogleIdentity("subject-a", "a@example.test")
        val identityB = GoogleIdentity("subject-b", "b@example.test")
        val rootId = "0Aroot-stale"
        lateinit var manager: DriveSyncManager
        var switched = false
        val transport = RecordingTransport { request ->
            if (!switched) {
                switched = true
                val newerGeneration = manager.beginAuthenticationAttempt()
                check(manager.authenticateIfCurrent(newerGeneration, identityB))
                check(
                    manager.installAuthorizedDriveSession(
                        newerGeneration,
                        "token-b",
                        listOf(DRIVE_FILE_SCOPE)
                    ) is DriveAuthorizationApplyResult.Accepted
                )
            }
            check(isRootIdentityRequest(request))
            jsonResponse(200, """{"id":"$rootId"}""")
        }
        manager = newManager(transport = transport)
        val oldGeneration = authorize(manager, identityA, "token-a")

        assertTrue(runCatching { manager.createRootBackupFolder(oldGeneration) }.exceptionOrNull() is CancellationException)
        assertTrue(manager.authorizationStatus.value.isAuthorized)
        assertEquals(identityB, manager.authorizationStatus.value.identity)
        assertTrue(manager.authorizationStatus.value.generation > oldGeneration)
        assertNull(manager.authorizationStatus.value.backupFolder)
        assertNull(manager.currentSyncAccountRoot())
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun signOut_cancelsRootCreationAndDrainWaitsForAnAlreadySentPost() = runBlocking {
        val postStarted = CountDownLatch(1)
        val releasePost = CountDownLatch(1)
        val rootId = "0Aroot-signout"
        val transport = RecordingTransport { request ->
            when {
                isRootIdentityRequest(request) ->
                    jsonResponse(200, """{"id":"$rootId"}""")
                request.method == "GET" -> emptyFileListResponse()
                else -> {
                    postStarted.countDown()
                    check(releasePost.await(5, TimeUnit.SECONDS))
                    jsonResponse(
                        200,
                        """{"id":"old-root","name":"SOTAware Construct Backups","mimeType":"application/vnd.google-apps.folder","parents":["$rootId"],"appProperties":{"sotaware_backup_root":"1"},"trashed":false}"""
                    )
                }
            }
        }
        val manager = newManager(transport = transport)
        val generation = authorize(manager, GoogleIdentity("subject-a", "a@example.test"), "old-token")
        val root = async(Dispatchers.IO) { manager.createRootBackupFolder(generation) }
        try {
            assertTrue(postStarted.await(5, TimeUnit.SECONDS))
            manager.clearSession()
            val drain = async(start = CoroutineStart.UNDISPATCHED) { manager.cancelRootOperationsAndJoin() }
            assertFalse("Transition must wait for in-flight HTTP to finish", drain.isCompleted)
            assertNull(manager.currentSyncAccountRoot())
            assertNull(manager.stage4Gateway())
            releasePost.countDown()
            drain.await()
            root.join()
            assertTrue(root.isCancelled)
            assertTrue(root.isCompleted)
            assertNull(manager.authorizationStatus.value.backupFolder)
            assertEquals(listOf("GET", "GET", "POST"), transport.requests.map { it.method })
        } finally {
            releasePost.countDown()
            manager.clearSession()
            manager.cancelRootOperationsAndJoin()
        }
    }

    @Test
    fun unauthorized401_cancelsPendingRootLookupAndPreventsItsCreateRequest() = runBlocking {
        val lookupStarted = CountDownLatch(1)
        val releaseLookup = CountDownLatch(1)
        val rootId = "0Aroot-unauthorized"
        val transport = RecordingTransport { request ->
            if (isRootIdentityRequest(request)) {
                lookupStarted.countDown()
                check(releaseLookup.await(5, TimeUnit.SECONDS))
                jsonResponse(200, """{"id":"$rootId"}""")
            } else unauthorizedResponse()
        }
        val manager = newManager(transport = transport)
        val identity = GoogleIdentity("subject-a", "a@example.test")
        val generation = authorize(manager, identity, "rejected-token")
        val gateway = requireNotNull(manager.stage4Gateway())
        val root = async(Dispatchers.IO) { manager.createRootBackupFolder(generation) }
        try {
            assertTrue(lookupStarted.await(5, TimeUnit.SECONDS))
            assertTrue(gateway.find(SyncScope(identity.email, "root-a", DocumentId.new())) is RemoteLookup.Failed)
            assertFalse(manager.isSignedIn())
            val drain = async(start = CoroutineStart.UNDISPATCHED) { manager.cancelRootOperationsAndJoin() }
            assertFalse(drain.isCompleted)
            releaseLookup.countDown()
            drain.await()
            root.join()
            assertTrue(root.isCancelled)
            assertTrue(root.isCompleted)
            assertTrue(transport.requests.all { it.method == "GET" })
            assertNull(manager.authorizationStatus.value.backupFolder)
        } finally {
            releaseLookup.countDown()
            manager.clearSession()
            manager.cancelRootOperationsAndJoin()
        }
    }

    private suspend fun assertNonUnauthorizedFailureDoesNotRevoke(
        responder: (RecordedRequest) -> LowLevelHttpResponse
    ) {
        val transport = RecordingTransport(responder)
        val manager = newManager(transport = transport)
        val identity = GoogleIdentity("subject-a", "a@example.test")
        authorize(manager, identity, "valid-token")
        val scope = SyncScope(identity.email, "root-a", DocumentId.new())

        assertTrue(requireNotNull(manager.stage4Gateway()).find(scope) is RemoteLookup.Failed)
        assertTrue(manager.authorizationStatus.value.isAuthorized)
        assertEquals(identity, manager.authorizationStatus.value.identity)
        val requestsAfterFailure = transport.requests.size

        assertTrue(requireNotNull(manager.stage4Gateway()).find(scope) is RemoteLookup.Failed)
        assertEquals(requestsAfterFailure + 1, transport.requests.size)
        assertTrue(manager.authorizationStatus.value.isAuthorized)
    }

    private fun authorize(
        manager: DriveSyncManager,
        identity: GoogleIdentity,
        token: String
    ): Long {
        val generation = manager.beginAuthenticationAttempt()
        assertTrue(manager.authenticateIfCurrent(generation, identity))
        assertTrue(
            manager.installAuthorizedDriveSession(
                generation,
                token,
                listOf(DRIVE_FILE_SCOPE)
            ) is DriveAuthorizationApplyResult.Accepted
        )
        return generation
    }

    private fun newManager(
        prefs: InMemorySharedPreferences = InMemorySharedPreferences(),
        transport: HttpTransport = RecordingTransport { emptyFileListResponse() }
    ): DriveSyncManager {
        val root = Files.createTempDirectory("stage9-auth-context").toFile().absoluteFile
        val filesDir = root.resolve("files").also { check(it.mkdirs()) }
        val cacheDir = root.resolve("cache").also { check(it.mkdirs()) }
        check(filesDir.isAbsolute && cacheDir.isAbsolute) {
            "synthetic app context directories must be absolute"
        }
        val manager = DriveSyncManager(
            prefs, { filesDir }, transport, rootFailureDiagnostic = {},
            assetTransferFactory = { service, accountId, appStorage ->
                DriveImmutableAssetTransfer(
                    service, accountId,
                    appStorage.resolve("drive-transfer"),
                    appStorage.resolve("drive-staging"),
                    trustedRootDirectory = appStorage,
                    operationsFactory = TestPhotoPathOperationsFactory
                )
            }
        )
        testContexts += TestContext(root, filesDir, cacheDir, manager)
        return manager
    }

    private fun isRootIdentityRequest(request: RecordedRequest): Boolean =
        request.method == "GET" && request.url.contains("/files/root")

    private class RecordingTransport(
        private val responder: (RecordedRequest) -> LowLevelHttpResponse
    ) : MockHttpTransport() {
        val requests = mutableListOf<RecordedRequest>()

        override fun buildRequest(method: String, url: String): LowLevelHttpRequest =
            object : MockLowLevelHttpRequest(url) {
                private val headers = linkedMapOf<String, String>()

                override fun addHeader(name: String, value: String) {
                    headers[name] = value
                }

                override fun execute(): LowLevelHttpResponse {
                    val request = RecordedRequest(
                        method = method,
                        url = url,
                        headers = headers.toMap(),
                        body = if (method.equals("GET", ignoreCase = true)) null else getContentAsString()
                    )
                    synchronized(requests) { requests += request }
                    return responder(request)
                }
            }
    }

    private data class RecordedRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String?
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    /** Minimal isolated app-private context storage used by DriveSyncManager. */
    private data class TestContext(
        val root: File,
        val filesDir: File,
        val cacheDir: File,
        val manager: DriveSyncManager
    )

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = values.toMap()

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                values[key.orEmpty()] = value
                return this
            }

            override fun putStringSet(
                key: String?,
                value: MutableSet<String>?
            ): SharedPreferences.Editor {
                values[key.orEmpty()] = value?.toSet()
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                values[key.orEmpty()] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                values[key.orEmpty()] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                values[key.orEmpty()] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                values[key.orEmpty()] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                values.remove(key.orEmpty())
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                values.clear()
                return this
            }

            override fun commit(): Boolean = true

            override fun apply() = Unit
        }
    }

    private companion object {
        const val TEST_CLEANUP_TIMEOUT_MILLIS = 2_000L
        const val PREF_BACKUP_FOLDER_ID = "backup_folder_id"
        const val PREF_BACKUP_FOLDER_NAME = "backup_folder_name"
        const val PREF_BACKUP_FOLDER_ACCOUNT = "backup_folder_account"
        const val PREF_BACKUP_FOLDER_SUBJECT = "backup_folder_subject"
        const val PREF_RESTORE_GOOGLE_SESSION = "restore_google_session"

        fun jsonResponse(statusCode: Int, content: String): MockLowLevelHttpResponse =
            MockLowLevelHttpResponse()
                .setStatusCode(statusCode)
                .setContentType("application/json")
                .setContent(content)

        fun emptyFileListResponse(): MockLowLevelHttpResponse =
            jsonResponse(200, """{"files":[]}""")

        fun forbiddenResponse(): MockLowLevelHttpResponse =
            jsonResponse(403, """{"error":{"code":403,"message":"forbidden"}}""")

        fun unauthorizedResponse(): MockLowLevelHttpResponse =
            jsonResponse(401, """{"error":{"code":401,"message":"unauthorized"}}""")
    }
}
