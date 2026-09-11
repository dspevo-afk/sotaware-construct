package com.example.myapplication.stage9b

import com.example.myapplication.stage5.DefaultImageProbe
import com.example.myapplication.stage5.PhotoDescriptor
import com.example.myapplication.stage5.PhotoDocumentCriticalSections
import com.example.myapplication.stage5.PhotoPathOperationsFactory
import com.example.myapplication.stage5.PhotoPathResolver
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.Stage5ValidationException
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.IdentityHashMap

private const val POOL_MANIFEST_MAGIC = "SOTAWARE_STAGE9B_IMMUTABLE_PHOTO_POOL_V1"
private const val POOL_LEGACY_MANIFEST_NAME = ".stage9b-photo-pool.index"
private const val POOL_MANIFEST_SLOT_A = ".stage9b-photo-pool.a"
private const val POOL_MANIFEST_SLOT_B = ".stage9b-photo-pool.b"
private const val POOL_MANIFEST_STAGING = ".stage9b-pool-index.tmp"
private const val POOL_ASSET_PREFIX = ".stage9b-photo-asset-"
private const val POOL_ASSET_SUFFIX = ".bin"
private const val POOL_MANIFEST_MAX_BYTES = 2 * 1024 * 1024

/** The pool is transient storage; one current 100MiB set plus one held set fits. */
const val IMMUTABLE_PHOTO_POOL_MAX_TOTAL_BYTES: Long = Stage5Limits.MAX_TOTAL_PHOTO_BYTES * 2L
const val IMMUTABLE_PHOTO_POOL_MAX_ASSET_COUNT: Int = Stage5Limits.MAX_TOTAL_PHOTOS * 2

private data class PoolRecord(
    val descriptor: PhotoDescriptor,
    var retentionCount: Long
)

/**
 * App-private, content-addressed immutable photo storage.
 *
 * The resolver is the only filesystem primitive used here.  Content files
 * are published with CREATE_NEW plus an atomic same-directory move, and the
 * small ownership index is itself atomically replaced.  Unknown files are
 * deliberately never swept: an interrupted publication is safer as retained
 * evidence than as a guessed deletion.
 */
