package dev.gcode.agent

import com.google.common.truth.Truth.assertThat
import dev.gcode.agent.loop.AgentEvent
import dev.gcode.agent.tools.ApprovalRequest
import dev.gcode.agent.tools.ApprovalResult
import dev.gcode.agent.tools.FixedEffectTool
import dev.gcode.agent.tools.Prepared
import dev.gcode.agent.tools.ToolContext
import dev.gcode.agent.tools.ToolRegistryExecutor
import dev.gcode.agent.tools.ToolResult
import dev.gcode.agent.tools.schema
import dev.gcode.gemini.model.FunctionCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ExecutorTest {
    private val running = AtomicInteger(0)
    private var maxConcurrent = 0
    private val log = mutableListOf<String>()

    private inner class FakeTool(override val name: String, effect: ToolEffect, private val ask: Boolean = false, private val wait: Long = 0) : FixedEffectTool(effect) {
        override val description = name
        override val parameters = schema { }
        override suspend fun prepare(args: JsonObject, context: ToolContext) = Prepared(if (ask) ApprovalRequest.Action(name, name, name, false) else null) {
            val now = running.incrementAndGet()
            maxConcurrent = maxOf(maxConcurrent, now)
            delay(wait)
            running.decrementAndGet()
            synchronized(log) { log += name }
            ToolResult.ok("$name done")
        }
    }

    private fun call(name: String, id: String) = FunctionCall(name, JsonObject(emptyMap()), id)

    @Test
    fun `reads run in parallel, writes in order, responses keep call order and ids`() = runTest {
        val prompts = mutableListOf<String>()
        val exec = ToolRegistryExecutor(
            listOf(FakeTool("r1", ToolEffect.READ, wait = 50), FakeTool("r2", ToolEffect.READ, wait = 10), FakeTool("w", ToolEffect.WORKSPACE_WRITE, ask = true)),
            mode = { PermissionMode.ASK },
            prompter = { prompts += it.title; ApprovalResult.Approved },
        )
        val events = mutableListOf<AgentEvent>()
        val parts = exec.execute(listOf(call("r1", "a"), call("r2", "b"), call("w", "c")), events::add)
        assertThat(maxConcurrent).isEqualTo(2)
        assertThat(parts.map { it.functionResponse?.id }).containsExactly("a", "b", "c").inOrder()
        assertThat(log.last()).isEqualTo("w")
        assertThat(prompts).containsExactly("w")
        assertThat(events.filterIsInstance<AgentEvent.ToolFinished>()).hasSize(3)
    }

    @Test
    fun `auto accept edits skips the diff sheet but still asks for git`() = runTest {
        val prompts = mutableListOf<String>()
        val exec = ToolRegistryExecutor(
            listOf(FakeTool("edit", ToolEffect.WORKSPACE_WRITE, ask = true), FakeTool("commit", ToolEffect.GIT_LOCAL, ask = true)),
            mode = { PermissionMode.AUTO_ACCEPT_EDITS }, prompter = { prompts += it.title; ApprovalResult.Approved },
        )
        exec.execute(listOf(call("edit", "1"), call("commit", "2")), {})
        assertThat(prompts).containsExactly("commit")
    }

    @Test
    fun `plan mode denies writes and hides them from declarations`() = runTest {
        val exec = ToolRegistryExecutor(
            listOf(FakeTool("read", ToolEffect.READ), FakeTool("write", ToolEffect.WORKSPACE_WRITE)),
            mode = { PermissionMode.PLAN }, prompter = { ApprovalResult.Approved },
        )
        exec.refreshMode()
        assertThat(exec.declarations().map { it.name }).containsExactly("read")
        val part = exec.execute(listOf(call("write", "1")), {}).single()
        assertThat((part.functionResponse!!.response["error"] as JsonPrimitive).content).contains("not available in plan mode")
    }

    @Test
    fun `a rejection is reported to the model with the user's comment`() = runTest {
        val exec = ToolRegistryExecutor(listOf(FakeTool("w", ToolEffect.REMOTE, ask = true)), mode = { PermissionMode.FULL_AUTO.let { PermissionMode.ASK } }, prompter = { ApprovalResult.Rejected("not now") })
        val response = exec.execute(listOf(call("w", "1")), {}).single().functionResponse!!.response
        assertThat((response["user_comment"] as JsonPrimitive).content).isEqualTo("not now")
        assertThat(log).isEmpty()
    }

    @Test
    fun `dangerous actions are confirmed even in full auto, unknown tools return errors`() = runTest {
        val prompts = mutableListOf<ApprovalRequest>()
        val exec = ToolRegistryExecutor(listOf(FakeTool("danger", ToolEffect.DANGEROUS, ask = true)), mode = { PermissionMode.FULL_AUTO }, prompter = { prompts += it; ApprovalResult.Approved })
        val parts = exec.execute(listOf(call("danger", "1"), call("nope", "2")), {})
        assertThat((prompts.single() as ApprovalRequest.Action).dangerous).isTrue()
        assertThat((parts[1].functionResponse!!.response["error"] as JsonPrimitive).content).contains("Unknown tool")
    }
}
