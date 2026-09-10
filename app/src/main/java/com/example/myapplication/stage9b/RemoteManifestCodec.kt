package com.example.myapplication.stage9b

import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage5.ImageInfo
import com.example.myapplication.stage5.PhotoDescriptor
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.requiredPhotoNames
import com.example.myapplication.stage5.validatePhotoFileName
import com.example.myapplication.stage5.validateSnapshot
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale

/** Current Drive manifest wire version.  Older payloads are never migrated. */
const val DRIVE_MANIFEST_SCHEMA_VERSION: Int = 3

/** The immutable descriptor published for one occurrence filename. */
data class RemoteAssetDescriptor(
    val remoteAssetId: String,
    val byteCount: Long,
    val sha256: String,
    val mimeType: String,
    val width: Int,
    val height: Int
) {
    init {
        require(remoteAssetId.isNotBlank()) { "remoteAssetId must not be blank" }
        require(remoteAssetId.length <= MAX_REMOTE_ASSET_ID_CHARS) {
            "remoteAssetId exceeds its limit"
        }
        require(remoteAssetId.matches(REMOTE_ASSET_ID_REGEX)) {
            "remoteAssetId contains unsupported characters"
        }
        require(byteCount in 1L..Stage5Limits.MAX_PHOTO_BYTES.toLong()) {
            "remote asset byteCount is outside its limit"
        }
        require(sha256.matches(SHA256_REGEX)) { "remote asset SHA-256 is invalid" }
        ImageInfo(mimeType, width, height)
    }

    fun asPhotoDescriptor(): PhotoDescriptor = PhotoDescriptor(
        byteCount = byteCount,
        sha256 = sha256,
        mimeType = mimeType,
        width = width,
        height = height
    )

    companion object {
        const val MAX_REMOTE_ASSET_ID_CHARS: Int = 512
        private val REMOTE_ASSET_ID_REGEX = Regex("[A-Za-z0-9_-]{1,$MAX_REMOTE_ASSET_ID_CHARS}")
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")
    }
}

/** A validated, current-format remote manifest. */
data class RemoteManifest(
    val scope: SyncScope,
    val displayName: String,
    val snapshot: DocumentSnapshotV1,
    val snapshotDigest: String,
    val assets: Map<String, RemoteAssetDescriptor>,
    val sourceFingerprint: SourceFingerprint? = null
) {
    init {
        requireWireString(scope.accountId, "manifest accountId")
        requireWireString(scope.backupRootId, "manifest backupRootId")
        requireWireString(displayName, "manifest displayName")
        require(snapshot.schemaVersion == DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION) {
            "manifest snapshot schema is unsupported"
        }
        require(snapshotDigest.matches(Regex("[0-9a-f]{64}"))) {
            "manifest snapshotDigest is invalid"
        }
        require(snapshotDigest == RemoteManifestCodec.snapshotDigest(snapshot)) {
            "manifest snapshotDigest does not match canonical snapshot"
        }
        require(assets.size <= Stage5Limits.MAX_REMOTE_DESCRIPTOR_COUNT) {
            "manifest asset count exceeds its limit"
        }
        assets.keys.forEach(::validatePhotoFileName)
        require(assets.keys == requiredPhotoNames(snapshot)) {
            "manifest asset keys do not exactly match snapshot references"
        }
        // A stable remote ID denotes one immutable byte object.  Repeating it
        // for an occurrence is valid only when the complete descriptor agrees;
        // otherwise a later occurrence could reinterpret the same Drive file
        // with a different MIME, size, or image geometry.
        val descriptorsByRemoteId = HashMap<String, RemoteAssetDescriptor>()
        assets.values.forEach { descriptor ->
            val prior = descriptorsByRemoteId.putIfAbsent(descriptor.remoteAssetId, descriptor)
            require(prior == null || prior == descriptor) {
                "manifest remoteAssetId maps to conflicting descriptors"
            }
        }
        var total = 0L
        assets.values.forEach { descriptor ->
            total = try {
                Math.addExact(total, descriptor.byteCount)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("manifest asset byte count overflow")
            }
            require(total <= Stage5Limits.MAX_TOTAL_PHOTO_BYTES) {
                "manifest asset bytes exceed the aggregate limit"
            }
        }
    }

    private fun requireWireString(value: String, label: String) {
        require(value.isNotBlank() && value.length <= Stage5Limits.MAX_STRING_CHARS) {
            "$label is blank or oversized"
        }
        require(value.none { it.code < 0x20 || it.code == 0x7f }) {
            "$label contains a control character"
        }
    }
}

