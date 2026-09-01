package com.example.myapplication.stage1

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.DrawnPath
import com.example.myapplication.Measurement
import com.example.myapplication.Note
import com.example.myapplication.PageData
import com.example.myapplication.PageScale
import com.example.myapplication.PhotoImageNote
import com.example.myapplication.PhotoPin
import com.example.myapplication.Point
import com.example.myapplication.Shape
import com.example.myapplication.ShapeType
import java.util.Collections
import java.util.LinkedHashMap

/**
 * The only Stage 1 state-capture authority. It copies every persisted domain
 * from the current ViewModel into snapshot-owned scalar objects and collections.
 *
 * [snapshotRevision] is caller-supplied document metadata. Stage 1 intentionally
 * does not derive it from wall-clock time and does not implement Stage 4 sync
 * generations, conflict cursors, or revision comparisons.
 */
fun snapshotFromState(
    vm: BlueprintViewModel,
    source: DocumentSourceIdentityV1,
    snapshotRevision: Long = INITIAL_DOCUMENT_SNAPSHOT_V1_REVISION
): DocumentSnapshotV1 {
    require(snapshotRevision >= INITIAL_DOCUMENT_SNAPSHOT_V1_REVISION) {
        "snapshotRevision must be non-negative"
    }

    val pageIndices = (
        vm.pagePaths.keys +
            vm.pageMeasurements.keys +
            vm.pageNotes.keys +
            vm.pagePhotoPins.keys +
            vm.pageShapes.keys +
            vm.pageScales.keys
        ).toSet().sorted()

    val pages = LinkedHashMap<Int, PageSnapshotV1>(pageIndices.size)
    pageIndices.forEach { pageIndex ->
        pages[pageIndex] = PageSnapshotV1(
            paths = immutableList(vm.pagePaths[pageIndex].orEmpty().map { it.toSnapshot() }),
            measurements = immutableList(vm.pageMeasurements[pageIndex].orEmpty().map { it.toSnapshot() }),
            notes = immutableList(vm.pageNotes[pageIndex].orEmpty().map { it.toSnapshot() }),
            photoPins = immutableList(vm.pagePhotoPins[pageIndex].orEmpty().map { it.toSnapshot() }),
            scale = vm.pageScales[pageIndex]?.toSnapshot(),
            shapes = immutableList(vm.pageShapes[pageIndex].orEmpty().map { it.toSnapshot() })
        )
    }

    return DocumentSnapshotV1(
        schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
        snapshotRevision = snapshotRevision,
        source = source.copy(providerMetadata = immutableMap(source.providerMetadata)),
        pages = immutableMap(pages)
    )
}

private data class MaterializedPage(
    val paths: List<DrawnPath>,
    val measurements: List<Measurement>,
    val notes: List<Note>,
    val photoPins: List<PhotoPin>,
    val scale: PageScale?,
    val shapes: List<Shape>
)

private fun PageSnapshotV1.materialize(): MaterializedPage = MaterializedPage(
    paths = paths.map { it.toLegacy() }.toList(),
    measurements = measurements.map { it.toLegacy() }.toList(),
    notes = notes.map { it.toLegacy() }.toList(),
    photoPins = photoPins.map { it.toLegacy() }.toList(),
    scale = scale?.toLegacy(),
    shapes = shapes.map { it.toLegacy() }.toList()
)

/**
 * Converts and validates the complete incoming snapshot before any live state
 * is changed. This preserves the existing document if malformed or externally
 * mutated snapshot data fails during materialization.
 */
private fun materializeSnapshot(snapshot: DocumentSnapshotV1): Map<Int, MaterializedPage> {
    require(snapshot.schemaVersion == DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION) {
        "Unsupported document snapshot schema: ${snapshot.schemaVersion}"
    }
    require(snapshot.snapshotRevision >= INITIAL_DOCUMENT_SNAPSHOT_V1_REVISION) {
        "snapshotRevision must be non-negative"
    }

    val materialized = LinkedHashMap<Int, MaterializedPage>(snapshot.pages.size)
    snapshot.pages.toSortedMap().forEach { (pageIndex, page) ->
        require(pageIndex >= 0) { "page indices must be non-negative" }
        materialized[pageIndex] = page.materialize()
    }
    return materialized
}

/**
 * Replaces all persisted ViewModel maps with [snapshot]. Absent pages and
 * absent/empty domains cannot leave stale local state behind.
 */
