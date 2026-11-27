package dev.gcode.agent

import com.google.common.truth.Truth.assertThat
import dev.gcode.agent.loop.AgentConfig
import dev.gcode.agent.loop.AgentEvent
import dev.gcode.agent.loop.AgentLoop
import dev.gcode.agent.loop.ConversationStore
import dev.gcode.agent.loop.FinishReason
import dev.gcode.agent.loop.HistoryNormalizer
import dev.gcode.agent.loop.MessageKind
import dev.gcode.agent.loop.NoToolExecutor
import dev.gcode.gemini.ResilientGemini
import dev.gcode.gemini.model.Candidate
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.GenerateContentRequest
import dev.gcode.gemini.model.GenerateContentResponse
import dev.gcode.gemini.model.ModelInfo
import dev.gcode.gemini.model.Part
import dev.gcode.gemini.model.UsageMetadata
import dev.gcode.gemini.select.ModelHealth
import dev.gcode.gemini.transport.GeminiApiException
import dev.gcode.gemini.transport.GeminiTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AgentLoopTest {
    class MemoryStore : ConversationStore {
        val saved = mutableListOf<Pair<Content, MessageKind>>()
        override suspend fun history(): List<Content> = saved.map { it.first }
        override suspend fun append(content: Content, usage: UsageMetadata?, model: String?, kind: MessageKind) {
            saved += content to kind
        }
    }

    private class FakeTransport(private val responses: MutableList<() -> Flow<GenerateContentResponse>>) : GeminiTransport {
        val requests = mutableListOf<GenerateContentRequest>()
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override fun streamGenerateContent(model: String, request: GenerateContentRequest): Flow<GenerateContentResponse> {
            requests += request
            return responses.removeAt(0)()
        }
        override suspend fun generateContent(model: String, request: GenerateContentRequest) = error("unused")
        override suspend fun countTokens(model: String, contents: List<Content>) = 0
    }

    private fun textChunks(vararg texts: String): () -> Flow<GenerateContentResponse> = {
        flow {
            texts.forEachIndexed { i, t ->
                emit(GenerateContentResponse(listOf(Candidate(Content.model(Part.text(t)), if (i == texts.lastIndex) "STOP" else null)), UsageMetadata(promptTokenCount = 7)))
            }
        }
    }

    private val config = AgentConfig(listOf("m"), autoSwitch = false, systemInstruction = "sys", thinking = { null })

    @Test
    fun `streams text, saves user and model turns, and finishes`() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport(mutableListOf(textChunks("Hel", "lo")))
        val loop = AgentLoop(ResilientGemini(transport, ModelHealth(), clock = { 0 }, sleep = {}), store, NoToolExecutor)
        val events = loop.run(Content.user("hi"), config).toList()
        assertThat(events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }).isEqualTo("Hello")
        assertThat(events.last()).isEqualTo(AgentEvent.Finished(FinishReason.COMPLETED))
        assertThat(store.saved.map { it.first.role }).containsExactly("user", "model").inOrder()
        assertThat(store.saved[1].first.text).isEqualTo("Hello")
        assertThat(transport.requests.single().systemInstruction?.text).isEqualTo("sys")
        assertThat(transport.requests.single().tools).isNull()
    }

    @Test
    fun `an error keeps partial text and reports the failure`() = runTest {
        val store = MemoryStore()
        val transport = FakeTransport(
            mutableListOf({
                flow {
                    emit(GenerateContentResponse(listOf(Candidate(Content.model(Part.text("partial"))))))
                    throw GeminiApiException(500, "INTERNAL", "boom")
                }
            }),
        )
        val loop = AgentLoop(ResilientGemini(transport, ModelHealth(), clock = { 0 }, sleep = {}), store, NoToolExecutor)
        val events = loop.run(Content.user("hi"), config).toList()
        val finished = events.last() as AgentEvent.Finished
        assertThat(finished.reason).isEqualTo(FinishReason.ERROR)
        assertThat(store.saved.last().first.text).isEqualTo("partial")
    }

    @Test
    fun `history normalizer merges same role turns and drops a leading model turn`() {
        val merged = HistoryNormalizer.normalize(
            listOf(Content.model(Part.text("x")), Content.user("a"), Content.user("b"), Content.model(Part.text("c"))),
        )
        assertThat(merged.map { it.role }).containsExactly("user", "model").inOrder()
        assertThat(merged[0].parts.map { it.text }).containsExactly("a", "b").inOrder()
    }
}
