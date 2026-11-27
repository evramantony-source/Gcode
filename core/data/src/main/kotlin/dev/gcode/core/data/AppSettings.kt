package dev.gcode.core.data

/**
 * Global app settings model. DataStore persistence arrives in Phase 2.
 */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val modelCacheHours: Int = DEFAULT_MODEL_CACHE_HOURS,
    val compactionThresholdPercent: Int = DEFAULT_COMPACTION_THRESHOLD,
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

enum class ThemeMode { SYSTEM, LIGHT, DARK }
