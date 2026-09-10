package com.example.myapplication.stage9b

import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage5.PhotoDescriptor
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.Stage5ValidationException
import com.example.myapplication.stage5.requiredPhotoNames
import com.example.myapplication.stage5.validatePhotoDescriptor
import com.example.myapplication.stage5.validatePhotoFileName
import com.example.myapplication.stage5.validateSnapshot
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One immutable, reopenable photo source.
 *
 * Implementations must return a new stream for every call.  Production
 * implementations are backed by an anchored app-private file; the interface
 * deliberately does not expose a path or a caller-owned descriptor.
 */
interface PhotoAsset {
    val descriptor: PhotoDescriptor

    /** The caller owns and must close the returned stream. */
    fun open(): InputStream
}

/**
 * Explicit lifetime for a transient capture.  [PhotoAssetSet] itself remains
 * immutable and reusable; this separate claim is what protects the pool files
 * while a caller is preparing a durable owner (normally the pending-upload
 * outbox).  Release is idempotent so every cancellation/failure path can use
 * one finally block without risking a second decrement.
 */
class PhotoAssetCapture internal constructor(
    val assets: PhotoAssetSet,
    private val releaseAction: () -> Unit
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    val isReleased: Boolean
        get() = released.get()

    fun release() {
        if (released.compareAndSet(false, true)) {
            releaseAction()
        }
    }

    override fun close() = release()

    companion object {
        /** Empty captures do not own any files but still have an explicit lifecycle. */
        fun empty(): PhotoAssetCapture = PhotoAssetCapture(PhotoAssetSet.EMPTY) {}

        /** Test/fake seam for callers that already own immutable source bytes. */
        internal fun of(assets: PhotoAssetSet, releaseAction: () -> Unit = {}): PhotoAssetCapture =
            PhotoAssetCapture(assets, releaseAction)
    }
}

/**
 * Process-wide ownership for immutable content hashes.  Pool instances and
 * outbox instances are deliberately independent objects, so a per-instance
 * weak map cannot protect a borrowed file from another instance.  Hash claims
 * are strong and explicit; they disappear only through [PhotoAssetLease.close]
 * (or with process death), never merely because a handle became unreachable.
 */
internal object PhotoAssetOwnershipRegistry {
    private val byHash = ConcurrentHashMap<String, AtomicInteger>()
    private val byOwnerAndHash = ConcurrentHashMap<String, AtomicInteger>()

    @Synchronized
    fun claim(ownerKey: String, assets: PhotoAssetSet): PhotoAssetLease {
        val hashes = assets.values.mapTo(LinkedHashSet()) { it.descriptor.sha256 }
        hashes.forEach { hash ->
            byHash.computeIfAbsent(hash) { AtomicInteger() }.incrementAndGet()
            byOwnerAndHash.computeIfAbsent(ownerKey + "\u0000" + hash) { AtomicInteger() }
                .incrementAndGet()
        }
        return PhotoAssetLease(ownerKey, hashes) { release(ownerKey, hashes) }
    }

    @Synchronized
    fun isHashClaimed(hash: String): Boolean = byHash[hash]?.get()?.let { it > 0 } == true

    @Synchronized
    fun isOwnerClaimed(ownerKey: String): Boolean =
        byOwnerAndHash.entries.any { it.key.startsWith(ownerKey + "\u0000") && it.value.get() > 0 }

    @Synchronized
    fun activeClaimCount(ownerKey: String): Int = byOwnerAndHash.entries
        .filter { it.key.startsWith(ownerKey + "\u0000") }
        .sumOf { it.value.get() }

    @Synchronized
    private fun release(ownerKey: String, hashes: Set<String>) {
        hashes.forEach { hash ->
            decrement(byHash, hash)
            decrement(byOwnerAndHash, ownerKey + "\u0000" + hash)
        }
    }

    private fun <K> decrement(map: ConcurrentHashMap<K, AtomicInteger>, key: K) {
        map[key]?.let { count ->
            while (true) {
                val old = count.get()
                if (old <= 0) break
                if (count.compareAndSet(old, old - 1)) {
                    if (old == 1) map.remove(key, count)
                    break
                }
            }
        }
    }
}

class PhotoAssetLease internal constructor(
    val ownerKey: String,
    val hashes: Set<String>,
    private val releaseAction: () -> Unit
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    val isReleased: Boolean
        get() = released.get()

    override fun close() {
        if (released.compareAndSet(false, true)) releaseAction()
    }
}

/**
 * Immutable occurrence-name to asset mapping.  The map contains handles and
 * descriptors only; it never materializes the bytes for the whole document.
 */
