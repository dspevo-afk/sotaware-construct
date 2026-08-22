package com.example.myapplication.stage1

/**
 * The schema number for the first canonical document snapshot representation.
 *
 * This is deliberately separate from [DocumentSnapshotV1.snapshotRevision]. The
 * schema number changes when the shape of the snapshot changes; the revision is
 * supplied by the current document owner to identify a logical capture. Stage 1
 * does not allocate synchronization generations or compare revisions.
 */
const val DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION: Int = 1

/** A deterministic value for a capture whose owner has no logical revision yet. */
const val INITIAL_DOCUMENT_SNAPSHOT_V1_REVISION: Long = 0L

/**
 * Current source identity metadata available before the Stage 2 DocumentId work.
 *
 * [sourceUri] and [displayName] describe the open source as it exists today. The
 * optional provider metadata gives Stage 2 a place to carry provider-specific
 * information without moving the annotation/page model. It is not an app-
 * generated UUID and does not establish the final Stage 2 identity architecture.
 */
data class DocumentSourceIdentityV1(
    val sourceUri: String,
    val displayName: String? = null,
    val providerMetadata: Map<String, String> = emptyMap()
) {
    init {
        require(sourceUri.isNotBlank()) { "sourceUri must not be blank" }
    }
}

/**
 * Canonical typed representation of one logical document for Stage 1.
 *
 * The mapper constructs this object with defensive, read-only collections. The
 * legacy runtime models remain below this boundary and are never stored inside
 * the snapshot, because several of them contain mutable fields and collections.
 */
data class DocumentSnapshotV1(
    val schemaVersion: Int,
    val snapshotRevision: Long,
    val source: DocumentSourceIdentityV1,
    val pages: Map<Int, PageSnapshotV1>
) {
    init {
        require(schemaVersion == DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION) {
            "Unsupported document snapshot schema: $schemaVersion"
        }
        require(snapshotRevision >= INITIAL_DOCUMENT_SNAPSHOT_V1_REVISION) {
            "snapshotRevision must be non-negative"
        }
        require(pages.keys.all { it >= 0 }) { "page indices must be non-negative" }
    }
}

/** All persisted domains belonging to one page. */
data class PageSnapshotV1(
    val paths: List<DrawnPathSnapshotV1> = emptyList(),
    val measurements: List<MeasurementSnapshotV1> = emptyList(),
    val notes: List<NoteSnapshotV1> = emptyList(),
    val photoPins: List<PhotoPinSnapshotV1> = emptyList(),
    val scale: PageScaleSnapshotV1? = null,
    val shapes: List<ShapeSnapshotV1> = emptyList()
)

data class PointSnapshotV1(
    val x: Float,
    val y: Float
)

data class DrawnPathSnapshotV1(
    val points: List<PointSnapshotV1>,
    val colorArgb: Int,
    val strokeWidth: Float,
    val isHighlighter: Boolean
)

data class MeasurementSnapshotV1(
    val p1: PointSnapshotV1,
    val p2: PointSnapshotV1,
    val text: String
)

data class NoteSnapshotV1(
    val x: Float,
    val y: Float,
    val text: String,
    val fontSize: Float,
    val isBold: Boolean,
    val rotation: Float
)

data class PageScaleSnapshotV1(
    val pixelsPerFoot: Float
)

/** The versioned, typed equivalent of the current legacy ShapeType enum. */
enum class SnapshotShapeTypeV1 {
    RECTANGLE,
    CIRCLE,
    ARROW,
    CLOUD
}

data class ShapeSnapshotV1(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
    val type: SnapshotShapeTypeV1,
    val colorArgb: Int,
    val strokeWidth: Float,
    val isFilled: Boolean,
    val strokeWidthRatio: Float,
    val widthRatio: Float,
    val heightRatio: Float,
    val id: String
)

/**
 * A page photo pin and all annotations keyed by each referenced image filename.
 * The current app's persisted photo metadata is the pin position/ID and the
 * referenced image filename list; richer file metadata can be added later as a
 * versioned field without changing the page/domain topology.
 */
data class PhotoPinSnapshotV1(
    val x: Float,
    val y: Float,
    val id: String,
    val imageFileNames: List<String>,
    val imageNotes: Map<String, List<PhotoImageNoteSnapshotV1>>,
    val imageShapes: Map<String, List<ShapeSnapshotV1>>
)

data class PhotoImageNoteSnapshotV1(
    val x: Float,
    val y: Float,
    val text: String,
    val fontSize: Float,
    val isBold: Boolean,
    val rotation: Float,
    val fontSizeRatio: Float,
    val id: String
)