fun applySnapshotReplace(
    snapshot: DocumentSnapshotV1,
    vm: BlueprintViewModel
) {
    // Materialize first: replacement is atomic with respect to conversion
    // failures even though Stage 2 owns durable/transactional persistence.
    val materializedPages = materializeSnapshot(snapshot)

    vm.pagePaths.clear()
    vm.pageMeasurements.clear()
    vm.pageNotes.clear()
    vm.pagePhotoPins.clear()
    vm.pageShapes.clear()
    vm.pageScales.clear()

    // Undo/redo actions point at mutable legacy objects and are not persisted
    // snapshot domains. They cannot safely survive replacement of those objects.
    vm.pageHistory.clear()
    vm.pageRedoStack.clear()
    vm.clearThumbnailCache()
    vm.pageHighlights.clear()
    vm.pageSearchTerms.clear()

    materializedPages.forEach { (pageIndex, page) ->
        // Keep an explicit page key even when a particular domain is empty. This
        // preserves page existence for pages that carry only scale or one other
        // domain, while the empty snapshot still represents an empty document.
        vm.pagePaths[pageIndex] = mutableStateListOf<DrawnPath>().also { it.addAll(page.paths) }
        vm.pageMeasurements[pageIndex] = mutableStateListOf<Measurement>().also { it.addAll(page.measurements) }
        vm.pageNotes[pageIndex] = mutableStateListOf<Note>().also { it.addAll(page.notes) }
        vm.pagePhotoPins[pageIndex] = mutableStateListOf<PhotoPin>().also { it.addAll(page.photoPins) }
        vm.pageShapes[pageIndex] = mutableStateListOf<Shape>().also { it.addAll(page.shapes) }
        page.scale?.let { vm.pageScales[pageIndex] = it }
    }
}

/**
 * Temporary compatibility adapter for legacy Drive callers that still accept
 * Map<Int, PageData>. It consumes only a canonical snapshot and performs fresh
 * legacy-object construction; it does not capture state from a ViewModel.
 */
fun snapshotToLegacyPageData(snapshot: DocumentSnapshotV1): Map<Int, PageData> {
    val result = LinkedHashMap<Int, PageData>(snapshot.pages.size)
    snapshot.pages.toSortedMap().forEach { (pageIndex, page) ->
        result[pageIndex] = PageData(
            paths = page.paths.map { it.toLegacy() },
            measurements = page.measurements.map { it.toLegacy() },
            notes = page.notes.map { it.toLegacy() },
            photoPins = page.photoPins.map { it.toLegacy() },
            scale = page.scale?.toLegacy(),
            shapes = page.shapes.map { it.toLegacy() }
        )
    }
    return result
}

/**
 * Temporary compatibility adapter for legacy Drive payloads entering the
 * canonical state model. This maps an external payload; it never reads live
 * ViewModel state and therefore is not a second capture authority.
 */
fun snapshotFromLegacyPageData(
    pageData: Map<Int, PageData>,
    source: DocumentSourceIdentityV1,
    snapshotRevision: Long = INITIAL_DOCUMENT_SNAPSHOT_V1_REVISION
): DocumentSnapshotV1 {
    val pages = LinkedHashMap<Int, PageSnapshotV1>(pageData.size)
    pageData.toSortedMap().forEach { (pageIndex, page) ->
        pages[pageIndex] = page.toSnapshot()
    }
    return DocumentSnapshotV1(
        schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
        snapshotRevision = snapshotRevision,
        source = source.copy(providerMetadata = immutableMap(source.providerMetadata)),
        pages = immutableMap(pages)
    )
}

private fun PageData.toSnapshot(): PageSnapshotV1 = PageSnapshotV1(
    paths = immutableList(paths.map { it.toSnapshot() }),
    measurements = immutableList(measurements.map { it.toSnapshot() }),
    notes = immutableList(notes.map { it.toSnapshot() }),
    photoPins = immutableList(photoPins.map { it.toSnapshot() }),
    scale = scale?.toSnapshot(),
    shapes = immutableList(shapes.map { it.toSnapshot() })
)

/**
 * Stage 0's seam is retained only as a thin compatibility adapter. All
 * complete live-state capture begins at snapshotFromState().
 */
fun buildPageDataForSync(
    vm: BlueprintViewModel,
    source: DocumentSourceIdentityV1
): Map<Int, PageData> = snapshotToLegacyPageData(snapshotFromState(vm, source))

/**
 * Creates the Stage 1 source metadata available for the currently open URI.
 * Stage 2 may add an app-generated DocumentId without changing page/domain
 * topology.
 */
fun documentSourceIdentityForSnapshot(uri: Uri, displayName: String): DocumentSourceIdentityV1 =
    DocumentSourceIdentityV1(
        sourceUri = uri.toString(),
        displayName = displayName,
        providerMetadata = uri.authority?.let { mapOf("authority" to it) } ?: emptyMap()
    )

private fun DrawnPath.toSnapshot(): DrawnPathSnapshotV1 = DrawnPathSnapshotV1(
    points = immutableList(points.map { it.toSnapshot() }),
    colorArgb = colorArgb,
    strokeWidth = strokeWidth,
    isHighlighter = isHighlighter
)

private fun Measurement.toSnapshot(): MeasurementSnapshotV1 = MeasurementSnapshotV1(
    p1 = p1.toSnapshot(),
    p2 = p2.toSnapshot(),
    text = text
)

