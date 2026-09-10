package com.example.myapplication.stage9b

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.Note
import com.example.myapplication.Shape
import com.example.myapplication.ShapeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class AnnotationRenderingInstrumentedTest {
    @Test
    fun drawsMultilineBoldRotatedNoteAndUsesMeasuredBounds() {
        val note = Note(
            x = .5f,
            y = .5f,
            text = "Bold first line that wraps ".repeat(12) + "\nsecond line",
            isBold = true,
            rotation = 37f,
            fontSizeRatio = .045f
        )
        val bounds = AnnotationCanvasRendering.noteBounds(note, 1200f, 800f)
        assertTrue(bounds.width() > 0f)
        assertTrue(bounds.height() > 0f)
        assertTrue(bounds.height() > AnnotationCanvasRendering.noteBounds(note.copy(text = "one"), 1200f, 800f).height())
        assertTrue(AnnotationCanvasRendering.containsNote(note, 600f, 400f, 1200f, 800f))
        assertFalse(AnnotationCanvasRendering.containsNote(note, bounds.right + 40f, 400f, 1200f, 800f))

        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0)
            AnnotationCanvasRendering.drawNote(
                Canvas(bitmap), note, 0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(),
                0xfff5f5f5.toInt()
            )
            assertTrue(nonTransparentPixels(bitmap) > 100)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun drawsEveryShapeKindWithRatioGeometryAndRotation() {
        val bitmap = Bitmap.createBitmap(1000, 700, Bitmap.Config.ARGB_8888)
        try {
            for (type in ShapeType.values()) {
                bitmap.eraseColor(0)
                val shape = Shape(
                    x = .5f,
                    y = .5f,
                    rotation = 23f,
                    type = type,
                    colorArgb = 0xff00c853.toInt(),
                    isFilled = type != ShapeType.ARROW,
                    widthRatio = .35f,
                    heightRatio = .3f,
                    strokeWidthRatio = .012f
                )
                AnnotationCanvasRendering.drawShape(
                    Canvas(bitmap), shape, 0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()
                )
                assertTrue("no pixels for $type", nonTransparentPixels(bitmap) > 20)
                assertTrue("center miss for $type", AnnotationCanvasRendering.containsShape(shape, 500f, 350f, 1000f, 700f))
                assertFalse("far hit for $type", AnnotationCanvasRendering.containsShape(shape, 20f, 20f, 1000f, 700f))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun normalizedNoteAndShapeRasterScalesCompareAfterResize() {
        val note = Note(.38f, .58f, "same\nnormalized text", isBold = true, rotation = -18f, fontSizeRatio = .035f)
        val shape = Shape(
            x = .72f,
            y = .28f,
            rotation = 31f,
            type = ShapeType.CLOUD,
            colorArgb = 0xff1565c0.toInt(),
            isFilled = true,
            widthRatio = .2f,
            heightRatio = .18f,
            strokeWidthRatio = .01f
        )
        val small = renderScene(400, 300, note, shape)
        val large = renderScene(800, 600, note, shape)
        val resized = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        try {
            Canvas(resized).drawBitmap(large, null, Rect(0, 0, 400, 300), Paint(Paint.FILTER_BITMAP_FLAG))
            val differing = differingPixels(small, resized, tolerance = 42)
            val foreground = unionForegroundPixels(small, resized)
            assertTrue("scaled raster diverged in $differing of $foreground foreground pixels",
                foreground > 100 && differing < foreground * .35f)
            assertTrue(nonTransparentPixels(small) > 100)
            assertTrue(nonTransparentPixels(resized) > 100)
        } finally {
            small.recycle()
            large.recycle()
            resized.recycle()
        }
    }

    @Test
    fun invalidSurfaceGeometryReturnsEmptyAndDoesNotPaint() {
        val note = Note(.5f, .5f, "ignored", fontSizeRatio = .02f)
        assertEquals(0f, AnnotationCanvasRendering.noteBounds(note, 0f, 100f).width(), 0f)
        assertFalse(AnnotationCanvasRendering.containsNote(note, 0f, 0f, 0f, 100f))
        assertEquals(
            0f,
            AnnotationCanvasRendering.noteBounds(note.copy(fontSizeRatio = Float.MAX_VALUE), 100f, 100f).height(),
            0f
        )

        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0)
            AnnotationCanvasRendering.drawShape(
                Canvas(bitmap),
                Shape(.5f, .5f, 0f, ShapeType.RECTANGLE, 0xffff0000.toInt(), widthRatio = .2f, heightRatio = .2f),
                0f,
                0f,
                Float.NaN,
                64f
            )
            assertEquals(0, nonTransparentPixels(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun rtlAndMixedDirectionTextRemainInsideTheSharedCenteredBounds() {
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        try {
            val rtl = "مرحبا بالعالم"
            for (text in listOf(rtl, "first line\n" + rtl, "שלום עולם")) {
                val note = Note(.5f, .5f, text, isBold = true, fontSizeRatio = .05f)
                bitmap.eraseColor(0)
                AnnotationCanvasRendering.drawNote(Canvas(bitmap), note, 0f, 0f, 1200f, 800f, -1)
                val bounds = AnnotationCanvasRendering.noteBounds(note, 1200f, 800f)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                var painted = 0
                pixels.forEachIndexed { index, pixel ->
                    if ((pixel ushr 24) > 64) {
                        painted++
                        val x = index % bitmap.width
                        val y = index / bitmap.width
                        assertTrue("text escaped shared bounds at $x,$y: $bounds",
                            x >= bounds.left - 6f && x <= bounds.right + 6f &&
                                y >= bounds.top - 6f && y <= bounds.bottom + 6f)
                        assertTrue(AnnotationCanvasRendering.containsNote(note, x.toFloat(), y.toFloat(), 1200f, 800f, 6f))
                    }
                }
                assertTrue("note did not render at its center anchor", painted > 100)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderScene(width: Int, height: Int, note: Note, shape: Shape): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(0)
            val canvas = Canvas(bitmap)
            AnnotationCanvasRendering.drawNote(canvas, note, 0f, 0f, width.toFloat(), height.toFloat(), 0xff212121.toInt())
            AnnotationCanvasRendering.drawShape(canvas, shape, 0f, 0f, width.toFloat(), height.toFloat())
        }

    private fun nonTransparentPixels(bitmap: Bitmap): Int {
        var count = 0
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (pixel in pixels) if (pixel ushr 24 != 0) count++
        return count
    }

    private fun unionForegroundPixels(first: Bitmap, second: Bitmap): Int {
        val a = IntArray(first.width * first.height)
        val b = IntArray(second.width * second.height)
        first.getPixels(a, 0, first.width, 0, 0, first.width, first.height)
        second.getPixels(b, 0, second.width, 0, 0, second.width, second.height)
        return a.indices.count { (a[it] ushr 24) != 0 || (b[it] ushr 24) != 0 }
    }

    private fun differingPixels(first: Bitmap, second: Bitmap, tolerance: Int): Int {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        val a = IntArray(first.width * first.height)
        val b = IntArray(second.width * second.height)
        first.getPixels(a, 0, first.width, 0, 0, first.width, first.height)
        second.getPixels(b, 0, second.width, 0, 0, second.width, second.height)
        var differing = 0
        for (i in a.indices) {
            val delta = abs((a[i] ushr 24) - (b[i] ushr 24)) +
                abs(((a[i] shr 16) and 0xff) - ((b[i] shr 16) and 0xff)) +
                abs(((a[i] shr 8) and 0xff) - ((b[i] shr 8) and 0xff)) +
                abs((a[i] and 0xff) - (b[i] and 0xff))
            if (delta > tolerance) differing++
        }
        return differing
    }
}
