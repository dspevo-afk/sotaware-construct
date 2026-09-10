package com.example.myapplication.stage9b

import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage5.PhotoDocumentCriticalSections
import com.example.myapplication.stage5.PhotoPathOperationsFactory
import com.example.myapplication.stage5.PhotoPathResolver
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.validateNoDuplicateJsonMembers
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedHashMap
import java.util.Locale

/** The durable reservation aggregate is deliberately small and bounded. */
internal const val DRIVE_TRANSFER_MAX_RESERVED_RESOURCES: Int = 4_096
internal const val DRIVE_TRANSFER_MAX_RESERVED_BYTES: Int = 8 * 1024 * 1024

private const val STORAGE_MAGIC = "SOTAWARE_STAGE9B_DRIVE_TRANSFER_RECORD_V1"
private const val STORAGE_VERSION = 1
private const val RESERVATION_RECORD_KEY = "resource-reservations"
private const val RESERVATION_SCHEMA = 1
private const val RESERVATION_RECORD_MAX_BYTES = 8 * 1024 * 1024
private val STORAGE_KEY_REGEX = Regex("[A-Za-z0-9_-]{1,160}")
private val RESOURCE_ID_REGEX = Regex("[A-Za-z0-9_-]{1,512}")
private val SHA256_REGEX = Regex("[0-9a-f]{64}")

private fun createDurableTransferResolver(
    directory: Path,
    trustedRootDirectory: Path?,
    operationsFactory: PhotoPathOperationsFactory?
): PhotoPathResolver {
    // Reject an unanchored/relative location before PhotoPathResolver can
    // create anything.  This keeps admission side-effect free on bad input.
    require(directory.isAbsolute) { "Drive transfer storage directory must be absolute" }
    return if (operationsFactory == null) {
        PhotoPathResolver(
            directory.toFile(),
            createRoot = true,
            trustedRootDirectory = trustedRootDirectory?.toFile()
        )
    } else {
        PhotoPathResolver(
            directory.toFile(),
            createRoot = true,
            operationsFactory = operationsFactory,
            trustedRootDirectory = trustedRootDirectory?.toFile()
        )
    }
}

/**
 * A validated identity for a generated Drive resource.  The parent is part
 * of the identity on purpose: a resumable operation must never reuse a
 * document resource that was reserved below another folder.
 */
internal data class DurableDriveResourceIdentity(
    val scope: SyncScope,
    val sourceFingerprint: String?,
    val parentFolderId: String,
    val resourceKind: String
)

private data class DurableDriveResourceRecord(
    val accountId: String,
    val backupRootId: String,
    val documentId: String,
    val sourceFingerprint: String?,
    val parentFolderId: String,
    val resourceKind: String,
    val remoteId: String
)

/**
 * Small descriptor-relative storage used by the Drive transport.  Each
 * logical record has two fixed slots.  A new slot is created and atomically
 * renamed into place while the previous slot remains readable, so a crash
 * cannot leave a publication gap.  There is intentionally no downgrade to a
 * non-atomic rename and no path-based file operation.
 */
