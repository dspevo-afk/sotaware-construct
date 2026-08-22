package com.example.myapplication.stage1

import androidx.compose.runtime.toMutableStateList
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.DrawnPath
import com.example.myapplication.Measurement
import com.example.myapplication.Note
import com.example.myapplication.PageData
import com.example.myapplication.PageScale
import com.example.myapplication.PhotoPin
import com.example.myapplication.Point
import com.example.myapplication.Shape
import com.example.myapplication.stage0.LegacyStateFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DocumentSnapshotV1RoundTripTest {
    private val source = DocumentSourceIdentityV1(
        sourceUri = "content://documents/current-plan.pdf",
        displayName = "current-plan.pdf",
        providerMetadata = mapOf("authority" to "com.example.documents")
    )

    @Test
    fun fullyPopulated_snapshotApplySnapshot_roundTripsEveryDomainExplicitly() {
        val vm = viewModelFrom(LegacyStateFixture.fullyPopulatedPageData())
        val first = snapshotFromState(vm, source, snapshotRevision = 17L)

        assertEquals(DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION, first.schemaVersion)
        assertEquals(17L, first.snapshotRevision)
        assertEquals(source.sourceUri, first.source.sourceUri)
        assertEquals(source.displayName, first.source.displayName)
        assertEquals(source.providerMetadata, first.source.providerMetadata)
        assertEquals(setOf(0, 2), first.pages.keys)

        val firstPage = first.pages.getValue(0)
        val firstScaleOnlyPage = first.pages.getValue(2)
        assertNotNull(firstPage.paths.single())
        assertNotNull(firstPage.measurements.single())
        assertNotNull(firstPage.notes.single())
        assertNotNull(firstPage.photoPins.single())
        assertNotNull(firstPage.shapes.single())
        assertEquals(42.75f, requireNotNull(firstPage.scale).pixelsPerFoot, 0.0f)
        assertEquals(18.5f, requireNotNull(firstScaleOnlyPage.scale).pixelsPerFoot, 0.0f)
        assertTrue(firstScaleOnlyPage.paths.isEmpty())
        assertTrue(firstScaleOnlyPage.measurements.isEmpty())
        assertTrue(firstScaleOnlyPage.notes.isEmpty())
        assertTrue(firstScaleOnlyPage.photoPins.isEmpty())
        assertTrue(firstScaleOnlyPage.shapes.isEmpty())

        applySnapshotReplace(first, vm)
        val second = snapshotFromState(vm, source, snapshotRevision = first.snapshotRevision)

        assertEquals(first, second)
        assertEquals(firstPage.paths, second.pages.getValue(0).paths)
        assertEquals(firstPage.measurements, second.pages.getValue(0).measurements)
        assertEquals(firstPage.notes, second.pages.getValue(0).notes)
        assertEquals(firstPage.shapes, second.pages.getValue(0).shapes)
        assertEquals(firstPage.scale, second.pages.getValue(0).scale)
        assertEquals(firstPage.photoPins, second.pages.getValue(0).photoPins)

        val firstPath = firstPage.paths.single()
        assertEquals(listOf(PointSnapshotV1(12.5f, 18.75f)), firstPath.points.take(1))
        assertEquals(-16711936, firstPath.colorArgb)
        assertEquals(7.25f, firstPath.strokeWidth, 0.0f)
        assertTrue(firstPath.isHighlighter)

        val firstMeasurement = firstPage.measurements.single()
        assertEquals(PointSnapshotV1(101.5f, 202.25f), firstMeasurement.p1)
        assertEquals(PointSnapshotV1(501.75f, 702.5f), firstMeasurement.p2)
        assertEquals("14' 6.25\"", firstMeasurement.text)

        val firstNote = firstPage.notes.single()
        assertEquals(0.31f, firstNote.x, 0.0f)
        assertEquals(0.47f, firstNote.y, 0.0f)
        assertEquals("LEGACY PAGE NOTE", firstNote.text)
        assertEquals(21.5f, firstNote.fontSize, 0.0f)
        assertTrue(firstNote.isBold)
        assertEquals(-12.0f, firstNote.rotation, 0.0f)

        val firstShape = firstPage.shapes.single()
        assertShape(firstShape, SnapshotShapeTypeV1.CLOUD, "page-shape-legacy-001")

        val firstPhoto = firstPage.photoPins.single()
        assertEquals(0.62f, firstPhoto.x, 0.0f)
        assertEquals(0.73f, firstPhoto.y, 0.0f)
        assertEquals("photo-pin-legacy-001", firstPhoto.id)
        assertEquals(listOf(LegacyStateFixture.PHOTO_ONE, LegacyStateFixture.PHOTO_TWO), firstPhoto.imageFileNames)
        assertEquals(setOf(LegacyStateFixture.PHOTO_ONE), firstPhoto.imageNotes.keys)
        assertEquals(setOf(LegacyStateFixture.PHOTO_ONE), firstPhoto.imageShapes.keys)

        val firstImageNote = firstPhoto.imageNotes.getValue(LegacyStateFixture.PHOTO_ONE).single()
        assertEquals(0.18f, firstImageNote.x, 0.0f)
        assertEquals(0.29f, firstImageNote.y, 0.0f)
        assertEquals("IMAGE NOTE WITH METADATA", firstImageNote.text)
        assertEquals(24.0f, firstImageNote.fontSize, 0.0f)
        assertTrue(firstImageNote.isBold)
        assertEquals(33.0f, firstImageNote.rotation, 0.0f)
        assertEquals(0.018f, firstImageNote.fontSizeRatio, 0.0f)
        assertEquals("image-note-legacy-001", firstImageNote.id)

        val firstImageShape = firstPhoto.imageShapes.getValue(LegacyStateFixture.PHOTO_ONE).single()
        assertShape(
            shape = firstImageShape,
            type = SnapshotShapeTypeV1.ARROW,
            id = "image-shape-legacy-001",
            x = 0.42f,
            y = 0.58f,
            width = 412.0f,
            height = 208.0f,
            rotation = 27.0f,
            colorArgb = -65536,
            strokeWidth = 5.0f,
            isFilled = false,
            strokeWidthRatio = 0.009f,
            widthRatio = 0.42f,
            heightRatio = 0.19f
        )
    }

    @Test
    fun scaleOnlyPage_survivesSnapshotRoundTrip() {
        val vm = BlueprintViewModel()
        vm.pageScales[4] = PageScale(8.25f)

        val snapshot = snapshotFromState(vm, source, snapshotRevision = 2L)
        assertEquals(setOf(4), snapshot.pages.keys)
        assertEquals(8.25f, requireNotNull(snapshot.pages.getValue(4).scale).pixelsPerFoot, 0.0f)
        assertTrue(snapshot.pages.getValue(4).paths.isEmpty())
        assertTrue(snapshot.pages.getValue(4).shapes.isEmpty())

        applySnapshotReplace(snapshot, vm)
        val roundTrip = snapshotFromState(vm, source, snapshotRevision = 2L)
        assertEquals(snapshot, roundTrip)
        assertEquals(8.25f, vm.pageScales.getValue(4).pixelsPerFoot, 0.0f)
    }

    @Test
    fun shapeOnlyPage_survivesSnapshotRoundTrip() {
        val vm = BlueprintViewModel()
        vm.pageShapes[7] = listOf(LegacyStateFixture.pageShape()).toMutableStateList()

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
        assertEquals("page-shape-legacy-001", vm.pageShapes.getValue(7).single().id)
    }

    @Test
    fun photoOnlyPage_preservesPhotoIdentityFilenamesAndNestedAnnotations() {
        val vm = BlueprintViewModel()
        val photo = LegacyStateFixture.fullyPopulatedPageData().getValue(0).photoPins.single()
        vm.pagePhotoPins[8] = listOf(photo).toMutableStateList()

        val snapshot = snapshotFromState(vm, source, snapshotRevision = 4L)
        val page = snapshot.pages.getValue(8)
        assertEquals(1, page.photoPins.size)
        assertTrue(page.paths.isEmpty())
        assertTrue(page.measurements.isEmpty())
        assertTrue(page.notes.isEmpty())
        assertTrue(page.shapes.isEmpty())
        assertNull(page.scale)
        assertEquals(listOf(LegacyStateFixture.PHOTO_ONE, LegacyStateFixture.PHOTO_TWO), page.photoPins.single().imageFileNames)
        assertEquals("image-note-legacy-001", page.photoPins.single().imageNotes.getValue(LegacyStateFixture.PHOTO_ONE).single().id)
        assertEquals("image-shape-legacy-001", page.photoPins.single().imageShapes.getValue(LegacyStateFixture.PHOTO_ONE).single().id)

        applySnapshotReplace(snapshot, vm)
        val roundTrip = snapshotFromState(vm, source, snapshotRevision = 4L)
        assertEquals(snapshot, roundTrip)
    }

    @Test
    fun emptyDocument_replacesPopulatedStateAndRoundTripsEmpty() {
        val dirty = viewModelFrom(LegacyStateFixture.fullyPopulatedPageData())
        dirty.pageHighlights[99] = emptyList()
        dirty.pageSearchTerms[99] = "stale search"
        val emptySource = BlueprintViewModel()
        val emptySnapshot = snapshotFromState(emptySource, source, snapshotRevision = 5L)

        applySnapshotReplace(emptySnapshot, dirty)

        assertTrue(dirty.pagePaths.isEmpty())
        assertTrue(dirty.pageMeasurements.isEmpty())
        assertTrue(dirty.pageNotes.isEmpty())
        assertTrue(dirty.pagePhotoPins.isEmpty())
        assertTrue(dirty.pageShapes.isEmpty())
        assertTrue(dirty.pageScales.isEmpty())
        assertTrue(dirty.pageHistory.isEmpty())
        assertTrue(dirty.pageRedoStack.isEmpty())
        assertTrue(dirty.pageHighlights.isEmpty())
        assertTrue(dirty.pageSearchTerms.isEmpty())
        assertEquals(emptySnapshot, snapshotFromState(dirty, source, snapshotRevision = 5L))
    }

    @Test
    fun multiplePagesAndGhostPageReplacement_removeAbsentPageFromEveryStateMap() {
        val incomingState = BlueprintViewModel()
        incomingState.pagePaths[0] = listOf(
            DrawnPath(listOf(Point(1f, 2f)), colorArgb = 1, strokeWidth = 2f, isHighlighter = false)
        ).toMutableStateList()
        incomingState.pagePhotoPins[1] = listOf(
            PhotoPin(0.1f, 0.2f, id = "incoming-photo", imageFileNames = mutableListOf("new.jpg"))
        ).toMutableStateList()
        incomingState.pageScales[2] = PageScale(12f)
        val incoming = snapshotFromState(incomingState, source, snapshotRevision = 6L)

        val dirty = viewModelFrom(LegacyStateFixture.fullyPopulatedPageData())
        dirty.pagePaths[99] = incomingState.pagePaths.getValue(0).toMutableStateList()
        dirty.pageMeasurements[99] = listOf(Measurement(Point(3f, 4f), Point(5f, 6f), "ghost")).toMutableStateList()
        dirty.pageNotes[99] = listOf(Note(0f, 0f, "ghost")).toMutableStateList()
        dirty.pagePhotoPins[99] = listOf(PhotoPin(0f, 0f, id = "ghost-photo")).toMutableStateList()
        dirty.pageShapes[99] = listOf(LegacyStateFixture.pageShape()).toMutableStateList()
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
        val dirty = viewModelFrom(LegacyStateFixture.fullyPopulatedPageData())
        val oldPhoto = LegacyStateFixture.fullyPopulatedPageData().getValue(0).photoPins.single()
        dirty.pagePhotoPins[1] = listOf(oldPhoto).toMutableStateList()
        dirty.pageScales[0] = PageScale(77f)

        val incomingState = BlueprintViewModel()
        incomingState.pagePaths[0] = listOf(
            DrawnPath(listOf(Point(9f, 9f)), colorArgb = 9, strokeWidth = 1f, isHighlighter = true)
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
        val vm = viewModelFrom(LegacyStateFixture.fullyPopulatedPageData())
        val before = snapshotFromState(vm, source, snapshotRevision = 18L)
        val externallyMutablePages = mutableMapOf(0 to PageSnapshotV1())
        val snapshot = DocumentSnapshotV1(
            schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
            snapshotRevision = 18L,
            source = source,
            pages = externallyMutablePages
        )

        // Simulate an unsafe caller mutating a collection supplied to the DTO.
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
        val vm = viewModelFrom(LegacyStateFixture.fullyPopulatedPageData())
        val snapshot = snapshotFromState(vm, source, snapshotRevision = 8L)
        val originalPage = snapshot.pages.getValue(0)
        val originalPhoto = originalPage.photoPins.single()
        val originalPathPoint = originalPage.paths.single().points.first()

        // Mutating live state after capture cannot mutate snapshot-owned scalars.
        vm.pagePaths.getValue(0).single().points.first().x = -100f
        vm.pageMeasurements.getValue(0).single().p1.x = -101f
        vm.pageNotes.getValue(0).single().text = "changed live note"
        vm.pageShapes.getValue(0).single().width = -102f
        vm.pageScales[0] = PageScale(-103f)
        vm.pagePhotoPins.getValue(0).single().x = -104f
        vm.pagePhotoPins.getValue(0).single().imageFileNames[0] = "changed-live.jpg"
        vm.pagePhotoPins.getValue(0).single().imageNotes.getValue(LegacyStateFixture.PHOTO_ONE).single().text = "changed live image note"
        vm.pagePhotoPins.getValue(0).single().imageShapes.getValue(LegacyStateFixture.PHOTO_ONE).single().width = -105f

        assertEquals(12.5f, originalPathPoint.x, 0.0f)
        assertEquals(101.5f, snapshot.pages.getValue(0).measurements.single().p1.x, 0.0f)
        assertEquals("LEGACY PAGE NOTE", snapshot.pages.getValue(0).notes.single().text)
        assertEquals(320.0f, snapshot.pages.getValue(0).shapes.single().width, 0.0f)
        assertEquals(42.75f, requireNotNull(snapshot.pages.getValue(0).scale).pixelsPerFoot, 0.0f)
        assertEquals(0.62f, originalPhoto.x, 0.0f)
        assertEquals(LegacyStateFixture.PHOTO_ONE, originalPhoto.imageFileNames.first())
        assertEquals("IMAGE NOTE WITH METADATA", originalPhoto.imageNotes.getValue(LegacyStateFixture.PHOTO_ONE).single().text)
        assertEquals(412.0f, originalPhoto.imageShapes.getValue(LegacyStateFixture.PHOTO_ONE).single().width, 0.0f)

        // Applying creates another deep copy; later live edits cannot mutate the snapshot.
        applySnapshotReplace(snapshot, vm)
        vm.pagePaths.getValue(0).single().points.first().y = -200f
        vm.pagePhotoPins.getValue(0).single().imageFileNames.add("later-live.jpg")
        vm.pagePhotoPins.getValue(0).single().imageNotes.getValue(LegacyStateFixture.PHOTO_ONE).single().text = "later live image note"
        vm.pagePhotoPins.getValue(0).single().imageShapes.getValue(LegacyStateFixture.PHOTO_ONE).single().height = -201f

        assertEquals(18.75f, originalPathPoint.y, 0.0f)
        assertEquals(listOf(LegacyStateFixture.PHOTO_ONE, LegacyStateFixture.PHOTO_TWO), originalPhoto.imageFileNames)
        assertEquals("IMAGE NOTE WITH METADATA", originalPhoto.imageNotes.getValue(LegacyStateFixture.PHOTO_ONE).single().text)
        assertEquals(208.0f, originalPhoto.imageShapes.getValue(LegacyStateFixture.PHOTO_ONE).single().height, 0.0f)

        // The mapper exposes read-only collection views, so direct snapshot mutation
        // fails instead of reaching the source state.
        @Suppress("UNCHECKED_CAST")
        val pages = snapshot.pages as MutableMap<Int, PageSnapshotV1>
        assertUnsupportedMutation { pages.clear() }
        @Suppress("UNCHECKED_CAST")
        val paths = originalPage.paths as MutableList<DrawnPathSnapshotV1>
        assertUnsupportedMutation { paths.clear() }
        assertEquals(2, snapshot.pages.size)
        assertEquals(1, vm.pagePaths.getValue(0).size)
    }

    @Test
    fun compatibilityAdapter_startsFromSnapshotAndReturnsFreshLegacyObjects() {
        val vm = viewModelFrom(LegacyStateFixture.fullyPopulatedPageData())
        val snapshot = snapshotFromState(vm, source, snapshotRevision = 9L)
        val legacy = snapshotToLegacyPageData(snapshot)

        assertEquals(setOf(0, 2), legacy.keys)
        val page = legacy.getValue(0)
        assertEquals(1, page.paths.size)
        assertEquals(1, page.measurements.size)
        assertEquals(1, page.notes.size)
        assertEquals(1, page.photoPins.size)
        assertEquals(1, page.shapes.size)
        assertEquals(42.75f, requireNotNull(page.scale).pixelsPerFoot, 0.0f)
        assertEquals("photo-pin-legacy-001", page.photoPins.single().id)
        assertEquals(listOf(LegacyStateFixture.PHOTO_ONE, LegacyStateFixture.PHOTO_TWO), page.photoPins.single().imageFileNames)
        assertEquals("image-note-legacy-001", page.photoPins.single().imageNotes.getValue(LegacyStateFixture.PHOTO_ONE).single().id)
        assertEquals("image-shape-legacy-001", page.photoPins.single().imageShapes.getValue(LegacyStateFixture.PHOTO_ONE).single().id)

        page.photoPins.single().imageFileNames.add("adapter-only.jpg")
        page.paths.single().points.first().x = -300f
        assertEquals(listOf(LegacyStateFixture.PHOTO_ONE, LegacyStateFixture.PHOTO_TWO), snapshot.pages.getValue(0).photoPins.single().imageFileNames)
        assertEquals(12.5f, snapshot.pages.getValue(0).paths.single().points.first().x, 0.0f)
    }

    private fun viewModelFrom(pageData: Map<Int, PageData>): BlueprintViewModel {
        val vm = BlueprintViewModel()
        pageData.forEach { (pageIndex, page) ->
            vm.pagePaths[pageIndex] = page.paths.toMutableStateList()
            vm.pageMeasurements[pageIndex] = page.measurements.toMutableStateList()
            vm.pageNotes[pageIndex] = page.notes.toMutableStateList()
            vm.pagePhotoPins[pageIndex] = page.photoPins.toMutableStateList()
            vm.pageShapes[pageIndex] = page.shapes.toMutableStateList()
            page.scale?.let { vm.pageScales[pageIndex] = it }
        }
        return vm
    }

    private fun logicalPageKeys(vm: BlueprintViewModel): Set<Int> =
        (vm.pagePaths.keys + vm.pageMeasurements.keys + vm.pageNotes.keys +
            vm.pagePhotoPins.keys + vm.pageShapes.keys + vm.pageScales.keys).toSet()

    private fun assertShape(
        shape: ShapeSnapshotV1,
        type: SnapshotShapeTypeV1,
        id: String,
        x: Float = 388.0f,
        y: Float = 244.0f,
        width: Float = 320.0f,
        height: Float = 180.0f,
        rotation: Float = 37.5f,
        colorArgb: Int = -16776961,
        strokeWidth: Float = 6.5f,
        isFilled: Boolean = true,
        strokeWidthRatio: Float = 0.0125f,
        widthRatio: Float = 0.27f,
        heightRatio: Float = 0.19f
    ) {
        assertEquals(type, shape.type)
        assertEquals(id, shape.id)
        assertEquals(x, shape.x, 0.0f)
        assertEquals(y, shape.y, 0.0f)
        assertEquals(width, shape.width, 0.0f)
        assertEquals(height, shape.height, 0.0f)
        assertEquals(rotation, shape.rotation, 0.0f)
        assertEquals(colorArgb, shape.colorArgb)
        assertEquals(strokeWidth, shape.strokeWidth, 0.0f)
        assertEquals(isFilled, shape.isFilled)
        assertEquals(strokeWidthRatio, shape.strokeWidthRatio, 0.0f)
        assertEquals(widthRatio, shape.widthRatio, 0.0f)
        assertEquals(heightRatio, shape.heightRatio, 0.0f)
    }

    private fun assertUnsupportedMutation(block: () -> Unit) {
        try {
            block()
            fail("snapshot collection should be read-only")
        } catch (_: UnsupportedOperationException) {
            // Expected: snapshot mutation must not be able to reach live state.
        }
    }
}
