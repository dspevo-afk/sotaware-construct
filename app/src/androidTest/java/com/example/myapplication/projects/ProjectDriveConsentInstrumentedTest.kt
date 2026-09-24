package com.example.myapplication.projects

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectDriveConsentInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun pendingConsentAndActivityOwnerSurviveActivityRecreation() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch<ProjectDriveConsentTestActivity>(
            Intent(targetContext, ProjectDriveConsentTestActivity::class.java)
        )
        lateinit var owner: ProjectDriveConsentOwner
        try {
            scenario.onActivity { activity ->
                owner = ViewModelProvider(activity)[ProjectDriveConsentOwner::class.java]
            }
            val savedLabel = "synthetic-operation/${owner.nonce}/synthetic-backup-account"
            compose.onNodeWithText("Save pending consent").performClick()
            compose.onNodeWithText(savedLabel).assertIsDisplayed()

            scenario.recreate()
            scenario.onActivity { activity ->
                assertSame(owner, ViewModelProvider(activity)[ProjectDriveConsentOwner::class.java])
            }
            compose.onNodeWithText(savedLabel).assertIsDisplayed()
        } finally {
            scenario.close()
        }
    }
}