/** Result of decoding a manifest, retaining its canonical digest for readback. */
data class DecodedRemoteManifest(
    val manifest: RemoteManifest,
    val canonicalBytes: ByteArray,
    val canonicalDigest: String
)

class RemoteManifestValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Strict current-format Drive manifest codec.  This boundary deliberately does
 * not call the old Drive payload parser: v0/v1/v2 payloads are rejected before
 * any remote mutation and the original bytes remain untouched by callers.
 */
object RemoteManifestCodec {
    private const val FIELD_MANIFEST_VERSION = "manifestVersion"
    private const val FIELD_ACCOUNT_ID = "accountId"
    private const val FIELD_BACKUP_ROOT_ID = "backupRootId"
    private const val FIELD_DOCUMENT_ID = "documentId"
    private const val FIELD_DISPLAY_NAME = "displayName"
    private const val FIELD_SOURCE_FINGERPRINT = "sourceFingerprint"
    private const val FIELD_SNAPSHOT = "snapshot"
    private const val FIELD_SNAPSHOT_DIGEST = "snapshotDigest"
    private const val FIELD_ASSETS = "assets"

    private const val FIELD_REMOTE_ASSET_ID = "remoteAssetId"
    private const val FIELD_BYTE_COUNT = "byteCount"
    private const val FIELD_SHA256 = "sha256"
    private const val FIELD_MIME_TYPE = "mimeType"
    private const val FIELD_WIDTH = "width"
    private const val FIELD_HEIGHT = "height"

    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()

    private val manifestFields = setOf(
        FIELD_MANIFEST_VERSION,
        FIELD_ACCOUNT_ID,
        FIELD_BACKUP_ROOT_ID,
        FIELD_DOCUMENT_ID,
        FIELD_DISPLAY_NAME,
        FIELD_SOURCE_FINGERPRINT,
        FIELD_SNAPSHOT,
        FIELD_SNAPSHOT_DIGEST,
        FIELD_ASSETS
    )
    private val descriptorFields = setOf(
        FIELD_REMOTE_ASSET_ID,
        FIELD_BYTE_COUNT,
        FIELD_SHA256,
        FIELD_MIME_TYPE,
        FIELD_WIDTH,
        FIELD_HEIGHT
    )

    /** Canonical digest of the snapshot JSON, independent of manifest metadata. */
    fun snapshotDigest(snapshot: DocumentSnapshotV1): String {
        try {
            validateSnapshot(snapshot)
        } catch (error: Throwable) {
            throw RemoteManifestValidationException("snapshot validation failed", error)
        }
        return sha256Hex(canonicalJsonBytes(gson.toJsonTree(snapshot)))
    }

    /** Canonical digest of an already encoded manifest. */
    fun canonicalDigest(bytes: ByteArray): String = sha256Hex(bytes)

