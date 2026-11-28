package dev.gcode.agent.extend

import dev.gcode.agent.PermissionMode
import dev.gcode.agent.ToolEffect
import dev.gcode.agent.loop.AgentConfig
import dev.gcode.agent.loop.AgentEvent
import dev.gcode.agent.loop.AgentLoop
import dev.gcode.agent.loop.ConversationStore
import dev.gcode.agent.loop.FinishReason
import dev.gcode.agent.loop.MessageKind
import dev.gcode.agent.tools.ApprovalRequest
import dev.gcode.agent.tools.Prepared
import dev.gcode.agent.tools.StaticEffect
import dev.gcode.agent.tools.Tool
import dev.gcode.agent.tools.ToolArgumentException
import dev.gcode.agent.tools.ToolContext
import dev.gcode.agent.tools.ToolRegistryExecutor
import dev.gcode.agent.tools.ToolResult
import dev.gcode.agent.tools.UserPrompter
import dev.gcode.agent.tools.requireStr
import dev.gcode.agent.tools.schema
import dev.gcode.agent.tools.str
import dev.gcode.gemini.ResilientGemini
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.UsageMetadata
import kotlinx.serialization.json.JsonObject

/** A sub-agent defined in `.gcode/agents/<name>.md` (frontmatter: name, description, tools, model). */
data class AgentDefinition(
    val name: String,
    val description: String,
    /** Tool names it may use; null means the parent's tools (except `task`). */
    val tools: List<String>?,
    val model: String?,
    val prompt: String,
) {
    companion object {
        const val AGENTS_DIR = ".gcode/agents"

        fun parse(fileName: String, text: String): AgentDefinition? {
            val fm = Frontmatter.parse(text)
            val name = fm.string("name") ?: fileName.removeSuffix(".md")
            if (!Regex("^[A-Za-z0-9][A-Za-z0-9_-]*$").matches(name) || fm.body.isBlank()) return null
            return AgentDefinition(
                name = name,
                description = fm.string("description") ?: fm.body.lineSequence().first { it.isNotBlank() }.take(100),
                tools = fm.list("tools"),
                model = fm.string("model"),
                prompt = fm.body,
            )
        }

        fun load(files: Map<String, String>): List<AgentDefinition> =
            files.filterKeys { it.endsWith(".md") && !it.contains('/') }.mapNotNull { (path, text) -> parse(path, text) }.sortedBy { it.name }

        /** The built-in general-purpose sub-agent. */
        val GENERAL = AgentDefinition(
            name = "general",
            description = "General-purpose agent for research and multi-step tasks with the same tools as you",
            tools = null,
            model = null,
            prompt = "You are a sub-agent of GCode. Complete the task you are given with the tools available, then reply with a concise report " +
                "of what you found or changed (file paths, key facts). The report is all the parent agent sees.",
        )
    }
}

/** In-memory conversation for one sub-agent run. */
class InMemoryConversation : ConversationStore {
    val messages = mutableListOf<Pair<Content, MessageKind>>()
    override suspend fun history(): List<Content> = messages.map { it.first }
    override suspend fun append(content: Content, usage: UsageMetadata?, model: String?, kind: MessageKind) {
        messages += content to kind
    }
}

/**
 * The `task` tool (ARCHITECTURE.md section 6.3): runs a sub-agent with its own conversation and a subset of
 * the tools. Its tool calls go through the same permission mode and approvals as the parent's.
 */
