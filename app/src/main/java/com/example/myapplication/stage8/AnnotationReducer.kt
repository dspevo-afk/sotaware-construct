package com.example.myapplication.stage8

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.DrawnPath
import com.example.myapplication.Measurement
import com.example.myapplication.Note
import com.example.myapplication.PageScale
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
private fun measurementBytes(value: Measurement?): Long = value?.let { 112L + stringBytes(it.text) } ?: 0L
private fun noteBytes(value: Note?): Long = value?.let { 128L + stringBytes(it.text) } ?: 0L
private fun imageNoteBytes(value: PhotoImageNote?): Long = value?.let { 128L + stringBytes(it.text) } ?: 0L
private fun shapeBytes(value: Shape?): Long = value?.let { 160L } ?: 0L

private fun validId(id: String): Boolean = com.example.myapplication.stage8.validAnnotationId(id)
private fun validNorm(value: Float): Boolean = value.isFinite() && value in 0f..1f
private fun validRatio(value: Float): Boolean = value.isFinite() && value > 0f && value <= 1f
private fun validRotation(value: Float): Boolean = value.isFinite() && kotlin.math.abs(value) <= AnnotationModelV2.MAX_ROTATION_DEGREES
private fun validText(value: String): Boolean = value.isNotBlank() && value.length <= Stage5Limits.MAX_TEXT_CHARS
private fun validFileName(value: String): Boolean = try {
    com.example.myapplication.stage5.validatePhotoFileName(value)
    true
} catch (_: IllegalArgumentException) { false }

private fun validatePoint(point: Point): Boolean = validNorm(point.x) && validNorm(point.y)

private fun validatePath(path: DrawnPath): Boolean =
    validId(path.id) && path.points.isNotEmpty() && path.points.size <= Stage5Limits.MAX_PATH_POINTS &&
        path.points.all(::validatePoint) && validRatio(path.strokeWidthRatio)

private fun validateMeasurement(value: Measurement): Boolean =
    validId(value.id) && validatePoint(value.p1) && validatePoint(value.p2) && validText(value.text)

private fun validateNote(value: Note): Boolean =
    validId(value.id) && validNorm(value.x) && validNorm(value.y) && validText(value.text) &&
        validRatio(value.fontSizeRatio) && validRotation(value.rotation)

private fun validateShape(value: Shape): Boolean =
    validId(value.id) && validNorm(value.x) && validNorm(value.y) && validRatio(value.widthRatio) &&
        validRatio(value.heightRatio) && validRatio(value.strokeWidthRatio) && validRotation(value.rotation)

private fun validateImageNote(value: PhotoImageNote): Boolean =
    validId(value.id) && validNorm(value.x) && validNorm(value.y) && validText(value.text) &&
        validRatio(value.fontSizeRatio) && validRotation(value.rotation)

private fun validatePin(value: PhotoPin): Boolean {
    if (!validId(value.id) || !validNorm(value.x) || !validNorm(value.y)) return false
    if (value.imageFileNames.size > Stage5Limits.MAX_PHOTOS_PER_PIN ||
        value.imageFileNames.any { !validFileName(it) } || value.imageFileNames.toSet().size != value.imageFileNames.size
    ) return false
    val attached = value.imageFileNames.toSet()
    if (value.imageNotes.keys.any { it !in attached } || value.imageShapes.keys.any { it !in attached }) return false
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
    for (fileName in attached) {
        val noteIds = value.imageNotes[fileName].orEmpty().map { it.id }
        val shapeIds = value.imageShapes[fileName].orEmpty().map { it.id }
        if ((noteIds + shapeIds).toSet().size != noteIds.size + shapeIds.size) return false
        if (noteIds.size + shapeIds.size > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE) return false
    }
    return true
}

private fun pagePhotoReferenceCount(vm: BlueprintViewModel): Long =
    vm.pagePhotoPins.values.sumOf { pins ->
        pins.sumOf { it.imageFileNames.size.toLong() }
    }

