package com.example.myapplication.stage8

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.DrawnPath
import com.example.myapplication.Measurement
import com.example.myapplication.Note
import com.example.myapplication.PageScale
import com.example.myapplication.PageItem
import com.example.myapplication.PhotoImageNote
import com.example.myapplication.PhotoPin
import com.example.myapplication.Point
import com.example.myapplication.Shape
import com.example.myapplication.stage5.Stage5Limits
import java.util.LinkedHashSet

/** Bounded in-memory history retained by the document ViewModel. */
internal object AnnotationHistoryLimits {
    const val MAX_ENTRIES: Int = 128
    const val MAX_BYTES: Long = 8L * 1024L * 1024L
}

private fun stringBytes(value: String): Long = value.length.toLong() * 2L
private fun pathBytes(value: DrawnPath?): Long = value?.let { 80L + it.points.size * 24L } ?: 0L
private fun measurementBytes(value: Measurement?): Long = value?.let { 112L + stringBytes(it.text) + it.intermediatePoints.size * 24L } ?: 0L
private fun noteBytes(value: Note?): Long = value?.let { 128L + stringBytes(it.text) } ?: 0L
private fun imageNoteBytes(value: PhotoImageNote?): Long = value?.let { 128L + stringBytes(it.text) } ?: 0L
private fun shapeBytes(value: Shape?): Long = value?.let { 160L } ?: 0L

private fun validId(id: String): Boolean = com.example.myapplication.stage8.validAnnotationId(id)
private fun validNorm(value: Float): Boolean = value.isFinite() && value in 0f..1f
private fun validRatio(value: Float): Boolean = value.isFinite() && value > 0f && value <= 1f
private fun validRotation(value: Float): Boolean = value.isFinite() && kotlin.math.abs(value) <= AnnotationModelV2.MAX_ROTATION_DEGREES
private fun validText(value: String): Boolean = value.isNotBlank() && value.length <= Stage5Limits.MAX_TEXT_CHARS
// A sub-drawing name is optional ("104", "Kitchen"); blank input means unnamed.
private fun validSubDrawingName(value: String?): Boolean =
    value == null || (value.length <= Stage5Limits.MAX_ID_CHARS && value.isNotBlank())
// A badge radius is a finite page-width fraction within the pinchable range.
private fun validBadgeRatio(value: Float): Boolean =
    value.isFinite() && value >= com.example.myapplication.SubDrawingGeometry.MIN_BADGE_RATIO &&
        value <= com.example.myapplication.SubDrawingGeometry.MAX_BADGE_RATIO
private fun validFileName(value: String): Boolean = try {
    com.example.myapplication.stage5.validatePhotoFileName(value)
    true
} catch (_: IllegalArgumentException) { false }

private fun validatePoint(point: Point): Boolean = validNorm(point.x) && validNorm(point.y)

private fun validatePath(path: DrawnPath): Boolean =
    validId(path.id) && path.points.isNotEmpty() && path.points.size <= Stage5Limits.MAX_PATH_POINTS &&
        path.points.all(::validatePoint) && validRatio(path.strokeWidthRatio)

private fun validateMeasurement(value: Measurement): Boolean =
    validId(value.id) && validatePoint(value.p1) && validatePoint(value.p2) && validText(value.text) &&
        value.intermediatePoints.size <= Stage5Limits.MAX_PATH_POINTS - 2 && value.intermediatePoints.all(::validatePoint) &&
        validRatio(value.strokeWidthRatio)

private fun validateNote(value: Note): Boolean =
    validId(value.id) && validNorm(value.x) && validNorm(value.y) && validText(value.text) &&
        validRatio(value.fontSizeRatio) && validRotation(value.rotation)

private fun validateShape(value: Shape): Boolean =
    validId(value.id) && validNorm(value.x) && validNorm(value.y) && validRatio(value.widthRatio) &&
        validRatio(value.heightRatio) && validRatio(value.strokeWidthRatio) && validRotation(value.rotation)

private fun validateSubDrawing(value: com.example.myapplication.SubDrawing): Boolean {
    if (!validId(value.id)) return false
    if (!com.example.myapplication.SubDrawingGeometry.isValidCrop(value.left, value.top, value.right, value.bottom)) return false
    if (value.colorIndex !in 0 until com.example.myapplication.SubDrawingPalette.size) return false
    if (!validSubDrawingName(value.name)) return false
    if (value.badgeRatio != null && !validBadgeRatio(value.badgeRatio)) return false
    if ((value.badgeX == null) != (value.badgeY == null)) return false
    if (value.badgeX != null && !com.example.myapplication.SubDrawingGeometry.isValidBadgePosition(value.badgeX, value.badgeY!!)) return false
    if (value.scale != null && !isValidPageScale(value.scale.pointsPerFoot)) return false
    val ids = value.paths.map { it.id } + value.measurements.map { it.id } +
        value.notes.map { it.id } + value.shapes.map { it.id } + value.photos.map { it.id }
    if (ids.toSet().size != ids.size) return false
    if (ids.size + 1L > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE) return false
    if (value.photos.size > Stage5Limits.MAX_PHOTO_PINS_PER_PAGE) return false
    if (value.paths.any { !validatePath(it) } || value.measurements.any { !validateMeasurement(it) } ||
        value.notes.any { !validateNote(it) } || value.shapes.any { !validateShape(it) } ||
        value.photos.any { !validatePin(it) }
    ) return false
    return true
}

private fun subDrawingBytes(value: com.example.myapplication.SubDrawing?): Long = value?.let {
    132L + stringBytes(it.name.orEmpty()) + it.paths.sumOf(::pathBytes) + it.measurements.sumOf(::measurementBytes) +
        it.notes.sumOf(::noteBytes) + it.shapes.sumOf(::shapeBytes) + it.photos.sumOf { pin ->
            128L + pin.imageFileNames.sumOf { name -> stringBytes(name) } +
                pin.imageNotes.values.sumOf { list -> list.sumOf(::imageNoteBytes) } +
                pin.imageShapes.values.sumOf { list -> list.sumOf(::shapeBytes) } +
                pin.imagePaths.values.sumOf { list -> list.sumOf(::pathBytes) } +
                pin.imageMeasurements.values.sumOf { list -> list.sumOf(::measurementBytes) } +
                pin.imageScales.size * 32L
        } +
        (if (it.scale != null) 32L else 0L)
} ?: 0L

private fun subDrawingAnnotationCount(sub: com.example.myapplication.SubDrawing): Long =
    1L + sub.paths.size.toLong() + sub.measurements.size.toLong() +
        sub.notes.size.toLong() + sub.shapes.size.toLong() +
        sub.photos.sumOf { pinAnnotationCount(it) } + (if (sub.scale != null) 1L else 0L)

private fun validateImageNote(value: PhotoImageNote): Boolean =
    validId(value.id) && validNorm(value.x) && validNorm(value.y) && validText(value.text) &&
        validRatio(value.fontSizeRatio) && validRotation(value.rotation)

private fun validatePin(value: PhotoPin): Boolean {
    if (!validId(value.id) || !validNorm(value.x) || !validNorm(value.y)) return false
    if (value.imageFileNames.size > Stage5Limits.MAX_PHOTOS_PER_PIN ||
        value.imageFileNames.any { !validFileName(it) } || value.imageFileNames.toSet().size != value.imageFileNames.size
    ) return false
    val attached = value.imageFileNames.toSet()
    if ((value.imageNotes.keys + value.imageShapes.keys + value.imagePaths.keys +
            value.imageMeasurements.keys + value.imageScales.keys).any { it !in attached }) return false
    if (value.imageNotes.values.any { it.size > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE } ||
        value.imageShapes.values.any { it.size > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE }
    ) return false
    value.imageNotes.values.forEach { notes ->
        val ids = notes.map { it.id }
        if (ids.toSet().size != ids.size || notes.any { !validateImageNote(it) }) return false
    }
    value.imageShapes.values.forEach { shapes ->
        val ids = shapes.map { it.id }
        if (ids.toSet().size != ids.size || shapes.any { !validateShape(it) }) return false
    }
    if (value.imagePaths.values.any { it.size > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE || it.any { path -> !validatePath(path) } } ||
        value.imageMeasurements.values.any { it.size > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE || it.any { m -> !validateMeasurement(m) } } ||
        value.imageScales.values.any { !isValidPageScale(it.pointsPerFoot) }) return false
    for (fileName in attached) {
        val noteIds = value.imageNotes[fileName].orEmpty().map { it.id }
        val shapeIds = value.imageShapes[fileName].orEmpty().map { it.id }
        val ids = noteIds + shapeIds + value.imagePaths[fileName].orEmpty().map { it.id } +
            value.imageMeasurements[fileName].orEmpty().map { it.id }
        if (ids.toSet().size != ids.size) return false
        if (ids.size + (if (fileName in value.imageScales) 1 else 0) > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE) return false
    }
    return true
}

private fun pagePhotoReferenceCount(vm: BlueprintViewModel): Long =
    vm.pagePhotoPins.values.sumOf { pins ->
        pins.sumOf { it.imageFileNames.size.toLong() }
    }

private fun pinAnnotationCount(pin: PhotoPin): Long =
    1L + pin.imageNotes.values.sumOf { it.size.toLong() } +
        pin.imageShapes.values.sumOf { it.size.toLong() } +
        pin.imagePaths.values.sumOf { it.size.toLong() } +
        pin.imageMeasurements.values.sumOf { it.size.toLong() } + pin.imageScales.size

/** Counts every annotation domain that the current snapshot validator budgets. */
/** Every photo reference stored on a sub-drawing, across all pages. */
private fun subDrawingPhotoReferenceCount(vm: BlueprintViewModel): Long =
    vm.pageSubDrawings.values.sumOf { subs ->
        subs.sumOf { sub -> sub.photos.sumOf { it.imageFileNames.size.toLong() } }
    }

private fun pageAnnotationCount(vm: BlueprintViewModel, page: Int): Long {
    val paths = vm.pagePaths[page]?.size ?: 0
    val measurements = vm.pageMeasurements[page]?.size ?: 0
    val notes = vm.pageNotes[page]?.size ?: 0
    val pins = vm.pagePhotoPins[page].orEmpty()
    val shapes = vm.pageShapes[page]?.size ?: 0
    val nested = pins.sumOf { pinAnnotationCount(it) - 1L }
    val subDrawings = vm.pageSubDrawings[page].orEmpty().sumOf { subDrawingAnnotationCount(it) }
    val scale = if (vm.pageScales.containsKey(page)) 1L else 0L
    return paths.toLong() + measurements + notes + pins.size + shapes + nested + subDrawings + scale
}

private fun hasPageAnnotationCapacity(
    vm: BlueprintViewModel,
    page: Int,
    additional: Long = 1L
): Boolean = additional >= 0L &&
    pageAnnotationCount(vm, page) <= Stage5Limits.MAX_ANNOTATIONS_PER_PAGE.toLong() - additional

private fun pageContainsAnnotationId(vm: BlueprintViewModel, page: Int, id: String): Boolean =
    vm.pagePaths[page].orEmpty().any { it.id == id } ||
        vm.pageMeasurements[page].orEmpty().any { it.id == id } ||
        vm.pageNotes[page].orEmpty().any { it.id == id } ||
        vm.pagePhotoPins[page].orEmpty().any { it.id == id } ||
        vm.pageShapes[page].orEmpty().any { it.id == id } ||
        vm.pageSubDrawings[page].orEmpty().any { sub ->
            sub.id == id || sub.paths.any { it.id == id } || sub.measurements.any { it.id == id } ||
                sub.notes.any { it.id == id } || sub.shapes.any { it.id == id } ||
                sub.photos.any { pin ->
                    pin.id == id || pin.imageNotes.values.any { notes -> notes.any { it.id == id } } ||
                        pin.imageShapes.values.any { shapes -> shapes.any { it.id == id } } ||
                        pin.imagePaths.values.any { paths -> paths.any { it.id == id } } ||
                        pin.imageMeasurements.values.any { values -> values.any { it.id == id } }
                }
        }

/**
 * The only editable annotation state boundary.  Every accepted command first
 * validates the complete candidate and expected-before identity, then applies
 * one detached transition and records one bounded history entry. Rejected and
 * unchanged commands have no state, history, or effect side effects.
 */
