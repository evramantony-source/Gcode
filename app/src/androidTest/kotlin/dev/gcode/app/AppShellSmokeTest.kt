package dev.gcode.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.gcode.app.ui.navigation.TopLevelDestination
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test: the app launches, shows every top-level destination, and navigates through
 * More > Open-source licences > LGPL text and back. Extended to onboarding and chat in Phase 10.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppShellSmokeTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun shellLaunchesAndNavigates() {
        composeRule.onNodeWithTag("screen_chat").assertIsDisplayed()
        TopLevelDestination.entries.forEach { composeRule.onNodeWithTag(it.testTag).assertIsDisplayed() }

        composeRule.onNodeWithTag(TopLevelDestination.BUILDS.testTag).performClick()
        composeRule.onNodeWithTag("screen_builds").assertIsDisplayed()

        composeRule.onNodeWithTag(TopLevelDestination.MORE.testTag).performClick()
        composeRule.onNodeWithTag("screen_more").assertIsDisplayed()
        composeRule.onNodeWithTag("more_licenses").performClick()
        composeRule.onNodeWithTag("screen_licenses").assertIsDisplayed()
        composeRule.onNodeWithTag("license_card_lgpl").assertIsDisplayed()

        composeRule.onNodeWithTag("navigate_back").performClick()
        composeRule.onNodeWithTag("screen_more").assertIsDisplayed()
    }
}
