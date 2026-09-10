package com.example.myapplication.stage9b

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Stage9BFixtureControlInstrumentedTest {
    @Test
    fun signedFixtureControlDoesNotGrantManageDocumentsToTheTarget() {
        Stage9BProviderFixtureAccess.prepare()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.MANAGE_DOCUMENTS))
        val failure = runCatching {
            context.contentResolver.call(
                Uri.parse("content://${Stage9BQualificationDocumentsProvider.AUTHORITY}"),
                Stage9BQualificationDocumentsProvider.CALL_PREPARE, null, null)
        }.exceptionOrNull()
        assertTrue("protected document control unexpectedly became accessible: $failure",
            failure is SecurityException)
    }

    @Test
    fun unsignedShellCallerCannotUseTheFixtureControlPlane() {
        Stage9BProviderFixtureAccess.prepare()
        // executeShellCommand exposes stdout only. Send a fixed command to
        // the API-31 shell stdin so its denial on stderr is captured as evidence.
        val descriptors = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommandRw("sh")
        val descriptor = descriptors[0]
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { output ->
                output.write(("content call --uri content://${Stage9BFixtureControlProvider.AUTHORITY} " +
                    "--method ${Stage9BQualificationDocumentsProvider.CALL_PREPARE} 2>&1\nexit\n")
                    .toByteArray(Charsets.UTF_8))
            }
        } catch (failure: Throwable) {
            descriptor.close()
            throw failure
        }
        val response = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val bytes = ByteArray(65536)
            var total = 0
            while (total < bytes.size) {
                val count = input.read(bytes, total, bytes.size - total)
                if (count < 0) break
                if (count > 0) total += count
            }
            bytes.copyOf(total).toString(Charsets.UTF_8)
        }
        assertTrue("shell caller was not rejected by the fixture identity gate: $response",
            response.contains("SecurityException") && response.contains("exact signed debug target UID"))
    }
}
