package com.example.myapplication.stage0

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.imageio.ImageIO

class Stage0FixtureInventoryTest {
    @Test
    fun characterization_pdfFixtures_areRealDeterministicPdfResources() {
        val planA = resourceBytes("stage0/pdfs/source-a/plan.pdf")
        val planB = resourceBytes("stage0/pdfs/source-b/plan.pdf")
        assertEquals("plan.pdf", "stage0/pdfs/source-a/plan.pdf".substringAfterLast('/'))
        assertEquals("plan.pdf", "stage0/pdfs/source-b/plan.pdf".substringAfterLast('/'))
        assertNotEquals("same-name PDFs must have different bytes", planA.toList(), planB.toList())
        assertTrue(ascii(planA).contains("PLAN-A-UNMISTAKABLE-CONTENT"))
        assertTrue(ascii(planB).contains("PLAN-B-UNMISTAKABLE-CONTENT"))

        listOf(
            "stage0/pdfs/source-a/plan.pdf",
            "stage0/pdfs/source-b/plan.pdf",
            "stage0/pdfs/cropped-rotated/embedded_text_crop_offset_rotate.pdf",
            "stage0/pdfs/scanned/scanned_image_only.pdf",
            "stage0/pdfs/blueprint/large_blueprint.pdf"
        ).forEach { path ->
            assertPdfStructure(path)
        }
    }

    @Test
    fun characterization_croppedRotatedPdf_hasSelectableTextCropOffsetAndRotation() {
        val text = resourceText("stage0/pdfs/cropped-rotated/embedded_text_crop_offset_rotate.pdf")
        assertTrue(text.contains("/CropBox [36 48 756 548]"))
        assertTrue(text.contains("/Rotate 90"))
        assertTrue(text.contains("BT"))
        assertTrue(text.contains("(CROPPED-OFFSET-ROTATED-SELECTABLE-TEXT) Tj"))
        assertTrue(text.contains("/Type /Font"))
    }

    @Test
    fun characterization_pdfFixtures_areLoadableByTheAppPdfBoxLibrary() {
        val sameNamePaths = listOf(
            "stage0/pdfs/source-a/plan.pdf",
            "stage0/pdfs/source-b/plan.pdf"
        )
        sameNamePaths.forEach { path ->
            PDDocument.load(ByteArrayInputStream(resourceBytes(path))).use { document ->
                assertEquals("$path should contain one page", 1, document.numberOfPages)
            }
        }

        PDDocument.load(
            ByteArrayInputStream(resourceBytes("stage0/pdfs/cropped-rotated/embedded_text_crop_offset_rotate.pdf"))
        ).use { document ->
            val page = document.getPage(0)
            assertEquals(90, page.rotation)
            assertEquals(36f, page.cropBox.lowerLeftX, 0f)
            assertEquals(48f, page.cropBox.lowerLeftY, 0f)
        }

        PDDocument.load(
            ByteArrayInputStream(resourceBytes("stage0/pdfs/scanned/scanned_image_only.pdf"))
        ).use { document ->
            assertEquals(1, document.numberOfPages)
        }

        PDDocument.load(
            ByteArrayInputStream(resourceBytes("stage0/pdfs/blueprint/large_blueprint.pdf"))
        ).use { document ->
            assertEquals(4, document.numberOfPages)
        }
    }

    @Test
    fun characterization_scannedPdf_isImageOnlyWithoutTextObjects() {
        val text = resourceText("stage0/pdfs/scanned/scanned_image_only.pdf")
        assertTrue(text.contains("/Subtype /Image"))
        assertTrue(text.contains("/Filter /ASCIIHexDecode"))
        assertTrue(text.contains("/Im1 Do"))
        assertFalse("scan fixture must not accidentally become selectable text", text.contains("BT"))
        assertFalse("scan fixture must not carry a font resource", text.contains("/Font"))
    }

    @Test
    fun characterization_blueprintPdf_isMultiPageVectorFixtureOfReasonableSize() {
        val bytes = resourceBytes("stage0/pdfs/blueprint/large_blueprint.pdf")
        val text = ascii(bytes)
        assertTrue("blueprint fixture should exercise more than a tiny page", bytes.size > 30_000)
        assertEquals(4, Regex("/Type /Page\\b").findAll(text).count())
        assertTrue(text.contains("/Kids [3 0 R 5 0 R 7 0 R 9 0 R]"))
        assertTrue(text.contains("VECTOR DETAIL GRID"))
        assertTrue(text.contains("ROOM GRID 1-A"))
        assertTrue(text.contains("MECHANICAL 4-C"))
    }

