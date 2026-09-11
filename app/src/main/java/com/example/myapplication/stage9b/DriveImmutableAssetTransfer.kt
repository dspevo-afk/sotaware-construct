package com.example.myapplication.stage9b

import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage5.PhotoPathOperationsFactory
import com.example.myapplication.stage5.PhotoPathResolver
import com.example.myapplication.stage5.PhotoDescriptor
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.validateNoDuplicateJsonMembers
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.EmptyContent
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpRequestFactory
import com.google.api.client.http.HttpResponse
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.FilterInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val DRIVE_TRANSFER_MAX_STATE_BYTES = 512 * 1024
private const val DRIVE_HTTP_TIMEOUT_MILLIS = 30_000

/** A validated result that is safe to reference from a v3 manifest. */
data class AssetTransferResult(
    val descriptors: Map<String, RemoteAssetDescriptor>,
    val uploadedBytes: Long,
    val reusedBytes: Long = 0L
) {
    init {
        require(uploadedBytes >= 0L) { "uploadedBytes must not be negative" }
        require(reusedBytes >= 0L) { "reusedBytes must not be negative" }
    }
}

open class DriveAssetTransferException(message: String, cause: Throwable? = null) : IOException(message, cause)
class DriveAssetSessionExpiredException(message: String) : DriveAssetTransferException(message)
class DriveAssetStaleGenerationException(val generation: Long? = null) :
    DriveAssetTransferException("Drive asset transfer became stale")

/** Explicit ownership for file-backed assets returned by a remote download. */
interface RemoteDownloadOwnership {
    val isReleased: Boolean

    /** Idempotently invalidate the result and reclaim transfer-owned files. */
    fun release()
}

/**
 * Content-addressed, immutable Drive photo transfer.  Directory resources are
 * opened by explicit operation owners; the caller owns the returned
 * [PhotoAssetSet] files and decides when they become reachable.
 */
