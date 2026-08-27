package com.example.myapplication.stage5

import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.PhotoContentTransaction
import com.example.myapplication.stage4.PhotoRollbackException
import com.example.myapplication.stage4.StagedPhotoContentTransaction
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * The only production photo filesystem primitive. Implementations operate on
 * one direct child name relative to an already-open document photo directory.
 * The production implementation below is descriptor-relative; tests inject a
 * deterministic implementation explicitly rather than enabling a path-based
 * fallback in the app.
 */
internal interface PhotoPathOperations : AutoCloseable {
    fun exists(name: String): Boolean
    fun isRegularFile(name: String): Boolean
    fun size(name: String): Long
    fun openRead(name: String): InputStream
    fun openNewOutput(name: String): FileChannel
    fun move(source: String, target: String, replaceExisting: Boolean)
    fun delete(name: String)
}

internal fun interface PhotoPathOperationsFactory {
    fun open(root: Path): PhotoPathOperations
}

/** Durable association used to recover an interrupted whole-set replacement. */
internal data class PhotoTransactionJournalEntry(
    val stagedName: String,
    val targetName: String,
    val backupName: String,
    val targetExisted: Boolean
)

/**
 * The canonical identity associated with one side of a photo replacement.
 * The document id binds the record to one photo root; the source URI and
 * validated snapshot digest prevent a different source or snapshot from
 * deciding the recovery outcome after process death.
 */
data class PhotoCanonicalIdentity(
    val documentId: String,
    val snapshotDigest: String,
    val sourceUri: String
) {
    init {
        DocumentId.parse(documentId)
        require(snapshotDigest.matches(Regex("[0-9a-f]{64}"))) {
            "photo canonical snapshot digest is invalid"
        }
        require(sourceUri.isNotBlank() && sourceUri.length <= Stage5Limits.MAX_STRING_CHARS) {
            "photo canonical source URI is invalid"
        }
    }
}

internal data class PhotoCanonicalRecoveryRecord(
    val previous: PhotoCanonicalIdentity,
    val intended: PhotoCanonicalIdentity,
    /** SHA-256 of the prepared file journal; null only for readable V1 evidence. */
    val journalIdentity: String? = null,
    /** Digest of the exact journal targets before and after the replacement. */
    val previousPhotoDigest: String? = null,
    val intendedPhotoDigest: String? = null,
    /**
     * Remote acceptance records also carry a durable metadata phase.  The
     * phase marker is written only after the metadata authority commits, so a
     * restarted resolver can distinguish a safe new/new tuple from the crash
     * window between canonical apply and metadata commit.
     */
    val mode: PhotoCanonicalRecoveryMode? = null,
    /**
     * Exact live canonical authority at admission time.  [previous] is the
     * durable authority for source compatibility; older V1/V2/V3 records map
     * this field to [previous] because they never recorded a separate live
     * identity.
     */
    val previousLive: PhotoCanonicalIdentity = previous
) {
    init {
        require(previous.documentId == intended.documentId) {
            "photo canonical recovery document identities differ"
        }
        require(previousLive.documentId == previous.documentId) {
            "photo canonical recovery live document identity differs"
        }
        require(previousLive.sourceUri == previous.sourceUri) {
            "photo canonical recovery live source identity differs"
        }
        journalIdentity?.let {
            require(it.matches(Regex("[0-9a-f]{64}"))) {
                "photo canonical recovery journal identity is invalid"
            }
        }
        previousPhotoDigest?.let {
            require(it.matches(Regex("[0-9a-f]{64}"))) {
                "photo canonical previous content digest is invalid"
            }
        }
        intendedPhotoDigest?.let {
            require(it.matches(Regex("[0-9a-f]{64}"))) {
                "photo canonical intended content digest is invalid"
            }
        }
        require((previousPhotoDigest == null) == (intendedPhotoDigest == null)) {
            "photo canonical content digests must be recorded as a pair"
        }
        if (mode == PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE) {
            require(previousPhotoDigest != null && intendedPhotoDigest != null) {
                "remote-acceptance recovery requires both photo content digests"
            }
        }
    }
}

/** Cross-store recovery modes that require more than the legacy V2 intent. */
enum class PhotoCanonicalRecoveryMode {
    REMOTE_ACCEPTANCE
}

/** A marker read is never allowed to silently become an absent marker. */
internal sealed class PhotoCommitMarkerProbe {
    data object Absent : PhotoCommitMarkerProbe()
    data object Bound : PhotoCommitMarkerProbe()
    data class Ambiguous(val error: PhotoCanonicalRecoveryException) : PhotoCommitMarkerProbe()
}

/**
 * Full rollback proof retained while the fixed-path cleanup markers are
 * removed.  The embedded journal is required when the ordinary journal has
 * already been deleted but cleanup evidence is still present after a crash.
 */
private data class PhotoRollbackCompletionEvidence(
    val record: PhotoCanonicalRecoveryRecord,
    val previousMetadataIdentity: String,
    val journalBytes: ByteArray
)

internal enum class PhotoRecoveryAction {
    NONE,
    FINALIZED,
    ROLLED_BACK
}

/**
 * Process-wide serialization for one document photo root. The key is used
 * only for coordination; all file operations remain descriptor-relative.
 */
internal object PhotoDocumentCriticalSections {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    internal fun rootKey(root: Path): String = root.toAbsolutePath().normalize().toString()

    internal fun <T> withLock(root: Path, block: () -> T): T {
        val lock = locks.computeIfAbsent(rootKey(root)) { ReentrantLock() }
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

/**
 * A camera publication remains protected after its atomic rename until the
 * caller attaches or explicitly cleans it. Process death drops this volatile
 * reservation; the next authoritative admission can then collect the orphan.
 */
internal object PhotoPublicationReservations {
    private val reservations = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    internal fun reserve(root: Path, reference: String) {
        val byReference = reservations.computeIfAbsent(PhotoDocumentCriticalSections.rootKey(root)) {
            ConcurrentHashMap()
        }
        if (byReference.size >= Stage5Limits.MAX_TOTAL_PHOTOS && !byReference.containsKey(reference)) {
            throw Stage5ValidationException("photo publication reservation count exceeds its limit")
        }
        byReference[reference] = System.currentTimeMillis()
    }

    internal fun release(root: Path, reference: String) {
        reservations[PhotoDocumentCriticalSections.rootKey(root)]?.let { byReference ->
            byReference.remove(reference)
            if (byReference.isEmpty()) {
                reservations.remove(PhotoDocumentCriticalSections.rootKey(root), byReference)
            }
        }
    }

    internal fun active(root: Path, nowMillis: Long = System.currentTimeMillis()): Set<String> {
        val byReference = reservations[PhotoDocumentCriticalSections.rootKey(root)] ?: return emptySet()
        val active = linkedSetOf<String>()
        byReference.entries.forEach { entry ->
            val age = nowMillis - entry.value
            if (age <= Stage5Limits.MAX_PHOTO_PUBLICATION_RESERVATION_MILLIS) {
                active += entry.key
            } else if (age > Stage5Limits.MAX_PHOTO_PUBLICATION_RESERVATION_MILLIS) {
                byReference.remove(entry.key, entry.value)
            }
        }
        if (byReference.isEmpty()) {
            reservations.remove(PhotoDocumentCriticalSections.rootKey(root), byReference)
        }
        return active
    }
}

/** Typed fail-closed evidence when canonical and photo authorities disagree. */
class PhotoCanonicalRecoveryException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

private val PHOTO_DIGEST_GSON = GsonBuilder().disableHtmlEscaping().create()

private fun canonicalJson(element: JsonElement): String = when {
    element.isJsonObject -> element.asJsonObject.entrySet()
        .sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
            PHOTO_DIGEST_GSON.toJson(key) + ":" + canonicalJson(value)
        }
    element.isJsonArray -> element.asJsonArray.joinToString(prefix = "[", postfix = "]") {
        canonicalJson(it)
    }
    else -> element.toString()
}

internal fun photoCanonicalIdentity(
    documentId: DocumentId,
    snapshot: DocumentSnapshotV1
): PhotoCanonicalIdentity {
    validateSnapshot(snapshot)
    val json = canonicalJson(JsonParser.parseString(PHOTO_DIGEST_GSON.toJson(snapshot)))
    require(json.toByteArray(StandardCharsets.UTF_8).size <= Stage5Limits.MAX_JSON_BYTES) {
        "photo canonical snapshot digest input exceeds the JSON limit"
    }
    return PhotoCanonicalIdentity(
        documentId = documentId.value,
        snapshotDigest = sha256Hex(json.toByteArray(StandardCharsets.UTF_8)),
        sourceUri = snapshot.source.sourceUri
    )
}

/**
 * Computes a deterministic digest for the direct targets named by one photo
 * journal. Missing and present files are distinct states. The framing uses
 * length-prefixed fields so a filename or byte sequence cannot collide with a
 * different sequence merely because it contains a delimiter.
 */
internal fun photoTransactionContentDigest(
    entries: List<PhotoTransactionJournalEntry>,
    readTarget: (String) -> ByteArray?
): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    fun field(bytes: ByteArray) {
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    entries.sortedBy { it.targetName }.forEach { entry ->
        field(entry.targetName.toByteArray(StandardCharsets.UTF_8))
        val bytes = readTarget(entry.targetName)
        if (bytes == null) {
            field(byteArrayOf(0))
        } else {
            field(byteArrayOf(1))
            field(bytes)
        }
    }
    return digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(java.util.Locale.ROOT, byte.toInt() and 0xff)
    }
}

private const val PHOTO_TRANSACTION_MARKER = ".stage5-photo-transaction.marker"
private const val PHOTO_TRANSACTION_COMMITTED_MARKER = ".stage5-photo-transaction.commit"
private const val PHOTO_CANONICAL_INTENT_MARKER = ".stage5-photo-canonical.intent"
private const val PHOTO_CANONICAL_METADATA_COMMITTED_MARKER = ".stage5-photo-metadata.commit"
private const val PHOTO_TRANSACTION_CLEANUP_MARKER = ".stage5-photo-transaction.cleanup"
private const val PHOTO_TRANSACTION_MAGIC = "SOTAWARE_STAGE5_PHOTO_TRANSACTION_V1"
private const val PHOTO_CANONICAL_INTENT_MAGIC_V1 = "SOTAWARE_STAGE5_PHOTO_CANONICAL_V1"
private const val PHOTO_CANONICAL_INTENT_MAGIC = "SOTAWARE_STAGE5_PHOTO_CANONICAL_V2"
private const val PHOTO_CANONICAL_INTENT_MAGIC_V3 = "SOTAWARE_STAGE5_PHOTO_CANONICAL_V3"
/** Packed previous-live identity carried in the existing bounded V2/V3 field layout. */
private const val PHOTO_CANONICAL_PREVIOUS_PAIR_MAGIC = "SOTAWARE_STAGE5_PHOTO_PREVIOUS_PAIR_V1"
/** Journal-bound, validated payload used to rehydrate an unequal prior live snapshot. */
private const val PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT = ".stage5-photo-canonical.live"
private const val PHOTO_CANONICAL_LIVE_SNAPSHOT_MAGIC = "SOTAWARE_STAGE5_PHOTO_CANONICAL_LIVE_V1"
/** Durable phase written before deleting the live-snapshot sidecar. */
private const val PHOTO_CANONICAL_LIVE_SNAPSHOT_CLEANUP_MARKER = ".stage5-photo-canonical.live.cleanup"
private const val PHOTO_CANONICAL_LIVE_SNAPSHOT_CLEANUP_MAGIC =
    "SOTAWARE_STAGE5_PHOTO_CANONICAL_LIVE_CLEANUP_V1"
private const val PHOTO_TRANSACTION_CLEANUP_MAGIC = "SOTAWARE_STAGE5_PHOTO_CLEANUP_V1"
private const val PHOTO_TRANSACTION_ROLLBACK_COMPLETE_MAGIC = "SOTAWARE_STAGE5_PHOTO_ROLLBACK_COMPLETE_V1"
private const val PHOTO_TRANSACTION_ROLLBACK_PENDING_MAGIC = "SOTAWARE_STAGE5_PHOTO_ROLLBACK_PENDING_V1"
private const val PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MARKER = ".stage5-photo-rollback.complete"
private const val PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MAGIC = "SOTAWARE_STAGE5_PHOTO_ROLLBACK_COMPLETE_V2"
private const val PHOTO_METADATA_COMMITTED_MAGIC = "METADATA_COMMITTED"
private const val MAX_PHOTO_ROLLBACK_EVIDENCE_BYTES = 2 * 1024 * 1024
internal const val MAX_PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT_BYTES =
    Stage5Limits.MAX_JSON_BYTES * 2 + 16 * 1024

private object SecurePhotoPathOperationsFactory : PhotoPathOperationsFactory {
    override fun open(root: Path): PhotoPathOperations = SecurePhotoPathOperations.open(root)
}

/**
 * SecureDirectoryStream keeps the directory descriptor authoritative while
 * each operation uses a single relative component. If the provider does not
 * implement this primitive, construction fails closed instead of using a
 * check-then-use path operation.
 */
private class SecurePhotoPathOperations private constructor(
    private val fileSystem: java.nio.file.FileSystem,
    private val directory: SecureDirectoryStream<Path>
) : PhotoPathOperations {
    companion object {
        fun open(root: Path): PhotoPathOperations {
            val absolute = root.toAbsolutePath().normalize()
            val filesystemRoot = absolute.root
                ?: throw IOException("photo root has no filesystem root")
            val fileSystem = absolute.fileSystem
            var current = openSecureDirectory(filesystemRoot)
            try {
                // Open every ancestor relative to the already-open parent
                // descriptor. NOFOLLOW_LINKS applies to each component, so a
                // parent replacement cannot redirect the final photo root.
                absolute.iterator().forEach { component ->
                    val next = current.newDirectoryStream(
                        fileSystem.getPath(component.toString()),
                        LinkOption.NOFOLLOW_LINKS
                    )
                    current.close()
                    current = next
                }
            } catch (error: IOException) {
                try {
                    current.close()
                } catch (_: IOException) {
                } catch (_: SecurityException) {
                }
                throw IOException("secure photo directory could not be opened", error)
            } catch (error: SecurityException) {
                try {
                    current.close()
                } catch (_: IOException) {
                } catch (_: SecurityException) {
                }
                throw IOException("secure photo directory could not be opened", error)
            }
            @Suppress("UNCHECKED_CAST")
            return SecurePhotoPathOperations(
                fileSystem,
                current
            )
        }

        private fun openSecureDirectory(path: Path): SecureDirectoryStream<Path> {
            val stream = try {
                Files.newDirectoryStream(path)
            } catch (error: IOException) {
                throw IOException("secure photo filesystem root could not be opened", error)
            } catch (error: SecurityException) {
                throw IOException("secure photo filesystem root could not be opened", error)
            }
            if (stream !is SecureDirectoryStream<*>) {
                try {
                    stream.close()
                } catch (_: IOException) {
                } catch (_: SecurityException) {
                }
                throw IOException("photo provider lacks SecureDirectoryStream support")
            }
            @Suppress("UNCHECKED_CAST")
            return stream as SecureDirectoryStream<Path>
        }
    }

    private fun relative(name: String): Path {
        if (name.isEmpty() || name == "." || name == ".." ||
            name.contains('/') || name.contains('\\') || name.indexOf('\u0000') >= 0
        ) {
            throw Stage5ValidationException("secure photo operation requires one relative child name")
        }
        return fileSystem.getPath(name)
    }

    private fun attributes(name: String): java.nio.file.attribute.BasicFileAttributes? {
        val relative = relative(name)
        return try {
            val view = directory.getFileAttributeView(
                relative,
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS
            ) ?: throw IOException("secure photo attributes are unavailable")
            view.readAttributes()
        } catch (_: NoSuchFileException) {
            null
        }
    }

    private fun requireNotSymlink(name: String, attributes: java.nio.file.attribute.BasicFileAttributes?) {
        if (attributes?.isSymbolicLink == true) {
            throw Stage5ValidationException("photo target is a symbolic link: $name")
        }
    }

    override fun exists(name: String): Boolean {
        val attributes = attributes(name)
        requireNotSymlink(name, attributes)
        return attributes != null
    }

    override fun isRegularFile(name: String): Boolean {
        val attributes = attributes(name)
        requireNotSymlink(name, attributes)
        return attributes?.isRegularFile == true
    }

    override fun size(name: String): Long {
        val attributes = attributes(name)
        requireNotSymlink(name, attributes)
        if (attributes == null) throw NoSuchFileException(name)
        if (!attributes.isRegularFile) throw IOException("photo target is not a regular file: $name")
        return attributes.size()
    }

    override fun openRead(name: String): InputStream {
        val attributes = attributes(name)
        requireNotSymlink(name, attributes)
        if (attributes != null && !attributes.isRegularFile) {
            throw IOException("photo target is not a regular file: $name")
        }
        val channel = directory.newByteChannel(
            relative(name),
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        )
        return Channels.newInputStream(channel)
    }

    override fun openNewOutput(name: String): FileChannel {
        val attributes = attributes(name)
        requireNotSymlink(name, attributes)
        if (attributes != null) throw FileAlreadyExistsException(name)
        val channel = directory.newByteChannel(
            relative(name),
            setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        )
        return channel as? FileChannel ?: run {
            try {
                channel.close()
            } catch (_: IOException) {
            } catch (_: SecurityException) {
            }
            throw IOException("photo provider did not return a forceable file channel")
        }
    }

    override fun move(source: String, target: String, replaceExisting: Boolean) {
        if (replaceExisting) {
            throw Stage5ValidationException("secure photo moves do not allow replacement")
        }
        val sourceAttributes = attributes(source)
        requireNotSymlink(source, sourceAttributes)
        if (sourceAttributes == null) throw NoSuchFileException(source)
        val targetAttributes = attributes(target)
        requireNotSymlink(target, targetAttributes)
        if (targetAttributes != null) throw FileAlreadyExistsException(target)
        // Same-directory SecureDirectoryStream.move is descriptor-relative and
        // maps to the provider's rename primitive; no path fallback exists.
        directory.move(relative(source), directory, relative(target))
    }

    override fun delete(name: String) {
        val attributes = attributes(name)
        requireNotSymlink(name, attributes)
        if (attributes == null) return
        if (!attributes.isRegularFile) throw IOException("photo target is not a regular file: $name")
        directory.deleteFile(relative(name))
    }

    override fun close() {
        directory.close()
    }
}

/**
 * Canonical resolver for document photo assets. All operational calls are
 * relative to the opened SecureDirectoryStream; unsupported providers fail
 * closed. The returned File is only a URI/FileProvider identity, never the
 * implementation path used for photo reads, writes, moves, or deletes.
 */
