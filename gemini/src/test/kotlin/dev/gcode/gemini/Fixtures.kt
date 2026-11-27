package dev.gcode.gemini

import dev.gcode.gemini.model.ModelInfo

/** A models.list snapshot shaped like the real catalog on 2026-11-27. */
object Fixtures {
    private fun m(id: String, thinking: Boolean = true, methods: List<String> = listOf("generateContent", "countTokens")) =
        ModelInfo(name = "models/$id", displayName = id, inputTokenLimit = 1_048_576, outputTokenLimit = 65_536, supportedGenerationMethods = methods, thinking = thinking)

    val fullCatalog = listOf(
        m("gemini-4-hydrogen"), m("gemini-4-neon"), m("gemini-4-carbon"), m("gemini-4-argon"),
        m("gemini-3.8-flash"), m("gemini-3.6-flash"), m("gemini-3-flash-preview"),
        m("gemini-3.5-flash-lite"), m("gemini-3.1-flash-lite"), m("gemini-2.5-flash-lite"),
        m("gemini-3.1-pro-preview"), m("gemini-3.8-flash-tts"), m("gemini-3.8-live"),
        m("gemini-embedding-2", thinking = false, methods = listOf("embedContent")),
        m("gemma-4-31b-it", thinking = false),
        m("gemini-flash-latest"),
    )

    /** A free-tier style key without the Gemini 4 models. */
    val withoutGemini4 = fullCatalog.filterNot { it.id.startsWith("gemini-4") }
}