class DriveImmutableAssetTransfer private constructor(
    private val requestFactory: HttpRequestFactory,
    private val apiBaseUrl: String,
    private val uploadBaseUrl: String,
    private val accountId: String,
    private val stateDirectory: Path,
    private val stagingDirectory: Path,
    private val nowMillis: () -> Long,
    private val trustedRootDirectory: Path?,
    private val operationsFactory: PhotoPathOperationsFactory?,
    /** Deterministic test seam for platforms whose directory handles cannot be opened. */
    private val directoryForce: (() -> Unit)?
) {
    companion object {
        const val CHUNK_SIZE_BYTES: Int = 1024 * 1024
        const val STREAM_BUFFER_BYTES: Int = 64 * 1024
        const val RESUMABLE_GRANULARITY_BYTES: Int = 256 * 1024
        const val MAX_SESSION_URL_LENGTH: Int = 2_048
        private const val MAX_NO_PROGRESS_RESPONSES: Int = 8

        private const val PROP_ACCOUNT_ID = "sotaware_account_id"
        private const val PROP_BACKUP_ROOT_ID = "sotaware_backup_root_id"
        private const val PROP_DOCUMENT_ID = "sotaware_document_id"
        private const val PROP_SOURCE_FINGERPRINT = "sotaware_source_fingerprint"
        private const val PROP_MANIFEST_SCHEMA = "sotaware_manifest_schema"
        private const val PROP_ASSET_SHA256 = "sotaware_asset_sha256"
        private const val PROP_IMMUTABLE = "sotaware_immutable_asset"
        private const val PROVIDER_HOST_SUFFIX = ".googleapis.com"
        private const val PROVIDER_HOST = "googleapis.com"
        private const val UPLOAD_PATH_PREFIX = "/upload/drive/v3/"
        private const val MIME_JSON = "application/json; charset=UTF-8"
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")

        /** Constructor used by Android wiring with the authenticated Drive service. */
        operator fun invoke(
            service: Drive,
            accountId: String,
            stateDirectory: Path,
            stagingDirectory: Path,
            nowMillis: () -> Long = { System.currentTimeMillis() },
            trustedRootDirectory: Path? = null
        ): DriveImmutableAssetTransfer {
            val api = service.baseUrl.trimEnd('/') + "/"
            val root = service.rootUrl.trimEnd('/') + "/"
            return DriveImmutableAssetTransfer(
                requestFactory = service.requestFactory,
                apiBaseUrl = api,
                uploadBaseUrl = root + "upload/drive/v3/",
                accountId = accountId,
                stateDirectory = stateDirectory,
                stagingDirectory = stagingDirectory,
                nowMillis = nowMillis,
                trustedRootDirectory = trustedRootDirectory,
                operationsFactory = null,
                directoryForce = null
            )
        }

        /** Android/test wiring seam using the anchored photo operations. */
        internal operator fun invoke(
            service: Drive,
            accountId: String,
            stateDirectory: Path,
            stagingDirectory: Path,
            nowMillis: () -> Long = { System.currentTimeMillis() },
            trustedRootDirectory: Path? = null,
            operationsFactory: PhotoPathOperationsFactory,
            directoryForce: (() -> Unit)? = null
        ): DriveImmutableAssetTransfer {
            val api = service.baseUrl.trimEnd('/') + "/"
            val root = service.rootUrl.trimEnd('/') + "/"
            return DriveImmutableAssetTransfer(
                requestFactory = service.requestFactory,
                apiBaseUrl = api,
                uploadBaseUrl = root + "upload/drive/v3/",
                accountId = accountId,
                stateDirectory = stateDirectory,
                stagingDirectory = stagingDirectory,
                nowMillis = nowMillis,
                trustedRootDirectory = trustedRootDirectory,
                operationsFactory = operationsFactory,
                directoryForce = directoryForce
            )
        }

        /** Constructor used by deterministic synthetic HTTP adapters. */
        operator fun invoke(
            requestFactory: HttpRequestFactory,
            apiBaseUrl: String,
            accountId: String,
            stateDirectory: Path,
            stagingDirectory: Path,
            uploadBaseUrl: String = deriveUploadBaseUrl(apiBaseUrl),
            nowMillis: () -> Long = { System.currentTimeMillis() },
            trustedRootDirectory: Path? = null
        ): DriveImmutableAssetTransfer = DriveImmutableAssetTransfer(
            requestFactory,
            apiBaseUrl.trimEnd('/') + "/",
            uploadBaseUrl.trimEnd('/') + "/",
            accountId,
            stateDirectory,
            stagingDirectory,
            nowMillis,
            trustedRootDirectory,
            null,
            null
        )

        /** Deterministic adapter seam retaining descriptor-relative operations in tests. */
        internal operator fun invoke(
            requestFactory: HttpRequestFactory,
            apiBaseUrl: String,
            accountId: String,
            stateDirectory: Path,
            stagingDirectory: Path,
            uploadBaseUrl: String = deriveUploadBaseUrl(apiBaseUrl),
            nowMillis: () -> Long = { System.currentTimeMillis() },
            trustedRootDirectory: Path? = null,
            operationsFactory: PhotoPathOperationsFactory,
            directoryForce: (() -> Unit)? = null
        ): DriveImmutableAssetTransfer = DriveImmutableAssetTransfer(
            requestFactory,
            apiBaseUrl.trimEnd('/') + "/",
            uploadBaseUrl.trimEnd('/') + "/",
            accountId,
            stateDirectory,
            stagingDirectory,
            nowMillis,
            trustedRootDirectory,
            operationsFactory,
            directoryForce
        )

        /** Java/Kotlin callers that prefer a named factory. */
        fun fromDrive(
            service: Drive,
            accountId: String,
            stateDirectory: Path,
            stagingDirectory: Path,
            nowMillis: () -> Long = { System.currentTimeMillis() },
            trustedRootDirectory: Path? = null
        ): DriveImmutableAssetTransfer = invoke(
            service,
            accountId,
            stateDirectory,
            stagingDirectory,
            nowMillis,
            trustedRootDirectory
        )

        private fun deriveUploadBaseUrl(apiBaseUrl: String): String {
            val uri = try {
                java.net.URI(apiBaseUrl)
            } catch (error: Exception) {
                throw IllegalArgumentException("Drive API base URL is invalid", error)
            }
            require(uri.scheme.equals("https", ignoreCase = true)) { "Drive API base URL must use HTTPS" }
            return "${uri.scheme}://${uri.authority}/upload/drive/v3/"
        }
    }

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    /**
     * Open one durable-store owner for a single synchronous operation.  The
     * transfer itself deliberately retains only constructor arguments; an
     * ephemeral gateway must not retain directory descriptors between calls.
     */
    private fun newStateStore(): DriveTransferStateStore = DriveTransferStateStore(
        stateDirectory = stateDirectory,
        accountId = accountId,
        nowMillis = nowMillis,
        gson = gson,
        trustedRootDirectory = trustedRootDirectory,
        operationsFactory = operationsFactory,
        directoryForce = directoryForce
    )

    private fun newAdoptionRecoveryStore() = DriveAdoptionRecoveryStore(
        DurableDriveTransferStorage(stateDirectory, nowMillis, gson, trustedRootDirectory, operationsFactory, directoryForce), accountId
    )

    internal fun readAdoptionRecovery(scope: SyncScope, source: SourceFingerprint): DriveAdoptionRecovery? =
        newAdoptionRecoveryStore().use { it.read(scope, source) }

    internal fun prepareAdoptionRecovery(record: DriveAdoptionRecovery) =
        newAdoptionRecoveryStore().use { it.prepare(record) }

    internal fun recordAdoptionCompensation(record: DriveAdoptionRecovery, cursor: com.example.myapplication.stage4.RemoteCursor, etag: String) =
        newAdoptionRecoveryStore().use { it.recordCompensation(record, cursor, etag) }

    internal fun reselectAdoptionRecovery(
        record: DriveAdoptionRecovery, candidate: com.example.myapplication.stage4.RemoteAdoptionCandidate, etag: String
    ): DriveAdoptionRecovery = newAdoptionRecoveryStore().use { it.reselect(record, candidate, etag) }

    internal fun retireRejectedAdoptionRecovery(record: DriveAdoptionRecovery) =
        newAdoptionRecoveryStore().use { it.retireRejected(record) }

    internal fun acknowledgeAdoptionRecovery(
        scope: SyncScope,
        candidate: com.example.myapplication.stage4.RemoteAdoptionCandidate,
        remote: com.example.myapplication.stage4.RemoteDocumentMetadata
    ) = newAdoptionRecoveryStore().use { it.acknowledge(scope, candidate, remote) }

    /** Open an anchored staging owner for one operation/returned download set. */
    private fun newStagingResolver(): PhotoPathResolver {
        return if (operationsFactory == null) {
            PhotoPathResolver(
                stagingDirectory.toFile(),
                createRoot = true,
                trustedRootDirectory = trustedRootDirectory?.toFile()
            )
        } else {
            PhotoPathResolver(
                stagingDirectory.toFile(),
                createRoot = true,
                operationsFactory = operationsFactory,
                trustedRootDirectory = trustedRootDirectory?.toFile()
            )
        }
    }

    /** Constructor admission opens and closes both roots, including partial failure. */
    private fun admitRoots() {
        newStateStore().use {
            newStagingResolver().use { }
        }
    }
    /** Exact returned-set -> lease mapping; descriptors never authorize cleanup.
     * Weak keys preserve repeated-discard idempotence while allowing a caller
     * that drops the returned set to release the bookkeeping itself. */
    private val downloadOwners = Collections.synchronizedMap(
        WeakHashMap<PhotoAssetSet, DownloadedAssetLease>()
    )
    private val downloadOwnersByRequest = Collections.synchronizedMap(
        LinkedHashMap<String, MutableList<DownloadedAssetLease>>()
    )

    init {
        require(accountId.isNotBlank()) { "accountId must not be blank" }
        require(stateDirectory.isAbsolute && stagingDirectory.isAbsolute) {
            "Drive transfer state and staging directories must be absolute"
        }
        validateHttpsBase(apiBaseUrl, "/drive/v3/")
        validateHttpsBase(uploadBaseUrl, UPLOAD_PATH_PREFIX)
        // Construct both roots through PhotoPathResolver.  The trusted root,
        // when supplied by Android, permits aliases only above that boundary
        // and rejects every symlink component below it.  Admission is only a
        // check: operation owners below open and close their own descriptors.
        admitRoots()
    }

    /**
     * Upload all assets needed by a snapshot before its manifest is published.
     * Asset bytes are content-addressed and never overwrite an existing remote
     * file.  [existing] is only an observation; every reused file is verified by
     * Drive metadata or a bounded streamed read before it is accepted.
     */
    suspend fun upload(
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?,
        snapshotDigest: String,
        parentFolderId: String,
        assets: PhotoAssetSet,
        existing: Map<String, RemoteAssetDescriptor> = emptyMap(),
        isGenerationCurrent: () -> Boolean = { true }
    ): AssetTransferResult = withContext(Dispatchers.IO) {
        validateIdentity(scope, snapshotDigest, parentFolderId)
        val validated = validateSetWithoutSnapshot(assets)
        uploadValidated(
            scope,
            sourceFingerprint,
            snapshotDigest,
            parentFolderId,
            validated,
            existing,
            isGenerationCurrent
        )
    }

    /** Snapshot-aware overload used by the gateway and normal production path. */
    suspend fun upload(
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?,
        snapshot: com.example.myapplication.stage1.DocumentSnapshotV1,
        parentFolderId: String,
        assets: PhotoAssetSet,
        existing: Map<String, RemoteAssetDescriptor> = emptyMap(),
        isGenerationCurrent: () -> Boolean = { true }
    ): AssetTransferResult = withContext(Dispatchers.IO) {
        val digest = RemoteManifestCodec.snapshotDigest(snapshot)
        validateIdentity(scope, digest, parentFolderId)
        val validated = try {
            validatePhotoAssets(snapshot, assets)
        } catch (error: IllegalArgumentException) {
            throw DriveAssetTransferException("photo asset validation failed", error)
        }
        uploadValidated(scope, sourceFingerprint, digest, parentFolderId, validated, existing, isGenerationCurrent)
    }

    private fun uploadValidated(
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?,
        snapshotDigest: String,
        parentFolderId: String,
        assets: PhotoAssetSet,
        existing: Map<String, RemoteAssetDescriptor>,
        isGenerationCurrent: () -> Boolean
    ): AssetTransferResult {
        if (assets.isEmpty()) return AssetTransferResult(emptyMap(), 0L, 0L)
        newStateStore().use { stateStore ->
            newStagingResolver().use { stagingResolver ->
                return uploadValidatedWithResources(
                    scope,
                    sourceFingerprint,
                    snapshotDigest,
                    parentFolderId,
                    assets,
                    existing,
                    isGenerationCurrent,
                    stateStore,
                    stagingResolver
                )
            }
        }
    }

    private fun uploadValidatedWithResources(
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?,
        snapshotDigest: String,
        parentFolderId: String,
        assets: PhotoAssetSet,
        existing: Map<String, RemoteAssetDescriptor>,
        isGenerationCurrent: () -> Boolean,
        stateStore: DriveTransferStateStore,
        stagingResolver: PhotoPathResolver
    ): AssetTransferResult {
        val identity = stateStore.identity(scope, sourceFingerprint, snapshotDigest, parentFolderId)
        val state = stateStore.load(identity)
        val byHash = LinkedHashMap<String, List<String>>()
        assets.descriptors.forEach { (name, descriptor) ->
            val names = byHash[descriptor.sha256].orEmpty().toMutableList()
            names += name
            byHash[descriptor.sha256] = names
        }
        // A content hash identifies the immutable Drive object, including its
        // filename/MIME identity.  Two occurrences sharing bytes but carrying
        // conflicting metadata cannot safely point at one object: applying the
        // first descriptor to all names would publish a manifest that no
        // longer describes the requested occurrence.  Reject before any
        // generated ID or remote mutation instead of silently choosing one.
        byHash.values.forEach { names ->
            val first = assets.descriptors.getValue(names.first())
            names.drop(1).forEach { name ->
                require(assets.descriptors.getValue(name) == first) {
                    "photo occurrences sharing a hash have conflicting descriptors: $name"
                }
            }
        }
        val result = LinkedHashMap<String, RemoteAssetDescriptor>()
        var uploadedBytes = 0L
        var reusedBytes = 0L
        byHash.toSortedMap().forEach { (hash, names) ->
            checkGeneration(isGenerationCurrent)
            val occurrence = names.first()
            val descriptor = assets.descriptors.getValue(occurrence)
            state?.assets?.get(hash)?.let { prior ->
                stateStore.validateAsset(hash, prior)
                if (prior.byteCount != descriptor.byteCount ||
                    prior.sha256 != descriptor.sha256 ||
                    prior.mimeType != descriptor.mimeType ||
                    prior.width != descriptor.width ||
                    prior.height != descriptor.height
                ) {
                    throw DriveAssetTransferException("persisted asset state does not match the immutable descriptor")
                }
            }
            // A content-addressed asset may be referenced by a different
            // occurrence filename after an annotation/snapshot edit.  Probe
            // every descriptor from the prior manifest, not only the same
            // occurrence key, while still verifying the actual remote bytes
            // and scope before accepting a reuse.
            val observedExisting = existing.values.asSequence()
                .distinctBy { it.remoteAssetId }
                .firstOrNull { it.sha256 == descriptor.sha256 && it.byteCount == descriptor.byteCount }
            val reused = observedExisting?.let {
                verifyExistingAsset(scope, parentFolderId, sourceFingerprint, it, descriptor, stagingResolver)
            }
            val remoteId = if (reused != null) {
                reused.remoteAssetId
            } else {
                val prior = state?.assets?.get(hash)
                val stableId = prior?.remoteAssetId?.takeIf { it.isNotBlank() }
                    ?: generateStableId(scope, snapshotDigest, hash, isGenerationCurrent).also { id ->
                        stateStore.putAsset(
                            identity,
                            hash,
                            PersistedAssetState(
                                remoteAssetId = id,
                                byteCount = descriptor.byteCount,
                                sha256 = descriptor.sha256,
                                mimeType = descriptor.mimeType,
                                width = descriptor.width,
                                height = descriptor.height,
                                sessionUrl = prior?.sessionUrl,
                                acknowledgedBytes = prior?.acknowledgedBytes ?: 0L
                            )
                        )
                    }
                val persisted = stateStore.load(identity)?.assets?.get(hash)
                    ?: throw DriveAssetTransferException("asset state was not persisted before Drive create")
                val uploaded = uploadOne(
                    scope = scope,
                    sourceFingerprint = sourceFingerprint,
                    parentFolderId = parentFolderId,
                    asset = assets[occurrence]
                        ?: throw DriveAssetTransferException("asset handle disappeared: $occurrence"),
                    descriptor = descriptor,
                    remoteAssetId = stableId,
                    persisted = persisted,
                    identity = identity,
                    isGenerationCurrent = isGenerationCurrent,
                    stateStore = stateStore,
                    stagingResolver = stagingResolver
                )
                uploadedBytes += uploaded
                stableId
            }
            if (reused != null) reusedBytes += descriptor.byteCount
            names.forEach { name ->
                result[name] = RemoteAssetDescriptor(
                    remoteAssetId = remoteId,
                    byteCount = descriptor.byteCount,
                    sha256 = descriptor.sha256,
                    mimeType = descriptor.mimeType,
                    width = descriptor.width,
                    height = descriptor.height
                )
            }
        }
        return AssetTransferResult(result, uploadedBytes, reusedBytes)
    }

    /**
     * Download and validate each immutable asset into [stagingDirectory].  The
     * returned set is reopenable and contains no aggregate byte array.
     */
    suspend fun download(
        scope: SyncScope,
        parentFolderId: String,
        descriptors: Map<String, RemoteAssetDescriptor>,
        sourceFingerprint: SourceFingerprint? = null,
        snapshotDigest: String? = null
    ): PhotoAssetSet = downloadWithCancellationHandoff { completed ->
        downloadInternal(
            scope,
            parentFolderId,
            descriptors,
            sourceFingerprint,
            snapshotDigest,
            expectedSnapshot = null,
            completed = completed
        )
    }

    /** Snapshot-aware overload that also runs the bounded image decoder. */
    suspend fun download(
        scope: SyncScope,
        parentFolderId: String,
        snapshot: com.example.myapplication.stage1.DocumentSnapshotV1,
        descriptors: Map<String, RemoteAssetDescriptor>,
        sourceFingerprint: SourceFingerprint? = null
    ): PhotoAssetSet = downloadWithCancellationHandoff { completed ->
        downloadInternal(
            scope,
            parentFolderId,
            descriptors,
            sourceFingerprint,
            snapshotDigest = RemoteManifestCodec.snapshotDigest(snapshot),
            expectedSnapshot = snapshot,
            completed = completed
        )
    }

    /**
     * A dispatcher handoff may drop a completed file-backed result when the
     * caller is canceled while switching back to its context. Keep the exact
     * set out-of-band until the handoff is accepted so that cancellation can
     * reclaim its ownership.
     */
    private suspend fun downloadWithCancellationHandoff(
        block: suspend (AtomicReference<PhotoAssetSet?>) -> PhotoAssetSet
    ): PhotoAssetSet {
        val completed = AtomicReference<PhotoAssetSet?>()
        try {
            return block(completed)
        } catch (cancelled: CancellationException) {
            completed.getAndSet(null)?.let(::discardDownloadedAssets)
            throw cancelled
        }
    }

    private suspend fun downloadInternal(
        scope: SyncScope,
        parentFolderId: String,
        descriptors: Map<String, RemoteAssetDescriptor>,
        sourceFingerprint: SourceFingerprint?,
        snapshotDigest: String?,
        expectedSnapshot: com.example.myapplication.stage1.DocumentSnapshotV1?,
        completed: AtomicReference<PhotoAssetSet?>
    ): PhotoAssetSet = withContext(Dispatchers.IO) {
        require(scope.accountId == accountId) { "gateway account does not match SyncScope" }
        require(scope.accountId.length in 1..Stage5Limits.MAX_STRING_CHARS &&
            scope.accountId.none { it.code < 0x20 || it.code == 0x7f }) {
            "account identifier is invalid"
        }
        require(scope.backupRootId.matches(Regex("[A-Za-z0-9_-]{1,512}"))) {
            "backup root identifier is invalid"
        }
        require(parentFolderId.matches(Regex("[A-Za-z0-9_-]{1,512}"))) {
            "parentFolderId is invalid"
        }
        if (snapshotDigest != null) require(snapshotDigest.matches(SHA256_REGEX)) {
            "snapshotDigest is invalid"
        }
        if (descriptors.size > Stage5Limits.MAX_REMOTE_DESCRIPTOR_COUNT) {
            throw DriveAssetTransferException("remote asset count exceeds its limit")
        }
        if (descriptors.isEmpty()) return@withContext PhotoAssetSet.EMPTY
        val resolver = newStagingResolver()
        val staged = LinkedHashMap<String, Path>()
        val owned = LinkedHashMap<String, Path>()
        var total = 0L
        var lease: DownloadedAssetLease? = null
        try {
            descriptors.toSortedMap().forEach { (name, descriptor) ->
                requireSafeName(name)
                checkDescriptorBudget(descriptor)
                total = Math.addExact(total, descriptor.byteCount)
                require(total <= Stage5Limits.MAX_TOTAL_PHOTO_BYTES) {
                    "remote asset bytes exceed aggregate limit"
                }
                val metadata = getAssetMetadata(descriptor.remoteAssetId)
                    ?: throw DriveAssetTransferException("remote asset is missing: ${descriptor.remoteAssetId}")
                requireAssetScope(metadata, scope, parentFolderId, sourceFingerprint, descriptor)
                // Every download attempt gets its own final staging file. A
                // deterministic content-addressed target would let another
                // overlapping request reuse (and later delete) this request's
                // bytes before its owner is released.
                val target = stagingPath(descriptor.mimeType, resolver)
                val streamed = streamRemoteAsset(metadata, descriptor, target, resolver)
                if (streamed.published) owned[target.toString()] = target
                val digest = streamed.digest
                if (digest.byteCount != descriptor.byteCount || digest.sha256 != descriptor.sha256) {
                    throw DriveAssetTransferException("remote asset integrity check failed: $name")
                }
                staged[name] = target
            }
            val photoDescriptors = descriptors.mapValues { it.value.asPhotoDescriptor() }
            lease = DownloadedAssetLease(
                resolver = resolver,
                paths = staged.toMap(),
                ownedPaths = owned.values.toSet(),
                descriptors = photoDescriptors,
                forceDirectory = { forceStagingDirectory(resolver) },
                onReleased = ::unregisterDownloadOwner
            )
            val set = try {
                photoAssetsFromDescriptors(photoDescriptors) { name -> requireNotNull(lease).open(name) }
            } catch (error: Throwable) {
                requireNotNull(lease).release()
                throw error
            }
            // Reopenable sources are still validated against the descriptor
            // before crossing the transport boundary.
            if (expectedSnapshot != null) {
                validatePhotoAssets(expectedSnapshot, set, photoDescriptors)
            } else {
                validatePhotoAssetsForDescriptors(set, photoDescriptors)
            }
            val retainedLease = requireNotNull(lease)
            downloadOwners[set] = retainedLease
            val requestKey = downloadRequestKey(scope, parentFolderId, descriptors)
            synchronized(downloadOwnersByRequest) {
                downloadOwnersByRequest.getOrPut(requestKey) { mutableListOf() }.add(retainedLease)
            }
            // Publish ownership before leaving the dispatcher boundary. If
            // prompt cancellation drops the returned value, the outer
            // handoff can still reclaim this exact lease.
            completed.set(set)
            return@withContext set
        } catch (cancelled: CancellationException) {
            lease?.release()
            if (lease == null) owned.values.forEach { path -> deleteOwnedPartial(path, resolver) }
            throw cancelled
        } catch (error: Throwable) {
            lease?.release()
            if (lease == null) owned.values.forEach { path -> deleteOwnedPartial(path, resolver) }
            if (error is DriveAssetTransferException) throw error
            throw DriveAssetTransferException("remote asset download failed", error)
        } finally {
            if (lease == null) {
                try {
                    resolver.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * Releases the exact owner associated with a returned set.  The lease
     * waits for all currently-open streams before deleting files created by
     * this transport; calling it repeatedly is harmless.
     */
    fun discardDownloadedAssets(assets: PhotoAssetSet) {
        if (assets.isEmpty()) return
        val owner = downloadOwners[assets]
            ?: throw DriveAssetTransferException("downloaded asset ownership is unknown")
        owner.release()
    }

    /** Returns the explicit owner while a set is being handed to a gateway. */
    internal fun ownershipFor(assets: PhotoAssetSet): RemoteDownloadOwnership? =
        if (assets.isEmpty()) null else downloadOwners[assets]

    /** Retain state for an operation until its manifest has been durably accepted. */
    fun clearState(
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?,
        snapshotDigest: String,
        parentFolderId: String = scope.backupRootId
    ) {
        newStateStore().use { stateStore ->
            stateStore.delete(stateStore.identity(scope, sourceFingerprint, snapshotDigest, parentFolderId))
        }
    }

    /**
     * Reserves one stable Drive ID before the caller attempts a folder or
     * manifest create.  The reservation key includes every scope component,
     * including the parent folder, so a mismatched old state is unusable.
     */
    fun reserveResourceId(
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?,
        parentFolderId: String,
        resourceKind: String,
        isGenerationCurrent: () -> Boolean
    ): String {
        validateScope(scope, parentFolderId)
        require(resourceKind == "document-folder" || resourceKind == "document-manifest") {
            "resourceKind must be document-folder or document-manifest"
        }
        return newStateStore().use { stateStore ->
            stateStore.reserveResourceId(
                scope = scope,
                sourceFingerprint = sourceFingerprint,
                parentFolderId = parentFolderId,
                resourceKind = resourceKind,
                isGenerationCurrent = isGenerationCurrent
            ) { requestGeneratedId(isGenerationCurrent) }
        }
    }

    /**
     * Verifies an already-published immutable asset before another document
     * adopts its stable Drive ID.  Drive output metadata is accepted only when
     * both size and SHA-256 are present and agree; otherwise [remoteDigest]
     * performs the bounded streamed media verification used by reuse checks.
     */
    internal fun verifyRemoteAsset(
        scope: SyncScope,
        parentFolderId: String,
        sourceFingerprint: SourceFingerprint?,
        descriptor: RemoteAssetDescriptor
    ) {
        validateScope(scope, parentFolderId)
        newStagingResolver().use { stagingResolver ->
            val metadata = getAssetMetadata(descriptor.remoteAssetId)
                ?: throw DriveAssetTransferException("Drive immutable asset is missing")
            requireAssetScope(metadata, scope, parentFolderId, sourceFingerprint, descriptor)
            val digest = remoteDigest(metadata, descriptor.byteCount, descriptor.sha256, stagingResolver)
            if (digest.byteCount != descriptor.byteCount || digest.sha256 != descriptor.sha256) {
                throw DriveAssetTransferException("Drive immutable asset integrity mismatch")
            }
        }
    }

    private fun uploadOne(
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?,
        parentFolderId: String,
        asset: PhotoAsset,
        descriptor: PhotoDescriptor,
        remoteAssetId: String,
        persisted: PersistedAssetState,
        identity: TransferIdentity,
        isGenerationCurrent: () -> Boolean,
        stateStore: DriveTransferStateStore,
        stagingResolver: PhotoPathResolver
    ): Long {
        val metadata = File()
            .setId(remoteAssetId)
            .setName(assetFileName(descriptor))
            .setMimeType(descriptor.mimeType)
            .setParents(listOf(parentFolderId))
            .setAppProperties(assetProperties(scope, sourceFingerprint, descriptor))
        var sessionUrl = persisted.sessionUrl
        var acknowledged = persisted.acknowledgedBytes.coerceIn(0L, descriptor.byteCount)
        if (sessionUrl != null) {
            try {
                val server = queryAcknowledged(sessionUrl, descriptor.byteCount)
                acknowledged = server.acknowledgedBytes
                if (server.complete) {
                verifyUploadedMetadata(scope, parentFolderId, sourceFingerprint, remoteAssetId, descriptor, stagingResolver)
                    stateStore.updateAcknowledged(identity, descriptor.sha256, descriptor.byteCount, null, acknowledged)
                    return 0L
                }
                stateStore.updateAcknowledged(identity, descriptor.sha256, descriptor.byteCount, sessionUrl, acknowledged)
            } catch (_: DriveAssetSessionExpiredException) {
                sessionUrl = null
                acknowledged = 0L
                stateStore.updateAcknowledged(identity, descriptor.sha256, descriptor.byteCount, null, 0L)
            }
        }
        if (sessionUrl == null) {
            if (acknowledged >= descriptor.byteCount) {
                try {
                    verifyUploadedMetadata(scope, parentFolderId, sourceFingerprint, remoteAssetId, descriptor, stagingResolver)
                    stateStore.updateAcknowledged(identity, descriptor.sha256, descriptor.byteCount, null, descriptor.byteCount)
                    return 0L
                } catch (_: DriveAssetTransferException) {
                    // The persisted completion may have been written before
                    // the remote create response was lost.  If the stable ID
                    // is absent or invalid, start a fresh session below.
                }
            }
            checkGeneration(isGenerationCurrent)
            sessionUrl = try {
                startResumableSession(metadata, descriptor, isGenerationCurrent)
            } catch (error: DriveAssetStaleGenerationException) {
                throw error
            } catch (error: DriveAssetTransferException) {
                // A process can lose the response after Drive has committed
                // the immutable file.  Probe the pre-generated ID before
                // attempting another create; only a verified byte identity is
                // accepted as idempotent completion.
                val existing = try { getAssetMetadata(remoteAssetId) } catch (probe: Throwable) {
                    error.addSuppressed(probe)
                    null
                }
                if (existing != null) {
                    verifyUploadedMetadata(scope, parentFolderId, sourceFingerprint, remoteAssetId, descriptor, stagingResolver)
                    stateStore.updateAcknowledged(identity, descriptor.sha256, descriptor.byteCount, null, descriptor.byteCount)
                    return 0L
                }
                throw error
            }
            acknowledged = 0L
            stateStore.updateAcknowledged(identity, descriptor.sha256, descriptor.byteCount, sessionUrl, acknowledged)
        }
        var sent = 0L
        var noProgressResponses = 0
        val chunk = ByteArray(CHUNK_SIZE_BYTES)
        while (acknowledged < descriptor.byteCount) {
            checkGeneration(isGenerationCurrent)
            // Reopen the immutable source for every server-acknowledged
            // offset.  A 308 may acknowledge less than the bytes in the
            // request; retaining the old stream in that case would skip from
            // the wrong position and upload a corrupt range.
            val remaining = descriptor.byteCount - acknowledged
            val target = minOf(CHUNK_SIZE_BYTES.toLong(), remaining).toInt()
            val count = asset.open().use { input ->
                skipExactly(input, acknowledged)
                readChunk(input, chunk, target)
            }
            if (count <= 0) throw DriveAssetTransferException("asset ended before declared byteCount")
            if (count.toLong() > remaining) throw DriveAssetTransferException("asset exceeded declared byteCount")
            val start = acknowledged
            val response = try {
                putChunk(
                    sessionUrl = requireNotNull(sessionUrl),
                    mimeType = descriptor.mimeType,
                    bytes = chunk,
                    count = count,
                    start = start,
                    total = descriptor.byteCount,
                    isGenerationCurrent = isGenerationCurrent
                )
            } catch (_: DriveAssetSessionExpiredException) {
                // The resumable session can expire independently of the
                // durable operation.  Start another session for the same
                // pre-generated immutable ID and replay from byte zero.
                checkGeneration(isGenerationCurrent)
                sessionUrl = null
                acknowledged = 0L
                stateStore.updateAcknowledged(identity, descriptor.sha256, descriptor.byteCount, null, 0L)
                sessionUrl = startResumableSession(metadata, descriptor, isGenerationCurrent)
                stateStore.updateAcknowledged(identity, descriptor.sha256, descriptor.byteCount, sessionUrl, 0L)
                continue
            }
            sent += count.toLong()
            if (response.complete) {
                acknowledged = descriptor.byteCount
            } else {
                if (response.acknowledgedBytes > start + count) {
                    throw DriveAssetTransferException("Drive acknowledged bytes that were not uploaded")
                }
                if (response.acknowledgedBytes <= start) {
                    noProgressResponses++
                    if (noProgressResponses > MAX_NO_PROGRESS_RESPONSES) {
                        throw DriveAssetTransferException("Drive resumable upload made no progress")
                    }
                } else {
                    noProgressResponses = 0
                }
                acknowledged = response.acknowledgedBytes
            }
            stateStore.updateAcknowledged(identity, descriptor.sha256, descriptor.byteCount, sessionUrl, acknowledged)
        }
        verifyUploadedMetadata(scope, parentFolderId, sourceFingerprint, remoteAssetId, descriptor, stagingResolver)
        stateStore.updateAcknowledged(identity, descriptor.sha256, descriptor.byteCount, null, descriptor.byteCount)
        return sent.coerceAtLeast(0L)
    }

    private fun startResumableSession(
        metadata: File,
        descriptor: PhotoDescriptor,
        isGenerationCurrent: () -> Boolean
    ): String {
        val body = gson.toJson(metadata).toByteArray(StandardCharsets.UTF_8)
        val request = configureRequest(requestFactory.buildPostRequest(
            GenericUrl(uploadBaseUrl + "files")
                .set("uploadType", "resumable")
                .set("supportsAllDrives", true),
            ByteArrayContent(MIME_JSON, body)
        ))
        request.headers.setContentType(MIME_JSON)
        request.headers.set("X-Upload-Content-Type", descriptor.mimeType)
        request.headers.set("X-Upload-Content-Length", descriptor.byteCount.toString())
        request.headers.setIfNoneMatch("*")
        checkGeneration(isGenerationCurrent)
        val response = request.execute()
        return response.withResponse {
            rejectUnexpectedRedirect(it.statusCode, "Drive resumable session start")
            if (it.statusCode == 412) throw DriveAssetTransferException("immutable Drive asset already exists")
            if (it.statusCode !in 200..299) throw DriveAssetTransferException("Drive resumable session start failed: ${it.statusCode}")
            validateSessionUrl(it.headers.location ?: throw DriveAssetTransferException("Drive did not return a resumable Location"))
        }
    }

    private fun putChunk(
        sessionUrl: String,
        mimeType: String,
        bytes: ByteArray,
        count: Int,
        start: Long,
        total: Long,
        isGenerationCurrent: () -> Boolean
    ): ChunkResponse {
        val end = start + count - 1L
        require(start >= 0L && end < total) { "chunk range is outside asset" }
        if (end + 1L < total) require(count % RESUMABLE_GRANULARITY_BYTES == 0) {
            "non-final resumable chunk is not a 256 KiB multiple"
        }
        val request = configureRequest(requestFactory.buildPutRequest(
            GenericUrl(sessionUrl),
            ByteArrayContent(mimeType, bytes, 0, count)
        ))
        request.headers.setContentRange("bytes $start-$end/$total")
        request.headers.setContentLength(count.toLong())
        checkGeneration(isGenerationCurrent)
        val response = request.execute()
        return response.withResponse {
            rejectUnexpectedRedirect(it.statusCode, "Drive asset chunk upload")
            when (it.statusCode) {
                200, 201 -> ChunkResponse(total, true)
                308 -> ChunkResponse(parseAcknowledgedRange(it.headers.getFirstHeaderStringValue("Range"), total), false)
                404 -> throw DriveAssetSessionExpiredException("Drive resumable session expired")
                else -> throw DriveAssetTransferException("Drive asset chunk upload failed: ${it.statusCode}")
            }
        }
    }

    private fun queryAcknowledged(sessionUrl: String, total: Long): ChunkResponse {
        val request = configureRequest(requestFactory.buildPutRequest(GenericUrl(validateSessionUrl(sessionUrl)), EmptyContent()))
        request.headers.setContentRange("bytes */$total")
        request.headers.setContentLength(0L)
        val response = request.execute()
        return response.withResponse {
            rejectUnexpectedRedirect(it.statusCode, "Drive resumable status query")
            when (it.statusCode) {
                200, 201 -> ChunkResponse(total, true)
                308 -> ChunkResponse(parseAcknowledgedRange(it.headers.getFirstHeaderStringValue("Range"), total), false)
                404 -> throw DriveAssetSessionExpiredException("Drive resumable session expired")
                else -> throw DriveAssetTransferException("Drive resumable status query failed: ${it.statusCode}")
            }
        }
    }

    private fun parseAcknowledgedRange(value: String?, total: Long): Long {
        if (value.isNullOrBlank()) return 0L
        val match = Regex("bytes=0-(\\d+)").matchEntire(value.trim())
            ?: throw DriveAssetTransferException("Drive returned an invalid resumable Range")
        val end = match.groupValues[1].toLongOrNull()
            ?: throw DriveAssetTransferException("Drive returned an invalid resumable Range")
        require(end in -1L until total) { "Drive resumable Range exceeds the asset" }
        return end + 1L
    }

    private fun verifyExistingAsset(
        scope: SyncScope,
        parentFolderId: String,
        sourceFingerprint: SourceFingerprint?,
        observed: RemoteAssetDescriptor,
        expected: PhotoDescriptor,
        stagingResolver: PhotoPathResolver
    ): RemoteAssetDescriptor? {
        val metadata = getAssetMetadata(observed.remoteAssetId) ?: return null
        return try {
            requireAssetScope(metadata, scope, parentFolderId, sourceFingerprint, observed)
            val digest = remoteDigest(metadata, expected.byteCount, expected.sha256, stagingResolver)
            if (digest.byteCount == expected.byteCount && digest.sha256 == expected.sha256) {
                RemoteAssetDescriptor(
                    observed.remoteAssetId,
                    expected.byteCount,
                    expected.sha256,
                    expected.mimeType,
                    expected.width,
                    expected.height
                )
            } else null
        } catch (_: DriveAssetTransferException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun verifyUploadedMetadata(
        scope: SyncScope,
        parentFolderId: String,
        sourceFingerprint: SourceFingerprint?,
        remoteAssetId: String,
        descriptor: PhotoDescriptor,
        stagingResolver: PhotoPathResolver
    ): File {
        val metadata = getAssetMetadata(remoteAssetId)
            ?: throw DriveAssetTransferException("Drive asset completion cannot be read back")
        val digest = try {
            requireAssetScope(metadata, scope, parentFolderId, sourceFingerprint, RemoteAssetDescriptor(
                remoteAssetId, descriptor.byteCount, descriptor.sha256,
                descriptor.mimeType, descriptor.width, descriptor.height
            ))
            remoteDigest(metadata, descriptor.byteCount, descriptor.sha256, stagingResolver)
        } catch (error: DriveAssetTransferException) {
            throw error
        }
        if (digest.byteCount != descriptor.byteCount || digest.sha256 != descriptor.sha256) {
            throw DriveAssetTransferException("Drive asset readback integrity mismatch")
        }
        return metadata
    }

    private fun remoteDigest(
        metadata: File,
        expectedSize: Long,
        expectedSha256: String,
        stagingResolver: PhotoPathResolver
    ): DigestResult {
        // Drive File is also a GenericJson map; the Kotlin `size` property
        // would resolve to the map entry count instead of the Drive byte size.
        val reportedSize = metadata.getSize()
        val reportedSha = metadata.sha256Checksum
        if (reportedSize != null && reportedSize != expectedSize) {
            throw DriveAssetTransferException("Drive asset size does not match descriptor")
        }
        if (!reportedSha.isNullOrBlank()) {
            val normalised = reportedSha.lowercase(Locale.ROOT)
            if (normalised != expectedSha256) throw DriveAssetTransferException("Drive asset checksum does not match descriptor")
            // A checksum without the output-only size field is not enough to
            // prove the declared byte count.  Fall through to a bounded media
            // read so both properties are verified against actual bytes.
            if (reportedSize != null) return DigestResult(reportedSize, normalised)
        }
        // sha256Checksum is output-only and may be absent.  Verify by a bounded
        // streamed media read instead of trusting an app property.
        val path = stagingTemporaryPath(stagingResolver, "drive-verify", ".bin")
        requireContained(path, stagingResolver.root.toPath(), stagingResolver, "asset verification staging")
        prepareStagingTemporary(path, stagingResolver)
        var published = false
        return try {
            val streamed = streamRemoteAsset(metadata, null, path, stagingResolver)
            published = streamed.published
            val digest = streamed.digest
            if (digest.byteCount != expectedSize || digest.sha256 != expectedSha256) {
                throw DriveAssetTransferException("Drive streamed asset checksum does not match descriptor")
            }
            digest
        } finally {
            if (published) deleteOwnedPartial(path, stagingResolver)
        }
    }

    private fun streamRemoteAsset(
        metadata: File,
        descriptor: RemoteAssetDescriptor?,
        target: Path,
        stagingResolver: PhotoPathResolver
    ): StreamedAsset {
        requireContained(target, stagingResolver.root.toPath(), stagingResolver, "asset staging")
        // Targets are allocated uniquely for this attempt. If an external or
        // overlapping operation occupies the random name, fail closed and
        // leave that file untouched rather than treating it as reusable.
        if (stagingResolver.exists(target)) {
            throw DriveAssetTransferException("asset staging target is already occupied")
        }
        val temporary = stagingTemporaryPath(stagingResolver, "drive-transfer", ".part")
        requireContained(temporary, stagingResolver.root.toPath(), stagingResolver, "asset staging temporary")
        prepareStagingTemporary(temporary, stagingResolver)
        var count = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        var published = false
        var temporaryCreated = false
        try {
            configureRequest(requestFactory.buildGetRequest(
                GenericUrl(apiBaseUrl + "files/" + encodePath(requireNotNull(metadata.id)))
                    .set("alt", "media")
                    .set("supportsAllDrives", true)
            )).execute().let { response ->
                try {
                rejectUnexpectedRedirect(response.statusCode, "Drive asset media read")
                if (!response.isSuccessStatusCode) throw DriveAssetTransferException("Drive asset media read failed: ${response.statusCode}")
                stagingResolver.openNewOutput(temporary, "Drive asset media staging").use { channel ->
                    temporaryCreated = true
                    response.content.use { input ->
                        val buffer = ByteArray(STREAM_BUFFER_BYTES)
                        var zeroReads = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) {
                                zeroReads++
                                if (zeroReads > Stage5Limits.MAX_ZERO_READS) {
                                    throw DriveAssetTransferException("Drive asset media made no progress")
                                }
                                continue
                            }
                            zeroReads = 0
                            count += read
                            if (count > Stage5Limits.MAX_PHOTO_BYTES) throw DriveAssetTransferException("Drive asset exceeds size limit")
                            digest.update(buffer, 0, read)
                            channel.write(java.nio.ByteBuffer.wrap(buffer, 0, read))
                        }
                    }
                    channel.force(true)
                }
                } finally {
                    try { response.disconnect() } catch (_: IOException) { }
                }
            }
            if (descriptor != null && count != descriptor.byteCount) throw DriveAssetTransferException("Drive asset size changed during read")
            val result = DigestResult(count, digest.digest().toHex())
            if (descriptor != null && result.sha256 != descriptor.sha256) throw DriveAssetTransferException("Drive asset hash changed during read")
            stagingResolver.atomicMove(temporary, target, replaceExisting = false)
            published = true
            forceStagingDirectory(stagingResolver)
            val readBack = readStagedDigest(target, descriptor, stagingResolver)
            if (readBack != result) throw DriveAssetTransferException("Drive asset staging read-back differs")
            return StreamedAsset(result, published = true)
        } catch (cancelled: CancellationException) {
            if (temporaryCreated) deleteOwnedPartial(temporary, stagingResolver)
            if (published) deleteOwnedPartial(target, stagingResolver)
            throw cancelled
        } catch (error: Throwable) {
            if (!published && temporaryCreated) deleteOwnedPartial(temporary, stagingResolver)
            if (published) deleteOwnedPartial(target, stagingResolver)
            if (error is DriveAssetTransferException) throw error
            throw DriveAssetTransferException("Drive asset media transfer failed", error)
        }
    }

    private fun readStagedDigest(
        path: Path,
        descriptor: RemoteAssetDescriptor?,
        stagingResolver: PhotoPathResolver
    ): DigestResult {
        requireContained(path, stagingResolver.root.toPath(), stagingResolver, "asset staging read-back")
        val expected = descriptor?.byteCount
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        stagingResolver.openRead(path, "asset staging read-back").use { input ->
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            var zeroReads = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) {
                    zeroReads++
                    if (zeroReads > Stage5Limits.MAX_ZERO_READS) {
                        throw DriveAssetTransferException("staged asset read-back made no progress")
                    }
                    continue
                }
                zeroReads = 0
                count += read
                if (count > Stage5Limits.MAX_PHOTO_BYTES || (expected != null && count > expected)) {
                    throw DriveAssetTransferException("staged asset read-back exceeds its descriptor")
                }
                digest.update(buffer, 0, read)
            }
        }
        val result = DigestResult(count, digest.digest().toHex())
        if (descriptor != null &&
            (result.byteCount != descriptor.byteCount || result.sha256 != descriptor.sha256)
        ) {
            throw DriveAssetTransferException("staged asset read-back does not match its descriptor")
        }
        return result
    }

    private fun generateStableId(
        scope: SyncScope,
        snapshotDigest: String,
        hash: String,
        isGenerationCurrent: () -> Boolean
    ): String = requestGeneratedId(isGenerationCurrent)

    private fun requestGeneratedId(isGenerationCurrent: () -> Boolean): String {
        checkGeneration(isGenerationCurrent)
        val url = GenericUrl(apiBaseUrl + "files/generateIds")
            .set("count", 1)
            .set("space", "drive")
            .set("type", "files")
            .set("fields", "ids")
        val response = configureRequest(requestFactory.buildGetRequest(url)).execute()
        return response.withResponse {
            rejectUnexpectedRedirect(it.statusCode, "Drive ID generation")
            if (!it.isSuccessStatusCode) throw DriveAssetTransferException("Drive ID generation failed: ${it.statusCode}")
            val body = it.content?.use { input -> readBoundedUtf8(input, 16 * 1024) }
                ?: throw DriveAssetTransferException("Drive ID generation response has no body")
            val root = try {
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                validateNoDuplicateJsonMembers(bytes, "Drive ID generation response")
                JsonParser.parseString(body)
            } catch (error: Throwable) {
                if (error is DriveAssetTransferException) throw error
                throw DriveAssetTransferException("Drive ID generation response is invalid", error)
            }
            if (!root.isJsonObject || root.asJsonObject.keySet() != setOf("ids")) {
                throw DriveAssetTransferException("Drive ID generation response fields are invalid")
            }
            val ids = root.asJsonObject["ids"]
            if (ids == null || !ids.isJsonArray) {
                throw DriveAssetTransferException("Drive ID generation response IDs are invalid")
            }
            val id = ids.asJsonArray.singleOrNull()?.takeIf { value ->
                value.isJsonPrimitive && value.asJsonPrimitive.isString &&
                    value.asString.matches(Regex("[A-Za-z0-9_-]{1,512}"))
            }?.asString ?: throw DriveAssetTransferException("Drive did not return exactly one stable asset ID")
            checkGeneration(isGenerationCurrent)
            id
        }
    }

    private fun getAssetMetadata(id: String): File? {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,512}"))) { "remote asset ID is invalid" }
        val url = GenericUrl(apiBaseUrl + "files/" + encodePath(id))
            .set("supportsAllDrives", true)
            .set("fields", "id,name,mimeType,size,sha256Checksum,parents,appProperties")
        val response = configureRequest(requestFactory.buildGetRequest(url)).execute()
        return response.withResponse {
            if (it.statusCode == 404) {
                null
            } else {
                rejectUnexpectedRedirect(it.statusCode, "Drive asset metadata read")
                if (!it.isSuccessStatusCode) throw DriveAssetTransferException("Drive asset metadata read failed: ${it.statusCode}")
                try {
                    parseDriveFileMetadata(it)
                } catch (error: Throwable) {
                    throw DriveAssetTransferException("Drive asset metadata is malformed", error)
                }
            }
        }
    }

    /**
     * Google GenericJson's Gson adapter treats unknown numeric map members as
     * Doubles on some client versions, which cannot be assigned to File.size.
     * Decode the bounded, requested response into the typed setters instead.
     */
    private fun parseDriveFileMetadata(response: HttpResponse): File {
        val body = response.content?.use { input -> readBoundedUtf8(input, 256 * 1024) }
            ?: throw DriveAssetTransferException("Drive asset metadata has no body")
        validateNoDuplicateJsonMembers(body.toByteArray(StandardCharsets.UTF_8), "Drive asset metadata")
        val root = JsonParser.parseString(body)
        if (!root.isJsonObject) throw DriveAssetTransferException("Drive asset metadata root is invalid")
        val json = root.asJsonObject
        fun string(name: String, required: Boolean = false): String? {
            val value = json[name] ?: if (required) throw DriveAssetTransferException("Drive asset metadata field is missing: $name") else return null
            if (value.isJsonNull) {
                if (required) throw DriveAssetTransferException("Drive asset metadata field is missing: $name")
                return null
            }
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString.isEmpty()) {
                throw DriveAssetTransferException("Drive asset metadata field is invalid: $name")
            }
            return value.asString
        }
        // Drive v3 encodes int64 fields as decimal JSON strings, not numbers.
        // Keep strict grammar/range validation rather than coercing arbitrary JSON.
        fun int64String(name: String): Long? {
            val value = json[name] ?: return null
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString ||
                !value.asString.matches(Regex("(0|[1-9][0-9]*)"))
            ) throw DriveAssetTransferException("Drive asset metadata field is invalid: $name")
            return value.asString.toLongOrNull()
                ?: throw DriveAssetTransferException("Drive asset metadata field is outside its limit: $name")
        }
        val file = File()
            .setId(string("id", required = true))
            .setName(string("name", required = true))
            .setMimeType(string("mimeType", required = true))
        int64String("size")?.let { size ->
            if (size < 0L) throw DriveAssetTransferException("Drive asset metadata size is invalid")
            file.setSize(size)
        }
        string("sha256Checksum")?.let(file::setSha256Checksum)
        val parents = json["parents"]
        if (parents != null && !parents.isJsonNull) {
            if (!parents.isJsonArray) throw DriveAssetTransferException("Drive asset metadata parents are invalid")
            file.setParents(ArrayList<String>(parents.asJsonArray.size()).also { list ->
                parents.asJsonArray.forEach { value ->
                    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString.isEmpty()) {
                        throw DriveAssetTransferException("Drive asset metadata parent is invalid")
                    }
                    list += value.asString
                }
            })
        }
        val properties = json["appProperties"]
        if (properties != null && !properties.isJsonNull) {
            if (!properties.isJsonObject) throw DriveAssetTransferException("Drive asset metadata properties are invalid")
            file.setAppProperties(LinkedHashMap<String, String>().also { map ->
                properties.asJsonObject.entrySet().forEach { (key, value) ->
                    if (!key.matches(Regex("[A-Za-z0-9_.-]{1,128}")) ||
                        !value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString.length > Stage5Limits.MAX_STRING_CHARS
                    ) throw DriveAssetTransferException("Drive asset metadata property is invalid")
                    map[key] = value.asString
                }
            })
        }
        return file
    }

    private fun readBoundedUtf8(input: InputStream, maxBytes: Int): String {
        require(maxBytes > 0) { "metadata bound is invalid" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var zeroReads = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) {
                zeroReads++
                if (zeroReads > Stage5Limits.MAX_ZERO_READS) {
                    throw DriveAssetTransferException("Drive asset metadata made no progress")
                }
                continue
            }
            zeroReads = 0
            if (output.size() > maxBytes - read) throw DriveAssetTransferException("Drive asset metadata exceeds its limit")
            output.write(buffer, 0, read)
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(output.toByteArray()))
                .toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw DriveAssetTransferException("Drive asset metadata is not valid UTF-8", error)
        }
    }

    private fun requireAssetScope(
        metadata: File,
        scope: SyncScope,
        parentFolderId: String,
        sourceFingerprint: SourceFingerprint?,
        descriptor: RemoteAssetDescriptor
    ) {
        if (metadata.id != descriptor.remoteAssetId) throw DriveAssetTransferException("Drive asset ID changed")
        if (metadata.parents.orEmpty() != listOf(parentFolderId)) {
            throw DriveAssetTransferException("Drive asset is outside its document folder")
        }
        val properties = metadata.appProperties.orEmpty()
        if (properties[PROP_ACCOUNT_ID] != scope.accountId) throw DriveAssetTransferException("Drive asset account scope mismatch")
        if (properties[PROP_BACKUP_ROOT_ID] != scope.backupRootId) throw DriveAssetTransferException("Drive asset root scope mismatch")
        if (properties[PROP_DOCUMENT_ID] != scope.documentId.value) throw DriveAssetTransferException("Drive asset document scope mismatch")
        if (properties[PROP_MANIFEST_SCHEMA] != DRIVE_MANIFEST_SCHEMA_VERSION.toString()) throw DriveAssetTransferException("Drive asset manifest scope mismatch")
        if (properties[PROP_IMMUTABLE] != "1") throw DriveAssetTransferException("Drive asset is not marked immutable")
        if (properties[PROP_ASSET_SHA256]?.lowercase(Locale.ROOT) != descriptor.sha256) throw DriveAssetTransferException("Drive asset hash property mismatch")
        if (metadata.name != assetFileName(descriptor.asPhotoDescriptor())) {
            throw DriveAssetTransferException("Drive asset filename does not match its content identity")
        }
        if (metadata.mimeType != descriptor.mimeType) {
            throw DriveAssetTransferException("Drive asset MIME type does not match its descriptor")
        }
        if (sourceFingerprint != null) {
            if (properties[PROP_SOURCE_FINGERPRINT] != sourceFingerprint.toWireValue()) {
                throw DriveAssetTransferException("Drive asset source scope mismatch")
            }
        } else if (properties[PROP_SOURCE_FINGERPRINT] != null) {
            throw DriveAssetTransferException("Drive asset carries an unexpected source scope")
        }
    }

    private fun assetProperties(
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?,
        descriptor: PhotoDescriptor
    ): Map<String, String> = buildMap {
        put(PROP_ACCOUNT_ID, scope.accountId)
        put(PROP_BACKUP_ROOT_ID, scope.backupRootId)
        put(PROP_DOCUMENT_ID, scope.documentId.value)
        put(PROP_MANIFEST_SCHEMA, DRIVE_MANIFEST_SCHEMA_VERSION.toString())
        put(PROP_ASSET_SHA256, descriptor.sha256)
        put(PROP_IMMUTABLE, "1")
        sourceFingerprint?.let { put(PROP_SOURCE_FINGERPRINT, it.toWireValue()) }
    }

    private fun validateIdentity(scope: SyncScope, snapshotDigest: String, parentFolderId: String) {
        validateScope(scope, parentFolderId)
        require(snapshotDigest.matches(SHA256_REGEX)) { "snapshotDigest is invalid" }
    }

    private fun validateScope(scope: SyncScope, parentFolderId: String) {
        require(scope.accountId == accountId) { "gateway account does not match SyncScope" }
        require(scope.accountId.length in 1..Stage5Limits.MAX_STRING_CHARS &&
            scope.accountId.none { it.code < 0x20 || it.code == 0x7f }) {
            "account identifier is invalid"
        }
        require(scope.backupRootId.matches(Regex("[A-Za-z0-9_-]{1,512}"))) {
            "backup root identifier is invalid"
        }
        require(parentFolderId.matches(Regex("[A-Za-z0-9_-]{1,512}"))) { "parentFolderId is invalid" }
    }

    private fun checkGeneration(isGenerationCurrent: () -> Boolean) {
        if (!isGenerationCurrent()) throw DriveAssetStaleGenerationException()
    }

    private fun validateSessionUrl(url: String): String {
        if (url.length !in 1..MAX_SESSION_URL_LENGTH) throw DriveAssetTransferException("resumable URL is oversized")
        val uri = try { java.net.URI(url) } catch (error: Exception) {
            throw DriveAssetTransferException("resumable URL is malformed", error)
        }
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (!uri.scheme.equals("https", true) || uri.userInfo != null || uri.fragment != null ||
            (uri.port != -1 && uri.port != 443) || !(host == PROVIDER_HOST || host.endsWith(PROVIDER_HOST_SUFFIX)) ||
            !uri.path.startsWith(UPLOAD_PATH_PREFIX) ||
            uri.path.split('/').any { it == "." || it == ".." }
        ) throw DriveAssetTransferException("resumable URL is not an allowed Google Drive endpoint")
        return uri.toString()
    }

    private fun stagingPath(mimeType: String, stagingResolver: PhotoPathResolver): Path {
        val suffix = when (mimeType) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
        // Do not derive a shared filename from the remote identity. The
        // resolver's UUID-backed internal handle is unique per attempt and
        // remains anchored to the opened staging root.
        return uniqueStagingPath(stagingResolver, "drive-asset", suffix)
    }

    /** Unique, bounded scratch name owned by one transfer attempt. */
    private fun stagingTemporaryPath(
        stagingResolver: PhotoPathResolver,
        kind: String,
        extension: String
    ): Path = uniqueStagingPath(stagingResolver, kind, extension)

    /**
     * Reserve a fresh path without sweeping an existing file. UUID allocation
     * is deliberately checked through the anchored resolver; a collision is a
     * fail-closed condition rather than permission to delete or reuse bytes
     * owned by another task.
     */
    private fun uniqueStagingPath(
        stagingResolver: PhotoPathResolver,
        kind: String,
        extension: String
    ): Path {
        val path = stagingResolver.newInternalFile(kind, extension).toPath()
        requireContained(path, stagingResolver.root.toPath(), stagingResolver, "asset staging")
        if (stagingResolver.exists(path)) {
            throw DriveAssetTransferException("asset staging path is already occupied")
        }
        return path
    }

    /** Verify that this transfer's newly allocated partial path is unused. */
    private fun prepareStagingTemporary(path: Path, stagingResolver: PhotoPathResolver) {
        requireContained(path, stagingResolver.root.toPath(), stagingResolver, "asset staging temporary")
        if (stagingResolver.exists(path)) {
            throw DriveAssetTransferException("asset staging temporary is already occupied")
        }
    }

    private fun requireContained(
        path: Path,
        root: Path,
        stagingResolver: PhotoPathResolver,
        label: String
    ) {
        val expectedRoot = stagingResolver.root.toPath().toAbsolutePath().normalize()
        require(root.toAbsolutePath().normalize() == expectedRoot) { "$label uses an unknown root" }
        stagingResolver.ensureContained(path, label)
    }

    private fun deleteOwnedPartial(path: Path, stagingResolver: PhotoPathResolver) {
        try {
            requireContained(path, stagingResolver.root.toPath(), stagingResolver, "asset cleanup")
            if (stagingResolver.exists(path)) {
                stagingResolver.deletePath(path, "asset cleanup")
                forceStagingDirectory(stagingResolver)
            }
        } catch (_: Throwable) {
            // Cleanup failure cannot turn a validated transfer into success;
            // state remains durable for a later bounded cleanup pass.
        }
    }

    /** Directory durability fence for the already-open, trusted staging root. */
    private fun forceStagingDirectory(stagingResolver: PhotoPathResolver) {
        directoryForce?.let {
            it()
            return
        }
        try {
            FileChannel.open(stagingResolver.root.toPath(), StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        } catch (error: IOException) {
            throw DriveAssetTransferException("Drive asset staging directory durability is unavailable", error)
        } catch (error: SecurityException) {
            throw DriveAssetTransferException("Drive asset staging directory durability is protected", error)
        } catch (error: UnsupportedOperationException) {
            throw DriveAssetTransferException("Drive asset staging directory durability is unsupported", error)
        }
    }

    private fun configureRequest(request: HttpRequest): HttpRequest = request.apply {
        numberOfRetries = 0
        throwExceptionOnExecuteError = false
        retryOnExecuteIOException = false
        followRedirects = false
        connectTimeout = DRIVE_HTTP_TIMEOUT_MILLIS
        readTimeout = DRIVE_HTTP_TIMEOUT_MILLIS
        writeTimeout = DRIVE_HTTP_TIMEOUT_MILLIS
    }

    private fun rejectUnexpectedRedirect(statusCode: Int, operation: String) {
        if (statusCode in 300..399 && statusCode != 308) {
            throw DriveAssetTransferException("$operation returned an unexpected redirect")
        }
    }

    private fun validateHttpsBase(url: String, requiredPath: String) {
        val uri = java.net.URI(url)
        require(uri.scheme.equals("https", true)) { "Drive transport endpoints must use HTTPS" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Drive transport endpoint contains unsafe URI components"
        }
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        require(host == PROVIDER_HOST || host.endsWith(PROVIDER_HOST_SUFFIX)) {
            "Drive transport endpoint is not a Google provider"
        }
        require(uri.port == -1 || uri.port == 443) { "Drive transport endpoint has an unsafe port" }
        require(uri.path.endsWith(requiredPath)) { "Drive transport endpoint has an unexpected path" }
    }

    private fun requireSafeName(name: String) {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.(?i:jpg|jpeg|png|webp)"))) {
            "remote asset occurrence filename is unsafe"
        }
    }

    private fun checkDescriptorBudget(descriptor: RemoteAssetDescriptor) {
        require(descriptor.byteCount in 1L..Stage5Limits.MAX_PHOTO_BYTES.toLong())
        require(descriptor.sha256.matches(SHA256_REGEX))
    }

    private fun assetFileName(descriptor: PhotoDescriptor): String =
        "sotaware-asset-${descriptor.sha256}" + when (descriptor.mimeType) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }

    private fun skipExactly(input: InputStream, bytes: Long) {
        if (bytes <= 0L) return
        var remaining = bytes
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var zeroReads = 0
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw DriveAssetTransferException("asset ended while resuming acknowledged range")
            if (read == 0) {
                zeroReads++
                if (zeroReads > 16) throw DriveAssetTransferException("asset input made no progress while resuming")
                continue
            }
            zeroReads = 0
            remaining -= read
        }
    }

    private fun readChunk(input: InputStream, target: ByteArray, requested: Int): Int {
        var offset = 0
        var zeroReads = 0
        while (offset < requested) {
            val read = input.read(target, offset, requested - offset)
            if (read < 0) break
            if (read == 0) {
                zeroReads++
                if (zeroReads > 16) throw DriveAssetTransferException("asset input made no progress")
                continue
            }
            zeroReads = 0
            offset += read
        }
        return offset
    }

    private fun validatePhotoAssetsForDescriptors(set: PhotoAssetSet, descriptors: Map<String, PhotoDescriptor>) {
        // The shared validator needs a canonical snapshot to check occurrence
        // references.  Descriptor equality and bounded stream verification are
        // still performed here when a low-level download caller has no snapshot.
        set.forEach { (name, asset) ->
            require(descriptors[name] == asset.descriptor) { "staged descriptor changed: $name" }
            asset.open().use { input ->
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                var zeroReads = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) {
                        zeroReads++
                        if (zeroReads > Stage5Limits.MAX_ZERO_READS) {
                            throw DriveAssetTransferException("staged asset input made no progress")
                        }
                        continue
                    }
                    zeroReads = 0
                    count += read
                    if (count > asset.descriptor.byteCount) throw DriveAssetTransferException("staged asset exceeds descriptor")
                    digest.update(buffer, 0, read)
                }
                require(count == asset.descriptor.byteCount && digest.digest().toHex() == asset.descriptor.sha256) {
                    "staged asset integrity changed"
                }
            }
        }
    }

    private fun validateSetWithoutSnapshot(assets: PhotoAssetSet): PhotoAssetSet {
        if (assets.isEmpty()) return PhotoAssetSet.EMPTY
        if (assets.size > Stage5Limits.MAX_REMOTE_DESCRIPTOR_COUNT) {
            throw DriveAssetTransferException("photo asset count exceeds its limit")
        }
        var total = 0L
        assets.forEach { (name, asset) ->
            requireSafeName(name)
            val descriptor = asset.descriptor
            if (descriptor.byteCount !in 1L..Stage5Limits.MAX_PHOTO_BYTES.toLong() ||
                !descriptor.sha256.matches(Regex("[0-9a-f]{64}"))) {
                throw DriveAssetTransferException("photo asset descriptor is outside its limit: $name")
            }
            total = try { Math.addExact(total, descriptor.byteCount) }
            catch (_: ArithmeticException) { throw DriveAssetTransferException("photo asset bytes overflow") }
            require(total <= Stage5Limits.MAX_TOTAL_PHOTO_BYTES) { "photo assets exceed aggregate limit" }
            asset.open().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                var count = 0L
                var zeroReads = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) {
                        zeroReads++
                        if (zeroReads > Stage5Limits.MAX_ZERO_READS) {
                            throw DriveAssetTransferException("photo asset input made no progress")
                        }
                        continue
                    }
                    zeroReads = 0
                    count += read
                    if (count > descriptor.byteCount) throw DriveAssetTransferException("photo asset exceeds descriptor")
                    digest.update(buffer, 0, read)
                }
                if (count != descriptor.byteCount || digest.digest().toHex() != descriptor.sha256) {
                    throw DriveAssetTransferException("photo asset descriptor does not match immutable bytes: $name")
                }
            }
        }
        return assets
    }

    private data class ChunkResponse(val acknowledgedBytes: Long, val complete: Boolean)
    private data class DigestResult(val byteCount: Long, val sha256: String)
    private data class StreamedAsset(val digest: DigestResult, val published: Boolean)

    /**
     * Explicit owner for downloaded staging files.  A stream holds a counted
     * lease; discard marks the owner released but defers deletion until the
     * final stream closes (or reaches EOF).
     */
    private class DownloadedAssetLease(
        private val resolver: PhotoPathResolver,
        private val paths: Map<String, Path>,
        ownedPaths: Set<Path>,
        private val descriptors: Map<String, PhotoDescriptor>,
        private val forceDirectory: () -> Unit,
        private val onReleased: (RemoteDownloadOwnership) -> Unit
    ) : RemoteDownloadOwnership {
        private val lock = Any()
        private val owned = ownedPaths.map { it.toAbsolutePath().normalize() }.toSet()
        private var activeStreams = 0
        private var discardRequested = false
        private var released = false

        override val isReleased: Boolean
            get() = synchronized(lock) { released || discardRequested }

        fun open(name: String): InputStream = synchronized(lock) {
            if (released || discardRequested) throw IOException("downloaded asset owner has been released")
            val path = paths[name] ?: throw IOException("downloaded asset is unavailable: $name")
            val descriptor = descriptors[name] ?: throw IOException("downloaded asset descriptor is unavailable: $name")
            resolver.ensureContained(path, "downloaded asset")
            if (!resolver.isRegularFile(path)) throw IOException("downloaded asset is not a regular file: $name")
            if (resolver.size(path, "downloaded asset") != descriptor.byteCount) {
                throw IOException("downloaded asset size changed: $name")
            }
            activeStreams++
            try {
                CountingInputStream(
                    resolver.openRead(path, "downloaded asset $name"),
                    ::streamClosed
                )
            } catch (error: Throwable) {
                activeStreams--
                throw error
            }
        }

        override fun release() {
            synchronized(lock) {
                discardRequested = true
                if (activeStreams == 0) discardLocked()
            }
        }

        private fun streamClosed() {
            synchronized(lock) {
                if (activeStreams > 0) activeStreams--
                if (discardRequested && activeStreams == 0) discardLocked()
            }
        }

        private fun discardLocked() {
            if (released) return
            released = true
            try {
                owned.forEach { path ->
                    try {
                        resolver.ensureContained(path, "downloaded asset cleanup")
                        if (resolver.exists(path)) resolver.deletePath(path, "downloaded asset cleanup")
                    } catch (_: Throwable) {
                        // The ownership decision remains released; an inability
                        // to delete is retained as conservative evidence.
                    }
                }
                try {
                    forceDirectory()
                } catch (_: Throwable) {
                    // Directory durability failure cannot make an owner unsafe;
                    // the files remain conservative evidence for a later pass.
                }
            } finally {
                // The resolver is an attempt owner, not a transfer singleton.
                // It is closed only after release and after the final counted
                // stream has closed or reached EOF.
                try {
                    resolver.close()
                } catch (_: Throwable) {
                }
                onReleased(this)
            }
        }

        private class CountingInputStream(
            delegate: InputStream,
            private val onClose: () -> Unit
        ) : FilterInputStream(delegate) {
            private val closed = AtomicBoolean(false)

            override fun read(): Int {
                val value = super.read()
                if (value < 0) finish(closeDelegate = true)
                return value
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val value = super.read(buffer, offset, length)
                if (value < 0) finish(closeDelegate = true)
                return value
            }

            override fun close() {
                try {
                    super.close()
                } finally {
                    finish(closeDelegate = false)
                }
            }

            private fun finish(closeDelegate: Boolean) {
                if (closed.compareAndSet(false, true)) {
                    if (closeDelegate) {
                        try {
                            super.close()
                        } finally {
                            onClose()
                        }
                    } else {
                        onClose()
                    }
                }
            }
        }
    }

    private fun downloadRequestPrefix(
        scope: SyncScope,
        descriptors: Map<String, RemoteAssetDescriptor>
    ): String {
        val descriptorKey = descriptors.toSortedMap().entries.joinToString("\u0001") { (name, descriptor) ->
            "$name\u0002${descriptor.remoteAssetId}\u0002${descriptor.byteCount}\u0002${descriptor.sha256}"
        }
        return listOf(scope.accountId, scope.backupRootId, scope.documentId.value, descriptorKey)
            .joinToString("\u0000")
    }

    private fun downloadRequestKey(
        scope: SyncScope,
        parentFolderId: String,
        descriptors: Map<String, RemoteAssetDescriptor>
    ): String = downloadRequestPrefix(scope, descriptors) + "\u0000" + parentFolderId

    private fun unregisterDownloadOwner(owner: RemoteDownloadOwnership) {
        // Retain the exact set -> released owner identity so a repeated
        // discard remains harmless. Only the request-indexed compatibility
        // bookkeeping is removed after release.
        synchronized(downloadOwnersByRequest) {
            val emptyKeys = mutableListOf<String>()
            downloadOwnersByRequest.forEach { (key, owners) ->
                owners.removeIf { it === owner }
                if (owners.isEmpty()) emptyKeys += key
            }
            emptyKeys.forEach(downloadOwnersByRequest::remove)
        }
    }

    private fun SourceFingerprint.toWireValue(): String =
        "${SourceFingerprint.SHA256_ALGORITHM}:${digestHex.lowercase(Locale.ROOT)}:${byteCount}"
}

