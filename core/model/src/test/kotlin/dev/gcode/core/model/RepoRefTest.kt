package dev.gcode.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RepoRefTest {
    @Test
    fun `parses owner and name`() {
        val ref = RepoRef.parse("octocat/hello-world")
        assertThat(ref).isEqualTo(RepoRef("octocat", "hello-world"))
        assertThat(ref?.fullName).isEqualTo("octocat/hello-world")
    }

    @Test
    fun `strips whitespace and git suffix`() {
        assertThat(RepoRef.parse("  octocat/repo.git ")).isEqualTo(RepoRef("octocat", "repo"))
    }

    @Test
    fun `rejects malformed input`() {
        listOf("", "octocat", "a/b/c", "-bad/repo", "bad-/repo", "octo--cat/repo", "octocat/..", "octocat/re po")
            .forEach { assertThat(RepoRef.parse(it)).isNull() }
    }
}
