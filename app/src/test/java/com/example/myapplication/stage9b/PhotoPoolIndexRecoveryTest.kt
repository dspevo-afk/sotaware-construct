package com.example.myapplication.stage9b

import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage5.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PhotoPoolIndexRecoveryTest {
    @Test fun validInterruptedRecaptureKeepsCommittedIndexAndCanRetry() = withRoot { root ->
        pool(root).use { it.capture(assets()).close() }
        val before = committed(root).readBytes()
        val staged = stageNext(root) { lines -> changeRetention(lines, "1") }
        pool(root).use { reopened ->
            assertFalse(staged.exists())
            assertArrayEquals("staging is not a second committed owner", before, committed(root).readBytes())
            reopened.capture(assets()).use { assertPhoto(it.assets) }
        }
    }

    @Test fun interruptedReleaseIsNotAppliedTwiceToLiveLease() = withRoot { root ->
        pool(root).use { owner ->
            val held = owner.capture(assets())
            try {
                stageNext(root) { lines -> changeRetention(lines, "0") }
                pool(root).use { observer ->
                    assertEquals(1, observer.assetCount)
                    assertPhoto(held.assets)
                    held.close()
                    assertEquals(1, observer.cleanupUnreachable())
                }
            } finally { held.close() }
        }
    }

    @Test fun interruptedCollectionDoesNotRemoveCommittedOrLiveAssets() = withRoot { root ->
        pool(root).use { owner ->
            owner.capture(assets()).use { held ->
                stageNext(root) { lines -> lines[2] = "0"; lines.subList(3, lines.size).clear() }
                pool(root).use { observer ->
                    assertEquals(1, observer.assetCount)
                    assertEquals(0, observer.cleanupUnreachable())
                    assertPhoto(held.assets)
                }
            }
        }
    }

    @Test fun interruptedFirstPublicationCanAdoptVerifiedOrphanAtExactBounds() = withRoot { root ->
        val descriptor = assets().getValue("photo.jpg").descriptor
        File(root, ".stage9b-photo-asset-${descriptor.sha256}.bin").writeBytes(bytes())
        File(root, TEMP).writeText("$MAGIC\n1\n1\n" + entry(descriptor, "1") + "\n")
        pool(root, count = 1, byteLimit = descriptor.byteCount).use { reopened ->
            assertFalse(File(root, TEMP).exists())
            assertEquals(0, reopened.assetCount)
            reopened.capture(assets()).use { assertPhoto(it.assets) }
            assertEquals(1, reopened.physicalFileCount)
            assertEquals(descriptor.byteCount, reopened.physicalBytes)
        }
    }

    @Test fun truncatedStagingIsPreserved() = rejected(BadStage.TRUNCATED)
    @Test fun staleStagingIsPreserved() = rejected(BadStage.STALE)
    @Test fun nonconsecutiveStagingIsPreserved() = rejected(BadStage.GAP)
    @Test fun missingStagedAssetIsPreserved() = rejected(BadStage.MISSING)
    @Test fun conflictingStagedDescriptorIsPreserved() = rejected(BadStage.DESCRIPTOR)
    @Test fun stagedByteBudgetCannotBeBypassed() = rejected(BadStage.BYTES)

    @Test fun rejectedRecoveryClosesEveryOpenedDirectoryAnchor() = withRoot { root ->
        File(root, TEMP).writeText("incomplete")
        var opened = 0
        var closed = 0
        val factory = object : PhotoPathOperationsFactory {
            override fun open(root: java.nio.file.Path): PhotoPathOperations {
                opened++
                val delegate = LocalPhotoPathOperationsFactory.open(root)
                return object : PhotoPathOperations by delegate {
                    override fun close() { closed++; delegate.close() }
                }
            }
        }
        repeat(3) {
            try {
                ImmutablePhotoAssetPool(root, DefaultImageProbe, 4, 1024L * 1024L, factory, root).use { }
                fail("corrupt staging must be retained")
            } catch (_: Stage5ValidationException) { }
        }
        assertTrue(opened > 0)
        assertEquals(opened, closed)
        assertEquals("incomplete", File(root, TEMP).readText())
    }

    private enum class BadStage { TRUNCATED, STALE, GAP, MISSING, DESCRIPTOR, BYTES }
    private fun rejected(kind: BadStage) = withRoot { root ->
        pool(root).use { it.capture(assets()).close() }
        val slots = root.listFiles()!!.filter { it.name in SLOTS }.associate { it.name to it.readBytes() }
        val staged = stageNext(root) { lines ->
            when (kind) {
                BadStage.STALE -> lines[1] = (lines[1].toLong() - 1L).toString()
                BadStage.GAP -> lines[1] = (lines[1].toLong() + 1L).toString()
                BadStage.MISSING -> lines[3] = lines[3].replaceBefore('\t', "0".repeat(64))
                BadStage.DESCRIPTOR -> {
                    val fields = lines[3].split('\t').toMutableList()
                    fields[3] = (fields[3].toInt() + 1).toString()
                    lines[3] = fields.joinToString("\t")
                }
                BadStage.BYTES -> {
                    val extra = bytes() + byteArrayOf(0)
                    val descriptor = assets().getValue("photo.jpg").descriptor.copy(byteCount = extra.size.toLong(), sha256 = sha256Hex(extra))
                    File(root, ".stage9b-photo-asset-${descriptor.sha256}.bin").writeBytes(extra)
                    lines[2] = "2"; lines.add(entry(descriptor, "1"))
                }
                else -> Unit
            }
        }
        if (kind == BadStage.TRUNCATED) staged.writeText("incomplete")
        val preserved = staged.readBytes()
        try {
            pool(root, byteLimit = if (kind == BadStage.BYTES) bytes().size.toLong() else 1024L * 1024L).use { }
            fail("unproven staging must fail closed: $kind")
        } catch (_: Stage5ValidationException) { }
        assertArrayEquals(preserved, staged.readBytes())
        slots.forEach { (name, content) -> assertArrayEquals(content, File(root, name).readBytes()) }
    }

    private fun stageNext(root: File, change: (MutableList<String>) -> Unit): File {
        val lines = committed(root).readLines().toMutableList()
        lines[1] = (lines[1].toLong() + 1L).toString()
        change(lines)
        return File(root, TEMP).apply { writeText(lines.joinToString("\n", postfix = "\n")) }
    }
    private fun changeRetention(lines: MutableList<String>, value: String) {
        val fields = lines[3].split('\t').toMutableList()
        fields[5] = value; lines[3] = fields.joinToString("\t")
    }
    private fun entry(d: PhotoDescriptor, retention: String) = listOf(d.sha256, d.byteCount, d.mimeType, d.width, d.height, retention).joinToString("\t")
    private fun committed(root: File) = root.listFiles()!!.filter { it.name in SLOTS }.maxBy { it.readLines()[1].toLong() }
    private fun pool(root: File, count: Int = 4, byteLimit: Long = 1024L * 1024L) =
        ImmutablePhotoAssetPool(root, DefaultImageProbe, count, byteLimit, LocalPhotoPathOperationsFactory, root)
    private fun bytes() = Stage4PhotoFixture.jpegBytes()
    private fun assets() = PhotoAssetSet.of(mapOf("photo.jpg" to object : PhotoAsset {
        override val descriptor = validatePhotoBytes(bytes()).descriptor
        override fun open() = bytes().inputStream()
    }))
    private fun assertPhoto(assets: PhotoAssetSet) = assertArrayEquals(bytes(), assets.getValue("photo.jpg").open().use { it.readBytes() })
    private fun withRoot(test: (File) -> Unit) {
        val root = Files.createTempDirectory("pool-index-recovery").toFile()
        try { test(root) } finally { check(root.deleteRecursively()) }
    }
    companion object {
        private const val MAGIC = "SOTAWARE_STAGE9B_IMMUTABLE_PHOTO_POOL_V1"
        private const val TEMP = ".stage9b-pool-index.tmp"
        private val SLOTS = setOf(".stage9b-photo-pool.a", ".stage9b-photo-pool.b")
    }
}
