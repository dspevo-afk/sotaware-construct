package com.example.myapplication.stage7

import com.example.myapplication.PdfBitmapRect
import com.example.myapplication.PdfBox
import com.example.myapplication.PdfCoordinateMapper
import com.example.myapplication.PdfNormalizedRect
import com.example.myapplication.PdfPageGeometry
import com.example.myapplication.PdfTopLeftRect
import com.example.myapplication.copyNormalizedRectOrNull
import com.example.myapplication.normalizeBitmapRect
import com.example.myapplication.normalizedRectToBitmapRectOrNull
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class PdfCoordinateMapperTest {
    @Test
    fun croppedRotatedFixture_derivesCropGeometryAndGoldenRawMapping() {
        loadPdf("stage0/pdfs/cropped-rotated/embedded_text_crop_offset_rotate.pdf").use { document ->
            val page = document.getPage(0)
            val geometry = geometryFor(page)

            assertEquals(PdfBox(0f, 0f, 800f, 600f), geometry.mediaBox)
            assertEquals(PdfBox(36f, 48f, 756f, 548f), geometry.cropBox)
            assertEquals(90, geometry.rotationDegrees)
            assertFloatEquals(720f, geometry.visibleWidth)
            assertFloatEquals(500f, geometry.visibleHeight)
            assertFloatEquals(500f, geometry.displayedWidth)
            assertFloatEquals(720f, geometry.displayedHeight)

            val mapped = PdfCoordinateMapper.fromRawPdfRect(
                PdfBox(100f, 100f, 200f, 160f),
                geometry
            )
            assertNormalizedRect(
                expectedLeft = 0.104f,
                expectedTop = 0.0888889f,
                expectedRight = 0.224f,
                expectedBottom = 0.2277778f,
                actual = mapped
            )
        }
    }

    @Test
    fun rawAdapter_mapsEveryQuarterTurnUsingAllFourCorners() {
        val media = PdfBox(0f, 0f, 800f, 600f)
        val raw = PdfBox(100f, 100f, 200f, 160f)
        val expected = listOf(
            floatArrayOf(0.125f, 440f / 600f, 0.25f, 500f / 600f),
            floatArrayOf(100f / 600f, 100f / 800f, 160f / 600f, 200f / 800f),
            floatArrayOf(600f / 800f, 100f / 600f, 700f / 800f, 160f / 600f),
            floatArrayOf(440f / 600f, 600f / 800f, 500f / 600f, 700f / 800f)
        )

        listOf(0, 90, 180, 270).forEachIndexed { index, rotation ->
            val geometry = PdfPageGeometry(media, media, rotation)
            val actual = PdfCoordinateMapper.fromRawPdfRect(raw, geometry)
            assertNormalizedRect(expected[index], actual)
        }
    }

    @Test
    fun rawAdapter_accountsForNonZeroMediaAndCropOrigins() {
        val geometry = PdfPageGeometry(
            mediaBox = PdfBox(10f, 20f, 810f, 620f),
            cropBox = PdfBox(46f, 68f, 766f, 568f),
            rotationDegrees = 90
        )
        val mapped = PdfCoordinateMapper.fromRawPdfRect(
            PdfBox(110f, 120f, 210f, 180f),
            geometry
        )

        assertNormalizedRect(0.104f, 0.0888889f, 0.224f, 0.2277778f, mapped)
    }

    @Test
    fun rawAdapter_mapsVisibleBottomLeftAndAllPageCornersIntoBounds() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val geometry = PdfPageGeometry(
                PdfBox(-20f, -30f, 780f, 570f),
                PdfBox(16f, 18f, 736f, 518f),
                rotation
            )
            val fullPage = PdfCoordinateMapper.fromRawPdfRect(geometry.cropBox, geometry)
            assertNormalizedRect(0f, 0f, 1f, 1f, fullPage)

            val bottomLeft = PdfCoordinateMapper.fromRawPdfRect(
                PdfBox(geometry.cropBox.left, geometry.cropBox.bottom,
                    geometry.cropBox.left + 1f, geometry.cropBox.bottom + 1f),
                geometry
            )
            if (rotation == 0) {
                assertNormalizedRect(
                    0f,
                    (geometry.visibleHeight - 1f) / geometry.visibleHeight,
                    1f / geometry.visibleWidth,
                    1f,
                    bottomLeft
                )
            }
            assertTrue(bottomLeft.left in 0f..1f)
            assertTrue(bottomLeft.top in 0f..1f)
            assertTrue(bottomLeft.right in 0f..1f)
            assertTrue(bottomLeft.bottom in 0f..1f)
        }
    }

    @Test
    fun pdfBoxAdapters_keepAlreadyRotatedAndUnrotatedContractsDistinct() {
        val geometry = PdfPageGeometry(
            PdfBox(0f, 0f, 800f, 600f),
            PdfBox(36f, 48f, 756f, 548f),
            90
        )
        val expected = PdfNormalizedRect(0.104f, 0.0888889f, 0.224f, 0.2277778f)

        // Already-adjusted PDFBox coordinates are directly relative to the
        // rotated visible crop (500 x 720); no second rotation is applied.
        val alreadyDisplayed = PdfCoordinateMapper.fromPdfBoxAlreadyDisplayedTopLeftRect(
            PdfTopLeftRect(52f, 64f, 112f, 164f),
            geometry
        )

        // getXDirAdj/getYDirAdj are unrotated crop-relative top-left values.
        // This rectangle represents the same raw [100,100,200,160] box.
        val unrotated = PdfCoordinateMapper.fromPdfBoxUnrotatedTopLeftRect(
            PdfTopLeftRect(64f, 388f, 164f, 448f),
            geometry
        )

        assertNormalizedRect(expected.left, expected.top, expected.right, expected.bottom, alreadyDisplayed)
        assertNormalizedRect(expected.left, expected.top, expected.right, expected.bottom, unrotated)
    }

    @Test
    fun pdfBoxTextPositionTopAndHeightConvention_usesCroppedRotatedFixtureGolden() {
        loadPdf("stage0/pdfs/cropped-rotated/embedded_text_crop_offset_rotate.pdf").use { document ->
            val geometry = geometryFor(document.getPage(0))
            val expected = PdfNormalizedRect(0.104f, 0.0888889f, 0.224f, 0.2277778f)

            // TextPosition.getX()/getY() are already displayed top-left values,
            // with getY() at the top edge. On this 90-degree page, the
            // constructor's individual width is the displayed vertical
            // advance and maxHeight is the displayed horizontal extent; the
            // port's getWidth*() values must not be used for this rectangle.
            val displayedGlyphHeight = 60f
            val displayedGlyphAdvance = 100f
            val fromTopAndHeight = PdfCoordinateMapper.fromPdfBoxAlreadyDisplayedTopLeftRect(
                PdfTopLeftRect(
                    left = 52f,
                    top = 64f,
                    right = 52f + displayedGlyphHeight,
                    bottom = 64f + displayedGlyphAdvance
                ),
                geometry
            )
            assertNormalizedRect(expected.left, expected.top, expected.right, expected.bottom, fromTopAndHeight)

            // The old inverted convention is outside the displayed crop here
            // and must fail closed rather than being accepted as the golden box.
            assertRejected {
                PdfCoordinateMapper.fromPdfBoxAlreadyDisplayedTopLeftRect(
                    PdfTopLeftRect(left = 52f, top = 64f - 100f, right = 112f, bottom = 64f),
                    geometry
                )
            }

            // The legacy getXDirAdj/getYDirAdj adapter is unrotated. Its
            // top-plus-height equivalent for the same raw box is [64,388,
            // 164,448]; using [y - h,y] produces a different valid box.
            val unrotatedTopAndHeight = PdfCoordinateMapper.fromPdfBoxUnrotatedTopLeftRect(
                PdfTopLeftRect(left = 64f, top = 388f, right = 164f, bottom = 388f + 60f),
                geometry
            )
            val unrotatedInverted = PdfCoordinateMapper.fromPdfBoxUnrotatedTopLeftRect(
                PdfTopLeftRect(left = 64f, top = 388f - 60f, right = 164f, bottom = 388f),
                geometry
            )
            assertNormalizedRect(expected.left, expected.top, expected.right, expected.bottom, unrotatedTopAndHeight)
            assertTrue(unrotatedInverted != unrotatedTopAndHeight)
        }
    }

    @Test
    fun scannedFixture_derivesUsableGeometryAndFullCropMapsToFullDisplay() {
        loadPdf("stage0/pdfs/scanned/scanned_image_only.pdf").use { document ->
            val geometry = geometryFor(document.getPage(0))
            assertTrue(geometry.mediaBox.width > 0f)
            assertTrue(geometry.mediaBox.height > 0f)
            assertTrue(geometry.visibleWidth > 0f)
            assertTrue(geometry.visibleHeight > 0f)
            assertNormalizedRect(
                0f,
                0f,
                1f,
                1f,
                PdfCoordinateMapper.fromRawPdfRect(geometry.cropBox, geometry)
            )
        }
    }

    @Test
    fun bitmapAdapter_normalizesAndInvertsWithFreshBoundedCoordinates() {
        val source = PdfBitmapRect(25f, 50f, 75f, 150f)
        val normalized = PdfCoordinateMapper.normalizeBitmapRect(source, 100, 200)
        assertNormalizedRect(0.25f, 0.25f, 0.75f, 0.75f, normalized)

        val pixels = PdfCoordinateMapper.normalizedRectToBitmapRect(normalized, 100f, 200f)
        assertFloatEquals(25f, pixels.left)
        assertFloatEquals(50f, pixels.top)
        assertFloatEquals(75f, pixels.right)
        assertFloatEquals(150f, pixels.bottom)

        val clipped = PdfCoordinateMapper.normalizeBitmapRect(
            PdfBitmapRect(-10f, 50f, 75f, 210f),
            100,
            200
        )
        assertNormalizedRect(0f, 0.25f, 0.75f, 1f, clipped)
    }

    @Test
    fun malformedGeometryAndRectangles_failClosed() {
        assertRejected { PdfBox(0f, 0f, 0f, 1f) }
        assertRejected { PdfBox(1f, 0f, 0f, 1f) }
        assertRejected { PdfTopLeftRect(2f, 3f, 1f, 4f) }
        assertRejected { PdfTopLeftRect(0f, 0f, 1f, 0f) }
        assertRejected { PdfBitmapRect(2f, 3f, 1f, 4f) }
        assertRejected { PdfBitmapRect(0f, 0f, 1f, 0f) }
        assertRejected { PdfNormalizedRect(0f, 0f, 0f, 1f) }
        assertRejected { PdfBox(0f, 0f, 10_000_001f, 1f) }
        assertRejected { PdfBox(Float.NaN, 0f, 1f, 1f) }
        assertRejected { PdfBox(0f, 0f, Float.POSITIVE_INFINITY, 1f) }
        assertRejected {
            PdfPageGeometry(
                PdfBox(0f, 0f, 10f, 10f),
                PdfBox(0f, 0f, 11f, 10f),
                0
            )
        }
        assertRejected {
            PdfPageGeometry(PdfBox(0f, 0f, 10f, 10f), PdfBox(0f, 0f, 10f, 10f), 45)
        }

        val geometry = PdfPageGeometry(PdfBox(0f, 0f, 800f, 600f), PdfBox(36f, 48f, 756f, 548f), 90)
        assertRejected {
            PdfCoordinateMapper.fromRawPdfRect(PdfBox(35f, 48f, 100f, 160f), geometry)
        }
        assertRejected {
            PdfCoordinateMapper.normalizeBitmapRect(PdfBitmapRect(-100f, 0f, -50f, 10f), 100, 100)
        }
        assertRejected {
            PdfCoordinateMapper.normalizeBitmapRect(PdfBitmapRect(0f, 0f, 10f, 10f), 0, 100)
        }
    }

    @Test
    fun normalizedOutput_isAlwaysBoundedForRepresentativeRawBoxes() {
        val geometry = PdfPageGeometry(
            PdfBox(0f, 0f, 800f, 600f),
            PdfBox(36f, 48f, 756f, 548f),
            0
        )
        val boxes = listOf(
            PdfBox(36f, 48f, 37f, 49f),
            PdfBox(100f, 100f, 200f, 160f),
            PdfBox(755f, 547f, 756f, 548f),
            geometry.cropBox
        )
        boxes.forEach { raw ->
            val normalized = PdfCoordinateMapper.fromRawPdfRect(raw, geometry)
            assertTrue(normalized.left in 0f..1f)
            assertTrue(normalized.top in 0f..1f)
            assertTrue(normalized.right in 0f..1f)
            assertTrue(normalized.bottom in 0f..1f)
        }
    }

    private fun geometryFor(page: com.tom_roush.pdfbox.pdmodel.PDPage): PdfPageGeometry {
        fun com.tom_roush.pdfbox.pdmodel.common.PDRectangle.toPdfBox() = PdfBox(
            lowerLeftX,
            lowerLeftY,
            upperRightX,
            upperRightY
        )
        return PdfPageGeometry(page.mediaBox.toPdfBox(), page.cropBox.toPdfBox(), page.rotation)
    }

    private fun loadPdf(resource: String): PDDocument = PDDocument.load(
        ByteArrayInputStream(
            requireNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
                "missing test resource $resource"
            }.use { it.readBytes() }
        )
    )

    private fun assertNormalizedRect(
        expectedLeft: Float,
        expectedTop: Float,
        expectedRight: Float,
        expectedBottom: Float,
        actual: PdfNormalizedRect
    ) {
        assertFloatEquals(expectedLeft, actual.left)
        assertFloatEquals(expectedTop, actual.top)
        assertFloatEquals(expectedRight, actual.right)
        assertFloatEquals(expectedBottom, actual.bottom)
    }

    private fun assertNormalizedRect(expected: FloatArray, actual: PdfNormalizedRect) {
        assertNormalizedRect(expected[0], expected[1], expected[2], expected[3], actual)
    }

    private fun assertFloatEquals(expected: Float, actual: Float) {
        assertEquals(expected.toDouble(), actual.toDouble(), 0.00001)
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("expected malformed coordinate input to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