class PhotoPathResolver internal constructor(
    rootDirectory: File,
    createRoot: Boolean,
    private val operationsFactory: PhotoPathOperationsFactory
) : AutoCloseable {
    constructor(rootDirectory: File, createRoot: Boolean = true) : this(
        rootDirectory,
        createRoot,
        SecurePhotoPathOperationsFactory
    )

    val root: File
    private val rootPath: Path
    private val operations: PhotoPathOperations

    @Volatile
    private var pendingCanonicalRecovery: PhotoCanonicalRecoveryRecord? = null

    /** Durable rollback evidence is an unresolved readiness blocker. */
    @Volatile
    private var rollbackRecoveryPending = false

    init {
        val requested = rootDirectory.absoluteFile.toPath().toAbsolutePath().normalize()
        ensureNoSymlinkComponents(requested)
        if (createRoot && !Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(requested)
        }
        if (!Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
            throw Stage5ValidationException("photo root is not a directory")
        }
        ensureNoSymlinkComponents(requested)
        // Keep the already-checked lexical root instead of resolving it
        // through a second path lookup that could accept a newly introduced
        // root symlink between validation and canonicalization.
        root = requested.toFile().absoluteFile
        rootPath = requested
        ensureContained(rootPath, "photo root")
        operations = operationsFactory.open(rootPath)
        PhotoDocumentCriticalSections.withLock(rootPath) {
            recoverInterruptedPhotoTransaction()
        }
    }

    fun resolve(reference: String): File {
        requireCanonicalRecoveryResolved()
        validatePhotoFileName(reference)
        val target = root.resolve(reference)
        ensureContained(target.toPath(), reference)
        return target
    }

    fun newPhotoReference(): String = "photo-${UUID.randomUUID()}.jpg"

    fun newPublishedFile(): Pair<String, File> {
        val reference = newPhotoReference()
        return reference to resolve(reference)
    }

    fun newInternalFile(kind: String, extension: String): File {
        requireCanonicalRecoveryResolved()
        require(kind.matches(Regex("[A-Za-z0-9-]{1,32}"))) { "unsafe internal photo path prefix" }
        require(extension.matches(Regex("\\.[A-Za-z0-9]{1,8}"))) { "unsafe internal photo path extension" }
        val file = root.resolve(".$kind-${UUID.randomUUID()}$extension")
        ensureContained(file.toPath(), "internal photo path")
        return file
    }

    internal fun beginPhotoTransaction(entries: List<PhotoTransactionJournalEntry>): String {
        if (entries.isEmpty()) return ""
        if (entries.size > Stage5Limits.MAX_TOTAL_PHOTOS) {
            throw Stage5ValidationException("photo transaction entry count exceeds its limit")
        }
        if (operations.exists(PHOTO_TRANSACTION_CLEANUP_MARKER) ||
            operations.exists(PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MARKER) ||
            operations.exists(PHOTO_CANONICAL_LIVE_SNAPSHOT_CLEANUP_MARKER)
        ) {
            throw PhotoCanonicalRecoveryException(
                "photo transaction cleanup evidence must be reconciled before a new transaction"
            )
        }
        val marker = buildString {
            append(PHOTO_TRANSACTION_MAGIC).append('\n')
            append("PREPARED\n")
            append(entries.size).append('\n')
            entries.forEach { entry ->
                validateTransactionInternalName(entry.stagedName)
                validatePhotoFileName(entry.targetName)
                validateTransactionInternalName(entry.backupName)
                append(entry.stagedName).append('\t')
                    .append(entry.targetName).append('\t')
                    .append(entry.backupName).append('\t')
                    .append(if (entry.targetExisted) '1' else '0').append('\n')
            }
        }.toByteArray(StandardCharsets.US_ASCII)
        writeInternalFile(PHOTO_TRANSACTION_MARKER, marker, "photo transaction marker")
        return sha256Hex(marker)
    }

    /**
     * Returns the identity of the currently durable journal.  The parsed
     * marker bytes, rather than an in-memory transaction object, are the
     * authority used to prevent a stale transaction from touching a newer
     * transaction's evidence.
     */
    internal fun requirePhotoTransactionIdentity(expected: String) {
        require(expected.matches(Regex("[0-9a-f]{64}"))) {
            "photo transaction identity is invalid"
        }
        val actual = try {
            val bytes = readTransactionMarker()
            parsePhotoTransactionMarker(bytes)
            sha256Hex(bytes)
        } catch (error: PhotoCanonicalRecoveryException) {
            throw error
        } catch (error: Stage5ValidationException) {
            throw PhotoCanonicalRecoveryException(
                "photo transaction journal could not be read for ownership validation",
                error
            )
        } catch (error: IOException) {
            throw PhotoCanonicalRecoveryException(
                "photo transaction journal could not be read for ownership validation",
                error
            )
        } catch (error: SecurityException) {
            throw PhotoCanonicalRecoveryException(
                "photo transaction journal could not be read for ownership validation",
                error
            )
        }
        if (actual != expected) {
            throw PhotoCanonicalRecoveryException(
                "photo transaction ownership changed; recovery evidence retained"
            )
        }
    }

    /**
     * Records the canonical state transition before any target is published.
     * The marker is intentionally separate from the file journal, but new
     * records carry the exact journal digest so a restarted resolver cannot
     * pair an intent with a later transaction's artifacts.
     */
    internal fun beginPhotoCanonicalRecovery(
        record: PhotoCanonicalRecoveryRecord,
        previousLiveSnapshot: DocumentSnapshotV1? = null
    ) {
        require(pendingCanonicalRecovery == null) { "photo canonical recovery is already pending" }
        if (!operations.exists(PHOTO_TRANSACTION_MARKER)) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery intent requires a prepared photo journal"
            )
        }
        if (operations.exists(PHOTO_TRANSACTION_COMMITTED_MARKER) ||
            operations.exists(PHOTO_CANONICAL_INTENT_MARKER) ||
            operations.exists(PHOTO_CANONICAL_METADATA_COMMITTED_MARKER) ||
            operations.exists(PHOTO_TRANSACTION_CLEANUP_MARKER) ||
            operations.exists(PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MARKER) ||
            operations.exists(PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT) ||
            operations.exists(PHOTO_CANONICAL_LIVE_SNAPSHOT_CLEANUP_MARKER)
        ) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery has unexpected prior commit evidence"
            )
        }
        val journalBytes = try {
            readTransactionMarker()
        } catch (error: Stage5ValidationException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery journal could not be read",
                error
            )
        } catch (error: IOException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery journal could not be read",
                error
            )
        } catch (error: SecurityException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery journal could not be read",
                error
            )
        }
        try {
            parsePhotoTransactionMarker(journalBytes)
        } catch (error: Stage5ValidationException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery journal is corrupt",
                error
            )
        }
        val journalIdentity = sha256Hex(journalBytes)
        val liveSnapshotArtifact = previousLiveSnapshot?.let { snapshot ->
            validateSnapshot(snapshot)
            val actualIdentity = photoCanonicalIdentity(
                DocumentId.parse(record.previousLive.documentId),
                snapshot
            )
            if (actualIdentity != record.previousLive) {
                throw PhotoCanonicalRecoveryException(
                    "photo canonical recovery live snapshot does not match its recorded identity"
                )
            }
            if (record.previousLive == record.previous) {
                null
            } else {
                buildCanonicalLiveSnapshotArtifact(record, journalIdentity, snapshot)
            }
        }
        if (record.previousLive != record.previous && liveSnapshotArtifact == null) {
            throw PhotoCanonicalRecoveryException(
                "an unequal durable/live recovery pair requires a durable live snapshot"
            )
        }
        val marker = buildList {
            add(if (record.mode == null) PHOTO_CANONICAL_INTENT_MAGIC else PHOTO_CANONICAL_INTENT_MAGIC_V3)
            add(journalIdentity)
            if (record.mode != null) add(record.mode.name)
            add(encodeRecoveryValue(record.previous.documentId))
            add(encodeRecoveryValue(record.previous.snapshotDigest))
            add(encodePreviousCanonicalIdentityField(record.previous, record.previousLive))
            add(encodeRecoveryValue(record.intended.documentId))
            add(encodeRecoveryValue(record.intended.snapshotDigest))
            add(encodeRecoveryValue(record.intended.sourceUri))
            record.previousPhotoDigest?.let { previousDigest ->
                add(previousDigest)
                add(requireNotNull(record.intendedPhotoDigest))
            }
        }.joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.US_ASCII)
        liveSnapshotArtifact?.let {
            writeInternalFile(
                PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT,
                it,
                "photo canonical live snapshot recovery artifact"
            )
        }
        writeInternalFile(
            PHOTO_CANONICAL_INTENT_MARKER,
            marker,
            "photo canonical recovery intent"
        )
        pendingCanonicalRecovery = record.copy(journalIdentity = journalIdentity)
    }

    /**
     * Builds the bounded sidecar that makes an unequal prior live authority
     * rehydratable after process death.  The journal digest, document id,
     * canonical digest, source, and exact JSON hash are all checked again on
     * read before the payload can influence recovery.
     */
    private fun buildCanonicalLiveSnapshotArtifact(
        record: PhotoCanonicalRecoveryRecord,
        journalIdentity: String,
        snapshot: DocumentSnapshotV1
    ): ByteArray {
        val jsonBytes = PHOTO_DIGEST_GSON.toJson(snapshot).toByteArray(StandardCharsets.UTF_8)
        if (jsonBytes.isEmpty() || jsonBytes.size > Stage5Limits.MAX_JSON_BYTES) {
            throw Stage5ValidationException(
                "photo canonical live snapshot exceeds its bounded JSON size"
            )
        }
        val encodedJson = Base64.getUrlEncoder().withoutPadding().encodeToString(jsonBytes)
        val artifact = listOf(
            PHOTO_CANONICAL_LIVE_SNAPSHOT_MAGIC,
            journalIdentity,
            encodeRecoveryValue(record.previousLive.documentId),
            record.previousLive.snapshotDigest,
            encodeRecoveryValue(record.previousLive.sourceUri),
            sha256Hex(jsonBytes),
            encodedJson
        ).joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.US_ASCII)
        if (artifact.size > MAX_PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT_BYTES) {
            throw Stage5ValidationException(
                "photo canonical live snapshot recovery artifact exceeds its bounded size"
            )
        }
        return artifact
    }

    /** Reads and fully validates the journal-bound live snapshot sidecar. */
    private fun requireCanonicalLiveSnapshotArtifact(
        pending: PhotoCanonicalRecoveryRecord
    ): DocumentSnapshotV1 {
        if (pending.previousLive == pending.previous) {
            throw PhotoCanonicalRecoveryException(
                "an equal canonical recovery pair must not have a live snapshot artifact"
            )
        }
        val expectedJournal = pending.journalIdentity
            ?: throw PhotoCanonicalRecoveryException(
                "legacy canonical recovery evidence cannot own a live snapshot artifact"
            )
        val bytes = try {
            val declaredSize = operations.size(PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT)
            if (declaredSize > MAX_PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT_BYTES) {
                throw Stage5ValidationException(
                    "photo canonical live snapshot recovery artifact exceeds its bounded size"
                )
            }
            operations.openRead(PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT).use {
                readBoundedBytes(
                    it,
                    MAX_PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT_BYTES,
                    "photo canonical live snapshot recovery artifact"
                )
            }
        } catch (error: PhotoCanonicalRecoveryException) {
            throw error
        } catch (error: Stage5ValidationException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot recovery artifact could not be read",
                error
            )
        } catch (error: IOException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot recovery artifact could not be read",
                error
            )
        } catch (error: SecurityException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot recovery artifact is protected",
                error
            )
        }
        val text = bytes.toString(StandardCharsets.US_ASCII)
        if (!bytes.all { (it.toInt() and 0x80) == 0 }) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot recovery artifact is not ASCII"
            )
        }
        val body = when {
            !text.endsWith('\n') -> throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot recovery artifact is truncated"
            )
            text.dropLast(1).endsWith('\n') -> throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot recovery artifact has trailing content"
            )
            else -> text.dropLast(1)
        }
        val lines = body.split('\n')
        if (lines.size != 7 || lines[0] != PHOTO_CANONICAL_LIVE_SNAPSHOT_MAGIC) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot recovery artifact is malformed"
            )
        }
        if (lines[1] != expectedJournal) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot recovery artifact belongs to another journal"
            )
        }
        val documentId = decodeRecoveryValue(
            lines[2],
            "photo canonical live snapshot document identity encoding is invalid"
        )
        val sourceUri = decodeRecoveryValue(
            lines[4],
            "photo canonical live snapshot source identity encoding is invalid"
        )
        val liveIdentity = try {
            PhotoCanonicalIdentity(documentId, lines[3], sourceUri)
        } catch (error: IllegalArgumentException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot identity is invalid",
                error
            )
        }
        if (liveIdentity != pending.previousLive) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot identity does not match recovery evidence"
            )
        }
        if (!lines[5].matches(Regex("[0-9a-f]{64}"))) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot JSON digest is invalid"
            )
        }
        val jsonBytes = try {
            Base64.getUrlDecoder().decode(lines[6])
        } catch (error: IllegalArgumentException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot JSON is not valid Base64",
                error
            )
        }
        if (jsonBytes.isEmpty() || jsonBytes.size > Stage5Limits.MAX_JSON_BYTES) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot JSON exceeds its bounded size"
            )
        }
        if (sha256Hex(jsonBytes) != lines[5]) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot JSON digest does not match its evidence"
            )
        }
        val snapshot = try {
            decodeValidatedSnapshotJson(
                PHOTO_DIGEST_GSON,
                jsonBytes.toString(StandardCharsets.UTF_8),
                "photo canonical live snapshot"
            )
        } catch (error: PhotoCanonicalRecoveryException) {
            throw error
        } catch (error: Stage5ValidationException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot is invalid",
                error
            )
        } catch (error: IllegalArgumentException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot is invalid",
                error
            )
        }
        val materializedIdentity = photoCanonicalIdentity(
            DocumentId.parse(pending.previousLive.documentId),
            snapshot
        )
        if (materializedIdentity != pending.previousLive) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot materialized with the wrong identity"
            )
        }
        return snapshot
    }

    /**
     * Rehydrates an unequal prior live authority for a cold-start readiness
     * check.  The sidecar is validated even when the durable authority is the
     * intended/new state, so malformed evidence cannot be silently ignored.
     * A null result means the loaded durable authority is already the intended
     * side of the recorded transition, no recovery is pending, or the exact
     * rollback proof authorizes sidecar-less B/B normalization during terminal
     * cleanup. The latter is still verified by reconcilePhotoTransaction().
     */
    internal fun rehydratePreviousLiveCanonicalSnapshot(
        currentDurable: PhotoCanonicalIdentity
    ): DocumentSnapshotV1? {
        if (pendingCanonicalRecovery == null) {
            when {
                operations.exists(PHOTO_CANONICAL_INTENT_MARKER) -> loadPendingCanonicalRecoveryFromDisk()
                operations.exists(PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MARKER) -> {
                    pendingCanonicalRecovery = requireRollbackCompletionEvidence().record
                }
            }
        }
        val artifactExists = operations.exists(PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT)
        val pending = pendingCanonicalRecovery
        if (pending == null) {
            if (artifactExists) {
                throw PhotoCanonicalRecoveryException(
                    "photo canonical live snapshot artifact has no owning recovery intent"
                )
            }
            return null
        }
        if (currentDurable.documentId != pending.previous.documentId) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery durable identity does not match the photo root"
            )
        }
        if (pending.previousLive == pending.previous) {
            if (artifactExists) {
                throw PhotoCanonicalRecoveryException(
                    "equal canonical recovery evidence has an unexpected live snapshot artifact"
                )
            }
            return null
        }
        requireCanonicalRecoveryJournal(pending)
        if (!artifactExists) {
            // A sidecar-less unequal-live record is safe only in the narrow
            // terminal-cleanup window whose journal-bound rollback proof was
            // already written before the sidecar was deleted.  Let
            // reconcilePhotoTransaction() verify the resulting durable/live
            // B/B normalization and finish cleanup; every other missing,
            // tampered, or prematurely deleted sidecar remains fail-closed.
            if (!isAlreadyProvenRollbackWithoutLiveSnapshot(pending)) {
                throw PhotoCanonicalRecoveryException(
                    "unequal canonical recovery evidence is missing its live snapshot artifact"
                )
            }
            return null
        }
        val liveSnapshot = requireCanonicalLiveSnapshotArtifact(pending)
        return if (currentDurable == pending.previous) liveSnapshot else null
    }

    /**
     * Records that the metadata authority has durably committed the intended
     * remote acceptance.  This is a separate CREATE_NEW marker so a crash
     * before this point remains distinguishable from the safe new/new phase.
     */
    internal fun markPhotoCanonicalMetadataCommitted(transactionIdentity: String) {
        requirePhotoTransactionIdentity(transactionIdentity)
        val pending = pendingCanonicalRecovery
            ?: throw PhotoCanonicalRecoveryException(
                "photo metadata phase requires a pending canonical recovery intent"
            )
        if (pending.mode != PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE) {
            throw PhotoCanonicalRecoveryException(
                "photo metadata phase is not valid for this recovery intent"
            )
        }
        if (operations.exists(PHOTO_CANONICAL_METADATA_COMMITTED_MARKER)) {
            requireMetadataPhaseIdentity(transactionIdentity)
            return
        }
        writeInternalFile(
            PHOTO_CANONICAL_METADATA_COMMITTED_MARKER,
            "$PHOTO_METADATA_COMMITTED_MAGIC\n$transactionIdentity\n"
                .toByteArray(StandardCharsets.US_ASCII),
            "photo canonical metadata phase"
        )
    }

    /**
     * Persists the metadata tuple expected by a compensating rollback before
     * photo bytes are restored. The existing cleanup marker is used as the
     * rollback state record so a fresh resolver can distinguish an in-progress
     * cross-store rollback from ordinary post-commit cleanup.
     */
    internal fun beginPhotoCanonicalRollback(
        transactionIdentity: String,
        previousMetadataIdentity: String
    ) {
        requirePhotoTransactionIdentity(transactionIdentity)
        if (!previousMetadataIdentity.matches(Regex("[0-9a-f]{64}"))) {
            throw PhotoCanonicalRecoveryException(
                "photo rollback metadata identity is invalid; evidence retained"
            )
        }
        val pending = pendingCanonicalRecovery
            ?: throw PhotoCanonicalRecoveryException(
                "photo rollback requires a pending canonical recovery intent"
            )
        if (pending.mode != PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE) {
            throw PhotoCanonicalRecoveryException(
                "photo rollback metadata binding requires a remote-acceptance intent"
            )
        }
        when (val probe = probeAuthoritativePhotoTransactionCommitMarker(transactionIdentity)) {
            PhotoCommitMarkerProbe.Absent -> Unit
            PhotoCommitMarkerProbe.Bound -> throw PhotoCanonicalRecoveryException(
                "photo rollback cannot begin after an authoritative photo commit"
            )
            is PhotoCommitMarkerProbe.Ambiguous -> throw probe.error
        }
        val existing = readRollbackPendingMetadataIdentity(transactionIdentity)
        if (existing != null) {
            if (existing != previousMetadataIdentity) {
                throw PhotoCanonicalRecoveryException(
                    "photo rollback evidence belongs to another metadata tuple"
                )
            }
            return
        }
        if (operations.exists(PHOTO_TRANSACTION_CLEANUP_MARKER)) {
            throw PhotoCanonicalRecoveryException(
                "photo rollback cleanup evidence is in an incompatible state"
            )
        }
        writeInternalFile(
            PHOTO_TRANSACTION_CLEANUP_MARKER,
            "$PHOTO_TRANSACTION_ROLLBACK_PENDING_MAGIC\n$transactionIdentity\n$previousMetadataIdentity\n"
                .toByteArray(StandardCharsets.US_ASCII),
            "photo cross-store rollback intent"
        )
    }

    /**
     * Durable completion proof for a cross-store rollback. This marker is
     * written only after canonical durable/live state and sync metadata have
     * both been restored, and after photo bytes have been restored while the
     * journal was retained. A restart may therefore resume marker cleanup,
     * but never infer completion from a missing commit marker.
     */
    internal fun markPhotoCanonicalRollbackComplete(
        transactionIdentity: String,
        previousMetadataIdentity: String? = null
    ) {
        requirePhotoTransactionIdentity(transactionIdentity)
        val pending = pendingCanonicalRecovery
            ?: throw PhotoCanonicalRecoveryException(
                "photo rollback completion requires a pending canonical recovery intent"
            )
        if (pending.mode != PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE) {
            throw PhotoCanonicalRecoveryException(
                "photo rollback completion is not valid for this recovery intent"
            )
        }
        val (entries, identity) = requireCanonicalRecoveryJournal(pending)
        check(identity == transactionIdentity) { "photo rollback completion journal identity changed" }
        pending.previousPhotoDigest?.let { expected ->
            val actual = currentPhotoTransactionContentDigest(entries)
            if (actual != expected) {
                throw PhotoCanonicalRecoveryException(
                    "photo rollback completion bytes do not match the previous authority"
                )
            }
        }
        if (operations.exists(PHOTO_TRANSACTION_COMMITTED_MARKER)) {
            throw PhotoCanonicalRecoveryException(
                "photo rollback completion cannot follow an authoritative photo commit"
            )
        }
        val rollbackMetadataIdentity = readRollbackPendingMetadataIdentity(transactionIdentity)
        if (rollbackMetadataIdentity != null) {
            if (previousMetadataIdentity == null || previousMetadataIdentity != rollbackMetadataIdentity) {
                throw PhotoCanonicalRecoveryException(
                    "photo rollback completion is not bound to the restored metadata tuple"
                )
            }
            // The pending marker contains only the metadata identity. Write a
            // second, complete proof before cleanup can remove the canonical
            // intent or journal. This closes the partial-cleanup window where
            // a fresh process otherwise could not reconstruct the old tuple.
            ensureRollbackCompletionEvidence(
                pending,
                transactionIdentity,
                rollbackMetadataIdentity
            )
            return
        }
        if (pending.mode == PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE) {
            // A remote-acceptance intent must never be downgraded to the
            // historical V1 completion record. Without the V3 pending
            // metadata identity there is no proof that the other authorities
            // were restored, so retain the intent/journal and fail closed.
            throw PhotoCanonicalRecoveryException(
                "remote-acceptance rollback requires V3 rollback-pending evidence"
            )
        }
        if (operations.exists(PHOTO_TRANSACTION_CLEANUP_MARKER)) {
            requireCleanupMarkerIdentity(transactionIdentity)
            return
        }
        writeInternalFile(
            PHOTO_TRANSACTION_CLEANUP_MARKER,
            "$PHOTO_TRANSACTION_ROLLBACK_COMPLETE_MAGIC\n$transactionIdentity\n"
                .toByteArray(StandardCharsets.US_ASCII),
            "photo cross-store rollback completion"
        )
    }

    /**
     * Reconciles a pending file replacement with the two canonical authorities
     * available at the active document boundary. Only an exact old/old or
     * new/new match is actionable; mixed or unrelated identities remain on
     * disk as evidence and surface typed RECOVERY to the caller.
     */
    internal fun reconcilePhotoTransaction(
        currentDurable: PhotoCanonicalIdentity,
        currentLive: PhotoCanonicalIdentity,
        currentMetadataIdentity: String? = null
    ): PhotoRecoveryAction {
        if (pendingCanonicalRecovery == null) {
            when {
                operations.exists(PHOTO_CANONICAL_INTENT_MARKER) -> loadPendingCanonicalRecoveryFromDisk()
                operations.exists(PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MARKER) -> {
                    pendingCanonicalRecovery = requireRollbackCompletionEvidence().record
                }
            }
        }
        val pending = pendingCanonicalRecovery
        if (pending == null) {
            if (operations.exists(PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT)) {
                throw PhotoCanonicalRecoveryException(
                    "photo canonical live snapshot artifact has no owning recovery intent"
                )
            }
            return PhotoRecoveryAction.NONE
        }
        if (currentDurable.documentId != pending.previous.documentId ||
            currentLive.documentId != pending.previousLive.documentId
        ) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery document identity does not match the photo root"
            )
        }
        val (journalEntries, journalIdentity) = requireCanonicalRecoveryJournal(pending)
        if (pending.previousLive == pending.previous) {
            if (operations.exists(PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT)) {
                throw PhotoCanonicalRecoveryException(
                    "equal canonical recovery evidence has an unexpected live snapshot artifact"
                )
            }
        } else {
            val liveSnapshotArtifactExists = operations.exists(PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT)
            if (liveSnapshotArtifactExists) {
                requireCanonicalLiveSnapshotArtifact(pending)
            } else if (
                !isAlreadyProvenRollbackWithoutLiveSnapshot(pending)
            ) {
                throw PhotoCanonicalRecoveryException(
                    "unequal canonical recovery evidence is missing its live snapshot artifact"
                )
            }
        }
        val rollbackMetadataIdentity = if (pending.mode == PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE) {
            readRollbackPendingMetadataIdentity(journalIdentity)
                ?: if (operations.exists(PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MARKER)) {
                    // The pending cleanup marker may already have been
                    // deleted after the complete V2 proof was written. The
                    // proof carries the same old metadata identity and is
                    // the only remaining owner in that crash window.
                    requireRollbackCompletionEvidence(journalIdentity).previousMetadataIdentity
                } else {
                    null
                }
        } else {
            null
        }
        if (rollbackMetadataIdentity != null) rollbackRecoveryPending = true
        val actualPhotoDigest = try {
            if (pending.previousPhotoDigest != null) {
                currentPhotoTransactionContentDigest(journalEntries)
            } else {
                null
            }
        } catch (error: Stage5ValidationException) {
            closeAfterRecoveryFailure()
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery content could not be verified; evidence retained",
                error
            )
        } catch (error: IOException) {
            closeAfterRecoveryFailure()
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery content could not be read; evidence retained",
                error
            )
        } catch (error: SecurityException) {
            closeAfterRecoveryFailure()
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery content is protected; evidence retained",
                error
            )
        }
        fun matchesPhotoDigest(expected: String?): Boolean =
            expected == null || actualPhotoDigest == expected
        val photoMatchesPrevious = matchesPhotoDigest(pending.previousPhotoDigest)
        val photoMatchesIntended = matchesPhotoDigest(pending.intendedPhotoDigest)
        val durableIsPrevious = currentDurable == pending.previous &&
            photoMatchesPrevious
        val liveIsPrevious = currentLive == pending.previousLive &&
            photoMatchesPrevious
        val liveWasAlreadyProvenBeforeRestart =
            isAlreadyProvenRollbackWithoutLiveSnapshot(pending) &&
                currentLive == pending.previous
        val durableIsIntended = currentDurable == pending.intended &&
            matchesPhotoDigest(pending.intendedPhotoDigest)
        val liveIsIntended = currentLive == pending.intended &&
            matchesPhotoDigest(pending.intendedPhotoDigest)
        return try {
            val committedMarker = when (
                val probe = probeAuthoritativePhotoTransactionCommitMarker(journalIdentity)
            ) {
                PhotoCommitMarkerProbe.Absent -> false
                PhotoCommitMarkerProbe.Bound -> true
                is PhotoCommitMarkerProbe.Ambiguous -> throw probe.error
            }
            val metadataCommitted = if (pending.mode == PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE) {
                if (operations.exists(PHOTO_CANONICAL_METADATA_COMMITTED_MARKER)) {
                    requireMetadataPhaseIdentity(journalIdentity)
                    true
                } else {
                    false
                }
            } else {
                false
            }
            when {
                pending.mode == PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE &&
                    rollbackMetadataIdentity != null -> {
                    if (committedMarker) {
                        throw PhotoCanonicalRecoveryException(
                            "cross-store rollback evidence conflicts with an authoritative photo commit"
                        )
                    }
                    if (currentMetadataIdentity == null ||
                        currentMetadataIdentity != rollbackMetadataIdentity
                    ) {
                        throw PhotoCanonicalRecoveryException(
                            "cross-store rollback metadata authority is not the recorded old tuple; evidence retained"
                        )
                    }
                    if (!durableIsPrevious ||
                        (!liveIsPrevious && !liveWasAlreadyProvenBeforeRestart) ||
                        !photoMatchesPrevious
                    ) {
                        throw PhotoCanonicalRecoveryException(
                            "cross-store rollback authorities are mixed; evidence retained"
                        )
                    }
                    clearPhotoTransactionMarkers(journalIdentity)
                    pendingCanonicalRecovery = null
                    rollbackRecoveryPending = false
                    PhotoRecoveryAction.ROLLED_BACK
                }
                pending.mode == PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE &&
                    durableIsIntended && liveIsIntended && metadataCommitted -> {
                    finalizePhotoTransaction(journalEntries, journalIdentity)
                    pendingCanonicalRecovery = null
                    rollbackRecoveryPending = false
                    PhotoRecoveryAction.FINALIZED
                }
                pending.mode == PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE &&
                    !metadataCommitted -> throw PhotoCanonicalRecoveryException(
                    "remote-acceptance metadata phase is not durably recorded; evidence retained"
                )
                pending.mode == null && durableIsIntended && liveIsIntended -> {
                    finalizePhotoTransaction(journalEntries, journalIdentity)
                    pendingCanonicalRecovery = null
                    rollbackRecoveryPending = false
                    PhotoRecoveryAction.FINALIZED
                }
                pending.mode == null &&
                    currentDurable == pending.previous &&
                    currentLive == pending.previousLive &&
                    !committedMarker && (photoMatchesPrevious || photoMatchesIntended) -> {
                    rollbackPhotoTransaction(journalEntries, journalIdentity)
                    pendingCanonicalRecovery = null
                    rollbackRecoveryPending = false
                    PhotoRecoveryAction.ROLLED_BACK
                }
                else -> throw PhotoCanonicalRecoveryException(
                    "photo, canonical, and metadata state disagree during recovery; evidence retained"
                )
            }
        } catch (error: PhotoCanonicalRecoveryException) {
            throw error
        } catch (error: Stage5ValidationException) {
            closeAfterRecoveryFailure()
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery could not complete safely; evidence retained",
                error
            )
        } catch (error: IOException) {
            closeAfterRecoveryFailure()
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery could not complete safely; evidence retained",
                error
            )
        } catch (error: SecurityException) {
            closeAfterRecoveryFailure()
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery could not complete safely; evidence retained",
                error
            )
        }
    }

    /**
     * Verifies the canonical intent's owner before any recovery decision or
     * file mutation. Legacy V1 intents deliberately have no owner proof and
     * therefore remain evidence rather than becoming actionable.
     */
    private fun requireCanonicalRecoveryJournal(
        pending: PhotoCanonicalRecoveryRecord
    ): Pair<List<PhotoTransactionJournalEntry>, String> {
        val expected = pending.journalIdentity
            ?: throw PhotoCanonicalRecoveryException(
                "legacy photo canonical recovery intent has no provable journal owner"
            )
        val bytes = try {
            if (operations.exists(PHOTO_TRANSACTION_MARKER)) {
                readTransactionMarker()
            } else {
                requireRollbackCompletionEvidence(expected).journalBytes
            }
        } catch (error: PhotoCanonicalRecoveryException) {
            throw error
        } catch (error: Stage5ValidationException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery journal could not be read; evidence retained",
                error
            )
        } catch (error: IOException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery journal could not be read; evidence retained",
                error
            )
        } catch (error: SecurityException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery journal could not be read; evidence retained",
                error
            )
        }
        val entries = try {
            parsePhotoTransactionMarker(bytes)
        } catch (error: Stage5ValidationException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery journal is corrupt; evidence retained",
                error
            )
        }
        val actual = sha256Hex(bytes)
        if (actual != expected) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery intent is bound to another journal; evidence retained"
            )
        }
        return entries to actual
    }

    /**
     * A complete rollback proof is written only after the old durable/live,
     * metadata, and photo authorities have been checked.  If the live sidecar
     * was then deleted and the process died during terminal cleanup, a cold
     * start cannot materialize the old unsaved live snapshot again.  The
     * proof plus the cleanup phase is nevertheless an explicit, journal-bound
     * indication that cleanup was already authorized; callers may normalize
     * the fresh process's live authority to the durable snapshot, but only
     * after this exact proof and the remaining tuple are checked.
     */
    private fun isAlreadyProvenRollbackWithoutLiveSnapshot(
        pending: PhotoCanonicalRecoveryRecord
    ): Boolean = try {
        if (pending.mode != PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE ||
            pending.previousLive == pending.previous ||
            !operations.exists(PHOTO_CANONICAL_LIVE_SNAPSHOT_CLEANUP_MARKER) ||
            operations.exists(PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT) ||
            !operations.exists(PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MARKER)
        ) {
            false
        } else {
            val evidence = requireRollbackCompletionEvidence(pending.journalIdentity)
            if (evidence.record != pending) {
                throw PhotoCanonicalRecoveryException(
                    "photo rollback proof disagrees with the canonical recovery intent"
                )
            }
            requireCanonicalRecoveryJournal(evidence.record)
            true
        }
    } catch (error: PhotoCanonicalRecoveryException) {
        throw error
    } catch (error: Stage5ValidationException) {
        throw PhotoCanonicalRecoveryException(
            "photo rollback proof could not be validated; evidence retained",
            error
        )
    } catch (error: IOException) {
        throw PhotoCanonicalRecoveryException(
            "photo rollback proof could not be read; evidence retained",
            error
        )
    } catch (error: SecurityException) {
        throw PhotoCanonicalRecoveryException(
            "photo rollback proof could not be verified; evidence retained",
            error
        )
    }

    /**
     * An already-open store can outlive the resolver that prepared a
     * transaction (the explicit legacy migration path does this). Refresh the
     * pending intent from its durable bytes before a later same-store
     * reconciliation, while still requiring the intent's journal owner.
     */
    private fun loadPendingCanonicalRecoveryFromDisk() {
        val intentBytes = try {
            operations.openRead(PHOTO_CANONICAL_INTENT_MARKER).use {
                readBoundedBytes(it, 64 * 1024, "photo canonical recovery intent")
            }
        } catch (error: Stage5ValidationException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery intent could not be read",
                error
            )
        } catch (error: IOException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery intent could not be read",
                error
            )
        } catch (error: SecurityException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery intent could not be read",
                error
            )
        }
        val parsed = try {
            parsePhotoCanonicalRecoveryMarker(intentBytes)
        } catch (error: Stage5ValidationException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery intent is corrupt",
                error
            )
        }
        val expected = parsed.journalIdentity
            ?: throw PhotoCanonicalRecoveryException(
                "legacy photo canonical recovery intent has no provable journal owner"
            )
        val journalBytes = try {
            readTransactionMarker()
        } catch (error: Stage5ValidationException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery journal could not be read",
                error
            )
        } catch (error: IOException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery journal could not be read",
                error
            )
        } catch (error: SecurityException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery journal could not be read",
                error
            )
        }
        try {
            parsePhotoTransactionMarker(journalBytes)
        } catch (error: Stage5ValidationException) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery journal is corrupt",
                error
            )
        }
        val actual = sha256Hex(journalBytes)
        if (actual != expected) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery intent is bound to another journal"
            )
        }
        if (parsed.mode == null && operations.exists(PHOTO_CANONICAL_METADATA_COMMITTED_MARKER)) {
            throw PhotoCanonicalRecoveryException(
                "photo metadata phase is not bound to a remote-acceptance intent"
            )
        }
        if (parsed.mode == PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE &&
            operations.exists(PHOTO_CANONICAL_METADATA_COMMITTED_MARKER)
        ) {
            requireMetadataPhaseIdentity(actual)
        }
        pendingCanonicalRecovery = parsed
    }

    internal fun requireCanonicalRecoveryResolved() {
        if (pendingCanonicalRecovery != null ||
            rollbackRecoveryPending ||
            operations.exists(PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT) ||
            operations.exists(PHOTO_CANONICAL_LIVE_SNAPSHOT_CLEANUP_MARKER)
        ) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery is required before photo access"
            )
        }
    }

    /**
     * Targets named by a durable journal are protected from generated-photo
     * GC until the journal is cleared. This closes the interval between
     * staging/publication and canonical commit for transaction replacements.
     */
    internal fun currentPhotoTransactionTargets(): Set<String> {
        if (!operations.exists(PHOTO_TRANSACTION_MARKER)) return emptySet()
        if (pendingCanonicalRecovery == null && operations.exists(PHOTO_CANONICAL_INTENT_MARKER)) {
            loadPendingCanonicalRecoveryFromDisk()
        }
        val entries = pendingCanonicalRecovery?.let { requireCanonicalRecoveryJournal(it).first }
            ?: parsePhotoTransactionMarker(readTransactionMarker())
        return entries
            .mapTo(linkedSetOf()) { it.targetName }
    }

    internal fun markPhotoTransactionCommitted(transactionIdentity: String? = null) {
        transactionIdentity?.let(::requirePhotoTransactionIdentity)
        if (!operations.exists(PHOTO_TRANSACTION_COMMITTED_MARKER)) {
            writeInternalFile(
                PHOTO_TRANSACTION_COMMITTED_MARKER,
                if (transactionIdentity == null) {
                    "COMMITTED\n"
                } else {
                    "COMMITTED\n$transactionIdentity\n"
                }.toByteArray(StandardCharsets.US_ASCII),
                "photo transaction commit marker"
            )
            if (transactionIdentity != null) {
                when (val probe = probeAuthoritativePhotoTransactionCommitMarker(transactionIdentity)) {
                    PhotoCommitMarkerProbe.Bound -> Unit
                    PhotoCommitMarkerProbe.Absent -> throw PhotoCanonicalRecoveryException(
                        "photo transaction commit marker disappeared after it was written; evidence retained"
                    )
                    is PhotoCommitMarkerProbe.Ambiguous -> throw probe.error
                }
            }
        } else if (transactionIdentity != null) {
            requireCommitMarkerIdentity(transactionIdentity)
        }
    }

    /**
     * Reads the authoritative commit marker without collapsing an unreadable,
     * malformed, or foreign marker into absence. The caller must retain the
     * journal whenever [Ambiguous] is returned.
     */
    internal fun probeAuthoritativePhotoTransactionCommitMarker(
        transactionIdentity: String
    ): PhotoCommitMarkerProbe {
        val present = try {
            operations.exists(PHOTO_TRANSACTION_COMMITTED_MARKER)
        } catch (error: Stage5ValidationException) {
            return PhotoCommitMarkerProbe.Ambiguous(
                PhotoCanonicalRecoveryException(
                    "photo transaction commit marker presence is ambiguous; evidence retained",
                    error
                )
            )
        } catch (error: IOException) {
            return PhotoCommitMarkerProbe.Ambiguous(
                PhotoCanonicalRecoveryException(
                    "photo transaction commit marker presence could not be read; evidence retained",
                    error
                )
            )
        } catch (error: SecurityException) {
            return PhotoCommitMarkerProbe.Ambiguous(
                PhotoCanonicalRecoveryException(
                    "photo transaction commit marker presence is protected; evidence retained",
                    error
                )
            )
        } catch (error: IllegalArgumentException) {
            return PhotoCommitMarkerProbe.Ambiguous(
                PhotoCanonicalRecoveryException(
                    "photo transaction commit marker presence is invalid; evidence retained",
                    error
                )
            )
        } catch (error: IllegalStateException) {
            return PhotoCommitMarkerProbe.Ambiguous(
                PhotoCanonicalRecoveryException(
                    "photo transaction commit marker presence is unavailable; evidence retained",
                    error
                )
            )
        }
        if (!present) return PhotoCommitMarkerProbe.Absent
        return try {
            requireCommitMarkerIdentity(transactionIdentity)
            PhotoCommitMarkerProbe.Bound
        } catch (error: PhotoCanonicalRecoveryException) {
            PhotoCommitMarkerProbe.Ambiguous(error)
        } catch (error: Stage5ValidationException) {
            PhotoCommitMarkerProbe.Ambiguous(
                PhotoCanonicalRecoveryException(
                    "photo transaction commit marker is malformed; evidence retained",
                    error
                )
            )
        } catch (error: IOException) {
            PhotoCommitMarkerProbe.Ambiguous(
                PhotoCanonicalRecoveryException(
                    "photo transaction commit marker could not be read; evidence retained",
                    error
                )
            )
        } catch (error: SecurityException) {
            PhotoCommitMarkerProbe.Ambiguous(
                PhotoCanonicalRecoveryException(
                    "photo transaction commit marker is protected; evidence retained",
                    error
                )
            )
        } catch (error: IllegalArgumentException) {
            PhotoCommitMarkerProbe.Ambiguous(
                PhotoCanonicalRecoveryException(
                    "photo transaction commit marker is invalid; evidence retained",
                    error
                )
            )
        } catch (error: IllegalStateException) {
            PhotoCommitMarkerProbe.Ambiguous(
                PhotoCanonicalRecoveryException(
                    "photo transaction commit marker state is invalid; evidence retained",
                    error
                )
            )
        }
    }

    /** Compatibility boolean for callers that only need the safe positive case. */
    internal fun hasAuthoritativePhotoTransactionCommitMarker(transactionIdentity: String): Boolean =
        when (val probe = probeAuthoritativePhotoTransactionCommitMarker(transactionIdentity)) {
            PhotoCommitMarkerProbe.Absent -> false
            PhotoCommitMarkerProbe.Bound -> true
            is PhotoCommitMarkerProbe.Ambiguous -> throw probe.error
        }

    /**
     * Cleanup is itself journaled. A complete rollback proof and, when
     * present, the sidecar-cleanup phase are retained until all authority
     * markers are removed. A process death or injected partial delete can
     * therefore be resumed without treating a missing sidecar as an orphan.
     */
    internal fun clearPhotoTransactionMarkers(transactionIdentity: String? = null) {
        val rollbackEvidence = if (operations.exists(PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MARKER)) {
            requireRollbackCompletionEvidence(transactionIdentity)
        } else {
            null
        }
        val journalExists = operations.exists(PHOTO_TRANSACTION_MARKER)
        val journalBytes = if (journalExists) {
            readTransactionMarker()
        } else {
            rollbackEvidence?.journalBytes
                ?: throw PhotoCanonicalRecoveryException(
                    "photo transaction cleanup has no journal or complete rollback evidence"
                )
        }
        parsePhotoTransactionMarker(journalBytes)
        val identity = transactionIdentity
            ?: rollbackEvidence?.record?.journalIdentity
            ?: sha256Hex(journalBytes)
        if (journalExists) {
            requirePhotoTransactionIdentity(identity)
        } else if (rollbackEvidence?.record?.journalIdentity != identity) {
            throw PhotoCanonicalRecoveryException(
                "photo transaction cleanup evidence has no matching journal owner"
            )
        }
        if (rollbackEvidence != null &&
            !rollbackEvidence.journalBytes.contentEquals(journalBytes)
        ) {
            throw PhotoCanonicalRecoveryException(
                "photo rollback completion evidence contains a different journal"
            )
        }
        if (operations.exists(PHOTO_TRANSACTION_COMMITTED_MARKER)) {
            when (val probe = probeAuthoritativePhotoTransactionCommitMarker(identity)) {
                PhotoCommitMarkerProbe.Bound -> Unit
                PhotoCommitMarkerProbe.Absent -> throw PhotoCanonicalRecoveryException(
                    "photo transaction commit marker disappeared during cleanup"
                )
                is PhotoCommitMarkerProbe.Ambiguous -> throw probe.error
            }
        }
        if (operations.exists(PHOTO_CANONICAL_INTENT_MARKER)) {
            val intent = requireCanonicalIntentIdentity(identity)
            if (rollbackEvidence != null && intent != rollbackEvidence.record) {
                throw PhotoCanonicalRecoveryException(
                    "photo rollback completion evidence disagrees with the canonical intent"
                )
            }
        }
        if (operations.exists(PHOTO_CANONICAL_METADATA_COMMITTED_MARKER)) {
            requireMetadataPhaseIdentity(identity)
        }
        val rollbackMetadataIdentity = readRollbackPendingMetadataIdentity(identity)
        val completeRollbackEvidence = rollbackEvidence ?: if (rollbackMetadataIdentity != null) {
            val pending = pendingCanonicalRecovery
                ?: if (operations.exists(PHOTO_CANONICAL_INTENT_MARKER)) {
                    requireCanonicalIntentIdentity(identity)
                } else {
                    throw PhotoCanonicalRecoveryException(
                        "photo rollback cleanup has no canonical recovery intent"
                    )
                }
            ensureRollbackCompletionEvidence(
                pending,
                identity,
                rollbackMetadataIdentity
            )
        } else {
            null
        }
        if (completeRollbackEvidence != null &&
            rollbackMetadataIdentity != null &&
            completeRollbackEvidence.previousMetadataIdentity != rollbackMetadataIdentity
        ) {
            throw PhotoCanonicalRecoveryException(
                "photo rollback cleanup metadata evidence changed"
            )
        }
        val artifactRecord = rollbackEvidence?.record
            ?: pendingCanonicalRecovery
            ?: if (operations.exists(PHOTO_CANONICAL_INTENT_MARKER)) {
                requireCanonicalIntentIdentity(identity)
            } else {
                null
            }
        val liveSnapshotExists = operations.exists(PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT)
        val liveSnapshotCleanupMarkerExists =
            operations.exists(PHOTO_CANONICAL_LIVE_SNAPSHOT_CLEANUP_MARKER)
        if (liveSnapshotCleanupMarkerExists) {
            requireLiveSnapshotCleanupMarkerIdentity(identity)
        }
        val liveSnapshotCleanupRequired = liveSnapshotExists || liveSnapshotCleanupMarkerExists
        if (liveSnapshotExists) {
            val record = artifactRecord
                ?: throw PhotoCanonicalRecoveryException(
                    "photo canonical live snapshot artifact has no owning recovery intent"
                )
            requireCanonicalLiveSnapshotArtifact(record)
            // This phase marker is the durable owner of the sidecar deletion.
            // It is created while every existing journal/intent/proof remains
            // intact and is removed only after the sidecar and all owners have
            // been durably cleaned.
            ensureLiveSnapshotCleanupMarker(identity)
        } else if (
            artifactRecord != null &&
            artifactRecord.previousLive != artifactRecord.previous &&
            !liveSnapshotCleanupMarkerExists
        ) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot evidence disappeared before cleanup was authorized"
            )
        }
        ensureCleanupMarker(identity)

        // Delete the rehydratable live authority before any journal, intent, or
        // rollback proof. If this operation fails, every owner and the exact
        // sidecar bytes remain available to a fresh resolver.
        if (liveSnapshotExists) {
            deleteMarker(
                PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT,
                "photo canonical live snapshot recovery artifact cleanup"
            )
        }

        deleteMarker(PHOTO_CANONICAL_INTENT_MARKER, "photo canonical intent cleanup")
        deleteMarker(
            PHOTO_CANONICAL_METADATA_COMMITTED_MARKER,
            "photo canonical metadata phase cleanup"
        )
        deleteMarker(PHOTO_TRANSACTION_COMMITTED_MARKER, "photo transaction commit cleanup")
        deleteMarker(PHOTO_TRANSACTION_MARKER, "photo transaction marker cleanup")
        try {
            deleteMarker(PHOTO_TRANSACTION_CLEANUP_MARKER, "photo transaction cleanup evidence")
        } catch (error: IOException) {
            // The journal was deleted immediately before this final marker
            // operation. If the provider rejects that deletion, restore the
            // exact journal bytes while the cleanup evidence still proves its
            // owner. A restart can then validate and resume the same cleanup;
            // it never has to infer ownership from a fixed-path marker alone.
            if (!operations.exists(PHOTO_TRANSACTION_MARKER)) {
                try {
                    writeInternalFile(
                        PHOTO_TRANSACTION_MARKER,
                        journalBytes,
                        "photo transaction journal retention"
                    )
                } catch (retentionFailure: Stage5ValidationException) {
                    error.addSuppressed(retentionFailure)
                } catch (retentionFailure: IOException) {
                    error.addSuppressed(retentionFailure)
                } catch (retentionFailure: SecurityException) {
                    error.addSuppressed(retentionFailure)
                }
            }
            throw error
        }
        // Keep the rollback proof until the owner markers have been removed.
        // The sidecar-cleanup phase marker is deleted last: if a process dies
        // after the sidecar delete, its presence tells a fresh resolver that
        // the sidecar removal was already authorized and can be completed
        // without treating the missing sidecar as an orphan.
        if (completeRollbackEvidence != null) {
            deleteMarker(
                PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MARKER,
                "photo rollback completion evidence cleanup"
            )
        }
        if (liveSnapshotCleanupRequired) {
            deleteMarker(
                PHOTO_CANONICAL_LIVE_SNAPSHOT_CLEANUP_MARKER,
                "photo canonical live snapshot cleanup phase"
            )
        }
    }

    /**
     * Writes a complete rollback proof before any authority marker is
     * deleted.  The ordinary pending marker intentionally stays in place;
     * this additional CREATE_NEW record is the durable fallback if cleanup
     * later removes the canonical intent or journal and then fails.
     */
    private fun ensureRollbackCompletionEvidence(
        pending: PhotoCanonicalRecoveryRecord,
        expectedIdentity: String,
        previousMetadataIdentity: String
    ): PhotoRollbackCompletionEvidence {
        if (pending.mode != PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE ||
            pending.journalIdentity != expectedIdentity
        ) {
            throw PhotoCanonicalRecoveryException(
                "photo rollback completion is not bound to the remote-acceptance journal"
            )
        }
        if (!previousMetadataIdentity.matches(Regex("[0-9a-f]{64}"))) {
            throw PhotoCanonicalRecoveryException(
                "photo rollback completion metadata identity is invalid"
            )
        }
        val previousPhotoDigest = pending.previousPhotoDigest
            ?: throw PhotoCanonicalRecoveryException(
                "photo rollback completion is missing the previous photo digest"
            )
        val intendedPhotoDigest = pending.intendedPhotoDigest
            ?: throw PhotoCanonicalRecoveryException(
                "photo rollback completion is missing the intended photo digest"
            )
        val journalBytes = readTransactionMarker()
        try {
            parsePhotoTransactionMarker(journalBytes)
        } catch (error: Stage5ValidationException) {
            throw PhotoCanonicalRecoveryException(
                "photo rollback completion journal is corrupt",
                error
            )
        }
        if (sha256Hex(journalBytes) != expectedIdentity) {
            throw PhotoCanonicalRecoveryException(
                "photo rollback completion journal ownership changed"
            )
        }
        val marker = buildRollbackCompletionEvidence(
            pending,
            previousMetadataIdentity,
            journalBytes
        )
        if (marker.size > MAX_PHOTO_ROLLBACK_EVIDENCE_BYTES) {
            throw PhotoCanonicalRecoveryException(
                "photo rollback completion evidence exceeds its bounded size"
            )
        }
        if (operations.exists(PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MARKER)) {
            val existing = requireRollbackCompletionEvidence(expectedIdentity)
            if (existing.previousMetadataIdentity != previousMetadataIdentity ||
                existing.record != pending ||
                !existing.journalBytes.contentEquals(journalBytes)
            ) {
                throw PhotoCanonicalRecoveryException(
                    "photo rollback completion evidence changed while cleanup was pending"
                )
            }
            return existing
        }
        writeInternalFile(
            PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MARKER,
            marker,
            "photo rollback completion evidence"
        )
        return PhotoRollbackCompletionEvidence(
            record = pending,
            previousMetadataIdentity = previousMetadataIdentity,
            journalBytes = journalBytes.copyOf()
        )
    }

    private fun buildRollbackCompletionEvidence(
        record: PhotoCanonicalRecoveryRecord,
        previousMetadataIdentity: String,
        journalBytes: ByteArray
    ): ByteArray {
        val journalIdentity = requireNotNull(record.journalIdentity)
        val previousPhotoDigest = requireNotNull(record.previousPhotoDigest)
        val intendedPhotoDigest = requireNotNull(record.intendedPhotoDigest)
        return listOf(
            PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MAGIC,
            journalIdentity,
            previousMetadataIdentity,
            encodeRecoveryValue(record.previous.documentId),
            encodeRecoveryValue(record.previous.snapshotDigest),
            encodePreviousCanonicalIdentityField(record.previous, record.previousLive),
            encodeRecoveryValue(record.intended.documentId),
            encodeRecoveryValue(record.intended.snapshotDigest),
            encodeRecoveryValue(record.intended.sourceUri),
            previousPhotoDigest,
            intendedPhotoDigest,
            Base64.getUrlEncoder().withoutPadding().encodeToString(journalBytes)
        ).joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.US_ASCII)
    }

    /** Reads the complete rollback proof, including an embedded journal fallback. */
    private fun requireRollbackCompletionEvidence(
        expectedIdentity: String? = null
    ): PhotoRollbackCompletionEvidence {
        val bytes = operations.openRead(PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MARKER).use {
            readBoundedBytes(
                it,
                MAX_PHOTO_ROLLBACK_EVIDENCE_BYTES,
                "photo rollback completion evidence"
            )
        }
        val text = bytes.toString(StandardCharsets.US_ASCII)
        val body = when {
            !text.endsWith('\n') -> text
            text.dropLast(1).endsWith('\n') -> throw Stage5ValidationException(
                "photo rollback completion evidence has trailing content"
            )
            else -> text.dropLast(1)
        }
        val lines = body.split('\n')
        if (lines.size != 12 || lines[0] != PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MAGIC) {
            throw Stage5ValidationException("photo rollback completion evidence is malformed")
        }
        val journalIdentity = lines[1]
        if (!journalIdentity.matches(Regex("[0-9a-f]{64}")) ||
            (expectedIdentity != null && expectedIdentity != journalIdentity)
        ) {
            throw PhotoCanonicalRecoveryException(
                "photo rollback completion evidence belongs to another journal"
            )
        }
        val previousMetadataIdentity = lines[2]
        if (!previousMetadataIdentity.matches(Regex("[0-9a-f]{64}"))) {
            throw Stage5ValidationException(
                "photo rollback completion metadata identity is invalid"
            )
        }
        fun value(index: Int): String = try {
            Base64.getUrlDecoder().decode(lines[index]).toString(StandardCharsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            throw Stage5ValidationException(
                "photo rollback completion identity is not valid UTF-8 Base64",
                error
            )
        }
        val previousPhotoDigest = lines[9]
        val intendedPhotoDigest = lines[10]
        if (!previousPhotoDigest.matches(Regex("[0-9a-f]{64}")) ||
            !intendedPhotoDigest.matches(Regex("[0-9a-f]{64}"))
        ) {
            throw Stage5ValidationException(
                "photo rollback completion photo digest is invalid"
            )
        }
        val journalBytes = try {
            Base64.getUrlDecoder().decode(lines[11])
        } catch (error: IllegalArgumentException) {
            throw Stage5ValidationException(
                "photo rollback completion journal is not valid Base64",
                error
            )
        }
        if (journalBytes.isEmpty() || journalBytes.size > 1 * 1024 * 1024) {
            throw Stage5ValidationException(
                "photo rollback completion journal exceeds its bounded size"
            )
        }
        try {
            parsePhotoTransactionMarker(journalBytes)
        } catch (error: Stage5ValidationException) {
            throw Stage5ValidationException(
                "photo rollback completion journal is corrupt",
                error
            )
        }
        if (sha256Hex(journalBytes) != journalIdentity) {
            throw PhotoCanonicalRecoveryException(
                "photo rollback completion journal digest does not match its evidence"
            )
        }
        val record = try {
            val previous = parsePreviousCanonicalIdentities(value(3), value(4), value(5))
            PhotoCanonicalRecoveryRecord(
                previous = previous.durable,
                previousLive = previous.live,
                intended = PhotoCanonicalIdentity(value(6), value(7), value(8)),
                journalIdentity = journalIdentity,
                previousPhotoDigest = previousPhotoDigest,
                intendedPhotoDigest = intendedPhotoDigest,
                mode = PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE
            )
        } catch (error: IllegalArgumentException) {
            throw Stage5ValidationException(
                "photo rollback completion canonical identity is invalid",
                error
            )
        }
        return PhotoRollbackCompletionEvidence(
            record = record,
            previousMetadataIdentity = previousMetadataIdentity,
            journalBytes = journalBytes
        )
    }

    private fun ensureCleanupMarker(identity: String) {
        if (operations.exists(PHOTO_TRANSACTION_CLEANUP_MARKER)) {
            requireCleanupMarkerIdentity(identity)
            return
        }
        writeInternalFile(
            PHOTO_TRANSACTION_CLEANUP_MARKER,
            "$PHOTO_TRANSACTION_CLEANUP_MAGIC\n$identity\n"
                .toByteArray(StandardCharsets.US_ASCII),
            "photo transaction cleanup evidence"
        )
    }

    private fun ensureLiveSnapshotCleanupMarker(identity: String) {
        if (operations.exists(PHOTO_CANONICAL_LIVE_SNAPSHOT_CLEANUP_MARKER)) {
            requireLiveSnapshotCleanupMarkerIdentity(identity)
            return
        }
        writeInternalFile(
            PHOTO_CANONICAL_LIVE_SNAPSHOT_CLEANUP_MARKER,
            "$PHOTO_CANONICAL_LIVE_SNAPSHOT_CLEANUP_MAGIC\n$identity\n"
                .toByteArray(StandardCharsets.US_ASCII),
            "photo canonical live snapshot cleanup phase"
        )
    }

    private fun deleteMarker(name: String, label: String) {
        try {
            operations.delete(name)
        } catch (error: IOException) {
            throw IOException("$label failed", error)
        } catch (error: SecurityException) {
            throw IOException("$label failed", error)
        }
    }

    fun ensureContained(path: Path, label: String = path.toString()) {
        val absolute = path.toAbsolutePath().normalize()
        if (!absolute.startsWith(rootPath)) {
            throw Stage5ValidationException("photo path escapes root: $label")
        }
        var current: Path? = absolute
        while (current != null && current.startsWith(rootPath)) {
            if (Files.isSymbolicLink(current)) {
                throw Stage5ValidationException("photo path contains a symbolic link: $label")
            }
            if (current == rootPath) break
            current = current.parent
        }
    }

    fun exists(path: Path): Boolean {
        return operations.exists(relativeName(path, "photo existence"))
    }

    fun isRegularFile(path: Path): Boolean {
        return operations.isRegularFile(relativeName(path, "photo type"))
    }

    /** Secure descriptor-relative size query used by aggregate preflight. */
    fun size(path: Path, label: String = path.toString()): Long {
        return try {
            operations.size(relativeName(path, label))
        } catch (error: Stage5ValidationException) {
            throw error
        } catch (error: IOException) {
            throw IOException("secure photo size query failed: $label", error)
        } catch (error: SecurityException) {
            throw IOException("secure photo size query failed: $label", error)
        }
    }

    /** Secure descriptor-relative read open; callers must close the stream. */
    fun openRead(path: Path, label: String = path.toString()): InputStream {
        return try {
            operations.openRead(relativeName(path, label))
        } catch (error: Stage5ValidationException) {
            throw error
        } catch (error: IOException) {
            throw IOException("secure photo read failed: $label", error)
        } catch (error: SecurityException) {
            throw IOException("secure photo read failed: $label", error)
        }
    }

    /** Secure descriptor-relative CREATE_NEW output. */
    fun openNewOutput(path: Path, label: String = path.toString()): FileChannel {
        return try {
            operations.openNewOutput(relativeName(path, label))
        } catch (error: Stage5ValidationException) {
            throw error
        } catch (error: IOException) {
            throw IOException("secure photo create failed: $label", error)
        } catch (error: SecurityException) {
            throw IOException("secure photo create failed: $label", error)
        }
    }

    /** Atomic-only descriptor-relative move under the same opened root. */
    fun atomicMove(source: Path, target: Path, replaceExisting: Boolean = false) {
        try {
            val sourceName = relativeName(source, "photo move source")
            val targetName = relativeName(target, "photo move target")
            if (!replaceExisting && operations.exists(targetName)) {
                throw FileAlreadyExistsException(target.toString())
            }
            operations.move(sourceName, targetName, replaceExisting)
        } catch (error: Stage5ValidationException) {
            throw error
        } catch (error: IOException) {
            throw IOException("atomic photo move failed", error)
        } catch (error: SecurityException) {
            throw IOException("atomic photo move failed", error)
        }
    }

    fun deletePath(path: Path, label: String = path.toString()) {
        try {
            operations.delete(relativeName(path, label))
        } catch (error: Stage5ValidationException) {
            throw error
        } catch (error: IOException) {
            throw IOException("secure photo delete failed: $label", error)
        } catch (error: SecurityException) {
            throw IOException("secure photo delete failed: $label", error)
        }
    }

    fun delete(reference: String) {
        deletePath(resolve(reference).toPath(), "photo $reference")
    }

    fun writeBytes(path: Path, bytes: ByteArray, label: String) {
        openNewOutput(path, label).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    override fun close() {
        operations.close()
    }

    private fun writeInternalFile(name: String, bytes: ByteArray, label: String) {
        validateTransactionInternalName(name, allowMarkers = true)
        openNewOutput(internalPath(name), label).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun internalPath(name: String): Path {
        validateTransactionInternalName(name, allowMarkers = true)
        val path = rootPath.resolve(name)
        ensureContained(path, "internal photo path $name")
        return path
    }

    private fun recoverInterruptedPhotoTransaction() {
        val markerExists = operations.exists(PHOTO_TRANSACTION_MARKER)
        val commitMarkerExists = operations.exists(PHOTO_TRANSACTION_COMMITTED_MARKER)
        val canonicalIntentExists = operations.exists(PHOTO_CANONICAL_INTENT_MARKER)
        val metadataPhaseExists = operations.exists(PHOTO_CANONICAL_METADATA_COMMITTED_MARKER)
        val cleanupMarkerExists = operations.exists(PHOTO_TRANSACTION_CLEANUP_MARKER)
        val rollbackEvidenceExists = operations.exists(PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MARKER)
        val liveSnapshotArtifactExists = operations.exists(PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT)
        val liveSnapshotCleanupMarkerExists =
            operations.exists(PHOTO_CANONICAL_LIVE_SNAPSHOT_CLEANUP_MARKER)
        if (liveSnapshotArtifactExists && !canonicalIntentExists && !rollbackEvidenceExists) {
            closeAfterRecoveryFailure()
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot artifact has no owning recovery evidence"
            )
        }
        if (cleanupMarkerExists || rollbackEvidenceExists || liveSnapshotCleanupMarkerExists) {
            recoverPhotoTransactionCleanup(
                markerExists = markerExists,
                commitMarkerExists = commitMarkerExists,
                canonicalIntentExists = canonicalIntentExists,
                metadataPhaseExists = metadataPhaseExists,
                rollbackEvidenceExists = rollbackEvidenceExists,
                liveSnapshotCleanupMarkerExists = liveSnapshotCleanupMarkerExists
            )
            return
        }
        if (!markerExists) {
            if (commitMarkerExists || canonicalIntentExists || metadataPhaseExists ||
                liveSnapshotArtifactExists || liveSnapshotCleanupMarkerExists
            ) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "photo transaction recovery evidence has no file journal"
                )
            }
            return
        }
        val markerBytes = try {
            operations.openRead(PHOTO_TRANSACTION_MARKER).use {
                readBoundedBytes(it, 1 * 1024 * 1024, "photo transaction marker")
            }
        } catch (error: Stage5ValidationException) {
            closeAfterRecoveryFailure()
            throw Stage5ValidationException("photo transaction recovery marker could not be read", error)
        } catch (error: IOException) {
            closeAfterRecoveryFailure()
            throw Stage5ValidationException("photo transaction recovery marker could not be read", error)
        } catch (error: SecurityException) {
            closeAfterRecoveryFailure()
            throw Stage5ValidationException("photo transaction recovery marker could not be read", error)
        }
        val entries = try {
            parsePhotoTransactionMarker(markerBytes)
        } catch (error: Stage5ValidationException) {
            closeAfterRecoveryFailure()
            throw Stage5ValidationException("photo transaction recovery marker is corrupt", error)
        }
        val transactionIdentity = sha256Hex(markerBytes)
        if (commitMarkerExists) {
            // A bare historical COMMITTED marker is readable only as
            // diagnostic evidence. It cannot authorize the journal that
            // happens to occupy this fixed path after a restart.
            when (val probe = probeAuthoritativePhotoTransactionCommitMarker(transactionIdentity)) {
                PhotoCommitMarkerProbe.Bound -> Unit
                PhotoCommitMarkerProbe.Absent -> {
                    closeAfterRecoveryFailure()
                    throw PhotoCanonicalRecoveryException(
                        "photo transaction commit marker disappeared during recovery"
                    )
                }
                is PhotoCommitMarkerProbe.Ambiguous -> {
                    closeAfterRecoveryFailure()
                    throw probe.error
                }
            }
        }
        if (metadataPhaseExists && !canonicalIntentExists) {
            closeAfterRecoveryFailure()
            throw PhotoCanonicalRecoveryException(
                "photo metadata phase has no canonical recovery intent"
            )
        }
        if (canonicalIntentExists) {
            val intentBytes = try {
                operations.openRead(PHOTO_CANONICAL_INTENT_MARKER).use {
                    readBoundedBytes(it, 64 * 1024, "photo canonical recovery intent")
                }
            } catch (error: Stage5ValidationException) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "photo canonical recovery intent could not be read",
                    error
                )
            } catch (error: IOException) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "photo canonical recovery intent could not be read",
                    error
                )
            } catch (error: SecurityException) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "photo canonical recovery intent could not be read",
                    error
                )
            }
            val parsedIntent = try {
                parsePhotoCanonicalRecoveryMarker(intentBytes)
            } catch (error: Stage5ValidationException) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "photo canonical recovery intent is corrupt",
                    error
                )
            } catch (error: IllegalArgumentException) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "photo canonical recovery intent is corrupt",
                    error
                )
            }
            val intentJournalIdentity = parsedIntent.journalIdentity
            if (intentJournalIdentity == null) {
                // V1 intent records remain readable for diagnostics and safe
                // evidence retention, but they never prove which journal was
                // prepared alongside them. Refuse to pair one with the
                // current journal after restart.
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "legacy photo canonical recovery intent has no provable journal owner"
                )
            }
            if (intentJournalIdentity != transactionIdentity) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "photo canonical recovery intent is bound to another journal"
                )
            }
            if (parsedIntent.mode == null && metadataPhaseExists) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "photo metadata phase is not bound to a remote-acceptance intent"
                )
            }
            if (parsedIntent.mode == PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE &&
                metadataPhaseExists
            ) {
                try {
                    requireMetadataPhaseIdentity(transactionIdentity)
                } catch (error: Stage5ValidationException) {
                    closeAfterRecoveryFailure()
                    throw PhotoCanonicalRecoveryException(
                        "photo canonical metadata phase is not bound to its journal",
                        error
                    )
                } catch (error: IllegalArgumentException) {
                    closeAfterRecoveryFailure()
                    throw PhotoCanonicalRecoveryException(
                        "photo canonical metadata phase is not bound to its journal",
                        error
                    )
                } catch (error: IOException) {
                    closeAfterRecoveryFailure()
                    throw PhotoCanonicalRecoveryException(
                        "photo canonical metadata phase is not bound to its journal",
                        error
                    )
                } catch (error: SecurityException) {
                    closeAfterRecoveryFailure()
                    throw PhotoCanonicalRecoveryException(
                        "photo canonical metadata phase is not bound to its journal",
                        error
                    )
                }
            }
            pendingCanonicalRecovery = parsedIntent
            // The active coordinator/import/photo-open boundary must provide
            // current durable/live canonical identities before any choice is
            // made. Do not infer a result from file presence alone.
            return
        }
        try {
            if (commitMarkerExists) {
                finalizePhotoTransaction(entries)
            } else {
                rollbackPhotoTransaction(entries)
            }
        } catch (error: Stage5ValidationException) {
            closeAfterRecoveryFailure()
            throw Stage5ValidationException("photo transaction recovery could not restore a safe state", error)
        } catch (error: IllegalArgumentException) {
            closeAfterRecoveryFailure()
            throw Stage5ValidationException("photo transaction recovery could not restore a safe state", error)
        } catch (error: IOException) {
            closeAfterRecoveryFailure()
            throw Stage5ValidationException("photo transaction recovery could not restore a safe state", error)
        } catch (error: SecurityException) {
            closeAfterRecoveryFailure()
            throw Stage5ValidationException("photo transaction recovery could not restore a safe state", error)
        }
    }

    /**
     * Resumes marker cleanup after a process death or partial deletion. The
     * completion marker is written only after target/artifact work has
     * finished, so this path never infers a target rollback from a missing
     * commit marker.
     */
    private fun recoverPhotoTransactionCleanup(
        markerExists: Boolean,
        commitMarkerExists: Boolean,
        canonicalIntentExists: Boolean,
        metadataPhaseExists: Boolean,
        rollbackEvidenceExists: Boolean,
        liveSnapshotCleanupMarkerExists: Boolean
    ) {
        if (liveSnapshotCleanupMarkerExists) {
            val liveCleanupIdentity = try {
                requireLiveSnapshotCleanupMarkerIdentity()
            } catch (error: PhotoCanonicalRecoveryException) {
                closeAfterRecoveryFailure()
                throw error
            } catch (error: Stage5ValidationException) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "photo canonical live snapshot cleanup phase is invalid",
                    error
                )
            } catch (error: IOException) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "photo canonical live snapshot cleanup phase could not be read",
                    error
                )
            } catch (error: SecurityException) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "photo canonical live snapshot cleanup phase could not be verified",
                    error
                )
            }
            val liveSnapshotArtifactExists = operations.exists(PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT)
            try {
                val cleanupMarkerExists = operations.exists(PHOTO_TRANSACTION_CLEANUP_MARKER)
                if (rollbackEvidenceExists) {
                    val evidence = requireRollbackCompletionEvidence(liveCleanupIdentity)
                    requireCanonicalRecoveryJournal(evidence.record)
                    if (commitMarkerExists) {
                        throw PhotoCanonicalRecoveryException(
                            "photo live-snapshot cleanup phase conflicts with rollback and commit evidence"
                        )
                    }
                    if (canonicalIntentExists) {
                        val intent = requireCanonicalIntentIdentity(liveCleanupIdentity)
                        if (intent != evidence.record) {
                            throw PhotoCanonicalRecoveryException(
                                "photo live-snapshot cleanup phase disagrees with canonical intent"
                            )
                        }
                    }
                    if (metadataPhaseExists) requireMetadataPhaseIdentity(liveCleanupIdentity)
                    if (markerExists) {
                        val pendingMetadataIdentity = readRollbackPendingMetadataIdentity(liveCleanupIdentity)
                            ?: throw PhotoCanonicalRecoveryException(
                                "photo live-snapshot cleanup phase has incompatible rollback evidence"
                            )
                        if (pendingMetadataIdentity != evidence.previousMetadataIdentity) {
                            throw PhotoCanonicalRecoveryException(
                                "photo live-snapshot cleanup phase disagrees with rollback metadata"
                            )
                        }
                    }
                    // A rollback proof is deliberately kept pending until a
                    // caller supplies the exact old authority tuple. In
                    // particular, a fresh resolver must not consume the
                    // unequal-live sidecar before the session gate can
                    // rehydrate it.
                    pendingCanonicalRecovery = evidence.record
                    rollbackRecoveryPending = true
                    return
                }
                if (readRollbackPendingMetadataIdentity(liveCleanupIdentity) != null) {
                    throw PhotoCanonicalRecoveryException(
                        "photo live-snapshot cleanup phase lacks complete rollback proof"
                    )
                }
                if (!liveSnapshotArtifactExists &&
                    !markerExists &&
                    !commitMarkerExists &&
                    !canonicalIntentExists &&
                    !metadataPhaseExists &&
                    !rollbackEvidenceExists
                ) {
                    // The journal and all authority markers were already
                    // removed after the sidecar delete. If cleanup evidence
                    // is the only remaining owner, validate it before
                    // removing this final, already-proven residue.
                    if (cleanupMarkerExists) {
                        requireCleanupMarkerIdentity(liveCleanupIdentity)
                        deleteMarker(
                            PHOTO_TRANSACTION_CLEANUP_MARKER,
                            "photo transaction cleanup evidence"
                        )
                    }
                    deleteMarker(
                        PHOTO_CANONICAL_LIVE_SNAPSHOT_CLEANUP_MARKER,
                        "photo canonical live snapshot cleanup phase"
                    )
                    return
                }

                // The phase may have been persisted immediately before a
                // process death, before the ordinary cleanup marker was
                // created. The normal clear routine is intentionally able to
                // create that marker and resume from either side of the
                // sidecar deletion, while retaining all owner validation.
                clearPhotoTransactionMarkers(liveCleanupIdentity)
                return
            } catch (error: PhotoCanonicalRecoveryException) {
                closeAfterRecoveryFailure()
                throw error
            } catch (error: Stage5ValidationException) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "photo live-snapshot cleanup phase is not owned by its journal",
                    error
                )
            } catch (error: IOException) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "photo live-snapshot cleanup phase could not be completed safely",
                    error
                )
            } catch (error: SecurityException) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "photo live-snapshot cleanup phase could not be completed safely",
                    error
                )
            }
        }
        if (rollbackEvidenceExists) {
            try {
                val evidence = requireRollbackCompletionEvidence()
                val identity = evidence.record.journalIdentity
                    ?: throw PhotoCanonicalRecoveryException(
                        "photo rollback completion evidence has no journal identity"
                    )
                requireCanonicalRecoveryJournal(evidence.record)
                if (commitMarkerExists) {
                    throw PhotoCanonicalRecoveryException(
                        "photo rollback completion evidence conflicts with a photo commit marker"
                    )
                }
                if (canonicalIntentExists) {
                    val intent = requireCanonicalIntentIdentity(identity)
                    if (intent != evidence.record) {
                        throw PhotoCanonicalRecoveryException(
                            "photo rollback completion evidence disagrees with the canonical intent"
                        )
                    }
                }
                if (metadataPhaseExists) requireMetadataPhaseIdentity(identity)
                if (markerExists) {
                    val pendingMetadataIdentity = readRollbackPendingMetadataIdentity(identity)
                        ?: throw PhotoCanonicalRecoveryException(
                            "photo rollback completion evidence has incompatible cleanup state"
                        )
                    if (pendingMetadataIdentity != evidence.previousMetadataIdentity) {
                        throw PhotoCanonicalRecoveryException(
                            "photo rollback completion evidence disagrees with rollback metadata"
                        )
                    }
                }
                // Keep the complete record active until the callback supplies
                // exact old canonical and metadata authorities. An empty or
                // offline open therefore remains unresolved.
                pendingCanonicalRecovery = evidence.record
                rollbackRecoveryPending = true
                return
            } catch (error: PhotoCanonicalRecoveryException) {
                closeAfterRecoveryFailure()
                throw error
            } catch (error: Stage5ValidationException) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "photo rollback completion evidence is invalid",
                    error
                )
            } catch (error: IOException) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "photo rollback completion evidence could not be read",
                    error
                )
            } catch (error: SecurityException) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "photo rollback completion evidence could not be verified",
                    error
                )
            }
        }
        val cleanupBytes = try {
            operations.openRead(PHOTO_TRANSACTION_CLEANUP_MARKER).use {
                readBoundedBytes(it, 64 * 1024, "photo transaction cleanup evidence")
            }
        } catch (error: Stage5ValidationException) {
            closeAfterRecoveryFailure()
            throw PhotoCanonicalRecoveryException(
                "photo transaction cleanup evidence could not be read",
                error
            )
        } catch (error: IOException) {
            closeAfterRecoveryFailure()
            throw PhotoCanonicalRecoveryException(
                "photo transaction cleanup evidence could not be read",
                error
            )
        } catch (error: SecurityException) {
            closeAfterRecoveryFailure()
            throw PhotoCanonicalRecoveryException(
                "photo transaction cleanup evidence could not be read",
                error
            )
        }
        val cleanupLines = cleanupBytes.toString(StandardCharsets.US_ASCII).split('\n')
        val rollbackPending = cleanupLines.firstOrNull() == PHOTO_TRANSACTION_ROLLBACK_PENDING_MAGIC
        val rollbackCompletion = cleanupLines.firstOrNull() == PHOTO_TRANSACTION_ROLLBACK_COMPLETE_MAGIC
        val standardCleanup = cleanupLines.firstOrNull() == PHOTO_TRANSACTION_CLEANUP_MAGIC
        val validCleanup = if (rollbackPending) {
            cleanupLines.size == 4 &&
                cleanupLines[1].matches(Regex("[0-9a-f]{64}")) &&
                cleanupLines[2].matches(Regex("[0-9a-f]{64}")) &&
                cleanupLines[3].isEmpty()
        } else {
            cleanupLines.size == 3 &&
                (standardCleanup || rollbackCompletion) &&
                cleanupLines[1].matches(Regex("[0-9a-f]{64}")) &&
                cleanupLines[2].isEmpty()
        }
        if (!validCleanup) {
            closeAfterRecoveryFailure()
            throw PhotoCanonicalRecoveryException(
                "photo transaction cleanup evidence is corrupt"
            )
        }
        val identity = cleanupLines[1]
        if (rollbackCompletion &&
            (commitMarkerExists || canonicalIntentExists || metadataPhaseExists || rollbackEvidenceExists)
        ) {
            // V1 completion records predate the journal-bound V2/V3
            // protocols. They are safe to consume only in a legacy-only
            // root; otherwise a forged downgrade could authorize cleanup of
            // an active remote-acceptance transaction.
            closeAfterRecoveryFailure()
            throw PhotoCanonicalRecoveryException(
                "legacy photo rollback completion cannot authorize mixed Stage 5 evidence"
            )
        }
        if (!markerExists) {
            // A lone completion record is safe to remove. Any other marker
            // without its journal is retained because its ownership cannot be
            // proven from this fixed root.
            if (rollbackPending || commitMarkerExists || canonicalIntentExists || metadataPhaseExists) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "photo transaction cleanup evidence has markers but no journal"
                )
            }
            deleteMarker(PHOTO_TRANSACTION_CLEANUP_MARKER, "photo transaction cleanup evidence")
            return
        }
        if (rollbackPending) {
            // This is an in-progress cross-store rollback, not a cleanup
            // completion record. Leave it for reconcilePhotoTransaction,
            // which has the current canonical and metadata authorities.
            try {
                requirePhotoTransactionIdentity(identity)
                if (!canonicalIntentExists) {
                    throw PhotoCanonicalRecoveryException(
                        "cross-store rollback evidence has no canonical intent"
                    )
                }
                val intent = requireCanonicalIntentIdentity(identity)
                if (intent.mode != PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE) {
                    throw PhotoCanonicalRecoveryException(
                        "cross-store rollback evidence is not bound to remote acceptance"
                    )
                }
                if (commitMarkerExists) {
                    throw PhotoCanonicalRecoveryException(
                        "cross-store rollback evidence conflicts with a photo commit marker"
                    )
                }
                if (metadataPhaseExists) requireMetadataPhaseIdentity(identity)
                pendingCanonicalRecovery = intent
                rollbackRecoveryPending = true
                return
            } catch (error: PhotoCanonicalRecoveryException) {
                closeAfterRecoveryFailure()
                throw error
            } catch (error: Stage5ValidationException) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "cross-store rollback evidence is not owned by its journal",
                    error
                )
            } catch (error: IOException) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "cross-store rollback evidence could not be verified",
                    error
                )
            } catch (error: SecurityException) {
                closeAfterRecoveryFailure()
                throw PhotoCanonicalRecoveryException(
                    "cross-store rollback evidence could not be verified",
                    error
                )
            }
        }
        try {
            requirePhotoTransactionIdentity(identity)
            if (commitMarkerExists) {
                when (val probe = probeAuthoritativePhotoTransactionCommitMarker(identity)) {
                    PhotoCommitMarkerProbe.Bound -> Unit
                    PhotoCommitMarkerProbe.Absent -> throw PhotoCanonicalRecoveryException(
                        "photo transaction commit marker disappeared during cleanup"
                    )
                    is PhotoCommitMarkerProbe.Ambiguous -> throw probe.error
                }
            }
            if (canonicalIntentExists) {
                val intent = requireCanonicalIntentIdentity(identity)
                if (rollbackCompletion && intent.previousPhotoDigest != null) {
                    val entries = parsePhotoTransactionMarker(readTransactionMarker())
                    val actual = currentPhotoTransactionContentDigest(entries)
                    if (actual != intent.previousPhotoDigest) {
                        throw PhotoCanonicalRecoveryException(
                            "photo rollback completion evidence does not match the previous photo bytes"
                        )
                    }
                }
            }
            if (metadataPhaseExists) requireMetadataPhaseIdentity(identity)
            clearPhotoTransactionMarkers(identity)
        } catch (error: PhotoCanonicalRecoveryException) {
            closeAfterRecoveryFailure()
            throw error
        } catch (error: Stage5ValidationException) {
            closeAfterRecoveryFailure()
            throw PhotoCanonicalRecoveryException(
                "photo transaction cleanup evidence is not owned by its journal",
                error
            )
        } catch (error: IllegalArgumentException) {
            closeAfterRecoveryFailure()
            throw PhotoCanonicalRecoveryException(
                "photo transaction cleanup evidence is invalid",
                error
            )
        } catch (error: IOException) {
            closeAfterRecoveryFailure()
            throw PhotoCanonicalRecoveryException(
                "photo transaction cleanup could not be completed safely",
                error
            )
        } catch (error: SecurityException) {
            closeAfterRecoveryFailure()
            throw PhotoCanonicalRecoveryException(
                "photo transaction cleanup could not be completed safely",
                error
            )
        }
    }

    private fun closeAfterRecoveryFailure() {
        try {
            operations.close()
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
    }

    private fun readTransactionMarker(): ByteArray = operations.openRead(PHOTO_TRANSACTION_MARKER).use {
        readBoundedBytes(it, 1 * 1024 * 1024, "photo transaction marker")
    }

    private fun currentPhotoTransactionContentDigest(
        entries: List<PhotoTransactionJournalEntry>
    ): String = photoTransactionContentDigest(entries) { name ->
        if (!operations.exists(name)) {
            null
        } else {
            if (!operations.isRegularFile(name)) {
                throw Stage5ValidationException("photo transaction target is not a regular file: $name")
            }
            operations.openRead(name).use {
                readBoundedBytes(it, Stage5Limits.MAX_PHOTO_BYTES, "photo transaction target $name")
            }
        }
    }

    private fun requireCommitMarkerIdentity(
        expected: String
    ) {
        val bytes = operations.openRead(PHOTO_TRANSACTION_COMMITTED_MARKER).use {
            readBoundedBytes(it, 64 * 1024, "photo transaction commit marker")
        }
        val text = bytes.toString(StandardCharsets.US_ASCII)
        if (text != "COMMITTED\n$expected\n") {
            throw PhotoCanonicalRecoveryException(
                "photo transaction commit marker belongs to another journal"
            )
        }
    }

    private fun requireCanonicalIntentIdentity(expected: String): PhotoCanonicalRecoveryRecord {
        val bytes = operations.openRead(PHOTO_CANONICAL_INTENT_MARKER).use {
            readBoundedBytes(it, 64 * 1024, "photo canonical recovery intent")
        }
        val record = parsePhotoCanonicalRecoveryMarker(bytes)
        if (record.journalIdentity != expected) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery intent belongs to another journal"
            )
        }
        return record
    }

    private fun requireMetadataPhaseIdentity(expected: String) {
        val bytes = operations.openRead(PHOTO_CANONICAL_METADATA_COMMITTED_MARKER).use {
            readBoundedBytes(it, 64 * 1024, "photo canonical metadata phase")
        }
        val text = bytes.toString(StandardCharsets.US_ASCII)
        if (text != "$PHOTO_METADATA_COMMITTED_MAGIC\n$expected\n") {
            throw PhotoCanonicalRecoveryException(
                "photo canonical metadata phase belongs to another journal"
            )
        }
    }

    private fun requireCleanupMarkerIdentity(expected: String) {
        val bytes = operations.openRead(PHOTO_TRANSACTION_CLEANUP_MARKER).use {
            readBoundedBytes(it, 64 * 1024, "photo transaction cleanup evidence")
        }
        val text = bytes.toString(StandardCharsets.US_ASCII)
        if (text == "$PHOTO_TRANSACTION_CLEANUP_MAGIC\n$expected\n") {
            return
        }
        if (text == "$PHOTO_TRANSACTION_ROLLBACK_COMPLETE_MAGIC\n$expected\n") {
            rejectLegacyRollbackCompletionIfMixedWithStage5Evidence()
            return
        }
        val lines = text.split('\n')
        if (lines.size == 4 &&
            lines[0] == PHOTO_TRANSACTION_ROLLBACK_PENDING_MAGIC &&
            lines[1] == expected &&
            lines[2].matches(Regex("[0-9a-f]{64}")) &&
            lines[3].isEmpty()
        ) {
            return
        }
        throw PhotoCanonicalRecoveryException(
            "photo transaction cleanup evidence belongs to another journal"
        )
    }

    private fun requireLiveSnapshotCleanupMarkerIdentity(expected: String? = null): String {
        val bytes = operations.openRead(PHOTO_CANONICAL_LIVE_SNAPSHOT_CLEANUP_MARKER).use {
            readBoundedBytes(
                it,
                64 * 1024,
                "photo canonical live snapshot cleanup phase"
            )
        }
        val text = bytes.toString(StandardCharsets.US_ASCII)
        val lines = text.split('\n')
        if (lines.size != 3 ||
            lines[0] != PHOTO_CANONICAL_LIVE_SNAPSHOT_CLEANUP_MAGIC ||
            lines[2].isNotEmpty() ||
            !lines[1].matches(Regex("[0-9a-f]{64}"))
        ) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot cleanup phase is malformed"
            )
        }
        if (expected != null && lines[1] != expected) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical live snapshot cleanup phase belongs to another journal"
            )
        }
        return lines[1]
    }

    /**
     * V1 completion has no canonical, metadata, or rollback-proof binding.
     * Keep it readable for an old legacy-only transaction, but never let it
     * consume any Stage 5 evidence that could belong to a newer transaction.
     */
    private fun rejectLegacyRollbackCompletionIfMixedWithStage5Evidence() {
        if (operations.exists(PHOTO_TRANSACTION_COMMITTED_MARKER) ||
            operations.exists(PHOTO_CANONICAL_INTENT_MARKER) ||
            operations.exists(PHOTO_CANONICAL_METADATA_COMMITTED_MARKER) ||
            operations.exists(PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MARKER) ||
            operations.exists(PHOTO_CANONICAL_LIVE_SNAPSHOT_CLEANUP_MARKER)
        ) {
            throw PhotoCanonicalRecoveryException(
                "legacy photo rollback completion cannot authorize mixed Stage 5 evidence"
            )
        }
    }

    /** Returns the old metadata identity only for a valid rollback-pending marker. */
    private fun readRollbackPendingMetadataIdentity(expected: String): String? {
        if (!operations.exists(PHOTO_TRANSACTION_CLEANUP_MARKER)) {
            return if (operations.exists(PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MARKER)) {
                requireRollbackCompletionEvidence(expected).previousMetadataIdentity
            } else {
                null
            }
        }
        val bytes = operations.openRead(PHOTO_TRANSACTION_CLEANUP_MARKER).use {
            readBoundedBytes(it, 64 * 1024, "photo rollback evidence")
        }
        val lines = bytes.toString(StandardCharsets.US_ASCII).split('\n')
        if (lines.firstOrNull() == PHOTO_TRANSACTION_ROLLBACK_COMPLETE_MAGIC) {
            rejectLegacyRollbackCompletionIfMixedWithStage5Evidence()
        }
        if (lines.firstOrNull() != PHOTO_TRANSACTION_ROLLBACK_PENDING_MAGIC) {
            requireCleanupMarkerIdentity(expected)
            return null
        }
        if (lines.size != 4 ||
            lines[1] != expected ||
            !lines[2].matches(Regex("[0-9a-f]{64}")) ||
            lines[3].isNotEmpty()
        ) {
            throw PhotoCanonicalRecoveryException(
                "photo rollback evidence is corrupt or belongs to another journal"
            )
        }
        return lines[2]
    }

    private fun finalizePhotoTransaction(
        entries: List<PhotoTransactionJournalEntry>,
        transactionIdentity: String? = null
    ) {
        transactionIdentity?.let(::requirePhotoTransactionIdentity)
        entries.forEach { entry ->
            if (!operations.isRegularFile(entry.targetName)) {
                throw Stage5ValidationException(
                    "committed photo transaction target is missing or non-regular: ${entry.targetName}"
                )
            }
            if (operations.exists(entry.stagedName)) operations.delete(entry.stagedName)
            if (operations.exists(entry.backupName)) operations.delete(entry.backupName)
        }
        clearPhotoTransactionMarkers(transactionIdentity)
    }

    private fun rollbackPhotoTransaction(
        entries: List<PhotoTransactionJournalEntry>,
        transactionIdentity: String? = null
    ) {
        transactionIdentity?.let(::requirePhotoTransactionIdentity)
        entries.asReversed().forEach { entry ->
            val targetExists = operations.exists(entry.targetName)
            val backupExists = operations.exists(entry.backupName)
            if (entry.targetExisted) {
                if (!backupExists) {
                    // The intent may have been durably recorded before the
                    // first atomic rename. An existing target with no backup
                    // is still the prior target in that bounded state; remove
                    // only the staged candidate and retain the old bytes.
                    if (targetExists) {
                        if (operations.exists(entry.stagedName)) operations.delete(entry.stagedName)
                        return@forEach
                    }
                    throw Stage5ValidationException(
                        "photo transaction recovery is ambiguous for ${entry.targetName}"
                    )
                }
                if (!operations.isRegularFile(entry.backupName)) {
                    throw Stage5ValidationException(
                        "photo transaction backup is missing or non-regular: ${entry.backupName}"
                    )
                }
                if (targetExists) operations.delete(entry.targetName)
                operations.move(entry.backupName, entry.targetName, replaceExisting = false)
            } else if (targetExists) {
                if (!operations.isRegularFile(entry.targetName)) {
                    throw Stage5ValidationException(
                        "photo transaction target is non-regular: ${entry.targetName}"
                    )
                }
                operations.delete(entry.targetName)
            }
            if (operations.exists(entry.stagedName)) operations.delete(entry.stagedName)
        }
        clearPhotoTransactionMarkers(transactionIdentity)
    }

    private fun parsePhotoCanonicalRecoveryMarker(bytes: ByteArray): PhotoCanonicalRecoveryRecord {
        val text = bytes.toString(StandardCharsets.US_ASCII)
        val body = when {
            !text.endsWith('\n') -> text
            text.dropLast(1).endsWith('\n') -> throw Stage5ValidationException(
                "photo canonical recovery intent has trailing content"
            )
            else -> text.dropLast(1)
        }
        val lines = body.split('\n')
        fun value(index: Int): String = decodeRecoveryValue(
            lines[index],
            "invalid photo canonical recovery intent value"
        )
        return when (lines.firstOrNull()) {
            PHOTO_CANONICAL_INTENT_MAGIC_V3 -> {
                if (lines.size != 11 ||
                    lines[2] != PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE.name
                ) {
                    throw Stage5ValidationException("invalid remote-acceptance photo canonical recovery intent")
                }
                val journalIdentity = lines[1]
                if (!journalIdentity.matches(Regex("[0-9a-f]{64}"))) {
                    throw Stage5ValidationException(
                        "photo canonical recovery intent journal identity is invalid"
                    )
                }
                if (!lines[9].matches(Regex("[0-9a-f]{64}")) ||
                    !lines[10].matches(Regex("[0-9a-f]{64}"))
                ) {
                    throw Stage5ValidationException(
                        "remote-acceptance photo canonical recovery intent requires both photo content digests"
                    )
                }
                val previous = parsePreviousCanonicalIdentities(value(3), value(4), value(5))
                try {
                    PhotoCanonicalRecoveryRecord(
                        previous = previous.durable,
                        previousLive = previous.live,
                        intended = PhotoCanonicalIdentity(value(6), value(7), value(8)),
                        journalIdentity = journalIdentity,
                        previousPhotoDigest = lines.getOrNull(9),
                        intendedPhotoDigest = lines.getOrNull(10),
                        mode = PhotoCanonicalRecoveryMode.REMOTE_ACCEPTANCE
                    )
                } catch (error: IllegalArgumentException) {
                    throw Stage5ValidationException(
                        "photo canonical recovery intent canonical identity is invalid",
                        error
                    )
                }
            }
            PHOTO_CANONICAL_INTENT_MAGIC -> {
                if (lines.size != 8 && lines.size != 10) {
                    throw Stage5ValidationException("invalid versioned photo canonical recovery intent")
                }
                val journalIdentity = lines[1]
                if (!journalIdentity.matches(Regex("[0-9a-f]{64}"))) {
                    throw Stage5ValidationException(
                        "photo canonical recovery intent journal identity is invalid"
                    )
                }
                val previous = parsePreviousCanonicalIdentities(value(2), value(3), value(4))
                try {
                    PhotoCanonicalRecoveryRecord(
                        previous = previous.durable,
                        previousLive = previous.live,
                        intended = PhotoCanonicalIdentity(value(5), value(6), value(7)),
                        journalIdentity = journalIdentity,
                        previousPhotoDigest = lines.getOrNull(8),
                        intendedPhotoDigest = lines.getOrNull(9)
                    )
                } catch (error: IllegalArgumentException) {
                    throw Stage5ValidationException(
                        "photo canonical recovery intent canonical identity is invalid",
                        error
                    )
                }
            }
            PHOTO_CANONICAL_INTENT_MAGIC_V1 -> {
                if (lines.size != 7) {
                    throw Stage5ValidationException("invalid legacy photo canonical recovery intent")
                }
                // Keep the old format parseable so it can be diagnosed and
                // retained, but recoverInterruptedPhotoTransaction refuses
                // to act without a cryptographic journal owner.
                PhotoCanonicalRecoveryRecord(
                    previous = PhotoCanonicalIdentity(value(1), value(2), value(3)),
                    intended = PhotoCanonicalIdentity(value(4), value(5), value(6))
                )
            }
            else -> throw Stage5ValidationException("invalid photo canonical recovery intent header")
        }
    }

    private fun encodeRecoveryValue(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    /**
     * Keeps the established bounded V2/V3 marker line counts while carrying a
     * distinct prior live authority when one exists.  Equal authorities use
     * the historical source field verbatim, so old readers remain compatible;
     * unequal authorities use one length-bounded Base64 field containing both
     * complete identities.  The durable document/digest fields are duplicated
     * inside the pair and checked on read to detect tampering or truncation.
     */
    private fun encodePreviousCanonicalIdentityField(
        previousDurable: PhotoCanonicalIdentity,
        previousLive: PhotoCanonicalIdentity
    ): String {
        if (previousDurable == previousLive) {
            return encodeRecoveryValue(previousDurable.sourceUri)
        }
        val packed = listOf(
            PHOTO_CANONICAL_PREVIOUS_PAIR_MAGIC,
            encodeRecoveryValue(previousDurable.documentId),
            encodeRecoveryValue(previousDurable.snapshotDigest),
            encodeRecoveryValue(previousDurable.sourceUri),
            encodeRecoveryValue(previousLive.documentId),
            encodeRecoveryValue(previousLive.snapshotDigest),
            encodeRecoveryValue(previousLive.sourceUri)
        ).joinToString("\n")
        return encodeRecoveryValue(packed)
    }

    private data class ParsedPreviousCanonicalIdentities(
        val durable: PhotoCanonicalIdentity,
        val live: PhotoCanonicalIdentity
    )

    private fun decodeRecoveryValue(encoded: String, message: String): String = try {
        Base64.getUrlDecoder().decode(encoded).toString(StandardCharsets.UTF_8)
    } catch (error: IllegalArgumentException) {
        throw Stage5ValidationException(message, error)
    }

    private fun parsePreviousCanonicalIdentities(
        durableDocumentId: String,
        durableSnapshotDigest: String,
        sourceField: String
    ): ParsedPreviousCanonicalIdentities {
        fun identity(documentId: String, snapshotDigest: String, sourceUri: String): PhotoCanonicalIdentity = try {
            PhotoCanonicalIdentity(documentId, snapshotDigest, sourceUri)
        } catch (error: IllegalArgumentException) {
            throw Stage5ValidationException(
                "photo canonical recovery previous identity is invalid",
                error
            )
        }

        val durableSource = sourceField.takeUnless {
            it.startsWith("$PHOTO_CANONICAL_PREVIOUS_PAIR_MAGIC\n")
        }
        if (durableSource != null) {
            val durable = identity(durableDocumentId, durableSnapshotDigest, durableSource)
            return ParsedPreviousCanonicalIdentities(durable, durable)
        }

        val fields = sourceField.split('\n')
        if (fields.size != 7 || fields[0] != PHOTO_CANONICAL_PREVIOUS_PAIR_MAGIC) {
            throw Stage5ValidationException(
                "photo canonical recovery previous authority pair is malformed"
            )
        }
        val embeddedDurable = identity(
            decodeRecoveryValue(fields[1], "invalid previous durable document identity encoding"),
            decodeRecoveryValue(fields[2], "invalid previous durable snapshot identity encoding"),
            decodeRecoveryValue(fields[3], "invalid previous durable source identity encoding")
        )
        val durable = identity(durableDocumentId, durableSnapshotDigest, embeddedDurable.sourceUri)
        if (embeddedDurable != durable) {
            throw PhotoCanonicalRecoveryException(
                "photo canonical recovery previous durable identity pair is inconsistent"
            )
        }
        val live = identity(
            decodeRecoveryValue(fields[4], "invalid previous live document identity encoding"),
            decodeRecoveryValue(fields[5], "invalid previous live snapshot identity encoding"),
            decodeRecoveryValue(fields[6], "invalid previous live source identity encoding")
        )
        if (live.documentId != durable.documentId) {
            throw Stage5ValidationException(
                "photo canonical recovery previous live document identity differs"
            )
        }
        return ParsedPreviousCanonicalIdentities(durable, live)
    }

    private fun parsePhotoTransactionMarker(bytes: ByteArray): List<PhotoTransactionJournalEntry> {
        val text = bytes.toString(StandardCharsets.US_ASCII)
        val lines = text.split('\n')
        if (lines.size < 4 || lines[0] != PHOTO_TRANSACTION_MAGIC || lines[1] != "PREPARED") {
            throw Stage5ValidationException("invalid photo transaction marker header")
        }
        val count = lines[2].toIntOrNull()
            ?: throw Stage5ValidationException("invalid photo transaction marker count")
        if (count <= 0 || count > Stage5Limits.MAX_TOTAL_PHOTOS || lines.size != count + 4) {
            throw Stage5ValidationException("invalid photo transaction marker entry count")
        }
        val claimedNames = HashMap<String, String>()
        fun claim(name: String, kind: String) {
            val previous = claimedNames.putIfAbsent(name, kind)
            if (previous != null) {
                throw Stage5ValidationException(
                    "photo transaction marker $kind name collides with $previous name"
                )
            }
        }
        return (0 until count).map { index ->
            val fields = lines[index + 3].split('\t')
            if (fields.size != 4 || fields[3] !in setOf("0", "1")) {
                throw Stage5ValidationException("invalid photo transaction marker entry")
            }
            validateTransactionInternalName(fields[0])
            validatePhotoFileName(fields[1])
            validateTransactionInternalName(fields[2])
            claim(fields[0], "staged")
            claim(fields[1], "target")
            claim(fields[2], "backup")
            PhotoTransactionJournalEntry(fields[0], fields[1], fields[2], fields[3] == "1")
        }
    }

    private fun validateTransactionInternalName(name: String, allowMarkers: Boolean = false) {
        if (allowMarkers && (
                name == PHOTO_TRANSACTION_MARKER ||
                    name == PHOTO_TRANSACTION_COMMITTED_MARKER ||
                    name == PHOTO_CANONICAL_INTENT_MARKER ||
                    name == PHOTO_CANONICAL_METADATA_COMMITTED_MARKER ||
                    name == PHOTO_CANONICAL_LIVE_SNAPSHOT_ARTIFACT ||
                    name == PHOTO_CANONICAL_LIVE_SNAPSHOT_CLEANUP_MARKER ||
                    name == PHOTO_TRANSACTION_CLEANUP_MARKER ||
                    name == PHOTO_TRANSACTION_ROLLBACK_EVIDENCE_MARKER
                )
        ) return
        if (!name.matches(Regex("\\.[A-Za-z0-9-]{1,32}-[0-9a-fA-F-]{8,64}\\.(tmp|bak|bad)"))) {
            throw Stage5ValidationException("unsafe internal photo name")
        }
    }

    private fun relativeName(path: Path, label: String): String {
        ensureContained(path, label)
        val absolute = path.toAbsolutePath().normalize()
        if (absolute.parent != rootPath || absolute.fileName == null) {
            throw Stage5ValidationException("photo operation is not a direct child of the root: $label")
        }
        return absolute.fileName.toString()
    }

    private fun ensureNoSymlinkComponents(path: Path) {
        var current: Path? = path.toAbsolutePath().normalize()
        while (current != null) {
            if (Files.isSymbolicLink(current)) {
                throw Stage5ValidationException("photo path root contains a symbolic link")
            }
            current = current.parent
        }
    }
}

