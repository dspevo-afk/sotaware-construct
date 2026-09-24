package com.example.myapplication.stage8

import com.example.myapplication.Measurement
import com.example.myapplication.PageScale

/** A recalibration either replaces every derived label, or changes nothing. */
internal fun recalibratedMeasurements(
    values: List<Measurement>, scale: PageScale?, size: AnnotationSize?
): List<Measurement>? {
    if (values.isEmpty()) return emptyList()
    if (scale == null || size == null || size.width <= 0f || size.height <= 0f) return null
    val result = ArrayList<Measurement>(values.size)
    for (value in values) {
        val distance = measurementSourceLength(value.vertices, size.width, size.height, allowZero = true) ?: return null
        val label = formatSourceDistance(distance, scale.pointsPerFoot) ?: return null
        result += value.copyMeasurement(text = label)
    }
    return result
}
