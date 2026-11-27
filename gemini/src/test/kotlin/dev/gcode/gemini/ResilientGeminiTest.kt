package dev.gcode.gemini

import com.google.common.truth.Truth.assertThat
import dev.gcode.gemini.model.Candidate
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.GenerateContentRequest
import dev.gcode.gemini.model.GenerateContentResponse
import dev.gcode.gemini.model.ModelInfo
import dev.gcode.gemini.select.ModelHealth
import dev.gcode.gemini.transport.GeminiApiException
import dev.gcode.gemini.transport.GeminiTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class ResilientGeminiTest {
    /** Each call to a model pops the next scripted outcome for that model. */
    private class ScriptedTransport(private val script: Map<String, MutableList<Any>>) : GeminiTransport {
        val calls = mutableListOf<String>()
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override fun streamGenerateContent(model: String, request: GenerateContentRequest): Flow<GenerateContentResponse> = flow {
            calls += model
            when (val next = script.getValue(model).removeAt(0)) {
                is Exception -> throw next
                is String -> emit(GenerateContentResponse(listOf(Candidate(Content.model(dev.gcode.gemini.model.Part.text(next)), "STOP"))))
            }
        }
        override suspend fun generateContent(model: String, request: GenerateContentRequest) = error("unused")
        override suspend fun countTokens(model: String, contents: List<Content>) = 0
    }

    private val req: (String) -> GenerateContentRequest = { GenerateContentRequest(listOf(Content.user("hi"))) }
    private fun rateLimit(delay: Long? = null) = GeminiApiException(429, "RESOURCE_EXHAUSTED", "Quota exceeded", delay)
    private val sleeps = mutableListOf<Long>()
    private fun client(t: GeminiTransport, health: ModelHealth = ModelHealth()) =
        ResilientGemini(t, health, BackoffPolicy(maxAttempts = 3, random = Random(1)), clock = { 0L }, sleep = { sleeps += it })

    @Test
    fun `retries a rate limited call with backoff and honours retryDelay`() = runTest {
        val t = ScriptedTransport(mapOf("a" to mutableListOf<Any>(rateLimit(7_000), "ok")))
        val events = client(t).stream(listOf("a"), autoSwitch = false, req).toList()
        assertThat(sleeps).containsExactly(7_000L)
        assertThat(events.filterIsInstance<StreamEvent.Waiting>()).hasSize(1)
        assertThat(events.filterIsInstance<StreamEvent.Chunk>().single().model).isEqualTo("a")
    }

    @Test
    fun `without auto switch the error surfaces after max attempts`() = runTest {
        val t = ScriptedTransport(mapOf("a" to mutableListOf<Any>(rateLimit(), rateLimit(), rateLimit())))
        val error = runCatching { client(t).stream(listOf("a", "b"), autoSwitch = false, req).toList() }.exceptionOrNull()
        assertThat(error).isInstanceOf(GeminiApiException::class.java)
        assertThat(t.calls).containsExactly("a", "a", "a")
        assertThat(sleeps).hasSize(2)
    }

    @Test
    fun `auto switch moves to the next model and puts the first on cool down`() = runTest {
        val health = ModelHealth()
        val t = ScriptedTransport(mapOf("a" to mutableListOf<Any>(rateLimit(), rateLimit(), rateLimit()), "b" to mutableListOf<Any>("ok")))
        val events = client(t, health).stream(listOf("a", "b"), autoSwitch = true, req).toList()
        assertThat(events.filterIsInstance<StreamEvent.Switched>().single().to).isEqualTo("b")
        assertThat(health.isUsable("a", 0)).isFalse()
        assertThat(events.last()).isInstanceOf(StreamEvent.Chunk::class.java)
    }

    @Test
    fun `unavailable models fall back even without auto switch`() = runTest {
        val health = ModelHealth()
        val zeroQuota = GeminiApiException(429, "RESOURCE_EXHAUSTED", "Quota exceeded for metric x, limit: 0, model gemini-4-hydrogen")
        val t = ScriptedTransport(mapOf("hydrogen" to mutableListOf<Any>(zeroQuota), "flash" to mutableListOf<Any>("ok")))
        val events = client(t, health).stream(listOf("hydrogen", "flash"), autoSwitch = false, req).toList()
        assertThat(events.filterIsInstance<StreamEvent.Switched>().single().from).isEqualTo("hydrogen")
        assertThat(sleeps).isEmpty()
        assertThat(health.isUsable("hydrogen", 0)).isFalse()
    }

    @Test
    fun `other client errors are not retried`() = runTest {
        val t = ScriptedTransport(mapOf("a" to mutableListOf<Any>(GeminiApiException(400, "INVALID_ARGUMENT", "bad"))))
        assertThrows(GeminiApiException::class.java) { kotlinx.coroutines.runBlocking { client(t).stream(listOf("a"), true, req).toList() } }
        assertThat(t.calls).hasSize(1)
    }

    @Test
    fun `backoff grows and is capped`() {
        val policy = BackoffPolicy(baseMs = 1_000, maxMs = 4_000, random = Random(2))
        assertThat(policy.delayFor(0, null)).isIn(500L..1_000L)
        assertThat(policy.delayFor(5, null)).isIn(2_000L..4_000L)
        assertThat(policy.delayFor(0, 3_000)).isEqualTo(3_000)
    }
}
