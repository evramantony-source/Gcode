package dev.gcode.app.agent

import dev.gcode.agent.SystemPrompt
import dev.gcode.agent.loop.AgentConfig
import dev.gcode.agent.loop.AgentEvent
import dev.gcode.agent.loop.AgentLoop
import dev.gcode.agent.loop.ConversationStore
import dev.gcode.agent.loop.FinishReason
import dev.gcode.agent.loop.MessageKind
import dev.gcode.agent.loop.NoToolExecutor
import dev.gcode.app.di.ApplicationScope
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
import javax.inject.Inject
import javax.inject.Singleton

/** Live state of a running (or just finished) turn, shown above the composer. */
data class LiveTurn(
    val running: Boolean = false,
    val activeModel: String? = null,
    val streamingText: String = "",
    val streamingThoughts: String = "",
    val notice: String? = null,
    val error: String? = null,
    val finishReason: FinishReason? = null,
)

/**
 * Runs agent turns in an application-wide scope, so a reply keeps streaming across configuration
 * changes and screen switches. One turn per session at a time.
 */
@Singleton
class AgentSessionManager @Inject constructor(
    private val sessions: SessionRepository,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val gemini: ResilientGemini,
    private val selector: ModelSelector,
    private val health: ModelHealth,
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

    fun clearError(sessionId: String) = update(sessionId) { it.copy(error = null, notice = null) }

    private fun start(sessionId: String, user: Content?) {
        if (isRunning(sessionId)) return
        update(sessionId) { LiveTurn(running = true) }
        jobs[sessionId] = scope.launch {
            try {
                val config = buildConfig()
                AgentLoop(gemini, store(sessionId), NoToolExecutor).run(user, config).collect { event -> onEvent(sessionId, event) }
            } catch (e: CancellationException) {
                update(sessionId) { it.copy(running = false, streamingText = "", streamingThoughts = "", finishReason = FinishReason.CANCELLED) }
                throw e
            } catch (e: Throwable) {
                update(sessionId) { it.copy(running = false, error = describe(e), finishReason = FinishReason.ERROR) }
            } finally {
                jobs.remove(sessionId)
                update(sessionId) { it.copy(running = false) }
            }
        }
    }

    private suspend fun buildConfig(): AgentConfig {
        val s = settings.current()
        val catalog = runCatching { models.refreshIfStale() }.getOrElse { models.current() }
        val now = System.currentTimeMillis()
        val chain = selector.chain(catalog, s.preferredModel).filter { health.isUsable(it, now) }
        val candidates = if (s.autoSwitchModels) selector.switchCandidates(catalog, s.preferredModel).filter { health.isUsable(it, now) } else chain
        val list = candidates.ifEmpty { listOf(s.preferredModel) }
        val level = s.thinkingLevel?.let { w -> ThinkingLevel.entries.firstOrNull { it.wire == w } }
        return AgentConfig(
            modelCandidates = list,
            autoSwitch = s.autoSwitchModels,
            systemInstruction = SystemPrompt.base(),
            thinking = { model ->
                ThinkingSupport.configFor(model, catalog.find(model)?.thinking ?: false, level, s.showThoughts, s.rejectedThinking)
            },
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
            is AgentEvent.ModelStarted -> update(sessionId) { it.copy(activeModel = event.model, notice = null) }
            is AgentEvent.TextDelta -> update(sessionId) { it.copy(streamingText = it.streamingText + event.text, notice = null) }
            is AgentEvent.ThoughtDelta -> update(sessionId) { it.copy(streamingThoughts = it.streamingThoughts + event.text) }
            is AgentEvent.Retrying -> update(sessionId) {
                it.copy(notice = "${event.reason}: retrying ${event.model} in ${(event.delayMs + 999) / 1000} s (attempt ${event.attempt})")
            }
            is AgentEvent.ModelSwitched -> update(sessionId) { it.copy(activeModel = event.to, notice = "Switched from ${event.from} to ${event.to}: ${event.reason}") }
            is AgentEvent.ModelMessage -> update(sessionId) { it.copy(streamingText = "", streamingThoughts = "") }
            is AgentEvent.Finished -> {
                (event.error as? GeminiApiException)?.let { rememberRejectedThinking(it) }
                update(sessionId) {
                    it.copy(
                        running = false,
                        streamingText = "",
                        streamingThoughts = "",
                        finishReason = event.reason,
                        error = event.error?.let(::describe),
                    )
                }
            }
            is AgentEvent.ToolStarted, is AgentEvent.ToolFinished, is AgentEvent.ToolProgress -> Unit
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