internal class DurableDriveTransferStorage(
    directory: Path,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val gson: Gson = Gson(),
    trustedRootDirectory: Path? = null,
    operationsFactory: PhotoPathOperationsFactory? = null,
    private val directoryForce: (() -> Unit)? = null
) : AutoCloseable {
    private val lock = Any()
    private val resolver: PhotoPathResolver = createDurableTransferResolver(
        directory,
        trustedRootDirectory,
        operationsFactory
    )

    /** The opened, anchored root used for the process-shared critical section. */
    internal val root: Path = resolver.root.toPath()

    init {
        require(DRIVE_TRANSFER_MAX_RESERVED_BYTES <= RESERVATION_RECORD_MAX_BYTES)
    }

    /** Reads the newest validated slot for [key], or null when it is absent. */
    fun read(key: String, maxPayloadBytes: Int = Int.MAX_VALUE): ByteArray? = synchronized(lock) {
        PhotoDocumentCriticalSections.withLock(root) {
            readLocked(key, maxPayloadBytes)
        }
    }

    /**
     * Publishes one payload with a forced file, an atomic same-directory
     * rename, a directory durability fence, and an exact read-back.  A
     * failure after the rename deliberately leaves the new slot in place so
     * callers can recover a reservation instead of generating another ID.
     */
    fun write(key: String, payload: ByteArray, maxPayloadBytes: Int = Int.MAX_VALUE) =
        synchronized(lock) {
            PhotoDocumentCriticalSections.withLock(root) {
                writeLocked(key, payload, maxPayloadBytes)
            }
        }

    /** Deletes both known slots only after the owning record has been read. */
    fun delete(key: String) = synchronized(lock) {
        PhotoDocumentCriticalSections.withLock(root) {
            val existing = readSlotsLocked(key)
            if (existing.any { it is SlotRead.Invalid }) {
                throw DriveAssetTransferException("Drive transfer record is invalid; refusing deletion")
            }
            var deleted = false
            slotPaths(key).forEach { path ->
                if (resolver.exists(path)) {
                    resolver.deletePath(path, "Drive transfer record cleanup")
                    deleted = true
                }
            }
            if (deleted) forceDirectory()
        }
    }

    /**
     * Returns a stable, durably reserved Drive ID.  The generator is called
     * at most once for a missing key and only while the root-scoped process
     * lock is held.  If publication/read-back fails after the rename, the
     * record remains available to the next caller.
     */
    fun reserveResourceId(
        identity: DurableDriveResourceIdentity,
        isGenerationCurrent: () -> Boolean,
        generate: () -> String
    ): String = synchronized(lock) {
        PhotoDocumentCriticalSections.withLock(root) {
            validateResourceIdentity(identity)
            if (!isGenerationCurrent()) throw DriveAssetStaleGenerationException()
            val records = readReservationsLocked()
            val key = reservationKey(identity)
            val existing = records[key]
            if (existing != null) {
                validateReservationRecord(key, existing)
                if (!isGenerationCurrent()) throw DriveAssetStaleGenerationException()
                return@withLock existing.remoteId
            }
            if (records.size >= DRIVE_TRANSFER_MAX_RESERVED_RESOURCES) {
                throw DriveAssetTransferException("Drive resource reservation count exceeds its limit")
            }
            // Keep enough room for one maximally-sized record.  This avoids
            // spending a network ID when the bounded aggregate is already at
            // its admission edge.
            val currentBytes = encodeReservations(records).size
            if (currentBytes > DRIVE_TRANSFER_MAX_RESERVED_BYTES - MAX_RESERVATION_RECORD_BYTES) {
                throw DriveAssetTransferException("Drive resource reservation bytes exceed their limit")
            }
            if (!isGenerationCurrent()) throw DriveAssetStaleGenerationException()
            val remoteId = generate().also(::validateResourceId)
            // A generation can be superseded while the authenticated request
            // is in flight.  Do not publish or return an ID to that stale
            // caller; the generated value was never remotely created.
            if (!isGenerationCurrent()) throw DriveAssetStaleGenerationException()
            val next = LinkedHashMap(records)
            next[key] = DurableDriveResourceRecord(
                accountId = identity.scope.accountId,
                backupRootId = identity.scope.backupRootId,
                documentId = identity.scope.documentId.value,
                sourceFingerprint = identity.sourceFingerprint,
                parentFolderId = identity.parentFolderId,
                resourceKind = identity.resourceKind,
                remoteId = remoteId
            )
            val encoded = encodeReservations(next)
            if (encoded.size > DRIVE_TRANSFER_MAX_RESERVED_BYTES) {
                throw DriveAssetTransferException("Drive resource reservation bytes exceed their limit")
            }
            // writeLocked retains the slot after any post-rename force or
            // read-back error.  Do not catch and delete that evidence here.
            writeLocked(RESERVATION_RECORD_KEY, encoded, DRIVE_TRANSFER_MAX_RESERVED_BYTES)
            val readBack = readReservationsLocked()[key]
                ?: throw DriveAssetTransferException("Drive resource reservation read-back is missing")
            validateReservationRecord(key, readBack)
            if (readBack.remoteId != remoteId) {
                throw DriveAssetTransferException("Drive resource reservation read-back changed")
            }
            remoteId
        }
    }

    override fun close() = synchronized(lock) {
        resolver.close()
    }

    private fun readLocked(key: String, maxPayloadBytes: Int): ByteArray? {
        validateStorageKey(key)
        require(maxPayloadBytes >= 0) { "Drive transfer payload bound is invalid" }
        val slots = readSlotsLocked(key)
        val valid = slots.filterIsInstance<SlotRead.Valid>()
        if (slots.any { it is SlotRead.Invalid }) {
            throw DriveAssetTransferException("Drive transfer record is corrupt; refusing to guess a slot")
        }
        if (valid.isEmpty()) return null
        val newest = valid.maxBy { it.generation }
        if (valid.count { it.generation == newest.generation } > 1) {
            val same = valid.filter { it.generation == newest.generation }
            if (same.any { !it.payload.contentEquals(newest.payload) }) {
                throw DriveAssetTransferException("Drive transfer record has ambiguous generations")
            }
        }
        if (newest.payload.size > maxPayloadBytes) {
            throw DriveAssetTransferException("Drive transfer record exceeds its limit")
        }
        return newest.payload.copyOf()
    }

    private fun writeLocked(key: String, payload: ByteArray, maxPayloadBytes: Int) {
        validateStorageKey(key)
        require(maxPayloadBytes >= 0) { "Drive transfer payload bound is invalid" }
        if (payload.size > maxPayloadBytes) {
            throw DriveAssetTransferException("Drive transfer record exceeds its limit")
        }
        val slots = readSlotsLocked(key)
        if (slots.any { it is SlotRead.Invalid }) {
            throw DriveAssetTransferException("Drive transfer record is corrupt; refusing overwrite")
        }
        val valid = slots.filterIsInstance<SlotRead.Valid>()
        val active = valid.maxWithOrNull(compareBy<SlotRead.Valid> { it.generation }.thenBy { it.slot })
        val targetSlot = if (active == null) 0 else 1 - active.slot
        val target = slotPaths(key)[targetSlot]
        if (resolver.exists(target)) {
            // The target is the inactive validated slot.  Removing it before
            // the new atomic rename leaves the active slot as the recovery
            // authority if the process dies in this small window.
            resolver.deletePath(target, "Drive transfer inactive-slot cleanup")
            forceDirectory()
        }
        val generation = (active?.generation ?: -1L).let { prior ->
            if (prior == Long.MAX_VALUE) throw DriveAssetTransferException("Drive transfer generation overflow")
            prior + 1L
        }
        val envelope = encodeEnvelope(generation, payload)
        if (envelope.size > RESERVATION_RECORD_MAX_BYTES) {
            throw DriveAssetTransferException("Drive transfer record exceeds its physical limit")
        }
        // One fixed, anchored temporary per storage root prevents crashed
        // writers from accumulating an unbounded UUID-named orphan set.  The
        // process/root lock makes this single scratch name safe across callers.
        val temporary = temporaryPath()
        if (resolver.exists(temporary)) {
            if (!resolver.isRegularFile(temporary) ||
                resolver.size(temporary, "Drive transfer stale staging") > RESERVATION_RECORD_MAX_BYTES
            ) {
                throw DriveAssetTransferException("Drive transfer staging evidence is invalid")
            }
            resolver.deletePath(temporary, "Drive transfer stale staging cleanup")
            forceDirectory()
        }
        var published = false
        try {
            resolver.openNewOutput(temporary, "Drive transfer record staging").use { channel ->
                val buffer = ByteBuffer.wrap(envelope)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            val staged = readFile(temporary, envelope.size, "Drive transfer record staging")
            if (!staged.contentEquals(envelope)) {
                throw DriveAssetTransferException("Drive transfer record staging read-back differs")
            }
            resolver.atomicMove(temporary, target, replaceExisting = false)
            published = true
            forceDirectory()
            val readBack = readSlot(target, targetSlot)
                ?: throw DriveAssetTransferException("Drive transfer record disappeared after publication")
            if (readBack !is SlotRead.Valid ||
                readBack.generation != generation ||
                !readBack.payload.contentEquals(payload)
            ) {
                throw DriveAssetTransferException("Drive transfer record read-back differs")
            }
        } catch (error: DriveAssetTransferException) {
            throw error
        } catch (error: IOException) {
            throw DriveAssetTransferException("Drive transfer record publication failed", error)
        } finally {
            // A failed pre-publication temporary belongs to this operation;
            // after publication the target is durable evidence and is never
            // guessed at or removed here.
            if (!published && resolver.exists(temporary)) {
                try {
                    resolver.deletePath(temporary, "Drive transfer staging cleanup")
                } catch (_: Throwable) {
                }
            }
        }
    }

    private sealed class SlotRead {
        data class Valid(val slot: Int, val generation: Long, val payload: ByteArray) : SlotRead()
        data object Absent : SlotRead()
        data class Invalid(val slot: Int, val error: Throwable) : SlotRead()
    }

    private fun readSlotsLocked(key: String): List<SlotRead> {
        validateStorageKey(key)
        val legacy = resolver.root.toPath().resolve("$key.json")
        resolver.ensureContained(legacy, "Drive transfer legacy record")
        if (resolver.exists(legacy)) {
            throw DriveAssetTransferException("unsupported Drive transfer record format")
        }
        return slotPaths(key).mapIndexed { index, path ->
            try {
                readSlot(path, index) ?: SlotRead.Absent
            } catch (error: Throwable) {
                SlotRead.Invalid(index, error)
            }
        }
    }

    private fun readSlot(path: Path, slot: Int): SlotRead? {
        resolver.ensureContained(path, "Drive transfer record")
        if (!resolver.exists(path)) return null
        if (!resolver.isRegularFile(path)) {
            throw DriveAssetTransferException("Drive transfer record is not a regular file")
        }
        val size = resolver.size(path, "Drive transfer record")
        if (size <= 0L || size > RESERVATION_RECORD_MAX_BYTES) {
            throw DriveAssetTransferException("Drive transfer record has an invalid size")
        }
        val envelope = readFile(path, size.toInt(), "Drive transfer record")
        val root = try {
            validateNoDuplicateJsonMembers(envelope, "Drive transfer record")
            JsonParser.parseString(decodeUtf8(envelope))
        } catch (error: Throwable) {
            throw DriveAssetTransferException("Drive transfer record is malformed", error)
        }
        if (!root.isJsonObject) throw DriveAssetTransferException("Drive transfer record root is invalid")
        val objectRoot = root.asJsonObject
        requireExactFields(objectRoot, setOf("magic", "version", "generation", "payload"), "Drive transfer record")
        val magic = requireString(objectRoot, "magic")
        val version = requireLong(objectRoot, "version")
        val generation = requireLong(objectRoot, "generation")
        val encodedPayload = requireString(objectRoot, "payload")
        if (magic != STORAGE_MAGIC || version != STORAGE_VERSION.toLong() || generation < 0L) {
            throw DriveAssetTransferException("Drive transfer record version is unsupported")
        }
        val payload = try {
            Base64.getUrlDecoder().decode(encodedPayload)
        } catch (error: IllegalArgumentException) {
            throw DriveAssetTransferException("Drive transfer record payload is invalid", error)
        }
        if (payload.size > RESERVATION_RECORD_MAX_BYTES) {
            throw DriveAssetTransferException("Drive transfer record payload exceeds its limit")
        }
        return SlotRead.Valid(slot, generation, payload)
    }

    private fun encodeEnvelope(generation: Long, payload: ByteArray): ByteArray {
        val root = JsonObject()
        root.addProperty("magic", STORAGE_MAGIC)
        root.addProperty("version", STORAGE_VERSION)
        root.addProperty("generation", generation)
        root.addProperty("payload", Base64.getUrlEncoder().withoutPadding().encodeToString(payload))
        return gson.toJson(root).toByteArray(Charsets.UTF_8)
    }

    private fun slotPaths(key: String): Array<Path> {
        validateStorageKey(key)
        return arrayOf(
            resolver.root.toPath().resolve(".$key.a").also {
                resolver.ensureContained(it, "Drive transfer record slot")
            },
            resolver.root.toPath().resolve(".$key.b").also {
                resolver.ensureContained(it, "Drive transfer record slot")
            }
        )
    }

    private fun temporaryPath(): Path = resolver.root.toPath().resolve(".drive-transfer.tmp").also {
        resolver.ensureContained(it, "Drive transfer staging")
    }

    private fun readFile(path: Path, expectedSize: Int, label: String): ByteArray {
        if (expectedSize < 0 || expectedSize > RESERVATION_RECORD_MAX_BYTES) {
            throw DriveAssetTransferException("$label exceeds its limit")
        }
        val bytes = ByteArray(expectedSize)
        resolver.openRead(path, label).use { input ->
            var offset = 0
            var zeroReads = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read < 0) throw DriveAssetTransferException("$label is truncated")
                if (read == 0) {
                    zeroReads++
                    if (zeroReads > Stage5Limits.MAX_ZERO_READS) {
                        throw DriveAssetTransferException("$label made no progress")
                    }
                    continue
                }
                zeroReads = 0
                offset += read
            }
            if (input.read() >= 0) throw DriveAssetTransferException("$label exceeds its declared size")
        }
        return bytes
    }

    private fun forceDirectory() {
        directoryForce?.let {
            it()
            return
        }
        try {
            FileChannel.open(root, StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        } catch (error: IOException) {
            throw DriveAssetTransferException("Drive transfer directory durability is unavailable", error)
        } catch (error: SecurityException) {
            throw DriveAssetTransferException("Drive transfer directory durability is protected", error)
        } catch (error: UnsupportedOperationException) {
            throw DriveAssetTransferException("Drive transfer directory durability is unsupported", error)
        }
    }

    private fun readReservationsLocked(): LinkedHashMap<String, DurableDriveResourceRecord> {
        val bytes = readLocked(RESERVATION_RECORD_KEY, DRIVE_TRANSFER_MAX_RESERVED_BYTES)
            ?: return LinkedHashMap()
        val root = try {
            validateNoDuplicateJsonMembers(bytes, "Drive resource reservations")
            JsonParser.parseString(decodeUtf8(bytes))
        } catch (error: Throwable) {
            if (error is DriveAssetTransferException) throw error
            throw DriveAssetTransferException("Drive resource reservations are malformed", error)
        }
        if (!root.isJsonObject) throw DriveAssetTransferException("Drive resource reservations root is invalid")
        val objectRoot = root.asJsonObject
        requireExactFields(objectRoot, setOf("schema", "resources"), "Drive resource reservations")
        if (requireLong(objectRoot, "schema") != RESERVATION_SCHEMA.toLong()) {
            throw DriveAssetTransferException("Drive resource reservation schema is unsupported")
        }
        val resourceElement = objectRoot["resources"]
            ?: throw DriveAssetTransferException("Drive resource reservations are missing")
        if (!resourceElement.isJsonObject) throw DriveAssetTransferException("Drive resource reservations are invalid")
        if (resourceElement.asJsonObject.size() > DRIVE_TRANSFER_MAX_RESERVED_RESOURCES) {
            throw DriveAssetTransferException("Drive resource reservation count exceeds its limit")
        }
        val records = LinkedHashMap<String, DurableDriveResourceRecord>()
        resourceElement.asJsonObject.entrySet().sortedBy { it.key }.forEach { (key, element) ->
            if (!key.matches(SHA256_REGEX)) throw DriveAssetTransferException("Drive resource reservation key is invalid")
            if (!element.isJsonObject) throw DriveAssetTransferException("Drive resource reservation entry is invalid")
            val entry = element.asJsonObject
            val reservationFields = setOf(
                "accountId", "backupRootId", "documentId", "sourceFingerprint",
                "parentFolderId", "resourceKind", "remoteId"
            )
            // Gson omits null object members unless its caller opted into
            // serializeNulls.  A missing sourceFingerprint is therefore the
            // canonical encoding of a null source; no other omission is
            // accepted.
            if (entry.keySet() != reservationFields &&
                entry.keySet() != reservationFields - "sourceFingerprint"
            ) {
                throw DriveAssetTransferException("Drive resource reservation entry fields are invalid")
            }
            val record = DurableDriveResourceRecord(
                accountId = requireString(entry, "accountId"),
                backupRootId = requireString(entry, "backupRootId"),
                documentId = requireString(entry, "documentId"),
                sourceFingerprint = entry["sourceFingerprint"]?.let { value ->
                    if (value.isJsonNull) null else requireStringElement(value, "sourceFingerprint")
                },
                parentFolderId = requireString(entry, "parentFolderId"),
                resourceKind = requireString(entry, "resourceKind"),
                remoteId = requireString(entry, "remoteId")
            )
            try {
                validateReservationRecord(key, record)
            } catch (error: DriveAssetTransferException) {
                throw error
            } catch (error: Throwable) {
                throw DriveAssetTransferException("Drive resource reservation entry is invalid", error)
            }
            records[key] = record
        }
        return records
    }

    private fun encodeReservations(records: Map<String, DurableDriveResourceRecord>): ByteArray {
        val root = JsonObject()
        root.addProperty("schema", RESERVATION_SCHEMA)
        val resources = JsonObject()
        records.toSortedMap().forEach { (key, record) ->
            validateReservationRecord(key, record)
            val entry = JsonObject()
            entry.addProperty("accountId", record.accountId)
            entry.addProperty("backupRootId", record.backupRootId)
            entry.addProperty("documentId", record.documentId)
            if (record.sourceFingerprint == null) entry.add("sourceFingerprint", com.google.gson.JsonNull.INSTANCE)
            else entry.addProperty("sourceFingerprint", record.sourceFingerprint)
            entry.addProperty("parentFolderId", record.parentFolderId)
            entry.addProperty("resourceKind", record.resourceKind)
            entry.addProperty("remoteId", record.remoteId)
            resources.add(key, entry)
        }
        root.add("resources", resources)
        return gson.toJson(root).toByteArray(Charsets.UTF_8)
    }

    private fun validateResourceIdentity(identity: DurableDriveResourceIdentity) {
        val scope = identity.scope
        require(scope.accountId.isNotBlank() && scope.accountId.length <= Stage5Limits.MAX_STRING_CHARS &&
            scope.accountId.none { it.code < 0x20 || it.code == 0x7f }
        ) {
            "Drive resource account identifier is invalid"
        }
        require(scope.backupRootId.matches(RESOURCE_ID_REGEX)) { "Drive resource backup root identifier is invalid" }
        require(scope.documentId.value.matches(RESOURCE_ID_REGEX)) { "Drive resource document identifier is invalid" }
        require(identity.parentFolderId.matches(RESOURCE_ID_REGEX)) { "Drive resource parent folder identifier is invalid" }
        require(identity.resourceKind == "document-folder" || identity.resourceKind == "document-manifest") {
            "Drive resource kind is unsupported"
        }
        identity.sourceFingerprint?.let(::validateSourceFingerprint)
    }

    private fun validateReservationRecord(key: String, record: DurableDriveResourceRecord) {
        require(key.matches(SHA256_REGEX)) { "Drive resource reservation key is invalid" }
        require(record.accountId.isNotBlank() && record.accountId.length <= Stage5Limits.MAX_STRING_CHARS &&
            record.accountId.none { it.code < 0x20 || it.code == 0x7f }) {
            "Drive resource reservation account is invalid"
        }
        require(record.backupRootId.matches(RESOURCE_ID_REGEX)) { "Drive resource reservation root is invalid" }
        require(record.documentId.matches(RESOURCE_ID_REGEX)) { "Drive resource reservation document is invalid" }
        require(record.parentFolderId.matches(RESOURCE_ID_REGEX)) { "Drive resource reservation parent is invalid" }
        require(record.resourceKind == "document-folder" || record.resourceKind == "document-manifest") {
            "Drive resource reservation kind is invalid"
        }
        record.sourceFingerprint?.let(::validateSourceFingerprint)
        require(record.remoteId.matches(RESOURCE_ID_REGEX)) { "Drive resource reservation ID is invalid" }
        val documentId = try {
            com.example.myapplication.stage2.DocumentId.parse(record.documentId)
        } catch (error: Throwable) {
            throw DriveAssetTransferException("Drive resource reservation document is invalid", error)
        }
        val identity = DurableDriveResourceIdentity(
            scope = SyncScope(record.accountId, record.backupRootId, documentId),
            sourceFingerprint = record.sourceFingerprint,
            parentFolderId = record.parentFolderId,
            resourceKind = record.resourceKind
        )
        if (reservationKey(identity) != key) {
            throw DriveAssetTransferException("Drive resource reservation identity does not match its key")
        }
    }

    private fun reservationKey(identity: DurableDriveResourceIdentity): String {
        val source = identity.sourceFingerprint ?: "<none>"
        val canonical = listOf(
            identity.scope.accountId,
            identity.scope.backupRootId,
            identity.scope.documentId.value,
            source,
            identity.parentFolderId,
            identity.resourceKind
        ).joinToString("\u0000")
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    private fun validateResourceId(id: String) {
        if (!id.matches(RESOURCE_ID_REGEX)) throw DriveAssetTransferException("Drive did not return a valid resource ID")
    }

    private fun validateSourceFingerprint(value: String) {
        val parts = value.split(':')
        require(parts.size == 3 && parts[0] == "SHA-256" &&
            parts[1].matches(SHA256_REGEX) && parts[2].matches(Regex("[0-9]+")) &&
            parts[2].toLongOrNull() != null && value.length <= Stage5Limits.MAX_STRING_CHARS
        ) { "Drive resource source fingerprint is invalid" }
    }

    private fun validateStorageKey(key: String) {
        require(key.matches(STORAGE_KEY_REGEX)) { "Drive transfer storage key is invalid" }
    }

    private fun requireExactFields(objectRoot: JsonObject, allowed: Set<String>, label: String) {
        val unknown = objectRoot.keySet() - allowed
        if (unknown.isNotEmpty() || objectRoot.keySet() != allowed) {
            throw DriveAssetTransferException("$label fields are invalid")
        }
    }

    private fun requireString(objectRoot: JsonObject, name: String): String =
        requireStringElement(objectRoot[name] ?: throw DriveAssetTransferException("$name is missing"), name)

    private fun requireStringElement(element: JsonElement, name: String): String {
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString || element.asString.isEmpty()) {
            throw DriveAssetTransferException("$name must be a non-empty string")
        }
        return element.asString
    }

    private fun requireLong(objectRoot: JsonObject, name: String): Long {
        val element = objectRoot[name] ?: throw DriveAssetTransferException("$name is missing")
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber ||
            !element.asString.matches(Regex("-?(0|[1-9][0-9]*)"))
        ) throw DriveAssetTransferException("$name must be an integer")
        return element.asString.toLongOrNull()
            ?: throw DriveAssetTransferException("$name is outside its numeric limit")
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw DriveAssetTransferException("Drive transfer record is not valid UTF-8", error)
    }
}

private const val MAX_RESERVATION_RECORD_BYTES = 4 * 1024

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
