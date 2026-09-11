package com.example.myapplication.stage9b

import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage4.*
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.sha256Hex
import com.example.myapplication.stage5.PhotoDocumentCriticalSections
import com.example.myapplication.stage5.validateNoDuplicateJsonMembers
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections

/** Durable compensation intent, not a second document/snapshot authority. */
internal data class DriveAdoptionRecovery(
    val scope: SyncScope,
    val candidate: RemoteAdoptionCandidate,
    val folderName: String,
    val originalFolderProperties: Map<String, String>,
    val originalManifestProperties: Map<String, String>,
    val originalAssetProperties: Map<String, Map<String, String>>,
    val originalManifestDigest: String,
    val adoptedManifestDigest: String,
    // Original observation, advanced only by a verified conditional compensation.
    val resumeManifestCursor: RemoteCursor,
    val resumeManifestEtag: String
)

/** One bounded record per account/root/document/source in existing anchored transfer storage. */
internal class DriveAdoptionRecoveryStore(
    private val storage: DurableDriveTransferStorage,
    private val accountId: String
) : AutoCloseable {
    companion object { private const val MAX_BYTES = 5 * 1024 * 1024 }
    private val gson = com.google.gson.GsonBuilder().serializeNulls().create()

    fun read(scope: SyncScope, source: SourceFingerprint): DriveAdoptionRecovery? {
        val bytes = storage.read(key(scope, source), MAX_BYTES) ?: return null
        return decode(bytes, scope, source)
    }

    private fun decode(bytes: ByteArray, scope: SyncScope, source: SourceFingerprint): DriveAdoptionRecovery {
        validateNoDuplicateJsonMembers(bytes, "Drive adoption recovery")
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val root = JsonParser.parseString(text)
        require(root.isJsonObject) { "Drive adoption recovery must be an object" }
        val json = root.asJsonObject
        require(json.keySet() == setOf("schema", "accountId", "backupRootId", "documentId", "remoteDocumentId",
            "sourceFingerprint", "folderId", "fileId", "displayName", "cursorRevision", "cursorModifiedTime",
            "candidateProperties", "folderName", "folderProperties", "manifestProperties", "assetProperties",
            "originalManifestDigest", "adoptedManifestDigest", "resumeRevision", "resumeModifiedTime", "resumeEtag")) { "Drive adoption recovery fields are invalid" }
        require(json["schema"].isJsonPrimitive && json["schema"].asJsonPrimitive.isNumber &&
            json["schema"].toString() == "1") { "unsupported Drive adoption recovery version" }
        require(string(json, "accountId") == scope.accountId && string(json, "backupRootId") == scope.backupRootId &&
            string(json, "documentId") == scope.documentId.value && string(json, "sourceFingerprint") == source.toDriveProperty()) {
            "Drive adoption recovery scope mismatch"
        }
        val folderId = string(json, "folderId").also(::requireId)
        val fileId = string(json, "fileId").also(::requireId)
        val modified = json["cursorModifiedTime"].let { value ->
            if (value.isJsonNull) null else {
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.matches(Regex("0|[1-9][0-9]*"))) {
                    "Drive adoption recovery cursor time is invalid"
                }
                value.asString.toLong().also { require(it >= 0L) }
            }
        }
        val candidate = RemoteAdoptionCandidate(scope.accountId, scope.backupRootId,
            DocumentId.parse(string(json, "remoteDocumentId")), source, string(json, "displayName"),
            RemoteReference(folderId, fileId, properties(json, "candidateProperties")),
            RemoteCursor(string(json, "cursorRevision"), modified))
        val assets = json["assetProperties"]
        require(assets.isJsonObject && assets.asJsonObject.size() <= Stage5Limits.MAX_REMOTE_DESCRIPTOR_COUNT) {
            "Drive adoption recovery assets are invalid"
        }
        val assetProperties = assets.asJsonObject.entrySet().associate { (id, _) ->
            requireId(id)
            id to properties(assets.asJsonObject, id)
        }
        return DriveAdoptionRecovery(scope, candidate, string(json, "folderName"),
            properties(json, "folderProperties"), properties(json, "manifestProperties"),
            Collections.unmodifiableMap(assetProperties), digest(json, "originalManifestDigest"), digest(json, "adoptedManifestDigest"),
            RemoteCursor(string(json, "resumeRevision"), nullableTime(json, "resumeModifiedTime")),
            string(json, "resumeEtag").also { require(it.length in 2..2048 && it.first() == '"' && it.last() == '"' &&
                it.substring(1, it.lastIndex).all { character -> character.code in 33..126 && character != '"' }) {
                "Drive adoption recovery ETag is invalid"
            } })
    }

    fun prepare(record: DriveAdoptionRecovery) = PhotoDocumentCriticalSections.withLock(storage.root) {
        val source = record.candidate.sourceFingerprint
        val existing = read(record.scope, source)
        require(existing == null || existing == record) { "another unresolved Drive adoption owns this recovery record" }
        if (existing != null) return@withLock
        writeVerified(record)
    }

    /** Caller must prove this exact revision was returned by its conditional compensation. */
    fun recordCompensation(expected: DriveAdoptionRecovery, cursor: RemoteCursor, etag: String) =
        PhotoDocumentCriticalSections.withLock(storage.root) {
            require(read(expected.scope, expected.candidate.sourceFingerprint) == expected) {
                "Drive adoption recovery changed before compensation receipt"
            }
            writeVerified(expected.copy(resumeManifestCursor = cursor, resumeManifestEtag = etag))
        }

    /** Caller has verified every resource is original and the explicit new cursor is current. */
    fun reselect(expected: DriveAdoptionRecovery, selected: RemoteAdoptionCandidate, etag: String): DriveAdoptionRecovery =
        PhotoDocumentCriticalSections.withLock(storage.root) {
            require(selected.copy(cursor = expected.candidate.cursor) == expected.candidate &&
                selected.cursor != expected.candidate.cursor) { "adoption reselection must retain resource identity" }
            require(read(expected.scope, expected.candidate.sourceFingerprint) == expected) {
                "Drive adoption recovery changed before reselection"
            }
            // Atomic replacement, not delete-then-prepare: a crash keeps either
            // the old intent or this fully verified new authorization.
            expected.copy(candidate = selected, resumeManifestCursor = selected.cursor, resumeManifestEtag = etag)
                .also(::writeVerified)
        }

    private fun writeVerified(record: DriveAdoptionRecovery) {
        val source = record.candidate.sourceFingerprint
        val candidate = record.candidate
        val json = JsonObject().apply {
            addProperty("schema", 1)
            addProperty("accountId", record.scope.accountId); addProperty("backupRootId", record.scope.backupRootId)
            addProperty("documentId", record.scope.documentId.value); addProperty("remoteDocumentId", candidate.remoteDocumentId.value)
            addProperty("sourceFingerprint", source.toDriveProperty())
            addProperty("folderId", candidate.reference.folderId); addProperty("fileId", candidate.reference.snapshotFileId)
            addProperty("displayName", candidate.displayName); addProperty("cursorRevision", candidate.cursor.revision)
            addProperty("cursorModifiedTime", candidate.cursor.modifiedTimeMillis?.toString())
            add("candidateProperties", gson.toJsonTree(candidate.reference.appProperties))
            addProperty("folderName", record.folderName)
            add("folderProperties", gson.toJsonTree(record.originalFolderProperties))
            add("manifestProperties", gson.toJsonTree(record.originalManifestProperties))
            add("assetProperties", gson.toJsonTree(record.originalAssetProperties))
            addProperty("originalManifestDigest", record.originalManifestDigest)
            addProperty("adoptedManifestDigest", record.adoptedManifestDigest)
            addProperty("resumeRevision", record.resumeManifestCursor.revision)
            addProperty("resumeModifiedTime", record.resumeManifestCursor.modifiedTimeMillis?.toString())
            addProperty("resumeEtag", record.resumeManifestEtag)
        }
        val bytes = gson.toJson(json).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Drive adoption recovery exceeds its limit" }
        require(decode(bytes, record.scope, source) == record) { "Drive adoption recovery is not canonical or valid" }
        storage.write(key(record.scope, source), bytes, MAX_BYTES)
        require(read(record.scope, source) == record) { "Drive adoption recovery readback differs" }
    }

    /** Only the first manifest PUT's definitive precondition rejection authorizes this no-op retirement. */
    fun retireRejected(expected: DriveAdoptionRecovery) = PhotoDocumentCriticalSections.withLock(storage.root) {
        require(read(expected.scope, expected.candidate.sourceFingerprint) == expected) {
            "Drive adoption recovery changed before rejected-write retirement"
        }
        storage.delete(key(expected.scope, expected.candidate.sourceFingerprint))
        require(read(expected.scope, expected.candidate.sourceFingerprint) == null) {
            "rejected Drive adoption recovery was not retired"
        }
    }

    /** A committed adoption can retire only after durable coordinator acceptance. */
    fun acknowledge(scope: SyncScope, candidate: RemoteAdoptionCandidate, remote: RemoteDocumentMetadata) =
        PhotoDocumentCriticalSections.withLock(storage.root) {
        val current = read(scope, candidate.sourceFingerprint) ?: return@withLock
        require(current.candidate == candidate && remote.scope == scope &&
            remote.reference.folderId == candidate.reference.folderId &&
            remote.reference.snapshotFileId == candidate.reference.snapshotFileId) {
            "Drive adoption acknowledgement does not own the recovery record"
        }
        storage.delete(key(scope, candidate.sourceFingerprint))
    }

    private fun key(scope: SyncScope, source: SourceFingerprint): String {
        require(scope.accountId == accountId) { "Drive adoption recovery account mismatch" }
        requireId(scope.backupRootId)
        require(scope.accountId.length <= Stage5Limits.MAX_STRING_CHARS && scope.accountId.none { it.code < 32 })
        return "adoption-" + sha256Hex(gson.toJson(listOf(scope.accountId, scope.backupRootId,
            scope.documentId.value, source.toDriveProperty())).toByteArray(StandardCharsets.UTF_8))
    }
    private fun requireId(id: String) { require(id.matches(Regex("[A-Za-z0-9_-]{1,512}"))) { "Drive recovery resource ID is invalid" } }
    private fun string(json: JsonObject, key: String): String {
        val value = json[key]
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString &&
            value.asString.length <= Stage5Limits.MAX_STRING_CHARS && value.asString.none { it.code < 32 }) {
            "Drive adoption recovery string is invalid: $key"
        }
        return value.asString
    }
    private fun nullableTime(json: JsonObject, key: String): Long? {
        val value = json[key]
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString &&
            value.asString.matches(Regex("0|[1-9][0-9]*"))) { "Drive adoption recovery time is invalid" }
        return value.asString.toLong().also { require(it >= 0L) }
    }

    private fun digest(json: JsonObject, key: String) = string(json, key).also {
        require(it.matches(Regex("[0-9a-f]{64}"))) { "Drive adoption recovery digest is invalid" }
    }
    private fun properties(json: JsonObject, key: String): Map<String, String> {
        val value = json[key]
        require(value != null && value.isJsonObject && value.asJsonObject.size() <= Stage5Limits.MAX_REMOTE_PROPERTIES) {
            "Drive adoption recovery properties are invalid"
        }
        return Collections.unmodifiableMap(value.asJsonObject.entrySet().associate { (name, _) ->
            require(name.length in 1..Stage5Limits.MAX_ID_CHARS && name.none { it.code < 32 })
            name to string(value.asJsonObject, name)
        })
    }
    override fun close() = storage.close()
}
