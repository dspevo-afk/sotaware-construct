package com.example.myapplication.stage8

import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.Note
import com.example.myapplication.PhotoImageNote
import com.example.myapplication.PhotoPin
import com.example.myapplication.Shape
import com.example.myapplication.DrawnPath
import com.example.myapplication.Measurement
import com.example.myapplication.PageScale
import com.example.myapplication.Point
import androidx.compose.runtime.mutableStateListOf

/**
 * Small state boundary for annotation mutations.  The reducer owns only the
 * PDF/image annotation domains; persistence and synchronization remain the
 * responsibility of the caller through [effectSink].
 */
class AnnotationReducer(
    private val vm: BlueprintViewModel,
    private val effectSink: (EffectIntent) -> Unit = {},
    /** Immutable identity captured by this reducer's UI/session closure. */
    private val sessionKey: Any? = null,
    private val currentSessionKey: () -> Any? = { sessionKey },
    private val sessionActivePredicate: () -> Boolean = { true }
) {
    enum class Kind { ADD, UPDATE, DELETE, MOVE, RESIZE, ROTATE, CLEAR, UNDO, REDO }

    data class EffectIntent(
        val page: Int,
        val kind: Kind,
        val pinId: String? = null,
        val fileName: String? = null,
        val annotationId: String? = null
    )

    private sealed class Entry {
        abstract val page: Int
        abstract val kind: Kind
        abstract fun deepCopy(): Entry
        abstract fun apply(vm: BlueprintViewModel): Boolean
        abstract fun reverse(vm: BlueprintViewModel): Boolean
        abstract fun intent(kind: Kind): EffectIntent
    }

    private data class PdfPathEntry(
        override val page: Int,
        val before: DrawnPath?,
        val after: DrawnPath?,
        val ordinal: Int,
        override val kind: Kind
    ) : Entry() {
        override fun deepCopy() = copy(before = before?.copyPath(), after = after?.copyPath())
        override fun apply(vm: BlueprintViewModel) = applyPath(vm, page, ordinal, before, after)
        override fun reverse(vm: BlueprintViewModel) = applyPath(vm, page, ordinal, after, before)
        override fun intent(kind: Kind) = EffectIntent(page, kind)
    }

    private data class PdfMeasurementEntry(
        override val page: Int,
        val before: Measurement?,
        val after: Measurement?,
        val ordinal: Int,
        override val kind: Kind
    ) : Entry() {
        override fun deepCopy() = copy(
            before = before?.copyMeasurement(before.p1.copyPoint(), before.p2.copyPoint()),
            after = after?.copyMeasurement(after.p1.copyPoint(), after.p2.copyPoint())
        )
        override fun apply(vm: BlueprintViewModel) = applyMeasurement(vm, page, ordinal, before, after)
        override fun reverse(vm: BlueprintViewModel) = applyMeasurement(vm, page, ordinal, after, before)
        override fun intent(kind: Kind) = EffectIntent(page, kind)
    }

    private data class PhotoPinEntry(
        override val page: Int,
        val before: PhotoPin?,
        val after: PhotoPin?,
        val id: String,
        val ordinal: Int,
        override val kind: Kind
    ) : Entry() {
        override fun deepCopy() = copy(before = before?.copyPin(), after = after?.copyPin())
        override fun apply(vm: BlueprintViewModel) = applyPhotoPin(vm, page, id, ordinal, before, after)
        override fun reverse(vm: BlueprintViewModel) = applyPhotoPin(vm, page, id, ordinal, after, before)
        override fun intent(kind: Kind) = EffectIntent(page, kind, annotationId = id)
    }

    private data class ScaleEntry(
        override val page: Int,
        val before: PageScale?,
        val after: PageScale?,
        override val kind: Kind = Kind.UPDATE
    ) : Entry() {
        override fun deepCopy() = copy(before = before?.copy(), after = after?.copy())
        override fun apply(vm: BlueprintViewModel) = applyScale(vm, page, before, after)
        override fun reverse(vm: BlueprintViewModel) = applyScale(vm, page, after, before)
        override fun intent(kind: Kind) = EffectIntent(page, kind)
    }

    private data class PdfNoteEntry(
        override val page: Int,
        val before: Note?,
        val after: Note?,
        val ordinal: Int,
        override val kind: Kind,
    ) : Entry() {
        override fun deepCopy() = copy(before = before?.copyNote(), after = after?.copyNote())
        override fun apply(vm: BlueprintViewModel): Boolean = applyPdfNote(vm, page, ordinal, before, after)
        override fun reverse(vm: BlueprintViewModel): Boolean = applyPdfNote(vm, page, ordinal, after, before)
        override fun intent(kind: Kind) = EffectIntent(page, kind)
    }

    private data class PdfShapeEntry(
        override val page: Int,
        val before: Shape?,
        val after: Shape?,
        val id: String,
        val ordinal: Int,
        override val kind: Kind,
    ) : Entry() {
        override fun deepCopy() = copy(before = before?.copyShape(), after = after?.copyShape())
        override fun apply(vm: BlueprintViewModel): Boolean = replaceById(vm.pageShapes[page], id, before, after, ordinal)
        override fun reverse(vm: BlueprintViewModel): Boolean = replaceById(vm.pageShapes[page], id, after, before, ordinal)
        override fun intent(kind: Kind) = EffectIntent(page, kind, annotationId = id)
    }

    private data class ImageNoteEntry(
        override val page: Int,
        val pinId: String,
        val fileName: String,
        val before: PhotoImageNote?,
        val after: PhotoImageNote?,
        val ordinal: Int,
        override val kind: Kind,
    ) : Entry() {
        private fun pin(vm: BlueprintViewModel): PhotoPin? = vm.pagePhotoPins[page]?.firstOrNull { it.id == pinId }
        override fun deepCopy() = copy(before = before?.copyImageNote(), after = after?.copyImageNote())
        override fun apply(vm: BlueprintViewModel) = replaceImageNote(pin(vm), fileName, before?.id ?: after?.id, before, after, ordinal)
        override fun reverse(vm: BlueprintViewModel) = replaceImageNote(pin(vm), fileName, after?.id ?: before?.id, after, before, ordinal)
        override fun intent(kind: Kind) = EffectIntent(page, kind, pinId, fileName, before?.id ?: after?.id)
    }

    private data class ImageShapeEntry(
        override val page: Int,
        val pinId: String,
        val fileName: String,
        val before: Shape?,
        val after: Shape?,
        val id: String,
        val ordinal: Int,
        override val kind: Kind,
    ) : Entry() {
        private fun pin(vm: BlueprintViewModel): PhotoPin? = vm.pagePhotoPins[page]?.firstOrNull { it.id == pinId }
        override fun deepCopy() = copy(before = before?.copyShape(), after = after?.copyShape())
        override fun apply(vm: BlueprintViewModel) = replaceImageShape(pin(vm), fileName, id, before, after, ordinal)
        override fun reverse(vm: BlueprintViewModel) = replaceImageShape(pin(vm), fileName, id, after, before, ordinal)
        override fun intent(kind: Kind) = EffectIntent(page, kind, pinId, fileName, id)
    }

    private data class PageSnapshot(
        val paths: List<DrawnPath>?, val measurements: List<Measurement>?,
        val notes: List<Note>?, val photoPins: List<PhotoPin>?,
        val scale: PageScale?, val scalePresent: Boolean,
        val shapes: List<Shape>?
    ) {
        fun deepCopy() = copy(
            paths = paths?.map { it.copy(points = it.points.map { p -> p.copyPoint() }) },
            measurements = measurements?.map { it.copyMeasurement(it.p1.copyPoint(), it.p2.copyPoint()) },
            notes = notes?.map(Note::copyNote), photoPins = photoPins?.map(PhotoPin::copyPin),
            scale = scale?.copy(), shapes = shapes?.map(Shape::copyShape)
        )
    }

    private data class ClearPageEntry(override val page: Int, val before: PageSnapshot) : Entry() {
        override val kind = Kind.CLEAR
        override fun deepCopy() = copy(before = before.deepCopy())
        override fun apply(vm: BlueprintViewModel) = applySnapshot(vm, page, emptySnapshot(vm, page))
        override fun reverse(vm: BlueprintViewModel) = applySnapshot(vm, page, before)
        override fun intent(kind: Kind) = EffectIntent(page, kind)
    }

    private val undo = mutableMapOf<Int, MutableList<Entry>>()
    private val redo = mutableMapOf<Int, MutableList<Entry>>()
    private val legacyUndoBoundaries = mutableMapOf<Int, Int>()

    fun addPdfNote(page: Int, note: Note): Boolean {
        if (!isSessionActive()) return false
        val list = vm.pageNotes[page] ?: return false
        if (list.any { it === note }) return false
        val entry = PdfNoteEntry(page, null, note.copyNote(), list.size, Kind.ADD)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    fun addPdfPath(page: Int, path: DrawnPath): Boolean {
        if (!isSessionActive()) return false
        val list = vm.pagePaths[page] ?: return false
        val entry = PdfPathEntry(page, null, path.copyPath(), list.size, Kind.ADD)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    fun deletePdfPath(page: Int, path: DrawnPath): Boolean {
        if (!isSessionActive()) return false
        val list = vm.pagePaths[page] ?: return false
        val index = list.indexOfFirst { it === path }
            .takeIf { it >= 0 } ?: list.indexOf(path)
        if (index < 0) return false
        val entry = PdfPathEntry(page, list[index].copyPath(), null, index, Kind.DELETE)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    fun addMeasurement(page: Int, measurement: Measurement): Boolean {
        if (!isSessionActive()) return false
        val list = vm.pageMeasurements[page] ?: return false
        val entry = PdfMeasurementEntry(page, null, measurement.copyMeasurement(measurement.p1.copyPoint(), measurement.p2.copyPoint()), list.size, Kind.ADD)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    fun updateMeasurement(page: Int, current: Measurement, replacement: Measurement, kind: Kind = Kind.UPDATE): Boolean {
        val list = vm.pageMeasurements[page] ?: return false
        val index = list.indexOfFirst { it === current }.takeIf { it >= 0 }
            ?: list.indexOf(current)
        return updateMeasurementAt(page, index, replacement, kind, current)
    }

    /** Updates the selected measurement by its stable ordinal, not equality. */
    fun updateMeasurementAt(
        page: Int,
        index: Int,
        replacement: Measurement,
        kind: Kind = Kind.UPDATE,
        before: Measurement? = null
    ): Boolean {
        if (!isSessionActive() || index < 0) return false
        val list = vm.pageMeasurements[page] ?: return false
        if (index !in list.indices) return false
        val original = before ?: list[index]
        if (original == replacement) return false
        val entry = PdfMeasurementEntry(page, original.copyMeasurement(original.p1.copyPoint(), original.p2.copyPoint()), replacement.copyMeasurement(replacement.p1.copyPoint(), replacement.p2.copyPoint()), index, kind)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    fun moveMeasurement(page: Int, current: Measurement, replacement: Measurement) = updateMeasurement(page, current, replacement, Kind.MOVE)
    fun resizeMeasurement(page: Int, current: Measurement, replacement: Measurement) = updateMeasurement(page, current, replacement, Kind.RESIZE)
    fun deleteMeasurement(page: Int, measurement: Measurement): Boolean {
        if (!isSessionActive()) return false
        val list = vm.pageMeasurements[page] ?: return false
        val index = list.indexOfFirst { it === measurement }.takeIf { it >= 0 }
            ?: list.indexOf(measurement)
        if (index < 0) return false
        val entry = PdfMeasurementEntry(page, list[index].copyMeasurement(list[index].p1.copyPoint(), list[index].p2.copyPoint()), null, index, Kind.DELETE)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    fun addPhotoPin(page: Int, pin: PhotoPin): Boolean {
        if (!isSessionActive()) return false
        val list = vm.pagePhotoPins[page] ?: return false
        if (list.any { it.id == pin.id }) return false
        val entry = PhotoPinEntry(page, null, pin.copyPin(), pin.id, list.size, Kind.ADD)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    fun updatePhotoPin(page: Int, pin: PhotoPin, replacement: PhotoPin, kind: Kind = Kind.UPDATE): Boolean {
        if (!isSessionActive() || pin.id != replacement.id || pin == replacement) return false
        val list = vm.pagePhotoPins[page] ?: return false
        val index = list.indexOfFirst { it.id == pin.id }
        if (index < 0) return false
        val expected = pin.copyPin()
        if (list[index] != expected) return false
        val entry = PhotoPinEntry(page, expected, replacement.copyPin(), pin.id, index, kind)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    fun attachPhoto(page: Int, pin: PhotoPin, fileName: String): Boolean {
        if (!isSessionActive() || fileName.isBlank()) return false
        val replacement = pin.copyPin()
        if (replacement.imageFileNames.contains(fileName)) return false
        replacement.imageFileNames += fileName
        return updatePhotoPin(page, pin, replacement, Kind.UPDATE)
    }

    fun detachPhoto(page: Int, pin: PhotoPin, fileName: String): Boolean {
        if (!isSessionActive()) return false
        val replacement = pin.copyPin()
        if (!replacement.imageFileNames.remove(fileName)) return false
        return updatePhotoPin(page, pin, replacement, Kind.UPDATE)
    }

    fun deletePhotoPin(page: Int, pin: PhotoPin): Boolean {
        if (!isSessionActive()) return false
        val list = vm.pagePhotoPins[page] ?: return false
        val index = list.indexOfFirst { it.id == pin.id }
        if (index < 0) return false
        val expected = pin.copyPin()
        if (list[index] != expected) return false
        val entry = PhotoPinEntry(page, expected, null, pin.id, index, Kind.DELETE)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    fun setScale(page: Int, scale: PageScale?): Boolean {
        if (!isSessionActive()) return false
        val old = vm.pageScales[page]?.copy()
        if (old == scale) return false
        val entry = ScaleEntry(page, old, scale?.copy())
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    fun updatePdfNote(page: Int, current: Note, replacement: Note, kind: Kind = Kind.UPDATE): Boolean {
        if (!isSessionActive()) return false
        val list = vm.pageNotes[page] ?: return false
        val index = list.indexOfFirst { it === current }
        return updatePdfNoteAt(page, index, replacement, kind, current)
    }

    fun updatePdfNoteAt(page: Int, index: Int, replacement: Note, kind: Kind = Kind.UPDATE, before: Note? = null): Boolean {
        if (!isSessionActive()) return false
        val list = vm.pageNotes[page] ?: return false
        if (index < 0) return false
        if (index !in list.indices) return false
        val original = before ?: list[index]
        if (original == replacement) return false
        val entry = PdfNoteEntry(page, original.copyNote(), replacement.copyNote(), index, kind)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    fun deletePdfNote(page: Int, note: Note): Boolean {
        if (!isSessionActive()) return false
        val list = vm.pageNotes[page] ?: return false
        val index = list.indexOfFirst { it === note }
        return deletePdfNoteAt(page, index, note)
    }

    fun deletePdfNoteAt(page: Int, index: Int, note: Note? = null): Boolean {
        if (!isSessionActive()) return false
        val list = vm.pageNotes[page] ?: return false
        if (index < 0) return false
        if (index !in list.indices) return false
        val original = list[index]
        // The ordinal is the identity carried by the selection. Equality is
        // only a detached-value integrity check; it never chooses the index.
        // This permits selections rebuilt from reducer copies while keeping
        // equal-valued notes independently addressable.
        if (note != null && original != note) return false
        val entry = PdfNoteEntry(page, original.copyNote(), null, index, Kind.DELETE)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    fun addPdfShape(page: Int, shape: Shape): Boolean {
        if (!isSessionActive()) return false
        val list = vm.pageShapes[page] ?: return false
        if (list.any { it.id == shape.id }) return false
        val entry = PdfShapeEntry(page, null, shape.copyShape(), shape.id, list.size, Kind.ADD)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    fun updatePdfShape(page: Int, shape: Shape, replacement: Shape, kind: Kind = Kind.UPDATE): Boolean =
        updateShape(page, null, null, shape, replacement, kind)

    fun movePdfShape(page: Int, shape: Shape, replacement: Shape): Boolean = updatePdfShape(page, shape, replacement, Kind.MOVE)
    fun resizePdfShape(page: Int, shape: Shape, replacement: Shape): Boolean = updatePdfShape(page, shape, replacement, Kind.RESIZE)
    fun rotatePdfShape(page: Int, shape: Shape, replacement: Shape): Boolean = updatePdfShape(page, shape, replacement, Kind.ROTATE)

    fun deletePdfShape(page: Int, shape: Shape): Boolean {
        if (!isSessionActive()) return false
        val list = vm.pageShapes[page] ?: return false
        if (list.none { it.id == shape.id }) return false
        val ordinal = list.indexOfFirst { it.id == shape.id }
        // Keep the gesture's captured value as the expected precondition. A
        // lookup by ID alone would allow a delayed delete to remove geometry
        // that was already replaced by a newer action.
        val entry = PdfShapeEntry(page, shape.copyShape(), null, shape.id, ordinal, Kind.DELETE)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    fun addImageNote(page: Int, pinId: String, fileName: String, note: PhotoImageNote): Boolean {
        if (!isSessionActive()) return false
        val pin = findPin(page, pinId) ?: return false
        val list = pin.imageNotes[fileName] ?: mutableListOf()
        if (list.any { it.id == note.id }) return false
        val entry = ImageNoteEntry(page, pinId, fileName, null, note.copyImageNote(), list.size, Kind.ADD)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    fun updateImageNote(page: Int, pinId: String, fileName: String, note: PhotoImageNote, replacement: PhotoImageNote, kind: Kind = Kind.UPDATE): Boolean {
        if (!isSessionActive()) return false
        if (note.id != replacement.id || note == replacement) return false
        val pin = findPin(page, pinId) ?: return false
        val list = pin.imageNotes[fileName] ?: return false
        if (list.none { it.id == note.id }) return false
        val ordinal = list.indexOfFirst { it.id == note.id }
        val entry = ImageNoteEntry(page, pinId, fileName, note.copyImageNote(), replacement.copyImageNote(), ordinal, kind)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    fun moveImageNote(page: Int, pinId: String, fileName: String, note: PhotoImageNote, replacement: PhotoImageNote) = updateImageNote(page, pinId, fileName, note, replacement, Kind.MOVE)
    fun resizeImageNote(page: Int, pinId: String, fileName: String, note: PhotoImageNote, replacement: PhotoImageNote) = updateImageNote(page, pinId, fileName, note, replacement, Kind.RESIZE)
    fun rotateImageNote(page: Int, pinId: String, fileName: String, note: PhotoImageNote, replacement: PhotoImageNote) = updateImageNote(page, pinId, fileName, note, replacement, Kind.ROTATE)

    fun deleteImageNote(page: Int, pinId: String, fileName: String, note: PhotoImageNote): Boolean {
        if (!isSessionActive()) return false
        val pin = findPin(page, pinId) ?: return false
        if (pin.imageNotes[fileName]?.none { it.id == note.id } != false) return false
        val ordinal = pin.imageNotes[fileName]!!.indexOfFirst { it.id == note.id }
        val entry = ImageNoteEntry(page, pinId, fileName, note.copyImageNote(), null, ordinal, Kind.DELETE)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    fun addImageShape(page: Int, pinId: String, fileName: String, shape: Shape): Boolean {
        if (!isSessionActive()) return false
        val pin = findPin(page, pinId) ?: return false
        if (pin.imageShapes[fileName]?.any { it.id == shape.id } == true) return false
        val entry = ImageShapeEntry(page, pinId, fileName, null, shape.copyShape(), shape.id, pin.imageShapes[fileName]?.size ?: 0, Kind.ADD)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    fun updateImageShape(page: Int, pinId: String, fileName: String, shape: Shape, replacement: Shape, kind: Kind = Kind.UPDATE): Boolean =
        updateShape(page, pinId, fileName, shape, replacement, kind)

    fun moveImageShape(page: Int, pinId: String, fileName: String, shape: Shape, replacement: Shape) = updateImageShape(page, pinId, fileName, shape, replacement, Kind.MOVE)
    fun resizeImageShape(page: Int, pinId: String, fileName: String, shape: Shape, replacement: Shape) = updateImageShape(page, pinId, fileName, shape, replacement, Kind.RESIZE)
    fun rotateImageShape(page: Int, pinId: String, fileName: String, shape: Shape, replacement: Shape) = updateImageShape(page, pinId, fileName, shape, replacement, Kind.ROTATE)

    fun deleteImageShape(page: Int, pinId: String, fileName: String, shape: Shape): Boolean {
        if (!isSessionActive()) return false
        val pin = findPin(page, pinId) ?: return false
        if (pin.imageShapes[fileName]?.none { it.id == shape.id } != false) return false
        val ordinal = pin.imageShapes[fileName]!!.indexOfFirst { it.id == shape.id }
        val entry = ImageShapeEntry(page, pinId, fileName, shape.copyShape(), null, shape.id, ordinal, Kind.DELETE)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    /** Clears every persisted domain on one page as one undoable reducer action. */
    fun clearPage(page: Int): Boolean {
        if (!isSessionActive()) return false
        val before = captureSnapshot(vm, page)
        if (before == emptySnapshot(vm, page)) return false
        val entry = ClearPageEntry(page, before)
        if (!entry.apply(vm)) return false
        // Legacy actions must not replay over a reducer-owned clear, while the
        // reducer's own history remains available for undo/redo.
        vm.clearLegacyPageHistory(page)
        legacyUndoBoundaries[page] = 0
        commit(entry)
        return true
    }

    /** Legacy mutations form an ordering boundary with reducer history. */
    fun notifyLegacyMutation(page: Int) {
        if (isSessionActive()) legacyUndoBoundaries[page] = (legacyUndoBoundaries[page] ?: 0) + 1
    }

    fun undo(page: Int): Boolean {
        if (!isSessionActive()) return false
        val pending = legacyUndoBoundaries[page] ?: 0
        if (pending > 0) { legacyUndoBoundaries[page] = pending - 1; return false }
        return moveHistory(page, undo, redo, true)
    }
    fun redo(page: Int): Boolean = isSessionActive() && moveHistory(page, redo, undo, false)
    /** Admission check for UI fallbacks: stale closures must not reach legacy history. */
    fun acceptsCurrentSession(): Boolean = isSessionActive()
    fun canUndo(page: Int) = !undo[page].isNullOrEmpty()
    fun canRedo(page: Int) = !redo[page].isNullOrEmpty()
    fun clear() {
        undo.clear()
        redo.clear()
    }

    private fun updateShape(page: Int, pinId: String?, fileName: String?, shape: Shape, replacement: Shape, kind: Kind): Boolean {
        if (!isSessionActive()) return false
        if (shape.id != replacement.id || shape == replacement) return false
        val current = if (pinId == null) vm.pageShapes[page]?.firstOrNull { it.id == shape.id }
        else findPin(page, pinId)?.imageShapes?.get(fileName)?.firstOrNull { it.id == shape.id }
        if (current == null) return false
        // The gesture captured a prior value. Reject a late update if another
        // action has already changed this shape; stable IDs alone would let a
        // stale closure overwrite the newer geometry.
        if (current != shape) return false
        val ordinal = if (pinId == null) vm.pageShapes[page]!!.indexOfFirst { it.id == shape.id }
        else findPin(page, pinId)!!.imageShapes[fileName]!!.indexOfFirst { it.id == shape.id }
        val entry = if (pinId == null) PdfShapeEntry(page, current.copyShape(), replacement.copyShape(), shape.id, ordinal, kind)
        else ImageShapeEntry(page, pinId, fileName!!, current.copyShape(), replacement.copyShape(), shape.id, ordinal, kind)
        if (!entry.apply(vm)) return false
        commit(entry)
        return true
    }

    private fun commit(entry: Entry) {
        undo.getOrPut(entry.page) { mutableListOf() }.add(entry.deepCopy())
        redo[entry.page]?.clear()
        effectSink(entry.intent(entry.kind))
    }

    private fun moveHistory(page: Int, source: MutableMap<Int, MutableList<Entry>>, destination: MutableMap<Int, MutableList<Entry>>, reverse: Boolean): Boolean {
        val entry = source[page]?.removeLastOrNull() ?: return false
        val changed = if (reverse) entry.reverse(vm) else entry.apply(vm)
        if (!changed) {
            source.getOrPut(page) { mutableListOf() }.add(entry)
            return false
        }
        destination.getOrPut(page) { mutableListOf() }.add(entry.deepCopy())
        effectSink(entry.intent(if (reverse) Kind.UNDO else Kind.REDO))
        return true
    }

    private fun findPin(page: Int, pinId: String): PhotoPin? = vm.pagePhotoPins[page]?.firstOrNull { it.id == pinId }

    private fun isSessionActive(): Boolean =
        sessionActivePredicate() && currentSessionKey() == sessionKey

    companion object {
        private fun captureSnapshot(vm: BlueprintViewModel, page: Int) = PageSnapshot(
            vm.pagePaths[page]?.map { it.copy(points = it.points.map { p -> p.copyPoint() }) },
            vm.pageMeasurements[page]?.map { it.copyMeasurement(it.p1.copyPoint(), it.p2.copyPoint()) },
            vm.pageNotes[page]?.map(Note::copyNote), vm.pagePhotoPins[page]?.map(PhotoPin::copyPin),
            vm.pageScales[page]?.copy(), vm.pageScales.containsKey(page), vm.pageShapes[page]?.map(Shape::copyShape)
        )
        private fun DrawnPath.copyPath() = copy(points = points.map(Point::copyPoint))
        private fun applyPath(vm: BlueprintViewModel, page: Int, index: Int, expected: DrawnPath?, value: DrawnPath?): Boolean {
            val list = vm.pagePaths[page] ?: return false
            if (value == null) {
                if (index !in list.indices || (expected != null && list[index] != expected)) return false
                list.removeAt(index)
            } else if (expected == null) {
                list.add(index.coerceIn(0, list.size), value.copyPath())
            } else {
                if (index !in list.indices || list[index] != expected) return false
                list[index] = value.copyPath()
            }
            return true
        }
        private fun applyMeasurement(vm: BlueprintViewModel, page: Int, index: Int, expected: Measurement?, value: Measurement?): Boolean {
            val list = vm.pageMeasurements[page] ?: return false
            if (value == null) {
                if (index !in list.indices || (expected != null && list[index] != expected)) return false
                list.removeAt(index)
            } else if (expected == null) {
                list.add(index.coerceIn(0, list.size), value.copyMeasurement(value.p1.copyPoint(), value.p2.copyPoint()))
            } else {
                if (index !in list.indices || list[index] != expected) return false
                list[index] = value.copyMeasurement(value.p1.copyPoint(), value.p2.copyPoint())
            }
            return true
        }
        private fun applyPhotoPin(vm: BlueprintViewModel, page: Int, id: String, index: Int, expected: PhotoPin?, value: PhotoPin?): Boolean {
            val list = vm.pagePhotoPins[page] ?: return false
            val current = list.indexOfFirst { it.id == id }
            if (value == null) {
                if (current < 0 || (expected != null && list[current] != expected)) return false
                list.removeAt(current)
            } else if (expected == null) {
                if (current >= 0) return false
                list.add(index.coerceIn(0, list.size), value.copyPin())
            } else {
                if (current < 0 || list[current] != expected) return false
                // Preserve the live pin object because image-surface selection
                // holds that reference while nested maps are being edited.
                overwritePhotoPin(list[current], value)
            }
            return true
        }
        private fun overwritePhotoPin(target: PhotoPin, source: PhotoPin) {
            target.x = source.x
            target.y = source.y
            target.imageFileNames.clear()
            target.imageFileNames.addAll(source.imageFileNames)
            target.imageNotes.clear()
            source.imageNotes.forEach { (file, notes) ->
                target.imageNotes[file] = notes.map { it.copyImageNote() }.toMutableList()
            }
            target.imageShapes.clear()
            source.imageShapes.forEach { (file, shapes) ->
                target.imageShapes[file] = shapes.map { it.copyShape() }.toMutableList()
            }
        }
        private fun applyScale(vm: BlueprintViewModel, page: Int, expected: PageScale?, value: PageScale?): Boolean {
            if (expected == null && value == null) return false
            if (expected == null) {
                if (vm.pageScales.containsKey(page)) return false
            } else if (vm.pageScales[page] != expected) return false
            if (value == null) vm.pageScales.remove(page) else vm.pageScales[page] = value.copy()
            return true
        }
        private fun emptySnapshot(vm: BlueprintViewModel, page: Int) = PageSnapshot(
            if (vm.pagePaths.containsKey(page)) emptyList() else null,
            if (vm.pageMeasurements.containsKey(page)) emptyList() else null,
            if (vm.pageNotes.containsKey(page)) emptyList() else null,
            if (vm.pagePhotoPins.containsKey(page)) emptyList() else null,
            null, false, if (vm.pageShapes.containsKey(page)) emptyList() else null
        )
        private fun applySnapshot(vm: BlueprintViewModel, page: Int, s: PageSnapshot): Boolean {
            fun <T> replace(map: MutableMap<Int, androidx.compose.runtime.snapshots.SnapshotStateList<T>>, value: List<T>?) {
                if (value == null) map.remove(page) else map[page] = mutableStateListOf<T>().also { it.addAll(value) }
            }
            replace(vm.pagePaths, s.paths); replace(vm.pageMeasurements, s.measurements)
            replace(vm.pageNotes, s.notes); replace(vm.pagePhotoPins, s.photoPins); replace(vm.pageShapes, s.shapes)
            if (s.scalePresent) vm.pageScales[page] = s.scale!!.copy() else vm.pageScales.remove(page)
            return true
        }
        private fun applyPdfNote(vm: BlueprintViewModel, page: Int, index: Int, expected: Note?, value: Note?): Boolean {
            val list = vm.pageNotes[page] ?: return false
            if (expected == null && value != null) {
                if (list.any { it === value }) return false
                list.add(index.coerceIn(0, list.size), value)
                return true
            }
            if (expected != null && value == null) {
                if (index !in list.indices || list[index] != expected) return false
                list.removeAt(index)
                return true
            }
            if (expected == null || value == null || index !in list.indices) return false
            if (list[index] != expected) return false
            list[index] = value
            return true
        }
        private fun replaceById(list: MutableList<Shape>?, id: String, expected: Shape?, value: Shape?, ordinal: Int): Boolean {
            if (list == null) return false
            val index = list.indexOfFirst { it.id == id }
            if (expected == null && value != null) {
                if (index >= 0) return false
                list.add(ordinal.coerceIn(0, list.size), value.copyShape())
                return true
            }
            if (index < 0 || expected == null || list[index] != expected) return false
            if (value == null) list.removeAt(index) else list[index] = value.copyShape()
            return true
        }
        private fun replaceImageNote(pin: PhotoPin?, fileName: String, id: String?, expected: PhotoImageNote?, value: PhotoImageNote?, ordinal: Int): Boolean {
            if (pin == null || id == null) return false
            val list = pin.imageNotes[fileName]
            if (expected == null && value != null) {
                if (list?.any { it.id == id } == true) return false
                pin.imageNotes.getOrPut(fileName) { mutableListOf() }
                    .add(ordinal.coerceIn(0, pin.imageNotes[fileName]!!.size), value.copyImageNote())
                return true
            }
            val i = list?.indexOfFirst { it.id == id } ?: -1
            if (i < 0 || expected == null || list!![i] != expected) return false
            if (value == null) list.removeAt(i) else list[i] = value.copyImageNote()
            return true
        }
        private fun replaceImageShape(pin: PhotoPin?, fileName: String, id: String, expected: Shape?, value: Shape?, ordinal: Int): Boolean {
            if (pin == null) return false
            val list = pin.imageShapes[fileName]
            if (expected == null && value != null) {
                if (list?.any { it.id == id } == true) return false
                pin.imageShapes.getOrPut(fileName) { mutableListOf() }
                    .add(ordinal.coerceIn(0, pin.imageShapes[fileName]!!.size), value.copyShape())
                return true
            }
            val i = list?.indexOfFirst { it.id == id } ?: -1
            if (i < 0 || expected == null || list!![i] != expected) return false
            if (value == null) list.removeAt(i) else list[i] = value.copyShape()
            return true
        }
    }
}
