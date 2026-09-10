package com.example.myapplication.stage9b

import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage5.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PhotoPoolAbandonedMetadataTest {
    @Test fun abandonedManifestTemporaryBytesCountAgainstAdmission() = fixture(100, 1024)
    @Test fun abandonedManifestTemporaryFilesCountAgainstAdmission() = fixture(1, 1024 * 1024)

    private fun fixture(maxCount: Int, maxBytes: Long) {
        val root = Files.createTempDirectory("pool-abandoned-index").toFile()
        try {
            val bytes = Stage4PhotoFixture.jpegBytes()
            val descriptor = validatePhotoBytes(bytes).descriptor
            val actualBound = if (maxCount == 1) maxBytes else bytes.size.toLong() + 1024L
            val assets = PhotoAssetSet.of(mapOf("photo.jpg" to object : PhotoAsset {
                override val descriptor = descriptor
                override fun open() = bytes.inputStream()
            }))
            ImmutablePhotoAssetPool(root, DefaultImageProbe, maxCount, actualBound, LocalPhotoPathOperationsFactory, root).use { pool ->
                val evidence = File(root, ".stage9b-pool-index-abandoned.tmp").apply {
                    writeBytes(ByteArray(if (maxCount == 1) 4 else actualBound.toInt()) { 7 })
                }
                val before = evidence.readBytes()
                try { pool.capture(assets).close(); fail("abandoned metadata must not bypass physical admission") }
                catch (_: Stage5ValidationException) { }
                assertArrayEquals(before, evidence.readBytes())
                assertEquals(0, pool.assetCount)
            }
        } finally { check(root.deleteRecursively()) }
    }
}