    /** Encode a complete current-format manifest with no inline asset bytes. */
    fun encode(
        scope: SyncScope,
        displayName: String,
        snapshot: DocumentSnapshotV1,
        assets: Map<String, RemoteAssetDescriptor>,
        sourceFingerprint: SourceFingerprint? = null
    ): ByteArray {
        val digest = snapshotDigest(snapshot)
        val manifest = try {
            RemoteManifest(scope, displayName, snapshot, digest, LinkedHashMap(assets), sourceFingerprint)
        } catch (error: IllegalArgumentException) {
            throw RemoteManifestValidationException("manifest validation failed", error)
        }
        val root = JsonObject()
        root.addProperty(FIELD_MANIFEST_VERSION, DRIVE_MANIFEST_SCHEMA_VERSION)
        root.addProperty(FIELD_ACCOUNT_ID, manifest.scope.accountId)
        root.addProperty(FIELD_BACKUP_ROOT_ID, manifest.scope.backupRootId)
        root.addProperty(FIELD_DOCUMENT_ID, manifest.scope.documentId.value)
        root.addProperty(FIELD_DISPLAY_NAME, manifest.displayName)
        manifest.sourceFingerprint?.let { root.addProperty(FIELD_SOURCE_FINGERPRINT, it.toWireValue()) }
        root.add(FIELD_SNAPSHOT, canonicalJsonElement(gson.toJsonTree(manifest.snapshot)))
        root.addProperty(FIELD_SNAPSHOT_DIGEST, manifest.snapshotDigest)
        val assetsObject = JsonObject()
        manifest.assets.toSortedMap().forEach { (name, descriptor) ->
            val value = JsonObject()
            value.addProperty(FIELD_REMOTE_ASSET_ID, descriptor.remoteAssetId)
            value.addProperty(FIELD_BYTE_COUNT, descriptor.byteCount)
            value.addProperty(FIELD_SHA256, descriptor.sha256)
            value.addProperty(FIELD_MIME_TYPE, descriptor.mimeType)
            value.addProperty(FIELD_WIDTH, descriptor.width)
            value.addProperty(FIELD_HEIGHT, descriptor.height)
            assetsObject.add(name, value)
        }
        root.add(FIELD_ASSETS, assetsObject)
        val result = canonicalJsonBytes(root)
        require(result.size <= Stage5Limits.MAX_JSON_BYTES) {
            "Drive manifest exceeds JSON limit"
        }
        return result
    }

    /** Convenience overload for callers that already have a [RemoteManifest]. */
    fun encode(manifest: RemoteManifest): ByteArray = encode(
        scope = manifest.scope,
        displayName = manifest.displayName,
        snapshot = manifest.snapshot,
        assets = manifest.assets,
        sourceFingerprint = manifest.sourceFingerprint
    )

