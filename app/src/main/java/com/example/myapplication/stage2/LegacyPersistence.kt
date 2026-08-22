package com.example.myapplication.stage2

import android.content.Context
import com.example.myapplication.PageMarkups
import com.example.myapplication.PageScale
import java.io.File
import java.io.FileInputStream
import java.io.ObjectInputStream

/** The state discovered in the pre-Stage-2 local persistence format. */
data class LegacyDocumentState(
    val markups: Map<Int, PageMarkups>,
    val scales: Map<Int, PageScale>,
    val markupArtifact: File?,
    val scalePreferenceKeys: Set<String>
)

sealed class LegacyReadResult {
    data class Found(val state: LegacyDocumentState) : LegacyReadResult()
    object Absent : LegacyReadResult()
    data class Failed(val detail: String) : LegacyReadResult()
}

/**
 * Explicit migration-only seam.  Normal document reads and writes never call
 * this interface.
 */
fun interface LegacyPersistenceSource {
    fun read(sourceUri: String): LegacyReadResult
}

/**
 * Reads the exact legacy artifacts without using the old forgiving loaders.
 * Java serialization is intentionally kept here so the existing serialized
 * class names and descriptors remain untouched until migration is qualified.
 */
class AndroidLegacyPersistenceSource(private val context: Context) : LegacyPersistenceSource {
    override fun read(sourceUri: String): LegacyReadResult {
        val markupFile = File(context.filesDir, legacyMarkupFileName(sourceUri))
        val prefs = context.getSharedPreferences("scales", Context.MODE_PRIVATE)
        val scalePrefix = "${sourceUri}_"
        val matchingScaleEntries = prefs.all.filterKeys { it.startsWith(scalePrefix) }
        val scales = linkedMapOf<Int, PageScale>()
        try {
            matchingScaleEntries.forEach { (key, value) ->
                val pageText = key.removePrefix(scalePrefix)
                val page = pageText.toIntOrNull()
                    ?: return LegacyReadResult.Failed("invalid legacy scale key: $key")
                val raw = value as? Number
                    ?: return LegacyReadResult.Failed("invalid legacy scale value: $key")
                val pixelsPerFoot = raw.toFloat()
                require(pixelsPerFoot.isFinite() && pixelsPerFoot > 0f) {
                    "invalid legacy scale value: $key"
                }
                scales[page] = PageScale(pixelsPerFoot)
            }
        } catch (error: Exception) {
            return LegacyReadResult.Failed("legacy scale read failed: ${error.message}")
        }

        val markups = if (markupFile.exists()) {
            try {
                readLegacyMarkups(markupFile)
            } catch (error: Exception) {
                return LegacyReadResult.Failed("legacy markup read failed: ${error.message}")
            }
        } else {
            emptyMap()
        }

        if (!markupFile.exists() && scales.isEmpty()) return LegacyReadResult.Absent
        return LegacyReadResult.Found(
            LegacyDocumentState(
                markups = markups,
                scales = scales,
                markupArtifact = markupFile.takeIf { it.exists() },
                scalePreferenceKeys = matchingScaleEntries.keys
            )
        )
    }

    private fun readLegacyMarkups(file: File): Map<Int, PageMarkups> {
        val raw: Any = ObjectInputStream(FileInputStream(file)).use { it.readObject() }
        val map = raw as? Map<*, *> ?: error("legacy root is not a map")
        val result = linkedMapOf<Int, PageMarkups>()
        map.forEach { (rawPage, rawMarkups) ->
            val page = rawPage as? Int ?: error("legacy page key is not Int")
            require(page >= 0) { "legacy page index is negative" }
            val markups = rawMarkups as? PageMarkups ?: error("legacy page payload is invalid")
            requireNotNull(markups.paths)
            requireNotNull(markups.measurements)
            requireNotNull(markups.notes)
            requireNotNull(markups.photoPins)
            requireNotNull(markups.shapes)
            result[page] = markups
        }
        return result
    }
}

fun legacyMarkupFileName(sourceUri: String): String =
    "markups_${sourceUri.hashCode()}.bin"