class PhotoAssetSet private constructor(
    private val backing: Map<String, PhotoAsset>
) : Map<String, PhotoAsset> by backing {
    /** A reusable empty set. */
    companion object {
        val EMPTY: PhotoAssetSet = PhotoAssetSet(emptyMap())

        /**
         * Copies only map structure and stable asset handles.  The supplied
         * source map and its iteration order can subsequently change without
         * changing this set.
         */
        fun of(source: Map<String, PhotoAsset>): PhotoAssetSet {
            if (source.isEmpty()) return EMPTY
            if (source.size > Stage5Limits.MAX_TOTAL_PHOTOS) {
                throw Stage5ValidationException("photo asset count exceeds its limit")
            }
            val copy = LinkedHashMap<String, PhotoAsset>(source.size)
            source.entries.sortedBy { it.key }.forEach { (name, asset) ->
                validatePhotoFileName(name)
                requireNotNull(asset) { "photo asset is missing: $name" }
                if (copy.put(name, asset) != null) {
                    throw Stage5ValidationException("duplicate photo asset reference: $name")
                }
            }
            var total = 0L
            copy.values.forEach { asset ->
                val count = asset.descriptor.byteCount
                if (count <= 0L || count > Stage5Limits.MAX_PHOTO_BYTES.toLong()) {
                    throw Stage5ValidationException("photo asset byte count exceeds its limit")
                }
                if (total > Stage5Limits.MAX_TOTAL_PHOTO_BYTES - count) {
                    throw Stage5ValidationException("total photo asset content exceeds its limit")
                }
                total += count
            }
            return PhotoAssetSet(Collections.unmodifiableMap(copy))
        }
    }

    /** Stable descriptor copy, keyed by occurrence reference. */
    val descriptors: Map<String, PhotoDescriptor>
        get() = Collections.unmodifiableMap(
            LinkedHashMap<String, PhotoDescriptor>(size).also { copy ->
                entries.forEach { (name, asset) -> copy[name] = asset.descriptor }
            }
        )

    /** Aggregate descriptor size; no source is opened. */
    val totalBytes: Long
        get() = values.fold(0L) { total, asset ->
            val count = asset.descriptor.byteCount
            if (total > Long.MAX_VALUE - count) Long.MAX_VALUE else total + count
        }
}

/** Byte identity used by journals without retaining file contents. */
data class PhotoContentIdentity(
    val byteCount: Long,
    val sha256: String
) {
    init {
        require(byteCount >= 0L) { "photo content byte count must be non-negative" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) {
            "photo content SHA-256 is invalid"
        }
    }
}

private const val PHOTO_ASSET_COPY_BUFFER_BYTES = 64 * 1024

/**
 * Streams one asset to [output], validating its declared byte count and
 * SHA-256 against the actual bytes and EOF.  The input is closed; [output]
 * remains owned by the caller so staged-file cleanup can still run on error.
 */
fun copyPhotoAsset(asset: PhotoAsset, output: OutputStream): Long {
    val descriptor = asset.descriptor
    val expected = descriptor.byteCount
    if (expected <= 0L || expected > Stage5Limits.MAX_PHOTO_BYTES.toLong()) {
        throw Stage5ValidationException("photo asset byte count exceeds its limit")
    }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(PHOTO_ASSET_COPY_BUFFER_BYTES)
    var count = 0L
    var zeroReads = 0
    asset.open().use { input ->
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (error: IOException) {
                throw error
            }
            if (read < 0) break
            if (read == 0) {
                zeroReads++
                if (zeroReads > Stage5Limits.MAX_ZERO_READS) {
                    throw Stage5ValidationException("photo asset input made no progress")
                }
                continue
            }
            zeroReads = 0
            if (count > expected - read.toLong()) {
                throw Stage5ValidationException("photo asset exceeds its declared byte count")
            }
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            count += read.toLong()
        }
    }
    if (count != expected) {
        throw Stage5ValidationException(
            "photo asset byte count changed: expected $expected, got $count"
        )
    }
    val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    if (actualHash != descriptor.sha256) {
        throw Stage5ValidationException("photo asset SHA-256 does not match its descriptor")
    }
    return count
}

/** Streams one asset into a bounded per-file decoder buffer. */
internal fun photoAssetBytesForValidation(asset: PhotoAsset): ByteArray =
    asset.open().use {
        readPhotoBytesForValidation(it, asset.descriptor.byteCount, "photo asset")
    }

/**
 * Reads exactly one capped file array for image validation.  The destination
 * array is allocated once from the declared size; no ByteArrayOutputStream
 * growth/copy chain is used.  The caller receives actual count and EOF
 * validation before decoding/hash validation is applied.  The caller owns
 * [input] and is responsible for closing it.
 */
internal fun readPhotoBytesForValidation(
    input: InputStream,
    expectedByteCount: Long,
    label: String
): ByteArray {
    if (expectedByteCount <= 0L || expectedByteCount > Stage5Limits.MAX_PHOTO_BYTES.toLong()) {
        throw Stage5ValidationException("$label byte count exceeds its limit")
    }
    val expected = expectedByteCount.toInt()
    val bytes = ByteArray(expected)
    var offset = 0
    var zeroReads = 0
    while (offset < expected) {
        val read = input.read(bytes, offset, minOf(PHOTO_ASSET_COPY_BUFFER_BYTES, expected - offset))
        if (read < 0) {
            throw Stage5ValidationException("$label ended before its declared byte count")
        }
        if (read == 0) {
            zeroReads++
            if (zeroReads > Stage5Limits.MAX_ZERO_READS) {
                throw Stage5ValidationException("$label input made no progress")
            }
            continue
        }
        zeroReads = 0
        if (read > expected - offset) {
            throw Stage5ValidationException("$label exceeded its declared byte count")
        }
        offset += read
    }
    if (input.read() >= 0) {
        throw Stage5ValidationException("$label exceeds its declared byte count")
    }
    return bytes
}

