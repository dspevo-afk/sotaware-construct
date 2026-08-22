package com.example.myapplication.stage1

import androidx.compose.runtime.toMutableStateList
import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.PageData
import com.example.myapplication.stage0.LegacyStateFixture
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Network-free proof that the four existing upload entry points share the
 * same Stage 1 capture/legacy-adapter path. The production call sites all
 * invoke buildPageDataForSync(), so this test exercises that exact adapter
 * with route-labelled captures and compares the reconstructed snapshots.
 */
class SyncRouteEquivalenceTest {
    private val source = DocumentSourceIdentityV1(
        sourceUri = "content://documents/current-plan.pdf",
        displayName = "current-plan.pdf",
        providerMetadata = mapOf("authority" to "com.example.documents")
    )

    @Test
    fun immediateDebouncedAutomaticAndManualRoutes_produceIdenticalSnapshots() {
        val vm = BlueprintViewModel()
        LegacyStateFixture.fullyPopulatedPageData().forEach { (pageIndex, page) ->
            vm.pagePaths[pageIndex] = page.paths.toMutableStateList()
            vm.pageMeasurements[pageIndex] = page.measurements.toMutableStateList()
            vm.pageNotes[pageIndex] = page.notes.toMutableStateList()
            vm.pagePhotoPins[pageIndex] = page.photoPins.toMutableStateList()
            vm.pageShapes[pageIndex] = page.shapes.toMutableStateList()
            page.scale?.let { vm.pageScales[pageIndex] = it }
        }

        val routePayloads: List<Pair<String, Map<Int, PageData>>> = listOf(
            "immediate" to buildPageDataForSync(vm, source),
            "debounced" to buildPageDataForSync(vm, source),
            "automatic" to buildPageDataForSync(vm, source),
            "manual" to buildPageDataForSync(vm, source)
        )
        val routeSnapshots = routePayloads.map { (route, payload) ->
            route to snapshotFromLegacyPageData(payload, source)
        }

        assertEquals(listOf("immediate", "debounced", "automatic", "manual"), routeSnapshots.map { it.first })
        val expected = routeSnapshots.first().second
        routeSnapshots.drop(1).forEach { (route, actual) ->
            assertEquals("route=$route", expected, actual)
        }
        assertEquals(expected.pages.keys, setOf(0, 2))
        assertEquals(expected.pages.getValue(0).shapes.single().id, "page-shape-legacy-001")
        assertEquals(expected.pages.getValue(0).photoPins.single().imageFileNames, routeSnapshots.last().second.pages.getValue(0).photoPins.single().imageFileNames)
        assertEquals(expected.pages.getValue(2).scale, routeSnapshots.last().second.pages.getValue(2).scale)
    }
}
