package dev.gcode.app.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gcode.agent.PermissionMode
import dev.gcode.agent.SystemPrompt
import dev.gcode.agent.loop.AgentConfig
import dev.gcode.agent.loop.AgentEvent
import dev.gcode.agent.loop.AgentLoop
import dev.gcode.agent.loop.ConversationStore
import dev.gcode.agent.loop.FinishReason
import dev.gcode.agent.loop.MessageKind
import dev.gcode.agent.tools.CoreTools
import dev.gcode.agent.tools.FileToolDeps
import dev.gcode.agent.tools.GitToolDeps
import dev.gcode.agent.tools.MemoryCheckpointStore
import dev.gcode.agent.tools.TodoItem
import dev.gcode.agent.tools.TodoJson
import dev.gcode.agent.tools.Tool
import dev.gcode.agent.tools.ToolRegistryExecutor
import dev.gcode.app.di.ApplicationScope
import dev.gcode.app.service.AgentService
import dev.gcode.app.workspace.ActiveWorkspace
import dev.gcode.app.workspace.WorkspaceProvider
import dev.gcode.core.data.db.GCodeDatabase
import dev.gcode.core.data.db.MessageEntity
import dev.gcode.core.data.repo.ModelRepository
import dev.gcode.core.data.repo.SessionRepository
import dev.gcode.core.data.settings.SettingsRepository
import dev.gcode.gemini.ResilientGemini
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.UsageMetadata
import dev.gcode.gemini.select.ModelHealth
import dev.gcode.gemini.select.ModelSelector
import dev.gcode.gemini.select.ThinkingLevel
import dev.gcode.gemini.select.ThinkingSupport
import dev.gcode.gemini.transport.GeminiApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A tool call in the running turn (shown as a card while it runs). */
data class LiveToolCall(val id: String, val name: String, val args: String, val running: Boolean = true, val ok: Boolean = true, val summary: String? = null, val display: String? = null)

/** Live state of a running (or just finished) turn, shown above the composer. */
data class LiveTurn(
    val running: Boolean = false,
    val activeModel: String? = null,
    val streamingText: String = "",
    val streamingThoughts: String = "",
    val notice: String? = null,
    val error: String? = null,
    val finishReason: FinishReason? = null,
    val tools: List<LiveToolCall> = emptyList(),
    val status: String? = null,
)

/**
 * Runs agent turns in an application-wide scope (decision 12), with the tool executor, permission mode,
 * approvals and checkpoints for the session. A foreground service keeps turns alive in the background.
 */
