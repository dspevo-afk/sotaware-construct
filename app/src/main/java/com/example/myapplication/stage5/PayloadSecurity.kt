package com.example.myapplication.stage5

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage8.AnnotationModelV2
import com.example.myapplication.stage8.validAnnotationId
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PhotoImageNoteSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage1.PointSnapshotV1
import com.example.myapplication.stage1.ShapeSnapshotV1
import com.example.myapplication.stage7.BitmapBudgetPolicy
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.LinkedHashSet
import java.util.Locale

/**
 * Stage 5 limits are intentionally centralized so every untrusted boundary
 * shares the same finite resource budget.  The 64 MiB JSON limit is above the
 * current exports but rejects the committed 64 MiB+ adversarial fixture.
 */
object Stage5Limits {
    const val MAX_JSON_BYTES: Int = 64 * 1024 * 1024
    const val MAX_METADATA_BYTES: Int = 8 * 1024 * 1024
    const val MAX_JSON_DEPTH: Int = 64
    const val MAX_ZERO_READS: Int = 16
    const val MAX_CAPTURE_AGE_MILLIS: Long = 24L * 60L * 60L * 1000L
    const val MAX_PHOTO_PUBLICATION_RESERVATION_MILLIS: Long = 5L * 60L * 1000L
    const val MAX_PAGES: Int = 8_192
    const val MAX_ANNOTATIONS_PER_PAGE: Int = 10_000
    const val MAX_PATH_POINTS: Int = 100_000
    const val MAX_PHOTO_PINS_PER_PAGE: Int = 512
    const val MAX_PHOTOS_PER_PIN: Int = 128
    const val MAX_TOTAL_PHOTOS: Int = 2_048
    const val MAX_PHOTO_DIRECTORY_ENTRIES: Int = MAX_TOTAL_PHOTOS * 2
    const val MAX_STRING_CHARS: Int = 4_096
    const val MAX_TEXT_CHARS: Int = 32 * 1024
    const val MAX_ID_CHARS: Int = 256
    const val MAX_PROVIDER_PROPERTIES: Int = 64
    const val MAX_PHOTO_BYTES: Int = 25 * 1024 * 1024
    const val MAX_TOTAL_PHOTO_BYTES: Long = 100L * 1024L * 1024L
    const val MAX_IMAGE_WIDTH: Int = 10_000
    const val MAX_IMAGE_HEIGHT: Int = 10_000
    const val MAX_IMAGE_PIXELS: Long = 25_000_000L
    const val MAX_REMOTE_DESCRIPTOR_COUNT: Int = MAX_TOTAL_PHOTOS
    const val MAX_REMOTE_PROPERTIES: Int = 64
    const val MAX_NUMERIC_ABS: Float = 100_000_000f
    const val MAX_RATIO: Float = 1f
}


private val PENDING_UPLOAD_REASON_NAMES = setOf(
    "IMMEDIATE",
    "DEBOUNCED",
    "MANUAL",
    "PERIODIC",
    "PHOTO",
    "IMPORT",
    "LIFECYCLE"
)

private val PENDING_UPLOAD_INTENT_NAMES = setOf(
    "AUTOMATIC_RETRY",
    "EXPLICIT_CONFLICT_REPLAY"
)

private val WINDOWS_DEVICE_BASENAMES = setOf(
    "CON", "PRN", "AUX", "NUL",
    "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
    "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
)

class Stage5ValidationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

fun requireBoundedString(
    value: String?,
    label: String,
    required: Boolean = false,
    maxChars: Int = Stage5Limits.MAX_STRING_CHARS
): String? {
    if (value == null) {
        if (required) throw Stage5ValidationException("$label is missing")
        return null
    }
    if (value.length > maxChars) throw Stage5ValidationException("$label exceeds $maxChars characters")
    if (required && value.isBlank()) throw Stage5ValidationException("$label is blank")
    return value
}

/** Validates the wire form used to bind a payload to its source revision. */
fun validateSourceFingerprintProperty(value: String?, label: String): String? {
    if (value == null) return null
    val parts = value.split(':')
    if (parts.size != 3 || !parts[0].equals("SHA-256", ignoreCase = true) ||
        !parts[1].matches(Regex("[0-9a-fA-F]{64}")) || parts[2].toLongOrNull()?.let { it >= 0L } != true
    ) {
        throw Stage5ValidationException("$label is not a valid SHA-256 source fingerprint")
    }
    return value
}

/** Parse a bounded object before any Gson DTO can materialize default values. */
fun parseBoundedJsonObject(input: InputStream, maxBytes: Int, label: String): JsonObject {
    val bytes = readBoundedBytes(input, maxBytes, label)
    val text = decodeUtf8Bytes(bytes, label)
    validateNoDuplicateJsonMembers(bytes, label)
    val root = try {
        JsonParser.parseString(text)
    } catch (error: JsonParseException) {
        throw Stage5ValidationException("$label is malformed", error)
    } catch (error: IllegalStateException) {
        throw Stage5ValidationException("$label is malformed", error)
    }
    if (!root.isJsonObject) throw Stage5ValidationException("$label root must be an object")
    return root.asJsonObject
}

/**
 * Strictly walks a bounded JSON byte sequence before Gson's tree parser can
 * discard duplicate object members. This applies recursively to every object
 * and array, and also rejects malformed or trailing content.
 */
fun validateNoDuplicateJsonMembers(bytes: ByteArray, label: String) {
    try {
        JsonReader(InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8)).use { reader ->
            reader.isLenient = false
            scanJsonValue(reader, label, depth = 0)
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw Stage5ValidationException("$label has trailing JSON content")
            }
        }
    } catch (error: Stage5ValidationException) {
        throw error
    } catch (error: IOException) {
        throw Stage5ValidationException("$label is malformed", error)
    } catch (error: IllegalStateException) {
        throw Stage5ValidationException("$label is malformed", error)
    }
}

private fun scanJsonValue(reader: JsonReader, label: String, depth: Int) {
    if (depth > Stage5Limits.MAX_JSON_DEPTH) {
        throw Stage5ValidationException("$label exceeds JSON nesting depth")
    }
    when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            reader.beginObject()
            val names = HashSet<String>()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (!names.add(name)) {
                    throw Stage5ValidationException("$label contains duplicate JSON member: ${name.take(128)}")
                }
                scanJsonValue(reader, label, depth + 1)
            }
            reader.endObject()
        }
        JsonToken.BEGIN_ARRAY -> {
            reader.beginArray()
            while (reader.hasNext()) scanJsonValue(reader, label, depth + 1)
            reader.endArray()
        }
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> reader.nextNull()
        else -> throw Stage5ValidationException("$label contains an invalid JSON value")
    }
}

/**
 * Validates the complete canonical snapshot wire tree before Gson constructs
 * DocumentSnapshotV1. Required primitive fields are checked here because Gson
 * can otherwise silently supply Kotlin/JVM defaults for absent values.
 */
fun validateCanonicalSnapshotTree(snapshot: JsonObject, label: String = "snapshot") {
    rejectUnknownFields(snapshot, setOf("schemaVersion", "snapshotRevision", "source", "pages"), label)
    requireInt(snapshot, "schemaVersion", label, exact = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION)
    requireLong(snapshot, "snapshotRevision", label, min = 0L)

    val source = requireObject(snapshot, "source", label)
    rejectUnknownFields(source, setOf("sourceUri", "displayName", "providerMetadata"), "$label.source")
    requireString(source, "sourceUri", "$label.source", required = true, maxChars = Stage5Limits.MAX_STRING_CHARS)
    optionalString(source, "displayName", "$label.source", Stage5Limits.MAX_STRING_CHARS)
    val providerMetadata = requireObject(source, "providerMetadata", "$label.source")
    if (providerMetadata.size() > Stage5Limits.MAX_PROVIDER_PROPERTIES) {
        throw Stage5ValidationException("$label.source.providerMetadata exceeds its entry limit")
    }
    providerMetadata.entrySet().forEach { (key, value) ->
        if (key.isBlank() || key.length > Stage5Limits.MAX_STRING_CHARS) {
            throw Stage5ValidationException("$label.source.providerMetadata contains an unsafe key")
        }
        requireStringElement(value, "$label.source.providerMetadata[$key]", Stage5Limits.MAX_STRING_CHARS)
    }

    val pages = requireObject(snapshot, "pages", label)
    if (pages.size() > Stage5Limits.MAX_PAGES) {
        throw Stage5ValidationException("$label.pages exceeds its page limit")
    }
    var photoReferenceCount = 0L
    pages.entrySet().forEach { (pageKey, pageElement) ->
        val pageIndex = pageKey.toIntOrNull()
            ?: throw Stage5ValidationException("$label.pages contains a non-integer page key")
        if (pageIndex < 0 || pageIndex >= 1_000_000 || pageKey != pageIndex.toString()) {
            throw Stage5ValidationException("$label.pages contains an out-of-range page key")
        }
        val page = requireObjectElement(pageElement, "$label.pages[$pageKey]")
        photoReferenceCount = validatePageTree(page, "$label.pages[$pageKey]", photoReferenceCount)
    }
}

