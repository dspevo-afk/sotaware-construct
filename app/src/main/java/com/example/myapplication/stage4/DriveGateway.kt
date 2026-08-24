package com.example.myapplication.stage4

import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.util.Base64
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

const val SYNC_DOCUMENT_ID_APP_PROPERTY: String = "sotaware_document_id"
const val SYNC_SCHEMA_APP_PROPERTY: String = "sotaware_snapshot_schema"
const val SYNC_SOURCE_FINGERPRINT_APP_PROPERTY: String = "sotaware_source_fingerprint"

internal fun SourceFingerprint.toDriveProperty(): String =
    "${algorithm}:${digestHex}:${byteCount}"

internal fun sourceFingerprintFromDriveProperty(value: String?): SourceFingerprint? {
    if (value.isNullOrBlank()) return null
    val parts = value.split(':')
    if (parts.size != 3) return null
    return runCatching {
        SourceFingerprint(
            algorithm = parts[0],
            digestHex = parts[1],
            byteCount = parts[2].toLong()
        )
    }.getOrNull()
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
        require(folderId.isNotBlank()) { "remote folder id must not be blank" }
        require(snapshotFileId.isNotBlank()) { "remote snapshot file id must not be blank" }
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
    /** Complete photo bytes referenced by [snapshot], never filename-only metadata. */
    val photoFiles: Map<String, ByteArray> = emptyMap()
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
    snapshot.pages.values
        .flatMap { page -> page.photoPins }
        .flatMap { pin -> pin.imageFileNames }
        .toSet()

/**
 * Fail-closed validation for the photo sidecar of a typed snapshot.  This is
 * deliberately independent of Drive display names and prevents a JSON-only
 * payload from advancing a remote cursor.
 */
fun validatedPhotoFiles(
    snapshot: DocumentSnapshotV1,
    photoFiles: Map<String, ByteArray>
): Map<String, ByteArray> {
    val names = requiredPhotoFileNames(snapshot)
    require(names.all { name ->
        name.isNotBlank() && photoFiles[name]?.isNotEmpty() == true
    }) {
        "complete photo bytes are required for every referenced photo"
    }
    return names.associateWith { name -> photoFiles.getValue(name).copyOf() }
}

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
    /** Complete bytes for every photo referenced by [snapshot]. */
    val photoFiles: Map<String, ByteArray> = emptyMap()
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
    data class Downloaded(val remote: RemoteSnapshotEnvelope) : DownloadResult()
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

/**
 * Deterministic in-memory Drive used by the Stage 4 JVM tests. It models
 * stable IDs, app properties, server revisions, real continuation tokens, a
 * read-only lookup, and a final-commit fence. The fence is checked before the
 * remote record is changed, so a stale generation cannot mutate the fake.
 */
class FakeDriveGateway(
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) : DriveGateway {
    data class RemoteRecord(
        val scope: SyncScope,
        val displayName: String,
        val reference: RemoteReference,
        val cursor: RemoteCursor,
        val snapshot: DocumentSnapshotV1,
        val sourceFingerprint: SourceFingerprint? = null,
        val photoFiles: Map<String, ByteArray> = emptyMap()
    )

    data class Call(
        val operation: String,
        val scope: SyncScope,
        val generation: Long? = null
    )

    private val remote = LinkedHashMap<SyncScope, RemoteRecord>()
    private val folders = LinkedHashMap<String, RemoteFolder>()
    private val folderFiles = LinkedHashMap<String, MutableList<RemoteFile>>()
    private val lock = Mutex()
    private val finalCommitLocks = ConcurrentHashMap<SyncScope, Mutex>()
    private val activeFinalCommits = AtomicInteger(0)
    private val activeFinalCommitsByScope = ConcurrentHashMap<SyncScope, AtomicInteger>()
    private val maxConcurrentFinalCommitsByScope = ConcurrentHashMap<SyncScope, AtomicInteger>()
    private val revisionCounter = AtomicInteger(0)

    /** Set by a test to suspend a request after preparation and before commit. */
    @Volatile
    var beforeFinalCommit: (suspend (UploadRequest) -> Unit)? = null

    /** Set by a test to suspend after lease admission inside the final mutation section. */
    @Volatile
    var insideFinalMutation: (suspend (UploadRequest) -> Unit)? = null

    @Volatile
    var failUpload: DriveFailure? = null

    @Volatile
    var failDownload: DriveFailure? = null

    /** Set by a test to suspend a download before its payload is accepted. */
    @Volatile
    var beforeDownload: (suspend (SyncScope, RemoteReference) -> Unit)? = null
    var beforeAdopt: (suspend (AdoptionRequest) -> Unit)? = null

    /** Set by a test to model a Drive revision changing during media transfer. */
    @Volatile
    var mutateRevisionDuringDownload: Boolean = false

    @Volatile
    var pageSize: Int = 100

    val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
    val folderPageTokens: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    val filePageTokens: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    val createdFolderCount: AtomicInteger = AtomicInteger(0)
    val createdFileCount: AtomicInteger = AtomicInteger(0)

    @Volatile
    var maxConcurrentFinalCommits: Int = 0
        private set

    /** Per-scope evidence that independent workers never overlap mutations. */
    fun maxConcurrentFinalCommits(scope: SyncScope): Int =
        maxConcurrentFinalCommitsByScope[scope]?.get() ?: 0

    private data class RemoteFolder(
        val id: String,
        val parentId: String,
        val name: String,
        val appProperties: Map<String, String>
    )

    private data class RemoteFile(
        val id: String,
        val folderId: String,
        val name: String,
        val appProperties: Map<String, String>,
        val cursor: RemoteCursor,
        val scope: SyncScope
    )

    override suspend fun find(scope: SyncScope): RemoteLookup = find(scope, null)

    override suspend fun find(
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?
    ): RemoteLookup {
        calls += Call("find", scope)
        return try {
            val matchingFolder = paginateFolders(scope.backupRootId)
                .firstOrNull {
                    it.appProperties[SYNC_DOCUMENT_ID_APP_PROPERTY] == scope.documentId.value
                }
            if (matchingFolder == null && sourceFingerprint != null) {
                val adoptionFolder = paginateFolders(scope.backupRootId).firstOrNull {
                    it.appProperties[SYNC_SOURCE_FINGERPRINT_APP_PROPERTY] == sourceFingerprint.toDriveProperty()
                }
                if (adoptionFolder != null) {
                    val adoptionFile = paginateFiles(adoptionFolder.id).firstOrNull {
                        it.appProperties[SYNC_SOURCE_FINGERPRINT_APP_PROPERTY] == sourceFingerprint.toDriveProperty()
                    }
                    val adoptionRemote = adoptionFile?.let { file ->
                        lock.withLock {
                            remote.values.firstOrNull {
                                it.scope.accountId == scope.accountId &&
                                    it.scope.backupRootId == scope.backupRootId &&
                                    it.reference.folderId == adoptionFolder.id &&
                                    it.reference.snapshotFileId == file.id
                            }
                        }
                    }
                    if (adoptionRemote != null) {
                        return RemoteLookup.PendingAdoption(
                            RemoteAdoptionCandidate(
                                accountId = scope.accountId,
                                backupRootId = scope.backupRootId,
                                remoteDocumentId = adoptionRemote.scope.documentId,
                                sourceFingerprint = sourceFingerprint,
                                displayName = adoptionRemote.displayName,
                                reference = adoptionRemote.reference,
                                cursor = adoptionRemote.cursor
                            )
                        )
                    }
                }
                return RemoteLookup.NotFound
            }
            if (matchingFolder == null) return RemoteLookup.NotFound
            val matchingFile = paginateFiles(matchingFolder.id)
                .filter { it.appProperties[SYNC_DOCUMENT_ID_APP_PROPERTY] == scope.documentId.value }
                .maxByOrNull { it.cursor.revision }
                ?: return RemoteLookup.NotFound
            val record = lock.withLock { remote[scope] }
                ?: return RemoteLookup.NotFound
            RemoteLookup.Found(record.toMetadata())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            RemoteLookup.Failed(DriveFailure.Pagination(error.message ?: "remote pagination failed", error))
        }
    }

    override suspend fun upload(request: UploadRequest): UploadResult {
        calls += Call("upload", request.scope, request.generation)
        failUpload?.let { return UploadResult.Rejected(it) }
        try {
            requireValidSnapshot(request.snapshot)
            validatedPhotoFiles(request.snapshot, request.photoFiles)
        } catch (error: IllegalArgumentException) {
            return UploadResult.Rejected(DriveFailure.Validation("upload payload validation failed", error))
        }

        // Preparation is deliberately outside the mutation lease. A newer
        // request may supersede this request while it is suspended here; the
        // lease then rejects it before any remote record is mutated.
        beforeFinalCommit?.invoke(request)
        val mutationSession = request.mutationLease.begin(
            request.generation,
            request.isGenerationCurrent
        ) ?: return UploadResult.Rejected(DriveFailure.StaleGeneration(request.generation))
        return try {
            mutationSession.mutate {
                finalCommitLocks.computeIfAbsent(request.scope) { Mutex() }.withLock {
                    val active = activeFinalCommits.incrementAndGet()
                    maxConcurrentFinalCommits = maxOf(maxConcurrentFinalCommits, active)
                    val activeForScope = activeFinalCommitsByScope
                        .computeIfAbsent(request.scope) { AtomicInteger(0) }
                        .incrementAndGet()
                    maxConcurrentFinalCommitsByScope
                        .computeIfAbsent(request.scope) { AtomicInteger(0) }
                        .updateAndGet { current -> maxOf(current, activeForScope) }
                    try {
                        if (!request.isGenerationCurrent()) {
                            return@withLock UploadResult.Rejected(
                                DriveFailure.StaleGeneration(request.generation),
                                mutationSession
                            )
                        }
                        insideFinalMutation?.invoke(request)
                        if (!request.isGenerationCurrent()) {
                            return@withLock UploadResult.Rejected(
                                DriveFailure.StaleGeneration(request.generation),
                                mutationSession
                            )
                        }
                        val current = lock.withLock { remote[request.scope] }
                        if (current == null && request.sourceFingerprint != null) {
                            when (val adoption = find(request.scope, request.sourceFingerprint)) {
                                is RemoteLookup.PendingAdoption -> return@withLock UploadResult.PendingAdoption(
                                    adoption.candidate,
                                    mutationSession
                                )
                                else -> Unit
                            }
                        }
                        if (current == null && request.expectedCursor != null) {
                            return@withLock UploadResult.Rejected(
                                DriveFailure.NotFound(
                                    "remote document disappeared while an accepted cursor was present"
                                ),
                                mutationSession
                            )
                        }
                        if (current != null && request.expectedCursor != current.cursor) {
                            return@withLock UploadResult.Conflict(current.toMetadata(), mutationSession)
                        }

                        val folderId = current?.reference?.folderId ?: createFolderForUpload(request)
                        val fileId = current?.reference?.snapshotFileId ?: idFactory().also {
                            createdFileCount.incrementAndGet()
                        }
                        val cursor = RemoteCursor("remote-r${revisionCounter.incrementAndGet()}")
                        val properties = buildMap {
                            put(SYNC_DOCUMENT_ID_APP_PROPERTY, request.scope.documentId.value)
                            put(SYNC_SCHEMA_APP_PROPERTY, DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION.toString())
                            request.sourceFingerprint?.let {
                                put(SYNC_SOURCE_FINGERPRINT_APP_PROPERTY, it.toDriveProperty())
                            }
                        }
                        val reference = RemoteReference(folderId, fileId, properties)
                        val next = RemoteRecord(
                            scope = request.scope,
                            displayName = request.displayName,
                            reference = reference,
                            cursor = cursor,
                            snapshot = request.snapshot,
                            sourceFingerprint = request.sourceFingerprint,
                            photoFiles = validatedPhotoFiles(request.snapshot, request.photoFiles)
                        )
                        lock.withLock {
                            remote[request.scope] = next
                            val folder = folders[folderId]
                            if (folder != null) {
                                val files = folderFiles.getOrPut(folderId) { mutableListOf() }
                                val index = files.indexOfFirst { it.id == fileId }
                                val file = RemoteFile(fileId, folderId, "annotations.json", properties, cursor, request.scope)
                                if (index >= 0) files[index] = file else files += file
                            }
                        }
                        UploadResult.Uploaded(next.toEnvelope(), mutationSession)
                    } finally {
                        activeFinalCommitsByScope.getValue(request.scope).decrementAndGet()
                        activeFinalCommits.decrementAndGet()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            mutationSession.close()
            throw cancelled
        } catch (error: Throwable) {
            UploadResult.Rejected(
                DriveFailure.Transfer("fake upload", error.message ?: error.toString(), error),
                mutationSession
            )
        }
    }

    override suspend fun adopt(request: AdoptionRequest): AdoptionResult {
        calls += Call("adopt", request.scope, request.generation)
        val mutationSession = request.mutationLease.begin(
            request.generation,
            request.isGenerationCurrent
        ) ?: return AdoptionResult.Rejected(DriveFailure.StaleGeneration(request.generation))
        return try {
            mutationSession.mutate {
                if (!request.isGenerationCurrent()) {
                    return@mutate AdoptionResult.Rejected(
                        DriveFailure.StaleGeneration(request.generation),
                        mutationSession
                    )
                }
                beforeAdopt?.invoke(request)
                lock.withLock {
                    val candidateScope = SyncScope(
                        request.scope.accountId,
                        request.scope.backupRootId,
                        request.candidate.remoteDocumentId
                    )
                    val current = remote[candidateScope]
                        ?: return@withLock AdoptionResult.Rejected(
                            DriveFailure.NotFound("selected adoption candidate no longer exists"),
                            mutationSession
                        )
                    if (remote.containsKey(request.scope)) {
                        return@withLock AdoptionResult.Rejected(
                            DriveFailure.Validation("the local synchronization scope already has a remote document"),
                            mutationSession
                        )
                    }
                    if (current.reference != request.candidate.reference ||
                        current.cursor != request.candidate.cursor ||
                        current.sourceFingerprint != request.localSourceFingerprint ||
                        current.reference.appProperties[SYNC_DOCUMENT_ID_APP_PROPERTY] !=
                            request.candidate.remoteDocumentId.value ||
                        current.reference.appProperties[SYNC_SOURCE_FINGERPRINT_APP_PROPERTY] !=
                            request.localSourceFingerprint.toDriveProperty()
                    ) {
                        return@withLock AdoptionResult.Rejected(
                            DriveFailure.Validation("selected adoption candidate changed or has an incompatible source fingerprint"),
                            mutationSession
                        )
                    }
                    val properties = LinkedHashMap(current.reference.appProperties).apply {
                        this[SYNC_DOCUMENT_ID_APP_PROPERTY] = request.scope.documentId.value
                    }
                    val localReference = RemoteReference(
                        current.reference.folderId,
                        current.reference.snapshotFileId,
                        properties
                    )
                    val adopted = current.copy(
                        scope = request.scope,
                        reference = localReference
                    )
                    remote.remove(candidateScope)
                    remote[request.scope] = adopted
                    folders[localReference.folderId]?.let { folder ->
                        folders[localReference.folderId] = folder.copy(appProperties = properties)
                    }
                    folderFiles[localReference.folderId]?.replaceAll { file ->
                        if (file.id == localReference.snapshotFileId) {
                            file.copy(appProperties = properties, scope = request.scope)
                        } else file
                    }
                    AdoptionResult.Adopted(
                        remote = adopted.toMetadata(),
                        adoptedRemoteDocumentId = request.candidate.remoteDocumentId,
                        mutationSession = mutationSession
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            mutationSession.close()
            throw cancelled
        } catch (error: Throwable) {
            AdoptionResult.Rejected(
                DriveFailure.Transfer("adopt remote document", error.message ?: error.toString(), error),
                mutationSession
            )
        }
    }

    override suspend fun download(
        scope: SyncScope,
        reference: RemoteReference,
        expectedCursor: RemoteCursor?
    ): DownloadResult {
        calls += Call("download", scope)
        failDownload?.let { return DownloadResult.Failed(it) }
        val current = lock.withLock { remote[scope] }
            ?: return DownloadResult.NotFound
        if (current.reference.folderId != reference.folderId ||
            current.reference.snapshotFileId != reference.snapshotFileId ||
            current.reference.appProperties[SYNC_DOCUMENT_ID_APP_PROPERTY] != scope.documentId.value
        ) {
            return DownloadResult.Failed(
                DriveFailure.Validation("remote reference does not belong to the requested SyncScope")
            )
        }
        if (expectedCursor != null && expectedCursor != current.cursor) {
            return DownloadResult.Failed(
                DriveFailure.Validation("remote cursor changed during download")
            )
        }
        beforeDownload?.invoke(scope, reference)
        if (mutateRevisionDuringDownload) {
            lock.withLock {
                remote[scope]?.let { currentRecord ->
                    val cursor = RemoteCursor("remote-r${revisionCounter.incrementAndGet()}")
                    remote[scope] = currentRecord.copy(cursor = cursor)
                    folderFiles[currentRecord.reference.folderId]?.replaceAll { file ->
                        if (file.id == currentRecord.reference.snapshotFileId) file.copy(cursor = cursor) else file
                    }
                }
            }
        }
        val reread = lock.withLock { remote[scope] } ?: return DownloadResult.NotFound
        if (reread.cursor != current.cursor) {
            return DownloadResult.Failed(
                DriveFailure.Validation("remote cursor changed during download")
            )
        }
        try {
            requireValidSnapshot(reread.snapshot)
            validatedPhotoFiles(reread.snapshot, reread.photoFiles)
        } catch (error: IllegalArgumentException) {
            return DownloadResult.Failed(DriveFailure.Validation("remote payload validation failed", error))
        }
        return DownloadResult.Downloaded(reread.toEnvelope())
    }

    /** Test-only corruption hook; real uploads and seeds always validate first. */
    internal suspend fun replaceRemoteSnapshotForTesting(
        scope: SyncScope,
        snapshot: DocumentSnapshotV1
    ) {
        lock.withLock {
            remote[scope]?.let { remote[scope] = it.copy(snapshot = snapshot) }
        }
    }

    /** Seeds a complete remote record for conflict/pagination tests. */
    suspend fun seed(
        scope: SyncScope,
        displayName: String,
        snapshot: DocumentSnapshotV1,
        cursor: RemoteCursor = RemoteCursor("remote-r${revisionCounter.incrementAndGet()}"),
        sourceFingerprint: SourceFingerprint? = null,
        photoFiles: Map<String, ByteArray> = emptyMap()
    ): RemoteSnapshotEnvelope {
        requireValidSnapshot(snapshot)
        val expected = lock.withLock { remote[scope]?.cursor }
        val lease = ScopeRemoteMutationLease()
        lease.advance(1L)
        val request = UploadRequest(
            scope = scope,
            displayName = displayName,
            snapshot = snapshot,
            expectedCursor = expected,
            generation = 1L,
            mutationLease = lease,
            isGenerationCurrent = { lease.isGenerationCurrent(1L) },
            sourceFingerprint = sourceFingerprint,
            photoFiles = if (requiredPhotoFileNames(snapshot).isEmpty()) {
                emptyMap()
            } else if (photoFiles.isEmpty()) {
                // Deterministic fixture bytes keep test-only seeds complete;
                // production callers must provide actual bytes through the
                // coordinator bridge.
                requiredPhotoFileNames(snapshot).associateWith { it.toByteArray() }
            } else photoFiles
        )
        val result = upload(request)
        val envelope = when (result) {
            is UploadResult.Uploaded -> result.remote
            else -> {
                result.mutationSession?.close()
                error("seed failed: $result")
            }
        }
        result.mutationSession?.close()
        return if (envelope.cursor == cursor) envelope else lock.withLock {
            val current = remote.getValue(scope)
            val changed = current.copy(cursor = cursor)
            remote[scope] = changed
            folderFiles[changed.reference.folderId]?.replaceAll { file ->
                if (file.id == changed.reference.snapshotFileId) file.copy(cursor = cursor) else file
            }
            changed.toEnvelope()
        }
    }

    suspend fun record(scope: SyncScope): RemoteRecord? = lock.withLock { remote[scope] }

    /** Test-only removal used to verify accepted-cursor fail-closed behavior. */
    internal suspend fun removeRemoteForTesting(scope: SyncScope) {
        lock.withLock {
            val removed = remote.remove(scope) ?: return@withLock
            folders.remove(removed.reference.folderId)
            folderFiles.remove(removed.reference.folderId)
        }
    }

    private suspend fun createFolderForUpload(request: UploadRequest): String = lock.withLock {
        val folderId = idFactory()
        val properties = buildMap {
            put(SYNC_DOCUMENT_ID_APP_PROPERTY, request.scope.documentId.value)
            request.sourceFingerprint?.let {
                put(SYNC_SOURCE_FINGERPRINT_APP_PROPERTY, it.toDriveProperty())
            }
        }
        folders[folderId] = RemoteFolder(folderId, request.scope.backupRootId, request.displayName, properties)
        folderFiles[folderId] = mutableListOf()
        createdFolderCount.incrementAndGet()
        folderId
    }

    private suspend fun paginateFolders(parentId: String): List<RemoteFolder> {
        val all = lock.withLock { folders.values.filter { it.parentId == parentId }.sortedBy { it.id } }
        return paginate(all, folderPageTokens)
    }

    private suspend fun paginateFiles(folderId: String): List<RemoteFile> {
        val all = lock.withLock { folderFiles[folderId].orEmpty().toList().sortedBy { it.id } }
        return paginate(all, filePageTokens)
    }

    private fun <T> paginate(items: List<T>, tokens: MutableList<String?>): List<T> {
        val size = pageSize.also { require(it > 0) { "pageSize must be positive" } }
        val result = mutableListOf<T>()
        var start = 0
        var token: String? = null
        do {
            tokens += token
            val end = minOf(start + size, items.size)
            result += items.subList(start, end)
            start = end
            token = if (start < items.size) start.toString() else null
        } while (token != null)
        return result
    }

    private fun RemoteRecord.toMetadata(): RemoteDocumentMetadata =
        RemoteDocumentMetadata(scope, displayName, reference, cursor)

    private fun RemoteRecord.toEnvelope(): RemoteSnapshotEnvelope =
        RemoteSnapshotEnvelope(scope, displayName, reference, cursor, snapshot, sourceFingerprint, photoFiles)
}

/**
 * Google Drive adapter. It identifies resources only with appProperties and
 * stable IDs; display names are written as metadata and never used as an
 * identity query. Listing follows every continuation token, and [find] is
 * strictly read-only.
 */
class GoogleDriveGateway(
    private val service: Drive,
    private val accountId: String
) : DriveGateway {
    /**
     * The Drive client JSON factory is for Drive model classes and requires
     * public @Key fields for custom DTOs.  The payload is an application DTO,
     * so use the same Gson reflection codec as local canonical persistence;
     * this also keeps adoption rewrite and download validation symmetric.
     */
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    override suspend fun find(scope: SyncScope): RemoteLookup = find(scope, null)

    override suspend fun find(
        scope: SyncScope,
        sourceFingerprint: SourceFingerprint?
    ): RemoteLookup = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            if (scope.accountId != accountId) {
                return@withContext RemoteLookup.Failed(
                    DriveFailure.NotAuthenticated("gateway account does not match SyncScope")
                )
            }
            val folders = listAllFiles(
                query = "'${escapeQuery(scope.backupRootId)}' in parents and mimeType='application/vnd.google-apps.folder' and trashed=false",
                fields = "nextPageToken, files(id,name,appProperties,parents)",
                orderBy = "name"
            )
            val folder = folders.firstOrNull {
                it.appProperties?.get(SYNC_DOCUMENT_ID_APP_PROPERTY) == scope.documentId.value
            }
            val adoptionFolder = if (folder == null && sourceFingerprint != null) {
                folders.firstOrNull {
                    it.appProperties?.get(SYNC_SOURCE_FINGERPRINT_APP_PROPERTY) == sourceFingerprint.toDriveProperty()
                }
            } else null
            val selectedFolder = folder ?: adoptionFolder
                ?: return@withContext RemoteLookup.NotFound
            val file = listAllFiles(
                query = "'${escapeQuery(requireNotNull(selectedFolder.id))}' in parents and trashed=false",
                fields = "nextPageToken, files(id,name,appProperties,headRevisionId,modifiedTime)",
                orderBy = "modifiedTime desc"
            ).firstOrNull {
                it.appProperties?.get(SYNC_DOCUMENT_ID_APP_PROPERTY) == scope.documentId.value ||
                    (adoptionFolder != null &&
                        it.appProperties?.get(SYNC_SOURCE_FINGERPRINT_APP_PROPERTY) == sourceFingerprint?.toDriveProperty())
            }
                ?: return@withContext RemoteLookup.NotFound
            val cursor = cursorFor(file)
            if (adoptionFolder != null) {
                val remoteDocumentId = file.appProperties?.get(SYNC_DOCUMENT_ID_APP_PROPERTY)
                    ?.let { runCatching { DocumentId.parse(it) }.getOrNull() }
                    ?: return@withContext RemoteLookup.Failed(
                        DriveFailure.Validation("same-source remote resource has no valid DocumentId")
                    )
                return@withContext RemoteLookup.PendingAdoption(
                    RemoteAdoptionCandidate(
                        accountId = scope.accountId,
                        backupRootId = scope.backupRootId,
                        remoteDocumentId = remoteDocumentId,
                        sourceFingerprint = requireNotNull(sourceFingerprint),
                        displayName = selectedFolder.name.orEmpty(),
                        reference = referenceForAny(selectedFolder, file),
                        cursor = cursor
                    )
                )
            }
            RemoteLookup.Found(
                RemoteDocumentMetadata(
                    scope = scope,
                    displayName = selectedFolder.name.orEmpty(),
                    reference = referenceFor(selectedFolder, file, scope),
                    cursor = cursor
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            RemoteLookup.Failed(
                DriveFailure.Validation("remote metadata validation failed", error)
            )
        } catch (error: Throwable) {
            RemoteLookup.Failed(DriveFailure.Unknown("find remote document", error.message ?: error.toString(), error))
        }
    }

    override suspend fun upload(request: UploadRequest): UploadResult = withContext(kotlinx.coroutines.Dispatchers.IO) {
        var mutationSession: RemoteMutationSession? = null
        try {
            requireValidSnapshot(request.snapshot)
            val photoFiles = validatedPhotoFiles(request.snapshot, request.photoFiles)
            mutationSession = request.mutationLease.begin(
                request.generation,
                request.isGenerationCurrent
            ) ?: return@withContext UploadResult.Rejected(
                DriveFailure.StaleGeneration(request.generation)
            )
            mutationSession!!.mutate {
                // The lease is the generation linearization point. All reads
                // needed to choose a stable ID and every subsequent Drive
                // mutation remain inside the same per-scope lease.
                if (!request.isGenerationCurrent()) {
                    return@mutate UploadResult.Rejected(
                        DriveFailure.StaleGeneration(request.generation),
                        mutationSession
                    )
                }
                val current = when (val found = find(request.scope, request.sourceFingerprint)) {
                    is RemoteLookup.Found -> found.metadata
                    RemoteLookup.NotFound -> null
                    is RemoteLookup.PendingAdoption -> return@mutate UploadResult.PendingAdoption(
                        found.candidate,
                        mutationSession!!
                    )
                    is RemoteLookup.Failed -> return@mutate UploadResult.Rejected(found.failure, mutationSession)
                }
                if (current == null && request.expectedCursor != null) {
                    return@mutate UploadResult.Rejected(
                        DriveFailure.NotFound(
                            "remote document disappeared while an accepted cursor was present"
                        ),
                        mutationSession
                    )
                }
                if (current != null && request.expectedCursor != current.cursor) {
                    return@mutate UploadResult.Conflict(current, mutationSession!!)
                }
                val folder = if (current == null) {
                    if (!request.isGenerationCurrent()) {
                        return@mutate UploadResult.Rejected(
                            DriveFailure.StaleGeneration(request.generation),
                            mutationSession
                        )
                    }
                    val metadata = File()
                        .setName(request.displayName)
                        .setMimeType("application/vnd.google-apps.folder")
                        .setParents(listOf(request.scope.backupRootId))
                        .setAppProperties(buildMap {
                            put(SYNC_DOCUMENT_ID_APP_PROPERTY, request.scope.documentId.value)
                            request.sourceFingerprint?.let {
                                put(SYNC_SOURCE_FINGERPRINT_APP_PROPERTY, it.toDriveProperty())
                            }
                        })
                    val createFolder = service.files().create(metadata)
                        .setSupportsAllDrives(true)
                        .setFields("id,name,appProperties")
                    // A create is conditional as well. If the installed
                    // transport drops this header, the adapter fails closed
                    // instead of pretending a collection POST is atomic.
                    createFolder.requestHeaders.setIfNoneMatch("*")
                    try {
                        createFolder.execute()
                    } catch (precondition: GoogleJsonResponseException) {
                        if (precondition.statusCode == 412) {
                            return@mutate preconditionConflict(request, mutationSession!!)
                        }
                        throw precondition
                    }
                } else {
                    service.files().get(current.reference.folderId)
                        .setSupportsAllDrives(true)
                        .setFields("id,name,appProperties")
                        .execute()
                }
                requireTaggedFolder(folder, request.scope)
                if (!request.isGenerationCurrent()) {
                    return@mutate UploadResult.Rejected(
                        DriveFailure.StaleGeneration(request.generation),
                        mutationSession
                    )
                }
                val properties = mapOf(
                    SYNC_DOCUMENT_ID_APP_PROPERTY to request.scope.documentId.value,
                    SYNC_SCHEMA_APP_PROPERTY to DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION.toString()
                )
                val completeProperties = buildMap {
                    putAll(properties)
                    request.sourceFingerprint?.let {
                        put(SYNC_SOURCE_FINGERPRINT_APP_PROPERTY, it.toDriveProperty())
                    }
                }
                val payload = gson.toJson(
                    DrivePayload(
                        accountId = request.scope.accountId,
                        backupRootId = request.scope.backupRootId,
                        documentId = request.scope.documentId.value,
                        displayName = request.displayName,
                        snapshot = request.snapshot,
                        sourceFingerprint = request.sourceFingerprint?.toDriveProperty(),
                        photoFiles = photoFiles.mapValues { (_, bytes) -> Base64.encodeBase64String(bytes) }
                    )
                ).toByteArray(Charsets.UTF_8)
                val media = ByteArrayContent("application/json", payload)
                if (!request.isGenerationCurrent()) {
                    return@mutate UploadResult.Rejected(
                        DriveFailure.StaleGeneration(request.generation),
                        mutationSession
                    )
                }
                val file = if (current == null) {
                    val metadata = File()
                        .setName("annotations.json")
                        .setParents(listOf(folder.id))
                        .setAppProperties(completeProperties)
                    val createFile = service.files().create(metadata, media)
                        .setSupportsAllDrives(true)
                        .setFields("id,name,appProperties,headRevisionId,modifiedTime")
                    createFile.requestHeaders.setIfNoneMatch("*")
                    try {
                        createFile.execute()
                    } catch (precondition: GoogleJsonResponseException) {
                        if (precondition.statusCode == 412) {
                            return@mutate preconditionConflict(request, mutationSession!!)
                        }
                        throw precondition
                    }
                } else {
                    // The lookup cursor is only an observation. Re-read the
                    // file's authoritative revision and ETag, then attach
                    // If-Match to the actual update so a cross-device writer
                    // between lookup and execute cannot be overwritten.
                    val currentFileRequest = service.files().get(current.reference.snapshotFileId)
                        .setSupportsAllDrives(true)
                        .setFields("id,name,parents,appProperties,headRevisionId,modifiedTime")
                    val currentFile = currentFileRequest.execute()
                    requireTaggedFile(currentFile, request.scope)
                    if (cursorFor(currentFile) != current.cursor) {
                        return@mutate preconditionConflict(request, mutationSession!!)
                    }
                    val etag = currentFileRequest.lastResponseHeaders
                        ?.getFirstHeaderStringValue("ETag")
                        ?: return@mutate UploadResult.Rejected(
                            DriveFailure.Validation(
                                "Drive did not expose an ETag for conditional snapshot update"
                            ),
                            mutationSession
                        )
                    val update = service.files().update(
                        current.reference.snapshotFileId,
                        File().setAppProperties(completeProperties),
                        media
                    )
                        .setSupportsAllDrives(true)
                        .setFields("id,name,appProperties,headRevisionId,modifiedTime")
                    update.requestHeaders.setIfMatch(etag)
                    try {
                        update.execute()
                    } catch (precondition: GoogleJsonResponseException) {
                        if (precondition.statusCode == 412) {
                            return@mutate preconditionConflict(request, mutationSession!!)
                        }
                        throw precondition
                    }
                }
                requireTaggedFile(file, request.scope)
                val reference = referenceFor(folder, file, request.scope)
                val envelope = RemoteSnapshotEnvelope(
                    request.scope,
                    request.displayName,
                    reference,
                    cursorFor(file),
                    request.snapshot,
                    request.sourceFingerprint,
                    photoFiles
                )
                UploadResult.Uploaded(envelope, mutationSession!!)
            }
        } catch (cancelled: CancellationException) {
            mutationSession?.close()
            throw cancelled
        } catch (error: IllegalArgumentException) {
            UploadResult.Rejected(
                DriveFailure.Validation("upload payload validation failed", error),
                mutationSession
            )
        } catch (error: Throwable) {
            UploadResult.Rejected(
                DriveFailure.Transfer("upload snapshot", error.message ?: error.toString(), error),
                mutationSession
            )
        }
    }

    override suspend fun adopt(request: AdoptionRequest): AdoptionResult =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            var mutationSession: RemoteMutationSession? = null
            try {
                if (request.scope.accountId != accountId) {
                    return@withContext AdoptionResult.Rejected(
                        DriveFailure.NotAuthenticated("gateway account does not match SyncScope")
                    )
                }
                mutationSession = request.mutationLease.begin(
                    request.generation,
                    request.isGenerationCurrent
                ) ?: return@withContext AdoptionResult.Rejected(
                    DriveFailure.StaleGeneration(request.generation)
                )
                mutationSession!!.mutate {
                    if (!request.isGenerationCurrent()) {
                        return@mutate AdoptionResult.Rejected(
                            DriveFailure.StaleGeneration(request.generation),
                            mutationSession
                        )
                    }
                    val folderRequest = service.files().get(request.candidate.reference.folderId)
                        .setSupportsAllDrives(true)
                        .setFields("id,name,parents,appProperties")
                    val folder = folderRequest.execute()
                        ?: return@mutate AdoptionResult.Rejected(
                            DriveFailure.NotFound("selected adoption folder no longer exists"),
                            mutationSession
                        )
                    val folderProperties = folder.appProperties.orEmpty()
                    require(
                        folder.id == request.candidate.reference.folderId &&
                            folder.parents.orEmpty().contains(request.scope.backupRootId) &&
                            folderProperties[SYNC_DOCUMENT_ID_APP_PROPERTY] ==
                                request.candidate.remoteDocumentId.value &&
                            folderProperties[SYNC_SOURCE_FINGERPRINT_APP_PROPERTY] ==
                                request.localSourceFingerprint.toDriveProperty()
                    ) { "selected adoption folder identity changed" }
                    val fileRequest = service.files().get(request.candidate.reference.snapshotFileId)
                        .setSupportsAllDrives(true)
                        .setFields("id,name,parents,appProperties,headRevisionId,modifiedTime")
                    val file = fileRequest.execute()
                        ?: return@mutate AdoptionResult.Rejected(
                            DriveFailure.NotFound("selected adoption snapshot no longer exists"),
                            mutationSession
                        )
                    val fileProperties = file.appProperties.orEmpty()
                    require(
                        file.id == request.candidate.reference.snapshotFileId &&
                            file.parents.orEmpty().contains(folder.id) &&
                            fileProperties[SYNC_DOCUMENT_ID_APP_PROPERTY] ==
                                request.candidate.remoteDocumentId.value &&
                            fileProperties[SYNC_SOURCE_FINGERPRINT_APP_PROPERTY] ==
                                request.localSourceFingerprint.toDriveProperty() &&
                            cursorFor(file) == request.candidate.cursor
                    ) { "selected adoption snapshot identity changed" }
                    val folderEtag = folderRequest.lastResponseHeaders
                        ?.getFirstHeaderStringValue("ETag")
                        ?: return@mutate AdoptionResult.Rejected(
                            DriveFailure.Validation("Drive did not expose a folder ETag for conditional adoption"),
                            mutationSession
                        )
                    val fileEtag = fileRequest.lastResponseHeaders
                        ?.getFirstHeaderStringValue("ETag")
                        ?: return@mutate AdoptionResult.Rejected(
                            DriveFailure.Validation("Drive did not expose a snapshot ETag for conditional adoption"),
                            mutationSession
                        )

                    // Adoption changes the local app-generated DocumentId, so
                    // the embedded canonical envelope must be rewritten before
                    // the file is linked.  A metadata-only relink would leave
                    // the first download rejecting its own payload.
                    val payloadOutput = ByteArrayOutputStream()
                    service.files().get(file.id)
                        .setSupportsAllDrives(true)
                        .executeMediaAndDownloadTo(payloadOutput)
                    val originalPayload = gson.fromJson(
                        payloadOutput.toString(Charsets.UTF_8.name()),
                        DrivePayload::class.java
                    ) ?: return@mutate AdoptionResult.Rejected(
                        DriveFailure.Validation("selected adoption payload is missing"),
                        mutationSession
                    )
                    require(
                        originalPayload.accountId == request.candidate.accountId &&
                            originalPayload.backupRootId == request.candidate.backupRootId &&
                            originalPayload.documentId == request.candidate.remoteDocumentId.value &&
                            sourceFingerprintFromDriveProperty(originalPayload.sourceFingerprint) ==
                                request.localSourceFingerprint
                    ) {
                        "selected adoption payload identity changed " +
                            "(account=${originalPayload.accountId}, root=${originalPayload.backupRootId}, " +
                            "documentId=${originalPayload.documentId}, fingerprint=${originalPayload.sourceFingerprint})"
                    }
                    val originalSnapshot = requireNotNull(originalPayload.snapshot) {
                        "selected adoption canonical snapshot is missing"
                    }
                    requireValidSnapshot(originalSnapshot)
                    val originalPhotoFiles = originalPayload.photoFiles.orEmpty().mapValues { (name, encoded) ->
                        Base64.decodeBase64(encoded)
                            ?: throw IllegalArgumentException("selected adoption photo is not valid base64: $name")
                    }
                    validatedPhotoFiles(originalSnapshot, originalPhotoFiles)
                    val rewrittenPayload = gson.toJson(
                        originalPayload.copy(
                            accountId = request.scope.accountId,
                            backupRootId = request.scope.backupRootId,
                            documentId = request.scope.documentId.value
                        )
                    ).toByteArray(Charsets.UTF_8)
                    val rewrittenMedia = ByteArrayContent("application/json", rewrittenPayload)

                    val localFolderProperties = LinkedHashMap(folderProperties).apply {
                        this[SYNC_DOCUMENT_ID_APP_PROPERTY] = request.scope.documentId.value
                    }
                    val localFileProperties = LinkedHashMap(fileProperties).apply {
                        this[SYNC_DOCUMENT_ID_APP_PROPERTY] = request.scope.documentId.value
                    }
                    if (!request.isGenerationCurrent()) {
                        return@mutate AdoptionResult.Rejected(
                            DriveFailure.StaleGeneration(request.generation),
                            mutationSession
                        )
                    }
                    val fileUpdate = service.files().update(
                        file.id,
                        File().setAppProperties(localFileProperties),
                        rewrittenMedia
                    ).setSupportsAllDrives(true)
                        .setFields("id,name,parents,appProperties,headRevisionId,modifiedTime")
                    fileUpdate.requestHeaders.setIfMatch(fileEtag)
                    val updatedFile = try {
                        fileUpdate.execute()
                    } catch (precondition: GoogleJsonResponseException) {
                        if (precondition.statusCode == 412) {
                            return@mutate AdoptionResult.Rejected(
                                DriveFailure.Conflict("selected adoption snapshot changed before payload rewrite"),
                                mutationSession
                            )
                        }
                        throw precondition
                    } ?: throw IllegalStateException("Drive returned no file after adoption payload rewrite")
                    val updatedFileEtag = fileUpdate.lastResponseHeaders
                        ?.getFirstHeaderStringValue("ETag")
                        ?: throw IllegalStateException("Drive did not expose an ETag after adoption payload rewrite")

                    suspend fun restoreOriginalFile() {
                        val rollbackFile = service.files().update(
                            file.id,
                            File().setAppProperties(fileProperties),
                            ByteArrayContent("application/json", payloadOutput.toByteArray())
                        ).setSupportsAllDrives(true)
                            .setFields("id,name,parents,appProperties,headRevisionId,modifiedTime")
                        rollbackFile.requestHeaders.setIfMatch(updatedFileEtag)
                        rollbackFile.execute()
                    }
                    try {
                        requireTaggedFile(updatedFile, request.scope)
                    } catch (error: Throwable) {
                        try {
                            restoreOriginalFile()
                        } catch (rollback: Throwable) {
                            throw IllegalStateException("adoption rollback failed after file validation", rollback)
                        }
                        throw error
                    }

                    if (!request.isGenerationCurrent()) {
                        restoreOriginalFile()
                        return@mutate AdoptionResult.Rejected(
                            DriveFailure.StaleGeneration(request.generation),
                            mutationSession
                        )
                    }
                    val folderUpdate = service.files().update(
                        folder.id,
                        File().setAppProperties(localFolderProperties)
                    ).setSupportsAllDrives(true).setFields("id,name,parents,appProperties")
                    folderUpdate.requestHeaders.setIfMatch(folderEtag)
                    val updatedFolder = try {
                        folderUpdate.execute()
                    } catch (precondition: GoogleJsonResponseException) {
                        try {
                            restoreOriginalFile()
                        } catch (rollback: Throwable) {
                            throw IllegalStateException("adoption rollback failed after folder precondition", rollback)
                        }
                        if (precondition.statusCode == 412) {
                            return@mutate AdoptionResult.Rejected(
                                DriveFailure.Conflict("selected adoption folder changed before linking"),
                                mutationSession
                            )
                        }
                        throw precondition
                    } catch (error: Throwable) {
                        try {
                            restoreOriginalFile()
                        } catch (rollback: Throwable) {
                            throw IllegalStateException("adoption rollback failed after folder update", rollback)
                        }
                        throw error
                    } ?: throw IllegalStateException("Drive returned no folder after adoption")
                    try {
                        requireTaggedFolder(updatedFolder, request.scope)
                    } catch (error: Throwable) {
                        try {
                            val updatedFolderEtag = folderUpdate.lastResponseHeaders
                                ?.getFirstHeaderStringValue("ETag")
                                ?: throw IllegalStateException("Drive did not expose an ETag for folder rollback")
                            val rollbackFolder = service.files().update(
                                folder.id,
                                File().setAppProperties(folderProperties)
                            ).setSupportsAllDrives(true).setFields("id,appProperties")
                            rollbackFolder.requestHeaders.setIfMatch(updatedFolderEtag)
                            rollbackFolder.execute()
                            restoreOriginalFile()
                        } catch (rollback: Throwable) {
                            throw IllegalStateException("adoption rollback failed after folder validation", rollback)
                        }
                        throw error
                    }
                    val reference = RemoteReference(folder.id, file.id, localFileProperties)
                    AdoptionResult.Adopted(
                        remote = RemoteDocumentMetadata(
                            scope = request.scope,
                            displayName = updatedFolder.name.orEmpty(),
                            reference = reference,
                            cursor = cursorFor(updatedFile)
                        ),
                        adoptedRemoteDocumentId = request.candidate.remoteDocumentId,
                        mutationSession = mutationSession!!
                    )
                }
            } catch (cancelled: CancellationException) {
                mutationSession?.close()
                throw cancelled
            } catch (error: IllegalArgumentException) {
                AdoptionResult.Rejected(
                    DriveFailure.Validation("adoption validation failed", error),
                    mutationSession
                )
            } catch (error: Throwable) {
                AdoptionResult.Rejected(
                    DriveFailure.Transfer("adopt remote document", error.message ?: error.toString(), error),
                    mutationSession
                )
            }
        }

    override suspend fun download(
        scope: SyncScope,
        reference: RemoteReference,
        expectedCursor: RemoteCursor?
    ): DownloadResult = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            if (scope.accountId != accountId) {
                return@withContext DownloadResult.Failed(
                    DriveFailure.NotAuthenticated("gateway account does not match SyncScope")
                )
            }
            val folder = service.files().get(reference.folderId)
                .setSupportsAllDrives(true)
                .setFields("id,parents,appProperties")
                .execute()
                ?: return@withContext DownloadResult.NotFound
            if (folder.id != reference.folderId ||
                folder.appProperties.orEmpty()[SYNC_DOCUMENT_ID_APP_PROPERTY] != scope.documentId.value ||
                folder.parents.orEmpty().none { it == scope.backupRootId }
            ) {
                return@withContext DownloadResult.Failed(
                    DriveFailure.Validation("remote folder reference does not belong to the requested SyncScope")
                )
            }
            val file = service.files().get(reference.snapshotFileId)
                .setSupportsAllDrives(true)
                .setFields("id,name,parents,appProperties,headRevisionId,modifiedTime")
                .execute()
                ?: return@withContext DownloadResult.NotFound
            val properties = file.appProperties.orEmpty()
            if (properties[SYNC_DOCUMENT_ID_APP_PROPERTY] != scope.documentId.value) {
                return@withContext DownloadResult.Failed(
                    DriveFailure.Validation("remote file DocumentId property does not match SyncScope")
                )
            }
            if (file.id != reference.snapshotFileId ||
                file.parents.orEmpty().none { it == reference.folderId }
            ) {
                return@withContext DownloadResult.Failed(
                    DriveFailure.Validation("remote file reference does not belong to the requested folder")
                )
            }
            val cursor = cursorFor(file)
            if (expectedCursor != null && expectedCursor != cursor) {
                return@withContext DownloadResult.Failed(
                    DriveFailure.Validation("remote cursor changed during download")
                )
            }
            val output = ByteArrayOutputStream()
            service.files().get(reference.snapshotFileId)
                .setSupportsAllDrives(true)
                .executeMediaAndDownloadTo(output)
            // Media transfer is not a snapshot transaction. Re-read the
            // authoritative Drive revision after the bytes arrive so a
            // remote writer cannot be accepted as the cursor we read before
            // the transfer.
            val afterTransfer = service.files().get(reference.snapshotFileId)
                .setSupportsAllDrives(true)
                .setFields("id,name,parents,appProperties,headRevisionId,modifiedTime")
                .execute()
                ?: return@withContext DownloadResult.NotFound
            val afterCursor = cursorFor(afterTransfer)
            if (afterCursor != cursor ||
                afterTransfer.id != reference.snapshotFileId ||
                afterTransfer.parents.orEmpty().none { it == reference.folderId } ||
                afterTransfer.appProperties.orEmpty()[SYNC_DOCUMENT_ID_APP_PROPERTY] != scope.documentId.value
            ) {
                return@withContext DownloadResult.Failed(
                    DriveFailure.Validation("remote cursor changed during media transfer")
                )
            }
            val payload = gson.fromJson(output.toString(Charsets.UTF_8.name()), DrivePayload::class.java)
                ?: return@withContext DownloadResult.Failed(DriveFailure.Validation("remote payload missing"))
            if (payload.accountId != scope.accountId || payload.backupRootId != scope.backupRootId ||
                payload.documentId != scope.documentId.value
            ) {
                return@withContext DownloadResult.Failed(DriveFailure.Validation("remote payload scope mismatch"))
            }
            val snapshot = payload.snapshot ?: return@withContext DownloadResult.Failed(
                DriveFailure.Validation("remote canonical snapshot missing")
            )
            val photoFiles = payload.photoFiles.orEmpty().mapValues { (_, encoded) ->
                Base64.decodeBase64(encoded)
                    ?: throw IllegalArgumentException("remote photo content is not valid base64")
            }
            val sourceFingerprint = sourceFingerprintFromDriveProperty(payload.sourceFingerprint)
            val fileFingerprint = sourceFingerprintFromDriveProperty(
                properties[SYNC_SOURCE_FINGERPRINT_APP_PROPERTY]
            )
            if (sourceFingerprint != fileFingerprint) {
                return@withContext DownloadResult.Failed(
                    DriveFailure.Validation("remote source fingerprint metadata is inconsistent")
                )
            }
            requireValidSnapshot(snapshot)
            validatedPhotoFiles(snapshot, photoFiles)
            DownloadResult.Downloaded(
                RemoteSnapshotEnvelope(
                    scope,
                    payload.displayName.orEmpty(),
                    reference,
                    afterCursor,
                    snapshot,
                    sourceFingerprint,
                    photoFiles
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            DownloadResult.Failed(DriveFailure.Validation("remote payload validation failed", error))
        } catch (error: Throwable) {
            DownloadResult.Failed(DriveFailure.Transfer("download snapshot", error.message ?: error.toString(), error))
        }
    }

    private fun listAllFiles(query: String, fields: String, orderBy: String): List<File> {
        val files = mutableListOf<File>()
        val seenPageTokens = mutableSetOf<String>()
        var token: String? = null
        do {
            if (token != null && !seenPageTokens.add(token!!)) {
                throw IllegalStateException("Drive listing repeated continuation token '$token'")
            }
            val request = service.files().list()
                .setQ(query)
                .setFields(fields)
                .setOrderBy(orderBy)
                .setPageSize(100)
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
            request.setPageToken(token)
            val page = request.execute()
            files += page.files.orEmpty()
            token = page.nextPageToken
        } while (token != null)
        return files
    }

    private suspend fun preconditionConflict(
        request: UploadRequest,
        mutationSession: RemoteMutationSession
    ): UploadResult {
        return when (val fresh = find(request.scope, request.sourceFingerprint)) {
            is RemoteLookup.Found -> UploadResult.Conflict(fresh.metadata, mutationSession)
            RemoteLookup.NotFound -> UploadResult.Rejected(
                DriveFailure.Validation(
                    "Drive rejected the conditional mutation but exposed no authoritative remote metadata"
                ),
                mutationSession
            )
            is RemoteLookup.PendingAdoption -> UploadResult.Rejected(
                DriveFailure.Validation(
                    "Drive conditional mutation found a same-source resource requiring explicit adoption"
                ),
                mutationSession
            )
            is RemoteLookup.Failed -> UploadResult.Rejected(fresh.failure, mutationSession)
        }
    }

    private fun referenceFor(folder: File, file: File, scope: SyncScope): RemoteReference {
        val properties = file.appProperties.orEmpty().ifEmpty {
            folder.appProperties.orEmpty()
        }
        require(properties[SYNC_DOCUMENT_ID_APP_PROPERTY] == scope.documentId.value) {
            "remote Drive resource is not tagged for this document"
        }
        return RemoteReference(folder.id, file.id, Collections.unmodifiableMap(LinkedHashMap(properties)))
    }

    private fun referenceForAny(folder: File, file: File): RemoteReference {
        val properties = file.appProperties.orEmpty().ifEmpty {
            folder.appProperties.orEmpty()
        }
        require(properties[SYNC_DOCUMENT_ID_APP_PROPERTY].orEmpty().isNotBlank()) {
            "remote Drive resource has no stable DocumentId property"
        }
        return RemoteReference(
            requireNotNull(folder.id),
            requireNotNull(file.id),
            Collections.unmodifiableMap(LinkedHashMap(properties))
        )
    }

    private fun requireTaggedFolder(folder: File, scope: SyncScope) {
        require(folder.id.isNotBlank()) { "Drive folder response has no stable id" }
        require(folder.appProperties.orEmpty()[SYNC_DOCUMENT_ID_APP_PROPERTY] == scope.documentId.value) {
            "Drive folder response is not tagged for this document"
        }
    }

    private fun requireTaggedFile(file: File, scope: SyncScope) {
        require(file.id.isNotBlank()) { "Drive snapshot response has no stable id" }
        require(file.appProperties.orEmpty()[SYNC_DOCUMENT_ID_APP_PROPERTY] == scope.documentId.value) {
            "Drive snapshot response is not tagged for this document"
        }
    }

    private fun cursorFor(file: File): RemoteCursor = RemoteCursor(
        revision = file.headRevisionId ?: file.version?.toString() ?: file.modifiedTime?.value?.toString()
        ?: error("Drive resource has no authoritative revision"),
        modifiedTimeMillis = file.modifiedTime?.value
    )

    private fun escapeQuery(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

    data class DrivePayload(
        val accountId: String? = null,
        val backupRootId: String? = null,
        val documentId: String? = null,
        val displayName: String? = null,
        val snapshot: DocumentSnapshotV1? = null,
        val sourceFingerprint: String? = null,
        val photoFiles: Map<String, String>? = null
    )
}

/**
 * Gson can bypass Kotlin constructor defaults, so remote payloads need a
 * runtime shape check before the canonical replacement path sees them.
 */
fun requireValidSnapshot(snapshot: DocumentSnapshotV1) {
    require(snapshot.schemaVersion == DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION) { "unsupported snapshot schema" }
    require(snapshot.snapshotRevision >= 0L) { "negative snapshot revision" }
    val source = requireNotNull(snapshot.source) { "snapshot source missing" }
    require(source.sourceUri.isNotBlank()) { "snapshot source URI missing" }
    requireNotNull(source.providerMetadata) { "snapshot source metadata missing" }
    val pages = requireNotNull(snapshot.pages) { "snapshot pages missing" }
    pages.forEach { (pageIndex, pageValue) ->
        require(pageIndex >= 0) { "negative page index" }
        val page = requireNotNull(pageValue) { "page payload missing" }
        val paths = requireNotNull(page.paths) { "page paths missing" }
        val measurements = requireNotNull(page.measurements) { "page measurements missing" }
        val notes = requireNotNull(page.notes) { "page notes missing" }
        val photoPins = requireNotNull(page.photoPins) { "page photo pins missing" }
        val shapes = requireNotNull(page.shapes) { "page shapes missing" }
        paths.forEach { pathValue ->
            val path = requireNotNull(pathValue) { "path payload missing" }
            val points = requireNotNull(path.points) { "path points missing" }
            points.forEach { pointValue ->
                val point = requireNotNull(pointValue) { "path point missing" }
                require(point.x.isFinite() && point.y.isFinite()) { "invalid path point" }
            }
            require(path.strokeWidth.isFinite()) { "invalid path stroke width" }
        }
        measurements.forEach { measurementValue ->
            val measurement = requireNotNull(measurementValue) { "measurement payload missing" }
            val p1 = requireNotNull(measurement.p1) { "measurement start point missing" }
            val p2 = requireNotNull(measurement.p2) { "measurement end point missing" }
            requireNotNull(measurement.text) { "measurement text missing" }
            require(p1.x.isFinite() && p1.y.isFinite()) { "invalid measurement point" }
            require(p2.x.isFinite() && p2.y.isFinite()) { "invalid measurement point" }
        }
        notes.forEach { noteValue ->
            val note = requireNotNull(noteValue) { "note payload missing" }
            requireNotNull(note.text) { "note text missing" }
            require(note.x.isFinite() && note.y.isFinite() && note.fontSize.isFinite() && note.rotation.isFinite()) {
                "invalid note"
            }
        }
        page.scale?.let { scale ->
            require(scale.pixelsPerFoot.isFinite() && scale.pixelsPerFoot > 0f) { "invalid scale" }
        }
        shapes.forEach { shape -> requireValidShape(requireNotNull(shape) { "shape payload missing" }) }
        photoPins.forEach { pinValue ->
            val pin = requireNotNull(pinValue) { "photo pin payload missing" }
            require(requireNotNull(pin.id) { "photo pin id missing" }.isNotBlank()) {
                "photo pin id missing"
            }
            val imageFileNames = requireNotNull(pin.imageFileNames) { "photo filenames missing" }
            val imageNotes = requireNotNull(pin.imageNotes) { "photo image notes missing" }
            val imageShapes = requireNotNull(pin.imageShapes) { "photo image shapes missing" }
            require(pin.x.isFinite() && pin.y.isFinite()) { "invalid photo pin" }
            require(imageFileNames.all { requireNotNull(it) { "photo filename missing" }.isNotBlank() }) {
                "photo filename missing"
            }
            require(imageNotes.keys.all { it in imageFileNames }) { "image note references an unknown photo" }
            require(imageShapes.keys.all { it in imageFileNames }) { "image shape references an unknown photo" }
            imageNotes.values.forEach { notesForImage ->
                requireNotNull(notesForImage) { "photo image note list missing" }
                    .forEach { note -> requireValidImageNote(requireNotNull(note) { "photo image note missing" }) }
            }
            imageShapes.values.forEach { shapesForImage ->
                requireNotNull(shapesForImage) { "photo image shape list missing" }
                    .forEach { shape -> requireValidShape(requireNotNull(shape) { "photo image shape missing" }) }
            }
        }
    }
}

private fun requireValidShape(shape: com.example.myapplication.stage1.ShapeSnapshotV1) {
    requireNotNull(shape.type) { "shape type missing" }
    require(requireNotNull(shape.id) { "shape id missing" }.isNotBlank()) { "shape id missing" }
    require(
        shape.x.isFinite() && shape.y.isFinite() && shape.width.isFinite() && shape.height.isFinite() &&
            shape.rotation.isFinite() && shape.strokeWidth.isFinite() && shape.strokeWidthRatio.isFinite() &&
            shape.widthRatio.isFinite() && shape.heightRatio.isFinite()
    ) { "invalid shape" }
}

private fun requireValidImageNote(note: com.example.myapplication.stage1.PhotoImageNoteSnapshotV1) {
    require(requireNotNull(note.id) { "image note id missing" }.isNotBlank()) { "image note id missing" }
    requireNotNull(note.text) { "image note text missing" }
    require(
        note.x.isFinite() && note.y.isFinite() && note.fontSize.isFinite() &&
            note.rotation.isFinite() && note.fontSizeRatio.isFinite()
    ) { "invalid image note" }
}
