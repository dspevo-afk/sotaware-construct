package com.example.myapplication.stage2

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.myapplication.PageScale
import com.example.myapplication.stage0.LegacyStateFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.ObjectOutputStream
import java.nio.file.Files

class LegacyPersistenceSourceTest {
    @Test
    fun androidLegacyReader_usesExactScaleKeyDelimiterAndPreservesJavaState() {
        val root = Files.createTempDirectory("stage2-legacy-reader").toFile()
        try {
            val context = TestContext(root)
            val sourceUri = "content://documents/plan"
            val similarlyPrefixedUri = "$sourceUri-copy"
            val legacyFile = File(context.filesDir, legacyMarkupFileName(sourceUri))
            ObjectOutputStream(FileOutputStream(legacyFile)).use {
                it.writeObject(LegacyStateFixture.fullyPopulatedLegacyMarkups())
            }
            context.preferences.edit()
                .putFloat("${sourceUri}_0", 10f)
                .putFloat("${similarlyPrefixedUri}_0", 20f)
                .apply()

            val result = AndroidLegacyPersistenceSource(context).read(sourceUri)
            assertTrue(result is LegacyReadResult.Found)
            val state = (result as LegacyReadResult.Found).state
            assertEquals(mapOf(0 to PageScale(10f)), state.scales)
            assertEquals(LegacyStateFixture.fullyPopulatedLegacyMarkups(), state.markups)
            assertTrue(legacyFile.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private class TestContext(private val root: File) : ContextWrapper(null) {
        val preferences = InMemoryPreferences()
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = preferences
    }

    private class InMemoryPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

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
            override fun putString(key: String?, value: String?): SharedPreferences.Editor { values[key.orEmpty()] = value; return this }
            override fun putStringSet(key: String?, value: MutableSet<String>?): SharedPreferences.Editor { values[key.orEmpty()] = value?.toSet(); return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { values[key.orEmpty()] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { values[key.orEmpty()] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { values[key.orEmpty()] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { values[key.orEmpty()] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { values.remove(key.orEmpty()); return this }
            override fun clear(): SharedPreferences.Editor { values.clear(); return this }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
    }
}
