package dev.gcode.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.gcode.app.ui.navigation.TopLevelDestination
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test with a scripted Gemini fake: onboarding (key check) -> chat -> streamed reply,
 * then every top-level destination and the licences screen. Extended in later phases.
 */
@OptIn(ExperimentalTestApi::class)
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
    fun onboardingChatAndNavigation() {
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_onboarding") or hasTestTag("screen_chat"), TIMEOUT)
        if (composeRule.onAllNodes(hasTestTag("screen_onboarding")).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("gemini_key_field").performTextInput("AIzaFakeKeyForInstrumentedTests0000")
            composeRule.onNodeWithTag("verify_key").performClick()
        }
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_chat"), TIMEOUT)

        composeRule.onNodeWithTag("composer").performTextInput("Say hello")
        composeRule.onNodeWithTag("send").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText(FakeGeminiTransport.REPLY_START + FakeGeminiTransport.REPLY_END, substring = true), TIMEOUT)
        composeRule.onNodeWithTag("status_model").assertIsDisplayed()

        TopLevelDestination.entries.forEach { composeRule.onNodeWithTag(it.testTag).assertIsDisplayed() }
        composeRule.onNodeWithTag(TopLevelDestination.BUILDS.testTag).performClick()
        composeRule.onNodeWithTag("screen_builds").assertIsDisplayed()

        composeRule.onNodeWithTag(TopLevelDestination.MORE.testTag).performClick()
        composeRule.onNodeWithTag("screen_more").assertIsDisplayed()
        composeRule.onNodeWithTag("more_licenses").performClick()
        composeRule.onNodeWithTag("screen_licenses").assertIsDisplayed()
        composeRule.onNodeWithTag("license_card_lgpl").assertIsDisplayed()
        composeRule.onNodeWithTag("navigate_back").performClick()
        composeRule.onNodeWithTag("more_settings").performClick()
        composeRule.onNodeWithTag("screen_settings").assertIsDisplayed()
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
