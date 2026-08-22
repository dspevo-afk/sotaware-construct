package com.example.myapplication.stage0

import androidx.compose.runtime.toMutableStateList
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.buildPageDataForSync
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPayloadCharacterizationTest {
    @Test
    fun characterization_syncPayloadSeam_preservesAllDomainsAndScaleOnlyPages() {
        val vm = BlueprintViewModel()
        val expected = LegacyStateFixture.fullyPopulatedPageData()
        val populated = expected.getValue(0)

        vm.pagePaths[0] = populated.paths.toMutableStateList()
        vm.pageMeasurements[0] = populated.measurements.toMutableStateList()
        vm.pageNotes[0] = populated.notes.toMutableStateList()
        vm.pagePhotoPins[0] = populated.photoPins.toMutableStateList()
        vm.pageShapes[0] = populated.shapes.toMutableStateList()
        vm.pageScales[0] = requireNotNull(populated.scale)
        vm.pageScales[2] = requireNotNull(expected.getValue(2).scale)

        val payload = buildPageDataForSync(
            vm,
            DocumentSourceIdentityV1(
                sourceUri = "content://stage0/plan-a.pdf",
                displayName = "plan-a.pdf",
                providerMetadata = mapOf("authority" to "stage0")
            )
        )
        assertEquals(setOf(0, 2), payload.keys)
        assertEquals(populated.paths, payload.getValue(0).paths)
        assertEquals(populated.measurements, payload.getValue(0).measurements)
        assertEquals(populated.notes, payload.getValue(0).notes)
        assertEquals(populated.photoPins, payload.getValue(0).photoPins)
        assertEquals(populated.shapes, payload.getValue(0).shapes)
        assertEquals(populated.scale, payload.getValue(0).scale)
        assertEquals(expected.getValue(2).scale, payload.getValue(2).scale)
        assertEquals(emptyList<Any>(), payload.getValue(2).paths)
        assertEquals(emptyList<Any>(), payload.getValue(2).shapes)
    }
}
