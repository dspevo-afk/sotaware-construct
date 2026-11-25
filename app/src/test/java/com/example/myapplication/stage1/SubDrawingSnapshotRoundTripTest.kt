package com.example.myapplication.stage1

import androidx.compose.runtime.toMutableStateList
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
import com.example.myapplication.SubDrawing
import com.example.myapplication.SubDrawingPalette
import com.example.myapplication.stage0.CurrentStateFixture
import com.example.myapplication.stage5.decodeValidatedSnapshotJson
import com.example.myapplication.stage5.validateCanonicalSnapshotTree
import com.example.myapplication.stage5.validateSnapshot
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sub-drawings travel through the canonical snapshot codecs: capture, apply,
 * wire validation, and documents written before sub-drawings existed.
 */
class SubDrawingSnapshotRoundTripTest {
    private val source = CurrentStateFixture.source()
    private val gson = Gson()

    private fun nestedPath(id: String) = DrawnPath(
        points = listOf(Point(0.1f, 0.2f), Point(0.4f, 0.7f)),
        colorArgb = -65536,
        isHighlighter = false,
        strokeWidthRatio = 0.005f,
        id = id
    )

    private fun nestedMeasurement(id: String) = Measurement(
        p1 = Point(0f, 0f),
        p2 = Point(0.5f, 0.25f),
        text = "12' 3\"",
        id = id
    )

    private fun nestedNote(id: String) = Note(0.3f, 0.4f, "sub note", isBold = true, rotation = -12f, fontSizeRatio = 0.028f, id = id)

    private fun nestedShape(id: String) = Shape(
        x = 0.5f,
        y = 0.5f,
        rotation = 30f,
        type = ShapeType.ARROW,
        colorArgb = -1,
        isFilled = true,
        strokeWidthRatio = 0.005f,
        widthRatio = 0.2f,
        heightRatio = 0.1f,
        id = id
    )

    private fun populatedVm(): BlueprintViewModel {
        val vm = BlueprintViewModel()
        vm.pageSubDrawings[3] = listOf(
            SubDrawing(
                id = "sub-a",
                left = 0.1f,
                top = 0.2f,
                right = 0.6f,
                bottom = 0.7f,
                colorIndex = 2,
                paths = listOf(nestedPath("sub-path")),
                measurements = listOf(nestedMeasurement("sub-measure")),
                notes = listOf(nestedNote("sub-note")),
                shapes = listOf(nestedShape("sub-shape")),
                photos = listOf(
                    PhotoPin(
                        x = 0.3f,
                        y = 0.6f,
                        id = "sub-pin",
                        imageFileNames = listOf("photo.jpg"),
                        imageNotes = mapOf("photo.jpg" to listOf(PhotoImageNote(x = 0.1f, y = 0.2f, text = "pin note", id = "sub-pin-note"))),
                        imagePaths = mapOf("photo.jpg" to listOf(nestedPath("sub-pin-path"))),
                        imageScales = mapOf("photo.jpg" to PageScale(41f))
                    )
                ),
                scale = PageScale(36.5f),
                name = "104"
            ),
            SubDrawing(id = "sub-b", left = 0.7f, top = 0.1f, right = 0.95f, bottom = 0.4f, colorIndex = 5)
        ).toMutableStateList()
        vm.pageScales[3] = PageScale(20f)
        return vm
    }

