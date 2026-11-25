package com.example.myapplication.stage8

import com.example.myapplication.Point
import org.junit.Assert.*
import org.junit.Test

class ViewerGestureAdmissionTest {
    @Test fun fittedPageGuttersCannotBecomeNewAnnotationPoints() {
        val transform = ViewerTransform(600f, 900f, 1200f, 600f)
        assertNull(transform.toNormalizedIfInsidePage(200f, 100f))
        assertNull(transform.toNormalizedIfInsidePage(200f, 500f))
        assertNull(transform.toNormalizedIfInsidePage(1000f, 300f))
        assertEquals(Point(0f, .5f), transform.toNormalizedIfInsidePage(400f, 300f))
        assertEquals(Point(1f, .5f), transform.toNormalizedIfInsidePage(800f, 300f))
        // Drags already admitted inside the page may still clamp at its edge.
        assertEquals(Point(0f, .5f), transform.toNormalized(200f, 300f))
    }

    @Test fun annotationAdmissionFollowsZoomAndTranslation() {
        val transform = ViewerTransform(400f, 800f, 600f, 600f, 2f, 37f, -29f)
        val expected = Point(.25f, .75f)
        val screen = transform.toScreen(expected)
        val actual = requireNotNull(transform.toNormalizedIfInsidePage(screen.x, screen.y))
        assertEquals(expected.x, actual.x, .0001f)
        assertEquals(expected.y, actual.y, .0001f)
        assertNull(transform.toNormalizedIfInsidePage(transform.left - 1f, screen.y))
        assertNull(transform.toNormalizedIfInsidePage(screen.x, transform.top - 1f))
    }

    @Test fun nonFiniteTouchesCannotStartAnnotations() {
        val transform = ViewerTransform(100f, 100f, 200f, 200f)
        assertNull(transform.toNormalizedIfInsidePage(Float.NaN, 50f))
        assertNull(transform.toNormalizedIfInsidePage(50f, Float.POSITIVE_INFINITY))
    }
}
