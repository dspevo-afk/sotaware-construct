package com.example.myapplication.stage5

import com.example.myapplication.DrawnPath
import com.example.myapplication.Measurement
import com.example.myapplication.Note
import com.example.myapplication.PageData
import com.example.myapplication.PageScale
import com.example.myapplication.PhotoImageNote
import com.example.myapplication.PhotoPin
import com.example.myapplication.Point
import com.example.myapplication.Shape
import com.example.myapplication.ShapeType
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Typed compatibility codec for the pre-envelope JSON shape (schema 0).
 * Current field names remain unchanged. It intentionally does not use Gson's
 * raw Map<String, Any> conversion, and it materializes runtime models only
 * after all required fields and limits have been checked.
 */
object LegacyPageDataCodec {
    private val gson: Gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    private class LegacyPhotoReferenceCounter {
        var count: Int = 0

        fun add(amount: Int, label: String) {
            count += amount
            if (count > Stage5Limits.MAX_TOTAL_PHOTOS) {
                throw Stage5ValidationException("$label photo reference count exceeds limit")
            }
        }
    }

    private class LegacyAnnotationBudget(private val label: String) {
        private var count = 0L

        fun add(amount: Int, kind: String) {
            if (amount < 0 || count > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE - amount.toLong()) {
                throw Stage5ValidationException("$label $kind exceed the aggregate annotation limit")
            }
            count += amount.toLong()
        }
    }

    fun encode(pageData: Map<Int, PageData>): String {
        if (pageData.size > Stage5Limits.MAX_PAGES) {
            throw Stage5ValidationException("page count exceeds limit")
        }
        pageData.keys.forEach {
            if (it < 0 || it >= 1_000_000) throw Stage5ValidationException("page index is out of range")
        }
        val uniquePhotoNames = hashSetOf<String>()
        val photoReferenceCount = LegacyPhotoReferenceCounter()
        val dto = pageData.toSortedMap().mapKeys { it.key.toString() }.mapValues { (key, page) ->
            validateLegacyPageData(page, "legacy page $key", uniquePhotoNames, photoReferenceCount)
            page.toDto()
        }
        val bytes = encodeBoundedJson(gson, dto, Stage5Limits.MAX_JSON_BYTES, "serialized legacy JSON")
        return bytes.toString(StandardCharsets.UTF_8)
    }

    /** Outbound validation is as strict as the inbound V0 tree boundary. */
    private fun validateLegacyPageData(
        page: PageData,
        label: String,
        uniquePhotoNames: MutableSet<String>,
        photoReferenceCount: LegacyPhotoReferenceCounter
    ) {
        val budget = LegacyAnnotationBudget(label)
        budget.add(page.paths.size, "paths")
        budget.add(page.measurements.size, "measurements")
        budget.add(page.notes.size, "notes")
        budget.add(page.photoPins.size, "photo pins")
        budget.add(page.shapes.size, "shapes")
        if (page.scale != null) budget.add(1, "scale")
        if (page.paths.size > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE ||
            page.measurements.size > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE ||
            page.notes.size > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE ||
            page.photoPins.size > Stage5Limits.MAX_PHOTO_PINS_PER_PAGE ||
            page.shapes.size > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE
        ) throw Stage5ValidationException("$label annotation list exceeds limit")

        page.paths.forEachIndexed { index, path ->
            if (path.points.isEmpty() || path.points.size > Stage5Limits.MAX_PATH_POINTS) {
                throw Stage5ValidationException("$label path $index point count is out of range")
            }
            path.points.forEachIndexed { pointIndex, point ->
                validateLegacyTypedPoint(point, "$label path $index point $pointIndex")
            }
            validateLegacyTypedFloat(path.strokeWidth, "$label path $index strokeWidth", min = 0f)
        }
        page.measurements.forEachIndexed { index, measurement ->
            validateLegacyTypedPoint(measurement.p1, "$label measurement $index p1")
            validateLegacyTypedPoint(measurement.p2, "$label measurement $index p2")
            validateLegacyTypedText(measurement.text, "$label measurement $index text")
        }
        page.notes.forEachIndexed { index, note ->
            validateLegacyTypedFloat(note.x, "$label note $index x")
            validateLegacyTypedFloat(note.y, "$label note $index y")
            validateLegacyTypedText(note.text, "$label note $index text")
            validateLegacyTypedFloat(note.fontSize, "$label note $index fontSize", min = 0f)
            validateLegacyTypedFloat(note.rotation, "$label note $index rotation")
        }
        page.photoPins.forEachIndexed { index, pin ->
            validateLegacyTypedPhotoPin(pin, "$label photo pin $index", uniquePhotoNames, photoReferenceCount, budget)
        }
        page.shapes.forEachIndexed { index, shape ->
            validateLegacyTypedShape(shape, "$label shape $index")
        }
        page.scale?.let { validateLegacyTypedFloat(it.pixelsPerFoot, "$label scale pixelsPerFoot", min = Float.MIN_VALUE) }
    }

