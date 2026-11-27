package dev.gcode.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.gcode.git.Author
import dev.gcode.git.JGitRepository
import dev.gcode.git.MergeOutcome
import dev.gcode.git.SandboxedSystemReader
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs real JGit operations on the device (risk R1): JGit 5.13 must work on ART from API 26.
 * Uses a bare repository in the app's cache as the remote, so no network is needed.
 */
@RunWith(AndroidJUnit4::class)
class GitOnDeviceTest {
    private lateinit var root: File
    private val me = Author("Device Test", "1+device@users.noreply.github.com")

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SandboxedSystemReader.install(File(context.filesDir, "jgit"))
        root = File(context.cacheDir, "git-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun cloneCommitPushPullBranchAndMerge() {
        val bare = File(root, "remote.git")
        Git.init().setBare(true).setDirectory(bare).setInitialBranch("main").call().close()
        val url = bare.toURI().toString()

        JGitRepository.init(File(root, "seed"), "main").use { seed ->
            seed.addRemote("origin", url)
            File(seed.directory, "README.md").writeText("# On device\n")
            seed.addAll()
            seed.commit("Initial commit", me)
            assertTrue(seed.push(null).ok)
        }

        val a = JGitRepository.clone(url, File(root, "a"), null)
        val b = JGitRepository.clone(url, File(root, "b"), null)
        a.use {
            File(a.directory, "src/App.kt").apply { parentFile!!.mkdirs(); writeText("class App\n") }
            assertEquals(listOf("src/App.kt"), a.status().untracked)
            a.addAll()
            assertTrue(a.diff(staged = true).contains("+class App"))
            a.commit("Add App", me)
            assertTrue(a.push(null).ok)
            assertEquals(listOf("Add App", "Initial commit"), a.log().map { it.title })
        }
        b.use {
            assertTrue(b.pull(null) is MergeOutcome.Merged)
            assertTrue(File(b.directory, "src/App.kt").exists())
            b.createBranch("feature")
            File(b.directory, "README.md").writeText("feature\n"); b.addAll(); b.commit("feature", me)
            b.checkout("main")
            File(b.directory, "README.md").writeText("main\n"); b.addAll(); b.commit("main", me)
            assertEquals(MergeOutcome.Conflicts(listOf("README.md")), b.merge("feature"))
            b.abortMerge()
            assertTrue(b.status().isClean)
        }
    }
}
