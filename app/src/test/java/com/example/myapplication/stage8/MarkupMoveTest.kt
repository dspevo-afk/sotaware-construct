package com.example.myapplication.stage8

import com.example.myapplication.DrawnPath
import com.example.myapplication.Measurement
import com.example.myapplication.PhotoPin
import com.example.myapplication.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkupMoveTest {
    private fun assertPoint(x: Float, y: Float, p: Point) { assertEquals(x, p.x, 1e-5f); assertEquals(y, p.y, 1e-5f) }

    @Test
    fun pathMovesWholeAndStopsAtTheEdgeWithoutChangingShape() {
        val path = DrawnPath(listOf(Point(.1f, .1f), Point(.3f, .2f)), 0, false, id = "p")
        val moved = MarkupMove.path(path, .2f, .1f)
        assertPoint(.3f, .2f, moved.points[0]); assertPoint(.5f, .3f, moved.points[1])
        val clamped = MarkupMove.path(path, .9f, -.5f)
        assertPoint(.8f, 0f, clamped.points[0]); assertPoint(1f, .1f, clamped.points[1])
        assertEquals("p", clamped.id)
    }

    @Test
    fun measurementMovesEveryVertexAndKeepsItsText() {
        val m = Measurement(Point(.2f, .2f), Point(.4f, .2f), "10'", id = "m", intermediatePoints = listOf(Point(.3f, .3f)))
        val moved = MarkupMove.measurement(m, .1f, .1f)
        assertPoint(.3f, .3f, moved.p1); assertPoint(.4f, .4f, moved.intermediatePoints.single()); assertPoint(.5f, .3f, moved.p2)
        assertEquals("10'", moved.text)
    }

    @Test
    fun vertexDragReachesPolylineCornersAndRejectsBadIndexes() {
        val m = Measurement(Point(.2f, .2f), Point(.4f, .2f), "x", intermediatePoints = listOf(Point(.3f, .3f)))
        val corner = MarkupMove.measurementVertex(m, 1, Point(.35f, .5f))!!
        assertPoint(.35f, .5f, corner.intermediatePoints.single())
        assertPoint(.2f, .2f, corner.p1); assertPoint(.4f, .2f, corner.p2)
        assertPoint(.9f, .2f, MarkupMove.measurementVertex(m, 2, Point(.9f, .2f))!!.p2)
        assertNull(MarkupMove.measurementVertex(m, 3, Point(.1f, .1f)))
        assertNull(MarkupMove.measurementVertex(m, 0, Point(Float.NaN, .1f)))
    }

    @Test
    fun pinAndPointClampToTheSurface() {
        val pin = MarkupMove.pin(PhotoPin(.5f, .5f, id = "pin"), .7f, -.2f)
        assertEquals(1f, pin.x, 1e-5f); assertEquals(.3f, pin.y, 1e-5f)
        assertEquals(0f to 0f, MarkupMove.clampOffset(0f, 0f, 1f, 1f, Float.NaN, .1f))
    }
}
