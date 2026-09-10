package com.example.myapplication.stage8

import com.example.myapplication.stage5.Stage5Limits
import java.util.UUID

/**
 * Shared current annotation admission and normalized coordinate primitives.
 * Runtime values live in AnnotationModels; no parallel model is maintained.
 */
data class NormalizedPoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite()) { "point coordinates must be finite" }
        require(x in 0f..1f && y in 0f..1f) { "point coordinates must be normalized" }
    }
}

/** IDs are opaque to callers; UUID syntax is used only as a bounded generator. */
fun newAnnotationId(): String = UUID.randomUUID().toString()

fun validAnnotationId(value: String): Boolean =
    value.length in 1..128 && value.isNotBlank() &&
        value.all { it.code in 0x21..0x7e } &&
        !value.contains('/') && !value.contains('\\') && !value.contains("..")

/** Shared admission helpers used by reducer and snapshot adapters. */
object AnnotationModelV2 {
    const val MAX_ROTATION_DEGREES = 360_000f

    fun validatePoint(point: NormalizedPoint) = point

    fun validateId(id: String) {
        require(validAnnotationId(id)) { "invalid annotation id" }
    }

    fun validateUniqueIds(ids: Iterable<String>) {
        val seen = HashSet<String>()
        ids.forEach { id ->
            validateId(id)
            require(seen.add(id)) { "duplicate annotation id" }
        }
    }

    fun validateRotation(rotation: Float) {
        require(rotation.isFinite() && kotlin.math.abs(rotation) <= MAX_ROTATION_DEGREES) {
            "rotation is invalid"
        }
    }
}
