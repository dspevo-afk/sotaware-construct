package com.example.myapplication.stage9b

import com.example.myapplication.Shape
import com.example.myapplication.ShapeType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure hit/ratio checks; drawing and StaticLayout behavior are covered on Android. */
class AnnotationRenderingGeometryTest {
    @Test
    fun normalizedShapeDimensionsScaleWithTheVisibleSurface() {
        val shape = Shape(
            x = .5f,
            y = .5f,
            rotation = 0f,
            type = ShapeType.RECTANGLE,
            colorArgb = 0xff00ff00.toInt(),
            widthRatio = .25f,
            heightRatio = .5f,
            strokeWidthRatio = .01f
        )

        assertTrue(AnnotationCanvasRendering.containsShape(shape, 500f, 400f, 1000f, 800f))
        assertTrue(AnnotationCanvasRendering.containsShape(shape, 624f, 400f, 1000f, 800f))
        assertFalse(AnnotationCanvasRendering.containsShape(shape, 626f, 400f, 1000f, 800f))
        assertTrue(AnnotationCanvasRendering.containsShape(shape, 1248f, 800f, 2000f, 1600f))
        assertFalse(AnnotationCanvasRendering.containsShape(shape, 1252f, 800f, 2000f, 1600f))
    }

    @Test
    fun rotatedShapeHitUsesInverseRotationAndSurfacePadding() {
        val shape = Shape(
            x = .5f,
            y = .5f,
            rotation = 90f,
            type = ShapeType.RECTANGLE,
            colorArgb = 0xffff0000.toInt(),
            widthRatio = .2f,
            heightRatio = .1f,
            strokeWidthRatio = .01f
        )

        // The 200x100 rectangle is rotated clockwise, so its long axis is vertical.
        assertTrue(AnnotationCanvasRendering.containsShape(shape, 500f, 590f, 1000f, 1000f))
        assertFalse(AnnotationCanvasRendering.containsShape(shape, 500f, 610f, 1000f, 1000f))
        assertTrue(AnnotationCanvasRendering.containsShape(shape, 500f, 610f, 1000f, 1000f, padding = 12f))
    }

    @Test
    fun invalidGeometryIsIgnoredWithoutThrowing() {
        val invalid = Shape(
            x = Float.NaN,
            y = .5f,
            rotation = 0f,
            type = ShapeType.CLOUD,
            colorArgb = 0xffffffff.toInt(),
            widthRatio = .2f,
            heightRatio = .2f,
            strokeWidthRatio = .01f
        )
        assertFalse(AnnotationCanvasRendering.containsShape(invalid, 50f, 50f, 100f, 100f))
        assertFalse(AnnotationCanvasRendering.containsShape(invalid, 50f, 50f, 0f, 100f))
    }
}
