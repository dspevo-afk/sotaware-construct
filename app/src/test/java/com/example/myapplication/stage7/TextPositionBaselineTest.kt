package com.example.myapplication.stage7

import com.example.myapplication.PdfBox
import com.example.myapplication.PdfPageGeometry
import com.example.myapplication.pdfBoxDisplayedTextPositionOrNull
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * PDFBox's TextPosition.getY() is the glyph's BASELINE (page height minus the text
 * matrix's baseline translate), not its top edge. Search highlights and text
 * selection on an upright page sat one text height below the word while the
 * extracted box was built downward from the baseline.
 */
class TextPositionBaselineTest {
    private val glyphHeight = 17f
    private val advance = 60f

    private fun position(rotation: Int, pageWidth: Float, pageHeight: Float, baselineX: Float, baselineY: Float) =
        TextPosition(
            rotation, pageWidth, pageHeight, Matrix(24f, 0f, 0f, 24f, baselineX, baselineY),
            baselineX + advance, baselineY, glyphHeight, advance, 6f, "E", intArrayOf(69), null, 24f, 24
        )

    private fun geometry(width: Float, height: Float, rotation: Int) =
        PdfPageGeometry(PdfBox(0f, 0f, width, height), PdfBox(0f, 0f, width, height), rotation)

    @Test fun uprightTextRisesAboveItsBaseline() {
        // Baseline 700 pt up an 800 pt page = 100 pt from the top.
        val p = position(0, 600f, 800f, 100f, 700f)
        assertEquals(100f, p.y, 1e-4f)
        val box = assertNotNull(pdfBoxDisplayedTextPositionOrNull(p, geometry(600f, 800f, 0))).rectangle
        assertEquals(100f - glyphHeight, box.top, 1e-4f)
        assertEquals(100f, box.bottom, 1e-4f)
        assertEquals(100f, box.left, 1e-4f)
        assertEquals(100f + advance, box.right, 1e-4f)
    }

    @Test fun upsideDownTextHangsBelowItsBaseline() {
        val p = position(180, 600f, 800f, 300f, 300f)
        val box = assertNotNull(pdfBoxDisplayedTextPositionOrNull(p, geometry(600f, 800f, 180))).rectangle
        assertEquals(p.y, box.top, 1e-4f)
        assertEquals(p.y + glyphHeight, box.bottom, 1e-4f)
        assertEquals(p.x - advance, box.left, 1e-4f)
        assertEquals(p.x, box.right, 1e-4f)
    }

    @Test fun sidewaysTextKeepsItsAuditedExtents() {
        // 90 degrees: text runs down the screen and rises to the right of its baseline.
        // TextPosition carries the unrotated page size; the 600 x 800 page displays 800 x 600.
        val p = position(90, 600f, 800f, 300f, 300f)
        val box = assertNotNull(pdfBoxDisplayedTextPositionOrNull(p, geometry(600f, 800f, 90))).rectangle
        assertEquals(p.x, box.left, 1e-4f)
        assertEquals(p.x + glyphHeight, box.right, 1e-4f)
        assertEquals(p.y, box.top, 1e-4f)
        assertEquals(p.y + advance, box.bottom, 1e-4f)
    }

    private fun <T> assertNotNull(value: T?): T { assertNotNull("position should be accepted", value as Any?); return value!! }
}