class TaskTool(
    private val gemini: ResilientGemini,
    private val parentTools: () -> List<Tool>,
    private val definitions: () -> List<AgentDefinition>,
    private val mode: suspend () -> PermissionMode,
    private val prompter: UserPrompter,
    /** Base config of the parent turn (models, thinking); the system prompt is replaced. */
    private val baseConfig: () -> AgentConfig,
    private val maxSteps: Int = 30,
) : Tool {
    override val name = NAME
    override val description: String
        get() = "Run a sub-agent on a self-contained task (research, a focused change, a review) with a fresh context. " +
            "It returns only its final report. Available agents: " + all().joinToString("; ") { "${it.name}: ${it.description}" }
    override val parameters = schema {
        string("description", "3–6 word summary shown to the user", required = true)
        string("prompt", "Complete instructions for the sub-agent, with all the context it needs", required = true)
        string("agent", "Agent name (default: general)")
    }

    private fun all(): List<AgentDefinition> = listOf(AgentDefinition.GENERAL) + definitions().filter { it.name != AgentDefinition.GENERAL.name }

    private fun definition(args: JsonObject): AgentDefinition {
        val wanted = args.str("agent")?.takeIf { it.isNotBlank() } ?: AgentDefinition.GENERAL.name
        return all().firstOrNull { it.name == wanted } ?: throw ToolArgumentException("No agent '$wanted'. Available: ${all().joinToString { it.name }}")
    }

    /** The sub-agent's tools: never `task` itself (no recursion). Unknown names in a definition are ignored. */
    fun toolsFor(definition: AgentDefinition): List<Tool> {
        val available = parentTools().filter { it.name != NAME && it.name != "submit_plan" }
        return definition.tools?.let { names -> available.filter { it.name in names } } ?: available
    }

    /** Read-only if every tool it may use is read-only; otherwise it counts as a workspace change. */
    override suspend fun effect(args: JsonObject): ToolEffect {
        val tools = runCatching { toolsFor(definition(args)) }.getOrElse { return ToolEffect.READ }
        return if (tools.all { (it as? StaticEffect)?.staticEffect == ToolEffect.READ || it.name == "todo_write" }) ToolEffect.READ else ToolEffect.WORKSPACE_WRITE
    }

    override suspend fun prepare(args: JsonObject, context: ToolContext): Prepared {
        val def = definition(args)
        val summary = args.requireStr("description")
        val prompt = args.requireStr("prompt")
        val tools = toolsFor(def)
        val approval = ApprovalRequest.Action(NAME, "Run sub-agent ${def.name}: $summary", "Tools: ${tools.joinToString { it.name }}\n\n$prompt", dangerous = false)
        return Prepared(approval) {
            val executor = ToolRegistryExecutor(tools, mode = mode, prompter = prompter)
            executor.refreshMode()
            val store = InMemoryConversation()
            val base = baseConfig()
            val config = base.copy(
                modelCandidates = def.model?.let { listOf(it) + base.modelCandidates.filter { m -> m != it } } ?: base.modelCandidates,
                systemInstruction = def.prompt + "\n\n" + SUBAGENT_RULES,
                maxSteps = maxSteps,
                beforeModelCall = { executor.refreshMode() },
            )
            var reason: FinishReason = FinishReason.COMPLETED
            var error: Throwable? = null
            var calls = 0
            AgentLoop(gemini, store, executor).run(Content.user(prompt), config).collect { event ->
                when (event) {
                    is AgentEvent.ToolStarted -> {
                        calls++
                        context.progress("${def.name}: ${event.call.name}")
                    }
                    is AgentEvent.Finished -> {
                        reason = event.reason
                        error = event.error
                    }
                    else -> Unit
                }
            }
            val report = store.messages.lastOrNull { it.first.role == Content.ROLE_MODEL && it.first.text.isNotBlank() }?.first?.text.orEmpty()
            when (reason) {
                FinishReason.COMPLETED -> ToolResult.ok("${def.name} finished ($calls tool calls)", "report" to report.ifBlank { "(no report)" })
                FinishReason.STEP_LIMIT -> ToolResult(true, ToolResult.jsonOf(listOf("report" to report, "note" to "The sub-agent hit its step limit ($maxSteps); the report may be incomplete.")), "${def.name} hit the step limit")
                else -> ToolResult.error("Sub-agent ${def.name} stopped: ${reason.name.lowercase()}${error?.message?.let { " ($it)" } ?: ""}. Partial report: ${report.take(2_000)}")
            }
        }
    }

    companion object {
        const val NAME = "task"
        const val SUBAGENT_RULES = "You are running as a sub-agent: you cannot ask the user questions. Work autonomously within your tools, " +
            "then finish with a concise final report; it is the only thing returned to the parent agent."
    }
}
