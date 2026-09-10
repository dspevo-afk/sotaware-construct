package com.example.myapplication.stage1

import androidx.compose.runtime.toMutableStateList
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.DrawnPath
import com.example.myapplication.Measurement
import com.example.myapplication.Note
import com.example.myapplication.PageScale
import com.example.myapplication.PhotoPin
import com.example.myapplication.Point
import com.example.myapplication.stage0.CurrentStateFixture
import com.example.myapplication.stage8.AnnotationReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DocumentSnapshotV1RoundTripTest {
    private val source = CurrentStateFixture.source()

    @Test
    fun fullyPopulated_schema2ApplySnapshot_roundTripsEveryCurrentDomainExplicitly() {
        val vm = CurrentStateFixture.viewModel()
        val first = snapshotFromState(vm, source, snapshotRevision = 17L)
        val expected = CurrentStateFixture.fullyPopulatedSnapshot(source, snapshotRevision = 17L)

        assertEquals(DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION, first.schemaVersion)
        assertEquals(expected, first)
        assertEquals(17L, first.snapshotRevision)
        assertEquals(source, first.source)
        assertEquals(setOf(0, 2), first.pages.keys)

        val firstPage = first.pages.getValue(0)
        val firstScaleOnlyPage = first.pages.getValue(2)
        assertNotNull(firstPage.paths.single())
        assertNotNull(firstPage.measurements.single())
        assertNotNull(firstPage.notes.single())
        assertNotNull(firstPage.photoPins.single())
        assertNotNull(firstPage.shapes.single())
        assertEquals(42.75f, requireNotNull(firstPage.scale).pointsPerFoot, 0.0f)
        assertEquals(18.5f, requireNotNull(firstScaleOnlyPage.scale).pointsPerFoot, 0.0f)
        assertTrue(firstScaleOnlyPage.paths.isEmpty())
        assertTrue(firstScaleOnlyPage.measurements.isEmpty())
        assertTrue(firstScaleOnlyPage.notes.isEmpty())
        assertTrue(firstScaleOnlyPage.photoPins.isEmpty())
        assertTrue(firstScaleOnlyPage.shapes.isEmpty())

        applySnapshotReplace(first, vm)
        val second = snapshotFromState(vm, source, snapshotRevision = first.snapshotRevision)
        assertEquals(first, second)

        val path = firstPage.paths.single()
        assertEquals(CurrentStateFixture.PATH_ID, path.id)
        assertEquals(listOf(PointSnapshotV1(0.025f, 0.0625f)), path.points.take(1))
        assertEquals(0.0125f, path.strokeWidthRatio, 0.0f)
        assertTrue(path.isHighlighter)

        val measurement = firstPage.measurements.single()
        assertEquals(CurrentStateFixture.MEASUREMENT_ID, measurement.id)
        assertEquals(PointSnapshotV1(0.2f, 0.3f), measurement.p1)
        assertEquals(PointSnapshotV1(0.8f, 0.9f), measurement.p2)
        assertEquals("14' 6.25\"", measurement.text)

        val note = firstPage.notes.single()
        assertEquals(CurrentStateFixture.NOTE_ID, note.id)
        assertEquals(0.31f, note.x, 0.0f)
        assertEquals(0.47f, note.y, 0.0f)
        assertEquals("CURRENT PAGE NOTE", note.text)
        assertTrue(note.isBold)
        assertEquals(0.028f, note.fontSizeRatio, 0.0f)
        assertEquals(-12.0f, note.rotation, 0.0f)

        val shape = firstPage.shapes.single()
        assertEquals(CurrentStateFixture.PAGE_SHAPE_ID, shape.id)
        assertEquals(SnapshotShapeTypeV1.CLOUD, shape.type)
        assertEquals(0.56f, shape.x, 0.0f)
        assertEquals(0.42f, shape.y, 0.0f)
        assertEquals(0.27f, shape.widthRatio, 0.0f)
        assertEquals(0.19f, shape.heightRatio, 0.0f)
        assertEquals(0.0125f, shape.strokeWidthRatio, 0.0f)

        val photo = firstPage.photoPins.single()
        assertEquals(CurrentStateFixture.PHOTO_PIN_ID, photo.id)
        assertEquals(listOf(CurrentStateFixture.PHOTO_ONE, CurrentStateFixture.PHOTO_TWO), photo.imageFileNames)
        assertEquals(setOf(CurrentStateFixture.PHOTO_ONE), photo.imageNotes.keys)
        assertEquals(setOf(CurrentStateFixture.PHOTO_ONE), photo.imageShapes.keys)
        assertEquals(
            CurrentStateFixture.IMAGE_NOTE_ID,
            photo.imageNotes.getValue(CurrentStateFixture.PHOTO_ONE).single().id
        )
        assertEquals(
            CurrentStateFixture.IMAGE_SHAPE_ID,
            photo.imageShapes.getValue(CurrentStateFixture.PHOTO_ONE).single().id
        )
    }

    @Test
    fun scaleOnlyPage_survivesCurrentSnapshotRoundTrip() {
        val vm = BlueprintViewModel()
        vm.pageScales[4] = PageScale(8.25f)

        val snapshot = snapshotFromState(vm, source, snapshotRevision = 2L)
        assertEquals(setOf(4), snapshot.pages.keys)
        assertEquals(8.25f, requireNotNull(snapshot.pages.getValue(4).scale).pointsPerFoot, 0.0f)
        assertTrue(snapshot.pages.getValue(4).paths.isEmpty())
        assertTrue(snapshot.pages.getValue(4).shapes.isEmpty())

        applySnapshotReplace(snapshot, vm)
        val roundTrip = snapshotFromState(vm, source, snapshotRevision = 2L)
        assertEquals(snapshot, roundTrip)
        assertEquals(8.25f, vm.pageScales.getValue(4).pointsPerFoot, 0.0f)
    }

    @Test
    fun shapeOnlyPage_survivesCurrentSnapshotRoundTrip() {
        val vm = BlueprintViewModel()
        vm.pageShapes[7] = listOf(CurrentStateFixture.pageShape()).toMutableStateList()

        val snapshot = snapshotFromState(vm, source, snapshotRevision = 3L)
        val page = snapshot.pages.getValue(7)
        assertEquals(1, page.shapes.size)
        assertTrue(page.paths.isEmpty())
        assertTrue(page.measurements.isEmpty())
        assertTrue(page.notes.isEmpty())
        assertTrue(page.photoPins.isEmpty())
        assertNull(page.scale)

        applySnapshotReplace(snapshot, vm)
        val roundTrip = snapshotFromState(vm, source, snapshotRevision = 3L)
        assertEquals(snapshot, roundTrip)
        assertEquals(CurrentStateFixture.PAGE_SHAPE_ID, vm.pageShapes.getValue(7).single().id)
    }

    @Test
    fun photoOnlyPage_preservesPhotoIdentityFilenamesAndNestedAnnotations() {
        val vm = BlueprintViewModel()
        val photo = CurrentStateFixture.photoPin()
        vm.pagePhotoPins[8] = listOf(photo).toMutableStateList()

        val snapshot = snapshotFromState(vm, source, snapshotRevision = 4L)
        val page = snapshot.pages.getValue(8)
        assertEquals(1, page.photoPins.size)
        assertTrue(page.paths.isEmpty())
        assertTrue(page.measurements.isEmpty())
        assertTrue(page.notes.isEmpty())
        assertTrue(page.shapes.isEmpty())
        assertNull(page.scale)
        assertEquals(listOf(CurrentStateFixture.PHOTO_ONE, CurrentStateFixture.PHOTO_TWO), page.photoPins.single().imageFileNames)
        assertEquals(CurrentStateFixture.IMAGE_NOTE_ID, page.photoPins.single().imageNotes.getValue(CurrentStateFixture.PHOTO_ONE).single().id)
        assertEquals(CurrentStateFixture.IMAGE_SHAPE_ID, page.photoPins.single().imageShapes.getValue(CurrentStateFixture.PHOTO_ONE).single().id)

        applySnapshotReplace(snapshot, vm)
        val roundTrip = snapshotFromState(vm, source, snapshotRevision = 4L)
        assertEquals(snapshot, roundTrip)
    }

    @Test
    fun emptyDocument_replacesPopulatedStateAndRoundTripsEmpty() {
        val dirty = CurrentStateFixture.viewModel()
        dirty.pageHighlights[99] = emptyList()
        dirty.pageSearchTerms[99] = "stale search"
        val emptySnapshot = emptySnapshot(source, revision = 5L)
        val session = Any()
        val reducer = AnnotationReducer(
            dirty,
            sessionKey = session,
            currentSessionKey = { session },
            sessionActivePredicate = { true }
        )
        assertTrue(reducer.addPdfNote(0, Note(0.2f, 0.2f, "history entry", id = "history-entry")).changed)
        assertTrue(reducer.canUndo(0))
        assertTrue(reducer.undo(0).changed)
        assertTrue(reducer.canRedo(0))

        applySnapshotReplace(emptySnapshot, dirty)

        assertTrue(dirty.pagePaths.isEmpty())
        assertTrue(dirty.pageMeasurements.isEmpty())
        assertTrue(dirty.pageNotes.isEmpty())
        assertTrue(dirty.pagePhotoPins.isEmpty())
        assertTrue(dirty.pageShapes.isEmpty())
        assertTrue(dirty.pageScales.isEmpty())
        val replacementReducer = AnnotationReducer(
            dirty,
            sessionKey = session,
            currentSessionKey = { session },
            sessionActivePredicate = { true }
        )
        assertFalse(reducer.canUndo(0))
        assertFalse(reducer.canRedo(0))
        assertFalse(replacementReducer.canUndo(0))
        assertFalse(replacementReducer.canRedo(0))
        assertTrue(dirty.pageHighlights.isEmpty())
        assertTrue(dirty.pageSearchTerms.isEmpty())
        assertEquals(emptySnapshot, snapshotFromState(dirty, source, snapshotRevision = 5L))
    }

    @Test
    fun multiplePagesAndGhostPageReplacement_removeAbsentPageFromEveryStateMap() {
        val incomingState = BlueprintViewModel()
        incomingState.pagePaths[0] = listOf(
            DrawnPath(
                points = listOf(Point(0.1f, 0.2f)),
                colorArgb = 1,
                isHighlighter = false,
                strokeWidthRatio = 0.005f,
                id = "incoming-path"
            )
        ).toMutableStateList()
        incomingState.pagePhotoPins[1] = listOf(
            PhotoPin(0.1f, 0.2f, id = "incoming-photo", imageFileNames = mutableListOf("new.jpg"))
        ).toMutableStateList()
        incomingState.pageScales[2] = PageScale(12f)
        val incoming = snapshotFromState(incomingState, source, snapshotRevision = 6L)

        val dirty = CurrentStateFixture.viewModel()
        dirty.pagePaths[99] = incomingState.pagePaths.getValue(0).toMutableStateList()
        dirty.pageMeasurements[99] = listOf(Measurement(Point(0.3f, 0.4f), Point(0.5f, 0.6f), "ghost", "ghost-measurement")).toMutableStateList()
        dirty.pageNotes[99] = listOf(Note(0f, 0f, "ghost", id = "ghost-note")).toMutableStateList()
        dirty.pagePhotoPins[99] = listOf(PhotoPin(0f, 0f, id = "ghost-photo")).toMutableStateList()
        dirty.pageShapes[99] = listOf(CurrentStateFixture.pageShape()).toMutableStateList()
        dirty.pageScales[99] = PageScale(99f)

        applySnapshotReplace(incoming, dirty)

        assertEquals(setOf(0, 1, 2), logicalPageKeys(dirty))
        assertFalse(dirty.pagePaths.containsKey(99))
        assertFalse(dirty.pageMeasurements.containsKey(99))
        assertFalse(dirty.pageNotes.containsKey(99))
        assertFalse(dirty.pagePhotoPins.containsKey(99))
        assertFalse(dirty.pageShapes.containsKey(99))
        assertFalse(dirty.pageScales.containsKey(99))
        assertEquals(setOf(0, 1, 2), dirty.pagePaths.keys)
        assertEquals(setOf(0, 1, 2), dirty.pageMeasurements.keys)
        assertEquals(setOf(0, 1, 2), dirty.pageNotes.keys)
        assertEquals(setOf(0, 1, 2), dirty.pagePhotoPins.keys)
        assertEquals(setOf(0, 1, 2), dirty.pageShapes.keys)
        assertEquals(setOf(2), dirty.pageScales.keys)
    }

    @Test
    fun replacement_removesEmptyDomainsAndNestedPhotoDataFromExistingPages() {
        val dirty = CurrentStateFixture.viewModel()
        val oldPhoto = CurrentStateFixture.photoPin()
        dirty.pagePhotoPins[1] = listOf(oldPhoto).toMutableStateList()
        dirty.pageScales[0] = PageScale(77f)

        val incomingState = BlueprintViewModel()
        incomingState.pagePaths[0] = listOf(
            DrawnPath(
                listOf(Point(0.9f, 0.9f)),
                colorArgb = 9,
                isHighlighter = true,
                strokeWidthRatio = 0.005f,
                id = "replacement-path"
            )
        ).toMutableStateList()
        val newPhoto = PhotoPin(
            x = 0.8f,
            y = 0.9f,
            id = oldPhoto.id,
            imageFileNames = mutableListOf("replacement.jpg")
        )
        incomingState.pagePhotoPins[1] = listOf(newPhoto).toMutableStateList()
        val incoming = snapshotFromState(incomingState, source, snapshotRevision = 7L)

        applySnapshotReplace(incoming, dirty)

        assertTrue(dirty.pageMeasurements.getValue(0).isEmpty())
        assertTrue(dirty.pageNotes.getValue(0).isEmpty())
        assertTrue(dirty.pagePhotoPins.getValue(0).isEmpty())
        assertTrue(dirty.pageShapes.getValue(0).isEmpty())
        assertFalse(dirty.pageScales.containsKey(0))
        assertEquals(listOf("replacement.jpg"), dirty.pagePhotoPins.getValue(1).single().imageFileNames)
        assertTrue(dirty.pagePhotoPins.getValue(1).single().imageNotes.isEmpty())
        assertTrue(dirty.pagePhotoPins.getValue(1).single().imageShapes.isEmpty())
        assertEquals(setOf(0, 1), logicalPageKeys(dirty))
    }

    @Test
    fun replacement_materializesBeforeMutation_whenSnapshotBackingMapIsExternallyCorrupted() {
        val vm = CurrentStateFixture.viewModel()
        val before = snapshotFromState(vm, source, snapshotRevision = 18L)
        val externallyMutablePages = mutableMapOf(0 to PageSnapshotV1())
        val snapshot = DocumentSnapshotV1(
            schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
            snapshotRevision = 18L,
            source = source,
            pages = externallyMutablePages
        )

        externallyMutablePages[-1] = PageSnapshotV1()

        try {
            applySnapshotReplace(snapshot, vm)
            fail("invalid page index should be rejected before replacement")
        } catch (_: IllegalArgumentException) {
            // Expected: the current state must remain intact after validation failure.
        }

        assertEquals(before, snapshotFromState(vm, source, snapshotRevision = 18L))
    }

    @Test
    fun snapshotAndAppliedState_doNotShareMutableReferences() {
        val vm = CurrentStateFixture.viewModel()
        val snapshot = snapshotFromState(vm, source, snapshotRevision = 8L)
        val originalPage = snapshot.pages.getValue(0)
        val originalPhoto = originalPage.photoPins.single()
        val originalPathPoint = originalPage.paths.single().points.first()

        // Live state edits are replacements of immutable committed values; the
        // captured current snapshot must remain detached from those values.
        vm.pagePaths[0] = listOf(
            vm.pagePaths.getValue(0).single().copy(points = listOf(Point(-1f, -1f)))
        ).toMutableStateList()
        vm.pageMeasurements[0] = listOf(
            vm.pageMeasurements.getValue(0).single().copy(p1 = Point(-1f, -1f))
        ).toMutableStateList()
        vm.pageNotes[0] = listOf(vm.pageNotes.getValue(0).single().copy(text = "changed live note")).toMutableStateList()
        vm.pageShapes[0] = listOf(vm.pageShapes.getValue(0).single().copy(widthRatio = 0.5f)).toMutableStateList()
        vm.pageScales[0] = PageScale(103f)
        vm.pagePhotoPins[0] = listOf(vm.pagePhotoPins.getValue(0).single().copyPin().copy(x = -1f)).toMutableStateList()

        assertEquals(0.025f, originalPathPoint.x, 0.0f)
        assertEquals(0.2f, snapshot.pages.getValue(0).measurements.single().p1.x, 0.0f)
        assertEquals("CURRENT PAGE NOTE", snapshot.pages.getValue(0).notes.single().text)
        assertEquals(0.27f, snapshot.pages.getValue(0).shapes.single().widthRatio, 0.0f)
        assertEquals(42.75f, requireNotNull(snapshot.pages.getValue(0).scale).pointsPerFoot, 0.0f)
        assertEquals(0.62f, originalPhoto.x, 0.0f)
        assertEquals(CurrentStateFixture.PHOTO_ONE, originalPhoto.imageFileNames.first())
        assertEquals("IMAGE NOTE WITH METADATA", originalPhoto.imageNotes.getValue(CurrentStateFixture.PHOTO_ONE).single().text)
        assertEquals(0.42f, originalPhoto.imageShapes.getValue(CurrentStateFixture.PHOTO_ONE).single().widthRatio, 0.0f)

        applySnapshotReplace(snapshot, vm)
        vm.pagePaths[0] = listOf(vm.pagePaths.getValue(0).single().copy(points = listOf(Point(-2f, -2f)))).toMutableStateList()
        vm.pagePhotoPins[0] = listOf(
            vm.pagePhotoPins.getValue(0).single().copy(
                imageFileNames = vm.pagePhotoPins.getValue(0).single().imageFileNames + "later-live.jpg"
            )
        ).toMutableStateList()

        assertEquals(0.0625f, originalPathPoint.y, 0.0f)
        assertEquals(listOf(CurrentStateFixture.PHOTO_ONE, CurrentStateFixture.PHOTO_TWO), originalPhoto.imageFileNames)
        assertEquals("IMAGE NOTE WITH METADATA", originalPhoto.imageNotes.getValue(CurrentStateFixture.PHOTO_ONE).single().text)
        assertEquals(0.19f, originalPhoto.imageShapes.getValue(CurrentStateFixture.PHOTO_ONE).single().heightRatio, 0.0f)

        @Suppress("UNCHECKED_CAST")
        val pages = snapshot.pages as MutableMap<Int, PageSnapshotV1>
        assertUnsupportedMutation { pages.clear() }
        @Suppress("UNCHECKED_CAST")
        val paths = originalPage.paths as MutableList<DrawnPathSnapshotV1>
        assertUnsupportedMutation { paths.clear() }
        assertEquals(2, snapshot.pages.size)
        assertEquals(1, vm.pagePaths.getValue(0).size)
    }

    private fun emptySnapshot(source: DocumentSourceIdentityV1, revision: Long) =
        DocumentSnapshotV1(
            schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
            snapshotRevision = revision,
            source = source,
            pages = emptyMap()
        )

    private fun logicalPageKeys(vm: BlueprintViewModel): Set<Int> =
        (vm.pagePaths.keys + vm.pageMeasurements.keys + vm.pageNotes.keys +
            vm.pagePhotoPins.keys + vm.pageShapes.keys + vm.pageScales.keys).toSet()

    private fun assertUnsupportedMutation(block: () -> Unit) {
        try {
            block()
            fail("snapshot collection should be read-only")
        } catch (_: UnsupportedOperationException) {
            // Expected: snapshot mutation must not be able to reach live state.
        }
    }
}