/**
 * Validates exact snapshot references, descriptor identity, aggregate bounds,
 * hashes, encoded containers and image dimensions.  At most one file is
 * materialized at a time for the existing bounded image decoder.
 */
fun validatePhotoAssets(
    snapshot: DocumentSnapshotV1,
    assets: PhotoAssetSet,
    expectedDescriptors: Map<String, PhotoDescriptor>? = null
): PhotoAssetSet {
    validateSnapshot(snapshot)
    val names = requiredPhotoNames(snapshot)
    if (assets.keys != names) {
        throw Stage5ValidationException("photo asset keys do not exactly match snapshot references")
    }
    if (expectedDescriptors != null && expectedDescriptors.keys != names) {
        throw Stage5ValidationException("photo descriptor keys do not exactly match snapshot references")
    }
    var total = 0L
    names.sorted().forEach { name ->
        val asset = assets[name]
            ?: throw Stage5ValidationException("required photo asset missing: $name")
        val descriptor = asset.descriptor
        expectedDescriptors?.get(name)?.let { expected ->
            if (descriptor != expected) {
                throw Stage5ValidationException("photo descriptor does not match: $name")
            }
        }
        if (total > Stage5Limits.MAX_TOTAL_PHOTO_BYTES - descriptor.byteCount) {
            throw Stage5ValidationException("total photo asset content exceeds its limit")
        }
        total += descriptor.byteCount
        val bytes = photoAssetBytesForValidation(asset)
        validatePhotoDescriptor(bytes, expected = descriptor)
    }
    if (total != assets.totalBytes) {
        throw Stage5ValidationException("photo asset aggregate descriptor size changed")
    }
    return assets
}

/**
 * Creates descriptor-backed handles from an opener.  This adapter does not
 * bless arbitrary paths: production openers must already be anchored to the
 * app-private resolver and prove stable file identity/containment.
 */
fun photoAssetsFromDescriptors(
    descriptors: Map<String, PhotoDescriptor>,
    open: (String) -> InputStream
): PhotoAssetSet {
    if (descriptors.isEmpty()) return PhotoAssetSet.EMPTY
    val assets = LinkedHashMap<String, PhotoAsset>(descriptors.size)
    descriptors.entries.sortedBy { it.key }.forEach { (name, photoDescriptor) ->
        validatePhotoFileName(name)
        val sourceName = name
        assets[sourceName] = object : PhotoAsset {
            override val descriptor: PhotoDescriptor = photoDescriptor

            override fun open(): InputStream = try {
                open(sourceName)
            } catch (error: NullPointerException) {
                throw IOException("photo asset opener returned no stream: $sourceName", error)
            }
        }
    }
    return PhotoAssetSet.of(assets)
}

/** Streams a bounded byte identity for journal/recovery fences. The caller owns [input]. */
internal fun photoContentIdentity(
    input: InputStream,
    maxBytes: Long = Stage5Limits.MAX_PHOTO_BYTES.toLong(),
    label: String = "photo content"
): PhotoContentIdentity {
    require(maxBytes >= 0L) { "photo content bound must be non-negative" }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(PHOTO_ASSET_COPY_BUFFER_BYTES)
    var count = 0L
    var zeroReads = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) {
            zeroReads++
            if (zeroReads > Stage5Limits.MAX_ZERO_READS) {
                throw Stage5ValidationException("$label input made no progress")
            }
            continue
        }
        zeroReads = 0
        if (count > maxBytes - read.toLong()) {
            throw Stage5ValidationException("$label exceeds $maxBytes bytes")
        }
        digest.update(buffer, 0, read)
        count += read.toLong()
    }
    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return PhotoContentIdentity(count, hash)
}

/**
 * Content-set digest based on descriptor byte identities rather than retaining
 * complete byte arrays.  Missing and present files remain distinct states.
 */
internal fun photoTransactionDescriptorDigest(
    entries: List<com.example.myapplication.stage5.PhotoTransactionJournalEntry>,
    readTarget: (String) -> PhotoContentIdentity?
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun field(bytes: ByteArray) {
        val size = java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()
        digest.update(size)
        digest.update(bytes)
    }
    entries.sortedBy { it.targetName }.forEach { entry ->
        field(entry.targetName.toByteArray(Charsets.UTF_8))
        val identity = readTarget(entry.targetName)
        if (identity == null) {
            field(byteArrayOf(0))
        } else {
            field(byteArrayOf(1))
            field(java.nio.ByteBuffer.allocate(8).putLong(identity.byteCount).array())
            field(identity.sha256.toByteArray(Charsets.US_ASCII))
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
