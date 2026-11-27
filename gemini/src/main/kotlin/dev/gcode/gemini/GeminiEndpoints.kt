package dev.gcode.gemini

/**
 * Gemini API (generateContent family) endpoints, verified against the official docs on 2026-11-27.
 * The full client arrives in Phase 2; this keeps URL construction in one tested place.
 */
object GeminiEndpoints {
    const val BASE_URL = "https://generativelanguage.googleapis.com"
    const val API_VERSION = "v1beta"
    const val API_KEY_HEADER = "x-goog-api-key"

    /** Preferred default model; the fallback chain is computed from models.list at runtime. */
    const val DEFAULT_MODEL = "gemini-4-hydrogen"

    fun listModels(): String = "$BASE_URL/$API_VERSION/models"

    fun generateContent(model: String): String = "${modelUrl(model)}:generateContent"

    fun streamGenerateContent(model: String): String = "${modelUrl(model)}:streamGenerateContent?alt=sse"

    fun countTokens(model: String): String = "${modelUrl(model)}:countTokens"

    /** Accepts both `gemini-x` and `models/gemini-x`. */
    fun normalizeModelId(model: String): String = model.trim().removePrefix("models/").also {
        require(it.isNotEmpty() && it.none { c -> c.isWhitespace() || c == '/' || c == ':' }) {
            "Invalid model id: '$model'"
        }
    }

    private fun modelUrl(model: String) = "$BASE_URL/$API_VERSION/models/${normalizeModelId(model)}"
}