/** Validates the bounded metadata envelope before its typed DTO is built. */
fun validateSyncMetadataTree(root: JsonObject) {
    val allowed = setOf(
        "schemaVersion", "accountId", "backupRootId", "documentId",
        "remoteFolderId", "remoteSnapshotFileId", "remoteAppProperties",
        "acceptedRevision", "acceptedModifiedTimeMillis", "conflictRevision",
        "conflictModifiedTimeMillis", "conflictDetail", "adoptedRemoteDocumentId",
        "pendingAdoptionRemoteDocumentId", "pendingAdoptionSourceFingerprint",
        "pendingAdoptionDisplayName", "pendingAdoptionFolderId",
        "pendingAdoptionSnapshotFileId", "pendingAdoptionAppProperties",
        "pendingAdoptionRevision", "pendingAdoptionModifiedTimeMillis",
        "pendingUploadReason", "pendingUploadIntent", "pendingUploadSourceUri", "pendingUploadSourceFingerprint",
        "pendingUploadGeneration", "pendingUploadExpectedRevision",
        "pendingUploadExpectedModifiedTimeMillis", "pendingUploadPhotoSidecar"
    )
    rejectUnknownFields(root, allowed, "sync metadata")
    allowed.forEach { name ->
        if (root.has(name) && root.get(name).isJsonNull) {
            throw Stage5ValidationException("sync metadata $name must be omitted instead of null")
        }
    }
    requireInt(root, "schemaVersion", "sync metadata", exact = 2)
    requireString(root, "accountId", "sync metadata", required = true, maxChars = Stage5Limits.MAX_STRING_CHARS)
    requireString(root, "backupRootId", "sync metadata", required = true, maxChars = Stage5Limits.MAX_STRING_CHARS)
    requireString(root, "documentId", "sync metadata", required = true, maxChars = Stage5Limits.MAX_ID_CHARS)

    val remoteFolderId = optionalString(root, "remoteFolderId", "sync metadata", Stage5Limits.MAX_STRING_CHARS)
    val remoteSnapshotFileId = optionalString(root, "remoteSnapshotFileId", "sync metadata", Stage5Limits.MAX_STRING_CHARS)
    val remoteAppProperties = optionalObject(root, "remoteAppProperties", "sync metadata")
    val remoteReferencePresent = remoteFolderId != null || remoteSnapshotFileId != null || remoteAppProperties != null
    if (remoteReferencePresent) {
        if (remoteFolderId.isNullOrBlank() || remoteSnapshotFileId.isNullOrBlank() || remoteAppProperties == null) {
            throw Stage5ValidationException("sync metadata remote reference is incomplete")
        }
        requireMetadataProperties(
            remoteAppProperties,
            "sync metadata remoteAppProperties",
            requiredDocumentId = requireString(root, "documentId", "sync metadata", required = true, maxChars = Stage5Limits.MAX_ID_CHARS)
        )
    }

    val acceptedRevision = optionalString(root, "acceptedRevision", "sync metadata", Stage5Limits.MAX_STRING_CHARS)
    val conflictRevision = optionalString(root, "conflictRevision", "sync metadata", Stage5Limits.MAX_STRING_CHARS)
    val acceptedTimePresent = root.has("acceptedModifiedTimeMillis")
    val conflictTimePresent = root.has("conflictModifiedTimeMillis")
    if (acceptedRevision == null && acceptedTimePresent) {
        throw Stage5ValidationException("sync metadata accepted cursor time has no revision")
    }
    if (conflictRevision == null && conflictTimePresent) {
        throw Stage5ValidationException("sync metadata conflict cursor time has no revision")
    }

    val pendingAdoptionFields = listOf(
        "pendingAdoptionRemoteDocumentId", "pendingAdoptionSourceFingerprint", "pendingAdoptionDisplayName",
        "pendingAdoptionFolderId", "pendingAdoptionSnapshotFileId", "pendingAdoptionAppProperties",
        "pendingAdoptionRevision", "pendingAdoptionModifiedTimeMillis"
    )
    val pendingAdoptionPresent = pendingAdoptionFields.any { root.has(it) }
    val pendingAdoptionRemoteDocumentId = optionalString(root, "pendingAdoptionRemoteDocumentId", "sync metadata", Stage5Limits.MAX_STRING_CHARS)
    val pendingAdoptionSourceFingerprint = optionalString(root, "pendingAdoptionSourceFingerprint", "sync metadata", Stage5Limits.MAX_STRING_CHARS)
    val pendingAdoptionDisplayName = optionalString(root, "pendingAdoptionDisplayName", "sync metadata", Stage5Limits.MAX_STRING_CHARS)
    val pendingAdoptionFolderId = optionalString(root, "pendingAdoptionFolderId", "sync metadata", Stage5Limits.MAX_STRING_CHARS)
    val pendingAdoptionSnapshotFileId = optionalString(root, "pendingAdoptionSnapshotFileId", "sync metadata", Stage5Limits.MAX_STRING_CHARS)
    val pendingAdoptionRevision = optionalString(root, "pendingAdoptionRevision", "sync metadata", Stage5Limits.MAX_STRING_CHARS)
    val pendingAdoptionAppProperties = optionalObject(root, "pendingAdoptionAppProperties", "sync metadata")
    if (pendingAdoptionPresent) {
        if (pendingAdoptionRemoteDocumentId.isNullOrBlank() ||
            pendingAdoptionSourceFingerprint.isNullOrBlank() ||
            pendingAdoptionDisplayName.isNullOrBlank() ||
            pendingAdoptionFolderId.isNullOrBlank() ||
            pendingAdoptionSnapshotFileId.isNullOrBlank() ||
            pendingAdoptionRevision.isNullOrBlank() ||
            pendingAdoptionAppProperties == null
        ) {
            throw Stage5ValidationException("sync metadata pending adoption group is incomplete")
        }
        validateSourceFingerprintProperty(pendingAdoptionSourceFingerprint, "pending adoption fingerprint")
        requireMetadataProperties(
            pendingAdoptionAppProperties,
            "sync metadata pendingAdoptionAppProperties",
            requiredDocumentId = pendingAdoptionRemoteDocumentId
        )
    }
    if (!pendingAdoptionPresent && root.has("pendingAdoptionModifiedTimeMillis")) {
        throw Stage5ValidationException("sync metadata pending adoption time has no adoption group")
    }
    optionalLongField(root, "pendingAdoptionModifiedTimeMillis", "sync metadata")

    val pendingUploadFields = listOf(
        "pendingUploadReason", "pendingUploadIntent", "pendingUploadSourceUri", "pendingUploadSourceFingerprint",
        "pendingUploadGeneration", "pendingUploadExpectedRevision", "pendingUploadExpectedModifiedTimeMillis",
        "pendingUploadPhotoSidecar"
    )
    val pendingUploadPresent = pendingUploadFields.any { root.has(it) }
    val pendingUploadReason = optionalString(root, "pendingUploadReason", "sync metadata", Stage5Limits.MAX_STRING_CHARS)
    if (pendingUploadReason != null && pendingUploadReason !in PENDING_UPLOAD_REASON_NAMES) {
        throw Stage5ValidationException("sync metadata pending upload reason is invalid: $pendingUploadReason")
    }
    val pendingUploadIntent = optionalString(root, "pendingUploadIntent", "sync metadata", Stage5Limits.MAX_STRING_CHARS)
    if (pendingUploadIntent != null && pendingUploadIntent !in PENDING_UPLOAD_INTENT_NAMES) {
        throw Stage5ValidationException("sync metadata pending upload intent is invalid: $pendingUploadIntent")
    }
    val pendingUploadSourceUri = optionalString(root, "pendingUploadSourceUri", "sync metadata", Stage5Limits.MAX_STRING_CHARS)
    val pendingUploadSourceFingerprint = optionalString(root, "pendingUploadSourceFingerprint", "sync metadata", Stage5Limits.MAX_STRING_CHARS)
    val pendingUploadPhotoSidecar = optionalObject(root, "pendingUploadPhotoSidecar", "sync metadata")
    val pendingUploadExpectedRevision = optionalString(root, "pendingUploadExpectedRevision", "sync metadata", Stage5Limits.MAX_STRING_CHARS)
    if (pendingUploadPresent) {
        if (pendingUploadReason == null || pendingUploadIntent == null || pendingUploadSourceUri.isNullOrBlank() ||
            !root.has("pendingUploadGeneration") || pendingUploadPhotoSidecar == null
        ) {
            throw Stage5ValidationException("sync metadata pending upload group is incomplete")
        }
        pendingUploadSourceFingerprint?.let {
            validateSourceFingerprintProperty(it, "pending upload fingerprint")
        }
        if (root.has("pendingUploadExpectedModifiedTimeMillis") && pendingUploadExpectedRevision == null) {
            throw Stage5ValidationException("sync metadata pending upload expected time has no revision")
        }
        validatePendingUploadPhotoSidecar(requireNotNull(pendingUploadPhotoSidecar))

    } else if (pendingUploadIntent != null || pendingUploadSourceFingerprint != null || pendingUploadExpectedRevision != null ||
        root.has("pendingUploadExpectedModifiedTimeMillis")
    ) {
        throw Stage5ValidationException("sync metadata contains orphaned pending upload fields")
    }

    listOf(
        "remoteFolderId", "remoteSnapshotFileId", "acceptedRevision", "conflictRevision",
        "conflictDetail", "adoptedRemoteDocumentId", "pendingAdoptionRemoteDocumentId",
        "pendingAdoptionSourceFingerprint", "pendingAdoptionDisplayName",
        "pendingAdoptionFolderId", "pendingAdoptionSnapshotFileId", "pendingAdoptionRevision",
        "pendingUploadSourceUri", "pendingUploadSourceFingerprint",
        "pendingUploadIntent",
        "pendingUploadExpectedRevision"
    ).forEach { name ->
        optionalString(root, name, "sync metadata", if (name == "conflictDetail") Stage5Limits.MAX_TEXT_CHARS else Stage5Limits.MAX_STRING_CHARS)
    }
    optionalLongField(root, "acceptedModifiedTimeMillis", "sync metadata")
    optionalLongField(root, "conflictModifiedTimeMillis", "sync metadata")
    optionalLongField(root, "pendingAdoptionModifiedTimeMillis", "sync metadata")
    optionalLongField(root, "pendingUploadGeneration", "sync metadata", min = 1L)
    optionalLongField(root, "pendingUploadExpectedModifiedTimeMillis", "sync metadata")

    listOf("remoteAppProperties", "pendingAdoptionAppProperties").forEach { name ->
        val objectValue = optionalObject(root, name, "sync metadata") ?: return@forEach
        if (objectValue.size() > Stage5Limits.MAX_REMOTE_PROPERTIES) {
            throw Stage5ValidationException("sync metadata $name exceeds its entry limit")
        }
        objectValue.entrySet().forEach { (key, value) ->
            requireBoundedString(key, "sync metadata $name key", required = true)
            requireStringElement(value, "sync metadata $name[$key]", Stage5Limits.MAX_STRING_CHARS)
        }
    }
}

