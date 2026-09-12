package com.example.myapplication.stage8

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.myapplication.DrawnPath
import com.example.myapplication.Measurement
import com.example.myapplication.Note
import com.example.myapplication.PageItem
import com.example.myapplication.PhotoPin
import com.example.myapplication.Point
import com.example.myapplication.Shape
import kotlin.math.hypot
import kotlin.math.roundToLong

/** Calculated dimensions use whole inches, rounding the total once before splitting. */
fun formatFeet(feet: Float): String {
    require(feet.isFinite() && feet >= 0f) { "Length must be finite and nonnegative" }
    val totalInches = (feet * 12f).roundToLong()
    val wholeFeet = totalInches / 12
    val inches = totalInches % 12
    return if (wholeFeet > 0) "$wholeFeet' $inches\"" else "$inches\""
}

/** The pending measurement/calibration points are also the banner's phase authority. */
class MeasurementPointSelection {
    var firstPoint: Point? by mutableStateOf(null)
    var secondPoint: Point? by mutableStateOf(null)
    val hasFirstPoint: Boolean get() = firstPoint != null
    fun clear() { firstPoint = null; secondPoint = null }
}

/** Local viewer pixels, after PDF crop/rotation mapping has produced the bitmap. */
data class ViewerTransform(
    val bitmapWidth: Float,
    val bitmapHeight: Float,
    val viewerWidth: Float,
    val viewerHeight: Float,
    val zoom: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f
) {
    init {
        require(listOf(bitmapWidth, bitmapHeight, viewerWidth, viewerHeight, zoom).all { it.isFinite() && it > 0f })
        require(translationX.isFinite() && translationY.isFinite())
    }
    val fittedScale: Float get() = minOf(viewerWidth / bitmapWidth, viewerHeight / bitmapHeight)
    val compositeScale: Float get() = fittedScale * zoom
    val left: Float get() = (viewerWidth - bitmapWidth * compositeScale) / 2 + translationX
    val top: Float get() = (viewerHeight - bitmapHeight * compositeScale) / 2 + translationY
    fun toScreen(point: Point): Point = Point(
        left + point.x * bitmapWidth * compositeScale,
        top + point.y * bitmapHeight * compositeScale
    )
    fun toNormalized(x: Float, y: Float): Point = Point(
        ((x - left) / (bitmapWidth * compositeScale)).coerceIn(0f, 1f),
        ((y - top) / (bitmapHeight * compositeScale)).coerceIn(0f, 1f)
    )
    fun hits(point: Point, screenX: Float, screenY: Float, radiusPx: Float): Boolean {
        val screen = toScreen(point)
        return hypot(screen.x - screenX, screen.y - screenY) < radiusPx
    }

    companion object {
        fun clampControl(position: Point, width: Float, height: Float, viewerWidth: Float, viewerHeight: Float): Point = Point(
            position.x.coerceIn(0f, (viewerWidth - width).coerceAtLeast(0f)),
            position.y.coerceIn(0f, (viewerHeight - height).coerceAtLeast(0f))
        )
    }
}

/** Selection stores identity only. Ordinals and objects are resolved at use time. */
data class PdfSelectionKey(val kind: Kind, val id: String) {
    enum class Kind { MEASUREMENT, NOTE, PATH, PHOTO, SHAPE }
    fun resolve(
        measurements: List<Measurement>, notes: List<Note>, paths: List<DrawnPath>,
        photos: List<PhotoPin>, shapes: List<Shape>
    ): PageItem? = when (kind) {
        Kind.MEASUREMENT -> measurements.firstOrNull { it.id == id }?.let { PageItem.Measure(it) }
        Kind.NOTE -> notes.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { PageItem.NoteItem(notes[it], it) }
        Kind.PATH -> paths.firstOrNull { it.id == id }?.let { PageItem.Path(it) }
        Kind.PHOTO -> photos.firstOrNull { it.id == id }?.let { PageItem.PhotoPinItem(it) }
        Kind.SHAPE -> shapes.firstOrNull { it.id == id }?.let { PageItem.ShapeItem(it) }
    }
    companion object {
        fun from(item: PageItem): PdfSelectionKey = when (item) {
            is PageItem.Measure -> PdfSelectionKey(Kind.MEASUREMENT, item.data.id)
            is PageItem.NoteItem -> PdfSelectionKey(Kind.NOTE, item.data.id)
            is PageItem.Path -> PdfSelectionKey(Kind.PATH, item.data.id)
            is PageItem.PhotoPinItem -> PdfSelectionKey(Kind.PHOTO, item.data.id)
            is PageItem.ShapeItem -> PdfSelectionKey(Kind.SHAPE, item.data.id)
        }
    }
}

/** Photo rendering paints notes in list order, then shapes in list order. */
object PhotoAnnotationStack {
    sealed interface Hit {
        data class NoteHit(val note: Note) : Hit
        data class ShapeHit(val shape: Shape) : Hit
    }
    fun hit(notes: List<Note>, shapes: List<Shape>, containsNote: (Note) -> Boolean, containsShape: (Shape) -> Boolean): Hit? =
        shapes.lastOrNull(containsShape)?.let { Hit.ShapeHit(it) }
            ?: notes.lastOrNull(containsNote)?.let { Hit.NoteHit(it) }
}