    private fun validateLegacyTypedPhotoPin(
        pin: PhotoPin,
        label: String,
        uniquePhotoNames: MutableSet<String>,
        photoReferenceCount: LegacyPhotoReferenceCounter,
        budget: LegacyAnnotationBudget
    ) {
        validateLegacyTypedFloat(pin.x, "$label x")
        validateLegacyTypedFloat(pin.y, "$label y")
        validateLegacyTypedId(pin.id, "$label id")
        if (pin.imageFileNames.size > Stage5Limits.MAX_PHOTOS_PER_PIN) {
            throw Stage5ValidationException("$label photo list exceeds limit")
        }
        photoReferenceCount.add(pin.imageFileNames.size, label)
        val names = hashSetOf<String>()
        pin.imageFileNames.forEachIndexed { index, name ->
            validatePhotoFileName(name)
            if (!names.add(name)) throw Stage5ValidationException("$label has duplicate photo references")
            uniquePhotoNames.add(name)
        }
        if (pin.imageNotes.size > Stage5Limits.MAX_PHOTOS_PER_PIN || pin.imageShapes.size > Stage5Limits.MAX_PHOTOS_PER_PIN) {
            throw Stage5ValidationException("$label photo annotation map exceeds limit")
        }
        if (!names.containsAll(pin.imageNotes.keys) || !names.containsAll(pin.imageShapes.keys)) {
            throw Stage5ValidationException("$label has an unknown photo annotation reference")
        }
        pin.imageNotes.forEach { (name, notes) ->
            validatePhotoFileName(name)
            if (notes.size > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE) throw Stage5ValidationException("$label image notes exceed limit")
            budget.add(notes.size, "image notes")
            notes.forEachIndexed { index, note -> validateLegacyTypedImageNote(note, "$label photo $name note $index") }
        }
        pin.imageShapes.forEach { (name, shapes) ->
            validatePhotoFileName(name)
            if (shapes.size > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE) throw Stage5ValidationException("$label image shapes exceed limit")
            budget.add(shapes.size, "image shapes")
            shapes.forEachIndexed { index, shape -> validateLegacyTypedShape(shape, "$label photo $name shape $index") }
        }
    }

    private fun validateLegacyTypedImageNote(note: PhotoImageNote, label: String) {
        validateLegacyTypedFloat(note.x, "$label x")
        validateLegacyTypedFloat(note.y, "$label y")
        validateLegacyTypedText(note.text, "$label text")
        validateLegacyTypedFloat(note.fontSize, "$label fontSize", min = 0f)
        validateLegacyTypedFloat(note.rotation, "$label rotation")
        validateLegacyTypedFloat(note.fontSizeRatio, "$label fontSizeRatio", min = 0f, max = Stage5Limits.MAX_RATIO)
        validateLegacyTypedId(note.id, "$label id")
    }

    private fun validateLegacyTypedShape(shape: Shape, label: String) {
        validateLegacyTypedFloat(shape.x, "$label x")
        validateLegacyTypedFloat(shape.y, "$label y")
        validateLegacyTypedFloat(shape.width, "$label width", min = 0f)
        validateLegacyTypedFloat(shape.height, "$label height", min = 0f)
        validateLegacyTypedFloat(shape.rotation, "$label rotation")
        validateLegacyTypedFloat(shape.strokeWidth, "$label strokeWidth", min = 0f)
        validateLegacyTypedFloat(shape.strokeWidthRatio, "$label strokeWidthRatio", min = 0f, max = Stage5Limits.MAX_RATIO)
        validateLegacyTypedFloat(shape.widthRatio, "$label widthRatio", min = 0f, max = Stage5Limits.MAX_RATIO)
        validateLegacyTypedFloat(shape.heightRatio, "$label heightRatio", min = 0f, max = Stage5Limits.MAX_RATIO)
        validateLegacyTypedId(shape.id, "$label id")
    }

    private fun validateLegacyTypedPoint(point: Point, label: String) {
        validateLegacyTypedFloat(point.x, "$label x")
        validateLegacyTypedFloat(point.y, "$label y")
    }

    private fun validateLegacyTypedFloat(value: Float, label: String, min: Float = -Stage5Limits.MAX_NUMERIC_ABS, max: Float = Stage5Limits.MAX_NUMERIC_ABS) {
        if (!value.isFinite() || value < min || value > max) throw Stage5ValidationException("$label is invalid")
    }

    private fun validateLegacyTypedText(value: String, label: String) {
        if (value.isBlank() || value.length > Stage5Limits.MAX_TEXT_CHARS) throw Stage5ValidationException("$label is missing or oversized")
    }

    private fun validateLegacyTypedId(value: String, label: String) {
        if (value.isBlank() || value.length > Stage5Limits.MAX_ID_CHARS) throw Stage5ValidationException("$label is missing or oversized")
    }

