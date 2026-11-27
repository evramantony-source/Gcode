package dev.gcode.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
import dev.gcode.git.SandboxedSystemReader
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/** Opens a cloned repository: explorer, Sora editor (edit + save), and the source-control tabs. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CodeEditorTest {
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
        repoDir = File(context.filesDir, "workspace/octo/editor-test").apply { deleteRecursively(); mkdirs() }
        JGitRepository.init(repoDir, "main").use { r ->
            File(repoDir, "README.md").writeText("# Editor test\n")
            r.addAll()
            r.commit("Initial commit", Author("Test", "t@example.com"))
        }
        runBlocking {
            secrets.put(SecretKey.GEMINI_API_KEY, "AIzaFakeKeyForInstrumentedTests0000")
            db.localRepos().upsert(LocalRepoEntity("octo/editor-test", repoDir.absolutePath, "main", "https://github.com/octo/editor-test.git", clonedAt = 1, lastOpenedAt = 1))
            settings.update { it.copy(onboardingComplete = true, activeRepo = "octo/editor-test") }
        }
    }

    @Test
    fun openEditSaveAndBrowseSourceControl() {
        composeRule.waitUntilAtLeastOneExists(hasTestTag(TopLevelDestination.CODE.testTag), TIMEOUT)
        composeRule.onNodeWithTag(TopLevelDestination.CODE.testTag).performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("file_README.md"), TIMEOUT)
        composeRule.onNodeWithTag("file_README.md").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("code_editor"), TIMEOUT)

        // Type into the Sora editor, then save through the toolbar.
        composeRule.runOnUiThread {
            val editor = composeRule.activity.window.decorView.findEditor()!!
            editor.text.insert(1, 0, "Edited on device\n")
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("save_file").performClick()
        composeRule.waitUntil(TIMEOUT) { File(repoDir, "README.md").readText().contains("Edited on device") }
        assertEquals("# Editor test\nEdited on device\n", File(repoDir, "README.md").readText())

        // The change shows up in source control.
        composeRule.onNodeWithTag("code_tab_changes").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("changes_list"), TIMEOUT)
        composeRule.onNodeWithTag("code_tab_history").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("history_list"), TIMEOUT)
        composeRule.onNodeWithTag("code_tab_branches").performClick()
        composeRule.onNodeWithTag("branch_list").assertIsDisplayed()
    }

    private fun android.view.View.findEditor(): CodeEditor? {
        if (this is CodeEditor) return this
        if (this is android.view.ViewGroup) for (i in 0 until childCount) getChildAt(i).findEditor()?.let { return it }
        return null
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
