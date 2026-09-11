package com.example.myapplication.stage10

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Installed/compiled policy evidence, deliberately not a claimed two-device restore test. */
@RunWith(AndroidJUnit4::class)
class Stage10BackupPolicyInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun installedApplicationDisablesPlatformAutoBackup() {
        assertEquals("installed manifest must disable backup", 0,
            context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test fun compiledLegacyBackupRulesExcludeEverySupportedDataDomain() {
        val rules = exclusions(R.xml.backup_rules, setOf("full-backup-content"))
        assertEquals(setOf("root", "file", "database", "sharedpref", "external"),
            rules.getValue("full-backup-content"))
    }

    @Test fun compiledCloudAndDeviceTransferRulesExcludeCredentialAndDeviceProtectedData() {
        val rules = exclusions(R.xml.data_extraction_rules, setOf("cloud-backup", "device-transfer"))
        val expected = setOf("root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref")
        assertEquals(expected, rules.getValue("cloud-backup"))
        assertEquals(expected, rules.getValue("device-transfer"))
    }

    private fun exclusions(resource: Int, sections: Set<String>): Map<String, Set<String>> {
        val found = linkedMapOf<String, MutableSet<String>>()
        context.resources.getXml(resource).use { xml ->
            var section: String? = null
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG) {
                    when {
                        xml.name in sections -> {
                            assertNull("nested backup sections are invalid", section)
                            section = xml.name
                            assertNull("duplicate backup section", found.put(xml.name, linkedSetOf()))
                        }
                        xml.name == "include" -> fail("backup must not opt app data back in")
                        xml.name == "exclude" -> {
                            assertNotNull("exclusion must belong to an expected section", section)
                            assertEquals(".", xml.getAttributeValue(null, "path"))
                            val domain = xml.getAttributeValue(null, "domain")
                            assertNotNull("exclusion requires a domain", domain)
                            assertTrue("duplicate exclusion", found.getValue(section!!).add(domain))
                        }
                    }
                } else if (xml.eventType == XmlPullParser.END_TAG && xml.name == section) {
                    section = null
                }
                xml.next()
            }
        }
        assertEquals(sections, found.keys)
        return found
    }
}