private fun Note.toSnapshot(): NoteSnapshotV1 = NoteSnapshotV1(
    x = x,
    y = y,
    text = text,
    fontSize = fontSize,
    isBold = isBold,
    rotation = rotation
)

private fun PageScale.toSnapshot(): PageScaleSnapshotV1 = PageScaleSnapshotV1(
    pixelsPerFoot = pixelsPerFoot
)

private fun PhotoPin.toSnapshot(): PhotoPinSnapshotV1 = PhotoPinSnapshotV1(
    x = x,
    y = y,
    id = id,
    imageFileNames = immutableList(imageFileNames),
    imageNotes = immutableMap(imageNotes.mapValues { (_, notes) ->
        immutableList(notes.map { it.toSnapshot() })
    }),
    imageShapes = immutableMap(imageShapes.mapValues { (_, shapes) ->
        immutableList(shapes.map { it.toSnapshot() })
    })
)

private fun PhotoImageNote.toSnapshot(): PhotoImageNoteSnapshotV1 = PhotoImageNoteSnapshotV1(
    x = x,
    y = y,
    text = text,
    fontSize = fontSize,
    isBold = isBold,
    rotation = rotation,
    fontSizeRatio = fontSizeRatio,
    id = id
)

private fun Shape.toSnapshot(): ShapeSnapshotV1 = ShapeSnapshotV1(
    x = x,
    y = y,
    width = width,
    height = height,
    rotation = rotation,
    type = type.toSnapshotType(),
    colorArgb = colorArgb,
    strokeWidth = strokeWidth,
    isFilled = isFilled,
    strokeWidthRatio = strokeWidthRatio,
    widthRatio = widthRatio,
    heightRatio = heightRatio,
    id = id
)

private fun Point.toSnapshot(): PointSnapshotV1 = PointSnapshotV1(x = x, y = y)

private fun PageScaleSnapshotV1.toLegacy(): PageScale = PageScale(pixelsPerFoot)

private fun PointSnapshotV1.toLegacy(): Point = Point(x = x, y = y)

private fun DrawnPathSnapshotV1.toLegacy(): DrawnPath = DrawnPath(
    points = points.map { it.toLegacy() },
    colorArgb = colorArgb,
    strokeWidth = strokeWidth,
    isHighlighter = isHighlighter
)

private fun MeasurementSnapshotV1.toLegacy(): Measurement = Measurement(
    p1 = p1.toLegacy(),
    p2 = p2.toLegacy(),
    text = text
)

private fun NoteSnapshotV1.toLegacy(): Note = Note(
    x = x,
    y = y,
    text = text,
    fontSize = fontSize,
    isBold = isBold,
    rotation = rotation
)

private fun PhotoImageNoteSnapshotV1.toLegacy(): PhotoImageNote = PhotoImageNote(
    x = x,
    y = y,
    text = text,
    fontSize = fontSize,
    isBold = isBold,
    rotation = rotation,
    fontSizeRatio = fontSizeRatio,
    id = id
)

private fun PhotoPinSnapshotV1.toLegacy(): PhotoPin = PhotoPin(
    x = x,
    y = y,
    id = id,
    imageFileNames = imageFileNames.toMutableList(),
    imageNotes = imageNotes.mapValues { (_, notes) ->
        notes.map { it.toLegacy() }.toMutableList()
    }.toMutableMap(),
    imageShapes = imageShapes.mapValues { (_, shapes) ->
        shapes.map { it.toLegacy() }.toMutableList()
    }.toMutableMap()
)

private fun ShapeSnapshotV1.toLegacy(): Shape = Shape(
    x = x,
    y = y,
    width = width,
    height = height,
    rotation = rotation,
    type = type.toLegacyType(),
    colorArgb = colorArgb,
    strokeWidth = strokeWidth,
    isFilled = isFilled,
    strokeWidthRatio = strokeWidthRatio,
    widthRatio = widthRatio,
    heightRatio = heightRatio,
    id = id
)

private fun ShapeType.toSnapshotType(): SnapshotShapeTypeV1 = when (this) {
    ShapeType.RECTANGLE -> SnapshotShapeTypeV1.RECTANGLE
    ShapeType.CIRCLE -> SnapshotShapeTypeV1.CIRCLE
    ShapeType.ARROW -> SnapshotShapeTypeV1.ARROW
    ShapeType.CLOUD -> SnapshotShapeTypeV1.CLOUD
}

private fun SnapshotShapeTypeV1.toLegacyType(): ShapeType = when (this) {
    SnapshotShapeTypeV1.RECTANGLE -> ShapeType.RECTANGLE
    SnapshotShapeTypeV1.CIRCLE -> ShapeType.CIRCLE
    SnapshotShapeTypeV1.ARROW -> ShapeType.ARROW
    SnapshotShapeTypeV1.CLOUD -> ShapeType.CLOUD
}

private fun <T> immutableList(values: Iterable<T>): List<T> =
    Collections.unmodifiableList(values.toList())

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