/** Durable, atomically replaced state for an account/root/document operation. */
internal class DriveTransferStateStore(
    stateDirectory: Path,
    private val accountId: String,
    private val nowMillis: () -> Long,
    private val gson: Gson,
    trustedRootDirectory: Path? = null,
    operationsFactory: PhotoPathOperationsFactory? = null,
    directoryForce: (() -> Unit)? = null
) : AutoCloseable {
    private val lock = Any()
    private val storage = DurableDriveTransferStorage(
        directory = stateDirectory,
        nowMillis = nowMillis,
        gson = gson,
        trustedRootDirectory = trustedRootDirectory,
        operationsFactory = operationsFactory,
        directoryForce = directoryForce
    )

    fun identity(
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?,
        snapshotDigest: String,
        parentFolderId: String
    ): TransferIdentity {
        require(scope.accountId == accountId) { "state account does not match SyncScope" }
        require(scope.accountId.length <= Stage5Limits.MAX_STRING_CHARS &&
            scope.accountId.none { it.code < 0x20 || it.code == 0x7f }) {
            "state account identifier is invalid"
        }
        require(scope.backupRootId.matches(Regex("[A-Za-z0-9_-]{1,512}"))) {
            "state backup root identifier is invalid"
        }
        require(parentFolderId.matches(Regex("[A-Za-z0-9_-]{1,512}"))) {
            "state parent folder identifier is invalid"
        }
        require(snapshotDigest.matches(Regex("[0-9a-f]{64}"))) { "state snapshot digest is invalid" }
        return TransferIdentity(
            accountId = scope.accountId,
            backupRootId = scope.backupRootId,
            documentId = scope.documentId.value,
            sourceFingerprint = sourceFingerprint?.toWireValue(),
            snapshotDigest = snapshotDigest,
            parentFolderId = parentFolderId
        )
    }

    fun load(identity: TransferIdentity): PersistedTransferState? = synchronized(lock) {
        val bytes = storage.read(storageKey(identity), DRIVE_TRANSFER_MAX_STATE_BYTES)
            ?: return@synchronized null
        val value = try {
            validateNoDuplicateJsonMembers(bytes, "Drive transfer state")
            val root = JsonParser.parseString(decodeUtf8(bytes))
            validateStateTree(root)
            gson.fromJson(root, PersistedTransferState::class.java)
        } catch (error: Throwable) {
            if (error is DriveAssetTransferException) throw error
            throw DriveAssetTransferException("Drive transfer state is malformed", error)
        }
        if (value == null || value.identity != identity) throw DriveAssetTransferException("Drive transfer state scope mismatch")
        if (value.updatedAtMillis < 0L) throw DriveAssetTransferException("Drive transfer state timestamp is invalid")
        val assets = value.assets
            ?: throw DriveAssetTransferException("Drive transfer state assets are missing")
        if (assets.size > Stage5Limits.MAX_REMOTE_DESCRIPTOR_COUNT) {
            throw DriveAssetTransferException("Drive transfer state asset count exceeds its limit")
        }
        try {
            assets.forEach { (hash, asset) -> validateAsset(hash, asset) }
        } catch (error: DriveAssetTransferException) {
            throw error
        } catch (error: Throwable) {
            throw DriveAssetTransferException("Drive transfer state asset is invalid", error)
        }
        value
    }

    fun putAsset(identity: TransferIdentity, hash: String, state: PersistedAssetState) = synchronized(lock) {
        validateAsset(hash, state)
        val current = load(identity) ?: PersistedTransferState(identity, LinkedHashMap(), nowMillis())
        val assets = LinkedHashMap(current.assets)
        assets[hash] = state
        if (assets.size > Stage5Limits.MAX_REMOTE_DESCRIPTOR_COUNT) {
            throw DriveAssetTransferException("Drive transfer state asset count exceeds its limit")
        }
        write(PersistedTransferState(identity, assets, nowMillis()))
    }

    fun updateAcknowledged(
        identity: TransferIdentity,
        hash: String,
        byteCount: Long,
        sessionUrl: String?,
        acknowledged: Long
    ) = synchronized(lock) {
        val current = load(identity) ?: throw DriveAssetTransferException("Drive asset state is missing")
        if (sessionUrl != null) validateSessionUrl(sessionUrl)
        require(acknowledged in 0L..byteCount) { "acknowledged range is invalid" }
        val prior = current.assets[hash] ?: throw DriveAssetTransferException("Drive asset state is missing")
        validateAsset(hash, prior)
        val assets = LinkedHashMap(current.assets)
        assets[hash] = prior.copy(sessionUrl = sessionUrl, acknowledgedBytes = acknowledged)
        write(PersistedTransferState(identity, assets, nowMillis()))
    }

    fun delete(identity: TransferIdentity) = synchronized(lock) {
        storage.delete(storageKey(identity))
    }

    fun reserveResourceId(
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?,
        parentFolderId: String,
        resourceKind: String,
        isGenerationCurrent: () -> Boolean,
        generate: () -> String
    ): String {
        require(scope.accountId == accountId) { "state account does not match SyncScope" }
        return storage.reserveResourceId(
            DurableDriveResourceIdentity(
                scope = scope,
                sourceFingerprint = sourceFingerprint?.toWireValue(),
                parentFolderId = parentFolderId,
                resourceKind = resourceKind
            ),
            isGenerationCurrent,
            generate
        )
    }

    override fun close() {
        storage.close()
    }

    private fun storageKey(identity: TransferIdentity): String {
        val key = sha256Hex(gson.toJson(identity).toByteArray(StandardCharsets.UTF_8))
        return "transfer-$key"
    }

    private fun write(value: PersistedTransferState) {
        if (value.assets.size > Stage5Limits.MAX_REMOTE_DESCRIPTOR_COUNT) {
            throw DriveAssetTransferException("Drive transfer state asset count exceeds its limit")
        }
        val bytes = gson.toJson(value).toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > DRIVE_TRANSFER_MAX_STATE_BYTES) throw DriveAssetTransferException("Drive transfer state exceeds its limit")
        if (value.updatedAtMillis < 0L) throw DriveAssetTransferException("Drive transfer state timestamp is invalid")
        storage.write(storageKey(value.identity), bytes, DRIVE_TRANSFER_MAX_STATE_BYTES)
    }

    private fun validateSessionUrl(url: String) {
        if (url.length !in 1..DriveImmutableAssetTransfer.MAX_SESSION_URL_LENGTH) {
            throw IllegalArgumentException("state resumable URL is oversized")
        }
        val uri = try { java.net.URI(url) } catch (error: Exception) {
            throw IllegalArgumentException("state resumable URL is malformed", error)
        }
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        require(uri.scheme.equals("https", true) && uri.userInfo == null && uri.fragment == null &&
            (uri.port == -1 || uri.port == 443) && (host == "googleapis.com" || host.endsWith(".googleapis.com")) &&
            uri.path.startsWith("/upload/drive/v3/") &&
            uri.path.split('/').none { it == "." || it == ".." }) { "state resumable URL is not a provider endpoint" }
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw DriveAssetTransferException("Drive transfer state is not valid UTF-8", error)
    }

    private fun validateStateTree(root: JsonElement) {
        if (!root.isJsonObject) throw DriveAssetTransferException("Drive transfer state root must be an object")
        val jsonObject = root.asJsonObject
        requireExactStateFields(jsonObject, setOf("identity", "assets", "updatedAtMillis"), "state")
        val identity = jsonObject["identity"]
            ?: throw DriveAssetTransferException("Drive transfer state identity is missing")
        if (!identity.isJsonObject) throw DriveAssetTransferException("Drive transfer state identity must be an object")
        requireExactStateFields(
            identity.asJsonObject,
            setOf("accountId", "backupRootId", "documentId", "sourceFingerprint", "snapshotDigest", "parentFolderId"),
            "state identity",
            optional = setOf("sourceFingerprint")
        )
        requireStateString(identity.asJsonObject, "accountId")
        requireStateString(identity.asJsonObject, "backupRootId")
        requireStateString(identity.asJsonObject, "documentId")
        requireStateString(identity.asJsonObject, "snapshotDigest")
        requireStateString(identity.asJsonObject, "parentFolderId")
        identity.asJsonObject["sourceFingerprint"]?.let { value ->
            if (!value.isJsonNull) requireStateStringElement(value, "state identity sourceFingerprint")
        }
        val assets = jsonObject["assets"]
            ?: throw DriveAssetTransferException("Drive transfer state assets are missing")
        if (!assets.isJsonObject) throw DriveAssetTransferException("Drive transfer state assets must be an object")
        if (assets.asJsonObject.size() > Stage5Limits.MAX_REMOTE_DESCRIPTOR_COUNT) {
            throw DriveAssetTransferException("Drive transfer state asset count exceeds its limit")
        }
        assets.asJsonObject.entrySet().forEach { (hash, element) ->
            if (!hash.matches(Regex("[0-9a-f]{64}"))) {
                throw DriveAssetTransferException("Drive transfer state hash is invalid")
            }
            if (!element.isJsonObject) throw DriveAssetTransferException("Drive transfer state asset must be an object")
            val asset = element.asJsonObject
            requireExactStateFields(
                asset,
                setOf("remoteAssetId", "byteCount", "sha256", "mimeType", "width", "height", "sessionUrl", "acknowledgedBytes"),
                "state asset",
                optional = setOf("sessionUrl")
            )
            requireStateString(asset, "remoteAssetId")
            requireStateInteger(asset, "byteCount")
            requireStateString(asset, "sha256")
            requireStateString(asset, "mimeType")
            requireStateInteger(asset, "width")
            requireStateInteger(asset, "height")
            requireStateInteger(asset, "acknowledgedBytes")
            asset["sessionUrl"]?.let { value ->
                if (!value.isJsonNull) requireStateStringElement(value, "state asset sessionUrl")
            }
        }
        requireStateInteger(jsonObject, "updatedAtMillis")
    }

    private fun requireExactStateFields(
        jsonObject: JsonObject,
        allowed: Set<String>,
        label: String,
        optional: Set<String> = emptySet()
    ) {
        val unknown = jsonObject.keySet() - allowed
        if (unknown.isNotEmpty()) throw DriveAssetTransferException("$label contains unknown fields")
        val required = allowed - optional
        val missing = required - jsonObject.keySet()
        if (missing.isNotEmpty()) throw DriveAssetTransferException("$label is missing fields")
    }

    private fun requireStateString(jsonObject: JsonObject, name: String) {
        val value = jsonObject[name] ?: throw DriveAssetTransferException("state field '$name' is missing")
        requireStateStringElement(value, "state field '$name'")
    }

    private fun requireStateStringElement(value: JsonElement, label: String) {
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString.isEmpty()) {
            throw DriveAssetTransferException("$label must be a non-empty string")
        }
    }

    private fun requireStateInteger(jsonObject: JsonObject, name: String) {
        val value = jsonObject[name] ?: throw DriveAssetTransferException("state field '$name' is missing")
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber ||
            !value.asString.matches(Regex("-?(0|[1-9][0-9]*)"))
        ) throw DriveAssetTransferException("state field '$name' must be an integer")
    }

    internal fun validateAsset(hash: String, asset: PersistedAssetState?) {
        if (asset == null) throw DriveAssetTransferException("Drive transfer state contains a null asset")
        if (!hash.matches(Regex("[0-9a-f]{64}"))) {
            throw DriveAssetTransferException("Drive transfer state hash is invalid")
        }
        if (!asset.remoteAssetId.matches(Regex("[A-Za-z0-9_-]{1,512}"))) {
            throw DriveAssetTransferException("state remote asset ID is invalid")
        }
        try {
            PhotoDescriptor(
                byteCount = asset.byteCount,
                sha256 = asset.sha256,
                mimeType = asset.mimeType,
                width = asset.width,
                height = asset.height
            )
        } catch (error: IllegalArgumentException) {
            throw DriveAssetTransferException("Drive transfer state descriptor is invalid", error)
        }
        if (asset.sha256 != hash) throw DriveAssetTransferException("Drive transfer state hash key does not match descriptor")
        if (asset.acknowledgedBytes !in 0L..asset.byteCount) {
            throw DriveAssetTransferException("state acknowledged range is invalid")
        }
        asset.sessionUrl?.let {
            try {
                validateSessionUrl(it)
            } catch (error: IllegalArgumentException) {
                throw DriveAssetTransferException("state resumable URL is invalid", error)
            }
        }
    }

    private fun SourceFingerprint.toWireValue(): String =
        "${SourceFingerprint.SHA256_ALGORITHM}:${digestHex.lowercase(Locale.ROOT)}:${byteCount}"
}

internal data class TransferIdentity(
    val accountId: String,
    val backupRootId: String,
    val documentId: String,
    val sourceFingerprint: String?,
    val snapshotDigest: String,
    val parentFolderId: String
)

internal data class PersistedTransferState(
    val identity: TransferIdentity,
    val assets: Map<String, PersistedAssetState>,
    val updatedAtMillis: Long
)

internal data class PersistedAssetState(
    val remoteAssetId: String,
    val byteCount: Long,
    val sha256: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sessionUrl: String? = null,
    val acknowledgedBytes: Long = 0L
)

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

private fun encodePath(value: String): String =
    java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")

private inline fun <T> HttpResponse.withResponse(block: (HttpResponse) -> T): T {
    try {
        return block(this)
    } finally {
        try {
            disconnect()
        } catch (_: IOException) {
            // The operation's result is already determined; transport cleanup
            // failure must not mask a cancellation or validation failure.
        }
    }
}
