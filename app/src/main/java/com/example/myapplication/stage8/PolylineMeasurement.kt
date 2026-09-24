package com.example.myapplication.stage8

import com.example.myapplication.Measurement
import com.example.myapplication.PageScale
import com.example.myapplication.Point
import com.example.myapplication.stage5.Stage5Limits
import kotlin.math.hypot

fun measurementSourceLength(points: List<Point>, width: Float, height: Float, allowZero: Boolean = false): Float? {
    if (points.size !in 2..Stage5Limits.MAX_PATH_POINTS || !width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f ||
        points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return null
    val distance = points.zipWithNext().sumOf { (a, b) ->
        hypot((b.x.toDouble() - a.x) * width, (b.y.toDouble() - a.y) * height)
    }
    return distance.takeIf { it.isFinite() && (it > 0.0 || (allowZero && it == 0.0)) && it <= Float.MAX_VALUE }?.toFloat()
}

fun buildMeasurement(points: List<Point>, width: Float, height: Float, scale: PageScale?, style: DrawingToolStyle): Measurement? {
    if (scale == null || !scale.pointsPerFoot.isFinite() || scale.pointsPerFoot <= 0f) return null
    val distance = measurementSourceLength(points, width, height) ?: return null
    val text = formatSourceDistance(distance, scale.pointsPerFoot) ?: return null
    return Measurement(points.first().copyPoint(), points.last().copyPoint(), text,
        intermediatePoints = points.drop(1).dropLast(1).map { it.copyPoint() }, colorArgb = style.colorArgb, strokeWidthRatio = style.width)
}
