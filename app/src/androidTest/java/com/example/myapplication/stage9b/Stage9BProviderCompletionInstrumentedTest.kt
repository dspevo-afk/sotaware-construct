package com.example.myapplication.stage9b

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated native descriptor/lifecycle tests, not a substitute for actual SAF grants. */
@RunWith(AndroidJUnit4::class)
class Stage9BProviderCompletionInstrumentedTest {
    @Test fun actualWritersBlockReadsClearAndDeleteUntilTheLastSuccessfulClose() = withProvider { context, provider ->
        val id = provider.createDocument(Stage9BQualificationDocumentsProvider.ROOT_ID,
            "application/octet-stream", "completed.bin")
        val first = provider.openDocument(id, "w", null)
        val second = provider.openDocument(id, "w", null)
        try {
            val bytes = "fully-written-fixture".toByteArray()
            ParcelFileDescriptor.AutoCloseOutputStream(first).use { it.write(bytes) }
            assertDenied { provider.openDocument(id, "r", null).close() }
            assertFalse(childIds(provider).contains(id))
            assertDenied { provider.deleteDocument(id) }
            assertDenied { provider.handleFixtureCall(Stage9BQualificationDocumentsProvider.CALL_CLEAR, null, null) }
            second.close()
            await { childIds(provider).contains(id) }
            ParcelFileDescriptor.AutoCloseInputStream(provider.openDocument(id, "r", null)).use {
                assertArrayEquals(bytes, it.readBytes())
            }
            assertDenied { provider.openDocument(id, "w", null).close() }
            provider.handleFixtureCall(Stage9BQualificationDocumentsProvider.CALL_CLEAR, null, null)
            assertFalse(childIds(provider).contains(id))
        } finally {
            first.close()
            second.close()
        }
    }

    @Test fun recreatedProviderCannotAdoptAnUnknownCreationMarker() = withProvider { context, provider ->
        val root = Stage9BQualificationDocumentsProvider.ROOT_ID
        val id = provider.createDocument(root, "application/octet-stream", "interrupted.bin")
        val recreated = attachProvider(context)
        assertDenied { recreated.openDocument(id, "r", null).close() }
        assertDenied { recreated.openDocument(id, "w", null).close() }
        assertFalse(childIds(recreated).contains(id))
        // Only an explicit, owned fixture clear permits a genuinely new create.
        recreated.handleFixtureCall(Stage9BQualificationDocumentsProvider.CALL_CLEAR, null, null)
        val replacement = recreated.createDocument(root, "application/octet-stream", "interrupted.bin")
        val bytes = "replacement-generation".toByteArray()
        ParcelFileDescriptor.AutoCloseOutputStream(recreated.openDocument(replacement, "w", null)).use {
            it.write(bytes)
        }
        await { childIds(recreated).contains(replacement) }
        ParcelFileDescriptor.AutoCloseInputStream(recreated.openDocument(replacement, "r", null)).use {
            assertArrayEquals(bytes, it.readBytes())
        }
    }

    private fun childIds(provider: Stage9BQualificationDocumentsProvider): Set<String> =
        provider.queryChildDocuments(Stage9BQualificationDocumentsProvider.ROOT_ID,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null as String?).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun assertDenied(action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        assertTrue("pending/unknown fixture operation unexpectedly succeeded",
            failure is java.io.FileNotFoundException || failure is IllegalStateException)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("provider close callback did not complete")
            SystemClock.sleep(20L)
        }
    }

    private fun attachProvider(context: Context): Stage9BQualificationDocumentsProvider {
        val info = ProviderInfo().apply {
            authority = "stage9b.isolated.lifecycle." + UUID.randomUUID()
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
            applicationInfo = context.applicationInfo
        }
        return Stage9BQualificationDocumentsProvider().apply { attachInfo(context, info) }
    }

    private fun withProvider(block: (Context, Stage9BQualificationDocumentsProvider) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val owned = File(instrumentation.targetContext.filesDir,
            "stage9b-provider-lifecycle-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = owned
            override fun getAssets() = instrumentation.context.assets
        }
        try { block(context, attachProvider(context)) }
        finally { owned.deleteRecursively() }
    }
}
