package dev.gcode.agent.loop

import dev.gcode.gemini.ResilientGemini
import dev.gcode.gemini.StreamAccumulator
import dev.gcode.gemini.StreamEvent
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.FunctionCall
import dev.gcode.gemini.model.FunctionDeclaration
import dev.gcode.gemini.model.GenerateContentRequest
import dev.gcode.gemini.model.GenerationConfig
import dev.gcode.gemini.model.Part
import dev.gcode.gemini.model.ThinkingConfig
import dev.gcode.gemini.model.Tool
import dev.gcode.gemini.model.UsageMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where the loop reads and writes the conversation. Messages are saved before tools run. */
interface ConversationStore {
    /** The model-visible history (compacted messages replaced by their summary). */
    suspend fun history(): List<Content>

    suspend fun append(content: Content, usage: UsageMetadata?, model: String?, kind: MessageKind = MessageKind.CHAT)
}

enum class MessageKind { CHAT, TOOL_RESULTS, SUMMARY }

/** Runs tool calls for one model turn and returns one functionResponse part per call, in call order. */
interface ToolExecutor {
    fun declarations(): List<FunctionDeclaration>

    suspend fun execute(calls: List<FunctionCall>, events: suspend (AgentEvent) -> Unit): List<Part>
}

/** Plain chat: no tools are declared; an unexpected call gets an error response so history stays valid. */
object NoToolExecutor : ToolExecutor {
    override fun declarations(): List<FunctionDeclaration> = emptyList()
    override suspend fun execute(calls: List<FunctionCall>, events: suspend (AgentEvent) -> Unit): List<Part> =
        calls.map { Part.functionResponse(it.name, buildJsonObject { put("error", "Unknown tool: ${it.name}") }, it.id) }
}

data class AgentConfig(
    /** Models to try, in order (fallback chain or auto-switch candidates). */
    val modelCandidates: List<String>,
    val autoSwitch: Boolean,
    val systemInstruction: String,
    val thinking: (model: String) -> ThinkingConfig?,
    val maxSteps: Int = 50,
    /** Called before every model call; may compact the history. */
    val beforeModelCall: suspend () -> Unit = {},
)

/**
 * model -> tool calls -> results -> model, until the model stops calling tools, the user stops it,
 * or the step limit is reached (ARCHITECTURE.md section 6.1).
 */
