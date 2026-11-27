package dev.gcode.github

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GitHubApiTest {
    @Test
    fun `default headers pin the api version`() {
        val headers = GitHubApi.defaultHeaders(token = "t0ken")
        assertThat(headers).containsEntry("X-GitHub-Api-Version", "2026-03-10")
        assertThat(headers).containsEntry("Accept", "application/vnd.github+json")
        assertThat(headers).containsEntry("Authorization", "Bearer t0ken")
    }

    @Test
    fun `no authorization header without a token`() {
        assertThat(GitHubApi.defaultHeaders(token = null)).doesNotContainKey("Authorization")
        assertThat(GitHubApi.defaultHeaders(token = " ")).doesNotContainKey("Authorization")
    }

    @Test
    fun `sign-in scopes do not include delete_repo`() {
        assertThat(GitHubApi.SIGN_IN_SCOPES).containsExactly("repo", "workflow", "read:org")
    }
}
