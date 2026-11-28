package dev.gcode.agent.extend

import com.google.common.truth.Truth.assertThat
import dev.gcode.agent.AgentLoopTest
import dev.gcode.agent.PermissionMode
import dev.gcode.agent.ToolEffect
import dev.gcode.agent.compaction.Compaction
import dev.gcode.agent.compaction.HistoryEntry
import dev.gcode.agent.loop.AgentConfig
import dev.gcode.agent.loop.MessageKind
import dev.gcode.agent.tools.ApprovalResult
import dev.gcode.agent.tools.CoreTools
import dev.gcode.agent.tools.FileToolDeps
import dev.gcode.agent.tools.GitToolDeps
import dev.gcode.agent.tools.MemoryCheckpointStore
import dev.gcode.agent.tools.MemoryTodoStore
import dev.gcode.agent.tools.SecretGuard
import dev.gcode.agent.tools.ToolContext
import dev.gcode.agent.tools.mcp.McpServerConfig
import dev.gcode.agent.tools.mcp.McpToolAdapter
import dev.gcode.agent.tools.mcp.SchemaSanitizer
import dev.gcode.agent.workspace.LocalWorkspaceFs
import dev.gcode.gemini.ResilientGemini
import dev.gcode.gemini.model.Candidate
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.GenerateContentRequest
import dev.gcode.gemini.model.GenerateContentResponse
import dev.gcode.gemini.model.GeminiJson
import dev.gcode.gemini.model.ModelInfo
import dev.gcode.gemini.model.Part
import dev.gcode.gemini.select.ModelHealth
import dev.gcode.gemini.transport.GeminiTransport
import dev.gcode.mcp.McpClient
import dev.gcode.mcp.McpTool
import dev.gcode.mcp.McpToolAnnotations
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExtensionsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `frontmatter with scalars, inline and block lists`() {
        val fm = Frontmatter.parse(
            """
            ---
            name: reviewer
            description: "Reviews diffs: style and bugs"
            tools: [read_file, grep]
            model: 'gemini-3.8-flash'
            tags:
              - review
              - quality
            # a comment
            ---
            You review code.

            Be strict.
            """.trimIndent(),
        )
        assertThat(fm.string("name")).isEqualTo("reviewer")
        assertThat(fm.string("description")).isEqualTo("Reviews diffs: style and bugs")
        assertThat(fm.list("tools")).containsExactly("read_file", "grep").inOrder()
        assertThat(fm.list("tags")).containsExactly("review", "quality").inOrder()
        assertThat(fm.string("model")).isEqualTo("gemini-3.8-flash")
        assertThat(fm.body).isEqualTo("You review code.\n\nBe strict.")
        assertThat(Frontmatter.parse("tools: x\nNo frontmatter").body).isEqualTo("tools: x\nNo frontmatter")
        assertThat(Frontmatter.parse("---\ntools: a, b\n---\nbody").list("tools")).containsExactly("a", "b")
    }

    @Test
    fun `slash commands expand arguments`() {
        assertThat(SlashCommands.parse("/fix-issue 42 urgent")).isEqualTo("fix-issue" to "42 urgent")
        assertThat(SlashCommands.parse("  /init")).isEqualTo("init" to "")
        assertThat(SlashCommands.parse("not /a command")).isNull()
        assertThat(SlashCommands.parse("// comment")).isNull()
        assertThat(SlashCommands.expand("Fix issue #\$1 ($2). Context: \$ARGUMENTS", "42 urgent")).isEqualTo("Fix issue #42 (urgent). Context: 42 urgent")
        assertThat(SlashCommands.expand("Review the diff.", "focus on tests")).isEqualTo("Review the diff.\n\nfocus on tests")
        assertThat(SlashCommands.expand("Review \$ARGUMENTS", "")).isEqualTo("Review ")
    }

    @Test
    fun `project commands load from files and resolve`() {
        val project = SlashCommands.load(
            mapOf(
                "review.md" to "---\ndescription: Review changes\nargument-hint: [focus]\n---\nReview my changes. Focus: \$ARGUMENTS",
                "git/release-notes.md" to "Write release notes since \$1",
                "notes.txt" to "ignored",
            ),
        )
        assertThat(project.map { it.name }).containsExactly("git:release-notes", "review").inOrder()
        assertThat(project.last().argumentHint).isEqualTo("[focus]")
        assertThat(SlashCommands.resolve("/review security", project)).isEqualTo(CommandAction.Prompt("Review my changes. Focus: security", "review"))
        assertThat(SlashCommands.resolve("/git:release-notes v1.2", project)).isEqualTo(CommandAction.Prompt("Write release notes since v1.2", "git:release-notes"))
        assertThat(SlashCommands.resolve("/compact", project)).isEqualTo(CommandAction.Compact)
        assertThat(SlashCommands.resolve("/clear", project)).isEqualTo(CommandAction.Clear)
        assertThat((SlashCommands.resolve("/init", project) as CommandAction.Prompt).text).contains("GEMINI.md")
        assertThat(SlashCommands.resolve("/nope", project)).isEqualTo(CommandAction.Unknown("nope"))
        assertThat(SlashCommands.suggestions("/re", project).map { it.name }).containsExactly("review")
        assertThat(SlashCommands.suggestions("/", project).map { it.name }).containsAtLeast("compact", "init", "review")
    }

    @Test
    fun `agent definitions`() {
        val defs = AgentDefinition.load(
            mapOf(
                "reviewer.md" to "---\ndescription: Reviews code\ntools: read_file, grep\n---\nYou are a reviewer.",
                "empty.md" to "---\nname: empty\n---\n",
                "nested/x.md" to "ignored",
            ),
        )
        assertThat(defs.map { it.name }).containsExactly("reviewer")
        assertThat(defs.single().tools).containsExactly("read_file", "grep")
        assertThat(defs.single().prompt).isEqualTo("You are a reviewer.")
    }

    private fun entry(seq: Int, content: Content, kind: MessageKind = MessageKind.CHAT) = HistoryEntry(seq, content, kind)

    @Test
    fun `compaction cuts only at turn boundaries`() {
        val call = Content.model(Part.functionCall("read_file", buildJsonObject { put("path", "a") }, "c1"))
        val result = Content(Content.ROLE_USER, listOf(Part.functionResponse("read_file", buildJsonObject { put("result", "x".repeat(5_000)) }, "c1")))
        val history = listOf(
            entry(1, Content.user("Add a login screen")),
            entry(2, call),
            entry(3, result, MessageKind.TOOL_RESULTS),
            entry(4, Content.model(Part.text("Done"))),
            entry(5, Content.user("Now add tests")),
            entry(6, call),
            entry(7, result, MessageKind.TOOL_RESULTS),
        )
        // Keeps the current turn (from message 5), including its tool call and response.
        assertThat(Compaction.boundary(history)).isEqualTo(5)
        // Nothing before the only turn: nothing to compact.
        assertThat(Compaction.boundary(history.drop(4))).isNull()
        assertThat(Compaction.boundary(emptyList())).isNull()
        // Tool results never start a turn.
        assertThat(Compaction.startsTurn(history[2])).isFalse()
        assertThat(Compaction.manualBoundary(history)).isEqualTo(8)

        assertThat(Compaction.shouldCompact(800_000, 1_000_000, 80)).isTrue()
        assertThat(Compaction.shouldCompact(799_999, 1_000_000, 80)).isFalse()
        assertThat(Compaction.shouldCompact(10, 0, 80)).isFalse()

        val transcript = Compaction.transcript("${Compaction.SUMMARY_HEADER}\n\nEarlier goal", history.take(4))
        assertThat(transcript).startsWith("[Earlier summary]\nEarlier goal")
        assertThat(transcript).contains("[User]\nAdd a login screen")
        assertThat(transcript).contains("→ read_file(")
        assertThat(transcript).contains("← read_file: result: xxx")
        assertThat(transcript).contains(" …")
        assertThat(transcript.length).isLessThan(5_000)
        val request = Compaction.summaryRequest(null, history.take(4))
        assertThat(request.tools).isNull()
        assertThat(request.contents.single().text).contains("## Goal")
    }

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
    fun `task runs a sub-agent with a tool subset and returns its report`() = runTest {
        val dir = tmp.newFolder("ws").apply { File(this, "notes.md").writeText("The API key lives in the keystore.\n") }
        val fs = LocalWorkspaceFs(dir)
        val parentTools = CoreTools.create(FileToolDeps({ fs }, { SecretGuard() }, MemoryCheckpointStore()), GitToolDeps { null }, MemoryTodoStore())
        val script = Script(
            mutableListOf(
                Content.model(Part.functionCall("read_file", buildJsonObject { put("path", "notes.md") }, "s1")),
                Content.model(Part.text("notes.md says the API key lives in the keystore.")),
            ),
        )
        val progress = mutableListOf<String>()
        val reviewer = AgentDefinition("reviewer", "Reads files", listOf("read_file", "grep", "task"), null, "You read files.")
        val task = TaskTool(
            gemini = ResilientGemini(script, ModelHealth(), clock = { 0 }, sleep = {}),
            parentTools = { parentTools },
            definitions = { listOf(reviewer) },
            mode = { PermissionMode.ASK },
            prompter = { ApprovalResult.Approved },
            baseConfig = { AgentConfig(listOf("m"), false, "parent", { null }) },
        )
        // Read-only agents start without approval; the general agent can write, so it counts as a workspace change.
        assertThat(task.effect(buildJsonObject { put("agent", "reviewer") })).isEqualTo(ToolEffect.READ)
        assertThat(task.effect(JsonObject(emptyMap()))).isEqualTo(ToolEffect.WORKSPACE_WRITE)
        assertThat(task.toolsFor(reviewer).map { it.name }).containsExactly("read_file", "grep")
        assertThat(task.description).contains("reviewer: Reads files")

        val args = buildJsonObject { put("description", "Find the key"); put("prompt", "Where is the API key stored?"); put("agent", "reviewer") }
        val result = task.prepare(args, ToolContext("t") { progress += it }).apply(ApprovalResult.Approved)
        assertThat(result.ok).isTrue()
        assertThat(result.response["report"]!!.jsonPrimitive.content).isEqualTo("notes.md says the API key lives in the keystore.")
        assertThat(progress).containsExactly("reviewer: read_file")
        val first = script.requests.first()
        assertThat(first.systemInstruction!!.text).startsWith("You read files.")
        assertThat(first.tools!!.single().functionDeclarations!!.map { it.name }).containsExactly("read_file", "grep")
        assertThat(first.contents.single().text).isEqualTo("Where is the API key stored?")
    }

    @Test
    fun `mcp tools get safe names, sanitised schemas and effects`() {
        val schema = GeminiJson.parseToJsonElement(
            """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,
               "properties":{"q":{"type":"string","description":"Query","minLength":1},
                             "limit":{"type":["integer","null"]},
                             "mode":{"anyOf":[{"type":"null"},{"type":"string","enum":["fast","full"]}],"description":"Mode"},
                             "tags":{"type":"array","items":{"type":"string","pattern":"x"}}},
               "required":["q"]}""",
        ).jsonObject
        val clean = SchemaSanitizer.sanitize(schema)
        assertThat(clean.keys).containsExactly("type", "properties", "required")
        val props = clean["properties"]!!.jsonObject
        assertThat(props["q"]!!.jsonObject.keys).containsExactly("type", "description")
        assertThat(props["limit"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("integer")
        assertThat(props["limit"]!!.jsonObject["nullable"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(props["mode"]!!.jsonObject["enum"].toString()).isEqualTo("""["fast","full"]""")
        assertThat(props["mode"]!!.jsonObject["description"]!!.jsonPrimitive.content).isEqualTo("Mode")
        assertThat(props["tags"]!!.jsonObject["items"]!!.jsonObject.keys).containsExactly("type")
        assertThat(SchemaSanitizer.sanitize(JsonObject(emptyMap()))).isEqualTo(buildJsonObject { put("type", "object"); put("properties", JsonObject(emptyMap())) })

        assertThat(McpToolAdapter.functionName("My Docs!", "search.pages")).isEqualTo("mcp__My_Docs__search_pages")
        assertThat(McpToolAdapter.functionName("s".repeat(40), "t".repeat(40)).length).isEqualTo(64)

        val client = McpClient(OkHttpClient(), "https://example.com/mcp")
        val readOnly = McpTool("search", annotations = McpToolAnnotations(readOnlyHint = true))
        assertThat(McpToolAdapter(McpServerConfig("1", "docs", "u", trusted = true), client, readOnly).staticEffect).isEqualTo(ToolEffect.READ)
        // Hints from untrusted servers are not believed.
        assertThat(McpToolAdapter(McpServerConfig("1", "docs", "u", trusted = false), client, readOnly).staticEffect).isEqualTo(ToolEffect.REMOTE)
        assertThat(McpToolAdapter(McpServerConfig("1", "docs", "u", trusted = true), client, McpTool("write")).staticEffect).isEqualTo(ToolEffect.REMOTE)
    }
}
