package dev.gcode.gemini.select

import dev.gcode.gemini.model.ThinkingConfig

enum class ThinkingLevel(val wire: String) { MINIMAL("minimal"), LOW("low"), MEDIUM("medium"), HIGH("high"), XHIGH("xhigh") }

/** What a model accepts. `usesBudget` marks Gemini 2.5 models, which take thinkingBudget instead of levels. */
data class ThinkingOptions(
    val levels: List<ThinkingLevel>,
    val default: ThinkingLevel?,
    val usesBudget: Boolean = false,
) {
    val supported: Boolean get() = levels.isNotEmpty() || usesBudget
}

/**
 * Thinking levels per model, from the Gemini thinking docs (checked 2026-11-27).
 * Unknown thinking models get low/medium/high and the model's own default.
 */
object ThinkingSupport {
    private val L = ThinkingLevel.LOW
    private val M = ThinkingLevel.MEDIUM
    private val H = ThinkingLevel.HIGH
    private val X = ThinkingLevel.XHIGH
    private val MIN = ThinkingLevel.MINIMAL

    private val KNOWN: Map<String, ThinkingOptions> = mapOf(
        "gemini-4-carbon" to ThinkingOptions(listOf(L, M, H), M),
        "gemini-4-argon" to ThinkingOptions(listOf(L, M, H), M),
        "gemini-4-neon" to ThinkingOptions(listOf(L, M, H, X), H),
        "gemini-4-hydrogen" to ThinkingOptions(listOf(L, M, H, X), H),
        "gemini-3.8-flash" to ThinkingOptions(listOf(L, M, H), M),
        "gemini-3.6-flash" to ThinkingOptions(listOf(MIN, L, M, H), M),
        "gemini-3.5-flash-lite" to ThinkingOptions(listOf(MIN, L, M, H), MIN),
        "gemini-3.1-pro-preview" to ThinkingOptions(listOf(L, M, H), H),
        "gemini-3-flash-preview" to ThinkingOptions(listOf(MIN, L, M, H), H),
    )

    fun optionsFor(modelId: String, catalogSaysThinking: Boolean): ThinkingOptions {
        KNOWN[modelId]?.let { return it }
        if (modelId.startsWith("gemini-2.5")) return ThinkingOptions(emptyList(), null, usesBudget = true)
        return if (catalogSaysThinking) ThinkingOptions(listOf(L, M, H), null) else ThinkingOptions(emptyList(), null)
    }

    /**
     * Builds the request's thinking config for the chosen level, or null to use the model default.
     * A level the model does not accept, or one rejected earlier, is dropped (never sent with a budget).
     */
    fun configFor(
        modelId: String,
        catalogSaysThinking: Boolean,
        chosen: ThinkingLevel?,
        includeThoughts: Boolean,
        rejected: Set<String> = emptySet(),
    ): ThinkingConfig? {
        val options = optionsFor(modelId, catalogSaysThinking)
        if (!options.supported) return null
        val level = chosen?.takeIf { it in options.levels && "$modelId:${it.wire}" !in rejected }
        if (options.usesBudget) return if (includeThoughts) ThinkingConfig(includeThoughts = true) else null
        if (level == null && !includeThoughts) return null
        return ThinkingConfig(thinkingLevel = level?.wire, includeThoughts = includeThoughts.takeIf { it })
    }
}
