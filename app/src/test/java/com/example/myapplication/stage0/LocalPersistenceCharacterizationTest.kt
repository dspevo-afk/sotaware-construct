package com.example.myapplication.stage0

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.myapplication.PageScale
import com.example.myapplication.loadScalesForPdf
import com.example.myapplication.saveScaleForPdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPersistenceCharacterizationTest {
    @Test
    fun characterization_localScalePreferences_preservePageAndDocumentKeys() {
        val context = InMemoryContext()
        val firstDocument = "content://documents/plan-a.pdf"
        val secondDocument = "content://documents/plan-b.pdf"

        saveScaleForPdf(context, firstDocument, page = 0, pixelsPerFoot = 42.75f)
        saveScaleForPdf(context, firstDocument, page = 2, pixelsPerFoot = 18.5f)
        saveScaleForPdf(context, secondDocument, page = 0, pixelsPerFoot = 73.25f)

        assertEquals(
            mapOf(0 to PageScale(42.75f), 2 to PageScale(18.5f)),
            loadScalesForPdf(context, firstDocument)
        )
        assertEquals(
            mapOf(0 to PageScale(73.25f)),
            loadScalesForPdf(context, secondDocument)
        )
        assertEquals(
            setOf("${firstDocument}_0", "${firstDocument}_2", "${secondDocument}_0"),
            context.scalePreferenceKeys()
        )
    }

    @Test
    fun characterization_localScalePreferences_recordsUriPrefixCollisionFixture() {
        val context = InMemoryContext()
        val document = "content://documents/plan"
        val similarlyPrefixedDocument = "${document}-copy"
        saveScaleForPdf(context, document, page = 0, pixelsPerFoot = 10f)
        saveScaleForPdf(context, similarlyPrefixedDocument, page = 0, pixelsPerFoot = 20f)
        assertTrue(similarlyPrefixedDocument.startsWith(document))
        assertEquals(
            setOf("${document}_0", "${similarlyPrefixedDocument}_0"),
            context.scalePreferenceKeys()
        )
        // The production startsWith-based loader remains a documented Stage 2 defect;
        // this test preserves the colliding artifact shape without blessing its load result.
    }

    private class InMemoryContext : ContextWrapper(null) {
        private val preferences = InMemorySharedPreferences()

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = preferences

        fun scalePreferenceKeys(): Set<String> = preferences.values.keys
    }

    private class InMemorySharedPreferences : SharedPreferences {
        val values = linkedMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                values[key.orEmpty()] = value
                return this
            }

            override fun putStringSet(key: String?, value: MutableSet<String>?): SharedPreferences.Editor {
                values[key.orEmpty()] = value?.toSet()
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                values[key.orEmpty()] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                values[key.orEmpty()] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                values[key.orEmpty()] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                values[key.orEmpty()] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                values.remove(key.orEmpty())
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                values.clear()
                return this
            }

            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
    }
}
