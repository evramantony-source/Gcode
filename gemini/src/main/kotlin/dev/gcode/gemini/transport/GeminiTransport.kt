package dev.gcode.gemini.transport

import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.GenerateContentRequest
import dev.gcode.gemini.model.GenerateContentResponse
import dev.gcode.gemini.model.ModelInfo
import kotlinx.coroutines.flow.Flow

/**
 * The provider layer (decision D1). The rest of the app talks to Gemini only through this interface,
 * so moving to another Gemini API surface later replaces just the implementation.
 */
interface GeminiTransport {
    /** All models visible to the key (every page of `models.list`). */
    suspend fun listModels(): List<ModelInfo>

    /** Streams response chunks (SSE). Cancelling the collector closes the HTTP call. */
    fun streamGenerateContent(model: String, request: GenerateContentRequest): Flow<GenerateContentResponse>

    suspend fun generateContent(model: String, request: GenerateContentRequest): GenerateContentResponse

    suspend fun countTokens(model: String, contents: List<Content>): Int
}
