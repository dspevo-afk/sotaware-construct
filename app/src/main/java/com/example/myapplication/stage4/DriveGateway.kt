package com.example.myapplication.stage4

import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage5.BoundedOutputStream
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.PhotoDescriptor
import com.example.myapplication.stage5.escapeDriveQueryLiteral
import com.example.myapplication.stage5.validateSnapshot
import com.example.myapplication.stage5.validatePhotoFileName
import com.example.myapplication.stage5.validateSourceFingerprintProperty
import com.example.myapplication.stage9b.DRIVE_MANIFEST_SCHEMA_VERSION
import com.example.myapplication.stage9b.AssetTransferResult
import com.example.myapplication.stage9b.DriveImmutableAssetTransfer
import com.example.myapplication.stage9b.DriveAdoptionRecovery
import com.example.myapplication.stage9b.DriveAssetTransferException
import com.example.myapplication.stage9b.DriveAssetStaleGenerationException
import com.example.myapplication.stage9b.PhotoAsset
import com.example.myapplication.stage9b.PhotoAssetSet
import com.example.myapplication.stage9b.RemoteAssetDescriptor
import com.example.myapplication.stage9b.RemoteManifestCodec
import com.example.myapplication.stage9b.RemoteManifest
import com.example.myapplication.stage9b.RemoteManifestValidationException
import com.example.myapplication.stage9b.RemoteDownloadOwnership
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.HttpResponseException
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

const val SYNC_DOCUMENT_ID_APP_PROPERTY: String = "sotaware_document_id"
const val SYNC_SCHEMA_APP_PROPERTY: String = "sotaware_snapshot_schema"
const val SYNC_SOURCE_FINGERPRINT_APP_PROPERTY: String = "sotaware_source_fingerprint"
private const val SYNC_ASSET_MANIFEST_SCHEMA_APP_PROPERTY: String = "sotaware_manifest_schema"
const val DRIVE_PAYLOAD_SCHEMA_VERSION: Int = DRIVE_MANIFEST_SCHEMA_VERSION

internal fun SourceFingerprint.toDriveProperty(): String =
    "${SourceFingerprint.SHA256_ALGORITHM}:${digestHex.lowercase(java.util.Locale.ROOT)}:${byteCount}"

internal fun sourceFingerprintFromDriveProperty(value: String?): SourceFingerprint? {
    if (value == null) return null
    validateSourceFingerprintProperty(value, "Drive source fingerprint")
    val parts = value.split(':')
    require(parts.size == 3) { "Drive source fingerprint has an invalid shape" }
    return SourceFingerprint(
        // SourceFingerprint equality is data-class based.  Materialize the
        // canonical spelling here so a provider's case-insensitive wire value
        // cannot create a different in-memory identity from the same source.
        algorithm = SourceFingerprint.SHA256_ALGORITHM,
        digestHex = parts[1].lowercase(java.util.Locale.ROOT),
        byteCount = parts[2].toLong()
    )
}

/** The complete identity used for every remote synchronization operation. */
data class SyncScope(
    val accountId: String,
    val backupRootId: String,
    val documentId: DocumentId
) {
    init {
        require(accountId.isNotBlank()) { "accountId must not be blank" }
        require(backupRootId.isNotBlank()) { "backupRootId must not be blank" }
    }
}

/** A server-owned revision/cursor. Device wall-clock time is never authoritative. */
data class RemoteCursor(
    val revision: String,
    val modifiedTimeMillis: Long? = null
) {
    init {
        require(revision.isNotBlank()) { "remote revision must not be blank" }
        require(modifiedTimeMillis == null || modifiedTimeMillis >= 0L) {
            "remote modified time must be non-negative"
        }
    }
}

/** Stable Drive IDs and the identity property used to validate them. */
data class RemoteReference(
    val folderId: String,
    val snapshotFileId: String,
    val appProperties: Map<String, String>
) {
    init {
        require(folderId.matches(Regex("[A-Za-z0-9_-]{1,512}"))) {
            "remote folder id is invalid"
        }
        require(snapshotFileId.matches(Regex("[A-Za-z0-9_-]{1,512}"))) {
            "remote snapshot file id is invalid"
        }
        require(appProperties[SYNC_DOCUMENT_ID_APP_PROPERTY].orEmpty().isNotBlank()) {
            "remote reference must carry the DocumentId app property"
        }
    }
}

data class RemoteDocumentMetadata(
    val scope: SyncScope,
    val displayName: String,
    val reference: RemoteReference,
    val cursor: RemoteCursor
) {
    init {
        require(reference.appProperties[SYNC_DOCUMENT_ID_APP_PROPERTY] == scope.documentId.value) {
            "remote reference DocumentId does not match SyncScope"
        }
    }
}

/** A typed, complete remote payload. The gateway never returns a partial legacy map. */
data class RemoteSnapshotEnvelope(
    val scope: SyncScope,
    val displayName: String,
    val reference: RemoteReference,
    val cursor: RemoteCursor,
    val snapshot: DocumentSnapshotV1,
    /** The verified source revision carried by the typed remote payload. */
    val sourceFingerprint: SourceFingerprint? = null,
    /** Immutable, reopenable assets referenced by [snapshot]. */
    val photoFiles: PhotoAssetSet = PhotoAssetSet.EMPTY,
    /** Current v3 immutable descriptors, retained for manifest readback. */
    val photoDescriptors: Map<String, RemoteAssetDescriptor> = emptyMap()
) {
    init {
        require(reference.appProperties[SYNC_DOCUMENT_ID_APP_PROPERTY] == scope.documentId.value) {
            "remote envelope reference DocumentId does not match SyncScope"
        }
    }
}

/** A same-source remote resource whose app-generated DocumentId belongs to another device. */
data class RemoteAdoptionCandidate(
    val accountId: String,
    val backupRootId: String,
    val remoteDocumentId: DocumentId,
    val sourceFingerprint: SourceFingerprint,
    val displayName: String,
    val reference: RemoteReference,
    val cursor: RemoteCursor
) {
    init {
        require(accountId.isNotBlank()) { "adoption account must not be blank" }
        require(backupRootId.isNotBlank()) { "adoption root must not be blank" }
        require(reference.appProperties[SYNC_DOCUMENT_ID_APP_PROPERTY] == remoteDocumentId.value) {
            "adoption candidate must retain its remote DocumentId"
        }
        require(reference.appProperties[SYNC_SOURCE_FINGERPRINT_APP_PROPERTY] == sourceFingerprint.toDriveProperty()) {
            "adoption candidate must carry its source fingerprint"
        }
    }
}

/**
 * A per-SyncScope remote mutation lease. Preparation may be superseded, but a
 * request must hold this lease from its final generation check through the
 * remote mutation and the coordinator's accepted-metadata commit.
 */
interface RemoteMutationLease {
    suspend fun advance(generation: Long)

    /** True only while [generation] is the lease's linearized generation. */
    fun isGenerationCurrent(generation: Long): Boolean

    suspend fun begin(
        generation: Long,
        isGenerationCurrent: () -> Boolean
    ): RemoteMutationSession?
}

interface RemoteMutationSession {
    suspend fun <T> mutate(block: suspend () -> T): T

    fun close()
}

/** Deterministic lease implementation shared by the coordinator and fake Drive. */
class ScopeRemoteMutationLease : RemoteMutationLease {
    private val mutex = Mutex()
    private val generationLock = Any()

    @Volatile
    private var latestGeneration: Long = 0L

    override suspend fun advance(generation: Long) {
        // The mutex is the generation linearization point. A newer generation
        // waits for the active holder to finish its external mutation and
        // accepted-metadata handoff before it becomes current. This prevents a
        // stale holder from passing a final check and then creating/updating a
        // real Drive resource after the newer generation was published.
        mutex.withLock {
            synchronized(generationLock) {
                if (generation > latestGeneration) latestGeneration = generation
            }
        }
    }

    override fun isGenerationCurrent(generation: Long): Boolean =
        latestGeneration == generation

    override suspend fun begin(
        generation: Long,
        isGenerationCurrent: () -> Boolean
    ): RemoteMutationSession? {
        if (!isGenerationCurrent()) return null
        mutex.lock()
        if (latestGeneration != generation || !isGenerationCurrent()) {
            mutex.unlock()
            return null
        }
        return HeldSession(mutex)
    }

    private class HeldSession(
        private val mutex: Mutex
    ) : RemoteMutationSession {
        @Volatile
        private var active = true

        override suspend fun <T> mutate(block: suspend () -> T): T {
            check(active) { "remote mutation session is closed" }
            return block()
        }

        @Synchronized
        override fun close() {
            if (!active) return
            active = false
            mutex.unlock()
        }
    }
}

data class DrivePage<T>(
    val items: List<T>,
    val nextPageToken: String?
)

/** Shared continuation-token loop for every active Drive listing adapter. */
suspend fun <T> collectDrivePages(
    fetchPage: suspend (pageToken: String?) -> DrivePage<T>
): List<T> {
    val items = mutableListOf<T>()
    val seenTokens = mutableSetOf<String>()
    var token: String? = null
    do {
        if (token != null && !seenTokens.add(token!!)) {
            error("Drive pagination repeated continuation token '$token'")
        }
        val page = fetchPage(token)
        items += page.items
        token = page.nextPageToken?.takeIf { it.isNotBlank() }
    } while (token != null)
    return items
}

/**
 * Returns every photo file named by the canonical snapshot.  The names remain
 * a legacy compatibility field, but a synchronization payload is not complete
 * unless the corresponding bytes travel with it.
 */
fun requiredPhotoFileNames(snapshot: DocumentSnapshotV1): Set<String> =
    com.example.myapplication.stage5.requiredPhotoNames(snapshot)

/**
 * Fail-closed validation for the photo sidecar of a typed snapshot.  This is
 * deliberately independent of Drive display names and prevents a JSON-only
 * payload from advancing a remote cursor.
 */
fun validatedPhotoFiles(
    snapshot: DocumentSnapshotV1,
    photoFiles: PhotoAssetSet,
    expectedDescriptors: Map<String, PhotoDescriptor>? = null
): PhotoAssetSet = com.example.myapplication.stage9b.validatePhotoAssets(
    snapshot,
    photoFiles,
    expectedDescriptors
)

