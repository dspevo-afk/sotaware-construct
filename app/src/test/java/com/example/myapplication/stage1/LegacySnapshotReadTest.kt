package com.example.myapplication.stage1

import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.stage0.CurrentStateFixture
import com.example.myapplication.stage5.decodeValidatedSnapshotJson
import com.example.myapplication.stage5.validateCanonicalSnapshotTree
import com.example.myapplication.stage5.validateSnapshot
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents saved by the previous build (schema 2) keep opening with every
 * markup after an update: their wire decodes, validates, applies, and is
 * re-published in the current schema. Unreadable versions stay rejected.
 */
class LegacySnapshotReadTest {
    private val source = CurrentStateFixture.source()
    private val gson = Gson()

    private fun point(x: Double, y: Double) = JsonObject().apply {
        addProperty("x", x)
        addProperty("y", y)
    }

    /** One page exactly as the schema-2 writer emitted it. */
    private fun legacyPage(): JsonObject = JsonObject().apply {
        add("paths", JsonArray().apply {
            add(JsonObject().apply {
                add("points", JsonArray().apply {
                    add(point(0.1, 0.2))
                    add(point(0.4, 0.7))
                })
                addProperty("colorArgb", -65536)
                addProperty("isHighlighter", false)
                addProperty("strokeWidthRatio", 0.005)
                addProperty("id", "legacy-path")
            })
        })
        // Version 2 measurements carried no vertices, colour, or stroke width.
        add("measurements", JsonArray().apply {
            add(JsonObject().apply {
                add("p1", point(0.0, 0.0))
                add("p2", point(0.5, 0.25))
                addProperty("text", "10' 0\"")
                addProperty("id", "legacy-measure")
            })
        })
        // Version 2 notes carried no colour.
        add("notes", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("x", 0.3)
                addProperty("y", 0.4)
                addProperty("text", "legacy note")
                addProperty("fontSizeRatio", 0.028)
                addProperty("isBold", true)
                addProperty("rotation", -12.0)
                addProperty("id", "legacy-note")
            })
        })
        add("photoPins", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("x", 0.6)
                addProperty("y", 0.65)
                addProperty("id", "legacy-pin")
                add("imageFileNames", JsonArray().apply { add("photo-1.jpg") })
                add("imageNotes", JsonObject().apply {
                    add("photo-1.jpg", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("x", 0.1)
                            addProperty("y", 0.2)
                            addProperty("text", "on photo")
                            addProperty("fontSizeRatio", 0.03)
                            addProperty("isBold", false)
                            addProperty("rotation", 0.0)
                            addProperty("id", "legacy-image-note")
                        })
                    })
                })
                add("imageShapes", JsonObject().apply { add("photo-1.jpg", JsonArray()) })
            })
        })
        add("shapes", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("x", 0.5)
                addProperty("y", 0.5)
                addProperty("rotation", 30.0)
                addProperty("type", "ARROW")
                addProperty("colorArgb", -1)
                addProperty("isFilled", true)
                addProperty("strokeWidthRatio", 0.005)
                addProperty("widthRatio", 0.2)
                addProperty("heightRatio", 0.1)
                addProperty("id", "legacy-shape")
            })
        })
        add("scale", JsonObject().apply { addProperty("pointsPerFoot", 120.0) })
    }

    private fun legacyRoot(schemaVersion: Int, page: JsonObject): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", schemaVersion)
        addProperty("snapshotRevision", 7L)
        add("source", JsonObject().apply {
            addProperty("sourceUri", source.sourceUri)
            addProperty("displayName", source.displayName ?: "blueprint.pdf")
            add("providerMetadata", JsonObject())
        })
        add("pages", JsonObject().apply { add("0", page) })
    }

    @Test
    fun `schema 2 wire validates and decodes with current defaults`() {
        val root = legacyRoot(DOCUMENT_SNAPSHOT_LEGACY_SCHEMA_VERSION, legacyPage())
        validateCanonicalSnapshotTree(root, "legacy document")

        // The local load owner decodes the validated tree, then the mapper
        // materializes it; that is the flow a saved drawing opens through.
        val snapshot = requireNotNull(
            gson.fromJson(root, com.example.myapplication.stage1.DocumentSnapshotV1::class.java)
        )
        assertEquals(DOCUMENT_SNAPSHOT_LEGACY_SCHEMA_VERSION, snapshot.schemaVersion)

        val vm = BlueprintViewModel()
        applySnapshotReplace(snapshot, vm)
        val measurement = vm.pageMeasurements.getValue(0).single()
        assertEquals("10' 0\"", measurement.text)
        assertEquals("legacy-measure", measurement.id)
        // A version-2 measurement carried no polyline vertices: p1 and p2 only.
        assertEquals(listOf(measurement.p1, measurement.p2), measurement.vertices)
        assertTrue(measurement.intermediatePoints.isEmpty())
        val note = vm.pageNotes.getValue(0).single()
        assertEquals("legacy note", note.text)
        val pin = vm.pagePhotoPins.getValue(0).single()
        assertEquals(listOf("photo-1.jpg"), pin.imageFileNames)
        assertEquals(120f, vm.pageScales.getValue(0).pointsPerFoot, 0f)
    }

    @Test
    fun `schema 2 document applies every markup and republishes in the current schema`() {
        val root = legacyRoot(DOCUMENT_SNAPSHOT_LEGACY_SCHEMA_VERSION, legacyPage())
        validateCanonicalSnapshotTree(root, "legacy document")
        val snapshot = requireNotNull(
            gson.fromJson(root, com.example.myapplication.stage1.DocumentSnapshotV1::class.java)
        )

        val vm = BlueprintViewModel()
        applySnapshotReplace(snapshot, vm)
        assertEquals(1, vm.pagePaths[0]?.size)
        assertEquals(1, vm.pageMeasurements[0]?.size)
        assertEquals(1, vm.pageNotes[0]?.size)
        assertEquals(1, vm.pageShapes[0]?.size)
        assertEquals(1, vm.pagePhotoPins[0]?.size)
        assertEquals(120f, vm.pageScales[0]!!.pointsPerFoot, 0f)
        assertTrue(vm.pageSubDrawings[0].orEmpty().isEmpty())

        val republished = snapshotFromState(vm, source, snapshotRevision = 8L)
        assertEquals(DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION, republished.schemaVersion)
        validateSnapshot(republished)
        // Re-opening the republished document keeps the same markups.
        val reopened = BlueprintViewModel()
        applySnapshotReplace(republished, reopened)
        assertEquals(vm.pagePaths[0]?.map { it.id }, reopened.pagePaths[0]?.map { it.id })
        assertEquals(vm.pageMeasurements[0]?.map { it.text }, reopened.pageMeasurements[0]?.map { it.text })
        assertEquals(vm.pageNotes[0]?.map { it.id }, reopened.pageNotes[0]?.map { it.id })
        assertEquals(vm.pageShapes[0]?.map { it.id }, reopened.pageShapes[0]?.map { it.id })
        assertEquals(vm.pagePhotoPins[0]?.map { it.id }, reopened.pagePhotoPins[0]?.map { it.id })
    }

    @Test
    fun `legacy schema claiming sub-drawings is rejected`() {
        val page = legacyPage().apply { add("subDrawings", JsonArray().apply { add(JsonObject()) }) }
        val root = legacyRoot(DOCUMENT_SNAPSHOT_LEGACY_SCHEMA_VERSION, page)
        assertThrows(
            com.example.myapplication.stage5.Stage5ValidationException::class.java
        ) { validateCanonicalSnapshotTree(root, "legacy document") }
    }

    @Test
    fun `retired and future schema versions stay rejected`() {
        listOf(1, 4).forEach { version ->
            val root = legacyRoot(version, legacyPage())
            assertThrows(
                com.example.myapplication.stage5.Stage5ValidationException::class.java
            ) { validateCanonicalSnapshotTree(root, "legacy document") }
        }
    }

    @Test
    fun `current schema still requires the fields version 2 made optional`() {
        val root = legacyRoot(DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION, legacyPage())
        assertThrows(
            com.example.myapplication.stage5.Stage5ValidationException::class.java
        ) { validateCanonicalSnapshotTree(root, "current document") }
    }
}
