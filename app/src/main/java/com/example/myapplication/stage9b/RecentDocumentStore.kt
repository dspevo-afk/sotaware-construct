package com.example.myapplication.stage9b

import android.content.Context
import android.content.SharedPreferences
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentId
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets

/**
 * Current serialized format for the small recent-document index.
 *
 * This is deliberately a new preference file/key.  The old
 * `pdf_prefs/recent_uris` StringSet is legacy input and is not read, migrated,
 * or overwritten here.
 */
const val RECENT_DOCUMENT_STORE_SCHEMA_VERSION: Int = 1

/** The maximum number of successfully opened documents retained. */
const val MAX_RECENT_DOCUMENTS: Int = 20

/** Bound each identity/display field before it reaches SharedPreferences. */
const val MAX_RECENT_DOCUMENT_FIELD_CHARS: Int = 4_096

/** Bound the complete current-format value held in one preference entry. */
const val MAX_RECENT_DOCUMENT_SERIALIZED_BYTES: Int = 1 * 1024 * 1024

/** Dedicated storage names; neither name overlaps the legacy recent set. */
const val RECENT_DOCUMENT_PREFERENCES_NAME: String = "recent_documents_v1"
const val RECENT_DOCUMENT_PREFERENCES_KEY: String = "records"

/**
 * One successful open, identified by the app-owned [documentId] and the exact
 * source association used to open it.  The timestamp must be supplied by the
 * caller at the successful-open boundary; this type never invents one.
 *
 * [displayName] remains nullable because provider display metadata can be
 * absent.  A null value is preserved as null rather than being replaced with a
 * filename or an "Unknown" placeholder.
 */
data class RecentDocumentRecord(
    val documentId: DocumentId,
    val sourceUri: String,
    val displayName: String?,
    val lastSuccessfullyOpenedAtEpochMillis: Long
) {
    init {
        require(sourceUri.isNotBlank()) { "recent document source URI must not be blank" }
        require(sourceUri.length <= MAX_RECENT_DOCUMENT_FIELD_CHARS) {
            "recent document source URI is oversized"
        }
        displayName?.let { name ->
            require(name.isNotBlank()) { "recent document display name must not be blank" }
            require(name.length <= MAX_RECENT_DOCUMENT_FIELD_CHARS) {
                "recent document display name is oversized"
            }
        }
        require(lastSuccessfullyOpenedAtEpochMillis >= 0L) {
            "recent document timestamp must be non-negative"
        }
    }

    /** Stable pair used for deduplication; display names are not identity. */
    val documentSource: RecentDocumentIdentity
        get() = RecentDocumentIdentity(documentId, sourceUri)

    companion object {
        /** Build a record from the authoritative Stage 2 association. */
        fun fromAssociation(
            association: DocumentAssociation,
            lastSuccessfullyOpenedAtEpochMillis: Long
        ): RecentDocumentRecord = RecentDocumentRecord(
            documentId = association.documentId,
            sourceUri = association.source.sourceUri,
            displayName = association.source.displayName,
            lastSuccessfullyOpenedAtEpochMillis = lastSuccessfullyOpenedAtEpochMillis
        )

        /** Build a record from a canonical source identity and app-owned ID. */
        fun fromSource(
            documentId: DocumentId,
            source: DocumentSourceIdentityV1,
            lastSuccessfullyOpenedAtEpochMillis: Long
        ): RecentDocumentRecord = RecentDocumentRecord(
            documentId = documentId,
            sourceUri = source.sourceUri,
            displayName = source.displayName,
            lastSuccessfullyOpenedAtEpochMillis = lastSuccessfullyOpenedAtEpochMillis
        )
    }
}

/** The exact identity pair on which recent entries are deduplicated. */
data class RecentDocumentIdentity(
    val documentId: DocumentId,
    val sourceUri: String
)

/** Convenience bridge for the integration owner. */
fun DocumentAssociation.toRecentDocumentRecord(
    lastSuccessfullyOpenedAtEpochMillis: Long
): RecentDocumentRecord = RecentDocumentRecord.fromAssociation(
    association = this,
    lastSuccessfullyOpenedAtEpochMillis = lastSuccessfullyOpenedAtEpochMillis
)

/** Newest first, then stable identity fields for deterministic ties. */
private val RECENT_DOCUMENT_TIE_ORDER: Comparator<RecentDocumentRecord> =
    compareByDescending<RecentDocumentRecord> { it.lastSuccessfullyOpenedAtEpochMillis }
        .thenBy { it.documentId.value }
        .thenBy { it.sourceUri }
        .thenBy { it.displayName ?: "" }