    @Test
    fun subDrawings_roundTripThroughCaptureAndApplyWithNestedMarkupsAndScale() {
        val vm = populatedVm()
        val snapshot = snapshotFromState(vm, source, snapshotRevision = 11L)
        validateSnapshot(snapshot)
        validateCanonicalSnapshotTree(gson.toJsonTree(snapshot).asJsonObject)

        val page = snapshot.pages.getValue(3)
        assertEquals(2, page.subDrawings.size)
        val first = page.subDrawings.first { it.id == "sub-a" }
        assertEquals(0.1f, first.left, 0f)
        assertEquals(0.2f, first.top, 0f)
        assertEquals(0.6f, first.right, 0f)
        assertEquals(0.7f, first.bottom, 0f)
        assertEquals(2, first.colorIndex)
        assertEquals("104", first.name)
        assertNull(page.subDrawings.first { it.id == "sub-b" }.name)
        assertEquals(36.5f, requireNotNull(first.scale).pointsPerFoot, 0f)
        assertEquals(listOf("sub-path"), first.paths.map { it.id })
        assertEquals(listOf("sub-measure"), first.measurements.map { it.id })
        assertEquals(listOf("sub-note"), first.notes.map { it.id })
        assertEquals(listOf("sub-shape"), first.shapes.map { it.id })
        // The sub-drawing's photo pin travels with it, markups included.
        val pin = first.photos.single()
        assertEquals("sub-pin", pin.id)
        assertEquals(listOf("photo.jpg"), pin.imageFileNames)
        assertEquals(listOf("sub-pin-note"), pin.imageNotes.getValue("photo.jpg").map { it.id })
        assertEquals(listOf("sub-pin-path"), pin.imagePaths.getValue("photo.jpg").map { it.id })
        assertEquals(41f, requireNotNull(pin.imageScales["photo.jpg"]).pointsPerFoot, 0f)

        // Apply into a clean ViewModel, then re-capture: byte-identical snapshot.
        val target = BlueprintViewModel()
        applySnapshotReplace(snapshot, target)
        val applied = target.pageSubDrawings.getValue(3).single { it.id == "sub-a" }
        assertEquals(0.6f, applied.right, 0f)
        assertEquals("104", applied.name)
        assertEquals(36.5f, requireNotNull(applied.scale).pointsPerFoot, 0f)
        assertEquals(listOf("sub-path"), applied.paths.map { it.id })
        assertEquals(ShapeType.ARROW, applied.shapes.single().type)
        assertEquals(20f, requireNotNull(target.pageScales.getValue(3)).pointsPerFoot, 0f)

        assertEquals(snapshot, snapshotFromState(target, source, snapshotRevision = 11L))
    }

    @Test
    fun subDrawingOnlyPage_survivesApplyAndRemovesStalePages() {
        val vm = BlueprintViewModel()
        vm.pageSubDrawings[6] = listOf(SubDrawing(id = "sub-only", left = 0f, top = 0f, right = 0.5f, bottom = 0.5f)).toMutableStateList()
        val snapshot = snapshotFromState(vm, source, snapshotRevision = 12L)
        assertEquals(setOf(6), snapshot.pages.keys)

        // A page that only carried a sub-drawing must not survive replacement.
        val dirty = BlueprintViewModel()
        dirty.pagePaths[9] = listOf(nestedPath("stale-path")).toMutableStateList()
        dirty.pageSubDrawings[9] = listOf(SubDrawing(id = "stale-sub", left = 0f, top = 0f, right = 0.5f, bottom = 0.5f)).toMutableStateList()
        applySnapshotReplace(snapshot, dirty)

        assertTrue(dirty.pageSubDrawings.getValue(6).single().id == "sub-only")
        assertEquals(setOf(6), dirty.pageSubDrawings.keys)
        assertEquals(setOf(6), dirty.pagePaths.keys)
    }

    @Test
    fun snapshotWithoutSubDrawings_decodesAppliesAndRoundTripsUnchanged() {
        val wire = gson.toJson(snapshotFromState(CurrentStateFixture.viewModel(), source, snapshotRevision = 13L))
        val root = gson.fromJson(wire, JsonObject::class.java)
        root.getAsJsonObject("pages").entrySet().forEach { entry ->
            entry.value.asJsonObject.remove("subDrawings")
        }

        val decoded = decodeValidatedSnapshotJson(gson, root.toString(), "legacy page snapshot")
        assertTrue(decoded.pages.values.all { it.subDrawings.isEmpty() })

        val vm = BlueprintViewModel()
        applySnapshotReplace(decoded, vm)
        assertTrue(vm.pageSubDrawings.values.all { it.isEmpty() })
        assertEquals(decoded, snapshotFromState(vm, source, snapshotRevision = 13L))
    }

