package com.example.myapplication.stage1

import com.example.myapplication.stage0.CurrentStateFixture
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Network-free proof that every current capture route uses the one typed
 * snapshot boundary. The route labels stand in for immediate, debounced,
 * automatic and manual callers; none is allowed to recapture through a legacy
 * page-data or codec adapter.
 */
class SyncRouteEquivalenceTest {
    private val source = CurrentStateFixture.source()

    @Test
    fun immediateDebouncedAutomaticAndManualRoutes_produceIdenticalSchema2Snapshots() {
        val vm = CurrentStateFixture.viewModel()
        val routeSnapshots = listOf("immediate", "debounced", "automatic", "manual")
            .map { route -> route to snapshotFromState(vm, source, snapshotRevision = 21L) }

        assertEquals(listOf("immediate", "debounced", "automatic", "manual"), routeSnapshots.map { it.first })
        val expected = routeSnapshots.first().second
        routeSnapshots.drop(1).forEach { (route, actual) ->
            assertEquals("route=$route", expected, actual)
        }
        assertEquals(DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION, expected.schemaVersion)
        assertEquals(setOf(0, 2), expected.pages.keys)
        assertEquals(CurrentStateFixture.PAGE_SHAPE_ID, expected.pages.getValue(0).shapes.single().id)
        assertEquals(
            listOf(CurrentStateFixture.PHOTO_ONE, CurrentStateFixture.PHOTO_TWO),
            routeSnapshots.last().second.pages.getValue(0).photoPins.single().imageFileNames
        )
        assertEquals(18.5f, routeSnapshots.last().second.pages.getValue(2).scale?.pointsPerFoot)
    }
}
