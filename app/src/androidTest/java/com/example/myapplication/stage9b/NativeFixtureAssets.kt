package com.example.myapplication.stage9b

import android.content.Context
import com.example.myapplication.stage5.PhotoDescriptor
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.validatePhotoBytes
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * Test-only SAF/photo fixtures.  The handles are file-backed and reopenable;
 * no aggregate byte map is retained in the test process.
 */
object NativeFixtureAssets {
    const val STAGE9B_DOCUMENTS_AUTHORITY =
        "com.example.myapplication.test.stage9b.documents"
    const val STAGE9B_INITIAL_PDF_EXTRA =
        "com.sotaware.construct.stage8.INITIAL_PDF_URI"

    /** Builds a provider URI without touching the target app's private files. */
    fun fixtureUri(authority: String, path: String) =
        android.net.Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath(path)
            .build()

    /**
     * Creates small descriptor-backed handles from test APK assets.  The
     * source map is only names-to-asset-paths; each [PhotoAsset.open] call
     * opens a fresh stream from the instrumentation APK.
     */
    fun photoAssets(
        context: Context,
        references: Map<String, String>
    ): PhotoAssetSet {
        if (references.isEmpty()) return PhotoAssetSet.EMPTY
        val descriptors = LinkedHashMap<String, PhotoDescriptor>(references.size)
        references.toSortedMap().forEach { (name, assetPath) ->
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            descriptors[name] = validatePhotoBytes(bytes).descriptor
        }
        return photoAssetsFromDescriptors(descriptors) { name ->
            context.assets.open(references.getValue(name))
        }
    }

    /**
     * Four independent JPEG files at the 25 MiB per-file boundary (roughly
     * 100 MiB total).
     * JPEG APP15 segments carry the padding before EOI, keeping the encoded
     * container complete while exercising file-backed metadata/outbox paths.
     */
    fun fileBackedLargePhotoAssets(
        context: Context,
        references: Map<String, String>,
        targetBytes: Int = Stage5Limits.MAX_PHOTO_BYTES
    ): OwnedPhotoAssets {
        require(references.isNotEmpty()) { "at least one photo reference is required" }
        require(targetBytes in 1..Stage5Limits.MAX_PHOTO_BYTES) {
            "fixture photo size is outside the production limit"
        }
        val directory = File(
            context.filesDir,
            "stage9b-native-assets-${System.nanoTime()}"
        )
        check(directory.mkdirs()) { "could not create native fixture directory" }
        return try {
            val handles = LinkedHashMap<String, PhotoAsset>(references.size)
            references.toSortedMap().forEach { (name, assetPath) ->
                val base = context.assets.open(assetPath).use { it.readBytes() }
                val baseDescriptor = validatePhotoBytes(base).descriptor
                val file = File(directory, name)
                writePaddedJpeg(file, base, targetBytes)
                val descriptor = baseDescriptor.copy(
                    byteCount = file.length(),
                    sha256 = sha256File(file)
                )
                handles[name] = object : PhotoAsset {
                    override val descriptor: PhotoDescriptor = descriptor
                    override fun open(): InputStream = FileInputStream(file)
                }
            }
            OwnedPhotoAssets(PhotoAssetSet.of(handles), directory)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    class OwnedPhotoAssets internal constructor(
        val assets: PhotoAssetSet,
        private val directory: File
    ) : AutoCloseable {
        val root: File get() = directory

        override fun close() {
            directory.deleteRecursively()
        }
    }

    private fun writePaddedJpeg(file: File, base: ByteArray, targetBytes: Int) {
        require(base.size >= 4 && base[0] == 0xFF.toByte() && base[1] == 0xD8.toByte()) {
            "fixture source is not a JPEG"
        }
        require(base[base.size - 2] == 0xFF.toByte() && base.last() == 0xD9.toByte()) {
            "fixture JPEG has no terminal EOI"
        }
        val payloadTarget = targetBytes.toLong().coerceAtMost(Stage5Limits.MAX_PHOTO_BYTES.toLong())
        FileOutputStream(file).use { output ->
            output.write(base, 0, base.size - 2)
            var remaining = payloadTarget - (base.size - 2L) - 2L
            val zeroChunk = ByteArray(64 * 1024)
            while (remaining >= 4L) {
                val payload = minOf(65_533L, remaining - 4L).toInt()
                output.write(0xFF)
                output.write(0xEF) // APP15: legal JPEG application segment.
                val segmentLength = payload + 2
                output.write((segmentLength ushr 8) and 0xFF)
                output.write(segmentLength and 0xFF)
                var left = payload
                while (left > 0) {
                    val count = minOf(left, zeroChunk.size)
                    output.write(zeroChunk, 0, count)
                    left -= count
                }
                remaining -= payload + 4L
            }
            output.write(0xFF)
            output.write(0xD9) // EOI must be the final two bytes.
        }
        check(file.length() in (targetBytes - 3L)..targetBytes.toLong()) {
            "padded JPEG did not reach its bounded target"
        }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
