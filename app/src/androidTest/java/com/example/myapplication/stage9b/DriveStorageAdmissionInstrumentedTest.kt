package com.example.myapplication.stage9b

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.DriveSyncManager
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.RemoteLookup
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage9.DRIVE_FILE_SCOPE
import com.example.myapplication.stage9.DriveAuthorizationApplyResult
import com.example.myapplication.stage9.GoogleIdentity
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the default Android filesystem boundary, not the desktop test seam. */
@RunWith(AndroidJUnit4::class)
class DriveStorageAdmissionInstrumentedTest {
    @Test
    fun defaultTransferAcceptsAndroidOwnedAliasesAboveFilesDir() = runBlocking {
        withStorageFixture { manager, root, requests ->
            val result = requireNotNull(manager.stage4Gateway()).find(scope())
            assertEquals(RemoteLookup.NotFound, result)
            assertEquals(1, requests.get())
            assertTrue(File(root, "drive-transfer").isDirectory)
            assertTrue(File(root, "drive-staging").isDirectory)
        }
    }

    @Test
    fun defaultTransferRejectsSymlinkBelowItsTrustedRootBeforeNetwork() = runBlocking {
        withStorageFixture { manager, root, requests ->
            val outside = File(root.parentFile, "stage9b-storage-outside-${UUID.randomUUID()}")
            check(outside.mkdir())
            val sentinel = File(outside, "sentinel.bin").apply { writeBytes(byteArrayOf(9, 8, 7)) }
            val link = File(root, "drive-staging").toPath()
            try {
                Files.createSymbolicLink(link, outside.toPath())
                val failure = runCatching { manager.stage4Gateway() }.exceptionOrNull()
                assertTrue("unsafe staging must be rejected, not silently redirected: $failure",
                    failure is java.io.IOException ||
                        failure is com.example.myapplication.stage5.Stage5ValidationException)
                assertEquals(0, requests.get())
                assertArrayEquals(byteArrayOf(9, 8, 7), sentinel.readBytes())
                assertTrue(manager.authorizationStatus.value.isAuthorized)
            } finally {
                Files.deleteIfExists(link)
                check(sentinel.delete())
                check(outside.delete())
            }
        }
    }

    private fun scope() = SyncScope("storage@example.test", "disposable-root", DocumentId.new())

    private suspend fun withStorageFixture(
        block: suspend (DriveSyncManager, File, AtomicInteger) -> Unit
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "stage9b-storage-${UUID.randomUUID()}"
        val root = File(context.filesDir, name).apply { check(mkdir()) }
        val requests = AtomicInteger()
        val transport = object : MockHttpTransport() {
            override fun buildRequest(method: String, url: String) = object : MockLowLevelHttpRequest(url) {
                override fun execute(): MockLowLevelHttpResponse {
                    requests.incrementAndGet()
                    check(method == "GET")
                    return MockLowLevelHttpResponse().setStatusCode(200)
                        .setContentType("application/json").setContent("{\"files\":[]}")
                }
            }
        }
        val preferences = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
        val manager = DriveSyncManager(preferences, { root }, transport, rootFailureDiagnostic = {})
        try {
            val generation = manager.beginAuthenticationAttempt()
            assertTrue(manager.authenticateIfCurrent(generation,
                GoogleIdentity("storage-subject", "storage@example.test")))
            assertTrue(manager.installAuthorizedDriveSession(generation, "synthetic-token",
                listOf(DRIVE_FILE_SCOPE)) is DriveAuthorizationApplyResult.Accepted)
            block(manager, root, requests)
        } finally {
            manager.clearSession()
            manager.cancelRootOperationsAndJoin()
            // Drain queued writes before deleting this test-owned preferences file.
            check(preferences.edit().clear().commit())
            check(root.deleteRecursively())
            context.deleteSharedPreferences(name)
        }
    }
}