@Singleton
class AgentSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionRepository,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val gemini: ResilientGemini,
    private val selector: ModelSelector,
    private val health: ModelHealth,
    private val prompts: PromptBroker,
    private val workspaces: WorkspaceProvider,
    private val db: GCodeDatabase,
    private val extraTools: ExtraToolsProvider,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val jobs = mutableMapOf<String, Job>()
    private val _live = MutableStateFlow<Map<String, LiveTurn>>(emptyMap())
    val live: StateFlow<Map<String, LiveTurn>> = _live.asStateFlow()

    fun isRunning(sessionId: String): Boolean = jobs[sessionId]?.isActive == true

    fun send(sessionId: String, user: Content) = start(sessionId, user)

    fun retry(sessionId: String) = start(sessionId, null)

    fun stop(sessionId: String) {
        jobs[sessionId]?.cancel()
    }

    fun stopAll() = jobs.values.toList().forEach { it.cancel() }

    fun clearError(sessionId: String) = update(sessionId) { it.copy(error = null, notice = null) }

    private fun start(sessionId: String, user: Content?) {
        if (isRunning(sessionId)) return
        update(sessionId) { LiveTurn(running = true) }
        AgentService.start(context)
        jobs[sessionId] = scope.launch {
            try {
                val workspace = workspaces.current()
                val executor = executor(sessionId, workspace)
                val config = buildConfig(sessionId, workspace, executor)
                AgentLoop(gemini, store(sessionId), executor).run(user, config).collect { event -> onEvent(sessionId, event) }
            } catch (e: CancellationException) {
                update(sessionId) { it.copy(running = false, streamingText = "", streamingThoughts = "", finishReason = FinishReason.CANCELLED, tools = it.tools.map { t -> t.copy(running = false) }) }
                throw e
            } catch (e: Throwable) {
                update(sessionId) { it.copy(running = false, error = describe(e), finishReason = FinishReason.ERROR) }
            } finally {
                jobs.remove(sessionId)
                update(sessionId) { it.copy(running = false, status = null) }
            }
        }
    }

    suspend fun mode(sessionId: String): PermissionMode = PermissionMode.fromId(sessions.get(sessionId)?.mode)

    private suspend fun executor(sessionId: String, workspace: ActiveWorkspace?): ToolRegistryExecutor {
        val checkpoints = if (workspace != null) {
            RoomCheckpointStore(sessionId, workspace.key, db, sessions, File(context.filesDir, "checkpoints"))
        } else {
            MemoryCheckpointStore()
        }
        val todos = SessionTodoStore(sessionId, sessions) {}
        val tools: List<Tool> = CoreTools.create(
            FileToolDeps({ workspace?.fs }, { workspace?.guard ?: dev.gcode.agent.tools.SecretGuard() }, checkpoints),
            GitToolDeps { workspace?.git },
            todos,
        ) + extraTools.tools(sessionId, workspace)
        return ToolRegistryExecutor(
            tools,
            mode = { mode(sessionId) },
            prompter = prompts,
            onModeChange = { m -> sessions.setMode(sessionId, m.id) },
        ).also { it.refreshMode() }
    }

    private suspend fun buildConfig(sessionId: String, workspace: ActiveWorkspace?, executor: ToolRegistryExecutor): AgentConfig {
        val s = settings.current()
        val catalog = runCatching { models.refreshIfStale() }.getOrElse { models.current() }
        val now = System.currentTimeMillis()
        val chain = selector.chain(catalog, s.preferredModel).filter { health.isUsable(it, now) }
        val candidates = if (s.autoSwitchModels) selector.switchCandidates(catalog, s.preferredModel).filter { health.isUsable(it, now) } else chain
        val level = s.thinkingLevel?.let { w -> ThinkingLevel.entries.firstOrNull { it.wire == w } }
        val branch = workspace?.git?.let { g -> runCatching { g.withRepo { it.currentBranch() } }.getOrNull() }
        val wsInfo = workspace?.let {
            SystemPrompt.Workspace(it.fs.displayName, if (it.repo != null) "cloned GitHub repository" else "device folder", branch, it.repo?.defaultBranch, it.git != null)
        }
        return AgentConfig(
            modelCandidates = candidates.ifEmpty { listOf(s.preferredModel) },
            autoSwitch = s.autoSwitchModels,
            systemInstruction = SystemPrompt.agent(wsInfo, mode(sessionId), TodoJson.decode(sessions.get(sessionId)?.todoJson), memory = extraTools.memory(workspace)),
            thinking = { model -> ThinkingSupport.configFor(model, catalog.find(model)?.thinking ?: false, level, s.showThoughts, s.rejectedThinking) },
            maxSteps = s.maxSteps,
            beforeModelCall = { executor.refreshMode(); extraTools.beforeModelCall(sessionId) },
        )
    }

    private fun store(sessionId: String) = object : ConversationStore {
        override suspend fun history(): List<Content> = sessions.messages(sessionId).filter { !it.compacted }.map { it.content }

        override suspend fun append(content: Content, usage: UsageMetadata?, model: String?, kind: MessageKind) {
            val k = when (kind) {
                MessageKind.CHAT -> MessageEntity.KIND_CHAT
                MessageKind.TOOL_RESULTS -> MessageEntity.KIND_TOOL_RESULTS
                MessageKind.SUMMARY -> MessageEntity.KIND_SUMMARY
            }
            sessions.append(sessionId, content, usage, model, k)
            if (usage != null && model != null) {
                val limit = models.current().find(model)?.inputTokenLimit ?: 0
                sessions.recordUsage(sessionId, usage.promptTokenCount + usage.candidatesTokenCount, limit, model)
            }
        }
    }

    private suspend fun onEvent(sessionId: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.ModelStarted -> update(sessionId) { it.copy(activeModel = event.model, notice = null, status = "Thinking with ${event.model}") }
            is AgentEvent.TextDelta -> update(sessionId) { it.copy(streamingText = it.streamingText + event.text, notice = null, status = "Writing") }
            is AgentEvent.ThoughtDelta -> update(sessionId) { it.copy(streamingThoughts = it.streamingThoughts + event.text) }
            is AgentEvent.Retrying -> update(sessionId) {
                it.copy(notice = "${event.reason}: retrying ${event.model} in ${(event.delayMs + 999) / 1000} s (attempt ${event.attempt})")
            }
            is AgentEvent.ModelSwitched -> update(sessionId) { it.copy(activeModel = event.to, notice = "Switched from ${event.from} to ${event.to}: ${event.reason}") }
            is AgentEvent.ModelMessage -> update(sessionId) { it.copy(streamingText = "", streamingThoughts = "", tools = emptyList()) }
            is AgentEvent.ToolStarted -> update(sessionId) {
                it.copy(
                    tools = it.tools + LiveToolCall(event.call.id ?: event.call.name, event.call.name, summarizeArgs(event.call.args)),
                    status = "Running ${event.call.name}",
                )
            }
            is AgentEvent.ToolFinished -> update(sessionId) { turn ->
                turn.copy(tools = turn.tools.map { t -> if (t.id == (event.call.id ?: event.call.name)) t.copy(running = false, ok = event.ok, summary = event.summary, display = event.display) else t })
            }
            is AgentEvent.ToolProgress -> update(sessionId) { it.copy(status = event.message) }
            is AgentEvent.Finished -> {
                (event.error as? GeminiApiException)?.let { rememberRejectedThinking(it) }
                update(sessionId) {
                    it.copy(
                        running = false,
                        streamingText = "",
                        streamingThoughts = "",
                        finishReason = event.reason,
                        error = event.error?.let(::describe) ?: if (event.reason == FinishReason.STEP_LIMIT) "Stopped after the step limit. Send \"continue\" to keep going." else null,
                    )
                }
            }
        }
    }

    /** A 400 about the thinking level: drop that level for the model so the next try uses its default. */
    private suspend fun rememberRejectedThinking(e: GeminiApiException) {
        if (!e.isInvalidArgument || !e.message.contains("thinking", ignoreCase = true)) return
        val s = settings.current()
        val level = s.thinkingLevel ?: return
        val model = live.value.values.firstNotNullOfOrNull { it.activeModel } ?: s.preferredModel
        settings.update { it.copy(rejectedThinking = it.rejectedThinking + "$model:$level") }
    }

    private fun update(sessionId: String, f: (LiveTurn) -> LiveTurn) =
        _live.update { map -> map + (sessionId to f(map[sessionId] ?: LiveTurn())) }

    companion object {
        fun describe(e: Throwable): String = when {
            e is GeminiApiException && e.isInvalidKey -> "The Gemini API key was rejected. Check it in Settings."
            e is GeminiApiException && e.isRateLimited -> "Rate limited by the Gemini API: ${e.message}"
            e is GeminiApiException -> "Gemini API error ${e.httpCode}: ${e.message}"
            e is java.io.IOException -> "Network error: ${e.message ?: e::class.simpleName}"
            else -> e.message ?: e::class.simpleName.orEmpty()
        }

        /** Short, single-line view of tool arguments for cards (long values cut). */
        fun summarizeArgs(args: JsonObject): String = args.entries.joinToString(", ") { (k, v) ->
            val text = (v as? JsonPrimitive)?.content ?: v.toString()
            "$k=" + if (text.length > 60) text.take(57) + "…" else text
        }
    }
}

/** The chat session currently shown; other screens (Sessions) can switch it. */
@Singleton
class ActiveSession @Inject constructor() {
    private val _id = MutableStateFlow<String?>(null)
    val id: StateFlow<String?> = _id.asStateFlow()

    fun open(sessionId: String?) {
        _id.value = sessionId
    }
}

/**
 * Tools and prompt additions contributed by later phases (GitHub/Actions/web tools, sub-agents, MCP,
 * project memory, compaction). Phase 4 contributes none.
 */
interface ExtraToolsProvider {
    suspend fun tools(sessionId: String, workspace: ActiveWorkspace?): List<Tool>
    suspend fun memory(workspace: ActiveWorkspace?): String?
    suspend fun beforeModelCall(sessionId: String)
}

@Singleton
class DefaultExtraTools @Inject constructor() : ExtraToolsProvider {
    override suspend fun tools(sessionId: String, workspace: ActiveWorkspace?): List<Tool> = emptyList()
    override suspend fun memory(workspace: ActiveWorkspace?): String? = null
    override suspend fun beforeModelCall(sessionId: String) = Unit
}
