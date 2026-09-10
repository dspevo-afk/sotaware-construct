package com.example.myapplication

import java.io.Serializable
import java.util.Collections
import java.util.UUID

/** Source-space calibration, independent of bitmap sampling and viewport zoom. */
data class PageScale(val pointsPerFoot: Float) : Serializable

data class Point(val x: Float, val y: Float) : Serializable {
    fun copyPoint() = copy()
}

data class DrawnPath(
    val points: List<Point>,
    val colorArgb: Int,
    val isHighlighter: Boolean,
    val strokeWidthRatio: Float = .005f,
    val id: String = UUID.randomUUID().toString()
) : Serializable {
    /**
     * Detach both the path object and its nested point list at a reducer/state
     * boundary.  A committed path must not retain a caller-owned mutable list.
     */
    fun copyPath() = copy(
        points = Collections.unmodifiableList(points.map { it.copyPoint() })
    )
}

data class Measurement(
    val p1: Point,
    val p2: Point,
    val text: String,
    val id: String = UUID.randomUUID().toString()
) : Serializable {
    fun copyMeasurement(p1: Point = this.p1, p2: Point = this.p2, text: String = this.text) =
        copy(p1 = p1.copyPoint(), p2 = p2.copyPoint(), text = text)
}

/** Shared PDF/photo text: center and font height are relative to its visible surface. */
data class Note(
    val x: Float,
    val y: Float,
    val text: String,
    val isBold: Boolean = false,
    val rotation: Float = 0f,
    val fontSizeRatio: Float = .02f,
    val id: String = UUID.randomUUID().toString()
) : Serializable {
    fun copyNote() = copy()
    fun copyImageNote() = copy()
}

/** A surface role, not a second model or an older serialized format. */
typealias PhotoImageNote = Note

data class Shape(
    val x: Float,
    val y: Float,
    val rotation: Float,
    val type: ShapeType,
    val colorArgb: Int,
    val isFilled: Boolean = false,
    val strokeWidthRatio: Float = .005f,
    val widthRatio: Float = .1f,
    val heightRatio: Float = .1f,
    val id: String = UUID.randomUUID().toString()
) : Serializable { fun copyShape() = copy() }

data class PhotoPin(
    val x: Float,
    val y: Float,
    val id: String = UUID.randomUUID().toString(),
    val imageFileNames: List<String> = emptyList(),
    val imageNotes: Map<String, List<Note>> = emptyMap(),
    val imageShapes: Map<String, List<Shape>> = emptyMap()
) : Serializable {
    /** Freeze all collection structure at the command boundary before committing. */
    fun copyPin() = copy(
        imageFileNames = Collections.unmodifiableList(imageFileNames.toList()),
        imageNotes = Collections.unmodifiableMap(imageNotes.mapValues { (_, notes) ->
            Collections.unmodifiableList(notes.map { it.copy() })
        }),
        imageShapes = Collections.unmodifiableMap(imageShapes.mapValues { (_, shapes) ->
            Collections.unmodifiableList(shapes.map { it.copy() })
        })
    )
}
