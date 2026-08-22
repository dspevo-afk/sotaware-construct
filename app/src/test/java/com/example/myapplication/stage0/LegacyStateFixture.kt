package com.example.myapplication.stage0

import com.example.myapplication.DrawnPath
import com.example.myapplication.Measurement
import com.example.myapplication.Note
import com.example.myapplication.PageData
import com.example.myapplication.PageMarkups
import com.example.myapplication.PageScale
import com.example.myapplication.PhotoImageNote
import com.example.myapplication.PhotoPin
import com.example.myapplication.Point
import com.example.myapplication.Shape
import com.example.myapplication.ShapeType

/**
 * One deliberately non-default legacy state used by all Stage 0 characterization tests.
 * IDs are fixed so a test failure identifies a lost field instead of a regenerated UUID.
 */
internal object LegacyStateFixture {
    const val PHOTO_ONE = "field-photo-001.jpg"
    const val PHOTO_TWO = "field-photo-002.jpg"

    fun fullyPopulatedPageData(): Map<Int, PageData> = linkedMapOf(
        0 to PageData(
            paths = listOf(
                DrawnPath(
                    points = listOf(
                        Point(12.5f, 18.75f),
                        Point(240.0f, 320.5f),
                        Point(480.25f, 640.125f)
                    ),
                    colorArgb = -16711936,
                    strokeWidth = 7.25f,
                    isHighlighter = true
                )
            ),
            measurements = listOf(
                Measurement(
                    p1 = Point(101.5f, 202.25f),
                    p2 = Point(501.75f, 702.5f),
                    text = "14' 6.25\""
                )
            ),
            notes = listOf(
                Note(
                    x = 0.31f,
                    y = 0.47f,
                    text = "LEGACY PAGE NOTE",
                    fontSize = 21.5f,
                    isBold = true,
                    rotation = -12.0f
                )
            ),
            photoPins = listOf(
                PhotoPin(
                    x = 0.62f,
                    y = 0.73f,
                    id = "photo-pin-legacy-001",
                    imageFileNames = mutableListOf(PHOTO_ONE, PHOTO_TWO),
                    imageNotes = mutableMapOf(
                        PHOTO_ONE to mutableListOf(
                            PhotoImageNote(
                                x = 0.18f,
                                y = 0.29f,
                                text = "IMAGE NOTE WITH METADATA",
                                fontSize = 24.0f,
                                isBold = true,
                                rotation = 33.0f,
                                fontSizeRatio = 0.018f,
                                id = "image-note-legacy-001"
                            )
                        )
                    ),
                    imageShapes = mutableMapOf(
                        PHOTO_ONE to mutableListOf(
                            imageShape()
                        )
                    )
                )
            ),
            scale = PageScale(42.75f),
            shapes = listOf(pageShape())
        ),
        2 to PageData(
            paths = emptyList(),
            measurements = emptyList(),
            notes = emptyList(),
            photoPins = emptyList(),
            scale = PageScale(18.5f),
            shapes = emptyList()
        )
    )

    fun fullyPopulatedLegacyMarkups(): Map<Int, PageMarkups> {
        return fullyPopulatedPageData().mapValues { (_, page) ->
            PageMarkups(
                paths = page.paths,
                measurements = page.measurements,
                notes = page.notes,
                photoPins = page.photoPins,
                shapes = page.shapes
            )
        }
    }

    fun pageShape(): Shape = Shape(
        x = 388.0f,
        y = 244.0f,
        width = 320.0f,
        height = 180.0f,
        rotation = 37.5f,
        type = ShapeType.CLOUD,
        colorArgb = -16776961,
        strokeWidth = 6.5f,
        isFilled = true,
        strokeWidthRatio = 0.0125f,
        widthRatio = 0.27f,
        heightRatio = 0.19f,
        id = "page-shape-legacy-001"
    )

    fun imageShape(): Shape = Shape(
        x = 0.42f,
        y = 0.58f,
        width = 412.0f,
        height = 208.0f,
        rotation = 27.0f,
        type = ShapeType.ARROW,
        colorArgb = -65536,
        strokeWidth = 5.0f,
        isFilled = false,
        strokeWidthRatio = 0.009f,
        widthRatio = 0.42f,
        heightRatio = 0.19f,
        id = "image-shape-legacy-001"
    )
}