    /**
     * Decode and validate a v3 manifest.  [expectedScope] is required at remote
     * boundaries; omitting it is useful only for local tests/inspection.
     */
    fun decode(
        bytes: ByteArray,
        expectedScope: SyncScope? = null,
        expectedSourceFingerprint: SourceFingerprint? = null
    ): DecodedRemoteManifest {
        if (bytes.isEmpty() || bytes.size > Stage5Limits.MAX_JSON_BYTES) {
            throw RemoteManifestValidationException("Drive manifest size is outside its limit")
        }
        val root = try {
            readStrictJson(bytes)
        } catch (error: Throwable) {
            if (error is RemoteManifestValidationException) throw error
            throw RemoteManifestValidationException("Drive manifest JSON is malformed", error)
        }
        if (!root.isJsonObject) throw RemoteManifestValidationException("Drive manifest root must be an object")
        val jsonObject = root.asJsonObject
        requireExactFields(
            jsonObject,
            manifestFields,
            required = setOf(
                FIELD_MANIFEST_VERSION,
                FIELD_ACCOUNT_ID,
                FIELD_BACKUP_ROOT_ID,
                FIELD_DOCUMENT_ID,
                FIELD_DISPLAY_NAME,
                FIELD_SNAPSHOT,
                FIELD_SNAPSHOT_DIGEST,
                FIELD_ASSETS
            ),
            label = "manifest"
        )
        val version = requireInt(jsonObject, FIELD_MANIFEST_VERSION, 0, Int.MAX_VALUE)
        if (version != DRIVE_MANIFEST_SCHEMA_VERSION) {
            throw RemoteManifestValidationException(
                "unsupported Drive manifest version $version; input was not modified"
            )
        }
        val accountId = requireString(jsonObject, FIELD_ACCOUNT_ID, 1, Stage5Limits.MAX_STRING_CHARS)
        val backupRootId = requireString(jsonObject, FIELD_BACKUP_ROOT_ID, 1, Stage5Limits.MAX_STRING_CHARS)
        val documentValue = requireString(jsonObject, FIELD_DOCUMENT_ID, 1, Stage5Limits.MAX_ID_CHARS)
        val documentId = try {
            DocumentId.parse(documentValue)
        } catch (error: IllegalArgumentException) {
            throw RemoteManifestValidationException("manifest documentId is invalid", error)
        }
        val displayName = requireString(jsonObject, FIELD_DISPLAY_NAME, 1, Stage5Limits.MAX_STRING_CHARS)
        val sourceFingerprint = jsonObject[FIELD_SOURCE_FINGERPRINT]?.let {
            requireStringElement(it, "manifest.$FIELD_SOURCE_FINGERPRINT", 1, Stage5Limits.MAX_STRING_CHARS)
            parseSourceFingerprint(it.asString)
        }
        if (expectedSourceFingerprint != null) {
            if (sourceFingerprint?.canonicalKey() != expectedSourceFingerprint.canonicalKey()) {
                throw RemoteManifestValidationException("manifest source fingerprint does not match scope")
            }
        }
        val scope = SyncScope(accountId, backupRootId, documentId)
        if (expectedScope != null && scope != expectedScope) {
            throw RemoteManifestValidationException("manifest scope does not match requested remote scope")
        }
        val snapshotElement = jsonObject[FIELD_SNAPSHOT]
        if (snapshotElement == null || !snapshotElement.isJsonObject) {
            throw RemoteManifestValidationException("manifest snapshot must be an object")
        }
        val snapshot = try {
            // Validate raw required fields, types, IDs and aggregate budgets
            // before Gson can supply primitive defaults or allocate DTO lists.
            com.example.myapplication.stage5.validateCanonicalSnapshotTree(
                snapshotElement.asJsonObject, "manifest.snapshot"
            )
            gson.fromJson(snapshotElement, DocumentSnapshotV1::class.java)
                ?: throw IllegalArgumentException("manifest snapshot is null")
        } catch (error: Throwable) {
            throw RemoteManifestValidationException("manifest snapshot cannot be materialized", error)
        }
        try {
            validateSnapshot(snapshot)
        } catch (error: Throwable) {
            throw RemoteManifestValidationException("manifest snapshot validation failed", error)
        }
        // Gson intentionally ignores unknown object members.  A remote
        // manifest is a strict current-format boundary, so compare the raw
        // snapshot tree with the materialized canonical tree and reject any
        // member that would otherwise be silently discarded.
        // Compare wire-normalized numbers on both sides. A DTO Float such
        // as 0.1f has a different Double value from parsed JSON 0.1 even
        // though Gson writes the same canonical decimal. Comparing mixed
        // JsonPrimitive number implementations would reject our own output.
        val materializedWireTree = JsonParser.parseString(gson.toJson(snapshot))
        if (canonicalJsonElement(snapshotElement) != canonicalJsonElement(materializedWireTree)) {
            throw RemoteManifestValidationException("manifest snapshot contains unsupported fields")
        }
        val snapshotDigest = requireString(jsonObject, FIELD_SNAPSHOT_DIGEST, 64, 64)
        if (!snapshotDigest.matches(Regex("[0-9a-f]{64}"))) {
            throw RemoteManifestValidationException("manifest snapshotDigest must be lowercase SHA-256")
        }
        if (snapshotDigest(snapshot) != snapshotDigest) {
            throw RemoteManifestValidationException("manifest snapshotDigest does not match canonical snapshot")
        }
        val assetsElement = jsonObject[FIELD_ASSETS]
        if (assetsElement == null || !assetsElement.isJsonObject) {
            throw RemoteManifestValidationException("manifest assets must be an object")
        }
        val assets = parseAssets(assetsElement.asJsonObject, snapshot)
        val manifest = try {
            RemoteManifest(scope, displayName, snapshot, snapshotDigest, assets, sourceFingerprint)
        } catch (error: IllegalArgumentException) {
            throw RemoteManifestValidationException("manifest asset validation failed", error)
        }
        val canonical = encode(manifest)
        // Whitespace/order is intentionally not a trust boundary.  The digest
        // returned here is always the canonical bytes used by publication
        // readback, while callers can compare their received bytes separately.
        return DecodedRemoteManifest(manifest, canonical, sha256Hex(canonical))
    }

