package com.example.myapplication.stage9b

import com.example.myapplication.stage5.PhotoDescriptor
import com.example.myapplication.stage5.sha256Hex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoAssetsTest {
    @Test
    fun assetSetCopiesHandlesAndDescriptorsButNeverBytes() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val source = byteAsset(bytes)
        val mutable = linkedMapOf("photo.jpg" to source)
        val set = PhotoAssetSet.of(mutable)
        mutable.clear()

        assertEquals(setOf("photo.jpg"), set.keys)
        assertEquals(source.descriptor, set.descriptors.getValue("photo.jpg"))
        assertEquals(bytes.size.toLong(), set.totalBytes)
        assertArrayEquals(bytes, set.getValue("photo.jpg").open().use { it.readBytes() })
    }

    @Test
    fun descriptorOpenerReopensForEveryStream() {
        val bytes = byteArrayOf(9, 8, 7)
        var opens = 0
        val descriptor = descriptor(bytes)
        val set = photoAssetsFromDescriptors(mapOf("photo.jpg" to descriptor)) {
            opens++
            ByteArrayInputStream(bytes)
        }

        assertArrayEquals(bytes, set.getValue("photo.jpg").open().use { it.readBytes() })
        assertArrayEquals(bytes, set.getValue("photo.jpg").open().use { it.readBytes() })
        assertEquals(2, opens)
    }

    @Test
    fun copyStreamsExactBytesAndLeavesOutputOpen() {
        val bytes = ByteArray(128 * 1024) { (it and 0x7f).toByte() }
        val output = TrackingOutputStream()
        val copied = copyPhotoAsset(byteAsset(bytes), output)

        assertEquals(bytes.size.toLong(), copied)
        assertArrayEquals(bytes, output.toByteArray())
        assertFalse(output.closed)
    }

    @Test
    fun copyRejectsEarlyEofAndHashMismatch() {
        val expected = descriptor(byteArrayOf(1, 2, 3, 4))
        val truncated = object : PhotoAsset {
            override val descriptor = expected
            override fun open(): InputStream = ByteArrayInputStream(byteArrayOf(1, 2))
        }
        assertRejected { copyPhotoAsset(truncated, ByteArrayOutputStream()) }

        val wrongHash = object : PhotoAsset {
            override val descriptor = expected
            override fun open(): InputStream = ByteArrayInputStream(byteArrayOf(4, 3, 2, 1))
        }
        assertRejected { copyPhotoAsset(wrongHash, ByteArrayOutputStream()) }
    }

    @Test
    fun emptySetIsSharedAndHasNoAggregateBytes() {
        assertTrue(PhotoAssetSet.EMPTY.isEmpty())
        assertEquals(0L, PhotoAssetSet.EMPTY.totalBytes)
        assertTrue(PhotoAssetSet.of(emptyMap()) === PhotoAssetSet.EMPTY)
    }

    private fun byteAsset(bytes: ByteArray): PhotoAsset {
        val copy = bytes.copyOf()
        return object : PhotoAsset {
            override val descriptor = descriptor(copy)
            override fun open(): InputStream = ByteArrayInputStream(copy.copyOf())
        }
    }

    private fun descriptor(bytes: ByteArray) = PhotoDescriptor(
        byteCount = bytes.size.toLong(),
        sha256 = sha256Hex(bytes),
        mimeType = "image/jpeg",
        width = 1,
        height = 1
    )

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected rejection")
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
