package dev.gcode.agent.loop

import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.FunctionCall
import dev.gcode.gemini.model.UsageMetadata

/** Everything the UI needs to render a turn while it runs. */
sealed interface AgentEvent {
    data class ModelStarted(val model: String, val step: Int) : AgentEvent
    data class TextDelta(val text: String) : AgentEvent
    data class ThoughtDelta(val text: String) : AgentEvent
    data class Retrying(val model: String, val attempt: Int, val delayMs: Long, val reason: String) : AgentEvent
    data class ModelSwitched(val from: String, val to: String, val reason: String) : AgentEvent

    /** A complete model turn, already saved. */
    data class ModelMessage(val content: Content, val usage: UsageMetadata?, val model: String, val finishReason: String?) : AgentEvent

    data class ToolStarted(val call: FunctionCall) : AgentEvent
    data class ToolFinished(val call: FunctionCall, val ok: Boolean, val summary: String, val durationMs: Long, val display: String? = null) : AgentEvent

    /** Free-form progress from a long-running tool (e.g. a sub-agent or a build). */
    data class ToolProgress(val callId: String?, val message: String) : AgentEvent

    data class Finished(val reason: FinishReason, val error: Throwable? = null) : AgentEvent
}

enum class FinishReason { COMPLETED, CANCELLED, STEP_LIMIT, ERROR, BLOCKED }