    @Test
    fun subDrawingWireFormat_isAcceptedAndMalformedCropsAreRejected() {
        val vm = populatedVm()
        val snapshot = snapshotFromState(vm, source, snapshotRevision = 14L)
        val tree = gson.toJsonTree(snapshot).asJsonObject

        // Accepted by both the typed and the wire oracle, then decoded from its own JSON.
        validateCanonicalSnapshotTree(tree)
        assertEquals(snapshot, decodeValidatedSnapshotJson(gson, tree.toString(), "sub-drawing snapshot"))

        val crop = tree.getAsJsonObject("pages").getAsJsonObject("3")
            .getAsJsonArray("subDrawings").asJsonArray.first { it.asJsonObject.get("id").asString == "sub-a" }
            .asJsonObject

        assertRejected { validateCanonicalSnapshotTree(crop.deepCopy().apply { addProperty("right", 0.05f) }.asJsonObject) }
        assertRejected { validateCanonicalSnapshotTree(crop.deepCopy().apply { addProperty("colorIndex", 99) }.asJsonObject) }
        assertRejected { validateCanonicalSnapshotTree(crop.deepCopy().apply { addProperty("top", -0.2f) }.asJsonObject) }
        assertRejected { validateCanonicalSnapshotTree(crop.deepCopy().apply { add("unknownField", gson.toJsonTree(1)) }.asJsonObject) }

        // A nested photo pin referencing an unattached photo is malformed input.
        val badPin = crop.deepCopy().apply {
            val paths = getAsJsonArray("photos").asJsonArray.first().asJsonObject.getAsJsonObject("imagePaths")
            val list = JsonArray()
            list.add(gson.toJsonTree(mapOf("id" to "ghost", "points" to listOf(mapOf("x" to 0f, "y" to 0f)))))
            paths.add("missing.jpg", list)
        }
        assertRejected { validateCanonicalSnapshotTree(badPin) }

        // The same defects on the typed snapshot are rejected before any live state changes.
        val badCrop = snapshot.pages.getValue(3).subDrawings.first { it.id == "sub-a" }.copy(right = 0.05f)
        assertRejected { validateSnapshot(snapshot.copy(pages = mapOf(3 to snapshot.pages.getValue(3).copy(subDrawings = listOf(badCrop))))) }
        val badColor = snapshot.pages.getValue(3).subDrawings.first { it.id == "sub-a" }.copy(colorIndex = 99)
        assertRejected { validateSnapshot(snapshot.copy(pages = mapOf(3 to snapshot.pages.getValue(3).copy(subDrawings = listOf(badColor))))) }
    }

    @Test
    fun appliedSubDrawingCollections_areDetachedFromTheCapturedSnapshot() {
        val vm = populatedVm()
        val snapshot = snapshotFromState(vm, source, snapshotRevision = 15L)
        val captured = snapshot.pages.getValue(3).subDrawings.first { it.id == "sub-a" }

        applySnapshotReplace(snapshot, vm)
        vm.pageSubDrawings[3] = listOf(
            vm.pageSubDrawings.getValue(3).single { it.id == "sub-a" }.copy(right = 0.99f, paths = emptyList())
        ).toMutableStateList()

        assertEquals(0.6f, captured.right, 0f)
        assertEquals(listOf("sub-path"), captured.paths.map { it.id })
        assertNotNull(captured.scale)
    }

    @Test
    fun subDrawingNames_surviveWireValidationDecodeAndApply() {
        val vm = populatedVm()
        val snapshot = snapshotFromState(vm, source, snapshotRevision = 16L)
        val tree = gson.toJsonTree(snapshot).asJsonObject

        validateCanonicalSnapshotTree(tree)
        val decoded = decodeValidatedSnapshotJson(gson, tree.toString(), "named sub-drawing snapshot")
        assertEquals("104", decoded.pages.getValue(3).subDrawings.first { it.id == "sub-a" }.name)

        val target = BlueprintViewModel()
        applySnapshotReplace(decoded, target)
        assertEquals("104", target.pageSubDrawings.getValue(3).single { it.id == "sub-a" }.name)
        assertNull(target.pageSubDrawings.getValue(3).single { it.id == "sub-b" }.name)
        // Re-capturing the applied document writes the same names.
        assertEquals(snapshot, snapshotFromState(target, source, snapshotRevision = 16L))
    }

