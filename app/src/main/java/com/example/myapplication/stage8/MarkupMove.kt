package com.example.myapplication.stage8

import com.example.myapplication.DrawnPath
import com.example.myapplication.Measurement
import com.example.myapplication.PhotoPin
import com.example.myapplication.Point

/**
 * Moving a selected item by dragging it. Every move translates the whole item by
 * the same offset, clamped so the item stays on its surface ([0,1] in both axes)
 * without changing its shape: a line dragged against an edge stops there instead
 * of being squashed.
 */
object MarkupMove {
    /** The offset actually applied to points spanning [minX..maxX] x [minY..maxY]. */
    fun clampOffset(minX: Float, minY: Float, maxX: Float, maxY: Float, dx: Float, dy: Float): Pair<Float, Float> {
        if (!dx.isFinite() || !dy.isFinite()) return 0f to 0f
        val cx = dx.coerceIn(-minX.coerceAtLeast(0f), (1f - maxX).coerceAtLeast(0f))
        val cy = dy.coerceIn(-minY.coerceAtLeast(0f), (1f - maxY).coerceAtLeast(0f))
        return cx to cy
    }

    private fun shift(points: List<Point>, dx: Float, dy: Float): List<Point> {
        if (points.isEmpty()) return points
        val (cx, cy) = clampOffset(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y }, dx, dy)
        return points.map { Point(it.x + cx, it.y + cy) }
    }

    fun path(path: DrawnPath, dx: Float, dy: Float): DrawnPath = path.copy(points = shift(path.points, dx, dy)).copyPath()

    /** A measurement keeps its text: moving a whole line never changes its length. */
    fun measurement(m: Measurement, dx: Float, dy: Float): Measurement {
        val moved = shift(m.vertices, dx, dy)
        return m.copy(p1 = moved.first(), p2 = moved.last(), intermediatePoints = moved.subList(1, moved.size - 1).toList()).copyMeasurement()
    }

    fun point(p: Point, dx: Float, dy: Float): Point = shift(listOf(p), dx, dy).first()

    fun pin(pin: PhotoPin, dx: Float, dy: Float): PhotoPin = point(Point(pin.x, pin.y), dx, dy).let { pin.copy(x = it.x, y = it.y) }

    /** Moves one vertex of a measurement (ends and every polyline corner alike). */
    fun measurementVertex(m: Measurement, index: Int, to: Point): Measurement? {
        val vertices = m.vertices.toMutableList()
        if (index !in vertices.indices || !to.x.isFinite() || !to.y.isFinite()) return null
        vertices[index] = Point(to.x.coerceIn(0f, 1f), to.y.coerceIn(0f, 1f))
        return m.copy(p1 = vertices.first(), p2 = vertices.last(), intermediatePoints = vertices.subList(1, vertices.size - 1).toList()).copyMeasurement()
    }
}
