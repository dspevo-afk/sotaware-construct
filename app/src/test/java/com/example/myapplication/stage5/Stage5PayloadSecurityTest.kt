package com.example.myapplication.stage5

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
import com.example.myapplication.stage9b.PhotoAssetSet
import com.example.myapplication.stage9b.PhotoAsset
import com.example.myapplication.stage9b.copyPhotoAsset
import com.example.myapplication.stage9b.RemoteAssetDescriptor
import com.example.myapplication.stage9b.RemoteManifestCodec
import com.example.myapplication.stage9b.RemoteManifestValidationException
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.SyncScope
import com.example.myapplication.stage9b.testPhotoAssets
import com.example.myapplication.stage9b.validatePhotoAssets
import com.example.myapplication.stage7.BitmapBudgetPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
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
    fun currentSnapshotBoundary_rebuildsMalformedFixtureCasesAgainstSchema2() {
        val valid = baseSnapshot(
            page = PageSnapshotV1(
                paths = listOf(
                    DrawnPathSnapshotV1(
                        points = listOf(PointSnapshotV1(0f, 0f)),
                        colorArgb = 0,
                        isHighlighter = false,
                        strokeWidthRatio = 0.01f,
                        id = "path-1"
                    )
                )
            )
        )
        val root = JsonParser.parseString(Gson().toJson(valid)).asJsonObject

        assertRejected("retired snapshot schema") {
            decodeValidatedSnapshotJson(Gson(), root.deepCopy().apply { addProperty("schemaVersion", 1) }.toString(), "retired snapshot")
        }
        assertRejected("wrong snapshot primitive type") {
            decodeValidatedSnapshotJson(Gson(), root.deepCopy().apply { addProperty("snapshotRevision", "zero") }.toString(), "wrong type")
        }
        assertRejected("null required source") {
            decodeValidatedSnapshotJson(Gson(), root.deepCopy().apply { add("source", com.google.gson.JsonNull.INSTANCE) }.toString(), "null source")
        }
        assertRejected("unknown current shape enum") {
            val shaped = baseSnapshot(
                page = PageSnapshotV1(
                    shapes = listOf(
                        com.example.myapplication.stage1.ShapeSnapshotV1(
                            x = 0.5f,
                            y = 0.5f,
                            rotation = 0f,
                            type = com.example.myapplication.stage1.SnapshotShapeTypeV1.ARROW,
                            colorArgb = 0,
                            isFilled = false,
                            strokeWidthRatio = 0.01f,
                            widthRatio = 0.1f,
                            heightRatio = 0.1f,
                            id = "shape-1"
                        )
                    )
                )
            )
            val shapeJson = JsonParser.parseString(Gson().toJson(shaped)).asJsonObject
            shapeJson.getAsJsonObject("pages").getAsJsonObject("0")
                .getAsJsonArray("shapes").first().asJsonObject.addProperty("type", "TRIANGLE")
            decodeValidatedSnapshotJson(Gson(), shapeJson.toString(), "unknown shape")
        }
        assertRejected("non-finite current numeric value") {
            val nonFinite = baseSnapshot(
                page = PageSnapshotV1(
                    notes = listOf(NoteSnapshotV1(0f, 0f, "non-finite", false, Float.POSITIVE_INFINITY, 0.05f, "non-finite-note"))
                )
            )
            decodeValidatedSnapshotJson(
                Gson(),
                GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(nonFinite),
                "non-finite current"
            )
        }
        assertRejected("malformed current JSON") {
            decodeValidatedSnapshotJson(Gson(), "{\"schemaVersion\":2,", "malformed current")
        }
    }

    @Test
    fun currentSnapshotRawTreeBoundary_rejectsNestedOversizeMissingFieldsAndNonCanonicalPageKeys() {
        val valid = baseSnapshot(
            page = PageSnapshotV1(
                paths = listOf(
                    DrawnPathSnapshotV1(
                        points = listOf(PointSnapshotV1(0f, 0f)),
                        colorArgb = 0,
                        isHighlighter = false,
                        strokeWidthRatio = 0.01f,
                        id = "path-1"
                    )
                )
            )
        )
        val oversized = JsonParser.parseString(Gson().toJson(valid)).asJsonObject
        val points = oversized.getAsJsonObject("pages").getAsJsonObject("0")
            .getAsJsonArray("paths")[0].asJsonObject.getAsJsonArray("points")
        repeat(Stage5Limits.MAX_PATH_POINTS) { points.add(JsonParser.parseString("{\"x\":0,\"y\":0}")) }
        assertRejected("oversized nested current point array") {
            decodeValidatedSnapshotJson(Gson(), oversized.toString(), "oversized current")
        }

        val missingNestedPrimitive = JsonParser.parseString(Gson().toJson(valid)).asJsonObject
        missingNestedPrimitive.getAsJsonObject("pages").getAsJsonObject("0")
            .getAsJsonArray("paths")[0].asJsonObject
            .getAsJsonArray("points")[0].asJsonObject.remove("y")
        assertRejected("missing nested current primitive") {
            decodeValidatedSnapshotJson(Gson(), missingNestedPrimitive.toString(), "missing current")
        }

        val nonCanonicalPageKey = JsonParser.parseString(Gson().toJson(valid)).asJsonObject
            .getAsJsonObject("pages")
        val page = requireNotNull(nonCanonicalPageKey.remove("0"))
        nonCanonicalPageKey.add("1", page)
        nonCanonicalPageKey.add("01", page.deepCopy())
        assertRejected("non-canonical current page keys") {
            decodeValidatedSnapshotJson(Gson(), JsonParser.parseString(Gson().toJson(valid)).asJsonObject.apply { add("pages", nonCanonicalPageKey) }.toString(), "non-canonical pages")
        }

        val duplicatePageJson = """
            {"schemaVersion":2,"snapshotRevision":0,"source":{"sourceUri":"content://stage5/source","displayName":"plan.pdf","providerMetadata":{}},"pages":{"0":${Gson().toJson(page)},"0":${Gson().toJson(page)}}}
        """.trimIndent()
        assertRejected("duplicate current page key") {
            decodeValidatedSnapshotJson(Gson(), duplicatePageJson, "duplicate current page")
        }
    }

    @Test
    fun strictJsonBoundary_rejectsNestedDuplicateMembersBeforeAnyTreeMaterialization() {
        val duplicateCanonical = """
            {
              "schemaVersion":2,
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
            NoteSnapshotV1(0f, 0f, "page-note", false, 0f, 0.05f, "page-note-$it")
        }
        val nestedNotes = List(Stage5Limits.MAX_ANNOTATIONS_PER_PAGE / 2 + 1) { index ->
            PhotoImageNoteSnapshotV1(
                0f,
                0f,
                "image-note",
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
        val drivePayload = currentManifestTree().apply { add("snapshot", rawSnapshot) }
        assertManifestRejectedFor(drivePayload, "aggregate annotation")
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

        val drivePayload = currentManifestTree().apply { add("snapshot", JsonParser.parseString(rawSnapshot)) }
        assertManifestRejectedFor(drivePayload, "photo reference")

        assertRejected("pending repeated photo reference count") {
            decodeValidatedSnapshotJson(Gson(), rawSnapshot, "pending upload snapshot")
        }
    }

    @Test
    fun rawDriveValidation_rejectsDescriptorPixelProductBeforeDtoMaterialization() {
        val payload = currentManifestTree(baseSnapshot(photoNames = listOf("photo.png")))
        payload.getAsJsonObject("assets").getAsJsonObject("photo.png").apply {
            addProperty("width", Stage5Limits.MAX_IMAGE_WIDTH)
            addProperty("height", Stage5Limits.MAX_IMAGE_HEIGHT)
        }
        assertManifestRejectedFor(payload, "dimensions exceed")
    }

    @Test
    fun rawMetadataValidation_rejectsUnknownAndRemoteOnlyPendingReasons() {
        listOf("NOT_A_REASON", "REMOTE_CHECK", "REMOTE_ACCEPTANCE").forEach { reason ->
            val metadata = JsonParser.parseString(
                """
                {"schemaVersion":2,"accountId":"account","backupRootId":"root",
                 "documentId":"document","pendingUploadReason":"$reason"}
                """.trimIndent()
            ).asJsonObject
            assertRejected("pending reason $reason") { validateSyncMetadataTree(metadata) }
        }
    }

    @Test
    fun payloadSchema_requiresCurrentAssetsAndRejectsRetiredMissingAndFutureVersions() {
        val current = currentManifestTree()
        RemoteManifestCodec.decode(current.toString().toByteArray(), remoteScope)
        for (version in listOf(0, 1, 2, 4)) {
            assertManifestRejectedFor(current.deepCopy().apply { addProperty("manifestVersion", version) }, "unsupported Drive manifest version")
        }
        assertManifestRejectedFor(current.deepCopy().apply { remove("assets") }, "missing fields")
        assertRejected { RemoteManifestCodec.decode(current.deepCopy().apply {
            add("manifestVersion", com.google.gson.JsonNull.INSTANCE)
        }.toString().toByteArray(), remoteScope) }
    }

    @Test
    fun rawCanonicalSnapshotBoundary_rejectsMissingNestedPrimitiveBeforeGsonDefaultsIt() {
        val snapshot = baseSnapshot(
            page = PageSnapshotV1(
                notes = listOf(NoteSnapshotV1(0f, 0f, "required", false, 0f, 0.05f, "required-note"))
            )
        )
        val root = JsonParser.parseString(Gson().toJson(snapshot)).asJsonObject
        root.getAsJsonObject("pages")
            .getAsJsonObject("0")
            .getAsJsonArray("notes")
            .first()
            .asJsonObject
            .remove("fontSizeRatio")

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
    fun boundedReaders_rejectLimitPlusOneNonUtf8AndRetiredInlinePhotoFields() {
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
        // Inline payloads are no longer a second decoding API. Even small,
        // otherwise-valid base64 is rejected without changing the input.
        for (inline in listOf("cGhvdG8=", "not base64?")) {
            val payload = currentManifestTree().apply {
                add("photoFiles", JsonObject().apply { addProperty("photo.png", inline) })
            }
            assertManifestRejectedFor(payload, "unknown fields")
        }
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
    fun currentSnapshotBoundary_validatesTypedModelsBeforeEncodingOrPersistence() {
        assertRejected("negative outbound page key") {
            validateSnapshot(baseSnapshot(pages = mapOf(-1 to PageSnapshotV1())))
        }
        assertRejected("outbound non-finite note") {
            validateSnapshot(
                baseSnapshot(
                    page = PageSnapshotV1(
                        notes = listOf(NoteSnapshotV1(0f, 0f, "bad", false, Float.NaN, 0.05f, "bad-note"))
                    )
                )
            )
        }
        assertRejected("outbound unsafe photo filename") {
            validateSnapshot(
                baseSnapshot(
                    page = PageSnapshotV1(
                        photoPins = listOf(
                            PhotoPinSnapshotV1(
                                x = 0.5f,
                                y = 0.5f,
                                id = "pin",
                                imageFileNames = listOf("../escape.jpg"),
                                imageNotes = emptyMap(),
                                imageShapes = emptyMap()
                            )
                        )
                    )
                )
            )
        }

        val nestedImageNotes = List(Stage5Limits.MAX_ANNOTATIONS_PER_PAGE / 2 + 1) { index ->
            PhotoImageNoteSnapshotV1(0f, 0f, "image-note", false, 0f, 0.5f, "typed-image-note-$index")
        }
        assertRejected("outbound nested annotation aggregate") {
            validateSnapshot(
                baseSnapshot(
                    page = PageSnapshotV1(
                        notes = List(Stage5Limits.MAX_ANNOTATIONS_PER_PAGE / 2) { index ->
                            NoteSnapshotV1(0f, 0f, "page-note", false, 0f, 0.05f, "page-note-$index")
                        },
                        photoPins = listOf(
                            PhotoPinSnapshotV1(
                                x = 0.5f,
                                y = 0.5f,
                                id = "aggregate-pin",
                                imageFileNames = listOf("photo.jpg"),
                                imageNotes = mapOf("photo.jpg" to nestedImageNotes),
                                imageShapes = emptyMap()
                            )
                        )
                    )
                )
            )
        }
    }

    @Test
    fun photoStreamCeilingAndIdentity_areSymmetricForProducerAndConsumer() {
        val bytes = realPngBytes()
        val original = bytes.copyOf()
        val asset = testPhotoAssets(mapOf("photo.png" to bytes)).getValue("photo.png")
        val output = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), copyPhotoAsset(asset, output))
        assertEquals(bytes.toList(), output.toByteArray().toList())
        for (count in listOf(0L, Stage5Limits.MAX_PHOTO_BYTES.toLong() + 1L)) {
            assertRejected("invalid asset byte ceiling $count") {
                PhotoAssetSet.of(mapOf("photo.png" to object : PhotoAsset {
                    override val descriptor = asset.descriptor.copy(byteCount = count)
                    override fun open(): InputStream = error("invalid descriptor must fail before opening")
                }))
            }
        }
        for (content in listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0), bytes.copyOf().also { it[0] = 0 })) {
            var closed = false
            var maximumRead = 0
            val corrupt = object : PhotoAsset {
                override val descriptor = asset.descriptor
                override fun open(): InputStream = object : ByteArrayInputStream(content) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        maximumRead = maxOf(maximumRead, length)
                        return super.read(buffer, offset, length)
                    }
                    override fun close() { closed = true; super.close() }
                }
            }
            assertRejected("truncated, extra or changed stream content") { copyPhotoAsset(corrupt, ByteArrayOutputStream()) }
            assertTrue("a rejected source stream must be closed", closed)
            assertTrue("stream copy exceeded its bounded buffer", maximumRead <= 64 * 1024)
        }
        assertEquals(original.toList(), bytes.toList())
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
                        notes = listOf(NoteSnapshotV1(0f, 0f, "note", false, Float.NaN, 0.05f, "nonfinite-note"))
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
            NoteSnapshotV1(0f, 0f, "note", false, 0f, 0.05f, "note-$it")
        }
        assertRejected { validateSnapshot(baseSnapshot(page = PageSnapshotV1(notes = tooManyNotes))) }

        val tooManyPoints = List(Stage5Limits.MAX_PATH_POINTS + 1) { PointSnapshotV1(0f, 0f) }
        assertRejected {
            validateSnapshot(
                baseSnapshot(
                    page = PageSnapshotV1(
                        paths = listOf(DrawnPathSnapshotV1(tooManyPoints, 0, false, 0.01f, "too-many-path"))
                    )
                )
            )
        }

        assertRejected {
            validateSnapshot(
                baseSnapshot(
                    page = PageSnapshotV1(
                        notes = listOf(NoteSnapshotV1(0f, 0f, "x".repeat(Stage5Limits.MAX_TEXT_CHARS + 1), false, 0f, 0.05f, "oversized-note"))
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
    fun defaultImageProbe_samplesHighResolutionDecodeAndKeepsSmallInputValid() {
        val highResolutionBytes = HighResolutionPhonePhotoFixture.jpegBytes()
        val highResolutionPlan = requireNotNull(
            BitmapBudgetPolicy.photoDecodePlan(
                HighResolutionPhonePhotoFixture.WIDTH,
                HighResolutionPhonePhotoFixture.HEIGHT
            )
        )
        var highResolutionReleaseCount = 0
        val highResolutionSampledWidth =
            (HighResolutionPhonePhotoFixture.WIDTH + highResolutionPlan.inSampleSize - 1) /
                highResolutionPlan.inSampleSize
        val highResolutionSampledHeight =
            (HighResolutionPhonePhotoFixture.HEIGHT + highResolutionPlan.inSampleSize - 1) /
                highResolutionPlan.inSampleSize
        val highResolutionDecoder = RecordingPhotoImageDecoder(
            bounds = PhotoImageBounds(
                HighResolutionPhonePhotoFixture.WIDTH,
                HighResolutionPhonePhotoFixture.HEIGHT
            ),
            decoded = DecodedPhotoImage(
                width = highResolutionSampledWidth,
                height = highResolutionSampledHeight,
                allocationBytes = highResolutionSampledWidth.toLong() *
                    highResolutionSampledHeight.toLong() * BitmapBudgetPolicy.BYTES_PER_ARGB_8888_PIXEL,
                isArgb8888 = true
            ) { highResolutionReleaseCount++ }
        )

        val highResolutionInfo = probePhotoBytesWithDecoder(highResolutionBytes, highResolutionDecoder)

        assertEquals(HighResolutionPhonePhotoFixture.WIDTH, highResolutionInfo.width)
        assertEquals(HighResolutionPhonePhotoFixture.HEIGHT, highResolutionInfo.height)
        assertEquals(highResolutionPlan.inSampleSize, requireNotNull(highResolutionDecoder.requestedSampleSize))
        assertTrue(requireNotNull(highResolutionDecoder.requestedSampleSize) > 1)
        assertEquals(1, highResolutionReleaseCount)

        val smallBytes = realPngBytes()
        var smallReleaseCount = 0
        val smallDecoder = RecordingPhotoImageDecoder(
            bounds = PhotoImageBounds(2, 3),
            decoded = DecodedPhotoImage(
                width = 2,
                height = 3,
                allocationBytes = 2L * 3L * BitmapBudgetPolicy.BYTES_PER_ARGB_8888_PIXEL,
                isArgb8888 = true
            ) { smallReleaseCount++ }
        )

        val smallInfo = probePhotoBytesWithDecoder(smallBytes, smallDecoder)

        assertEquals(2, smallInfo.width)
        assertEquals(3, smallInfo.height)
        assertEquals(1, requireNotNull(smallDecoder.requestedSampleSize))
        assertEquals(1, smallReleaseCount)
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
        val files = testPhotoAssets(mapOf("photo.jpg" to bytes))
        assertEquals(setOf("photo.jpg"), validatePhotoAssets(snapshot, files).keys)
        assertRejected { validatePhotoAssets(snapshot, PhotoAssetSet.EMPTY) }
        assertRejected { validatePhotoAssets(snapshot, testPhotoAssets(mapOf("photo.jpg" to bytes, "extra.jpg" to bytes))) }
        assertRejected { validatePhotoAssets(snapshot, files, mapOf("other.jpg" to descriptor)) }
        assertEquals(
            descriptor,
            validatePhotoAssets(snapshot, files, mapOf("photo.jpg" to descriptor)).getValue("photo.jpg").descriptor
        )
    }

    private val remoteScope = SyncScope("account", "root", DocumentId.parse("00000000-0000-0000-0000-000000000051"))

    private fun currentManifestTree(snapshot: DocumentSnapshotV1 = baseSnapshot()): JsonObject {
        val descriptor = validatePhotoBytes(realPngBytes()).descriptor
        val assets = requiredPhotoNames(snapshot).associateWith { name ->
            RemoteAssetDescriptor("fixture-" + name.replace(".", "-"), descriptor.byteCount, descriptor.sha256,
                descriptor.mimeType, descriptor.width, descriptor.height)
        }
        return JsonParser.parseString(RemoteManifestCodec.encode(remoteScope, "plan.pdf", snapshot, assets)
            .toString(Charsets.UTF_8)).asJsonObject
    }

    private fun assertManifestRejectedFor(tree: JsonObject, expectedReason: String) {
        val input = tree.toString().toByteArray(Charsets.UTF_8)
        val original = input.copyOf()
        val failure = try {
            RemoteManifestCodec.decode(input, remoteScope)
            throw AssertionError("manifest must be rejected for $expectedReason")
        } catch (error: RemoteManifestValidationException) { error }
        val reasons = generateSequence<Throwable>(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
        assertTrue("wrong rejection reason: $reasons", reasons.contains(expectedReason))
        assertEquals(original.toList(), input.toList())
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
            schemaVersion = 2,
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

    private class RecordingPhotoImageDecoder(
        private val bounds: PhotoImageBounds,
        private val decoded: DecodedPhotoImage
    ) : PhotoImageDecoder {
        var requestedSampleSize: Int? = null

        override fun decodeBounds(bytes: ByteArray): PhotoImageBounds = bounds

        override fun decode(bytes: ByteArray, inSampleSize: Int): DecodedPhotoImage {
            requestedSampleSize = inSampleSize
            return decoded
        }
    }

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
