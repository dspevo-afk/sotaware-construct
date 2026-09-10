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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Android proof that ephemeral transfer admission does not retain directory descriptors. */
@RunWith(AndroidJUnit4::class)
class DriveTransferResourceLifecycleInstrumentedTest {
    @Test
    fun warmGatewayConstructionHasBoundedFdGrowth() = runBlocking {
        withStorageFixture { manager, _, _ ->
            assertEquals(RemoteLookup.NotFound, requireNotNull(manager.stage4Gateway()).find(scope()))
            val baseline = fdCount()
            repeat(32) { assertNotNull(manager.stage4Gateway()) }
            assertTrue("ephemeral gateway construction leaked descriptors", fdCount() <= baseline + 4)
        }
    }

    @Test
    fun failedSecondRootAdmissionHasBoundedFdGrowth() = runBlocking {
        withStorageFixture { manager, root, requests ->
            val outside = File(root.parentFile, "stage9b-transfer-lifetime-outside-${UUID.randomUUID()}")
                .apply { check(mkdir()) }
            val sentinel = File(outside, "sentinel.bin").apply { writeBytes(byteArrayOf(7, 6, 5)) }
            val staging = File(root, "drive-staging").toPath()
            try {
                Files.createSymbolicLink(staging, outside.toPath())
                val baseline = fdCount()
                repeat(32) {
                    val failure = runCatching { manager.stage4Gateway() }.exceptionOrNull()
                    assertTrue(
                        "staging admission must fail closed: $failure",
                        failure is IOException ||
                            failure is com.example.myapplication.stage5.Stage5ValidationException
                    )
                }
                assertTrue("failed admission leaked descriptors", fdCount() <= baseline + 4)
                assertEquals(0, requests.get())
                assertTrue(sentinel.readBytes().contentEquals(byteArrayOf(7, 6, 5)))
            } finally {
                Files.deleteIfExists(staging)
                check(sentinel.delete())
                check(outside.delete())
            }
        }
    }

    private fun scope() = SyncScope("storage@example.test", "disposable-root", DocumentId.new())

    private fun fdCount(): Int = File("/proc/self/fd").list()?.size
        ?: error("/proc/self/fd is unavailable")

    private suspend fun withStorageFixture(
        block: suspend (DriveSyncManager, File, AtomicInteger) -> Unit
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "stage9b-transfer-lifetime-${UUID.randomUUID()}"
        val root = File(context.filesDir, name).apply { check(mkdir()) }
        val requests = AtomicInteger()
        val transport = object : MockHttpTransport() {
            override fun buildRequest(method: String, url: String) =
                object : MockLowLevelHttpRequest(url) {
                    override fun execute(): MockLowLevelHttpResponse {
                        requests.incrementAndGet()
                        check(method == "GET")
                        return MockLowLevelHttpResponse().setStatusCode(200)
                            .setContentType("application/json")
                            .setContent("{\"files\":[]}")
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