/**
 * Normalize recent records deterministically: deduplicate by (DocumentId,
 * source URI), order newest first, and retain at most [maxEntries].
 *
 * Existing records are expected to have already crossed the typed constructor
 * boundary.  The function is intentionally side-effect free, so it is usable
 * by JVM tests and by non-Android callers.
 */
fun orderRecentDocumentRecords(
    records: Iterable<RecentDocumentRecord>,
    maxEntries: Int = MAX_RECENT_DOCUMENTS
): List<RecentDocumentRecord> {
    require(maxEntries in 1..MAX_RECENT_DOCUMENTS) {
        "recent document bound must be between 1 and $MAX_RECENT_DOCUMENTS"
    }

    val winners = LinkedHashMap<RecentDocumentIdentity, RecentDocumentRecord>()
    records.forEach { candidate ->
        val identity = candidate.documentSource
        val incumbent = winners[identity]
        if (incumbent == null || RECENT_DOCUMENT_TIE_ORDER.compare(candidate, incumbent) < 0) {
            // The comparator sorts newest first, so the first candidate is the
            // winner when it is newer or wins a deterministic tie.
            winners[identity] = candidate
        }
    }
    return winners.values
        .sortedWith(RECENT_DOCUMENT_TIE_ORDER)
        .take(maxEntries)
}

/**
 * Record one successful open without mutating the caller's collection.
 * Reopening the same document/source replaces its old timestamp and display
 * metadata; a different source or app-owned ID remains a distinct entry even
 * when the display names match.
 */
fun recordRecentDocument(
    existing: Iterable<RecentDocumentRecord>,
    opened: RecentDocumentRecord,
    maxEntries: Int = MAX_RECENT_DOCUMENTS
): List<RecentDocumentRecord> = orderRecentDocumentRecords(
    records = existing.filterNot { it.documentSource == opened.documentSource } + opened,
    maxEntries = maxEntries
)

/** Read result; a malformed current value is never represented as an empty list. */
sealed interface RecentDocumentReadResult {
    data class Loaded(val records: List<RecentDocumentRecord>) : RecentDocumentReadResult
    data class Failed(val error: RecentDocumentStoreError) : RecentDocumentReadResult
}

/** Write result; callers can distinguish a durable commit from a failed one. */
sealed interface RecentDocumentWriteResult {
    data object Committed : RecentDocumentWriteResult
    data class Failed(val error: RecentDocumentStoreError) : RecentDocumentWriteResult
}

/** Typed failures retained by both read and write paths. */
sealed interface RecentDocumentStoreError {
    data class ReadFailed(
        val detail: String?,
        val cause: Throwable? = null
    ) : RecentDocumentStoreError

    data class CorruptData(
        val detail: String,
        val cause: Throwable? = null
    ) : RecentDocumentStoreError

    data class UnsupportedSchema(
        val actualVersion: Int
    ) : RecentDocumentStoreError

    data class InvalidRecord(
        val detail: String,
        val cause: Throwable? = null
    ) : RecentDocumentStoreError

    data class WriteFailed(
        val detail: String?,
        val cause: Throwable? = null
    ) : RecentDocumentStoreError
}

/** Small seam for pure JVM tests and for an adapter without a framework fake. */
interface RecentDocumentStoreStorage {
    fun readSerialized(): String?

    /** Return true only when the complete replacement is durably committed. */
    fun commitSerialized(serialized: String): Boolean
}

/** Operations exposed to UI/session integration without prescribing its owner. */
interface RecentDocumentStore {
    fun read(): RecentDocumentReadResult

    fun record(opened: RecentDocumentRecord): RecentDocumentWriteResult
}

/**
 * Current-format recent storage backed by an injected durable string store.
 * The public SharedPreferences constructor below is the Android production
 * adapter; injection keeps ordering and failure behavior directly testable on
 * the JVM.
 */
