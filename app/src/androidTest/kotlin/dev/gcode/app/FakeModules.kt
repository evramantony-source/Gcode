package dev.gcode.app

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dev.gcode.app.di.GeminiModule
import dev.gcode.app.di.GeminiTransportFactory
import dev.gcode.app.di.SecretsModule
import dev.gcode.core.data.secret.SecretStore
import dev.gcode.gemini.model.Candidate
import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.GenerateContentRequest
import dev.gcode.gemini.model.GenerateContentResponse
import dev.gcode.gemini.model.ModelInfo
import dev.gcode.gemini.model.Part
import dev.gcode.gemini.model.UsageMetadata
import dev.gcode.gemini.transport.GeminiTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

/** In-memory secrets: the emulator's Keystore is not needed for UI tests. */
class InMemorySecretStore : SecretStore {
    private val values = MutableStateFlow<Map<String, String>>(emptyMap())
    override suspend fun get(key: String) = values.value[key]
    override suspend fun put(key: String, value: String) { values.value = values.value + (key to value) }
    override suspend fun remove(key: String) { values.value = values.value - key }
    override fun observePresent(key: String): Flow<Boolean> = values.map { key in it }
}

/** Scripted Gemini: two models and a streamed two-chunk reply. No network. */
class FakeGeminiTransport : GeminiTransport {
    override suspend fun listModels() = listOf(
        ModelInfo("models/gemini-4-hydrogen", displayName = "Gemini 4 Hydrogen", inputTokenLimit = 1_048_576, supportedGenerationMethods = listOf("generateContent"), thinking = true),
        ModelInfo("models/gemini-3.8-flash", displayName = "Gemini 3.8 Flash", inputTokenLimit = 1_048_576, supportedGenerationMethods = listOf("generateContent"), thinking = true),
    )

    /** First turn: a todo_write tool call. After the tool result: a streamed text reply. */
    override fun streamGenerateContent(model: String, request: GenerateContentRequest): Flow<GenerateContentResponse> = flow {
        val last = request.contents.last()
        if (last.parts.none { it.functionResponse != null }) {
            val todos = kotlinx.serialization.json.buildJsonObject {
                put("todos", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject { put("content", kotlinx.serialization.json.JsonPrimitive("Say hello")); put("status", kotlinx.serialization.json.JsonPrimitive("in_progress")) })
                })
            }
            emit(GenerateContentResponse(listOf(Candidate(Content.model(Part.functionCall("todo_write", todos, "call-1")), "STOP"))))
            return@flow
        }
        emit(GenerateContentResponse(listOf(Candidate(Content.model(Part.text(REPLY_START))))))
        emit(GenerateContentResponse(listOf(Candidate(Content.model(Part.text(REPLY_END)), "STOP")), UsageMetadata(promptTokenCount = 10_000, candidatesTokenCount = 20)))
    }

    override suspend fun generateContent(model: String, request: GenerateContentRequest) =
        GenerateContentResponse(listOf(Candidate(Content.model(Part.text(REPLY_START + REPLY_END)), "STOP")))

    override suspend fun countTokens(model: String, contents: List<Content>) = 10

    companion object {
        const val REPLY_START = "Hello from "
        const val REPLY_END = "fake Gemini"
    }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SecretsModule::class])
object FakeSecretsModule {
    @Provides
    @Singleton
    fun secretStore(): SecretStore = InMemorySecretStore()
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [GeminiModule::class])
object FakeGeminiModule {
    @Provides
    @Singleton
    fun transport(): GeminiTransport = FakeGeminiTransport()

    @Provides
    @Singleton
    fun factory(transport: GeminiTransport): GeminiTransportFactory = GeminiTransportFactory { transport }
}
