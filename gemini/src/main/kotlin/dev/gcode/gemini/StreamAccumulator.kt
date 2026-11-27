package dev.gcode.gemini

import dev.gcode.gemini.model.Content
import dev.gcode.gemini.model.GenerateContentResponse
import dev.gcode.gemini.model.Part
import dev.gcode.gemini.model.UsageMetadata
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Folds streamed chunks into one model [Content] without breaking thought signatures:
 * - consecutive plain-text parts (same thought flag, no signature) are concatenated;
 * - any part with a `thoughtSignature` (even an empty text part) stays a separate part, exactly as received;
 * - function-call parts are kept whole.
 */
class StreamAccumulator {
    private val parts = mutableListOf<Part>()

    var usage: UsageMetadata? = null
        private set
    var finishReason: String? = null
        private set
    var blockReason: String? = null
        private set
    var groundingMetadata: JsonObject? = null
        private set
    var modelVersion: String? = null
        private set

    /** Adds a chunk and returns its parts (for live UI updates). */
    fun add(chunk: GenerateContentResponse): List<Part> {
        chunk.usageMetadata?.let { usage = it }
        chunk.modelVersion?.let { modelVersion = it }
        chunk.promptFeedback?.blockReason?.let { blockReason = it }
        val candidate = chunk.candidates.firstOrNull() ?: return emptyList()
        candidate.finishReason?.let { finishReason = it }
        candidate.groundingMetadata?.let { groundingMetadata = it }
        val incoming = candidate.content?.parts.orEmpty()
        incoming.forEach(::append)
        return incoming
    }

    private fun append(part: Part) {
        val last = parts.lastOrNull()
        if (last != null && part.isPlainText && last.isPlainText && last.isThought == part.isThought) {
            parts[parts.lastIndex] = merge(last, part)
        } else {
            parts += part
        }
    }

    private fun merge(a: Part, b: Part): Part {
        val text = (a.text.orEmpty()) + (b.text.orEmpty())
        val map = a.json.toMutableMap()
        map["text"] = JsonPrimitive(text)
        return Part(JsonObject(map))
    }

    val hasContent: Boolean get() = parts.isNotEmpty()

    fun content(): Content = Content(Content.ROLE_MODEL, parts.toList())
}