    fun decode(json: String): Map<Int, PageData> {
        val bytes = boundedUtf8Bytes(json, Stage5Limits.MAX_JSON_BYTES, "legacy JSON")
        validateNoDuplicateJsonMembers(bytes, "legacy JSON")
        validateLegacyRootKeys(bytes)
        val root = try {
            JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8))
        } catch (error: JsonParseException) {
            throw Stage5ValidationException("legacy JSON is malformed", error)
        } catch (error: IllegalStateException) {
            throw Stage5ValidationException("legacy JSON is malformed", error)
        }
        require(root.isJsonObject) { "legacy JSON root must be an object" }
        val rootObject = root.asJsonObject
        validateLegacyRootTree(rootObject)
        val result = linkedMapOf<Int, PageData>()
        rootObject.entrySet().sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }.forEach { (key, element) ->
            val pageIndex = key.toIntOrNull()
                ?: throw Stage5ValidationException("legacy page key is not an integer: $key")
            require(pageIndex >= 0) { "legacy page index must be non-negative" }
            val pageObject = requireObject(element, "page $key")
            val dto = try {
                gson.fromJson(pageObject, LegacyPageDto::class.java)
            } catch (error: JsonParseException) {
                throw Stage5ValidationException("legacy page $key has an invalid typed shape", error)
            } catch (error: IllegalStateException) {
                throw Stage5ValidationException("legacy page $key has an invalid typed shape", error)
            }
            result[pageIndex] = dto.toPageData(pageObject, key)
        }
        return result
    }

    /** JsonObject cannot retain duplicate keys, so detect them while streaming the root. */
    private fun validateLegacyRootKeys(bytes: ByteArray) {
        try {
            JsonReader(InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8)).use { reader ->
                reader.isLenient = false
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    throw Stage5ValidationException("legacy JSON root must be an object")
                }
                reader.beginObject()
                val pageIndices = hashSetOf<Int>()
                while (reader.hasNext()) {
                    val pageIndex = canonicalLegacyPageIndex(reader.nextName())
                    if (!pageIndices.add(pageIndex)) {
                        throw Stage5ValidationException("legacy page key is duplicated")
                    }
                    reader.skipValue()
                }
                reader.endObject()
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    throw Stage5ValidationException("legacy JSON has trailing content")
                }
            }
        } catch (error: Stage5ValidationException) {
            throw error
        } catch (error: IOException) {
            throw Stage5ValidationException("legacy JSON root is malformed", error)
        } catch (error: IllegalStateException) {
            throw Stage5ValidationException("legacy JSON root is malformed", error)
        }
    }

    /**
     * Validates the complete schema-0 tree while it is still a Gson tree.
     * This is deliberately separate from [LegacyPageDto] validation: Gson
     * allocates nested lists and supplies null/default values for absent DTO
     * fields, so materializing a hostile page first would defeat the boundary.
     */
    private fun validateLegacyRootTree(root: JsonObject) {
        if (root.size() > Stage5Limits.MAX_PAGES) {
            throw Stage5ValidationException("legacy page count exceeds limit")
        }
        val pageIndices = hashSetOf<Int>()
        val uniquePhotoNames = hashSetOf<String>()
        val photoReferenceCount = LegacyPhotoReferenceCounter()
        root.entrySet().forEach { (key, element) ->
            val pageIndex = canonicalLegacyPageIndex(key)
            if (!pageIndices.add(pageIndex)) {
                throw Stage5ValidationException("legacy page key is duplicated: $key")
            }
            validateLegacyPageTree(
                requireLegacyObject(element, "legacy page $key"),
                "legacy page $key",
                uniquePhotoNames,
                photoReferenceCount
            )
        }
        if (photoReferenceCount.count > Stage5Limits.MAX_TOTAL_PHOTOS) {
            throw Stage5ValidationException("legacy photo reference count exceeds limit")
        }
        // This independent bound is about distinct asset keys, not references.
        if (uniquePhotoNames.size > Stage5Limits.MAX_TOTAL_PHOTOS) {
            throw Stage5ValidationException("legacy unique photo name count exceeds limit")
        }
    }

    private fun canonicalLegacyPageIndex(key: String): Int {
        if (!key.matches(Regex("0|[1-9][0-9]*"))) {
            throw Stage5ValidationException("legacy page key is not canonical: $key")
        }
        val pageIndex = key.toIntOrNull()
            ?: throw Stage5ValidationException("legacy page key is out of range: $key")
        if (pageIndex < 0 || pageIndex >= 1_000_000 || key != pageIndex.toString()) {
            throw Stage5ValidationException("legacy page key is out of range: $key")
        }
        return pageIndex
    }

    private fun validateLegacyPageTree(
        page: JsonObject,
        label: String,
        uniquePhotoNames: MutableSet<String>,
        photoReferenceCount: LegacyPhotoReferenceCounter
    ) {
        requireLegacyFields(
            page,
            required = setOf("paths", "measurements", "notes", "photoPins", "shapes"),
            nullable = setOf("scale"),
            label = label
        )
        val paths = requiredLegacyArray(page, "paths", label)
        val measurements = requiredLegacyArray(page, "measurements", label)
        val notes = requiredLegacyArray(page, "notes", label)
        val photoPins = requiredLegacyArray(page, "photoPins", label)
        val shapes = requiredLegacyArray(page, "shapes", label)
        val annotationBudget = LegacyAnnotationBudget(label)
        annotationBudget.add(paths.size(), "paths")
        annotationBudget.add(measurements.size(), "measurements")
        annotationBudget.add(notes.size(), "notes")
        annotationBudget.add(photoPins.size(), "photo pins")
        annotationBudget.add(shapes.size(), "shapes")
        if (page.get("scale")?.isJsonNull == false) annotationBudget.add(1, "scale")
        if (paths.size() > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE ||
            measurements.size() > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE ||
            notes.size() > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE ||
            shapes.size() > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE
        ) {
            throw Stage5ValidationException("$label annotation list exceeds limit")
        }
        if (photoPins.size() > Stage5Limits.MAX_PHOTO_PINS_PER_PAGE) {
            throw Stage5ValidationException("$label photo pin list exceeds limit")
        }
        if (paths.size() + measurements.size() + notes.size() + photoPins.size() + shapes.size() >
            Stage5Limits.MAX_ANNOTATIONS_PER_PAGE
        ) {
            throw Stage5ValidationException("$label annotation count exceeds limit")
        }

        paths.forEachIndexed { index, element -> validateLegacyPathTree(element, "$label path $index") }
        measurements.forEachIndexed { index, element ->
            validateLegacyMeasurementTree(element, "$label measurement $index")
        }
        notes.forEachIndexed { index, element -> validateLegacyNoteTree(element, "$label note $index") }
        photoPins.forEachIndexed { index, element ->
            validateLegacyPinTree(element, "$label photo pin $index", uniquePhotoNames, photoReferenceCount, annotationBudget)
        }
        shapes.forEachIndexed { index, element -> validateLegacyShapeTree(element, "$label shape $index") }
        page.get("scale")?.takeUnless { it.isJsonNull }?.let {
            validateLegacyScaleTree(it, "$label scale")
        }
    }

    private fun validateLegacyPathTree(element: JsonElement, label: String) {
        val path = requireLegacyObject(element, label)
        requireLegacyFields(path, setOf("points", "colorArgb", "strokeWidth", "isHighlighter"), label = label)
        val points = requiredLegacyArray(path, "points", label)
        if (points.isEmpty() || points.size() > Stage5Limits.MAX_PATH_POINTS) {
            throw Stage5ValidationException("$label point count is out of range")
        }
        points.forEachIndexed { index, point -> validateLegacyPointTree(point, "$label point $index") }
        legacyInt(path, "colorArgb", label)
        legacyNumber(path, "strokeWidth", label, min = 0.0)
        legacyBoolean(path, "isHighlighter", label)
    }

    private fun validateLegacyPointTree(element: JsonElement, label: String) {
        val point = requireLegacyObject(element, label)
        requireLegacyFields(point, setOf("x", "y"), label = label)
        legacyNumber(point, "x", label)
        legacyNumber(point, "y", label)
    }

    private fun validateLegacyMeasurementTree(element: JsonElement, label: String) {
        val measurement = requireLegacyObject(element, label)
        val pairFields = setOf("p1", "p2", "text")
        val directFields = setOf("startX", "startY", "endX", "endY", "distanceFeet", "distanceInches")
        val fields = measurement.keySet()
        if (fields.any { it !in pairFields && it !in directFields }) {
            throw Stage5ValidationException("$label contains an unsupported field")
        }
        val pairVariant = fields.any { it in pairFields }
        val directVariant = fields.any { it in directFields }
        if (pairVariant == directVariant) {
            throw Stage5ValidationException("$label does not match a known legacy measurement variant")
        }
        if (pairVariant) {
            requireLegacyFields(measurement, pairFields, label = label)
            validateLegacyPointTree(requiredLegacyElement(measurement, "p1", label), "$label p1")
            validateLegacyPointTree(requiredLegacyElement(measurement, "p2", label), "$label p2")
            legacyText(measurement, "text", label)
        } else {
            requireLegacyFields(measurement, directFields, label = label)
            legacyNumber(measurement, "startX", label)
            legacyNumber(measurement, "startY", label)
            legacyNumber(measurement, "endX", label)
            legacyNumber(measurement, "endY", label)
            legacyNumber(measurement, "distanceFeet", label, min = 0.0)
            legacyNumber(measurement, "distanceInches", label, min = 0.0)
        }
    }

    private fun validateLegacyNoteTree(element: JsonElement, label: String) {
        val note = requireLegacyObject(element, label)
        requireLegacyFields(note, setOf("x", "y", "text", "fontSize", "isBold", "rotation"), label = label)
        legacyNumber(note, "x", label)
        legacyNumber(note, "y", label)
        legacyText(note, "text", label)
        legacyNumber(note, "fontSize", label, min = 0.0)
        legacyBoolean(note, "isBold", label)
        legacyNumber(note, "rotation", label)
    }

    private fun validateLegacyPinTree(
        element: JsonElement,
        label: String,
        uniquePhotoNames: MutableSet<String>,
        photoReferenceCount: LegacyPhotoReferenceCounter,
        annotationBudget: LegacyAnnotationBudget
    ) {
        val pin = requireLegacyObject(element, label)
        requireLegacyFields(
            pin,
            setOf("x", "y", "id", "imageFileNames", "imageNotes", "imageShapes"),
            label = label
        )
        legacyNumber(pin, "x", label)
        legacyNumber(pin, "y", label)
        legacyId(pin, "id", label)
        val names = requiredLegacyArray(pin, "imageFileNames", label)
        if (names.size() > Stage5Limits.MAX_PHOTOS_PER_PIN) {
            throw Stage5ValidationException("$label photo list exceeds limit")
        }
        photoReferenceCount.add(names.size(), label)
        val namesInPin = hashSetOf<String>()
        names.forEachIndexed { index, nameElement ->
            val name = legacyPhotoName(nameElement, "$label imageFileNames[$index]")
            if (!namesInPin.add(name)) throw Stage5ValidationException("$label has duplicate photo references")
            uniquePhotoNames.add(name)
        }
        val imageNoteCount = validateLegacyPhotoAnnotationMap(pin, "imageNotes", label, namesInPin) { noteLabel, note ->
            validateLegacyImageNoteTree(note, noteLabel)
        }
        annotationBudget.add(imageNoteCount, "image notes")
        val imageShapeCount = validateLegacyPhotoAnnotationMap(pin, "imageShapes", label, namesInPin) { shapeLabel, shape ->
            validateLegacyShapeTree(shape, shapeLabel)
        }
        annotationBudget.add(imageShapeCount, "image shapes")
    }

    private fun validateLegacyPhotoAnnotationMap(
        pin: JsonObject,
        field: String,
        label: String,
        namesInPin: Set<String>,
        validate: (String, JsonElement) -> Unit
    ): Int {
        val map = requireLegacyObject(requiredLegacyElement(pin, field, label), "$label $field")
        if (map.size() > Stage5Limits.MAX_PHOTOS_PER_PIN) {
            throw Stage5ValidationException("$label $field entry count exceeds limit")
        }
        var annotationCount = 0
        map.entrySet().forEach { (name, valuesElement) ->
            validatePhotoFileName(name)
            if (name !in namesInPin) throw Stage5ValidationException("$label $field has an unknown photo key")
            val values = requireLegacyArray(valuesElement, "$label $field[$name]")
            if (values.size() > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE) {
                throw Stage5ValidationException("$label $field[$name] exceeds annotation limit")
            }
            annotationCount += values.size()
            values.forEachIndexed { index, value -> validate("$label $field[$name][$index]", value) }
        }
        return annotationCount
    }

    private fun validateLegacyImageNoteTree(element: JsonElement, label: String) {
        val note = requireLegacyObject(element, label)
        requireLegacyFields(
            note,
            setOf("x", "y", "text", "fontSize", "isBold", "rotation", "fontSizeRatio", "id"),
            label = label
        )
        legacyNumber(note, "x", label)
        legacyNumber(note, "y", label)
        legacyText(note, "text", label)
        legacyNumber(note, "fontSize", label, min = 0.0)
        legacyBoolean(note, "isBold", label)
        legacyNumber(note, "rotation", label)
        legacyNumber(note, "fontSizeRatio", label, min = 0.0, max = Stage5Limits.MAX_RATIO.toDouble())
        legacyId(note, "id", label)
    }

    private fun validateLegacyShapeTree(element: JsonElement, label: String) {
        val shape = requireLegacyObject(element, label)
        requireLegacyFields(
            shape,
            setOf(
                "x", "y", "width", "height", "rotation", "type", "colorArgb", "strokeWidth",
                "isFilled", "strokeWidthRatio", "widthRatio", "heightRatio", "id"
            ),
            label = label
        )
        legacyNumber(shape, "x", label)
        legacyNumber(shape, "y", label)
        legacyNumber(shape, "width", label, min = 0.0)
        legacyNumber(shape, "height", label, min = 0.0)
        legacyNumber(shape, "rotation", label)
        val type = legacyString(shape, "type", label, required = true)
        if (type !in setOf("RECTANGLE", "CIRCLE", "ARROW", "CLOUD")) {
            throw Stage5ValidationException("$label has an unknown shape enum: $type")
        }
        legacyInt(shape, "colorArgb", label)
        legacyNumber(shape, "strokeWidth", label, min = 0.0)
        legacyBoolean(shape, "isFilled", label)
        legacyNumber(shape, "strokeWidthRatio", label, min = 0.0, max = Stage5Limits.MAX_RATIO.toDouble())
        legacyNumber(shape, "widthRatio", label, min = 0.0, max = Stage5Limits.MAX_RATIO.toDouble())
        legacyNumber(shape, "heightRatio", label, min = 0.0, max = Stage5Limits.MAX_RATIO.toDouble())
        legacyId(shape, "id", label)
    }

    private fun validateLegacyScaleTree(element: JsonElement, label: String) {
        val scale = requireLegacyObject(element, label)
        requireLegacyFields(scale, setOf("pixelsPerFoot"), label = label)
        legacyNumber(scale, "pixelsPerFoot", label, min = Float.MIN_VALUE.toDouble())
    }

    private fun requireLegacyFields(
        objectValue: JsonObject,
        required: Set<String>,
        label: String,
        nullable: Set<String> = emptySet()
    ) {
        val allowed = required + nullable
        objectValue.keySet().firstOrNull { it !in allowed }?.let {
            throw Stage5ValidationException("$label contains unsupported field: $it")
        }
        required.forEach { name ->
            if (!objectValue.has(name)) throw Stage5ValidationException("$label.$name is missing")
            if (objectValue.get(name).isJsonNull && name !in nullable) {
                throw Stage5ValidationException("$label.$name is null")
            }
        }
    }

    private fun requiredLegacyElement(objectValue: JsonObject, name: String, label: String): JsonElement {
        if (!objectValue.has(name) || objectValue.get(name).isJsonNull) {
            throw Stage5ValidationException("$label.$name is missing or null")
        }
        return objectValue.get(name)
    }

    private fun requiredLegacyArray(objectValue: JsonObject, name: String, label: String): JsonArray =
        requireLegacyArray(requiredLegacyElement(objectValue, name, label), "$label.$name")

    private fun requireLegacyArray(element: JsonElement, label: String): JsonArray {
        if (!element.isJsonArray) throw Stage5ValidationException("$label must be an array")
        return element.asJsonArray
    }

    private fun requireLegacyObject(element: JsonElement, label: String): JsonObject {
        if (!element.isJsonObject) throw Stage5ValidationException("$label must be an object")
        return element.asJsonObject
    }

    private fun legacyPrimitive(objectValue: JsonObject, name: String, label: String): JsonPrimitive =
        requiredLegacyElement(objectValue, name, label).let { element ->
            if (!element.isJsonPrimitive) throw Stage5ValidationException("$label.$name must be a primitive")
            element.asJsonPrimitive
        }

    private fun legacyString(objectValue: JsonObject, name: String, label: String, required: Boolean): String {
        val primitive = legacyPrimitive(objectValue, name, label)
        if (!primitive.isString) throw Stage5ValidationException("$label.$name must be a string")
        val value = primitive.asString
        if (value.length > Stage5Limits.MAX_STRING_CHARS || (required && value.isBlank())) {
            throw Stage5ValidationException("$label.$name is blank or oversized")
        }
        return value
    }

    private fun legacyText(objectValue: JsonObject, name: String, label: String): String {
        val primitive = legacyPrimitive(objectValue, name, label)
        if (!primitive.isString) throw Stage5ValidationException("$label.$name must be text")
        val value = primitive.asString
        if (value.isBlank() || value.length > Stage5Limits.MAX_TEXT_CHARS) {
            throw Stage5ValidationException("$label.$name is blank or oversized")
        }
        return value
    }

    private fun legacyId(objectValue: JsonObject, name: String, label: String): String {
        val value = legacyString(objectValue, name, label, required = true)
        if (value.length > Stage5Limits.MAX_ID_CHARS) throw Stage5ValidationException("$label.$name is oversized")
        return value
    }

    private fun legacyPhotoName(element: JsonElement, label: String): String {
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw Stage5ValidationException("$label must be a photo filename")
        }
        val value = element.asString
        if (value.length > Stage5Limits.MAX_STRING_CHARS) throw Stage5ValidationException("$label is oversized")
        return validatePhotoFileName(value)
    }

    private fun legacyBoolean(objectValue: JsonObject, name: String, label: String): Boolean {
        val primitive = legacyPrimitive(objectValue, name, label)
        if (!primitive.isBoolean) throw Stage5ValidationException("$label.$name must be boolean")
        return primitive.asBoolean
    }

    private fun legacyNumber(
        objectValue: JsonObject,
        name: String,
        label: String,
        min: Double = -Stage5Limits.MAX_NUMERIC_ABS.toDouble(),
        max: Double = Stage5Limits.MAX_NUMERIC_ABS.toDouble()
    ): Double {
        val primitive = legacyPrimitive(objectValue, name, label)
        if (!primitive.isNumber) throw Stage5ValidationException("$label.$name must be a finite number")
        val value = try {
            BigDecimal(primitive.asString).toDouble()
        } catch (error: NumberFormatException) {
            throw Stage5ValidationException("$label.$name is not a valid number", error)
        }
        if (!value.isFinite() || value < min || value > max) {
            throw Stage5ValidationException("$label.$name is non-finite or out of range")
        }
        return value
    }

    private fun legacyInt(objectValue: JsonObject, name: String, label: String): Int {
        val primitive = legacyPrimitive(objectValue, name, label)
        if (!primitive.isNumber) throw Stage5ValidationException("$label.$name must be an integer")
        val value = try {
            BigDecimal(primitive.asString).intValueExact()
        } catch (error: NumberFormatException) {
            throw Stage5ValidationException("$label.$name must be an integer", error)
        } catch (error: ArithmeticException) {
            throw Stage5ValidationException("$label.$name must be an integer", error)
        }
        return value
    }

    private fun LegacyPageDto.toPageData(pageObject: JsonObject, pageLabel: String): PageData {
        val pathDtos = paths ?: throw Stage5ValidationException("page $pageLabel paths missing")
        val measurementDtos = measurements ?: throw Stage5ValidationException("page $pageLabel measurements missing")
        val noteDtos = notes ?: throw Stage5ValidationException("page $pageLabel notes missing")
        val pinDtos = photoPins ?: throw Stage5ValidationException("page $pageLabel photo pins missing")
        val shapeDtos = shapes ?: throw Stage5ValidationException("page $pageLabel shapes missing")
        require(pathDtos.size <= Stage5Limits.MAX_ANNOTATIONS_PER_PAGE)
        require(measurementDtos.size <= Stage5Limits.MAX_ANNOTATIONS_PER_PAGE)
        require(noteDtos.size <= Stage5Limits.MAX_ANNOTATIONS_PER_PAGE)
        require(pinDtos.size <= Stage5Limits.MAX_PHOTO_PINS_PER_PAGE)
        require(shapeDtos.size <= Stage5Limits.MAX_ANNOTATIONS_PER_PAGE)
        val domainCount = pathDtos.size + measurementDtos.size + noteDtos.size + pinDtos.size + shapeDtos.size
        require(domainCount <= Stage5Limits.MAX_ANNOTATIONS_PER_PAGE) { "page annotation count exceeds limit" }
        return PageData(
            paths = pathDtos.mapIndexed { index, path -> path.toLegacy(pageLabel, index) },
            measurements = measurementDtos.mapIndexed { index, measurement -> measurement.toLegacy(pageLabel, index) },
            notes = noteDtos.mapIndexed { index, note -> note.toLegacy(pageLabel, index) },
            photoPins = pinDtos.mapIndexed { index, pin -> pin.toLegacy(pageLabel, index) },
            scale = scale?.toLegacy(pageLabel),
            shapes = shapeDtos.mapIndexed { index, shape -> shape.toLegacy("page $pageLabel shape $index") }
        )
    }

    private fun LegacyPathDto.toLegacy(page: String, index: Int): DrawnPath {
        val pointDtos = points ?: throw Stage5ValidationException("page $page path $index points missing")
        require(pointDtos.size <= Stage5Limits.MAX_PATH_POINTS)
        require(pointDtos.isNotEmpty()) { "page $page path $index must contain points" }
        return DrawnPath(
            points = pointDtos.mapIndexed { pointIndex, point -> point.toLegacy("page $page path $index point $pointIndex") },
            colorArgb = colorArgb ?: throw Stage5ValidationException("page $page path $index color missing"),
            strokeWidth = requiredFloat(strokeWidth, "page $page path $index strokeWidth", 0f),
            isHighlighter = isHighlighter ?: throw Stage5ValidationException("page $page path $index highlight flag missing")
        )
    }

    private fun LegacyMeasurementDto.toLegacy(page: String, index: Int): Measurement {
        val start = p1?.toLegacy("page $page measurement $index p1")
        val end = p2?.toLegacy("page $page measurement $index p2")
        if (start != null && end != null) {
            return Measurement(start, end, requiredText(text, "page $page measurement $index text"))
        }
        // Explicit schema-0 compatibility for the old direct measurement form.
        require(startX != null && startY != null && endX != null && endY != null) {
            "page $page measurement $index is missing p1/p2 or direct endpoints"
        }
        val feet = requiredNumber(distanceFeet, "page $page measurement $index distanceFeet").toInt()
        val inches = requiredFloat(distanceInches, "page $page measurement $index distanceInches", 0f)
        return Measurement(
            Point(requiredFloat(startX, "page $page measurement $index startX"), requiredFloat(startY, "page $page measurement $index startY")),
            Point(requiredFloat(endX, "page $page measurement $index endX"), requiredFloat(endY, "page $page measurement $index endY")),
            "$feet' ${String.format(Locale.US, "%.2f", inches)}\""
        )
    }

    private fun LegacyNoteDto.toLegacy(page: String, index: Int): Note = Note(
        x = requiredFloat(x, "page $page note $index x"),
        y = requiredFloat(y, "page $page note $index y"),
        text = requiredText(text, "page $page note $index text"),
        fontSize = requiredFloat(fontSize, "page $page note $index fontSize", 0f),
        isBold = isBold ?: throw Stage5ValidationException("page $page note $index isBold missing"),
        rotation = requiredFloat(rotation, "page $page note $index rotation")
    )

    private fun LegacyPinDto.toLegacy(page: String, index: Int): PhotoPin {
        val names = imageFileNames ?: throw Stage5ValidationException("page $page photo pin $index imageFileNames missing")
        val notes = imageNotes ?: throw Stage5ValidationException("page $page photo pin $index imageNotes missing")
        val shapes = imageShapes ?: throw Stage5ValidationException("page $page photo pin $index imageShapes missing")
        require(names.size <= Stage5Limits.MAX_PHOTOS_PER_PIN)
        require(names.distinct().size == names.size) { "page $page photo pin $index has duplicate photo references" }
        names.forEach(::validatePhotoFileName)
        require(notes.keys.all { it in names }) { "page $page photo pin $index has an unknown image note reference" }
        require(shapes.keys.all { it in names }) { "page $page photo pin $index has an unknown image shape reference" }
        return PhotoPin(
            x = requiredFloat(x, "page $page photo pin $index x"),
            y = requiredFloat(y, "page $page photo pin $index y"),
            id = requiredId(id, "page $page photo pin $index id"),
            imageFileNames = names.toMutableList(),
            imageNotes = notes.mapValues { (name, values) ->
                require(values.size <= Stage5Limits.MAX_ANNOTATIONS_PER_PAGE)
                values.mapIndexed { noteIndex, note -> note.toLegacy("page $page photo $name note $noteIndex") }.toMutableList()
            }.toMutableMap(),
            imageShapes = shapes.mapValues { (name, values) ->
                require(values.size <= Stage5Limits.MAX_ANNOTATIONS_PER_PAGE)
                values.mapIndexed { shapeIndex, shape -> shape.toLegacy("page $page photo $name shape $shapeIndex") }.toMutableList()
            }.toMutableMap()
        )
    }

    private fun LegacyImageNoteDto.toLegacy(label: String): PhotoImageNote = PhotoImageNote(
        x = requiredFloat(x, "$label x"),
        y = requiredFloat(y, "$label y"),
        text = requiredText(text, "$label text"),
        fontSize = requiredFloat(fontSize, "$label fontSize", 0f),
        isBold = isBold ?: throw Stage5ValidationException("$label isBold missing"),
        rotation = requiredFloat(rotation, "$label rotation"),
        fontSizeRatio = requiredFloat(fontSizeRatio, "$label fontSizeRatio", 0f, Stage5Limits.MAX_RATIO),
        id = requiredId(id, "$label id")
    )

    private fun LegacyShapeDto.toLegacy(label: String): Shape {
        val typeName = type ?: throw Stage5ValidationException("$label type missing")
        val type = try {
            ShapeType.valueOf(typeName)
        } catch (error: IllegalArgumentException) {
            throw Stage5ValidationException("$label has unknown shape enum: $typeName", error)
        }
        return Shape(
            x = requiredFloat(x, "$label x"),
            y = requiredFloat(y, "$label y"),
            width = requiredFloat(width, "$label width", 0f),
            height = requiredFloat(height, "$label height", 0f),
            rotation = requiredFloat(rotation, "$label rotation"),
            type = type,
            colorArgb = colorArgb ?: throw Stage5ValidationException("$label color missing"),
            strokeWidth = requiredFloat(strokeWidth, "$label strokeWidth", 0f),
            isFilled = isFilled ?: throw Stage5ValidationException("$label isFilled missing"),
            strokeWidthRatio = requiredFloat(strokeWidthRatio, "$label strokeWidthRatio", 0f, Stage5Limits.MAX_RATIO),
            widthRatio = requiredFloat(widthRatio, "$label widthRatio", 0f, Stage5Limits.MAX_RATIO),
            heightRatio = requiredFloat(heightRatio, "$label heightRatio", 0f, Stage5Limits.MAX_RATIO),
            id = requiredId(id, "$label id")
        )
    }

    private fun LegacyPointDto.toLegacy(label: String): Point = Point(
        requiredFloat(x, "$label x"),
        requiredFloat(y, "$label y")
    )

    private fun LegacyScaleDto.toLegacy(page: String): PageScale =
        PageScale(requiredFloat(pixelsPerFoot, "page $page scale pixelsPerFoot", Float.MIN_VALUE))

    private fun requiredObject(element: JsonElement?, label: String): JsonObject =
        requireObject(element ?: throw Stage5ValidationException("$label is missing"), label)

    private fun requireObject(element: JsonElement, label: String): JsonObject {
        require(element.isJsonObject) { "$label must be an object" }
        return element.asJsonObject
    }

    private fun requiredText(value: String?, label: String): String {
        require(!value.isNullOrBlank() && value.length <= Stage5Limits.MAX_TEXT_CHARS) { "$label is missing or oversized" }
        return value
    }

    private fun requiredId(value: String?, label: String): String {
        require(!value.isNullOrBlank() && value.length <= Stage5Limits.MAX_ID_CHARS) { "$label is missing or oversized" }
        return value
    }

    private fun requiredNumber(value: Number?, label: String): Double {
        val number = value?.toDouble() ?: throw Stage5ValidationException("$label is missing")
        require(number.isFinite() && number >= 0.0 && number <= Stage5Limits.MAX_NUMERIC_ABS) { "$label is invalid" }
        return number
    }

    private fun requiredFloat(value: Number?, label: String, min: Float = -Stage5Limits.MAX_NUMERIC_ABS, max: Float = Stage5Limits.MAX_NUMERIC_ABS): Float {
        val number = value?.toDouble() ?: throw Stage5ValidationException("$label is missing")
        require(number.isFinite() && number >= min && number <= max) { "$label is invalid" }
        return number.toFloat()
    }

    private data class LegacyPageDto(
        val paths: List<LegacyPathDto>?,
        val measurements: List<LegacyMeasurementDto>?,
        val notes: List<LegacyNoteDto>?,
        val photoPins: List<LegacyPinDto>?,
        val shapes: List<LegacyShapeDto>?,
        val scale: LegacyScaleDto?
    )

    private data class LegacyPathDto(val points: List<LegacyPointDto>?, val colorArgb: Int?, val strokeWidth: Float?, val isHighlighter: Boolean?)
    private data class LegacyPointDto(val x: Float?, val y: Float?)
    private data class LegacyMeasurementDto(
        val p1: LegacyPointDto?, val p2: LegacyPointDto?, val text: String?,
        val startX: Float?, val startY: Float?, val endX: Float?, val endY: Float?,
        val distanceFeet: Float?, val distanceInches: Float?
    )
    private data class LegacyNoteDto(val x: Float?, val y: Float?, val text: String?, val fontSize: Float?, val isBold: Boolean?, val rotation: Float?)
    private data class LegacyPinDto(
        val x: Float?, val y: Float?, val id: String?, val imageFileNames: List<String>?,
        val imageNotes: Map<String, List<LegacyImageNoteDto>>?, val imageShapes: Map<String, List<LegacyShapeDto>>?
    )
    private data class LegacyImageNoteDto(
        val x: Float?, val y: Float?, val text: String?, val fontSize: Float?, val isBold: Boolean?,
        val rotation: Float?, val fontSizeRatio: Float?, val id: String?
    )
    private data class LegacyShapeDto(
        val x: Float?, val y: Float?, val width: Float?, val height: Float?, val rotation: Float?,
        val type: String?, val colorArgb: Int?, val strokeWidth: Float?, val isFilled: Boolean?,
        val strokeWidthRatio: Float?, val widthRatio: Float?, val heightRatio: Float?, val id: String?
    )
    private data class LegacyScaleDto(val pixelsPerFoot: Float?)

    private data class LegacyPageOutputDto(
        val paths: List<LegacyPathOutputDto>,
        val measurements: List<LegacyMeasurementOutputDto>,
        val notes: List<LegacyNoteOutputDto>,
        val photoPins: List<LegacyPinOutputDto>,
        val shapes: List<LegacyShapeOutputDto>,
        val scale: LegacyScaleOutputDto?
    )
    private data class LegacyPathOutputDto(val points: List<LegacyPointOutputDto>, val colorArgb: Int, val strokeWidth: Float, val isHighlighter: Boolean)
    private data class LegacyPointOutputDto(val x: Float, val y: Float)
    private data class LegacyMeasurementOutputDto(val p1: LegacyPointOutputDto, val p2: LegacyPointOutputDto, val text: String)
    private data class LegacyNoteOutputDto(val x: Float, val y: Float, val text: String, val fontSize: Float, val isBold: Boolean, val rotation: Float)
    private data class LegacyPinOutputDto(
        val x: Float, val y: Float, val id: String, val imageFileNames: List<String>,
        val imageNotes: Map<String, List<LegacyImageNoteOutputDto>>,
        val imageShapes: Map<String, List<LegacyShapeOutputDto>>
    )
    private data class LegacyImageNoteOutputDto(
        val x: Float, val y: Float, val text: String, val fontSize: Float, val isBold: Boolean,
        val rotation: Float, val fontSizeRatio: Float, val id: String
    )
    private data class LegacyShapeOutputDto(
        val x: Float, val y: Float, val width: Float, val height: Float, val rotation: Float,
        val type: String, val colorArgb: Int, val strokeWidth: Float, val isFilled: Boolean,
        val strokeWidthRatio: Float, val widthRatio: Float, val heightRatio: Float, val id: String
    )
    private data class LegacyScaleOutputDto(val pixelsPerFoot: Float)

    private fun PageData.toDto() = LegacyPageOutputDto(
        paths = paths.map { LegacyPathOutputDto(it.points.map { point -> LegacyPointOutputDto(point.x, point.y) }, it.colorArgb, it.strokeWidth, it.isHighlighter) },
        measurements = measurements.map { LegacyMeasurementOutputDto(LegacyPointOutputDto(it.p1.x, it.p1.y), LegacyPointOutputDto(it.p2.x, it.p2.y), it.text) },
        notes = notes.map { LegacyNoteOutputDto(it.x, it.y, it.text, it.fontSize, it.isBold, it.rotation) },
        photoPins = photoPins.map { pin ->
            LegacyPinOutputDto(
                pin.x, pin.y, pin.id, pin.imageFileNames.toList(),
                pin.imageNotes.mapValues { (_, values) -> values.map { note -> LegacyImageNoteOutputDto(note.x, note.y, note.text, note.fontSize, note.isBold, note.rotation, note.fontSizeRatio, note.id) } },
                pin.imageShapes.mapValues { (_, values) -> values.map { shape -> shape.toOutputDto() } }
            )
        },
        shapes = shapes.map { it.toOutputDto() },
        scale = scale?.let { LegacyScaleOutputDto(it.pixelsPerFoot) }
    )

    private fun Shape.toOutputDto() = LegacyShapeOutputDto(
        x, y, width, height, rotation, type.name, colorArgb, strokeWidth, isFilled,
        strokeWidthRatio, widthRatio, heightRatio, id
    )
}