class AnnotationReducer(
    private val vm: BlueprintViewModel,
    private val effectSink: (EffectIntent) -> Unit = {},
    private val sessionKey: Any?,
    private val currentSessionKey: () -> Any?,
    private val sessionActivePredicate: () -> Boolean
) {
    enum class Kind { ADD, UPDATE, DELETE, MOVE, RESIZE, ROTATE, CLEAR, UNDO, REDO }

    /** Outcome of a validated reducer command. */
    enum class Result {
        Accepted,
        Unchanged,
        Rejected;

        val changed: Boolean get() = this == Accepted
    }

    data class EffectIntent(
        val page: Int,
        val kind: Kind,
        val pinId: String? = null,
        val fileName: String? = null,
        val annotationId: String? = null
    )

    internal sealed class Entry {
        abstract val page: Int
        abstract val kind: Kind
        abstract val weightBytes: Long
        abstract fun deepCopy(): Entry
        abstract fun apply(vm: BlueprintViewModel): Boolean
        abstract fun reverse(vm: BlueprintViewModel): Boolean
        abstract fun intent(kind: Kind): EffectIntent
    }

    private data class PathEntry(
        override val page: Int,
        val before: DrawnPath?,
        val after: DrawnPath?,
        val ordinal: Int,
        override val kind: Kind
    ) : Entry() {
        override val weightBytes get() = 64L + pathBytes(before) + pathBytes(after)
        override fun deepCopy() = copy(before = before?.copyPath(), after = after?.copyPath())
        override fun apply(vm: BlueprintViewModel) = replaceById(vm.pagePaths[page], before?.id ?: after?.id, before, after, ordinal)
        override fun reverse(vm: BlueprintViewModel) = replaceById(vm.pagePaths[page], before?.id ?: after?.id, after, before, ordinal)
        override fun intent(kind: Kind) = EffectIntent(page, kind, annotationId = before?.id ?: after?.id)
    }

    private data class MeasurementEntry(
        override val page: Int,
        val before: Measurement?,
        val after: Measurement?,
        val ordinal: Int,
        override val kind: Kind
    ) : Entry() {
        override val weightBytes get() = 64L + measurementBytes(before) + measurementBytes(after)
        override fun deepCopy() = copy(
            before = before?.let { it.copyMeasurement(it.p1.copyPoint(), it.p2.copyPoint()) },
            after = after?.let { it.copyMeasurement(it.p1.copyPoint(), it.p2.copyPoint()) }
        )
        override fun apply(vm: BlueprintViewModel) = replaceById(vm.pageMeasurements[page], before?.id ?: after?.id, before, after, ordinal)
        override fun reverse(vm: BlueprintViewModel) = replaceById(vm.pageMeasurements[page], before?.id ?: after?.id, after, before, ordinal)
        override fun intent(kind: Kind) = EffectIntent(page, kind, annotationId = before?.id ?: after?.id)
    }

    private data class NoteEntry(
        override val page: Int,
        val before: Note?,
        val after: Note?,
        val ordinal: Int,
        override val kind: Kind
    ) : Entry() {
        override val weightBytes get() = 64L + noteBytes(before) + noteBytes(after)
        override fun deepCopy() = copy(before = before?.copyNote(), after = after?.copyNote())
        override fun apply(vm: BlueprintViewModel) = replaceById(vm.pageNotes[page], before?.id ?: after?.id, before, after, ordinal)
        override fun reverse(vm: BlueprintViewModel) = replaceById(vm.pageNotes[page], before?.id ?: after?.id, after, before, ordinal)
        override fun intent(kind: Kind) = EffectIntent(page, kind, annotationId = before?.id ?: after?.id)
    }

    private data class PinEntry(
        override val page: Int,
        val before: PhotoPin?,
        val after: PhotoPin?,
        val id: String,
        val ordinal: Int,
        override val kind: Kind
    ) : Entry() {
        override val weightBytes get() = 64L + pinBytes(before) + pinBytes(after)
        override fun deepCopy() = copy(before = before?.copyPin(), after = after?.copyPin())
        override fun apply(vm: BlueprintViewModel) = replacePin(vm.pagePhotoPins[page], id, before, after, ordinal)
        override fun reverse(vm: BlueprintViewModel) = replacePin(vm.pagePhotoPins[page], id, after, before, ordinal)
        override fun intent(kind: Kind) = EffectIntent(page, kind, annotationId = id)
    }

    private data class ShapeEntry(
        override val page: Int,
        val pinId: String?,
        val fileName: String?,
        val before: Shape?,
        val after: Shape?,
        val id: String,
        val ordinal: Int,
        override val kind: Kind
    ) : Entry() {
        override val weightBytes get() = 96L + shapeBytes(before) + shapeBytes(after) + (fileName?.length?.toLong() ?: 0L) * 2L
        override fun deepCopy() = copy(before = before?.copyShape(), after = after?.copyShape())
        override fun apply(vm: BlueprintViewModel): Boolean = replace(vm, before, after)
        override fun reverse(vm: BlueprintViewModel): Boolean = replace(vm, after, before)
        override fun intent(kind: Kind) = EffectIntent(page, kind, pinId, fileName, id)
        private fun replace(vm: BlueprintViewModel, expected: Shape?, value: Shape?): Boolean {
            if (pinId == null) {
                return replaceShape(vm.pageShapes[page], id, expected, value, ordinal)
            }
            val file = fileName ?: return false
            return replaceImageShape(vm, page, pinId, file, id, expected, value, ordinal)
        }
    }

    private data class ImageNoteEntry(
        override val page: Int,
        val pinId: String,
        val fileName: String,
        val before: PhotoImageNote?,
        val after: PhotoImageNote?,
        val id: String,
        val ordinal: Int,
        override val kind: Kind
    ) : Entry() {
        override val weightBytes get() = 112L + stringBytes(fileName) + imageNoteBytes(before) + imageNoteBytes(after)
        override fun deepCopy() = copy(before = before?.copyImageNote(), after = after?.copyImageNote())
        override fun apply(vm: BlueprintViewModel) = replaceImageNote(vm, page, pinId, fileName, id, before, after, ordinal)
        override fun reverse(vm: BlueprintViewModel) = replaceImageNote(vm, page, pinId, fileName, id, after, before, ordinal)
        override fun intent(kind: Kind) = EffectIntent(page, kind, pinId, fileName, id)
    }

    private data class ScaleEntry(
        override val page: Int,
        val before: PageScale?,
        val after: PageScale?,
        val beforeMeasurements: List<Measurement>,
        val afterMeasurements: List<Measurement>,
        override val kind: Kind = Kind.UPDATE
    ) : Entry() {
        override val weightBytes get() = 128L + beforeMeasurements.sumOf(::measurementBytes) + afterMeasurements.sumOf(::measurementBytes)
        override fun deepCopy() = copy(before = before?.copy(), after = after?.copy(),
            beforeMeasurements = beforeMeasurements.map { it.copyMeasurement() },
            afterMeasurements = afterMeasurements.map { it.copyMeasurement() })
        override fun apply(vm: BlueprintViewModel) = replace(vm, before, after, beforeMeasurements, afterMeasurements)
        override fun reverse(vm: BlueprintViewModel) = replace(vm, after, before, afterMeasurements, beforeMeasurements)
        override fun intent(kind: Kind) = EffectIntent(page, kind)
        private fun replace(vm: BlueprintViewModel, expected: PageScale?, value: PageScale?,
            expectedMeasurements: List<Measurement>, values: List<Measurement>): Boolean {
            if (vm.pageScales[page] != expected || vm.pageMeasurements[page].orEmpty().toList() != expectedMeasurements) return false
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                if (value == null) vm.pageScales.remove(page) else vm.pageScales[page] = value.copy()
                vm.pageMeasurements[page]?.let { list ->
                    list.clear(); list.addAll(values.map { it.copyMeasurement() })
                }
            }
            return true
        }
    }

    private data class PageSnapshot(
        val paths: List<DrawnPath>?,
        val measurements: List<Measurement>?,
        val notes: List<Note>?,
        val photoPins: List<PhotoPin>?,
        val scale: PageScale?,
        val scalePresent: Boolean,
        val shapes: List<Shape>?,
        val subDrawings: List<com.example.myapplication.SubDrawing>? = null
    ) {
        val weightBytes: Long
            get() = 64L + (paths?.sumOf(::pathBytes) ?: 0L) + (measurements?.sumOf(::measurementBytes) ?: 0L) +
                (notes?.sumOf(::noteBytes) ?: 0L) + (photoPins?.sumOf(::pinBytes) ?: 0L) +
                (shapes?.sumOf(::shapeBytes) ?: 0L) + (subDrawings?.sumOf(::subDrawingBytes) ?: 0L) +
                if (scale != null) 32L else 0L

        fun deepCopy() = copy(
            paths = paths?.map { it.copyPath() },
            measurements = measurements?.map { it.copyMeasurement(it.p1.copyPoint(), it.p2.copyPoint()) },
            notes = notes?.map { it.copyNote() }, photoPins = photoPins?.map { it.copyPin() },
            scale = scale?.copy(), shapes = shapes?.map { it.copyShape() },
            subDrawings = subDrawings?.map { it.copySubDrawing() }
        )
    }

    private data class SubDrawingEntry(
        override val page: Int,
        val before: com.example.myapplication.SubDrawing?,
        val after: com.example.myapplication.SubDrawing?,
        val id: String,
        val ordinal: Int,
        override val kind: Kind
    ) : Entry() {
        override val weightBytes get() = 64L + subDrawingBytes(before) + subDrawingBytes(after)
        override fun deepCopy() = copy(before = before?.copySubDrawing(), after = after?.copySubDrawing())
        override fun apply(vm: BlueprintViewModel) = replaceSubDrawing(vm.pageSubDrawings[page], id, before, after, ordinal)
        override fun reverse(vm: BlueprintViewModel) = replaceSubDrawing(vm.pageSubDrawings[page], id, after, before, ordinal)
        override fun intent(kind: Kind) = EffectIntent(page, kind, annotationId = id)
    }

    private data class ClearEntry(override val page: Int, val before: PageSnapshot) : Entry() {
        override val kind = Kind.CLEAR
        override val weightBytes get() = 64L + before.weightBytes
        override fun deepCopy() = copy(before = before.deepCopy())
        override fun apply(vm: BlueprintViewModel) = applySnapshot(vm, page, emptySnapshot(vm, page))
        override fun reverse(vm: BlueprintViewModel) = applySnapshot(vm, page, before)
        override fun intent(kind: Kind) = EffectIntent(page, kind)
    }

    /**
     * Clears one sub-drawing's own markups (paths, measurements, notes, shapes,
     * and its photo pins' per-photo markups). The crop, its name, its badge size,
     * its scale, the page's markups, and every other sub-drawing are untouched.
     * One undoable transaction: Undo restores everything that was cleared.
     */
    private data class ClearSubDrawingEntry(
        override val page: Int,
        val id: String,
        val ordinal: Int,
        val before: com.example.myapplication.SubDrawing?,
        val after: com.example.myapplication.SubDrawing?
    ) : Entry() {
        override val kind = Kind.CLEAR
        override val weightBytes get() = 64L + subDrawingBytes(before) + subDrawingBytes(after)
        override fun deepCopy() = copy(before = before?.copySubDrawing(), after = after?.copySubDrawing())
        override fun apply(vm: BlueprintViewModel) = replaceSubDrawing(vm.pageSubDrawings[page], id, before, after, ordinal)
        override fun reverse(vm: BlueprintViewModel) = replaceSubDrawing(vm.pageSubDrawings[page], id, after, before, ordinal)
        override fun intent(kind: Kind) = EffectIntent(page, kind, annotationId = id)
    }

    /** Lifecycle owner for bounded chronological reducer history. */
    internal class HistoryOwner(
        private val maxEntries: Int = AnnotationHistoryLimits.MAX_ENTRIES,
        private val maxBytes: Long = AnnotationHistoryLimits.MAX_BYTES
    ) {
        internal data class Record(val entry: Entry, val sequence: Long, val weightBytes: Long)
        internal data class Checkpoint(
            val undo: Map<Int, List<Record>>,
            val redo: Map<Int, List<Record>>,
            val nextSequenceValue: Long,
            val epoch: Long
        )

        private val undo = mutableMapOf<Int, MutableList<Record>>()
        private val redo = mutableMapOf<Int, MutableList<Record>>()
        private var nextSequenceValue = 0L
        private var recordCount = 0
        private var recordBytes = 0L
        private val epochState = mutableStateOf(0L)
        private val observableRevision = mutableStateOf(0L)
        internal val epoch: Long get() = epochState.value
        internal fun isEpochCurrent(value: Long) = epochState.value == value
        internal fun nextSequence(): Long {
            nextSequenceValue = if (nextSequenceValue == Long.MAX_VALUE) 1L else nextSequenceValue + 1L
            return nextSequenceValue
        }

        internal fun captureCheckpoint() = Checkpoint(
            undo.mapValues { (_, list) -> list.map { it.copy(entry = it.entry.deepCopy()) } },
            redo.mapValues { (_, list) -> list.map { it.copy(entry = it.entry.deepCopy()) } },
            nextSequenceValue,
            epoch
        )

        internal fun restoreCheckpoint(checkpoint: Checkpoint) {
            clearInternal()
            checkpoint.undo.forEach { (page, list) -> undo[page] = list.map { it.copy(entry = it.entry.deepCopy()) }.toMutableList() }
            checkpoint.redo.forEach { (page, list) -> redo[page] = list.map { it.copy(entry = it.entry.deepCopy()) }.toMutableList() }
            nextSequenceValue = checkpoint.nextSequenceValue
            epochState.value = checkpoint.epoch
            recalculate()
            touch()
        }

        internal fun canRecord(entry: Entry) = maxEntries > 0 && entry.weightBytes in 1L..maxBytes
        internal fun record(entry: Entry) {
            clearRecords(redo.remove(entry.page))
            val record = Record(entry.deepCopy(), nextSequence(), entry.weightBytes.coerceAtLeast(1L))
            undo.getOrPut(entry.page) { mutableListOf() }.add(record)
            recordCount++
            recordBytes += record.weightBytes
            trimToBudget()
            touch()
        }

        /**
         * Drops the most recent undo record matching [predicate] for [page].
         * Used when a command is abandoned before the user ever sees it: its
         * history entry must not survive, or Undo would resurrect work the
         * user threw away. Returns false when nothing matches.
         */
        internal fun discardUndoRecord(page: Int, predicate: (Entry) -> Boolean): Boolean {
            val list = undo[page] ?: return false
            val index = lastIndexOf(list, predicate)
            if (index < 0) return false
            val removed = list.removeAt(index)
            recordCount--; recordBytes -= removed.weightBytes
            if (list.isEmpty()) undo.remove(page)
            touch()
            return true
        }

        private fun lastIndexOf(list: MutableList<Record>, predicate: (Entry) -> Boolean): Int {
            var index = list.lastIndex
            while (index >= 0) {
                if (predicate(list[index].entry)) return index
                index--
            }
            return -1
        }

        internal fun takeUndo(page: Int): Record? = take(undo, page)
        internal fun takeRedo(page: Int): Record? = take(redo, page)
        internal fun restoreUndo(page: Int, value: Record) = restore(undo, page, value)
        internal fun restoreRedo(page: Int, value: Record) = restore(redo, page, value)
        internal fun putUndo(page: Int, value: Record) = put(undo, page, value)
        internal fun putRedo(page: Int, value: Record) = put(redo, page, value)
        internal fun canUndo(page: Int): Boolean { observableRevision.value; return !undo[page].isNullOrEmpty() }
        internal fun canRedo(page: Int): Boolean { observableRevision.value; return !redo[page].isNullOrEmpty() }
        internal fun clear() { clearInternal(); touch() }
        internal fun invalidateForReplacement() { epochState.value++; clearInternal(); touch() }
        internal fun resetForSession() { epochState.value++; clearInternal(); touch() }

        internal fun retainedPhotoNames(): Set<String> = buildSet {
            (undo.values.asSequence() + redo.values.asSequence()).flatten().forEach { addAll(photoNames(it.entry)) }
        }

        private fun <M : MutableMap<Int, MutableList<Record>>> take(map: M, page: Int): Record? {
            val list = map[page] ?: return null
            val result = list.removeLastOrNull() ?: return null
            recordCount--; recordBytes -= result.weightBytes
            if (list.isEmpty()) map.remove(page)
            touch(); return result
        }
        private fun restore(map: MutableMap<Int, MutableList<Record>>, page: Int, value: Record) {
            map.getOrPut(page) { mutableListOf() }.add(value)
            recordCount++; recordBytes += value.weightBytes; touch()
        }
        private fun put(map: MutableMap<Int, MutableList<Record>>, page: Int, value: Record) {
            map.getOrPut(page) { mutableListOf() }.add(value.copy(sequence = nextSequence()))
            recordCount++; recordBytes += value.weightBytes; touch()
        }
        private fun clearInternal() { undo.clear(); redo.clear(); recordCount = 0; recordBytes = 0L }
        private fun clearRecords(records: MutableList<Record>?) { if (records != null) { recordCount -= records.size; recordBytes -= records.sumOf { it.weightBytes } } }
        private fun recalculate() { recordCount = (undo.values + redo.values).sumOf { it.size }; recordBytes = (undo.values + redo.values).sumOf { it.sumOf { r -> r.weightBytes } } }
        private fun trimToBudget() {
            while (recordCount > maxEntries || recordBytes > maxBytes) {
                var selectedMap: MutableMap<Int, MutableList<Record>>? = null
                var selectedPage = -1; var selectedIndex = -1; var oldest = Long.MAX_VALUE
                listOf(undo, redo).forEach { map -> map.forEach { (page, values) -> values.forEachIndexed { index, record -> if (record.sequence < oldest) { selectedMap = map; selectedPage = page; selectedIndex = index; oldest = record.sequence } } } }
                val map = selectedMap ?: break
                val values = map[selectedPage] ?: break
                val removed = values.removeAt(selectedIndex); recordCount--; recordBytes -= removed.weightBytes
                if (values.isEmpty()) map.remove(selectedPage)
            }
        }
        private fun touch() { observableRevision.value++ }
        private fun photoNames(entry: Entry): Set<String> = when (entry) {
            is PinEntry -> photoNames(entry.before) + photoNames(entry.after)
            is SubDrawingEntry -> photoNames(entry.before) + photoNames(entry.after)
            is ImageNoteEntry -> setOf(entry.fileName)
            is ShapeEntry -> entry.fileName?.let(::setOf) ?: emptySet()
            is ClearEntry -> entry.before.photoPins.orEmpty().flatMapTo(LinkedHashSet()) { photoNames(it) } +
                entry.before.subDrawings.orEmpty().flatMap { sub -> photoNames(sub) }.toSet()
            else -> emptySet()
        }
        private fun photoNames(pin: PhotoPin?): Set<String> = pin?.let { buildSet { addAll(it.imageFileNames); addAll(it.imageNotes.keys); addAll(it.imageShapes.keys) } } ?: emptySet()
        private fun photoNames(sub: com.example.myapplication.SubDrawing?): Set<String> =
            sub?.let { value -> value.photos.flatMapTo(LinkedHashSet()) { pin -> photoNames(pin) } } ?: emptySet()
    }

    private val historyOwner = vm.annotationHistory
    private val capturedHistoryEpoch = historyOwner.epoch

    fun addPdfPath(page: Int, path: DrawnPath): Result =
        addPageAnnotation(page, vm.pagePaths[page], path.copyPath(), path.id, validatePath(path)) { list, value ->
            PathEntry(page, null, value, list.size, Kind.ADD)
        }

    fun deletePdfPath(page: Int, path: DrawnPath): Result =
        deletePageAnnotation(
            page, vm.pagePaths[page], path, validatePath(path), { it.copyPath() }
        ) { page, before, ordinal -> PathEntry(page, before, null, ordinal, Kind.DELETE) }

    fun updatePdfPath(page: Int, current: DrawnPath, replacement: DrawnPath, kind: Kind = Kind.UPDATE): Result =
        replacePageAnnotation(
            page, vm.pagePaths[page], current, replacement, kind,
            validatePath(current) && validatePath(replacement), { it.copyPath() }
        ) { page, before, after, ordinal, kind -> PathEntry(page, before, after, ordinal, kind) }

    fun addMeasurement(page: Int, measurement: Measurement): Result =
        addPageAnnotation(page, vm.pageMeasurements[page], measurement.copyMeasurement(), measurement.id, validateMeasurement(measurement)) { list, value ->
            MeasurementEntry(page, null, value, list.size, Kind.ADD)
        }

    fun updateMeasurement(page: Int, current: Measurement, replacement: Measurement, kind: Kind = Kind.UPDATE): Result {
        if (!isMutationPage(page) || !validateMeasurement(current)) return Result.Rejected
        val index = vm.pageMeasurements[page]?.indexOfFirst { it.id == current.id } ?: -1
        return updateMeasurementAt(page, index, replacement, kind, current)
    }
    fun updateMeasurementAt(page: Int, index: Int, replacement: Measurement, kind: Kind = Kind.UPDATE, before: Measurement? = null): Result {
        if (!isMutationPage(page) || !validateMeasurement(replacement) || index !in (vm.pageMeasurements[page]?.indices ?: IntRange.EMPTY)) return Result.Rejected
        val list = vm.pageMeasurements[page] ?: return Result.Rejected
        val original = list[index]
        if (!validateMeasurement(original)) return Result.Rejected
        if (before != null && (!validateMeasurement(before) || before.id != original.id || before != original)) return Result.Rejected
        if (original.id != replacement.id) return Result.Rejected
        if (original == replacement) return Result.Unchanged
        return commit(MeasurementEntry(page, original.copyMeasurement(original.p1.copyPoint(), original.p2.copyPoint()), replacement.copyMeasurement(replacement.p1.copyPoint(), replacement.p2.copyPoint()), index, kind))
    }
    fun moveMeasurement(page: Int, current: Measurement, replacement: Measurement): Result = updateMeasurement(page, current, replacement, Kind.MOVE)
    fun resizeMeasurement(page: Int, current: Measurement, replacement: Measurement): Result = updateMeasurement(page, current, replacement, Kind.RESIZE)
    fun deleteMeasurement(page: Int, measurement: Measurement): Result {
        if (!isMutationPage(page) || !validateMeasurement(measurement)) return Result.Rejected
        val list = vm.pageMeasurements[page] ?: return Result.Rejected
        val current = list.firstOrNull { it.id == measurement.id } ?: return Result.Rejected
        if (current != measurement) return Result.Rejected
        return commit(MeasurementEntry(page, current.copyMeasurement(current.p1.copyPoint(), current.p2.copyPoint()), null, list.indexOf(current), Kind.DELETE))
    }

    fun addPdfNote(page: Int, note: Note): Result =
        addPageAnnotation(page, vm.pageNotes[page], note.copyNote(), note.id, validateNote(note)) { list, value ->
            NoteEntry(page, null, value, list.size, Kind.ADD)
        }
    fun updatePdfNote(page: Int, current: Note, replacement: Note, kind: Kind = Kind.UPDATE): Result =
        updatePdfNoteAt(page, vm.pageNotes[page]?.indexOfFirst { it.id == current.id } ?: -1, replacement, kind, current)
    fun updatePdfNoteAt(page: Int, index: Int, replacement: Note, kind: Kind = Kind.UPDATE, before: Note? = null): Result {
        if (!isMutationPage(page) || !validateNote(replacement) || index !in (vm.pageNotes[page]?.indices ?: IntRange.EMPTY)) return Result.Rejected
        val list = vm.pageNotes[page] ?: return Result.Rejected
        val original = list[index]
        if (!validateNote(original)) return Result.Rejected
        if (before != null && (!validateNote(before) || before.id != original.id || before != original)) return Result.Rejected
        if (original.id != replacement.id) return Result.Rejected
        if (original == replacement) return Result.Unchanged
        return commit(NoteEntry(page, original.copyNote(), replacement.copyNote(), index, kind))
    }
    fun deletePdfNote(page: Int, note: Note): Result = deletePdfNoteAt(page, vm.pageNotes[page]?.indexOfFirst { it.id == note.id } ?: -1, note)
    fun deletePdfNoteAt(page: Int, index: Int, note: Note? = null): Result {
        if (!isMutationPage(page) || index !in (vm.pageNotes[page]?.indices ?: IntRange.EMPTY)) return Result.Rejected
        val list = vm.pageNotes[page] ?: return Result.Rejected
        val current = list[index]
        if (!validateNote(current) || (note != null && (!validateNote(note) || note.id != current.id || note != current))) return Result.Rejected
        return commit(NoteEntry(page, current.copyNote(), null, index, Kind.DELETE))
    }

    fun addPhotoPin(page: Int, pin: PhotoPin): Result {
        if (!validatePin(pin)) return Result.Rejected
        val list = vm.pagePhotoPins[page] ?: return Result.Rejected
        // Pins carry their own count and photo-reference limits on top of the
        // shared page checks the add owner applies.
        if (list.size >= Stage5Limits.MAX_PHOTO_PINS_PER_PAGE ||
            pagePhotoReferenceCount(vm) + subDrawingPhotoReferenceCount(vm) >
            Stage5Limits.MAX_TOTAL_PHOTOS.toLong() - pin.imageFileNames.size
        ) return Result.Rejected
        return addPageAnnotation(page, list, pin.copyPin(), pin.id, capacity = pinAnnotationCount(pin)) { entries, value ->
            PinEntry(page, null, value, pin.id, entries.size, Kind.ADD)
        }
    }
    fun updatePhotoPin(page: Int, pin: PhotoPin, replacement: PhotoPin, kind: Kind = Kind.UPDATE): Result {
        if (!isMutationPage(page) || !validatePin(pin) || !validatePin(replacement) || pin.id != replacement.id) return Result.Rejected
        val list = vm.pagePhotoPins[page] ?: return Result.Rejected
        val current = list.firstOrNull { it.id == pin.id } ?: return Result.Rejected
        if (current != pin) return Result.Rejected
        val projectedPhotos = pagePhotoReferenceCount(vm) + subDrawingPhotoReferenceCount(vm) -
            pin.imageFileNames.size + replacement.imageFileNames.size
        if (projectedPhotos > Stage5Limits.MAX_TOTAL_PHOTOS.toLong()) return Result.Rejected
        val projectedAnnotations = pageAnnotationCount(vm, page) - pinAnnotationCount(pin) + pinAnnotationCount(replacement)
        if (projectedAnnotations > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE.toLong()) return Result.Rejected
        if (current == replacement) return Result.Unchanged
        return commit(PinEntry(page, current.copyPin(), replacement.copyPin(), pin.id, list.indexOf(current), kind))
    }
    /** The owning surface of a photo pin: its sub-drawing id, or null for the page. */
    fun photoPinOwnerSubDrawingId(page: Int, pinId: String): String? =
        vm.pageSubDrawings[page]?.firstOrNull { sub -> sub.photos.any { it.id == pinId } }?.id

    /** Every photo pin on one page, page-level and sub-drawing-owned alike. */
    fun allPhotoPins(page: Int): List<PhotoPin> =
        vm.pagePhotoPins[page].orEmpty().toList() +
            vm.pageSubDrawings[page].orEmpty().flatMap { it.photos }

    /**
     * The stored pin by id, page-level first, then the pins owned by this page's
     * sub-drawings. Every per-photo command (clear, caption, appearance) reads its
     * "current" pin through here, so a pin placed inside a sub-drawing behaves
     * exactly like a page-level pin.
     */
    private fun storedPin(vm: BlueprintViewModel, page: Int, id: String): PhotoPin? =
        vm.pagePhotoPins[page]?.firstOrNull { it.id == id }
            ?: vm.pageSubDrawings[page]?.firstOrNull { sub -> sub.photos.any { it.id == id } }
                ?.photos?.firstOrNull { it.id == id }

    /**
     * Routes one per-photo markup replacement to the pin's owning surface: a
     * sub-drawing-owned pin updates through [updateSubDrawingPhoto], a page-level
     * pin through [updatePhotoPin]. Callers pass the stored pin they read from
     * [storedPin], so "current matches stored" always holds.
     */
    private fun commitPinReplacement(page: Int, current: PhotoPin, replacement: PhotoPin, kind: Kind): Result {
        val subId = photoPinOwnerSubDrawingId(page, current.id) ?: return updatePhotoPin(page, current, replacement, kind)
        return updateSubDrawingPhoto(page, subId, current, replacement, kind)
    }

    // ---- Target-based markup commands -------------------------------------
    // One entry point per action for every surface. Sheet and sub-drawing
    // targets reuse the existing per-surface commands; photo targets edit the
    // stored pin's per-photo lists and commit through the pin's owning surface.

    fun add(target: MarkupTarget, item: PageItem): Result = when (target) {
        is MarkupTarget.Sheet -> when (item) {
            is PageItem.Path -> addPdfPath(target.page, item.data)
            is PageItem.Measure -> addMeasurement(target.page, item.data)
            is PageItem.NoteItem -> addPdfNote(target.page, item.data)
            is PageItem.ShapeItem -> addPdfShape(target.page, item.data)
            is PageItem.PhotoPinItem -> addPhotoPin(target.page, item.data)
            is PageItem.SubDrawingItem -> addSubDrawing(target.page, item.data)
        }
        is MarkupTarget.SubDrawing -> when (item) {
            is PageItem.Path -> addSubDrawingPath(target.page, target.subId, item.data)
            is PageItem.Measure -> addSubDrawingMeasurement(target.page, target.subId, item.data)
            is PageItem.NoteItem -> addSubDrawingNote(target.page, target.subId, item.data)
            is PageItem.ShapeItem -> addSubDrawingShape(target.page, target.subId, item.data)
            is PageItem.PhotoPinItem -> addSubDrawingPhoto(target.page, target.subId, item.data)
            is PageItem.SubDrawingItem -> Result.Rejected
        }
        is MarkupTarget.Photo -> {
            val id = photoItemId(item)
            if (id == null || !validPhotoItem(item)) Result.Rejected
            else editPhotoMarkup(target, Kind.ADD) { pin ->
                val f = target.fileName
                val taken = pin.imagePaths[f].orEmpty().any { it.id == id } || pin.imageMeasurements[f].orEmpty().any { it.id == id } ||
                    pin.imageNotes[f].orEmpty().any { it.id == id } || pin.imageShapes[f].orEmpty().any { it.id == id }
                val count = pin.imagePaths[f].orEmpty().size + pin.imageMeasurements[f].orEmpty().size +
                    pin.imageNotes[f].orEmpty().size + pin.imageShapes[f].orEmpty().size
                if (taken || count >= Stage5Limits.MAX_ANNOTATIONS_PER_PAGE) null
                else editPhotoList(pin, f, item) { it + photoItemCopy(item) }
            }
        }
    }

    fun update(target: MarkupTarget, current: PageItem, replacement: PageItem, kind: Kind = Kind.UPDATE): Result {
        if (current::class != replacement::class) return Result.Rejected
        return when (target) {
            is MarkupTarget.Sheet -> when (current) {
                is PageItem.Path -> updatePdfPath(target.page, current.data, (replacement as PageItem.Path).data, kind)
                is PageItem.Measure -> updateMeasurement(target.page, current.data, (replacement as PageItem.Measure).data, kind)
                is PageItem.NoteItem -> updatePdfNote(target.page, current.data, (replacement as PageItem.NoteItem).data, kind)
                is PageItem.ShapeItem -> updatePdfShape(target.page, current.data, (replacement as PageItem.ShapeItem).data, kind)
                is PageItem.PhotoPinItem -> updatePhotoPin(target.page, current.data, (replacement as PageItem.PhotoPinItem).data, kind)
                is PageItem.SubDrawingItem -> updateSubDrawing(target.page, current.data, (replacement as PageItem.SubDrawingItem).data, kind)
            }
            is MarkupTarget.SubDrawing -> when (current) {
                is PageItem.Path -> updateSubDrawingPath(target.page, target.subId, current.data, (replacement as PageItem.Path).data, kind)
                is PageItem.Measure -> updateSubDrawingMeasurement(target.page, target.subId, current.data, (replacement as PageItem.Measure).data, kind)
                is PageItem.NoteItem -> updateSubDrawingNote(target.page, target.subId, current.data, (replacement as PageItem.NoteItem).data, kind)
                is PageItem.ShapeItem -> updateSubDrawingShape(target.page, target.subId, current.data, (replacement as PageItem.ShapeItem).data, kind)
                is PageItem.PhotoPinItem -> updateSubDrawingPhoto(target.page, target.subId, current.data, (replacement as PageItem.PhotoPinItem).data, kind)
                is PageItem.SubDrawingItem -> Result.Rejected
            }
            is MarkupTarget.Photo -> {
                val id = photoItemId(current)
                if (id == null || id != photoItemId(replacement) || !validPhotoItem(current) || !validPhotoItem(replacement)) Result.Rejected
                else editPhotoMarkup(target, kind) { pin ->
                    editPhotoList(pin, target.fileName, current) { list ->
                        val index = list.indexOfFirst { photoItemId(it) == id }
                        if (index < 0 || list[index] != photoItemData(current)) null
                        else list.toMutableList().also { it[index] = photoItemCopy(replacement) }
                    }
                }
            }
        }
    }

    fun delete(target: MarkupTarget, item: PageItem): Result = when (target) {
        is MarkupTarget.Sheet -> when (item) {
            is PageItem.NoteItem -> if (item.ordinal >= 0) deletePdfNoteAt(target.page, item.ordinal, item.data) else deletePdfNote(target.page, item.data)
            is PageItem.ShapeItem -> deletePdfShape(target.page, item.data)
            is PageItem.Path -> deletePdfPath(target.page, item.data)
            is PageItem.Measure -> deleteMeasurement(target.page, item.data)
            is PageItem.PhotoPinItem -> deletePhotoPin(target.page, item.data)
            is PageItem.SubDrawingItem -> deleteSubDrawing(target.page, item.data)
        }
        is MarkupTarget.SubDrawing -> when (item) {
            is PageItem.NoteItem -> deleteSubDrawingNote(target.page, target.subId, item.data)
            is PageItem.ShapeItem -> deleteSubDrawingShape(target.page, target.subId, item.data)
            is PageItem.Path -> deleteSubDrawingPath(target.page, target.subId, item.data)
            is PageItem.Measure -> deleteSubDrawingMeasurement(target.page, target.subId, item.data)
            is PageItem.PhotoPinItem -> deleteSubDrawingPhoto(target.page, target.subId, item.data)
            is PageItem.SubDrawingItem -> Result.Rejected
        }
        is MarkupTarget.Photo -> {
            val id = photoItemId(item)
            if (id == null || !validPhotoItem(item)) Result.Rejected
            else editPhotoMarkup(target, Kind.DELETE) { pin ->
                editPhotoList(pin, target.fileName, item) { list ->
                    val index = list.indexOfFirst { photoItemId(it) == id }
                    if (index < 0 || list[index] != photoItemData(item)) null
                    else list.toMutableList().also { it.removeAt(index) }
                }
            }
        }
    }

    /** The stored pin behind a photo target, page-level or sub-drawing-owned. */
    fun pinFor(target: MarkupTarget.Photo): PhotoPin? = storedPin(vm, target.page, target.pinId)

    private fun editPhotoMarkup(target: MarkupTarget.Photo, kind: Kind, transform: (PhotoPin) -> PhotoPin?): Result {
        if (!isMutationPage(target.page) || !validId(target.pinId) || !validFileName(target.fileName)) return Result.Rejected
        val pin = storedPin(vm, target.page, target.pinId) ?: return Result.Rejected
        if (target.fileName !in pin.imageFileNames) return Result.Rejected
        val replacement = transform(pin)?.copyPin() ?: return Result.Rejected
        if (!validatePin(replacement)) return Result.Rejected
        if (replacement == pin) return Result.Unchanged
        return commitPinReplacement(target.page, pin, replacement, kind)
    }

    private fun validPhotoItem(item: PageItem): Boolean = when (item) {
        is PageItem.Path -> validatePath(item.data)
        is PageItem.Measure -> validateMeasurement(item.data)
        is PageItem.NoteItem -> validateImageNote(item.data)
        is PageItem.ShapeItem -> validateShape(item.data)
        else -> false
    }

    private fun photoItemId(item: Any): String? = when (item) {
        is PageItem.Path -> item.data.id
        is PageItem.Measure -> item.data.id
        is PageItem.NoteItem -> item.data.id
        is PageItem.ShapeItem -> item.data.id
        is DrawnPath -> item.id
        is Measurement -> item.id
        is Note -> item.id
        is Shape -> item.id
        else -> null
    }

    private fun photoItemData(item: PageItem): Any? = when (item) {
        is PageItem.Path -> item.data
        is PageItem.Measure -> item.data
        is PageItem.NoteItem -> item.data
        is PageItem.ShapeItem -> item.data
        else -> null
    }

    private fun photoItemCopy(item: PageItem): Any = when (item) {
        is PageItem.Path -> item.data.copyPath()
        is PageItem.Measure -> item.data.copyMeasurement(item.data.p1.copyPoint(), item.data.p2.copyPoint())
        is PageItem.NoteItem -> item.data.copyImageNote()
        is PageItem.ShapeItem -> item.data.copyShape()
        else -> error("not a photo markup")
    }

    /** Applies [edit] to the per-photo list that holds [kindOf]'s type; an empty result drops the file's entry. */
    @Suppress("UNCHECKED_CAST")
    private fun editPhotoList(pin: PhotoPin, file: String, kindOf: PageItem, edit: (List<Any>) -> List<Any>?): PhotoPin? {
        fun <T> next(map: Map<String, List<T>>): Map<String, List<T>>? {
            val result = edit(map[file].orEmpty() as List<Any>) ?: return null
            return if (result.isEmpty()) map - file else map + (file to result as List<T>)
        }
        return when (kindOf) {
            is PageItem.Path -> next(pin.imagePaths)?.let { pin.copy(imagePaths = it) }
            is PageItem.Measure -> next(pin.imageMeasurements)?.let { pin.copy(imageMeasurements = it) }
            is PageItem.NoteItem -> next(pin.imageNotes)?.let { pin.copy(imageNotes = it) }
            is PageItem.ShapeItem -> next(pin.imageShapes)?.let { pin.copy(imageShapes = it) }
            else -> null
        }
    }

    fun canAttachPhoto(page: Int, pinId: String, fileName: String? = null): Boolean {
        if (!isSessionActive() || !isInitializedPage(page) || !validId(pinId)) return false
        val pin = vm.pagePhotoPins[page]?.firstOrNull { it.id == pinId } ?: return false
        if (fileName != null && (!validFileName(fileName) || fileName in pin.imageFileNames)) return false
        if (pin.imageFileNames.size >= Stage5Limits.MAX_PHOTOS_PER_PIN) return false
        val total = pagePhotoReferenceCount(vm) + subDrawingPhotoReferenceCount(vm)
        return total < Stage5Limits.MAX_TOTAL_PHOTOS
    }
    fun attachPhoto(page: Int, pin: PhotoPin, fileName: String): Result {
        if (!isMutationPage(page) || !validatePin(pin) || !canAttachPhoto(page, pin.id, fileName)) return Result.Rejected
        val replacement = pin.copy(imageFileNames = pin.imageFileNames + fileName).copyPin()
        return updatePhotoPin(page, pin, replacement)
    }
    fun detachPhoto(page: Int, pin: PhotoPin, fileName: String): Result {
        if (!isMutationPage(page) || !validatePin(pin) || !validFileName(fileName)) return Result.Rejected
        if (fileName !in pin.imageFileNames) return Result.Rejected
        val replacement = pin.copy(
            imageFileNames = pin.imageFileNames.filterNot { it == fileName },
            imageNotes = pin.imageNotes - fileName,
            imageShapes = pin.imageShapes - fileName,
            imagePaths = pin.imagePaths - fileName,
            imageMeasurements = pin.imageMeasurements - fileName,
            imageScales = pin.imageScales - fileName
        ).copyPin()
        return updatePhotoPin(page, pin, replacement)
    }
    fun deletePhotoPin(page: Int, pin: PhotoPin): Result {
        if (!isMutationPage(page) || !validatePin(pin)) return Result.Rejected
        val list = vm.pagePhotoPins[page] ?: return Result.Rejected
        val current = list.firstOrNull { it.id == pin.id } ?: return Result.Rejected
        if (current != pin) return Result.Rejected
        return commit(PinEntry(page, current.copyPin(), null, pin.id, list.indexOf(current), Kind.DELETE))
    }

    fun setScale(page: Int, scale: PageScale?, sourceSize: AnnotationSize? = null): Result {
        if (!isMutationPage(page) || (scale != null && !isValidPageScale(scale.pointsPerFoot))) return Result.Rejected
        val old = vm.pageScales[page]?.copy()
        if (old != null && !isValidPageScale(old.pointsPerFoot)) return Result.Rejected
        val before = vm.pageMeasurements[page].orEmpty().map { it.copyMeasurement() }
        if (old == scale && sourceSize == null) return Result.Unchanged
        val after = recalibratedMeasurements(before, scale, sourceSize) ?: return Result.Rejected
        if (after.any { !validateMeasurement(it) }) return Result.Rejected
        if (old == scale && before == after) return Result.Unchanged
        if (old == null && scale != null && !hasPageAnnotationCapacity(vm, page)) return Result.Rejected
        return commit(ScaleEntry(page, old, scale?.copy(), before, after))
    }

    fun addImagePath(page: Int, pin: PhotoPin, file: String, path: DrawnPath): Result {
        if (file !in pin.imageFileNames || !validatePath(path)) return Result.Rejected
        return updatePhotoPin(page, pin, pin.copy(imagePaths = pin.imagePaths +
            (file to (pin.imagePaths[file].orEmpty() + path.copyPath()))), Kind.ADD)
    }

    fun addImageMeasurement(page: Int, pin: PhotoPin, file: String, measurement: Measurement): Result {
        if (file !in pin.imageFileNames || !validateMeasurement(measurement)) return Result.Rejected
        return updatePhotoPin(page, pin, pin.copy(imageMeasurements = pin.imageMeasurements +
            (file to (pin.imageMeasurements[file].orEmpty() + measurement.copyMeasurement()))), Kind.ADD)
    }

    fun setImageScale(page: Int, pin: PhotoPin, file: String, scale: PageScale, sourceAspect: Float? = null): Result {
        if (!isMutationPage(page) || !validatePin(pin) || storedPin(vm, page, pin.id) != pin ||
            file !in pin.imageFileNames || !isValidPageScale(scale.pointsPerFoot)) return Result.Rejected
        if (sourceAspect != null && (!sourceAspect.isFinite() || sourceAspect <= 0f)) return Result.Rejected
        val values = pin.imageMeasurements[file].orEmpty()
        if (pin.imageScales[file] == scale && sourceAspect == null) return Result.Unchanged
        val updated = recalibratedMeasurements(values, scale, sourceAspect?.let { AnnotationSize(1f, it) }) ?: return Result.Rejected
        val measurements = if (file in pin.imageMeasurements) pin.imageMeasurements + (file to updated) else pin.imageMeasurements
        return commitPinReplacement(page, pin, pin.copy(imageScales = pin.imageScales + (file to scale.copy()), imageMeasurements = measurements), Kind.UPDATE)
    }

    /**
     * Clears every markup drawn on one photo of a pin. Works for page-level pins
     * and for pins whose owner is a sub-drawing: the stored pin is read from its
     * owning surface and the replacement commits back through that surface.
     */
    fun clearImageAnnotations(page: Int, pin: PhotoPin, file: String): Result {
        val current = storedPin(vm, page, pin.id) ?: return Result.Rejected
        if (current != pin || file !in pin.imageFileNames) return Result.Rejected
        return commitPinReplacement(page, pin, pin.copy(imagePaths = pin.imagePaths - file,
            imageMeasurements = pin.imageMeasurements - file, imageScales = pin.imageScales - file,
            imageNotes = pin.imageNotes - file, imageShapes = pin.imageShapes - file), Kind.CLEAR)
    }

    fun addPdfShape(page: Int, shape: Shape): Result =
        addPageAnnotation(page, vm.pageShapes[page], shape.copyShape(), shape.id, validateShape(shape)) { list, value ->
            ShapeEntry(page, null, null, null, value, shape.id, list.size, Kind.ADD)
        }
    fun updatePdfShape(page: Int, shape: Shape, replacement: Shape, kind: Kind = Kind.UPDATE): Result = updateShape(page, null, null, shape, replacement, kind)
    fun movePdfShape(page: Int, shape: Shape, replacement: Shape): Result = updatePdfShape(page, shape, replacement, Kind.MOVE)
    fun resizePdfShape(page: Int, shape: Shape, replacement: Shape): Result = updatePdfShape(page, shape, replacement, Kind.RESIZE)
    fun rotatePdfShape(page: Int, shape: Shape, replacement: Shape): Result = updatePdfShape(page, shape, replacement, Kind.ROTATE)
    fun deletePdfShape(page: Int, shape: Shape): Result {
        if (!isMutationPage(page) || !validateShape(shape)) return Result.Rejected
        val list = vm.pageShapes[page] ?: return Result.Rejected
        val current = list.firstOrNull { it.id == shape.id } ?: return Result.Rejected
        if (current != shape) return Result.Rejected
        return commit(ShapeEntry(page, null, null, current.copyShape(), null, shape.id, list.indexOf(current), Kind.DELETE))
    }

    fun addImageNote(page: Int, pinId: String, fileName: String, note: PhotoImageNote): Result {
        if (!isMutationPage(page) || !validId(pinId) || !validateImageNote(note) || !validFileName(fileName)) return Result.Rejected
        val pin = findPin(vm, page, pinId) ?: return Result.Rejected
        if (fileName !in pin.imageFileNames) return Result.Rejected
        val list = pin.imageNotes[fileName] ?: emptyList()
        if (list.size >= Stage5Limits.MAX_ANNOTATIONS_PER_PAGE || list.any { it.id == note.id } ||
            pin.imageShapes[fileName].orEmpty().any { it.id == note.id } || pin.imagePaths[fileName].orEmpty().any { it.id == note.id } || pin.imageMeasurements[fileName].orEmpty().any { it.id == note.id } || !hasPageAnnotationCapacity(vm, page)
        ) return Result.Rejected
        return commit(ImageNoteEntry(page, pinId, fileName, null, note.copyImageNote(), note.id, list.size, Kind.ADD))
    }
    fun updateImageNote(page: Int, pinId: String, fileName: String, note: PhotoImageNote, replacement: PhotoImageNote, kind: Kind = Kind.UPDATE): Result {
        if (!isMutationPage(page) || !validId(pinId) || !validFileName(fileName) || !validateImageNote(note) || !validateImageNote(replacement) || note.id != replacement.id) return Result.Rejected
        val pin = findPin(vm, page, pinId) ?: return Result.Rejected
        if (fileName !in pin.imageFileNames) return Result.Rejected
        val list = pin.imageNotes[fileName] ?: return Result.Rejected
        val current = list.firstOrNull { it.id == note.id } ?: return Result.Rejected
        if (current != note) return Result.Rejected
        if (current == replacement) return Result.Unchanged
        return commit(ImageNoteEntry(page, pinId, fileName, current.copyImageNote(), replacement.copyImageNote(), note.id, list.indexOf(current), kind))
    }
    fun moveImageNote(page: Int, pinId: String, fileName: String, note: PhotoImageNote, replacement: PhotoImageNote): Result = updateImageNote(page, pinId, fileName, note, replacement, Kind.MOVE)
    fun resizeImageNote(page: Int, pinId: String, fileName: String, note: PhotoImageNote, replacement: PhotoImageNote): Result = updateImageNote(page, pinId, fileName, note, replacement, Kind.RESIZE)
    fun rotateImageNote(page: Int, pinId: String, fileName: String, note: PhotoImageNote, replacement: PhotoImageNote): Result = updateImageNote(page, pinId, fileName, note, replacement, Kind.ROTATE)
    fun deleteImageNote(page: Int, pinId: String, fileName: String, note: PhotoImageNote): Result {
        if (!isMutationPage(page) || !validId(pinId) || !validFileName(fileName) || !validateImageNote(note)) return Result.Rejected
        val pin = findPin(vm, page, pinId) ?: return Result.Rejected
        if (fileName !in pin.imageFileNames) return Result.Rejected
        val list = pin.imageNotes[fileName] ?: return Result.Rejected
        val current = list.firstOrNull { it.id == note.id } ?: return Result.Rejected
        if (current != note) return Result.Rejected
        return commit(ImageNoteEntry(page, pinId, fileName, current.copyImageNote(), null, note.id, list.indexOf(current), Kind.DELETE))
    }

    fun addImageShape(page: Int, pinId: String, fileName: String, shape: Shape): Result {
        if (!isMutationPage(page) || !validId(pinId) || !validFileName(fileName) || !validateShape(shape)) return Result.Rejected
        val pin = findPin(vm, page, pinId) ?: return Result.Rejected
        if (fileName !in pin.imageFileNames) return Result.Rejected
        val list = pin.imageShapes[fileName] ?: emptyList()
        if (list.size >= Stage5Limits.MAX_ANNOTATIONS_PER_PAGE || list.any { it.id == shape.id } ||
            pin.imageNotes[fileName].orEmpty().any { it.id == shape.id } || pin.imagePaths[fileName].orEmpty().any { it.id == shape.id } || pin.imageMeasurements[fileName].orEmpty().any { it.id == shape.id } || !hasPageAnnotationCapacity(vm, page)
        ) return Result.Rejected
        return commit(ShapeEntry(page, pinId, fileName, null, shape.copyShape(), shape.id, list.size, Kind.ADD))
    }
    fun updateImageShape(page: Int, pinId: String, fileName: String, shape: Shape, replacement: Shape, kind: Kind = Kind.UPDATE): Result = updateShape(page, pinId, fileName, shape, replacement, kind)
    fun moveImageShape(page: Int, pinId: String, fileName: String, shape: Shape, replacement: Shape): Result = updateImageShape(page, pinId, fileName, shape, replacement, Kind.MOVE)
    fun resizeImageShape(page: Int, pinId: String, fileName: String, shape: Shape, replacement: Shape): Result = updateImageShape(page, pinId, fileName, shape, replacement, Kind.RESIZE)
    fun rotateImageShape(page: Int, pinId: String, fileName: String, shape: Shape, replacement: Shape): Result = updateImageShape(page, pinId, fileName, shape, replacement, Kind.ROTATE)
    fun deleteImageShape(page: Int, pinId: String, fileName: String, shape: Shape): Result {
        if (!isMutationPage(page) || !validId(pinId) || !validFileName(fileName) || !validateShape(shape)) return Result.Rejected
        val pin = findPin(vm, page, pinId) ?: return Result.Rejected
        if (fileName !in pin.imageFileNames) return Result.Rejected
        val list = pin.imageShapes[fileName] ?: return Result.Rejected
        val current = list.firstOrNull { it.id == shape.id } ?: return Result.Rejected
        if (current != shape) return Result.Rejected
        return commit(ShapeEntry(page, pinId, fileName, current.copyShape(), null, shape.id, list.indexOf(current), Kind.DELETE))
    }

    /**
     * Sub-drawing commands. Every one validates the complete candidate and the
     * expected-before identity, then records one bounded history entry, exactly
     * like the photo-pin family. Nested markup edits commit a full before/after
     * pair for the owning sub-drawing so undo/redo covers them too.
     */
    fun addSubDrawing(page: Int, sub: com.example.myapplication.SubDrawing): Result {
        val list = vm.pageSubDrawings[page] ?: return Result.Rejected
        if (list.size >= Stage5Limits.MAX_PHOTO_PINS_PER_PAGE) return Result.Rejected
        // A new crop takes the first colour no other crop on this page already uses.
        val colorIndex = com.example.myapplication.SubDrawingPalette.assignIndex(list.toList())
        return addPageAnnotation(page, list, sub.copySubDrawing().copy(colorIndex = colorIndex), sub.id, validateSubDrawing(sub)) { entries, value ->
            SubDrawingEntry(page, null, value, sub.id, entries.size, Kind.ADD)
        }
    }

    fun updateSubDrawing(
        page: Int,
        sub: com.example.myapplication.SubDrawing,
        replacement: com.example.myapplication.SubDrawing,
        kind: Kind = Kind.UPDATE
    ): Result {
        if (!isMutationPage(page) || !validateSubDrawing(sub) || !validateSubDrawing(replacement) || sub.id != replacement.id) return Result.Rejected
        val list = vm.pageSubDrawings[page] ?: return Result.Rejected
        val current = list.firstOrNull { it.id == sub.id } ?: return Result.Rejected
        if (current != sub) return Result.Rejected
        val projectedAnnotations = pageAnnotationCount(vm, page) - subDrawingAnnotationCount(sub) + subDrawingAnnotationCount(replacement)
        if (projectedAnnotations > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE.toLong()) return Result.Rejected
        if (current == replacement) return Result.Unchanged
        return commit(SubDrawingEntry(page, current.copySubDrawing(), replacement.copySubDrawing(), sub.id, list.indexOf(current), kind))
    }

    fun deleteSubDrawing(page: Int, sub: com.example.myapplication.SubDrawing): Result {
        if (!isMutationPage(page) || !validateSubDrawing(sub)) return Result.Rejected
        val list = vm.pageSubDrawings[page] ?: return Result.Rejected
        val current = list.firstOrNull { it.id == sub.id } ?: return Result.Rejected
        if (current != sub) return Result.Rejected
        return commit(SubDrawingEntry(page, current.copySubDrawing(), null, sub.id, list.indexOf(current), Kind.DELETE))
    }

    /**
     * Throws away a sub-drawing the user just created and never accepted - the
     * naming popup's Cancel. The crop is removed and its own creation record is
     * dropped from history, so Undo cannot resurrect a discarded sub-drawing and
     * no delete step appears in the page's history. Only an untouched creation
     * qualifies: once the crop has been named, marked up, moved through Undo, or
     * cleared by a page clear, this command rejects and [deleteSubDrawing] is the
     * ordinary undoable way to remove it.
     */
    fun abandonSubDrawing(page: Int, sub: com.example.myapplication.SubDrawing): Result {
        if (!isMutationPage(page) || !validateSubDrawing(sub)) return Result.Rejected
        val list = vm.pageSubDrawings[page] ?: return Result.Rejected
        val current = list.firstOrNull { it.id == sub.id } ?: return Result.Rejected
        // Only a crop that was never named and carries no nested markup is an
        // abandoned creation; anything else is durable data.
        if (current.name != null) return Result.Rejected
        if (current.paths.isNotEmpty() || current.measurements.isNotEmpty() ||
            current.notes.isNotEmpty() || current.shapes.isNotEmpty() || current.photos.isNotEmpty()
        ) return Result.Rejected
        val index = list.indexOf(current)
        val entry = SubDrawingEntry(page, current.copySubDrawing(), null, sub.id, index, Kind.DELETE)
        if (!entry.apply(vm)) {
            entry.reverse(vm)
            return Result.Rejected
        }
        // The creation record goes first: without it Undo has nothing to resurrect.
        historyOwner.discardUndoRecord(page) { record ->
            record is SubDrawingEntry && record.kind == Kind.ADD && record.id == sub.id && record.before == null
        }
        effectSink(entry.intent(Kind.DELETE))
        return Result.Accepted
    }

    fun setSubDrawingScale(page: Int, sub: com.example.myapplication.SubDrawing, scale: PageScale?): Result {
        if (!isMutationPage(page) || !validateSubDrawing(sub)) return Result.Rejected
        if (scale != null && !isValidPageScale(scale.pointsPerFoot)) return Result.Rejected
        val current = vm.pageSubDrawings[page]?.firstOrNull { it.id == sub.id } ?: return Result.Rejected
        if (current != sub) return Result.Rejected
        if (current.scale == scale) return Result.Unchanged
        // Calibrating a sub-drawing re-derives its stored measurement labels.
        // Its surface is the crop's own source points, and the stored scale is
        // page-space points per foot, so the same recalculation the page uses
        // keeps every label equal to what the same length measures on the page.
        val aspect = com.example.myapplication.SubDrawingGeometry.subSurfaceAspect(current) ?: return Result.Rejected
        val updatedMeasurements = recalibratedMeasurements(
            current.measurements.map { it.copyMeasurement() },
            scale?.copy(),
            AnnotationSize(1f, aspect)
        ) ?: return Result.Rejected
        if (updatedMeasurements.any { !validateMeasurement(it) }) return Result.Rejected
        return updateSubDrawing(
            page, current,
            current.copy(scale = scale?.copy(), measurements = updatedMeasurements),
            Kind.UPDATE
        )
    }

    /**
     * Renames one sub-drawing (its room name, "104" / "Kitchen"). Null clears the
     * name. One undoable transaction like every other sub-drawing command, so
     * Ctrl-Z restores the previous name and redo re-applies the new one.
     */
    fun renameSubDrawing(page: Int, subId: String, name: String?): Result =
        updateSubDrawingNested(page, subId, Kind.UPDATE) { sub ->
            val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
            if (!validSubDrawingName(trimmed)) null else sub.copy(name = trimmed)
        }

    /**
     * Resizes one sub-drawing's page badge (its radius as a fraction of the page's
     * width). One undoable transaction like every other sub-drawing command, so
     * Undo restores the previous size and redo re-applies the new one. Only the
     * named crop's badge changes; the crop's rectangle and every other crop stay
     * exactly as they were.
     */
    fun resizeSubDrawingBadge(page: Int, subId: String, badgeRatio: Float): Result =
        resizeSubDrawingBadge(page, subId, badgeRatio, Kind.RESIZE)

    fun resizeSubDrawingBadge(page: Int, subId: String, badgeRatio: Float, kind: Kind): Result =
        updateSubDrawingNested(page, subId, kind) { sub ->
            if (!validBadgeRatio(badgeRatio)) null
            else sub.copy(badgeRatio = com.example.myapplication.SubDrawingGeometry.badgeRatioOrDefault(badgeRatio))
        }

        /**
     * Moves one crop's page badge to [center] (normalized page coordinates) as one
     * undoable transaction. The crop rectangle never moves with it.
     */
    fun moveSubDrawingBadge(page: Int, subId: String, center: Point, kind: Kind = Kind.MOVE): Result =
        updateSubDrawingNested(page, subId, kind) { sub ->
            if (!com.example.myapplication.SubDrawingGeometry.isValidBadgePosition(center.x, center.y)) null
            else sub.copy(badgeX = center.x, badgeY = center.y)
        }

    private fun subDrawingOf(page: Int, id: String): com.example.myapplication.SubDrawing? =
        vm.pageSubDrawings[page]?.firstOrNull { it.id == id }

    private fun updateSubDrawingNested(
        page: Int,
        subId: String,
        kind: Kind,
        capacityCheck: (com.example.myapplication.SubDrawing) -> Boolean = { true },
        transform: (com.example.myapplication.SubDrawing) -> com.example.myapplication.SubDrawing?
    ): Result {
        if (!isMutationPage(page) || !validAnnotationId(subId)) return Result.Rejected
        val current = subDrawingOf(page, subId) ?: return Result.Rejected
        if (!validateSubDrawing(current)) return Result.Rejected
        val replacement = transform(current)?.copySubDrawing() ?: return Result.Rejected
        if (!validateSubDrawing(replacement)) return Result.Rejected
        if (replacement.id != current.id) return Result.Rejected
        if (current == replacement) return Result.Unchanged
        val projectedAnnotations = pageAnnotationCount(vm, page) - subDrawingAnnotationCount(current) + subDrawingAnnotationCount(replacement)
        if (projectedAnnotations > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE.toLong() || !capacityCheck(replacement)) return Result.Rejected
        val list = vm.pageSubDrawings[page] ?: return Result.Rejected
        return commit(SubDrawingEntry(page, current.copySubDrawing(), replacement, current.id, list.indexOf(current), kind))
    }

    fun addSubDrawingPath(page: Int, subId: String, path: DrawnPath): Result {
        if (!validatePath(path)) return Result.Rejected
        return updateSubDrawingNested(page, subId, Kind.ADD) { sub ->
            if (sub.paths.any { it.id == path.id }) null
            else sub.copy(paths = sub.paths + path.copyPath())
        }
    }

    fun deleteSubDrawingPath(page: Int, subId: String, path: DrawnPath): Result =
        updateSubDrawingNested(page, subId, Kind.DELETE) { sub ->
            if (sub.paths.none { it.id == path.id }) null else sub.copy(paths = sub.paths.filterNot { it.id == path.id })
        }

    fun updateSubDrawingPath(page: Int, subId: String, current: DrawnPath, replacement: DrawnPath, kind: Kind = Kind.UPDATE): Result {
        if (!validatePath(current) || !validatePath(replacement) || current.id != replacement.id) return Result.Rejected
        return updateSubDrawingNested(page, subId, kind) { sub ->
            val index = sub.paths.indexOfFirst { it.id == current.id }
            if (index < 0 || sub.paths[index] != current) null
            else sub.copy(paths = sub.paths.toMutableList().also { it[index] = replacement.copyPath() })
        }
    }

    fun addSubDrawingMeasurement(page: Int, subId: String, measurement: Measurement): Result {
        if (!validateMeasurement(measurement)) return Result.Rejected
        return updateSubDrawingNested(page, subId, Kind.ADD) { sub ->
            if (sub.measurements.any { it.id == measurement.id }) null
            else sub.copy(measurements = sub.measurements + measurement.copyMeasurement())
        }
    }

    fun deleteSubDrawingMeasurement(page: Int, subId: String, measurement: Measurement): Result =
        updateSubDrawingNested(page, subId, Kind.DELETE) { sub ->
            if (sub.measurements.none { it.id == measurement.id }) null
            else sub.copy(measurements = sub.measurements.filterNot { it.id == measurement.id })
        }

    fun updateSubDrawingMeasurement(
        page: Int,
        subId: String,
        current: Measurement,
        replacement: Measurement,
        kind: Kind = Kind.UPDATE
    ): Result {
        if (!validateMeasurement(current) || !validateMeasurement(replacement) || current.id != replacement.id) return Result.Rejected
        return updateSubDrawingNested(page, subId, kind) { sub ->
            val index = sub.measurements.indexOfFirst { it.id == current.id }
            if (index < 0 || sub.measurements[index] != current) null
            else sub.copy(measurements = sub.measurements.toMutableList().also { it[index] = replacement.copyMeasurement() })
        }
    }

    fun addSubDrawingNote(page: Int, subId: String, note: Note): Result {
        if (!validateNote(note)) return Result.Rejected
        return updateSubDrawingNested(page, subId, Kind.ADD) { sub ->
            if (sub.notes.any { it.id == note.id }) null else sub.copy(notes = sub.notes + note.copyNote())
        }
    }

    fun deleteSubDrawingNote(page: Int, subId: String, note: Note): Result =
        updateSubDrawingNested(page, subId, Kind.DELETE) { sub ->
            if (sub.notes.none { it.id == note.id }) null else sub.copy(notes = sub.notes.filterNot { it.id == note.id })
        }

    fun updateSubDrawingNote(
        page: Int,
        subId: String,
        current: Note,
        replacement: Note,
        kind: Kind = Kind.UPDATE
    ): Result {
        if (!validateNote(current) || !validateNote(replacement) || current.id != replacement.id) return Result.Rejected
        return updateSubDrawingNested(page, subId, kind) { sub ->
            val index = sub.notes.indexOfFirst { it.id == current.id }
            if (index < 0 || sub.notes[index] != current) null
            else sub.copy(notes = sub.notes.toMutableList().also { it[index] = replacement.copyNote() })
        }
    }

    fun addSubDrawingShape(page: Int, subId: String, shape: Shape): Result {
        if (!validateShape(shape)) return Result.Rejected
        return updateSubDrawingNested(page, subId, Kind.ADD) { sub ->
            if (sub.shapes.any { it.id == shape.id }) null else sub.copy(shapes = sub.shapes + shape.copyShape())
        }
    }

    fun deleteSubDrawingShape(page: Int, subId: String, shape: Shape): Result =
        updateSubDrawingNested(page, subId, Kind.DELETE) { sub ->
            if (sub.shapes.none { it.id == shape.id }) null else sub.copy(shapes = sub.shapes.filterNot { it.id == shape.id })
        }

    fun updateSubDrawingShape(
        page: Int,
        subId: String,
        current: Shape,
        replacement: Shape,
        kind: Kind = Kind.UPDATE
    ): Result {
        if (!validateShape(current) || !validateShape(replacement) || current.id != replacement.id) return Result.Rejected
        return updateSubDrawingNested(page, subId, kind) { sub ->
            val index = sub.shapes.indexOfFirst { it.id == current.id }
            if (index < 0 || sub.shapes[index] != current) null
            else sub.copy(shapes = sub.shapes.toMutableList().also { it[index] = replacement.copyShape() })
        }
    }

    /** Photo pins made inside a sub-drawing belong to that sub-drawing. */
    fun addSubDrawingPhoto(page: Int, subId: String, pin: PhotoPin): Result {
        if (!validatePin(pin)) return Result.Rejected
        return updateSubDrawingNested(page, subId, Kind.ADD, capacityCheck = { sub ->
            sub.photos.size <= Stage5Limits.MAX_PHOTO_PINS_PER_PAGE
        }) { sub ->
            if (sub.photos.any { it.id == pin.id }) null else sub.copy(photos = sub.photos + pin.copyPin())
        }
    }

    fun deleteSubDrawingPhoto(page: Int, subId: String, pin: PhotoPin): Result =
        updateSubDrawingNested(page, subId, Kind.DELETE) { sub ->
            if (sub.photos.none { it.id == pin.id }) null else sub.copy(photos = sub.photos.filterNot { it.id == pin.id })
        }

    fun updateSubDrawingPhoto(
        page: Int,
        subId: String,
        current: PhotoPin,
        replacement: PhotoPin,
        kind: Kind = Kind.UPDATE
    ): Result {
        if (!validatePin(current) || !validatePin(replacement) || current.id != replacement.id) return Result.Rejected
        return updateSubDrawingNested(page, subId, kind, capacityCheck = { sub ->
            sub.photos.sumOf { it.imageFileNames.size } <= Stage5Limits.MAX_TOTAL_PHOTOS
        }) { sub ->
            val index = sub.photos.indexOfFirst { it.id == current.id }
            if (index < 0 || sub.photos[index] != current) null
            else sub.copy(photos = sub.photos.toMutableList().also { it[index] = replacement.copyPin() })
        }
    }

    private fun updateSubDrawingImageMarkup(
        page: Int,
        subId: String,
        pinId: String,
        fileName: String,
        kind: Kind,
        transform: (PhotoPin) -> PhotoPin?
    ): Result {
        if (!validFileName(fileName)) return Result.Rejected
        val sub = subDrawingOf(page, subId) ?: return Result.Rejected
        val pin = sub.photos.firstOrNull { it.id == pinId } ?: return Result.Rejected
        if (fileName !in pin.imageFileNames) return Result.Rejected
        val replacement = transform(pin)?.copyPin() ?: return Result.Rejected
        if (replacement == pin) return Result.Unchanged
        return updateSubDrawingPhoto(page, subId, pin, replacement, kind)
    }

    fun addSubDrawingImagePath(page: Int, subId: String, pinId: String, fileName: String, path: DrawnPath): Result {
        if (!validatePath(path)) return Result.Rejected
        return updateSubDrawingImageMarkup(page, subId, pinId, fileName, Kind.ADD) { pin ->
            pin.copy(imagePaths = pin.imagePaths + (fileName to pin.imagePaths[fileName].orEmpty() + path.copyPath()))
        }
    }

    fun addSubDrawingImageMeasurement(page: Int, subId: String, pinId: String, fileName: String, measurement: Measurement): Result {
        if (!validateMeasurement(measurement)) return Result.Rejected
        return updateSubDrawingImageMarkup(page, subId, pinId, fileName, Kind.ADD) { pin ->
            pin.copy(imageMeasurements = pin.imageMeasurements + (fileName to pin.imageMeasurements[fileName].orEmpty() + measurement.copyMeasurement()))
        }
    }

    fun addSubDrawingImageShape(page: Int, subId: String, pinId: String, fileName: String, shape: Shape): Result {
        if (!validateShape(shape)) return Result.Rejected
        return updateSubDrawingImageMarkup(page, subId, pinId, fileName, Kind.ADD) { pin ->
            val list = pin.imageShapes[fileName].orEmpty()
            if (shape.id in list.map { it.id }) null
            else pin.copy(imageShapes = pin.imageShapes + (fileName to list + shape.copyShape()))
        }
    }

    fun addSubDrawingImageNote(page: Int, subId: String, pinId: String, fileName: String, note: Note): Result {
        if (!validateImageNote(note)) return Result.Rejected
        return updateSubDrawingImageMarkup(page, subId, pinId, fileName, Kind.ADD) { pin ->
            val list = pin.imageNotes[fileName].orEmpty()
            if (note.id in list.map { it.id }) null
            else pin.copy(imageNotes = pin.imageNotes + (fileName to list + note.copyImageNote()))
        }
    }

    fun setSubDrawingImageScale(page: Int, subId: String, pinId: String, fileName: String, scale: PageScale, sourceAspect: Float?): Result {
        if (!isValidPageScale(scale.pointsPerFoot)) return Result.Rejected
        val sub = subDrawingOf(page, subId) ?: return Result.Rejected
        val pin = sub.photos.firstOrNull { it.id == pinId } ?: return Result.Rejected
        if (fileName !in pin.imageFileNames) return Result.Rejected
        val aspect = sourceAspect?.takeIf { it.isFinite() && it > 0f } ?: return Result.Rejected
        // The photo's own calibration is stored on the photo surface; the crop
        // factor only scales how its measurements read, never the stored value.
        val values = pin.imageMeasurements[fileName].orEmpty().map { it.copyMeasurement() }
        val updated = recalibratedMeasurements(values, scale, AnnotationSize(1f, aspect)) ?: return Result.Rejected
        if (updated.any { !validateMeasurement(it) }) return Result.Rejected
        val measurements = if (fileName in pin.imageMeasurements) pin.imageMeasurements + (fileName to updated) else pin.imageMeasurements
        return updateSubDrawingPhoto(
            page, subId, pin,
            pin.copy(imageScales = pin.imageScales + (fileName to scale.copy()), imageMeasurements = measurements),
            Kind.UPDATE
        )
    }

    fun deleteSubDrawingImageMarkup(page: Int, subId: String, pinId: String, fileName: String, itemId: String): Result =
        updateSubDrawingImageMarkup(page, subId, pinId, fileName, Kind.DELETE) { pin ->
            pin.copy(
                imagePaths = pin.imagePaths + (fileName to pin.imagePaths[fileName].orEmpty().filterNot { it.id == itemId }),
                imageMeasurements = pin.imageMeasurements + (fileName to pin.imageMeasurements[fileName].orEmpty().filterNot { it.id == itemId }),
                imageNotes = pin.imageNotes + (fileName to pin.imageNotes[fileName].orEmpty().filterNot { it.id == itemId }),
                imageShapes = pin.imageShapes + (fileName to pin.imageShapes[fileName].orEmpty().filterNot { it.id == itemId })
            )
        }

    fun changeSubDrawingImageAppearance(page: Int, subId: String, pinId: String, fileName: String, item: PageItem, style: DrawingToolStyle): Result {
        if (!style.isValid()) return Result.Rejected
        val replacementOf: (PhotoPin) -> PhotoPin? = { pin ->
            when (item) {
                is PageItem.Path -> {
                    if (item.data !in pin.imagePaths[fileName].orEmpty()) null
                    else pin.copy(imagePaths = pin.imagePaths + (fileName to pin.imagePaths[fileName].orEmpty().map {
                        if (it.id == item.data.id) it.copy(colorArgb = style.colorArgb, strokeWidthRatio = style.width) else it }))
                }
                is PageItem.Measure -> {
                    if (item.data !in pin.imageMeasurements[fileName].orEmpty()) null
                    else pin.copy(imageMeasurements = pin.imageMeasurements + (fileName to pin.imageMeasurements[fileName].orEmpty().map {
                        if (it.id == item.data.id) it.copy(colorArgb = style.colorArgb, strokeWidthRatio = style.width) else it }))
                }
                is PageItem.NoteItem -> {
                    if (item.data !in pin.imageNotes[fileName].orEmpty()) null
                    else pin.copy(imageNotes = pin.imageNotes + (fileName to pin.imageNotes[fileName].orEmpty().map {
                        if (it.id == item.data.id) it.copy(colorArgb = style.colorArgb, fontSizeRatio = style.fontSize, isBold = style.bold) else it }))
                }
                is PageItem.ShapeItem -> {
                    if (item.data !in pin.imageShapes[fileName].orEmpty()) null
                    else pin.copy(imageShapes = pin.imageShapes + (fileName to pin.imageShapes[fileName].orEmpty().map {
                        if (it.id == item.data.id) it.copy(
                            colorArgb = style.colorArgb, strokeWidthRatio = style.width, type = style.shape ?: it.type, isFilled = style.filled
                        ) else it }))
                }
                is PageItem.PhotoPinItem -> null
                is PageItem.SubDrawingItem -> null
            }
        }
        return updateSubDrawingImageMarkup(page, subId, pinId, fileName, Kind.UPDATE, replacementOf)
    }

    fun attachSubDrawingPhoto(page: Int, subId: String, pinId: String, fileName: String): Result {
        if (!validFileName(fileName)) return Result.Rejected
        val sub = subDrawingOf(page, subId) ?: return Result.Rejected
        val pin = sub.photos.firstOrNull { it.id == pinId } ?: return Result.Rejected
        if (fileName in pin.imageFileNames || pin.imageFileNames.size >= Stage5Limits.MAX_PHOTOS_PER_PIN) return Result.Rejected
        return updateSubDrawingPhoto(page, subId, pin, pin.copy(imageFileNames = pin.imageFileNames + fileName).copyPin())
    }

    fun detachSubDrawingPhoto(page: Int, subId: String, pinId: String, fileName: String): Result {
        val sub = subDrawingOf(page, subId) ?: return Result.Rejected
        val pin = sub.photos.firstOrNull { it.id == pinId } ?: return Result.Rejected
        if (fileName !in pin.imageFileNames) return Result.Rejected
        val replacement = pin.copy(
            imageFileNames = pin.imageFileNames.filterNot { it == fileName },
            imageNotes = pin.imageNotes - fileName,
            imageShapes = pin.imageShapes - fileName,
            imagePaths = pin.imagePaths - fileName,
            imageMeasurements = pin.imageMeasurements - fileName,
            imageScales = pin.imageScales - fileName
        ).copyPin()
        return updateSubDrawingPhoto(page, subId, pin, replacement, Kind.DELETE)
    }

    fun canAttachSubDrawingPhoto(page: Int, subId: String, pinId: String, fileName: String? = null): Boolean {
        if (!isSessionActive() || !isInitializedPage(page) || !validAnnotationId(subId) || !validId(pinId)) return false
        val sub = vm.pageSubDrawings[page]?.firstOrNull { it.id == subId } ?: return false
        val pin = sub.photos.firstOrNull { it.id == pinId } ?: return false
        if (fileName != null && (!validFileName(fileName) || fileName in pin.imageFileNames)) return false
        if (pin.imageFileNames.size >= Stage5Limits.MAX_PHOTOS_PER_PIN) return false
        val total = pagePhotoReferenceCount(vm) + subDrawingPhotoReferenceCount(vm)
        return total < Stage5Limits.MAX_TOTAL_PHOTOS
    }

    /** Clear is one ordinary undoable transaction; it does not destroy history. */
    fun clearPage(page: Int): Result {
        if (!isMutationPage(page)) return Result.Rejected
        val before = captureSnapshot(vm, page)
        if (before == emptySnapshot(vm, page)) return Result.Unchanged
        return commit(ClearEntry(page, before))
    }

    /**
     * Clears one sub-drawing's own markups: its lines, highlighter strokes,
     * measurements, notes, shapes, and the markups drawn on each of its pinned
     * photos. The crop itself (name, badge, scale, position) and its photos stay;
     * the page's markups and every other sub-drawing are untouched. Undo restores
     * everything that was cleared.
     */
    fun clearSubDrawing(page: Int, subId: String): Result {
        if (!isMutationPage(page) || !validAnnotationId(subId)) return Result.Rejected
        val list = vm.pageSubDrawings[page] ?: return Result.Rejected
        val current = list.firstOrNull { it.id == subId } ?: return Result.Rejected
        if (!validateSubDrawing(current)) return Result.Rejected
        if (current.paths.isEmpty() && current.measurements.isEmpty() &&
            current.notes.isEmpty() && current.shapes.isEmpty() &&
            current.photos.all { pin ->
                pin.imagePaths.values.all { it.isEmpty() } &&
                    pin.imageMeasurements.values.all { it.isEmpty() } &&
                    pin.imageNotes.values.all { it.isEmpty() } &&
                    pin.imageShapes.values.all { it.isEmpty() } &&
                    pin.imageScales.isEmpty()
            }
        ) return Result.Unchanged
        val cleared = current.copy(
            paths = emptyList(),
            measurements = emptyList(),
            notes = emptyList(),
            shapes = emptyList(),
            photos = current.photos.map { pin ->
                pin.copy(
                    imagePaths = pin.imagePaths - pin.imageFileNames.toSet(),
                    imageMeasurements = pin.imageMeasurements - pin.imageFileNames.toSet(),
                    imageNotes = pin.imageNotes - pin.imageFileNames.toSet(),
                    imageShapes = pin.imageShapes - pin.imageFileNames.toSet(),
                    imageScales = pin.imageScales - pin.imageFileNames.toSet()
                ).copyPin()
            }
        )
        return commit(ClearSubDrawingEntry(page, subId, list.indexOf(current), current.copySubDrawing(), cleared))
    }

    /**
     * Clears every markup on one photo of a pin, whichever surface owns the pin.
     * The viewer's clear button reads the open photo's pin from the surface it is
     * showing (page or sub-drawing), so this is the single command for both.
     */
    fun clearPhotoAnnotations(page: Int, pinId: String, fileName: String): Result {
        val current = storedPin(vm, page, pinId) ?: return Result.Rejected
        return clearImageAnnotations(page, current, fileName)
    }

    /** Clears the markups on every photo pinned inside one sub-drawing. */
    fun clearSubDrawingPhotoAnnotations(page: Int, subId: String): Result {
        if (!isMutationPage(page) || !validAnnotationId(subId)) return Result.Rejected
        val list = vm.pageSubDrawings[page] ?: return Result.Rejected
        val current = list.firstOrNull { it.id == subId } ?: return Result.Rejected
        if (!validateSubDrawing(current)) return Result.Rejected
        if (current.photos.all { pin ->
                pin.imagePaths.values.all { it.isEmpty() } &&
                    pin.imageMeasurements.values.all { it.isEmpty() } &&
                    pin.imageNotes.values.all { it.isEmpty() } &&
                    pin.imageShapes.values.all { it.isEmpty() } &&
                    pin.imageScales.isEmpty()
            }
        ) return Result.Unchanged
        val cleared = current.copy(
            photos = current.photos.map { pin ->
                pin.copy(
                    imagePaths = pin.imagePaths - pin.imageFileNames.toSet(),
                    imageMeasurements = pin.imageMeasurements - pin.imageFileNames.toSet(),
                    imageNotes = pin.imageNotes - pin.imageFileNames.toSet(),
                    imageShapes = pin.imageShapes - pin.imageFileNames.toSet(),
                    imageScales = pin.imageScales - pin.imageFileNames.toSet()
                ).copyPin()
            }
        )
        return commit(ClearSubDrawingEntry(page, subId, list.indexOf(current), current.copySubDrawing(), cleared))
    }

    fun undo(page: Int): Result {
        if (!isMutationPage(page)) return Result.Rejected
        val record = historyOwner.takeUndo(page) ?: return Result.Rejected
        if (!record.entry.reverse(vm)) {
            historyOwner.restoreUndo(page, record)
            return Result.Rejected
        }
        historyOwner.putRedo(page, record)
        effectSink(record.entry.intent(Kind.UNDO))
        return Result.Accepted
    }
    fun redo(page: Int): Result {
        if (!isMutationPage(page)) return Result.Rejected
        val record = historyOwner.takeRedo(page) ?: return Result.Rejected
        if (!record.entry.apply(vm)) {
            historyOwner.restoreRedo(page, record)
            return Result.Rejected
        }
        historyOwner.putUndo(page, record)
        effectSink(record.entry.intent(Kind.REDO))
        return Result.Accepted
    }
    fun acceptsCurrentSession(): Boolean = isSessionActive()
    fun canUndo(page: Int): Boolean = isSessionActive() && isInitializedPage(page) && historyOwner.canUndo(page)
    fun canRedo(page: Int): Boolean = isSessionActive() && isInitializedPage(page) && historyOwner.canRedo(page)
    internal fun retainedPhotoNames() = historyOwner.retainedPhotoNames()
    fun clear() = historyOwner.clear()

    private fun updateShape(page: Int, pinId: String?, fileName: String?, shape: Shape, replacement: Shape, kind: Kind): Result {
        if (!isMutationPage(page) || !validateShape(shape) || !validateShape(replacement) || shape.id != replacement.id) return Result.Rejected
        if (pinId != null && (!validId(pinId) || !validFileName(fileName ?: ""))) return Result.Rejected
        val list = if (pinId == null) vm.pageShapes[page] else findPin(vm, page, pinId)?.imageShapes?.get(fileName)
        val current = list?.firstOrNull { it.id == shape.id } ?: return Result.Rejected
        if (current != shape) return Result.Rejected
        if (current == replacement) return Result.Unchanged
        return commit(ShapeEntry(page, pinId, fileName, current.copyShape(), replacement.copyShape(), shape.id, list.indexOf(current), kind))
    }

    /**
     * The one way a page-level annotation is added: check the session and page,
     * check the candidate, keep ids unique on the page, keep the page within its
     * annotation budget, then record exactly one history entry. Every add command
     * in every family (paths, measurements, notes, pins, shapes, sub-drawings) runs
     * through here, so a family cannot quietly grow its own weaker copy of the rules.
     */
    private fun <T> addPageAnnotation(
        page: Int,
        list: MutableList<T>?,
        value: T,
        id: String,
        valid: Boolean = true,
        capacity: Long = 1L,
        entry: (MutableList<T>, T) -> Entry
    ): Result {
        if (!isMutationPage(page) || !valid || list == null) return Result.Rejected
        if (pageContainsAnnotationId(vm, page, id) || !hasPageAnnotationCapacity(vm, page, capacity)) return Result.Rejected
        return commit(entry(list, value))
    }

    /**
     * The one way an annotation on a page collection is replaced: check the session
     * and page, check both sides, find the stored item by id, require the caller's
     * `current` to be exactly what is stored, keep unchanged input out of history,
     * then record one entry. Paths, measurements, notes, pins, shapes, and
     * sub-drawings all run through here, so every family undoes and redoes the same
     * way and a rejected edit can never reach the view model.
     */
    private fun <T> replacePageAnnotation(
        page: Int,
        list: MutableList<T>?,
        current: T,
        replacement: T,
        kind: AnnotationReducer.Kind,
        valid: Boolean,
        detach: (T) -> T,
        entry: (Int, T?, T?, Int, AnnotationReducer.Kind) -> Entry
    ): Result {
        if (!isMutationPage(page) || !valid || list == null) return Result.Rejected
        if (current == null || replacement == null) return Result.Rejected
        val id = annotationItemId(current)
        if (id.isEmpty() || annotationItemId(replacement) != id) return Result.Rejected
        val index = list.indexOfFirst { annotationItemId(it) == id }
        if (index < 0 || list[index] != current) return Result.Rejected
        if (current == replacement) return Result.Unchanged
        return commit(entry(page, detach(current), detach(replacement), index, kind))
    }

    /** The id a page-collection item is known by; empty for anything unannotated. */
    private fun <T> annotationItemId(item: T): String = itemId(item)

    /** The one way an annotation is deleted from a page collection. */
    private fun <T> deletePageAnnotation(
        page: Int,
        list: MutableList<T>?,
        item: T,
        valid: Boolean,
        detach: (T) -> T,
        entry: (Int, T?, Int) -> Entry
    ): Result {
        if (!isMutationPage(page) || !valid || list == null) return Result.Rejected
        if (item == null) return Result.Rejected
        val id = annotationItemId(item)
        val index = list.indexOfFirst { annotationItemId(it) == id }
        if (id.isEmpty() || index < 0 || list[index] != item) return Result.Rejected
        return commit(entry(page, detach(item), index))
    }


    private fun commit(entry: Entry): Result {
        if (!historyOwner.canRecord(entry) || !entry.apply(vm)) return Result.Rejected
        historyOwner.record(entry)
        effectSink(entry.intent(entry.kind))
        return Result.Accepted
    }

    /** A page is writable only after the document/session owner initialized it. */
    private fun isInitializedPage(page: Int): Boolean = page >= 0 && (
        vm.pageScales.containsKey(page) ||
            vm.pagePaths.containsKey(page) ||
            vm.pageMeasurements.containsKey(page) ||
            vm.pageNotes.containsKey(page) ||
            vm.pagePhotoPins.containsKey(page) ||
            vm.pageShapes.containsKey(page) ||
            vm.pageSubDrawings.containsKey(page) ||
            historyOwner.canUndo(page) ||
            historyOwner.canRedo(page)
        )

    private fun isMutationPage(page: Int): Boolean = isSessionActive() && isInitializedPage(page)

    private fun isSessionActive() = sessionKey != null && historyOwner.isEpochCurrent(capturedHistoryEpoch) && sessionActivePredicate() && currentSessionKey() == sessionKey

    companion object {
        private fun <T> replaceById(list: MutableList<T>?, id: String?, expected: T?, value: T?, ordinal: Int): Boolean {
            if (list == null || id == null) return false
            val index = when (expected) {
                null -> list.indexOfFirst { itemId(it) == id }
                else -> list.indexOfFirst { itemId(it) == id }
            }
            if (expected == null && value != null) {
                if (index >= 0) return false
                list.add(ordinal.coerceIn(0, list.size), detached(value))
                return true
            }
            if (index < 0 || expected == null || list[index] != expected) return false
            if (value == null) list.removeAt(index) else list[index] = detached(value)
            return true
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> itemId(item: T): String = when (item) {
            is DrawnPath -> item.id
            is Measurement -> item.id
            is Note -> item.id
            is PhotoPin -> item.id
            is Shape -> item.id
            is com.example.myapplication.SubDrawing -> item.id
            else -> ""
        }
        @Suppress("UNCHECKED_CAST")
        private fun <T> detached(value: T): T = when (value) {
            is DrawnPath -> value.copyPath() as T
            is Measurement -> value.copyMeasurement(value.p1.copyPoint(), value.p2.copyPoint()) as T
            is Note -> value.copyNote() as T
            else -> value
        }

        private fun replaceSubDrawing(
            list: MutableList<com.example.myapplication.SubDrawing>?,
            id: String,
            expected: com.example.myapplication.SubDrawing?,
            value: com.example.myapplication.SubDrawing?,
            ordinal: Int
        ): Boolean = replaceById(list, id, expected, value, ordinal)

        private fun replacePin(list: MutableList<PhotoPin>?, id: String, expected: PhotoPin?, value: PhotoPin?, ordinal: Int): Boolean {
            if (list == null) return false
            val index = list.indexOfFirst { it.id == id }
            if (expected == null && value != null) {
                if (index >= 0) return false
                list.add(ordinal.coerceIn(0, list.size), value.copyPin()); return true
            }
            if (index < 0 || expected == null || list[index] != expected) return false
            if (value == null) list.removeAt(index) else list[index] = value.copyPin()
            return true
        }
        private fun replaceShape(list: MutableList<Shape>?, id: String, expected: Shape?, value: Shape?, ordinal: Int): Boolean {
            if (list == null) return false
            val index = list.indexOfFirst { it.id == id }
            if (expected == null && value != null) {
                if (index >= 0) return false
                list.add(ordinal.coerceIn(0, list.size), value.copyShape()); return true
            }
            if (index < 0 || expected == null || list[index] != expected) return false
            if (value == null) list.removeAt(index) else list[index] = value.copyShape()
            return true
        }
        private fun replaceImageNote(vm: BlueprintViewModel, page: Int, pinId: String, fileName: String,
            id: String, expected: PhotoImageNote?, value: PhotoImageNote?, ordinal: Int): Boolean {
            val pins = vm.pagePhotoPins[page] ?: return false
            val pinIndex = pins.indexOfFirst { it.id == pinId }
            if (pinIndex < 0) return false
            val pin = pins[pinIndex]
            if (fileName !in pin.imageFileNames) return false
            val values = pin.imageNotes[fileName].orEmpty().toMutableList()
            val index = values.indexOfFirst { it.id == id }
            if (expected == null && value != null) {
                if (index >= 0) return false
                values.add(ordinal.coerceIn(0, values.size), value.copy())
            } else {
                if (index < 0 || expected == null || values[index] != expected) return false
                if (value == null) values.removeAt(index) else values[index] = value.copy()
            }
            val annotations = pin.imageNotes.toMutableMap()
            if (values.isEmpty()) annotations.remove(fileName) else annotations[fileName] = values
            pins[pinIndex] = pin.copy(imageNotes = annotations).copyPin()
            return true
        }
        private fun replaceImageShape(vm: BlueprintViewModel, page: Int, pinId: String, fileName: String,
            id: String, expected: Shape?, value: Shape?, ordinal: Int): Boolean {
            val pins = vm.pagePhotoPins[page] ?: return false
            val pinIndex = pins.indexOfFirst { it.id == pinId }
            if (pinIndex < 0) return false
            val pin = pins[pinIndex]
            if (fileName !in pin.imageFileNames) return false
            val values = pin.imageShapes[fileName].orEmpty().toMutableList()
            val index = values.indexOfFirst { it.id == id }
            if (expected == null && value != null) {
                if (index >= 0) return false
                values.add(ordinal.coerceIn(0, values.size), value.copy())
            } else {
                if (index < 0 || expected == null || values[index] != expected) return false
                if (value == null) values.removeAt(index) else values[index] = value.copy()
            }
            val annotations = pin.imageShapes.toMutableMap()
            if (values.isEmpty()) annotations.remove(fileName) else annotations[fileName] = values
            pins[pinIndex] = pin.copy(imageShapes = annotations).copyPin()
            return true
        }

        private fun replaceScale(vm: BlueprintViewModel, page: Int, expected: PageScale?, value: PageScale?): Boolean {
            if (expected == null && value == null) return false
            if (expected == null) { if (vm.pageScales.containsKey(page)) return false }
            else if (vm.pageScales[page] != expected) return false
            if (value == null) vm.pageScales.remove(page) else vm.pageScales[page] = value.copy()
            return true
        }
        private fun applySnapshot(vm: BlueprintViewModel, page: Int, snapshot: PageSnapshot): Boolean {
            val value = snapshot.deepCopy()
            fun <T> put(map: MutableMap<Int, androidx.compose.runtime.snapshots.SnapshotStateList<T>>, list: List<T>?) {
                if (list == null) map.remove(page) else map[page] = mutableStateListOf<T>().also { it.addAll(list) }
            }
            put(vm.pagePaths, value.paths); put(vm.pageMeasurements, value.measurements); put(vm.pageNotes, value.notes)
            put(vm.pagePhotoPins, value.photoPins); put(vm.pageShapes, value.shapes)
            put(vm.pageSubDrawings, value.subDrawings)
            if (value.scalePresent) vm.pageScales[page] = value.scale!!.copy() else vm.pageScales.remove(page)
            return true
        }
        private fun captureSnapshot(vm: BlueprintViewModel, page: Int) = PageSnapshot(
            vm.pagePaths[page]?.map { it.copyPath() }, vm.pageMeasurements[page]?.map { it.copyMeasurement(it.p1.copyPoint(), it.p2.copyPoint()) },
            vm.pageNotes[page]?.map { it.copyNote() }, vm.pagePhotoPins[page]?.map { it.copyPin() }, vm.pageScales[page]?.copy(), vm.pageScales.containsKey(page), vm.pageShapes[page]?.map { it.copyShape() },
            vm.pageSubDrawings[page]?.map { it.copySubDrawing() }
        )
        private fun emptySnapshot(vm: BlueprintViewModel, page: Int) = PageSnapshot(
            if (vm.pagePaths.containsKey(page)) emptyList() else null, if (vm.pageMeasurements.containsKey(page)) emptyList() else null,
            if (vm.pageNotes.containsKey(page)) emptyList() else null, if (vm.pagePhotoPins.containsKey(page)) emptyList() else null,
            null, false, if (vm.pageShapes.containsKey(page)) emptyList() else null,
            if (vm.pageSubDrawings.containsKey(page)) emptyList() else null
        )
        private fun findPin(vm: BlueprintViewModel, page: Int, id: String) = vm.pagePhotoPins[page]?.firstOrNull { it.id == id }
        private fun pinBytes(pin: PhotoPin?): Long = pin?.let { 128L + it.imageFileNames.sumOf { n -> stringBytes(n) } + it.imageNotes.values.sumOf { list -> list.sumOf(::imageNoteBytes) } + it.imageShapes.values.sumOf { list -> list.sumOf(::shapeBytes) } + it.imagePaths.values.sumOf { list -> list.sumOf(::pathBytes) } + it.imageMeasurements.values.sumOf { list -> list.sumOf(::measurementBytes) } + it.imageScales.size * 32L } ?: 0L
    }
}
