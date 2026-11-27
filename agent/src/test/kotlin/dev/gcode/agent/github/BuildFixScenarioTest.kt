package dev.gcode.agent.github

import com.google.common.truth.Truth.assertThat
import dev.gcode.agent.AgentLoopTest
import dev.gcode.agent.PermissionMode
import dev.gcode.agent.loop.AgentConfig
import dev.gcode.agent.loop.AgentEvent
import dev.gcode.agent.loop.AgentLoop
import dev.gcode.agent.loop.FinishReason
import dev.gcode.agent.tools.ApprovalRequest
import dev.gcode.agent.tools.ApprovalResult
import dev.gcode.agent.tools.CoreTools
import dev.gcode.agent.tools.FileToolDeps
import dev.gcode.agent.tools.GitToolDeps
import dev.gcode.agent.tools.MemoryCheckpointStore
import dev.gcode.agent.tools.MemoryTodoStore
import dev.gcode.agent.tools.SecretGuard
import dev.gcode.agent.tools.ToolRegistryExecutor
import dev.gcode.agent.tools.github.BuildFixGuard
import dev.gcode.agent.tools.github.GitHubToolDeps
import dev.gcode.agent.tools.github.GitHubTools
import dev.gcode.agent.workspace.LocalWorkspaceFs
import dev.gcode.core.model.RepoRef
import dev.gcode.gemini.ResilientGemini
import dev.gcode.gemini.model.Candidate
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.GenerateContentRequest
import dev.gcode.gemini.model.GenerateContentResponse
import dev.gcode.gemini.model.ModelInfo
import dev.gcode.gemini.model.Part
import dev.gcode.gemini.select.ModelHealth
import dev.gcode.gemini.transport.GeminiTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Scripted model: build fails → read logs → fix → rebuild succeeds (ARCHITECTURE.md section 6.6). */
class BuildFixScenarioTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Script(private val turns: MutableList<Content>) : GeminiTransport {
        val requests = mutableListOf<GenerateContentRequest>()
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override fun streamGenerateContent(model: String, request: GenerateContentRequest): Flow<GenerateContentResponse> {
            requests += request
            return flowOf(GenerateContentResponse(listOf(Candidate(turns.removeAt(0), "STOP"))))
        }
        override suspend fun generateContent(model: String, request: GenerateContentRequest) = error("unused")
        override suspend fun countTokens(model: String, contents: List<Content>) = 0
    }

    private fun call(name: String, id: String, args: JsonObject = JsonObject(emptyMap())) = Content.model(Part.functionCall(name, args, id))

    @Test
    fun `failed build is diagnosed, fixed and rebuilt`() = runTest {
        val dir = tmp.newFolder("repo")
        File(dir, "Main.kt").writeText("fun main() = greet()\n")
        val gh = FakeGitHub().apply { conclusions += listOf("failure", "success") }
        val host = FakeHost(gh)
        val guard = BuildFixGuard { 3 }
        val fs = LocalWorkspaceFs(dir)
        val tools = CoreTools.create(FileToolDeps({ fs }, { SecretGuard() }, MemoryCheckpointStore()), GitToolDeps { null }, MemoryTodoStore()) +
            GitHubTools.create(GitHubToolDeps(gh, gh, gh, gh, gh, host, sleep = {}), guard)
        val prompts = mutableListOf<ApprovalRequest>()
        val executor = ToolRegistryExecutor(tools, mode = { PermissionMode.FULL_AUTO }, prompter = { prompts += it; ApprovalResult.Approved })
        executor.refreshMode()

        val fix = buildJsonObject { put("path", "Main.kt"); put("old_string", "fun main() = greet()"); put("new_string", "fun greet() = println(\"hi\")\nfun main() = greet()") }
        val script = Script(
            mutableListOf(
                call("gh_trigger_workflow", "t1"),
                call("gh_wait_for_run", "t2", buildJsonObject { put("run_id", 100) }),
                call("gh_get_run_logs", "t3", buildJsonObject { put("run_id", 100) }),
                call("edit_file", "t4", fix),
                call("gh_trigger_workflow", "t5"),
                call("gh_wait_for_run", "t6", buildJsonObject { put("run_id", 101) }),
                Content.model(Part.text("The build failed on a missing greet() function; I added it and the rebuild succeeded.")),
            ),
        )
        val loop = AgentLoop(ResilientGemini(script, ModelHealth(), clock = { 0 }, sleep = {}), AgentLoopTest.MemoryStore(), executor)
        val events = loop.run(Content.user("Build the app and fix it if it fails"), AgentConfig(listOf("m"), false, "sys", { null })).toList()

        assertThat(events.last()).isEqualTo(AgentEvent.Finished(FinishReason.COMPLETED))
        assertThat(File(dir, "Main.kt").readText()).contains("fun greet()")
        assertThat(host.builds).hasSize(2)
        assertThat(guard.failures(RepoRef("octo", "app"))).isEqualTo(0)

        fun response(request: Int) = script.requests[request].contents.last().parts.single().functionResponse!!.response
        assertThat(response(2)["next"]!!.jsonPrimitive.content).contains("Fix attempts left: 3 of 3")
        assertThat(response(3)["logs"]!!.jsonPrimitive.content).contains("Unresolved reference 'greet'")
        assertThat(response(6)["conclusion"]!!.jsonPrimitive.content).isEqualTo("success")
        // Full auto: builds ran without prompts; nothing in this scenario is in the always-confirm set.
        assertThat(prompts).isEmpty()
        // The GitHub tools were declared to the model.
        assertThat(script.requests.first().tools!!.single().functionDeclarations!!.map { it.name }).containsAtLeast("gh_trigger_workflow", "gh_wait_for_run", "gh_get_run_logs")
    }
}
