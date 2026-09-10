package com.example.myapplication.stage9b

import com.example.myapplication.stage1.*
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage5.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/** Uses the real store admission and its unchanged 200 MiB pool limit. */
class DocumentPhotoPoolAdmissionRegressionTest {
    @Test fun repeatedPhotoReplacementBeyondHistoricalDiskLimitPreservesOutstandingExport() {
        val root = Files.createTempDirectory("stage9b-production-pool-admission").toFile()
        val id = DocumentId.new()
        val store = DocumentPhotoAssetStore(root, id, DefaultImageProbe, TestPhotoPathOperationsFactory)
        val poolRoot = File(root, "immutable-photo-assets/${id.value}")
        val snapshot = DocumentSnapshotV1(schemaVersion = 2, snapshotRevision = 1L,
            source = DocumentSourceIdentityV1("content://synthetic/pool-admission", "plan.pdf"),
            pages = mapOf(0 to PageSnapshotV1(photoPins = listOf(PhotoPinSnapshotV1(
                x = .5f, y = .5f, id = "pin", imageFileNames = listOf("photo.jpg"),
                imageNotes = emptyMap(), imageShapes = emptyMap())))))
        val jpeg = Stage4PhotoFixture.jpegBytes()
        val content = paddedJpeg(jpeg).apply {
            id.value.toByteArray().copyInto(this, jpeg.size + 2)
        }
        var outstanding: PhotoAssetCapture? = null
        try {
            File(store.resolver.root, "photo.jpg").writeBytes(content)
            outstanding = store.capturePhotoAssets(snapshot)
            val originalHash = outstanding.assets.getValue("photo.jpg").descriptor.sha256
            store.close() // Export ownership must survive the UI/store lifetime.
            DocumentPhotoAssetStore(root, id, DefaultImageProbe, TestPhotoPathOperationsFactory).use { current ->
                repeat(10) { generation ->
                    content[jpeg.size + 66] = (generation + 1).toByte()
                    File(current.resolver.root, "photo.jpg").writeBytes(content)
                    current.capturePhotoAssetsForAdmission(snapshot, snapshot).use { capture ->
                        assertEquals(content.size.toLong(), capture.assets.totalBytes)
                        assertEquals("only held export plus current admission may remain", 2,
                            poolRoot.listFiles()!!.count { it.name.startsWith(".stage9b-photo-asset-") })
                        assertTrue(poolRoot.listFiles()!!.sumOf { it.length() } < IMMUTABLE_PHOTO_POOL_MAX_TOTAL_BYTES)
                    }
                }
                assertEquals(originalHash, outstanding.assets.getValue("photo.jpg").open().use {
                    photoContentIdentity(it, content.size.toLong(), "held export").sha256
                })
                outstanding.close()
                current.capturePhotoAssets(snapshot).use {
                    assertEquals(1, poolRoot.listFiles()!!.count { it.name.startsWith(".stage9b-photo-asset-") })
                }
            }
        } finally {
            outstanding?.close()
            store.close()
            check(root.deleteRecursively())
        }
    }

    private fun paddedJpeg(base: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(Stage5Limits.MAX_PHOTO_BYTES)
        output.write(base, 0, base.size - 2)
        var remaining = Stage5Limits.MAX_PHOTO_BYTES - base.size
        val padding = ByteArray(65_533)
        while (remaining >= 4) {
            val payload = minOf(padding.size, remaining - 4)
            output.write(0xff); output.write(0xef) // JPEG APP15, not trailing garbage.
            output.write((payload + 2) ushr 8); output.write((payload + 2) and 0xff)
            output.write(padding, 0, payload)
            remaining -= payload + 4
        }
        output.write(0xff); output.write(0xd9)
        return output.toByteArray()
    }
}