    @Test
    fun subDrawingWithoutANameOnTheWire_opensUnchangedAndRepublishesUnnamed() {
        // Drawings whose sub-drawings predate names carry no name field at all;
        // Gson leaves the declared field null, which is the same unnamed crop.
        val tree = gson.toJsonTree(snapshotFromState(populatedVm(), source, snapshotRevision = 17L)).asJsonObject
        val crop = tree.getAsJsonObject("pages").getAsJsonObject("3").getAsJsonArray("subDrawings")
            .asJsonArray.first { it.asJsonObject.get("id").asString == "sub-a" }.asJsonObject
        crop.remove("name")

        validateCanonicalSnapshotTree(tree)
        val decoded = decodeValidatedSnapshotJson(gson, tree.toString(), "pre-name sub-drawing snapshot")
        assertNull(decoded.pages.getValue(3).subDrawings.first { it.id == "sub-a" }.name)

        val vm = BlueprintViewModel()
        applySnapshotReplace(decoded, vm)
        val applied = vm.pageSubDrawings.getValue(3).single { it.id == "sub-a" }
        assertNull(applied.name)
        // Every markup the unnamed crop carried is still there.
        assertEquals(listOf("sub-path"), applied.paths.map { it.id })
        assertEquals(36.5f, requireNotNull(applied.scale).pointsPerFoot, 0f)

        val republished = snapshotFromState(vm, source, snapshotRevision = 17L)
        assertNull(republished.pages.getValue(3).subDrawings.first { it.id == "sub-a" }.name)
        validateSnapshot(republished)
    }

    @Test
    fun malformedSubDrawingNames_areRejectedBeforeTheyReachLiveState() {
        val snapshot = snapshotFromState(populatedVm(), source, snapshotRevision = 18L)
        val tree = gson.toJsonTree(snapshot).asJsonObject
        fun cropOf(root: JsonObject): JsonObject = root.getAsJsonObject("pages").getAsJsonObject("3")
            .getAsJsonArray("subDrawings").asJsonArray.first { it.asJsonObject.get("id").asString == "sub-a" }
            .asJsonObject

        // A name longer than the id budget, and a non-string name, are both
        // untrusted input; a blank name is accepted as the unnamed crop.
        assertRejected {
            val mutated = tree.deepCopy()
            cropOf(mutated).addProperty("name", "n".repeat(4_096))
            validateCanonicalSnapshotTree(mutated)
        }
        assertRejected {
            val mutated = tree.deepCopy()
            cropOf(mutated).add("name", gson.toJsonTree(7))
            validateCanonicalSnapshotTree(mutated)
        }
        val blankNames = tree.deepCopy()
        cropOf(blankNames).addProperty("name", "   ")
        blankNames.getAsJsonObject("pages").getAsJsonObject("3").getAsJsonArray("subDrawings")
            .asJsonArray.first { it.asJsonObject.get("id").asString == "sub-b" }
            .asJsonObject.addProperty("name", "")
        validateCanonicalSnapshotTree(blankNames)
        val decodedBlank = decodeValidatedSnapshotJson(gson, blankNames.toString(), "blank-name snapshot")
        assertNull(decodedBlank.pages.getValue(3).subDrawings.first { it.id == "sub-a" }.name)

        val oversized = snapshot.pages.getValue(3).subDrawings.first { it.id == "sub-a" }
            .copy(name = "n".repeat(com.example.myapplication.stage5.Stage5Limits.MAX_ID_CHARS + 1))
        assertRejected {
            validateSnapshot(
                snapshot.copy(pages = mapOf(
                    3 to snapshot.pages.getValue(3).copy(
                        subDrawings = listOf(oversized, snapshot.pages.getValue(3).subDrawings.first { it.id == "sub-b" })
                    )
                ))
            )
        }
    }