class SharedPreferencesRecentDocumentStore(
    private val storage: RecentDocumentStoreStorage,
    private val maxEntries: Int = MAX_RECENT_DOCUMENTS
) : RecentDocumentStore {
    private val lock = STORE_LOCK
    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .serializeNulls()
        .create()

    init {
        require(maxEntries in 1..MAX_RECENT_DOCUMENTS) {
            "recent document bound must be between 1 and $MAX_RECENT_DOCUMENTS"
        }
    }

    constructor(
        preferences: SharedPreferences,
        preferenceKey: String = RECENT_DOCUMENT_PREFERENCES_KEY,
        maxEntries: Int = MAX_RECENT_DOCUMENTS
    ) : this(
        storage = SharedPreferencesRecentDocumentStorage(preferences, preferenceKey),
        maxEntries = maxEntries
    )

    constructor(
        context: Context,
        preferencesName: String = RECENT_DOCUMENT_PREFERENCES_NAME,
        preferenceKey: String = RECENT_DOCUMENT_PREFERENCES_KEY,
        maxEntries: Int = MAX_RECENT_DOCUMENTS
    ) : this(
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
        preferenceKey = preferenceKey,
        maxEntries = maxEntries
    )

    override fun read(): RecentDocumentReadResult = synchronized(lock) {
        readLocked()
    }

    override fun record(opened: RecentDocumentRecord): RecentDocumentWriteResult = synchronized(lock) {
        recordLocked(opened)
    }

    private fun recordLocked(opened: RecentDocumentRecord): RecentDocumentWriteResult {
        val existingResult = readLocked()
        val existing = when (existingResult) {
            is RecentDocumentReadResult.Loaded -> existingResult.records
            is RecentDocumentReadResult.Failed ->
                return RecentDocumentWriteResult.Failed(existingResult.error)
        }

        val next = try {
            recordRecentDocument(existing, opened, maxEntries)
        } catch (error: IllegalArgumentException) {
            return RecentDocumentWriteResult.Failed(
                RecentDocumentStoreError.InvalidRecord(error.message ?: "invalid recent document", error)
            )
        }
        val serialized = try {
            encode(next)
        } catch (error: IllegalArgumentException) {
            return RecentDocumentWriteResult.Failed(
                RecentDocumentStoreError.WriteFailed(
                    detail = error.message ?: "recent document value is not serializable",
                    cause = error
                )
            )
        }

        val committed = try {
            storage.commitSerialized(serialized)
        } catch (error: Exception) {
            return RecentDocumentWriteResult.Failed(
                RecentDocumentStoreError.WriteFailed(error.message, error)
            )
        }
        return if (!committed) {
            RecentDocumentWriteResult.Failed(
                RecentDocumentStoreError.WriteFailed("durable recent document commit returned false")
            )
        } else {
            RecentDocumentWriteResult.Committed
        }
    }

    private fun readLocked(): RecentDocumentReadResult {
        val serialized = try {
            storage.readSerialized()
        } catch (error: Exception) {
            return RecentDocumentReadResult.Failed(
                RecentDocumentStoreError.ReadFailed(error.message, error)
            )
        } ?: return RecentDocumentReadResult.Loaded(emptyList())

        return try {
            RecentDocumentReadResult.Loaded(decode(serialized))
        } catch (error: UnsupportedRecentDocumentSchemaException) {
            RecentDocumentReadResult.Failed(
                RecentDocumentStoreError.UnsupportedSchema(error.actualVersion)
            )
        } catch (error: Exception) {
            RecentDocumentReadResult.Failed(
                RecentDocumentStoreError.CorruptData(
                    detail = error.message ?: "recent document value is corrupt",
                    cause = error
                )
            )
        }
    }

    private fun encode(records: List<RecentDocumentRecord>): String {
        require(records.size <= maxEntries) { "recent document count exceeds bound" }
        val envelope = RecentDocumentEnvelope(
            schemaVersion = RECENT_DOCUMENT_STORE_SCHEMA_VERSION,
            records = records.map { record ->
                RecentDocumentSerializedRecord(
                    documentId = record.documentId.value,
                    sourceUri = record.sourceUri,
                    displayName = record.displayName,
                    lastSuccessfullyOpenedAtEpochMillis = record.lastSuccessfullyOpenedAtEpochMillis
                )
            }
        )
        val json = gson.toJson(envelope)
        require(json.toByteArray(StandardCharsets.UTF_8).size <= MAX_RECENT_DOCUMENT_SERIALIZED_BYTES) {
            "recent document value exceeds serialized byte bound"
        }
        return json
    }

    private fun decode(serialized: String): List<RecentDocumentRecord> {
        val bytes = serialized.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty()) { "recent document value is empty" }
        require(bytes.size <= MAX_RECENT_DOCUMENT_SERIALIZED_BYTES) {
            "recent document value exceeds serialized byte bound"
        }
        com.example.myapplication.stage5.validateNoDuplicateJsonMembers(bytes, "recent document value")
        val root = JsonParser.parseString(serialized)
        require(root.isJsonObject) { "recent document value must be a JSON object" }
        val objectValue = root.asJsonObject
        requireExactKeys(objectValue, setOf("schemaVersion", "records"), "recent document envelope")
        val schemaVersion = requireInteger(objectValue.get("schemaVersion"), "schemaVersion")
            .toIntOrNull()
            ?: throw IllegalArgumentException("schemaVersion is outside Int range")
        if (schemaVersion != RECENT_DOCUMENT_STORE_SCHEMA_VERSION) {
            throw UnsupportedRecentDocumentSchemaException(schemaVersion)
        }

        val recordsElement = objectValue.get("records")
        require(recordsElement != null && recordsElement.isJsonArray) {
            "recent document records must be an array"
        }
        val recordsArray = recordsElement.asJsonArray
        require(recordsArray.size() <= maxEntries) {
            "recent document count exceeds bound"
        }

        val records = recordsArray.mapIndexed { index, element ->
            require(element.isJsonObject) { "recent document record $index must be an object" }
            val recordObject = element.asJsonObject
            requireExactKeys(
                recordObject,
                setOf("documentId", "sourceUri", "displayName", "lastSuccessfullyOpenedAtEpochMillis"),
                "recent document record $index"
            )
            val documentId = DocumentId.parse(
                requireString(recordObject.get("documentId"), "recent document record $index documentId")
            )
            val sourceUri = requireString(
                recordObject.get("sourceUri"),
                "recent document record $index sourceUri"
            )
            val displayNameElement = recordObject.get("displayName")
            val displayName = when {
                displayNameElement == null ->
                    throw IllegalArgumentException("recent document record $index displayName is missing")
                displayNameElement.isJsonNull -> null
                else -> requireString(
                    displayNameElement,
                    "recent document record $index displayName"
                )
            }
            val timestamp = requireInteger(
                recordObject.get("lastSuccessfullyOpenedAtEpochMillis"),
                "recent document record $index timestamp"
            )
                .toLongOrNull()
                ?: throw IllegalArgumentException("recent document record $index timestamp is outside Long range")
            RecentDocumentRecord(documentId, sourceUri, displayName, timestamp)
        }

        val identities = HashSet<RecentDocumentIdentity>()
        records.forEach { record ->
            require(identities.add(record.documentSource)) {
                "recent document records contain a duplicate document/source"
            }
        }
        return orderRecentDocumentRecords(records, maxEntries)
    }

    private fun requireExactKeys(objectValue: JsonObject, allowed: Set<String>, label: String) {
        objectValue.keySet().firstOrNull { it !in allowed }?.let { unknown ->
            throw IllegalArgumentException("$label has unsupported field: $unknown")
        }
        allowed.firstOrNull { !objectValue.has(it) }?.let { missing ->
            throw IllegalArgumentException("$label is missing field: $missing")
        }
    }

    private fun requireString(element: JsonElement?, label: String): String {
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            "$label must be a string"
        }
        return element.asString.also { value ->
            require(value.isNotBlank()) { "$label must not be blank" }
            require(value.length <= MAX_RECENT_DOCUMENT_FIELD_CHARS) {
                "$label is oversized"
            }
        }
    }

    private fun requireInteger(element: JsonElement?, label: String): String {
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
            "$label must be an integer"
        }
        val text = element.asString
        require(INTEGER_REGEX.matches(text)) { "$label must be an integer" }
        return text
    }

    private data class RecentDocumentEnvelope(
        val schemaVersion: Int,
        val records: List<RecentDocumentSerializedRecord>
    )

    private data class RecentDocumentSerializedRecord(
        val documentId: String,
        val sourceUri: String,
        val displayName: String?,
        val lastSuccessfullyOpenedAtEpochMillis: Long
    )

    private class UnsupportedRecentDocumentSchemaException(val actualVersion: Int) : Exception()

    private companion object {
        val INTEGER_REGEX = Regex("-?(0|[1-9][0-9]*)")

        val STORE_LOCK = Any()
    }
}

/** Android storage seam: one dedicated key and synchronous atomic commit(). */
private class SharedPreferencesRecentDocumentStorage(
    private val preferences: SharedPreferences,
    private val preferenceKey: String
) : RecentDocumentStoreStorage {
    init {
        require(preferenceKey.isNotBlank()) { "recent document preference key must not be blank" }
    }

    override fun readSerialized(): String? = preferences.getString(preferenceKey, null)

    override fun commitSerialized(serialized: String): Boolean = preferences.edit()
        .putString(preferenceKey, serialized)
        .commit()
}
