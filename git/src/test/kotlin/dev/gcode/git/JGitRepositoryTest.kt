package dev.gcode.git

import com.google.common.truth.Truth.assertThat
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JGitRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val me = Author("Test User", "1+test@users.noreply.github.com")
    private lateinit var remoteUrl: String

    @Before
    fun setUp() {
        SandboxedSystemReader.install(tmp.newFolder("jgit-home"))
        val bare = tmp.newFolder("remote.git")
        Git.init().setBare(true).setDirectory(bare).setInitialBranch("main").call().close()
        remoteUrl = bare.toURI().toString()
        // Seed the remote with one commit.
        JGitRepository.init(File(tmp.root, "seed"), "main").use { seed ->
            seed.addRemote("origin", remoteUrl)
            File(seed.directory, "README.md").writeText("# Demo\n")
            seed.addAll()
            seed.commit("Initial commit", me)
            assertThat(seed.push(null).ok).isTrue()
        }
    }

    private fun cloneFresh(name: String) = JGitRepository.clone(remoteUrl, File(tmp.root, name), null)

    @Test
    fun `status reports staged, unstaged and untracked files and diffs show changes`() {
        cloneFresh("a").use { repo ->
            assertThat(repo.status().isClean).isTrue()
            File(repo.directory, "README.md").appendText("More text\n")
            File(repo.directory, "new.txt").writeText("new\n")
            var status = repo.status()
            assertThat(status.unstaged).containsExactly(FileChange("README.md", ChangeKind.MODIFIED))
            assertThat(status.untracked).containsExactly("new.txt")
            assertThat(repo.diff()).contains("+More text")

            repo.add(listOf("README.md", "new.txt"))
            status = repo.status()
            assertThat(status.staged.map { it.path }).containsExactly("README.md", "new.txt")
            assertThat(repo.diff(staged = true)).contains("+new")

            repo.unstage(listOf("new.txt"))
            assertThat(repo.status().untracked).containsExactly("new.txt")
        }
    }

    @Test
    fun `commit, push and pull between two clones, then log and commit diff`() {
        cloneFresh("a").use { a ->
            File(a.directory, "src/Main.kt").apply { parentFile.mkdirs(); writeText("fun main() = println(1)\n") }
            a.addAll()
            val commit = a.commit("Add main", me)
            assertThat(commit.authorEmail).isEqualTo(me.email)
            assertThat(a.status().ahead).isEqualTo(1)
            val push = a.push(null)
            assertThat(push.ok).isTrue()
            assertThat(a.status().ahead).isEqualTo(0)
            assertThat(a.log().map { it.title }).containsExactly("Add main", "Initial commit").inOrder()
            assertThat(a.commitDiff(commit.sha)).contains("+fun main() = println(1)")
        }
        cloneFresh("b").use { b ->
            assertThat(File(b.directory, "src/Main.kt").exists()).isTrue()
        }
    }

    @Test
    fun `pull fast-forwards, and a diverged push is rejected until pulled`() {
        val a = cloneFresh("a")
        val b = cloneFresh("b")
        a.use {
            File(a.directory, "a.txt").writeText("a\n"); a.addAll(); a.commit("a", me); a.push(null)
        }
        b.use {
            File(b.directory, "b.txt").writeText("b\n"); b.addAll(); b.commit("b", me)
            val rejected = b.push(null)
            assertThat(rejected.ok).isFalse()
            assertThat(rejected.message).contains("Pull first")
            assertThat(b.pull(null)).isInstanceOf(MergeOutcome.Merged::class.java)
            assertThat(b.push(null).ok).isTrue()
        }
    }

    @Test
    fun `force push uses a lease and is rejected when the remote moved`() {
        val a = cloneFresh("a")
        val b = cloneFresh("b")
        a.use { File(a.directory, "x").writeText("1"); a.addAll(); a.commit("x", me); a.push(null) }
        b.use {
            File(b.directory, "y").writeText("2"); b.addAll(); b.commit("y", me)
            // b has not fetched a's commit: the lease protects it.
            assertThat(b.push(null, force = true).ok).isFalse()
            b.fetch(null)
            assertThat(b.push(null, force = true).ok).isTrue()
        }
    }

    @Test
    fun `branches, checkout of a remote branch, merge and conflicts`() {
        cloneFresh("a").use { a ->
            a.createBranch("feature")
            assertThat(a.currentBranch()).isEqualTo("feature")
            File(a.directory, "README.md").writeText("feature line\n"); a.addAll(); a.commit("feature change", me)
            a.push(null)
            a.checkout("main")
            File(a.directory, "README.md").writeText("main line\n"); a.addAll(); a.commit("main change", me)
            val outcome = a.merge("feature")
            assertThat(outcome).isEqualTo(MergeOutcome.Conflicts(listOf("README.md")))
            assertThat(a.isMerging()).isTrue()
            assertThat(File(a.directory, "README.md").readText()).contains("<<<<<<<")
            a.abortMerge()
            assertThat(a.isMerging()).isFalse()
            assertThat(a.branches().map { it.name }).containsAtLeast("main", "feature", "origin/feature")
            assertThrows(GitOperationException::class.java) { a.deleteBranch("main") }
            a.deleteBranch("feature", force = true)
        }
        cloneFresh("b").use { b ->
            b.checkout("feature")
            assertThat(b.currentBranch()).isEqualTo("feature")
            assertThat(b.status().upstream).isEqualTo("origin/feature")
        }
    }

    @Test
    fun `clone into a non empty directory fails and a failed clone leaves nothing`() {
        val target = tmp.newFolder("busy").also { File(it, "file").writeText("x") }
        assertThrows(GitOperationException::class.java) { JGitRepository.clone(remoteUrl, target, null) }
        val missing = File(tmp.root, "missing-clone")
        assertThrows(GitOperationException::class.java) { JGitRepository.clone(File(tmp.root, "nope.git").toURI().toString(), missing, null) }
        assertThat(missing.exists()).isFalse()
    }

    @Test
    fun `workspace manager lists and deletes clones`() {
        val ws = WorkspaceManager(tmp.newFolder("ws"))
        val ref = dev.gcode.core.model.RepoRef("octo", "demo")
        JGitRepository.clone(remoteUrl, ws.dirFor(ref), null).close()
        assertThat(ws.cloned()).containsExactly(ref)
        assertThat(ws.sizeBytes(ref)).isGreaterThan(0L)
        assertThat(ws.delete(ref)).isTrue()
        assertThat(ws.cloned()).isEmpty()
    }
}