private fun validatePendingUploadPhotoSidecar(sidecar: JsonObject) {
    val allowed = setOf(
        "schemaVersion", "contentId", "manifestSha256", "snapshotSha256", "snapshotByteCount",
        "photoCount", "totalPhotoBytes"
    )
    rejectUnknownFields(sidecar, allowed, "sync metadata pendingUploadPhotoSidecar")
    sidecar.keySet().forEach { key ->
        if (sidecar.get(key).isJsonNull) {
            throw Stage5ValidationException("sync metadata pendingUploadPhotoSidecar.$key must not be null")
        }
    }
    requireInt(
        sidecar,
        "schemaVersion",
        "sync metadata pendingUploadPhotoSidecar",
        exact = 3
    )
    listOf("contentId", "manifestSha256", "snapshotSha256").forEach { name ->
        val value = requireString(
            sidecar,
            name,
            "sync metadata pendingUploadPhotoSidecar",
            required = true,
            maxChars = 64
        )
        if (!value.matches(Regex("[0-9a-f]{64}"))) {
            throw Stage5ValidationException("sync metadata pendingUploadPhotoSidecar.$name is invalid")
        }
    }
    requireLong(
        sidecar,
        "snapshotByteCount",
        "sync metadata pendingUploadPhotoSidecar",
        min = 1L,
        max = Stage5Limits.MAX_JSON_BYTES.toLong()
    )
    val photoCount = requireInt(
        sidecar,
        "photoCount",
        "sync metadata pendingUploadPhotoSidecar",
        min = 0,
        max = Stage5Limits.MAX_TOTAL_PHOTOS
    )
    val totalPhotoBytes = requireLong(
        sidecar,
        "totalPhotoBytes",
        "sync metadata pendingUploadPhotoSidecar",
        min = 0L,
        max = Stage5Limits.MAX_TOTAL_PHOTO_BYTES
    )
    if ((photoCount == 0 && totalPhotoBytes != 0L) ||
        (photoCount > 0 && totalPhotoBytes < photoCount.toLong())
    ) {
        throw Stage5ValidationException("sync metadata pendingUploadPhotoSidecar byte count is invalid")
    }
}

private fun requireMetadataProperties(
    properties: JsonObject,
    label: String,
    requiredDocumentId: String
) {
    if (properties.size() > Stage5Limits.MAX_REMOTE_PROPERTIES) {
        throw Stage5ValidationException("$label exceeds its entry limit")
    }
    properties.entrySet().forEach { (key, value) ->
        requireBoundedString(key, "$label key", required = true)
        requireStringElement(value, "$label[$key]", Stage5Limits.MAX_STRING_CHARS)
    }
    if (properties.get("sotaware_document_id")?.asString != requiredDocumentId) {
        throw Stage5ValidationException("$label is missing its document identity property")
    }
}

private fun requiredPhotoNamesFromTree(snapshot: JsonObject): Set<String> {
    val names = LinkedHashSet<String>()
    val pages = requireObject(snapshot, "pages", "snapshot")
    pages.entrySet().forEach { (pageKey, pageElement) ->
        val page = requireObjectElement(pageElement, "snapshot.pages[$pageKey]")
        val pins = requireArray(page, "photoPins", "snapshot.pages[$pageKey]")
        pins.forEachIndexed { index, pinElement ->
            val pin = requireObjectElement(pinElement, "snapshot.pages[$pageKey].photoPins[$index]")
            val files = requireArray(pin, "imageFileNames", "snapshot.pages[$pageKey].photoPins[$index]")
            files.forEachIndexed { fileIndex, nameElement ->
                val name = requireStringElement(nameElement, "snapshot photo[$fileIndex]", Stage5Limits.MAX_STRING_CHARS)
                if (!names.add(name)) {
                    // Shared references across pins are valid; validatePhotoSet
                    // and the canonical tree have already rejected duplicates
                    // within a pin and bounded the document-wide reference set.
                }
            }
        }
    }
    return names
}

private fun optionalLongField(objectValue: JsonObject, name: String, label: String, min: Long = 0L) {
    if (objectValue.has(name) && !objectValue.get(name).isJsonNull) {
        requireLong(objectValue, name, label, min = min)
    }
}

/** Decode a pending or other canonical snapshot only after tree validation. */
fun decodeValidatedSnapshotJson(gson: Gson, json: String, label: String): DocumentSnapshotV1 {
    val bytes = boundedUtf8Bytes(json, Stage5Limits.MAX_JSON_BYTES, label)
    val root = parseBoundedJsonObject(ByteArrayInputStream(bytes), Stage5Limits.MAX_JSON_BYTES, label)
    validateCanonicalSnapshotTree(root, label)
    val snapshot = try {
        gson.fromJson(root, DocumentSnapshotV1::class.java)
            ?: throw Stage5ValidationException("$label is missing")
    } catch (error: Stage5ValidationException) {
        throw error
    } catch (error: JsonParseException) {
        throw Stage5ValidationException("$label could not be materialized", error)
    } catch (error: IllegalStateException) {
        throw Stage5ValidationException("$label could not be materialized", error)
    }
    validateSnapshot(snapshot)
    return snapshot
}

