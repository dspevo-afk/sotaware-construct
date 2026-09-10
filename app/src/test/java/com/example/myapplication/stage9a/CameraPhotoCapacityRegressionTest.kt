package com.example.myapplication.stage9a

import androidx.compose.runtime.mutableStateListOf
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.PhotoPin
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage4.Stage4PhotoFixture
import com.example.myapplication.stage5.DocumentPhotoAssetStore
import com.example.myapplication.stage5.ImageIoPhotoDecodeProbe
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.Stage5ValidationException
import com.example.myapplication.stage5.TestPhotoPathOperationsFactory
import com.example.myapplication.stage8.AnnotationReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

/** Regression coverage for the Stage 9A camera/reducer photo-capacity boundary. */
class CameraPhotoCapacityRegressionTest {
    @Test
    fun attachPhoto_rejectsPhotoBeyondPerPinLimitWithoutMutationOrEffect() {
        val vm = BlueprintViewModel()
        val pin = PhotoPin(
            x = 0.25f,
            y = 0.50f,
            id = "full-pin",
            imageFileNames = MutableList(Stage5Limits.MAX_PHOTOS_PER_PIN) { index ->
                "photo-pin-$index.jpg"
            }
        )
        vm.pagePhotoPins[0] = mutableStateListOf(pin)
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = AnnotationReducer(
            vm,
            effectSink = { effects += it },
            sessionKey = "camera-capacity-test",
            currentSessionKey = { "camera-capacity-test" },
            sessionActivePredicate = { true }
        )

        assertFalse(reducer.attachPhoto(0, pin, "photo-overflow.jpg").changed)
        assertEquals(Stage5Limits.MAX_PHOTOS_PER_PIN, pin.imageFileNames.size)
        assertEquals(emptyList<AnnotationReducer.EffectIntent>(), effects)
        assertFalse(reducer.canUndo(0))
    }

    @Test
    fun attachPhoto_rejectsPhotoBeyondDocumentReferenceLimitWithoutMutationOrEffect() {
        val vm = BlueprintViewModel()
        val pins = mutableStateListOf<PhotoPin>()
        var remaining = Stage5Limits.MAX_TOTAL_PHOTOS
        var pinOrdinal = 0
        while (remaining > 0) {
            val count = minOf(remaining, Stage5Limits.MAX_PHOTOS_PER_PIN - 1)
            pins += PhotoPin(
                x = 0.1f,
                y = 0.1f,
                id = "pin-$pinOrdinal",
                imageFileNames = MutableList(count) { photoOrdinal ->
                    "photo-$pinOrdinal-$photoOrdinal.jpg"
                }
            )
            remaining -= count
            pinOrdinal++
        }
        vm.pagePhotoPins[0] = pins
        val target = pins.last()
        // Construction deliberately keeps the target below its per-pin cap so
        // this assertion isolates the document-wide reference ceiling.
        assertFalse(target.imageFileNames.size >= Stage5Limits.MAX_PHOTOS_PER_PIN)
        assertEquals(
            Stage5Limits.MAX_TOTAL_PHOTOS,
            pins.sumOf { it.imageFileNames.size }
        )
        val effects = mutableListOf<AnnotationReducer.EffectIntent>()
        val reducer = AnnotationReducer(
            vm,
            effectSink = { effects += it },
            sessionKey = "camera-capacity-test",
            currentSessionKey = { "camera-capacity-test" },
            sessionActivePredicate = { true }
        )

        assertFalse(reducer.attachPhoto(0, target, "photo-document-overflow.jpg").changed)
        assertEquals(Stage5Limits.MAX_TOTAL_PHOTOS, pins.sumOf { it.imageFileNames.size })
        assertEquals(emptyList<AnnotationReducer.EffectIntent>(), effects)
        assertFalse(reducer.canUndo(0))
    }
    @Test
    fun photoStore_rejectsNewPublicationWhenDocumentReferenceLimitIsAlreadyFull() {
        val root = Files.createTempDirectory("stage9a-camera-photo-capacity").toFile()
        val store = DocumentPhotoAssetStore(
            filesDirectory = root,
            documentId = DocumentId.new(),
            imageProbe = ImageIoPhotoDecodeProbe,
            operationsFactory = TestPhotoPathOperationsFactory
        )
        try {
            val references = (0 until Stage5Limits.MAX_TOTAL_PHOTOS)
                .map { "photo-${UUID.randomUUID()}.jpg" }
                .toSet()
            val error = try {
                store.publishNewPhoto(
                    bytes = Stage4PhotoFixture.previousJpegBytes(),
                    existingPhotoReferences = references
                )
                null
            } catch (failure: Stage5ValidationException) {
                failure
            }
            requireNotNull(error) { "publication unexpectedly exceeded the document photo-reference limit" }
            assertEquals("referenced photo count exceeds its limit", error.message)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

}
