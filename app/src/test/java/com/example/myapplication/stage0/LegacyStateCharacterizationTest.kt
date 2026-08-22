package com.example.myapplication.stage0

import com.example.myapplication.DrawnPath
import com.example.myapplication.Measurement
import com.example.myapplication.Note
import com.example.myapplication.PageMarkups
import com.example.myapplication.PageScale
import com.example.myapplication.PhotoImageNote
import com.example.myapplication.PhotoPin
import com.example.myapplication.Point
import com.example.myapplication.RecentFile
import com.example.myapplication.Shape
import com.example.myapplication.ShapeType
import com.example.myapplication.HistoryAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass

class LegacyStateCharacterizationTest {
    @Test
    fun characterization_legacyJavaSerialization_preservesEveryPageMarkupDomain() {
        val expected = LegacyStateFixture.fullyPopulatedLegacyMarkups()
        @Suppress("UNCHECKED_CAST")
        val actual = javaRoundTrip(expected) as Map<Int, PageMarkups>

        assertEquals(setOf(0, 2), actual.keys)
        assertPageMarkup(expected.getValue(0), actual.getValue(0))
        assertEquals(0, actual.getValue(2).paths.size)
        assertEquals(0, actual.getValue(2).measurements.size)
        assertEquals(0, actual.getValue(2).notes.size)
        assertEquals(0, actual.getValue(2).photoPins.size)
        assertEquals(0, actual.getValue(2).shapes.size)
    }

    @Test
    fun characterization_legacyJavaSerialization_preservesLegacyClassNames() {
        assertEquals("com.example.myapplication.Point", Point::class.java.name)
        assertEquals("com.example.myapplication.PageMarkups", PageMarkups::class.java.name)
        assertEquals("com.example.myapplication.DrawnPath", DrawnPath::class.java.name)
        assertEquals("com.example.myapplication.Measurement", Measurement::class.java.name)
        assertEquals("com.example.myapplication.Note", Note::class.java.name)
        assertEquals("com.example.myapplication.PhotoPin", PhotoPin::class.java.name)
        assertEquals("com.example.myapplication.PhotoImageNote", PhotoImageNote::class.java.name)
        assertEquals("com.example.myapplication.Shape", Shape::class.java.name)
        assertEquals("com.example.myapplication.ShapeType", ShapeType::class.java.name)
        assertEquals("com.example.myapplication.PageScale", PageScale::class.java.name)
        assertEquals("com.example.myapplication.RecentFile", RecentFile::class.java.name)
        assertEquals("com.example.myapplication.HistoryAction", HistoryAction::class.java.name)
        assertEquals("com.example.myapplication.HistoryAction\$AddPath", HistoryAction.AddPath::class.java.name)
        assertEquals("com.example.myapplication.HistoryAction\$AddShape", HistoryAction.AddShape::class.java.name)
    }

    @Test
    fun characterization_legacyJavaSerialization_preservesComputedDescriptors() {
        assertSerialVersionUid(PageScale::class.java, 153906683982873790L)
        assertSerialVersionUid(Point::class.java, 1576020023380671823L)
        assertSerialVersionUid(DrawnPath::class.java, -6494739642876298976L)
        assertSerialVersionUid(Measurement::class.java, -1571215891530072054L)
        assertSerialVersionUid(Note::class.java, -1814352541871794802L)
        assertSerialVersionUid(Shape::class.java, -2394404539924785531L)
        assertSerialVersionUid(PhotoPin::class.java, 8532709806303289552L)
        assertSerialVersionUid(PhotoImageNote::class.java, 4646406617829992417L)
        assertSerialVersionUid(PageMarkups::class.java, 9056201298526674561L)
        assertSerialVersionUid(ShapeType::class.java, 0L)
    }

    private fun assertSerialVersionUid(type: Class<*>, expected: Long) {
        assertEquals(expected, ObjectStreamClass.lookup(type).serialVersionUID)
    }

