package com.example.myapplication.stage9b

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage6.BundleExportInput
import com.example.myapplication.stage6.DocumentBundleService
import com.example.myapplication.stage6.SOTAWARE_BUNDLE_SNAPSHOT_SCHEMA_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Bounded Android-side codec/staging smoke. The actual ACTION_* SAF picker
 * workflow remains a root-owned integration gate and is not faked here.
 */
@RunWith(AndroidJUnit4::class)
class CurrentBundleInstrumentedTest {
    @Test
    fun currentBundleRoundTripUsesAppPrivateStagingAndReleasesIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staging = File(context.filesDir, "stage9b-bundle-test")
        val service = DocumentBundleService(
            stagingDirectory = staging,
            trustedRootDirectory = context.filesDir
        )
        assertEquals(context.filesDir.toPath(), staging.parentFile?.toPath())
        val source = DocumentSourceIdentityV1(
            sourceUri = "content://stage9b/native/current.pdf",
            displayName = "current.pdf",
            providerMetadata = mapOf("authority" to "stage9b")
        )
        val snapshot = DocumentSnapshotV1(
            schemaVersion = SOTAWARE_BUNDLE_SNAPSHOT_SCHEMA_VERSION,
            snapshotRevision = 1L,
            source = source,
            pages = emptyMap()
        )
        try {
            val output = ByteArrayOutputStream()
            service.writeBundle(
                output,
                BundleExportInput(
                    exportedDocumentId = DocumentId.new(),
                    source = source,
                    sourceFingerprint = SourceFingerprint.fromBytes("native-current".toByteArray()),
                    snapshot = snapshot,
                    photoFiles = PhotoAssetSet.EMPTY
                )
            )
            assertFalse(output.size() == 0)
            val decoded = service.readBundle(ByteArrayInputStream(output.toByteArray()))
            decoded.use {
                assertEquals(snapshot, decoded.snapshot)
                assertEquals(emptySet<String>(), decoded.photoFiles.keys)
            }
            assertFalse(staging.listFiles().orEmpty().any { it.name.startsWith(".sotaware-bundle-") })
        } finally {
            staging.deleteRecursively()
        }
    }
}
