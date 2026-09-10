package com.example.myapplication.stage9b

import androidx.compose.runtime.mutableStateListOf
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.Note
import com.example.myapplication.PhotoImageNote
import com.example.myapplication.PhotoPin
import com.example.myapplication.Shape
import com.example.myapplication.ShapeType
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage1.NoteSnapshotV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PhotoImageNoteSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage1.ShapeSnapshotV1
import com.example.myapplication.stage1.SnapshotShapeTypeV1
import com.example.myapplication.stage1.snapshotFromState
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.Stage5ValidationException
import com.example.myapplication.stage5.validateSnapshot
import com.example.myapplication.stage8.AnnotationReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Stage 9B.0 annotation-admission contract.
 *
 * The reducer is the live-state mutation boundary, while validateSnapshot is
 * the persisted-state oracle.  These tests intentionally fail on the current
 * baseline until reducer admission mirrors the validator's text and photo
 * reference rules.
 */
class AnnotationAdmissionRegressionTest {
    private val liveSessionKey = Any()
    private val source = DocumentSourceIdentityV1(
        sourceUri = "content://stage9b/annotation-admission",
        displayName = "fixture.pdf"
    )

    @Test
    fun emptyPdfNoteIsRejectedWithoutStateHistoryOrEffect() {
        assertSnapshotRejected("empty PDF note", snapshotWithPdfNote(text = ""))

        val vm = BlueprintViewModel()
        vm.pageNotes[0] = mutableStateListOf()
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = liveReducer(vm, effects)
        val before = snapshot(vm).also(::validateSnapshot)

        assertFalse(reducer.addPdfNote(0, Note(.25f, .5f, text = "", id = "invalid-pdf-note")).changed)

        assertEquals(before, snapshot(vm))
        validateSnapshot(snapshot(vm))
        assertTrue(effects.isEmpty())
        assertFalse(reducer.canUndo(0))
        assertFalse(reducer.canRedo(0))
    }

    @Test
    fun oversizedPdfNoteIsRejectedWithoutStateHistoryOrEffect() {
        val oversizedText = "x".repeat(Stage5Limits.MAX_TEXT_CHARS + 1)
        assertEquals(32769, oversizedText.length)
        assertSnapshotRejected("oversized PDF note", snapshotWithPdfNote(oversizedText))

        val vm = BlueprintViewModel()
        vm.pageNotes[0] = mutableStateListOf()
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = liveReducer(vm, effects)
        val before = snapshot(vm).also(::validateSnapshot)

        assertFalse(reducer.addPdfNote(0, Note(.25f, .5f, oversizedText, id = "invalid-pdf-note")).changed)

        assertEquals(before, snapshot(vm))
        validateSnapshot(snapshot(vm))
        assertTrue(effects.isEmpty())
        assertFalse(reducer.canUndo(0))
        assertFalse(reducer.canRedo(0))
    }

    @Test
    fun unattachedPhotoImageNoteIsRejectedWithoutStateHistoryOrEffect() {
        val invalidNote = PhotoImageNote(.1f, .2f, "caption", id = "unattached-note")
        assertSnapshotRejected(
            "unattached image note",
            snapshotWithPhotoPin(imageNotes = mapOf("missing.jpg" to listOf(invalidNoteSnapshot())))
        )

        val vm = BlueprintViewModel()
        val pin = PhotoPin(
            .25f,
            .5f,
            id = "pin-with-unattached-target",
            imageFileNames = mutableListOf("attached.jpg")
        )
        vm.pagePhotoPins[0] = mutableStateListOf(pin)
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = liveReducer(vm, effects)
        val before = snapshot(vm).also(::validateSnapshot)

        assertFalse(reducer.addImageNote(0, pin.id, "missing.jpg", invalidNote).changed)

        assertEquals(before, snapshot(vm))
        validateSnapshot(snapshot(vm))
        assertEquals(listOf("attached.jpg"), pin.imageFileNames)
        assertTrue(pin.imageNotes.isEmpty())
        assertTrue(effects.isEmpty())
        assertFalse(reducer.canUndo(0))
        assertFalse(reducer.canRedo(0))
    }

