package com.example.myapplication.audit

import com.example.myapplication.*
import com.example.myapplication.stage6.*
import com.example.myapplication.stage8.*
import org.junit.Assert.*
import org.junit.Test

class PhotoContractsTest {
    @Test fun sceneOrderAndSelectionFollowTheSameTopmostObject() {
        val path = DrawnPath(listOf(Point(0f, .5f), Point(1f, .5f)), 1, false, .01f)
        val measurement = Measurement(Point(0f, .5f), Point(1f, .5f), "dimension")
        val note = Note(.5f, .5f, "note")
        val shape = Shape(.5f, .5f, 0f, ShapeType.RECTANGLE, 2)
        val scene = PhotoAnnotationScene.items(listOf(path), listOf(measurement), listOf(note), listOf(shape))
        assertEquals(listOf(PageItem.Path(path), PageItem.Measure(measurement), PageItem.NoteItem(note, 0), PageItem.ShapeItem(shape)), scene)
        assertEquals(PageItem.ShapeItem(shape), PhotoAnnotationScene.hit(scene) { true })
        assertEquals(PageItem.NoteItem(note, 0), PhotoAnnotationScene.hit(scene) { it !is PageItem.ShapeItem })
        assertEquals(PageItem.Measure(measurement), PhotoAnnotationScene.hit(scene) { it is PageItem.Measure || it is PageItem.Path })
        assertNull(PhotoAnnotationScene.hit(scene) { false })
    }

    @Test fun everyAcceptedPhotoFitsOnePositiveCellAcrossAppendixPages() {
        for ((w, h) in listOf(792 to 612, 612 to 792, 3456 to 2592, 60 to 80)) {
            val layout = PhotoAppendixLayout.create(w, h)
            assertTrue(layout.capacity in 1..6)
            for (count in listOf(1, 2, 7, 31, 128)) {
                val images = (0 until count).toList()
                val pages = images.chunked(layout.capacity)
                assertEquals(images, pages.flatten())
                for (page in pages) {
                    val columns = if (page.size == 1) 1 else layout.columns
                    val rows = (page.size + columns - 1) / columns
                    val width = (layout.contentWidth - layout.gap * (columns - 1)) / columns
                    val height = (layout.availableHeight - layout.gap * (rows - 1)) / rows
                    assertTrue(width > 0f && height > 0f)
                    val decode = photoAppendixDecodeSize(width.toInt(), height.toInt(), count)
                    assertTrue(decode.first > 0 && decode.second > 0)
                    assertTrue(decode.first.toLong() * decode.second * count <= 8_000_000L)
                }
            }
        }
    }
}