private fun validatePageTree(page: JsonObject, label: String, photoReferenceCount: Long): Long {
    rejectUnknownFields(page, setOf("paths", "measurements", "notes", "photoPins", "scale", "shapes"), label)
    val paths = requireArray(page, "paths", label)
    val measurements = requireArray(page, "measurements", label)
    val notes = requireArray(page, "notes", label)
    val photoPins = requireArray(page, "photoPins", label)
    val shapes = requireArray(page, "shapes", label)
    val scale = optionalObject(page, "scale", label)
    val annotationBudget = AnnotationBudget(label)
    annotationBudget.add(paths.size(), "paths")
    annotationBudget.add(measurements.size(), "measurements")
    annotationBudget.add(notes.size(), "notes")
    annotationBudget.add(photoPins.size(), "photo pins")
    annotationBudget.add(shapes.size(), "shapes")
    if (scale != null) annotationBudget.add(1, "scale")
    if (paths.size() > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE ||
        measurements.size() > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE ||
        notes.size() > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE ||
        shapes.size() > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE
    ) throw Stage5ValidationException("$label annotation list exceeds its limit")
    if (photoPins.size() > Stage5Limits.MAX_PHOTO_PINS_PER_PAGE) {
        throw Stage5ValidationException("$label photo pin count exceeds its limit")
    }
    paths.forEachIndexed { index, value -> validatePathTree(requireObjectElement(value, "$label.paths[$index]"), "$label.paths[$index]") }
    measurements.forEachIndexed { index, value -> validateMeasurementTree(requireObjectElement(value, "$label.measurements[$index]"), "$label.measurements[$index]") }
    notes.forEachIndexed { index, value -> validateNoteTree(requireObjectElement(value, "$label.notes[$index]"), "$label.notes[$index]") }
    var cumulativePhotoReferences = photoReferenceCount
    photoPins.forEachIndexed { index, value ->
        cumulativePhotoReferences = validatePhotoPinTree(
            requireObjectElement(value, "$label.photoPins[$index]"),
            "$label.photoPins[$index]",
            cumulativePhotoReferences,
            annotationBudget
        )
    }
    shapes.forEachIndexed { index, value -> validateShapeTree(requireObjectElement(value, "$label.shapes[$index]"), "$label.shapes[$index]") }
    scale?.let { validateScaleTree(it, "$label.scale") }
    validateUniqueTreeIds(listOf(paths, measurements, notes, photoPins, shapes), label)
    return cumulativePhotoReferences
}

private fun validatePathTree(path: JsonObject, label: String) {
    rejectUnknownFields(path, setOf("points", "colorArgb", "strokeWidthRatio", "isHighlighter", "id"), label)
    val points = requireArray(path, "points", label)
    if (points.size() !in 1..Stage5Limits.MAX_PATH_POINTS) throw Stage5ValidationException("$label point count is invalid")
    requireInt(path, "colorArgb", label)
    requireFloat(path, "strokeWidthRatio", label, min = Float.MIN_VALUE, max = Stage5Limits.MAX_RATIO)
    requireBoolean(path, "isHighlighter", label)
    requireAnnotationId(path, label)
    points.forEachIndexed { index, value -> validatePointTree(requireObjectElement(value, "$label.points[$index]"), "$label.points[$index]") }
}

private fun validateMeasurementTree(measurement: JsonObject, label: String) {
    rejectUnknownFields(measurement, setOf("p1", "p2", "text", "id"), label)
    requireAnnotationId(measurement, label)
    validatePointTree(requireObject(measurement, "p1", label), "$label.p1")
    validatePointTree(requireObject(measurement, "p2", label), "$label.p2")
    requireString(measurement, "text", label, required = true, maxChars = Stage5Limits.MAX_TEXT_CHARS)
}

private fun validateNoteTree(note: JsonObject, label: String) {
    rejectUnknownFields(note, setOf("x", "y", "text", "fontSizeRatio", "isBold", "rotation", "id"), label)
    requireAnnotationId(note, label)
    requireFloat(note, "x", label, min = 0f, max = 1f)
    requireFloat(note, "y", label, min = 0f, max = 1f)
    requireString(note, "text", label, required = true, maxChars = Stage5Limits.MAX_TEXT_CHARS)
    requireFloat(note, "fontSizeRatio", label, min = Float.MIN_VALUE, max = Stage5Limits.MAX_RATIO)
    requireBoolean(note, "isBold", label)
    requireFloat(note, "rotation", label, min = -AnnotationModelV2.MAX_ROTATION_DEGREES, max = AnnotationModelV2.MAX_ROTATION_DEGREES)
}

private fun validatePhotoPinTree(
    pin: JsonObject,
    label: String,
    photoReferenceCount: Long,
    annotationBudget: AnnotationBudget
): Long {
    rejectUnknownFields(pin, setOf("x", "y", "id", "imageFileNames", "imageNotes", "imageShapes"), label)
    requireFloat(pin, "x", label, min = 0f, max = 1f)
    requireFloat(pin, "y", label, min = 0f, max = 1f)
    requireAnnotationId(pin, label)
    val names = requireArray(pin, "imageFileNames", label)
    if (names.size() > Stage5Limits.MAX_PHOTOS_PER_PIN) throw Stage5ValidationException("$label photo count exceeds its limit")
    val cumulativePhotoReferences = photoReferenceCount + names.size().toLong()
    if (cumulativePhotoReferences > Stage5Limits.MAX_TOTAL_PHOTOS.toLong()) {
        throw Stage5ValidationException("$label photo reference count exceeds limit")
    }
    val nameSet = LinkedHashSet<String>()
    names.forEachIndexed { index, value ->
        val name = requireStringElement(value, "$label.imageFileNames[$index]", Stage5Limits.MAX_STRING_CHARS)
        validatePhotoFileName(name)
        if (!nameSet.add(name)) throw Stage5ValidationException("$label contains a duplicate photo reference")
    }
    val imageNotes = requireObject(pin, "imageNotes", label)
    val imageShapes = requireObject(pin, "imageShapes", label)
    if (imageNotes.size() > Stage5Limits.MAX_PHOTOS_PER_PIN || imageShapes.size() > Stage5Limits.MAX_PHOTOS_PER_PIN) {
        throw Stage5ValidationException("$label photo annotation map exceeds its limit")
    }
    imageNotes.entrySet().forEach { (name, values) ->
        if (name !in nameSet) throw Stage5ValidationException("$label has an unknown image note reference")
        val list = requireArrayElement(values, "$label.imageNotes[$name]")
        if (list.size() > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE) throw Stage5ValidationException("$label image notes exceed their limit")
        annotationBudget.add(list.size(), "image notes")
        list.forEachIndexed { index, value -> validateImageNoteTree(requireObjectElement(value, "$label.imageNotes[$name][$index]"), "$label.imageNotes[$name][$index]") }
    }
    imageShapes.entrySet().forEach { (name, values) ->
        if (name !in nameSet) throw Stage5ValidationException("$label has an unknown image shape reference")
        val list = requireArrayElement(values, "$label.imageShapes[$name]")
        if (list.size() > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE) throw Stage5ValidationException("$label image shapes exceed their limit")
        annotationBudget.add(list.size(), "image shapes")
        list.forEachIndexed { index, value -> validateShapeTree(requireObjectElement(value, "$label.imageShapes[$name][$index]"), "$label.imageShapes[$name][$index]") }
    }
    nameSet.forEach { name ->
        val noteIds = imageNotes.get(name)?.asJsonArray ?: JsonArray()
        val shapeIds = imageShapes.get(name)?.asJsonArray ?: JsonArray()
        validateUniqueTreeIds(listOf(noteIds, shapeIds), "$label.photo[$name]")
    }
    return cumulativePhotoReferences
}

private fun validateImageNoteTree(note: JsonObject, label: String) = validateNoteTree(note, label)

private fun validateShapeTree(shape: JsonObject, label: String) {
    rejectUnknownFields(shape, setOf("x", "y", "rotation", "type", "colorArgb", "isFilled", "strokeWidthRatio", "widthRatio", "heightRatio", "id"), label)
    requireAnnotationId(shape, label)
    requireFloat(shape, "x", label, min = 0f, max = 1f)
    requireFloat(shape, "y", label, min = 0f, max = 1f)
    requireFloat(shape, "rotation", label, min = -AnnotationModelV2.MAX_ROTATION_DEGREES, max = AnnotationModelV2.MAX_ROTATION_DEGREES)
    val type = requireString(shape, "type", label, required = true, maxChars = Stage5Limits.MAX_STRING_CHARS)
    if (type !in setOf("RECTANGLE", "CIRCLE", "ARROW", "CLOUD")) throw Stage5ValidationException("$label has an unknown shape enum")
    requireInt(shape, "colorArgb", label)
    requireBoolean(shape, "isFilled", label)
    requireFloat(shape, "strokeWidthRatio", label, min = Float.MIN_VALUE, max = Stage5Limits.MAX_RATIO)
    requireFloat(shape, "widthRatio", label, min = Float.MIN_VALUE, max = Stage5Limits.MAX_RATIO)
    requireFloat(shape, "heightRatio", label, min = Float.MIN_VALUE, max = Stage5Limits.MAX_RATIO)
}

