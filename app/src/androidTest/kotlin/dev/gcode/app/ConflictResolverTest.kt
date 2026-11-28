package dev.gcode.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.gcode.app.ui.navigation.TopLevelDestination
import dev.gcode.core.data.db.GCodeDatabase
import dev.gcode.core.data.db.LocalRepoEntity
import dev.gcode.core.data.secret.SecretKey
import dev.gcode.core.data.secret.SecretStore
import dev.gcode.core.data.settings.SettingsRepository
import dev.gcode.git.Author
import dev.gcode.git.JGitRepository
import dev.gcode.git.MergeOutcome
import dev.gcode.git.SandboxedSystemReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/** A real merge conflict, resolved hunk by hunk in the UI and committed as a merge. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ConflictResolverTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var secrets: SecretStore
    @Inject lateinit var db: GCodeDatabase

    private lateinit var repoDir: File

    @Before
    fun setUp() {
        hiltRule.inject()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SandboxedSystemReader.install(File(context.filesDir, "jgit"))
        repoDir = File(context.filesDir, "workspace/octo/conflict-test").apply { deleteRecursively(); mkdirs() }
        val me = Author("Test", "t@example.com")
        JGitRepository.init(repoDir, "main").use { r ->
            File(repoDir, "Greeting.kt").writeText("val greeting = \"hello\"\n")
            r.addAll(); r.commit("init", me)
            r.createBranch("feature", checkout = true)
            File(repoDir, "Greeting.kt").writeText("val greeting = \"hi there\"\n")
            r.addAll(); r.commit("feature greeting", me)
            r.checkout("main")
            File(repoDir, "Greeting.kt").writeText("val greeting = \"good morning\"\n")
            r.addAll(); r.commit("main greeting", me)
            assertTrue(r.merge("feature") is MergeOutcome.Conflicts)
        }
        runBlocking {
            secrets.put(SecretKey.GEMINI_API_KEY, "AIzaFakeKeyForInstrumentedTests0000")
            db.localRepos().upsert(LocalRepoEntity("octo/conflict-test", repoDir.absolutePath, "main", "https://github.com/octo/conflict-test.git", clonedAt = 1, lastOpenedAt = 1))
            settings.update { it.copy(onboardingComplete = true, activeRepo = "octo/conflict-test") }
        }
    }

    @Test
    fun resolveWithTheirsAndCommitTheMerge() {
        composeRule.waitUntilAtLeastOneExists(hasTestTag(TopLevelDestination.CODE.testTag), TIMEOUT)
        composeRule.onNodeWithTag(TopLevelDestination.CODE.testTag).performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("code_tab_changes"), TIMEOUT)
        composeRule.onNodeWithTag("code_tab_changes").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("resolve_conflicts"), TIMEOUT)
        composeRule.onNodeWithTag("resolve_conflicts").performClick()

        composeRule.waitUntilAtLeastOneExists(hasTestTag("hunk_0_theirs"), TIMEOUT)
        Screenshots.take(composeRule, "conflicts")
        composeRule.onNodeWithTag("hunk_0_theirs").performClick()
        composeRule.onNodeWithTag("conflict_save").performClick()

        composeRule.waitUntilAtLeastOneExists(hasTestTag("commit_merge"), TIMEOUT)
        composeRule.onNodeWithTag("commit_merge").performClick()
        composeRule.waitUntil(TIMEOUT) { JGitRepository.open(repoDir).use { !it.isMerging() } }

        assertEquals("val greeting = \"hi there\"\n", File(repoDir, "Greeting.kt").readText())
        JGitRepository.open(repoDir).use { r ->
            assertFalse(r.isMerging())
            assertEquals(2, r.log(1).single().parents.size)
        }
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
