package dev.gcode.agent.github

import com.google.common.truth.Truth.assertThat
import dev.gcode.agent.ToolEffect
import dev.gcode.agent.tools.ApprovalRequest
import dev.gcode.agent.tools.ApprovalResult
import dev.gcode.agent.tools.ToolContext
import dev.gcode.agent.tools.ToolResult
import dev.gcode.agent.tools.github.BuildFixGuard
import dev.gcode.agent.tools.github.GhWaitForRunTool
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ActionsToolsTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val gh = FakeGitHub()
    private val host = FakeHost(gh, limit = 2)
    private val sleeps = mutableListOf<Long>()
    private val guard = BuildFixGuard { 2 }
    private val tools = GitHubTools.create(GitHubToolDeps(gh, gh, gh, gh, gh, host, sleep = { sleeps += it }), guard).associateBy { it.name }
    private val repo = RepoRef("octo", "app")

    private suspend fun call(name: String, args: JsonObject = JsonObject(emptyMap())): Pair<ApprovalRequest?, ToolResult> {
        val prepared = tools.getValue(name).prepare(args, ToolContext("c"))
        return prepared.approval to prepared.apply(ApprovalResult.Approved)
    }

    private fun ToolResult.field(key: String) = response[key]?.jsonPrimitive?.content
    private fun run(id: Long) = buildJsonObject { put("run_id", id) }

    @Test
    fun `guard allows the first build and limit fix attempts`() = runTest {
        val g = BuildFixGuard { 3 }
        assertThat(g.blockReason(repo)).isNull()
        repeat(3) { g.record(repo, succeeded = false); assertThat(g.blockReason(repo)).isNull() } // 3 failures → 3rd fix allowed
        g.record(repo, succeeded = false)
        assertThat(g.blockReason(repo)).contains("Build-fix limit reached")
        assertThat(g.afterFailure(repo)).contains("no fix attempts are left")
        g.record(repo, succeeded = true)
        assertThat(g.blockReason(repo)).isNull()
        // Repositories are counted separately.
        g.record(RepoRef("o", "x"), succeeded = false)
        assertThat(g.failures(repo)).isEqualTo(0)
    }

    @Test
    fun `zero limit means no automatic fixes`() = runTest {
        val g = BuildFixGuard { 0 }
        g.record(repo, succeeded = false)
        assertThat(g.blockReason(repo)).isNotNull()
    }

    @Test
    fun `trigger then wait reports failure with remaining attempts`() = runTest {
        gh.conclusions += "failure"
        val (approval, started) = call("gh_trigger_workflow", buildJsonObject { put("inputs", buildJsonArray { add(JsonPrimitive("flavor=free")) }) })
        assertThat(approval).isInstanceOf(ApprovalRequest.Action::class.java)
        assertThat(host.builds.single()).isEqualTo(null to mapOf("flavor" to "free"))
        val runId = started.field("run_id")!!.toLong()

        val (_, waited) = call("gh_wait_for_run", run(runId))
        assertThat(waited.ok).isFalse()
        assertThat(waited.field("conclusion")).isEqualTo("failure")
        assertThat(waited.field("jobs")).contains("failed steps: \"Build with Gradle\"")
        assertThat(waited.field("next")).contains("Fix attempts left: 2 of 2")
        assertThat(sleeps).containsExactly(GhWaitForRunTool.POLL_MS)
        assertThat(guard.failures(repo)).isEqualTo(1)
    }

    @Test
    fun `logs contain only the failed step with the compiler error`() = runTest {
        gh.conclusions += "failure"
        val runId = call("gh_trigger_workflow").second.field("run_id")!!.toLong()
        call("gh_wait_for_run", run(runId))
        val logs = call("gh_get_run_logs", run(runId)).second.field("logs")!!
        assertThat(logs).contains("Step 2: Build with Gradle")
        assertThat(logs).contains("Unresolved reference 'greet'")
        assertThat(logs).doesNotContain("Setting up")
    }

    @Test
    fun `trigger is refused once the fix limit is used up`() = runTest {
        repeat(3) { guard.record(repo, succeeded = false) }
        val (approval, result) = call("gh_trigger_workflow")
        assertThat(approval).isNull()
        assertThat(result.ok).isFalse()
        assertThat(result.summary).contains("Build-fix limit reached")
        assertThat(host.builds).isEmpty()
    }

    @Test
    fun `wait gives up after the timeout`() = runTest {
        val started = gh.startRun()
        gh.conclusions += "success"
        // Poll budget of one minute: 4 polls of 15 s, but the fake completes on the 2nd poll.
        val (_, result) = call("gh_wait_for_run", buildJsonObject { put("run_id", started.id); put("timeout_minutes", 1) })
        assertThat(result.ok).isTrue()
        assertThat(result.field("next")).contains("gh_download_apk")
    }

    @Test
    fun `status, artifacts, download, install and release`() = runTest {
        gh.conclusions += "success"
        val runId = call("gh_trigger_workflow").second.field("run_id")!!.toLong()
        assertThat(call("gh_get_run_status").second.field("runs")).contains("run $runId")
        assertThat(call("gh_get_run_status", run(runId)).second.field("jobs")).contains("job ${runId * 10} build")
        assertThat(call("gh_list_artifacts", run(runId)).second.field("artifacts")).contains("app-debug-apk")
        val apk = call("gh_download_apk", run(runId)).second.field("path")!!
        assertThat(apk).endsWith(".apk")

        assertThat(tools.getValue("gh_install_apk").effect(JsonObject(emptyMap()))).isEqualTo(ToolEffect.DANGEROUS)
        val (approval, installed) = call("gh_install_apk", buildJsonObject { put("path", apk) })
        assertThat((approval as ApprovalRequest.Action).dangerous).isTrue()
        assertThat(installed.ok).isTrue()
        assertThat(host.installs).containsExactly(apk)

        val file = tmp.newFile("app-debug.apk").apply { writeText("APK") }
        val (_, release) = call("gh_create_release", buildJsonObject { put("tag", "v1.0.0"); put("apk_path", file.path); put("generate_notes", true) })
        assertThat(release.ok).isTrue()
        assertThat(gh.releasesCreated.single().generateReleaseNotes).isTrue()
        assertThat(gh.uploads).containsExactly("app-debug.apk:application/vnd.android.package-archive")
        assertThat(call("gh_setup_build_workflow").second.ok).isTrue()
    }
}
