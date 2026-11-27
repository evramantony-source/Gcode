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
    /** Signed-in GitHub account (profile only; the token is in the SecretStore). */
    val githubLogin: String? = null,
    val githubUserId: Long? = null,
    val githubName: String? = null,
    /** "oauth", "pat_fine_grained" or "pat_classic". */
    val githubTokenType: String? = null,
    /** Overrides BuildConfig.GITHUB_CLIENT_ID when set. */
    val githubClientIdOverride: String? = null,
    /** Full name (owner/repo) of the repository open in the workspace. */
    val activeRepo: String? = null,
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
