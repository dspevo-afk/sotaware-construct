package com.example.myapplication.stage9b

import com.example.myapplication.stage1.*
import com.example.myapplication.stage5.Stage5ValidationException
import com.example.myapplication.stage5.validateSnapshot
import com.example.myapplication.stage5.validateCanonicalSnapshotTree
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Current-format oracles exercise semantics, not accidental rejection of old schemas. */
class CanonicalAnnotationValidationTest {
    private fun note(id: String = "note-a") = NoteSnapshotV1(.5f, .5f, "line one\nline two", true, 15f, .02f, id)
    private fun shape(id: String = "shape-a") = ShapeSnapshotV1(.4f, .5f, 30f, SnapshotShapeTypeV1.ARROW, -65536, false, .005f, .2f, .1f, id)
    private fun page() = PageSnapshotV1(
        paths = listOf(DrawnPathSnapshotV1(listOf(PointSnapshotV1(.1f, .2f), PointSnapshotV1(.8f, .6f)), -1, false, .005f, "path-a")),
        measurements = listOf(MeasurementSnapshotV1(PointSnapshotV1(.1f, .1f), PointSnapshotV1(.5f, .5f), "ten feet", "measure-a")),
        notes = listOf(note()),
        photoPins = listOf(PhotoPinSnapshotV1(.3f, .4f, "pin-a", listOf("photo.jpg"),
            mapOf("photo.jpg" to listOf(PhotoImageNoteSnapshotV1(.2f, .4f, "caption", false, 0f, .02f, "image-note-a"))),
            mapOf("photo.jpg" to listOf(shape("image-shape-a"))))),
        scale = PageScaleSnapshotV1(72f), shapes = listOf(shape())
    )
    private fun snapshot(page: PageSnapshotV1 = page()) = DocumentSnapshotV1(
        DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION, 1, DocumentSourceIdentityV1("content://synthetic/current", "fixture.pdf"), mapOf(0 to page))
    private fun rejected(snapshot: DocumentSnapshotV1) {
        assertThrows(Stage5ValidationException::class.java) { validateSnapshot(snapshot) }
        assertThrows(Stage5ValidationException::class.java) { validateCanonicalSnapshotTree(Gson().toJsonTree(snapshot).asJsonObject) }
    }

    @Test fun allCurrentDomainsPassBothTypedAndWireValidation() {
        val value = snapshot()
        validateSnapshot(value)
        validateCanonicalSnapshotTree(Gson().toJsonTree(value).asJsonObject)
        assertEquals(72f, value.pages.getValue(0).scale!!.pointsPerFoot)
    }
    @Test fun equalContentWithDistinctStableIdsRemainsIndependent() {
        val value = snapshot(page().copy(notes = listOf(note("one"), note("two"))))
        validateSnapshot(value)
        validateCanonicalSnapshotTree(Gson().toJsonTree(value).asJsonObject)
    }
    @Test fun duplicateIdentityAcrossPageDomainsIsRejected() {
        rejected(snapshot(page().copy(notes = listOf(note("shape-a")))))
    }
    @Test fun blankAndOversizedTextFailAtBothBoundaries() {
        listOf("", "x".repeat(32769)).forEach { rejected(snapshot(page().copy(notes = listOf(note().copy(text = it))))) }
    }
    @Test fun invalidNormalizedGeometryAndRatiosAreRejected() {
        rejected(snapshot(page().copy(notes = listOf(note().copy(x = 1.01f)))))
        rejected(snapshot(page().copy(notes = listOf(note().copy(fontSizeRatio = 0f)))))
        rejected(snapshot(page().copy(shapes = listOf(shape().copy(widthRatio = 0f)))))
        rejected(snapshot(page().copy(scale = PageScaleSnapshotV1(0f))))
    }
    @Test fun detachedImageAnnotationIsRejected() {
        val value = page()
        val pin = value.photoPins.single().copy(imageFileNames = emptyList())
        rejected(snapshot(value.copy(photoPins = listOf(pin))))
    }
    @Test fun duplicateIdentityWithinOnePhotoSurfaceIsRejected() {
        val value = page(); val pin = value.photoPins.single()
        val duplicate = pin.copy(imageShapes = mapOf("photo.jpg" to listOf(shape("image-note-a"))))
        rejected(snapshot(value.copy(photoPins = listOf(duplicate))))
    }
    @Test fun malformedMissingIdCannotUseJvmDefault() {
        val tree = Gson().toJsonTree(snapshot()).asJsonObject
        tree.getAsJsonObject("pages").getAsJsonObject("0").getAsJsonArray("notes")[0].asJsonObject.remove("id")
        assertThrows(Stage5ValidationException::class.java) { validateCanonicalSnapshotTree(tree) }
    }
    @Test fun retiredAbsoluteGeometryAndSnapshotVersionAreExplicitlyRejected() {
        val tree = Gson().toJsonTree(snapshot()).asJsonObject
        val oldVersion = tree.deepCopy().apply { addProperty("schemaVersion", 1) }
        assertThrows(Stage5ValidationException::class.java) { validateCanonicalSnapshotTree(oldVersion) }
        tree.getAsJsonObject("pages").getAsJsonObject("0").getAsJsonArray("notes")[0].asJsonObject.addProperty("fontSize", 16f)
        assertThrows(Stage5ValidationException::class.java) { validateCanonicalSnapshotTree(tree) }
    }
}