private fun validatePointTree(point: JsonObject, label: String) {
    rejectUnknownFields(point, setOf("x", "y"), label)
    requireFloat(point, "x", label, min = 0f, max = 1f)
    requireFloat(point, "y", label, min = 0f, max = 1f)
}

private fun validateScaleTree(scale: JsonObject, label: String) {
    rejectUnknownFields(scale, setOf("pointsPerFoot"), label)
    requireFloat(scale, "pointsPerFoot", label, min = Float.MIN_VALUE)
}

private fun requireAnnotationId(value: JsonObject, label: String): String {
    val id = requireString(value, "id", label, required = true, maxChars = Stage5Limits.MAX_ID_CHARS)
    if (!validAnnotationId(id)) throw Stage5ValidationException("$label has an invalid annotation id")
    return id
}

private fun validateUniqueTreeIds(domains: List<JsonArray>, label: String) {
    val seen = HashSet<String>()
    domains.forEach { values -> values.forEach { value ->
        val id = requireAnnotationId(requireObjectElement(value, label), label)
        if (!seen.add(id)) throw Stage5ValidationException("$label contains duplicate annotation identity")
    } }
}

private class AnnotationBudget(private val label: String) {
    private var count = 0L

    fun add(amount: Int, kind: String) {
        if (amount < 0 || count > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE - amount.toLong()) {
            throw Stage5ValidationException("$label $kind exceed the aggregate annotation limit")
        }
        count += amount.toLong()
    }
}

private fun rejectUnknownFields(objectValue: JsonObject, allowed: Set<String>, label: String) {
    objectValue.keySet().firstOrNull { it !in allowed }?.let {
        throw Stage5ValidationException("$label contains unsupported field: $it")
    }
}

private fun requireObject(objectValue: JsonObject, name: String, label: String): JsonObject =
    requireObjectElement(requiredElement(objectValue, name, label), "$label.$name")

private fun optionalObject(objectValue: JsonObject, name: String, label: String): JsonObject? =
    if (!objectValue.has(name) || objectValue.get(name).isJsonNull) null
    else requireObjectElement(objectValue.get(name), "$label.$name")

private fun requireArray(objectValue: JsonObject, name: String, label: String): JsonArray =
    requireArrayElement(requiredElement(objectValue, name, label), "$label.$name")

private fun requiredElement(objectValue: JsonObject, name: String, label: String): JsonElement {
    if (!objectValue.has(name) || objectValue.get(name).isJsonNull) {
        throw Stage5ValidationException("$label.$name is missing or null")
    }
    return objectValue.get(name)
}

private fun requireObjectElement(element: JsonElement, label: String): JsonObject {
    if (!element.isJsonObject) throw Stage5ValidationException("$label must be an object")
    return element.asJsonObject
}

private fun requireArrayElement(element: JsonElement, label: String): JsonArray {
    if (!element.isJsonArray) throw Stage5ValidationException("$label must be an array")
    return element.asJsonArray
}

private fun requirePrimitive(element: JsonElement, label: String): JsonPrimitive {
    if (!element.isJsonPrimitive) throw Stage5ValidationException("$label must be a primitive")
    return element.asJsonPrimitive
}

private fun requireStringElement(element: JsonElement, label: String, maxChars: Int): String {
    val primitive = requirePrimitive(element, label)
    if (!primitive.isString) throw Stage5ValidationException("$label must be a string")
    val value = primitive.asString
    if (value.length > maxChars) throw Stage5ValidationException("$label exceeds $maxChars characters")
    return value
}

private fun requireString(objectValue: JsonObject, name: String, label: String, required: Boolean, maxChars: Int): String {
    val element = if (required) requiredElement(objectValue, name, label) else objectValue.get(name)
    if (element == null || element.isJsonNull) {
        if (required) throw Stage5ValidationException("$label.$name is missing or null")
        return ""
    }
    val value = requireStringElement(element, "$label.$name", maxChars)
    if (required && value.isBlank()) throw Stage5ValidationException("$label.$name is blank")
    return value
}

private fun optionalString(objectValue: JsonObject, name: String, label: String, maxChars: Int): String? =
    if (!objectValue.has(name) || objectValue.get(name).isJsonNull) null
    else requireStringElement(objectValue.get(name), "$label.$name", maxChars)

private fun numberElement(element: JsonElement, label: String): BigDecimal {
    val primitive = requirePrimitive(element, label)
    if (!primitive.isNumber) throw Stage5ValidationException("$label must be a finite number")
    return try {
        BigDecimal(primitive.asString)
    } catch (error: NumberFormatException) {
        throw Stage5ValidationException("$label is not a valid number", error)
    }
}

private fun requireInt(objectValue: JsonObject, name: String, label: String, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE, exact: Int? = null): Int {
    val number = numberElement(requiredElement(objectValue, name, label), "$label.$name")
    val value = try { number.intValueExact() } catch (error: ArithmeticException) {
        throw Stage5ValidationException("$label.$name must be an integer", error)
    }
    if (value < min || value > max || (exact != null && value != exact)) throw Stage5ValidationException("$label.$name is out of range")
    return value
}

private fun requireLong(objectValue: JsonObject, name: String, label: String, min: Long = Long.MIN_VALUE, max: Long = Long.MAX_VALUE): Long {
    val number = numberElement(requiredElement(objectValue, name, label), "$label.$name")
    val value = try { number.longValueExact() } catch (error: ArithmeticException) {
        throw Stage5ValidationException("$label.$name must be an integer", error)
    }
    if (value < min || value > max) throw Stage5ValidationException("$label.$name is out of range")
    return value
}

private fun requireFloat(objectValue: JsonObject, name: String, label: String, min: Float = -Stage5Limits.MAX_NUMERIC_ABS, max: Float = Stage5Limits.MAX_NUMERIC_ABS): Float {
    val number = numberElement(requiredElement(objectValue, name, label), "$label.$name")
    val value = number.toDouble()
    if (!value.isFinite() || value < min.toDouble() || value > max.toDouble()) throw Stage5ValidationException("$label.$name is non-finite or out of range")
    return value.toFloat()
}

private fun requireBoolean(objectValue: JsonObject, name: String, label: String): Boolean {
    val primitive = requirePrimitive(requiredElement(objectValue, name, label), "$label.$name")
    if (!primitive.isBoolean) throw Stage5ValidationException("$label.$name must be boolean")
    return primitive.asBoolean
}

/** Reads at most [maxBytes] plus one sentinel byte and then fails closed. */
fun readBoundedBytes(input: InputStream, maxBytes: Int, label: String): ByteArray {
    require(maxBytes >= 0) { "maxBytes must be non-negative" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    var zeroReads = 0
    while (true) {
        val remaining = maxBytes - total
        val read = if (remaining <= 0) {
            input.read()
        } else {
            input.read(buffer, 0, minOf(buffer.size, remaining))
        }
        if (read < 0) break
        if (remaining <= 0) {
            throw Stage5ValidationException("$label exceeds $maxBytes bytes")
        }
        if (read == 0) {
            zeroReads++
            if (zeroReads > Stage5Limits.MAX_ZERO_READS) {
                throw Stage5ValidationException("$label input made no progress")
            }
            continue
        }
        zeroReads = 0
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray()
}

fun readBoundedUtf8(input: InputStream, maxBytes: Int = Stage5Limits.MAX_JSON_BYTES, label: String = "JSON"): String {
    return decodeUtf8Bytes(readBoundedBytes(input, maxBytes, label), label)
}

private fun decodeUtf8Bytes(bytes: ByteArray, label: String): String {
    return try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: CharacterCodingException) {
        throw Stage5ValidationException("$label is not valid UTF-8", error)
    }
}

fun boundedUtf8Bytes(value: String, maxBytes: Int, label: String): ByteArray {
    if (value.length > maxBytes) {
        throw Stage5ValidationException("$label exceeds $maxBytes characters")
    }
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size > maxBytes) throw Stage5ValidationException("$label exceeds $maxBytes bytes")
    return bytes
}