/**
 * Document-scoped asset store. Legacy global files are never used directly:
 * an explicit validated migration copies them atomically into this document's
 * root, preserves the original, and all subsequent reads use the copy.
 */
class DocumentPhotoAssetStore internal constructor(
    filesDirectory: File,
    private val documentId: DocumentId,
    internal val imageProbe: PhotoDecodeProbe,
    private val operationsFactory: PhotoPathOperationsFactory
) : AutoCloseable {
    constructor(
        filesDirectory: File,
        documentId: DocumentId,
        imageProbe: PhotoDecodeProbe = DefaultImageProbe
    ) : this(
        filesDirectory,
        documentId,
        imageProbe,
        SecurePhotoPathOperationsFactory
    )

    val resolver = PhotoPathResolver(
        File(filesDirectory, "documents/${documentId.value}/photos"),
        createRoot = true,
        operationsFactory = operationsFactory
    )

    private data class PhotoSource(
        val name: String,
        val resolver: PhotoPathResolver,
        val path: Path,
        val declaredSize: Long,
        val label: String
    )

    private data class PreparedLegacyPhotoSet(
        val bytes: Map<String, ByteArray>,
        val transaction: PhotoContentTransaction?
    )

    /**
     * Reconciles any interrupted cross-store replacement before using the
     * document root.  This boundary is deliberately journal-only: the
     * snapshots may be an admission-time durable/live pair and are therefore
     * not a safe authority set for destructive garbage collection.
     */
    fun reconcilePhotoContent(
        currentDurableSnapshot: DocumentSnapshotV1,
        currentLiveSnapshot: DocumentSnapshotV1,
        currentMetadataIdentity: String? = null
    ): Unit = PhotoDocumentCriticalSections.withLock(resolver.root.toPath()) {
        validateSnapshot(currentDurableSnapshot)
        validateSnapshot(currentLiveSnapshot)
        resolver.reconcilePhotoTransaction(
            photoCanonicalIdentity(documentId, currentDurableSnapshot),
            photoCanonicalIdentity(documentId, currentLiveSnapshot),
            currentMetadataIdentity
        )
    }

    /**
     * Rehydrates the exact prior live snapshot needed by a cold-start
     * recovery gate when the durable and live authorities were unequal.
     * Returning null is safe only when no unequal prior live authority is
     * pending or the durable authority is already the intended side.
     */
    internal fun rehydratePreviousLiveCanonicalSnapshot(
        currentDurableSnapshot: DocumentSnapshotV1
    ): DocumentSnapshotV1? = PhotoDocumentCriticalSections.withLock(resolver.root.toPath()) {
        validateSnapshot(currentDurableSnapshot)
        resolver.rehydratePreviousLiveCanonicalSnapshot(
            photoCanonicalIdentity(documentId, currentDurableSnapshot)
        )
    }

    /**
     * Post-authoritative-commit cleanup.  The caller must supply snapshots
     * freshly captured under the document transaction barrier; this method
     * unions both authorities and performs journal reconciliation plus
     * destructive GC while the document photo root is locked.  Cleanup
     * uncertainty is typed recovery evidence because the canonical/photo
     * commit has already become authoritative.
     */
    fun cleanupAfterCanonicalCommit(
        currentDurableSnapshot: DocumentSnapshotV1,
        currentLiveSnapshot: DocumentSnapshotV1
    ) {
        try {
            PhotoDocumentCriticalSections.withLock(resolver.root.toPath()) {
                validateSnapshot(currentDurableSnapshot)
                validateSnapshot(currentLiveSnapshot)
                resolver.reconcilePhotoTransaction(
                    photoCanonicalIdentity(documentId, currentDurableSnapshot),
                    photoCanonicalIdentity(documentId, currentLiveSnapshot)
                )
                cleanupUnreferencedGeneratedPhotos(
                    requiredPhotoNames(currentDurableSnapshot) +
                        requiredPhotoNames(currentLiveSnapshot)
                )
            }
        } catch (error: PhotoCanonicalRecoveryException) {
            throw error
        } catch (error: Stage5ValidationException) {
            throw PhotoCanonicalRecoveryException(
                "post-commit generated-photo cleanup could not be completed",
                error
            )
        } catch (error: IOException) {
            throw PhotoCanonicalRecoveryException(
                "post-commit generated-photo cleanup could not be completed",
                error
            )
        } catch (error: SecurityException) {
            throw PhotoCanonicalRecoveryException(
                "post-commit generated-photo cleanup could not be completed",
                error
            )
        }
    }

    /** Contained temporary target used by FileProvider camera capture. */
    fun newCaptureFile(): File {
        resolver.requireCanonicalRecoveryResolved()
        cleanupOrphanedCaptureFiles()
        val file = resolver.newInternalFile("camera-capture", ".tmp")
        resolver.openNewOutput(file.toPath(), "camera capture").use { it.force(true) }
        return file
    }

    fun discardCaptureFile(file: File) {
        resolver.requireCanonicalRecoveryResolved()
        requireCaptureFileName(file.name)
        resolver.deletePath(file.toPath(), "camera capture")
    }

    /** Removes only stale generated camera temps in this document root. */
    fun cleanupOrphanedCaptureFiles(nowMillis: Long = System.currentTimeMillis()): Int {
        resolver.requireCanonicalRecoveryResolved()
        var removed = 0
        listDocumentFiles("camera capture cleanup").forEach { file ->
            if (!isCaptureFileName(file.name)) return@forEach
            val age = nowMillis - file.lastModified()
            if (age >= Stage5Limits.MAX_CAPTURE_AGE_MILLIS) {
                resolver.deletePath(file.toPath(), "stale camera capture")
                removed++
            }
        }
        return removed
    }

    /** Returns only the canonical document-root path; there is no global fallback. */
    fun resolveForRead(reference: String): File? {
        resolver.requireCanonicalRecoveryResolved()
        val current = resolver.resolve(reference)
        return current.takeIf { resolver.isRegularFile(it.toPath()) }
    }

    /** Validates bytes through a secure open before returning the canonical path. */
    fun resolveValidatedForRead(reference: String): File? {
        val file = resolveForRead(reference) ?: return null
        read(reference)
        return file
    }

    fun read(reference: String): ByteArray {
        resolver.requireCanonicalRecoveryResolved()
        validatePhotoFileName(reference)
        val file = resolveForRead(reference)
            ?: throw Stage5ValidationException("photo content is unavailable: $reference")
        val bytes = resolver.openRead(file.toPath(), "photo $reference").use {
            readBoundedBytes(it, Stage5Limits.MAX_PHOTO_BYTES, "photo $reference")
        }
        return validatePhotoBytes(bytes, imageProbe = imageProbe).bytes
    }

    /**
     * Explicit compatibility claim for one safe legacy basename. The source
     * is bounded, decoded, hashed, copied through a CREATE_NEW temp, atomically
     * published below this DocumentId, and retained unchanged at its old path.
     */
    fun migrateLegacyPhoto(reference: String, legacyRoot: File): File {
        resolver.requireCanonicalRecoveryResolved()
        validatePhotoFileName(reference)
        val target = resolver.resolve(reference)
        if (resolver.isRegularFile(target.toPath())) {
            try {
                read(reference)
                return target
            } catch (invalidTarget: Stage5ValidationException) {
                // A failed prior publication must not shadow a valid legacy
                // source on the next attempt. Preserve evidence under an
                // internal name, then retry the safe-basename source below.
                quarantineInvalidLegacyTarget(target, reference, invalidTarget)
            } catch (invalidTarget: IOException) {
                quarantineInvalidLegacyTarget(target, reference, invalidTarget)
            } catch (invalidTarget: SecurityException) {
                quarantineInvalidLegacyTarget(target, reference, invalidTarget)
            }
        }
        if (resolver.exists(target.toPath())) {
            throw Stage5ValidationException("document photo target is not a regular file: $reference")
        }
        return PhotoPathResolver(
            legacyRoot,
            createRoot = false,
            operationsFactory = operationsFactory
        ).use { legacyResolver ->
            // The resolver is owned by this use block before any resolve,
            // type, or read operation can fail, including missing and
            // malformed legacy files.
            val legacy = legacyResolver.resolve(reference)
            if (!legacyResolver.isRegularFile(legacy.toPath())) {
                throw Stage5ValidationException("legacy photo content is unavailable: $reference")
            }
            val sourceBytes = legacyResolver.openRead(legacy.toPath(), "legacy photo $reference").use {
                readBoundedBytes(it, Stage5Limits.MAX_PHOTO_BYTES, "legacy photo $reference")
            }
            val sourceDescriptor = validatePhotoBytes(sourceBytes, imageProbe = imageProbe).descriptor
            val temporary = resolver.newInternalFile("legacy-migrate", ".tmp")
            var published = false
            var failure: Throwable? = null
            fun recordTemporaryCleanupFailure(cleanupFailure: Throwable) {
                if (failure != null) {
                    failure?.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
            try {
                resolver.writeBytes(temporary.toPath(), sourceBytes, "legacy photo migration")
                try {
                    resolver.atomicMove(temporary.toPath(), target.toPath())
                    published = true
                } catch (alreadyPublished: FileAlreadyExistsException) {
                    val existing = read(reference)
                    if (sha256Hex(existing) != sourceDescriptor.sha256) throw alreadyPublished
                    return@use target
                }
                val copied = read(reference)
                validatePhotoBytes(copied, expected = sourceDescriptor, imageProbe = imageProbe)
                return@use target
            } catch (error: Stage5ValidationException) {
                failure = error
                if (published) {
                    try {
                        resolver.deletePath(target.toPath(), "legacy migration rollback")
                    } catch (cleanupFailure: Stage5ValidationException) {
                        // Quarantine the invalid target so it cannot shadow
                        // the preserved legacy source on a retry.
                        quarantineAfterLegacyCleanupFailure(target, reference, cleanupFailure, error)
                    } catch (cleanupFailure: IOException) {
                        quarantineAfterLegacyCleanupFailure(target, reference, cleanupFailure, error)
                    } catch (cleanupFailure: SecurityException) {
                        quarantineAfterLegacyCleanupFailure(target, reference, cleanupFailure, error)
                    }
                }
                throw error
            } catch (error: IOException) {
                failure = error
                if (published) {
                    try {
                        resolver.deletePath(target.toPath(), "legacy migration rollback")
                    } catch (cleanupFailure: Stage5ValidationException) {
                        quarantineAfterLegacyCleanupFailure(target, reference, cleanupFailure, error)
                    } catch (cleanupFailure: IOException) {
                        quarantineAfterLegacyCleanupFailure(target, reference, cleanupFailure, error)
                    } catch (cleanupFailure: SecurityException) {
                        quarantineAfterLegacyCleanupFailure(target, reference, cleanupFailure, error)
                    }
                }
                throw error
            } catch (error: SecurityException) {
                failure = error
                if (published) {
                    try {
                        resolver.deletePath(target.toPath(), "legacy migration rollback")
                    } catch (cleanupFailure: Stage5ValidationException) {
                        quarantineAfterLegacyCleanupFailure(target, reference, cleanupFailure, error)
                    } catch (cleanupFailure: IOException) {
                        quarantineAfterLegacyCleanupFailure(target, reference, cleanupFailure, error)
                    } catch (cleanupFailure: SecurityException) {
                        quarantineAfterLegacyCleanupFailure(target, reference, cleanupFailure, error)
                    }
                }
                throw error
            } finally {
                try {
                    resolver.deletePath(temporary.toPath(), "legacy migration cleanup")
                } catch (cleanupFailure: Stage5ValidationException) {
                    recordTemporaryCleanupFailure(cleanupFailure)
                } catch (cleanupFailure: IOException) {
                    recordTemporaryCleanupFailure(cleanupFailure)
                } catch (cleanupFailure: SecurityException) {
                    recordTemporaryCleanupFailure(cleanupFailure)
                }
            }
        }
    }

    private fun quarantineInvalidLegacyTarget(
        target: File,
        reference: String,
        invalidTarget: Throwable
    ) {
        val quarantine = resolver.newInternalFile("legacy-quarantine", ".bad")
        try {
            resolver.atomicMove(target.toPath(), quarantine.toPath())
        } catch (quarantineFailure: Stage5ValidationException) {
            throwLegacyQuarantineFailure(reference, invalidTarget, quarantineFailure)
        } catch (quarantineFailure: IOException) {
            throwLegacyQuarantineFailure(reference, invalidTarget, quarantineFailure)
        } catch (quarantineFailure: SecurityException) {
            throwLegacyQuarantineFailure(reference, invalidTarget, quarantineFailure)
        }
    }

    private fun throwLegacyQuarantineFailure(
        reference: String,
        invalidTarget: Throwable,
        quarantineFailure: Throwable
    ): Nothing {
        if (quarantineFailure.suppressed.none { it === invalidTarget }) {
            quarantineFailure.addSuppressed(invalidTarget)
        }
        throw Stage5ValidationException(
            "invalid document photo target cannot be quarantined: $reference",
            quarantineFailure
        )
    }

    private fun quarantineAfterLegacyCleanupFailure(
        target: File,
        reference: String,
        cleanupFailure: Throwable,
        originalFailure: Throwable
    ) {
        try {
            val quarantine = resolver.newInternalFile("legacy-quarantine", ".bad")
            resolver.atomicMove(target.toPath(), quarantine.toPath())
        } catch (quarantineFailure: Stage5ValidationException) {
            cleanupFailure.addSuppressed(quarantineFailure)
        } catch (quarantineFailure: IOException) {
            cleanupFailure.addSuppressed(quarantineFailure)
        } catch (quarantineFailure: SecurityException) {
            cleanupFailure.addSuppressed(quarantineFailure)
        }
        if (originalFailure.suppressed.none { it === cleanupFailure }) {
            originalFailure.addSuppressed(cleanupFailure)
        }
    }

    /**
     * Source-compatible read-only legacy overload.  It deliberately does not
     * reconcile or collect files because a caller that has only one snapshot
     * cannot prove that it is also the durable authority.  Active admission
     * must use the durable/live overload below.
     */
    fun hasRequiredPhotoContent(snapshot: DocumentSnapshotV1, legacyRoot: File): Boolean =
        try {
            validateSnapshot(snapshot)
            readLegacyPhotoSet(snapshot, legacyRoot)
            true
        } catch (error: PhotoCanonicalRecoveryException) {
            throw error
        } catch (_: Stage5ValidationException) {
            false
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    /**
     * Active upload admission.  It is deliberately limited to the
     * document-scoped canonical photo root: an unclaimed basename in a global
     * legacy directory is not evidence that this document owns the bytes.
     * Legacy-root admission remains available only through the explicit
     * compatibility overload above and the migration APIs below.
     */
    fun hasRequiredPhotoContent(
        currentDurableSnapshot: DocumentSnapshotV1,
        currentLiveSnapshot: DocumentSnapshotV1
    ): Boolean {
        reconcilePhotoContent(currentDurableSnapshot, currentLiveSnapshot)
        return try {
            readPhotoContentForAdmission(currentLiveSnapshot)
            true
        } catch (error: PhotoCanonicalRecoveryException) {
            throw error
        } catch (_: Stage5ValidationException) {
            false
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Snapshot-aware upload admission. Recovery/GC failures propagate as
     * typed errors; only missing or invalid required content returns false.
     */
    fun hasRequiredPhotoContent(
        currentDurableSnapshot: DocumentSnapshotV1,
        currentLiveSnapshot: DocumentSnapshotV1,
        legacyRoot: File
    ): Boolean {
        reconcilePhotoContent(currentDurableSnapshot, currentLiveSnapshot)
        return try {
            readLegacyPhotoSet(currentLiveSnapshot, legacyRoot)
            true
        } catch (error: PhotoCanonicalRecoveryException) {
            throw error
        } catch (_: Stage5ValidationException) {
            false
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Read-only upload admission for legacy assets.  It validates and returns
     * the complete required byte set, but never publishes document targets or
     * commits a photo transaction before the caller's canonical save.
     */
    fun readPhotoContentForAdmission(
        snapshot: DocumentSnapshotV1,
        legacyRoot: File
    ): Map<String, ByteArray> = readLegacyPhotoSet(snapshot, legacyRoot)

    /** Read-only admission from this document's canonical root only. */
    fun readPhotoContentForAdmission(
        snapshot: DocumentSnapshotV1
    ): Map<String, ByteArray> = this.readReferencedPhotos(snapshot)

    /**
     * Explicitly migrates every referenced legacy asset as one transaction.
     * The complete set is size-preflighted and decoded before any target is
     * published. The suspend boundary lets callers retain the same transaction
     * ordering as Stage 4 without blocking a UI thread.
     */
    suspend fun migrateLegacyPhotos(
        snapshot: DocumentSnapshotV1,
        legacyRoot: File,
        previousCanonicalSnapshot: DocumentSnapshotV1? = null,
        previousLiveCanonicalSnapshot: DocumentSnapshotV1? = previousCanonicalSnapshot
    ): Map<String, ByteArray> = withMigratedLegacyPhotos(
        snapshot = snapshot,
        legacyRoot = legacyRoot,
        previousCanonicalSnapshot = previousCanonicalSnapshot,
        previousLiveCanonicalSnapshot = previousLiveCanonicalSnapshot
    ) { it }

    /**
     * Runs a document-scoped legacy migration around a caller's durable/apply
     * operation. Photos publish before the callback and commit only after it
     * succeeds; callback failure rolls every published target back.
     */
    suspend fun <T> withMigratedLegacyPhotos(
        snapshot: DocumentSnapshotV1,
        legacyRoot: File,
        previousCanonicalSnapshot: DocumentSnapshotV1? = null,
        previousLiveCanonicalSnapshot: DocumentSnapshotV1? = previousCanonicalSnapshot,
        commitResult: (T) -> Boolean = { true },
        /**
         * Runs while the caller still owns the document barrier and proves
         * that both canonical authorities exactly match the previous
         * snapshot.  A false result (or an unavailable proof) keeps the
         * photo journal/intent for cross-store recovery instead of deleting
         * the only evidence of a mixed import.
         */
        canonicalRollbackProven: (suspend () -> Boolean)? = null,
        block: suspend (Map<String, ByteArray>) -> T
    ): T {
        val prepared = withContext(Dispatchers.IO) {
            prepareLegacyPhotoSet(snapshot, legacyRoot)
        }
        val transaction = prepared.transaction
        val rollbackProof = canonicalRollbackProven ?: { previousCanonicalSnapshot == null }
        var canonicalApplyAttempted = false
        var canonicalCommitAccepted = false
        var canonicalRestorationProven: Boolean? = null
        try {
            transaction?.let {
                previousCanonicalSnapshot?.let { previous ->
                    it.prepareCanonicalRecovery(
                        photoCanonicalIdentity(documentId, previous),
                        photoCanonicalIdentity(
                            documentId,
                            previousLiveCanonicalSnapshot ?: previous
                        ),
                        previousLiveCanonicalSnapshot ?: previous,
                        photoCanonicalIdentity(documentId, snapshot)
                    )
                }
            }
            transaction?.publish()
            // The callback is the canonical durable/live replacement seam.
            // From this point a callback failure or cancellation may have
            // persisted the incoming snapshot before restoration was proven.
            canonicalApplyAttempted = true
            val result = block(prepared.bytes)
            canonicalCommitAccepted = commitResult(result)
            if (!canonicalCommitAccepted && transaction != null) {
                canonicalRestorationProven = try {
                    withContext(NonCancellable) { rollbackProof() }
                } catch (cancelled: CancellationException) {
                    // Do not retry a canceled proof from the outer handler;
                    // the compensation path below will retain evidence and
                    // the original cancellation will be rethrown.
                    canonicalRestorationProven = false
                    throw cancelled
                } catch (error: PhotoCanonicalRecoveryException) {
                    canonicalRestorationProven = false
                    throw error
                } catch (error: Stage5ValidationException) {
                    canonicalRestorationProven = false
                    throw error
                } catch (error: IOException) {
                    canonicalRestorationProven = false
                    throw error
                } catch (error: SecurityException) {
                    canonicalRestorationProven = false
                    throw error
                } catch (error: IllegalArgumentException) {
                    canonicalRestorationProven = false
                    throw error
                } catch (error: IllegalStateException) {
                    canonicalRestorationProven = false
                    throw error
                }
                if (canonicalRestorationProven != true) {
                    rollbackMigrationAfterFailure(
                        transaction = transaction,
                        canonicalApplyAttempted = canonicalApplyAttempted,
                        canonicalCommitAccepted = canonicalCommitAccepted,
                        canonicalRollbackProof = rollbackProof,
                        canonicalRestorationProven = false,
                        error = PhotoCanonicalRecoveryException(
                            "canonical import restoration was not proven; photo rollback evidence retained"
                        )
                    )
                }
            }
            transaction?.let {
                withContext(NonCancellable) {
                    if (canonicalCommitAccepted) it.commit() else it.rollback()
                }
            }
            return result
        } catch (cancelled: CancellationException) {
            rollbackMigrationAfterFailure(
                transaction,
                canonicalApplyAttempted,
                canonicalCommitAccepted,
                rollbackProof,
                canonicalRestorationProven,
                cancelled
            )
        } catch (error: PhotoCanonicalRecoveryException) {
            rollbackMigrationAfterFailure(
                transaction,
                canonicalApplyAttempted,
                canonicalCommitAccepted,
                rollbackProof,
                canonicalRestorationProven,
                error
            )
        } catch (error: PhotoRollbackException) {
            rollbackMigrationAfterFailure(
                transaction,
                canonicalApplyAttempted,
                canonicalCommitAccepted,
                rollbackProof,
                canonicalRestorationProven,
                error
            )
        } catch (error: Stage5ValidationException) {
            rollbackMigrationAfterFailure(
                transaction,
                canonicalApplyAttempted,
                canonicalCommitAccepted,
                rollbackProof,
                canonicalRestorationProven,
                error
            )
        } catch (error: IOException) {
            rollbackMigrationAfterFailure(
                transaction,
                canonicalApplyAttempted,
                canonicalCommitAccepted,
                rollbackProof,
                canonicalRestorationProven,
                error
            )
        } catch (error: SecurityException) {
            rollbackMigrationAfterFailure(
                transaction,
                canonicalApplyAttempted,
                canonicalCommitAccepted,
                rollbackProof,
                canonicalRestorationProven,
                error
            )
        } catch (error: IllegalStateException) {
            rollbackMigrationAfterFailure(
                transaction,
                canonicalApplyAttempted,
                canonicalCommitAccepted,
                rollbackProof,
                canonicalRestorationProven,
                error
            )
        }
    }

    private suspend fun rollbackMigrationAfterFailure(
        transaction: PhotoContentTransaction?,
        canonicalApplyAttempted: Boolean,
        canonicalCommitAccepted: Boolean,
        canonicalRollbackProof: suspend () -> Boolean,
        canonicalRestorationProven: Boolean?,
        error: Throwable
    ): Nothing {
        transaction?.let { photoTransaction ->
            if (canonicalCommitAccepted) {
                // A pre-authoritative marker failure leaves the staged
                // resolver open by design so a caller with an old-state
                // rollback path can still use it. Migration has no separate
                // canonical compensating callback, so it retains V2 evidence
                // and explicitly releases this transaction-owned resolver.
                photoTransaction.releaseAfterFailure()
                if (error is PhotoCanonicalRecoveryException) throw error
                throw PhotoCanonicalRecoveryException(
                    "canonical snapshot applied but photo transaction commit failed; recovery evidence retained",
                    error
                )
            }
            var proofCancellation: CancellationException? = null
            val oldCanonicalRestored = if (!canonicalApplyAttempted) {
                true
            } else {
                canonicalRestorationProven ?: try {
                    withContext(NonCancellable) { canonicalRollbackProof() }
                } catch (cancelled: CancellationException) {
                    proofCancellation = cancelled
                    false
                } catch (_: PhotoCanonicalRecoveryException) {
                    false
                } catch (_: Stage5ValidationException) {
                    false
                } catch (_: IOException) {
                    false
                } catch (_: SecurityException) {
                    false
                } catch (_: IllegalArgumentException) {
                    false
                } catch (_: IllegalStateException) {
                    false
                }
            }
            if (!oldCanonicalRestored) {
                try {
                    // Restore the photo bytes, but deliberately retain the
                    // V2/V3 journal and canonical intent until a fresh
                    // durable/live proof can authorize cleanup.
                    withContext(NonCancellable) {
                        photoTransaction.rollbackForCrossStoreCompensation()
                    }
                } catch (cancelled: CancellationException) {
                    photoTransaction.releaseAfterFailure()
                    if (cancelled !== error && cancelled.suppressed.none { it === error }) {
                        cancelled.addSuppressed(error)
                    }
                    throw cancelled
                } catch (rollback: PhotoRollbackException) {
                    photoTransaction.releaseAfterFailure()
                    if (rollback !== error && rollback.suppressed.none { it === error }) {
                        rollback.addSuppressed(error)
                    }
                    throw rollback
                } catch (rollback: IOException) {
                    photoTransaction.releaseAfterFailure()
                    if (rollback !== error && rollback.suppressed.none { it === error }) {
                        rollback.addSuppressed(error)
                    }
                    throw rollback
                } catch (rollback: SecurityException) {
                    photoTransaction.releaseAfterFailure()
                    if (rollback !== error && rollback.suppressed.none { it === error }) {
                        rollback.addSuppressed(error)
                    }
                    throw rollback
                } catch (rollback: IllegalArgumentException) {
                    photoTransaction.releaseAfterFailure()
                    if (rollback !== error && rollback.suppressed.none { it === error }) {
                        rollback.addSuppressed(error)
                    }
                    throw rollback
                } catch (rollback: IllegalStateException) {
                    photoTransaction.releaseAfterFailure()
                    if (rollback !== error && rollback.suppressed.none { it === error }) {
                        rollback.addSuppressed(error)
                    }
                    throw rollback
                }
                photoTransaction.releaseAfterFailure()
                if (error is CancellationException) {
                    proofCancellation?.let { proof ->
                        if (proof !== error && error.suppressed.none { it === proof }) {
                            error.addSuppressed(proof)
                        }
                    }
                    throw error
                }
                proofCancellation?.let { proof ->
                    if (proof !== error && proof.suppressed.none { it === error }) {
                        proof.addSuppressed(error)
                    }
                    throw proof
                }
                if (error is PhotoCanonicalRecoveryException) throw error
                throw PhotoCanonicalRecoveryException(
                    "canonical snapshot restoration was not proven; photo rollback evidence retained",
                    error
                )
            }
            try {
                withContext(NonCancellable) { photoTransaction.rollback() }
            } catch (cancelled: CancellationException) {
                photoTransaction.releaseAfterFailure()
                if (cancelled !== error && cancelled.suppressed.none { it === error }) {
                    cancelled.addSuppressed(error)
                }
                throw cancelled
            } catch (rollback: PhotoRollbackException) {
                photoTransaction.releaseAfterFailure()
                if (rollback !== error && rollback.suppressed.none { it === error }) {
                    rollback.addSuppressed(error)
                }
                throw rollback
            } catch (rollback: IOException) {
                photoTransaction.releaseAfterFailure()
                if (rollback !== error && rollback.suppressed.none { it === error }) {
                    rollback.addSuppressed(error)
                }
                throw rollback
            } catch (rollback: SecurityException) {
                photoTransaction.releaseAfterFailure()
                if (rollback !== error && rollback.suppressed.none { it === error }) {
                    rollback.addSuppressed(error)
                }
                throw rollback
            } catch (rollback: IllegalArgumentException) {
                photoTransaction.releaseAfterFailure()
                if (rollback !== error && rollback.suppressed.none { it === error }) {
                    rollback.addSuppressed(error)
                }
                throw rollback
            } catch (rollback: IllegalStateException) {
                photoTransaction.releaseAfterFailure()
                if (rollback !== error && rollback.suppressed.none { it === error }) {
                    rollback.addSuppressed(error)
                }
                throw rollback
            }
        }
        throw error
    }

    private fun prepareLegacyPhotoSet(
        snapshot: DocumentSnapshotV1,
        legacyRoot: File
    ): PreparedLegacyPhotoSet {
        val bytes = readLegacyPhotoSet(snapshot, legacyRoot)
        if (bytes.isEmpty()) return PreparedLegacyPhotoSet(bytes, null)
        val transaction = StagedPhotoContentTransaction.stageWithOperationsFactory(
            resolver.root,
            bytes,
            operationsFactory
        )
        return PreparedLegacyPhotoSet(bytes, transaction)
    }

    /**
     * Complete-set preflight. Stat every exact source first, enforce both the
     * per-file and aggregate ceilings before reading, then bounded-read and
     * decode/hash every source into a bounded map. A size change during the
     * read fails closed instead of trusting a stale stat result.
     */
    private fun readLegacyPhotoSet(
        snapshot: DocumentSnapshotV1,
        legacyRoot: File
    ): Map<String, ByteArray> {
        resolver.requireCanonicalRecoveryResolved()
        validateSnapshot(snapshot)
        val names = requiredPhotoNames(snapshot).sorted()
        if (names.isEmpty()) return emptyMap()

        var legacyResolver: PhotoPathResolver? = null
        val sources = mutableListOf<PhotoSource>()
        try {
            names.forEach { name ->
                val target = resolver.resolve(name).toPath()
                val source = when {
                    resolver.isRegularFile(target) -> PhotoSource(
                        name,
                        resolver,
                        target,
                        resolver.size(target, "document photo $name"),
                        "document photo $name"
                    )
                    resolver.exists(target) -> throw Stage5ValidationException(
                        "document photo content is not a regular file: $name"
                    )
                    else -> {
                        val legacy = legacyResolver ?: PhotoPathResolver(
                            legacyRoot,
                            createRoot = false,
                            operationsFactory = operationsFactory
                        ).also { legacyResolver = it }
                        val legacyPath = legacy.resolve(name).toPath()
                        if (!legacy.isRegularFile(legacyPath)) {
                            throw Stage5ValidationException("legacy photo content is unavailable: $name")
                        }
                        PhotoSource(
                            name,
                            legacy,
                            legacyPath,
                            legacy.size(legacyPath, "legacy photo $name"),
                            "legacy photo $name"
                        )
                    }
                }
                requirePhotoSize(source.declaredSize, source.label)
                sources += source
            }

            var declaredTotal = 0L
            sources.forEach { source ->
                declaredTotal = addPhotoSize(declaredTotal, source.declaredSize, source.label)
            }

            val rawBytes = linkedMapOf<String, ByteArray>()
            var actualTotal = 0L
            sources.forEach { source ->
                val bytes = source.resolver.openRead(source.path, source.label).use {
                    readBoundedBytes(it, Stage5Limits.MAX_PHOTO_BYTES, source.label)
                }
                if (bytes.size.toLong() != source.declaredSize) {
                    throw Stage5ValidationException("${source.label} changed during bounded read")
                }
                actualTotal = addPhotoSize(actualTotal, bytes.size.toLong(), source.label)
                rawBytes[source.name] = bytes
            }
            if (actualTotal != declaredTotal) {
                throw Stage5ValidationException("photo aggregate changed during preflight")
            }
            return validatePhotoSet(snapshot, rawBytes, imageProbe = imageProbe)
                .mapValues { (_, validated) -> validated.bytes }
        } finally {
            try {
                legacyResolver?.close()
            } catch (error: IOException) {
                throw error
            } catch (error: SecurityException) {
                throw error
            }
        }
    }

    private fun requirePhotoSize(size: Long, label: String) {
        if (size <= 0L || size > Stage5Limits.MAX_PHOTO_BYTES.toLong()) {
            throw Stage5ValidationException("$label exceeds the individual photo size limit")
        }
    }

    private fun addPhotoSize(total: Long, size: Long, label: String): Long {
        if (size < 0L || total > Stage5Limits.MAX_TOTAL_PHOTO_BYTES - size) {
            throw Stage5ValidationException("photo aggregate exceeds the total size limit at $label")
        }
        return total + size
    }

    /**
     * Bounded camera/gallery import. The generated final file is created only
     * after the temporary bytes pass byte, hash, signature, decode, and image
     * dimension validation.
     */
    fun publishNewPhoto(
        input: InputStream,
        extension: String = ".jpg",
        existingPhotoReferences: Set<String> = emptySet()
    ): String = PhotoDocumentCriticalSections.withLock(resolver.root.toPath()) {
        resolver.requireCanonicalRecoveryResolved()
        val normalizedExtension = extension.lowercase()
        require(normalizedExtension == ".jpg" || normalizedExtension == ".jpeg" || normalizedExtension == ".png" || normalizedExtension == ".webp") {
            "unsupported photo extension"
        }
        var temporary: File? = null
        var target: File? = null
        var reference: String? = null
        var published = false
        fun cleanupFailedPublication(error: Throwable): Nothing {
            var cleanupFailure: Throwable? = null
            fun recordCleanupFailure(failure: Throwable) {
                if (cleanupFailure == null) {
                    cleanupFailure = failure
                } else {
                    cleanupFailure?.addSuppressed(failure)
                }
            }
            if (published) {
                try {
                    resolver.deletePath(target!!.toPath(), "photo import rollback")
                } catch (cleanup: Stage5ValidationException) {
                    recordCleanupFailure(cleanup)
                } catch (cleanup: IOException) {
                    recordCleanupFailure(cleanup)
                } catch (cleanup: SecurityException) {
                    recordCleanupFailure(cleanup)
                }
            }
            temporary?.let { temp ->
                try {
                    resolver.deletePath(temp.toPath(), "photo import cleanup")
                } catch (cleanup: Stage5ValidationException) {
                    recordCleanupFailure(cleanup)
                } catch (cleanup: IOException) {
                    recordCleanupFailure(cleanup)
                } catch (cleanup: SecurityException) {
                    recordCleanupFailure(cleanup)
                }
            }
            reference?.let { name -> PhotoPublicationReservations.release(resolver.root.toPath(), name) }
            cleanupFailure?.let { failure ->
                if (error.suppressed.none { it === failure }) error.addSuppressed(failure)
            }
            throw error
        }
        try {
            // Reserve the generated name before any atomic rename makes it
            // visible. The reservation survives this method until attachment
            // or explicit cleanup releases it.
            reference = resolver.newPhotoReference()
            PhotoPublicationReservations.reserve(resolver.root.toPath(), reference!!)
            target = resolver.resolve(reference!!)
            temporary = resolver.newInternalFile("stage5-photo", ".tmp")
            val importedBytes = readBoundedBytes(input, Stage5Limits.MAX_PHOTO_BYTES, "photo import")
            resolver.openNewOutput(temporary!!.toPath(), "photo import").use { channel ->
                val buffer = ByteBuffer.wrap(importedBytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            val bytes = resolver.openRead(temporary!!.toPath(), "photo import").use {
                readBoundedBytes(it, Stage5Limits.MAX_PHOTO_BYTES, "photo import")
            }
            val validated = validatePhotoBytes(bytes, imageProbe = imageProbe)
            validateExistingPhotoCapacity(existingPhotoReferences, bytes.size.toLong())
            val expectedExtension = when (validated.descriptor.mimeType) {
                "image/jpeg" -> setOf(".jpg", ".jpeg")
                "image/png" -> setOf(".png")
                "image/webp" -> setOf(".webp")
                else -> emptySet()
            }
            require(normalizedExtension in expectedExtension) {
                "photo extension does not match decoded image type"
            }
            resolver.atomicMove(temporary!!.toPath(), target!!.toPath())
            published = true
            validatePhotoBytes(
                read(reference!!),
                expected = validated.descriptor,
                imageProbe = imageProbe
            )
            reference!!
        } catch (error: Stage5ValidationException) {
            cleanupFailedPublication(error)
        } catch (error: IOException) {
            cleanupFailedPublication(error)
        } catch (error: SecurityException) {
            cleanupFailedPublication(error)
        }
    }

    fun publishNewPhoto(
        bytes: ByteArray,
        extension: String = ".jpg",
        existingPhotoReferences: Set<String> = emptySet()
    ): String = bytes.inputStream().use {
        publishNewPhoto(it, extension, existingPhotoReferences)
    }

    /** Deletes one generated publication only; legacy basenames are preserved. */
    fun cleanup(reference: String) = PhotoDocumentCriticalSections.withLock(resolver.root.toPath()) {
        try {
            resolver.requireCanonicalRecoveryResolved()
            requireGeneratedPhotoName(reference)
            resolver.delete(reference)
        } finally {
            PhotoPublicationReservations.release(resolver.root.toPath(), reference)
        }
    }

    /** Releases the camera attachment reservation after live state owns it. */
    fun releasePhotoPublication(reference: String) {
        requireGeneratedPhotoName(reference)
        PhotoDocumentCriticalSections.withLock(resolver.root.toPath()) {
            PhotoPublicationReservations.release(resolver.root.toPath(), reference)
        }
    }

    /**
     * Bounded document-scoped garbage collection. Only generated publication
     * names are eligible; references, legacy basenames, rollback artifacts,
     * temporary captures, and outside paths are never touched.
     */
    fun cleanupUnreferencedGeneratedPhotos(
        referencedPhotoNames: Set<String>,
        inFlightPhotoNames: Set<String> = emptySet()
    ): Int = PhotoDocumentCriticalSections.withLock(resolver.root.toPath()) {
        resolver.requireCanonicalRecoveryResolved()
        referencedPhotoNames.forEach(::validatePhotoFileName)
        inFlightPhotoNames.forEach(::validatePhotoFileName)
        val protectedNames = referencedPhotoNames +
            inFlightPhotoNames +
            PhotoPublicationReservations.active(resolver.root.toPath()) +
            resolver.currentPhotoTransactionTargets()
        var removed = 0
        listDocumentFiles("generated photo cleanup").forEach { file ->
            val name = file.name
            if (!isGeneratedPhotoName(name) || name in protectedNames) return@forEach
            if (resolver.isRegularFile(file.toPath())) {
                resolver.deletePath(file.toPath(), "unreferenced generated photo")
                removed++
            }
        }
        return@withLock removed
    }

    fun cleanupUnreferencedGeneratedPhotos(
        snapshot: DocumentSnapshotV1,
        inFlightPhotoNames: Set<String> = emptySet()
    ): Int {
        resolver.requireCanonicalRecoveryResolved()
        validateSnapshot(snapshot)
        return cleanupUnreferencedGeneratedPhotos(requiredPhotoNames(snapshot), inFlightPhotoNames)
    }

    override fun close() {
        resolver.close()
    }

    private fun validateExistingPhotoCapacity(references: Set<String>, incomingSize: Long) {
        resolver.requireCanonicalRecoveryResolved()
        if (references.size > Stage5Limits.MAX_TOTAL_PHOTOS) {
            throw Stage5ValidationException("referenced photo count exceeds its limit")
        }
        var total = incomingSize
        references.forEach { reference ->
            validatePhotoFileName(reference)
            val path = resolver.resolve(reference).toPath()
            if (!resolver.isRegularFile(path)) {
                throw Stage5ValidationException("existing referenced photo is unavailable: $reference")
            }
            total = addPhotoSize(total, resolver.size(path, "existing photo $reference"), "existing photo $reference")
        }
        if (total > Stage5Limits.MAX_TOTAL_PHOTO_BYTES) {
            throw Stage5ValidationException("total photo content exceeds limit")
        }
    }

    private fun listDocumentFiles(label: String): Array<File> {
        val files = resolver.root.listFiles()
            ?: throw Stage5ValidationException("$label could not enumerate the document photo root")
        if (files.size > Stage5Limits.MAX_PHOTO_DIRECTORY_ENTRIES) {
            throw Stage5ValidationException("$label exceeds its bounded directory-entry limit")
        }
        return files
    }

    private fun requireGeneratedPhotoName(name: String) {
        validatePhotoFileName(name)
        if (!isGeneratedPhotoName(name)) throw Stage5ValidationException("only generated photo publications may be deleted")
    }

    private fun isGeneratedPhotoName(name: String): Boolean =
        name.matches(Regex("photo-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.jpg"))

    private fun requireCaptureFileName(name: String) {
        if (!isCaptureFileName(name)) throw Stage5ValidationException("unsafe camera capture file")
    }

    private fun isCaptureFileName(name: String): Boolean =
        name.matches(Regex("\\.camera-capture-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.tmp"))

    companion object {
        fun atomicMove(source: Path, target: Path, resolver: PhotoPathResolver) {
            resolver.atomicMove(source, target)
        }
    }
}

/** Captures and validates the complete photo sidecar for a canonical snapshot. */
fun DocumentPhotoAssetStore.readReferencedPhotos(
    snapshot: DocumentSnapshotV1
): Map<String, ByteArray> {
    validateSnapshot(snapshot)
    val names = requiredPhotoNames(snapshot)
    if (names.isEmpty()) return emptyMap()
    val rawBytes = linkedMapOf<String, ByteArray>()
    var total = 0L
    names.sorted().forEach { name ->
        val path = resolver.resolve(name).toPath()
        if (!resolver.isRegularFile(path)) {
            throw Stage5ValidationException("photo content is unavailable: $name")
        }
        val declaredSize = resolver.size(path, "photo $name")
        if (declaredSize <= 0L || declaredSize > Stage5Limits.MAX_PHOTO_BYTES.toLong()) {
            throw Stage5ValidationException("photo $name exceeds the individual size limit")
        }
        if (total > Stage5Limits.MAX_TOTAL_PHOTO_BYTES - declaredSize) {
            throw Stage5ValidationException("photo aggregate exceeds the total size limit")
        }
        val bytes = resolver.openRead(path, "photo $name").use {
            readBoundedBytes(it, Stage5Limits.MAX_PHOTO_BYTES, "photo $name")
        }
        if (bytes.size.toLong() != declaredSize) {
            throw Stage5ValidationException("photo $name changed during bounded read")
        }
        total += bytes.size.toLong()
        rawBytes[name] = bytes
    }
    return validatePhotoSet(snapshot, rawBytes, imageProbe = imageProbe)
        .mapValues { (_, validated) -> validated.bytes }
}