class ImmutablePhotoAssetPool private constructor(
    rootDirectory: File,
    private val imageProbe: com.example.myapplication.stage5.PhotoDecodeProbe = DefaultImageProbe,
    private val maxAssetCount: Int = IMMUTABLE_PHOTO_POOL_MAX_ASSET_COUNT,
    private val maxTotalBytes: Long = IMMUTABLE_PHOTO_POOL_MAX_TOTAL_BYTES,
    private val operationsFactory: PhotoPathOperationsFactory? = null,
    private val trustedRootDirectory: File? = null,
    @Suppress("UNUSED_PARAMETER") private val factoryConstructor: Boolean = false
) : AutoCloseable {
    constructor(
        rootDirectory: File,
        imageProbe: com.example.myapplication.stage5.PhotoDecodeProbe = DefaultImageProbe,
        maxAssetCount: Int = IMMUTABLE_PHOTO_POOL_MAX_ASSET_COUNT,
        maxTotalBytes: Long = IMMUTABLE_PHOTO_POOL_MAX_TOTAL_BYTES
    ) : this(
        rootDirectory,
        imageProbe,
        maxAssetCount,
        maxTotalBytes,
        operationsFactory = null,
        trustedRootDirectory = null,
        factoryConstructor = false
    )
    private val resolver: PhotoPathResolver
    private val rootPath: Path
    private val manifestSlots: Array<Path>
    private val lock = Any()
    private var closed = false
    private var activeManifestSlot: Int = -1
    private var manifestGeneration: Long = 0L
    private val records: LinkedHashMap<String, PoolRecord>
    /** Strong, explicit claims keyed by the returned set object. */
    private val claims = IdentityHashMap<PhotoAssetSet, MutableList<PhotoAssetLease>>()

    /** JVM/test constructor retaining the existing anchored operations seam. */
    internal constructor(
        rootDirectory: File,
        imageProbe: com.example.myapplication.stage5.PhotoDecodeProbe,
        maxAssetCount: Int,
        maxTotalBytes: Long,
        operationsFactory: PhotoPathOperationsFactory,
        trustedRootDirectory: File?
    ) : this(
        rootDirectory,
        imageProbe,
        maxAssetCount,
        maxTotalBytes,
        operationsFactory,
        trustedRootDirectory,
        factoryConstructor = true
    )

    /** Number of known, content-addressed files. */
    val assetCount: Int
        get() = synchronized(lock) {
            ensureOpen()
            PhotoDocumentCriticalSections.withLock(rootPath) {
                refreshManifestLocked()
                records.size
            }
        }

    /** Sum of known content bytes, independent of retention counts. */
    val totalBytes: Long
        get() = synchronized(lock) {
            ensureOpen()
            PhotoDocumentCriticalSections.withLock(rootPath) {
                refreshManifestLocked()
                records.values.sumOf { it.descriptor.byteCount }
            }
        }

    /** Physical content/evidence bytes, including unindexed files retained conservatively. */
    val physicalBytes: Long
        get() = synchronized(lock) { physicalBytesLocked() }

    /** Physical file count used by admission; unknown entries are not hidden from the bound. */
    val physicalFileCount: Int
        get() = synchronized(lock) { physicalFileCountLocked() }

    init {
        require(maxAssetCount in 1..IMMUTABLE_PHOTO_POOL_MAX_ASSET_COUNT) {
            "immutable photo pool count bound is invalid"
        }
        require(maxTotalBytes in 1L..IMMUTABLE_PHOTO_POOL_MAX_TOTAL_BYTES) {
            "immutable photo pool byte bound is invalid"
        }
        resolver = if (operationsFactory == null) {
            PhotoPathResolver(
                rootDirectory,
                createRoot = true,
                trustedRootDirectory = trustedRootDirectory
            )
        } else {
            PhotoPathResolver(
                rootDirectory,
                createRoot = true,
                operationsFactory = operationsFactory,
                trustedRootDirectory = trustedRootDirectory
            )
        }
        rootPath = resolver.root.toPath()
        manifestSlots = arrayOf(
            rootPath.resolve(POOL_MANIFEST_SLOT_A),
            rootPath.resolve(POOL_MANIFEST_SLOT_B)
        )
        manifestSlots.forEach { slot -> resolver.ensureContained(slot, "immutable photo pool manifest") }
        val legacyManifest = rootPath.resolve(POOL_LEGACY_MANIFEST_NAME)
        resolver.ensureContained(legacyManifest, "immutable photo pool legacy manifest")
        if (resolver.exists(legacyManifest)) {
            throw Stage5ValidationException("unsupported immutable photo pool manifest format")
        }
        records = try {
            PhotoDocumentCriticalSections.withLock(rootPath) { loadManifest() }
        } catch (error: Throwable) {
            // A rejected recovery must not leak the newly opened directory anchor.
            try { resolver.close() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
    }

    /**
     * Freeze all handles into this pool before returning.  The returned set
     * uses reopenable managed handles and increments a persistent retention
     * count once per distinct content hash.
     */
    fun freeze(assets: PhotoAssetSet): PhotoAssetSet = synchronized(lock) {
        ensureOpen()
        PhotoDocumentCriticalSections.withLock(rootPath) {
            freezeLocked(assets).also { frozen -> registerClaim(frozen) }
        }
    }

    /**
     * Captures a transient source and returns the explicit claim that owns it.
     * The claim must be released after an independent durable owner (the
     * outbox) has copied and read-back verified the bytes.
     */
    fun capture(assets: PhotoAssetSet): PhotoAssetCapture = synchronized(lock) {
        ensureOpen()
        PhotoDocumentCriticalSections.withLock(rootPath) {
            val frozen = freezeLocked(assets)
            val lease = registerClaim(frozen)
            PhotoAssetCapture.of(frozen) { releaseClaim(frozen, lease) }
        }
    }

    /** Retain an already-frozen set for another asynchronous owner. */
    fun retain(assets: PhotoAssetSet): PhotoAssetLease = synchronized(lock) {
        ensureOpen()
        PhotoDocumentCriticalSections.withLock(rootPath) {
            refreshManifestLocked()
            val hashes = distinctHashes(assets)
            val next = copyRecords()
            hashes.forEach { hash ->
                val record = next[hash]
                    ?: throw Stage5ValidationException("immutable photo asset is not owned: $hash")
                record.retentionCount = incrementRetention(record.retentionCount)
            }
            writeManifest(next)
            replaceRecords(next)
            val registryLease = registerClaim(assets)
            // The public lease owns both the process-wide borrowed-source
            // claim and the pool's persistent retention count.  Returning the
            // registry token directly would protect the bytes but leak the
            // manifest retention forever.
            PhotoAssetLease(poolOwnerKey(), hashes) {
                releaseClaim(assets, registryLease)
            }
        }
    }

    /** Release one retention claim per distinct content hash in [assets]. */
    fun release(assets: PhotoAssetSet) = synchronized(lock) {
        PhotoDocumentCriticalSections.withLock(rootPath) {
            val lease = claims[assets]?.firstOrNull { !it.isReleased }
                ?: throw Stage5ValidationException("immutable photo asset retention is already released")
            releaseClaim(assets, lease)
        }
    }

    /**
     * Removes only known assets that are absent from all supplied reachability
     * sets and have no live process-wide ownership lease.  The persisted
     * retention count is intentionally not treated as a cross-process lease:
     * after a process dies it is stale, and this explicit recovery/collection
     * boundary is what permits reclaiming that transient claim.  A live
     * capture or outbox handle remains protected by the registry even when a
     * second pool instance performs cleanup. Unknown files are untouched.
     * Interrupted deletion leaves unindexed bytes as conservative evidence.
     */
    fun cleanupUnreachable(reachable: Iterable<PhotoAssetSet> = emptyList()): Int = synchronized(lock) {
        ensureOpen()
        PhotoDocumentCriticalSections.withLock(rootPath) {
            refreshManifestLocked()
            cleanupUnreachableLocked(reachable)
        }
    }

    private fun cleanupUnreachableLocked(reachable: Iterable<PhotoAssetSet>): Int {
        val reachableHashes = LinkedHashSet<String>()
        reachable.forEach { set -> set.values.forEach { reachableHashes += it.descriptor.sha256 } }
        val candidates = records.filter { (hash, _) ->
            hash !in reachableHashes &&
                !PhotoAssetOwnershipRegistry.isHashClaimed(hash)
        }.keys.toList()
        if (candidates.isEmpty()) return 0
        val next = copyRecords().also { copy -> candidates.forEach { hash -> copy.remove(hash) } }
        // Publish the new ownership view first.  If a later file delete
        // is interrupted, the old bytes remain an unknown conservative
        // orphan rather than leaving a manifest pointing at a missing
        // required asset.
        writeManifest(next)
        replaceRecords(next)
        candidates.forEach { hash ->
            val path = assetPath(hash)
            if (resolver.exists(path)) {
                resolver.deletePath(path, "immutable photo asset cleanup")
            }
        }
        return candidates.size
    }

    /** Explicit name retained for callers that model collection as GC. */
    fun collectUnreachable(reachable: Iterable<PhotoAssetSet> = emptyList()): Int =
        cleanupUnreachable(reachable)

    /** Opens a pool-owned managed asset by content hash. */
    internal fun openAsset(descriptor: PhotoDescriptor): InputStream = synchronized(lock) {
        ensureOpen()
        PhotoDocumentCriticalSections.withLock(rootPath) {
            refreshManifestLocked()
            val record = records[descriptor.sha256]
                ?: throw Stage5ValidationException("immutable photo asset is not owned: ${descriptor.sha256}")
            if (record.descriptor != descriptor) {
                throw Stage5ValidationException("immutable photo descriptor does not match its hash")
            }
            openManagedStream(descriptor)
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            // A capture/retain lease may outlive the store object.  Keep the
            // anchored resolver open until its final claim is released so the
            // release can durably decrement the manifest.  Managed handles
            // already returned to callers use their own short-lived resolver.
            if (claims.values.none { leases -> leases.any { !it.isReleased } }) {
                resolver.close()
            }
        }
    }

    private fun freezeLocked(assets: PhotoAssetSet): PhotoAssetSet {
        refreshManifestLocked()
        // Admission is the production collection boundary. Preserve input handles
        // as well as every live capture/outbox lease before applying disk limits.
        // Canonical, history and durable outbox bytes live in their own stores;
        // this pool owns only transient immutable copies, not those authorities.
        cleanupUnreachableLocked(listOf(assets))
        if (assets.isEmpty()) return PhotoAssetSet.EMPTY
        val unique = LinkedHashMap<String, PhotoDescriptor>()
        assets.values.forEach { asset ->
            val descriptor = asset.descriptor
            val prior = unique[descriptor.sha256]
            if (prior != null && prior != descriptor) {
                throw Stage5ValidationException("same photo hash has conflicting descriptors")
            }
            unique[descriptor.sha256] = descriptor
        }
        val newEntries = unique.filterKeys { it !in records }
        // An interrupted first publication may leave verified content without an
        // index entry. It already counts physically; admission must not count it
        // twice. The existing target is fully verified below before adoption.
        val newFiles = newEntries.filterKeys { !resolver.exists(assetPath(it)) }
        val physicalCount = physicalFileCountLocked()
        if (physicalCount > maxAssetCount - newFiles.size) {
            throw Stage5ValidationException("immutable photo pool file count exceeds its limit")
        }
        val existingBytes = physicalBytesLocked()
        val newBytes = newFiles.values.sumOf { it.byteCount }
        if (existingBytes > maxTotalBytes - newBytes) {
            throw Stage5ValidationException("immutable photo pool disk bound exceeded")
        }

        val created = mutableListOf<String>()
        var manifestPublished = false
        try {
            unique.forEach { (hash, descriptor) ->
                val target = assetPath(hash)
                val record = records[hash]
                if (record != null) {
                    if (record.descriptor != descriptor) {
                        throw Stage5ValidationException("immutable photo descriptor changed: $hash")
                    }
                    verifyManagedFile(record)
                } else if (resolver.exists(target)) {
                    // A target left by a prior crash is adopted only after a
                    // complete descriptor/hash verification.
                    val adopted = PoolRecord(descriptor, retentionCount = 0L)
                    verifyManagedFile(adopted)
                } else {
                    stageOne(assetForHash(assets, hash), descriptor, target)
                    created += hash
                }
            }
            val next = copyRecords()
            unique.forEach { (hash, descriptor) ->
                val record = next[hash]
                if (record == null) {
                    next[hash] = PoolRecord(descriptor, retentionCount = 1L)
                } else {
                    record.retentionCount = incrementRetention(record.retentionCount)
                }
            }
            try {
                writeManifest(next)
                // writeManifest has atomically published a new owner view at
                // this point.  Align memory before read-back so a later
                // validation/fsync failure cannot strand a manifest reference.
                replaceRecords(next)
                manifestPublished = true
                val active = manifestSlots.getOrNull(activeManifestSlot)
                    ?: throw IOException("immutable photo pool manifest publication is unavailable")
                val readBack = readManifestSlot(active, verifyFiles = true).second
                if (readBack != next) {
                    throw IOException("immutable photo pool manifest read-back differs")
                }
            } catch (error: Throwable) {
                if (!manifestPublished) {
                    // No publication was observed.  Only this branch may
                    // delete newly staged files; after publication they are
                    // required by the manifest and become conservative
                    // retained evidence instead.
                    created.forEach { hash ->
                        try {
                            val path = assetPath(hash)
                            if (resolver.exists(path)) resolver.deletePath(path, "immutable photo pool rollback")
                        } catch (cleanup: Throwable) {
                            if (cleanup !== error && error.suppressed.none { it === cleanup }) error.addSuppressed(cleanup)
                        }
                    }
                }
                throw error
            }
            return PhotoAssetSet.of(
                assets.entries.associate { (name, asset) ->
                    name to managedAsset(next.getValue(asset.descriptor.sha256).descriptor)
                }
            )
        } catch (error: Throwable) {
            if (!manifestPublished) {
                created.forEach { hash ->
                    try {
                        val path = assetPath(hash)
                        if (resolver.exists(path)) resolver.deletePath(path, "immutable photo pool rollback")
                    } catch (cleanup: Throwable) {
                        if (cleanup !== error && error.suppressed.none { it === cleanup }) error.addSuppressed(cleanup)
                    }
                }
            }
            throw error
        }
    }

    private fun stageOne(asset: PhotoAsset, descriptor: PhotoDescriptor, target: Path) {
        val temporary = resolver.newInternalFile("stage9b-asset", ".tmp").toPath()
        var moved = false
        try {
            resolver.openNewOutput(temporary, "immutable photo asset staging").use { channel ->
                // copyPhotoAsset deliberately does not close the output;
                // closing a Channels wrapper would close the channel before
                // the durability fence below.
                val output = Channels.newOutputStream(channel)
                val copied = copyPhotoAsset(asset, output)
                if (copied != descriptor.byteCount) {
                    throw Stage5ValidationException("immutable photo asset byte count changed")
                }
                output.flush()
                channel.force(true)
            }
            try {
                resolver.atomicMove(temporary, target, replaceExisting = false)
                moved = true
            } catch (_: FileAlreadyExistsException) {
                // Another owner may have published the same content. Verify
                // it before accepting the target and clean our temp below.
                verifyManagedFile(PoolRecord(descriptor, retentionCount = 0L))
            }
        } finally {
            if (!moved && resolver.exists(temporary)) {
                resolver.deletePath(temporary, "immutable photo asset temp cleanup")
            }
        }
    }

    private fun verifyManagedFile(record: PoolRecord) {
        val path = assetPath(record.descriptor.sha256)
        if (!resolver.exists(path) || !resolver.isRegularFile(path)) {
            throw Stage5ValidationException("immutable photo asset is missing: ${record.descriptor.sha256}")
        }
        rejectHardLink(path, "immutable photo asset")
        if (resolver.size(path, "immutable photo asset") != record.descriptor.byteCount) {
            throw Stage5ValidationException("immutable photo asset size changed: ${record.descriptor.sha256}")
        }
        openManagedStream(record.descriptor).use { input ->
            val actual = photoContentIdentity(input, record.descriptor.byteCount, "immutable photo asset")
            if (actual.byteCount != record.descriptor.byteCount || actual.sha256 != record.descriptor.sha256) {
                throw Stage5ValidationException("immutable photo asset hash changed: ${record.descriptor.sha256}")
            }
        }
    }

    private fun managedAsset(photoDescriptor: PhotoDescriptor): PhotoAsset = object : PhotoAsset {
        override val descriptor: PhotoDescriptor = photoDescriptor
        override fun open(): InputStream = openManagedStream(photoDescriptor)
    }

    private fun openManagedStream(descriptor: PhotoDescriptor): InputStream {
        val openedResolver = newResolver(createRoot = false)
        return try {
            val path = openedResolver.root.toPath().resolve(assetFileName(descriptor.sha256))
            openedResolver.ensureContained(path, "immutable photo asset")
            if (!openedResolver.isRegularFile(path)) {
                throw Stage5ValidationException("immutable photo asset is unavailable: ${descriptor.sha256}")
            }
            rejectHardLink(path, "immutable photo asset")
            val stream = openedResolver.openRead(path, "immutable photo asset")
            object : FilterInputStream(stream) {
                private var closed = false

                override fun close() {
                    if (!closed) {
                        closed = true
                        var failure: Throwable? = null
                        try {
                            super.close()
                        } catch (error: Throwable) {
                            failure = error
                        }
                        try {
                            openedResolver.close()
                        } catch (error: Throwable) {
                            if (failure == null) failure = error else failure?.addSuppressed(error)
                        }
                        failure?.let { throw it }
                    }
                }
            }
        } catch (error: Throwable) {
            try {
                openedResolver.close()
            } catch (cleanup: Throwable) {
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    private fun newResolver(createRoot: Boolean): PhotoPathResolver =
        if (operationsFactory == null) {
            PhotoPathResolver(rootPath.toFile(), createRoot, trustedRootDirectory)
        } else {
            PhotoPathResolver(
                rootPath.toFile(),
                createRoot,
                operationsFactory,
                trustedRootDirectory
            )
        }

    private fun assetForHash(assets: PhotoAssetSet, hash: String): PhotoAsset =
        assets.values.firstOrNull { it.descriptor.sha256 == hash }
            ?: throw Stage5ValidationException("photo asset hash is not present in the set: $hash")

    private fun distinctHashes(assets: PhotoAssetSet): Set<String> =
        assets.values.mapTo(LinkedHashSet()) { it.descriptor.sha256 }

    private fun registerClaim(assets: PhotoAssetSet): PhotoAssetLease {
        val lease = PhotoAssetOwnershipRegistry.claim(poolOwnerKey(), assets)
        claims.getOrPut(assets) { mutableListOf() }.add(lease)
        return lease
    }

    private fun releaseClaim(assets: PhotoAssetSet, lease: PhotoAssetLease) = synchronized(lock) {
        PhotoDocumentCriticalSections.withLock(rootPath) {
            val hashes = distinctHashes(assets)
            if (hashes.isEmpty()) {
                lease.close()
                claims[assets]?.remove(lease)
                if (claims[assets].isNullOrEmpty()) claims.remove(assets)
                if (closed && claims.values.none { leases -> leases.any { !it.isReleased } }) {
                    resolver.close()
                }
                return@withLock
            }
            refreshManifestLocked()
            val next = copyRecords()
            hashes.forEach { hash ->
                val record = next[hash]
                    ?: throw Stage5ValidationException("immutable photo asset is not owned: $hash")
                if (record.retentionCount <= 0L) {
                    throw Stage5ValidationException("immutable photo asset retention is already released: $hash")
                }
                record.retentionCount--
            }
            writeManifest(next)
            replaceRecords(next)
            lease.close()
            claims[assets]?.remove(lease)
            if (claims[assets].isNullOrEmpty()) claims.remove(assets)
            if (closed && claims.values.none { leases -> leases.any { !it.isReleased } }) {
                resolver.close()
            }
        }
    }

    private fun poolOwnerKey(): String =
        "immutable-photo-pool:${rootPath.toAbsolutePath().normalize()}"

    /**
     * Counts only pool content/evidence files.  Manifest slots are metadata,
     * not logical photo bytes; every other regular file is counted, including
     * unknown leftovers, so interrupted cleanup cannot create an unbounded
     * invisible disk reserve.
     */
    private fun physicalBytesLocked(): Long {
        val files = resolver.root.listFiles() ?: return Long.MAX_VALUE
        var total = 0L
        files.forEach { file ->
            if (isPoolMetadataFile(file.name)) return@forEach
            if (Files.isSymbolicLink(file.toPath())) return@forEach
            if (!file.isFile) {
                total = Long.MAX_VALUE
                return@forEach
            }
            val size = try { file.length() } catch (_: SecurityException) { return Long.MAX_VALUE }
            if (size < 0L || total > Long.MAX_VALUE - size) total = Long.MAX_VALUE
            else total += size
        }
        return total
    }

    private fun physicalFileCountLocked(): Int {
        val files = resolver.root.listFiles() ?: return Int.MAX_VALUE
        var count = 0
        files.forEach { file ->
            if (isPoolMetadataFile(file.name)) return@forEach
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile) {
                count = Int.MAX_VALUE
                return@forEach
            }
            if (count < Int.MAX_VALUE) count++
        }
        return count
    }

    private fun isPoolMetadataFile(name: String): Boolean =
        name == POOL_MANIFEST_SLOT_A ||
            name == POOL_MANIFEST_SLOT_B ||
            name == POOL_LEGACY_MANIFEST_NAME

    /** A hard link would let an anchored pool name alias mutable outside data. */
    private fun rejectHardLink(path: Path, label: String) {
        try {
            val links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)
            if ((links as? Number)?.toLong()?.let { it > 1L } == true) {
                throw Stage5ValidationException("$label is a hard link")
            }
        } catch (_: UnsupportedOperationException) {
            // Android/Windows providers may not expose unix:nlink. The pool
            // still has descriptor-relative no-follow checks; no positive
            // hard-link claim is made on such providers.
        } catch (_: IllegalArgumentException) {
            // Same capability boundary for providers without unix attributes.
        }
    }

    private fun assetPath(hash: String): Path {
        if (!hash.matches(Regex("[0-9a-f]{64}"))) {
            throw Stage5ValidationException("photo asset hash is invalid")
        }
        val path = rootPath.resolve(assetFileName(hash))
        resolver.ensureContained(path, "immutable photo asset")
        return path
    }

    private fun assetFileName(hash: String): String = "$POOL_ASSET_PREFIX$hash$POOL_ASSET_SUFFIX"

    private fun incrementRetention(value: Long): Long {
        if (value == Long.MAX_VALUE) throw Stage5ValidationException("photo asset retention overflow")
        return value + 1L
    }

    private fun ensureOpen() {
        if (closed) throw IllegalStateException("immutable photo asset pool is closed")
    }

    private fun copyRecords(): LinkedHashMap<String, PoolRecord> = LinkedHashMap<String, PoolRecord>().also { copy ->
        records.forEach { (hash, record) -> copy[hash] = record.copy() }
    }

    private fun replaceRecords(next: LinkedHashMap<String, PoolRecord>) {
        records.clear()
        records.putAll(next)
    }

    /** Must run under the root lock before reading or mutating the ownership view. */
    private fun refreshManifestLocked() {
        // Refresh bounded metadata, not every photo byte on every lease operation.
        // New instances verify files, and freeze/open verify the files they use.
        replaceRecords(loadManifest(verifyFiles = false))
    }

    private fun loadManifest(verifyFiles: Boolean = true): LinkedHashMap<String, PoolRecord> {
        val loaded = manifestSlots.mapIndexedNotNull { slot, path ->
            if (!resolver.exists(path)) return@mapIndexedNotNull null
            slot to readManifestSlot(path, verifyFiles = false)
        }
        if (loaded.isEmpty()) {
            activeManifestSlot = -1
            manifestGeneration = 0L
            return LinkedHashMap<String, PoolRecord>().also { recoverManifestStagingLocked(it) }
        }
        val highestGeneration = loaded.maxOf { it.second.first }
        val winners = loaded.filter { it.second.first == highestGeneration }
        if (winners.size != 1) {
            throw Stage5ValidationException("immutable photo pool manifests have ambiguous generations")
        }
        activeManifestSlot = winners.single().first
        manifestGeneration = highestGeneration
        val committed = winners.single().second.second
        if (verifyFiles) committed.values.forEach { verifyManagedFile(it) }
        recoverManifestStagingLocked(committed)
        return committed
    }

    /**
     * A staged index is not a published retention transaction. Abandon only a
     * complete, consecutive, validated proposal under the shared root lock;
     * keep the committed slot and every asset byte. Rolling it forward could
     * apply a failed release twice when a still-live owner retries its lease.
     * Unknown/corrupt staging is preserved and remains an explicit failure.
     */
    private fun recoverManifestStagingLocked(committed: Map<String, PoolRecord>) {
        val temporary = rootPath.resolve(POOL_MANIFEST_STAGING)
        resolver.ensureContained(temporary, "immutable photo pool manifest staging")
        if (!resolver.exists(temporary)) return
        if (!resolver.isRegularFile(temporary)) {
            throw Stage5ValidationException("immutable photo pool staging is not a regular file")
        }
        rejectHardLink(temporary, "immutable photo pool manifest staging")
        val staged = readManifestSlot(temporary, verifyFiles = false)
        if (manifestGeneration == Long.MAX_VALUE || staged.first != manifestGeneration + 1L) {
            throw Stage5ValidationException("immutable photo pool staging generation is not consecutive")
        }
        if (staged.second.values.sumOf { it.descriptor.byteCount } > maxTotalBytes) {
            throw Stage5ValidationException("immutable photo pool staging exceeds its byte limit")
        }
        staged.second.forEach { (hash, proposed) ->
            val before = committed[hash]
            if (before != null && before.descriptor != proposed.descriptor) {
                throw Stage5ValidationException("immutable photo pool staging descriptor conflicts with committed state")
            }
            verifyManagedFile(proposed)
        }
        committed.values.forEach { verifyManagedFile(it) }
        // New unindexed content remains conservative evidence and can be
        // adopted later only through freeze's exact descriptor/hash checks.
        resolver.deletePath(temporary, "verified interrupted immutable photo pool manifest")
    }

    private fun readManifestSlot(
        path: Path,
        verifyFiles: Boolean
    ): Pair<Long, LinkedHashMap<String, PoolRecord>> {
        val bytes = resolver.openRead(path, "immutable photo pool manifest").use {
            com.example.myapplication.stage5.readBoundedBytes(
                it,
                minOf(POOL_MANIFEST_MAX_BYTES, Stage5Limits.MAX_JSON_BYTES),
                "immutable photo pool manifest"
            )
        }
        val text = try {
            String(bytes, StandardCharsets.US_ASCII)
        } catch (error: RuntimeException) {
            throw Stage5ValidationException("immutable photo pool manifest is not ASCII", error)
        }
        if (!text.endsWith("\n")) throw Stage5ValidationException("immutable photo pool manifest is truncated")
        val lines = text.dropLast(1).split('\n')
        if (lines.size < 3 || lines[0] != POOL_MANIFEST_MAGIC) {
            throw Stage5ValidationException("immutable photo pool manifest is unsupported")
        }
        val generation = lines[1].toLongOrNull()
            ?: throw Stage5ValidationException("immutable photo pool manifest generation is invalid")
        if (generation < 1L) throw Stage5ValidationException("immutable photo pool manifest generation is invalid")
        val count = lines[2].toIntOrNull()
            ?: throw Stage5ValidationException("immutable photo pool manifest count is invalid")
        if (count !in 0..maxAssetCount || lines.size != count + 3) {
            throw Stage5ValidationException("immutable photo pool manifest count is invalid")
        }
        val loaded = LinkedHashMap<String, PoolRecord>(count)
        lines.drop(3).forEach { line ->
            val fields = line.split('\t')
            if (fields.size != 6) throw Stage5ValidationException("immutable photo pool manifest entry is malformed")
            val hash = fields[0]
            if (!hash.matches(Regex("[0-9a-f]{64}")) || loaded.containsKey(hash)) {
                throw Stage5ValidationException("immutable photo pool manifest hash is invalid")
            }
            val descriptor = try {
                PhotoDescriptor(
                    byteCount = fields[1].toLong(),
                    sha256 = hash,
                    mimeType = fields[2],
                    width = fields[3].toInt(),
                    height = fields[4].toInt()
                )
            } catch (error: IllegalArgumentException) {
                throw Stage5ValidationException("immutable photo pool manifest descriptor is invalid", error)
            }
            val retention = fields[5].toLongOrNull()
                ?: throw Stage5ValidationException("immutable photo pool manifest retention is invalid")
            if (retention < 0L) throw Stage5ValidationException("immutable photo pool manifest retention is negative")
            loaded[hash] = PoolRecord(descriptor, retention)
        }
        if (verifyFiles) loaded.values.forEach { verifyManagedFile(it) }
        return generation to loaded
    }

    private fun writeManifest(next: LinkedHashMap<String, PoolRecord>) {
        if (next.size > maxAssetCount) throw Stage5ValidationException("immutable photo pool count exceeds its limit")
        val total = next.values.sumOf { it.descriptor.byteCount }
        if (total > maxTotalBytes) throw Stage5ValidationException("immutable photo pool bytes exceed its limit")
        val nextGeneration = if (manifestGeneration == Long.MAX_VALUE) {
            throw Stage5ValidationException("immutable photo pool manifest generation overflow")
        } else {
            manifestGeneration + 1L
        }
        val content = buildString {
            append(POOL_MANIFEST_MAGIC).append('\n')
            append(nextGeneration).append('\n')
            append(next.size).append('\n')
            next.toSortedMap().forEach { (hash, record) ->
                val descriptor = record.descriptor
                append(hash).append('\t')
                    .append(descriptor.byteCount).append('\t')
                    .append(descriptor.mimeType).append('\t')
                    .append(descriptor.width).append('\t')
                    .append(descriptor.height).append('\t')
                    .append(record.retentionCount).append('\n')
            }
        }.toByteArray(StandardCharsets.US_ASCII)
        if (content.size > POOL_MANIFEST_MAX_BYTES) {
            throw Stage5ValidationException("immutable photo pool manifest exceeds its limit")
        }
        val targetSlot = if (activeManifestSlot == 0) 1 else 0
        val target = manifestSlots[targetSlot]
        if (resolver.exists(target)) {
            resolver.deletePath(target, "immutable photo pool inactive manifest cleanup")
        }
        // The shared root lock permits one fixed staging name. A failed cleanup
        // cannot grow an unbounded UUID-temporary set through retain/release.
        // Unknown or unresolved prior staging remains evidence, never disposable.
        val temporary = rootPath.resolve(POOL_MANIFEST_STAGING)
        resolver.ensureContained(temporary, "immutable photo pool manifest staging")
        if (resolver.exists(temporary)) {
            throw Stage5ValidationException("immutable photo pool has unresolved manifest staging evidence")
        }
        var published = false
        try {
            resolver.writeBytes(temporary, content, "immutable photo pool manifest")
            resolver.atomicMove(temporary, target, replaceExisting = false)
            published = true
            val oldSlot = activeManifestSlot
            activeManifestSlot = targetSlot
            manifestGeneration = nextGeneration
            if (oldSlot >= 0 && oldSlot != targetSlot) {
                try {
                    if (resolver.exists(manifestSlots[oldSlot])) {
                        resolver.deletePath(manifestSlots[oldSlot], "immutable photo pool old manifest cleanup")
                    }
                } catch (_: IOException) {
                    // Keep old evidence if cleanup is interrupted; the
                    // highest generation remains authoritative on restart.
                } catch (_: SecurityException) {
                    // Same conservative policy for protected evidence.
                } catch (_: Stage5ValidationException) {
                    // A protected/malformed old slot is retained as evidence
                    // while the new generation remains authoritative.
                }
            }
        } finally {
            try {
                if (resolver.exists(temporary)) {
                    resolver.deletePath(temporary, "immutable photo pool manifest cleanup")
                }
            } catch (cleanup: Throwable) {
                if (!published) throw cleanup
            }
        }
    }
}