class AgentLoop(
    private val gemini: ResilientGemini,
    private val store: ConversationStore,
    private val tools: ToolExecutor,
) {
    /** Runs a turn. With [user] null the loop continues from the saved history (Retry after an error). */
    fun run(user: Content?, config: AgentConfig): Flow<AgentEvent> = channelFlow {
        // channelFlow: parallel tool calls report events from several coroutines.
        val emit: suspend (AgentEvent) -> Unit = { send(it) }
        if (user != null) store.append(user, null, null)
        var step = 0
        while (true) {
            if (step >= config.maxSteps) {
                emit(AgentEvent.Finished(FinishReason.STEP_LIMIT))
                return@channelFlow
            }
            step++
            config.beforeModelCall()
            val outcome = callModel(config, step, emit) ?: return@channelFlow
            val calls = outcome.content.functionCalls
            if (calls.isEmpty()) {
                emit(AgentEvent.Finished(if (outcome.blocked) FinishReason.BLOCKED else FinishReason.COMPLETED))
                return@channelFlow
            }
            val responses = runTools(calls, emit)
            store.append(Content(Content.ROLE_USER, responses), null, null, MessageKind.TOOL_RESULTS)
        }
    }

    private data class ModelOutcome(val content: Content, val blocked: Boolean)

    /** Streams one model turn and saves it. Returns null when the turn ended with an error or Stop. */
    private suspend fun callModel(config: AgentConfig, step: Int, emit: suspend (AgentEvent) -> Unit): ModelOutcome? {
        val history = HistoryNormalizer.normalize(store.history())
        val declarations = tools.declarations()
        val acc = StreamAccumulator()
        var model = config.modelCandidates.first()
        try {
            gemini.stream(config.modelCandidates, config.autoSwitch) { m ->
                GenerateContentRequest(
                    contents = history,
                    systemInstruction = Content(null, listOf(Part.text(config.systemInstruction))),
                    tools = declarations.takeIf { it.isNotEmpty() }?.let { listOf(Tool(functionDeclarations = it)) },
                    generationConfig = GenerationConfig(thinkingConfig = config.thinking(m)),
                )
            }.collect { event ->
                when (event) {
                    is StreamEvent.Started -> {
                        model = event.model
                        emit(AgentEvent.ModelStarted(event.model, step))
                    }
                    is StreamEvent.Waiting -> emit(AgentEvent.Retrying(event.model, event.attempt, event.delayMs, event.reason))
                    is StreamEvent.Switched -> emit(AgentEvent.ModelSwitched(event.from, event.to, event.reason))
                    is StreamEvent.Chunk -> acc.add(event.response).forEach { part ->
                        val text = part.text
                        if (!text.isNullOrEmpty()) emit(if (part.isThought) AgentEvent.ThoughtDelta(text) else AgentEvent.TextDelta(text))
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { savePartial(acc, model) }
            throw e
        } catch (e: Throwable) {
            savePartial(acc, model)
            emit(AgentEvent.Finished(FinishReason.ERROR, e))
            return null
        }
        val content = acc.content()
        if (!acc.hasContent) {
            val blocked = acc.blockReason != null || acc.finishReason in BLOCK_REASONS
            emit(AgentEvent.Finished(if (blocked) FinishReason.BLOCKED else FinishReason.COMPLETED, blockError(acc)))
            return null
        }
        store.append(content, acc.usage, model)
        emit(AgentEvent.ModelMessage(content, acc.usage, model, acc.finishReason))
        return ModelOutcome(content, blocked = acc.finishReason in BLOCK_REASONS)
    }

    /**
     * Runs the calls. If the user stops mid-way, every unanswered call still gets a response,
     * so the next request has a valid history.
     */
    private suspend fun runTools(calls: List<FunctionCall>, emit: suspend (AgentEvent) -> Unit): List<Part> {
        return try {
            tools.execute(calls, emit)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                store.append(Content(Content.ROLE_USER, cancelledResponses(calls)), null, null, MessageKind.TOOL_RESULTS)
            }
            throw e
        }
    }

    private suspend fun savePartial(acc: StreamAccumulator, model: String) {
        // Keep only visible text: a half-streamed function call cannot be answered.
        val text = acc.content().parts.filter { it.text != null && !it.isThought && it.functionCall == null }
            .mapNotNull { it.text }.joinToString("")
        if (text.isNotBlank()) store.append(Content.model(Part.text(text)), acc.usage, model)
    }

    private fun blockError(acc: StreamAccumulator): Throwable? =
        (acc.blockReason ?: acc.finishReason?.takeIf { it in BLOCK_REASONS })?.let { IllegalStateException("Response blocked: $it") }

    companion object {
        private val BLOCK_REASONS = setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "OTHER")

        fun cancelledResponses(calls: List<FunctionCall>): List<Part> = calls.map {
            Part.functionResponse(it.name, buildJsonObject { put("error", "Cancelled by the user before this tool ran.") }, it.id)
        }
    }
}

/** Merges consecutive turns with the same role, so an interrupted turn never leaves two user turns in a row. */
object HistoryNormalizer {
    fun normalize(history: List<Content>): List<Content> {
        val out = mutableListOf<Content>()
        for (c in history) {
            if (c.parts.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.role == c.role) {
                out[out.lastIndex] = Content(last.role, last.parts + c.parts)
            } else {
                out += c
            }
        }
        // The API expects the conversation to start with a user turn.
        while (out.isNotEmpty() && out.first().role != Content.ROLE_USER) out.removeAt(0)
        return out
    }
}
