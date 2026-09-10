package com.example.myapplication.stage4

import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage5.PhotoDescriptor
import com.example.myapplication.stage5.validatePhotoBytes
import com.example.myapplication.stage9b.DRIVE_MANIFEST_SCHEMA_VERSION
import com.example.myapplication.stage9b.PhotoAsset
import com.example.myapplication.stage9b.PhotoAssetSet
import com.example.myapplication.stage9b.RemoteAssetDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
        val photoFiles: PhotoAssetSet = PhotoAssetSet.EMPTY,
        val photoDescriptors: Map<String, RemoteAssetDescriptor> = emptyMap()
    )

    data class Call(val operation: String, val scope: SyncScope, val generation: Long? = null)

    private val remote = LinkedHashMap<SyncScope, RemoteRecord>()
    private val folders = LinkedHashMap<String, RemoteFolder>()
    private val folderFiles = LinkedHashMap<String, MutableList<RemoteFile>>()
    private val lock = Mutex()
    private val finalCommitLocks = ConcurrentHashMap<SyncScope, Mutex>()
    private val activeFinalCommits = AtomicInteger(0)
    private val activeFinalCommitsByScope = ConcurrentHashMap<SyncScope, AtomicInteger>()
    private val maxConcurrentFinalCommitsByScope = ConcurrentHashMap<SyncScope, AtomicInteger>()
    private val revisionCounter = AtomicInteger(0)

    @Volatile var beforeFinalCommit: (suspend (UploadRequest) -> Unit)? = null
    @Volatile var insideFinalMutation: (suspend (UploadRequest) -> Unit)? = null
    @Volatile var failUpload: DriveFailure? = null
    @Volatile var failDownload: DriveFailure? = null
    @Volatile var beforeDownload: (suspend (SyncScope, RemoteReference) -> Unit)? = null
    @Volatile var beforeAdopt: (suspend (AdoptionRequest) -> Unit)? = null
    @Volatile var mutateRevisionDuringDownload: Boolean = false
    @Volatile var pageSize: Int = 100

    val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
    val folderPageTokens: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    val filePageTokens: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    val createdFolderCount: AtomicInteger = AtomicInteger(0)
    val createdFileCount: AtomicInteger = AtomicInteger(0)
    @Volatile var maxConcurrentFinalCommits: Int = 0
        private set

    fun maxConcurrentFinalCommits(scope: SyncScope): Int = maxConcurrentFinalCommitsByScope[scope]?.get() ?: 0

    private data class RemoteFolder(val id: String, val parentId: String, val name: String, val appProperties: Map<String, String>)
    private data class RemoteFile(val id: String, val folderId: String, val name: String, val appProperties: Map<String, String>, val cursor: RemoteCursor, val scope: SyncScope)

    override suspend fun find(scope: SyncScope): RemoteLookup = find(scope, null)

    override suspend fun find(scope: SyncScope, sourceFingerprint: SourceFingerprint?): RemoteLookup {
        calls += Call("find", scope)
        return try {
            val matchingFolder = paginateFolders(scope.backupRootId).firstOrNull {
                it.appProperties[SYNC_DOCUMENT_ID_APP_PROPERTY] == scope.documentId.value
            }
            if (matchingFolder == null && sourceFingerprint != null) {
                val adoptionFolder = paginateFolders(scope.backupRootId).firstOrNull {
                    it.appProperties[SYNC_SOURCE_FINGERPRINT_APP_PROPERTY] == sourceFingerprint.toDriveProperty()
                }
                val adoptionRemote = adoptionFolder?.let { folder ->
                    paginateFiles(folder.id).firstOrNull {
                        it.appProperties[SYNC_SOURCE_FINGERPRINT_APP_PROPERTY] == sourceFingerprint.toDriveProperty()
                    }?.let { file ->
                        lock.withLock { remote.values.firstOrNull { record ->
                            record.scope.accountId == scope.accountId && record.scope.backupRootId == scope.backupRootId &&
                                record.reference.folderId == folder.id && record.reference.snapshotFileId == file.id
                        } }
                    }
                }
                if (adoptionRemote != null) return RemoteLookup.PendingAdoption(
                    RemoteAdoptionCandidate(scope.accountId, scope.backupRootId, adoptionRemote.scope.documentId,
                        sourceFingerprint, adoptionRemote.displayName, adoptionRemote.reference, adoptionRemote.cursor)
                )
                return RemoteLookup.NotFound
            }
            if (matchingFolder == null) return RemoteLookup.NotFound
            paginateFiles(matchingFolder.id).firstOrNull {
                it.name == "annotations.json" && it.appProperties[SYNC_DOCUMENT_ID_APP_PROPERTY] == scope.documentId.value
            } ?: return RemoteLookup.NotFound
            lock.withLock { remote[scope] }?.let { RemoteLookup.Found(it.toMetadata()) } ?: RemoteLookup.NotFound
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            RemoteLookup.Failed(DriveFailure.Validation("remote pagination input is invalid", error))
        } catch (error: IllegalStateException) {
            RemoteLookup.Failed(DriveFailure.Pagination(error.message ?: "remote pagination failed", error))
        }
    }

    override suspend fun upload(request: UploadRequest): UploadResult {
        calls += Call("upload", request.scope, request.generation)
        failUpload?.let { return UploadResult.Rejected(it) }
        val frozenAssets = try {
            requireValidSnapshot(request.snapshot)
            val assets = validatedPhotoFiles(request.snapshot, request.photoFiles)
            if (assets.descriptors.keys != requiredPhotoFileNames(request.snapshot)) {
                throw IllegalArgumentException("fake upload asset keys do not match snapshot")
            }
            freezeFakeAssets(assets)
        } catch (error: IllegalArgumentException) {
            return UploadResult.Rejected(DriveFailure.Validation("upload payload validation failed", error))
        }
        beforeFinalCommit?.invoke(request)
        val mutationSession = request.mutationLease.begin(request.generation, request.isGenerationCurrent)
            ?: return UploadResult.Rejected(DriveFailure.StaleGeneration(request.generation))
        return try {
            mutationSession.mutate {
                finalCommitLocks.computeIfAbsent(request.scope) { Mutex() }.withLock {
                    val active = activeFinalCommits.incrementAndGet()
                    maxConcurrentFinalCommits = maxOf(maxConcurrentFinalCommits, active)
                    val activeForScope = activeFinalCommitsByScope.computeIfAbsent(request.scope) { AtomicInteger(0) }.incrementAndGet()
                    maxConcurrentFinalCommitsByScope.computeIfAbsent(request.scope) { AtomicInteger(0) }.updateAndGet { maxOf(it, activeForScope) }
                    try {
                        if (!request.isGenerationCurrent()) return@withLock UploadResult.Rejected(DriveFailure.StaleGeneration(request.generation), mutationSession)
                        insideFinalMutation?.invoke(request)
                        if (!request.isGenerationCurrent()) return@withLock UploadResult.Rejected(DriveFailure.StaleGeneration(request.generation), mutationSession)
                        val current = lock.withLock { remote[request.scope] }
                        if (current == null && request.sourceFingerprint != null) {
                            when (val adoption = find(request.scope, request.sourceFingerprint)) {
                                is RemoteLookup.PendingAdoption -> return@withLock UploadResult.PendingAdoption(adoption.candidate, mutationSession)
                                else -> Unit
                            }
                        }
                        if (current == null && request.expectedCursor != null) return@withLock UploadResult.Rejected(DriveFailure.NotFound("remote document disappeared while an accepted cursor was present"), mutationSession)
                        if (current != null && request.expectedCursor != current.cursor) return@withLock UploadResult.Conflict(current.toMetadata(), mutationSession)
                        val folderId = current?.reference?.folderId ?: createFolderForUpload(request)
                        val descriptors = LinkedHashMap<String, RemoteAssetDescriptor>()
                        val idsByHash = LinkedHashMap<String, String>()
                        frozenAssets.forEach { (name, asset) ->
                            val descriptor = asset.descriptor
                            val id = current?.photoDescriptors?.get(name)
                                ?.takeIf { it.asPhotoDescriptor() == descriptor }
                                ?.remoteAssetId
                                ?: idsByHash[descriptor.sha256] ?: idFactory().also { idsByHash[descriptor.sha256] = it; createdFileCount.incrementAndGet() }
                            descriptors[name] = RemoteAssetDescriptor(id, descriptor.byteCount, descriptor.sha256, descriptor.mimeType, descriptor.width, descriptor.height)
                        }
                        val fileId = current?.reference?.snapshotFileId ?: idFactory().also { createdFileCount.incrementAndGet() }
                        val cursor = RemoteCursor("remote-r${revisionCounter.incrementAndGet()}")
                        val properties = buildMap {
                            put(SYNC_DOCUMENT_ID_APP_PROPERTY, request.scope.documentId.value)
                            put(SYNC_SCHEMA_APP_PROPERTY, DRIVE_MANIFEST_SCHEMA_VERSION.toString())
                            put("sotaware_account_id", request.scope.accountId)
                            put("sotaware_backup_root_id", request.scope.backupRootId)
                            request.sourceFingerprint?.let { put(SYNC_SOURCE_FINGERPRINT_APP_PROPERTY, it.toDriveProperty()) }
                        }
                        val next = RemoteRecord(request.scope, request.displayName,
                            RemoteReference(folderId, fileId, properties), cursor, request.snapshot,
                            request.sourceFingerprint, frozenAssets, descriptors)
                        lock.withLock {
                            remote[request.scope] = next
                            val files = folderFiles.getOrPut(folderId) { mutableListOf() }
                            val index = files.indexOfFirst { it.id == fileId }
                            val file = RemoteFile(fileId, folderId, "annotations.json", properties, cursor, request.scope)
                            if (index >= 0) files[index] = file else files += file
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
        } catch (error: IllegalArgumentException) {
            UploadResult.Rejected(DriveFailure.Validation("fake upload payload or state is invalid", error), mutationSession)
        } catch (error: Throwable) {
            UploadResult.Rejected(DriveFailure.Transfer("fake upload", error.message ?: error.toString(), error), mutationSession)
        }
    }

    override suspend fun adopt(request: AdoptionRequest): AdoptionResult {
        calls += Call("adopt", request.scope, request.generation)
        val mutationSession = request.mutationLease.begin(request.generation, request.isGenerationCurrent)
            ?: return AdoptionResult.Rejected(DriveFailure.StaleGeneration(request.generation))
        return try {
            mutationSession.mutate {
                if (!request.isGenerationCurrent()) return@mutate AdoptionResult.Rejected(DriveFailure.StaleGeneration(request.generation), mutationSession)
                beforeAdopt?.invoke(request)
                lock.withLock {
                    val candidateScope = SyncScope(request.scope.accountId, request.scope.backupRootId, request.candidate.remoteDocumentId)
                    val current = remote[candidateScope] ?: return@withLock AdoptionResult.Rejected(DriveFailure.NotFound("selected adoption candidate no longer exists"), mutationSession)
                    if (remote.containsKey(request.scope)) return@withLock AdoptionResult.Rejected(DriveFailure.Validation("the local synchronization scope already has a remote document"), mutationSession)
                    if (current.reference != request.candidate.reference || current.cursor != request.candidate.cursor || current.sourceFingerprint != request.localSourceFingerprint) {
                        return@withLock AdoptionResult.Rejected(DriveFailure.Validation("selected adoption candidate changed or has an incompatible source fingerprint"), mutationSession)
                    }
                    val properties = LinkedHashMap(current.reference.appProperties).apply { this[SYNC_DOCUMENT_ID_APP_PROPERTY] = request.scope.documentId.value }
                    val adopted = current.copy(scope = request.scope, reference = RemoteReference(current.reference.folderId, current.reference.snapshotFileId, properties))
                    remote.remove(candidateScope)
                    remote[request.scope] = adopted
                    folders[adopted.reference.folderId]?.let { folders[adopted.reference.folderId] = it.copy(appProperties = properties) }
                    folderFiles[adopted.reference.folderId]?.replaceAll { file -> if (file.id == adopted.reference.snapshotFileId) file.copy(appProperties = properties, scope = request.scope) else file }
                    AdoptionResult.Adopted(adopted.toMetadata(), request.candidate.remoteDocumentId, mutationSession)
                }
            }
        } catch (cancelled: CancellationException) { mutationSession.close(); throw cancelled
        } catch (error: IllegalArgumentException) { AdoptionResult.Rejected(DriveFailure.Validation("fake adoption payload or state is invalid", error), mutationSession)
        } catch (error: Throwable) { AdoptionResult.Rejected(DriveFailure.Transfer("adopt remote document", error.message ?: error.toString(), error), mutationSession) }
    }

    override suspend fun download(scope: SyncScope, reference: RemoteReference, expectedCursor: RemoteCursor?): DownloadResult {
        calls += Call("download", scope)
        failDownload?.let { return DownloadResult.Failed(it) }
        val current = lock.withLock { remote[scope] } ?: return DownloadResult.NotFound
        if (current.reference.folderId != reference.folderId || current.reference.snapshotFileId != reference.snapshotFileId || current.reference.appProperties[SYNC_DOCUMENT_ID_APP_PROPERTY] != scope.documentId.value) return DownloadResult.Failed(DriveFailure.Validation("remote reference does not belong to the requested SyncScope"))
        if (expectedCursor != null && expectedCursor != current.cursor) return DownloadResult.Failed(DriveFailure.Validation("remote cursor changed during download"))
        beforeDownload?.invoke(scope, reference)
        if (mutateRevisionDuringDownload) lock.withLock { remote[scope]?.let { record -> remote[scope] = record.copy(cursor = RemoteCursor("remote-r${revisionCounter.incrementAndGet()}")) } }
        val reread = lock.withLock { remote[scope] } ?: return DownloadResult.NotFound
        if (reread.cursor != current.cursor) return DownloadResult.Failed(DriveFailure.Validation("remote cursor changed during download"))
        return try { requireValidSnapshot(reread.snapshot); validatedPhotoFiles(reread.snapshot, reread.photoFiles); DownloadResult.Downloaded(reread.toEnvelope()) }
        catch (error: IllegalArgumentException) { DownloadResult.Failed(DriveFailure.Validation("remote payload validation failed", error)) }
    }

    internal suspend fun replaceRemoteSnapshotForTesting(scope: SyncScope, snapshot: DocumentSnapshotV1) { lock.withLock { remote[scope]?.let { remote[scope] = it.copy(snapshot = snapshot) } } }

    suspend fun seed(
        scope: SyncScope,
        displayName: String,
        snapshot: DocumentSnapshotV1,
        cursor: RemoteCursor = RemoteCursor("remote-r${revisionCounter.incrementAndGet()}"),
        sourceFingerprint: SourceFingerprint? = null,
        photoFiles: PhotoAssetSet = PhotoAssetSet.EMPTY
    ): RemoteSnapshotEnvelope {
        requireValidSnapshot(snapshot)
        val expected = lock.withLock { remote[scope]?.cursor }
        val lease = ScopeRemoteMutationLease()
        lease.advance(1L)
        val result = upload(UploadRequest(scope, displayName, snapshot, expected, 1L, lease, { lease.isGenerationCurrent(1L) }, sourceFingerprint, photoFiles))
        val envelope = (result as? UploadResult.Uploaded)?.remote ?: error("seed failed: $result")
        result.mutationSession?.close()
        if (envelope.cursor == cursor) return envelope
        return lock.withLock {
            val changed = remote.getValue(scope).copy(cursor = cursor)
            remote[scope] = changed
            folderFiles[changed.reference.folderId]?.replaceAll { file -> if (file.id == changed.reference.snapshotFileId) file.copy(cursor = cursor) else file }
            changed.toEnvelope()
        }
    }

    /** Test-only adapter for explicit synthetic bytes; production callers use PhotoAssetSet. */
    suspend fun seed(
        scope: SyncScope,
        displayName: String,
        snapshot: DocumentSnapshotV1,
        cursor: RemoteCursor = RemoteCursor("remote-r${revisionCounter.incrementAndGet()}"),
        sourceFingerprint: SourceFingerprint? = null,
        photoFiles: Map<String, ByteArray>
    ): RemoteSnapshotEnvelope = seed(scope, displayName, snapshot, cursor, sourceFingerprint, photoFiles.toPhotoAssetSet())

    suspend fun record(scope: SyncScope): RemoteRecord? = lock.withLock { remote[scope] }
    internal suspend fun removeRemoteForTesting(scope: SyncScope) { lock.withLock { remote.remove(scope)?.let { folders.remove(it.reference.folderId); folderFiles.remove(it.reference.folderId) } } }

    private suspend fun createFolderForUpload(request: UploadRequest): String = lock.withLock {
        val folderId = idFactory()
        val properties = buildMap {
            put(SYNC_DOCUMENT_ID_APP_PROPERTY, request.scope.documentId.value)
            put(SYNC_SCHEMA_APP_PROPERTY, DRIVE_MANIFEST_SCHEMA_VERSION.toString())
            put("sotaware_account_id", request.scope.accountId)
            put("sotaware_backup_root_id", request.scope.backupRootId)
            request.sourceFingerprint?.let { put(SYNC_SOURCE_FINGERPRINT_APP_PROPERTY, it.toDriveProperty()) }
        }
        folders[folderId] = RemoteFolder(folderId, request.scope.backupRootId, request.displayName, properties)
        folderFiles[folderId] = mutableListOf(); createdFolderCount.incrementAndGet(); folderId
    }
    private suspend fun paginateFolders(parentId: String): List<RemoteFolder> = paginate(lock.withLock { folders.values.filter { it.parentId == parentId }.sortedBy { it.id } }, folderPageTokens)
    private suspend fun paginateFiles(folderId: String): List<RemoteFile> = paginate(lock.withLock { folderFiles[folderId].orEmpty().toList().sortedBy { it.id } }, filePageTokens)
    private fun <T> paginate(items: List<T>, tokens: MutableList<String?>): List<T> { val size = pageSize.also { require(it > 0) }; val result = mutableListOf<T>(); var start = 0; var token: String? = null; do { tokens += token; val end = minOf(start + size, items.size); result += items.subList(start, end); start = end; token = if (start < items.size) start.toString() else null } while (token != null); return result }
    private fun RemoteRecord.toMetadata() = RemoteDocumentMetadata(scope, displayName, reference, cursor)
    private fun RemoteRecord.toEnvelope() = RemoteSnapshotEnvelope(scope, displayName, reference, cursor, snapshot, sourceFingerprint, photoFiles, photoDescriptors)
}

private data class InlinePhotoAsset(override val descriptor: PhotoDescriptor, private val bytes: ByteArray) : PhotoAsset {
    override fun open() = bytes.copyOf().inputStream()
}

private fun Map<String, ByteArray>.toPhotoAssetSet(): PhotoAssetSet = PhotoAssetSet.of(mapValues { (_, bytes) ->
    val validated = validatePhotoBytes(bytes.copyOf())
    InlinePhotoAsset(validated.descriptor, validated.bytes.copyOf())
})

/** Test-only fake boundary: detach caller handles so later mutation cannot
 * alter the simulated remote record.  Production Drive transfer remains
 * streaming and file-backed. */
private fun freezeFakeAssets(assets: PhotoAssetSet): PhotoAssetSet {
    if (assets.isEmpty()) return PhotoAssetSet.EMPTY
    val frozen = LinkedHashMap<String, PhotoAsset>(assets.size)
    assets.forEach { (name, asset) ->
        val output = ByteArrayOutputStream(asset.descriptor.byteCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        com.example.myapplication.stage9b.copyPhotoAsset(asset, output)
        val bytes = output.toByteArray()
        frozen[name] = InlinePhotoAsset(asset.descriptor, bytes)
    }
    return PhotoAssetSet.of(frozen)
}