    private fun assertPageMarkup(expected: PageMarkups, actual: PageMarkups) {
        assertEquals(expected.paths.size, actual.paths.size)
        val expectedPath = expected.paths.single()
        val actualPath = actual.paths.single()
        assertEquals(expectedPath.colorArgb, actualPath.colorArgb)
        assertEquals(expectedPath.strokeWidth, actualPath.strokeWidth, 0.0f)
        assertEquals(expectedPath.isHighlighter, actualPath.isHighlighter)
        assertEquals(expectedPath.points.size, actualPath.points.size)
        expectedPath.points.zip(actualPath.points).forEach { (expectedPoint, actualPoint) ->
            assertEquals(expectedPoint.x, actualPoint.x, 0.0f)
            assertEquals(expectedPoint.y, actualPoint.y, 0.0f)
        }

        assertEquals(expected.measurements.size, actual.measurements.size)
        val expectedMeasurement = expected.measurements.single()
        val actualMeasurement = actual.measurements.single()
        assertEquals(expectedMeasurement.p1.x, actualMeasurement.p1.x, 0.0f)
        assertEquals(expectedMeasurement.p1.y, actualMeasurement.p1.y, 0.0f)
        assertEquals(expectedMeasurement.p2.x, actualMeasurement.p2.x, 0.0f)
        assertEquals(expectedMeasurement.p2.y, actualMeasurement.p2.y, 0.0f)
        assertEquals(expectedMeasurement.text, actualMeasurement.text)

        assertEquals(expected.notes.size, actual.notes.size)
        val expectedNote = expected.notes.single()
        val actualNote = actual.notes.single()
        assertEquals(expectedNote.x, actualNote.x, 0.0f)
        assertEquals(expectedNote.y, actualNote.y, 0.0f)
        assertEquals(expectedNote.text, actualNote.text)
        assertEquals(expectedNote.fontSize, actualNote.fontSize, 0.0f)
        assertEquals(expectedNote.isBold, actualNote.isBold)
        assertEquals(expectedNote.rotation, actualNote.rotation, 0.0f)

        assertEquals(expected.photoPins.size, actual.photoPins.size)
        assertPhotoPin(expected.photoPins.single(), actual.photoPins.single())

        assertEquals(expected.shapes.size, actual.shapes.size)
        assertShape(expected.shapes.single(), actual.shapes.single())
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
        assertEquals(expectedNote.x, actualNote.x, 0.0f)
        assertEquals(expectedNote.y, actualNote.y, 0.0f)
        assertEquals(expectedNote.text, actualNote.text)
        assertEquals(expectedNote.fontSize, actualNote.fontSize, 0.0f)
        assertEquals(expectedNote.isBold, actualNote.isBold)
        assertEquals(expectedNote.rotation, actualNote.rotation, 0.0f)
        assertEquals(expectedNote.fontSizeRatio, actualNote.fontSizeRatio, 0.0f)
        assertEquals(expectedNote.id, actualNote.id)

        val expectedShape = expected.imageShapes.getValue(LegacyStateFixture.PHOTO_ONE).single()
        val actualShape = actual.imageShapes.getValue(LegacyStateFixture.PHOTO_ONE).single()
        assertShape(expectedShape, actualShape)
    }

    private fun assertShape(expected: Shape, actual: Shape) {
        assertEquals(expected.x, actual.x, 0.0f)
        assertEquals(expected.y, actual.y, 0.0f)
        assertEquals(expected.width, actual.width, 0.0f)
        assertEquals(expected.height, actual.height, 0.0f)
        assertEquals(expected.rotation, actual.rotation, 0.0f)
        assertEquals(expected.type, actual.type)
        assertEquals(expected.colorArgb, actual.colorArgb)
        assertEquals(expected.strokeWidth, actual.strokeWidth, 0.0f)
        assertEquals(expected.isFilled, actual.isFilled)
        assertEquals(expected.strokeWidthRatio, actual.strokeWidthRatio, 0.0f)
        assertEquals(expected.widthRatio, actual.widthRatio, 0.0f)
        assertEquals(expected.heightRatio, actual.heightRatio, 0.0f)
        assertEquals(expected.id, actual.id)
    }

    private fun javaRoundTrip(value: Any): Any {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(value) }
        assertTrue("legacy state should serialize to non-empty bytes", bytes.size() > 0)
        return ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() }
    }
}
