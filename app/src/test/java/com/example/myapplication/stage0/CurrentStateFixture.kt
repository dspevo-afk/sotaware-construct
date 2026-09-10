package com.example.myapplication.stage0

import androidx.compose.runtime.mutableStateListOf
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.DrawnPath
import com.example.myapplication.Measurement
import com.example.myapplication.Note
import com.example.myapplication.PageScale
import com.example.myapplication.PhotoImageNote
import com.example.myapplication.PhotoPin
import com.example.myapplication.Point
import com.example.myapplication.Shape
import com.example.myapplication.ShapeType
import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.DrawnPathSnapshotV1
import com.example.myapplication.stage1.MeasurementSnapshotV1
import com.example.myapplication.stage1.NoteSnapshotV1
import com.example.myapplication.stage1.PageScaleSnapshotV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PhotoImageNoteSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage1.PointSnapshotV1
import com.example.myapplication.stage1.ShapeSnapshotV1
import com.example.myapplication.stage1.SnapshotShapeTypeV1

/**
 * Deterministic current-format state used by the stage0-3 JVM tests.
 * Coordinates are normalized to the visible surface, calibration is in PDF
 * source points per foot, and every annotation has an explicit stable ID.
 */
internal object CurrentStateFixture {
    const val PHOTO_ONE = "field-photo-001.jpg"
    const val PHOTO_TWO = "field-photo-002.jpg"

    const val PATH_ID = "path-current-001"
    const val MEASUREMENT_ID = "measurement-current-001"
    const val NOTE_ID = "note-current-001"
    const val PHOTO_PIN_ID = "photo-pin-current-001"
    const val PAGE_SHAPE_ID = "page-shape-current-001"
    const val IMAGE_NOTE_ID = "image-note-current-001"
    const val IMAGE_SHAPE_ID = "image-shape-current-001"

    fun source(uri: String = "content://documents/current-plan.pdf") =
        DocumentSourceIdentityV1(
            sourceUri = uri,
            displayName = "current-plan.pdf",
            providerMetadata = mapOf("authority" to "com.example.documents")
        )

    /** Runtime state used to exercise the singular ViewModel capture owner. */
    fun viewModel(): BlueprintViewModel = BlueprintViewModel().also { vm ->
        vm.pagePaths[0] = mutableStateListOf(path())
        vm.pageMeasurements[0] = mutableStateListOf(measurement())
        vm.pageNotes[0] = mutableStateListOf(note())
        vm.pagePhotoPins[0] = mutableStateListOf(photoPin())
        vm.pageShapes[0] = mutableStateListOf(pageShape())
        vm.pageScales[0] = PageScale(42.75f)
        vm.pageScales[2] = PageScale(18.5f)
    }

    fun photoPin(): PhotoPin = PhotoPin(
        x = 0.62f,
        y = 0.73f,
        id = PHOTO_PIN_ID,
        imageFileNames = mutableListOf(PHOTO_ONE, PHOTO_TWO),
        imageNotes = mutableMapOf(
            PHOTO_ONE to mutableListOf(
                PhotoImageNote(
                    x = 0.18f,
                    y = 0.29f,
                    text = "IMAGE NOTE WITH METADATA",
                    isBold = true,
                    rotation = 33.0f,
                    fontSizeRatio = 0.031f,
                    id = IMAGE_NOTE_ID
                )
            )
        ),
        imageShapes = mutableMapOf(PHOTO_ONE to mutableListOf(imageShape()))
    )

    private fun path() = DrawnPath(
        points = listOf(
            Point(0.025f, 0.0625f),
            Point(0.5f, 0.5f),
            Point(0.875f, 0.9375f)
        ),
        colorArgb = -16711936,
        isHighlighter = true,
        strokeWidthRatio = 0.0125f,
        id = PATH_ID
    )

    private fun measurement() = Measurement(
        p1 = Point(0.2f, 0.3f),
        p2 = Point(0.8f, 0.9f),
        text = "14' 6.25\"",
        id = MEASUREMENT_ID
    )

    private fun note() = Note(
        x = 0.31f,
        y = 0.47f,
        text = "CURRENT PAGE NOTE",
        isBold = true,
        rotation = -12.0f,
        fontSizeRatio = 0.028f,
        id = NOTE_ID
    )