    @Test
    fun unattachedPhotoImageShapeIsRejectedWithoutStateHistoryOrEffect() {
        val invalidShape = shape("unattached-shape")
        assertSnapshotRejected(
            "unattached image shape",
            snapshotWithPhotoPin(imageShapes = mapOf("missing.jpg" to listOf(invalidShapeSnapshot())))
        )

        val vm = BlueprintViewModel()
        val pin = PhotoPin(
            .25f,
            .5f,
            id = "pin-with-unattached-target",
            imageFileNames = mutableListOf("attached.jpg")
        )
        vm.pagePhotoPins[0] = mutableStateListOf(pin)
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = liveReducer(vm, effects)
        val before = snapshot(vm).also(::validateSnapshot)

        assertFalse(reducer.addImageShape(0, pin.id, "missing.jpg", invalidShape).changed)

        assertEquals(before, snapshot(vm))
        validateSnapshot(snapshot(vm))
        assertEquals(listOf("attached.jpg"), pin.imageFileNames)
        assertTrue(pin.imageShapes.isEmpty())
        assertTrue(effects.isEmpty())
        assertFalse(reducer.canUndo(0))
        assertFalse(reducer.canRedo(0))
    }

    @Test
    fun invalidPdfNoteUpdatesLeaveTheOriginalUnchanged() {
        listOf("", "x".repeat(Stage5Limits.MAX_TEXT_CHARS + 1)).forEach { invalidText ->
            assertSnapshotRejected("invalid PDF note update", snapshotWithPdfNote(invalidText))

            val vm = BlueprintViewModel()
            val original = Note(
                .25f,
                .5f,
                "original",
                rotation = 0f,
                fontSizeRatio = .02f,
                id = "original-pdf-note"
            )
            vm.pageNotes[0] = mutableStateListOf(original)
            val effects = mutableListOf<AnnotationReducer.EffectIntent>()
            val reducer = liveReducer(vm, effects)
            val before = snapshot(vm).also(::validateSnapshot)
            val replacement = original.copy(text = invalidText)

            assertFalse(reducer.updatePdfNoteAt(0, 0, replacement, before = original).changed)

            assertEquals(original, vm.pageNotes[0]!!.single())
            assertEquals(before, snapshot(vm))
            validateSnapshot(snapshot(vm))
            assertTrue(effects.isEmpty())
            assertFalse(reducer.canUndo(0))
            assertFalse(reducer.canRedo(0))
        }
    }

    @Test
    fun validPdfAndAttachedImageAnnotationsRemainAdmissible() {
        val vm = BlueprintViewModel()
        vm.pageNotes[0] = mutableStateListOf()
        val pin = PhotoPin(
            .25f,
            .5f,
            id = "attached-pin",
            imageFileNames = mutableListOf("photo.jpg")
        )
        vm.pagePhotoPins[0] = mutableStateListOf(pin)
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = liveReducer(vm, effects)

        assertTrue(reducer.addPdfNote(0, Note(.25f, .5f, "valid PDF note", id = "valid-pdf-note")).changed)
        val currentPdfNote = vm.pageNotes[0]!!.single()
        val editedPdfNote = currentPdfNote.copy(text = "valid PDF note updated")
        assertTrue(reducer.updatePdfNoteAt(0, 0, editedPdfNote, before = currentPdfNote).changed)
        assertTrue(
            reducer.addImageNote(
                0,
                pin.id,
                "photo.jpg",
                PhotoImageNote(.1f, .2f, "valid image note", id = "valid-image-note")
            ).changed
        )
        assertTrue(reducer.addImageShape(0, pin.id, "photo.jpg", shape("valid-image-shape")).changed)

        validateSnapshot(snapshot(vm))
        assertEquals(4, effects.size)
        assertEquals(
            listOf(
                AnnotationReducer.Kind.ADD,
                AnnotationReducer.Kind.UPDATE,
                AnnotationReducer.Kind.ADD,
                AnnotationReducer.Kind.ADD
            ),
            effects.map { it.kind }
        )
        assertTrue(reducer.canUndo(0))
        assertFalse(reducer.canRedo(0))
    }