private fun pinAnnotationCount(pin: PhotoPin): Long =
    1L + pin.imageNotes.values.sumOf { it.size.toLong() } +
        pin.imageShapes.values.sumOf { it.size.toLong() }

/** Counts every annotation domain that the current snapshot validator budgets. */
private fun pageAnnotationCount(vm: BlueprintViewModel, page: Int): Long {
    val paths = vm.pagePaths[page]?.size ?: 0
    val measurements = vm.pageMeasurements[page]?.size ?: 0
    val notes = vm.pageNotes[page]?.size ?: 0
    val pins = vm.pagePhotoPins[page].orEmpty()
    val shapes = vm.pageShapes[page]?.size ?: 0
    val nested = pins.sumOf { pin ->
        pin.imageNotes.values.sumOf { it.size.toLong() } +
            pin.imageShapes.values.sumOf { it.size.toLong() }
    }
    val scale = if (vm.pageScales.containsKey(page)) 1L else 0L
    return paths.toLong() + measurements + notes + pins.size + shapes + nested + scale
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
        vm.pageShapes[page].orEmpty().any { it.id == id }

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
        override val kind: Kind = Kind.UPDATE
    ) : Entry() {
        override val weightBytes get() = 64L + (if (before != null) 32L else 0L) + (if (after != null) 32L else 0L)
        override fun deepCopy() = copy(before = before?.copy(), after = after?.copy())
        override fun apply(vm: BlueprintViewModel) = replaceScale(vm, page, before, after)
        override fun reverse(vm: BlueprintViewModel) = replaceScale(vm, page, after, before)
        override fun intent(kind: Kind) = EffectIntent(page, kind)
    }

    private data class PageSnapshot(
        val paths: List<DrawnPath>?,
        val measurements: List<Measurement>?,
        val notes: List<Note>?,
        val photoPins: List<PhotoPin>?,
        val scale: PageScale?,
        val scalePresent: Boolean,
        val shapes: List<Shape>?
    ) {
        val weightBytes: Long
            get() = 64L + (paths?.sumOf(::pathBytes) ?: 0L) + (measurements?.sumOf(::measurementBytes) ?: 0L) +
                (notes?.sumOf(::noteBytes) ?: 0L) + (photoPins?.sumOf(::pinBytes) ?: 0L) +
                (shapes?.sumOf(::shapeBytes) ?: 0L) + if (scale != null) 32L else 0L

        fun deepCopy() = copy(
            paths = paths?.map { it.copyPath() },
            measurements = measurements?.map { it.copyMeasurement(it.p1.copyPoint(), it.p2.copyPoint()) },
            notes = notes?.map { it.copyNote() }, photoPins = photoPins?.map { it.copyPin() },
            scale = scale?.copy(), shapes = shapes?.map { it.copyShape() }
        )
    }

    private data class ClearEntry(override val page: Int, val before: PageSnapshot) : Entry() {
        override val kind = Kind.CLEAR
        override val weightBytes get() = 64L + before.weightBytes
        override fun deepCopy() = copy(before = before.deepCopy())
        override fun apply(vm: BlueprintViewModel) = applySnapshot(vm, page, emptySnapshot(vm, page))
        override fun reverse(vm: BlueprintViewModel) = applySnapshot(vm, page, before)
        override fun intent(kind: Kind) = EffectIntent(page, kind)
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
            is ImageNoteEntry -> setOf(entry.fileName)
            is ShapeEntry -> entry.fileName?.let(::setOf) ?: emptySet()
            is ClearEntry -> entry.before.photoPins.orEmpty().flatMapTo(LinkedHashSet()) { photoNames(it) }
            else -> emptySet()
        }
        private fun photoNames(pin: PhotoPin?): Set<String> = pin?.let { buildSet { addAll(it.imageFileNames); addAll(it.imageNotes.keys); addAll(it.imageShapes.keys) } } ?: emptySet()
    }

    private val historyOwner = vm.annotationHistory
    private val capturedHistoryEpoch = historyOwner.epoch

    fun addPdfPath(page: Int, path: DrawnPath): Result {
        if (!isMutationPage(page) || !validatePath(path)) return Result.Rejected
        val list = vm.pagePaths[page] ?: return Result.Rejected
        if (pageContainsAnnotationId(vm, page, path.id) || !hasPageAnnotationCapacity(vm, page)) return Result.Rejected
        return commit(PathEntry(page, null, path.copyPath(), list.size, Kind.ADD))
    }

    fun deletePdfPath(page: Int, path: DrawnPath): Result {
        if (!isMutationPage(page) || !validatePath(path)) return Result.Rejected
        val list = vm.pagePaths[page] ?: return Result.Rejected
        val current = list.firstOrNull { it.id == path.id } ?: return Result.Rejected
        if (current != path) return Result.Rejected
        return commit(PathEntry(page, current.copyPath(), null, list.indexOf(current), Kind.DELETE))
    }

    fun addMeasurement(page: Int, measurement: Measurement): Result {
        if (!isMutationPage(page) || !validateMeasurement(measurement)) return Result.Rejected
        val list = vm.pageMeasurements[page] ?: return Result.Rejected
        if (pageContainsAnnotationId(vm, page, measurement.id) || !hasPageAnnotationCapacity(vm, page)) return Result.Rejected
        return commit(MeasurementEntry(page, null, measurement.copyMeasurement(measurement.p1.copyPoint(), measurement.p2.copyPoint()), list.size, Kind.ADD))
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

    fun addPdfNote(page: Int, note: Note): Result {
        if (!isMutationPage(page) || !validateNote(note)) return Result.Rejected
        val list = vm.pageNotes[page] ?: return Result.Rejected
        if (pageContainsAnnotationId(vm, page, note.id) || !hasPageAnnotationCapacity(vm, page)) return Result.Rejected
        return commit(NoteEntry(page, null, note.copyNote(), list.size, Kind.ADD))
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
        if (!isMutationPage(page) || !validatePin(pin)) return Result.Rejected
        val list = vm.pagePhotoPins[page] ?: return Result.Rejected
        if (pageContainsAnnotationId(vm, page, pin.id) || list.size >= Stage5Limits.MAX_PHOTO_PINS_PER_PAGE ||
            pagePhotoReferenceCount(vm) > Stage5Limits.MAX_TOTAL_PHOTOS.toLong() - pin.imageFileNames.size
        ) return Result.Rejected
        if (!hasPageAnnotationCapacity(vm, page, pinAnnotationCount(pin))) return Result.Rejected
        return commit(PinEntry(page, null, pin.copyPin(), pin.id, list.size, Kind.ADD))
    }
    fun updatePhotoPin(page: Int, pin: PhotoPin, replacement: PhotoPin, kind: Kind = Kind.UPDATE): Result {
        if (!isMutationPage(page) || !validatePin(pin) || !validatePin(replacement) || pin.id != replacement.id) return Result.Rejected
        val list = vm.pagePhotoPins[page] ?: return Result.Rejected
        val current = list.firstOrNull { it.id == pin.id } ?: return Result.Rejected
        if (current != pin) return Result.Rejected
        val projectedPhotos = pagePhotoReferenceCount(vm) - pin.imageFileNames.size + replacement.imageFileNames.size
        if (projectedPhotos > Stage5Limits.MAX_TOTAL_PHOTOS.toLong()) return Result.Rejected
        val projectedAnnotations = pageAnnotationCount(vm, page) - pinAnnotationCount(pin) + pinAnnotationCount(replacement)
        if (projectedAnnotations > Stage5Limits.MAX_ANNOTATIONS_PER_PAGE.toLong()) return Result.Rejected
        if (current == replacement) return Result.Unchanged
        return commit(PinEntry(page, current.copyPin(), replacement.copyPin(), pin.id, list.indexOf(current), kind))
    }
    fun canAttachPhoto(page: Int, pinId: String, fileName: String? = null): Boolean {
        if (!isSessionActive() || !isInitializedPage(page) || !validId(pinId)) return false
        val pin = vm.pagePhotoPins[page]?.firstOrNull { it.id == pinId } ?: return false
        if (fileName != null && (!validFileName(fileName) || fileName in pin.imageFileNames)) return false
        if (pin.imageFileNames.size >= Stage5Limits.MAX_PHOTOS_PER_PIN) return false
        val total = vm.pagePhotoPins.values.sumOf { values -> values.sumOf { it.imageFileNames.size } }
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
            imageShapes = pin.imageShapes - fileName
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

    fun setScale(page: Int, scale: PageScale?): Result {
        if (!isMutationPage(page) || (scale != null && !isValidPageScale(scale.pointsPerFoot))) return Result.Rejected
        val old = vm.pageScales[page]?.copy()
        if (old != null && !isValidPageScale(old.pointsPerFoot)) return Result.Rejected
        if (old == scale) return Result.Unchanged
        if (old == null && scale != null && !hasPageAnnotationCapacity(vm, page)) return Result.Rejected
        return commit(ScaleEntry(page, old, scale?.copy()))
    }

    fun addPdfShape(page: Int, shape: Shape): Result {
        if (!isMutationPage(page) || !validateShape(shape)) return Result.Rejected
        val list = vm.pageShapes[page] ?: return Result.Rejected
        if (pageContainsAnnotationId(vm, page, shape.id) || !hasPageAnnotationCapacity(vm, page)) return Result.Rejected
        return commit(ShapeEntry(page, null, null, null, shape.copyShape(), shape.id, list.size, Kind.ADD))
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
            pin.imageShapes[fileName].orEmpty().any { it.id == note.id } || !hasPageAnnotationCapacity(vm, page)
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
            pin.imageNotes[fileName].orEmpty().any { it.id == shape.id } || !hasPageAnnotationCapacity(vm, page)
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

    /** Clear is one ordinary undoable transaction; it does not destroy history. */
    fun clearPage(page: Int): Result {
        if (!isMutationPage(page)) return Result.Rejected
        val before = captureSnapshot(vm, page)
        if (before == emptySnapshot(vm, page)) return Result.Unchanged
        return commit(ClearEntry(page, before))
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
            else -> ""
        }
        @Suppress("UNCHECKED_CAST")
        private fun <T> detached(value: T): T = when (value) {
            is DrawnPath -> value.copyPath() as T
            is Measurement -> value.copyMeasurement(value.p1.copyPoint(), value.p2.copyPoint()) as T
            is Note -> value.copyNote() as T
            else -> value
        }

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
            if (value.scalePresent) vm.pageScales[page] = value.scale!!.copy() else vm.pageScales.remove(page)
            return true
        }
        private fun captureSnapshot(vm: BlueprintViewModel, page: Int) = PageSnapshot(
            vm.pagePaths[page]?.map { it.copyPath() }, vm.pageMeasurements[page]?.map { it.copyMeasurement(it.p1.copyPoint(), it.p2.copyPoint()) },
            vm.pageNotes[page]?.map { it.copyNote() }, vm.pagePhotoPins[page]?.map { it.copyPin() }, vm.pageScales[page]?.copy(), vm.pageScales.containsKey(page), vm.pageShapes[page]?.map { it.copyShape() }
        )
        private fun emptySnapshot(vm: BlueprintViewModel, page: Int) = PageSnapshot(
            if (vm.pagePaths.containsKey(page)) emptyList() else null, if (vm.pageMeasurements.containsKey(page)) emptyList() else null,
            if (vm.pageNotes.containsKey(page)) emptyList() else null, if (vm.pagePhotoPins.containsKey(page)) emptyList() else null,
            null, false, if (vm.pageShapes.containsKey(page)) emptyList() else null
        )
        private fun findPin(vm: BlueprintViewModel, page: Int, id: String) = vm.pagePhotoPins[page]?.firstOrNull { it.id == id }
        private fun pinBytes(pin: PhotoPin?): Long = pin?.let { 128L + it.imageFileNames.sumOf { n -> stringBytes(n) } + it.imageNotes.values.sumOf { list -> list.sumOf(::imageNoteBytes) } + it.imageShapes.values.sumOf { list -> list.sumOf(::shapeBytes) } } ?: 0L
    }
}
