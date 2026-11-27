package dev.gcode.core.data.settings

import dev.gcode.gemini.GeminiEndpoints

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Global settings (ARCHITECTURE.md section 9). Per-repo settings live in the database. */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val preferredModel: String = GeminiEndpoints.DEFAULT_MODEL,
    /** Wire value of the chosen thinking level, or null for the model's default. */
    val thinkingLevel: String? = null,
    val showThoughts: Boolean = true,
    val autoSwitchModels: Boolean = false,
    val compactionThresholdPercent: Int = DEFAULT_COMPACTION_THRESHOLD,
    val modelCacheHours: Int = DEFAULT_MODEL_CACHE_HOURS,
    /** Thinking levels the API rejected, as "model:level". */
    val rejectedThinking: Set<String> = emptySet(),
    val onboardingComplete: Boolean = false,
) {
    init {
        require(modelCacheHours in 1..168) { "modelCacheHours out of range: $modelCacheHours" }
        require(compactionThresholdPercent in 50..95) {
            "compactionThresholdPercent out of range: $compactionThresholdPercent"
        }
    }

    companion object {
        const val DEFAULT_MODEL_CACHE_HOURS = 24
        const val DEFAULT_COMPACTION_THRESHOLD = 80
    }
}