    /** Explicit schema-2 DTO fixture; tests should prefer this over adapters. */
    fun fullyPopulatedSnapshot(
        source: DocumentSourceIdentityV1 = source(),
        snapshotRevision: Long = 0L
    ): DocumentSnapshotV1 = DocumentSnapshotV1(
        schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
        snapshotRevision = snapshotRevision,
        source = source,
        pages = mapOf(
            0 to PageSnapshotV1(
                paths = listOf(
                    DrawnPathSnapshotV1(
                        points = listOf(
                            PointSnapshotV1(0.025f, 0.0625f),
                            PointSnapshotV1(0.5f, 0.5f),
                            PointSnapshotV1(0.875f, 0.9375f)
                        ),
                        colorArgb = -16711936,
                        isHighlighter = true,
                        strokeWidthRatio = 0.0125f,
                        id = PATH_ID
                    )
                ),
                measurements = listOf(
                    MeasurementSnapshotV1(
                        p1 = PointSnapshotV1(0.2f, 0.3f),
                        p2 = PointSnapshotV1(0.8f, 0.9f),
                        text = "14' 6.25\"",
                        id = MEASUREMENT_ID
                    )
                ),
                notes = listOf(
                    NoteSnapshotV1(
                        x = 0.31f,
                        y = 0.47f,
                        text = "CURRENT PAGE NOTE",
                        isBold = true,
                        rotation = -12.0f,
                        fontSizeRatio = 0.028f,
                        id = NOTE_ID
                    )
                ),
                photoPins = listOf(
                    PhotoPinSnapshotV1(
                        x = 0.62f,
                        y = 0.73f,
                        id = PHOTO_PIN_ID,
                        imageFileNames = listOf(PHOTO_ONE, PHOTO_TWO),
                        imageNotes = mapOf(
                            PHOTO_ONE to listOf(
                                PhotoImageNoteSnapshotV1(
                                    x = 0.18f,
                                    y = 0.29f,
                                    text = "IMAGE NOTE WITH METADATA",
                                    isBold = true,
                                    rotation = 33.0f,
                                    fontSizeRatio = 0.031f,
                                    id = IMAGE_NOTE_ID
                                )
                            )
                        ),
                        imageShapes = mapOf(
                            PHOTO_ONE to listOf(imageShapeSnapshot())
                        )
                    )
                ),
                scale = PageScaleSnapshotV1(pointsPerFoot = 42.75f),
                shapes = listOf(pageShapeSnapshot())
            ),
            2 to PageSnapshotV1(
                scale = PageScaleSnapshotV1(pointsPerFoot = 18.5f)
            )
        )
    )

    fun pageShape(): Shape = Shape(
        x = 0.56f,
        y = 0.42f,
        rotation = 37.5f,
        type = ShapeType.CLOUD,
        colorArgb = -16776961,
        isFilled = true,
        strokeWidthRatio = 0.0125f,
        widthRatio = 0.27f,
        heightRatio = 0.19f,
        id = PAGE_SHAPE_ID
    )

    fun imageShape(): Shape = Shape(
        x = 0.42f,
        y = 0.58f,
        rotation = 27.0f,
        type = ShapeType.ARROW,
        colorArgb = -65536,
        isFilled = false,
        strokeWidthRatio = 0.009f,
        widthRatio = 0.42f,
        heightRatio = 0.19f,
        id = IMAGE_SHAPE_ID
    )

    fun pageShapeSnapshot(): ShapeSnapshotV1 = ShapeSnapshotV1(
        x = 0.56f,
        y = 0.42f,
        rotation = 37.5f,
        type = SnapshotShapeTypeV1.CLOUD,
        colorArgb = -16776961,
        isFilled = true,
        strokeWidthRatio = 0.0125f,
        widthRatio = 0.27f,
        heightRatio = 0.19f,
        id = PAGE_SHAPE_ID
    )

    fun imageShapeSnapshot(): ShapeSnapshotV1 = ShapeSnapshotV1(
        x = 0.42f,
        y = 0.58f,
        rotation = 27.0f,
        type = SnapshotShapeTypeV1.ARROW,
        colorArgb = -65536,
        isFilled = false,
        strokeWidthRatio = 0.009f,
        widthRatio = 0.42f,
        heightRatio = 0.19f,
        id = IMAGE_SHAPE_ID
    )
}
