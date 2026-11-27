package dev.gcode.git

import com.google.common.truth.Truth.assertThat
import dev.gcode.core.model.RepoRef
import org.junit.Test

class GitRemotesTest {
    private val repo = RepoRef("octocat", "hello-world")

    @Test
    fun `builds https clone url`() {
        assertThat(GitRemotes.httpsCloneUrl(repo)).isEqualTo("https://github.com/octocat/hello-world.git")
    }

    @Test
    fun `parses https and ssh remotes`() {
        listOf(
            "https://github.com/octocat/hello-world.git",
            "https://github.com/octocat/hello-world",
            "git@github.com:octocat/hello-world.git",
            "ssh://git@github.com/octocat/hello-world.git",
        ).forEach { assertThat(GitRemotes.parseGitHubRemote(it)).isEqualTo(repo) }
    }

    @Test
    fun `ignores non github remotes`() {
        assertThat(GitRemotes.parseGitHubRemote("https://gitlab.com/a/b.git")).isNull()
    }
}