sealed class DriveFailure {
    data class NotAuthenticated(val detail: String) : DriveFailure()
    data class NotFound(val detail: String) : DriveFailure()
    data class Conflict(
        val detail: String,
        val remote: RemoteDocumentMetadata? = null
    ) : DriveFailure()
    data class Transfer(val operation: String, val detail: String, val cause: Throwable? = null) : DriveFailure()
    data class Validation(val detail: String, val cause: Throwable? = null) : DriveFailure()
    data class Pagination(val detail: String, val cause: Throwable? = null) : DriveFailure()
    data class StaleGeneration(val generation: Long) : DriveFailure()
    data class Unknown(val operation: String, val detail: String, val cause: Throwable? = null) : DriveFailure()
}

sealed class RemoteLookup {
    data class Found(val metadata: RemoteDocumentMetadata) : RemoteLookup()
    data object NotFound : RemoteLookup()
    /** Fail-closed result; a user/link flow must explicitly adopt this resource. */
    data class PendingAdoption(val candidate: RemoteAdoptionCandidate) : RemoteLookup()
    data class Failed(val failure: DriveFailure) : RemoteLookup()
}

data class UploadRequest(
    val scope: SyncScope,
    val displayName: String,
    val snapshot: DocumentSnapshotV1,
    val expectedCursor: RemoteCursor?,
    val generation: Long,
    val mutationLease: RemoteMutationLease,
    /** The final remote mutation must call this immediately before writing. */
    val isGenerationCurrent: () -> Boolean,
    /** Stable source revision used for cross-device adoption; never a local id. */
    val sourceFingerprint: SourceFingerprint? = null,
    /** Immutable, reopenable assets for every photo referenced by [snapshot]. */
    val photoFiles: PhotoAssetSet = PhotoAssetSet.EMPTY
) {
    init {
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(generation > 0L) { "generation must be positive" }
        require(snapshot.schemaVersion == DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION) {
            "unsupported snapshot schema"
        }
    }
}

/**
 * Explicit cross-device link/adoption request.  The remote DocumentId is
 * deliberately supplied by the user-facing candidate and is never inferred
 * from a display name or URI.
 */
data class AdoptionRequest(
    val scope: SyncScope,
    val candidate: RemoteAdoptionCandidate,
    val localSourceFingerprint: SourceFingerprint,
    val generation: Long,
    val mutationLease: RemoteMutationLease,
    val isGenerationCurrent: () -> Boolean
) {
    init {
        require(candidate.accountId == scope.accountId) { "adoption account does not match scope" }
        require(candidate.backupRootId == scope.backupRootId) { "adoption root does not match scope" }
        require(localSourceFingerprint == candidate.sourceFingerprint) {
            "adoption source fingerprint does not match the selected candidate"
        }
        require(generation > 0L) { "adoption generation must be positive" }
    }
}

sealed class AdoptionResult {
    abstract val mutationSession: RemoteMutationSession?

    data class Adopted(
        val remote: RemoteDocumentMetadata,
        val adoptedRemoteDocumentId: DocumentId,
        override val mutationSession: RemoteMutationSession
    ) : AdoptionResult()

    data class Rejected(
        val failure: DriveFailure,
        override val mutationSession: RemoteMutationSession? = null
    ) : AdoptionResult()
}

sealed class UploadResult {
    abstract val mutationSession: RemoteMutationSession?

    data class Uploaded(
        val remote: RemoteSnapshotEnvelope,
        override val mutationSession: RemoteMutationSession
    ) : UploadResult()

    data class Conflict(
        val remote: RemoteDocumentMetadata,
        override val mutationSession: RemoteMutationSession
    ) : UploadResult()

    data class PendingAdoption(
        val candidate: RemoteAdoptionCandidate,
        override val mutationSession: RemoteMutationSession
    ) : UploadResult()

    data class Rejected(
        val failure: DriveFailure,
        override val mutationSession: RemoteMutationSession? = null
    ) : UploadResult()
}

sealed class DownloadResult {
    data class Downloaded(
        val remote: RemoteSnapshotEnvelope,
        /** Explicit owner for any file-backed assets in [remote]. */
        val ownership: RemoteDownloadOwnership? = null
    ) : DownloadResult()
    data object NotFound : DownloadResult()
    data class Failed(val failure: DriveFailure) : DownloadResult()
}

/**
 * Typed remote boundary. Implementations must treat [find] as read-only and
 * must not create folders or files while resolving a missing document.
 */
interface DriveGateway {
    suspend fun find(scope: SyncScope): RemoteLookup

    /**
     * Optional source-identity lookup used only to produce an explicit,
     * fail-closed adoption candidate. The default keeps old gateway fixtures
     * source-compatible and never broadens a local-id lookup.
     */
    suspend fun find(scope: SyncScope, sourceFingerprint: SourceFingerprint?): RemoteLookup =
        find(scope)

    suspend fun upload(request: UploadRequest): UploadResult

    /**
     * Called only after the coordinator has durably acknowledged the accepted
     * remote cursor in local metadata. Implementations may retire resumable
     * transfer state; reservation evidence is deliberately retained.
     */
    suspend fun acknowledgeAcceptedUpload(
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?,
        snapshot: DocumentSnapshotV1,
        remote: RemoteSnapshotEnvelope
    ) = Unit

    /** Retire compensation intent only after accepted adoption metadata is durable. */
    suspend fun acknowledgeAcceptedAdoption(
        scope: SyncScope, candidate: RemoteAdoptionCandidate, remote: RemoteDocumentMetadata
    ) = Unit

    /**
     * Consumes an explicitly selected pending-adoption candidate.  The
     * default is fail-closed so legacy adapters cannot silently rebind a
     * same-name resource.
     */
    suspend fun adopt(request: AdoptionRequest): AdoptionResult = AdoptionResult.Rejected(
        DriveFailure.Validation("this Drive adapter does not support explicit document adoption")
    )

    suspend fun download(
        scope: SyncScope,
        reference: RemoteReference,
        expectedCursor: RemoteCursor? = null
    ): DownloadResult
}

/** A gateway decorator used by the Android UI while authentication/root state changes. */
class DynamicDriveGateway(
    private val provider: () -> DriveGateway?
) : DriveGateway {
    override suspend fun find(scope: SyncScope): RemoteLookup =
        provider()?.find(scope) ?: RemoteLookup.Failed(
            DriveFailure.NotAuthenticated("Google Drive is not initialized")
        )

    override suspend fun find(scope: SyncScope, sourceFingerprint: SourceFingerprint?): RemoteLookup =
        provider()?.find(scope, sourceFingerprint) ?: RemoteLookup.Failed(
            DriveFailure.NotAuthenticated("Google Drive is not initialized")
        )

    override suspend fun upload(request: UploadRequest): UploadResult =
        provider()?.upload(request) ?: UploadResult.Rejected(
            DriveFailure.NotAuthenticated("Google Drive is not initialized")
        )

    override suspend fun acknowledgeAcceptedUpload(
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?,
        snapshot: DocumentSnapshotV1,
        remote: RemoteSnapshotEnvelope
    ) {
        provider()?.acknowledgeAcceptedUpload(scope, sourceFingerprint, snapshot, remote)
            ?: throw DriveAssetTransferException(
                "Google Drive is not initialized while acknowledging an accepted upload"
            )
    }

    override suspend fun acknowledgeAcceptedAdoption(
        scope: SyncScope, candidate: RemoteAdoptionCandidate, remote: RemoteDocumentMetadata
    ) {
        provider()?.acknowledgeAcceptedAdoption(scope, candidate, remote)
            ?: throw DriveAssetTransferException("Google Drive is not initialized while acknowledging adoption")
    }

    override suspend fun adopt(request: AdoptionRequest): AdoptionResult =
        provider()?.adopt(request) ?: AdoptionResult.Rejected(
            DriveFailure.NotAuthenticated("Google Drive is not initialized")
        )

    override suspend fun download(
        scope: SyncScope,
        reference: RemoteReference,
        expectedCursor: RemoteCursor?
    ): DownloadResult = provider()?.download(scope, reference, expectedCursor)
        ?: DownloadResult.Failed(DriveFailure.NotAuthenticated("Google Drive is not initialized"))
}

