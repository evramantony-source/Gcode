package dev.gcode.gemini.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One turn of a conversation (generateContent `Content`). Roles are "user" and "model". */
@Serializable
data class Content(
    val role: String? = null,
    val parts: List<Part> = emptyList(),
) {
    val functionCalls: List<FunctionCall> get() = parts.mapNotNull { it.functionCall }

    /** Visible (non-thought) text of the turn. */
    val text: String get() = parts.filter { !it.isThought }.mapNotNull { it.text }.joinToString("")

    val thoughtText: String get() = parts.filter { it.isThought }.mapNotNull { it.text }.joinToString("")

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_MODEL = "model"

        fun user(vararg parts: Part) = Content(ROLE_USER, parts.toList())
        fun user(text: String) = Content(ROLE_USER, listOf(Part.text(text)))
        fun model(vararg parts: Part) = Content(ROLE_MODEL, parts.toList())
    }
}

/**
 * A content part, stored as the raw JSON object the API returned. Keeping the raw object means
 * fields such as `thoughtSignature` (which must be sent back exactly where they were received)
 * and any future fields survive the round trip unchanged.
 */
@Serializable(with = PartSerializer::class)
class Part(val json: JsonObject) {
    val text: String? get() = (json["text"] as? JsonPrimitive)?.contentOrNull
    val isThought: Boolean get() = (json["thought"] as? JsonPrimitive)?.booleanOrNull == true
    val thoughtSignature: String? get() = (json["thoughtSignature"] as? JsonPrimitive)?.contentOrNull
    val functionCall: FunctionCall? get() = json["functionCall"]?.let { GeminiJson.decodeFromJsonElement(FunctionCall.serializer(), it) }
    val functionResponse: FunctionResponse? get() =
        json["functionResponse"]?.let { GeminiJson.decodeFromJsonElement(FunctionResponse.serializer(), it) }
    val inlineData: InlineData? get() = json["inlineData"]?.let { GeminiJson.decodeFromJsonElement(InlineData.serializer(), it) }

    /** True for a part that carries only text (optionally a thought flag) and no signature. */
    val isPlainText: Boolean get() = text != null && json.keys.all { it == "text" || it == "thought" }

    override fun equals(other: Any?): Boolean = other is Part && other.json == json
    override fun hashCode(): Int = json.hashCode()
    override fun toString(): String = "Part($json)"

    companion object {
        fun text(text: String) = Part(buildJsonObject { put("text", text) })

        fun functionCall(name: String, args: JsonObject, id: String? = null) = Part(
            buildJsonObject {
                put("functionCall", GeminiJson.encodeToJsonElement(FunctionCall.serializer(), FunctionCall(name, args, id)))
            },
        )

        fun functionResponse(name: String, response: JsonObject, id: String? = null) = Part(
            buildJsonObject {
                put(
                    "functionResponse",
                    GeminiJson.encodeToJsonElement(FunctionResponse.serializer(), FunctionResponse(name, response, id)),
                )
            },
        )

        fun inlineData(mimeType: String, base64Data: String) = Part(
            buildJsonObject {
                put("inlineData", GeminiJson.encodeToJsonElement(InlineData.serializer(), InlineData(mimeType, base64Data)))
            },
        )
    }
}

internal object PartSerializer : KSerializer<Part> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Part) = JsonObject.serializer().serialize(encoder, value.json)
    override fun deserialize(decoder: Decoder): Part = Part(JsonObject.serializer().deserialize(decoder))
}

@Serializable
data class FunctionCall(
    val name: String,
    val args: JsonObject = JsonObject(emptyMap()),
    val id: String? = null,
)

@Serializable
data class FunctionResponse(
    val name: String,
    val response: JsonObject,
    val id: String? = null,
)

@Serializable
data class InlineData(
    val mimeType: String,
    val data: String,
)

/** Reads a string field from a JSON element, or null. */
fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

fun JsonObject.obj(key: String): JsonObject? = this[key]?.let { runCatching { it.jsonObject }.getOrNull() }

fun JsonObject.str(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

@Serializable
data class GenerateContentRequest(
    val contents: List<Content>,
    val systemInstruction: Content? = null,
    val tools: List<Tool>? = null,
    val toolConfig: ToolConfig? = null,
    val generationConfig: GenerationConfig? = null,
)

@Serializable
data class Tool(
    val functionDeclarations: List<FunctionDeclaration>? = null,
    @SerialName("google_search") val googleSearch: JsonObject? = null,
)

/** A function the model may call. `parameters` is an OpenAPI-style schema object. */
@Serializable
data class FunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: JsonObject? = null,
)

@Serializable
data class ToolConfig(val functionCallingConfig: FunctionCallingConfig)

@Serializable
data class FunctionCallingConfig(
    val mode: String,
    val allowedFunctionNames: List<String>? = null,
)

@Serializable
data class GenerationConfig(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val thinkingConfig: ThinkingConfig? = null,
)

/** `thinkingLevel` (Gemini 3+) and `thinkingBudget` (Gemini 2.5) must never be sent together. */
@Serializable
data class ThinkingConfig(
    val thinkingLevel: String? = null,
    val thinkingBudget: Int? = null,
    val includeThoughts: Boolean? = null,
)

@Serializable
data class GenerateContentResponse(
    val candidates: List<Candidate> = emptyList(),
    val usageMetadata: UsageMetadata? = null,
    val promptFeedback: PromptFeedback? = null,
    val modelVersion: String? = null,
)

@Serializable
data class Candidate(
    val content: Content? = null,
    val finishReason: String? = null,
    val groundingMetadata: JsonObject? = null,
    val index: Int? = null,
)

@Serializable
data class UsageMetadata(
    val promptTokenCount: Int = 0,
    val candidatesTokenCount: Int = 0,
    val thoughtsTokenCount: Int = 0,
    val toolUsePromptTokenCount: Int = 0,
    val cachedContentTokenCount: Int = 0,
    val totalTokenCount: Int = 0,
)

@Serializable
data class PromptFeedback(val blockReason: String? = null)

/** One entry of `models.list`. */
@Serializable
data class ModelInfo(
    val name: String,
    val baseModelId: String? = null,
    val version: String? = null,
    val displayName: String? = null,
    val description: String? = null,
    val inputTokenLimit: Int = 0,
    val outputTokenLimit: Int = 0,
    val supportedGenerationMethods: List<String> = emptyList(),
    val thinking: Boolean = false,
    val temperature: Double? = null,
    val maxTemperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
) {
    /** Model id without the `models/` prefix, e.g. `gemini-4-hydrogen`. */
    val id: String get() = name.removePrefix("models/")
    val supportsGenerateContent: Boolean get() = "generateContent" in supportedGenerationMethods
}

@Serializable
data class ListModelsResponse(
    val models: List<ModelInfo> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class CountTokensRequest(val contents: List<Content>)

@Serializable
data class CountTokensResponse(val totalTokens: Int = 0)