    @Test
    fun characterization_jsonFixtures_coverMalformedMissingAndMaliciousCases() {
        val malformed = resourceText("stage0/payloads/malformed.json")
        var malformedRejected = false
        try {
            JsonParser.parseString(malformed)
        } catch (_: RuntimeException) {
            malformedRejected = true
        }
        assertTrue("truncated JSON is a rejection fixture, not a valid state", malformedRejected)

        val malicious = JsonParser.parseString(resourceText("stage0/payloads/malicious_payloads.json")).asJsonObject
        val cases = malicious.getAsJsonArray("cases")
        assertEquals(9, cases.size())
        val names = cases.map { it.asJsonObject.get("name")?.asString }.filterNotNull()
        assertTrue(names.contains("../escape.jpg"))
        assertTrue(names.contains("/absolute/path.jpg"))
        assertTrue(names.contains("C:\\absolute\\path.jpg"))
        assertTrue(names.any { it.contains("'") && it.contains("&") })
        assertTrue(names.any { it.contains("trashed") && it.contains("=") })
        assertEquals("TRIANGLE", cases[5].asJsonObject.get("type").asString)
        assertTrue(cases[6].asJsonObject.get("pixelsPerFoot").asDouble.isInfinite())
        assertFalse(cases[7].asJsonObject.getAsJsonObject("photoPin").has("imageFileNames"))
        assertEquals(1_000_000, cases[8].asJsonObject.get("pages").asInt)
        assertEquals(50_000, cases[8].asJsonObject.get("imageWidth").asInt)

        val nonFinite = resourceText("stage0/payloads/malicious_non_finite_payloads.json")
        assertTrue(nonFinite.contains("NaN"))
        assertTrue(nonFinite.contains("Infinity"))
        val missing = JsonParser.parseString(resourceText("stage0/payloads/missing_required_fields.json"))
            .asJsonObject.getAsJsonObject("0")
        assertFalse(missing.getAsJsonArray("paths")[0].asJsonObject.getAsJsonArray("points")[0].asJsonObject.has("y"))
        assertFalse(missing.getAsJsonArray("photoPins")[0].asJsonObject.has("imageFileNames"))
        assertFalse(missing.getAsJsonArray("shapes")[0].asJsonObject.has("type"))
        assertFalse(missing.getAsJsonObject("scale").has("pixelsPerFoot"))
    }

    @Test
    fun characterization_phonePhotoGenerator_isHighResolutionAndByteDeterministic() {
        val first = HighResolutionPhonePhotoFixture.jpegBytes()
        val second = HighResolutionPhonePhotoFixture.jpegBytes()
        assertTrue(first.size > 100_000)
        assertArrayEquals("fixed drawing and JPEG settings must be reproducible", first, second)
        assertEquals(0xFF.toByte(), first[0])
        assertEquals(0xD8.toByte(), first[1])
        assertEquals(0xFF.toByte(), first[first.lastIndex - 1])
        assertEquals(0xD9.toByte(), first[first.lastIndex])
        assertEquals(4032, HighResolutionPhonePhotoFixture.WIDTH)
        assertEquals(3024, HighResolutionPhonePhotoFixture.HEIGHT)
        val decoded = ImageIO.read(ByteArrayInputStream(first))
        assertNotNull("generated photo must decode as an image", decoded)
        assertEquals(4032, decoded.width)
        assertEquals(3024, decoded.height)
        decoded.flush()
    }

    private fun assertPdfStructure(resource: String) {
        val bytes = resourceBytes(resource)
        val text = ascii(bytes)
        assertTrue(resource, text.startsWith("%PDF-1.4\n"))
        assertTrue(resource, text.contains("%%EOF"))

        val xrefOffset = text.indexOf("xref\n")
        assertTrue("$resource has an xref table", xrefOffset >= 0)
        val startxrefMarker = text.indexOf("startxref\n")
        assertTrue("$resource has a startxref marker", startxrefMarker >= 0)
        val startxref = text.substring(startxrefMarker + "startxref\n".length, startxrefMarker + "startxref\n".length + 10).toInt()
        assertEquals("$resource xref offset", xrefOffset, startxref)

        val xrefLines = text.substring(xrefOffset).split('\n')
        val objectCount = xrefLines[1].substringAfter(' ').toInt()
        val objectOffsets = Regex("(?m)^(\\d+) 0 obj").findAll(text).associate { match ->
            match.groupValues[1].toInt() to match.range.first
        }
        assertEquals("$resource xref object count", objectCount - 1, objectOffsets.size)
        objectOffsets.forEach { (number, offset) ->
            val row = xrefLines[number + 2]
            assertEquals("$resource object $number offset", offset, row.substring(0, 10).toInt())
        }

        Regex("/Length\\s+(\\d+)\\s*>>\\nstream\\n").findAll(text).forEach { match ->
            val dataStart = match.range.last + 1
            val dataEnd = text.indexOf("endstream", dataStart)
            assertTrue("$resource stream has an end", dataEnd >= dataStart)
            assertEquals("$resource stream length", match.groupValues[1].toInt(), dataEnd - dataStart)
        }
    }

    private fun resourceBytes(path: String): ByteArray {
        return requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "Missing resource $path" }
            .use { it.readBytes() }
    }

    private fun resourceText(path: String): String =
        resourceBytes(path).toString(StandardCharsets.UTF_8)

    private fun ascii(bytes: ByteArray): String = bytes.toString(StandardCharsets.US_ASCII)
}