    private fun parseAssets(jsonObject: JsonObject, snapshot: DocumentSnapshotV1): Map<String, RemoteAssetDescriptor> {
        if (jsonObject.size() > Stage5Limits.MAX_REMOTE_DESCRIPTOR_COUNT) {
            throw RemoteManifestValidationException("manifest asset count exceeds its limit")
        }
        val expectedNames = requiredPhotoNames(snapshot)
        val result = LinkedHashMap<String, RemoteAssetDescriptor>()
        jsonObject.entrySet().forEach { (name, value) ->
            try {
                validatePhotoFileName(name)
            } catch (error: IllegalArgumentException) {
                throw RemoteManifestValidationException("manifest asset filename is invalid: $name", error)
            }
            if (!value.isJsonObject) {
                throw RemoteManifestValidationException("manifest asset '$name' must be an object")
            }
            val descriptor = value.asJsonObject
            requireExactFields(descriptor, descriptorFields, descriptorFields, "manifest asset '$name'")
            val remoteAssetId = requireString(
                descriptor,
                FIELD_REMOTE_ASSET_ID,
                1,
                RemoteAssetDescriptor.MAX_REMOTE_ASSET_ID_CHARS
            )
            val byteCount = requireLong(
                descriptor,
                FIELD_BYTE_COUNT,
                1L,
                Stage5Limits.MAX_PHOTO_BYTES.toLong()
            )
            val sha256 = requireString(descriptor, FIELD_SHA256, 64, 64)
            if (!sha256.matches(Regex("[0-9a-f]{64}"))) {
                throw RemoteManifestValidationException("manifest asset '$name' SHA-256 is invalid")
            }
            val mimeType = requireString(descriptor, FIELD_MIME_TYPE, 1, 64)
            if (mimeType !in ImageInfo.APPROVED_IMAGE_MIME_TYPES) {
                throw RemoteManifestValidationException("manifest asset '$name' MIME type is invalid")
            }
            val width = requireInt(descriptor, FIELD_WIDTH, 1, Stage5Limits.MAX_IMAGE_WIDTH)
            val height = requireInt(descriptor, FIELD_HEIGHT, 1, Stage5Limits.MAX_IMAGE_HEIGHT)
            if (width.toLong() * height.toLong() > Stage5Limits.MAX_IMAGE_PIXELS) {
                throw RemoteManifestValidationException("manifest asset '$name' dimensions exceed limit")
            }
            result[name] = try {
                RemoteAssetDescriptor(remoteAssetId, byteCount, sha256, mimeType, width, height)
            } catch (error: IllegalArgumentException) {
                throw RemoteManifestValidationException("manifest asset '$name' is invalid", error)
            }
        }
        if (result.keys != expectedNames) {
            throw RemoteManifestValidationException("manifest assets do not exactly match snapshot references")
        }
        return result
    }

    private fun requireExactFields(
        jsonObject: JsonObject,
        allowed: Set<String>,
        required: Set<String>,
        label: String
    ) {
        val unknown = jsonObject.keySet() - allowed
        if (unknown.isNotEmpty()) throw RemoteManifestValidationException("$label has unknown fields: ${unknown.sorted()}")
        val missing = required - jsonObject.keySet()
        if (missing.isNotEmpty()) throw RemoteManifestValidationException("$label is missing fields: ${missing.sorted()}")
    }

    private fun requireString(jsonObject: JsonObject, name: String, min: Int, max: Int): String =
        requireStringElement(jsonObject[name] ?: throw RemoteManifestValidationException("missing field '$name'"), name, min, max)

