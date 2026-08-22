package com.example.myapplication.stage0

import android.content.SharedPreferences
import android.content.ContextWrapper
import com.example.myapplication.DriveSyncManager
import com.example.myapplication.PageData
import com.example.myapplication.PhotoPin
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyJsonCharacterizationTest {
    private val manager = DriveSyncManager(NoOpContext())

    @Test
    fun characterization_legacyDriveJson_roundTripsScaleShapesPhotosAndImageAnnotations() {
        val expected = LegacyStateFixture.fullyPopulatedPageData()
        val json = manager.serializePageData(expected)
        val actual = manager.deserializePageData(json)

        assertEquals(expected.keys, actual.keys)
        assertPageData(expected.getValue(0), actual.getValue(0))
        assertEquals(expected.getValue(2).scale, actual.getValue(2).scale)
    }

    @Test
    fun characterization_legacyDriveJson_containsEveryCurrentlySerializedFieldName() {
        val json = JsonParser.parseString(
            manager.serializePageData(LegacyStateFixture.fullyPopulatedPageData())
        ).asJsonObject
        val page = json.getAsJsonObject("0")

        assertTrue(page.has("paths"))
        assertTrue(page.has("measurements"))
        assertTrue(page.has("notes"))
        assertTrue(page.has("photoPins"))
        assertTrue(page.has("shapes"))
        assertTrue(page.has("scale"))

        val path = page.getAsJsonArray("paths")[0].asJsonObject
        assertTrue(path.has("points"))
        assertTrue(path.has("colorArgb"))
        assertTrue(path.has("strokeWidth"))
        assertTrue(path.has("isHighlighter"))
        val point = path.getAsJsonArray("points")[0].asJsonObject
        assertTrue(point.has("x"))
        assertTrue(point.has("y"))

        val measurement = page.getAsJsonArray("measurements")[0].asJsonObject
        assertTrue(measurement.has("p1"))
        assertTrue(measurement.has("p2"))
        assertTrue(measurement.has("text"))
        assertTrue(measurement.getAsJsonObject("p1").has("x"))
        assertTrue(measurement.getAsJsonObject("p1").has("y"))
        assertTrue(measurement.getAsJsonObject("p2").has("x"))
        assertTrue(measurement.getAsJsonObject("p2").has("y"))

        val note = page.getAsJsonArray("notes")[0].asJsonObject
        listOf("x", "y", "text", "fontSize", "isBold", "rotation").forEach { assertTrue(note.has(it)) }

        val pin = page.getAsJsonArray("photoPins")[0].asJsonObject
        listOf("x", "y", "id", "imageFileNames", "imageNotes", "imageShapes").forEach { assertTrue(pin.has(it)) }
        val imageNote = pin.getAsJsonObject("imageNotes")
            .getAsJsonArray(LegacyStateFixture.PHOTO_ONE)[0].asJsonObject
        listOf("x", "y", "text", "fontSize", "isBold", "rotation", "fontSizeRatio", "id")
            .forEach { assertTrue(imageNote.has(it)) }

        val imageShape = pin.getAsJsonObject("imageShapes")
            .getAsJsonArray(LegacyStateFixture.PHOTO_ONE)[0].asJsonObject
        assertShapeFieldNames(imageShape)

        val pageShape = page.getAsJsonArray("shapes")[0].asJsonObject
        assertShapeFieldNames(pageShape)
        assertTrue(page.getAsJsonObject("scale").has("pixelsPerFoot"))
    }

    @Test
    fun characterization_fullyPopulatedLegacyStateResource_hasScaleAndAllAnnotationDomains() {
        val root = JsonParser.parseString(resourceText("stage0/legacy/fully_populated_page_data.json")).asJsonObject
        val page = root.getAsJsonObject("0")
        assertEquals(setOf("0", "2"), root.keySet())
        assertTrue(page.getAsJsonArray("paths").size() > 0)
        assertTrue(page.getAsJsonArray("measurements").size() > 0)
        assertTrue(page.getAsJsonArray("notes").size() > 0)
        assertTrue(page.getAsJsonArray("photoPins").size() > 0)
        assertTrue(page.getAsJsonArray("shapes").size() > 0)
        assertTrue(page.getAsJsonObject("scale").get("pixelsPerFoot").asFloat > 0f)
    }

    private fun assertPageData(expected: PageData, actual: PageData) {
        assertEquals(expected.paths, actual.paths)
        assertEquals(expected.measurements, actual.measurements)
        assertEquals(expected.notes, actual.notes)
        assertEquals(expected.scale, actual.scale)
        assertEquals(expected.shapes, actual.shapes)
        assertEquals(expected.photoPins.size, actual.photoPins.size)
        assertPhotoPin(expected.photoPins.single(), actual.photoPins.single())
    }

    private fun assertPhotoPin(expected: PhotoPin, actual: PhotoPin) {
        assertEquals(expected.x, actual.x, 0.0f)
        assertEquals(expected.y, actual.y, 0.0f)
        assertEquals(expected.id, actual.id)
        assertEquals(expected.imageFileNames, actual.imageFileNames)
        assertEquals(expected.imageNotes.keys, actual.imageNotes.keys)
        assertEquals(expected.imageShapes.keys, actual.imageShapes.keys)
        val expectedNote = expected.imageNotes.getValue(LegacyStateFixture.PHOTO_ONE).single()
        val actualNote = actual.imageNotes.getValue(LegacyStateFixture.PHOTO_ONE).single()
        assertEquals(expectedNote, actualNote)
        assertEquals(
            expected.imageShapes.getValue(LegacyStateFixture.PHOTO_ONE).single(),
            actual.imageShapes.getValue(LegacyStateFixture.PHOTO_ONE).single()
        )
    }

    private fun assertShapeFieldNames(shape: com.google.gson.JsonObject) {
        listOf(
            "x", "y", "width", "height", "rotation", "type", "colorArgb", "strokeWidth",
            "isFilled", "strokeWidthRatio", "widthRatio", "heightRatio", "id"
        ).forEach { assertTrue("missing serialized shape field $it", shape.has(it)) }
    }

    private fun resourceText(path: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "Missing resource $path" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private class NoOpContext : ContextWrapper(null) {
        private val preferences: SharedPreferences = java.lang.reflect.Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, _ ->
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0f
                Double::class.javaPrimitiveType -> 0.0
                else -> null
            }
        } as SharedPreferences

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = preferences
    }
}
