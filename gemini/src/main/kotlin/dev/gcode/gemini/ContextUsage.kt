package dev.gcode.gemini

/** Context-window usage for the status bar and auto-compaction (ARCHITECTURE.md section 5.6). */
data class ContextUsage(val usedTokens: Int, val limitTokens: Int) {
    val fraction: Double get() = if (limitTokens <= 0) 0.0 else usedTokens.toDouble() / limitTokens
    val percent: Int get() = (fraction * 100).toInt().coerceIn(0, 100)

    fun exceeds(thresholdPercent: Int): Boolean = limitTokens > 0 && fraction * 100 >= thresholdPercent

    companion object {
        /** Rough estimate for text not yet counted by the API: about 4 characters per token. */
        fun estimateTokens(chars: Int): Int = (chars + 3) / 4
    }
}