/** Streams JSON through the same hard byte ceiling used for untrusted input. */
fun encodeBoundedJson(gson: Gson, value: Any, maxBytes: Int, label: String): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    BoundedOutputStream(output, maxBytes, label).use { bounded ->
        OutputStreamWriter(bounded, StandardCharsets.UTF_8).use { writer ->
            gson.toJson(value, writer)
        }
    }
    return output.toByteArray()
}

fun escapeDriveQueryLiteral(value: String): String {
    require(value.isNotBlank() && value.length <= Stage5Limits.MAX_STRING_CHARS) {
        "Drive query literal is blank or oversized"
    }
    return "'${value.replace("\\", "\\\\").replace("'", "\\'")}'"
}

/** OutputStream used by Drive media downloads before JSON materialization. */
class BoundedOutputStream(
    output: OutputStream,
    private val maxBytes: Int,
    private val label: String
) : FilterOutputStream(output) {
    private var count: Long = 0

    override fun write(b: Int) {
        ensureCapacity(1)
        super.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        ensureCapacity(len)
        super.write(b, off, len)
        count += len
    }

    private fun ensureCapacity(delta: Int) {
        if (delta < 0 || count + delta > maxBytes.toLong()) {
            throw Stage5ValidationException("$label exceeds $maxBytes bytes")
        }
    }
}

data class ImageInfo(val mimeType: String, val width: Int, val height: Int) {
    init {
        require(mimeType in APPROVED_IMAGE_MIME_TYPES) { "unsupported image MIME type: $mimeType" }
        require(width > 0 && height > 0) { "image dimensions must be positive" }
        require(width <= Stage5Limits.MAX_IMAGE_WIDTH) { "image width exceeds limit" }
        require(height <= Stage5Limits.MAX_IMAGE_HEIGHT) { "image height exceeds limit" }
        require(width.toLong() * height.toLong() <= Stage5Limits.MAX_IMAGE_PIXELS) {
            "image pixel count exceeds limit"
        }
    }

    companion object {
        val APPROVED_IMAGE_MIME_TYPES: Set<String> = setOf("image/jpeg", "image/png", "image/webp")
    }
}

/** Injectable decode contract used by production and deterministic JVM tests. */
interface PhotoDecodeProbe {
    fun probe(bytes: ByteArray): ImageInfo
}

/** Source-compatible name retained for existing focused tests/helpers. */
interface ImageProbe : PhotoDecodeProbe

/** Decoder-independent bounds returned before any bitmap allocation. */
internal data class PhotoImageBounds(val width: Int, val height: Int)

/**
 * A bounded decoded image whose owner is released exactly once by [close].
 * The seam keeps the budget contract directly testable without replacing the
 * Android process-wide decoder or weakening the production probe.
 */
internal class DecodedPhotoImage(
    val width: Int,
    val height: Int,
    val allocationBytes: Long,
    val isArgb8888: Boolean,
    private val release: () -> Unit
) : Closeable {
    private var released = false

    override fun close() {
        if (!released) {
            released = true
            release()
        }
    }
}

/** Injectable decoder seam used by the real probe and focused JVM tests. */
internal interface PhotoImageDecoder {
    fun decodeBounds(bytes: ByteArray): PhotoImageBounds

    fun decode(bytes: ByteArray, inSampleSize: Int): DecodedPhotoImage?
}

/** The production BitmapFactory implementation; qualification may decorate it. */
internal object AndroidPhotoImageDecoder : PhotoImageDecoder {
    override fun decodeBounds(bytes: ByteArray): PhotoImageBounds {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return PhotoImageBounds(options.outWidth, options.outHeight)
    }

    override fun decode(bytes: ByteArray, inSampleSize: Int): DecodedPhotoImage? {
        require(inSampleSize > 0) { "image sample size must be positive" }
        val options = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inMutable = false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return DecodedPhotoImage(
            width = bitmap.width,
            height = bitmap.height,
            allocationBytes = bitmap.allocationByteCount.toLong(),
            isArgb8888 = bitmap.config == Bitmap.Config.ARGB_8888
        ) {
            bitmap.recycle()
        }
    }
}

/**
 * Production Android photo probe. The default decoder is always
 * [AndroidPhotoImageDecoder]; the constructor seam is only for deterministic
 * observation around that same BitmapFactory delegate.
 */
internal class AndroidPhotoDecodeProbe internal constructor(
    private val decoder: PhotoImageDecoder = AndroidPhotoImageDecoder
) : PhotoDecodeProbe {
    override fun probe(bytes: ByteArray): ImageInfo = probePhotoBytesWithDecoder(bytes, decoder)
}

/**
 * Applies the Stage 7 sampling and allocation contract after Stage 5 has
 * validated the encoded container and its source dimensions.
 */
internal fun probePhotoBytesWithDecoder(
    bytes: ByteArray,
    decoder: PhotoImageDecoder
): ImageInfo {
    require(bytes.isNotEmpty()) { "image content is empty" }
    requireCompleteEncodedContainer(bytes)
    val bounds = decoder.decodeBounds(bytes)
    require(bounds.width > 0 && bounds.height > 0) { "image is not decodable" }

    // Preserve Stage 5's source-dimension and pixel validation before any
    // actual image allocation is attempted.
    val boundedInfo = ImageInfo(mimeFor(bytes), bounds.width, bounds.height)
    val decodePlan = BitmapBudgetPolicy.photoDecodePlan(bounds.width, bounds.height)
        ?: throw Stage5ValidationException("image cannot be decoded within bitmap budget")
    val decoded = decoder.decode(bytes, decodePlan.inSampleSize)
        ?: throw Stage5ValidationException("image is not decodable")

    decoded.use { image ->
        val allocation = if (image.isArgb8888) {
            BitmapBudgetPolicy.actualAllocationPlan(
                widthPx = image.width,
                heightPx = image.height,
                actualAllocationBytes = image.allocationBytes
            )
        } else {
            null
        }
        if (allocation == null ||
            image.width > decodePlan.target.width ||
            image.height > decodePlan.target.height ||
            !isWithinSampledDimensionRange(
                sourceDimensionPx = bounds.width,
                decodedDimensionPx = image.width,
                inSampleSize = decodePlan.inSampleSize
            ) ||
            !isWithinSampledDimensionRange(
                sourceDimensionPx = bounds.height,
                decodedDimensionPx = image.height,
                inSampleSize = decodePlan.inSampleSize
            )
        ) {
            throw Stage5ValidationException("image decode exceeded bitmap budget")
        }
    }
    return boundedInfo
}

/**
 * Real Android decoding is used in the app. Local JVM tests fall back to the
 * reflective ImageIO probe below; reflection keeps javax.imageio out of the
 * Android production classpath while still validating compressed bytes.
 */
object DefaultImageProbe : PhotoDecodeProbe {
    private val androidProbe = AndroidPhotoDecodeProbe()

    override fun probe(bytes: ByteArray): ImageInfo {
        try {
            return androidProbe.probe(bytes)
        } catch (unsupported: UnsupportedOperationException) {
            if (!unsupported.message.orEmpty().contains("not mocked", ignoreCase = true)) throw unsupported
            return ImageIoPhotoDecodeProbe.probe(bytes)
        } catch (runtime: RuntimeException) {
            if (!runtime.message.orEmpty().contains("not mocked", ignoreCase = true)) throw runtime
            return ImageIoPhotoDecodeProbe.probe(bytes)
        }
    }
}

