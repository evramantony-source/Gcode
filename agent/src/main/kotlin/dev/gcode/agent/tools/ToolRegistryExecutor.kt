package dev.gcode.agent.tools

import dev.gcode.agent.PermissionMode
import dev.gcode.agent.ToolEffect
import dev.gcode.agent.loop.AgentEvent
import dev.gcode.agent.loop.ToolExecutor
import dev.gcode.agent.permission.Decision
import dev.gcode.agent.permission.PermissionPolicy
import dev.gcode.gemini.model.FunctionCall
import dev.gcode.gemini.model.FunctionDeclaration
import dev.gcode.gemini.model.Part
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Runs tool calls with permissions (ARCHITECTURE.md sections 6.2 and 7):
 * contiguous READ calls run concurrently (at most [maxParallel]); everything else runs one at a time, in order,
 * with approval prompts serialised. Responses come back in call order with the call ids echoed.
 */
class ToolRegistryExecutor(
    tools: List<Tool>,
    private val mode: suspend () -> PermissionMode,
    private val prompter: UserPrompter,
    private val maxParallel: Int = 4,
    private val maxOutputChars: Int = 30_000,
    /** Called when Plan mode's plan is approved, with the mode to switch to. */
    private val onModeChange: suspend (PermissionMode) -> Unit = {},
) : ToolExecutor {
    private val byName = tools.associateBy { it.name }
    private var cachedMode: PermissionMode = PermissionMode.ASK

    val toolNames: Set<String> get() = byName.keys

    override fun declarations(): List<FunctionDeclaration> = byName.values
        .filter { tool -> isVisible(tool, cachedMode) }
        .map { FunctionDeclaration(it.name, it.description, it.parameters) }

    /** Refreshes the mode used for [declarations] (called before each model request). */
    suspend fun refreshMode() {
        cachedMode = mode()
    }

    private fun isVisible(tool: Tool, mode: PermissionMode): Boolean = when (tool.name) {
        SUBMIT_PLAN -> mode == PermissionMode.PLAN
        else -> tool.name in ALWAYS_VISIBLE || PermissionPolicy.visibleIn(staticEffect(tool), mode)
    }

    /** Effect used for visibility: tools whose effect depends on arguments are classified by their weakest case. */
    private fun staticEffect(tool: Tool): ToolEffect = (tool as? StaticEffect)?.staticEffect ?: ToolEffect.REMOTE

    override suspend fun execute(calls: List<FunctionCall>, events: suspend (AgentEvent) -> Unit): List<Part> {
        val results = arrayOfNulls<Part>(calls.size)
        val effects = calls.map { call -> byName[call.name]?.let { runCatching { it.effect(call.args) }.getOrNull() } }
        var i = 0
        while (i < calls.size) {
            if (effects[i] == ToolEffect.READ) {
                var j = i
                while (j < calls.size && effects[j] == ToolEffect.READ) j++
                val semaphore = Semaphore(maxParallel)
                coroutineScope {
                    (i until j).map { k -> async { semaphore.withPermit { results[k] = run(calls[k], events) } } }.awaitAll()
                }
                i = j
            } else {
                results[i] = run(calls[i], events)
                i++
            }
        }
        return results.map { it!! }
    }

    private suspend fun run(call: FunctionCall, events: suspend (AgentEvent) -> Unit): Part {
        val started = System.currentTimeMillis()
        events(AgentEvent.ToolStarted(call))
        val result = try {
            runOne(call, events)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolArgumentException) {
            ToolResult.error(e.message ?: "Invalid arguments")
        } catch (e: Exception) {
            ToolResult.error("${e::class.simpleName}: ${e.message}")
        }
        val response = truncate(result.response)
        events(AgentEvent.ToolFinished(call, result.ok, result.summary, System.currentTimeMillis() - started, result.display))
        return Part.functionResponse(call.name, response, call.id)
    }

    private suspend fun runOne(call: FunctionCall, events: suspend (AgentEvent) -> Unit): ToolResult {
        val tool = byName[call.name] ?: return ToolResult.error("Unknown tool: ${call.name}")
        val currentMode = mode()
        val effect = tool.effect(call.args)
        val decision = if (tool.name in ALWAYS_VISIBLE || tool.name == SUBMIT_PLAN) Decision.ALLOW else PermissionPolicy.decide(effect, currentMode)
        if (decision == Decision.DENY) {
            return ToolResult.error("${call.name} is not available in ${currentMode.id} mode. Use read-only tools and submit_plan.")
        }
        val prepared = tool.prepare(call.args, ToolContext(call.id) { events(AgentEvent.ToolProgress(call.id, it)) })
        val approval = prepared.approval
        val answer = when {
            approval == null -> ApprovalResult.Approved
            approval is ApprovalRequest.SecretAccess -> prompter.ask(approval) // always confirmed, every mode
            approval is ApprovalRequest.Plan -> prompter.ask(approval)
            decision == Decision.ALLOW -> ApprovalResult.Approved
            decision == Decision.CONFIRM && approval is ApprovalRequest.Action -> prompter.ask(approval.copy(dangerous = true))
            else -> prompter.ask(approval)
        }
        if (answer is ApprovalResult.Rejected) {
            return ToolResult(false, ToolResult.jsonOf(listOf("error" to "The user rejected this action.", "user_comment" to answer.reason)), "Rejected by the user")
        }
        if (answer is ApprovalResult.PlanApproved) {
            PermissionMode.entries.firstOrNull { it.id == answer.mode }?.let { onModeChange(it) }
        }
        return prepared.apply(answer)
    }

    private fun truncate(response: JsonObject): JsonObject {
        val oversized = response.entries.firstOrNull { (_, v) -> v is JsonPrimitive && v.isString && v.content.length > maxOutputChars } ?: return response
        val text = (oversized.value as JsonPrimitive).content
        return JsonObject(
            response + (oversized.key to JsonPrimitive(text.take(maxOutputChars))) +
                ("truncated" to JsonPrimitive("Output cut at $maxOutputChars of ${text.length} characters. Use offset/limit or a narrower query to see more.")),
        )
    }

    companion object {
        const val SUBMIT_PLAN = "submit_plan"

        /** Planning tools that never change the workspace. */
        val ALWAYS_VISIBLE = setOf("todo_write")
    }
}

/** Tools whose effect does not depend on arguments declare it for visibility decisions. */
interface StaticEffect {
    val staticEffect: ToolEffect
}
