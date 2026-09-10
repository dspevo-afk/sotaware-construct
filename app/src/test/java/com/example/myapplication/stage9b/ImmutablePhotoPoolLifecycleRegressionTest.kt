package com.example.myapplication.stage9b

import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage5.DefaultImageProbe
import com.example.myapplication.stage5.PhotoDescriptor
import com.example.myapplication.stage5.sha256Hex
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ImmutablePhotoPoolLifecycleRegressionTest {
    @Test fun admissionCollectsReleasedGenerationsWithoutManualCleanup() = withRoot { root ->
        pool(root, 1).use { pool ->
            repeat(12) { generation ->
                pool.capture(assets("generation-$generation")).use { capture ->
                    assertEquals(1, pool.assetCount)
                    assertArrayEquals(bytes("generation-$generation"), capture.assets.getValue("photo.jpg").open().use { it.readBytes() })
                }
            }
        }
    }

    @Test fun admissionPreservesLiveBorrowersAndUnknownEvidence() = withRoot { root ->
        pool(root, 3).use { first ->
            val held = first.capture(assets("held"))
            try {
                val unknown = File(root, "unknown-evidence.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                pool(root, 3).use { second ->
                    repeat(8) { generation -> second.capture(assets("next-$generation")).close() }
                    assertEquals(2, second.assetCount)
                    assertArrayEquals(bytes("held"), held.assets.getValue("photo.jpg").open().use { it.readBytes() })
                    assertArrayEquals(byteArrayOf(1, 2, 3), unknown.readBytes())
                }
            } finally { held.close() }
        }
    }

    @Test fun overlappingInstancesReleaseOldestLastWithoutLosingNewRecords() = overlap(oldestLast = true)
    @Test fun overlappingInstancesReleaseOldestFirstWithoutLosingNewRecords() = overlap(oldestLast = false)

    private fun overlap(oldestLast: Boolean) = withRoot { root ->
        pool(root, 3).use { first ->
            val a = first.capture(assets("overlap-a-$oldestLast"))
            pool(root, 3).use { second ->
                val b = second.capture(assets("overlap-b-$oldestLast"))
                try {
                    val before = generation(root)
                    if (oldestLast) { b.close(); a.close() } else { a.close(); b.close() }
                    assertTrue("release must advance the current generation", generation(root) > before)
                    assertEquals("every instance observes the latest authoritative records", 2, first.assetCount)
                    assertEquals(2, second.assetCount)
                    pool(root, 3).use { reopened ->
                        assertEquals(2, reopened.assetCount)
                        assertEquals(2, reopened.cleanupUnreachable())
                        assertEquals(0L, reopened.physicalBytes)
                    }
                } finally { b.close(); a.close() }
            }
        }
    }

    @Test fun oldInstanceCanRetainASetPublishedByAnotherInstance() = withRoot { root ->
        pool(root, 2).use { first ->
            pool(root, 2).use { second ->
                second.capture(assets("retained")).use { capture ->
                    first.retain(capture.assets).use {
                        capture.close()
                        assertEquals(0, second.cleanupUnreachable())
                        assertArrayEquals(bytes("retained"), capture.assets.getValue("photo.jpg").open().use { it.readBytes() })
                    }
                    assertEquals(1, second.cleanupUnreachable())
                }
            }
        }
    }

    @Test fun oldInstanceCleanupSeesNewlyReleasedRecords() = withRoot { root ->
        pool(root, 2).use { collector ->
            pool(root, 2).use { writer -> writer.capture(assets("collector")).close() }
            assertEquals(1, collector.cleanupUnreachable())
            assertEquals(0L, collector.physicalBytes)
        }
    }

    @Test fun closedOldStoreCanReleaseAfterAnotherWriterPublishes() = withRoot { root ->
        val first = pool(root, 3)
        val a = first.capture(assets("closed-a"))
        first.close()
        try {
            pool(root, 3).use { second ->
                second.capture(assets("closed-b")).close()
                a.close()
                assertEquals(2, second.cleanupUnreachable())
                assertEquals(0L, second.physicalBytes)
            }
        } finally { a.close(); first.close() }
    }

    @Test fun recaptureMayUseReleasedPoolHandlesAsItsSource() = withRoot { root ->
        pool(root, 1).use { pool ->
            val original = pool.capture(assets("source-preserved"))
            original.close()
            pool.capture(original.assets).use { recaptured ->
                assertArrayEquals(bytes("source-preserved"), recaptured.assets.getValue("photo.jpg").open().use { it.readBytes() })
            }
        }
    }

    @Test fun anotherOwnerClaimSurvivesAdmissionCollection() = withRoot { root ->
        pool(root, 2).use { pool ->
            val capture = pool.capture(assets("borrowed-outbox"))
            PhotoAssetOwnershipRegistry.claim("synthetic-outbox", capture.assets).use {
                capture.close()
                repeat(5) { pool.capture(assets("borrower-next-$it")).close() }
                assertArrayEquals(bytes("borrowed-outbox"), capture.assets.getValue("photo.jpg").open().use { it.readBytes() })
            }
            assertEquals(2, pool.cleanupUnreachable())
        }
    }

    private fun generation(root: File): Long = root.listFiles()!!.filter { it.name in setOf(".stage9b-photo-pool.a", ".stage9b-photo-pool.b") }.maxOf { it.readLines()[1].toLong() }
    private fun pool(root: File, count: Int) = ImmutablePhotoAssetPool(root, DefaultImageProbe, count, 1024 * 1024L, LocalPhotoPathOperationsFactory, root)
    private fun bytes(label: String): ByteArray {
        val jpeg = Stage4PhotoFixture.jpegBytes()
        val comment = "pool-lifecycle-$label".toByteArray()
        return jpeg.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, (comment.size + 2).toByte()) +
            comment + jpeg.copyOfRange(2, jpeg.size)
    }
    private fun assets(label: String): PhotoAssetSet {
        val content = bytes(label)
        return PhotoAssetSet.of(mapOf("photo.jpg" to object : PhotoAsset {
            override val descriptor = PhotoDescriptor(content.size.toLong(), sha256Hex(content), "image/jpeg", 64, 48)
            override fun open() = content.inputStream()
        }))
    }
    private fun withRoot(test: (File) -> Unit) {
        val root = Files.createTempDirectory("stage9b-pool-lifecycle").toFile()
        try { test(root) } finally { check(root.deleteRecursively()) }
    }
}