    @Test
    fun subDrawingBadgeSize_survivesWireValidationDecodeAndApply() {
        // Each crop carries its own badge radius; capture, wire validation,
        // decode, apply, and re-capture must all keep the same size.
        val source_snapshot = snapshotFromState(populatedVm(), source, snapshotRevision = 19L)
        val sized = source_snapshot.pages.getValue(3).subDrawings.map { it.copy(badgeRatio = 0.042f) }
        val snapshot = source_snapshot.copy(pages = mapOf(
            3 to source_snapshot.pages.getValue(3).copy(subDrawings = sized)
        ))

        validateSnapshot(snapshot)
        val tree = gson.toJsonTree(snapshot).asJsonObject
        validateCanonicalSnapshotTree(tree)
        val decoded = decodeValidatedSnapshotJson(gson, tree.toString(), "sized badge snapshot")
        assertEquals(0.042f, decoded.pages.getValue(3).subDrawings.first { it.id == "sub-a" }.badgeRatio!!, 0.0001f)

        val vm = BlueprintViewModel()
        applySnapshotReplace(decoded, vm)
        val applied = vm.pageSubDrawings.getValue(3).single { it.id == "sub-a" }
        assertEquals(0.042f, requireNotNull(applied.badgeRatio), 0.0001f)
        // Re-capturing writes the same size back out.
        assertEquals(0.042f, snapshotFromState(vm, source, snapshotRevision = 19L).pages.getValue(3)
            .subDrawings.first { it.id == "sub-a" }.badgeRatio!!, 0.0001f)
    }

    @Test
    fun subDrawingWithoutABadgeSizeOnTheWire_opensWithADefaultSize() {
        // Drawings saved before badge sizes existed carry no radius field at all.
        // Gson leaves the nullable field null, which every surface reads as the
        // size derived from the crop - a marker scaled by its own room, never a
        // circle covering the sheet.
        val captured = snapshotFromState(populatedVm(), source, snapshotRevision = 20L)
        val sized = captured.pages.getValue(3).subDrawings.map { it.copy(badgeRatio = 0.04f) }
        val snapshot = captured.copy(pages = mapOf(
            3 to captured.pages.getValue(3).copy(subDrawings = sized)
        ))
        val tree = gson.toJsonTree(snapshot).asJsonObject
        val crops = tree.getAsJsonObject("pages").getAsJsonObject("3").getAsJsonArray("subDrawings")
            .asJsonArray.map { it.asJsonObject }
        crops.first { it.get("id").asString == "sub-a" }.remove("badgeRatio")

        validateCanonicalSnapshotTree(tree)
        val decoded = decodeValidatedSnapshotJson(gson, tree.toString(), "pre-size sub-drawing snapshot")
        assertNull(decoded.pages.getValue(3).subDrawings.first { it.id == "sub-a" }.badgeRatio)
        assertEquals(0.04f, decoded.pages.getValue(3).subDrawings.first { it.id == "sub-b" }.badgeRatio!!, 0.0001f)

        val vm = BlueprintViewModel()
        applySnapshotReplace(decoded, vm)
        val unsized = vm.pageSubDrawings.getValue(3).single { it.id == "sub-a" }
        // No stored size: the badge falls back to the size derived from its crop,
        // which is scaled by the room and capped, so old sheets never get a huge
        // marker over the drawing.
        assertNull(unsized.badgeRatio)
        val derived = com.example.myapplication.SubDrawingGeometry.defaultBadgeRatio(unsized)
        assertTrue(derived >= com.example.myapplication.SubDrawingGeometry.MIN_BADGE_RATIO)
        assertTrue(derived <= com.example.myapplication.SubDrawingGeometry.DEFAULT_BADGE_RATIO)
        assertEquals(0.04f, requireNotNull(vm.pageSubDrawings.getValue(3).single { it.id == "sub-b" }.badgeRatio), 0.0001f)
        // Nothing about the crop itself changed by supplying the default size.
        assertEquals(listOf("sub-path"), unsized.paths.map { it.id })
        assertEquals(36.5f, requireNotNull(unsized.scale).pointsPerFoot, 0f)
    }

