package com.example.myapplication.stage5

import com.example.myapplication.Note
import com.example.myapplication.PageData
import com.example.myapplication.PhotoImageNote
import com.example.myapplication.PhotoPin
import com.example.myapplication.stage0.HighResolutionPhonePhotoFixture
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.DrawnPathSnapshotV1
import com.example.myapplication.stage1.NoteSnapshotV1
import com.example.myapplication.stage1.PageScaleSnapshotV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PhotoImageNoteSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage1.PointSnapshotV1
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage5PayloadSecurityTest {
    @Test
    fun legacyPayloadFixtures_useTypedRequiredFieldAndFiniteValidation() {
        assertEquals(
            2,
            LegacyPageDataCodec.decode(resourceText("stage0/legacy/fully_populated_page_data.json")).size
        )

        listOf(
            "stage0/payloads/malformed.json",
            "stage0/payloads/missing_required_fields.json",
            "stage0/payloads/malicious_payloads.json",
            "stage0/payloads/malicious_non_finite_payloads.json"
        ).forEach { resource ->
            assertRejected("fixture $resource") {
                LegacyPageDataCodec.decode(resourceText(resource))
            }
        }

        val unknownEnum = resourceText("stage0/legacy/fully_populated_page_data.json")
            .replace("\"ARROW\"", "\"TRIANGLE\"")
        assertRejected("unknown legacy shape enum") { LegacyPageDataCodec.decode(unknownEnum) }
    }

    @Test
    fun legacyPayload_preservesExplicitVersionZeroDirectMeasurementCompatibility() {
        val json = """
            {
              "0": {
                "paths": [], "measurements": [{
                  "startX": 1.0, "startY": 2.0, "endX": 3.0, "endY": 4.0,
                  "distanceFeet": 4, "distanceInches": 6.5
                }],
                "notes": [], "photoPins": [], "shapes": [], "scale": null
              }
            }
        """.trimIndent()

        val measurement = LegacyPageDataCodec.decode(json).getValue(0).measurements.single()
        assertEquals(1.0f, measurement.p1.x, 0.0f)
        assertEquals(4.0f, measurement.p2.y, 0.0f)
        assertEquals("4' 6.50\"", measurement.text)
    }

    @Test
    fun legacyPayload_preservesHistoricalOmittedNullableScale() {
        val root = JsonParser.parseString(
            resourceText("stage0/legacy/fully_populated_page_data.json")
        ).asJsonObject
        root.entrySet().forEach { (_, page) ->
            page.asJsonObject.remove("scale")
        }

        val decoded = LegacyPageDataCodec.decode(Gson().toJson(root))
        assertEquals(2, decoded.size)
        assertTrue(decoded.values.all { it.scale == null })

        val roundTripped = LegacyPageDataCodec.decode(LegacyPageDataCodec.encode(decoded))
        assertEquals(2, roundTripped.size)
        assertTrue(roundTripped.values.all { it.scale == null })
    }

    @Test
    fun legacyRawTreeBoundary_rejectsNestedOversizeMissingFieldsAndNonCanonicalPageKeysBeforeDtoMaterialization() {
        val oversized = JsonParser.parseString(
            resourceText("stage0/legacy/fully_populated_page_data.json")
        ).asJsonObject
        val points = oversized.getAsJsonObject("0")
            .getAsJsonArray("paths")[0].asJsonObject.getAsJsonArray("points")
        repeat(Stage5Limits.MAX_PATH_POINTS) {
            points.add(JsonParser.parseString("{\"x\":0,\"y\":0}"))
        }
        assertRejected("oversized nested legacy point array") { LegacyPageDataCodec.decode(Gson().toJson(oversized)) }

        val missingNestedPrimitive = JsonParser.parseString(
            resourceText("stage0/legacy/fully_populated_page_data.json")
        ).asJsonObject
        missingNestedPrimitive.getAsJsonObject("0")
            .getAsJsonArray("paths")[0].asJsonObject
            .getAsJsonArray("points")[0].asJsonObject.remove("y")
        assertRejected("missing nested legacy primitive") {
            LegacyPageDataCodec.decode(Gson().toJson(missingNestedPrimitive))
        }

        val nonCanonicalPageKey = JsonParser.parseString(
            resourceText("stage0/legacy/fully_populated_page_data.json")
        ).asJsonObject
        val page = requireNotNull(nonCanonicalPageKey.remove("0"))
        nonCanonicalPageKey.add("1", page)
        nonCanonicalPageKey.add("01", page.deepCopy())
        assertRejected("non-canonical duplicate legacy page keys") {
            LegacyPageDataCodec.decode(Gson().toJson(nonCanonicalPageKey))
        }

        val duplicatePage = JsonParser.parseString(
            resourceText("stage0/legacy/fully_populated_page_data.json")
        ).asJsonObject.getAsJsonObject("0")
        val duplicateKeyJson = """
            {"1":${Gson().toJson(duplicatePage)},"1":${Gson().toJson(duplicatePage)}}
        """.trimIndent()
        assertRejected("duplicate legacy page key") { LegacyPageDataCodec.decode(duplicateKeyJson) }
    }

    @Test
    fun legacyRawTreeBoundary_countsRepeatedPhotoReferencesBeforeDtoMaterialization() {
        val fixtureRoot = JsonParser.parseString(
            resourceText("stage0/legacy/fully_populated_page_data.json")
        ).asJsonObject
        val templatePage = fixtureRoot.getAsJsonObject("0").deepCopy()
        val templatePin = templatePage.getAsJsonArray("photoPins")[0].asJsonObject.deepCopy()
        val repeatedName = templatePin.getAsJsonArray("imageFileNames")[0].asString

        fun repeatedPin(id: String): JsonObject = templatePin.deepCopy().apply {
            addProperty("id", id)
            add("imageFileNames", JsonArray().apply { add(repeatedName) })
            add("imageNotes", JsonObject())
            add("imageShapes", JsonObject())
        }

        fun pageWithPins(prefix: String, count: Int): JsonObject = templatePage.deepCopy().apply {
            add("photoPins", JsonArray().apply {
                repeat(count) { index -> add(repeatedPin("$prefix-$index")) }
            })
        }

        val withinLimit = fixtureRoot.deepCopy()
        withinLimit.getAsJsonObject("0").add("photoPins", JsonArray().apply {
            add(repeatedPin("within-0"))
            add(repeatedPin("within-1"))
        })
        assertEquals(
            2,
            LegacyPageDataCodec.decode(Gson().toJson(withinLimit)).getValue(0).photoPins.size
        )

        val overLimit = fixtureRoot.deepCopy()
        overLimit.keySet().toList().forEach { key -> overLimit.remove(key) }
        repeat(4) { pageIndex ->
            overLimit.add(
                pageIndex.toString(),
                pageWithPins("page-$pageIndex", Stage5Limits.MAX_PHOTO_PINS_PER_PAGE)
            )
        }
        overLimit.add("4", pageWithPins("page-4", 1))

        var failure: Throwable? = null
        try {
            LegacyPageDataCodec.decode(Gson().toJson(overLimit))
        } catch (error: Stage5ValidationException) {
            failure = error
        }
        assertTrue("failure=$failure", failure is Stage5ValidationException)
        assertTrue("failure=${failure?.message}", failure?.message?.contains("photo reference count") == true)
    }

    @Test
    fun strictJsonBoundary_rejectsNestedDuplicateMembersBeforeAnyTreeMaterialization() {
        val duplicateCanonical = """
            {
              "schemaVersion":1,
              "snapshotRevision":0,
              "source":{
                "sourceUri":"content://stage5/source",
                "sourceUri":"content://stage5/other",
                "displayName":"plan.pdf",
                "providerMetadata":{}
              },
              "pages":{}
            }
        """.trimIndent()
        assertRejected("duplicate canonical nested member") {
            decodeValidatedSnapshotJson(Gson(), duplicateCanonical, "duplicate canonical")
        }

        val duplicateLegacy = """
            {
              "0": {
                "paths": [], "measurements": [], "notes": [],
                "photoPins": [{
                  "x":0,"y":0,"id":"pin-stage5",
                  "imageFileNames":["photo.jpg"],
                  "imageNotes":{"photo.jpg":[],"photo.jpg":[]},
                  "imageShapes":{}
                }],
                "shapes": [], "scale": null
              }
            }
        """.trimIndent()
        assertRejected("duplicate legacy nested member") {
            LegacyPageDataCodec.decode(duplicateLegacy)
        }

        assertRejected("duplicate Drive nested member") {
            parseBoundedJsonObject(
                duplicateCanonical.toByteArray(StandardCharsets.UTF_8).inputStream(),
                Stage5Limits.MAX_JSON_BYTES,
                "duplicate Drive payload"
            )
        }
    }

    @Test
    fun strictJsonBoundary_rejectsExcessiveNestingBeforeTreeMaterialization() {
        val tooDeep = buildString {
            repeat(Stage5Limits.MAX_JSON_DEPTH + 2) { append('[') }
            append('0')
            repeat(Stage5Limits.MAX_JSON_DEPTH + 2) { append(']') }
        }
        assertRejected("excessively nested JSON") {
            validateNoDuplicateJsonMembers(tooDeep.toByteArray(StandardCharsets.UTF_8), "deep JSON")
        }
    }

    @Test
    fun rawAndTypedAnnotationBudgets_countNestedPhotoAnnotationsAcrossDomains() {
        val topLevelNotes = List(Stage5Limits.MAX_ANNOTATIONS_PER_PAGE / 2) {
            NoteSnapshotV1(0f, 0f, "page-note", 12f, false, 0f)
        }
        val nestedNotes = List(Stage5Limits.MAX_ANNOTATIONS_PER_PAGE / 2 + 1) { index ->
            PhotoImageNoteSnapshotV1(
                0f,
                0f,
                "image-note",
                12f,
                false,
                0f,
                0.5f,
                "image-note-$index"
            )
        }
        val pin = PhotoPinSnapshotV1(
            x = 0.5f,
            y = 0.5f,
            id = "nested-budget-pin",
            imageFileNames = listOf("photo.jpg"),
            imageNotes = mapOf("photo.jpg" to nestedNotes),
            imageShapes = emptyMap()
        )
        val snapshot = baseSnapshot(
            page = PageSnapshotV1(
                notes = topLevelNotes,
                photoPins = listOf(pin)
            )
        )
        assertRejected("typed nested annotation aggregate") { validateSnapshot(snapshot) }

        val rawSnapshot = JsonParser.parseString(Gson().toJson(snapshot)).asJsonObject
        assertRejected("canonical nested annotation aggregate") {
            validateCanonicalSnapshotTree(rawSnapshot, "canonical nested annotation aggregate")
        }
        val drivePayload = JsonObject().apply {
            addProperty("payloadSchemaVersion", LEGACY_PAYLOAD_SCHEMA_VERSION)
            addProperty("accountId", "account")
            addProperty("backupRootId", "root")
            addProperty("documentId", "document")
            add("snapshot", rawSnapshot)
            add("photoFiles", JsonObject())
        }
        assertRejected("Drive nested annotation aggregate") { validateDrivePayloadTree(drivePayload) }

        val legacyRoot = JsonParser.parseString(
            resourceText("stage0/legacy/fully_populated_page_data.json")
        ).asJsonObject
        val legacyPage = legacyRoot.getAsJsonObject("0")
        legacyPage.add("notes", JsonArray().apply {
            repeat(Stage5Limits.MAX_ANNOTATIONS_PER_PAGE / 2) {
                add(JsonObject().apply {
                    addProperty("x", 0f)
                    addProperty("y", 0f)
                    addProperty("text", "page-note")
                    addProperty("fontSize", 12f)
                    addProperty("isBold", false)
                    addProperty("rotation", 0f)
                })
            }
        })
        val legacyPin = legacyPage.getAsJsonArray("photoPins")[0].asJsonObject
        val legacyPhotoName = legacyPin.getAsJsonArray("imageFileNames")[0].asString
        legacyPin.getAsJsonObject("imageNotes").add(legacyPhotoName, JsonArray().apply {
            repeat(Stage5Limits.MAX_ANNOTATIONS_PER_PAGE / 2 + 1) { index ->
                add(JsonObject().apply {
                    addProperty("x", 0f)
                    addProperty("y", 0f)
                    addProperty("text", "image-note")
                    addProperty("fontSize", 12f)
                    addProperty("isBold", false)
                    addProperty("rotation", 0f)
                    addProperty("fontSizeRatio", 0.5f)
                    addProperty("id", "legacy-image-note-$index")
                })
            }
        })
        assertRejected("legacy nested annotation aggregate") {
            LegacyPageDataCodec.decode(Gson().toJson(legacyRoot))
        }
    }

    @Test
    fun rawCanonicalPhotoReferenceCount_isSharedByCanonicalDriveAndPendingBoundaries() {
        val pages = linkedMapOf<Int, PageSnapshotV1>()
        for (pageIndex in 0 until 4) {
            pages[pageIndex] = PageSnapshotV1(
                photoPins = List(Stage5Limits.MAX_PHOTO_PINS_PER_PAGE) { pinIndex ->
                    PhotoPinSnapshotV1(
                        x = 0.5f,
                        y = 0.5f,
                        id = "raw-$pageIndex-$pinIndex",
                        imageFileNames = listOf("photo.jpg"),
                        imageNotes = emptyMap(),
                        imageShapes = emptyMap()
                    )
                }
            )
        }
        pages[4] = PageSnapshotV1(
            photoPins = listOf(
                PhotoPinSnapshotV1(
                    x = 0.5f,
                    y = 0.5f,
                    id = "raw-4-0",
                    imageFileNames = listOf("photo.jpg"),
                    imageNotes = emptyMap(),
                    imageShapes = emptyMap()
                )
            )
        )
        val rawSnapshot = Gson().toJson(baseSnapshot(pages = pages))

        assertRejected("canonical repeated photo reference count") {
            decodeValidatedSnapshotJson(Gson(), rawSnapshot, "canonical repeated photos")
        }

        val drivePayload = JsonObject().apply {
            addProperty("payloadSchemaVersion", LEGACY_PAYLOAD_SCHEMA_VERSION)
            addProperty("accountId", "account")
            addProperty("backupRootId", "root")
            addProperty("documentId", "document")
            add("snapshot", JsonParser.parseString(rawSnapshot))
            add("photoFiles", JsonObject())
        }
        assertRejected("Drive repeated photo reference count") {
            validateDrivePayloadTree(drivePayload)
        }

        assertRejected("pending repeated photo reference count") {
            decodeValidatedSnapshotJson(Gson(), rawSnapshot, "pending upload snapshot")
        }
    }

    @Test
    fun rawDriveValidation_rejectsDescriptorPixelProductBeforeDtoMaterialization() {
        val payload = JsonObject().apply {
            addProperty("payloadSchemaVersion", CURRENT_PAYLOAD_SCHEMA_VERSION)
            addProperty("accountId", "account")
            addProperty("backupRootId", "root")
            addProperty("documentId", "document")
            add("snapshot", JsonParser.parseString(Gson().toJson(baseSnapshot())))
            add("photoFiles", JsonObject())
            add("photoDescriptors", JsonObject().apply {
                add("photo.jpg", JsonObject().apply {
                    addProperty("byteCount", 1)
                    addProperty("sha256", "0".repeat(64))
                    addProperty("mimeType", "image/jpeg")
                    addProperty("width", Stage5Limits.MAX_IMAGE_WIDTH)
                    addProperty("height", Stage5Limits.MAX_IMAGE_HEIGHT)
                })
            })
        }
        assertRejected { validateDrivePayloadTree(payload) }
    }

    @Test
    fun rawMetadataValidation_rejectsUnknownAndRemoteOnlyPendingReasons() {
        listOf("NOT_A_REASON", "REMOTE_CHECK", "REMOTE_ACCEPTANCE").forEach { reason ->
            val metadata = JsonParser.parseString(
                """
                {"schemaVersion":1,"accountId":"account","backupRootId":"root",
                 "documentId":"document","pendingUploadReason":"$reason"}
                """.trimIndent()
            ).asJsonObject
            assertRejected("pending reason $reason") { validateSyncMetadataTree(metadata) }
        }
    }

    @Test
    fun payloadSchema_rejectsMissingDescriptorAndUnsupportedFutureVersions() {
        requireSupportedPayloadSchemaVersion(null, descriptorsPresent = false)
        requireSupportedPayloadSchemaVersion(LEGACY_PAYLOAD_SCHEMA_VERSION, descriptorsPresent = false)
        requireSupportedPayloadSchemaVersion(CURRENT_PAYLOAD_SCHEMA_VERSION, descriptorsPresent = true)

        assertRejected { requireSupportedPayloadSchemaVersion(CURRENT_PAYLOAD_SCHEMA_VERSION, false) }
        assertRejected { requireSupportedPayloadSchemaVersion(LEGACY_PAYLOAD_SCHEMA_VERSION, true) }
        assertRejected { requireSupportedPayloadSchemaVersion(CURRENT_PAYLOAD_SCHEMA_VERSION + 1, true) }
        assertRejected { requireSupportedPayloadSchemaVersion(1, false) }
    }

    @Test
    fun rawCanonicalSnapshotBoundary_rejectsMissingNestedPrimitiveBeforeGsonDefaultsIt() {
        val snapshot = baseSnapshot(
            page = PageSnapshotV1(
                notes = listOf(NoteSnapshotV1(0f, 0f, "required", 12f, false, 0f))
            )
        )
        val root = JsonParser.parseString(Gson().toJson(snapshot)).asJsonObject
        root.getAsJsonObject("pages")
            .getAsJsonObject("0")
            .getAsJsonArray("notes")
            .first()
            .asJsonObject
            .remove("fontSize")

        // Gson would otherwise materialize the missing Float as 0.0f and the
        // post-materialization validator would accept that default.
        assertRejected("missing nested note primitive") {
            decodeValidatedSnapshotJson(Gson(), root.toString(), "versioned snapshot")
        }

        val missingDomain = JsonParser.parseString(Gson().toJson(snapshot)).asJsonObject
        missingDomain.getAsJsonObject("pages").getAsJsonObject("0").remove("photoPins")
        assertRejected("missing required page domain") {
            decodeValidatedSnapshotJson(Gson(), missingDomain.toString(), "versioned snapshot")
        }
    }

    @Test
    fun boundedReadersAndBase64_rejectLimitPlusOneMalformedAndNonUtf8Input() {
        assertEquals(
            "abc",
            readBoundedUtf8(ByteArrayInputStream("abc".toByteArray()), maxBytes = 3, label = "test JSON")
        )
        assertRejected {
            readBoundedUtf8(ByteArrayInputStream("abcd".toByteArray()), maxBytes = 3, label = "test JSON")
        }
        assertRejected {
            readBoundedUtf8(ByteArrayInputStream(byteArrayOf(0xC3.toByte())), maxBytes = 3, label = "test JSON")
        }
        assertEquals(
            "photo".toByteArray(StandardCharsets.UTF_8).toList(),
            decodeBoundedBase64("cGhvdG8=", "test photo").toList()
        )
        assertRejected { decodeBoundedBase64("not base64?", "test photo") }
        assertRejected { requireEncodedPhotoLength(Stage5Limits.MAX_BASE64_CHARS + 1, "test photo") }
    }

    @Test
    fun boundedReaders_failClosedOnRepeatedZeroLengthReads() {
        val stalled = object : InputStream() {
            override fun read(): Int = 0

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertRejected("stalled bounded reader") {
            readBoundedBytes(stalled, 128, "stalled input")
        }
    }

    @Test
    fun legacyOutboundCodec_validatesTypedModelsBeforeEncoding() {
        val safePage = PageData(
            paths = emptyList(),
            measurements = emptyList(),
            notes = emptyList(),
            photoPins = emptyList(),
            scale = null
        )
        assertRejected("negative outbound page key") {
            LegacyPageDataCodec.encode(mapOf(-1 to safePage))
        }
        assertRejected("outbound non-finite note") {
            LegacyPageDataCodec.encode(
                mapOf(0 to safePage.copy(notes = listOf(Note(0f, 0f, "bad", Float.NaN, false, 0f))))
            )
        }
        assertRejected("outbound unsafe photo filename") {
            LegacyPageDataCodec.encode(
                mapOf(
                    0 to safePage.copy(
                        photoPins = listOf(
                            PhotoPin(
                                x = 0.5f,
                                y = 0.5f,
                                id = "pin",
                                imageFileNames = mutableListOf("../escape.jpg")
                            )
                        )
                    )
                )
            )
        }

        val nestedImageNotes = List(Stage5Limits.MAX_ANNOTATIONS_PER_PAGE / 2 + 1) { index ->
            PhotoImageNote(0f, 0f, "image-note", 12f, false, 0f, 0.5f, "typed-image-note-$index")
        }
        val aggregatePage = safePage.copy(
            notes = List(Stage5Limits.MAX_ANNOTATIONS_PER_PAGE / 2) {
                Note(0f, 0f, "page-note", 12f, false, 0f)
            },
            photoPins = listOf(
                PhotoPin(
                    x = 0.5f,
                    y = 0.5f,
                    id = "aggregate-pin",
                    imageFileNames = mutableListOf("photo.jpg"),
                    imageNotes = mutableMapOf("photo.jpg" to nestedImageNotes.toMutableList())
                )
            )
        )
        assertRejected("outbound nested annotation aggregate") {
            LegacyPageDataCodec.encode(mapOf(0 to aggregatePage))
        }
    }

    @Test
    fun photoBase64Ceiling_isSymmetricForProducerAndConsumer() {
        val expected = ((Stage5Limits.MAX_PHOTO_BYTES.toLong() + 2L) / 3L * 4L).toInt()
        assertEquals(expected, Stage5Limits.MAX_BASE64_CHARS)

        val encoded = encodeBoundedBase64(byteArrayOf(0, 1, 2, 3), "test photo")
        assertEquals(byteArrayOf(0, 1, 2, 3).toList(), decodeBoundedBase64(encoded, "test photo").toList())
        requireEncodedPhotoLength(Stage5Limits.MAX_BASE64_CHARS, "maximum encoded photo")
        assertRejected { requireEncodedPhotoLength(Stage5Limits.MAX_BASE64_CHARS + 1, "oversized encoded photo") }
    }

    @Test
    fun driveQueryLiteral_escapesQuotesAndBackslashesAsOneLiteral() {
        assertEquals("'a\\'b\\\\c'", escapeDriveQueryLiteral("a'b\\c"))
        assertTrue(escapeDriveQueryLiteral("x' or trashed = false") == "'x\\' or trashed = false'")
        assertRejected { escapeDriveQueryLiteral("") }
        assertRejected { escapeDriveQueryLiteral("x".repeat(Stage5Limits.MAX_STRING_CHARS + 1)) }
    }

    @Test
    fun snapshotValidation_rejectsSchemaMissingRequiredEnumsNonFiniteNegativeAndOversizedValues() {
        val invalidSchema = Gson().fromJson(
            """{"schemaVersion":99,"snapshotRevision":0,"source":{"sourceUri":"content://source"},"pages":{}}""",
            DocumentSnapshotV1::class.java
        )
        assertRejected { validateSnapshot(invalidSchema) }
        assertRejected { validateSnapshot(baseSnapshot().copy(snapshotRevision = -1L)) }
        assertRejected {
            validateSnapshot(
                baseSnapshot(
                    page = PageSnapshotV1(
                        notes = listOf(NoteSnapshotV1(0f, 0f, "note", 12f, false, Float.NaN))
                    )
                )
            )
        }
        assertRejected {
            validateSnapshot(baseSnapshot(page = PageSnapshotV1(scale = PageScaleSnapshotV1(-1f))))
        }
        assertRejected {
            validateSnapshot(
                baseSnapshot(
                    source = DocumentSourceIdentityV1("content://source", providerMetadata = mapOf("x" to "y".repeat(Stage5Limits.MAX_STRING_CHARS + 1)))
                )
            )
        }
    }

    @Test
    fun snapshotValidation_enforcesPageAnnotationPathAndStringLimits() {
        val tooManyPages = (0..Stage5Limits.MAX_PAGES).associateWith { PageSnapshotV1() }
        assertRejected { validateSnapshot(baseSnapshot(pages = tooManyPages)) }

        val tooManyNotes = List(Stage5Limits.MAX_ANNOTATIONS_PER_PAGE + 1) {
            NoteSnapshotV1(0f, 0f, "note", 12f, false, 0f)
        }
        assertRejected { validateSnapshot(baseSnapshot(page = PageSnapshotV1(notes = tooManyNotes))) }

        val tooManyPoints = List(Stage5Limits.MAX_PATH_POINTS + 1) { PointSnapshotV1(0f, 0f) }
        assertRejected {
            validateSnapshot(
                baseSnapshot(
                    page = PageSnapshotV1(
                        paths = listOf(DrawnPathSnapshotV1(tooManyPoints, 0, 1f, false))
                    )
                )
            )
        }

        assertRejected {
            validateSnapshot(
                baseSnapshot(
                    page = PageSnapshotV1(
                        notes = listOf(NoteSnapshotV1(0f, 0f, "x".repeat(Stage5Limits.MAX_TEXT_CHARS + 1), 12f, false, 0f))
                    )
                )
            )
        }
    }

    @Test
    fun imageValidation_acceptsCommittedHighResolutionPhotoAndRejectsIntegrityFailures() {
        val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
        val validated = validatePhotoBytes(bytes)
        val independentlyDecoded = ImageIoPhotoDecodeProbe.probe(bytes)
        assertEquals(4032, independentlyDecoded.width)
        assertEquals(3024, independentlyDecoded.height)
        assertEquals(4032, validated.descriptor.width)
        assertEquals(3024, validated.descriptor.height)
        assertEquals(bytes.size.toLong(), validated.descriptor.byteCount)
        assertEquals(sha256Hex(bytes), validated.descriptor.sha256)

        assertRejected { validatePhotoBytes(bytes.copyOf(bytes.size - 1)) }
        assertRejected {
            ImageIoPhotoDecodeProbe.probe(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x00, 0xFF.toByte(), 0xD9.toByte())
            )
        }
        assertRejected { validatePhotoBytes(byteArrayOf(1, 2, 3, 4)) }
        assertRejected {
            validatePhotoBytes(
                bytes,
                expected = validated.descriptor.copy(sha256 = "0".repeat(64))
            )
        }
        assertRejected {
            validatePhotoBytes(
                byteArrayOf(1),
                imageProbe = object : ImageProbe {
                    override fun probe(bytes: ByteArray): ImageInfo =
                        ImageInfo("image/jpeg", Stage5Limits.MAX_IMAGE_WIDTH + 1, 1)
                }
            )
        }
        val permissiveProbe = object : PhotoDecodeProbe {
            override fun probe(bytes: ByteArray): ImageInfo = ImageInfo("image/jpeg", 1, 1)
        }
        assertRejected("common container completeness boundary") {
            validatePhotoBytes(bytes.copyOf(bytes.size - 1), imageProbe = permissiveProbe)
        }
    }

    @Test
    fun imageValidation_acceptsRealPngWithExactBytesAndRejectsTruncatedOrTrailingContainer() {
        val bytes = realPngBytes()
        val validated = validatePhotoBytes(bytes)

        assertEquals("image/png", validated.descriptor.mimeType)
        assertEquals(2, validated.descriptor.width)
        assertEquals(3, validated.descriptor.height)
        assertEquals(bytes.size.toLong(), validated.descriptor.byteCount)
        assertEquals(sha256Hex(bytes), validated.descriptor.sha256)
        assertEquals(bytes.toList(), validated.bytes.toList())
        val decoded = requireNotNull(ImageIO.read(bytes.inputStream()))
        assertEquals(2, decoded.width)
        assertEquals(3, decoded.height)

        assertRejected("PNG missing IEND chunk") {
            validatePhotoBytes(bytes.copyOf(bytes.size - 12))
        }
        assertRejected("PNG truncated IEND chunk") {
            validatePhotoBytes(bytes.copyOf(bytes.size - 1))
        }
        assertRejected("PNG trailing byte after IEND") {
            validatePhotoBytes(bytes + byteArrayOf(0))
        }
    }

    @Test
    fun photoValidation_requiresExactReferenceAndDescriptorKeySets() {
        val snapshot = baseSnapshot(photoNames = listOf("photo.jpg"))
        val bytes = HighResolutionPhonePhotoFixture.jpegBytes()
        val descriptor = validatePhotoBytes(bytes).descriptor
        val files = mapOf("photo.jpg" to bytes)
        assertEquals(setOf("photo.jpg"), validatePhotoSet(snapshot, files).keys)
        assertRejected { validatePhotoSet(snapshot, emptyMap()) }
        assertRejected { validatePhotoSet(snapshot, files + ("extra.jpg" to bytes)) }
        assertRejected { validatePhotoSet(snapshot, files, mapOf("other.jpg" to descriptor)) }
        assertEquals(
            descriptor,
            validatePhotoSet(snapshot, files, mapOf("photo.jpg" to descriptor)).getValue("photo.jpg").descriptor
        )
    }

    private fun baseSnapshot(
        source: DocumentSourceIdentityV1 = DocumentSourceIdentityV1("content://stage5/source", "plan.pdf"),
        pages: Map<Int, PageSnapshotV1> = mapOf(0 to basePage(emptyList())),
        page: PageSnapshotV1? = null,
        photoNames: List<String> = emptyList()
    ): DocumentSnapshotV1 {
        val actualPages = if (page != null) mapOf(0 to page) else if (photoNames.isNotEmpty()) {
            mapOf(0 to basePage(photoNames))
        } else pages
        return DocumentSnapshotV1(
            schemaVersion = 1,
            snapshotRevision = 0L,
            source = source,
            pages = actualPages
        )
    }

    private fun basePage(photoNames: List<String>): PageSnapshotV1 = PageSnapshotV1(
        photoPins = if (photoNames.isEmpty()) emptyList() else listOf(
            PhotoPinSnapshotV1(
                x = 0.5f,
                y = 0.5f,
                id = "pin-stage5",
                imageFileNames = photoNames,
                imageNotes = emptyMap(),
                imageShapes = emptyMap()
            )
        )
    )

    private fun realPngBytes(): ByteArray {
        val image = BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xFFFF0000.toInt())
        image.setRGB(1, 0, 0xFF00FF00.toInt())
        image.setRGB(0, 1, 0xFF0000FF.toInt())
        image.setRGB(1, 1, 0xFFFFFFFF.toInt())
        image.setRGB(0, 2, 0x00000000)
        image.setRGB(1, 2, 0xFF123456.toInt())
        return ByteArrayOutputStream().use { output ->
            assertTrue("JVM must provide a PNG encoder", ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }

    private fun resourceText(path: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "missing resource $path" }
            .use { readBoundedUtf8(it, Stage5Limits.MAX_JSON_BYTES, path) }

    private fun assertRejected(label: String = "operation", block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: Stage5ValidationException) {
            rejected = true
        } catch (_: IOException) {
            rejected = true
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("$label must be rejected", rejected)
    }
}