    @Test
    fun unchangedPdfAndImageUpdatesProduceNoNewEffectOrHistory() {
        val vm = BlueprintViewModel()
        val pdfNote = Note(.25f, .5f, "unchanged", id = "unchanged-pdf-note")
        val imageNote = PhotoImageNote(.1f, .2f, "unchanged image", id = "unchanged-image-note")
        val imageShape = shape("unchanged-image-shape")
        val pin = PhotoPin(
            .25f,
            .5f,
            id = "attached-pin",
            imageFileNames = mutableListOf("photo.jpg"),
            imageNotes = mutableMapOf("photo.jpg" to mutableListOf(imageNote)),
            imageShapes = mutableMapOf("photo.jpg" to mutableListOf(imageShape))
        )
        vm.pageNotes[0] = mutableStateListOf(pdfNote)
        vm.pagePhotoPins[0] = mutableStateListOf(pin)
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = liveReducer(vm, effects)
        val before = snapshot(vm).also(::validateSnapshot)

        assertFalse(reducer.updatePdfNoteAt(0, 0, pdfNote.copyNote(), before = pdfNote).changed)
        assertFalse(
            reducer.updateImageNote(
                0,
                pin.id,
                "photo.jpg",
                imageNote,
                imageNote.copyImageNote()
            ).changed
        )
        assertFalse(
            reducer.updateImageShape(
                0,
                pin.id,
                "photo.jpg",
                imageShape,
                imageShape.copyShape()
            ).changed
        )

        assertEquals(before, snapshot(vm))
        validateSnapshot(snapshot(vm))
        assertTrue(effects.isEmpty())
        assertFalse(reducer.canUndo(0))
        assertFalse(reducer.canRedo(0))
    }

    private fun snapshot(vm: BlueprintViewModel): DocumentSnapshotV1 =
        snapshotFromState(vm, source)

    private fun liveReducer(
        vm: BlueprintViewModel,
        effects: MutableList<AnnotationReducer.EffectIntent>
    ): AnnotationReducer = AnnotationReducer(
        vm = vm,
        effectSink = { effects += it },
        sessionKey = liveSessionKey,
        currentSessionKey = { liveSessionKey },
        sessionActivePredicate = { true }
    )

    private fun assertSnapshotRejected(label: String, invalid: DocumentSnapshotV1) {
        try {
            validateSnapshot(invalid)
        } catch (_: Stage5ValidationException) {
            return
        }
        fail("$label unexpectedly passed snapshot validation")
    }

    private fun snapshotWithPdfNote(text: String): DocumentSnapshotV1 =
        DocumentSnapshotV1(
            schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
            snapshotRevision = 0L,
            source = source,
            pages = mapOf(
                0 to PageSnapshotV1(
                    notes = listOf(
                        NoteSnapshotV1(
                            x = .25f,
                            y = .5f,
                            text = text,
                            isBold = false,
                            rotation = 0f,
                            fontSizeRatio = .02f,
                            id = "pdf-note-fixture"
                        )
                    )
                )
            )
        )

    private fun snapshotWithPhotoPin(
        imageNotes: Map<String, List<PhotoImageNoteSnapshotV1>> = emptyMap(),
        imageShapes: Map<String, List<ShapeSnapshotV1>> = emptyMap()
    ): DocumentSnapshotV1 =
        DocumentSnapshotV1(
            schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
            snapshotRevision = 0L,
            source = source,
            pages = mapOf(
                0 to PageSnapshotV1(
                    photoPins = listOf(
                        PhotoPinSnapshotV1(
                            x = .25f,
                            y = .5f,
                            id = "pin-with-unattached-target",
                            imageFileNames = listOf("attached.jpg"),
                            imageNotes = imageNotes,
                            imageShapes = imageShapes
                        )
                    )
                )
            )
        )

    private fun invalidNoteSnapshot() = PhotoImageNoteSnapshotV1(
        x = .1f,
        y = .2f,
        text = "caption",
        isBold = false,
        rotation = 0f,
        fontSizeRatio = .02f,
        id = "unattached-note"
    )

    private fun invalidShapeSnapshot() = ShapeSnapshotV1(
        x = .5f,
        y = .5f,
        rotation = 0f,
        type = SnapshotShapeTypeV1.RECTANGLE,
        colorArgb = 1,
        isFilled = false,
        strokeWidthRatio = .005f,
        widthRatio = .2f,
        heightRatio = .1f,
        id = "unattached-shape"
    )

    private fun shape(id: String): Shape = Shape(
        x = .5f,
        y = .5f,
        rotation = 0f,
        type = ShapeType.RECTANGLE,
        colorArgb = 1,
        strokeWidthRatio = .005f,
        widthRatio = .2f,
        heightRatio = .1f,
        id = id
    )
}