/** Google Drive adapter for current-format manifest v3. */
class GoogleDriveGateway private constructor(
    private val service: Drive,
    private val accountId: String,
    private val assetTransfer: DriveImmutableAssetTransfer?,
    @Suppress("UNUSED_PARAMETER") private val constructorMarker: Unit
) : DriveGateway {
    private val conditionalWrites = DriveConditionalWrites(service)

    /**
     * A manifest If-Match failure is a remote conflict, not an asset transfer
     * outage.  Keep it distinct until [upload] can re-read the scoped remote
     * metadata and hand the conflict evidence to the coordinator.
     */
    private class ManifestConditionalUpdateConflict(
        cause: IOException
    ) : DriveAssetTransferException("Drive manifest conditional update conflicted", cause)

    constructor(
        service: Drive,
        accountId: String,
        stateDirectory: java.nio.file.Path,
        stagingDirectory: java.nio.file.Path
    ) : this(service, accountId, DriveImmutableAssetTransfer(service, accountId, stateDirectory, stagingDirectory), Unit)

    constructor(service: Drive, accountId: String, assetTransfer: DriveImmutableAssetTransfer) :
        this(service, accountId, assetTransfer, Unit)

    override suspend fun find(scope: SyncScope): RemoteLookup = find(scope, null)

    override suspend fun find(scope: SyncScope, sourceFingerprint: SourceFingerprint?): RemoteLookup =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (scope.accountId != accountId) return@withContext RemoteLookup.Failed(
                    DriveFailure.NotAuthenticated("gateway account does not match SyncScope")
                )
                val folders = listAllFiles(
                    "${escapeDriveQueryLiteral(scope.backupRootId)} in parents and mimeType='application/vnd.google-apps.folder' and trashed=false",
                    "nextPageToken, files(id,name,appProperties,parents,headRevisionId,modifiedTime)",
                    "name"
                )
                val matchingFolders = folders.filter {
                    it.appProperties?.get(SYNC_DOCUMENT_ID_APP_PROPERTY) == scope.documentId.value
                }
                require(matchingFolders.size <= 1) { "multiple Drive folders match the document identity" }
                val folder = matchingFolders.singleOrNull()
                val adoptionMatches = if (folder == null && sourceFingerprint != null) folders.filter {
                    it.appProperties?.get(SYNC_SOURCE_FINGERPRINT_APP_PROPERTY) == sourceFingerprint.toDriveProperty()
                } else emptyList()
                require(adoptionMatches.size <= 1) { "multiple Drive folders match the source identity" }
                val adoptionFolder = adoptionMatches.singleOrNull()
                val selectedFolder = folder ?: adoptionFolder ?: return@withContext RemoteLookup.NotFound
                if (adoptionFolder == null) {
                    requireTaggedFolder(selectedFolder, scope, sourceFingerprint)
                } else {
                    val remoteId = DocumentId.parse(selectedFolder.appProperties?.get(SYNC_DOCUMENT_ID_APP_PROPERTY).orEmpty())
                    requireAdoptionFolder(selectedFolder, scope, remoteId, requireNotNull(sourceFingerprint))
                }
                val folderId = requireNotNull(selectedFolder.id)
                val files = listAllFiles(
                    "${escapeDriveQueryLiteral(folderId)} in parents and trashed=false",
                    "nextPageToken, files(id,name,parents,appProperties,headRevisionId,modifiedTime)",
                    "modifiedTime desc"
                )
                val matchingFiles = files.filter {
                    it.name == "annotations.json" && it.parents.orEmpty().contains(folderId) &&
                        (it.appProperties?.get(SYNC_DOCUMENT_ID_APP_PROPERTY) == scope.documentId.value || adoptionFolder != null)
                }
                require(matchingFiles.size <= 1) { "multiple Drive manifests match the document identity" }
                val file = matchingFiles.singleOrNull() ?: return@withContext RemoteLookup.NotFound
                val cursor = cursorFor(file)
                if (adoptionFolder != null) {
                    val remoteId = DocumentId.parse(selectedFolder.appProperties?.get(SYNC_DOCUMENT_ID_APP_PROPERTY).orEmpty())
                    requireAdoptionFile(file, scope, folderId, remoteId, requireNotNull(sourceFingerprint))
                    return@withContext RemoteLookup.PendingAdoption(
                        RemoteAdoptionCandidate(
                            scope.accountId, scope.backupRootId, remoteId, requireNotNull(sourceFingerprint),
                            selectedFolder.name.orEmpty(), referenceForAny(selectedFolder, file), cursor
                        )
                    )
                }
                requireTaggedFile(file, scope, folderId, sourceFingerprint)
                RemoteLookup.Found(
                    RemoteDocumentMetadata(scope, selectedFolder.name.orEmpty(), referenceFor(selectedFolder, file, scope), cursor)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                RemoteLookup.Failed(DriveFailure.Validation("remote metadata validation failed", error))
            } catch (error: IllegalStateException) {
                RemoteLookup.Failed(DriveFailure.Validation("remote listing validation failed", error))
            } catch (error: IOException) {
                RemoteLookup.Failed(DriveFailure.Unknown("find remote document", error.message ?: error.toString(), error))
            } catch (error: SecurityException) {
                RemoteLookup.Failed(DriveFailure.Unknown("find remote document", error.message ?: error.toString(), error))
            }
        }

    override suspend fun upload(request: UploadRequest): UploadResult = RemoteMutationHandoff().deliver {
        try {
            if (request.scope.accountId != accountId) return@deliver UploadResult.Rejected(
                DriveFailure.NotAuthenticated("gateway account does not match SyncScope")
            )
            requireValidSnapshot(request.snapshot)
            mutationSession = request.mutationLease.begin(request.generation, request.isGenerationCurrent)
                ?: return@deliver UploadResult.Rejected(DriveFailure.StaleGeneration(request.generation))
            mutationSession!!.mutate {
                if (!request.isGenerationCurrent()) return@mutate UploadResult.Rejected(
                    DriveFailure.StaleGeneration(request.generation), mutationSession
                )
                val current = when (val lookup = find(request.scope, request.sourceFingerprint)) {
                    is RemoteLookup.Found -> lookup.metadata
                    RemoteLookup.NotFound -> null
                    is RemoteLookup.PendingAdoption -> return@mutate UploadResult.PendingAdoption(lookup.candidate, mutationSession!!)
                    is RemoteLookup.Failed -> return@mutate UploadResult.Rejected(lookup.failure, mutationSession)
                }
                if (current == null && request.expectedCursor != null) return@mutate UploadResult.Rejected(
                    DriveFailure.NotFound("remote document disappeared while an accepted cursor was present"), mutationSession
                )
                if (current != null && request.expectedCursor != current.cursor) return@mutate UploadResult.Conflict(current, mutationSession!!)
                val folder = ensureFolder(request, current, request.isGenerationCurrent)
                val oldManifest = current?.let {
                    readManifest(it.reference.snapshotFileId, request.scope, request.sourceFingerprint).also { manifest ->
                        require(manifest.sourceFingerprint?.toProperty() == request.sourceFingerprint?.toProperty()) {
                            "existing Drive manifest source fingerprint disagrees with resource scope"
                        }
                    }
                }
                val transferResult = if (request.photoFiles.isEmpty()) {
                    if (requiredPhotoFileNames(request.snapshot).isNotEmpty()) return@mutate UploadResult.Rejected(
                        DriveFailure.Validation("photo assets are required for the current snapshot"), mutationSession
                    )
                    AssetTransferResult(emptyMap(), 0L)
                } else {
                    val transfer = assetTransfer ?: return@mutate UploadResult.Rejected(
                        DriveFailure.Validation("immutable asset transfer is not configured"), mutationSession
                    )
                    transfer.upload(
                        request.scope, request.sourceFingerprint, request.snapshot,
                        requireNotNull(folder.id), request.photoFiles,
                        oldManifest?.assets.orEmpty(), request.isGenerationCurrent
                    )
                }
                val manifestBytes = RemoteManifestCodec.encode(
                    request.scope, request.displayName, request.snapshot,
                    transferResult.descriptors, request.sourceFingerprint
                )
                val expectedManifest = RemoteManifestCodec.decode(
                    manifestBytes, request.scope, request.sourceFingerprint
                ).manifest
                val properties = manifestProperties(
                    request.scope, request.sourceFingerprint,
                    RemoteManifestCodec.canonicalDigest(manifestBytes)
                )
                if (!request.isGenerationCurrent()) return@mutate UploadResult.Rejected(
                    DriveFailure.StaleGeneration(request.generation), mutationSession
                )
                val published = publishManifest(request, current, folder, manifestBytes, properties)
                val finalFolder = getFolder(requireNotNull(folder.id))
                    ?: throw IOException("Drive folder disappeared after manifest publication")
                val finalFile = getFile(requireNotNull(published.id))
                    ?: throw IOException("Drive manifest disappeared after publication")
                requireUploadFolder(finalFolder, request, requireNotNull(folder.id))
                requireUploadFile(
                    finalFile,
                    request,
                    requireNotNull(folder.id),
                    requireNotNull(published.id)
                )
                val decoded = RemoteManifestCodec.decode(
                    readManifestBytes(finalFile.id), request.scope, request.sourceFingerprint
                )
                require(decoded.manifest == expectedManifest) {
                    "Drive manifest readback does not match the exact scoped canonical manifest"
                }
                require(decoded.canonicalDigest == RemoteManifestCodec.canonicalDigest(manifestBytes)) {
                    "Drive manifest readback canonical bytes changed"
                }
                val reference = referenceFor(finalFolder, finalFile, request.scope)
                UploadResult.Uploaded(
                    RemoteSnapshotEnvelope(
                        request.scope, request.displayName, reference, cursorFor(finalFile),
                        request.snapshot, request.sourceFingerprint, request.photoFiles, decoded.manifest.assets
                    ),
                    mutationSession!!
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: DriveAssetStaleGenerationException) {
            UploadResult.Rejected(DriveFailure.StaleGeneration(request.generation), mutationSession)
        } catch (_: ManifestConditionalUpdateConflict) {
            classifyManifestConditionalConflict(request, mutationSession)
        } catch (error: RemoteManifestValidationException) {
            UploadResult.Rejected(DriveFailure.Validation("manifest validation failed", error), mutationSession)
        } catch (error: IllegalArgumentException) {
            UploadResult.Rejected(DriveFailure.Validation("upload payload validation failed", error), mutationSession)
        } catch (error: IllegalStateException) {
            UploadResult.Rejected(DriveFailure.Validation("upload response validation failed", error), mutationSession)
        } catch (error: IOException) {
            UploadResult.Rejected(DriveFailure.Transfer("upload snapshot", error.message ?: error.toString(), error), mutationSession)
        } catch (error: SecurityException) {
            UploadResult.Rejected(DriveFailure.Transfer("upload snapshot", error.message ?: error.toString(), error), mutationSession)
        }
    }

    /**
     * Resolve a manifest precondition failure through the same scoped,
     * read-only lookup used before an upload.  A conflict result is safe only
     * when that lookup returns a fully validated current document; otherwise
     * retain the mutation session and fail closed without inventing a cursor.
     */
    private suspend fun classifyManifestConditionalConflict(
        request: UploadRequest,
        session: RemoteMutationSession?
    ): UploadResult {
        val latest = try {
            find(request.scope, request.sourceFingerprint)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return UploadResult.Rejected(
                DriveFailure.Conflict(
                    "Drive manifest conditional update conflicted; latest scoped remote evidence could not be read",
                    null
                ),
                session
            )
        }
        return when (latest) {
            is RemoteLookup.Found -> {
                session?.let { UploadResult.Conflict(latest.metadata, it) }
                    ?: UploadResult.Rejected(
                        DriveFailure.Conflict(
                            "Drive manifest conditional update conflicted; mutation session was unavailable",
                            null
                        )
                    )
            }
            RemoteLookup.NotFound -> UploadResult.Rejected(
                DriveFailure.Conflict(
                    "Drive manifest conditional update conflicted; latest scoped remote document was not found",
                    null
                ),
                session
            )
            is RemoteLookup.PendingAdoption -> UploadResult.Rejected(
                DriveFailure.Conflict(
                    "Drive manifest conditional update conflicted; latest remote resource requires explicit adoption",
                    null
                ),
                session
            )
            is RemoteLookup.Failed -> UploadResult.Rejected(
                DriveFailure.Conflict(
                    "Drive manifest conditional update conflicted; latest scoped remote evidence was rejected: " +
                        remoteLookupFailureDetail(latest.failure),
                    null
                ),
                session
            )
        }
    }

    private fun remoteLookupFailureDetail(failure: DriveFailure): String = when (failure) {
        is DriveFailure.NotAuthenticated -> failure.detail
        is DriveFailure.NotFound -> failure.detail
        is DriveFailure.Conflict -> failure.detail
        is DriveFailure.Transfer -> "${failure.operation}: ${failure.detail}"
        is DriveFailure.Validation -> failure.detail
        is DriveFailure.Pagination -> failure.detail
        is DriveFailure.StaleGeneration -> "stale generation ${failure.generation}"
        is DriveFailure.Unknown -> "${failure.operation}: ${failure.detail}"
    }

    override suspend fun acknowledgeAcceptedUpload(
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?,
        snapshot: DocumentSnapshotV1,
        remote: RemoteSnapshotEnvelope
    ) {
        if (scope.accountId != accountId) {
            throw DriveAssetTransferException("gateway account does not match accepted upload scope")
        }
        require(remote.scope == scope) {
            "accepted upload cleanup scope does not match the requested document"
        }
        require(remote.snapshot == snapshot) {
            "accepted upload cleanup snapshot does not match the published upload"
        }
        require(remote.sourceFingerprint == sourceFingerprint) {
            "accepted upload cleanup source fingerprint does not match the published upload"
        }
        val transfer = assetTransfer ?: return
        transfer.clearState(
            scope = scope,
            sourceFingerprint = sourceFingerprint,
            snapshotDigest = RemoteManifestCodec.snapshotDigest(snapshot),
            parentFolderId = remote.reference.folderId
        )
    }

    override suspend fun acknowledgeAcceptedAdoption(
        scope: SyncScope, candidate: RemoteAdoptionCandidate, remote: RemoteDocumentMetadata
    ) = withContext(kotlinx.coroutines.Dispatchers.IO) {
        require(scope.accountId == accountId && candidate.accountId == accountId && remote.scope == scope) {
            "accepted adoption cleanup scope does not match the gateway"
        }
        (assetTransfer ?: throw DriveAssetTransferException("adoption recovery storage is not configured"))
            .acknowledgeAdoptionRecovery(scope, candidate, remote)
    }

    override suspend fun adopt(request: AdoptionRequest): AdoptionResult = RemoteMutationHandoff().deliver {
        try {
            if (request.scope.accountId != accountId) return@deliver AdoptionResult.Rejected(
                DriveFailure.NotAuthenticated("gateway account does not match SyncScope")
            )
            mutationSession = request.mutationLease.begin(request.generation, request.isGenerationCurrent)
                ?: return@deliver AdoptionResult.Rejected(DriveFailure.StaleGeneration(request.generation))
            mutationSession!!.mutate {
                if (!request.isGenerationCurrent()) return@mutate AdoptionResult.Rejected(
                    DriveFailure.StaleGeneration(request.generation), mutationSession
                )
                val transfer = assetTransfer ?: throw DriveAssetTransferException("adoption recovery storage is not configured")
                var recovery = transfer.readAdoptionRecovery(request.scope, request.localSourceFingerprint)
                if (recovery != null && recovery.candidate != request.candidate) {
                    recovery = reselectRecordedAdoption(request, recovery)
                }
                if (recovery != null) {
                    val recovered = recoverRecordedAdoption(request, recovery)
                    if (recovered != null) return@mutate AdoptionResult.Adopted(
                        recovered, request.candidate.remoteDocumentId, mutationSession!!
                    )
                    recovery = transfer.readAdoptionRecovery(request.scope, request.localSourceFingerprint)
                        ?: throw IOException("adoption recovery receipt disappeared")
                }
                val folderObservation = conditionalWrites.read(request.candidate.reference.folderId)
                    ?: return@mutate AdoptionResult.Rejected(
                    DriveFailure.NotFound("selected adoption folder no longer exists"), mutationSession
                )
                val folder = folderObservation.file
                require(folder.id == request.candidate.reference.folderId) {
                    "selected adoption folder ID changed"
                }
                requireAdoptionFolder(folder, request.scope, request.candidate.remoteDocumentId, request.localSourceFingerprint)
                val originalFolderProperties = Collections.unmodifiableMap(
                    LinkedHashMap(folder.appProperties.orEmpty())
                )
                val fileObservation = conditionalWrites.read(request.candidate.reference.snapshotFileId)
                    ?: return@mutate AdoptionResult.Rejected(
                    DriveFailure.NotFound("selected adoption manifest no longer exists"), mutationSession
                )
                val file = fileObservation.file
                require(file.id == request.candidate.reference.snapshotFileId) {
                    "selected adoption manifest ID changed"
                }
                requireAdoptionFile(file, request.scope, requireNotNull(folder.id), request.candidate.remoteDocumentId, request.localSourceFingerprint)
                // A retained intent alone never bypasses the user's selected revision.
                // Only a durably recorded, verified compensation receipt can advance it.
                if (recovery == null) require(cursorFor(file) == request.candidate.cursor) {
                    "selected adoption manifest revision changed"
                } else requireRecoveryRevision(recovery, fileObservation)
                val folderEtag = folderObservation.etag
                val fileEtag = fileObservation.etag
                val originalBytes = readManifestBytes(file.id)
                val decodedOriginal = RemoteManifestCodec.decode(
                    originalBytes,
                    SyncScope(request.scope.accountId, request.scope.backupRootId, request.candidate.remoteDocumentId),
                    request.localSourceFingerprint
                )
                val original = decodedOriginal.manifest
                val originalAssetOwnership = original.assets.values
                    .distinctBy { it.remoteAssetId }
                    .map { descriptor ->
                        readAssetOwnership(
                            descriptor,
                            folder.id,
                            SyncScope(request.scope.accountId, request.scope.backupRootId, request.candidate.remoteDocumentId),
                            original.sourceFingerprint
                        )
                    }
                val rewritten = RemoteManifestCodec.encode(
                    request.scope, original.displayName, original.snapshot, original.assets, original.sourceFingerprint
                )
                val localProperties = manifestProperties(
                    request.scope, request.localSourceFingerprint,
                    RemoteManifestCodec.canonicalDigest(rewritten)
                )
                if (!request.isGenerationCurrent()) return@mutate AdoptionResult.Rejected(
                    DriveFailure.StaleGeneration(request.generation), mutationSession
                )
                val recoveryRecord = DriveAdoptionRecovery(
                    scope = request.scope,
                    candidate = request.candidate,
                    folderName = folder.name.orEmpty(),
                    originalFolderProperties = originalFolderProperties,
                    originalManifestProperties = Collections.unmodifiableMap(LinkedHashMap(file.appProperties.orEmpty())),
                    originalAssetProperties = Collections.unmodifiableMap(originalAssetOwnership.associate {
                        it.id to it.originalProperties
                    }),
                    originalManifestDigest = decodedOriginal.canonicalDigest,
                    adoptedManifestDigest = RemoteManifestCodec.canonicalDigest(rewritten),
                    resumeManifestCursor = cursorFor(file),
                    resumeManifestEtag = fileEtag
                )
                // Must be durable/read-back verified before the first remote PUT.
                // Even an outage that also prevents rollback leaves a recoverable
                // intent for the existing explicitly selected pending candidate.
                transfer.prepareAdoptionRecovery(recoveryRecord)
                val updatedFileAndEtag = try {
                    val returned = conditionalWrites.update(file.id, fileEtag, localProperties, rewritten)
                    requireTaggedFile(returned.file, request.scope, requireNotNull(folder.id), request.localSourceFingerprint)
                    require(returned.file.appProperties.orEmpty() == localProperties) {
                        "Drive manifest ownership changed after adoption rewrite"
                    }
                    returned.file to returned.etag
                } catch (error: Exception) {
                    if (error !is IOException && error !is IllegalArgumentException) throw error
                    if (error is HttpResponseException && error.statusCode == 412) {
                        // This is the first PUT, explicitly rejected without mutation.
                        // Retire only our exact prepared intent: an external content
                        // change must not strand the next explicit selection. Ambiguous
                        // failures and every later write retain recovery evidence.
                        transfer.retireRejectedAdoptionRecovery(recoveryRecord)
                        return@mutate AdoptionResult.Rejected(
                            DriveFailure.Conflict("selected adoption manifest changed before rewrite"), mutationSession
                        )
                    }
                    // Server errors, lost replies and malformed acknowledgements can
                    // all follow a committed PUT. Never replay it. Establish exact
                    // scoped bytes/properties and a stable fresh ETag before continuing
                    // so every later failure has conditional rollback bookkeeping.
                    observedManifest(
                        file.id,
                        request.scope,
                        requireNotNull(folder.id),
                        request.localSourceFingerprint,
                        rewritten,
                        localProperties
                    ) ?: throw error
                }
                val updatedFile = updatedFileAndEtag.first
                val updatedEtag = updatedFileAndEtag.second
                val updatedAssetOwnership = mutableListOf<AssetOwnershipState>()
                fun rollbackManifestAndAssets(failure: Throwable) {
                    rollbackAssetOwnership(updatedAssetOwnership, failure)
                    try {
                        val restored = restoreManifest(file.id, updatedEtag, originalBytes, file.appProperties.orEmpty())
                        recordManifestCompensation(recoveryRecord, restored)
                    } catch (rollback: Throwable) {
                        failure.addSuppressed(rollback)
                    }
                }
                try {
                    originalAssetOwnership.forEach { ownership ->
                        if (!request.isGenerationCurrent()) {
                            val stale = DriveAssetStaleGenerationException(request.generation)
                            rollbackManifestAndAssets(stale)
                            return@mutate AdoptionResult.Rejected(
                                DriveFailure.StaleGeneration(request.generation), mutationSession
                            )
                        }
                        updatedAssetOwnership += updateAssetOwnership(ownership, request.scope, request.localSourceFingerprint)
                    }
                    val check = RemoteManifestCodec.decode(
                        readManifestBytes(file.id), request.scope, request.localSourceFingerprint
                    )
                    require(check.canonicalDigest == recoveryRecord.adoptedManifestDigest) {
                        "adoption manifest content changed before folder publication"
                    }
                } catch (error: Throwable) {
                    rollbackManifestAndAssets(error)
                    throw error
                }
                if (!request.isGenerationCurrent()) {
                    val stale = DriveAssetStaleGenerationException(request.generation)
                    rollbackManifestAndAssets(stale)
                    return@mutate AdoptionResult.Rejected(
                        DriveFailure.StaleGeneration(request.generation), mutationSession
                    )
                }
                val folderProperties = LinkedHashMap(folder.appProperties.orEmpty()).apply {
                    put(SYNC_DOCUMENT_ID_APP_PROPERTY, request.scope.documentId.value)
                    put(SYNC_SCHEMA_APP_PROPERTY, DRIVE_MANIFEST_SCHEMA_VERSION.toString())
                }
                var appliedFolderEtag: String? = null
                fun rollbackAfterFolder(failure: Throwable) {
                    rollbackManifestAndAssets(failure)
                    val etag = appliedFolderEtag
                    if (etag != null) {
                        try {
                            restoreFolder(folder.id, etag, originalFolderProperties)
                        } catch (rollback: Throwable) {
                            failure.addSuppressed(rollback)
                        }
                    }
                }
                if (!request.isGenerationCurrent()) {
                    val stale = DriveAssetStaleGenerationException(request.generation)
                    rollbackManifestAndAssets(stale)
                    return@mutate AdoptionResult.Rejected(
                        DriveFailure.StaleGeneration(request.generation), mutationSession
                    )
                }
                val updatedFolder = try {
                    val returned = conditionalWrites.update(folder.id, folderEtag, folderProperties)
                    appliedFolderEtag = returned.etag
                    returned.file
                } catch (error: Throwable) {
                    // A transport failure can be ambiguous: Drive may have
                    // committed the folder update before the response was
                    // lost.  Read back only the exact expected property map;
                    // if it is present, capture its fresh ETag so rollback is
                    // still conditional and cannot clobber an external edit.
                    appliedFolderEtag = if (error is HttpResponseException && error.statusCode == 412) null
                    else observedFolderEtag(folder.id, folderProperties, request.scope.backupRootId, folder.name.orEmpty())
                    rollbackAfterFolder(error)
                    throw error
                }
                if (appliedFolderEtag == null) {
                    val error = IOException("Drive did not expose an ETag after adoption folder update")
                    rollbackAfterFolder(error)
                    throw error
                }
                val finalFile = try {
                    requireTaggedFolder(updatedFolder, request.scope, request.localSourceFingerprint)
                    getFile(file.id) ?: throw IOException("Drive manifest disappeared after adoption")
                } catch (error: Throwable) {
                    rollbackAfterFolder(error)
                    throw error
                }
                val finalObservation = try {
                    requireTaggedFile(finalFile, request.scope, folder.id, request.localSourceFingerprint)
                    observeRecordedAdoption(recoveryRecord).also {
                        require(it.adopted.values.all { value -> value }) { "adoption final ownership is incomplete" }
                    }
                } catch (error: Throwable) {
                    rollbackAfterFolder(error)
                    throw error
                }
                // Both authoritative mutations have committed and their scoped
                // final readback is verified. Caller cancellation at this point
                // must hand the accepted cursor to local finalization, not undo
                // a completed adoption. The held mutation lease still excludes
                // newer generations until that durable handoff finishes.
                AdoptionResult.Adopted(
                    RemoteDocumentMetadata(
                        request.scope, finalObservation.folder.file.name.orEmpty(),
                        referenceFor(finalObservation.folder.file, finalObservation.manifest.file, request.scope),
                        cursorFor(finalObservation.manifest.file)
                    ),
                    request.candidate.remoteDocumentId,
                    mutationSession!!
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            AdoptionResult.Rejected(DriveFailure.Validation("adoption validation failed", error), mutationSession)
        } catch (error: IOException) {
            AdoptionResult.Rejected(DriveFailure.Transfer("adopt remote document", error.message ?: error.toString(), error), mutationSession)
        } catch (error: SecurityException) {
            AdoptionResult.Rejected(DriveFailure.Transfer("adopt remote document", error.message ?: error.toString(), error), mutationSession)
        } catch (error: IllegalStateException) {
            AdoptionResult.Rejected(DriveFailure.Transfer("adopt remote document", error.message ?: error.toString(), error), mutationSession)
        }
    }

    override suspend fun download(
        scope: SyncScope,
        reference: RemoteReference,
        expectedCursor: RemoteCursor?
    ): DownloadResult {
        // Keep both the staged owner and the completed result outside the IO
        // dispatcher so prompt cancellation cannot drop a file-backed result.
        val completed = AtomicReference<DownloadResult.Downloaded?>()
        var ownership: RemoteDownloadOwnership? = null
        fun releaseOwnership() {
            ownership?.release()
            ownership = null
        }
        return try {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    if (scope.accountId != accountId) return@withContext DownloadResult.Failed(
                        DriveFailure.NotAuthenticated("gateway account does not match SyncScope")
                    )
                    val source = sourceFingerprintFromDriveProperty(
                        reference.appProperties[SYNC_SOURCE_FINGERPRINT_APP_PROPERTY]
                    )
                    val folder = getFolder(reference.folderId) ?: return@withContext DownloadResult.NotFound
                    requireTaggedFolder(folder, scope, source)
                    require(folder.id == reference.folderId) { "remote folder reference id changed during download" }
                    val file = getFile(reference.snapshotFileId) ?: return@withContext DownloadResult.NotFound
                    requireTaggedFile(file, scope, reference.folderId, source)
                    require(file.id == reference.snapshotFileId) { "remote manifest reference id changed during download" }
                    val cursor = cursorFor(file)
                    if (expectedCursor != null && expectedCursor != cursor) return@withContext DownloadResult.Failed(
                        DriveFailure.Validation("remote cursor changed during download")
                    )
                    val manifest = RemoteManifestCodec.decode(
                        readManifestBytes(file.id), scope, source
                    ).manifest
                    // The manifest is authoritative for the source revision only when
                    // it agrees with the identity tags on both remote resources.  A
                    // caller-supplied reference with stripped properties must not turn
                    // a source-scoped document into an unscoped envelope.
                    require(manifest.sourceFingerprint?.toProperty() == source?.toProperty()) {
                        "remote manifest source fingerprint disagrees with resource scope"
                    }
                    val assets = if (manifest.assets.isEmpty()) PhotoAssetSet.EMPTY else {
                        val transfer = assetTransfer ?: return@withContext DownloadResult.Failed(
                            DriveFailure.Validation("immutable asset transfer is not configured")
                        )
                        transfer.download(
                            scope, requireNotNull(folder.id), manifest.snapshot, manifest.assets, manifest.sourceFingerprint
                        ).also { ownership = transfer.ownershipFor(it) }
                    }
                    val afterFile = getFile(file.id) ?: run {
                        releaseOwnership()
                        return@withContext DownloadResult.NotFound
                    }
                    val afterFolder = getFolder(folder.id) ?: run {
                        releaseOwnership()
                        return@withContext DownloadResult.NotFound
                    }
                    require(cursorFor(afterFile) == cursor) { "remote manifest changed while downloading" }
                    requireTaggedFolder(afterFolder, scope, source)
                    requireTaggedFile(afterFile, scope, folder.id, source)
                    val delivered = DownloadResult.Downloaded(
                        RemoteSnapshotEnvelope(
                            scope, manifest.displayName, referenceFor(afterFolder, afterFile, scope),
                            cursorFor(afterFile), manifest.snapshot, manifest.sourceFingerprint, assets, manifest.assets
                        ),
                        ownership
                    )
                    completed.set(delivered)
                    delivered
                } catch (cancelled: CancellationException) {
                    releaseOwnership()
                    throw cancelled
                } catch (error: RemoteManifestValidationException) {
                    releaseOwnership()
                    DownloadResult.Failed(DriveFailure.Validation("remote manifest validation failed", error))
                } catch (error: IllegalArgumentException) {
                    releaseOwnership()
                    DownloadResult.Failed(DriveFailure.Validation("remote identity validation failed", error))
                } catch (error: IOException) {
                    releaseOwnership()
                    DownloadResult.Failed(DriveFailure.Transfer("download snapshot", error.message ?: error.toString(), error))
                } catch (error: SecurityException) {
                    releaseOwnership()
                    DownloadResult.Failed(DriveFailure.Transfer("download snapshot", error.message ?: error.toString(), error))
                }
            }
        } catch (cancelled: CancellationException) {
            // The IO block may have completed and set [completed] just before
            // withContext noticed caller cancellation.
            completed.getAndSet(null)?.ownership?.release()
            releaseOwnership()
            throw cancelled
        }
    }

    private fun ensureFolder(
        request: UploadRequest,
        current: RemoteDocumentMetadata?,
        isGenerationCurrent: () -> Boolean
    ): File {
        if (current != null) return getFolder(current.reference.folderId)
            ?.also { requireUploadFolder(it, request, current.reference.folderId) }
            ?: throw IOException("Drive synchronization folder disappeared")
        val id = (assetTransfer ?: throw DriveAssetTransferException(
            "durable Drive transfer state is required for document folder creation"
        )).reserveResourceId(
            scope = request.scope,
            sourceFingerprint = request.sourceFingerprint,
            parentFolderId = request.scope.backupRootId,
            resourceKind = "document-folder",
            isGenerationCurrent = isGenerationCurrent
        )
        val metadata = File().setId(id).setName(request.displayName)
            .setMimeType("application/vnd.google-apps.folder")
            .setParents(listOf(request.scope.backupRootId))
            .setAppProperties(folderProperties(request.scope, request.sourceFingerprint))
        if (!isGenerationCurrent()) throw DriveAssetStaleGenerationException()
        val create = service.files().create(metadata).setSupportsAllDrives(true)
            .setFields("id,name,parents,appProperties")
        create.requestHeaders.setIfNoneMatch("*")
        val folder = try {
            create.execute() ?: throw IOException("Drive returned no folder after create")
        } catch (error: IOException) {
            // A stable generated ID makes a lost create response safe to
            // resolve.  Accept only a readback carrying the exact scope and
            // parent tags; never blindly retry a second folder create.
            val readback = try {
                getFolder(id)?.also { requireUploadFolder(it, request, id) }
            } catch (probe: Throwable) {
                error.addSuppressed(probe)
                null
            }
            readback ?: throw error
        }
        requireUploadFolder(folder, request, id)
        return folder
    }

    private fun publishManifest(
        request: UploadRequest,
        current: RemoteDocumentMetadata?,
        folder: File,
        bytes: ByteArray,
        properties: Map<String, String>
    ): File {
        val media = ByteArrayContent("application/json", bytes)
        if (current == null) {
            val id = (assetTransfer ?: throw DriveAssetTransferException(
                "durable Drive transfer state is required for document manifest creation"
            )).reserveResourceId(
                scope = request.scope,
                sourceFingerprint = request.sourceFingerprint,
                parentFolderId = requireNotNull(folder.id),
                resourceKind = "document-manifest",
                isGenerationCurrent = request.isGenerationCurrent
            )
            val metadata = File().setId(id).setName("annotations.json").setMimeType("application/json")
                .setParents(listOf(requireNotNull(folder.id))).setAppProperties(properties)
            val create = service.files().create(metadata, media).setSupportsAllDrives(true)
                .setFields("id,name,parents,appProperties,headRevisionId,modifiedTime")
            create.requestHeaders.setIfNoneMatch("*")
            return try {
                (create.execute() ?: throw IOException("Drive returned no manifest after create")).also {
                    require(it.id == id) {
                        "Drive returned a manifest ID different from the reserved ID"
                    }
                }
            } catch (error: IOException) {
                val readback = getFile(id)?.let { candidate ->
                    if (candidate.id == id && candidate.parents.orEmpty().contains(folder.id) &&
                        readManifestMatches(candidate, request.scope, requireNotNull(folder.id), request.sourceFingerprint, bytes)
                    ) candidate else null
                }
                readback ?: throw error
            }
        }
        val observation = conditionalWrites.read(current.reference.snapshotFileId)
            ?: throw IOException("Drive returned no manifest for update")
        val file = observation.file
        requireUploadFile(file, request, requireNotNull(folder.id), current.reference.snapshotFileId)
        if (cursorFor(file) != current.cursor) throw DriveAssetTransferException(
            "Drive manifest changed before conditional update"
        )
        return try {
            conditionalWrites.update(current.reference.snapshotFileId, observation.etag, properties, bytes).file.also {
                require(it.id == current.reference.snapshotFileId) {
                    "Drive returned a manifest ID different from the scoped reference"
                }
            }
        } catch (precondition: HttpResponseException) {
            if (precondition.statusCode == 412) throw ManifestConditionalUpdateConflict(precondition)
            throw precondition
        } catch (error: IOException) {
            val readback = getFile(current.reference.snapshotFileId)?.let { candidate ->
                if (candidate.id == current.reference.snapshotFileId &&
                    readManifestMatches(candidate, request.scope, requireNotNull(folder.id), request.sourceFingerprint, bytes)
                ) candidate else null
            }
            readback ?: throw error
        }
    }

    private fun readManifest(
        fileId: String,
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?
    ): RemoteManifest = RemoteManifestCodec.decode(
        readManifestBytes(fileId), scope, sourceFingerprint
    ).manifest

    private fun readManifestMatches(
        file: File,
        scope: SyncScope,
        expectedFolderId: String,
        sourceFingerprint: SourceFingerprint?,
        expected: ByteArray
    ): Boolean = try {
        requireTaggedFile(file, scope, expectedFolderId, sourceFingerprint)
        val decoded = RemoteManifestCodec.decode(readManifestBytes(requireNotNull(file.id)), scope, sourceFingerprint)
        decoded.canonicalDigest == RemoteManifestCodec.canonicalDigest(expected)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun readManifestBytes(fileId: String): ByteArray {
        val output = ByteArrayOutputStream()
        val bounded = BoundedOutputStream(output, Stage5Limits.MAX_JSON_BYTES, "Drive manifest")
        val request = service.files().get(fileId).setSupportsAllDrives(true)
        request.set("alt", "media")
        val http = request.buildHttpRequest().apply {
            // Manifest media is scoped JSON and participates in conditional
            // readback. Do not let the client replay it or follow a provider
            // redirect carrying authenticated request headers.
            numberOfRetries = 0
            retryOnExecuteIOException = false
            followRedirects = false
            throwExceptionOnExecuteError = false
        }
        val response = http.execute()
        try {
            if (response.statusCode in 300..399) {
                throw IOException("Drive manifest media read returned an unexpected redirect")
            }
            if (!response.isSuccessStatusCode) {
                throw IOException("Drive manifest media read failed: ${response.statusCode}")
            }
            response.content?.use { input ->
                input.copyTo(bounded, bufferSize = 64 * 1024)
            } ?: throw IOException("Drive manifest media read returned no content")
            return output.toByteArray()
        } finally {
            try {
                response.disconnect()
            } catch (_: IOException) {
                // Preserve the authoritative read/validation result.
            }
        }
    }

    private fun restoreManifest(
        fileId: String,
        etag: String,
        bytes: ByteArray,
        properties: Map<String, String>
    ): ConditionalDriveFile = conditionalWrites.update(fileId, etag, properties, bytes)

    private fun restoreFolder(
        folderId: String,
        etag: String,
        properties: Map<String, String>
    ) {
        conditionalWrites.update(folderId, etag, properties)
    }

    /**
     * Probe an adoption folder after an ambiguous update.  Returning an ETag
     * is safe only when the complete expected property map is present; a
     * mismatched readback is treated as an external change and is never
     * overwritten by rollback.
     */
    private fun observedFolderEtag(
        folderId: String,
        expectedProperties: Map<String, String>,
        expectedParentId: String,
        expectedName: String
    ): String? {
        return try {
            val observed = conditionalWrites.read(folderId)
            val current = observed?.file
            if (current == null || current.id != folderId || current.appProperties.orEmpty() != expectedProperties ||
                current.parents.orEmpty() != listOf(expectedParentId) || current.name.orEmpty() != expectedName) {
                null
            } else {
                observed.etag
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Probe a manifest after an ambiguous conditional update.  The bytes must
     * decode as the exact scoped v3 payload and the response must include an
     * ETag before the result can be treated as committed.
     */
    private fun observedManifest(
        fileId: String,
        scope: SyncScope,
        folderId: String,
        sourceFingerprint: SourceFingerprint?,
        expectedBytes: ByteArray,
        expectedProperties: Map<String, String>
    ): Pair<File, String>? {
        return try {
            val observed = conditionalWrites.read(fileId)
            val current = observed?.file
            val etag = observed?.etag
            if (current == null || etag == null || current.id != fileId ||
                current.appProperties.orEmpty() != expectedProperties ||
                !readManifestMatches(current, scope, folderId, sourceFingerprint, expectedBytes)
            ) {
                null
            } else {
                // Media and metadata are separate reads. An intervening edit must
                // not supply the rollback ETag or authorize the remaining adoption.
                val after = conditionalWrites.read(fileId)
                if (after == null || after.etag != etag || after.file.id != fileId ||
                    after.file.appProperties.orEmpty() != expectedProperties ||
                    after.file.parents.orEmpty() != current.parents.orEmpty()) null
                else after.file to after.etag
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Asset bytes are immutable, but their document-scope tags must follow an
     * explicit cross-device adoption.  Read and update each stable ID under an
     * ETag, retaining enough state to roll the ownership tags back if the
     * manifest/folder transaction cannot be completed.
     */
    private fun readAssetOwnership(
        descriptor: RemoteAssetDescriptor,
        folderId: String,
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?
    ): AssetOwnershipState {
        val observed = conditionalWrites.read(descriptor.remoteAssetId)
            ?: throw IOException("Drive immutable asset disappeared during adoption")
        val file = observed.file
        require(file.id == descriptor.remoteAssetId) { "Drive immutable asset ID changed during adoption" }
        require(file.parents.orEmpty() == listOf(folderId)) { "Drive immutable asset is outside its document folder" }
        require(file.getSize() == null || file.getSize() == descriptor.byteCount) {
            "Drive immutable asset size changed during adoption"
        }
        require(file.sha256Checksum.isNullOrBlank() ||
            file.sha256Checksum.equals(descriptor.sha256, ignoreCase = true)) {
            "Drive immutable asset checksum changed during adoption"
        }
        val properties = file.appProperties.orEmpty()
        require(properties["sotaware_account_id"] == scope.accountId)
        require(properties["sotaware_backup_root_id"] == scope.backupRootId)
        require(properties[SYNC_DOCUMENT_ID_APP_PROPERTY] == scope.documentId.value)
        require(properties[SYNC_ASSET_MANIFEST_SCHEMA_APP_PROPERTY] == DRIVE_MANIFEST_SCHEMA_VERSION.toString())
        require(properties["sotaware_asset_sha256"]?.lowercase(java.util.Locale.ROOT) == descriptor.sha256)
        require(properties["sotaware_immutable_asset"] == "1")
        require(properties[SYNC_SOURCE_FINGERPRINT_APP_PROPERTY] == sourceFingerprint?.toDriveProperty()) {
            "Drive immutable asset source scope changed during adoption"
        }
        // Adoption must not turn a metadata-only observation into an
        // authoritative link.  The configured transfer verifies Drive's
        // output checksum/size or streams the bytes through its bounded
        // staging path when those output fields are unavailable.
        (assetTransfer ?: throw DriveAssetTransferException(
            "immutable asset verification is not configured"
        )).verifyRemoteAsset(scope, folderId, sourceFingerprint, descriptor)
        val after = conditionalWrites.read(descriptor.remoteAssetId)
            ?: throw IOException("Drive immutable asset disappeared during verification")
        require(after.etag == observed.etag && after.file.appProperties.orEmpty() == properties &&
            after.file.parents.orEmpty() == file.parents.orEmpty()) {
            "Drive immutable asset changed while its content was verified"
        }
        val etag = after.etag
        return AssetOwnershipState(
            id = descriptor.remoteAssetId,
            descriptor = descriptor,
            folderId = folderId,
            originalProperties = Collections.unmodifiableMap(LinkedHashMap(properties)),
            currentProperties = Collections.unmodifiableMap(LinkedHashMap(properties)),
            etag = etag
        )
    }

    private fun updateAssetOwnership(
        ownership: AssetOwnershipState,
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?
    ): AssetOwnershipState {
        val properties = adoptedAssetProperties(ownership.currentProperties, scope, sourceFingerprint)
        val etag = try {
            val updated = conditionalWrites.update(ownership.id, ownership.etag, properties)
            require(updated.file.id == ownership.id) { "Drive immutable asset ID changed after adoption" }
            require(updated.file.appProperties.orEmpty() == properties) {
                "Drive immutable asset ownership changed after adoption"
            }
            require(updated.file.parents.orEmpty() == listOf(ownership.folderId)) {
                "Drive immutable asset parent changed after adoption"
            }
            require(updated.file.getSize() == null || updated.file.getSize() == ownership.descriptor.byteCount) {
                "Drive immutable asset size changed after adoption"
            }
            require(updated.file.sha256Checksum.isNullOrBlank() ||
                updated.file.sha256Checksum.equals(ownership.descriptor.sha256, ignoreCase = true)) {
                "Drive immutable asset content changed after adoption"
            }
            updated.etag
        } catch (error: Exception) {
            if (error !is IOException && error !is IllegalArgumentException) throw error
            if (error is HttpResponseException && error.statusCode == 412) {
                throw DriveAssetTransferException("Drive immutable asset ownership changed during adoption", error)
            }
            // The provider may have committed before its reply or response cleanup
            // failed. Reconcile the exact asset, scope, parent and immutable bytes;
            // matching custom properties alone cannot establish accepted ownership.
            // Never replay the PUT. Retain the observed ETag so a later rollback
            // includes this asset without overwriting a subsequent external edit.
            val observed = try {
                readAssetOwnership(ownership.descriptor, ownership.folderId, scope, sourceFingerprint)
            } catch (probe: Exception) {
                if (probe is CancellationException) throw probe
                if (probe !== error) error.addSuppressed(probe)
                null
            }
            if (observed == null || observed.currentProperties != properties) throw error
            observed.etag
        }
        return ownership.copy(
            currentProperties = Collections.unmodifiableMap(properties),
            etag = etag
        )
    }

    private fun rollbackAssetOwnership(
        updated: List<AssetOwnershipState>,
        failure: Throwable
    ) {
        updated.asReversed().forEach { ownership ->
            try {
                conditionalWrites.update(ownership.id, ownership.etag, ownership.originalProperties)
            } catch (rollback: Throwable) {
                failure.addSuppressed(rollback)
            }
        }
    }

    private data class RecordedAdoptionObservation(
        val folder: ConditionalDriveFile,
        val manifest: ConditionalDriveFile,
        val content: RemoteManifest,
        val assets: List<AssetOwnershipState>,
        val adopted: Map<String, Boolean>
    )

    private fun adoptedAssetProperties(
        original: Map<String, String>, scope: SyncScope, sourceFingerprint: SourceFingerprint?
    ): Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(original).apply {
        put(SYNC_DOCUMENT_ID_APP_PROPERTY, scope.documentId.value)
        put(SYNC_ASSET_MANIFEST_SCHEMA_APP_PROPERTY, DRIVE_MANIFEST_SCHEMA_VERSION.toString())
        put("sotaware_account_id", scope.accountId)
        put("sotaware_backup_root_id", scope.backupRootId)
        if (sourceFingerprint == null) remove(SYNC_SOURCE_FINGERPRINT_APP_PROPERTY)
        else put(SYNC_SOURCE_FINGERPRINT_APP_PROPERTY, sourceFingerprint.toDriveProperty())
    })

    /**
     * Classify the entire recorded intent before attempting any compensation.
     * Only exact original/adopted content and full property maps are ours.
     * Missing evidence, changed bytes/parents or an external owner stay blocked.
     */
    private fun observeRecordedAdoption(record: DriveAdoptionRecovery): RecordedAdoptionObservation {
        val scope = record.scope
        val candidate = record.candidate
        require(candidate.remoteDocumentId != scope.documentId) { "adoption source and target identities must differ" }
        val oldScope = scope.copy(documentId = candidate.remoteDocumentId)
        val source = candidate.sourceFingerprint
        val folderId = candidate.reference.folderId
        val fileId = candidate.reference.snapshotFileId
        val folder = conditionalWrites.read(folderId) ?: throw IOException("adoption recovery folder is unavailable")
        val manifest = conditionalWrites.read(fileId) ?: throw IOException("adoption recovery manifest is unavailable")
        val newFolderProperties = LinkedHashMap(record.originalFolderProperties).apply {
            put(SYNC_DOCUMENT_ID_APP_PROPERTY, scope.documentId.value)
            put(SYNC_SCHEMA_APP_PROPERTY, DRIVE_MANIFEST_SCHEMA_VERSION.toString())
        }
        val newManifestProperties = manifestProperties(scope, source, record.adoptedManifestDigest)
        val adopted = linkedMapOf<String, Boolean>()
        fun classify(id: String, actual: Map<String, String>, before: Map<String, String>, after: Map<String, String>): Boolean {
            val isNew = actual == after
            require(isNew || actual == before) { "adoption recovery found external ownership/properties for $id" }
            adopted[id] = isNew
            return isNew
        }
        val folderIsNew = classify(folderId, folder.file.appProperties.orEmpty(), record.originalFolderProperties, newFolderProperties)
        require(folder.file.id == folderId && folder.file.parents.orEmpty() == listOf(scope.backupRootId) &&
            folder.file.name.orEmpty() == record.folderName) { "adoption recovery folder association changed" }
        requireTaggedFolder(folder.file, if (folderIsNew) scope else oldScope, source)
        val manifestIsNew = classify(fileId, manifest.file.appProperties.orEmpty(), record.originalManifestProperties, newManifestProperties)
        require(manifest.file.id == fileId && manifest.file.parents.orEmpty() == listOf(folderId)) {
            "adoption recovery manifest association changed"
        }
        val currentScope = if (manifestIsNew) scope else oldScope
        requireTaggedFile(manifest.file, currentScope, folderId, source)
        val content = RemoteManifestCodec.decode(readManifestBytes(fileId), currentScope, source)
        require(content.canonicalDigest == if (manifestIsNew) record.adoptedManifestDigest else record.originalManifestDigest) {
            "adoption recovery manifest content changed"
        }
        val descriptors = content.manifest.assets.values.distinctBy { it.remoteAssetId }
        require(descriptors.map { it.remoteAssetId }.toSet() == record.originalAssetProperties.keys) {
            "adoption recovery immutable asset set differs"
        }
        val assets = descriptors.map { descriptor ->
            val before = record.originalAssetProperties.getValue(descriptor.remoteAssetId)
            val after = adoptedAssetProperties(before, scope, source)
            val initial = conditionalWrites.read(descriptor.remoteAssetId)
                ?: throw IOException("adoption recovery asset is unavailable")
            val isNew = classify(descriptor.remoteAssetId, initial.file.appProperties.orEmpty(), before, after)
            val verified = readAssetOwnership(descriptor, folderId, if (isNew) scope else oldScope, source)
            require(verified.etag == initial.etag && verified.currentProperties == if (isNew) after else before) {
                "adoption recovery asset changed during verification"
            }
            verified.copy(originalProperties = before)
        }
        // Bracket the whole asset/content sweep with stable authoritative metadata.
        val finalFolder = conditionalWrites.read(folderId) ?: throw IOException("adoption folder disappeared during verification")
        val finalManifest = conditionalWrites.read(fileId) ?: throw IOException("adoption manifest disappeared during verification")
        require(finalFolder.etag == folder.etag && finalFolder.file.appProperties.orEmpty() == folder.file.appProperties.orEmpty() &&
            finalFolder.file.parents.orEmpty() == folder.file.parents.orEmpty() && finalFolder.file.name == folder.file.name &&
            finalManifest.etag == manifest.etag && finalManifest.file.appProperties.orEmpty() == manifest.file.appProperties.orEmpty() &&
            finalManifest.file.parents.orEmpty() == manifest.file.parents.orEmpty() && finalManifest.file.name == manifest.file.name) {
            "adoption resources changed during final verification"
        }
        return RecordedAdoptionObservation(finalFolder, finalManifest, content.manifest, assets, adopted)
    }

    /** A new explicit selection may replace a resolved, entirely original-state intent. */
    private fun reselectRecordedAdoption(request: AdoptionRequest, record: DriveAdoptionRecovery): DriveAdoptionRecovery {
        require(record.scope == request.scope && record.candidate.sourceFingerprint == request.localSourceFingerprint &&
            request.candidate.copy(cursor = record.candidate.cursor) == record.candidate &&
            request.candidate.cursor != record.candidate.cursor) {
            "selected adoption does not identify a fresh revision of the recorded resources"
        }
        // A 412 or a lost reply is not by itself proof of no mutation. Revalidate
        // the whole original manifest, folder and asset set before replacing the
        // intent. Partial/adopted, missing or externally changed evidence stays put.
        val current = observeRecordedAdoption(record)
        require(current.adopted.values.none { it }) { "unresolved adoption mutations prevent reselection" }
        require(cursorFor(current.manifest.file) == request.candidate.cursor) {
            "newly selected adoption manifest revision changed"
        }
        if (!request.isGenerationCurrent()) throw DriveAssetStaleGenerationException(request.generation)
        return (assetTransfer ?: throw DriveAssetTransferException("adoption recovery storage is not configured"))
            .reselectAdoptionRecovery(record, request.candidate, current.manifest.etag)
    }

    private fun requireRecoveryRevision(record: DriveAdoptionRecovery, current: ConditionalDriveFile) {
        require(current.etag == record.resumeManifestEtag && cursorFor(current.file) == record.resumeManifestCursor) {
            "adoption manifest changed after its selected or compensated revision"
        }
    }

    /** Advance retry authority only from our successful conditional PUT and its exact readback. */
    private fun recordManifestCompensation(record: DriveAdoptionRecovery, restored: ConditionalDriveFile) {
        val fileId = record.candidate.reference.snapshotFileId
        val folderId = record.candidate.reference.folderId
        val oldScope = record.scope.copy(documentId = record.candidate.remoteDocumentId)
        val source = record.candidate.sourceFingerprint
        require(restored.file.id == fileId && restored.file.parents.orEmpty() == listOf(folderId) &&
            restored.file.appProperties.orEmpty() == record.originalManifestProperties) {
            "adoption compensation acknowledgement is inconsistent"
        }
        requireTaggedFile(restored.file, oldScope, folderId, source)
        val content = RemoteManifestCodec.decode(readManifestBytes(fileId), oldScope, source)
        require(content.canonicalDigest == record.originalManifestDigest) { "compensated manifest content changed" }
        val after = conditionalWrites.read(fileId) ?: throw IOException("compensated manifest is unavailable")
        require(after.etag == restored.etag && after.file.id == fileId &&
            cursorFor(after.file) == cursorFor(restored.file) && after.file.parents.orEmpty() == listOf(folderId) &&
            after.file.appProperties.orEmpty() == record.originalManifestProperties) {
            "compensated manifest revision changed before its receipt"
        }
        (assetTransfer ?: throw DriveAssetTransferException("adoption recovery storage is not configured"))
            .recordAdoptionCompensation(record, cursorFor(after.file), after.etag)
    }

    /**
     * Resume only the explicit candidate whose durable intent preceded mutation.
     * A complete prior commit is delivered without another PUT. A partial commit
     * is conditionally restored after a full exact preflight, then normal adoption
     * may retry with fresh revisions. Outages retain this record across recreation.
     */
    private fun recoverRecordedAdoption(request: AdoptionRequest, record: DriveAdoptionRecovery): RemoteDocumentMetadata? {
        require(record.scope == request.scope && record.candidate == request.candidate &&
            record.candidate.sourceFingerprint == request.localSourceFingerprint) {
            "selected adoption does not own the unresolved recovery intent"
        }
        val current = observeRecordedAdoption(record)
        if (!current.adopted.getValue(record.candidate.reference.snapshotFileId)) {
            requireRecoveryRevision(record, current.manifest)
        }
        if (current.adopted.values.all { it }) return RemoteDocumentMetadata(
            record.scope, current.folder.file.name.orEmpty(),
            referenceFor(current.folder.file, current.manifest.file, record.scope), cursorFor(current.manifest.file)
        )
        fun requireCurrent() {
            if (!request.isGenerationCurrent()) throw DriveAssetStaleGenerationException(request.generation)
        }
        if (current.adopted.values.any { it }) {
            val folderId = record.candidate.reference.folderId
            val fileId = record.candidate.reference.snapshotFileId
            if (current.adopted.getValue(folderId)) {
                requireCurrent()
                restoreFolder(folderId, current.folder.etag, record.originalFolderProperties)
            }
            current.assets.asReversed().filter { current.adopted.getValue(it.id) }.forEach { asset ->
                requireCurrent()
                conditionalWrites.update(asset.id, asset.etag, record.originalAssetProperties.getValue(asset.id))
            }
            if (current.adopted.getValue(fileId)) {
                val originalBytes = RemoteManifestCodec.encode(
                    record.scope.copy(documentId = record.candidate.remoteDocumentId), current.content.displayName,
                    current.content.snapshot, current.content.assets, current.content.sourceFingerprint
                )
                require(RemoteManifestCodec.canonicalDigest(originalBytes) == record.originalManifestDigest) {
                    "adoption recovery cannot reconstruct the original manifest"
                }
                requireCurrent()
                val restored = restoreManifest(fileId, current.manifest.etag, originalBytes, record.originalManifestProperties)
                recordManifestCompensation(record, restored)
            }
            require(observeRecordedAdoption(record).adopted.values.none { it }) { "adoption recovery compensation is incomplete" }
        }
        requireCurrent()
        return null
    }

    private fun getFolder(id: String): File? = try {
        service.files().get(id).setSupportsAllDrives(true)
            .setFields("id,name,parents,appProperties,headRevisionId,modifiedTime").execute()
    } catch (error: GoogleJsonResponseException) {
        if (error.statusCode == 404) null else throw error
    }

    private fun getFile(id: String): File? = try {
        service.files().get(id).setSupportsAllDrives(true)
            .setFields("id,name,mimeType,parents,appProperties,headRevisionId,modifiedTime").execute()
    } catch (error: GoogleJsonResponseException) {
        if (error.statusCode == 404) null else throw error
    }

    private fun generateDriveId(isGenerationCurrent: () -> Boolean): String {
        if (!isGenerationCurrent()) throw DriveAssetStaleGenerationException()
        val generated = service.files().generateIds().setCount(1).setSpace("drive").setFields("ids").execute()
            ?: throw IOException("Drive did not return generated IDs")
        return generated.ids.orEmpty().singleOrNull()?.takeIf {
            it.matches(Regex("[A-Za-z0-9_-]{1,512}"))
        } ?: throw IOException("Drive did not return exactly one stable ID")
    }

    private fun listAllFiles(query: String, fields: String, orderBy: String): List<File> {
        val files = mutableListOf<File>()
        val seen = mutableSetOf<String>()
        var token: String? = null
        do {
            if (token != null && !seen.add(token!!)) throw IllegalStateException(
                "Drive listing repeated continuation token '$token'"
            )
            val request = service.files().list().setQ(query).setFields(fields).setOrderBy(orderBy)
                .setPageSize(100).setSupportsAllDrives(true).setIncludeItemsFromAllDrives(true)
                .setPageToken(token)
            val page = request.execute()
            files += page.files.orEmpty()
            token = page.nextPageToken
        } while (token != null)
        return files
    }

    private fun manifestProperties(
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?,
        digest: String
    ): Map<String, String> = buildMap {
        put(SYNC_DOCUMENT_ID_APP_PROPERTY, scope.documentId.value)
        put(SYNC_SCHEMA_APP_PROPERTY, DRIVE_MANIFEST_SCHEMA_VERSION.toString())
        put("sotaware_account_id", scope.accountId)
        put("sotaware_backup_root_id", scope.backupRootId)
        put("sotaware_manifest_digest", digest)
        sourceFingerprint?.let { put(SYNC_SOURCE_FINGERPRINT_APP_PROPERTY, it.toProperty()) }
    }

    private fun folderProperties(scope: SyncScope, sourceFingerprint: SourceFingerprint?): Map<String, String> = buildMap {
        put(SYNC_DOCUMENT_ID_APP_PROPERTY, scope.documentId.value)
        put(SYNC_SCHEMA_APP_PROPERTY, DRIVE_MANIFEST_SCHEMA_VERSION.toString())
        put("sotaware_account_id", scope.accountId)
        put("sotaware_backup_root_id", scope.backupRootId)
        sourceFingerprint?.let { put(SYNC_SOURCE_FINGERPRINT_APP_PROPERTY, it.toProperty()) }
    }

    private fun referenceFor(folder: File, file: File, scope: SyncScope): RemoteReference {
        val properties = LinkedHashMap<String, String>().apply {
            putAll(folder.appProperties.orEmpty())
            putAll(file.appProperties.orEmpty())
        }
        require(properties[SYNC_DOCUMENT_ID_APP_PROPERTY] == scope.documentId.value)
        return RemoteReference(requireNotNull(folder.id), requireNotNull(file.id), Collections.unmodifiableMap(properties))
    }

    private fun referenceForAny(folder: File, file: File): RemoteReference {
        val properties = LinkedHashMap<String, String>().apply {
            putAll(folder.appProperties.orEmpty())
            putAll(file.appProperties.orEmpty())
        }
        require(properties[SYNC_DOCUMENT_ID_APP_PROPERTY].orEmpty().isNotBlank())
        return RemoteReference(requireNotNull(folder.id), requireNotNull(file.id), Collections.unmodifiableMap(properties))
    }

    private fun requireTaggedFolder(
        folder: File,
        scope: SyncScope,
        expectedSourceFingerprint: SourceFingerprint?
    ) {
        require(!folder.id.isNullOrBlank())
        require(folder.parents.orEmpty().contains(scope.backupRootId))
        val properties = folder.appProperties.orEmpty()
        require(properties[SYNC_DOCUMENT_ID_APP_PROPERTY] == scope.documentId.value)
        require(properties[SYNC_SCHEMA_APP_PROPERTY] == DRIVE_MANIFEST_SCHEMA_VERSION.toString())
        require(properties["sotaware_account_id"] == scope.accountId)
        require(properties["sotaware_backup_root_id"] == scope.backupRootId)
        requireRemoteSourceFingerprint(properties, expectedSourceFingerprint, "folder")
    }

    private fun requireTaggedFile(
        file: File,
        scope: SyncScope,
        expectedFolderId: String?,
        expectedSourceFingerprint: SourceFingerprint?
    ) {
        require(!file.id.isNullOrBlank())
        require(file.name == "annotations.json")
        expectedFolderId?.let { require(file.parents.orEmpty().contains(it)) }
        val properties = file.appProperties.orEmpty()
        require(properties[SYNC_DOCUMENT_ID_APP_PROPERTY] == scope.documentId.value)
        require(properties[SYNC_SCHEMA_APP_PROPERTY] == DRIVE_MANIFEST_SCHEMA_VERSION.toString())
        require(properties["sotaware_account_id"] == scope.accountId)
        require(properties["sotaware_backup_root_id"] == scope.backupRootId)
        requireRemoteSourceFingerprint(properties, expectedSourceFingerprint, "manifest")
    }

    private fun requireAdoptionFolder(
        folder: File,
        scope: SyncScope,
        expectedDocumentId: DocumentId,
        expectedSourceFingerprint: SourceFingerprint
    ) {
        require(!folder.id.isNullOrBlank())
        require(folder.parents.orEmpty().contains(scope.backupRootId))
        val p = folder.appProperties.orEmpty()
        require(p[SYNC_DOCUMENT_ID_APP_PROPERTY] == expectedDocumentId.value)
        require(p[SYNC_SCHEMA_APP_PROPERTY] == DRIVE_MANIFEST_SCHEMA_VERSION.toString())
        require(p["sotaware_account_id"] == scope.accountId)
        require(p["sotaware_backup_root_id"] == scope.backupRootId)
        require(p[SYNC_SOURCE_FINGERPRINT_APP_PROPERTY] == expectedSourceFingerprint.toProperty())
    }

    private fun requireAdoptionFile(
        file: File,
        scope: SyncScope,
        folderId: String,
        expectedDocumentId: DocumentId,
        expectedSourceFingerprint: SourceFingerprint
    ) {
        require(!file.id.isNullOrBlank())
        require(file.name == "annotations.json")
        require(file.parents.orEmpty().contains(folderId))
        val p = file.appProperties.orEmpty()
        require(p[SYNC_DOCUMENT_ID_APP_PROPERTY] == expectedDocumentId.value)
        require(p[SYNC_SCHEMA_APP_PROPERTY] == DRIVE_MANIFEST_SCHEMA_VERSION.toString())
        require(p["sotaware_account_id"] == scope.accountId)
        require(p["sotaware_backup_root_id"] == scope.backupRootId)
        require(p[SYNC_SOURCE_FINGERPRINT_APP_PROPERTY] == expectedSourceFingerprint.toProperty())
    }

    private fun requireUploadFolder(folder: File, request: UploadRequest, expectedFolderId: String?) {
        requireTaggedFolder(folder, request.scope, request.sourceFingerprint)
        expectedFolderId?.let { require(folder.id == it) }
    }

    private fun requireUploadFile(
        file: File,
        request: UploadRequest,
        expectedFolderId: String,
        expectedFileId: String?
    ) {
        requireTaggedFile(file, request.scope, expectedFolderId, request.sourceFingerprint)
        expectedFileId?.let { require(file.id == it) }
    }

    private fun requireRemoteSourceFingerprint(
        properties: Map<String, String>,
        expected: SourceFingerprint?,
        resource: String
    ) {
        val actual = properties[SYNC_SOURCE_FINGERPRINT_APP_PROPERTY]
        if (expected == null) require(actual == null) {
            "Drive $resource carries an unexpected source fingerprint"
        } else require(actual == expected.toProperty()) {
            "Drive $resource source fingerprint does not match request"
        }
    }

    private fun cursorFor(file: File): RemoteCursor = RemoteCursor(
        file.headRevisionId ?: file.version?.toString() ?: file.modifiedTime?.value?.toString()
            ?: error("Drive resource has no authoritative revision"),
        file.modifiedTime?.value
    )

    private data class AssetOwnershipState(
        val id: String,
        val descriptor: RemoteAssetDescriptor,
        val folderId: String,
        val originalProperties: Map<String, String>,
        val currentProperties: Map<String, String>,
        val etag: String
    )

    private fun SourceFingerprint.toProperty(): String =
        SourceFingerprint.SHA256_ALGORITHM + ":" + digestHex.lowercase(java.util.Locale.ROOT) + ":" + byteCount
}
/** Validate a snapshot before it crosses the remote boundary. */
fun requireValidSnapshot(snapshot: DocumentSnapshotV1) {
    validateSnapshot(snapshot)
}
