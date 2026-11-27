package dev.gcode.agent

import com.google.common.truth.Truth.assertThat
import dev.gcode.agent.loop.AgentConfig
import dev.gcode.agent.loop.AgentEvent
import dev.gcode.agent.loop.AgentLoop
import dev.gcode.agent.loop.FinishReason
import dev.gcode.agent.tools.ApprovalRequest
import dev.gcode.agent.tools.ApprovalResult
import dev.gcode.agent.tools.CoreTools
import dev.gcode.agent.tools.FileToolDeps
import dev.gcode.agent.tools.GitAccess
import dev.gcode.agent.tools.GitToolDeps
import dev.gcode.agent.tools.MemoryCheckpointStore
import dev.gcode.agent.tools.MemoryTodoStore
import dev.gcode.agent.tools.SecretGuard
import dev.gcode.agent.tools.ToolRegistryExecutor
import dev.gcode.agent.workspace.LocalWorkspaceFs
import dev.gcode.gemini.ResilientGemini
import dev.gcode.gemini.model.Candidate
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.GenerateContentRequest
import dev.gcode.gemini.model.GenerateContentResponse
import dev.gcode.gemini.model.ModelInfo
import dev.gcode.gemini.model.Part
import dev.gcode.gemini.select.ModelHealth
import dev.gcode.gemini.transport.GeminiTransport
import dev.gcode.git.Author
import dev.gcode.git.GitCredentials
import dev.gcode.git.JGitRepository
import dev.gcode.git.SandboxedSystemReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitToolsAndLoopTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var dir: File
    private val prompts = mutableListOf<ApprovalRequest>()

    @Before
    fun setUp() {
        SandboxedSystemReader.install(tmp.newFolder("home"))
        dir = tmp.newFolder("repo")
        JGitRepository.init(dir, "main").use { r ->
            File(dir, "README.md").writeText("hello\n")
            r.addAll()
            r.commit("init", Author("T", "t@example.com"))
        }
    }

    private fun access() = object : GitAccess {
        override suspend fun <T> withRepo(block: suspend (JGitRepository) -> T): T = JGitRepository.open(dir).use { block(it) }
        override suspend fun credentials(): GitCredentials? = null
        override suspend fun author() = Author("Agent", "agent@example.com")
        override suspend fun defaultBranch() = "main"
    }

    private fun executor(mode: PermissionMode = PermissionMode.ASK): ToolRegistryExecutor {
        val fs = LocalWorkspaceFs(dir)
        val tools = CoreTools.create(FileToolDeps({ fs }, { SecretGuard() }, MemoryCheckpointStore()), GitToolDeps { access() }, MemoryTodoStore())
        return ToolRegistryExecutor(tools, mode = { mode }, prompter = { prompts += it; ApprovalResult.Approved })
    }

    /** Scripted model: first a parallel batch of tool calls, then a final answer. */
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

    @Test
    fun `agent edits a file, commits it, and finishes`() = runTest {
        val edit = Part(buildJsonObject {
            put("functionCall", buildJsonObject {
                put("name", "edit_file"); put("id", "c1")
                put("args", buildJsonObject { put("path", "README.md"); put("old_string", "hello"); put("new_string", "hello agent") })
            })
            put("thoughtSignature", "SIG")
        })
        val status = Part.functionCall("git_status", buildJsonObject { }, "c2")
        val add = Part.functionCall("git_add", buildJsonObject { put("paths", buildJsonArray { add(JsonPrimitive(".")) }) }, "c3")
        val commit = Part.functionCall("git_commit", buildJsonObject { put("message", "Greet the agent") }, "c4")
        val script = Script(mutableListOf(Content.model(edit, status), Content.model(add, commit), Content.model(Part.text("Done."))))
        val store = AgentLoopTest.MemoryStore()
        val exec = executor()
        exec.refreshMode()
        val loop = AgentLoop(ResilientGemini(script, ModelHealth(), clock = { 0 }, sleep = {}), store, exec)
        val events = loop.run(Content.user("update the readme and commit"), AgentConfig(listOf("m"), false, "sys", { null })).toList()

        assertThat(events.last()).isEqualTo(AgentEvent.Finished(FinishReason.COMPLETED))
        assertThat(File(dir, "README.md").readText()).isEqualTo("hello agent\n")
        assertThat(JGitRepository.open(dir).use { it.log().first().title }).isEqualTo("Greet the agent")
        // The diff sheet (edit) and git approvals were shown; git_status needed none.
        assertThat(prompts.map { it::class.simpleName }).containsExactly("FileChange", "Action", "Action").inOrder()
        // The signed function-call part went back to the model unchanged, followed by the responses.
        val second = script.requests[1].contents
        assertThat(second[1].parts.first().thoughtSignature).isEqualTo("SIG")
        assertThat(second[2].parts.map { it.functionResponse?.id }).containsExactly("c1", "c2").inOrder()
        assertThat(script.requests[0].tools!!.single().functionDeclarations!!.map { it.name }).contains("git_push")
    }

    @Test
    fun `force push and branch deletion are dangerous`() = runTest {
        val tools = CoreTools.create(FileToolDeps({ null }, { SecretGuard() }, MemoryCheckpointStore()), GitToolDeps { access() }, MemoryTodoStore()).associateBy { it.name }
        assertThat(tools.getValue("git_push").effect(buildJsonObject { put("force", true) })).isEqualTo(ToolEffect.DANGEROUS)
        assertThat(tools.getValue("git_push").effect(buildJsonObject { })).isEqualTo(ToolEffect.REMOTE)
        assertThat(tools.getValue("git_branch").effect(buildJsonObject { put("action", "delete") })).isEqualTo(ToolEffect.DANGEROUS)
        assertThat(tools.getValue("git_branch").effect(buildJsonObject { put("action", "list") })).isEqualTo(ToolEffect.READ)
        // On main (the default branch), merging is in the always-confirm set.
        assertThat(tools.getValue("git_merge").effect(buildJsonObject { put("branch", "x") })).isEqualTo(ToolEffect.DANGEROUS)
    }

    @Test
    fun `plan mode exposes only read tools and submit_plan`() = runTest {
        val exec = executor(PermissionMode.PLAN)
        exec.refreshMode()
        val names = exec.declarations().map { it.name }
        assertThat(names).containsAtLeast("read_file", "grep", "git_status", "submit_plan", "todo_write")
        assertThat(names).containsNoneOf("write_file", "edit_file", "git_commit", "git_push")
    }
}
