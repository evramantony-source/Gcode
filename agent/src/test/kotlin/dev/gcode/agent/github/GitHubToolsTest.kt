package dev.gcode.agent.github

import com.google.common.truth.Truth.assertThat
import dev.gcode.agent.ToolEffect
import dev.gcode.agent.tools.ApprovalRequest
import dev.gcode.agent.tools.ApprovalResult
import dev.gcode.agent.tools.Tool
import dev.gcode.agent.tools.ToolArgumentException
import dev.gcode.agent.tools.ToolContext
import dev.gcode.agent.tools.ToolResult
import dev.gcode.agent.tools.github.BuildFixGuard
import dev.gcode.agent.tools.github.GitHubToolDeps
import dev.gcode.agent.tools.github.GitHubTools
import dev.gcode.core.model.RepoRef
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubToolsTest {
    private val gh = FakeGitHub()
    private val host = FakeHost(gh)
    private val sleeps = mutableListOf<Long>()
    private val deps = GitHubToolDeps(gh, gh, gh, gh, gh, host, sleep = { sleeps += it })
    private val guard = BuildFixGuard { 3 }
    private val tools = GitHubTools.create(deps, guard).associateBy { it.name }
    private val progress = mutableListOf<String>()

    private fun tool(name: String): Tool = tools.getValue(name)

    private suspend fun call(name: String, args: JsonObject = JsonObject(emptyMap())): Pair<ApprovalRequest?, ToolResult> {
        val prepared = tool(name).prepare(args, ToolContext("c") { progress += it })
        return prepared.approval to prepared.apply(ApprovalResult.Approved)
    }

    private fun ToolResult.field(key: String) = response[key]?.jsonPrimitive?.content

    @Test
    fun `every spec tool exists`() {
        assertThat(tools.keys).containsExactly(
            "gh_list_repos", "gh_search_repos", "gh_create_repo", "gh_clone_repo", "gh_fork_repo", "gh_get_file",
            "gh_create_pr", "gh_list_prs", "gh_merge_pr", "gh_comment", "gh_list_issues", "gh_create_issue",
            "gh_setup_build_workflow", "gh_trigger_workflow", "gh_get_run_status", "gh_wait_for_run", "gh_get_run_logs",
            "gh_list_artifacts", "gh_download_apk", "gh_install_apk", "gh_create_release",
        )
    }

    @Test
    fun `read-only tools need no approval`() = runTest {
        val (approval, result) = call("gh_list_repos")
        assertThat(approval).isNull()
        assertThat(result.field("repositories")).contains("octo/site [public]")
        assertThat(tool("gh_list_repos").effect(JsonObject(emptyMap()))).isEqualTo(ToolEffect.READ)
        assertThat(call("gh_search_repos", buildJsonObject { put("query", "markdown") }).second.field("repositories")).contains("someone/markdown")
        assertThat(call("gh_get_file").second.field("entries")).contains("dir  app")
        assertThat(call("gh_get_file", buildJsonObject { put("path", "README.md") }).second.field("content")).isEqualTo("content of README.md")
        val issue = call("gh_list_issues", buildJsonObject { put("number", 4) }).second.field("issue")
        assertThat(issue).contains("Crash on start")
        assertThat(issue).contains("bob")
    }

    @Test
    fun `remote secrets need the always-confirm secret prompt`() = runTest {
        val (approval, _) = call("gh_get_file", buildJsonObject { put("path", "app/release.keystore") })
        assertThat(approval).isInstanceOf(ApprovalRequest.SecretAccess::class.java)
    }

    @Test
    fun `public repositories are dangerous, private ones are not`() = runTest {
        val private = buildJsonObject { put("name", "demo") }
        val public = buildJsonObject { put("name", "demo"); put("private", false) }
        assertThat(tool("gh_create_repo").effect(private)).isEqualTo(ToolEffect.REMOTE)
        assertThat(tool("gh_create_repo").effect(public)).isEqualTo(ToolEffect.DANGEROUS)
        val (approval, result) = call("gh_create_repo", public)
        assertThat((approval as ApprovalRequest.Action).dangerous).isTrue()
        assertThat(approval.title).contains("PUBLIC")
        assertThat(result.ok).isTrue()
        assertThat(gh.created.single().private).isFalse()
        assertThat(gh.created.single().autoInit).isTrue()
        assertThrows(ToolArgumentException::class.java) { kotlinx.coroutines.runBlocking { call("gh_create_repo", buildJsonObject { put("name", "bad name") }) } }
    }

    @Test
    fun `merging into the default branch is dangerous and uses the head sha`() = runTest {
        gh.pull(7, base = "main")
        gh.pull(8, base = "develop")
        val into = { n: Int -> buildJsonObject { put("number", n); put("method", "squash") } }
        assertThat(tool("gh_merge_pr").effect(into(7))).isEqualTo(ToolEffect.DANGEROUS)
        assertThat(tool("gh_merge_pr").effect(into(8))).isEqualTo(ToolEffect.REMOTE)
        val (approval, result) = call("gh_merge_pr", into(7))
        assertThat((approval as ApprovalRequest.Action).dangerous).isTrue()
        assertThat(result.ok).isTrue()
        assertThat(gh.merges.single().second.sha).isEqualTo("headsha7")
        assertThat(gh.merges.single().second.mergeMethod).isEqualTo("squash")
    }

    @Test
    fun `merge waits for GitHub to compute mergeability`() = runTest {
        gh.pull(3, base = "develop", mergeable = null)
        val (_, result) = call("gh_merge_pr", buildJsonObject { put("number", 3) })
        assertThat(result.ok).isTrue()
        assertThat(sleeps).isNotEmpty()
    }

    @Test
    fun `unmergeable pull requests are refused`() = runTest {
        gh.pull(4, base = "develop", mergeable = false)
        assertThrows(ToolArgumentException::class.java) { kotlinx.coroutines.runBlocking { call("gh_merge_pr", buildJsonObject { put("number", 4) }) } }
    }

    @Test
    fun `pull requests default to the current branch and the default branch`() = runTest {
        val (approval, result) = call("gh_create_pr", buildJsonObject { put("title", "Add login"); put("body", "Adds a login screen") })
        assertThat((approval as ApprovalRequest.Action).detail).contains("feature → main")
        assertThat(result.field("number")).isEqualTo("9")
        assertThat(gh.pullsCreated.single().head).isEqualTo("feature")
        assertThat(gh.pullsCreated.single().base).isEqualTo("main")
        assertThrows(ToolArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { call("gh_create_pr", buildJsonObject { put("title", "x"); put("head", "main") }) }
        }
    }

    @Test
    fun `comments, issues and other repositories`() = runTest {
        call("gh_comment", buildJsonObject { put("number", 5); put("body", "Fixed in #9") })
        assertThat(gh.comments.single()).isEqualTo(5 to "Fixed in #9")
        call("gh_create_issue", buildJsonObject { put("title", "Flaky test"); put("labels", buildJsonArray { add(JsonPrimitive("ci")) }) })
        assertThat(gh.issuesCreated.single().labels).containsExactly("ci")
        assertThrows(ToolArgumentException::class.java) { kotlinx.coroutines.runBlocking { call("gh_list_prs", buildJsonObject { put("repo", "not-a-repo") }) } }
        host.active = null
        assertThrows(ToolArgumentException::class.java) { kotlinx.coroutines.runBlocking { call("gh_list_prs") } }
        assertThat(call("gh_list_prs", buildJsonObject { put("repo", "octo/other") }).second.ok).isTrue()
    }

    @Test
    fun `clone opens the repository and reports progress`() = runTest {
        val (approval, result) = call("gh_clone_repo", buildJsonObject { put("repo", "octo/new") })
        assertThat(approval).isNotNull()
        assertThat(result.summary).isEqualTo("Cloned octo/new")
        assertThat(host.active).isEqualTo(RepoRef("octo", "new"))
        assertThat(progress).contains("Receiving objects 50%")
        assertThat(tool("gh_clone_repo").effect(JsonObject(emptyMap()))).isEqualTo(ToolEffect.GIT_LOCAL)
    }
}