/** Real compressed-image decoding for local JVM tests without an Android dependency. */
object ImageIoPhotoDecodeProbe : PhotoDecodeProbe {
    override fun probe(bytes: ByteArray): ImageInfo {
        require(bytes.isNotEmpty()) { "image content is empty" }
        requireCompleteEncodedContainer(bytes)
        val decoded = try {
            val imageIo = Class.forName("javax.imageio.ImageIO")
            val createImageInputStream = imageIo.getMethod("createImageInputStream", Any::class.java)
            val getImageReaders = imageIo.getMethod("getImageReaders", Any::class.java)
            val input = ByteArrayInputStream(bytes)
            val imageInputStream = createImageInputStream.invoke(null, input)
                ?: throw Stage5ValidationException("JVM image decoder could not open the image stream")
            try {
                val readers = getImageReaders.invoke(null, imageInputStream)
                val iteratorType = Class.forName("java.util.Iterator")
                val iterator = iteratorType.getMethod("hasNext")
                if (!(iterator.invoke(readers) as Boolean)) {
                    throw Stage5ValidationException("JVM image decoder found no reader")
                }
                val reader = iteratorType.getMethod("next").invoke(readers)
                try {
                    val readerType = Class.forName("javax.imageio.ImageReader")
                    readerType.getMethod(
                        "setInput",
                        Any::class.java,
                        Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType
                    ).invoke(reader, imageInputStream, true, true)
                    val width = (readerType.getMethod("getWidth", Int::class.javaPrimitiveType).invoke(reader, 0) as Number).toInt()
                    val height = (readerType.getMethod("getHeight", Int::class.javaPrimitiveType).invoke(reader, 0) as Number).toInt()
                    val info = ImageInfo(mimeFor(bytes), width, height)
                    val decodePlan = BitmapBudgetPolicy.photoDecodePlan(width, height)
                        ?: throw Stage5ValidationException("image cannot be decoded within bitmap budget")
                    val readParamType = Class.forName("javax.imageio.ImageReadParam")
                    val readParam = readerType.getMethod("getDefaultReadParam").invoke(reader)
                    readParamType.getMethod(
                        "setSourceSubsampling",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    ).invoke(readParam, decodePlan.inSampleSize, decodePlan.inSampleSize, 0, 0)
                    val decodedImage = readerType.getMethod(
                        "read",
                        Int::class.javaPrimitiveType,
                        readParamType
                    ).invoke(reader, 0, readParam)
                        ?: throw Stage5ValidationException("JVM image decoder returned no image")
                    val decodedType = decodedImage.javaClass
                    val decodedWidth = (decodedType.getMethod("getWidth").invoke(decodedImage) as Number).toInt()
                    val decodedHeight = (decodedType.getMethod("getHeight").invoke(decodedImage) as Number).toInt()
                    if (decodedWidth > decodePlan.target.width ||
                        decodedHeight > decodePlan.target.height ||
                        !isWithinSampledDimensionRange(width, decodedWidth, decodePlan.inSampleSize) ||
                        !isWithinSampledDimensionRange(height, decodedHeight, decodePlan.inSampleSize) ||
                        BitmapBudgetPolicy.bitmapPlan(decodedWidth, decodedHeight) == null
                    ) {
                        throw Stage5ValidationException("JVM image decode exceeded bitmap budget")
                    }
                    info
                } finally {
                    try {
                        Class.forName("javax.imageio.ImageReader").getMethod("dispose").invoke(reader)
                    } catch (_: ReflectiveOperationException) {
                    } catch (_: SecurityException) {
                    }
                }
            } finally {
                try {
                    imageInputStream.javaClass.getMethod("close").invoke(imageInputStream)
                } catch (_: ReflectiveOperationException) {
                } catch (_: SecurityException) {
                }
                input.close()
            }
        } catch (error: Stage5ValidationException) {
            throw error
        } catch (error: ReflectiveOperationException) {
            throw Stage5ValidationException("JVM image decoder rejected the image", error)
        } catch (error: IOException) {
            throw Stage5ValidationException("JVM image decoder rejected the image", error)
        } catch (error: IllegalArgumentException) {
            throw Stage5ValidationException("JVM image decoder rejected the image", error)
        } catch (error: ClassCastException) {
            throw Stage5ValidationException("JVM image decoder rejected the image", error)
        } catch (error: SecurityException) {
            throw Stage5ValidationException("JVM image decoder rejected the image", error)
        }
        return decoded
    }
}

private fun isWithinSampledDimensionRange(
    sourceDimensionPx: Int,
    decodedDimensionPx: Int,
    inSampleSize: Int
): Boolean {
    if (sourceDimensionPx <= 0 || decodedDimensionPx <= 0 || inSampleSize <= 0) return false
    val sample = inSampleSize.toLong()
    val source = sourceDimensionPx.toLong()
    val minimum = maxOf(1L, source / sample)
    val maximum = (source + sample - 1L) / sample
    return decodedDimensionPx.toLong() in minimum..maximum
}

/**
 * ImageIO can return a decoded JPEG for an input whose final end marker was
 * truncated.  Require the container terminator in addition to the real
 * compressed-data decode so a partial transfer cannot be published as a
 * complete photo.
 */
private fun requireCompleteEncodedContainer(bytes: ByteArray) {
    when {
        bytes.isJpeg() -> {
            if (bytes.size < 4 || bytes[bytes.size - 2] != 0xFF.toByte() || bytes.last() != 0xD9.toByte()) {
                throw Stage5ValidationException("JPEG container is truncated")
            }
        }
        bytes.isPng() -> {
            val iend = byteArrayOf(
                0x00, 0x00, 0x00, 0x00,
                0x49, 0x45, 0x4E, 0x44.toByte(),
                0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
            )
            if (bytes.size < iend.size || !bytes.copyOfRange(bytes.size - iend.size, bytes.size).contentEquals(iend)) {
                throw Stage5ValidationException("PNG container is truncated")
            }
        }
        bytes.isWebp() -> {
            val declaredSize = (bytes[4].toInt() and 0xFF) or
                ((bytes[5].toInt() and 0xFF) shl 8) or
                ((bytes[6].toInt() and 0xFF) shl 16) or
                ((bytes[7].toInt() and 0xFF) shl 24)
            if (declaredSize != bytes.size - 8) {
                throw Stage5ValidationException("WebP container is truncated or has trailing bytes")
            }
        }
    }
}

private fun mimeFor(bytes: ByteArray): String = when {
    bytes.isJpeg() -> "image/jpeg"
    bytes.isPng() -> "image/png"
    bytes.isWebp() -> "image/webp"
    else -> throw Stage5ValidationException("unsupported image content")
}

private fun ByteArray.isJpeg(): Boolean = size >= 4 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()
private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
)

private fun ByteArray.isPng(): Boolean = size >= PNG_SIGNATURE.size &&
    PNG_SIGNATURE.indices.all { index -> this[index] == PNG_SIGNATURE[index] }
private fun ByteArray.isWebp(): Boolean = size >= 12 &&
    copyOfRange(0, 4).toString(StandardCharsets.US_ASCII) == "RIFF" &&
    copyOfRange(8, 12).toString(StandardCharsets.US_ASCII) == "WEBP"

data class PhotoDescriptor(
    val byteCount: Long,
    val sha256: String,
    val mimeType: String,
    val width: Int,
    val height: Int
) {
    init {
        require(byteCount > 0L && byteCount <= Stage5Limits.MAX_PHOTO_BYTES)
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "photo SHA-256 is invalid" }
        ImageInfo(mimeType, width, height)
    }
}

data class ValidatedPhoto(
    val bytes: ByteArray,
    val descriptor: PhotoDescriptor
)

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }

fun validatePhotoFileName(name: String): String {
    val deviceBasename = name.substringBefore('.').uppercase(Locale.ROOT)
    if (name.isBlank() || name.length > Stage5Limits.MAX_STRING_CHARS ||
        name.any { it.code < 0x20 || it.code == 0x7F } ||
        deviceBasename in WINDOWS_DEVICE_BASENAMES
    ) {
        throw Stage5ValidationException("photo filename is blank, oversized, or contains NUL")
    }
    if (name.contains('/') || name.contains('\\') || name == "." || name == ".." ||
        name.startsWith("/") || name.startsWith("\\") || name.matches(Regex("^[A-Za-z]:.*")) ||
        name.contains(Regex("(^|[./\\\\])\\.\\.?([/\\\\]|$)")) ||
        !name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.(?i:jpg|jpeg|png|webp)"))
    ) {
        throw Stage5ValidationException("unsafe photo filename: $name")
    }
    return name
}

/** Validates without retaining or defensively duplicating a decoder-owned buffer. */
internal fun validatePhotoDescriptor(
    bytes: ByteArray,
    expected: PhotoDescriptor? = null,
    imageProbe: PhotoDecodeProbe = DefaultImageProbe
): PhotoDescriptor {
    if (bytes.isEmpty()) throw Stage5ValidationException("photo content is empty")
    if (bytes.size > Stage5Limits.MAX_PHOTO_BYTES) {
        throw Stage5ValidationException("photo content exceeds ${Stage5Limits.MAX_PHOTO_BYTES} bytes")
    }
    requireCompleteEncodedContainer(bytes)
    val info = imageProbe.probe(bytes)
    val descriptor = PhotoDescriptor(bytes.size.toLong(), sha256Hex(bytes), info.mimeType, info.width, info.height)
    if (expected != null && descriptor != expected) {
        throw Stage5ValidationException("photo descriptor does not match transferred bytes")
    }
    return descriptor
}

/** The byte-owning API keeps its defensive copy contract. */
fun validatePhotoBytes(
    bytes: ByteArray,
    expected: PhotoDescriptor? = null,
    imageProbe: PhotoDecodeProbe = DefaultImageProbe
): ValidatedPhoto {
    val descriptor = validatePhotoDescriptor(bytes, expected, imageProbe)
    return ValidatedPhoto(bytes.copyOf(), descriptor)
}