    @Test
    fun malformedBadgeSizes_areRejectedBeforeTheyReachLiveState() {
        val snapshot = snapshotFromState(populatedVm(), source, snapshotRevision = 21L)
        val tree = gson.toJsonTree(snapshot).asJsonObject
        fun cropOf(root: JsonObject): JsonObject = root.getAsJsonObject("pages").getAsJsonObject("3")
            .getAsJsonArray("subDrawings").asJsonArray.first { it.asJsonObject.get("id").asString == "sub-a" }
            .asJsonObject

        // A radius outside the pinchable range, a non-number, and a string are all
        // untrusted input: none of them may become a live badge size.
        assertRejected {
            val mutated = tree.deepCopy()
            cropOf(mutated).addProperty("badgeRatio", 4f)
            validateCanonicalSnapshotTree(mutated)
        }
        assertRejected {
            val mutated = tree.deepCopy()
            cropOf(mutated).addProperty("badgeRatio", 0f)
            validateCanonicalSnapshotTree(mutated)
        }
        assertRejected {
            val mutated = tree.deepCopy()
            cropOf(mutated).addProperty("badgeRatio", -1f)
            validateCanonicalSnapshotTree(mutated)
        }
        assertRejected {
            val mutated = tree.deepCopy()
            cropOf(mutated).add("badgeRatio", gson.toJsonTree("big"))
            validateCanonicalSnapshotTree(mutated)
        }

        // The ViewModel-side check rejects the same sizes before recording them.
        val oversized = snapshot.pages.getValue(3).subDrawings.first { it.id == "sub-a" }
            .copy(badgeRatio = 4f)
        assertRejected {
            validateSnapshot(
                snapshot.copy(pages = mapOf(
                    3 to snapshot.pages.getValue(3).copy(
                        subDrawings = listOf(oversized, snapshot.pages.getValue(3).subDrawings.first { it.id == "sub-b" })
                    )
                ))
            )
        }
    }
    @Test
    fun subDrawingBadgePosition_roundTripsAndRejectsHalfOrOffPagePositions() {
        val captured = snapshotFromState(populatedVm(), source, snapshotRevision = 22L)
        val placed = captured.pages.getValue(3).subDrawings.map { if (it.id == "sub-a") it.copy(badgeX = 0.9f, badgeY = 0.05f) else it }
        val snapshot = captured.copy(pages = mapOf(3 to captured.pages.getValue(3).copy(subDrawings = placed)))
        validateSnapshot(snapshot)
        val tree = gson.toJsonTree(snapshot).asJsonObject
        validateCanonicalSnapshotTree(tree)
        val decoded = decodeValidatedSnapshotJson(gson, tree.toString(), "badge position snapshot")
        val vm = BlueprintViewModel()
        applySnapshotReplace(decoded, vm)
        val applied = vm.pageSubDrawings.getValue(3).single { it.id == "sub-a" }
        assertEquals(0.9f, requireNotNull(applied.badgeX), 0f)
        assertEquals(0.05f, requireNotNull(applied.badgeY), 0f)
        assertEquals(com.example.myapplication.Point(0.9f, 0.05f), com.example.myapplication.SubDrawingGeometry.badgeCenter(applied))
        // A crop without a position keeps its badge at the crop centre.
        val other = vm.pageSubDrawings.getValue(3).single { it.id == "sub-b" }
        assertNull(other.badgeX)
        assertEquals(com.example.myapplication.SubDrawingGeometry.center(other), com.example.myapplication.SubDrawingGeometry.badgeCenter(other))
        val recaptured = snapshotFromState(vm, source, snapshotRevision = 22L).pages.getValue(3).subDrawings.first { it.id == "sub-a" }
        assertEquals(0.9f, requireNotNull(recaptured.badgeX), 0f)

        fun cropOf(root: JsonObject): JsonObject = root.getAsJsonObject("pages").getAsJsonObject("3")
            .getAsJsonArray("subDrawings").asJsonArray.first { it.asJsonObject.get("id").asString == "sub-a" }.asJsonObject
        assertRejected { val m = tree.deepCopy(); cropOf(m).remove("badgeY"); validateCanonicalSnapshotTree(m) }
        assertRejected { val m = tree.deepCopy(); cropOf(m).addProperty("badgeX", 1.5f); validateCanonicalSnapshotTree(m) }
        assertRejected { validateSnapshot(snapshot.copy(pages = mapOf(3 to snapshot.pages.getValue(3).copy(
            subDrawings = placed.map { if (it.id == "sub-a") it.copy(badgeY = null) else it }))))
        }
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            org.junit.Assert.fail("malformed sub-drawing data should be rejected")
        } catch (_: com.example.myapplication.stage5.Stage5ValidationException) {
            // Expected: untrusted sub-drawing data never reaches live state.
        }
    }
}
