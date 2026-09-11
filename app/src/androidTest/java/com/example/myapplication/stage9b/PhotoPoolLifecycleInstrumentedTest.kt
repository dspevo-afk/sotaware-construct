package com.example.myapplication.stage9b

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.stage1.*
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage5.DocumentPhotoAssetStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Real Android anchored storage and production capture APIs; synthetic data only. */
@RunWith(AndroidJUnit4::class)
class PhotoPoolLifecycleInstrumentedTest {
    @Test fun productionAdmissionReclaimsReleasedCopiesButKeepsAnOutstandingExport() = fixture { root, id ->
        val first = DocumentPhotoAssetStore(root, id)
        writePhoto(first, 1)
        val held = first.capturePhotoAssets(snapshot())
        val heldBytes = held.assets.getValue("photo.jpg").open().use { it.readBytes() }
        first.close()
        val pool = poolRoot(root, id)
        val evidence = File(pool, "unknown-evidence.bin").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        try {
            DocumentPhotoAssetStore(root, id).use { second ->
                repeat(12) { generation ->
                    writePhoto(second, generation + 2)
                    second.capturePhotoAssetsForAdmission(snapshot(), snapshot()).use {
                        assertEquals(2, contentFiles(pool).size)
                        assertArrayEquals(heldBytes, held.assets.getValue("photo.jpg").open().use { it.readBytes() })
                    }
                }
                held.close()
                second.capturePhotoAssets(snapshot()).use { assertEquals(1, contentFiles(pool).size) }
                assertArrayEquals(byteArrayOf(4, 5, 6), evidence.readBytes())
            }
        } finally { held.close(); first.close() }
    }

    @Test fun overlappingStoresReleaseInEitherOrderWithoutLosingCollectionRecords() {
        listOf(true, false).forEach { oldestLast -> fixture { root, id ->
            DocumentPhotoAssetStore(root, id).use { first ->
                writePhoto(first, 21)
                val a = first.capturePhotoAssets(snapshot())
                DocumentPhotoAssetStore(root, id).use { second ->
                    writePhoto(second, 22)
                    val b = second.capturePhotoAssets(snapshot())
                    try {
                        val before = generation(poolRoot(root, id))
                        if (oldestLast) { b.close(); a.close() } else { a.close(); b.close() }
                        assertTrue(generation(poolRoot(root, id)) > before)
                        assertEquals(2, first.cleanupUnreachablePhotoAssets())
                        assertTrue(contentFiles(poolRoot(root, id)).isEmpty())
                        assertTrue(File(first.resolver.root, "photo.jpg").isFile)
                    } finally { b.close(); a.close() }
                }
            }
        } }
    }

    @Test fun olderCollectorSeesAnotherStoresNewlyReleasedCopy() = fixture { root, id ->
        DocumentPhotoAssetStore(root, id).use { collector ->
            assertEquals(0, collector.cleanupUnreachablePhotoAssets())
            DocumentPhotoAssetStore(root, id).use { writer ->
                writePhoto(writer, 31)
                writer.capturePhotoAssets(snapshot()).close()
            }
            assertEquals(1, collector.cleanupUnreachablePhotoAssets())
            assertTrue(contentFiles(poolRoot(root, id)).isEmpty())
        }
    }

    @Test fun interruptedIndexRecoversThroughProductionCaptureAfterReopen() = fixture { root, id ->
        val expected = DocumentPhotoAssetStore(root, id).use { writer ->
            writePhoto(writer, 41)
            writer.capturePhotoAssets(snapshot()).use { it.assets.getValue("photo.jpg").open().use { input -> input.readBytes() } }
        }
        val staged = stageInterruptedIndex(poolRoot(root, id), "1")
        DocumentPhotoAssetStore(root, id).use { reopened ->
            reopened.capturePhotoAssets(snapshot()).use { capture ->
                assertFalse(staged.exists())
                assertArrayEquals(expected, capture.assets.getValue("photo.jpg").open().use { it.readBytes() })
            }
        }
    }

    @Test fun interruptedReleaseKeepsLiveOwnerUntilItsActualRelease() = fixture { root, id ->
        DocumentPhotoAssetStore(root, id).use { owner ->
            writePhoto(owner, 42)
            val held = owner.capturePhotoAssets(snapshot())
            try {
                val expected = held.assets.getValue("photo.jpg").open().use { it.readBytes() }
                val staged = stageInterruptedIndex(poolRoot(root, id), "0")
                DocumentPhotoAssetStore(root, id).use { observer ->
                    assertEquals(0, observer.cleanupUnreachablePhotoAssets())
                    assertFalse(staged.exists())
                    assertArrayEquals(expected, held.assets.getValue("photo.jpg").open().use { it.readBytes() })
                    held.close()
                    assertEquals(1, observer.cleanupUnreachablePhotoAssets())
                }
            } finally { held.close() }
        }
    }

    private fun stageInterruptedIndex(pool: File, retention: String): File {
        val committed = pool.listFiles()!!.filter {
            it.name in setOf(".stage9b-photo-pool.a", ".stage9b-photo-pool.b")
        }.maxBy { it.readLines()[1].toLong() }
        val lines = committed.readLines().toMutableList()
        lines[1] = (lines[1].toLong() + 1L).toString()
        val fields = lines[3].split('\t').toMutableList()
        fields[5] = retention; lines[3] = fields.joinToString("\t")
        return File(pool, ".stage9b-pool-index.tmp").apply { writeText(lines.joinToString("\n", postfix = "\n")) }
    }

    private fun writePhoto(store: DocumentPhotoAssetStore, marker: Int) {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(android.graphics.Color.rgb(marker * 7 % 256, marker * 17 % 256, marker * 29 % 256))
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
        File(store.resolver.root, "photo.jpg").writeBytes(bytes)
    }
    private fun snapshot() = DocumentSnapshotV1(schemaVersion = 2, snapshotRevision = 1L,
        source = DocumentSourceIdentityV1("content://synthetic/pool-lifecycle", "plan.pdf"),
        pages = mapOf(0 to PageSnapshotV1(photoPins = listOf(PhotoPinSnapshotV1(
            x = .5f, y = .5f, id = "pin", imageFileNames = listOf("photo.jpg"),
            imageNotes = emptyMap(), imageShapes = emptyMap())))))
    private fun poolRoot(root: File, id: DocumentId) = File(root, "immutable-photo-assets/${id.value}")
    private fun contentFiles(root: File) = root.listFiles()!!.filter { it.name.startsWith(".stage9b-photo-asset-") }
    private fun generation(root: File) = root.listFiles()!!.filter {
        it.name in setOf(".stage9b-photo-pool.a", ".stage9b-photo-pool.b")
    }.maxOf { it.readLines()[1].toLong() }
    private fun fixture(test: (File, DocumentId) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "stage9b-pool-lifecycle-${UUID.randomUUID()}").apply { check(mkdir()) }
        try { test(root, DocumentId.new()) } finally { check(root.deleteRecursively()) }
    }
}