fun requiredPhotoNames(snapshot: DocumentSnapshotV1): Set<String> {
    val names = LinkedHashSet<String>()
    snapshot.pages.values.forEach { page ->
        page.photoPins.forEach { pin ->
            pin.imageFileNames.forEach { name ->
                validatePhotoFileName(name)
                if (!names.add(name)) {
                    // The same asset may be pinned more than once, but a
                    // repeated reference inside one pin is ambiguous and is
                    // rejected by validateSnapshot below.
                }
            }
        }
    }
    return names
}

fun validateSnapshot(snapshot: DocumentSnapshotV1) {
    try {
        require(snapshot.schemaVersion == DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION) { "unsupported snapshot schema" }
        require(snapshot.snapshotRevision >= 0L) { "negative snapshot revision" }
        require(snapshot.source.sourceUri.isNotBlank() && snapshot.source.sourceUri.length <= Stage5Limits.MAX_STRING_CHARS) {
            "snapshot source URI is missing or oversized"
        }
        snapshot.source.displayName?.let { require(it.length <= Stage5Limits.MAX_STRING_CHARS) }
        require(snapshot.source.providerMetadata.size <= Stage5Limits.MAX_PROVIDER_PROPERTIES)
        snapshot.source.providerMetadata.forEach { (key, value) ->
            require(key.isNotBlank() && key.length <= Stage5Limits.MAX_STRING_CHARS)
            require(value.length <= Stage5Limits.MAX_STRING_CHARS)
        }
        require(snapshot.pages.size <= Stage5Limits.MAX_PAGES) { "page count exceeds limit" }
        var totalPhotos = 0
        snapshot.pages.forEach { (index, page) ->
            require(index >= 0 && index < 1_000_000) { "page index is out of range" }
            validatePage(page)
            totalPhotos += page.photoPins.sumOf { it.imageFileNames.size }
        }
        require(totalPhotos <= Stage5Limits.MAX_TOTAL_PHOTOS) { "photo reference count exceeds limit" }
    } catch (error: Stage5ValidationException) {
        throw error
    } catch (error: IllegalArgumentException) {
        throw Stage5ValidationException(error.message ?: "snapshot validation failed", error)
    } catch (error: IllegalStateException) {
        throw Stage5ValidationException(error.message ?: "snapshot validation failed", error)
    } catch (error: NullPointerException) {
        throw Stage5ValidationException(error.message ?: "snapshot validation failed", error)
    }
}

private fun validatePage(page: PageSnapshotV1) {
    val annotationBudget = AnnotationBudget("snapshot page")
    annotationBudget.add(page.paths.size, "paths")
    annotationBudget.add(page.measurements.size, "measurements")
    annotationBudget.add(page.notes.size, "notes")
    annotationBudget.add(page.photoPins.size, "photo pins")
    annotationBudget.add(page.shapes.size, "shapes")
    if (page.scale != null) annotationBudget.add(1, "scale")
    require(page.paths.size <= Stage5Limits.MAX_ANNOTATIONS_PER_PAGE)
    page.paths.forEach { path ->
        nonBlankId(path.id, "path id")
        require(path.points.size in 1..Stage5Limits.MAX_PATH_POINTS)
        path.points.forEach(::validatePoint)
        finite(path.strokeWidthRatio, "path stroke ratio", Float.MIN_VALUE, Stage5Limits.MAX_RATIO)
    }
    page.measurements.forEach { measurement ->
        nonBlankId(measurement.id, "measurement id")
        validatePoint(measurement.p1)
        validatePoint(measurement.p2)
        nonBlankText(measurement.text, "measurement text")
    }
    page.notes.forEach { note ->
        nonBlankId(note.id, "note id")
        finite(note.x, "note x", 0f, 1f)
        finite(note.y, "note y", 0f, 1f)
        finite(note.fontSizeRatio, "note font ratio", Float.MIN_VALUE, Stage5Limits.MAX_RATIO)
        AnnotationModelV2.validateRotation(note.rotation)
        nonBlankText(note.text, "note text")
    }
    require(page.photoPins.size <= Stage5Limits.MAX_PHOTO_PINS_PER_PAGE)
    page.photoPins.forEach { validatePhotoPin(it, annotationBudget) }
    page.scale?.let { scale -> finite(scale.pointsPerFoot, "scale", Float.MIN_VALUE, Stage5Limits.MAX_NUMERIC_ABS) }
    page.shapes.forEach(::validateShape)
    AnnotationModelV2.validateUniqueIds(page.paths.map { it.id } + page.measurements.map { it.id } +
        page.notes.map { it.id } + page.photoPins.map { it.id } + page.shapes.map { it.id })
}

private fun validatePhotoPin(pin: PhotoPinSnapshotV1, annotationBudget: AnnotationBudget) {
    finite(pin.x, "photo pin x", 0f, 1f)
    finite(pin.y, "photo pin y", 0f, 1f)
    nonBlankId(pin.id, "photo pin id")
    require(pin.imageFileNames.size <= Stage5Limits.MAX_PHOTOS_PER_PIN)
    require(pin.imageFileNames.distinct().size == pin.imageFileNames.size) { "duplicate photo reference" }
    pin.imageFileNames.forEach(::validatePhotoFileName)
    require(pin.imageNotes.keys == pin.imageNotes.keys.intersect(pin.imageFileNames.toSet())) {
        "image note references an unknown photo"
    }
    require(pin.imageShapes.keys == pin.imageShapes.keys.intersect(pin.imageFileNames.toSet())) {
        "image shape references an unknown photo"
    }
    require(pin.imageNotes.size <= Stage5Limits.MAX_PHOTOS_PER_PIN)
    require(pin.imageShapes.size <= Stage5Limits.MAX_PHOTOS_PER_PIN)
    pin.imageFileNames.forEach { name ->
        AnnotationModelV2.validateUniqueIds(pin.imageNotes[name].orEmpty().map { it.id } +
            pin.imageShapes[name].orEmpty().map { it.id })
    }
    pin.imageNotes.values.forEach { notes ->
        require(notes.size <= Stage5Limits.MAX_ANNOTATIONS_PER_PAGE)
        annotationBudget.add(notes.size, "image notes")
        notes.forEach(::validateImageNote)
    }
    pin.imageShapes.values.forEach { shapes ->
        require(shapes.size <= Stage5Limits.MAX_ANNOTATIONS_PER_PAGE)
        annotationBudget.add(shapes.size, "image shapes")
        shapes.forEach(::validateShape)
    }
}

private fun validateImageNote(note: PhotoImageNoteSnapshotV1) {
    finite(note.x, "image note x", 0f, 1f)
    finite(note.y, "image note y", 0f, 1f)
    AnnotationModelV2.validateRotation(note.rotation)
    finite(note.fontSizeRatio, "image note font ratio", Float.MIN_VALUE, Stage5Limits.MAX_RATIO)
    nonBlankText(note.text, "image note text")
    nonBlankId(note.id, "image note id")
}

private fun validateShape(shape: ShapeSnapshotV1) {
    requireNotNull(shape.type) { "shape type is missing" }
    nonBlankId(shape.id, "shape id")
    finite(shape.x, "shape x", 0f, 1f)
    finite(shape.y, "shape y", 0f, 1f)
    AnnotationModelV2.validateRotation(shape.rotation)
    finite(shape.strokeWidthRatio, "shape stroke ratio", Float.MIN_VALUE, Stage5Limits.MAX_RATIO)
    finite(shape.widthRatio, "shape width ratio", Float.MIN_VALUE, Stage5Limits.MAX_RATIO)
    finite(shape.heightRatio, "shape height ratio", Float.MIN_VALUE, Stage5Limits.MAX_RATIO)
}

private fun validatePoint(point: PointSnapshotV1) {
    finite(point.x, "point x", 0f, 1f)
    finite(point.y, "point y", 0f, 1f)
}

private fun finite(value: Float, label: String, min: Float = -Stage5Limits.MAX_NUMERIC_ABS, max: Float = Stage5Limits.MAX_NUMERIC_ABS) {
    require(value.isFinite() && value >= min && value <= max) { "$label is non-finite or out of range" }
}

private fun nonBlankId(value: String, label: String) {
    require(validAnnotationId(value)) { "$label is missing, oversized, or invalid" }
}

private fun nonBlankText(value: String, label: String) {
    require(value.isNotBlank() && value.length <= Stage5Limits.MAX_TEXT_CHARS) { "$label is missing or oversized" }
}
