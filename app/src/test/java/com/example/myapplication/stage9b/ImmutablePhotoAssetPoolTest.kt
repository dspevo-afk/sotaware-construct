package com.example.myapplication.stage9b

import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage5.DefaultImageProbe
import com.example.myapplication.stage5.PhotoDescriptor
import com.example.myapplication.stage5.PhotoPathOperations
import com.example.myapplication.stage5.PhotoPathOperationsFactory
import com.example.myapplication.stage5.sha256Hex
import java.io.File
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ImmutablePhotoAssetPoolTest {
    @Test
    fun freezeDeduplicatesAndReopensAfterPoolClose() {
        val root = Files.createTempDirectory("stage9b-photo-pool").toFile()
        val bytes = Stage4PhotoFixture.jpegBytes()
        var sourceBytes = bytes.copyOf()
        val source = object : PhotoAsset {
            override val descriptor: PhotoDescriptor = PhotoDescriptor(
                bytes.size.toLong(),
                sha256Hex(bytes),
                "image/jpeg",
                64,
                48
            )

            override fun open(): InputStream = sourceBytes.copyOf().inputStream()
        }
        val input = PhotoAssetSet.of(
            mapOf("first.jpg" to source, "second.jpg" to source)
        )
        val pool = pool(root, maxAssetCount = 1, maxBytes = bytes.size.toLong() + 1L)
        var frozen: PhotoAssetSet? = null
        try {
            val captured = pool.freeze(input)
            frozen = captured
            assertEquals(1, pool.assetCount)
            assertEquals(bytes.size.toLong(), pool.totalBytes)
            sourceBytes = ByteArray(bytes.size)
            assertArrayEquals(bytes, captured.getValue("first.jpg").open().use { it.readBytes() })
            pool.close()
            assertArrayEquals(bytes, captured.getValue("second.jpg").open().use { it.readBytes() })

            val reopened = pool(root, maxAssetCount = 1, maxBytes = bytes.size.toLong() + 1L)
            var reopenedCapture: PhotoAssetSet? = null
            try {
                assertEquals(1, reopened.assetCount)
                val recaptured = reopened.freeze(PhotoAssetSet.of(mapOf("first.jpg" to byteAsset(bytes))))
                reopenedCapture = recaptured
                assertArrayEquals(bytes, recaptured.getValue("first.jpg").open().use { it.readBytes() })
            } finally {
                reopenedCapture?.let(reopened::release)
                reopened.close()
            }
        } finally {
            frozen?.let(pool::release)
            pool.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun releasedUnreachableAssetIsCollectedButUnknownFilesAreNotNeeded() {
        val root = Files.createTempDirectory("stage9b-photo-gc").toFile()
        // Claims are process-wide by hash: give this independent GC oracle distinct valid JPEG bytes.
        val bytes = Stage4PhotoFixture.jpegBytes() + "stage9b-pool-gc".toByteArray(Charsets.UTF_8)
        val pool = pool(root, maxAssetCount = 2, maxBytes = bytes.size.toLong() * 2L)
        try {
            val frozen = pool.freeze(PhotoAssetSet.of(mapOf("photo.jpg" to byteAsset(bytes))))
            val unknown = File(root, "unknown-evidence.bin").apply { writeBytes(byteArrayOf(11, 22, 33)) }
            assertEquals("live ownership must prevent collection", 0, pool.cleanupUnreachable())
            pool.release(frozen)
            assertEquals(1, pool.cleanupUnreachable())
            assertEquals(0, pool.assetCount)
            assertArrayEquals(byteArrayOf(11, 22, 33), unknown.readBytes())
        } finally {
            pool.close()
            root.deleteRecursively()
        }
    }

    private fun pool(root: File, maxAssetCount: Int, maxBytes: Long) =
        ImmutablePhotoAssetPool(
            root,
            DefaultImageProbe,
            maxAssetCount,
            maxBytes,
            LocalPhotoPathOperationsFactory,
            root
        )

    private fun byteAsset(bytes: ByteArray): PhotoAsset {
        val copy = bytes.copyOf()
        return object : PhotoAsset {
            override val descriptor: PhotoDescriptor = PhotoDescriptor(
                copy.size.toLong(),
                sha256Hex(copy),
                "image/jpeg",
                64,
                48
            )

            override fun open(): InputStream = copy.copyOf().inputStream()
        }
    }
}

internal object LocalPhotoPathOperationsFactory : PhotoPathOperationsFactory {
    override fun open(root: Path): PhotoPathOperations = LocalPhotoPathOperations(root)
}

private class LocalPhotoPathOperations(private val root: Path) : PhotoPathOperations {
    private fun path(name: String): Path = root.resolve(name)

    override fun exists(name: String): Boolean = Files.exists(path(name), LinkOption.NOFOLLOW_LINKS)

    override fun isRegularFile(name: String): Boolean = Files.isRegularFile(path(name), LinkOption.NOFOLLOW_LINKS)

    override fun size(name: String): Long = Files.size(path(name))

    override fun openRead(name: String): InputStream = Files.newInputStream(path(name), StandardOpenOption.READ)

    override fun openNewOutput(name: String): FileChannel = FileChannel.open(
        path(name),
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE
    ) as FileChannel

    override fun move(source: String, target: String, replaceExisting: Boolean) {
        if (replaceExisting) {
            Files.move(
                path(source),
                path(target),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } else {
            Files.move(path(source), path(target), StandardCopyOption.ATOMIC_MOVE)
        }
    }

    override fun delete(name: String) {
        Files.deleteIfExists(path(name))
    }

    override fun close() = Unit
}