    private fun requireStringElement(element: JsonElement, label: String, min: Int, max: Int): String {
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw RemoteManifestValidationException("$label must be a JSON string")
        }
        val value = element.asString
        if (value.length !in min..max || value.any { it.code < 0x20 || it.code == 0x7f }) {
            throw RemoteManifestValidationException("$label is blank, oversized, or contains a control character")
        }
        return value
    }

    private fun requireInt(jsonObject: JsonObject, name: String, min: Int, max: Int): Int {
        val raw = requireIntegerToken(jsonObject[name] ?: throw RemoteManifestValidationException("missing field '$name'"), name)
        val value = raw.toLongOrNull() ?: throw RemoteManifestValidationException("$name is outside integer range")
        if (value < min || value > max) throw RemoteManifestValidationException("$name is outside its range")
        return value.toInt()
    }

    private fun requireLong(jsonObject: JsonObject, name: String, min: Long, max: Long): Long {
        val raw = requireIntegerToken(jsonObject[name] ?: throw RemoteManifestValidationException("missing field '$name'"), name)
        val value = raw.toLongOrNull() ?: throw RemoteManifestValidationException("$name is outside long range")
        if (value < min || value > max) throw RemoteManifestValidationException("$name is outside its range")
        return value
    }

    private fun requireIntegerToken(element: JsonElement, label: String): String {
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw RemoteManifestValidationException("$label must be a JSON integer")
        }
        val raw = element.asString
        if (!raw.matches(Regex("-?(0|[1-9][0-9]*)"))) {
            throw RemoteManifestValidationException("$label must be a finite integer")
        }
        return raw
    }

    private fun parseSourceFingerprint(value: String): SourceFingerprint {
        val parts = value.split(':')
        if (parts.size != 3 || parts[0] != SourceFingerprint.SHA256_ALGORITHM ||
            !parts[2].matches(Regex("[0-9]+"))
        ) {
            throw RemoteManifestValidationException("manifest sourceFingerprint has an invalid shape")
        }
        return try {
            SourceFingerprint(
                SourceFingerprint.SHA256_ALGORITHM,
                parts[1].lowercase(Locale.ROOT),
                parts[2].toLong()
            )
        } catch (error: IllegalArgumentException) {
            throw RemoteManifestValidationException("manifest sourceFingerprint is invalid", error)
        }
    }

    private fun SourceFingerprint.toWireValue(): String =
        "${SourceFingerprint.SHA256_ALGORITHM}:${digestHex.lowercase(Locale.ROOT)}:${byteCount}"

    private fun SourceFingerprint.canonicalKey(): String =
        "${SourceFingerprint.SHA256_ALGORITHM}:${digestHex.lowercase(Locale.ROOT)}:${byteCount}"

    /** Duplicate-name rejecting parser built on Gson's strict JsonReader. */
    private fun readStrictJson(bytes: ByteArray): JsonElement {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw RemoteManifestValidationException("Drive manifest is not valid UTF-8", error)
        }
        val reader = JsonReader(text.reader())
        reader.isLenient = false
        val element = readElement(reader, 0)
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw RemoteManifestValidationException("trailing data after manifest JSON")
        }
        return element
    }

    private fun readElement(reader: JsonReader, depth: Int): JsonElement {
        if (depth > Stage5Limits.MAX_JSON_DEPTH) throw RemoteManifestValidationException("manifest JSON is too deep")
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val jsonObject = JsonObject()
                val names = HashSet<String>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (!names.add(name)) throw RemoteManifestValidationException("duplicate JSON field '$name'")
                    jsonObject.add(name, readElement(reader, depth + 1))
                }
                reader.endObject()
                jsonObject
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                val array = JsonArray()
                while (reader.hasNext()) array.add(readElement(reader, depth + 1))
                reader.endArray()
                array
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> {
                val raw = reader.nextString()
                if (raw.equals("NaN", true) || raw.equals("Infinity", true) || raw.equals("-Infinity", true)) {
                    throw RemoteManifestValidationException("non-finite JSON number")
                }
                try {
                    JsonParser.parseString(raw)
                } catch (error: Throwable) {
                    throw RemoteManifestValidationException("invalid JSON number", error)
                }
            }
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                com.google.gson.JsonNull.INSTANCE
            }
            else -> throw RemoteManifestValidationException("unexpected JSON token ${reader.peek()}")
        }
    }

    private fun canonicalJsonBytes(element: JsonElement): ByteArray =
        gson.toJson(canonicalJsonElement(element)).toByteArray(StandardCharsets.UTF_8)

    private fun canonicalJsonElement(element: JsonElement): JsonElement = when {
        element.isJsonObject -> {
            val sorted = JsonObject()
            element.asJsonObject.entrySet().sortedBy { it.key }.forEach { (key, value) ->
                sorted.add(key, canonicalJsonElement(value))
            }
            sorted
        }
        element.isJsonArray -> JsonArray().also { output ->
            element.asJsonArray.forEach { output.add(canonicalJsonElement(it)) }
        }
        else -> element
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}
