package com.example.myapplication.stage0

import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.snapshotFromState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPayloadCharacterizationTest {
    @Test
    fun currentSnapshotSeam_preservesAllDomainsAndScaleOnlyPages() {
        val source = CurrentStateFixture.source("content://stage0/plan-a.pdf")
        val snapshot = snapshotFromState(
            CurrentStateFixture.viewModel(),
            source,
            snapshotRevision = 12L
        )
        val expected = CurrentStateFixture.fullyPopulatedSnapshot(source, snapshotRevision = 12L)

        assertEquals(DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION, snapshot.schemaVersion)
        assertEquals(expected, snapshot)
        assertEquals(setOf(0, 2), snapshot.pages.keys)
        assertEquals(1, snapshot.pages.getValue(0).paths.size)
        assertEquals(1, snapshot.pages.getValue(0).measurements.size)
        assertEquals(1, snapshot.pages.getValue(0).notes.size)
        assertEquals(1, snapshot.pages.getValue(0).photoPins.size)
        assertEquals(1, snapshot.pages.getValue(0).shapes.size)
        assertEquals(42.75f, snapshot.pages.getValue(0).scale?.pointsPerFoot)
        assertEquals(18.5f, snapshot.pages.getValue(2).scale?.pointsPerFoot)
        assertTrue(snapshot.pages.getValue(2).paths.isEmpty())
        assertTrue(snapshot.pages.getValue(2).shapes.isEmpty())
    }
}
